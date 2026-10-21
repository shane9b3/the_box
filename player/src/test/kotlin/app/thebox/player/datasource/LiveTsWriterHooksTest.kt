package app.thebox.player.datasource

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.timeshift.ReconnectCause
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** (M6 time-shift, §4.2, A-N1, A-C4) The two writer-only hooks of `LiveTsDataSource`; null keeps M2 (LiveTsDataSourceTest). */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveTsWriterHooksTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    private val fixture = res("ts/h264_aac_2s.ts")

    private val executor = java.util.concurrent.Executors.newCachedThreadPool()
    private val toRelease = java.util.concurrent.CopyOnWriteArrayList<LiveBody>()

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() {
        toRelease.forEach { it.release() }
        executor.shutdownNow()
        server.close()
    }

    private val liveUrl get() = server.url("/live/user/pass/1.ts").toString()

    private fun source(events: EventRecorder, onPcrJump: ((Long) -> Unit)? = null, reconnectAllowed: ((app.thebox.core.playback.timeshift.ReconnectCause, Boolean) -> Boolean)? = null) =
        LiveTsDataSource(
            sessionCtx(liveUrl, events = events), client, SessionTelemetry(REAL_CLOCK), null, REAL_CLOCK, fastTiming(), StandaloneScope(),
            onPcrJump = onPcrJump, reconnectAllowed = reconnectAllowed,
        )

    private fun readAll(ds: LiveTsDataSource, max: Int): Pair<ByteArray, LiveStreamException?> {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (out.size() < max) {
            val n = try {
                ds.read(buf, 0, buf.size)
            } catch (e: LiveStreamException) {
                return out.toByteArray() to e
            }
            assertTrue(n > 0)
            out.write(buf, 0, n)
        }
        return out.toByteArray() to null
    }

    @Test
    fun withOnPcrJumpTheSplicedBytesAreKeptAndTheJumpIsReported() {
        val bin = res("ts/pcr_jump.bin")
        // The same split the M2 PCR test uses: the first connection's bytes, then the post-jump part.
        server.enqueue(liveResponse(fixture))
        server.enqueue(liveResponse(bin.copyOfRange(fixture.size, bin.size)))
        val events = EventRecorder()
        val jumps = mutableListOf<Long>()
        val ds = source(events, onPcrJump = { jumps += it })
        ds.open(sessionSpec())
        val (bytes, e) = readAll(ds, bin.size)
        ds.close()
        assertEquals("no PCR_DISCONTINUITY for the recorder", null, e)
        assertEquals(bin.size, bytes.size)
        assertEquals(1, jumps.size)
        assertTrue("delta ${jumps.single()}", jumps.single() in 7_000..9_000)
        assertTrue(events.events.any { it.first == PlaybackEventType.PCR_JUMP && it.second.startsWith("jump_kept") })
    }

    @Test
    fun aRefusedReconnectEndsTheReadWithReconnectExhaustedAndNoFurtherRequest() {
        server.enqueue(liveResponse(fixture.copyOf(3 * PKT)))
        server.enqueue(liveResponse(fixture))
        var asked = 0
        val ds = source(EventRecorder(), reconnectAllowed = { _, _ -> asked++; false })
        ds.open(sessionSpec())
        val (bytes, e) = readAll(ds, fixture.size)
        ds.close()
        assertEquals(3 * PKT, bytes.size)
        if (e == null) fail("RECONNECT_EXHAUSTED expected")
        assertEquals(LiveFailureKind.RECONNECT_EXHAUSTED, e!!.kind)
        assertEquals(1, asked)
        assertEquals("no reconnect request was made", 1, server.requestCount)
    }

    // ------------------------------------------------------------------ fix pass B1: the reconnect cause

    private fun recordingSource(
        causes: MutableList<Pair<ReconnectCause, Boolean>>,
        clock: FakeClock? = null,
        watchdog: ByteWatchdog? = null,
        callFactory: okhttp3.Call.Factory = client,
    ) = LiveTsDataSource(
        sessionCtx(liveUrl), callFactory, SessionTelemetry(clock ?: REAL_CLOCK), watchdog, clock ?: REAL_CLOCK, fastTiming(), StandaloneScope(),
        reconnectAllowed = { cause, first -> causes += cause to first; true },
    )

    @Test
    fun aServerCloseIsServerClosedOncePerLossAndRefusalsAreRefusedOnEveryAttempt() {
        server.enqueue(liveResponse(fixture.copyOf(3 * PKT)))
        server.enqueue(MockResponse.Builder().code(509).build())
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(liveResponse(fixture))
        val causes = java.util.concurrent.CopyOnWriteArrayList<Pair<ReconnectCause, Boolean>>()
        val ds = recordingSource(causes)
        ds.open(sessionSpec())
        readExactly(ds, 3 * PKT + PKT)
        ds.close()
        assertEquals(
            "the close opens the loss; 509 is a refusal; 503 is overload, not a slot (B1)",
            listOf(ReconnectCause.SERVER_CLOSED to true, ReconnectCause.REFUSED to false, ReconnectCause.NETWORK to false),
            causes.toList(),
        )
    }

    /** Fix pass S8: terminate() ends the reconnect wait at once (the zap path no longer waits up to a second for it). */
    @Test
    fun terminateEndsTheReconnectSleepAtOnce() {
        server.enqueue(liveResponse(fixture.copyOf(3 * PKT)))
        server.enqueue(MockResponse.Builder().code(509).build()) // the limit-status delay: 1 s
        server.enqueue(liveResponse(fixture))
        val ds = LiveTsDataSource(sessionCtx(liveUrl), client, SessionTelemetry(REAL_CLOCK), null, REAL_CLOCK, LiveTsDataSource.Timing(), StandaloneScope())
        ds.open(sessionSpec())
        val reading = executor.submit<Throwable?> {
            try { readAll(ds, fixture.size * 4); null } catch (t: Throwable) { t }
        }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (server.requestCount < 2 && System.nanoTime() < deadline) Thread.sleep(5)
        Thread.sleep(150) // now inside the 1 s limit-status wait
        val t0 = System.nanoTime()
        ds.terminate()
        reading.get(5, TimeUnit.SECONDS)
        val ms = (System.nanoTime() - t0) / 1_000_000
        ds.close()
        assertTrue("the wait ended $ms ms after terminate", ms < 500)
        assertEquals("no request after terminate", 2, server.requestCount)
    }

    /** Our own watchdog cancel, surfacing as the usual IOException: WATCHDOG, never counted. */
    @Test
    fun aWatchdogCancelIsWatchdogNotAServerClose() {
        val clock = FakeClock()
        val watchdog = ByteWatchdog(clock).apply { autoStart = false }
        val head = fixture.copyOf(3 * PKT)
        server.enqueue(liveResponse(LiveBody(head, stall = true).also { toRelease += it }))
        server.enqueue(liveResponse(fixture))
        val causes = java.util.concurrent.CopyOnWriteArrayList<Pair<ReconnectCause, Boolean>>()
        val ds = recordingSource(causes, clock, watchdog)
        ds.open(sessionSpec())
        readExactly(ds, head.size)
        val next = executor.submit<ByteArray> { readExactly(ds, 5 * PKT) }
        Thread.sleep(300)
        clock.advance(LiveTsDataSource.NO_BYTES_TIMEOUT_MS + 1)
        watchdog.tick()
        next.get(5, TimeUnit.SECONDS)
        ds.close()
        assertEquals(listOf(ReconnectCause.WATCHDOG to true), causes.toList())
    }

    /** B1 item 3: a cancelled body that ends with −1 instead of throwing is still our watchdog, not a server close. */
    @Test
    fun aWatchdogCancelThatEndsTheBodyWithEofIsWatchdog() {
        val clock = FakeClock()
        val watchdog = ByteWatchdog(clock).apply { autoStart = false }
        val head = fixture.copyOf(3 * PKT)
        server.enqueue(liveResponse(LiveBody(head, stall = true).also { toRelease += it }))
        server.enqueue(liveResponse(fixture))
        val causes = java.util.concurrent.CopyOnWriteArrayList<Pair<ReconnectCause, Boolean>>()
        val ds = recordingSource(causes, clock, watchdog, EofOnCancelCalls(client))
        ds.open(sessionSpec())
        readExactly(ds, head.size)
        val next = executor.submit<ByteArray> { readExactly(ds, 5 * PKT) }
        Thread.sleep(300)
        clock.advance(LiveTsDataSource.NO_BYTES_TIMEOUT_MS + 1)
        watchdog.tick()
        next.get(5, TimeUnit.SECONDS)
        ds.close()
        assertEquals(listOf(ReconnectCause.WATCHDOG to true), causes.toList())
    }

    /** B1 item 3: a first-byte timeout during the sniff whose body ends with −1 is a silence (reconnect), not TINY_BODY. */
    @Test
    fun aSniffTimeWatchdogCancelIsNotATinyBody() {
        val clock = FakeClock()
        val watchdog = ByteWatchdog(clock).apply { autoStart = false }
        server.enqueue(liveResponse(LiveBody(ByteArray(0), stall = true).also { toRelease += it }))
        server.enqueue(liveResponse(fixture))
        val causes = java.util.concurrent.CopyOnWriteArrayList<Pair<ReconnectCause, Boolean>>()
        val ds = recordingSource(causes, clock, watchdog, EofOnCancelCalls(client))
        val opened = executor.submit<Long> { ds.open(sessionSpec()) }
        Thread.sleep(300)
        clock.advance(LiveTsDataSource.FIRST_BYTE_TIMEOUT_MS + 1)
        watchdog.tick()
        opened.get(5, TimeUnit.SECONDS) // a TINY_BODY would throw here
        readExactly(ds, PKT)
        ds.close()
        assertEquals(listOf(ReconnectCause.WATCHDOG to true), causes.toList())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun anAllowedReconnectProceedsAsToday() {
        server.enqueue(liveResponse(fixture.copyOf(60 * PKT)))
        server.enqueue(liveResponse(fixture.copyOfRange(60 * PKT, fixture.size)))
        var asked = 0
        val ds = source(EventRecorder(), reconnectAllowed = { _, _ -> asked++; true })
        ds.open(sessionSpec())
        val got = readExactly(ds, fixture.size)
        ds.close()
        assertEquals(fixture.size, got.size)
        assertEquals(1, asked)
    }

    /**
     * Lead reconciliation R7 (WP-A #7): through the real `TransportFactory.writer` wiring, every `RECONNECT_OK` calls
     * `WriterHooks.onSplice` (→ `TsIndexer.onSplice`) on the thread that reads the upstream (the pump), before the
     * spliced bytes are returned.
     */
    @Test
    fun everyReconnectCallsOnSpliceOnTheReadingThreadBeforeTheSplicedBytes() {
        server.enqueue(liveResponse(fixture.copyOf(40 * PKT)))
        server.enqueue(liveResponse(fixture.copyOfRange(40 * PKT, 80 * PKT)))
        server.enqueue(liveResponse(fixture.copyOfRange(80 * PKT, fixture.size)))
        val transport = TransportFactoryImpl(app.thebox.player.datasource.PlaybackHttpClients(client), ByteWatchdog(REAL_CLOCK), REAL_CLOCK)
        var delivered = 0L
        val splices = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Long>>()
        val hooks = app.thebox.player.internal.WriterHooks(
            onPcrJump = {},
            reconnectAllowed = { _, _ -> true },
            onSplice = { splices += Thread.currentThread().name to delivered },
        )
        val (upstream, _) = transport.writer(sessionCtx(liveUrl, tuneId = 11), hooks)
        val pump = Thread.currentThread().name
        upstream.open()
        val buf = ByteArray(16 * 1024)
        try {
            while (delivered < fixture.size) {
                val n = upstream.read(buf, 0, buf.size)
                assertTrue(n > 0)
                delivered += n
            }
        } finally {
            upstream.close()
            transport.endSession(11)
        }
        assertEquals("one splice per reconnect", 2, splices.size)
        assertTrue("on the reading thread: $splices", splices.all { it.first == pump })
        assertEquals("before the first connection's successor bytes", 40L * PKT, splices[0].second)
        assertEquals(80L * PKT, splices[1].second)
    }
}

