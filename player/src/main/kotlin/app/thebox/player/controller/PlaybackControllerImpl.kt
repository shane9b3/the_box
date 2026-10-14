package app.thebox.player.controller

// Commit-0 stub (docs/design/m2-playback-core.md §1.4–§1.7). Owner: WP-D. Replace the TODO bodies; keep the names.
import android.content.Context
import androidx.media3.common.Player
import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.PlaybackEventLog
import app.thebox.core.playback.VideoDecoderMode
import app.thebox.core.playback.Viewport
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.player.api.ArchiveTune
import app.thebox.player.api.ChannelMemory
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.PlaybackDiagnostics
import app.thebox.player.api.PlaybackNotice
import app.thebox.player.api.PlaybackSettingsSource
import app.thebox.player.api.PlaybackSourceProvider
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.PositionSnapshot
import app.thebox.player.api.StopReason
import app.thebox.player.api.StreamStats
import app.thebox.player.api.TrackInfo
import app.thebox.player.api.TrackRef
import app.thebox.player.api.TuneTarget
import app.thebox.player.api.VideoInfo
import app.thebox.player.api.ZapList
import app.thebox.player.internal.MediaSourceBuilderApi
import app.thebox.player.internal.PlayerFactoryApi
import app.thebox.player.internal.StreamBrokerRegistry
import app.thebox.player.internal.StreamUrlResolver
import app.thebox.player.internal.TransportFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The state machine of §3.1, ladder execution, zap list, previous channel, pause/resume, archive seam. */
@Singleton
internal class PlaybackControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val provider: PlaybackSourceProvider,
    private val memory: ChannelMemory,
    private val settings: PlaybackSettingsSource,
    private val resolver: StreamUrlResolver,
    private val brokers: StreamBrokerRegistry,
    private val transport: TransportFactory,
    private val playerFactory: PlayerFactoryApi,
    private val mediaSources: MediaSourceBuilderApi,
    private val eventLog: PlaybackEventLog,
    private val clock: MonoClock,
) : PlaybackController {
    override val state: StateFlow<PlaybackState> get() = TODO("WP-D")
    override val pendingZap: StateFlow<ChannelIdentity?> get() = TODO("WP-D")
    override val video: StateFlow<VideoInfo> get() = TODO("WP-D")
    override val tracks: StateFlow<TrackInfo> get() = TODO("WP-D")
    override val stats: StateFlow<StreamStats> get() = TODO("WP-D")
    override val notices: SharedFlow<PlaybackNotice> get() = TODO("WP-D")
    override val keepScreenOn: StateFlow<Boolean> get() = TODO("WP-D")
    override val displayModeRequest: StateFlow<Int> get() = TODO("WP-D")
    override val surfacePlayer: Player get() = TODO("WP-D")
    override val diagnostics: PlaybackDiagnostics get() = TODO("WP-D")

    override fun tune(target: TuneTarget, zapList: ZapList?): Unit = TODO("WP-D")
    override fun zap(delta: Int, isRepeat: Boolean): Unit = TODO("WP-D")
    override fun setZapList(list: ZapList): Unit = TODO("WP-D")
    override fun previousChannel(): Boolean = TODO("WP-D")
    override fun tuneArchive(tune: ArchiveTune): Unit = TODO("WP-D")
    override fun seekTo(positionMs: Long): Unit = TODO("WP-D")
    override fun position(): PositionSnapshot = TODO("WP-D")
    override fun pause(): Unit = TODO("WP-D")
    override fun resume(): Unit = TODO("WP-D")
    override fun stop(reason: StopReason): Unit = TODO("WP-D")
    override fun retry(): Unit = TODO("WP-D")
    override fun setViewport(viewport: Viewport): Unit = TODO("WP-D")
    override fun setStatsEnabled(enabled: Boolean): Unit = TODO("WP-D")
    override fun selectAudioTrack(track: TrackRef?, remember: Boolean): Unit = TODO("WP-D")
    override fun selectTextTrack(track: TrackRef?, remember: Boolean): Unit = TODO("WP-D")
    override fun setAspectMode(mode: AspectMode, remember: Boolean): Unit = TODO("WP-D")
    override fun setVideoDecoderOverride(mode: VideoDecoderMode?, remember: Boolean): Unit = TODO("WP-D")
    override fun prewarm(): Unit = TODO("WP-D")
}
