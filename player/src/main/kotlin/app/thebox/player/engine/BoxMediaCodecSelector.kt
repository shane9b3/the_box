package app.thebox.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import app.thebox.core.playback.VideoDecoderMode
import app.thebox.ffmpeg.FfmpegAvailability
import app.thebox.player.internal.CodecPolicyHandle

/**
 * Decoder ordering and the per-MIME software-audio gate (docs/design/m2-playback-core.md §3.8.1).
 *
 * - Video, [videoMode] `AUTO`: hardware decoders first, then software (stable partition). `HARDWARE_ONLY` drops
 *   software-only decoders. `SOFTWARE_PREFERRED` puts software-only decoders first (as `MediaCodecSelector.PREFER_SOFTWARE`).
 * - Audio: a MIME type in [softwareAudioMimes] that FFmpeg supports gets an **empty** platform list, so
 *   `MediaCodecAudioRenderer` reports it unsupported and the track selector picks `FfmpegAudioRenderer` (per-MIME
 *   "prefer FFmpeg" without rebuilding the renderer list). Passthrough is unaffected: deny it in the audio gate too.
 *
 * Both switches are `@Volatile` (written on main, read on the playback thread) and apply at the next track selection or
 * codec init (the ladder always re-prepares). [ffmpegSupports] is `FfmpegAvailability.supports` in production; it is
 * false whenever the FFmpeg natives do not load (Robolectric, ARM-only AAR on x86).
 */
@OptIn(UnstableApi::class)
internal class BoxMediaCodecSelector(
    private val ffmpegSupports: (String) -> Boolean = FfmpegAvailability::supports,
    private val platform: MediaCodecSelector = MediaCodecSelector.DEFAULT,
) : MediaCodecSelector, CodecPolicyHandle {

    @Volatile override var videoMode: VideoDecoderMode = VideoDecoderMode.AUTO
    @Volatile override var softwareAudioMimes: Set<String> = emptySet()

    override fun getDecoderInfos(mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean): List<MediaCodecInfo> {
        if (MimeTypes.isAudio(mimeType) && mimeType in softwareAudioMimes && supportsSafely(mimeType)) {
            return emptyList()
        }
        val list = platform.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        if (!MimeTypes.isVideo(mimeType) || (list.size <= 1 && videoMode != VideoDecoderMode.HARDWARE_ONLY)) return list
        return order(list, videoMode)
    }

    private fun supportsSafely(mimeType: String): Boolean = try {
        ffmpegSupports(mimeType)
    } catch (_: LinkageError) {
        false
    }

    companion object {
        /** Pure ordering rule, exposed for tests and diagnostics. Stable within each class. */
        fun order(list: List<MediaCodecInfo>, mode: VideoDecoderMode): List<MediaCodecInfo> = when (mode) {
            VideoDecoderMode.AUTO -> {
                val (hardware, other) = list.partition { it.hardwareAccelerated }
                hardware + other
            }
            VideoDecoderMode.HARDWARE_ONLY -> list.filter { !it.softwareOnly }
            VideoDecoderMode.SOFTWARE_PREFERRED -> {
                val (software, other) = list.partition { it.softwareOnly }
                software + other
            }
        }

        /** Audio MIME types the FFmpeg extension may decode (the build's decoder list, `build-ffmpeg-aar.sh`). */
        val FFMPEG_CANDIDATE_MIMES: List<String> = listOf(
            MimeTypes.AUDIO_MPEG_L2, MimeTypes.AUDIO_MPEG_L1, MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3,
            MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_AAC,
            MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_VORBIS, MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC,
        )
    }
}
