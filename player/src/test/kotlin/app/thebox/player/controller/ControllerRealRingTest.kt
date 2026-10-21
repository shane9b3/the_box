package app.thebox.player.controller

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import app.thebox.core.playback.LadderDecision
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.timeshift.BufferMode
import app.thebox.core.playback.timeshift.FakeTsUpstream
import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.RingWriterImpl
import app.thebox.core.playback.timeshift.SyntheticTs
import app.thebox.core.playback.timeshift.TsIndexerImpl
import app.thebox.core.playback.timeshift.TsRingImpl
import app.thebox.core.playback.timeshift.TsUpstream
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.TuneTarget
import app.thebox.player.controller.fakes.CH1
import app.thebox.player.controller.fakes.ControllerFixture
import app.thebox.player.datasource.FailureInfo
import app.thebox.player.datasource.LiveFailureKind
import app.thebox.player.datasource.LiveStreamException
import app.thebox.player.internal.RingOpen
import app.thebox.player.internal.StreamSessionContext
import app.thebox.player.internal.TransportFactory
import app.thebox.player.internal.WriterHooks
import app.thebox.player.timeshift.FakePolicy
import app.thebox.player.timeshift.FakePosix
import app.thebox.player.timeshift.FakeWriterTelemetry
import app.thebox.player.timeshift.TimeshiftSessionFactoryImpl
import app.thebox.player.timeshift.TimeshiftStorageImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InterruptedIOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Provider

