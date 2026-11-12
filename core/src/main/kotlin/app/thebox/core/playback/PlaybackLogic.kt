package app.thebox.core.playback

import app.thebox.core.refresh.ChannelIdentity

// ------------------------------------------------------------------ resolution (WP-A, §3.2)

/** Pure resolution. Throws nothing: an impossible input yields [ResolveOutcome.Failed]. */
interface StreamUrlBuilder {
    fun build(input: ResolveInput): ResolveOutcome
}

sealed interface ResolveOutcome {
    data class Ok(val stream: ResolvedStream) : ResolveOutcome
    /** e.g. an Xtream template with no credentials, or a `{{u}}` template on a playlist without a username. */
    data class Failed(val code: PlaybackErrorCode, val reason: String) : ResolveOutcome
}

/**
 * Template expansion in the M2 order (§3.2.4): non-secret placeholders first, then credentials, each credential
 * encoded for its position (path segment before '?', query value after). Single pass; substituted values are never
 * re-scanned. Unknown `{{x}}` placeholders are left as written.
 */
interface CredentialExpander {
    fun expandNonSecret(template: String, base: String?, extension: String?): String
    fun expandCredentials(template: String, username: String?, password: String?): String
    /** Header values: credentials substituted raw (no URL encoding). */
    fun expandHeaderValue(value: String, username: String?, password: String?): String
}

// ------------------------------------------------------------------ broker (WP-A, §3.5)

interface ConnectionBrokerCore {
    val key: String
    fun configure(maxConnections: Int)
    /** Cancels every handle of ACTIVE PLAYBACK leases (synchronously) and marks them CLOSING. Returns how many. */
    fun preemptPlayback(reason: String): Int
    /**
     * Blocking acquire for [kind] on behalf of tune [ownerTuneId]. Pre-empts another owner's PLAYBACK lease when
     * needed, waits up to [waitMs] for CLOSING leases to release, then grants. Throws [ConnectionLimitException]
     * when the slots are held by leases this kind may not pre-empt. Thread-safe; called on loader threads.
     */
    fun acquire(kind: LeaseKind, ownerTuneId: Long, waitMs: Long): CoreLease
    fun snapshot(): BrokerSnapshot
}

interface CoreLease {
    val id: Long
    val kind: LeaseKind
    val ownerTuneId: Long
    /** True once pre-empted: the holder must stop reconnecting and close. */
    val isPreempted: Boolean
    /** Registers an in-flight connection. Returns false (and the caller must cancel it) if the lease is already pre-empted. */
    fun attach(handle: ConnectionHandle): Boolean
    fun detach(handle: ConnectionHandle)
    /** Idempotent. Call only after every attached connection is cancelled/closed. */
    fun release()
}

/** Leading-edge + trailing coalescer for zaps (§3.5.4). Pure; the controller drives it with a clock and a scheduler. */
interface ZapDebouncer {
    /** Returns IMMEDIATE (tune now) or DEFERRED (a trailing tune is (re)scheduled at [ZapPlan.fireAtMs]). */
    fun onZap(nowMs: Long, isRepeat: Boolean): ZapPlan
    fun onFired()
    fun reset()
}
data class ZapPlan(val immediate: Boolean, val fireAtMs: Long)

// ------------------------------------------------------------------ errors (WP-A, §4)

data class LadderContext(
    val nowMs: Long,
    val msSinceTuneStart: Long,
    /**
     * The "first media" milestone of §0.16 (reviewed 2026-10-13, B2): first rendered video frame when a video track is
     * selected and enabled, else the first onAudioPositionAdvancing (radio, HDR poster, HIDDEN).
     */
    val firstFrameRendered: Boolean,
    val isLive: Boolean,
    val isArchive: Boolean,
    val format: StreamFormat,
    val isXtream: Boolean,
    val baseCount: Int,
    val output: XtreamOutput?,
    /** Attempt counters for the current tune cycle (reset on tune(), on 30 s of healthy playback, §4.5). */
    val counters: LadderCounters,
    val reResolveAllowed: Boolean,
    val reconnectAttemptsSetting: Int,
    val tunnelingActive: Boolean,
    val passthroughActive: Boolean,
    val passthroughMime: String?,
    val audioChannelCount: Int,
    val videoDecoderMode: VideoDecoderMode,
    val softwareVideoAvailable: Boolean,
    val ffmpegSupportsMime: (String) -> Boolean,
    val uaFallbackAvailable: Boolean,
    val backupAvailable: Boolean,
    val tsCompatLevel: Int,
    /** Fresh account facts when the ladder asked for a refresh, else the cached card. */
    val accountActive: Int?,
    val accountMax: Int?,
    val accountStatus: AccountGate,
    val lastHost: String?,
    /**
     * Ms since the current stream-switch window began: the tune, or the one pipeline repair per cycle (tunneling off,
     * software video/audio, passthrough off, TS compat raise) that restarts the 15 s window (device finding 2026-10-17:
     * a repair decided at 14 s was failed by the deadline 0.6 s later). Defaults to [msSinceTuneStart].
     */
    val msSinceSwitchWindowStart: Long = msSinceTuneStart,
)

