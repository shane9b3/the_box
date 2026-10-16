package app.thebox.core.playback

import app.thebox.core.model.Catchup
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.source.EntryKind
import app.thebox.core.source.PlaylistType
import app.thebox.core.xtream.XtreamAccountInfo
import app.thebox.core.xtream.XtreamStreamRef

/** Monotonic milliseconds (SystemClock.elapsedRealtime on device, a fake in tests). */
fun interface MonoClock { fun nowMs(): Long }

// ------------------------------------------------------------------ formats and inputs

/** Container/transport of a stream. Decided by sniffing (§3.3.3); the URL only gives a hint. */
enum class StreamFormat { TS, HLS, PROGRESSIVE_OTHER, DASH, UNKNOWN }

/** playlist.xtream_output and playlist.xtream_output_learned (persisted by name). */
enum class XtreamOutput(val extension: String?) { AUTO(null), TS("ts"), HLS("m3u8") }

/**
 * Secrets for one playlist, mapped by :app from :data's PlaylistSecret. Never logged: toString() is redacted.
 * [baseUrls] is the Xtream login host followed by backups; empty for M3U.
 */
class StreamCredentials(
    val baseUrls: List<String>,
    val username: String?,
    val password: String?,
    val secretHeaders: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "StreamCredentials(bases=${baseUrls.size}, user=${if (username == null) "none" else "***"})"
}

/** Non-secret per-playlist facts (playlist row). */
data class PlaylistPlaybackInfo(
    val playlistId: Long,
    val type: PlaylistType,
    /** playlist.user_agent; null = app default. */
    val userAgent: String?,
    /** playlist.extra_headers_json (non-secret headers). */
    val extraHeaders: Map<String, String> = emptyMap(),
    val xtreamOutput: XtreamOutput = XtreamOutput.AUTO,
    /** playlist.xtream_output_learned (§2.10): set after a 405/406 flip succeeded; only read when [xtreamOutput] is AUTO. */
    val xtreamOutputLearned: XtreamOutput? = null,
    val activeBaseIndex: Int = 0,
    val ignoreTlsErrors: Boolean = false,
    val maxConnectionsOverride: Int? = null,
    val needsReauth: Boolean = false,
)

/** What the resolver needs from one channel row (the URL is still a template). */
data class ChannelStreamInfo(
    val identity: ChannelIdentity,
    val name: String,
    /** channel.url: `{{base}}/live/{{u}}/{{p}}/<id>.{{ext}}` (Xtream API), `{{u}}`/`{{p}}` templated (Xtream-shaped M3U) or literal. */
    val urlTemplate: String,
    /** Channel.headers (#EXTVLCOPT, #KODIPROP, pipe headers); values may contain `{{u}}`/`{{p}}`. */
    val headers: Map<String, String> = emptyMap(),
    val xtreamStreamId: Long? = null,
    val containerExt: String? = null,
    val kind: EntryKind = EntryKind.LIVE,
    val catchup: Catchup = Catchup.NONE,
)

/** Everything [StreamUrlBuilder.build] needs. [baseIndex]/[output] override the persisted values for rotation and flips. */
data class ResolveInput(
    val channel: ChannelStreamInfo,
    val playlist: PlaylistPlaybackInfo,
    val credentials: StreamCredentials?,
    val account: XtreamAccountInfo?,
    val defaultUserAgent: String,
    val baseIndex: Int = playlist.activeBaseIndex,
    val output: XtreamOutput? = null,
    /** One-shot UA fallback (ladder step L5); overrides playlist and app UA but never a channel UA. */
    val userAgentOverride: String? = null,
)

/**
 * A playable URL. [url] and [headers] carry credentials and tokens: never persist them, never log them
 * (toString() is redacted), and re-resolve from the template on every retry.
 */
class ResolvedStream(
    /** null for TuneTarget.DirectUrl. */
    val identity: ChannelIdentity?,
    /** Broker key: "p:<playlistId>" or "d:<host>" for direct URLs. */
    val brokerKey: String,
    val url: String,
    /** Final request headers, including User-Agent. Keys are canonical-cased (§3.2.5). */
    val headers: Map<String, String>,
    val formatHint: StreamFormat,
    /** Xtream address of this channel (clear credentials) for CatchupTarget.xtream; null when not Xtream-shaped. */
    val xtream: XtreamStreamRef?,
    /** Extension actually used for an Xtream URL, else null. */
    val output: XtreamOutput?,
    val baseIndex: Int,
    val baseCount: Int,
    /** `host[:port]` of [url], for stats and logs (not secret). */
    val host: String,
    val ignoreTlsErrors: Boolean,
    /** [url] before `{{u}}`/`{{p}}` expansion: CatchupTarget.liveUrl (§2.9). */
    val liveUrlTemplate: String,
    /** Headers before `{{u}}`/`{{p}}` expansion: CatchupTarget.headers. */
    val headerTemplates: Map<String, String>,
    /**
     * (code review 2026-10-14, N-S1) Names of headers dropped from [headers] because the name is not a token or the
     * expanded value is not printable ASCII (`HeaderPolicy.sanitize`). Names only; the controller logs them.
     */
    val droppedHeaders: List<String> = emptyList(),
) {
    override fun toString(): String = "ResolvedStream($brokerKey, $host, $formatHint, base=$baseIndex/$baseCount)"
}

