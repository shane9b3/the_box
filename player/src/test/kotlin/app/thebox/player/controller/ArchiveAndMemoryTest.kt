package app.thebox.player.controller

import androidx.media3.common.C
import app.thebox.core.catchup.ArchiveRequest
import app.thebox.core.model.CatchupMode
import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.BufferMode
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.VideoDecoderMode
import app.thebox.player.api.ArchiveTune
import app.thebox.player.api.ChannelPlaybackMemory
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.TuneTarget
import app.thebox.player.controller.fakes.CH1
import app.thebox.player.controller.fakes.CH2
import app.thebox.player.controller.fakes.ControllerFixture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** §3.14 archive seam and §3.12 per-channel memory wiring. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArchiveAndMemoryTest {
    private val f = ControllerFixture()

    @After fun tearDown() = f.close()

    private val request = ArchiveRequest(
        url = "http://panel.example:8080/timeshift/{{u}}/{{p}}/60/2026-10-13:20-00/1.ts",
        headers = emptyMap(),
        mode = CatchupMode.entries.first(),
        variant = "xc",
        requestStart = Instant.parse("2026-10-13T18:00:00Z"),
        requestEnd = null,
        nativeSeekRange = null,
    )

    @Test
    fun tuneArchiveExpandsCredentialsLastAndPlaysTheRequest() {
        f.controller.tuneArchive(ArchiveTune(CH1, request, startOffsetMs = 90_000))
        f.idle()
        assertEquals(listOf(request), f.resolver.archiveCalls)
        assertTrue(f.resolver.calls.isEmpty())
        assertEquals(request, f.sources.last.archive)
        assertEquals(StreamFormat.HLS, f.sources.last.format)
        assertEquals(listOf(90_000L), f.exo.seeks)
        assertEquals(BufferMode.MEDIUM, f.bufferFsm.modes.last())
        val pos = f.controller.position()
        assertEquals(request.requestStart.toEpochMilli(), pos.archiveStartUtcMs)
        assertTrue(pos.isSeekable)
        assertTrue((f.state as PlaybackState.Connecting).target.isArchive)

        f.controller.seekTo(120_000)
        assertEquals(120_000L, f.exo.seeks.last())
    }

    @Test
    fun archivePauseIsARealPause() {
        f.controller.tuneArchive(ArchiveTune(CH1, request))
        f.idle()
        f.play()
        f.controller.pause()
        f.advance(20_000)
        assertEquals(true, (f.state as PlaybackState.Playing).paused)
        f.controller.resume()
        assertTrue(f.exo.playWhenReady)
        assertEquals(1, f.sources.builds.size)
    }

    @Test
    fun seekIsANoOpOnLive() {
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.controller.seekTo(10_000)
        assertTrue(f.exo.seeks.isEmpty())
    }

    @Test
    fun bufferModeNoneFromSettingsAndFromAChannelOverride() {
        // Follow-up 2026-10-14: BufferMode.NONE is a manual mode like the others; archive keeps MEDIUM.
        f.settings.update { it.copy(bufferMode = BufferMode.NONE) }
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        assertEquals(BufferMode.NONE, f.bufferFsm.modes.last())
        f.controller.tuneArchive(ArchiveTune(CH1, request))
        f.idle()
        assertEquals(BufferMode.MEDIUM, f.bufferFsm.modes.last())

        f.settings.update { it.copy(bufferMode = BufferMode.AUTO) }
        f.memory.rows[CH2] = ChannelPlaybackMemory(bufferMode = BufferMode.NONE)
        f.controller.tune(TuneTarget.Channel(CH2))
        f.idle()
        assertEquals(BufferMode.NONE, f.bufferFsm.modes.last())
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        assertEquals(BufferMode.AUTO, f.bufferFsm.modes.last())
    }

    @Test
    fun memoryIsAppliedAtTune() {
        f.memory.rows[CH1] = ChannelPlaybackMemory(
            audioLanguage = "de", subtitleLanguage = "", aspect = AspectMode.ZOOM, videoDecoder = VideoDecoderMode.SOFTWARE_PREFERRED,
            bufferMode = BufferMode.LARGE, fpsMilli = 50_000,
        )
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        assertEquals("de", f.exo.params.preferredAudioLanguages.first())
        assertTrue(C.TRACK_TYPE_TEXT in f.exo.params.disabledTrackTypes)
        assertEquals(AspectMode.ZOOM, f.controller.video.value.aspectMode)
        assertEquals(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING, f.exo.videoScalingMode)
        assertEquals(VideoDecoderMode.SOFTWARE_PREFERRED, f.playerFactory.bundle!!.codecPolicy.videoMode)
        assertEquals(BufferMode.LARGE, f.bufferFsm.modes.last())
        assertEquals(listOf<Int?>(50_000), f.engine.displayMode.tunes)
    }

    @Test
    fun userChoicesAndLearnedFactsAreRemembered() {
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.play()
        f.controller.setAspectMode(AspectMode.RATIO_4_3)
        f.controller.selectTextTrack(null)
        f.engine.fps.value = 25f
        f.idle()
        val row = f.memory.rows.getValue(CH1)
        assertEquals(AspectMode.RATIO_4_3, row.aspect)
        assertEquals("", row.subtitleLanguage)
        assertEquals(25_000, row.fpsMilli)
        assertEquals(25f, f.controller.video.value.fps)
    }

    @Test
    fun hdrFormatIsLearnedAndBecomesAPosterInMini() {
        val hdr = androidx.media3.common.Format.Builder().setSampleMimeType(androidx.media3.common.MimeTypes.VIDEO_H265).setWidth(3840).setHeight(2160)
            .setColorInfo(androidx.media3.common.ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).setColorSpace(C.COLOR_SPACE_BT2020).build())
            .build()
        f.controller.setViewport(app.thebox.core.playback.Viewport.MINI)
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.exo.emitTracks(app.thebox.player.controller.fakes.FakeExoPlayer.avTracks(hdr))
        f.idle()
        assertEquals(app.thebox.core.playback.HdrKind.HDR10, f.controller.video.value.hdr)
        assertTrue(f.controller.video.value.posterMode)
        assertTrue(C.TRACK_TYPE_VIDEO in f.exo.params.disabledTrackTypes)
        assertEquals(true, f.memory.rows.getValue(CH1).knownHdr)
    }

    @Test
    fun decoderOverrideReprepares() {
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.play()
        f.controller.setVideoDecoderOverride(VideoDecoderMode.HARDWARE_ONLY)
        f.idle()
        assertEquals(2, f.sources.builds.size)
        assertEquals(1, f.resolver.calls.size)
        assertEquals(VideoDecoderMode.HARDWARE_ONLY, f.playerFactory.bundle!!.codecPolicy.videoMode)
        assertEquals(VideoDecoderMode.HARDWARE_ONLY, f.memory.rows.getValue(CH1).videoDecoder)
    }
}
