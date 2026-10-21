package app.thebox.core.playback.errors

import app.thebox.core.common.BoxLog
import app.thebox.core.playback.AccountGate
import app.thebox.core.playback.ErrorDetail
import app.thebox.core.playback.ErrorLadder
import app.thebox.core.playback.FailureSignal
import app.thebox.core.playback.LadderContext
import app.thebox.core.playback.LadderCounters
import app.thebox.core.playback.LadderDecision
import app.thebox.core.playback.LoadOrigin
import app.thebox.core.playback.NetworkFailureKind
import app.thebox.core.playback.NextStep
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.PlaybackWarning
import app.thebox.core.playback.StallKind
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.VideoDecoderMode
import app.thebox.core.playback.XtreamOutput
import kotlin.random.Random

/** Appendix B constants of the error layer (§4.2, §4.3, §4.5). */
object ErrorConstants {
    const val RE_RESOLVE_MAX_PER_WINDOW = 2
    const val RE_RESOLVE_WINDOW_MS = 60_000L
    const val ZAP_LIMIT_WINDOW_MS = 5_000L
    const val LIMIT_RETRY_DELAY_MS = 1_500L
    const val LIMIT_RERESOLVE_AT_MS = 3_000L
    val CONTROLLER_RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L)
    const val CONTROLLER_RETRY_JITTER = 0.2
    const val UNLIMITED_RETRY_INTERVAL_MS = 5_000L
    /** `PlaybackSettings.reconnectAttempts` value meaning "unlimited". */
    const val UNLIMITED_ATTEMPTS = -1
    const val LADDER_HEALTHY_RESET_MS = 30_000L
    const val PCR_BEHIND_LIVE_MAX_PER_MIN = 3
    const val PCR_BEHIND_LIVE_WINDOW_MS = 60_000L
    const val COMPAT_ESCALATE_WINDOW_MS = 300_000L
    /** L38: two `ResourcesReclaimed` within this window fail. */
    const val RECLAIMED_MAX_PER_WINDOW = 2
    const val RECLAIMED_WINDOW_MS = 60_000L
    /** L16 second step, L22, L39. */
    const val DNS_RETRY_DELAY_MS = 1_000L
    const val TINY_BODY_RETRY_DELAY_MS = 1_000L
    const val CREDENTIALS_RETRY_DELAY_MS = 1_000L
    /** Default of `PlaybackSettings.streamSwitchTimeoutMs` (§2.4). */
    const val DEFAULT_STREAM_SWITCH_TIMEOUT_MS = 15_000L

    const val HLS_SEGMENT_404_RETRIES = 2
    const val HLS_SEGMENT_404_DELAY_MS = 500L
    val LOAD_RETRY_BASE_DELAYS_MS = longArrayOf(500L, 1_000L, 2_000L)
    const val LOAD_RETRY_JITTER = 0.2
    const val LOAD_RETRY_MAX = 3
    /** §4.2: surfaced at once, the ladder decides. */
    val NON_RETRYABLE_STATUSES: Set<Int> = setOf(401, 403, 405, 406, 410, 451, 458, 509)

    const val LOG_TAG = "ErrorLadder"
}

/**
 * The decision table of §4.3, as an ordered list of [Row]s: the first row whose `match` holds decides. Two global rules
 * run around the table: the account gate (before, row id `G1`) and the stream-switch timeout (after, row id
 * `GT/<row>`). Every number is in [ErrorConstants].
 *
 * Beyond the contract (all main-thread, called by WP-D):
 * - [trace] returns the row id with the decision (logs, tests).
 * - [beginChannel] scopes the per-channel windows (5-min compat escalation, 3-per-minute PCR/behind-live, 2-per-minute
 *   reclaim) that outlive a tune cycle's [LadderCounters] (§4.3 "Healthy reset": not the 5-min compat window). Call it
 *   on every tune with the channel's stable key; a different key clears the windows.
 * - [streamSwitchTimeoutMs] supplies `PlaybackSettings.streamSwitchTimeoutMs`, which `LadderContext` does not carry.
 *
 * Interpretations of the table (see the WP-A report): `LadderContext.isXtream` means "has an Xtream account" (API or
 * Xtream-shaped M3U); `ReconnectExhausted(lastStatus)` with a 401/403/404/405/406/410/458/503/509 status is treated as
 * that `Http` status; `LadderContext.passthroughMime` is read as the selected audio MIME when a stall carries none;
 * `LadderCounters.audioSteps` is a bit set of the audio fallbacks taken ([AudioStep]).
 */
