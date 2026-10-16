package app.thebox.core.setup

import app.thebox.core.common.AuthReason
import app.thebox.core.common.CredentialFailure
import app.thebox.core.common.NetworkKind
import app.thebox.core.common.NotASourceKind
import app.thebox.core.common.SourceError
import app.thebox.core.settings.SourceKind
import app.thebox.core.settings.Tone
import app.thebox.core.xtream.AccountStatus
import app.thebox.core.xtream.XtreamAccountInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Every login result, failure and input problem → the result card (docs/design/m5-setup-settings.md §3.4.4): a
 * reason (headline), the lines, and one or two next steps (actions). Never shows raw exception text: only the error's
 * kind and its non-secret numbers (HTTP status, counts) reach the card; `detail` strings are ignored
 * (`SetupMapperCompletenessTest`). Pure; user text comes from [SetupStrings].
 */
class SetupResultMapper(
    private val s: SetupStrings,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val locale: () -> Locale = { Locale.getDefault() },
) {
    /** Where the error happened, for the texts that name the host or port or differ between login and playlist. */
    data class Context(
        val kind: SourceKind = SourceKind.XTREAM,
        /** "panel.example.com" (no port, no credentials), or null. */
        val host: String? = null,
        /** The explicitly typed port, or null. */
        val port: Int? = null,
        /** An edit of an existing source (the shrink guard applies only there). */
        val editing: Boolean = false,
    )

    // ------------------------------------------------------------------ login results

    /** A successful `player_api.php` login. [nowMs] is the device clock (expiry countdown, skew). */
    fun login(info: XtreamAccountInfo, nowMs: Long): SetupCard {
        if (!info.auth) return error(SourceError.Auth(AuthReason.BAD_CREDENTIALS))
        return when (info.status) {
            AccountStatus.EXPIRED -> expired(info.expiresAtSec)
            AccountStatus.BANNED, AccountStatus.DISABLED -> disabled(info.message)
            AccountStatus.ACTIVE, AccountStatus.UNKNOWN -> connected(info, nowMs)
        }
    }

    private fun connected(info: XtreamAccountInfo, nowMs: Long): SetupCard {
        val lines = ArrayList<String>(4)
        val exp = info.expiresAtSec
        val trial = if (info.isTrial) s.text(SetupText.CARD_TRIAL_SUFFIX) else ""
        lines += when {
            info.status == AccountStatus.UNKNOWN && exp == null -> s.text(SetupText.CARD_STATUS_UNKNOWN)
            exp == null -> s.text(SetupText.CARD_ACTIVE_NEVER_EXPIRES, trial)
            else -> s.text(SetupText.CARD_ACTIVE_EXPIRES, date(exp), daysUntil(exp, nowMs).coerceAtLeast(0), trial)
        }
        val active = info.activeConnections
        val max = info.maxConnections
        if (max != null && max > 0) {
            lines += if (active != null) s.text(SetupText.CARD_CONNECTIONS_IN_USE, active, max) else s.text(SetupText.CARD_CONNECTIONS_MAX, max)
        }
        if (info.allowedOutputFormats.isNotEmpty()) {
            lines += s.text(SetupText.CARD_FORMATS, info.allowedOutputFormats.map(::formatName).distinct().joinToString(", "))
        }
        info.server.timestampNow?.let { serverSec ->
            val skewMin = ((serverSec * 1000 - info.measuredAtDeviceMs) / 60_000.0).roundToLong()
            lines += when {
                abs(skewMin) < 1 -> s.text(SetupText.CARD_CLOCK_IN_SYNC)
                skewMin > 0 -> s.text(SetupText.CARD_CLOCK_AHEAD, skewMin)
                else -> s.text(SetupText.CARD_CLOCK_BEHIND, -skewMin)
            }
        }
        return SetupCard(Tone.GOOD, s.text(SetupText.CARD_CONNECTED), lines, SetupAction.CONTINUE, null)
    }

    private fun expired(expiresAtSec: Long?): SetupCard = SetupCard(
        Tone.WARN,
        if (expiresAtSec != null) s.text(SetupText.CARD_EXPIRED_ON, date(expiresAtSec)) else s.text(SetupText.CARD_EXPIRED),
        listOf(s.text(SetupText.CARD_EXPIRED_NEXT)),
        SetupAction.SAVE_ANYWAY, SetupAction.EDIT,
    )

    private fun disabled(message: String?): SetupCard {
        val lines = ArrayList<String>(2)
        message?.let(::providerMessage)?.let { lines += it }
        lines += s.text(SetupText.CARD_DISABLED_NEXT)
        return SetupCard(Tone.BAD, s.text(SetupText.CARD_DISABLED), lines, SetupAction.EDIT, SetupAction.SAVE_ANYWAY)
    }

    // ------------------------------------------------------------------ failures

    /** Every [SourceError]. `detail` strings are never shown (they may hold exception text or URLs). */
    fun error(e: SourceError, ctx: Context = Context()): SetupCard = when (e) {
        is SourceError.Auth -> auth(e.reason)
        is SourceError.Http -> http(e.status, ctx)
        is SourceError.Network -> network(e.kind, ctx)
        is SourceError.NotASource -> notASource(e.kind)
        is SourceError.SuspiciousShrink -> card(
            Tone.WARN,
            if (e.previousLive > 0) s.text(SetupText.CARD_SHRINK, count(e.nowLive), count(e.previousLive)) else s.text(SetupText.CARD_SHRINK_GENERIC),
            listOf(s.text(SetupText.CARD_SHRINK_KEPT)), SetupAction.SAVE_ANYWAY, SetupAction.RETRY,
        )
        is SourceError.Parse -> card(Tone.BAD, s.text(SetupText.CARD_PARSE), listOf(s.text(SetupText.CARD_PARSE_NEXT)), SetupAction.EDIT, SetupAction.RETRY)
        is SourceError.DiskSpace -> card(
            Tone.BAD, s.text(SetupText.CARD_DISK),
            listOf(s.text(SetupText.CARD_DISK_NEXT, mb((e.neededBytes - e.freeBytes).coerceAtLeast(1)))), SetupAction.RETRY, null,
        )
        is SourceError.Credentials -> when (e.reason) {
            CredentialFailure.UNAVAILABLE -> card(Tone.WARN, s.text(SetupText.CARD_SECURE_BUSY), listOf(s.text(SetupText.CARD_SECURE_BUSY_NEXT)), SetupAction.RETRY, null)
            CredentialFailure.MISSING, CredentialFailure.KEY_INVALIDATED, CredentialFailure.CORRUPT ->
                card(Tone.BAD, s.text(SetupText.CARD_LOGIN_LOST), listOf(s.text(SetupText.CARD_LOGIN_LOST_NEXT)), SetupAction.EDIT, null)
        }
        is SourceError.Unsupported -> card(Tone.BAD, s.text(SetupText.CARD_UNSUPPORTED), listOf(s.text(SetupText.CARD_UNSUPPORTED_NEXT)), SetupAction.EDIT, null)
        SourceError.Cancelled -> card(Tone.WARN, s.text(SetupText.CARD_CANCELLED), listOf(s.text(SetupText.CARD_CANCELLED_NEXT)), SetupAction.RETRY, SetupAction.EDIT)
        is SourceError.Internal -> card(Tone.BAD, s.text(SetupText.CARD_INTERNAL), listOf(s.text(SetupText.CARD_INTERNAL_NEXT, e.code)), SetupAction.RETRY, SetupAction.EDIT)
    }

    private fun auth(reason: AuthReason): SetupCard = when (reason) {
        AuthReason.BAD_CREDENTIALS, AuthReason.UNKNOWN -> card(
            Tone.BAD, s.text(SetupText.CARD_BAD_LOGIN), listOf(s.text(SetupText.CARD_BAD_LOGIN_NEXT)), SetupAction.EDIT, SetupAction.PHONE_SETUP,
        )
        AuthReason.EXPIRED -> expired(null)
        AuthReason.BANNED, AuthReason.DISABLED -> disabled(null)
    }

    private fun http(status: Int, ctx: Context): SetupCard = when (status) {
        401, 403 -> card(
            Tone.BAD,
            s.text(if (ctx.kind == SourceKind.XTREAM) SetupText.CARD_HTTP_LOGIN_REFUSED else SetupText.CARD_HTTP_REFUSED, status),
            listOf(s.text(SetupText.CARD_HTTP_REFUSED_NEXT)), SetupAction.EDIT, SetupAction.RETRY,
        )
        404, 410 -> card(Tone.BAD, s.text(SetupText.CARD_HTTP_NOT_FOUND, status), listOf(s.text(SetupText.CARD_HTTP_NOT_FOUND_NEXT)), SetupAction.EDIT, SetupAction.RETRY)
        // 458/509: provider connection limits; at login they mean the account is busy elsewhere (S16: probe-only otherwise).
        429, 458, 509 -> card(Tone.WARN, s.text(SetupText.CARD_HTTP_LIMIT, status), listOf(s.text(SetupText.CARD_HTTP_LIMIT_NEXT)), SetupAction.RETRY, SetupAction.EDIT)
        in 500..599 -> card(Tone.BAD, s.text(SetupText.CARD_HTTP_SERVER, status), listOf(s.text(SetupText.CARD_HTTP_SERVER_NEXT)), SetupAction.RETRY, SetupAction.EDIT)
        else -> card(Tone.BAD, s.text(SetupText.CARD_HTTP_OTHER, status), listOf(s.text(SetupText.CARD_HTTP_OTHER_NEXT)), SetupAction.EDIT, SetupAction.RETRY)
    }

    private fun network(kind: NetworkKind, ctx: Context): SetupCard = when (kind) {
        NetworkKind.DNS -> card(
            Tone.BAD,
            ctx.host?.let { s.text(SetupText.CARD_DNS_HOST, it) } ?: s.text(SetupText.CARD_DNS),
            listOf(s.text(SetupText.CARD_DNS_NEXT)), SetupAction.USE_SECURE_DNS, SetupAction.EDIT,
        )
        NetworkKind.TLS -> card(Tone.BAD, s.text(SetupText.CARD_TLS), listOf(s.text(SetupText.CARD_TLS_NEXT)), SetupAction.EDIT, SetupAction.RETRY)
        NetworkKind.CONNECT, NetworkKind.TIMEOUT, NetworkKind.RESET, NetworkKind.OTHER -> card(
            Tone.BAD,
            ctx.port?.let { s.text(SetupText.CARD_NO_ANSWER_PORT, it) } ?: s.text(SetupText.CARD_NO_ANSWER),
            listOf(s.text(SetupText.CARD_NO_ANSWER_NEXT)), SetupAction.RETRY, SetupAction.EDIT,
        )
    }

    private fun notASource(kind: NotASourceKind): SetupCard = when (kind) {
        NotASourceKind.HTML_PAGE -> card(Tone.BAD, s.text(SetupText.CARD_HTML), listOf(s.text(SetupText.CARD_HTML_NEXT)), SetupAction.EDIT, SetupAction.RETRY)
        NotASourceKind.HLS_MANIFEST -> card(Tone.WARN, s.text(SetupText.CARD_SINGLE_STREAM), listOf(s.text(SetupText.CARD_SINGLE_STREAM_NEXT)), SetupAction.ADD_AS_SINGLE_CHANNEL, SetupAction.EDIT)
        NotASourceKind.EMPTY -> card(Tone.BAD, s.text(SetupText.CARD_EMPTY), listOf(s.text(SetupText.CARD_EMPTY_NEXT)), SetupAction.EDIT, SetupAction.RETRY)
        NotASourceKind.BINARY, NotASourceKind.WRONG_FORMAT ->
            card(Tone.BAD, s.text(SetupText.CARD_WRONG_FORMAT), listOf(s.text(SetupText.CARD_WRONG_FORMAT_NEXT)), SetupAction.EDIT, SetupAction.RETRY)
    }

    /** A form value the normaliser refused (§3.4.3). */
    fun input(problem: InputProblem): SetupCard = when (problem) {
        InputProblem.EMPTY -> card(Tone.WARN, s.text(SetupText.CARD_INPUT_EMPTY), listOf(s.text(SetupText.CARD_INPUT_EMPTY_NEXT)), SetupAction.EDIT, SetupAction.PHONE_SETUP)
        InputProblem.NOT_A_URL -> card(Tone.BAD, s.text(SetupText.CARD_INPUT_NOT_URL), listOf(s.text(SetupText.CARD_INPUT_NOT_URL_NEXT)), SetupAction.EDIT, SetupAction.PHONE_SETUP)
        InputProblem.UNSUPPORTED_SCHEME -> card(Tone.BAD, s.text(SetupText.CARD_INPUT_SCHEME), listOf(s.text(SetupText.CARD_INPUT_SCHEME_NEXT)), SetupAction.EDIT, null)
        InputProblem.STALKER_PORTAL -> card(Tone.BAD, s.text(SetupText.CARD_INPUT_STALKER), listOf(s.text(SetupText.CARD_INPUT_STALKER_NEXT)), SetupAction.EDIT, null)
        InputProblem.SPACES_IN_HOST -> card(Tone.BAD, s.text(SetupText.CARD_INPUT_SPACES), listOf(s.text(SetupText.CARD_INPUT_SPACES_NEXT)), SetupAction.EDIT, null)
    }

    /** The label of [action] on the card of [error] (the shrink guard's SAVE_ANYWAY reads "Accept anyway"). */
    fun actionLabel(action: SetupAction, error: SourceError? = null): String = s.text(
        when (action) {
            SetupAction.CONTINUE -> SetupText.ACTION_CONTINUE
            SetupAction.SAVE_ANYWAY -> if (error is SourceError.SuspiciousShrink) SetupText.ACTION_ACCEPT_ANYWAY else SetupText.ACTION_SAVE_ANYWAY
            SetupAction.EDIT -> SetupText.ACTION_EDIT
            SetupAction.RETRY -> SetupText.ACTION_RETRY
            SetupAction.USE_XTREAM_INSTEAD -> SetupText.ACTION_USE_XTREAM
            SetupAction.ADD_AS_SINGLE_CHANNEL -> SetupText.ACTION_SINGLE_CHANNEL
            SetupAction.USE_SECURE_DNS -> SetupText.ACTION_SECURE_DNS
            SetupAction.PHONE_SETUP -> SetupText.ACTION_PHONE_SETUP
            SetupAction.CANCEL -> SetupText.ACTION_CANCEL
        },
    )

    /** §3.6.1: a failed refresh as one line, "reason · next step", from the stored refresh_log error code. */
    fun refreshFailureLine(code: String, ctx: Context = Context()): String {
        val e = errorFromCode(code)
        val c = error(e, ctx)
        val next = if (c.primary == SetupAction.EDIT) s.text(SetupText.ACTION_EDIT_SOURCE) else actionLabel(c.primary, e)
        return s.text(SetupText.LINE_REASON_NEXT, c.headline, next)
    }

    // ------------------------------------------------------------------ helpers

    private fun card(tone: Tone, headline: String, lines: List<String>, primary: SetupAction, secondary: SetupAction?) =
        SetupCard(tone, headline, lines, primary, secondary)

    private fun providerMessage(m: String): String? {
        val clean = m.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return null
        return if (clean.length <= SetupConstants.PROVIDER_MESSAGE_MAX_CHARS) clean else clean.take(SetupConstants.PROVIDER_MESSAGE_MAX_CHARS - 1).trimEnd() + "…"
    }

    private fun date(epochSec: Long): String =
        DateTimeFormatter.ofPattern("d MMM yyyy", locale()).withZone(zone()).format(Instant.ofEpochSecond(epochSec))

    private fun count(n: Int): String = String.format(locale(), "%,d", n)

    private fun mb(bytes: Long): String = String.format(locale(), "%,d", (bytes + 1_048_575) / 1_048_576)

    companion object {
        /** Whole days from [nowMs] to [epochSec], rounded up (a subscription ending in 2 h has "1 day" left). */
        fun daysUntil(epochSec: Long, nowMs: Long): Long {
            val ms = epochSec * 1000 - nowMs
            return if (ms <= 0) 0 else (ms + 86_399_999) / 86_400_000
        }

        /** Xtream `allowed_output_formats` → the name users know. */
        fun formatName(f: String): String = when (f.trim().lowercase()) {
            "ts", "mpegts" -> "MPEG-TS"
            "m3u8", "hls" -> "HLS"
            "rtmp" -> "RTMP"
            else -> f.trim().uppercase()
        }

        /** `refresh_log.error_code` / `playlist.last_status` back to a [SourceError] (details are not stored). */
        fun errorFromCode(code: String): SourceError {
            val c = code.trim().lowercase()
            fun <E : Enum<E>> enumOf(values: Array<E>, name: String): E? = values.firstOrNull { it.name.lowercase() == name }
            return when {
                c.startsWith("net.") -> SourceError.Network(enumOf(NetworkKind.entries.toTypedArray(), c.removePrefix("net.")) ?: NetworkKind.OTHER)
                c.startsWith("http.") -> c.removePrefix("http.").toIntOrNull()?.let { SourceError.Http(it) } ?: SourceError.Internal(c)
                c.startsWith("auth.") -> SourceError.Auth(enumOf(AuthReason.entries.toTypedArray(), c.removePrefix("auth.")) ?: AuthReason.UNKNOWN)
                c.startsWith("format.") -> SourceError.NotASource(enumOf(NotASourceKind.entries.toTypedArray(), c.removePrefix("format.")) ?: NotASourceKind.WRONG_FORMAT)
                c.startsWith("cred.") -> SourceError.Credentials(enumOf(CredentialFailure.entries.toTypedArray(), c.removePrefix("cred.")) ?: CredentialFailure.CORRUPT)
                c == "shrink" -> SourceError.SuspiciousShrink(0, 0)
                c == "parse" -> SourceError.Parse("")
                c == "disk" -> SourceError.DiskSpace(0, 0)
                c == "unsupported" -> SourceError.Unsupported("")
                c == "cancelled" -> SourceError.Cancelled
                else -> SourceError.Internal(c)
            }
        }
    }
}
