package app.thebox.player.controller

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import app.thebox.core.playback.FailureSignal
import app.thebox.core.playback.LadderDecision
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.PlaybackWarning
import app.thebox.core.playback.StallKind
import app.thebox.core.playback.StallVerdict
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.VideoDecoderMode
import app.thebox.core.playback.XtreamOutput
import app.thebox.player.api.BufferingCause
import app.thebox.player.api.PlaybackNotice
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.TuneTarget
import app.thebox.player.controller.fakes.CH1
import app.thebox.player.controller.fakes.ControllerFixture
import app.thebox.player.internal.TsCompat
import app.thebox.player.datasource.FailureInfo
import app.thebox.player.datasource.LiveFailureKind
import app.thebox.player.datasource.LiveStreamException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §5.3 WP-D "Ladder execution": each [LadderDecision] has the right side effects (session end before re-open, learned
 * values persisted only on first media, notices emitted once, waiting states of §4.3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LadderExecutionTest {
    private val f = ControllerFixture()

    @After fun tearDown() = f.close()

    private fun error() = PlaybackException("e", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

    /** A connection reset under Media3's HTTP source: classified Network(RESET), a transient signal. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun resetError(): PlaybackException = androidx.media3.exoplayer.ExoPlaybackException.createForSource(
        androidx.media3.datasource.HttpDataSource.HttpDataSourceException(
            java.io.IOException("reset"),
            androidx.media3.datasource.DataSpec(android.net.Uri.parse("http://panel.example:8080/x.m3u8")),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            androidx.media3.datasource.HttpDataSource.HttpDataSourceException.TYPE_READ,
        ),
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    )

    private fun tuneAndFail(decision: LadderDecision, playFirst: Boolean = false, transient: Boolean = false) {
        var used = false
        f.ladder.script = { _, _ -> if (!used) { used = true; decision } else LadderDecision.Fail(PlaybackError(PlaybackErrorCode.INTERNAL)) }
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        if (playFirst) {
            f.play()
            f.advance(1_000)
        }
        f.exo.emitError(if (transient) resetError() else error())
        f.idle()
    }

    @Test
    fun retrySameEndsTheSessionWaitsAndReusesTheResolvedStream() {
        tuneAndFail(LadderDecision.RetrySame(1_500))
        val first = f.sources.builds.first().ctx.tuneId
        assertTrue(f.transport.ended.contains(first))
        val waiting = f.state as PlaybackState.Connecting
        assertEquals(2, waiting.attempt)
        assertEquals(1, f.sources.builds.size)
        f.advance(1_400)
        assertEquals(1, f.sources.builds.size)
        f.advance(200)
        assertEquals(2, f.sources.builds.size)
        assertEquals(1, f.resolver.calls.size) // no re-resolve
        assertTrue(f.sources.last.ctx.tuneId > first)
        assertTrue(f.journal.indexOf("endSession:$first") < f.journal.indexOf("build:${f.sources.last.ctx.tuneId}:TS"))
    }

    @Test
    fun reResolveRotatesTheBaseAndPersistsItOnlyOnFirstMedia() {
        f.resolver.baseCount = 2
        tuneAndFail(LadderDecision.ReResolve(1_000, rotateBase = true))
        assertEquals(1, f.limiter.calls)
        f.advance(1_100)
        assertEquals(2, f.resolver.calls.size)
        assertEquals(1, f.resolver.calls[1].baseIndex)
        assertTrue(f.provider.savedBase.isEmpty())
        f.play()
        assertEquals(listOf(1), f.provider.savedBase)
        assertEquals(1, f.notices.count { it == PlaybackNotice.SwitchedToBackupServer(1) })
    }

    @Test
    fun reResolveWithAccountRefreshRefreshesFirst() {
        tuneAndFail(LadderDecision.ReResolve(0, refreshAccount = true))
        f.idle()
        assertEquals(1, f.provider.refreshes)
        assertEquals(2, f.resolver.calls.size)
    }

    @Test
    fun rateLimitedReResolveAsksTheLadderAgainWithoutTheReResolve() {
        f.limiter.allow = false
        f.ladder.script = { _, ctx -> if (ctx.reResolveAllowed) LadderDecision.ReResolve(0) else LadderDecision.RetrySame(1_500) }
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.exo.emitError(error())
        f.idle()
        assertEquals(2, f.ladder.seen.size)
        assertTrue(f.ladder.seen[0].ctx.reResolveAllowed)
        assertFalse(f.ladder.seen[1].ctx.reResolveAllowed)
        f.advance(1_600)
        assertEquals(1, f.resolver.calls.size)
        assertEquals(2, f.sources.builds.size)
    }

    @Test
    fun formatFlipReResolvesWithTheOtherOutputAndLearnsItOnFirstMedia() {
        tuneAndFail(LadderDecision.FlipXtreamOutput(XtreamOutput.HLS))
        f.idle()
        assertEquals(XtreamOutput.HLS, f.resolver.calls[1].output)
        assertTrue(f.provider.savedOutput.isEmpty())
        f.play()
        assertEquals(listOf<XtreamOutput?>(XtreamOutput.HLS), f.provider.savedOutput)
        assertEquals(1, f.notices.count { it == PlaybackNotice.SwitchedFormat(StreamFormat.HLS) })
    }

    @Test
    fun switchSourceTypeRebuildsAsHlsWithTheSameResolvedUrl() {
        tuneAndFail(LadderDecision.SwitchSourceType(StreamFormat.HLS))
        f.idle()
        assertEquals(1, f.resolver.calls.size)
        assertEquals(StreamFormat.HLS, f.sources.last.format)
        assertTrue(f.notices.none { it is PlaybackNotice.SwitchedFormat })
    }

    @Test
    fun reprepareWithTsCompatRebuildsMultiPmtWithoutSavingAtOnce() {
        // Follow-up 2026-10-14: the raise applies to the cycle at once; saving waits for healthy playback (below).
        tuneAndFail(LadderDecision.Reprepare(raiseTsCompat = true), playFirst = true)
        f.idle()
        assertEquals(TsCompat.MULTI_PMT, f.sources.last.compat)
        assertEquals(null, f.memory.rows[CH1]?.tsCompatLevel?.takeIf { it > 0 })
        val st = f.state as PlaybackState.Stalled
        assertEquals("Reprepare", st.step)
    }

    /** Plays CH1, then the ladder answers [signalOf]'s failure with an L27b raise and the raised session plays. */
    private fun raiseCompatAndPlay(fire: () -> Unit) {
        var used = false
        f.ladder.script = { _, _ -> if (!used) { used = true; LadderDecision.Reprepare(raiseTsCompat = true) } else LadderDecision.Fail(PlaybackError(PlaybackErrorCode.INTERNAL)) }
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle(); f.play(); f.advance(1_000)
        fire()
        f.idle()
        assertEquals(TsCompat.MULTI_PMT, f.sources.last.compat)
        f.play() // the raised session reaches first media
    }

    private fun savedLevel() = f.memory.rows[CH1]?.tsCompatLevel ?: 0

    @Test
    fun aCompatRaiseFromADecoderStallIsSavedAfterThirtySecondsHealthy() {
        raiseCompatAndPlay { f.engine.watchdog.onStall!!(StallVerdict(StallKind.DECODER_STUCK, "test")) }
        f.advance(29_000)
        assertEquals("not yet", 0, savedLevel())
        f.advance(1_500)
        assertEquals(1, savedLevel())
    }

    @Test
    fun aCompatRaiseIsNotSavedWhenTheSessionEndsFirst() {
        raiseCompatAndPlay { f.engine.watchdog.onStall!!(StallVerdict(StallKind.DECODER_STUCK, "test")) }
        f.advance(20_000)
        f.controller.stop(app.thebox.player.api.StopReason.USER)
        f.advance(30_000)
        assertEquals(0, savedLevel())
    }

    @Test
    fun aCompatRaiseFromPcrExhaustionIsNeverSaved() {
        raiseCompatAndPlay {
            val id = f.state.target!!.tuneId
            val e = LiveStreamException(
                LiveFailureKind.PCR_DISCONTINUITY,
                FailureInfo(null, "panel.example:8080", 1_000, 1, FailureInfo.Phase.READ, null, 5_000L, 0),
                androidx.media3.datasource.DataSpec(android.net.Uri.parse("thebox-session://$id")),
                null,
            )
            f.exo.emitError(androidx.media3.exoplayer.ExoPlaybackException.createForSource(e, e.reason))
        }
        assertTrue(f.ladder.seen.first().signal is FailureSignal.PcrDiscontinuity)
        f.advance(60_000)
        assertEquals(0, savedLevel())
    }

    @Test
    fun aRebufferAtTheRaisedLevelPreventsSaving() {
        raiseCompatAndPlay { f.engine.watchdog.onStall!!(StallVerdict(StallKind.DECODER_STUCK, "test")) }
        f.advance(10_000)
        f.exo.emitState(androidx.media3.common.Player.STATE_BUFFERING)
        f.exo.emitState(androidx.media3.common.Player.STATE_READY)
        f.advance(30_000)
        assertEquals(0, savedLevel())
    }

    @Test
    fun seekToLivePreparesAFreshSession() {
        tuneAndFail(LadderDecision.SeekToLiveAndPrepare, playFirst = true)
        f.idle()
        assertEquals(2, f.sources.builds.size)
        assertEquals(1, f.resolver.calls.size)
    }

    @Test
    fun softwareVideoSwitchesTheCodecPolicyAndWarns() {
        tuneAndFail(LadderDecision.SoftwareVideoAndReprepare, playFirst = true)
        f.idle()
        assertEquals(VideoDecoderMode.SOFTWARE_PREFERRED, f.playerFactory.bundle!!.codecPolicy.videoMode)
        assertEquals(1, f.notices.count { it == PlaybackNotice.VideoDecoderSwitchedToSoftware })
        f.play()
        assertEquals(PlaybackWarning.SOFTWARE_VIDEO, (f.state as PlaybackState.Playing).warning)
    }

    @Test
    fun audioFallbacksSetTheirRuntimeSwitches() {
        tuneAndFail(LadderDecision.DisablePassthroughAndReprepare("audio/eac3"), playFirst = true)
        f.idle()
        assertTrue("audio/eac3" in f.playerFactory.bundle!!.audioPolicy.deniedPassthrough)
        assertTrue(f.notices.contains(PlaybackNotice.PassthroughDisabled("audio/eac3")))

        f.ladder.script = { _, _ -> LadderDecision.SoftwareAudioAndReprepare("audio/mpeg-L2") }
        f.exo.emitError(error())
        f.idle()
        assertTrue("audio/mpeg-L2" in f.playerFactory.bundle!!.codecPolicy.softwareAudioMimes)
        assertTrue(f.notices.contains(PlaybackNotice.AudioDecoderSwitched(true, "audio/mpeg-L2")))

        f.ladder.script = { _, _ -> LadderDecision.StereoAndReprepare }
        f.exo.emitError(error())
        f.idle()
        assertTrue(f.playerFactory.bundle!!.audioPolicy.forceStereo)
    }

    @Test
    fun tunnelingOffIsAppliedToTheTrackSelector() {
        f.settings.update { it.copy(tunneling = true) }
        tuneAndFail(LadderDecision.DisableTunnelingAndReprepare, playFirst = true)
        f.idle()
        assertFalse(f.playerFactory.bundle!!.trackSelector.parameters.tunnelingEnabled)
        assertTrue(f.notices.contains(PlaybackNotice.TunnelingDisabled))
    }

    @Test
    fun dropAudioFromADetectorKeepsTheSessionAndWarns() {
        f.ladder.script = { _, _ -> LadderDecision.DropAudioAndReprepare(PlaybackWarning.NO_AUDIO) }
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.play()
        f.engine.watchdog.onStall!!(StallVerdict(StallKind.AUDIO_NOT_STARTED, "no audio"))
        f.idle()
        assertTrue(C.TRACK_TYPE_AUDIO in f.exo.params.disabledTrackTypes)
        assertEquals(1, f.sources.builds.size)
        assertEquals(PlaybackWarning.NO_AUDIO, (f.state as PlaybackState.Playing).warning)
        assertTrue(f.notices.contains(PlaybackNotice.Warning(PlaybackWarning.NO_AUDIO)))
        assertEquals(FailureSignal.Stall(StallKind.AUDIO_NOT_STARTED), f.ladder.seen.single().signal)
    }

    @Test
    fun userAgentFallbackReResolvesWithThePresetAndAnnouncesOnFirstMedia() {
        tuneAndFail(LadderDecision.UserAgentFallback)
        f.idle()
        assertEquals("VLC/3.0.20 LibVLC/3.0.20", f.resolver.calls[1].userAgent)
        assertTrue(f.notices.none { it is PlaybackNotice.UserAgentSwitched })
        f.play()
        assertEquals(1, f.notices.count { it is PlaybackNotice.UserAgentSwitched })
    }

    @Test
    fun transientReconnectAfterFirstMediaShowsProgressAndAnnouncesReconnected() {
        tuneAndFail(LadderDecision.ReResolve(1_000), playFirst = true, transient = true)
        assertTrue(f.ladder.seen.single().signal is FailureSignal.Network)
        val st = f.state as PlaybackState.Buffering
        assertEquals(BufferingCause.RECONNECTING, st.cause)
        assertEquals(1, f.notices.count { it is PlaybackNotice.Reconnecting })
        f.advance(1_100)
        f.play()
        assertEquals(1, f.notices.count { it == PlaybackNotice.Reconnected })
        assertTrue(f.state is PlaybackState.Playing)
    }

    @Test
    fun failShowsTheErrorStopsTheSessionAndOpensTheGate() {
        tuneAndFail(LadderDecision.Fail(PlaybackError(PlaybackErrorCode.CONNECTION_LIMIT)))
        val err = f.state as PlaybackState.Error
        assertEquals(PlaybackErrorCode.CONNECTION_LIMIT, err.error.code)
        assertEquals(1, err.error.detail.maxConnections)
        assertTrue(f.transport.ended.isNotEmpty())
        assertFalse(f.gate.isClosed)
        assertEquals("error:play.connection_limit", f.events.zaps.single().outcome)
    }

    @Test
    fun decisionAfterTheSwitchTimeoutBecomesAFail() {
        f.ladder.script = { _, _ -> LadderDecision.RetrySame(1_500) }
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.advance(15_000)
        // the timeout already failed the cycle; a late error is for no session and changes nothing
        assertEquals(PlaybackErrorCode.NO_DATA_TIMEOUT, (f.state as PlaybackState.Error).error.code)
    }

    /**
     * Device finding 2026-10-17 (Sony Bravia, HEVC, tunneling on): the decoder-stuck verdict at 14 s led to
     * DisableTunnelingAndReprepare, and the 15 s switch deadline failed the cycle 0.6 s into the repair. A pipeline
     * repair gets one fresh window; a second repair does not, so the cycle still ends within two windows.
     */
    @Test
    fun aPipelineRepairNearTheSwitchDeadlineGetsOneFreshWindow() {
        f.settings.update { it.copy(tunneling = true) }
        var n = 0
        f.ladder.script = { _, _ -> if (n++ == 0) LadderDecision.DisableTunnelingAndReprepare else LadderDecision.SoftwareVideoAndReprepare }
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.advance(14_000)
        f.engine.watchdog.onStall!!(StallVerdict(StallKind.DECODER_STUCK, "test"))
        f.idle()
        f.advance(2_000) // 16 s: past the original deadline, inside the repair's window
        assertFalse("the repair's session is connecting", f.state is PlaybackState.Error)
        assertTrue(f.state is PlaybackState.Connecting)
        f.advance(12_000) // 28 s: a second repair does not extend again
        f.engine.watchdog.onStall!!(StallVerdict(StallKind.DECODER_STUCK, "test"))
        f.idle()
        f.advance(1_500) // 29.5 s: the extended deadline
        f.idle()
        assertTrue("failed at the extended deadline: ${f.state}", f.state is PlaybackState.Error)
    }

    /** Device finding 2026-10-18: the TS guard's stuck-audio event becomes AudioTimestampsStuck; the drop disables the audio track. */
    @Test
    fun stuckAudioTimestampsFromTheGuardDropTheAudioTrack() {
        f.ladder.script = { s, _ -> if (s == FailureSignal.AudioTimestampsStuck) LadderDecision.DropAudioAndReprepare(app.thebox.core.playback.PlaybackWarning.AUDIO_TIMESTAMPS) else LadderDecision.Fail(PlaybackError(PlaybackErrorCode.INTERNAL)) }
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle(); f.play(); f.advance(5_000)
        f.sources.builds.last().ctx.onEvent(app.thebox.core.playback.PlaybackEventType.STALL, app.thebox.player.engine.MediaSourceBuilder.STUCK_AUDIO_CODE, "pid=257")
        f.idle()
        assertTrue(f.ladder.seen.any { it.signal == FailureSignal.AudioTimestampsStuck })
        // The live player keeps playing; the audio track is disabled in place and the warning names the cause.
        assertTrue("audio disabled on the player", f.exo.params.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_AUDIO))
        assertEquals(app.thebox.core.playback.PlaybackWarning.AUDIO_TIMESTAMPS, (f.state as PlaybackState.Playing).warning)
    }

    /** L33r: the repair re-prepares with the session context asking the TS guard to regenerate audio timestamps. */
    @Test
    fun repairAudioTimestampsRestartsWithTheGuardRepairing() {
        f.ladder.script = { s, _ -> if (s == FailureSignal.AudioTimestampsStuck) LadderDecision.RepairAudioTimestampsAndReprepare else LadderDecision.Fail(PlaybackError(PlaybackErrorCode.INTERNAL)) }
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle(); f.play(); f.advance(5_000)
        assertFalse(f.sources.builds.last().ctx.repairAudioTimestamps)
        f.sources.builds.last().ctx.onEvent(app.thebox.core.playback.PlaybackEventType.STALL, app.thebox.player.engine.MediaSourceBuilder.STUCK_AUDIO_CODE, "pid=257")
        f.idle()
        assertTrue("a new session with the repair on", f.sources.builds.last().ctx.repairAudioTimestamps)
        assertEquals("RepairAudioTimestampsAndReprepare", (f.state as PlaybackState.Stalled).step)
    }

    @Test
    fun tunnelingOffIsRememberedForTheChannelAndAppliedOnTheNextTune() {
        f.settings.update { it.copy(tunneling = true) }
        tuneAndFail(LadderDecision.DisableTunnelingAndReprepare)
        assertEquals(true, f.memory.rows[CH1]?.tunnelingOff)
        f.controller.stop(app.thebox.player.api.StopReason.USER)
        f.idle()
        f.ladder.script = { _, _ -> LadderDecision.Fail(PlaybackError(PlaybackErrorCode.INTERNAL)) }
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        assertFalse("the remembered channel tunes untunneled", f.playerFactory.bundle!!.trackSelector.parameters.tunnelingEnabled)
    }

    @Test
    fun ladderContextCarriesCycleFacts() {
        tuneAndFail(LadderDecision.Fail(PlaybackError(PlaybackErrorCode.INTERNAL)))
        val ctx = f.ladder.seen.single().ctx
        assertTrue(ctx.isXtream)
        assertTrue(ctx.isLive)
        assertFalse(ctx.firstFrameRendered)
        assertEquals(5, ctx.reconnectAttemptsSetting)
        assertTrue(ctx.uaFallbackAvailable)
        assertEquals(app.thebox.core.playback.AccountGate.ACTIVE, ctx.accountStatus)
        assertEquals(1, ctx.accountMax)
        assertEquals(StreamFormat.TS, ctx.format)
        assertTrue(ctx.ffmpegSupportsMime("audio/mpeg-L2"))
    }

    @Test
    fun refreshedTerminalAccountFailsBeforeAnotherOpen() {
        // Integration 2026-10-13 (device I-17): the account refreshed by an L3 re-resolve is EXPIRED, so G1 fails the
        // cycle before the panel is asked again.
        f.ladder.script = { _, ctx ->
            if (ctx.accountStatus == app.thebox.core.playback.AccountGate.EXPIRED) LadderDecision.Fail(PlaybackError(PlaybackErrorCode.ACCOUNT_EXPIRED))
            else LadderDecision.ReResolve(0, refreshAccount = true)
        }
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.provider.account = app.thebox.player.controller.fakes.FakeProvider.account(app.thebox.core.xtream.AccountStatus.EXPIRED)
        f.exo.emitError(error())
        f.idle()
        assertEquals(1, f.provider.refreshes)
        assertEquals(1, f.sources.builds.size)
        assertEquals(PlaybackErrorCode.ACCOUNT_EXPIRED, (f.state as PlaybackState.Error).error.code)
    }
}
