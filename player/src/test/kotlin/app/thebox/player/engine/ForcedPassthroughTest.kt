package app.thebox.player.engine

import android.content.Context
import android.media.AudioFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.playback.AudioOutputMode
import app.thebox.core.playback.LadderDecision
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.player.api.PlaybackNotice
import app.thebox.player.api.PlaybackSettings
import app.thebox.player.api.TuneTarget
import app.thebox.player.controller.fakes.CH1
import app.thebox.player.controller.fakes.ControllerFixture
import app.thebox.player.internal.PlayerBundle
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (M5, docs/design/m5-setup-settings.md §2.12 item 7, review B2/S16) "Always passthrough": the sink's output provider
 * forces AC-3/E-AC-3/DTS/TrueHD past the platform capabilities under FORCE_PASSTHROUGH and not otherwise; a denial
 * (route memory after an L34 failure) wins; the base denials per mode; the controller's L34 fallback under the force.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ForcedPassthroughTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun format(mime: String, channels: Int = 6): Format =
        Format.Builder().setSampleMimeType(mime).setChannelCount(channels).setSampleRate(48_000)
            .apply { if (mime == MimeTypes.AUDIO_RAW) setPcmEncoding(C.ENCODING_PCM_16BIT) }
            .build()

    private fun config(format: Format) = AudioOutputProvider.FormatConfig.Builder(format).setAudioSessionId(7).build()

    /** The platform (an HDMI sink that reports PCM only): rejects every encoding. */
    private fun pcmOnlyDelegate(): AudioOutputProvider = mock(AudioOutputProvider::class.java).also {
        `when`(it.getFormatSupport(any())).thenReturn(AudioOutputProvider.FormatSupport.UNSUPPORTED)
        `when`(it.getOutputConfig(any())).thenThrow(AudioOutputProvider.ConfigurationException("Unable to configure passthrough"))
    }

    private fun policy(force: Boolean, denied: Set<String> = emptySet()) =
        AudioOutputPolicy(StereoDownmixProcessor()).apply { forcePassthrough = force; deniedPassthrough = denied }

    @Test
    fun forcedEncodingsAreSupportedDirectlyOnlyUnderTheForce() {
        val forced = ForcedPassthroughOutputProvider(pcmOnlyDelegate(), policy(force = true))
        val auto = ForcedPassthroughOutputProvider(pcmOnlyDelegate(), policy(force = false))
        for (mime in AudioOutputPolicy.FORCED_MIMES) {
            assertEquals(mime, AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY, forced.getFormatSupport(config(format(mime))).supportLevel)
            assertEquals(mime, AudioOutputProvider.FORMAT_UNSUPPORTED, auto.getFormatSupport(config(format(mime))).supportLevel)
        }
        // AAC is never forced (passthrough of it is untested/broken, as in Media3's own list).
        assertEquals(AudioOutputProvider.FORMAT_UNSUPPORTED, forced.getFormatSupport(config(format(MimeTypes.AUDIO_AAC))).supportLevel)
    }

    @Test
    fun forcedOutputConfigCarriesThePassthroughEncodingAndTheSession() {
        val forced = ForcedPassthroughOutputProvider(pcmOnlyDelegate(), policy(force = true))
        val ac3 = forced.getOutputConfig(config(format(MimeTypes.AUDIO_AC3)))
        assertEquals(C.ENCODING_AC3, ac3.encoding)
        assertEquals(48_000, ac3.sampleRate)
        assertEquals(AudioFormat.CHANNEL_OUT_5POINT1, ac3.channelMask)
        assertEquals(7, ac3.audioSessionId)
        assertFalse(ac3.isOffload)
        assertTrue("a passthrough buffer", ac3.bufferSize > 0)
        assertEquals(C.ENCODING_E_AC3, forced.getOutputConfig(config(format(MimeTypes.AUDIO_E_AC3))).encoding)
        assertEquals(C.ENCODING_DTS, forced.getOutputConfig(config(format(MimeTypes.AUDIO_DTS))).encoding)
        assertEquals(C.ENCODING_DOLBY_TRUEHD, forced.getOutputConfig(config(format(MimeTypes.AUDIO_TRUEHD, channels = 8))).encoding)
        // Unknown channel count (HLS chunkless preparation): 5.1.
        assertEquals(AudioFormat.CHANNEL_OUT_5POINT1, forced.getOutputConfig(config(format(MimeTypes.AUDIO_AC3, channels = Format.NO_VALUE))).channelMask)
    }

    @Test
    fun withoutTheForceTheDelegateRefusalStands() {
        val auto = ForcedPassthroughOutputProvider(pcmOnlyDelegate(), policy(force = false))
        try {
            auto.getOutputConfig(config(format(MimeTypes.AUDIO_AC3)))
            fail("expected the delegate's ConfigurationException")
        } catch (e: AudioOutputProvider.ConfigurationException) {
            // expected
        }
    }

    @Test
    fun whatThePlatformSupportsIsDelegatedUnchanged() {
        val supported = AudioOutputProvider.FormatSupport.Builder().setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY).build()
        val outputConfig = AudioOutputProvider.OutputConfig.Builder().setEncoding(C.ENCODING_AC3).setSampleRate(48_000).build()
        val delegate = mock(AudioOutputProvider::class.java).also {
            `when`(it.getFormatSupport(any())).thenReturn(supported)
            `when`(it.getOutputConfig(any())).thenReturn(outputConfig)
        }
        val forced = ForcedPassthroughOutputProvider(delegate, policy(force = true))
        assertSame(supported, forced.getFormatSupport(config(format(MimeTypes.AUDIO_AC3))))
        assertSame(outputConfig, forced.getOutputConfig(config(format(MimeTypes.AUDIO_AC3))))
    }

    @Test
    fun aDenialWinsOverTheForce() {
        val p = policy(force = true, denied = setOf(MimeTypes.AUDIO_E_AC3))
        assertTrue(p.forces(format(MimeTypes.AUDIO_AC3)))
        assertFalse(p.forces(format(MimeTypes.AUDIO_E_AC3)))
        val forced = ForcedPassthroughOutputProvider(pcmOnlyDelegate(), p)
        assertEquals(AudioOutputProvider.FORMAT_UNSUPPORTED, forced.getFormatSupport(config(format(MimeTypes.AUDIO_E_AC3))).supportLevel)
        p.deniedPassthrough = setOf(AudioOutputPolicy.ALL)
        assertFalse(p.forces(format(MimeTypes.AUDIO_AC3)))
        assertFalse("PCM is never forced", p.forces(format(MimeTypes.AUDIO_RAW, channels = 2)))
    }

    @Test
    fun theEngineSinkPassesForcedEncodingsOnAPcmOnlyPlatform() {
        // Robolectric's AudioCapabilities report PCM only, like the emulator (I-M5-27).
        val p = policy(force = false)
        val sink = BoxRenderersFactory.buildSink(context, p, StereoDownmixProcessor(), enablePlaybackParams = false)
        val ac3 = format(MimeTypes.AUDIO_AC3)
        assertFalse(sink.supportsFormat(ac3))
        p.forcePassthrough = true // the next prepare reads it: no new sink
        assertTrue(sink.supportsFormat(ac3))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(ac3))
        p.deniedPassthrough = setOf(MimeTypes.AUDIO_AC3)
        assertFalse("the gate's denial wins", sink.supportsFormat(ac3))
    }

    @Test
    fun baseDenialsPerMode() {
        assertEquals(emptySet<String>(), PlayerFactory.baseDeniedPassthrough(PlaybackSettings(audioOutput = AudioOutputMode.AUTO)))
        assertEquals(emptySet<String>(), PlayerFactory.baseDeniedPassthrough(PlaybackSettings(audioOutput = AudioOutputMode.FORCE_PASSTHROUGH)))
        assertEquals(setOf(AudioOutputPolicy.ALL), PlayerFactory.baseDeniedPassthrough(PlaybackSettings(audioOutput = AudioOutputMode.ALWAYS_DECODE)))
        assertEquals(setOf(AudioOutputPolicy.ALL), PlayerFactory.baseDeniedPassthrough(PlaybackSettings(audioOutput = AudioOutputMode.STEREO)))
    }

    @Test
    fun applySettingsSetsTheForceFromTheMode() {
        val p = policy(force = false)
        val exo = androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
        try {
            val bundle = PlayerBundle(exo, DefaultTrackSelector(context), AdaptiveLoadControl(), BoxMediaCodecSelector({ false }), p)
            PlayerFactory.applySettings(bundle, PlaybackSettings(audioOutput = AudioOutputMode.FORCE_PASSTHROUGH)) { false }
            assertTrue(p.forcePassthrough)
            assertTrue(p.deniedPassthrough.isEmpty())
            PlayerFactory.applySettings(bundle, PlaybackSettings(audioOutput = AudioOutputMode.AUTO)) { false }
            assertFalse(p.forcePassthrough)
            PlayerFactory.applySettings(bundle, PlaybackSettings(audioOutput = AudioOutputMode.ALWAYS_DECODE)) { false }
            assertFalse(p.forcePassthrough)
            assertEquals(setOf(AudioOutputPolicy.ALL), p.deniedPassthrough)
        } finally {
            exo.release()
        }
    }

    @Test
    fun anL34FailureUnderTheForceDeniesTheRouteAndNotifies() {
        val f = ControllerFixture()
        try {
            f.settings.update { it.copy(audioOutput = AudioOutputMode.FORCE_PASSTHROUGH) }
            var used = false
            f.ladder.script = { _, _ ->
                if (!used) { used = true; LadderDecision.DisablePassthroughAndReprepare(MimeTypes.AUDIO_AC3) } else LadderDecision.Fail(PlaybackError(PlaybackErrorCode.INTERNAL))
            }
            f.controller.tune(TuneTarget.Channel(CH1))
            f.idle()
            f.play()
            f.advance(1_000)
            f.exo.emitError(PlaybackException("audio track init", null, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
            f.idle()
            assertTrue(MimeTypes.AUDIO_AC3 in f.playerFactory.bundle!!.audioPolicy.deniedPassthrough)
            assertTrue(f.notices.contains(PlaybackNotice.PassthroughDisabled(MimeTypes.AUDIO_AC3)))
            // The route memory survives the next tune while the force stays on.
            f.controller.tune(TuneTarget.Channel(CH1))
            f.idle()
            assertTrue(MimeTypes.AUDIO_AC3 in f.playerFactory.bundle!!.audioPolicy.deniedPassthrough)
        } finally {
            f.close()
        }
    }
}