enum class AccountGate { UNKNOWN, ACTIVE, EXPIRED, DISABLED, AUTH_FAILED }

data class LadderCounters(
    val limitSteps: Int = 0,
    val forbiddenSteps: Int = 0,
    val formatFlips: Int = 0,
    val sourceTypeSwitches: Int = 0,
    val transientRetries: Int = 0,
    val baseRotations: Int = 0,
    val pcrResets: Int = 0,
    val behindLiveWindow: Int = 0,
    val parsingRetries: Int = 0,
    val decoderSteps: Int = 0,
    val audioSteps: Int = 0,
    val stallRecoveries: Int = 0,
    val unknownRetries: Int = 0,
    val uaFallbackTried: Boolean = false,
)

interface ErrorLadder {
    /** Pure. Also returns the counters to store for the next failure in this cycle. */
    fun decide(signal: FailureSignal, ctx: LadderContext): Pair<LadderDecision, LadderCounters>

    /**
     * (amended 2026-10-13) Scopes the per-channel windows that outlive a cycle's [LadderCounters] (5-min compat
     * escalation, PCR/behind-live/reclaim windows). Called on every tune with the channel's stable key; a different key
     * clears them. Default: no windows.
     */
    fun beginChannel(channelKey: String?) {}
}

/** At most [maxPerWindow] re-resolves per [windowMs] per channel (§4.5). */
interface ReResolveLimiter {
    fun tryAcquire(key: ChannelIdentity?, nowMs: Long): Boolean

    /** (amended 2026-10-13) Peeks without consuming, to fill `LadderContext.reResolveAllowed` before the decision. */
    fun wouldAllow(key: ChannelIdentity?, nowMs: Long): Boolean = true

    /** (amended 2026-10-13) Clears [key]'s window ("Retry" from the error card, §4.3). */
    fun reset(key: ChannelIdentity?) {}
}

/** Loader-level retry rules (§4.2), shared by WP-B's LoadErrorHandlingPolicy. Returns null for "do not retry". */
interface LoadRetryRules {
    fun retryDelayMs(status: Int?, origin: LoadOrigin, transient: Boolean, errorCount: Int, random: Double): Long?
}

// ------------------------------------------------------------------ buffer (WP-A, §3.6)

interface BufferProfileFsm {
    /** Profile for the next tune of [playlistKey] under [mode] (a per-channel BufferMode override wins). */
    fun profileFor(playlistKey: String, mode: BufferMode, lowRam: Boolean): BufferProfile
    /** A rebuffer (READY → BUFFERING not caused by tune/seek/re-prepare). Returns the new profile when the level changed. */
    fun onRebuffer(playlistKey: String, nowMs: Long, mode: BufferMode, lowRam: Boolean): BufferProfile?
    /** Called once a minute while playing without a rebuffer. Returns the new profile when the level decayed. */
    fun onStableTick(playlistKey: String, nowMs: Long, mode: BufferMode, lowRam: Boolean): BufferProfile?
}

// ------------------------------------------------------------------ stall detection (WP-A, §3.7)

