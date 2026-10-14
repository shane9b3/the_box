package app.thebox.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** [PassthroughGateAudioSink], [AudioOutputPolicy] and [StereoDownmixProcessor] (§3.8.3). */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioOutputTest {

    private fun format(mime: String, channels: Int = 6): Format =
        Format.Builder().setSampleMimeType(mime).setChannelCount(channels).setSampleRate(48_000)
            .apply { if (mime == MimeTypes.AUDIO_RAW) setPcmEncoding(C.ENCODING_PCM_16BIT) }
            .build()

    /** A sink that accepts everything directly (an AVR that takes every encoding). */
    private fun acceptingSink(): AudioSink = mock(AudioSink::class.java).also {
        `when`(it.supportsFormat(any())).thenReturn(true)
        `when`(it.getFormatSupport(any())).thenReturn(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY)
    }

    @Test
    fun deniesEncodedFormatsPerMimeAndConsistently() {
        val policy = AudioOutputPolicy(StereoDownmixProcessor())
        val gate = PassthroughGateAudioSink(acceptingSink(), policy)
        val ac3 = format(MimeTypes.AUDIO_AC3)
        val eac3 = format(MimeTypes.AUDIO_E_AC3)

        assertTrue(gate.supportsFormat(ac3))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, gate.getFormatSupport(ac3))

        policy.deniedPassthrough = setOf(MimeTypes.AUDIO_AC3)
        assertFalse(gate.supportsFormat(ac3))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, gate.getFormatSupport(ac3))
        assertTrue("other encodings untouched", gate.supportsFormat(eac3))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, gate.getFormatSupport(eac3))
    }

    @Test
    fun wildcardDeniesEveryEncodingButNeverPcm() {
        val policy = AudioOutputPolicy(StereoDownmixProcessor()).apply { deniedPassthrough = setOf(AudioOutputPolicy.ALL) }
        val gate = PassthroughGateAudioSink(acceptingSink(), policy)
        for (mime in listOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_TRUEHD)) {
            assertFalse(mime, gate.supportsFormat(format(mime)))
            assertEquals(mime, AudioSink.SINK_FORMAT_UNSUPPORTED, gate.getFormatSupport(format(mime)))
        }
        val pcm = format(MimeTypes.AUDIO_RAW, channels = 2)
        assertTrue(gate.supportsFormat(pcm))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, gate.getFormatSupport(pcm))
    }

    @Test
    fun allowedEncodingsStillDependOnTheRealSink() {
        // Passthrough only for encodings the wrapped sink (AudioCapabilities) reports.
        val sink = mock(AudioSink::class.java)
        `when`(sink.supportsFormat(any())).thenReturn(false)
        `when`(sink.getFormatSupport(any())).thenReturn(AudioSink.SINK_FORMAT_UNSUPPORTED)
        val gate = PassthroughGateAudioSink(sink, AudioOutputPolicy(StereoDownmixProcessor()))
        assertFalse(gate.supportsFormat(format(MimeTypes.AUDIO_AC3)))
    }

    @Test
    fun forceStereoGoesThroughToTheDownmix() {
        val downmix = StereoDownmixProcessor()
        val policy = AudioOutputPolicy(downmix)
        assertFalse(downmix.enabled)
        policy.forceStereo = true
        assertTrue(downmix.enabled)
        assertTrue(policy.forceStereo)
    }

    // ------------------------------------------------------------------ StereoDownmixProcessor

    @Test
    fun downmixIsInactiveByDefault() {
        val p = StereoDownmixProcessor()
        assertEquals(AudioFormat.NOT_SET, p.configure(AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT)))
        assertFalse(p.isActive)
    }

    @Test
    fun downmixPassesStereoAndMonoThroughEvenWhenEnabled() {
        val p = StereoDownmixProcessor().apply { enabled = true }
        assertEquals(AudioFormat.NOT_SET, p.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)))
        assertFalse(p.isActive)
        assertEquals(AudioFormat.NOT_SET, p.configure(AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT)))
        assertFalse(p.isActive)
    }

    @Test
    fun downmixesSixChannelsToStereoWhenEnabledAtConfigure() {
        val p = StereoDownmixProcessor().apply { enabled = true }
        val out = p.configure(AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT))
        assertEquals(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT), out)
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertTrue(p.isActive)

        // One 5.1 frame: L R C LFE BL BR.
        val input = ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder())
        for (v in shortArrayOf(1000, 2000, 4000, 30000, 800, 1600)) input.putShort(v)
        input.flip()
        p.queueInput(input)
        assertEquals(0, input.remaining())
        val output = p.output.order(ByteOrder.nativeOrder())
        assertEquals(4, output.remaining())
        val left = output.short
        val right = output.short
        // L = 1000 + 0.7071·4000 + 0.7071·800; R = 2000 + 0.7071·4000 + 0.7071·1600 (LFE dropped).
        assertEquals(4394.0, left.toDouble(), 2.0)
        assertEquals(5959.0, right.toDouble(), 2.0)
    }

    @Test
    fun downmixSaturatesInsteadOfWrapping() {
        val p = StereoDownmixProcessor().apply { enabled = true }
        p.configure(AudioFormat(48_000, 3, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val input = ByteBuffer.allocateDirect(6).order(ByteOrder.nativeOrder())
        for (v in shortArrayOf(Short.MAX_VALUE, Short.MAX_VALUE, Short.MAX_VALUE)) input.putShort(v)
        input.flip()
        p.queueInput(input)
        val output = p.output.order(ByteOrder.nativeOrder())
        assertEquals(Short.MAX_VALUE, output.short)
        assertEquals(Short.MAX_VALUE, output.short)
    }

    @Test
    fun downmixHandlesFloatAndEveryLayoutUpToEight() {
        for (n in 3..8) {
            val gains = StereoDownmixProcessor.gainsFor(n)
            assertEquals(2 * n, gains.size)
            assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f), gains.copyOfRange(0, 4), 0f)
        }
        val p = StereoDownmixProcessor().apply { enabled = true }
        assertEquals(AudioFormat(44_100, 2, C.ENCODING_PCM_FLOAT), p.configure(AudioFormat(44_100, 8, C.ENCODING_PCM_FLOAT)))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val input = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder())
        for (v in floatArrayOf(0.5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)) input.putFloat(v)
        input.flip()
        p.queueInput(input)
        val output = p.output.order(ByteOrder.nativeOrder())
        assertEquals(0.5f, output.float, 1e-6f)
        assertEquals(0f, output.float, 1e-6f)
    }

    @Test
    fun downmixDisabledAfterConfigureStaysActiveUntilTheNextConfigure() {
        val p = StereoDownmixProcessor().apply { enabled = true }
        p.configure(AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        p.enabled = false
        assertTrue("applies at the next configure (re-prepare)", p.isActive)
        assertEquals(AudioFormat.NOT_SET, p.configure(AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT)))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertFalse(p.isActive)
    }
}