// ------------------------------------------------------------------ errors

/** Next-step actions offered with an error (M4 renders them; string keys `playback_action_<name lower-case>`). */
enum class NextStep {
    RETRY, ACCOUNT_DETAILS, EDIT_LOGIN, STOP_AND_WAIT, TRY_BACKUP, PREVIOUS_CHANNEL, CHANGE_USER_AGENT,
    PLAYLIST_SETTINGS, USE_SECURE_DNS, DIAGNOSTICS, TRY_SOFTWARE_DECODER, OPEN_EXTERNAL, AUDIO_OPTIONS,
    WATCH_LIVE, TRY_OTHER_CATCHUP_MODE, REFRESH_PLAYLIST,
}

/**
 * User-facing failure taxonomy (product §2.4). [key] is stable (logs, diagnostics, M4 string keys
 * `playback_error_<key with '.' → '_'>_title` / `_detail`). Append-only.
 */
enum class PlaybackErrorCode(val key: String, val defaultSteps: List<NextStep>) {
    AUTH_REJECTED("play.auth_rejected", listOf(NextStep.ACCOUNT_DETAILS, NextStep.EDIT_LOGIN)),
    ACCOUNT_EXPIRED("play.account_expired", listOf(NextStep.ACCOUNT_DETAILS)),
    ACCOUNT_DISABLED("play.account_disabled", listOf(NextStep.ACCOUNT_DETAILS)),
    NEEDS_REAUTH("play.needs_reauth", listOf(NextStep.EDIT_LOGIN)),
    CREDENTIALS_UNAVAILABLE("play.credentials_unavailable", listOf(NextStep.RETRY)),
    CONNECTION_LIMIT("play.connection_limit", listOf(NextStep.RETRY, NextStep.STOP_AND_WAIT)),
    CONNECTION_LIMIT_LOCAL("play.connection_limit_local", listOf(NextStep.RETRY)),
    FORBIDDEN("play.forbidden", listOf(NextStep.RETRY, NextStep.CHANGE_USER_AGENT, NextStep.DIAGNOSTICS)),
    CHANNEL_OFFLINE("play.channel_offline", listOf(NextStep.TRY_BACKUP, NextStep.PREVIOUS_CHANNEL, NextStep.RETRY)),
    FORMAT_REFUSED("play.format_refused", listOf(NextStep.PLAYLIST_SETTINGS, NextStep.DIAGNOSTICS)),
    WEB_PAGE_INSTEAD("play.web_page", listOf(NextStep.ACCOUNT_DETAILS, NextStep.RETRY)),
    EMPTY_RESPONSE("play.empty_response", listOf(NextStep.RETRY, NextStep.DIAGNOSTICS)),
    NO_DATA_TIMEOUT("play.no_data", listOf(NextStep.RETRY, NextStep.DIAGNOSTICS)),
    STREAM_INTERRUPTED("play.interrupted", listOf(NextStep.RETRY, NextStep.DIAGNOSTICS)),
    SERVER_ERROR("play.server_error", listOf(NextStep.RETRY, NextStep.TRY_BACKUP)),
    DNS_FAILURE("play.dns", listOf(NextStep.USE_SECURE_DNS, NextStep.DIAGNOSTICS)),
    CONNECT_FAILED("play.connect", listOf(NextStep.RETRY, NextStep.DIAGNOSTICS)),
    TLS_FAILURE("play.tls", listOf(NextStep.PLAYLIST_SETTINGS, NextStep.DIAGNOSTICS)),
    CODEC_UNSUPPORTED("play.codec_unsupported", listOf(NextStep.TRY_SOFTWARE_DECODER, NextStep.OPEN_EXTERNAL)),
    STREAM_UNDECODABLE("play.undecodable", listOf(NextStep.RETRY, NextStep.OPEN_EXTERNAL)),
    AUDIO_OUTPUT_FAILED("play.audio_output", listOf(NextStep.AUDIO_OPTIONS)),
    UNSUPPORTED_SOURCE("play.unsupported_source", listOf(NextStep.OPEN_EXTERNAL)),
    CATCHUP_NOT_IN_ARCHIVE("play.catchup_missing", listOf(NextStep.WATCH_LIVE, NextStep.TRY_OTHER_CATCHUP_MODE)),
    CHANNEL_NOT_FOUND("play.channel_missing", listOf(NextStep.REFRESH_PLAYLIST)),
    INTERNAL("play.internal", listOf(NextStep.RETRY, NextStep.DIAGNOSTICS)),
}

