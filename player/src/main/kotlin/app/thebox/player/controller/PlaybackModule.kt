package app.thebox.player.controller

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import app.thebox.core.playback.BufferProfileFsm
import app.thebox.core.playback.ErrorLadder
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.PlaybackEventLog
import app.thebox.core.playback.ReResolveLimiter
import app.thebox.core.playback.StreamUrlBuilder
import app.thebox.core.playback.ZapDebouncer
import app.thebox.core.playback.broker.ZapDebouncerImpl
import app.thebox.core.playback.buffer.BufferProfileFsmImpl
import app.thebox.core.playback.diag.PlaybackEventLogImpl
import app.thebox.core.playback.errors.ErrorLadderImpl
import app.thebox.core.playback.errors.ReResolveLimiterImpl
import app.thebox.core.playback.resolve.StreamUrlBuilderImpl
import app.thebox.core.playback.stall.StallDetectorsImpl
import app.thebox.core.playback.stall.StallThresholds
import app.thebox.core.playback.afr.DisplayModePickerImpl
import app.thebox.core.playback.AfrMode
import app.thebox.core.playback.StreamFormat
import android.os.Looper
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import app.thebox.player.api.PlaybackSettings
import app.thebox.player.api.PlaybackSettingsSource
import app.thebox.player.datasource.LiveTsDataSource
import app.thebox.player.engine.FpsEstimator
import app.thebox.player.internal.StreamTelemetry
import kotlinx.coroutines.flow.asStateFlow
import app.thebox.ffmpeg.FfmpegAvailability
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.StreamStats
import app.thebox.player.api.TrackInfo
import app.thebox.player.broker.StreamBrokerRegistryImpl
import app.thebox.player.datasource.TransportFactoryImpl
import app.thebox.player.engine.DeviceCapabilities
import app.thebox.player.engine.DisplayModeController
import app.thebox.player.engine.MediaSourceBuilder
import app.thebox.player.engine.PlaybackWatchdog
import app.thebox.player.engine.PlayerFactory
import app.thebox.player.engine.StatsCollector
import app.thebox.player.engine.TrackController
import app.thebox.player.internal.DisplayModeControllerApi
import app.thebox.player.internal.MediaSourceBuilderApi
import app.thebox.player.internal.PlayerBundle
import app.thebox.player.internal.PlayerFactoryApi
import app.thebox.player.internal.PlaybackWatchdogApi
import app.thebox.player.internal.StreamBrokerRegistry
import app.thebox.player.internal.StreamUrlResolver
import app.thebox.player.internal.TransportFactory
import app.thebox.player.resolve.StreamUrlResolverImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `:player` bindings (§2.8). Needs from the graph: `@ApplicationContext Context`, the unqualified `OkHttpClient`
 * (`:data`), and `PlaybackSourceProvider`/`ChannelMemory`/`PlaybackSettingsSource` (bound in `:app`).
 *
 * The `:core` logic objects (ladder, limiter, debouncer, buffer FSM) are provided here so the controller receives them
 * as interfaces (Robolectric tests pass fakes); the engine helpers that are built per player come through
 * [EngineEnvironment].
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class PlaybackModule {
    @Binds abstract fun controller(impl: PlaybackControllerImpl): PlaybackController
    @Binds abstract fun resolver(impl: StreamUrlResolverImpl): StreamUrlResolver
    @Binds abstract fun brokers(impl: StreamBrokerRegistryImpl): StreamBrokerRegistry
    @Binds abstract fun transport(impl: TransportFactoryImpl): TransportFactory
    @Binds abstract fun playerFactory(impl: PlayerFactory): PlayerFactoryApi
    @Binds abstract fun mediaSources(impl: MediaSourceBuilder): MediaSourceBuilderApi
    @Binds abstract fun engine(impl: DefaultEngineEnvironment): EngineEnvironment
    /** M3 catch-up seam (docs/design/m3-guide.md §2.8). */
    @Binds abstract fun archiveTargets(impl: app.thebox.player.resolve.ArchiveTargetsImpl): app.thebox.player.api.ArchiveTargets

    companion object {
        @Provides fun monoClock(): MonoClock = MonoClock { SystemClock.elapsedRealtime() }

        @Provides fun streamUrlBuilder(): StreamUrlBuilder = StreamUrlBuilderImpl()

        @Provides @Singleton fun eventLog(clock: MonoClock): PlaybackEventLog = PlaybackEventLogImpl(clock)

        /** The stream-switch timeout is a setting the `LadderContext` does not carry (WP-A gap 1). */
        @Provides fun errorLadder(settings: PlaybackSettingsSource): ErrorLadder =
            ErrorLadderImpl(streamSwitchTimeoutMs = { settings.current.streamSwitchTimeoutMs })

        /** Survives re-tunes of the same channel (§4.5), so one per process. */
        @Provides @Singleton fun reResolveLimiter(): ReResolveLimiter = ReResolveLimiterImpl()

        @Provides fun zapDebouncer(): ZapDebouncer = ZapDebouncerImpl()

        /** Per-playlist levels live in the FSM, so one per process. */
        @Provides @Singleton fun bufferFsm(): BufferProfileFsm = BufferProfileFsmImpl()

        @Provides fun sessionFactory(): MediaSessionFactory = MediaSessionFactory.Default

        @Provides fun deviceState(@ApplicationContext context: Context): LifecycleCoordinator.DeviceState =
            LifecycleCoordinator.AndroidDeviceState(context)
    }
}