/**
 * A call factory whose response bodies end with −1 (instead of throwing) once their call was cancelled: the case where
 * OkHttp ends a cancelled body quietly (fix pass B1 item 3). A dynamic proxy, so it tracks whatever `Call` declares.
 */
private class EofOnCancelCalls(private val client: okhttp3.OkHttpClient) : okhttp3.Call.Factory {
    override fun newCall(request: okhttp3.Request): okhttp3.Call {
        val real = client.newCall(request)
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        fun wrap(r: okhttp3.Response): okhttp3.Response {
            val body = r.body
            val src = object : okio.ForwardingSource(body.source()) {
                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                    if (cancelled.get()) return -1L
                    return try {
                        super.read(sink, byteCount)
                    } catch (e: java.io.IOException) {
                        if (cancelled.get()) -1L else throw e
                    }
                }
            }
            val buffered = src.buffer()
            val wrapped = object : okhttp3.ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = -1L
                override fun source(): okio.BufferedSource = buffered
            }
            return r.newBuilder().body(wrapped).build()
        }
        lateinit var proxy: okhttp3.Call
        proxy = java.lang.reflect.Proxy.newProxyInstance(okhttp3.Call::class.java.classLoader, arrayOf(okhttp3.Call::class.java)) { _, m, args ->
            when (m.name) {
                "cancel" -> { cancelled.set(true); real.cancel(); null }
                "execute" -> wrap(real.execute())
                "enqueue" -> {
                    val cb = args!![0] as okhttp3.Callback
                    real.enqueue(object : okhttp3.Callback {
                        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = cb.onFailure(proxy, e)
                        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) = cb.onResponse(proxy, wrap(response))
                    })
                    null
                }
                else -> try {
                    if (args == null) m.invoke(real) else m.invoke(real, *args)
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw e.targetException
                }
            }
        } as okhttp3.Call
        return proxy
    }
}