/** Facts shown in the error card's detail line. All strings are already redacted (hosts only, no paths/queries). */
data class ErrorDetail(
    val httpStatus: Int? = null,
    val host: String? = null,
    /** e.g. "video/hevc Main10" or "audio/mpeg-L2". */
    val codec: String? = null,
    val decoderName: String? = null,
    /** Connection-limit hint from the freshest account card (or the local broker). */
    val activeConnections: Int? = null,
    val maxConnections: Int? = null,
    /** Raw Media3 PlaybackException.errorCode, for diagnostics. */
    val media3ErrorCode: Int? = null,
    /** Stable FailureSignal name, for diagnostics. */
    val signal: String? = null,
)

/** (M4, lead ruling 2026-10-15) `maxAttempts == 0` means unlimited retries. Filled by the controller from the ladder's row (WP-C); null when the ladder gave up. */
data class RetryInfo(val attemptsMade: Int, val maxAttempts: Int, val nextAttemptInMs: Long?)

/** A user-facing failure. [actions] is [code].defaultSteps filtered by availability (e.g. TRY_BACKUP only with a backup). */
data class PlaybackError(
    val code: PlaybackErrorCode,
    val detail: ErrorDetail = ErrorDetail(),
    val actions: List<NextStep> = code.defaultSteps,
    val retry: RetryInfo? = null,
)

/** Non-fatal conditions shown while playback continues (a chip/toast in M4). */
enum class PlaybackWarning { NO_AUDIO, AUDIO_UNSUPPORTED, SOFTWARE_VIDEO, PASSTHROUGH_DISABLED }

/** Where an HTTP failure happened. */
enum class LoadOrigin { PROGRESSIVE, HLS_MANIFEST, HLS_SEGMENT, ARCHIVE, UNKNOWN }

enum class NetworkFailureKind { DNS, CONNECT, TIMEOUT, TLS, RESET, CLEARTEXT, OTHER }

enum class StallKind { NO_BYTES, DECODER_STUCK, POSITION_STUCK, FROZEN_VIDEO, AUDIO_NOT_STARTED, AUDIO_UNDERRUN_STORM, HLS_NO_PROGRESS }

/** Classified failure (output of :player's FailureClassifier, input of the ladder). §4.1. */
sealed interface FailureSignal {
    val name: String get() = this::class.simpleName ?: "Unknown"

    data class Http(val status: Int, val origin: LoadOrigin) : FailureSignal
    data class Network(val kind: NetworkFailureKind, val beforeFirstByte: Boolean) : FailureSignal
    /** LiveTsDataSource gave up after its in-source reconnects. [lastStatus] is the last HTTP status seen, if any. */
    data class ReconnectExhausted(val lastStatus: Int?, val noBytes: Boolean) : FailureSignal
    data object HtmlInsteadOfStream : FailureSignal
    data class TinyBody(val bytes: Int) : FailureSignal
    data object FormatIsHls : FailureSignal
    data object FormatUnrecognized : FailureSignal
    data class PcrDiscontinuity(val deltaMs: Long) : FailureSignal
    data object BehindLiveWindow : FailureSignal
    data class Parsing(val manifest: Boolean) : FailureSignal
    data class DecoderInit(val video: Boolean, val mimeType: String?, val decoderName: String?) : FailureSignal
    data class DecodingFailed(val video: Boolean, val mimeType: String?) : FailureSignal
    data class FormatUnsupported(val video: Boolean, val mimeType: String?) : FailureSignal
    data class AudioTrackInit(val encoded: Boolean, val channelCount: Int, val mimeType: String?) : FailureSignal
    data object AudioWrite : FailureSignal
    data object ResourcesReclaimed : FailureSignal
    /** Media3 StuckPlayerException (built-in detector). */
    data class Stuck(val type: Int) : FailureSignal
    /** One of our watchdog detectors fired (§3.7). */
    data class Stall(val kind: StallKind) : FailureSignal
    /** STATE_ENDED on a live item. */
    data object LiveEnded : FailureSignal
    data class Credentials(val permanent: Boolean) : FailureSignal
    /** The local broker refused a lease (another lease kind holds the slots). */
    data class LocalConnectionLimit(val active: Int, val max: Int) : FailureSignal
    data object ChannelMissing : FailureSignal
    data class Unknown(val media3ErrorCode: Int) : FailureSignal
}