class ErrorLadderImpl(
    private val streamSwitchTimeoutMs: () -> Long = { ErrorConstants.DEFAULT_STREAM_SWITCH_TIMEOUT_MS },
    private val random: () -> Double = { Random.nextDouble() },
) : ErrorLadder {

    data class Trace(val row: String, val decision: LadderDecision, val counters: LadderCounters)

    /** Bits of [LadderCounters.audioSteps]. */
    object AudioStep {
        const val PASSTHROUGH_OFF = 1
        const val SOFTWARE = 2
        const val STEREO = 4
        const val DROPPED = 8
        const val WRITE_RETRY = 16
        const val TS_REPAIRED = 32
    }

    private var windowKey: String? = null
    private val decoderWindow = TimeWindow()
    private val pcrWindow = TimeWindow()
    private val behindWindow = TimeWindow()
    private val reclaimWindow = TimeWindow()

    /** Scopes the per-channel windows; a different [channelKey] clears them. */
    override fun beginChannel(channelKey: String?) {
        if (channelKey == windowKey) return
        windowKey = channelKey
        decoderWindow.clear(); pcrWindow.clear(); behindWindow.clear(); reclaimWindow.clear()
    }

    override fun decide(signal: FailureSignal, ctx: LadderContext): Pair<LadderDecision, LadderCounters> {
        val t = trace(signal, ctx)
        return t.decision to t.counters
    }

    fun trace(signal: FailureSignal, ctx: LadderContext): Trace {
        val e = Eval(signal, normalise(signal, ctx), ctx)
        var row: Row? = null
        var step = gate(e)
        if (step == null) {
            row = rows.first { it.match(e) }
            step = row.apply(e)
        }
        var id = (if (e.viaL22a) "L22a/" else "") + (row?.id ?: GATE_ROW)
        var decision = step.decision
        if (decision !is LadderDecision.Fail && !step.timeoutExempt && !ctx.firstFrameRendered &&
            ctx.msSinceSwitchWindowStart >= streamSwitchTimeoutMs()
        ) {
            decision = e.fail(step.eventual ?: PlaybackErrorCode.NO_DATA_TIMEOUT)
            id = "$TIMEOUT_ROW/$id"
        } else {
            step.record?.add(ctx.nowMs)
        }
        BoxLog.i(ErrorConstants.LOG_TAG) { "row=$id signal=${signal.name} -> ${describe(decision)}" }
        return Trace(id, decision, step.counters)
    }

    // ------------------------------------------------------------------ evaluation state

    private class Step(
        val decision: LadderDecision,
        val counters: LadderCounters,
        /** Fail code the row would end in; used by the stream-switch timeout (null = transient → NO_DATA_TIMEOUT). */
        val eventual: PlaybackErrorCode?,
        val timeoutExempt: Boolean = false,
        val record: TimeWindow? = null,
    )

    private class Row(val id: String, val match: (Eval) -> Boolean, val apply: (Eval) -> Step)

    private inner class Eval(val original: FailureSignal, val s: FailureSignal, val ctx: LadderContext) {
        val c: LadderCounters get() = ctx.counters
        val viaL22a: Boolean = original is FailureSignal.TinyBody && s is FailureSignal.Http
        val status: Int? = (s as? FailureSignal.Http)?.status

        fun fail(code: PlaybackErrorCode): LadderDecision.Fail = LadderDecision.Fail(
            PlaybackError(code, detail(code), code.defaultSteps.filter { step -> keepStep(step) }),
        )

        private fun keepStep(step: NextStep): Boolean = when (step) {
            NextStep.TRY_BACKUP -> ctx.backupAvailable
            // P-S3: no "Software decoder" button when software already runs (setting, override or the L29 switch:
            // `videoDecoderMode` is the effective mode) or no software decoder exists for the MIME; it would loop.
            NextStep.TRY_SOFTWARE_DECODER -> ctx.videoDecoderMode != VideoDecoderMode.SOFTWARE_PREFERRED && ctx.softwareVideoAvailable
            else -> true
        }

        fun detail(code: PlaybackErrorCode): ErrorDetail {
            val o = original
            val limit = code == PlaybackErrorCode.CONNECTION_LIMIT
            return ErrorDetail(
                httpStatus = (o as? FailureSignal.Http)?.status ?: (o as? FailureSignal.ReconnectExhausted)?.lastStatus,
                host = ctx.lastHost,
                codec = when (o) {
                    is FailureSignal.DecoderInit -> o.mimeType
                    is FailureSignal.DecodingFailed -> o.mimeType
                    is FailureSignal.FormatUnsupported -> o.mimeType
                    is FailureSignal.AudioTrackInit -> o.mimeType
                    else -> null
                },
                decoderName = (o as? FailureSignal.DecoderInit)?.decoderName,
                activeConnections = (o as? FailureSignal.LocalConnectionLimit)?.active ?: if (limit) ctx.accountActive else null,
                maxConnections = (o as? FailureSignal.LocalConnectionLimit)?.max ?: if (limit) ctx.accountMax else null,
                media3ErrorCode = (o as? FailureSignal.Unknown)?.media3ErrorCode ?: (o as? FailureSignal.Stuck)?.type,
                signal = o.name,
            )
        }

        /** Re-resolve when the limiter allows it, else [fallback]. */
        fun reResolve(d: LadderDecision.ReResolve, fallback: () -> LadderDecision): LadderDecision =
            if (ctx.reResolveAllowed) d else fallback()

        fun attemptsLeft(n: Int): Boolean =
            ctx.reconnectAttemptsSetting == ErrorConstants.UNLIMITED_ATTEMPTS || n < ctx.reconnectAttemptsSetting

        val unlimited: Boolean get() = ctx.reconnectAttemptsSetting == ErrorConstants.UNLIMITED_ATTEMPTS

        fun retryDelay(n: Int): Long {
            if (unlimited) return ErrorConstants.UNLIMITED_RETRY_INTERVAL_MS
            val d = ErrorConstants.CONTROLLER_RETRY_DELAYS_MS
            return LoadRetryRulesImpl.jitter(d[minOf(n, d.size - 1)], ErrorConstants.CONTROLLER_RETRY_JITTER, random())
        }

        /** In the zap/limit path (L2–L6): inside the zap window, before first media, or already climbing it. */
        val zapPath: Boolean
            get() = ctx.msSinceTuneStart <= ErrorConstants.ZAP_LIMIT_WINDOW_MS || !ctx.firstFrameRendered || c.limitSteps > 0

        val accountUnknown: Boolean get() = ctx.accountActive == null || ctx.accountMax == null
        val accountFull: Boolean get() = !accountUnknown && ctx.accountActive!! >= ctx.accountMax!!
        val freeSlot: Boolean get() = !accountUnknown && ctx.accountActive!! < ctx.accountMax!!
    }

    // ------------------------------------------------------------------ normalisation and the account gate

    private fun normalise(signal: FailureSignal, ctx: LadderContext): FailureSignal = when (signal) {
        is FailureSignal.ReconnectExhausted ->
            if (signal.lastStatus != null && signal.lastStatus in STATUS_AS_HTTP) {
                FailureSignal.Http(signal.lastStatus, if (ctx.isArchive) LoadOrigin.ARCHIVE else LoadOrigin.PROGRESSIVE)
            } else signal
        // L22a (reviewed 2026-10-13, S7): a 200 that closes at once is the panel's "close" form of a lingering session.
        is FailureSignal.TinyBody ->
            if (signal.bytes == 0 && (ctx.msSinceTuneStart <= ErrorConstants.ZAP_LIMIT_WINDOW_MS || !ctx.firstFrameRendered)) {
                FailureSignal.Http(HTTP_BANDWIDTH_EXCEEDED, LoadOrigin.PROGRESSIVE)
            } else signal
        else -> signal
    }

    /** G1, account gate after a refresh (reviewed 2026-10-13, S7), and the cached-card shortcut for account-ish failures. */
    private fun gate(e: Eval): Step? {
        val code = when (e.ctx.accountStatus) {
            AccountGate.AUTH_FAILED -> PlaybackErrorCode.AUTH_REJECTED
            AccountGate.EXPIRED -> PlaybackErrorCode.ACCOUNT_EXPIRED
            AccountGate.DISABLED -> PlaybackErrorCode.ACCOUNT_DISABLED
            AccountGate.UNKNOWN, AccountGate.ACTIVE -> return null
        }
        if (!isUpstream(e.s)) return null
        val refreshed = e.c.forbiddenSteps > 0 || e.c.limitSteps >= 2
        val accountish = (e.status != null && e.status in ACCOUNTISH_STATUSES) || e.s is FailureSignal.HtmlInsteadOfStream
        if (!refreshed && !accountish) return null
        return Step(e.fail(code), e.c, code)
    }

    private fun isUpstream(s: FailureSignal): Boolean = when (s) {
        is FailureSignal.Http, is FailureSignal.ReconnectExhausted, FailureSignal.HtmlInsteadOfStream, is FailureSignal.TinyBody,
        FailureSignal.FormatIsHls, FailureSignal.FormatUnrecognized,
        -> true
        is FailureSignal.Network -> s.kind != NetworkFailureKind.CLEARTEXT
        is FailureSignal.Stall -> s.kind == StallKind.NO_BYTES || s.kind == StallKind.HLS_NO_PROGRESS
        else -> false
    }

    // ------------------------------------------------------------------ the table

    private fun httpIn(e: Eval, set: Set<Int>) = e.status != null && e.status in set

    /** L15/L17 shared body: transient retry with optional base rotation. */
    private fun transientRetry(e: Eval, rotateAllowed: Boolean, exhausted: PlaybackErrorCode, eventual: PlaybackErrorCode?): Step {
        val n = e.c.transientRetries
        if (!e.attemptsLeft(n)) return Step(e.fail(exhausted), e.c, exhausted)
        val delay = e.retryDelay(n)
        val rotate = rotateAllowed && e.c.baseRotations < e.ctx.baseCount - 1
        val d = e.reResolve(LadderDecision.ReResolve(delay, rotateBase = rotate)) { LadderDecision.RetrySame(delay) }
        val rotated = rotate && d is LadderDecision.ReResolve
        return Step(
            d,
            e.c.copy(transientRetries = n + 1, baseRotations = e.c.baseRotations + if (rotated) 1 else 0),
            eventual,
        )
    }

    private fun isL27Signal(e: Eval): Boolean = when (val s = e.s) {
        is FailureSignal.Parsing, is FailureSignal.Stuck, FailureSignal.LiveEnded -> true
        is FailureSignal.Stall -> s.kind == StallKind.DECODER_STUCK || s.kind == StallKind.POSITION_STUCK || s.kind == StallKind.FROZEN_VIDEO
        is FailureSignal.PcrDiscontinuity -> true // reached only once L25 is exhausted
        else -> false
    }

    private fun isVideoRelated(s: FailureSignal): Boolean =
        s is FailureSignal.Stuck || (s is FailureSignal.Stall && s.kind != StallKind.AUDIO_NOT_STARTED && s.kind != StallKind.AUDIO_UNDERRUN_STORM)

    private fun videoDecoderSignal(s: FailureSignal): Boolean =
        (s is FailureSignal.DecoderInit && s.video) || (s is FailureSignal.FormatUnsupported && s.video) || (s is FailureSignal.DecodingFailed && s.video)

    private fun audioSignal(s: FailureSignal): Boolean =
        (s is FailureSignal.DecoderInit && !s.video) || (s is FailureSignal.FormatUnsupported && !s.video) ||
            (s is FailureSignal.DecodingFailed && !s.video) ||
            (s is FailureSignal.Stall && (s.kind == StallKind.AUDIO_NOT_STARTED || s.kind == StallKind.AUDIO_UNDERRUN_STORM))

    private fun audioMime(e: Eval): String? = when (val s = e.s) {
        is FailureSignal.DecoderInit -> s.mimeType
        is FailureSignal.FormatUnsupported -> s.mimeType
        is FailureSignal.DecodingFailed -> s.mimeType
        is FailureSignal.AudioTrackInit -> s.mimeType
        else -> null
    } ?: e.ctx.passthroughMime

    private fun has(c: LadderCounters, bit: Int) = c.audioSteps and bit != 0
    private fun with(c: LadderCounters, bit: Int) = c.copy(audioSteps = c.audioSteps or bit)

    private fun escalations(e: Eval): Int = maxOf(e.c.stallRecoveries, decoderWindow.count(e.ctx.nowMs, ErrorConstants.COMPAT_ESCALATE_WINDOW_MS))
    private fun pcrCount(e: Eval): Int = maxOf(e.c.pcrResets, pcrWindow.count(e.ctx.nowMs, ErrorConstants.PCR_BEHIND_LIVE_WINDOW_MS))
    private fun behindCount(e: Eval): Int = maxOf(e.c.behindLiveWindow, behindWindow.count(e.ctx.nowMs, ErrorConstants.PCR_BEHIND_LIVE_WINDOW_MS))

    private val rows: List<Row> = listOf(
        // ---- local failures (no overlap with the HTTP rows)
        Row("L40", { it.s is FailureSignal.Credentials && (it.s as FailureSignal.Credentials).permanent }) {
            Step(it.fail(PlaybackErrorCode.NEEDS_REAUTH), it.c, PlaybackErrorCode.NEEDS_REAUTH)
        },
        Row("L39", { it.s is FailureSignal.Credentials && it.c.transientRetries == 0 }) {
            Step(LadderDecision.RetrySame(ErrorConstants.CREDENTIALS_RETRY_DELAY_MS), it.c.copy(transientRetries = 1), PlaybackErrorCode.CREDENTIALS_UNAVAILABLE)
        },
        Row("L39b", { it.s is FailureSignal.Credentials }) {
            Step(it.fail(PlaybackErrorCode.CREDENTIALS_UNAVAILABLE), it.c, PlaybackErrorCode.CREDENTIALS_UNAVAILABLE)
        },
        Row("L41", { it.s is FailureSignal.LocalConnectionLimit }) {
            Step(it.fail(PlaybackErrorCode.CONNECTION_LIMIT_LOCAL), it.c, PlaybackErrorCode.CONNECTION_LIMIT_LOCAL)
        },
        Row("L42", { it.s == FailureSignal.ChannelMissing }) {
            Step(it.fail(PlaybackErrorCode.CHANNEL_NOT_FOUND), it.c, PlaybackErrorCode.CHANNEL_NOT_FOUND)
        },

        // ---- 401
        Row("L1", { it.status == 401 && it.ctx.isXtream && it.c.forbiddenSteps == 0 }) { e ->
            val d = e.reResolve(LadderDecision.ReResolve(0, refreshAccount = true)) { e.fail(PlaybackErrorCode.AUTH_REJECTED) }
            Step(d, e.c.copy(forbiddenSteps = e.c.forbiddenSteps + 1), PlaybackErrorCode.AUTH_REJECTED)
        },
        Row("L1b", { it.status == 401 }) { e ->
            // Plain M3U: at once. Second 401: the gate handled terminal cards; ACTIVE → handle as 403 (L6).
            val code = if (e.ctx.isXtream && e.ctx.accountStatus == AccountGate.ACTIVE) PlaybackErrorCode.FORBIDDEN else PlaybackErrorCode.AUTH_REJECTED
            Step(e.fail(code), e.c, code)
        },

        // ---- connection limits in the zap path (L2–L6)
        Row("L2", { httpIn(it, LIMIT_STATUSES) && it.zapPath && it.c.limitSteps == 0 }) {
            Step(LadderDecision.RetrySame(ErrorConstants.LIMIT_RETRY_DELAY_MS), it.c.copy(limitSteps = 1), PlaybackErrorCode.CONNECTION_LIMIT)
        },
        Row("L3", { httpIn(it, LIMIT_STATUSES) && it.zapPath && it.c.limitSteps == 1 }) { e ->
            // "until 3,000 ms after the first failure": the L2 retry already waited LIMIT_RETRY_DELAY_MS of it.
            val delay = ErrorConstants.LIMIT_RERESOLVE_AT_MS - ErrorConstants.LIMIT_RETRY_DELAY_MS
            val d = e.reResolve(LadderDecision.ReResolve(delay, refreshAccount = true)) { LadderDecision.RetrySame(ErrorConstants.LIMIT_RETRY_DELAY_MS) }
            Step(d, e.c.copy(limitSteps = 2), PlaybackErrorCode.CONNECTION_LIMIT)
        },
        Row("L4", {
            httpIn(it, LIMIT_STATUSES) && it.zapPath && it.c.limitSteps >= 2 &&
                (httpIn(it, HARD_LIMIT_STATUSES) || it.accountFull || it.accountUnknown)
        }) { Step(it.fail(PlaybackErrorCode.CONNECTION_LIMIT), it.c, PlaybackErrorCode.CONNECTION_LIMIT) },
        Row("L5", {
            it.status == 403 && it.zapPath && it.c.limitSteps >= 2 && it.ctx.accountStatus == AccountGate.ACTIVE && it.freeSlot &&
                it.ctx.uaFallbackAvailable && !it.c.uaFallbackTried
        }) { Step(LadderDecision.UserAgentFallback, it.c.copy(uaFallbackTried = true), PlaybackErrorCode.FORBIDDEN) },
        Row("L6", { it.status == 403 && it.zapPath && it.c.limitSteps >= 2 }) {
            Step(it.fail(PlaybackErrorCode.FORBIDDEN), it.c, PlaybackErrorCode.FORBIDDEN)
        },

        // ---- mid-playback 403/458/509: token expiry (L7), then the L4/L6 rules
        Row("L7", { httpIn(it, HARD_LIMIT_STATUSES + 403) && !it.zapPath && it.c.forbiddenSteps == 0 && it.ctx.reResolveAllowed }) {
            Step(LadderDecision.ReResolve(0, refreshAccount = true), it.c.copy(forbiddenSteps = it.c.forbiddenSteps + 1), PlaybackErrorCode.FORBIDDEN)
        },
        Row("L7b", { httpIn(it, HARD_LIMIT_STATUSES + 403) && !it.zapPath }) { e ->
            val code = if (httpIn(e, HARD_LIMIT_STATUSES) || e.accountFull || e.accountUnknown) PlaybackErrorCode.CONNECTION_LIMIT else PlaybackErrorCode.FORBIDDEN
            Step(e.fail(code), e.c, code)
        },
        Row("L8", { it.status == 503 }) { transientRetry(it, !it.ctx.firstFrameRendered, PlaybackErrorCode.SERVER_ERROR, PlaybackErrorCode.SERVER_ERROR) },

        // ---- 405/406
        Row("L9", { httpIn(it, FORMAT_STATUSES) && it.ctx.isXtream && it.c.formatFlips == 0 && it.ctx.output != null && it.ctx.output != XtreamOutput.AUTO }) { e ->
            val to = if (e.ctx.output == XtreamOutput.HLS) XtreamOutput.TS else XtreamOutput.HLS
            Step(LadderDecision.FlipXtreamOutput(to), e.c.copy(formatFlips = e.c.formatFlips + 1), PlaybackErrorCode.FORMAT_REFUSED)
        },
        Row("L10", { httpIn(it, FORMAT_STATUSES) }) { Step(it.fail(PlaybackErrorCode.FORMAT_REFUSED), it.c, PlaybackErrorCode.FORMAT_REFUSED) },

        // ---- 404/410
        Row("L11", {
            httpIn(it, GONE_STATUSES) && !it.ctx.isArchive && it.ctx.backupAvailable &&
                ((it.s as FailureSignal.Http).origin == LoadOrigin.PROGRESSIVE || (it.s as FailureSignal.Http).origin == LoadOrigin.HLS_MANIFEST)
        }) { Step(LadderDecision.TryBackupSource, it.c, PlaybackErrorCode.CHANNEL_OFFLINE) },
        Row("L12", {
            httpIn(it, GONE_STATUSES) && !it.ctx.isArchive && (it.s as FailureSignal.Http).origin == LoadOrigin.HLS_SEGMENT && it.c.transientRetries == 0
        }) { e ->
            val d = e.reResolve(LadderDecision.ReResolve(0)) { e.fail(PlaybackErrorCode.CHANNEL_OFFLINE) }
            Step(d, e.c.copy(transientRetries = 1), PlaybackErrorCode.CHANNEL_OFFLINE)
        },
        Row("L13", { httpIn(it, GONE_STATUSES) && it.ctx.isArchive }) {
            Step(it.fail(PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE), it.c, PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE)
        },
        // (M4, docs/design/m4-player-ux.md §3.8.8, review S3) L13a: a panel that rejects an archive URL form answers 400 on
        // the first open; that is the variant's "not in archive", which ArchivePlayback probes past. A 400 after first
        // media (a mid-play re-open) stays with LHX.
        Row("L13a", { it.status == HTTP_BAD_REQUEST && it.ctx.isArchive && !it.ctx.firstFrameRendered }) {
            Step(it.fail(PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE), it.c, PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE)
        },
        Row("L14", { httpIn(it, GONE_STATUSES) }) { Step(it.fail(PlaybackErrorCode.CHANNEL_OFFLINE), it.c, PlaybackErrorCode.CHANNEL_OFFLINE) },

        // ---- 5xx, 408, 429
        Row("L15", { it.status != null && (it.status in SERVER_STATUSES || it.status in RETRY_STATUSES) }) {
            transientRetry(it, !it.ctx.firstFrameRendered, PlaybackErrorCode.SERVER_ERROR, PlaybackErrorCode.SERVER_ERROR)
        },
        // Statuses the table does not list (400, 402, 407, 451, …): the server refused this client.
        Row("LHX", { it.s is FailureSignal.Http }) { e ->
            val code = if (e.status!! in CLIENT_STATUSES) PlaybackErrorCode.FORBIDDEN else PlaybackErrorCode.SERVER_ERROR
            Step(e.fail(code), e.c, code)
        },

        // ---- network
        Row("L43", { it.s is FailureSignal.Network && (it.s as FailureSignal.Network).kind == NetworkFailureKind.CLEARTEXT }) {
            Step(it.fail(PlaybackErrorCode.INTERNAL), it.c, PlaybackErrorCode.INTERNAL)
        },
        Row("L16", { isNet(it, NetworkFailureKind.DNS) && it.c.baseRotations < it.ctx.baseCount - 1 }) { e ->
            val d = e.reResolve(LadderDecision.ReResolve(0, rotateBase = true)) { LadderDecision.RetrySame(ErrorConstants.DNS_RETRY_DELAY_MS) }
            Step(d, if (d is LadderDecision.ReResolve) e.c.copy(baseRotations = e.c.baseRotations + 1) else e.c.copy(transientRetries = e.c.transientRetries + 1), PlaybackErrorCode.DNS_FAILURE)
        },
        Row("L16b", { isNet(it, NetworkFailureKind.DNS) && it.c.transientRetries == 0 }) { e ->
            val d = e.reResolve(LadderDecision.ReResolve(ErrorConstants.DNS_RETRY_DELAY_MS)) { LadderDecision.RetrySame(ErrorConstants.DNS_RETRY_DELAY_MS) }
            Step(d, e.c.copy(transientRetries = 1), PlaybackErrorCode.DNS_FAILURE)
        },
        Row("L16c", { isNet(it, NetworkFailureKind.DNS) }) { Step(it.fail(PlaybackErrorCode.DNS_FAILURE), it.c, PlaybackErrorCode.DNS_FAILURE) },
        Row("L18", { isNet(it, NetworkFailureKind.TLS) }) { Step(it.fail(PlaybackErrorCode.TLS_FAILURE), it.c, PlaybackErrorCode.TLS_FAILURE) },
        Row("L17", { it.s is FailureSignal.Network && (it.s as FailureSignal.Network).beforeFirstByte }) { e ->
            val exhausted = if ((e.s as FailureSignal.Network).kind == NetworkFailureKind.TIMEOUT) PlaybackErrorCode.NO_DATA_TIMEOUT else PlaybackErrorCode.CONNECT_FAILED
            transientRetry(e, true, exhausted, null)
        },
        Row("L19", { isReconnectClass(it) && it.attemptsLeft(it.c.transientRetries) }) { e ->
            val delay = e.retryDelay(e.c.transientRetries)
            val d = e.reResolve(LadderDecision.ReResolve(delay)) { LadderDecision.RetrySame(delay) }
            Step(d, e.c.copy(transientRetries = e.c.transientRetries + 1), null, timeoutExempt = e.unlimited)
        },
        Row("L20", { isReconnectClass(it) }) { e ->
            val s = e.s
            val noBytes = (s is FailureSignal.ReconnectExhausted && s.noBytes) || (s is FailureSignal.Stall && !e.ctx.firstFrameRendered)
            val code = if (noBytes) PlaybackErrorCode.NO_DATA_TIMEOUT else PlaybackErrorCode.STREAM_INTERRUPTED
            Step(e.fail(code), e.c, code)
        },

        // ---- bodies that are not a stream
        Row("L21", { it.s == FailureSignal.HtmlInsteadOfStream && (it.ctx.isXtream || it.ctx.accountStatus != AccountGate.UNKNOWN) && it.c.forbiddenSteps == 0 }) { e ->
            val d = e.reResolve(LadderDecision.ReResolve(0, refreshAccount = true)) { e.fail(PlaybackErrorCode.WEB_PAGE_INSTEAD) }
            Step(d, e.c.copy(forbiddenSteps = e.c.forbiddenSteps + 1), PlaybackErrorCode.WEB_PAGE_INSTEAD)
        },
        Row("L21b", { it.s == FailureSignal.HtmlInsteadOfStream }) { Step(it.fail(PlaybackErrorCode.WEB_PAGE_INSTEAD), it.c, PlaybackErrorCode.WEB_PAGE_INSTEAD) },
        Row("L22", { it.s is FailureSignal.TinyBody && it.c.transientRetries == 0 }) { e ->
            val d = e.reResolve(LadderDecision.ReResolve(ErrorConstants.TINY_BODY_RETRY_DELAY_MS)) { LadderDecision.RetrySame(ErrorConstants.TINY_BODY_RETRY_DELAY_MS) }
            Step(d, e.c.copy(transientRetries = 1), PlaybackErrorCode.EMPTY_RESPONSE)
        },
        Row("L22b", { it.s is FailureSignal.TinyBody }) { Step(it.fail(PlaybackErrorCode.EMPTY_RESPONSE), it.c, PlaybackErrorCode.EMPTY_RESPONSE) },
        Row("L23", { it.s == FailureSignal.FormatIsHls && it.c.sourceTypeSwitches == 0 }) {
            Step(LadderDecision.SwitchSourceType(StreamFormat.HLS), it.c.copy(sourceTypeSwitches = 1), PlaybackErrorCode.UNSUPPORTED_SOURCE)
        },
        Row("L23b", { it.s == FailureSignal.FormatIsHls }) { Step(it.fail(PlaybackErrorCode.UNSUPPORTED_SOURCE), it.c, PlaybackErrorCode.UNSUPPORTED_SOURCE) },
        Row("L24", { it.s == FailureSignal.FormatUnrecognized && it.c.sourceTypeSwitches == 0 }) {
            Step(LadderDecision.SwitchSourceType(StreamFormat.PROGRESSIVE_OTHER), it.c.copy(sourceTypeSwitches = 1), PlaybackErrorCode.UNSUPPORTED_SOURCE)
        },
        Row("L24b", { it.s == FailureSignal.FormatUnrecognized }) { Step(it.fail(PlaybackErrorCode.UNSUPPORTED_SOURCE), it.c, PlaybackErrorCode.UNSUPPORTED_SOURCE) },

        // ---- timeline
        Row("L25", { it.s is FailureSignal.PcrDiscontinuity && pcrCount(it) < ErrorConstants.PCR_BEHIND_LIVE_MAX_PER_MIN }) {
            Step(LadderDecision.Reprepare(), it.c.copy(pcrResets = it.c.pcrResets + 1), PlaybackErrorCode.STREAM_UNDECODABLE, record = pcrWindow)
        },
        Row("L26", { it.s == FailureSignal.BehindLiveWindow && behindCount(it) < ErrorConstants.PCR_BEHIND_LIVE_MAX_PER_MIN }) {
            Step(LadderDecision.SeekToLiveAndPrepare, it.c.copy(behindLiveWindow = it.c.behindLiveWindow + 1), PlaybackErrorCode.STREAM_INTERRUPTED, record = behindWindow)
        },
        Row("L26b", { it.s == FailureSignal.BehindLiveWindow && it.c.behindLiveWindow <= ErrorConstants.PCR_BEHIND_LIVE_MAX_PER_MIN }) { e ->
            val d = e.reResolve(LadderDecision.ReResolve(0)) { e.fail(PlaybackErrorCode.STREAM_INTERRUPTED) }
            Step(d, e.c.copy(behindLiveWindow = ErrorConstants.PCR_BEHIND_LIVE_MAX_PER_MIN + 1), PlaybackErrorCode.STREAM_INTERRUPTED)
        },
        Row("L26c", { it.s == FailureSignal.BehindLiveWindow }) { Step(it.fail(PlaybackErrorCode.STREAM_INTERRUPTED), it.c, PlaybackErrorCode.STREAM_INTERRUPTED) },

        // ---- stuck pipelines (L27)
        Row("L27", { isL27Signal(it) && it.ctx.tunnelingActive && isVideoRelated(it.s) }) {
            Step(LadderDecision.DisableTunnelingAndReprepare, it.c, PlaybackErrorCode.STREAM_UNDECODABLE)
        },
        Row("L27a", { isL27Signal(it) && escalations(it) == 0 }) {
            Step(LadderDecision.Reprepare(), it.c.copy(stallRecoveries = it.c.stallRecoveries + 1), PlaybackErrorCode.STREAM_UNDECODABLE, record = decoderWindow)
        },
        Row("L27b", { isL27Signal(it) && escalations(it) == 1 && it.ctx.tsCompatLevel == 0 && it.ctx.format == StreamFormat.TS }) {
            Step(LadderDecision.Reprepare(raiseTsCompat = true), it.c.copy(stallRecoveries = it.c.stallRecoveries + 1), PlaybackErrorCode.STREAM_UNDECODABLE, record = decoderWindow)
        },
        Row("L27c", { isL27Signal(it) }) { e ->
            val code = if (e.s == FailureSignal.LiveEnded && e.ctx.format == StreamFormat.HLS) PlaybackErrorCode.STREAM_INTERRUPTED else PlaybackErrorCode.STREAM_UNDECODABLE
            Step(e.fail(code), e.c, code)
        },

        // ---- video decoders (L28–L30)
        Row("L28", { videoDecoderSignal(it.s) && it.ctx.tunnelingActive }) {
            Step(LadderDecision.DisableTunnelingAndReprepare, it.c, PlaybackErrorCode.CODEC_UNSUPPORTED)
        },
        Row("L30a", { it.s is FailureSignal.DecodingFailed && (it.s as FailureSignal.DecodingFailed).video && it.c.decoderSteps == 0 }) {
            Step(LadderDecision.Reprepare(), it.c.copy(decoderSteps = 1), PlaybackErrorCode.CODEC_UNSUPPORTED)
        },
        Row("L29", {
            videoDecoderSignal(it.s) && it.ctx.videoDecoderMode != VideoDecoderMode.SOFTWARE_PREFERRED && it.ctx.softwareVideoAvailable &&
                it.c.decoderSteps < (if (it.s is FailureSignal.DecodingFailed) 2 else 1)
        }) { Step(LadderDecision.SoftwareVideoAndReprepare, it.c.copy(decoderSteps = it.c.decoderSteps + 1), PlaybackErrorCode.CODEC_UNSUPPORTED) },
        Row("L30", { videoDecoderSignal(it.s) }) { Step(it.fail(PlaybackErrorCode.CODEC_UNSUPPORTED), it.c, PlaybackErrorCode.CODEC_UNSUPPORTED) },

        // ---- audio (L31–L37)
        Row("L31", { e ->
            audioSignal(e.s) && e.ctx.passthroughActive && !has(e.c, AudioStep.PASSTHROUGH_OFF) &&
                (e.ctx.passthroughMime == null || audioMime(e) == null || audioMime(e) == e.ctx.passthroughMime)
        }) { e ->
            Step(LadderDecision.DisablePassthroughAndReprepare(e.ctx.passthroughMime ?: audioMime(e)), with(e.c, AudioStep.PASSTHROUGH_OFF), PlaybackErrorCode.AUDIO_OUTPUT_FAILED)
        },
        // (device finding 2026-10-18) Broken audio timestamps: first regenerate them from the frame spacing (L33r), then
        // play the picture without the track (L33t); no decoder can help.
        Row("L33r", { it.s == FailureSignal.AudioTimestampsStuck && !has(it.c, AudioStep.TS_REPAIRED) }) {
            Step(LadderDecision.RepairAudioTimestampsAndReprepare, with(it.c, AudioStep.TS_REPAIRED), PlaybackErrorCode.AUDIO_OUTPUT_FAILED)
        },
        Row("L33t", { it.s == FailureSignal.AudioTimestampsStuck && !has(it.c, AudioStep.DROPPED) }) {
            Step(LadderDecision.DropAudioAndReprepare(PlaybackWarning.AUDIO_TIMESTAMPS), with(it.c, AudioStep.DROPPED), PlaybackErrorCode.AUDIO_OUTPUT_FAILED)
        },
        Row("L33u", { it.s == FailureSignal.AudioTimestampsStuck }) { Step(it.fail(PlaybackErrorCode.AUDIO_OUTPUT_FAILED), it.c, PlaybackErrorCode.AUDIO_OUTPUT_FAILED) },
        Row("L32", { e -> audioSignal(e.s) && !has(e.c, AudioStep.SOFTWARE) && audioMime(e)?.let { e.ctx.ffmpegSupportsMime(it) } == true }) { e ->
            Step(LadderDecision.SoftwareAudioAndReprepare(audioMime(e)!!), with(e.c, AudioStep.SOFTWARE), PlaybackErrorCode.AUDIO_OUTPUT_FAILED)
        },
        Row("L33", { audioSignal(it.s) && !has(it.c, AudioStep.DROPPED) }) { e ->
            val codecProblem = e.s is FailureSignal.DecoderInit || e.s is FailureSignal.FormatUnsupported
            val w = if (codecProblem) PlaybackWarning.AUDIO_UNSUPPORTED else PlaybackWarning.NO_AUDIO
            Step(LadderDecision.DropAudioAndReprepare(w), with(e.c, AudioStep.DROPPED), PlaybackErrorCode.AUDIO_OUTPUT_FAILED)
        },
        Row("L33b", { audioSignal(it.s) }) { Step(it.fail(PlaybackErrorCode.AUDIO_OUTPUT_FAILED), it.c, PlaybackErrorCode.AUDIO_OUTPUT_FAILED) },
        Row("L34", { it.s is FailureSignal.AudioTrackInit && (it.s as FailureSignal.AudioTrackInit).encoded && !has(it.c, AudioStep.PASSTHROUGH_OFF) }) { e ->
            Step(LadderDecision.DisablePassthroughAndReprepare(audioMime(e)), with(e.c, AudioStep.PASSTHROUGH_OFF), PlaybackErrorCode.AUDIO_OUTPUT_FAILED)
        },
        Row("L35", {
            it.s is FailureSignal.AudioTrackInit && (it.s as FailureSignal.AudioTrackInit).channelCount > STEREO_CHANNELS && !has(it.c, AudioStep.STEREO)
        }) { Step(LadderDecision.StereoAndReprepare, with(it.c, AudioStep.STEREO), PlaybackErrorCode.AUDIO_OUTPUT_FAILED) },
        Row("L37", { it.s == FailureSignal.AudioWrite && it.c.audioSteps == 0 }) {
            Step(LadderDecision.Reprepare(), with(it.c, AudioStep.WRITE_RETRY), PlaybackErrorCode.AUDIO_OUTPUT_FAILED)
        },
        Row("L36", { (it.s is FailureSignal.AudioTrackInit || it.s == FailureSignal.AudioWrite) && !has(it.c, AudioStep.DROPPED) }) {
            Step(LadderDecision.DropAudioAndReprepare(PlaybackWarning.NO_AUDIO), with(it.c, AudioStep.DROPPED), PlaybackErrorCode.AUDIO_OUTPUT_FAILED)
        },
        Row("L36b", { it.s is FailureSignal.AudioTrackInit || it.s == FailureSignal.AudioWrite }) {
            Step(it.fail(PlaybackErrorCode.AUDIO_OUTPUT_FAILED), it.c, PlaybackErrorCode.AUDIO_OUTPUT_FAILED)
        },

        // ---- the rest
        Row("L38", {
            it.s == FailureSignal.ResourcesReclaimed &&
                reclaimWindow.count(it.ctx.nowMs, ErrorConstants.RECLAIMED_WINDOW_MS) < ErrorConstants.RECLAIMED_MAX_PER_WINDOW - 1
        }) { Step(LadderDecision.Reprepare(), it.c, PlaybackErrorCode.STREAM_UNDECODABLE, record = reclaimWindow) },
        Row("L38b", { it.s == FailureSignal.ResourcesReclaimed }) {
            Step(it.fail(PlaybackErrorCode.STREAM_UNDECODABLE), it.c, PlaybackErrorCode.STREAM_UNDECODABLE)
        },
        Row("L44", { it.s is FailureSignal.Unknown && it.c.unknownRetries == 0 }) {
            Step(LadderDecision.Reprepare(), it.c.copy(unknownRetries = 1), PlaybackErrorCode.INTERNAL)
        },
        Row("L44b", { true }) { Step(it.fail(PlaybackErrorCode.INTERNAL), it.c, PlaybackErrorCode.INTERNAL) },
    )

    private fun isNet(e: Eval, kind: NetworkFailureKind) = e.s is FailureSignal.Network && (e.s as FailureSignal.Network).kind == kind

    private fun isReconnectClass(e: Eval): Boolean = when (val s = e.s) {
        is FailureSignal.ReconnectExhausted -> true
        is FailureSignal.Network -> !s.beforeFirstByte
        is FailureSignal.Stall -> s.kind == StallKind.NO_BYTES || s.kind == StallKind.HLS_NO_PROGRESS
        else -> false
    }

    /** A few timestamps per channel (main thread). */
    private class TimeWindow {
        private val stamps = LongArray(CAPACITY)
        private var size = 0

        fun add(nowMs: Long) {
            if (size == CAPACITY) {
                System.arraycopy(stamps, 1, stamps, 0, CAPACITY - 1)
                size--
            }
            stamps[size++] = nowMs
        }

        fun count(nowMs: Long, windowMs: Long): Int {
            var n = 0
            for (i in 0 until size) if (nowMs - stamps[i] < windowMs) n++
            return n
        }

        fun clear() {
            size = 0
        }

        companion object {
            const val CAPACITY = 8
        }
    }

    companion object {
        const val GATE_ROW = "G1"
        const val TIMEOUT_ROW = "GT"
        private const val HTTP_BANDWIDTH_EXCEEDED = 509
        private const val HTTP_BAD_REQUEST = 400
        private const val STEREO_CHANNELS = 2
        private val LIMIT_STATUSES = setOf(403, 458, 509, 503)
        private val HARD_LIMIT_STATUSES = setOf(458, 509)
        private val FORMAT_STATUSES = setOf(405, 406)
        private val GONE_STATUSES = setOf(404, 410)
        private val RETRY_STATUSES = setOf(408, 429)
        private val SERVER_STATUSES = 500..599
        private val CLIENT_STATUSES = 400..499
        private val ACCOUNTISH_STATUSES = setOf(401, 403, 458, 509)
        private val STATUS_AS_HTTP = setOf(401, 403, 404, 405, 406, 410, 458, 503, 509)

        /** §4.3 "Healthy reset": [counters] after [healthyPlayingMs] of `Playing` without an error. */
        fun countersAfterHealthy(counters: LadderCounters, healthyPlayingMs: Long): LadderCounters =
            if (healthyPlayingMs >= ErrorConstants.LADDER_HEALTHY_RESET_MS) LadderCounters() else counters

        /** Short form for logs and the golden table: `Fail(CODE)` for failures, the data-class form otherwise. */
        fun describe(d: LadderDecision): String = if (d is LadderDecision.Fail) "Fail(${d.error.code.name})" else d.toString()
    }
}
