package app.thebox.player.controller

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlaybackException
import app.thebox.core.model.Catchup
import app.thebox.core.model.CatchupMode
import app.thebox.core.playback.FailureSignal
import app.thebox.core.playback.LadderDecision
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.NetworkFailureKind
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.StallKind
import app.thebox.core.playback.StallVerdict
import app.thebox.core.playback.XtreamOutput
import app.thebox.core.playback.timeshift.PinOwner
import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.RingBoundaryException
import app.thebox.core.playback.timeshift.RingEndException
import app.thebox.core.playback.timeshift.SegmentReason
import app.thebox.core.playback.timeshift.TimeshiftConstants
import app.thebox.core.settings.TimeshiftLength
import app.thebox.player.api.BufferingCause
import app.thebox.player.api.LocalSeekResult
import app.thebox.player.api.PlaybackNotice
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.StopReason
import app.thebox.player.api.TuneTarget
import app.thebox.player.controller.fakes.CH1
import app.thebox.player.controller.fakes.CH2
import app.thebox.player.controller.fakes.ControllerFixture
import app.thebox.player.datasource.FailureInfo
import app.thebox.player.datasource.LiveFailureKind
import app.thebox.player.datasource.LiveStreamException
import app.thebox.player.internal.StreamSessionContext
import app.thebox.player.internal.StreamTelemetry
import app.thebox.player.internal.TransportFactory
import app.thebox.player.timeshift.FakeIndexer
import app.thebox.player.timeshift.FakePolicy
import app.thebox.player.timeshift.FakePosix
import app.thebox.player.timeshift.FakeRingFactory
import app.thebox.player.timeshift.FakeRingReader
import app.thebox.player.timeshift.FakeTsRing
import app.thebox.player.timeshift.FakeWriterFactory
import app.thebox.player.timeshift.FakeWriterTransport
import app.thebox.player.timeshift.RingReaderTelemetry
import app.thebox.player.timeshift.TimeshiftSessionFactoryImpl
import app.thebox.player.timeshift.TimeshiftStorageImpl
import app.thebox.player.timeshift.nullPackets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import javax.inject.Provider

