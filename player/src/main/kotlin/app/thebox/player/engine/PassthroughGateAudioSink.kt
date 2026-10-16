package app.thebox.player.engine

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import app.thebox.player.internal.AudioPolicyHandle

/**
 * The runtime audio-output switches of one player (docs/design/m2-playback-core.md §3.8.3), shared by
 * [PassthroughGateAudioSink] (passthrough denials), [ForcedPassthroughOutputProvider] (forced passthrough, M5) and
 * [StereoDownmixProcessor] (forced stereo). Written on main, read on the playback thread. Changes apply at the next
 * re-prepare (track selection and sink configuration).
 */
@OptIn(UnstableApi::class)
internal class AudioOutputPolicy(private val downmix: StereoDownmixProcessor) : AudioPolicyHandle {
    @Volatile override var deniedPassthrough: Set<String> = emptySet()

    /**
     * (M5, docs/design/m5-setup-settings.md §2.12 item 7, B2) "Always passthrough": [FORCED_MIMES] are sent as a bitstream
     * even when the platform's `AudioCapabilities` do not list them ([ForcedPassthroughOutputProvider]). Set from
     * `PlaybackSettings.audioOutput == FORCE_PASSTHROUGH` by `PlayerFactory.applySettings` on every prepare, so a change
     * applies at the next re-prepare (REPREPARE, lead ruling §1.9a). [deniedPassthrough] still wins: a route denial
     * learned from an L34 / AUDIO_NOT_STARTED failure turns passthrough off for that encoding even while forced.
     */
    @Volatile var forcePassthrough: Boolean = false

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

    /** True when [format] is one of [FORCED_MIMES], forcing is on, and its passthrough is not denied. */
    fun forces(format: Format): Boolean {
        if (!forcePassthrough) return false
        val mime = format.sampleMimeType ?: return false
        return mime in FORCED_MIMES && !denies(format)
    }

    companion object {
        /** Deny every encoding (`audioOutput` ALWAYS_DECODE or STEREO). */
        const val ALL = "*"

        /** What "Always passthrough" forces (decision 2026-10-14: AC-3, E-AC-3, DTS and TrueHD, with their variants). */
        val FORCED_MIMES: Set<String> = setOf(
            MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC,
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_UHD_P2,
            MimeTypes.AUDIO_TRUEHD,
        )
    }
}

/**
 * `ForwardingAudioSink` that reports encoded formats unsupported when [policy] denies their passthrough (§3.8.3).
 *
 * Effect: `MediaCodecAudioRenderer` decodes instead of bypassing (its bypass decision is `audioSink.supportsFormat`),
 * and `FfmpegAudioRenderer` outputs PCM. PCM formats and every encoding the policy allows are delegated, so
 * passthrough still happens only for encodings the wrapped `DefaultAudioSink` accepts for the current route (which,
 * under "Always passthrough", includes the forced ones through [ForcedPassthroughOutputProvider]).
 */
@OptIn(UnstableApi::class)
internal class PassthroughGateAudioSink(sink: AudioSink, private val policy: AudioOutputPolicy) : ForwardingAudioSink(sink) {
    override fun supportsFormat(format: Format): Boolean = !policy.denies(format) && super.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int =
        if (policy.denies(format)) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)
}

/**
 * (M5, docs/design/m5-setup-settings.md §2.12 item 7 F19 finding, B2) The Media3 1.11 hook for "Always passthrough": the
 * `AudioTrackAudioOutputProvider` of `DefaultAudioSink`, wrapped. Passthrough is decided here
 * (`DefaultAudioSink.supportsFormat/getFormatSupport` → [getFormatSupport]; `configure` → [getOutputConfig]), and both
 * read [AudioOutputPolicy.forcePassthrough] on every call, so the mode switches at the next prepare without a new sink
 * or engine.
 *
 * For a forced encoding the delegate rejects (the platform does not report it), [getFormatSupport] says
 * FORMAT_SUPPORTED_DIRECTLY (so `MediaCodecAudioRenderer` bypasses the decoder) and [getOutputConfig] builds the
 * passthrough `OutputConfig` the delegate refused. If the TV or receiver then refuses the encoding, `AudioTrack` creation
 * fails (`InitializationException` → `AudioSink.InitializationException`), i.e. the existing L34 path: the ladder denies
 * passthrough for the route ([AudioOutputPolicy.deniedPassthrough] wins over the force) and re-prepares as PCM. A
 * receiver that accepts the bitstream but plays nothing cannot be detected (B2), hence the row's "If you hear no sound,
 * turn this off".
 */