/**
 * Process-wide engine facts and the per-player WP-C helpers the controller drives (§2.5 seams plus the stats/track
 * mappers). The controller only sees this interface; [DefaultEngineEnvironment] builds the real WP-C classes and is the
 * one place that adapts to their constructors. Defaults are no-ops so test fakes only implement what they observe.
 */
internal interface EngineEnvironment {
    /** `DeviceCapabilities.lowRam` (§3.6.1). */
    val isLowRam: Boolean
    /** S16 capability dump (decoders, HDR, display modes, audio encodings, FFmpeg). Called off the main thread. */
    fun capabilities(): Map<String, String>
    /** `FfmpegAvailability.supports(mime)` (ladder L32). */
    fun ffmpegSupports(mimeType: String): Boolean
    /** A software-only platform decoder exists for [mimeType] (ladder L29). */
    fun softwareVideoDecoderAvailable(mimeType: String?): Boolean
    /** Called once, with the process player. */
    fun createParts(bundle: PlayerBundle): EngineParts
    /** `PlayerFactory.applySettings`: the settings-derived switches; the controller unions what the ladder learned after it. */
    fun applySettings(bundle: PlayerBundle, settings: PlaybackSettings) {}
    /** `DeviceCapabilities.observeAudioRoute`: [onChanged] on the main thread at every route change. Returns unregister. */
    fun observeAudioRoute(onChanged: () -> Unit): () -> Unit = {}
}

/** WP-C helpers bound to the one player. Main thread only. */
internal interface EngineParts {
    val watchdog: PlaybackWatchdogApi
    val displayMode: DisplayModeControllerApi
    /** Snapped fps of the current video (FpsEstimator → DisplayModePicker.snapFps), null while unknown. */
    val fps: StateFlow<Float?>
    /** Codec facts (MIME, decoders, size, dropped frames, passthrough, AFR); the controller adds the session facts. */
    fun stats(): StreamStats
    /** `TrackInfo` from the player's tracks (§3.12); [tracks] is the `onTracksChanged` argument. */
    fun tracks(tracks: Tracks): TrackInfo

    /**
     * A new session was prepared: watchdog `beginSession` + `setSessionKind` + `bind`, stats `bind`, fps estimator
     * `reset` (and [fps] back to null).
     */
    fun beginSession(telemetry: StreamTelemetry?, format: StreamFormat, isLive: Boolean, isHls: Boolean) {}
    /** B2 first-media milestone from the watchdog (true = first frame, false = first audio advance), once per session. */
    fun setFirstMediaListener(listener: ((video: Boolean) -> Unit)?) {}
    /** Stats overlay on/off (also decides whether the fps estimator is attached). */
    fun setStatsEnabled(enabled: Boolean) {}
    /** Tune defaults of §3.8.6 (`TrackController.applyTuneDefaults`). */
    fun applyTuneDefaults(audioLanguages: List<String>, textLanguages: List<String>, subtitles: String?, subtitlesEnabled: Boolean) {}
    /** AFR setting changed (S12). */
    fun onAfrModeChanged(mode: AfrMode) {}
    /** API < 31 display-mode switch while playing: pause on [onStarted], resume on [onSettled]. */
    fun setDisplaySwitchListener(onStarted: () -> Unit, onSettled: () -> Unit) {}
    fun release() {}
}

@Singleton
@OptIn(UnstableApi::class)
internal class DefaultEngineEnvironment @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilities: DeviceCapabilities,
    private val settings: PlaybackSettingsSource,
    private val clock: MonoClock,
) : EngineEnvironment {
    override val isLowRam: Boolean get() = capabilities.isLowRam
    override fun capabilities(): Map<String, String> = capabilities.dump()
    override fun ffmpegSupports(mimeType: String): Boolean = try {
        FfmpegAvailability.supports(mimeType)
    } catch (e: LinkageError) {
        false
    }

    override fun softwareVideoDecoderAvailable(mimeType: String?): Boolean {
        if (mimeType == null) return false
        return try {
            MediaCodecUtil.getDecoderInfos(mimeType, false, false).any { it.softwareOnly }
        } catch (e: MediaCodecUtil.DecoderQueryException) {
            false
        }
    }

    override fun createParts(bundle: PlayerBundle): EngineParts = DefaultEngineParts(context, bundle, settings, clock)

    override fun applySettings(bundle: PlayerBundle, settings: PlaybackSettings) =
        PlayerFactory.applySettings(bundle, settings, ::ffmpegSupports)

    override fun observeAudioRoute(onChanged: () -> Unit): () -> Unit = capabilities.observeAudioRoute(onChanged)
}

