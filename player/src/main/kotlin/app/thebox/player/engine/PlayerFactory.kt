package app.thebox.player.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Looper
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import app.thebox.core.common.BoxLog
import app.thebox.core.playback.AfrMode
import app.thebox.core.playback.AudioOutputMode
import app.thebox.ffmpeg.FfmpegAvailability
import app.thebox.player.api.PlaybackSettings
import app.thebox.player.api.PlaybackSettingsSource
import app.thebox.player.internal.PlayerBundle
import app.thebox.player.internal.PlayerFactoryApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the one [PlayerBundle] of the process (docs/design/m2-playback-core.md §3.8, §6.3 table, App. A.4):
 * `ExoPlayer` + `DefaultTrackSelector` + [AdaptiveLoadControl] + [BoxMediaCodecSelector] (codec policy) +
 * [AudioOutputPolicy] (passthrough gate and downmix). Every runtime fallback is a switch on those long-lived objects;
 * only [create]'s `softwareAudioFallback` (the global FFmpeg toggle) needs a new player.
 *
 * Initial switch values come from the current [PlaybackSettings] ([applySettings]); the controller re-applies them on
 * every tune together with what the ladder learned.
 */
@OptIn(UnstableApi::class)
@Singleton
internal class PlayerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: PlaybackSettingsSource,
    private val capabilities: DeviceCapabilities,
) : PlayerFactoryApi {

    /** Robolectric seams (review S14). Null in production. */
    internal class TestHooks(
        /** Applied to the builder last, e.g. `{ it.setClock(fakeClock) }`. */
        val configureBuilder: ((ExoPlayer.Builder) -> Unit)? = null,
        /** E.g. `IdlingMediaCodecAdapterFactory(context, clock)`. */
        val codecAdapterFactory: MediaCodecAdapter.Factory? = null,
        /** Replaces `FfmpegAvailability.supports` in the codec selector. */
        val ffmpegSupports: ((String) -> Boolean)? = null,
        /** Overrides the device-class byte cap. */
        val lowRam: Boolean? = null,
    )

    @VisibleForTesting internal var testHooks: TestHooks? = null

    override fun create(looper: Looper, softwareAudioFallback: Boolean): PlayerBundle {
        Media3LogBridge.install(debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        val hooks = testHooks
        val current = settings.current

        val ffmpegSupports = hooks?.ffmpegSupports ?: FfmpegAvailability::supports
        val selector = BoxMediaCodecSelector(ffmpegSupports)
        val downmix = StereoDownmixProcessor()
        val audioPolicy = AudioOutputPolicy(downmix)
        val renderers = BoxRenderersFactory(context, selector, audioPolicy, downmix, softwareAudioFallback, hooks?.codecAdapterFactory)

        val lowRam = hooks?.lowRam ?: capabilities.isLowRam
        val loadControl = AdaptiveLoadControl(
            maxTargetBytes = if (lowRam) AdaptiveLoadControl.LOW_RAM_TARGET_BYTES else AdaptiveLoadControl.NORMAL_TARGET_BYTES,
        )
        val trackSelector = DefaultTrackSelector(context, initialParameters(context, current))

        val builder = ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(AUDIO_ATTRIBUTES, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            .setVideoChangeFrameRateStrategy(frameRateStrategyFor(current.afrMode))
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .setReleaseTimeoutMs(RELEASE_TIMEOUT_MS)
            .setStuckBufferingDetectionTimeoutMs(STUCK_BUFFERING_BACKSTOP_MS)
            .setUseLazyPreparation(true)
            .setLooper(looper)
            .setName(PLAYER_NAME)
        hooks?.configureBuilder?.invoke(builder)
        val player = builder.build()

        val bundle = PlayerBundle(player, trackSelector, loadControl, selector, audioPolicy)
        applySettings(bundle, current, ffmpegSupports)
        BoxLog.i(TAG) {
            "player created: extensions=${if (softwareAudioFallback) "ON" else "OFF"} lowRam=$lowRam " +
                "cap=${loadControl.profile.targetBytes} afr=${current.afrMode} tunneling=${current.tunneling}"
        }
        return bundle
    }

    companion object {
        private const val TAG = "PlayerFactory"
        private const val PLAYER_NAME = "thebox"

        /** `RELEASE_TIMEOUT_MS` (§6.3): release must not stall the main thread on broken codecs. */
        const val RELEASE_TIMEOUT_MS = 1_000L

        /** `STUCK_BUFFERING_BACKSTOP_MS` (§3.7): Media3's own stuck-buffering detector, lowered from 600 s. */
        const val STUCK_BUFFERING_BACKSTOP_MS = 30_000

        /** Archive FF/RW ±30 s (product §2.3 S8). */
        const val SEEK_INCREMENT_MS = 30_000L

        val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        /** §3.9.1: SEAMLESS → ExoPlayer's own ONLY_IF_SEAMLESS; OFF and ALWAYS → OFF (ALWAYS votes itself). */
        fun frameRateStrategyFor(mode: AfrMode): Int = when (mode) {
            AfrMode.SEAMLESS -> C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
            AfrMode.OFF, AfrMode.ALWAYS -> C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        }

        /** Preferred audio languages: settings, else the device locale (§3.8.6). */
        fun preferredAudioLanguages(settings: PlaybackSettings): List<String> =
            settings.preferredAudioLanguages.ifEmpty { listOfNotNull(Locale.getDefault().language.takeIf { it.isNotEmpty() }) }

        /** Track-selection defaults of §3.8.6 (tunneling per setting, languages, `ROLE_FLAG_MAIN`, text on/off). */
        fun initialParameters(context: Context, settings: PlaybackSettings): DefaultTrackSelector.Parameters =
            DefaultTrackSelector.Parameters.Builder(context)
                .setTunnelingEnabled(settings.tunneling)
                .setPreferredAudioLanguages(*preferredAudioLanguages(settings).toTypedArray())
                .setPreferredAudioRoleFlags(C.ROLE_FLAG_MAIN)
                .setPreferredTextLanguages(*settings.preferredTextLanguages.toTypedArray())
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !settings.subtitlesEnabled)
                .build()

        /** Passthrough denied for every encoding unless the output mode is AUTO (§3.8.3). */
        fun baseDeniedPassthrough(settings: PlaybackSettings): Set<String> =
            if (settings.audioOutput == AudioOutputMode.AUTO) emptySet() else setOf(AudioOutputPolicy.ALL)

        /** FFmpeg-preferred MIME types from the "prefer software audio" setting (§3.8.1). */
        fun baseSoftwareAudioMimes(settings: PlaybackSettings, ffmpegSupports: (String) -> Boolean): Set<String> =
            if (!settings.preferSoftwareAudio) {
                emptySet()
            } else {
                BoxMediaCodecSelector.FFMPEG_CANDIDATE_MIMES.filterTo(LinkedHashSet()) { mime ->
                    try {
                        ffmpegSupports(mime)
                    } catch (_: LinkageError) {
                        false
                    }
                }
            }

        /**
         * Applies the settings-derived switches (decoder mode, passthrough denials, forced stereo, FFmpeg preference,
         * tunneling). The controller unions in what the ladder learned (per channel / per route) after calling this.
         * Takes effect at the next prepare.
         */
        fun applySettings(bundle: PlayerBundle, settings: PlaybackSettings, ffmpegSupports: (String) -> Boolean = FfmpegAvailability::supports) {
            bundle.codecPolicy.videoMode = settings.videoDecoderMode
            bundle.codecPolicy.softwareAudioMimes = baseSoftwareAudioMimes(settings, ffmpegSupports)
            bundle.audioPolicy.deniedPassthrough = baseDeniedPassthrough(settings)
            bundle.audioPolicy.forceStereo = settings.audioOutput == AudioOutputMode.STEREO
            if (bundle.trackSelector.parameters.tunnelingEnabled != settings.tunneling) {
                bundle.trackSelector.setParameters(bundle.trackSelector.buildUponParameters().setTunnelingEnabled(settings.tunneling))
            }
        }
    }
}
