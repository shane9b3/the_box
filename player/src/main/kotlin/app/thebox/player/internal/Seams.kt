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
import app.thebox.core.playback.timeshift.BufferMode
import app.thebox.core.playback.timeshift.BufferOff
import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.RingReader
import app.thebox.core.playback.timeshift.RingWriterListener
import app.thebox.core.playback.timeshift.SegmentReason
import app.thebox.core.playback.timeshift.TsRing
import app.thebox.core.playback.timeshift.TsUpstream
import app.thebox.player.api.LocalBufferState
import app.thebox.player.api.PlaybackSettings
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
    /** (device finding 2026-10-18, L33r) The TS guard regenerates the audio track's timestamps this session. */
    val repairAudioTimestamps: Boolean = false,
)

internal interface TransportFactory {
    /** Progressive live TS: TeeDataSource(LiveTsDataSource core, sink). */
    fun liveTs(ctx: StreamSessionContext): Pair<DataSource.Factory, StreamTelemetry>
    /** HLS manifests/segments and finite archive files: brokered OkHttp, Range allowed, no reconnect. */
    fun http(ctx: StreamSessionContext): Pair<DataSource.Factory, StreamTelemetry>
    /** Ends the session's transport: cancels every call, waits for close, releases the lease. Idempotent, main-safe. */
    fun endSession(tuneId: Long)
    /**
     * (M6 time-shift commit 0, docs/design/m6-timeshift.md §5.1, §16.4) A reader over the ring for one player session.
     * Registers the reader as a Terminable in ctx.tuneId's scope so endSession(readerTuneId) cancels it (A-S9).
     */
    fun ring(ctx: StreamSessionContext, open: RingOpen): Pair<DataSource.Factory, StreamTelemetry>
    /**
     * (M6 time-shift, lead reconciliation R9, §4.2) The recorder's upstream: one `LiveTsDataSource` with [hooks] per writer
     * (re)start, in the transport session of ctx.tuneId (the cycle's `writerTuneId`), so endSession(writerTuneId) cancels
     * its call and releases its lease exactly as for a player session (§3.3.7).
     */
    fun writer(ctx: StreamSessionContext, hooks: WriterHooks): Pair<TsUpstream, StreamTelemetry>
}

/**
 * (M6 time-shift, R9, §4.2, A-N1, A-C4) What the recorder's `LiveTsDataSource` gets that no other caller does. All three
 * are called on the pump thread (inside `read()`), never block, and never touch the player.
 */
internal class WriterHooks(
    /** A post-splice PCR jump was kept (A-N1): the held packets were released and the indexer places the boundary. */
    val onPcrJump: (deltaMs: Long) -> Unit,
    /**
     * Asked before every in-source reconnect attempt (A-C4, `RingWriter.reconnectAllowed`) with the failure's cause and
     * whether the attempt opens the loss (fix pass B1); false ends the read with `RECONNECT_EXHAUSTED`.
     */
    val reconnectAllowed: (cause: app.thebox.core.playback.timeshift.ReconnectCause, firstOfLoss: Boolean) -> Boolean,
    /** `RECONNECT_OK`: the next index entry is a splice (§3.4 item 6, `TsIndexer.onSplice`), called before the spliced bytes are returned. */
    val onSplice: () -> Unit,
)

/** (M6 time-shift commit 0, §16.4) What a ring reader opens: [startOffset] is a RAP's packet of [generation]. */
internal class RingOpen(
    val ring: TsRing,
    val generation: Long,
    val startOffset: Long,
    val telemetry: StreamTelemetry,
    /**
     * (Fix pass — playback, S7) The cycle's first reader: wait (bounded, `FIRST_RAP_WAIT_MS`) for the generation's first
     * RAP at or after [startOffset] and open there, so position 0 is that RAP's PTS (the controller's playhead origin).
     */
    val firstRap: Boolean = false,
)

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
    /** (M6 time-shift commit 0, §16.4) [ring] non-null: a progressive TS source over [TransportFactory.ring] (live only). */
    fun build(ctx: StreamSessionContext, format: StreamFormat, compat: TsCompat, transport: TransportFactory, archive: app.thebox.core.catchup.ArchiveRequest?, ring: RingOpen? = null): MediaSourceWithTelemetry
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

// ---------------------------------------------------------------- M6 time-shift (docs/design/m6-timeshift.md §16.4; WP-B)