/** One watchdog sample. Filled in place by :player once a second (no allocation per tick). */
class WatchdogSample {
    var nowMs = 0L
    var playbackState = 0               // Player.STATE_* values
    var playWhenReady = false
    var isPlaying = false
    var suppressed = false              // playbackSuppressionReason != NONE
    var isLive = true
    var positionMs = 0L
    var bufferedDurationMs = 0L
    var bytesTotal = 0L                 // monotonic, from telemetry
    /**
     * (code review 2026-10-14, E-B1/N-S2) Age of the last byte **while a read() is pending** in the live source
     * (`now - max(lastByteAt, readPendingSince)`), else 0: a loader parked on a full buffer reads nothing and is not
     * starving (§3.3.6).
     */
    var lastByteAgeMs = 0L
    var inDataSourceReconnect = false
    var isHls = false
    var videoExpected = false           // video track selected, valid surface, viewport MINI/FULL, not poster, no standby grace (E-S4)
    var firstFrameRendered = false
    var videoRenderedCount = 0L
    var videoDroppedCount = 0L
    var tunneling = false
    var audioExpected = false           // audio track selected and not disabled
    var audioAdvancing = false          // onAudioPositionAdvancing seen since the audio renderer (re)started
    var msSinceFirstFrame = 0L
    /** (reviewed 2026-10-13, S11) Time since isPlaying last became true; d1 measures from max(first frame, this). */
    var msSincePlayStart = 0L
    var audioUnderrunsLast3s = 0
    var audioPassthrough = false
    var msSinceSeekOrPrepare = 0L

    // (follow-up 2026-10-14, O-1) readiness stall inputs, filled by WP-C's PlaybackWatchdog.
    /** `isLoading` of the player (false: the loader stopped, e.g. the buffer reached `maxBufferMs`). */
    var isLoading = true
    /** The current profile's after-rebuffer start threshold in ms (0 = unknown: that branch is off). */
    var readyThresholdMs = 0L
    /** This session reached its first-media milestone (first frame, or audio for audio-only). */
    var firstMediaThisSession = false
    /** Video frames rendered since the last seek, prepare or track change. */
    var framesSinceDisruption = 0L
    /** Time since the last seek (a large value when there was none). */
    var msSinceSeek = Long.MAX_VALUE / 4
    /** `onVideoDecoderInitialized` count: a change re-takes the readiness-stall baseline. */
    var videoDecoderInits = 0
    /** Diagnostics only: `queuedInputBufferCount − (rendered + skipped + dropped)` of the video decoder. */
    var videoQueuedMinusOutput = 0L
}

data class StallVerdict(val kind: StallKind, val evidence: String)

interface StallDetectors {
    /** Feed one sample; returns a verdict when a detector fires (at most one per call, cool-downs applied). */
    fun onSample(s: WatchdogSample): StallVerdict?
    /** Reset baselines (tune, seek, re-prepare, viewport/track change). */
    fun reset(nowMs: Long)

    /** (code review 2026-10-14, N-S4) A new tune or re-prepare: [reset] plus anything learned about the old stream. */
    fun newStream(nowMs: Long) = reset(nowMs)
}

// ------------------------------------------------------------------ TS (WP-B, §3.3)

enum class SniffResult { TS, HLS_PLAYLIST, HTML, TINY, UNRECOGNIZED, NEED_MORE }

interface StreamSniffer {
    /** Classify the first [length] bytes. [endOfStream] = no more bytes will come. [tsOffset] valid when TS. */
    fun sniff(buf: ByteArray, length: Int, endOfStream: Boolean): Sniff
}
data class Sniff(val result: SniffResult, val tsOffset: Int = 0)

/**
 * Accepts arbitrary byte runs and releases only whole 188-byte packets that start with 0x47.
 * After [resync] it drops bytes until three consecutive sync bytes are seen again.
 * (reviewed 2026-10-13, B1) After [resync] it also forces a continuity-counter gap on every PID: the first packet with
 * payload of each PID gets CC = (last CC emitted on that PID + 2) & 0xF, and every later packet of that PID
 * (adaptation-only packets included, so they keep repeating the last payload CC as ISO 13818-1 requires) is shifted by
 * the same offset (mod 16) for the rest of the session. TsExtractor checks CCs on payload packets only. Without this, TsExtractor drops the first packet as a "duplicate" (1 in 16) or glues the new PES onto the
 * cut one (1 in 16) per PID per splice.
 * Packets written after [resync] are held back (not readable) until [releaseHeld] is called or [heldBytes] exceeds the
 * caller's bound, so the PCR verdict comes before any post-splice sample reaches the extractor.
 */
