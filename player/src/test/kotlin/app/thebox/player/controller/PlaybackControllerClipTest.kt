package app.thebox.player.controller

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import app.thebox.core.playback.BufferMode
import app.thebox.core.playback.LadderDecision
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.StallKind
import app.thebox.core.playback.StallVerdict
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.Viewport
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.ClipFormat
import app.thebox.player.api.ArchiveTune
import app.thebox.player.api.ChannelPlaybackMemory
import app.thebox.player.api.ClipTune
import app.thebox.player.api.PlaybackNotice
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.StopReason
import app.thebox.player.api.TuneTarget
import app.thebox.player.controller.fakes.CH1
import app.thebox.player.controller.fakes.CH2
import app.thebox.player.controller.fakes.ControllerFixture
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
import java.io.IOException

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §6.5 controller table, §10.2 `PlaybackControllerClipTest`, A-B1, A-S1, R-S1)
 * Every row of the `Spec.Clip` table: ENDED, a long pause, rebuffer, no repair ladder (IO twice then fail, no learning, no
 * failover), 403 → `CLIP_EXPIRED`, a stall verdict, the lifecycle stop, display mode and frame rate untouched, the broker
 * key and the lease, `previousIdentity`, `retry()` / `resume()` never restarting a clip, and yielding to any other tune.
 * Live and archive keep their own tests (unchanged).
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackControllerClipTest {
    private val f = ControllerFixture()
    private val c get() = f.controller

    @After fun tearDown() = f.close()

    private val hls = ClipTune(
        url = "https://manifest.example.net/manifest/v1/hls/v4/clear/1/abc/6s/master.m3u8?fastly_token=FAKETOKEN0123456789",
        format = ClipFormat.HLS,
        captionsUrl = "https://cdn.example.net/text/text.vtt?fastly_token=FAKETOKENvtt",
        title = "Vilardi strikes first",
    )
    private val mp4 = ClipTune("https://cdn.example.net/pmp4/main.mp4?fastly_token=FAKETOKEN0123456789", ClipFormat.MP4, null, "Game recap", startMs = 31_000)

    private fun clip(tune: ClipTune = hls): Long {
        val id = c.playClip(tune)
        f.idle()
        return id
    }

    private fun spec(url: String = "https://manifest.example.net/seg1.ts") = DataSpec(Uri.parse(url))

    private fun http(status: Int): PlaybackException = ExoPlaybackException.createForSource(
        HttpDataSource.InvalidResponseCodeException(status, null, null, emptyMap(), spec(), ByteArray(0)),
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    )

    private fun reset(): PlaybackException = ExoPlaybackException.createForSource(
        HttpDataSource.HttpDataSourceException(IOException("reset"), spec(), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_READ),
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    )

    private fun fail(e: PlaybackException) {
        f.exo.emitError(e)
        f.idle()
    }

    private fun target() = f.state.target!!

    // ------------------------------------------------------------------ start, resolve, broker, switches

    @Test
    fun aClipIsALocalStreamOnItsOwnBrokerKeyWithArchiveSwitches() {
        f.settings.update { it.copy(tunneling = true) }
        val id = clip()
        assertTrue("no resolver for a clip", f.resolver.calls.isEmpty() && f.resolver.archiveCalls.isEmpty())
        val build = f.sources.last
        assertEquals("clip:manifest.example.net", build.ctx.resolved.brokerKey)
        assertNull(build.ctx.resolved.identity)
        assertNull(build.ctx.resolved.xtream)
        assertEquals(hls.url, build.ctx.resolved.url)
        assertTrue("default UA only (the transport adds it)", build.ctx.resolved.headers.isEmpty())
        assertEquals(StreamFormat.HLS, build.format)
        assertNull(build.archive)
        assertEquals(hls.captionsUrl, build.clip!!.captionsUrl)
        assertFalse("ClipMedia never prints the signed address", build.clip.toString().contains("FAKETOKEN"))
        assertEquals("clip:manifest.example.net" to SportsConstants.HUB_CLIP_MAX_CONNECTIONS, f.brokers.configured.last())
        assertEquals("the lease is the clip key's, not the provider's", "clip:manifest.example.net", build.ctx.broker.key)
        assertEquals(BufferMode.MEDIUM, f.bufferFsm.modes.last())
        assertFalse("never tunnelled", f.playerFactory.bundle!!.trackSelector.parameters.tunnelingEnabled)
        assertEquals(listOf(false), f.engine.sessionIsLive)
        val t = target()
        assertTrue(t.isClip)
        assertFalse("ArchivePlayback never takes a clip as catch-up", t.isArchive)
        assertNull(t.identity)
        assertEquals("Vilardi strikes first", t.title)
        assertEquals(id, t.tuneId)
        assertTrue(f.exo.playWhenReady)
        assertTrue(f.events.events.any { it.type == PlaybackEventType.TUNE && it.code == "clip" && it.detail == "clip:manifest.example.net" })
        assertNull("no time-shift for a clip", c.localBuffer.value)
        assertNull("a clip's signed address is never handed off", c.peekExternalHandOff())
    }

    @Test
    fun anMp4ClipIsProgressiveStartsAtItsOffsetAndSeeks() {
        clip(mp4)
        assertEquals(StreamFormat.PROGRESSIVE_OTHER, f.sources.last.format)
        assertEquals(listOf(31_000L), f.exo.seeks)
        f.play()
        assertTrue(c.position().isSeekable)
        assertNull(c.position().archiveStartUtcMs)
        c.seekTo(41_000)
        assertEquals(41_000L, f.exo.seeks.last())
    }

    // ------------------------------------------------------------------ ENDED, pause, rebuffer

    @Test
    fun theEndOfAClipIsAnEndNotADeadLiveStream() {
        clip()
        f.play()
        val builds = f.sources.builds.size
        f.exo.emitState(Player.STATE_ENDED)
        f.idle()
        val st = f.state as PlaybackState.Playing
        assertTrue(st.ended && st.paused && st.target.isClip)
        assertTrue("no ladder", f.ladder.seen.isEmpty())
        assertEquals(builds, f.sources.builds.size)
    }

    @Test
    fun aLongPauseIsARealPauseAndResumesInPlace() {
        clip()
        f.play()
        val builds = f.sources.builds.size
        c.pause()
        f.advance(PlaybackControllerImpl.LIVE_PAUSE_RELEASE_MS + 5_000)
        val paused = f.state as PlaybackState.Playing
        assertTrue(paused.paused)
        assertEquals("the connection is not released", builds, f.sources.builds.size)
        assertTrue(f.transport.ended.isEmpty())
        c.resume()
        f.idle()
        assertFalse((f.state as PlaybackState.Playing).paused)
        assertTrue(f.exo.playWhenReady)
        assertEquals("no restart at 0:00", builds, f.sources.builds.size)
        assertFalse(f.notices.contains(PlaybackNotice.LiveResumed))
    }

    @Test
    fun aRebufferNeitherRaisesTheBufferNorNotifies() {
        clip()
        f.play()
        f.advance(PlaybackControllerImpl.SEEK_GRACE_MS + 1_000)
        f.exo.emitState(Player.STATE_BUFFERING)
        f.idle()
        assertTrue(f.state is PlaybackState.Buffering)
        assertEquals(0, f.bufferFsm.level)
        assertTrue(f.notices.none { it is PlaybackNotice.BufferIncreased })
    }

    // ------------------------------------------------------------------ no ladder

    @Test
    fun ioTwiceRestartsAtThePlayheadThenFailsWithoutTheLadder() {
        f.settings.update { it.copy(tunneling = true, failoverToBackup = true) }
        f.ladder.script = { _, _ -> LadderDecision.TryBackupSource }
        clip()
        f.play()
        f.exo.position = 12_000
        repeat(SportsConstants.HUB_CLIP_RESTARTS) { n ->
            fail(reset())
            assertEquals("restart ${n + 1}", 2 + n, f.sources.builds.size)
            assertEquals(12_000L, f.exo.seeks.last())
            assertTrue(f.sources.last.ctx.resolved.brokerKey.startsWith("clip:"))
            f.play()
        }
        fail(reset())
        val err = f.state as PlaybackState.Error
        assertTrue(err.target.isClip)
        assertEquals(PlaybackErrorCode.NO_DATA_TIMEOUT, err.error.code)
        assertEquals(1 + SportsConstants.HUB_CLIP_RESTARTS, f.sources.builds.size)
        assertTrue("never the ladder: no tunnelling, decoder, UA, re-resolve or backup step", f.ladder.seen.isEmpty())
        assertTrue("no backup channel tuned", f.resolver.calls.isEmpty())
        assertTrue("nothing learned", f.memory.rows.isEmpty())
        assertTrue(f.notices.none { it is PlaybackNotice.TunnelingDisabled || it is PlaybackNotice.Reconnecting || it is PlaybackNotice.UserAgentSwitched })
        assertEquals(listOf(false, false, false), f.engine.sessionIsLive)
    }

    @Test
    fun a5xxRestartsAndOther4xxFailWithoutNamingAChannel() {
        clip()
        f.play()
        fail(http(503))
        assertEquals(2, f.sources.builds.size)
        f.play()
        fail(http(404))
        val err = f.state as PlaybackState.Error
        assertEquals(PlaybackErrorCode.STREAM_INTERRUPTED, err.error.code)
        assertTrue(f.ladder.seen.isEmpty())
    }

    @Test
    fun a403Or410OnTheMediaIsClipExpiredAtOnce() {
        for (status in listOf(403, 410)) {
            clip()
            f.play()
            val builds = f.sources.builds.size
            fail(http(status))
            val err = f.state as PlaybackState.Error
            assertEquals(PlaybackErrorCode.CLIP_EXPIRED, err.error.code)
            assertEquals(listOf(app.thebox.core.playback.NextStep.RETRY), err.error.actions)
            assertEquals("no restart on an expired URL", builds, f.sources.builds.size)
        }
        assertTrue(f.ladder.seen.isEmpty())
    }

    @Test
    fun aStallVerdictTakesTheClipRestartRule() {
        clip()
        f.play()
        f.engine.watchdog.onStall!!(StallVerdict(StallKind.DECODER_STUCK, "x"))
        f.idle()
        assertEquals(2, f.sources.builds.size)
        assertTrue(f.ladder.seen.isEmpty())
        assertTrue(f.notices.none { it is PlaybackNotice.VideoDecoderSwitchedToSoftware })
        assertEquals("no channel memory for a clip", 0, f.memory.rows.size)
    }

    // ------------------------------------------------------------------ lifecycle, retry, resume (R-S1)

    @Test
    fun aLifecycleStopIsNotResumableAndAnExpiredReplayIsClipExpired() {
        val id = clip()
        f.play()
        c.stopForLifecycle(StopReason.BACKGROUND)
        f.idle()
        val st = f.state as PlaybackState.Stopped
        assertFalse(st.resumable)
        assertTrue(st.target!!.isClip)
        assertTrue("the clip's calls are cancelled and its lease released", id in f.transport.ended)
        val builds = f.sources.builds.size
        f.advance(60_000)
        c.resumeAfterLifecycle()
        f.idle()
        assertEquals("nothing replays after Home", builds, f.sources.builds.size)
        // Should anything play the same signed address again an hour later, the CDN's 403 is CLIP_EXPIRED.
        clip()
        fail(http(403))
        assertEquals(PlaybackErrorCode.CLIP_EXPIRED, (f.state as PlaybackState.Error).error.code)
    }

    @Test
    fun retryAndResumeNeverRestartAClipCycle() {
        clip()
        f.play()
        fail(http(403))
        val builds = f.sources.builds.size
        c.retry()
        f.idle()
        assertEquals("retry() after a clip error", builds, f.sources.builds.size)
        assertTrue(f.state is PlaybackState.Error)

        clip()
        f.play()
        c.stop(StopReason.USER)
        f.idle()
        val stopped = f.sources.builds.size
        c.retry()
        c.resume()
        f.idle()
        assertEquals("retry() / resume() after a clip stop", stopped, f.sources.builds.size)
        assertTrue(f.state is PlaybackState.Stopped)

        clip()
        f.play()
        c.stopForLifecycle(StopReason.STANDBY)
        f.idle()
        val standby = f.sources.builds.size
        c.resume()
        c.retry()
        f.idle()
        assertEquals("resume() after a lifecycle stop of a clip", standby, f.sources.builds.size)
    }

    @Test
    fun aRestartedClipIsStillOnlyRestartedByTheController() {
        // Sanity for the R-S1 rule: the IO restarts above are the controller's own and never replay through retry().
        clip()
        f.play()
        fail(reset())
        assertEquals(2, f.sources.builds.size)
        assertTrue(f.limiter.resets.isEmpty())
    }

    // ------------------------------------------------------------------ display mode, frame rate (A-B1.7)

    @Test
    fun aClipLeavesTheDisplayModeAndFrameRateAlone() {
        c.setViewport(Viewport.MINI)
        c.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.play()
        val dm = f.engine.displayMode
        val tunes = dm.tunes.size
        val settles = dm.settles
        val afr = f.engine.channelAfr.size
        val viewports = dm.viewports.toList()

        clip()
        c.setViewport(Viewport.FULL)
        f.engine.fps.value = 59.94f
        f.idle()
        f.play()
        assertEquals("no onTune", tunes, dm.tunes.size)
        assertEquals("no awaitSettled", settles, dm.settles)
        assertEquals("no setChannelAfr", afr, f.engine.channelAfr.size)
        assertEquals("FULL under a clip does not reach the display mode", viewports, dm.viewports)
        assertTrue("a clip's frame rate is not matched", dm.fpsCalls.isEmpty())
        assertEquals(0, dm.clears)

        // The channel after it gets its own onTune, and the viewport it missed.
        c.tune(TuneTarget.Channel(CH1))
        f.idle()
        assertEquals(tunes + 1, dm.tunes.size)
        assertEquals(Viewport.FULL, dm.viewports.last())
        assertEquals(afr + 1, f.engine.channelAfr.size)
    }

    // ------------------------------------------------------------------ ownership, previous channel, lease

    @Test
    fun anyOtherTuneEndsTheClipSessionFirst() {
        val id = clip()
        f.play()
        c.tune(TuneTarget.Channel(CH2))
        f.idle()
        val endAt = f.journal.indexOf("endSession:$id")
        val nextBuild = f.journal.entries.indexOfFirst { it.startsWith("build:") && it != "build:$id:HLS" }
        assertTrue("the clip's session ends before the next opens: ${f.journal}", endAt in 0 until nextBuild)
        val t = target()
        assertFalse(t.isClip)
        assertEquals(CH2, t.identity)
        assertEquals("p:1", f.sources.last.ctx.resolved.brokerKey)
        assertEquals(listOf(false, true), f.engine.sessionIsLive)

        val id2 = clip()
        f.play()
        c.tuneArchive(
            ArchiveTune(
                CH1,
                app.thebox.core.catchup.ArchiveRequest(
                    "http://panel.example:8080/timeshift/{{u}}/{{p}}/60/2026-10-13:20-00/1.ts", emptyMap(),
                    app.thebox.core.model.CatchupMode.entries.first(), "xc", java.time.Instant.parse("2026-10-13T18:00:00Z"), null, null,
                ),
            ),
        )
        f.idle()
        assertTrue(id2 in f.transport.ended)
        assertTrue(target().isArchive)
        assertFalse(target().isClip)
    }

    @Test
    fun aZapFromElsewhereDuringAClipMovesFromTheChannelItReplaced() {
        val list = object : app.thebox.player.api.ZapList {
            val order = listOf(CH1, CH2, app.thebox.player.controller.fakes.CH3)
            override val id = "all"
            override suspend fun neighbour(from: app.thebox.core.refresh.ChannelIdentity, delta: Int) =
                order[Math.floorMod(order.indexOf(from) + delta, order.size)]
        }
        c.tune(TuneTarget.Channel(CH1), list)
        f.idle()
        f.play()
        clip()
        f.play()
        clip(mp4) // the next clip of the queue keeps the anchor
        f.play()
        c.zap(+1)
        f.idle()
        assertEquals(CH2, target().identity)
        assertFalse(target().isClip)
    }

    @Test
    fun theStopAfterAClipCancelsItsLease() {
        val id = clip()
        f.play()
        c.stop(StopReason.USER)
        f.idle()
        assertTrue(id in f.transport.ended)
        assertTrue((f.state as PlaybackState.Stopped).target!!.isClip)
    }

    @Test
    fun aClipNeverMovesThePreviousChannel() {
        c.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.play()
        f.advance(2_000)
        c.tune(TuneTarget.Channel(CH2))
        f.idle()
        f.play()
        f.advance(2_000)
        assertEquals(CH1, c.previous.value)

        clip()
        f.play()
        f.advance(5_000)
        assertEquals("starting a clip from CH2", CH1, c.previous.value)
        c.tune(TuneTarget.Channel(CH2)) // ClipSession's resume
        f.idle()
        f.play()
        f.advance(2_000)
        assertEquals("resuming CH2 after the clip", CH1, c.previous.value)
        assertTrue(c.previousChannel())
        f.idle()
        assertEquals(CH1, target().identity)
    }

    @Test
    fun theClipRuleTable() {
        fun a(s: app.thebox.core.playback.FailureSignal) = FailureClassifier.clipAction(s)
        val o = app.thebox.core.playback.LoadOrigin.HLS_SEGMENT
        assertEquals(ClipAction.Fail(PlaybackErrorCode.CLIP_EXPIRED), a(app.thebox.core.playback.FailureSignal.Http(403, o)))
        assertEquals(ClipAction.Fail(PlaybackErrorCode.CLIP_EXPIRED), a(app.thebox.core.playback.FailureSignal.Http(410, o)))
        for (st in listOf(500, 503, 408, 429)) assertEquals(ClipAction.Restart, a(app.thebox.core.playback.FailureSignal.Http(st, o)))
        assertEquals(ClipAction.Fail(PlaybackErrorCode.STREAM_INTERRUPTED), a(app.thebox.core.playback.FailureSignal.Http(404, o)))
        assertEquals(ClipAction.Restart, a(app.thebox.core.playback.FailureSignal.Network(app.thebox.core.playback.NetworkFailureKind.DNS, true)))
        assertEquals(ClipAction.Fail(PlaybackErrorCode.INTERNAL), a(app.thebox.core.playback.FailureSignal.Network(app.thebox.core.playback.NetworkFailureKind.CLEARTEXT, true)))
        for (k in StallKind.entries) assertEquals(k.name, ClipAction.Restart, a(app.thebox.core.playback.FailureSignal.Stall(k)))
        assertEquals(ClipAction.Restart, a(app.thebox.core.playback.FailureSignal.Unknown(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
        assertEquals(ClipAction.Fail(PlaybackErrorCode.NO_DATA_TIMEOUT), a(app.thebox.core.playback.FailureSignal.Unknown(PlaybackException.ERROR_CODE_DRM_UNSPECIFIED)))
        assertEquals(ClipAction.Fail(PlaybackErrorCode.CODEC_UNSUPPORTED), a(app.thebox.core.playback.FailureSignal.DecoderInit(true, "video/hevc", null)))
        assertEquals(ClipAction.Fail(PlaybackErrorCode.STREAM_UNDECODABLE), a(app.thebox.core.playback.FailureSignal.Parsing(manifest = true)))
    }

    // ------------------------------------------------------------------ live unchanged

    @Test
    fun aLiveTuneAfterAClipHasTheLadderAndChannelMemoryAgain() {
        f.memory.rows[CH1] = ChannelPlaybackMemory(bufferMode = BufferMode.LARGE)
        clip()
        f.play()
        f.ladder.script = { _, _ -> LadderDecision.Fail(PlaybackError(PlaybackErrorCode.CHANNEL_OFFLINE)) }
        c.tune(TuneTarget.Channel(CH1))
        f.idle()
        assertEquals(BufferMode.LARGE, f.bufferFsm.modes.last())
        f.play()
        fail(reset())
        assertEquals(1, f.ladder.seen.size)
        assertTrue(f.ladder.seen.single().ctx.isLive)
        assertEquals(PlaybackErrorCode.CHANNEL_OFFLINE, (f.state as PlaybackState.Error).error.code)
        assertNotNull(f.ladder.channels.lastOrNull())
    }
}