/** (reviewed 2026-10-19, A-C1) Split: IO preparation in parallel with the resolve, then a main-thread start. */
internal interface TimeshiftSessionFactory {
    /**
     * IO only (sweep wait, StatFs, mkdir, first extent); needs no resolved stream (A-N5). The three time-shift settings
     * come in [PlaybackSettings] like every other playback setting (lead reconciliation R10).
     */
    suspend fun prepare(settings: PlaybackSettings, maxConnections: Int): TimeshiftPrepared
    /** Skip speculative work superseded while waiting for the serialized storage preparation. */
    suspend fun prepare(settings: PlaybackSettings, maxConnections: Int, needed: () -> Boolean): TimeshiftPrepared =
        if (needed()) prepare(settings, maxConnections) else TimeshiftPrepared.Off(BufferOff.SETTING_OFF)
    /** Main thread, after the resolve: builds the writer from [writerCtx] (resolved stream, writerTuneId) and starts the pump. */
    fun start(grant: TimeshiftGrant, writerCtx: StreamSessionContext, listener: RingWriterListener): TimeshiftSession
    /**
     * (Fix pass — playback, S3) The cycle ended before [start] used [grant]: the grant no longer holds the ring, and with
     * [discardFile] the file is discarded (stop, lifecycle). Thread-safe ownership change; disk I/O runs in the background.
     */
    fun abandon(grant: TimeshiftGrant, discardFile: Boolean) = Unit
    /** A rejected preparation may have no grant, but an earlier cycle can still have left an idle reusable ring. */
    fun discardUnused() = Unit
    /**
     * (Storage locations §5.5, §10; Q-H3 / lead ruling R10, closure fix pass 2026-10-28) [snap] with the cycle's storage
     * fields (where it saves, why it falls back, why it stopped, the location, the folder's rates). Main, ≤ 1 Hz; no I/O.
     * Default: [snap] as is (a factory without storage locations).
     */
    fun storageFields(grant: TimeshiftGrant, snap: LocalBufferState): LocalBufferState = snap
    /**
     * (Storage locations §5.3; Q-D1 / lead ruling R2) The guide's preview started: warm the save location (the keeper's
     * connect and the `.wake` write). Called off main (IO); never blocks on the network. Default: nothing.
     */
    fun warmSaveLocation() = Unit
}

/** Answer of [TimeshiftSessionFactory.prepare]: storage granted, or why the cycle is not buffered. */
internal sealed interface TimeshiftPrepared { class Granted(val grant: TimeshiftGrant) : TimeshiftPrepared; class Off(val reason: BufferOff) : TimeshiftPrepared }

/** Storage reserved for one cycle (a pre-first-byte retry restarts the writer instead, fix pass B2). */
internal class TimeshiftGrant(
    val ring: TsRing, val capacityBytes: Long, val mode: BufferMode, val holdLimitMs: Long?,
    /** (Storage locations commit 0, docs/design/m6-storage-locations.md §5.1, §12.4) The cycle's target choice. */
    val choice: app.thebox.core.playback.timeshift.RingTargetChoice = app.thebox.player.timeshift.InternalRingTargets.UNSIZED,
)

/**
 * One controller cycle's time-shift (§2 "Ownership"): the writer under its own [writerTuneId], the ring generation and
 * the stashed ladder error (§7.4). Closing it cancels the writer's call first, then releases the lease (A-S1).
 */
internal interface TimeshiftSession : AutoCloseable {
    val writerTuneId: Long
    val ring: TsRing
    val writerTelemetry: StreamTelemetry
    /** A writer `Fail` decided while behind live, shown once the saved part has played out (§7.4). */
    var stashed: Pair<PlaybackError, FailureSignal>?
    fun onPause(nowMonoMs: Long); fun onResume()
    fun restartWriter(resolved: ResolvedStream, reason: SegmentReason)
    fun readerTelemetry(reader: () -> RingReader?): StreamTelemetry      // §5.2
    fun snapshot(nowMonoMs: Long, wallMinusMonoMs: Long): LocalBufferState

    // (lead reconciliation R8: WP-B's TimeshiftSessionControl folded in; the controller needs no cast.)
    /** The storage this session writes into. */
    val grant: TimeshiftGrant
    /** The writer committed its first bytes since the session started or last restarted. */
    val firstBytes: Boolean
    /** A writer restart was posted and its first bytes have not arrived (§5.2 `inReconnect`). */
    val restartPending: Boolean
    /** The pause hold ([onPause]) is active. */
    val paused: Boolean
    /** Why saving stopped (null while saving; never SESSION_END), whether the writer stopped itself or [stopSaving] did. */
    val stopReason: RecordStop?
    val stoppedAtMonoMs: Long?
    /** A writer restart is about to run (after a ladder delay): the reader telemetry reports a reconnect meanwhile (A-B3). */
    fun markRestartPending()
    /** Stops saving with [reason] and releases the connection (SLOT_LOST, PREEMPTED, UPSTREAM_FAILED; §4.4). Main. */
    fun stopSaving(reason: RecordStop)
    /** Ends the session; [discardFile] also closes and deletes the ring file (stop, lifecycle; §8.3). [close] = keep it. */
    fun close(discardFile: Boolean)
}
