package app.thebox.player.engine

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter

/**
 * `DefaultRenderersFactory` with the M2 decoder/audio policy (docs/design/m2-playback-core.md §3.8.1):
 * - extension mode ON (platform first, FFmpeg second) when [softwareAudioFallback], else OFF; the per-MIME "prefer
 *   FFmpeg" is done at runtime by [selector] hiding platform decoders;
 * - `setEnableDecoderFallback(true)`, [selector] as the `MediaCodecSelector`;
 * - the audio sink is `PassthroughGateAudioSink(DefaultAudioSink(float output off, processors = [downmix]))`.
 *   Offload stays at its default (off).
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
) : DefaultRenderersFactory(context) {

    init {
        setExtensionRendererMode(if (softwareAudioFallback) EXTENSION_RENDERER_MODE_ON else EXTENSION_RENDERER_MODE_OFF)
        setEnableDecoderFallback(true)
        setMediaCodecSelector(selector)
        setEnableAudioFloatOutput(false)
    }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink {
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false) // some Fire OS sinks mishandle float (§3.8.3)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessors(arrayOf(downmix))
            .build()
        return PassthroughGateAudioSink(sink, audioPolicy)
    }

    override fun getCodecAdapterFactory(): MediaCodecAdapter.Factory = codecAdapterFactory ?: super.getCodecAdapterFactory()
}
