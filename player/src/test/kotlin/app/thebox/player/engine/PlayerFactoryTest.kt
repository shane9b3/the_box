package app.thebox.player.engine

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.test.utils.FakeClock
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.playback.AfrMode
import app.thebox.core.playback.AudioOutputMode
import app.thebox.core.playback.VideoDecoderMode
import app.thebox.player.api.PlaybackSettings
import app.thebox.player.internal.PlayerBundle
import com.google.common.base.Supplier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** [PlayerFactory]: the §6.3 builder table and the settings-derived switches. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerFactoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val bundles = mutableListOf<PlayerBundle>()

    @After fun tearDown() {
        bundles.forEach { it.player.release() }
    }

    private class Built(val bundle: PlayerBundle, val builder: ExoPlayer.Builder)

    private fun build(settings: PlaybackSettings = PlaybackSettings(), softwareAudioFallback: Boolean = true, lowRam: Boolean = false): Built {
        var captured: ExoPlayer.Builder? = null
        val factory = EngineTestSupport.factory(context, FakeClock(true), settings, ffmpegSupports = { it == MimeTypes.AUDIO_MPEG_L2 }) { captured = it }
        factory.testHooks = PlayerFactory.TestHooks(
            configureBuilder = factory.testHooks!!.configureBuilder,
            codecAdapterFactory = factory.testHooks!!.codecAdapterFactory,
            ffmpegSupports = factory.testHooks!!.ffmpegSupports,
            lowRam = lowRam,
        )
        val bundle = factory.create(Looper.getMainLooper(), softwareAudioFallback).also { bundles += it }
        return Built(bundle, checkNotNull(captured))
    }

    private fun field(builder: ExoPlayer.Builder, name: String): Any? =
        ExoPlayer.Builder::class.java.getDeclaredField(name).apply { isAccessible = true }.get(builder)

    @Suppress("UNCHECKED_CAST")
    private fun supplied(builder: ExoPlayer.Builder, name: String): Any? = (field(builder, name) as Supplier<Any>).get()

    private fun rendererField(f: DefaultRenderersFactory, name: String): Any? =
        DefaultRenderersFactory::class.java.getDeclaredField(name).apply { isAccessible = true }.get(f)

    @Test
    fun builderSettingsFollowTheTable() {
        val (bundle, b) = build().let { it.bundle to it.builder }
        val renderers = supplied(b, "renderersFactorySupplier") as BoxRenderersFactory
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON, rendererField(renderers, "extensionRendererMode"))
        assertEquals(true, rendererField(renderers, "enableDecoderFallback"))
        assertTrue(rendererField(renderers, "mediaCodecSelector") === bundle.codecPolicy)
        assertTrue(supplied(b, "loadControlSupplier") === bundle.loadControl)
        assertTrue(supplied(b, "trackSelectorSupplier") === bundle.trackSelector)

        val attrs = field(b, "audioAttributes") as AudioAttributes
        assertEquals(C.USAGE_MEDIA, attrs.usage)
        assertEquals(C.AUDIO_CONTENT_TYPE_MOVIE, attrs.contentType)
        assertEquals(true, field(b, "handleAudioFocus"))
        assertEquals(true, field(b, "handleAudioBecomingNoisy"))
        assertEquals(C.WAKE_MODE_NETWORK, field(b, "wakeMode"))
        assertEquals(C.VIDEO_SCALING_MODE_SCALE_TO_FIT, field(b, "videoScalingMode"))
        assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, field(b, "videoChangeFrameRateStrategy"))
        assertEquals(30_000L, field(b, "seekBackIncrementMs"))
        assertEquals(30_000L, field(b, "seekForwardIncrementMs"))
        assertEquals(1_000L, field(b, "releaseTimeoutMs"))
        assertEquals(30_000, field(b, "stuckBufferingDetectionTimeoutMs"))
        assertEquals(true, field(b, "useLazyPreparation"))
        assertEquals(Looper.getMainLooper(), field(b, "looper"))
        assertEquals("thebox", field(b, "playerName"))

        // The built player agrees.
        val player = bundle.player
        assertEquals(C.VIDEO_SCALING_MODE_SCALE_TO_FIT, player.videoScalingMode)
        assertEquals(30_000L, player.seekBackIncrement)
        assertEquals(C.USAGE_MEDIA, player.audioAttributes.usage)
        assertEquals(Looper.getMainLooper(), player.applicationLooper)
    }

    @Test
    fun trackSelectorDefaults() {
        val params = build().bundle.trackSelector.parameters
        assertFalse("tunneling off by default (§3.8.4)", params.tunnelingEnabled)
        assertEquals(C.ROLE_FLAG_MAIN, params.preferredAudioRoleFlags)
        assertEquals(listOf(Locale.getDefault().language), params.preferredAudioLanguages)
        assertTrue("subtitles off by default", C.TRACK_TYPE_TEXT in params.disabledTrackTypes)

        val custom = build(PlaybackSettings(tunneling = true, preferredAudioLanguages = listOf("deu", "eng"), subtitlesEnabled = true, preferredTextLanguages = listOf("deu")))
        val p = custom.bundle.trackSelector.parameters
        assertTrue(p.tunnelingEnabled)
        assertEquals(listOf("de", "en"), p.preferredAudioLanguages)
        assertEquals(listOf("de"), p.preferredTextLanguages)
        assertFalse(C.TRACK_TYPE_TEXT in p.disabledTrackTypes)
    }

    @Test
    fun frameRateStrategyPerAfrMode() {
        assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, field(build(PlaybackSettings(afrMode = AfrMode.ALWAYS)).builder, "videoChangeFrameRateStrategy"))
        assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, field(build(PlaybackSettings(afrMode = AfrMode.OFF)).builder, "videoChangeFrameRateStrategy"))
        assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, PlayerFactory.frameRateStrategyFor(AfrMode.SEAMLESS))
    }

    @Test
    fun ffmpegToggleOffMeansExtensionModeOff() {
        val b = build(softwareAudioFallback = false).builder
        val renderers = supplied(b, "renderersFactorySupplier") as BoxRenderersFactory
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF, rendererField(renderers, "extensionRendererMode"))
        assertEquals(true, rendererField(renderers, "enableDecoderFallback"))
    }

    @Test
    fun byteCapFollowsTheDeviceClass() {
        assertEquals(48 shl 20, build(lowRam = false).bundle.loadControl.profile.targetBytes)
        assertEquals(24 shl 20, build(lowRam = true).bundle.loadControl.profile.targetBytes)
    }

    @Test
    fun settingsBecomeRuntimeSwitches() {
        val defaults = build().bundle
        assertEquals(VideoDecoderMode.AUTO, defaults.codecPolicy.videoMode)
        assertTrue(defaults.codecPolicy.softwareAudioMimes.isEmpty())
        assertTrue(defaults.audioPolicy.deniedPassthrough.isEmpty())
        assertFalse(defaults.audioPolicy.forceStereo)

        val decode = build(PlaybackSettings(audioOutput = AudioOutputMode.ALWAYS_DECODE, preferSoftwareAudio = true, videoDecoderMode = VideoDecoderMode.SOFTWARE_PREFERRED)).bundle
        assertEquals(setOf(AudioOutputPolicy.ALL), decode.audioPolicy.deniedPassthrough)
        assertFalse(decode.audioPolicy.forceStereo)
        assertEquals("only what FFmpeg supports", setOf(MimeTypes.AUDIO_MPEG_L2), decode.codecPolicy.softwareAudioMimes)
        assertEquals(VideoDecoderMode.SOFTWARE_PREFERRED, decode.codecPolicy.videoMode)

        val stereo = build(PlaybackSettings(audioOutput = AudioOutputMode.STEREO)).bundle
        assertEquals(setOf(AudioOutputPolicy.ALL), stereo.audioPolicy.deniedPassthrough)
        assertTrue(stereo.audioPolicy.forceStereo)

        // Re-applying settings at runtime flips tunneling without a rebuild.
        PlayerFactory.applySettings(defaults, PlaybackSettings(tunneling = true)) { false }
        assertTrue(defaults.trackSelector.parameters.tunnelingEnabled)
        assertTrue(defaults.trackSelector is DefaultTrackSelector)
    }
}
