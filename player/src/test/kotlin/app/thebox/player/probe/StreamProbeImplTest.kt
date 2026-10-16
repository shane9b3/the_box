package app.thebox.player.probe

import app.thebox.core.playback.BrokerSnapshot
import app.thebox.core.playback.ConnectionBrokerCore
import app.thebox.core.playback.ConnectionLimitException
import app.thebox.core.playback.CoreLease
import app.thebox.core.playback.LeaseKind
import app.thebox.core.setup.ProbeVerdict
import app.thebox.core.setup.SetupConstants
import app.thebox.player.api.PlaybackSettings
import app.thebox.player.datasource.FIXTURE
import app.thebox.player.datasource.FakeLease
import app.thebox.player.datasource.FloodBody
import app.thebox.player.datasource.LiveBody
import app.thebox.player.datasource.liveResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * §3.4.5 / §6.1 WP-B: the probe over a fake broker and real calls to MockWebServer. Closes on every path (including
 * coroutine cancellation), never sends Range, LIMIT never pre-empts PLAYBACK, verdicts from the sniffed bytes.
 */
class StreamProbeImplTest {
    private lateinit var server: MockWebServer
    private val okhttp = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    /** Every call the probe creates, to check each one was cancelled (closed) before probe() returned. */
    private val calls = CopyOnWriteArrayList<Call>()
    private val factory = Call.Factory { r: Request -> okhttp.newCall(r).also { calls += it } }
    private val broker = ScriptedBroker()
    private val probe = StreamProbeImpl({ key -> broker.also { it.keys += key } }, { factory }, Dispatchers.IO, { System.nanoTime() / 1_000_000 })
    private val bodies = CopyOnWriteArrayList<LiveBody>()

    @Before fun setUp() { server = MockWebServer(); server.start() }

    @After fun tearDown() {
        bodies.forEach { it.release() }
        server.close()
    }

    /** Real time on Dispatchers.Default (the probe blocks an IO thread; virtual time would not wait for it). */
    private fun real(block: suspend CoroutineScope.() -> Unit) = runTest { withContext(Dispatchers.Default) { block() } }

    private fun url(path: String = "/live/u/p/1.ts") = server.url(path).toString()

    private fun assertClosed() {
        assertTrue("a call was made", calls.isNotEmpty())
        assertTrue("every call cancelled", calls.all { it.isCanceled() })
        val lease = broker.leases.single()
        assertEquals(LeaseKind.PROBE, lease.kind)
        assertEquals(SetupConstants.PROBE_OWNER_ID, lease.ownerTuneId)
        assertEquals("released exactly once", 1, lease.releaseCount.get())
        assertEquals("detached", lease.attachCount.get(), lease.detachCount.get())
    }

    @Test
    fun aTsStreamIsMediaReadIsBoundedAndNoRangeIsSent() = real {
        server.enqueue(liveResponse(FloodBody(FIXTURE.copyOf(188 * 50))))
        val r = probe.probe(9, url(), mapOf("Range" to "bytes=0-", "X-Extra" to "1", "User-Agent" to "Header/1"), "VLC/3.0.20 LibVLC/3.0.20")
        assertEquals(ProbeVerdict.MEDIA, r.verdict)
        assertEquals(200, r.httpStatus)
        assertTrue("at most PROBE_BYTES", r.bytes in 1..SetupConstants.PROBE_BYTES)
        val req = server.takeRequest()
        assertNull("never Range on live (CLAUDE.md)", req.headers["Range"])
        assertEquals("1", req.headers["X-Extra"])
        assertEquals("the probe's UA wins", "VLC/3.0.20 LibVLC/3.0.20", req.headers["User-Agent"])
        assertEquals(listOf("p:9"), broker.keys)
        assertClosed()
    }

