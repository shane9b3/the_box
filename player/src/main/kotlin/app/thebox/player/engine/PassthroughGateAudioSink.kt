package app.thebox.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import app.thebox.player.internal.AudioPolicyHandle

/**
 * The runtime audio-output switches of one player (docs/design/m2-playback-core.md §3.8.3), shared by
 * [PassthroughGateAudioSink] (passthrough denials) and [StereoDownmixProcessor] (forced stereo). Written on main, read
 * on the playback thread. Changes apply at the next re-prepare (track selection and sink configuration).
 */
@OptIn(UnstableApi::class)
internal class AudioOutputPolicy(private val downmix: StereoDownmixProcessor) : AudioPolicyHandle {
    @Volatile override var deniedPassthrough: Set<String> = emptySet()

    override var forceStereo: Boolean
        get() = downmix.enabled
        set(value) { downmix.enabled = value }

    /** True when [format] is an encoded (non-PCM) format whose passthrough is denied. PCM is never denied. */
    fun denies(format: Format): Boolean {
        val mime = format.sampleMimeType ?: return false
        if (mime == MimeTypes.AUDIO_RAW) return false
        val denied = deniedPassthrough
        return denied.isNotEmpty() && (ALL in denied || mime in denied)
    }

    companion object {
        /** Deny every encoding (`audioOutput != AUTO`). */
        const val ALL = "*"
    }
}

/**
 * `ForwardingAudioSink` that reports encoded formats unsupported when [policy] denies their passthrough (§3.8.3).
 *
 * Effect: `MediaCodecAudioRenderer` decodes instead of bypassing (its bypass decision is `audioSink.supportsFormat`),
 * and `FfmpegAudioRenderer` outputs PCM. PCM formats and every encoding the policy allows are delegated, so
 * passthrough still happens only for encodings the wrapped `DefaultAudioSink` reports for the current route.
 */
@OptIn(UnstableApi::class)
internal class PassthroughGateAudioSink(sink: AudioSink, private val policy: AudioOutputPolicy) : ForwardingAudioSink(sink) {
    override fun supportsFormat(format: Format): Boolean = !policy.denies(format) && super.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int =
        if (policy.denies(format)) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)
}
