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

    companion object {
        @Provides fun monoClock(): MonoClock = MonoClock { SystemClock.elapsedRealtime() }

        @Provides fun streamUrlBuilder(): StreamUrlBuilder = StreamUrlBuilderImpl()

        @Provides @Singleton fun eventLog(clock: MonoClock): PlaybackEventLog = PlaybackEventLogImpl(clock)

        @Provides fun errorLadder(): ErrorLadder = ErrorLadderImpl()

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
 * one place the integration pass adapts when their constructors change.
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
}

/** WP-C helpers bound to the one player. */
internal interface EngineParts {
    val watchdog: PlaybackWatchdogApi
    val displayMode: DisplayModeControllerApi
    /** Snapped fps of the current video (FpsEstimator → DisplayModePicker.snapFps), null while unknown. */
    val fps: StateFlow<Float?>
    /** Codec facts (MIME, decoders, size, dropped frames, passthrough, AFR); the controller adds the session facts. */
    fun stats(): StreamStats
    /** `TrackInfo` from the player's current tracks (§3.12). */
    fun tracks(): TrackInfo
}

@Singleton
@OptIn(UnstableApi::class)
internal class DefaultEngineEnvironment @Inject constructor(
    private val capabilities: DeviceCapabilities,
) : EngineEnvironment {
    override val isLowRam: Boolean get() = capabilities.isLowRam
    override fun capabilities(): Map<String, String> = capabilities.dump()
    override fun ffmpegSupports(mimeType: String): Boolean = FfmpegAvailability.supports(mimeType)

    override fun softwareVideoDecoderAvailable(mimeType: String?): Boolean {
        if (mimeType == null) return false
        return try {
            MediaCodecUtil.getDecoderInfos(mimeType, false, false).any { it.softwareOnly }
        } catch (e: MediaCodecUtil.DecoderQueryException) {
            false
        }
    }

    override fun createParts(bundle: PlayerBundle): EngineParts = DefaultEngineParts(bundle)
}

/** The WP-C classes behind [EngineParts]. Integration: adapt these constructor calls to the merged WP-C classes. */
@OptIn(UnstableApi::class)
private class DefaultEngineParts(bundle: PlayerBundle) : EngineParts {
    private val statsCollector = StatsCollector().also { bundle.player.addAnalyticsListener(it) }
    private val trackController = TrackController(bundle)
    override val watchdog: PlaybackWatchdogApi = PlaybackWatchdog(StallDetectorsImpl())
    override val displayMode: DisplayModeControllerApi = DisplayModeController()
    override val fps: StateFlow<Float?> = MutableStateFlow(null)
    override fun stats(): StreamStats = statsCollector.snapshot()
    override fun tracks(): TrackInfo = trackController.current()
}
