package app.thebox.player.engine

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import app.thebox.core.playback.AudioBufferSize
import app.thebox.core.playback.CodecQueueing
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter

/**
 * `DefaultRenderersFactory` with the M2 decoder/audio policy (docs/design/m2-playback-core.md §3.8.1):
 * - extension mode ON (platform first, FFmpeg second) when [softwareAudioFallback], else OFF; the per-MIME "prefer
 *   FFmpeg" is done at runtime by [selector] hiding platform decoders;
 * - `setEnableDecoderFallback(true)`, [selector] as the `MediaCodecSelector`;
 * - the audio sink is `PassthroughGateAudioSink(DefaultAudioSink(float output off, processors = [downmix]))`.
 *   Offload stays at its default (off).
 * - (M5, docs/design/m5-setup-settings.md §2.12 item 7, B2) the sink's output provider is [ForcedPassthroughOutputProvider]
 *   over Media3's `AudioTrackAudioOutputProvider`: AC-3/E-AC-3/DTS/TrueHD pass the reported capabilities while
 *   [AudioOutputPolicy.forcePassthrough] is on ("Always passthrough"), read at every prepare.
 *
 * [codecAdapterFactory] is the Robolectric seam (review S14): tests pass `IdlingMediaCodecAdapterFactory`.
 * `ExperimentalFfmpegVideoRenderer` is added by the base class in ON mode but is inert (App. A.7).
 */
@OptIn(UnstableApi::class)
internal class BoxRenderersFactory(
    context: Context,
    selector: BoxMediaCodecSelector,
    private val audioPolicy: AudioOutputPolicy,
    private val downmix: StereoDownmixProcessor,
    softwareAudioFallback: Boolean,
    private val codecAdapterFactory: MediaCodecAdapter.Factory? = null,
    /** (Smooth playback, 2026-11-23) "Decoder hand-off" and "Audio buffer", as stored when the player is built. */
    queueing: CodecQueueing = CodecQueueing.AUTO,
    private val audioBuffer: AudioBufferSize = AudioBufferSize.AUTO,
) : DefaultRenderersFactory(context) {

    init {
        when (queueing) {
            CodecQueueing.AUTO -> Unit // Media3's own choice: asynchronous on Android 12 and later
            CodecQueueing.ASYNC -> forceEnableMediaCodecAsynchronousQueueing()
            CodecQueueing.SYNC -> forceDisableMediaCodecAsynchronousQueueing()
        }
        setExtensionRendererMode(if (softwareAudioFallback) EXTENSION_RENDERER_MODE_ON else EXTENSION_RENDERER_MODE_OFF)
        setEnableDecoderFallback(true)
        setMediaCodecSelector(selector)
        setEnableAudioFloatOutput(false)
    }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
        buildSink(context, audioPolicy, downmix, enableAudioOutputPlaybackParams, audioBuffer)

    override fun getCodecAdapterFactory(): MediaCodecAdapter.Factory = codecAdapterFactory ?: super.getCodecAdapterFactory()

    companion object {
        /** The engine's audio sink (also what `ForcedPassthroughTest` builds). */
        /** "Audio buffer: Large": twice Media3's 250 to 750 ms range for decoded audio. */
        const val LARGE_PCM_MIN_US = 500_000
        const val LARGE_PCM_MAX_US = 1_500_000

        fun buildSink(
            context: Context,
            audioPolicy: AudioOutputPolicy,
            downmix: StereoDownmixProcessor,
            enablePlaybackParams: Boolean,
            audioBuffer: AudioBufferSize = AudioBufferSize.AUTO,
        ): AudioSink {
            // The buffer size belongs to the output provider (the sink builder's own setter cannot be combined with one).
            val output = AudioTrackAudioOutputProvider.Builder(context)
            if (audioBuffer == AudioBufferSize.LARGE) {
                output.setAudioTrackBufferSizeProvider(
                    DefaultAudioTrackBufferSizeProvider.Builder()
                        .setMinPcmBufferDurationUs(LARGE_PCM_MIN_US)
                        .setMaxPcmBufferDurationUs(LARGE_PCM_MAX_US)
                        .build(),
                )
            }
            val sink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false) // some Fire OS sinks mishandle float (§3.8.3)
                .setEnableAudioOutputPlaybackParameters(enablePlaybackParams)
                .setAudioProcessors(arrayOf(downmix))
                // (M5, docs/design/m5-setup-settings.md §2.12 item 7 F19, B2) "Always passthrough" lives in the output
                // provider; never combined with setAudioTrackBufferSizeProvider/setAudioOffloadSupportProvider/
                // setAudioTrackProvider (DefaultAudioSink.Builder.build checks it).
                .setAudioOutputProvider(ForcedPassthroughOutputProvider(output.build(), audioPolicy))
                .build()
            return PassthroughGateAudioSink(sink, audioPolicy)
        }
    }
}