/**
 * §17.2 `ControllerTimeshiftTest` with the **real** `FailureClassifier`, the real `TimeshiftSessionFactoryImpl` and
 * `TimeshiftSessionImpl`, over WP-B's fake ring and writer (the test plays the pump thread through the writer listener).
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ControllerTimeshiftTest {
    private val root: File = Files.createTempDirectory("ts-controller").toFile()
    private val clock = MonoClock { SystemClock.elapsedRealtime() }
    private val ring = FakeTsRing()
    private val ringFactory = FakeRingFactory(ring)
    private val writers = FakeWriterFactory()
    private val writerTransport = FakeWriterTransport()
    private val policy = FakePolicy()
    private val posix = FakePosix()
    private val storage = TimeshiftStorageImpl(
        File(root, "cache/timeshift"), File(root, "nb/timeshift-writes"), 1, clock,
        posix = posix, statFs = { TimeshiftStorageImpl.Space(32_000_000_000L, 10_000_000_000L) },
    )
    private var fxRef: ControllerFixture? = null
    private val transportProxy = object : TransportFactory {
        override fun liveTs(ctx: StreamSessionContext) = error("unused")
        override fun http(ctx: StreamSessionContext) = error("unused")
        override fun ring(ctx: StreamSessionContext, open: app.thebox.player.internal.RingOpen) = error("unused")
        override fun endSession(tuneId: Long) = fxRef!!.transport.endSession(tuneId)
        override fun writer(ctx: StreamSessionContext, hooks: app.thebox.player.internal.WriterHooks) = writerTransport.writer(ctx, hooks)
    }
    private val factory = TimeshiftSessionFactoryImpl(
        storage, policy, ringFactory, writers, Provider { FakeIndexer() }, transportProxy, clock,
        lowRam = false, background = { it.run() },
    )
    private val f = ControllerFixture(factory).also { fxRef = it }
    private val c get() = f.controller

    init {
        runBlocking { storage.awaitSweep() }
    }

    @After fun tearDown() {
        f.close()
        root.deleteRecursively()
    }

    // ------------------------------------------------------------------ helpers

    private val writer get() = writers.last
    private val wid: Long get() = writerTransport.last.ctx.tuneId

    private fun tune() {
        c.tune(TuneTarget.Channel(CH1))
        f.idle()
    }

    /** The pump thread's first commit (posted to main). */
    private fun firstBytes() {
        writer.listener!!.onFirstBytes()
        f.idle()
    }

    private fun readerTelemetry(): RingReaderTelemetry = f.sources.ringTelemetry.getValue(f.sources.last.ctx.tuneId) as RingReaderTelemetry

    /** The reader's place: blocked at the head (live) or behind. */
    private fun readerAt(head: Boolean) {
        readerTelemetry().attach(FakeRingReader(ring, 0, ring.generation).apply { forceAtHead = head })
    }

    /** 60 s of index: a RAP every 2 s at offsets of 10 packets, PTS mono 100,000 … 160,000. */
    private fun fillIndex() {
        ring.append(nullPackets(400))
        for (i in 0..30) ring.addRap(188L * 10 * i, 100_000L + 2_000L * i)
    }

    private fun playBuffered() {
        tune()
        fillIndex()
        firstBytes()
        // First media 2 s behind the newest PTS (a real tune's backlog), so latencyAtTune and the live landing are
        // realistic and "behind" (fix pass S1: playhead-based) means what it says.
        f.exo.position = 58_000
        f.play()
        readerAt(head = true)
    }

    /** Seeks 30 s behind live (a LOCAL time-shift). */
    private fun goBehind(): LocalSeekResult {
        f.exo.position = 0
        val r = c.seekLocal(130_000L, -1)
        f.idle()
        f.play()
        readerAt(head = false)
        return r
    }

    private fun liveEx(kind: LiveFailureKind, status: Int? = null, writerId: Long = wid, bytes: Long = 0, net: NetworkFailureKind? = null) =
        LiveStreamException(
            kind,
            FailureInfo(status, "panel.example:8080", bytes, 1, FailureInfo.Phase.READ, net, null, 0),
            DataSpec(Uri.parse("thebox-session://$writerId")),
            null,
        )

    private fun writerFails(e: java.io.IOException) {
        writer.listener!!.onFailure(e)
        f.idle()
    }

    private fun readerError(e: java.io.IOException) {
        f.exo.emitError(ExoPlaybackException.createForSource(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        f.idle()
    }

    private fun ringBuilds() = f.sources.builds.filter { it.ring != null }

    // ------------------------------------------------------------------ tune

    @Test
    fun aBufferedTuneStartsOneWriterUnderItsOwnTuneIdAndThePlayerReadsTheRing() {
        tune()
        assertEquals(1, writers.writers.size)
        assertEquals(listOf("start"), writer.calls)
        val build = f.sources.last
        assertNotNull("the player reads the ring", build.ring)
        assertEquals(0L, build.ring!!.startOffset)
        assertNotEquals(build.ctx.tuneId, wid)
        assertEquals("one connection: the writer's", 1, writerTransport.opened.size)
        val local = c.localBuffer.value
        assertNotNull(local)
        assertTrue(local!!.available)
        assertEquals(CH1, local.identity)
    }

    @Test
    fun theOffPathIsTheM2PipelineExactly() {
        f.settings.update { it.copy(timeshift = TimeshiftLength.OFF) }
        tune()
        f.play()
        c.pause()
        f.advance(PlaybackControllerImpl.LIVE_PAUSE_RELEASE_MS + 100)
        val bufferedOff = f.journal.entries.toList()
        val offStates = f.states.map { it.javaClass.simpleName to (it as? PlaybackState.Stopped)?.reason }

        val plain = ControllerFixture()
        plain.controller.tune(TuneTarget.Channel(CH1))
        plain.idle()
        plain.play()
        plain.controller.pause()
        plain.advance(PlaybackControllerImpl.LIVE_PAUSE_RELEASE_MS + 100)
        assertEquals(plain.journal.entries, bufferedOff)
        assertEquals(plain.states.map { it.javaClass.simpleName to (it as? PlaybackState.Stopped)?.reason }, offStates)
        assertTrue(writers.writers.isEmpty())
        assertTrue(f.sources.builds.none { it.ring != null })
        assertEquals(app.thebox.core.playback.timeshift.BufferOff.SETTING_OFF, c.localBuffer.value?.off ?: app.thebox.core.playback.timeshift.BufferOff.SETTING_OFF)
        plain.close()
    }

    @Test
    fun anHlsTuneIsNotBuffered() {
        f.resolver.next = { app.thebox.core.playback.ResolveOutcome.Ok(app.thebox.player.controller.fakes.stream(CH1, format = app.thebox.core.playback.StreamFormat.HLS)) }
        tune()
        assertTrue(writers.writers.isEmpty())
        assertNull(f.sources.last.ring)
        assertEquals(app.thebox.core.playback.timeshift.BufferOff.NOT_TS, c.localBuffer.value?.off)
        assertTrue(c.localBuffer.value!!.hls)
        c.stop(StopReason.USER)
        f.idle()
        assertNull("HLS rejected preparation must leave no owned ring", factory.currentRing())
        assertFalse(storage.ringFile.exists())
    }

    @Test
    fun switchingFromBufferedTsToHlsDiscardsThePreviouslyAllocatedRing() {
        tune()
        assertNotNull(factory.currentRing())
        assertTrue(storage.ringFile.exists())
        f.resolver.next = { app.thebox.core.playback.ResolveOutcome.Ok(app.thebox.player.controller.fakes.stream(CH2, format = app.thebox.core.playback.StreamFormat.HLS)) }
        c.tune(TuneTarget.Channel(CH2))
        f.idle()
        c.stop(StopReason.USER)
        f.idle()
        assertNull(factory.currentRing())
        assertTrue("a previously allocated TS ring is actually closed", ring.closed)
        assertFalse(storage.ringFile.exists())
    }

    @Test
    fun stoppingAfterATerminalFailureDiscardsItsRetainedReusableRing() {
        f.ladder.script = { _, _ -> LadderDecision.Fail(PlaybackError(PlaybackErrorCode.CHANNEL_OFFLINE)) }
        tune()
        writerFails(liveEx(LiveFailureKind.HTTP_STATUS, status = 404, bytes = 0))
        assertTrue(c.state.value is PlaybackState.Error)
        assertNotNull("the error retains a reusable ring", factory.currentRing())
        c.stop(StopReason.BACKGROUND)
        f.idle()
        assertNull(factory.currentRing())
        assertFalse(storage.ringFile.exists())
    }

    @Test
    fun zapMilestonesComeFromTheWriter() {
        tune()
        writerTransport.last.ctx.onEvent(PlaybackEventType.LEASE, "acquired", "PLAYBACK")
        f.idle()
        firstBytes()
        f.play()
        val zap = f.events.zaps.last()
        assertTrue("lease from the writer: ${zap.leaseMs}", zap.leaseMs >= 0)
        assertNotNull(zap.firstByteMs)
        assertEquals("ok", zap.outcome)
    }

    @Test
    fun aZapClosesTheOldRecorderBeforeTheNextOpens() {
        tune()
        val oldWid = wid
        c.tune(TuneTarget.Channel(CH2))
        f.idle()
        val j = f.journal.entries
        val ended = j.indexOf("endSession:$oldWid")
        val built = j.indexOfLast { it.startsWith("build:") }
        assertTrue("old recorder ended ($ended) before the new build ($built): $j", ended in 0 until built)
        assertTrue(writers.writers[0].calls.containsAll(listOf("stop:SESSION_END", "close")))
        assertEquals(2, writers.writers.size)
        assertTrue("the ring was reset for the new generation", ring.resets >= 2)
    }

    // ------------------------------------------------------------------ §7.2 pause and resume

    @Test
    fun pauseKeepsSavingWithNoReleaseJobAndResumeContinuesInPlace() {
        playBuffered()
        val tuneId = (c.state.value.target)!!.tuneId
        f.exo.position = 10_000
        c.pause()
        f.idle()
        assertTrue(writer.calls.contains("pause"))
        assertNotNull("PLAYHEAD pin set", ring.pins[PinOwner.PLAYHEAD])
        assertTrue("screen kept on while saving (§11.5)", c.keepScreenOn.value)
        f.advance(PlaybackControllerImpl.LIVE_PAUSE_RELEASE_MS * 3)
        val st = c.state.value
        assertTrue("still paused, no release: $st", st is PlaybackState.Playing && st.paused)
        assertFalse(f.journal.entries.any { it.startsWith("endSession") })
        val buildsBefore = f.sources.builds.size
        c.resume()
        f.idle()
        assertEquals(buildsBefore, f.sources.builds.size)
        assertEquals(tuneId, c.state.value.target!!.tuneId)
        assertTrue(writer.calls.contains("resume"))
        assertFalse(f.notices.contains(PlaybackNotice.LiveResumed))
        assertTrue(f.exo.playWhenReady)
    }

    @Test
    fun savingStoppedWhilePausedReleasesTheScreenAndResumeRestartsTheWriterWithAGap() {
        playBuffered()
        c.pause()
        f.idle()
        assertTrue(c.keepScreenOn.value)
        ring.saving = false
        writer.listener!!.onStopped(RecordStop.FULL_WHILE_PAUSED)
        f.idle()
        assertFalse("screen released once saving stopped", c.keepScreenOn.value)
        assertTrue("connection released", f.journal.entries.contains("endSession:$wid"))
        assertEquals(RecordStop.FULL_WHILE_PAUSED, c.localBuffer.value!!.stop)
        val resolves = f.resolver.calls.size
        c.resume()
        f.idle()
        assertTrue(writer.calls.contains("restart:GAP"))
        assertEquals("a fresh resolve", resolves + 1, f.resolver.calls.size)
    }

    @Test
    fun aCatchUpChannelKeepsTheScreenOnAfterSavingStopsForUpToThreeHours() {
        f.provider.catchup = Catchup(CatchupMode.DEFAULT, days = 3)
        playBuffered()
        c.pause()
        f.idle()
        writer.listener!!.onStopped(RecordStop.HOLD_LIMIT)
        f.idle()
        assertTrue(c.keepScreenOn.value)
        f.advance(TimeshiftConstants.PAUSE_KEEP_AWAKE_MAX_MS + 1_000)
        assertFalse(c.keepScreenOn.value)
    }

    // ------------------------------------------------------------------ §7.4 ladder

    @Test
    fun aWriter403BeforeTheFirstByteGivesTodaysCardDecidedOnce() {
        f.ladder.script = { s, _ ->
            if (s == FailureSignal.Http(403, app.thebox.core.playback.LoadOrigin.PROGRESSIVE)) LadderDecision.Fail(PlaybackError(PlaybackErrorCode.FORBIDDEN))
            else LadderDecision.Fail(PlaybackError(PlaybackErrorCode.INTERNAL))
        }
        tune()
        writerFails(liveEx(LiveFailureKind.HTTP_STATUS, status = 403))
        val st = c.state.value
        assertTrue("card, not stale: $st", st is PlaybackState.Error && st.error.code == PlaybackErrorCode.FORBIDDEN)
        assertEquals(1, f.ladder.seen.size)
        // The reader's blocked open ends with SESSION_END when the restart/fail closes it: stale, never a second decision.
        readerError(RingEndException(RecordStop.SESSION_END))
        assertEquals(1, f.ladder.seen.size)
    }

    /**
     * Fix pass B2: a first-open RetrySame restarts the cycle's one writer in place (its pump terminates, closes and
     * reopens; the empty first segment is reused). No second session on the grant (which left the generation ended),
     * no reset, and the reader waiting at the tail is untouched. `ControllerRealRingTest` drives the real ring and writer.
     */
    @Test
    fun aFirstOpenRetrySameKeepsTheCycleBuffered() {
        f.ladder.script = { _, _ -> LadderDecision.RetrySame(0) }
        tune()
        val firstWid = wid
        val builds = f.sources.builds.size
        val resets = ring.resets
        writerFails(liveEx(LiveFailureKind.NETWORK, net = NetworkFailureKind.CONNECT))
        f.idle()
        assertEquals(1, f.ladder.seen.size)
        assertEquals("one recorder for the cycle", 1, writers.writers.size)
        assertTrue(writer.calls.contains("restart:WRITER_RESTART"))
        assertFalse(writer.calls.contains("stop:SESSION_END"))
        assertEquals("the same writer tune id", firstWid, wid)
        assertEquals(2, writerTransport.opened.size)
        assertEquals("no ring reset", resets, ring.resets)
        assertEquals("the reader is untouched", builds, f.sources.builds.size)
        assertTrue(c.localBuffer.value!!.available)
        assertTrue("still connecting: ${c.state.value}", c.state.value is PlaybackState.Connecting)
    }

    /**
     * (Save-location integration, S4-Q5; storage §4.6, §10) A read of the saved file that fails on the resume after a
     * pause is "lost on resume" and back at live: one SavingStopped(DISK_ERROR) carrying both flags, which the host words
     * as PAUSE_RESUME_LOST_STORAGE for a network folder. A read failure at live keeps today's plain notice.
     */
    @Test
    fun aReadFailureOnTheResumeSaysLostOnResumeAndBackAtLive() {
        playBuffered()
        c.pause()
        f.idle()
        c.resume()
        f.idle()
        readerError(app.thebox.core.playback.timeshift.RingIoException(java.io.IOException("EIO")))
        val n = f.notices.filterIsInstance<PlaybackNotice.SavingStopped>()
        assertEquals(listOf(PlaybackNotice.SavingStopped(RecordStop.DISK_ERROR, jumpedToLive = true, resumeLost = true)), n)
    }

    @Test
    fun aReadFailureAtLiveKeepsThePlainNotice() {
        playBuffered()
        readerError(app.thebox.core.playback.timeshift.RingIoException(java.io.IOException("EIO")))
        val n = f.notices.filterIsInstance<PlaybackNotice.SavingStopped>()
        assertEquals(listOf(PlaybackNotice.SavingStopped(RecordStop.DISK_ERROR)), n)
    }

    /**
     * A-C4 (lead reconciliation R3): the hook is the writer's `reconnectAllowed`; a refusal makes the writer stop itself
     * with SLOT_LOST (`onStopped`, as `RingWriterImpl` does; `RingWriterTest` covers the cap), never the ladder.
     */
    @Test
    fun aRefusedReconnectWhilePausedIsSlotLostWithoutTheLadder() {
        playBuffered()
        c.pause()
        f.idle()
        val hooks = writerTransport.last.hooks
        assertTrue(hooks.reconnectAllowed(app.thebox.core.playback.timeshift.ReconnectCause.SERVER_CLOSED, true))
        writer.allowReconnect = false
        assertFalse("A-C4: the writer's cap refuses", hooks.reconnectAllowed(app.thebox.core.playback.timeshift.ReconnectCause.SERVER_CLOSED, true))
        assertEquals(2, writer.reconnectAsks)
        writer.listener!!.onStopped(RecordStop.SLOT_LOST)
        f.idle()
        assertTrue("the connection is released", f.journal.entries.contains("endSession:$wid"))
        assertTrue(f.notices.contains(PlaybackNotice.SavingStopped(RecordStop.SLOT_LOST)))
        assertEquals(RecordStop.SLOT_LOST, c.localBuffer.value!!.stop)
        assertTrue("no ladder", f.ladder.seen.isEmpty())
    }

    @Test
    fun theHookAsksTheWriterAndAClosedSessionRefuses() {
        playBuffered()
        val hooks = writerTransport.last.hooks
        repeat(5) { assertTrue(hooks.reconnectAllowed(app.thebox.core.playback.timeshift.ReconnectCause.SERVER_CLOSED, true)) }
        assertEquals(5, writer.reconnectAsks)
        c.stop(app.thebox.player.api.StopReason.USER)
        f.idle()
        assertFalse(hooks.reconnectAllowed(app.thebox.core.playback.timeshift.ReconnectCause.SERVER_CLOSED, true))
    }

    @Test
    fun aWriterReconnectShowsOnlyAtTheHead() {
        playBuffered()
        writerTransport.last.ctx.onEvent(PlaybackEventType.RECONNECT, "attempt", "1/3 eof")
        f.idle()
        val st = c.state.value
        assertTrue("$st", st is PlaybackState.Buffering && st.cause == BufferingCause.RECONNECTING)
        writerTransport.last.ctx.onEvent(PlaybackEventType.RECONNECT_OK, "ok", "1 ms")
        f.idle()
        assertTrue(c.state.value is PlaybackState.Playing)

        goBehind()
        val before = c.state.value
        val notices = f.notices.size
        writerTransport.last.ctx.onEvent(PlaybackEventType.RECONNECT, "attempt", "1/3 eof")
        f.idle()
        assertEquals(before, c.state.value)
        assertEquals(notices, f.notices.size)
    }

    @Test
    fun aWriterRetrySameWithADelayAtTheHeadIsNoNoBytesStall() {
        f.ladder.script = { _, _ -> LadderDecision.RetrySame(10_000) }
        playBuffered()
        val builds = f.sources.builds.size
        writerFails(liveEx(LiveFailureKind.RECONNECT_EXHAUSTED, net = NetworkFailureKind.RESET, bytes = 1_000))
        val t = readerTelemetry()
        assertTrue("restart pending reads as a reconnect at the head (B3)", t.inReconnect)
        assertEquals(0L, t.readPendingSinceMs)
        val st = c.state.value
        assertTrue("$st", st is PlaybackState.Buffering && st.cause == BufferingCause.RECONNECTING)
        f.advance(9_000)
        assertFalse(writer.calls.any { it.startsWith("restart") })
        f.advance(1_500)
        assertTrue(writer.calls.contains("restart:WRITER_RESTART"))
        assertEquals("reader untouched", builds, f.sources.builds.size)
    }

    @Test
    fun aWriterFailBehindLiveIsStashedThenShownAfterTheSavedPartPlayedOut() {
        f.ladder.script = { _, _ -> LadderDecision.Fail(PlaybackError(PlaybackErrorCode.CHANNEL_OFFLINE)) }
        playBuffered()
        goBehind()
        writerFails(liveEx(LiveFailureKind.HTTP_STATUS, status = 404, bytes = 5_000))
        assertTrue("still playing the saved part: ${c.state.value}", c.state.value !is PlaybackState.Error)
        assertTrue(writer.calls.contains("stop:UPSTREAM_FAILED"))
        assertEquals(1, f.ladder.seen.size)
        readerError(RingEndException(RecordStop.UPSTREAM_FAILED))
        val st = c.state.value
        assertTrue("$st", st is PlaybackState.Error && st.error.code == PlaybackErrorCode.CHANNEL_OFFLINE)
        assertEquals("decided once", 1, f.ladder.seen.size)
    }

    @Test
    fun aCycleScopedDecisionBehindLiveIsDeferredUntilTheReaderReachesTheHead() {
        f.ladder.script = { _, _ -> LadderDecision.FlipXtreamOutput(XtreamOutput.HLS) }
        f.resolver.next = { call ->
            val hls = call.output == XtreamOutput.HLS
            app.thebox.core.playback.ResolveOutcome.Ok(app.thebox.player.controller.fakes.stream(CH1, output = call.output, format = if (hls) app.thebox.core.playback.StreamFormat.HLS else app.thebox.core.playback.StreamFormat.TS))
        }
        playBuffered()
        goBehind()
        val builds = f.sources.builds.size
        writerFails(liveEx(LiveFailureKind.HTTP_STATUS, status = 406, bytes = 5_000))
        assertEquals("deferred", builds, f.sources.builds.size)
        readerError(RingEndException(RecordStop.UPSTREAM_FAILED))
        assertTrue(f.notices.contains(PlaybackNotice.JumpedToLiveRestart))
        assertTrue(f.sources.builds.size > builds)
        assertNull("the flip to HLS runs unbuffered", f.sources.last.ring)
        // UX-S7: buffering was decided again for the new source: HLS says so (its own toast), not a silent NOT_TS.
        assertEquals(app.thebox.core.playback.timeshift.BufferOff.NOT_TS, c.localBuffer.value?.off)
        assertTrue(c.localBuffer.value!!.hls)
    }

    /** UX-S7: after JUMPED_TO_LIVE_RESTART on a TS source, "Rewind saves again from now" holds: the relaunch is buffered. */
    @Test
    fun aDeferredRelaunchOnATsSourceBuffersAgain() {
        f.ladder.script = { _, _ -> LadderDecision.SwitchSourceType(app.thebox.core.playback.StreamFormat.TS) }
        playBuffered()
        goBehind()
        writerFails(liveEx(LiveFailureKind.HTTP_STATUS, status = 406, bytes = 5_000))
        readerError(RingEndException(RecordStop.UPSTREAM_FAILED))
        assertTrue(f.notices.contains(PlaybackNotice.JumpedToLiveRestart))
        assertEquals("a new recorder for the relaunch", 2, writers.writers.size)
        assertNotNull("buffered again", f.sources.last.ring)
        assertTrue(c.localBuffer.value!!.available)
    }

    @Test
    fun slotLostWhilePausedNeverReconnects() {
        playBuffered()
        c.pause()
        f.idle()
        writerFails(liveEx(LiveFailureKind.HTTP_STATUS, status = 509, bytes = 10_000))
        assertTrue(writer.calls.contains("stop:SLOT_LOST"))
        assertTrue(f.ladder.seen.isEmpty())
        assertFalse(writer.calls.any { it.startsWith("restart") })
        assertTrue(f.journal.entries.contains("endSession:$wid"))
    }

    @Test
    fun noBytesAtTheHeadGoesToTheWriterScopedLadder() {
        f.ladder.script = { _, _ -> LadderDecision.ReResolve(0) }
        playBuffered()
        val builds = f.sources.builds.size
        f.engine.watchdog.onStall!!(StallVerdict(StallKind.NO_BYTES, "age"))
        f.idle()
        assertTrue(writer.calls.contains("restart:WRITER_RESTART"))
        assertEquals(builds, f.sources.builds.size)
    }

    @Test
    fun everyLaunchSessionCallSiteKeepsThePositionBehindLive() {
        f.ladder.script = { _, _ -> LadderDecision.Reprepare() }
        f.resolver.baseCount = 2
        playBuffered()
        goBehind()
        val pinRap = ring.fakeIndex.offsetAt(ring.fakeIndex.floorRap(130_000L))
        f.exo.position = 0
        f.journal.entries.clear()

        c.reapplySettings()
        f.idle()
        assertEquals(pinRap, f.sources.last.ring!!.startOffset)
        f.play()

        c.setVideoDecoderOverride(app.thebox.core.playback.VideoDecoderMode.SOFTWARE_PREFERRED)
        f.idle()
        assertEquals(pinRap, f.sources.last.ring!!.startOffset)
        f.play()

        f.engine.watchdog.onStall!!(StallVerdict(StallKind.DECODER_STUCK, "x"))
        f.idle()
        assertEquals(pinRap, f.sources.last.ring!!.startOffset)
        f.play()
        assertFalse("reader re-prepares never stop the player: ${f.journal}", f.journal.entries.contains("player.stop"))
        assertEquals("no recorder restart for reader-side work", listOf("start"), writer.calls)

        // selectServer: a writer restart on the chosen base; the reader (and the viewer's place) is untouched.
        val builds = f.sources.builds.size
        c.selectServer(1)
        f.idle()
        assertEquals(builds, f.sources.builds.size)
        assertTrue(writer.calls.contains("restart:WRITER_RESTART"))
        assertEquals(1, f.resolver.calls.last().baseIndex)
        assertEquals(1, writerTransport.last.ctx.resolved.baseIndex)
    }

    @Test
    fun aPausedReapplyStaysPausedAtThePin() {
        playBuffered()
        f.exo.position = 4_000
        c.pause()
        f.idle()
        c.reapplySettings()
        f.idle()
        assertNotNull(f.sources.last.ring)
        assertFalse(f.exo.playWhenReady)
    }

    // ------------------------------------------------------------------ §6 seek

    @Test
    fun seekLocalReprepresAtARapWithTheLastFrameHeld() {
        playBuffered()
        f.exo.position = 50_000 // playhead = 100,000 + 50,000 = 150,000
        f.journal.entries.clear()
        val r = c.seekLocal(121_000L, -1)
        f.idle()
        assertEquals(LocalSeekResult.Started(120_000L), r)
        val offset = ring.fakeIndex.offsetAt(ring.fakeIndex.floorRap(121_000L))
        assertEquals(offset, f.sources.last.ring!!.startOffset)
        assertEquals(offset, ring.pins[PinOwner.PLAYHEAD])
        val j = f.journal.entries
        assertFalse("no stop() between the prepares: $j", j.contains("player.stop"))
        assertFalse("the surface is never cleared", j.any { it.startsWith("clearVideoSurface") })
        assertTrue(j.indexOf("setMediaSource") < j.lastIndexOf("prepare"))
        // position 0 = the RAP's PTS: the playhead is origin + position.
        f.exo.position = 500
        assertEquals(120_500L, c.position().localMonoMs)
    }

    @Test
    fun forwardStepsNeverLandOnTheSamePlaceAndOldTargetsClamp() {
        playBuffered()
        c.seekLocal(120_000L, -1)
        f.idle()
        f.exo.position = 300
        val r = c.seekLocal(120_100L, +1)
        f.idle()
        assertEquals(LocalSeekResult.Started(122_000L), r)
        val old = c.seekLocal(10_000L, -1)
        f.idle()
        assertEquals(LocalSeekResult.Clamped(100_000L), old)
    }

    @Test
    fun jumpToLiveLandsBehindTheNewestPtsByTheLiveLanding() {
        playBuffered()
        goBehind()
        assertTrue(c.jumpToLiveLocal())
        f.idle()
        val landing = c.liveLandingMs()
        assertTrue(landing >= TimeshiftConstants.LIVE_LANDING_EXTRA_MS)
        val expected = ring.fakeIndex.offsetAt(ring.fakeIndex.floorRap(160_000L - landing))
        assertEquals(expected, f.sources.last.ring!!.startOffset)
    }

    // ------------------------------------------------------------------ §5.3 boundaries

    @Test
    fun aBoundaryHandsOffToTheNextSegmentWithoutTheLadder() {
        playBuffered()
        val next = ring.fakeIndex.offsetAt(10)
        readerError(RingBoundaryException(next, SegmentReason.PCR_JUMP, gapMs = 2_000, gapStartMonoMs = 118_500))
        assertEquals(next, f.sources.last.ring!!.startOffset)
        assertTrue(f.ladder.seen.isEmpty())
        assertTrue(f.notices.contains(PlaybackNotice.GapSkipped(2_000, stream = true)))
        assertFalse(f.events.events.any { it.type == PlaybackEventType.ERROR && it.code.contains("Ring") })
    }

    @Test
    fun aSavingStopGapOnACatchUpChannelDuringATimeShiftGoesToTheProvider() {
        f.provider.catchup = Catchup(CatchupMode.DEFAULT, days = 3)
        playBuffered()
        goBehind()
        val builds = f.sources.builds.size
        readerError(RingBoundaryException(ring.fakeIndex.offsetAt(20), SegmentReason.GAP, gapMs = 5_000, gapStartMonoMs = 135_000))
        assertTrue(f.notices.contains(PlaybackNotice.LocalGap(135_000, 5_000, SegmentReason.GAP)))
        assertEquals(builds, f.sources.builds.size)
    }

    @Test
    fun aShortStreamGapAtTheHeadOfACatchUpChannelStaysLocal() {
        f.provider.catchup = Catchup(CatchupMode.DEFAULT, days = 3)
        playBuffered()
        readerError(RingBoundaryException(ring.fakeIndex.offsetAt(10), SegmentReason.PCR_JUMP, gapMs = 3_000, gapStartMonoMs = 118_000))
        assertFalse(f.notices.any { it is PlaybackNotice.LocalGap })
        assertEquals(ring.fakeIndex.offsetAt(10), f.sources.last.ring!!.startOffset)
    }

    // ------------------------------------------------------------------ §7.5 lifecycle

    @Test
    fun homeWhilePausedDiscardsTheBufferAndTheNextTuneSaysSoOnce() {
        playBuffered()
        val file = storage.ringFile
        assertTrue(file.exists())
        c.pause()
        f.idle()
        c.lifecycle.onProcessStop()
        f.idle()
        assertTrue(c.state.value is PlaybackState.Stopped)
        assertTrue(writer.calls.contains("stop:SESSION_END"))
        assertTrue(f.journal.entries.contains("endSession:$wid"))
        assertFalse("file deleted", file.exists())
        assertTrue(ring.closed)
        c.lifecycle.onProcessStart()
        f.idle()
        assertEquals(1, f.notices.count { it == PlaybackNotice.LeftAppCleared })
        c.tune(TuneTarget.Channel(CH2))
        f.idle()
        assertEquals(1, f.notices.count { it == PlaybackNotice.LeftAppCleared })
    }

    @Test
    fun standbyWhileLiveDiscardsWithoutTheNotice() {
        playBuffered()
        f.device.interactive = false
        c.lifecycle.onProcessStop()
        f.advance(31_000)
        assertTrue(c.state.value is PlaybackState.Stopped)
        assertEquals(StopReason.STANDBY, (c.state.value as PlaybackState.Stopped).reason)
        assertFalse(storage.ringFile.exists())
        f.device.interactive = true
        c.lifecycle.onProcessStart()
        f.idle()
        assertFalse(f.notices.contains(PlaybackNotice.LeftAppCleared))
    }

    @Test
    fun statsCarryTheRewindLine() {
        playBuffered()
        c.setStatsEnabled(true)
        f.advance(2_000)
        val s = c.stats.value
        assertEquals(60_000L, s.rewindSavedMs)
        assertTrue(s.rewindSaving)
    }

    // ------------------------------------------------------------------ fix pass — playback (2026-10-19)

    /** B3: at live the PLAYHEAD pin is not set; a pause sets it; back at live (reader open) it is cleared. */
    @Test
    fun thePlayheadPinExistsOnlyWhilePausedOrTimeShifted() {
        playBuffered()
        f.advance(3_000)
        assertNull("no PLAYHEAD pin at live", ring.pins[PinOwner.PLAYHEAD])
        c.pause()
        f.idle()
        assertNotNull("paused: pinned", ring.pins[PinOwner.PLAYHEAD])
        c.resume()
        f.idle()
        assertTrue(c.jumpToLiveLocal())
        f.idle()
        f.play()
        readerAt(head = true)
        f.exo.position = 0
        f.advance(2_000)
        assertNull("back at live: the pin is cleared", ring.pins[PinOwner.PLAYHEAD])
    }

    /** S1: a short rewind with the loader already at the head is still a time-shift: a 509 is SLOT_LOST, not the ladder. */
    @Test
    fun aShortRewindWithTheLoaderAtTheHeadIsStillBehind() {
        playBuffered()
        f.exo.position = 0
        c.seekLocal(150_000L, -1) // 10 s behind the newest PTS
        f.idle()
        f.play()
        readerAt(head = true) // the loader cursor caught up within seconds
        f.advance(1_500)
        writerFails(liveEx(LiveFailureKind.HTTP_STATUS, status = 509, bytes = 10_000))
        assertTrue(writer.calls.contains("stop:SLOT_LOST"))
        assertTrue("no ladder fight", f.ladder.seen.isEmpty())
    }

    /** S1: at live, a writer Fail after saved bytes is stashed; the card follows the reader's RingEnd(UPSTREAM_FAILED). */
    @Test
    fun aWriterFailAtLiveIsShownOnlyAfterTheBufferPlayedOut() {
        f.ladder.script = { _, _ -> LadderDecision.Fail(PlaybackError(PlaybackErrorCode.CHANNEL_OFFLINE)) }
        playBuffered()
        writerFails(liveEx(LiveFailureKind.HTTP_STATUS, status = 404, bytes = 5_000))
        assertTrue("the buffer still plays: ${c.state.value}", c.state.value !is PlaybackState.Error)
        assertTrue(writer.calls.contains("stop:UPSTREAM_FAILED"))
        readerError(RingEndException(RecordStop.UPSTREAM_FAILED))
        val st = c.state.value
        assertTrue("$st", st is PlaybackState.Error && st.error.code == PlaybackErrorCode.CHANNEL_OFFLINE)
        assertEquals("decided once", 1, f.ladder.seen.size)
    }

    /** S2: a writer segment storm restarts the recorder; a repeat relaunches the cycle; neither reaches the ladder. */
    @Test
    fun aSegmentStormNeverReachesTheLadder() {
        playBuffered()
        val builds = f.sources.builds.size
        writerFails(app.thebox.core.playback.timeshift.SegmentStormException(6, 60_000))
        assertTrue(writer.calls.contains("restart:WRITER_RESTART"))
        assertEquals("reader untouched", builds, f.sources.builds.size)
        assertTrue(f.ladder.seen.isEmpty())
        f.advance(30_000)
        writerFails(app.thebox.core.playback.timeshift.SegmentStormException(6, 60_000))
        assertTrue("the old recorder ended", writers.writers[0].calls.contains("stop:SESSION_END"))
        assertEquals("the relaunch is buffered again", 2, writers.writers.size)
        assertTrue("never L25/L27", f.ladder.seen.isEmpty())
    }

    /** S3 (factory): a discard while a grant is outstanding leaves the ring alone; abandoning the grant discards. */
    @Test
    fun aDiscardNeverClosesTheRingUnderAnOutstandingGrant() {
        val prepared = runBlocking { factory.prepare(f.settings.current, 1) }
        val grant = (prepared as app.thebox.player.internal.TimeshiftPrepared.Granted).grant
        factory.discardIfIdle()
        assertFalse("granted, not started: not discarded", ring.closed)
        factory.abandon(grant, discardFile = true)
        assertTrue("abandoned: discarded", ring.closed)
    }

    /** S3 (controller): a reader's SESSION_END on the current generation is no longer "stale": the cycle relaunches. */
    @Test
    fun aRingClosedUnderTheCurrentCycleRelaunchesIt() {
        playBuffered()
        val tunes = f.events.events.count { it.type == PlaybackEventType.TUNE }
        readerError(RingEndException(RecordStop.SESSION_END))
        assertEquals(tunes + 1, f.events.events.count { it.type == PlaybackEventType.TUNE })
        assertTrue(f.events.events.any { it.code == "ring_closed" })
    }

    /** S4: a ring file unlinked under us (STORAGE_EVICTED) is rebuilt by the next prepare, not reused RAM-only. */
    @Test
    fun aPrepareAfterTheFileWasEvictedRebuildsTheStorage() {
        tune()
        assertEquals(1, ringFactory.creates)
        posix.links = 0
        c.tune(TuneTarget.Channel(CH2))
        f.idle()
        assertEquals("rebuilt", 2, ringFactory.creates)
        posix.links = 1
    }

    /** S5: a pause while resolving, then the session starts: the writer is paused and no release job runs. */
    @Test
    fun aPauseWhileResolvingPausesTheRecorderAndArmsNoReleaseJob() {
        c.tune(TuneTarget.Channel(CH1))
        c.pause()
        f.idle()
        assertTrue("the recorder is paused: ${writer.calls}", writer.calls.contains("pause"))
        f.advance(PlaybackControllerImpl.LIVE_PAUSE_RELEASE_MS + 1_000)
        val st = c.state.value
        assertFalse("no M2 release: $st", st is PlaybackState.Stopped)
        assertFalse("the recorder keeps its connection", f.journal.entries.contains("endSession:$wid"))
    }

    /** UX-B2: a LocalGap the archive side never acts on is skipped locally after the timeout. */
    @Test
    fun aLocalGapWithNoFollowUpIsSkippedAfterTheTimeout() {
        f.provider.catchup = Catchup(CatchupMode.DEFAULT, days = 3)
        playBuffered()
        goBehind()
        val builds = f.sources.builds.size
        val next = ring.fakeIndex.offsetAt(20)
        readerError(RingBoundaryException(next, SegmentReason.GAP, gapMs = 5_000, gapStartMonoMs = 135_000))
        assertTrue(f.notices.any { it is PlaybackNotice.LocalGap })
        assertEquals(builds, f.sources.builds.size)
        f.advance(TimeshiftConstants.GAP_HANDOFF_TIMEOUT_MS + 100)
        assertEquals(next, f.sources.last.ring!!.startOffset)
        assertTrue(f.notices.contains(PlaybackNotice.GapSkipped(5_000, stream = false)))
        assertFalse("nothing pending any more", c.skipGapLocal())
    }

    /** UX-B2: the archive side's fallback skips at once. */
    @Test
    fun skipGapLocalSkipsAPendingHandOff() {
        f.provider.catchup = Catchup(CatchupMode.DEFAULT, days = 3)
        playBuffered()
        goBehind()
        assertFalse("nothing pending", c.skipGapLocal())
        val next = ring.fakeIndex.offsetAt(20)
        readerError(RingBoundaryException(next, SegmentReason.GAP, gapMs = 40_000, gapStartMonoMs = 135_000))
        assertTrue(c.skipGapLocal())
        f.idle()
        assertEquals(next, f.sources.last.ring!!.startOffset)
        assertTrue(f.notices.contains(PlaybackNotice.GapSkipped(40_000, stream = false)))
    }

    /** UX-S1: the snapshot carries the stable window, the hold limit and the cycle id. */
    @Test
    fun theLocalBufferCarriesWindowHoldAndCycle() {
        policy.hold = 600_000L
        policy.capacity = 4L shl 30 // > 30 min at the sizing rate: the window is the length
        playBuffered()
        val b = c.localBuffer.value!!
        assertEquals(600_000L, b.holdMs)
        assertTrue(b.cycleId > 0)
        assertEquals("30 min at the sizing rate (stable, never the measured rate)", 1_800_000L, b.windowMs)
        writerTransport.telemetry.bitrateBps = 16_000_000L
        f.advance(1_500)
        assertEquals(1_800_000L, c.localBuffer.value!!.windowMs)
        c.tune(TuneTarget.Channel(CH2))
        f.idle()
        assertNotEquals(b.cycleId, c.localBuffer.value!!.cycleId)
    }

    /** B1: the hook passes the cause and "first of this loss" straight to the writer's cap. */
    @Test
    fun theHookPassesTheCauseToTheWriter() {
        playBuffered()
        val hooks = writerTransport.last.hooks
        hooks.reconnectAllowed(app.thebox.core.playback.timeshift.ReconnectCause.WATCHDOG, true)
        hooks.reconnectAllowed(app.thebox.core.playback.timeshift.ReconnectCause.REFUSED, false)
        assertEquals(
            listOf(app.thebox.core.playback.timeshift.ReconnectCause.WATCHDOG to true, app.thebox.core.playback.timeshift.ReconnectCause.REFUSED to false),
            writer.reconnectCauses,
        )
    }
}