/**
 * Fix pass — playback (2026-10-19), review §7 items 3 and 4: the controller over the **real** `TsRingImpl`,
 * `RingWriterImpl` and `TsIndexerImpl` (a real `box-ts-pump` thread, real files in a temp directory). The fake ring and
 * writer of `ControllerTimeshiftTest` model neither the generation's `ended` flag nor the pump's exit (B2) nor the pin
 * and pool arithmetic (B3).
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ControllerRealRingTest {
    private val root: File = Files.createTempDirectory("ts-real-ring").toFile()
    private val clock = MonoClock { SystemClock.elapsedRealtime() }
    private val policy = FakePolicy(capacity = 128L shl 20)
    private val storage = TimeshiftStorageImpl(
        File(root, "cache/timeshift"), File(root, "nb/timeshift-writes"), 1, clock,
        posix = FakePosix(), statFs = { TimeshiftStorageImpl.Space(32_000_000_000L, 10_000_000_000L) },
        counterIo = java.util.concurrent.Executor { it.run() },
    )
    /** The writer's upstreams, in the order `TransportFactory.writer` hands them out (the ctx comes first). */
    private val upstreams = ArrayDeque<(StreamSessionContext) -> TsUpstream>()
    private val opened = mutableListOf<TsUpstream>()
    private var fxRef: ControllerFixture? = null
    private val transportProxy = object : TransportFactory {
        override fun liveTs(ctx: StreamSessionContext) = error("unused")
        override fun http(ctx: StreamSessionContext) = error("unused")
        override fun ring(ctx: StreamSessionContext, open: RingOpen) = error("unused")
        override fun endSession(tuneId: Long) {
            synchronized(opened) { opened.forEach { it.terminate() } }
            fxRef!!.transport.endSession(tuneId)
        }
        override fun writer(ctx: StreamSessionContext, hooks: WriterHooks): Pair<TsUpstream, app.thebox.player.internal.StreamTelemetry> {
            val u = upstreams.removeFirst()(ctx)
            synchronized(opened) { opened += u }
            return u to FakeWriterTelemetry()
        }
    }
    private val factory = TimeshiftSessionFactoryImpl(
        storage, policy, TsRingImpl.FACTORY, RingWriterImpl.FACTORY, Provider { TsIndexerImpl() }, transportProxy, clock,
        lowRam = false, background = { it.run() },
    )
    private val f = ControllerFixture(factory).also { fxRef = it }
    private val c get() = f.controller
    private val ring: TsRingImpl get() = factory.currentRing() as TsRingImpl

    init {
        runBlocking { storage.awaitSweep() }
    }

    @After fun tearDown() {
        c.stop(app.thebox.player.api.StopReason.USER)
        f.idle()
        f.close()
        root.deleteRecursively()
    }

    /** Idles the main looper (the pump posts its listener calls there) until [cond] holds, in real time. */
    private fun waitFor(what: String, ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.nanoTime() + ms * 1_000_000
        while (true) {
            f.idle()
            if (cond()) return
            check(System.nanoTime() < end) { "timed out waiting for $what" }
            Thread.sleep(2)
        }
    }

    private fun refusal(ctx: StreamSessionContext, status: Int) = LiveStreamException(
        LiveFailureKind.HTTP_STATUS,
        FailureInfo(status, "panel.example:8080", 0, 1, FailureInfo.Phase.OPEN, null, null, 0),
        DataSpec(Uri.parse("thebox-session://${ctx.tuneId}")),
        null,
    )

    // ------------------------------------------------------------------ B2

    /**
     * B2: a first-open 403 (the panel still counts the old socket) → RetrySame. The writer restarts in place, the bytes
     * of the second connection are recorded into the **same** generation, and a reader at the cycle's start offset
     * reads them (before the fix the old pump's exit ended the generation: the new writer got STOPPED and idled, the
     * reader got `RingEndException(SESSION_END)`, and the tune hung until the switch timeout).
     */
    @Test
    fun aFirstOpen403ThenRetrySameRecordsAndTheReaderPlays() {
        f.ladder.script = { _, _ -> LadderDecision.RetrySame(0) }
        upstreams += { ctx -> FakeTsUpstream(source = null, name = "refused").also { it.openFailure = refusal(ctx, 403) } }
        upstreams += { _ -> FakeTsUpstream(SyntheticTs(), name = "ok").also { it.pacing = { Thread.sleep(1) } } }
        c.tune(TuneTarget.Channel(CH1))
        f.idle()
        check(factory.currentRing() != null) { "no ring: ${c.localBuffer.value} ${f.events.events}" }
        val generation = ring.generation
        waitFor("the ladder decided once") { f.ladder.seen.size == 1 }
        waitFor("bytes of the second connection") { ring.head > 200L * 188 }
        assertEquals("the same generation", generation, ring.generation)
        assertNull("the generation was not ended", ring.stop)
        assertTrue(ring.saving)
        assertEquals(1, f.sources.builds.count { it.ring != null })
        val open = f.sources.last.ring!!
        val reader = open.ring.openReader(open.startOffset, open.generation)
        try {
            val buf = ByteArray(188 * 20)
            assertTrue("the reader plays", reader.read(buf, 0, buf.size) > 0)
        } finally {
            reader.close()
        }
        waitFor("first bytes reached the controller") { c.state.value.let { it is PlaybackState.Connecting && it.phase == app.thebox.player.api.ConnectPhase.BUFFERING_INITIAL } }
        assertTrue(c.localBuffer.value!!.available)
        assertFalse("no second session on the grant", f.events.events.any { it.code == "timeshift_end" })
    }

    // ------------------------------------------------------------------ B3

    /** Paces a synthetic stream: [read] returns only what the test [allow]ed (whole packets). */
    private class BudgetUpstream(private val ts: SyntheticTs) : TsUpstream {
        private val budget = AtomicLong()
        val delivered = AtomicLong()
        @Volatile private var terminated = false
        fun allow(bytes: Long) { budget.addAndGet(bytes) }
        override fun open() = Unit
        override fun read(dst: ByteArray, off: Int, len: Int): Int {
            while (!terminated) {
                val left = budget.get() - delivered.get()
                val want = (minOf(len.toLong(), left) / 188 * 188).toInt()
                if (want > 0) {
                    val n = ts.fill(dst, off, want)
                    if (n > 0) { delivered.addAndGet(n.toLong()); return n }
                }
                Thread.sleep(1)
            }
            throw InterruptedIOException("terminated")
        }
        override fun close() = Unit
        override fun terminate() { terminated = true }
    }

    /**
     * B3: playing at live for 60 s of a 1 MB/s channel with a 5 s GOP. With the PLAYHEAD pin following the playhead (a GOP
     * plus the latency behind the head), a RAM-only ring answers FULL and the pump stops reading; PAUSE_ONLY persists
     * continuously. Without it: the pump keeps pace, no GAP segment, and PAUSE_ONLY writes 0 bytes.
     */
    private fun playAtLiveFor60s(ramOnly: Boolean) {
        val ts = SyntheticTs(fps = 25, gopFrames = 125, bytesPerSec = 1_000_000)
        val perSec = ts.frameBytes.toLong() * ts.fps
        val up = BudgetUpstream(ts)
        upstreams += { _ -> up }
        c.tune(TuneTarget.Channel(CH1))
        f.idle()
        up.allow(perSec)
        waitFor("the first second") { ring.head >= perSec }
        if (ramOnly) ring.stopRecording(RecordStop.DISK_ERROR) // as after a disk stop (§4.6): the ring is RAM-only
        f.exo.position = 0
        f.play()
        val e = LongArray(2)
        waitFor("a first RAP") { ring.index.oldestRapEntry(e) >= 0 }
        val origin = e[1]
        var pushed = perSec
        for (s in 1..60) {
            up.allow(perSec)
            pushed += perSec
            waitFor("the pump keeps pace at $s s (no FULL wait)", ms = 3_000) { ring.head >= pushed }
            // The viewer at live: the playhead 3 s behind the newest PTS (a GOP plus the latency would be pinned).
            f.exo.position = (ring.index.newestPtsMono() - origin - 3_000).coerceAtLeast(0L)
            f.advance(1_000)
            val st = c.state.value
            assertTrue("playing at live: $st", st is PlaybackState.Playing && !st.paused)
        }
        assertFalse(
            "no GAP segment: ${f.events.events.filter { it.code == "segment" }.map { it.detail }}",
            f.events.events.any { it.type == PlaybackEventType.DECISION && it.code == "segment" && it.detail.startsWith("GAP") },
        )
    }

    @Test
    fun ramOnlyAtLiveNeverWaitsOnTheRing() {
        playAtLiveFor60s(ramOnly = true)
    }

    @Test
    fun pauseOnlyAtLiveWritesNothing() {
        policy.modeAnswer = BufferMode.PAUSE_ONLY
        playAtLiveFor60s(ramOnly = false)
        assertEquals(BufferMode.PAUSE_ONLY, ring.mode)
        assertEquals("PAUSE_ONLY at live persists nothing (§4.5)", 0L, ring.persistedBytes)
    }
}