    @Test
    fun hlsHtmlAndMediaContentTypesAreSniffed() = real {
        server.enqueue(MockResponse.Builder().code(200).body("#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:10,\nseg1.ts\n" + " ".repeat(100)).build())
        assertEquals(ProbeVerdict.HLS, probe.probe(1, url("/a.m3u8"), emptyMap(), null).verdict)
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("﻿  <!DOCTYPE html><html><body>Your subscription expired</body></html>").build())
        assertEquals(ProbeVerdict.HTML, probe.probe(1, url(), emptyMap(), null).verdict)
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "audio/aac").body("ÿñabc").build())
        assertEquals(ProbeVerdict.MEDIA, probe.probe(1, url(), emptyMap(), null).verdict)
        server.enqueue(MockResponse.Builder().code(200).body("").build())
        assertEquals(ProbeVerdict.OTHER, probe.probe(1, url(), emptyMap(), null).verdict)
        assertTrue(calls.all { it.isCanceled() })
        assertTrue(broker.leases.all { it.releaseCount.get() == 1 })
    }

    @Test
    fun statusesMapToVerdicts() = real {
        val table = listOf(401 to ProbeVerdict.FORBIDDEN, 403 to ProbeVerdict.FORBIDDEN, 404 to ProbeVerdict.NOT_FOUND, 458 to ProbeVerdict.LIMIT, 509 to ProbeVerdict.LIMIT, 500 to ProbeVerdict.OTHER, 405 to ProbeVerdict.OTHER)
        for ((code, verdict) in table) {
            server.enqueue(MockResponse.Builder().code(code).body("no").build())
            val r = probe.probe(1, url(), emptyMap(), null)
            assertEquals("HTTP $code", verdict, r.verdict)
            assertEquals(code, r.httpStatus)
        }
        assertTrue(calls.all { it.isCanceled() })
        assertTrue(broker.leases.all { it.releaseCount.get() == 1 })
    }

    @Test
    fun aFullBrokerAnswersLimitWithoutAcquiringOrPreemptingPlayback() = real {
        broker.active = 1 // one PLAYBACK lease of the tuned channel holds the only slot
        val r = probe.probe(1, url(), emptyMap(), null)
        assertEquals(ProbeVerdict.LIMIT, r.verdict)
        assertEquals("no acquire, so nothing can be pre-empted", 0, broker.acquires)
        assertEquals(0, broker.preempts)
        assertTrue(calls.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aRefusedAcquireIsLimitToo() = real {
        broker.refuse = true
        assertEquals(ProbeVerdict.LIMIT, probe.probe(1, url(), emptyMap(), null).verdict)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aPreemptionByATuneEndsTheProbeAsLimitAndClosesIt() = real {
        val stalled = LiveBody(FIXTURE.copyOf(188 * 4), stall = true).also { bodies += it }
        server.enqueue(liveResponse(stalled))
        val job = async(Dispatchers.Default) { probe.probe(1, url(), emptyMap(), null) }
        withTimeout(5_000) { while (broker.leases.isEmpty() || broker.leases.single().handles.isEmpty()) delay(10) }
        delay(100)
        broker.leases.single().preempt() // a PLAYBACK acquire of the same broker pre-empts the probe (BrokerCore rule)
        val r = withTimeout(5_000) { job.await() }
        assertTrue("LIMIT or MEDIA from the bytes read before", r.verdict == ProbeVerdict.LIMIT || r.verdict == ProbeVerdict.MEDIA)
        assertClosed()
    }

    @Test
    fun coroutineCancellationCancelsTheCallAndReleasesTheLease() = real {
        val stalled = LiveBody(ByteArray(0), stall = true).also { bodies += it }
        server.enqueue(liveResponse(stalled))
        val job = async(Dispatchers.Default) { probe.probe(1, url(), emptyMap(), null) }
        withTimeout(5_000) { while (calls.isEmpty()) delay(10) }
        delay(200)
        job.cancel()
        withTimeout(5_000) { while (!job.isCompleted) delay(10) }
        assertTrue(job.isCancelled)
        assertClosed()
    }

    @Test
    fun anUnreachableServerIsNetworkAndAClosedLeaseNeverOpensACall() = real {
        val dead = server.url("/x").toString()
        server.close()
        val r = probe.probe(1, dead, emptyMap(), null)
        assertTrue(r.verdict == ProbeVerdict.NETWORK || r.verdict == ProbeVerdict.TIMEOUT)
        assertClosed()

        broker.attachRefused = true
        calls.clear()
        val r2 = probe.probe(1, dead, emptyMap(), null)
        assertEquals(ProbeVerdict.LIMIT, r2.verdict)
        assertTrue(calls.single().isCanceled())
        server = MockWebServer().also { it.start() }
    }

    @Test
    fun anUnparsableUrlIsOtherAndReleasesTheLease() = real {
        assertEquals(ProbeVerdict.OTHER, probe.probe(1, "not a url", emptyMap(), null).verdict)
        assertEquals(1, broker.leases.single().releaseCount.get())
    }

    @Test
    fun sniffFindsAnUnalignedTsStart() {
        val ts = FIXTURE.copyOfRange(0, 188 * 3)
        val shifted = ByteArray(17) { 0x11 } + ts
        assertEquals(ProbeVerdict.MEDIA, StreamProbeImpl.sniff(shifted, StreamProbeImpl.HEAD_BYTES, null))
        assertEquals(ProbeVerdict.OTHER, StreamProbeImpl.sniff("GARBAGE".toByteArray() + ByteArray(300), 307, null))
        assertEquals(ProbeVerdict.HLS, StreamProbeImpl.sniff("x".toByteArray(), 1, "application/vnd.apple.mpegurl"))
    }

    @Test
    fun theVlcPresetMatchesThePlayersFallbackPreset() {
        // :data persists SetupConstants.VLC_USER_AGENT after a working probe; the player retries with its preset.
        assertEquals(PlaybackSettings().userAgentFallbackPreset, SetupConstants.VLC_USER_AGENT)
        assertFalse(SetupConstants.PROBE_OWNER_ID > 0)
    }

    /** A broker with a scripted occupancy; grants FakeLeases and counts acquires and pre-emptions. */
    private class ScriptedBroker : ConnectionBrokerCore {
        override val key = "p:test"
        val keys = CopyOnWriteArrayList<String>()
        val leases = CopyOnWriteArrayList<FakeLease>()
        @Volatile var active = 0
        @Volatile var max = 1
        @Volatile var refuse = false
        @Volatile var attachRefused = false
        @Volatile var acquires = 0
        @Volatile var preempts = 0
        private val ids = AtomicLong()

        override fun configure(maxConnections: Int) { max = maxConnections }
        override fun preemptPlayback(reason: String): Int { preempts++; return 0 }
        override fun acquire(kind: LeaseKind, ownerTuneId: Long, waitMs: Long): CoreLease {
            acquires++
            if (refuse) throw ConnectionLimitException(active, max)
            val l = FakeLease(ids.incrementAndGet(), kind, ownerTuneId)
            if (attachRefused) l.preempted = true
            leases += l
            return l
        }
        override fun snapshot() = BrokerSnapshot(key, active, 0, max, 0, emptyMap())
    }
}
