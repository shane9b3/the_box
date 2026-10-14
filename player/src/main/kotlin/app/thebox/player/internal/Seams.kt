// DataSink, MediaSource and DefaultTrackSelector are @UnstableApi (§6.7).
@file:OptIn(UnstableApi::class)

package app.thebox.player.internal

import android.os.Looper
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import app.thebox.core.playback.*
import kotlinx.coroutines.flow.StateFlow

// ---------------------------------------------------------------- WP-A: resolver and broker

internal interface StreamUrlResolver {
    /** Provider reads + StreamUrlBuilder. Never throws; failures are ResolveOutcome.Failed. */
    suspend fun resolve(target: TuneTargetKey, baseIndex: Int? = null, output: XtreamOutput? = null, userAgentOverride: String? = null): ResolveOutcome
    /** CatchupTarget for M4 (liveUrl and headers keep {{u}}/{{p}}; xtream carries clear credentials). */
    suspend fun catchupTarget(identity: app.thebox.core.refresh.ChannelIdentity): app.thebox.core.catchup.CatchupTarget?
    /** Expands credentials in an ArchiveRequest built by CatchupUrlBuilder (the M2 "last" step, §3.14). */
    suspend fun expandArchive(identity: app.thebox.core.refresh.ChannelIdentity, request: app.thebox.core.catchup.ArchiveRequest): ResolveOutcome
}

/** Tune target without UI types: a channel identity or a direct URL. */
internal sealed interface TuneTargetKey {
    data class Channel(val identity: app.thebox.core.refresh.ChannelIdentity) : TuneTargetKey
    data class Direct(val url: String, val headers: Map<String, String>, val maxConnections: Int, val formatHint: StreamFormat) : TuneTargetKey
}

internal interface StreamBrokerRegistry {
    fun broker(key: String): ConnectionBrokerCore
    /** max = override ?: account.maxConnections ?: 1, clamped to 1..MAX_CONNECTIONS_CAP. */
    fun configure(key: String, maxConnections: Int)
}

// ---------------------------------------------------------------- WP-B: transport

/** Live counters of one session, written by data sources (any thread), read by stats/watchdog (main). */
internal interface StreamTelemetry {
    val bytesTotal: Long
    val lastByteAtMs: Long
    val firstByteAtMs: Long
    val bitrateBps: Long
    val httpStatus: Int
    val finalHost: String?
    val redirects: Int
    val inReconnect: Boolean
    val reconnects: Int
    val sniffed: StreamFormat
    /** (amended 2026-10-13) True while an open is blocked in `broker.acquire` (the controller's WAITING_FOR_SLOT). */
    val waitingForLease: Boolean get() = false
    /**
     * (amended 2026-10-14, code review E-B1/N-S2) Monotonic time at which the live source's current `read()` started
     * waiting on the socket, or 0 when no read is pending (the loader is parked on a full buffer, or between reads).
     * The watchdog's TS no-bytes age is measured only while this is non-zero (§3.3.6, §3.7 a).
     */
    val readPendingSinceMs: Long get() = 0L
}

/** Everything a data source of one tune needs. Created by WP-D's TuneSession, consumed by WP-B. */
internal class StreamSessionContext(
    val tuneId: Long,
    val resolved: ResolvedStream,
    val broker: ConnectionBrokerCore,
    val leaseKind: LeaseKind,
    /** v2 recorder seam: null = NoOpDataSink. */
    val sinkFactory: DataSink.Factory?,
    /** Session events for the event log and the controller (called on loader threads; must not block). */
    val onEvent: (type: PlaybackEventType, code: String, detail: String) -> Unit,
)

internal interface TransportFactory {
    /** Progressive live TS: TeeDataSource(LiveTsDataSource core, sink). */
    fun liveTs(ctx: StreamSessionContext): Pair<DataSource.Factory, StreamTelemetry>
    /** HLS manifests/segments and finite archive files: brokered OkHttp, Range allowed, no reconnect. */
    fun http(ctx: StreamSessionContext): Pair<DataSource.Factory, StreamTelemetry>
    /** Ends the session's transport: cancels every call, waits for close, releases the lease. Idempotent, main-safe. */
    fun endSession(tuneId: Long)
}

// ---------------------------------------------------------------- WP-C: engine

internal class PlayerBundle(
    val player: ExoPlayer,
    val trackSelector: DefaultTrackSelector,
    val loadControl: AdaptiveLoadControlHandle,
    val codecPolicy: CodecPolicyHandle,
    val audioPolicy: AudioPolicyHandle,
)

internal interface PlayerFactoryApi {
    /** Main looper. [softwareAudioFallback] chooses EXTENSION_RENDERER_MODE_ON vs OFF. */
    fun create(looper: Looper, softwareAudioFallback: Boolean): PlayerBundle
}

internal interface AdaptiveLoadControlHandle { var profile: BufferProfile }

internal interface CodecPolicyHandle {
    var videoMode: VideoDecoderMode
    /** MIME types for which platform decoders are hidden so the FFmpeg renderer is chosen. */
    var softwareAudioMimes: Set<String>
}

internal interface AudioPolicyHandle {
    /** Encodings (as MIME types) never sent as passthrough on the current route. "*" = all. */
    var deniedPassthrough: Set<String>
    var forceStereo: Boolean
}

internal enum class TsCompat { DEFAULT, MULTI_PMT }

internal interface MediaSourceBuilderApi {
    fun build(ctx: StreamSessionContext, format: StreamFormat, compat: TsCompat, transport: TransportFactory, archive: app.thebox.core.catchup.ArchiveRequest?): MediaSourceWithTelemetry
}
internal class MediaSourceWithTelemetry(val source: MediaSource, val telemetry: StreamTelemetry)

internal interface PlaybackWatchdogApi {
    fun start(onStall: (StallVerdict) -> Unit)
    fun stop()
    fun bind(telemetry: StreamTelemetry?)
    fun reset()
    /** Viewport/poster/track changes that make video or audio (un)expected. */
    fun setExpectations(videoExpected: Boolean, audioExpected: Boolean)
}

internal interface DisplayModeControllerApi {
    val request: StateFlow<Int>
    val refreshHz: StateFlow<Float?>
    fun attachSurfaceView(view: SurfaceView?)
    fun onTune(knownFpsMilli: Int?)
    fun onFps(fps: Float)
    fun onViewport(viewport: Viewport)
    /** Suspends playback start until the display settled (API < 31 ALWAYS path); returns immediately otherwise. */
    fun awaitSettled(onSettled: () -> Unit)
    fun clear()
}
