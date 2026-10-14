package app.thebox.player.engine

import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import app.thebox.core.playback.VideoDecoderMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList

/**
 * Selector ordering with decoders flagged hardware/software (review S14: `ShadowMediaCodecConfig` registers codecs
 * without flags, so these tests register their own) and the per-MIME software-audio gate.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoxMediaCodecSelectorTest {

    @Before fun setUp() {
        ShadowMediaCodecList.reset()
        MediaCodecUtil.clearDecoderInfoCache()
        // Registered software first, so "hardware first" must reorder them.
        addDecoder("c2.android.avc.decoder", MimeTypes.VIDEO_H264, hardware = false, software = true)
        addDecoder("OMX.google.h264.decoder", MimeTypes.VIDEO_H264, hardware = false, software = true)
        addDecoder("OMX.amlogic.avc.decoder.awesome", MimeTypes.VIDEO_H264, hardware = true, software = false)
        addDecoder("c2.android.mp2.decoder", MimeTypes.AUDIO_MPEG_L2, hardware = false, software = true)
        addDecoder("c2.android.aac.decoder", MimeTypes.AUDIO_AAC, hardware = false, software = true)
    }

    @After fun tearDown() {
        ShadowMediaCodecList.reset()
        MediaCodecUtil.clearDecoderInfoCache()
    }

    private fun addDecoder(name: String, mime: String, hardware: Boolean, software: Boolean) {
        val caps = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat().apply { setString(MediaFormat.KEY_MIME, mime) })
            .setIsEncoder(false)
            .apply { if (MimeTypes.isVideo(mime)) setColorFormats(intArrayOf(CodecCapabilities.COLOR_FormatYUV420Flexible)) }
            .build()
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder()
                .setName(name)
                .setIsEncoder(false)
                .setIsHardwareAccelerated(hardware)
                .setIsSoftwareOnly(software)
                .setIsVendor(hardware)
                .setCapabilities(caps)
                .build(),
        )
    }

    private fun names(selector: BoxMediaCodecSelector, mime: String) = selector.getDecoderInfos(mime, false, false).map { it.name }

    @Test
    fun platformListSeesTheFlags() {
        val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(MimeTypes.VIDEO_H264, false, false)
        val amlogic = infos.single { it.name.startsWith("OMX.amlogic") }
        assertTrue(amlogic.hardwareAccelerated)
        assertTrue(infos.filter { it.name != amlogic.name }.all { it.softwareOnly && !it.hardwareAccelerated })
    }

    @Test
    fun autoPutsHardwareFirstAndKeepsSoftwareAsFallback() {
        val selector = BoxMediaCodecSelector(ffmpegSupports = { false })
        val list = names(selector, MimeTypes.VIDEO_H264)
        assertEquals("OMX.amlogic.avc.decoder.awesome", list.first())
        assertEquals(3, list.size)
        // Software keeps its platform order behind the hardware decoder (stable partition).
        val platformSoftware = MediaCodecSelector.DEFAULT.getDecoderInfos(MimeTypes.VIDEO_H264, false, false).filter { it.softwareOnly }.map { it.name }
        assertEquals(platformSoftware, list.drop(1))
    }

    @Test
    fun hardwareOnlyDropsSoftwareDecoders() {
        val selector = BoxMediaCodecSelector(ffmpegSupports = { false }).apply { videoMode = VideoDecoderMode.HARDWARE_ONLY }
        assertEquals(listOf("OMX.amlogic.avc.decoder.awesome"), names(selector, MimeTypes.VIDEO_H264))
    }

    @Test
    fun softwarePreferredPutsSoftwareFirst() {
        val selector = BoxMediaCodecSelector(ffmpegSupports = { false }).apply { videoMode = VideoDecoderMode.SOFTWARE_PREFERRED }
        val list = names(selector, MimeTypes.VIDEO_H264)
        assertEquals("OMX.amlogic.avc.decoder.awesome", list.last())
        assertEquals(3, list.size)
    }

    @Test
    fun hardwareOnlyWithoutHardwareIsEmpty() {
        ShadowMediaCodecList.reset()
        MediaCodecUtil.clearDecoderInfoCache()
        addDecoder("c2.android.hevc.decoder", MimeTypes.VIDEO_H265, hardware = false, software = true)
        val selector = BoxMediaCodecSelector(ffmpegSupports = { false }).apply { videoMode = VideoDecoderMode.HARDWARE_ONLY }
        assertEquals(emptyList<String>(), names(selector, MimeTypes.VIDEO_H265))
    }

    @Test
    fun softwareAudioGateHidesPlatformDecodersOnlyWhenFfmpegSupportsTheMime() {
        var ffmpegHasMp2 = true
        val selector = BoxMediaCodecSelector(ffmpegSupports = { it == MimeTypes.AUDIO_MPEG_L2 && ffmpegHasMp2 })
        assertEquals(listOf("c2.android.mp2.decoder"), names(selector, MimeTypes.AUDIO_MPEG_L2))

        selector.softwareAudioMimes = setOf(MimeTypes.AUDIO_MPEG_L2, MimeTypes.AUDIO_AAC)
        assertEquals("MP2 → FFmpeg", emptyList<String>(), names(selector, MimeTypes.AUDIO_MPEG_L2))
        assertEquals("FFmpeg cannot do AAC here: platform kept", listOf("c2.android.aac.decoder"), names(selector, MimeTypes.AUDIO_AAC))

        ffmpegHasMp2 = false
        assertEquals(listOf("c2.android.mp2.decoder"), names(selector, MimeTypes.AUDIO_MPEG_L2))
    }

    @Test
    fun softwareAudioMimesNeverAffectVideo() {
        val selector = BoxMediaCodecSelector(ffmpegSupports = { true }).apply { softwareAudioMimes = setOf(MimeTypes.VIDEO_H264) }
        assertEquals(3, names(selector, MimeTypes.VIDEO_H264).size)
    }

    @Test
    fun ffmpegLinkageErrorsCountAsUnsupported() {
        val selector = BoxMediaCodecSelector(ffmpegSupports = { throw UnsatisfiedLinkError("ffmpegJNI") })
        selector.softwareAudioMimes = setOf(MimeTypes.AUDIO_MPEG_L2)
        assertEquals(listOf("c2.android.mp2.decoder"), names(selector, MimeTypes.AUDIO_MPEG_L2))
    }

    @Test
    fun productionDefaultIsSafeWithoutFfmpegNatives() {
        // FfmpegAvailability.supports is false under Robolectric (no AAR, or natives that cannot load).
        val selector = BoxMediaCodecSelector().apply { softwareAudioMimes = setOf(MimeTypes.AUDIO_MPEG_L2) }
        assertEquals(listOf("c2.android.mp2.decoder"), names(selector, MimeTypes.AUDIO_MPEG_L2))
    }
}