/** What the controller does next (output of the ladder). §4.3. */
sealed interface LadderDecision {
    /** Re-open the same resolved stream (fresh session and lease, same URL template expansion). */
    data class RetrySame(val delayMs: Long) : LadderDecision
    /** Full re-resolve from the template (fresh token/redirect), optionally refreshing the account and rotating the base URL. */
    data class ReResolve(val delayMs: Long, val refreshAccount: Boolean = false, val rotateBase: Boolean = false) : LadderDecision
    data class FlipXtreamOutput(val to: XtreamOutput) : LadderDecision
    /** Rebuild the MediaSource as another type for the same URL (sniffed HLS behind a .ts URL, generic progressive). */
    data class SwitchSourceType(val to: StreamFormat) : LadderDecision
    data object SeekToLiveAndPrepare : LadderDecision
    /** New session for the same resolved stream, optionally raising the channel's TS compatibility level. */
    data class Reprepare(val raiseTsCompat: Boolean = false) : LadderDecision
    data object DisableTunnelingAndReprepare : LadderDecision
    data object SoftwareVideoAndReprepare : LadderDecision
    data class DisablePassthroughAndReprepare(val mimeType: String?) : LadderDecision
    data class SoftwareAudioAndReprepare(val mimeType: String) : LadderDecision
    data object StereoAndReprepare : LadderDecision
    /** Continue without audio (warning), e.g. no decoder for the audio codec. */
    data class DropAudioAndReprepare(val warning: PlaybackWarning) : LadderDecision
    data object UserAgentFallback : LadderDecision
    data object TryBackupSource : LadderDecision
    data class Fail(val error: PlaybackError) : LadderDecision
}

// ------------------------------------------------------------------ buffer, viewport, AFR, decoders

/**
 * Buffer profile choice (§3.6.1). AUTO is adaptive (default); the others are fixed manual overrides.
 * (follow-up 2026-10-14) NONE: live TV with the smallest profile that still plays ("None (live TV, fastest zapping)").
 */
enum class BufferMode { AUTO, SMALL, MEDIUM, LARGE, EXTRA_LARGE, NONE }

/** Effective LoadControl thresholds. All in milliseconds except [targetBytes]. */
data class BufferProfile(
    val startMs: Int,
    val afterRebufferMs: Int,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val targetBytes: Int,
    /** Auto level 0..3, or -1 for a manual mode. */
    val level: Int,
)

enum class Viewport { NONE, MINI, FULL, HIDDEN }
enum class AfrMode { OFF, SEAMLESS, ALWAYS }
enum class HdrPreviewMode { POSTER, ALLOW }
enum class HdrKind { SDR, HDR10, HLG, DOLBY_VISION, UNKNOWN_HDR }
enum class AspectMode { FIT, FILL, ZOOM, RATIO_16_9, RATIO_4_3 }
enum class VideoDecoderMode { AUTO, HARDWARE_ONLY, SOFTWARE_PREFERRED }
/**
 * Product §4.4 "Audio output". (M5, docs/design/m5-setup-settings.md §2.12 item 7, B2) FORCE_PASSTHROUGH = "Always
 * passthrough" (decision 2026-10-14): the sink is built with AC-3, E-AC-3, DTS and TrueHD added to the reported
 * capabilities (WP-A, the F19 hook); append-only.
 */
enum class AudioOutputMode { AUTO, ALWAYS_DECODE, STEREO, FORCE_PASSTHROUGH }

// ------------------------------------------------------------------ broker

enum class LeaseKind { PLAYBACK, PREVIEW, RECORDING, PROBE }

/** Something that can abort an in-flight upstream connection synchronously (an OkHttp Call in :player). */
fun interface ConnectionHandle { fun cancel() }

data class BrokerSnapshot(val brokerKey: String, val active: Int, val closing: Int, val max: Int, val waiting: Int, val byKind: Map<LeaseKind, Int>)

/** Thrown by a lease acquire when the local limit is held by leases that may not be pre-empted. */
class ConnectionLimitException(val active: Int, val max: Int) : RuntimeException("local connection limit $active/$max")
