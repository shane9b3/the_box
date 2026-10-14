package app.thebox.player.controller

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import app.thebox.core.playback.FailureSignal
import app.thebox.core.playback.LadderDecision
import app.thebox.core.playback.NextStep
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.ResolveOutcome
import app.thebox.core.playback.StreamFormat
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.player.api.ConnectPhase
import app.thebox.player.api.PlaybackNotice
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.StopReason
import app.thebox.player.api.TuneTarget
import app.thebox.player.api.ZapList
import app.thebox.player.controller.fakes.CH1
import app.thebox.player.controller.fakes.CH2
import app.thebox.player.controller.fakes.CH3
import app.thebox.player.controller.fakes.ControllerFixture
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §5.3 WP-D "Controller state machine": the rows of §3.1.2 with fakes of every seam. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackControllerStateTest {
    private val f = ControllerFixture()

    @After fun tearDown() = f.close()

    @Test
    fun successfulTuneWalksConnectingToPlayingAndCompletesTheZapRecord() {
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()

        val first = f.states[1]
        assertTrue(first is PlaybackState.Connecting && first.phase == ConnectPhase.RESOLVING)
        val st = f.state as PlaybackState.Connecting
        assertEquals(ConnectPhase.CONNECTING, st.phase)
        assertEquals(CH1, st.target.identity)
        assertEquals("Channel ch1", st.target.title)
        assertEquals(1, f.exo.prepares)
        assertTrue(f.exo.playWhenReady)
        assertEquals(listOf("p:1" to 1), f.brokers.configured)
        assertTrue(f.gate.isClosed)
        assertTrue(f.controller.keepScreenOn.value)

        f.telemetry().bytesTotal = 1000
        f.advance(500)
        assertEquals(ConnectPhase.BUFFERING_INITIAL, (f.state as PlaybackState.Connecting).phase)

        f.play()
        assertTrue(f.state is PlaybackState.Playing)
        val zap = f.events.zaps.single()
        assertEquals("ok", zap.outcome)
        assertNotNull(zap.firstFrameMs)
        assertNotNull(zap.readyMs)
        assertNotNull(zap.firstByteMs)
        assertEquals("ch1", zap.stableKey)

        f.advance(600)
        assertFalse("gate opens 500 ms after first media", f.gate.isClosed)
        assertTrue(f.memory.watched.isEmpty())
        f.advance(5_000)
        assertEquals(listOf(CH1), f.memory.watched)
    }

    @Test
    fun zapEndsTheOldSessionBeforeResolvingAndOpeningTheNewOne() {
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        val first = f.sources.last.ctx.tuneId
        f.controller.tune(TuneTarget.Channel(CH2))
        f.idle()
        val second = f.sources.last.ctx.tuneId
        val j = f.journal
        assertTrue(j.toString(), j.indexOf("endSession:$first") < j.indexOf("resolve:ch2"))
        assertTrue(j.indexOf("endSession:$first") < j.indexOf("build:$second:TS"))
        assertTrue(second > first)
        assertEquals("superseded", f.events.zaps.single().outcome)
    }

    @Test
    fun zapWhileResolvingCancelsTheFirstTuneWithoutOpeningIt() {
        val gate = CompletableDeferred<Unit>()
        f.resolver.gate = gate
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.resolver.gate = null
        f.controller.tune(TuneTarget.Channel(CH2))
        f.idle()
        gate.complete(Unit)
        f.idle()
        assertEquals(1, f.sources.builds.size)
        assertEquals(CH2, f.sources.last.ctx.resolved.identity)
        assertEquals(CH2, f.state.target?.identity)
    }

    @Test
    fun staleErrorFromAnEarlierSessionIsIgnored() {
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        val stale = PlaybackException("old", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        f.advance(50)
        f.controller.tune(TuneTarget.Channel(CH2))
        f.idle()
        f.exo.emitError(stale)
        f.idle()
        assertTrue(f.ladder.seen.isEmpty())
        assertTrue(f.state is PlaybackState.Connecting)
    }

    @Test
    fun resolveFailureGoesThroughTheLadderAsChannelMissing() {
        f.resolver.next = { ResolveOutcome.Failed(PlaybackErrorCode.CHANNEL_NOT_FOUND, "gone") }
        f.ladder.script = { _, _ -> LadderDecision.Fail(PlaybackError(PlaybackErrorCode.CHANNEL_NOT_FOUND)) }
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        assertEquals(FailureSignal.ChannelMissing, f.ladder.seen.single().signal)
        val err = f.state as PlaybackState.Error
        assertEquals(PlaybackErrorCode.CHANNEL_NOT_FOUND, err.error.code)
        assertFalse(f.gate.isClosed)
        assertFalse(f.controller.keepScreenOn.value)
    }

    @Test
    fun streamSwitchTimeoutWithoutFirstMediaFailsWithNoData() {
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.exo.emitState(Player.STATE_READY)
        f.idle()
        assertTrue(f.state is PlaybackState.Playing)
        f.advance(15_100)
        val err = f.state as PlaybackState.Error
        assertEquals(PlaybackErrorCode.NO_DATA_TIMEOUT, err.error.code)
        assertTrue(f.transport.ended.isNotEmpty())
    }

    @Test
    fun audioOnlyChannelReachesFirstMediaOnAudioPosition() {
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.play(app.thebox.player.controller.fakes.FakeExoPlayer.audioTracks())
        assertEquals("ok", f.events.zaps.single().outcome)
        assertEquals("audio", f.events.events.first { it.type == PlaybackEventType.FIRST_FRAME }.code)
        f.advance(20_000)
        assertTrue(f.state is PlaybackState.Playing)
        assertEquals(listOf(CH1), f.memory.watched)
    }

    @Test
    fun hiddenViewportDisablesVideoKeepsAudioAndUsesTheAudioMilestone() {
        f.controller.setViewport(app.thebox.core.playback.Viewport.HIDDEN)
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        assertTrue(C.TRACK_TYPE_VIDEO in f.exo.params.disabledTrackTypes)
        assertFalse(C.TRACK_TYPE_AUDIO in f.exo.params.disabledTrackTypes)
        f.exo.emitTracks(app.thebox.player.controller.fakes.FakeExoPlayer.avTracks())
        f.exo.emitState(Player.STATE_READY)
        f.exo.emitFirstFrame() // must not count: video is disabled
        f.idle()
        assertTrue(f.events.zaps.isEmpty())
        f.exo.emitAudioAdvancing()
        f.idle()
        assertEquals("ok", f.events.zaps.single().outcome)
        assertEquals(false to true, f.engine.watchdog.expectations) // video not expected, audio expected
        f.controller.setViewport(app.thebox.core.playback.Viewport.FULL)
        assertFalse(C.TRACK_TYPE_VIDEO in f.exo.params.disabledTrackTypes)
        assertEquals(1, f.exo.prepares) // no re-prepare, connection kept
    }

    @Test
    fun knownHdrChannelInMiniIsAPosterWithAudio() {
        f.memory.rows[CH1] = app.thebox.player.api.ChannelPlaybackMemory(knownHdr = true)
        f.controller.setViewport(app.thebox.core.playback.Viewport.MINI)
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        assertTrue(C.TRACK_TYPE_VIDEO in f.exo.params.disabledTrackTypes)
        assertTrue(f.controller.video.value.posterMode)
        f.controller.setViewport(app.thebox.core.playback.Viewport.FULL)
        assertFalse(C.TRACK_TYPE_VIDEO in f.exo.params.disabledTrackTypes)
        assertFalse(f.controller.video.value.posterMode)
    }

    @Test
    fun rebufferNotCausedByUsRaisesTheBufferProfile() {
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.play()
        f.advance(2_500)
        f.exo.emitState(Player.STATE_BUFFERING)
        f.idle()
        val st = f.state as PlaybackState.Buffering
        assertEquals(app.thebox.player.api.BufferingCause.REBUFFER, st.cause)
        assertTrue(f.notices.contains(PlaybackNotice.BufferIncreased(1)))
        assertEquals(1, f.playerFactory.bundle!!.loadControl.profile.level)
        f.exo.emitState(Player.STATE_READY)
        f.idle()
        assertTrue(f.state is PlaybackState.Playing)
    }

    @Test
    fun inSourceReconnectShowsBufferingReconnectingWithoutANotice() {
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.play()
        f.telemetry().inReconnect = true
        f.advance(600)
        val st = f.state as PlaybackState.Buffering
        assertEquals(app.thebox.player.api.BufferingCause.RECONNECTING, st.cause)
        assertTrue(f.notices.none { it is PlaybackNotice.Reconnecting })
    }

    @Test
    fun reconnectThatNeverStarvedThePlayerReturnsToPlaying() {
        // Integration 2026-10-13 (device I-3): the player stays READY through a transparent reconnect, so no READY
        // callback ends Buffering(RECONNECTING); the telemetry going quiet does.
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.play()
        f.telemetry().inReconnect = true
        f.advance(600)
        assertTrue(f.state is PlaybackState.Buffering)
        f.telemetry().inReconnect = false
        f.advance(600)
        assertTrue("state ${f.state}", f.state is PlaybackState.Playing)
    }

    @Test
    fun livePauseReleasesAfterTenSecondsAndResumeReResolves() {
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.play()
        f.controller.pause()
        assertEquals(true, (f.state as PlaybackState.Playing).paused)
        assertFalse(f.exo.playWhenReady)
        assertFalse(f.controller.keepScreenOn.value)
        f.advance(10_100)
        val stopped = f.state as PlaybackState.Stopped
        assertEquals(StopReason.LIVE_PAUSE_TIMEOUT, stopped.reason)
        assertTrue(stopped.resumable)
        assertEquals(1, f.transport.ended.size)
        val resolvesBefore = f.resolver.calls.size
        f.controller.resume()
        f.idle()
        assertEquals(resolvesBefore + 1, f.resolver.calls.size)
        assertTrue(f.notices.contains(PlaybackNotice.LiveResumed))
        assertTrue(f.state is PlaybackState.Connecting)
    }

    @Test
    fun userStopIsNotResumableAndClearsDisplayMode() {
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.controller.stop()
        val st = f.state as PlaybackState.Stopped
        assertEquals(StopReason.USER, st.reason)
        assertFalse(st.resumable)
        assertEquals(1, f.engine.displayMode.clears)
        assertFalse(f.gate.isClosed)
    }

    @Test
    fun zapDebounceTunesOnceAfterRepeatsAndShowsThePendingChannel() {
        val list = object : ZapList {
            val order = listOf(CH1, CH2, CH3)
            override val id = "all"
            override suspend fun neighbour(from: ChannelIdentity, delta: Int) = order[Math.floorMod(order.indexOf(from) + delta, order.size)]
        }
        f.controller.tune(TuneTarget.Channel(CH1), list)
        f.idle()
        f.play()
        f.advance(1_000)

        f.controller.zap(+1) // leading edge: immediate
        f.idle()
        assertEquals(CH2, f.state.target?.identity)
        val buildsAfterFirst = f.sources.builds.size

        f.controller.zap(+1, isRepeat = true)
        f.idle()
        assertEquals(CH3, f.controller.pendingZap.value)
        f.advance(100)
        f.controller.zap(+1, isRepeat = true)
        f.idle()
        assertEquals(CH1, f.controller.pendingZap.value)
        assertEquals(buildsAfterFirst, f.sources.builds.size) // still playing CH2, no second connection
        f.advance(400)
        assertNull(f.controller.pendingZap.value)
        assertEquals(CH1, f.state.target?.identity)
        assertEquals(buildsAfterFirst + 1, f.sources.builds.size)
    }

    @Test
    fun previousChannelNeedsOneSecondOfPlay() {
        assertFalse(f.controller.previousChannel())
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.play()
        f.advance(1_500)
        f.controller.tune(TuneTarget.Channel(CH2))
        f.idle()
        assertTrue(f.controller.previousChannel())
        f.idle()
        assertEquals(CH1, f.state.target?.identity)
    }

    @Test
    fun retryFromTheCardStartsAFreshCycleWithAFreshResolve() {
        f.ladder.script = { _, _ -> LadderDecision.Fail(PlaybackError(PlaybackErrorCode.CHANNEL_OFFLINE)) }
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.exo.emitError(PlaybackException("x", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        f.idle()
        val err = f.state as PlaybackState.Error
        // PREVIOUS_CHANNEL is offered only when there is one; TRY_BACKUP only with a backup.
        assertEquals(listOf(NextStep.RETRY), err.error.actions)
        assertEquals("panel.example:8080", err.error.detail.host)
        f.controller.retry()
        f.idle()
        assertEquals(2, f.resolver.calls.size)
        assertTrue(f.state is PlaybackState.Connecting)
        assertTrue(err.target.tuneId < f.state.target!!.tuneId)
    }

    @Test
    fun liveEndedGoesToTheLadder() {
        f.ladder.script = { _, _ -> LadderDecision.Reprepare() }
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.play()
        f.exo.emitState(Player.STATE_ENDED)
        f.idle()
        assertEquals(FailureSignal.LiveEnded, f.ladder.seen.single().signal)
        assertTrue(f.state is PlaybackState.Stalled)
    }

    @Test
    fun directUrlUsesTheHarnessConnectionLimitAndHint() {
        f.resolver.next = { ResolveOutcome.Ok(app.thebox.player.controller.fakes.stream(null, StreamFormat.HLS)) }
        f.controller.tune(TuneTarget.DirectUrl("http://h/x.m3u8", maxConnections = 2, formatHint = StreamFormat.HLS))
        f.idle()
        assertEquals("p:1" to 2, f.brokers.configured.single())
        assertEquals(StreamFormat.HLS, f.sources.last.format)
    }

    // ------------------------------------------------------------------ code review 2026-10-14

    private fun gatedList(gate: CompletableDeferred<Unit>, next: ChannelIdentity = CH2) = object : ZapList {
        override val id = "slow"
        override suspend fun neighbour(from: ChannelIdentity, delta: Int): ChannelIdentity { gate.await(); return next }
    }

    @Test
    fun stopDuringZapLookupStaysStopped() {
        // E-S1 (the reviewer's repro): Home right after CH± must not re-tune in the background.
        val gate = CompletableDeferred<Unit>()
        f.controller.tune(TuneTarget.Channel(CH1), gatedList(gate))
        f.idle(); f.play(); f.advance(1_000)
        f.controller.zap(+1) // neighbour() suspends
        f.idle()
        f.controller.stop(StopReason.BACKGROUND)
        f.idle()
        gate.complete(Unit)
        f.idle()
        assertTrue("state after stop + late zap: ${f.state}", f.state is PlaybackState.Stopped)
        assertEquals(1, f.resolver.calls.size)
        assertNull(f.controller.pendingZap.value)
    }

    @Test
    fun tuneDuringZapLookupWins() {
        val gate = CompletableDeferred<Unit>()
        f.controller.tune(TuneTarget.Channel(CH1), gatedList(gate))
        f.idle(); f.play(); f.advance(1_000)
        f.controller.zap(+1)
        f.idle()
        f.controller.tune(TuneTarget.Channel(CH3)) // OK on a guide channel
        f.idle()
        gate.complete(Unit)
        f.idle()
        assertEquals(CH3, f.state.target?.identity)
        assertEquals(listOf("ch1", "ch3"), f.resolver.calls.map { (it.target as app.thebox.player.internal.TuneTargetKey.Channel).identity.stableKey.value })
    }

    @Test
    fun theInitialViewportReachesTheDisplayModeController() {
        // E-S2: DisplayModeController starts at NONE; setViewport(FULL) is a no-op on the controller, so without the
        // push at engine creation AFR ALWAYS never evaluated.
        f.controller.setViewport(app.thebox.core.playback.Viewport.FULL)
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        assertEquals(listOf(app.thebox.core.playback.Viewport.FULL), f.engine.displayMode.viewports)
    }

    @Test
    fun directUrlsAreLimitedPerBrokerKey() {
        // N-S6: the re-resolve limiter key of a direct URL is its broker key, not one shared null bucket.
        f.ladder.script = { _, _ -> LadderDecision.ReResolve(0) }
        for (host in listOf("a.example", "b.example")) {
            f.resolver.next = { ResolveOutcome.Ok(app.thebox.player.controller.fakes.stream(null, brokerKey = "d:$host")) }
            f.controller.tune(TuneTarget.DirectUrl("http://$host/x.ts"))
            f.idle()
            f.exo.emitError(PlaybackException("x", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
            f.idle()
        }
        val keys = f.limiter.keys.map { it?.stableKey?.value }
        assertEquals(listOf("d:a.example", "d:b.example"), keys.distinct())
        f.resolver.next = { c -> ResolveOutcome.Ok(app.thebox.player.controller.fakes.stream((c.target as app.thebox.player.internal.TuneTargetKey.Channel).identity)) }
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.exo.emitError(PlaybackException("x", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        f.idle()
        assertEquals(CH1, f.limiter.keys.last())
    }

    @Test
    fun droppedHeadersAreLoggedByName() {
        // N-S1: HeaderPolicy dropped an invalid header; the event log records its name.
        f.resolver.next = { ResolveOutcome.Ok(app.thebox.player.controller.fakes.stream(CH1, droppedHeaders = listOf("X-Bad"))) }
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        val e = f.events.events.single { it.type == PlaybackEventType.RESOLVED && it.code == "headers_dropped" }
        assertEquals("X-Bad", e.detail)
    }

    @Test
    fun anUnsupportedSchemeIsUnsupportedSourceWithoutAnOpen() {
        // N-S3: the real MediaSourceBuilder throws before any transport; the controller fails at once.
        f.sources.failNext = app.thebox.player.engine.UnsupportedStreamFormatException(StreamFormat.TS, scheme = "rtmp")
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        val err = f.state as PlaybackState.Error
        assertEquals(PlaybackErrorCode.UNSUPPORTED_SOURCE, err.error.code)
        assertEquals("scheme rtmp", err.error.detail.signal)
        assertTrue(NextStep.OPEN_EXTERNAL in err.error.actions)
        assertTrue(f.sources.builds.isEmpty())
        assertTrue(f.ladder.seen.isEmpty())
    }

    @Test
    fun aBuilderBugIsInternalNotUnsupportedSource() {
        // Later E-4 (taken with N-S3): only UnsupportedStreamFormatException means "this source cannot be played".
        f.sources.failNext = IllegalStateException("bug")
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        assertEquals(PlaybackErrorCode.INTERNAL, (f.state as PlaybackState.Error).error.code)
    }

    @Test
    fun ladderWindowsAreKeyedByPlaylistAndStableKey() {
        // Follow-up 2026-10-14 (half of Later N-L2): the same stable key in two playlists is two channels.
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.controller.tune(TuneTarget.Channel(ChannelIdentity(2, CH1.stableKey)))
        f.idle()
        assertEquals(listOf<String?>("1/ch1", "2/ch1"), f.ladder.channels)
    }
}