/** The WP-C classes behind [EngineParts] (wiring per the WP-C hand-off, integration 2026-10-13). */
@OptIn(UnstableApi::class)
internal class DefaultEngineParts(
    context: Context,
    private val bundle: PlayerBundle,
    private val settings: PlaybackSettingsSource,
    clock: MonoClock,
) : EngineParts {
    private val player = bundle.player
    private val picker = DisplayModePickerImpl()
    private val statsCollector = StatsCollector(player, clock)
    private val trackController = TrackController(bundle)
    private val watchdogImpl = PlaybackWatchdog(
        player,
        StallDetectorsImpl(StallThresholds(tsNoBytesBackstopMs = 2 * LiveTsDataSource.NO_BYTES_TIMEOUT_MS)),
        clock,
        profile = { bundle.loadControl.profile },
    )
    private val displayModeImpl = DisplayModeController.create(
        context, picker, clock, afrMode = { settings.current.afrMode }, setPlayerStrategy = { player.setVideoChangeFrameRateStrategy(it) },
    )
    private val fpsState = MutableStateFlow<Float?>(null)
    private val fpsEstimator = FpsEstimator(picker::snapFps, Looper.getMainLooper()) { onEstimate(it) }
    private var fpsAttached = false
    private var fpsDone = false
    private var statsOn = false

    override val watchdog: PlaybackWatchdogApi get() = watchdogImpl
    override val displayMode: DisplayModeControllerApi get() = displayModeImpl
    override val fps: StateFlow<Float?> = fpsState.asStateFlow()

    /** Video format changes feed the track facts (TS: SPS/colour only known at the decoder input). */
    private val formatListener = object : AnalyticsListener {
        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            trackController.onVideoFormat(format)
        }
    }

    init {
        player.addAnalyticsListener(formatListener)
    }

    override fun stats(): StreamStats {
        statsCollector.afr = settings.current.afrMode
        statsCollector.displayRefreshHz = displayModeImpl.refreshHz.value
        statsCollector.fps = fpsState.value ?: trackController.video.declaredFps
        return statsCollector.snapshot()
    }

    override fun tracks(tracks: Tracks): TrackInfo = trackController.update(tracks)

    override fun beginSession(telemetry: StreamTelemetry?, format: StreamFormat, isLive: Boolean, isHls: Boolean) {
        watchdogImpl.bind(telemetry)
        watchdogImpl.setSessionKind(isLive, isHls)
        watchdogImpl.beginSession()
        statsCollector.bind(telemetry, format)
        fpsState.value = null
        fpsDone = false
        fpsEstimator.reset()
        updateFpsAttachment()
    }

    override fun setFirstMediaListener(listener: ((video: Boolean) -> Unit)?) {
        watchdogImpl.firstMediaListener = listener
    }

    override fun setStatsEnabled(enabled: Boolean) {
        statsOn = enabled
        statsCollector.setEnabled(enabled)
        updateFpsAttachment()
    }

    override fun applyTuneDefaults(audioLanguages: List<String>, textLanguages: List<String>, subtitles: String?, subtitlesEnabled: Boolean) =
        trackController.applyTuneDefaults(audioLanguages, textLanguages, subtitles, subtitlesEnabled)

    override fun onAfrModeChanged(mode: AfrMode) {
        displayModeImpl.onAfrModeChanged(mode)
        updateFpsAttachment()
    }

    override fun setDisplaySwitchListener(onStarted: () -> Unit, onSettled: () -> Unit) {
        displayModeImpl.switchListener = object : DisplayModeController.SwitchListener {
            override fun onSwitchStarted() = onStarted()
            override fun onSwitchSettled() = onSettled()
        }
    }

    override fun release() {
        detachFps()
        player.removeAnalyticsListener(formatListener)
        watchdogImpl.release()
        statsCollector.release()
        displayModeImpl.release()
    }

    /** §3.9.3: the estimator is attached only while AFR is ALWAYS or the stats overlay is on, until it has an estimate. */
    private fun updateFpsAttachment() {
        val wanted = !fpsDone && (statsOn || settings.current.afrMode == AfrMode.ALWAYS)
        if (wanted && !fpsAttached) {
            player.setVideoFrameMetadataListener(fpsEstimator)
            fpsAttached = true
        } else if (!wanted) {
            detachFps()
        }
    }

    private fun detachFps() {
        if (!fpsAttached) return
        player.clearVideoFrameMetadataListener(fpsEstimator)
        fpsAttached = false
    }

    private fun onEstimate(fps: Float) {
        fpsDone = true
        detachFps()
        fpsState.value = fps
    }
}