interface TsPacketAligner {
    /** Appends [len] bytes; returns how many bytes of whole packets are now available. */
    fun write(src: ByteArray, off: Int, len: Int): Int
    /** Copies up to [len] bytes of whole packets (never a partial packet unless [len] < 188). */
    fun read(dst: ByteArray, off: Int, len: Int): Int
    fun available(): Int
    /** Drop any partial packet and require a fresh 3-sync lock (called on reconnect). Returns bytes dropped. */
    fun resync(): Int
    /** (reviewed 2026-10-13, S1) Bytes written since the last [resync] that are not yet readable. */
    fun heldBytes(): Int
    /** Make the held packets readable (PCR verdict "no jump", or no PCR within the hold bound). */
    fun releaseHeld()
    /** Drop the held packets (PCR verdict "jump": they carry the new timestamps). */
    fun dropHeld()
}

interface PcrTracker {
    /** Inspect one whole packet at [off] (cheap header bit tests). */
    fun onPacket(pkt: ByteArray, off: Int, nowMs: Long)
    /** Mark a reconnect at [nowMs]: the next PCR on the PCR PID is checked against the extrapolated value. */
    fun markReconnect(nowMs: Long)
    /** Non-null once the first post-reconnect PCR was seen and deviates by more than the tolerance (signed ms). */
    fun pendingJumpMs(): Long?
    /** (reviewed 2026-10-13, S1) True after [markReconnect] until the first PCR on the PCR PID is seen. */
    fun awaitingPcr(): Boolean
}

// ------------------------------------------------------------------ AFR (WP-A, §3.9)

/** A display mode as data (built by WP-C from android.view.Display.Mode). */
data class DisplayModeInfo(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

interface DisplayModePicker {
    /** Snap a measured or declared frame rate to a broadcast/film rate, or null when none is within tolerance. */
    fun snapFps(fps: Float): Float?
    /** Mode to request for [fps] keeping the current resolution, or null when the current mode already fits / none fits. */
    fun pick(current: DisplayModeInfo, supported: List<DisplayModeInfo>, fps: Float): DisplayModeInfo?
}

// ------------------------------------------------------------------ surface maths (WP-A, §3.11)

data class RectPx(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class VideoPlacement(val videoRect: RectPx, val cropToFill: Boolean)

interface VideoRectCalculator {
    /** [pixelRatio] = VideoSize.pixelWidthHeightRatio. Never returns a rect larger than [hole]. */
    fun place(hole: RectPx, videoWidth: Int, videoHeight: Int, pixelRatio: Float, mode: AspectMode): VideoPlacement
}

// ------------------------------------------------------------------ diagnostics (WP-A, §3.13)

enum class PlaybackEventType {
    TUNE, RESOLVED, LEASE, OPEN, FIRST_BYTE, SNIFF, READY, FIRST_FRAME, REBUFFER, RECONNECT, RECONNECT_OK, NO_BYTES,
    PCR_JUMP, ERROR, DECISION, STALL, AUDIO_FALLBACK, VIDEO_FALLBACK, BUFFER_LEVEL, AFR, LIFECYCLE, STOP,
}

/** [detail] is passed through Redact.text and cut to 160 characters by the log, never by the caller. */
data class PlaybackEvent(val wallMs: Long, val elapsedMs: Long, val tuneId: Long, val type: PlaybackEventType, val code: String, val detail: String)

data class ZapRecord(
    val tuneId: Long,
    val stableKey: String?,
    val format: StreamFormat,
    val resolveMs: Long,
    val leaseMs: Long,
    val firstByteMs: Long?,
    val readyMs: Long?,
    val firstFrameMs: Long?,
    val outcome: String,
    /** (Keep-decoders WP-0) Decoder init and release cost of this zap; null when not measured. */
    val decoders: app.thebox.core.playback.diag.ZapDecoders? = null,
)

interface PlaybackEventLog {
    fun add(type: PlaybackEventType, tuneId: Long, code: String, detail: String = "")
    fun addZap(record: ZapRecord)
    fun events(): List<PlaybackEvent>
    fun zaps(): List<ZapRecord>
}
