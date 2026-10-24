package app.thebox.data.storage.smb

import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.smb.FakeRemoteLink
import app.thebox.core.storage.smb.FaultClass
import app.thebox.data.storage.FakeOpener
import app.thebox.data.storage.eventually
import com.hierynomus.protocol.commons.socket.ProxySocketFactory
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.event.SMBEventBus
import com.hierynomus.smbj.server.ServerList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** §16.2 `SmbjRemoteLinkTest` (WP-S1): the smbj configuration, two clients, `close(true)`, and the link's waits. */
class SmbjRemoteLinkTest {
    @Test fun theConfigTimeoutsAreTheConstants() {
        for (io in listOf(StorageConstants.SMB_IO_TIMEOUT_MS, StorageConstants.SMB_IO_TIMEOUT_SMALL_POOL_MS)) {
            val c = SmbjClients.config(io)
            assertEquals(io, c.readTimeout)
            assertEquals(io, c.writeTimeout)
            assertEquals(io, c.transactTimeout)
            assertEquals(StorageConstants.SMB_SO_TIMEOUT_MS.toInt(), c.soTimeout)
            assertTrue(c.soTimeout > StorageConstants.SMB_KEEPALIVE_MS)
            assertEquals(StorageConstants.SMB_MAX_IO_BYTES, c.readBufferSize)
            assertEquals(StorageConstants.SMB_MAX_IO_BYTES, c.writeBufferSize)
            assertTrue(c.socketFactory is ProxySocketFactory)
            assertTrue(c.securityProvider is BCSecurityProvider)
            assertTrue(c.isSigningEnabled)
            assertTrue(!c.isSigningRequired)
            assertTrue(!c.isEncryptData)
        }
        // The connect timeout lives in the socket factory (facts §1.5).
        val f = ProxySocketFactory::class.java.getDeclaredField("connectTimeout").also { it.isAccessible = true }
        assertEquals(StorageConstants.SMB_CONNECT_TIMEOUT_MS.toInt(), f.getInt(SmbjClients.config(8_000).socketFactory))
    }

    @Test fun theRingAndTheBrowserHaveTheirOwnClients() {
        val ring = SmbjClients.ringClient(StorageConstants.SMB_IO_TIMEOUT_MS)
        val browse = SmbjClients.browseClient()
        assertNotSame(ring, browse)
        assertNotSame(SmbjClients.ringClient(StorageConstants.SMB_IO_TIMEOUT_MS), ring) // one per link
    }

    @Test fun teardownIsAlwaysCloseTrue() {
        val forced = AtomicReference<Boolean?>()
        val client = SMBClient()
        val conn = object : Connection(SmbjClients.config(8_000), client, SMBEventBus(), ServerList()) {
            override fun close(force: Boolean) { forced.set(force) }
        }
        SmbjClients.forceClose(conn)
        assertEquals(true, forced.get())
    }

    private fun link(server: FakeRemoteLink, opener: FakeOpener, exec: SmbExecutor, demand: AtomicInteger = AtomicInteger()) = SmbjRemoteLink(
        opener, exec, server.clock, server.ioTimeoutMs, password = { PasswordRead.Ok(null) }, onDemand = { demand.incrementAndGet() },
        classifier = { t, _, _ -> server.classify(t).cls },
    )

    @Test fun shareWaitsForReadyAndAsksTheKeeper() {
        val server = FakeRemoteLink()
        val exec = SmbExecutor("t-box-smb", server.clock)
        val demand = AtomicInteger()
        val l = link(server, FakeOpener(server), exec, demand)
        try { l.share(server.clock.nowMs() + 50); fail() } catch (e: LinkUnavailableException) { }
        assertTrue(demand.get() >= 1)
        assertEquals(FaultClass.LINK_LOST, SmbFaults.classify(LinkUnavailableException(), false, null))
        exec.submit { l.connectBlocking() }.get()
        assertEquals(1L, l.epoch)
        val s = l.share(server.clock.nowMs() + 50)
        assertEquals(1L, s.epoch)
        // On box-smb itself share() never waits.
        val onExec = exec.submit { runCatching { l.share(server.clock.nowMs() + 10_000) }.isSuccess }.get()
        assertTrue(onExec)
        exec.submit { l.disconnect() }.get()
        val noWait = exec.submit { runCatching { l.share(server.clock.nowMs() + 10_000) }.exceptionOrNull() }.get(2, TimeUnit.SECONDS)
        assertTrue(noWait is LinkUnavailableException)
    }

    @Test fun reconnectIsSingleFlightAndSpacedByTheMinimumGap() {
        val server = FakeRemoteLink()
        val exec = SmbExecutor("t-box-smb", server.clock)
        val opener = FakeOpener(server)
        val l = link(server, opener, exec)
        exec.submit { l.connectBlocking() }.get()
        val e = l.epoch
        server.dropTransport()
        val t0 = System.nanoTime()
        val start = CountDownLatch(1)
        val results = AtomicReference<MutableList<Long>>(java.util.Collections.synchronizedList(ArrayList()))
        val threads = (1..3).map { Thread { start.await(); results.get() += l.reconnect(e, server.clock.nowMs() + 6_000).epoch } }
        threads.forEach { it.start() }; start.countDown(); threads.forEach { it.join(8_000) }
        assertEquals(listOf(e + 1, e + 1, e + 1), results.get().toList())
        assertEquals("one connect for all three", 2, opener.opens.get())
        val waitedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("min gap: $waitedMs ms", waitedMs >= StorageConstants.SMB_RECONNECT_MIN_GAP_MS - 200)
        assertTrue("the old connection was closed with close(true)", opener.closes.get() >= 1)
    }

    @Test fun anInterruptedWaitIsACancel() {
        val server = FakeRemoteLink()
        val l = link(server, FakeOpener(server), SmbExecutor("t-box-smb", server.clock))
        val err = AtomicReference<Throwable?>()
        val t = Thread { try { l.share(server.clock.nowMs() + 10_000) } catch (e: IOException) { err.set(e) } }
        t.start(); Thread.sleep(100); t.interrupt(); t.join(2_000)
        assertTrue("${err.get()}", err.get() is InterruptedIOException)
        assertEquals(0L, l.epoch)
    }

    @Test fun postRunsOnBoxSmbAndSurvivesAThrow() {
        val server = FakeRemoteLink()
        val exec = SmbExecutor("t-box-smb", server.clock)
        val l = link(server, FakeOpener(server), exec)
        val name = AtomicReference<String>()
        l.post { throw IllegalStateException("boom") }
        l.post { name.set(Thread.currentThread().name) }
        eventually("posted") { name.get() != null }
        assertEquals("t-box-smb", name.get())
    }

    @Test fun refsCountOpenFiles() {
        val server = FakeRemoteLink()
        val l = link(server, FakeOpener(server), SmbExecutor("t-box-smb", server.clock))
        l.retain(); l.retain(); l.release()
        assertEquals(1, l.openFiles)
        l.release(); l.release()
        assertEquals(0, l.openFiles)
    }
}
