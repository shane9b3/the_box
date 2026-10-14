package app.thebox.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Multichannel PCM → stereo downmix, active only when [enabled] is true at `configure()` time
 * (docs/design/m2-playback-core.md §3.8.3; used for `audioOutput == STEREO` and ladder L34).
 *
 * Deviation from the design text: `ChannelMixingMatrix.createForConstantGain(n, 2)` exists only for n ≤ 2 in 1.11.1
 * (it throws `UnsupportedOperationException` for 3..8), so the coefficients are our own: the ITU-R BS.775 −3 dB
 * downmix (centre and surrounds at 0.7071, back-centre at 0.5 to each side, LFE dropped), saturated to the PCM range.
 * Input channel order is Android's (`AudioFormat.CHANNEL_OUT_*` bit order, as `Util.getAudioTrackChannelConfig`):
 * 3 = L R C, 4 = L R BL BR, 5 = L R C BL BR, 6 = L R C LFE BL BR, 7 = 6 + BC, 8 = 6 + SL SR.
 *
 * Inactive (passes everything through untouched) for mono/stereo, more than 8 channels, encodings other than 16-bit or
 * float PCM, and whenever [enabled] was false at the last configure. `DefaultAudioSink` re-configures its processors on
 * every format change and flush, so a re-prepare applies a change of [enabled].
 */
@OptIn(UnstableApi::class)
internal class StereoDownmixProcessor : BaseAudioProcessor() {

    /** Read at `configure()`; written on main (through [AudioOutputPolicy.forceStereo]). */
    @Volatile var enabled: Boolean = false

    /** Per input channel: gain to the left and right output (index 2·ch and 2·ch+1). Set at configure. */
    private var gains = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val channels = inputAudioFormat.channelCount
        val encoding = inputAudioFormat.encoding
        if (!enabled || channels !in 3..8 || (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT)) {
            return AudioFormat.NOT_SET
        }
        gains = gainsFor(channels)
        return AudioFormat(inputAudioFormat.sampleRate, 2, encoding)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inChannels = inputAudioFormat.channelCount
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val inFrameBytes = inputAudioFormat.bytesPerFrame
        val frames = inputBuffer.remaining() / inFrameBytes
        val out = replaceOutputBuffer(frames * outputAudioFormat.bytesPerFrame)
        val g = gains
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        var pos = input.position()
        for (f in 0 until frames) {
            var left = 0f
            var right = 0f
            for (ch in 0 until inChannels) {
                val sample = if (float) input.getFloat(pos) else input.getShort(pos) / 32768f
                pos += if (float) 4 else 2
                left += sample * g[2 * ch]
                right += sample * g[2 * ch + 1]
            }
            if (float) {
                out.putFloat(left.coerceIn(-1f, 1f))
                out.putFloat(right.coerceIn(-1f, 1f))
            } else {
                out.putShort(toShort(left))
                out.putShort(toShort(right))
            }
        }
        inputBuffer.position(inputBuffer.position() + frames * inFrameBytes)
        out.flip()
    }

    override fun onReset() {
        gains = FloatArray(0)
    }

    companion object {
        private const val H = 0.7071f

        private fun toShort(v: Float): Short = (v * 32768f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

        /** Left/right gains per input channel for [channels] = 3..8 (layouts in the class KDoc). */
        fun gainsFor(channels: Int): FloatArray {
            val fl = floatArrayOf(1f, 0f)
            val fr = floatArrayOf(0f, 1f)
            val fc = floatArrayOf(H, H)
            val lfe = floatArrayOf(0f, 0f)
            val bl = floatArrayOf(H, 0f)
            val br = floatArrayOf(0f, H)
            val bc = floatArrayOf(0.5f, 0.5f)
            val layout = when (channels) {
                3 -> listOf(fl, fr, fc)
                4 -> listOf(fl, fr, bl, br)
                5 -> listOf(fl, fr, fc, bl, br)
                6 -> listOf(fl, fr, fc, lfe, bl, br)
                7 -> listOf(fl, fr, fc, lfe, bl, br, bc)
                8 -> listOf(fl, fr, fc, lfe, bl, br, bl, br) // side left/right fold like the backs
                else -> throw IllegalArgumentException("downmix needs 3..8 channels, got $channels")
            }
            return FloatArray(channels * 2) { i -> layout[i / 2][i % 2] }
        }
    }
}
