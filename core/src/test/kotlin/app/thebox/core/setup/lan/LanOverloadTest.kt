package app.thebox.core.setup.lan

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanOverloadTest {
    @Test
    fun stopClosesAConnectionAcceptedJustBeforeTheWorkerPoolShutsDown() {
        val accepted = CountDownLatch(1)
        val returnConnection = CountDownLatch(1)
        val connectionClosed = CountDownLatch(1)
        val lateConnection = object : LanConnection {
            override val input: InputStream get() = error("a stopped server must not read the late socket")
            override val output: OutputStream get() = error("a stopped server must not write the late socket")
            override val remoteAddress = "192.168.1.30"
            override fun setReadTimeout(ms: Int) = Unit
            override fun close() { connectionClosed.countDown() }
        }
        val transport = LanTransport { _, boundPort ->
            object : LanListener {
                override val port = boundPort
                override fun accept(): LanConnection {
                    accepted.countDown()
                    // Simulate a successful accept racing with stop before its result reaches worker submission.
                    returnConnection.await()
                    return lateConnection
                }
                override fun close() = Unit
            }
        }
        val server = LanHttpServerImpl(transport)
        server.start(LanBind("192.168.1.20", 47100, 1)) { error("the pool was already shut down") }
        try {
            assertTrue(accepted.await(3, TimeUnit.SECONDS))
            server.stop()
            returnConnection.countDown()
            assertTrue(connectionClosed.await(3, TimeUnit.SECONDS), "executor rejection must close the unserved socket")
        } finally {
            returnConnection.countDown()
            server.stop()
        }
    }

    @Test
    fun excessConnectionsAreClosedWithoutTouchingTheirStreams() {
        val queue = LinkedBlockingQueue<LanConnection>()
        val handlerEntered = CountDownLatch(1)
        val releaseHandler = CountDownLatch(1)
        val rejectedClosed = CountDownLatch(128)
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        val handled = AtomicInteger()
        val request = "GET / HTTP/1.1\r\nHost: 192.168.1.20:47100\r\n\r\n".encodeToByteArray()
        val holder = object : LanConnection {
            override val input = ByteArrayInputStream(request)
            override val output = ByteArrayOutputStream()
            override val remoteAddress = "192.168.1.30"
            override fun setReadTimeout(ms: Int) = Unit
            override fun close() = Unit
        }
        val transport = LanTransport { _, boundPort ->
            object : LanListener {
                private val closed = AtomicBoolean()
                override val port = boundPort
                override fun accept(): LanConnection? = queue.take().takeUnless { closed.get() }
                override fun close() {
                    closed.set(true)
                    queue.offer(holder) // Wake a blocked accept without opening a socket.
                }
            }
        }
        val server = LanHttpServerImpl(transport, LanHttpServerImpl.Config(maxSockets = 1, socketTimeoutMs = 10_000))
        server.start(LanBind("192.168.1.20", 47100, 1)) {
            handled.incrementAndGet()
            handlerEntered.countDown()
            releaseHandler.await()
            LanHttpServerImpl.plain(200, "OK")
        }
        try {
            queue.put(holder)
            assertTrue(handlerEntered.await(3, TimeUnit.SECONDS), "the sole worker is occupied")
            repeat(128) {
                queue.put(object : LanConnection {
                    private val closed = AtomicBoolean()
                    override val input: InputStream get() { reads.incrementAndGet(); return ByteArrayInputStream(request) }
                    override val output: OutputStream get() { writes.incrementAndGet(); return ByteArrayOutputStream() }
                    override val remoteAddress = "192.168.1.31"
                    override fun setReadTimeout(ms: Int) = Unit
                    override fun close() { if (closed.compareAndSet(false, true)) rejectedClosed.countDown() }
                })
            }
            assertTrue(rejectedClosed.await(3, TimeUnit.SECONDS), "rejection must keep draining the accept queue")
            assertEquals(0, reads.get(), "excess connections never reach a worker")
            assertEquals(0, writes.get(), "rejection never waits for a peer to read a 503")
            assertEquals(1, handled.get())
        } finally {
            releaseHandler.countDown()
            server.stop()
        }
    }
}