@OptIn(UnstableApi::class)
internal class ForcedPassthroughOutputProvider(
    delegate: AudioOutputProvider,
    private val policy: AudioOutputPolicy,
) : ForwardingAudioOutputProvider(delegate) {

    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport {
        val support = super.getFormatSupport(formatConfig)
        if (support.supportLevel != AudioOutputProvider.FORMAT_UNSUPPORTED || !policy.forces(formatConfig.format)) return support
        return AudioOutputProvider.FormatSupport.Builder()
            .setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY)
            .build()
    }

    override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig = try {
        super.getOutputConfig(formatConfig)
    } catch (e: AudioOutputProvider.ConfigurationException) {
        if (!policy.forces(formatConfig.format)) throw e
        forcedOutputConfig(formatConfig)
    }

    companion object {
        /** Used when the format carries no sample rate (HLS chunkless preparation). */
        const val DEFAULT_SAMPLE_RATE_HZ = 48_000

        /** The passthrough `OutputConfig` the platform capabilities refused (§2.12.7 F19). */
        fun forcedOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
            val format = formatConfig.format
            val mime = format.sampleMimeType ?: throw AudioOutputProvider.ConfigurationException("Forced passthrough without a MIME type")
            val encoding = MimeTypes.getEncoding(mime, format.codecs)
            if (encoding == C.ENCODING_INVALID) throw AudioOutputProvider.ConfigurationException("Forced passthrough: no encoding for $mime")
            val sampleRate = if (format.sampleRate != Format.NO_VALUE) format.sampleRate else DEFAULT_SAMPLE_RATE_HZ
            val channelMask = channelMaskFor(format)
            val bufferSize = if (formatConfig.preferredBufferSize != C.LENGTH_UNSET) {
                formatConfig.preferredBufferSize
            } else {
                DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT.getBufferSizeInBytes(
                    minBufferSize(sampleRate, channelMask, encoding),
                    encoding,
                    DefaultAudioSink.OUTPUT_MODE_PASSTHROUGH,
                    /* pcmFrameSize = */ 1,
                    sampleRate,
                    format.bitrate,
                    DefaultAudioSink.DEFAULT_PLAYBACK_SPEED.toDouble(),
                )
            }
            return AudioOutputProvider.OutputConfig.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .setBufferSize(bufferSize)
                .setAudioSessionId(formatConfig.audioSessionId)
                .setAudioAttributes(formatConfig.audioAttributes)
                .setIsOffload(false)
                .setIsTunneling(formatConfig.enableTunneling)
                .setUsePlaybackParameters(formatConfig.enablePlaybackParameters)
                .setVirtualDeviceId(formatConfig.virtualDeviceId)
                .build()
        }

        /**
         * Channel mask as Media3's own passthrough path chooses it (`AudioCapabilities.getChannelConfigForPassthrough`):
         * an unknown count and E-AC-3 JOC use 5.1 (TrueHD 7.1); on API ≤ 28 odd counts are rounded to a known-good layout.
         */
        fun channelMaskFor(format: Format): Int {
            val mime = format.sampleMimeType
            var count = when {
                format.channelCount != Format.NO_VALUE && mime != MimeTypes.AUDIO_E_AC3_JOC -> format.channelCount
                mime == MimeTypes.AUDIO_TRUEHD -> 8
                else -> 6
            }
            if (Build.VERSION.SDK_INT <= 28) {
                if (count == 7) count = 8 else if (count in 3..5) count = 6
            }
            if (format.channelMask != Format.NO_VALUE && format.channelCount == count) return format.channelMask
            val mask = Util.getAudioTrackChannelConfig(count)
            return if (mask == AudioFormat.CHANNEL_INVALID) AudioFormat.CHANNEL_OUT_5POINT1 else mask
        }

        private fun minBufferSize(sampleRate: Int, channelMask: Int, encoding: Int): Int = try {
            AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding).coerceAtLeast(0)
        } catch (e: RuntimeException) {
            0
        }
    }
}
