package app.thebox.core.setup.lan

import app.thebox.core.common.Redact
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64

/**
 * The pairing rules of one server lifetime (docs/design/m5-setup-settings.md §3.5.2), pure and clock-driven:
 * a 128-bit [token] (QR path `/s/{token}`) or the 6-digit [code] pairs **one** phone; once paired, neither works from
 * any other IP ([PairResult.Busy]). Pairing issues a second 128-bit session cookie bound to the phone's IP.
 * Same-IP re-pair (owner finding 2026-11-01: the phone's QR scanner fetched the link before the browser, so the
 * browser arrived without the cookie and the code then answered "another phone"): the right token or code presented
 * again **from the paired IP** pairs again with a new cookie, and the previous cookie is refused from then on. A wrong
 * token after pairing stays [PairResult.Busy]; a wrong code from the paired IP still counts. [PhoneApi] never pairs
 * on a HEAD or prefetch of the token path. [LanLimits.PAIR_MAX_WRONG] wrong codes rotate the code once; the second
 * exhaustion stops the session ([LanFailure.TOO_MANY_WRONG_CODES]). Idle [LanLimits.PAIR_IDLE_MS] and absolute [LanLimits.PAIR_MAX_MS] lifetimes end it with [LanFailure.EXPIRED].
 * Thread-safe (the server's workers call it concurrently). Token, code and cookie are registered for redaction.
 */
class PairingSession(
    private val clock: () -> Long,
    private val randomBytes: (ByteArray) -> Unit = { SecureRandom().nextBytes(it) },
    private val maxWrong: Int = LanLimits.PAIR_MAX_WRONG,
    private val idleMs: Long = LanLimits.PAIR_IDLE_MS,
    private val maxMs: Long = LanLimits.PAIR_MAX_MS,
    /** Called after every change the TV screen shows (pairing, a new code, a failure, a request). */
    private val onChange: () -> Unit = {},
) {
    sealed interface PairResult {
        data class Paired(val cookie: String) : PairResult
        /** Wrong code; [left] tries before the code rotates (0 = it just rotated). */
        data class Wrong(val left: Int) : PairResult
        /** Another phone (another IP) already paired. */
        data object Busy : PairResult
        /** The session ended ([failure] says why). */
        data class Stopped(val failure: LanFailure?) : PairResult
    }

    enum class Auth { OK, NO_SESSION, OTHER_PHONE, ENDED }

    private val lock = Any()
    val startedAtMs: Long = clock()
    val token: String = newSecret()

    @Volatile var code: String = newCode()
        private set
    @Volatile var pairedFrom: String? = null
        private set
    @Volatile var lastRequestAtMs: Long? = null
        private set
    /** Wrong codes in the current code's cycle. */
    @Volatile var wrongCodes: Int = 0
        private set
    /** True once a wrong-code exhaustion rotated the code (the TV says "here is a new one"). */
    @Volatile var rotated: Boolean = false
        private set
    @Volatile var failure: LanFailure? = null
        private set
    @Volatile var ended: Boolean = false
        private set

    private var cookie: String? = null

    /** QR path pairing. */
    fun pairWithToken(candidate: String, ip: String): PairResult = synchronized(lock) {
        noteRequest()
        if (ended) return PairResult.Stopped(failure)
        val paired = pairedFrom
        val right = equalSecret(candidate, token)
        if (paired != null && (paired != ip || !right)) return PairResult.Busy
        if (!right) return PairResult.Stopped(null)
        pair(ip) // first pairing, or a same-IP re-pair (2026-11-01): a new cookie replaces the old one
    }

    fun pairWithCode(candidate: String, ip: String): PairResult = synchronized(lock) {
        noteRequest()
        if (ended) return PairResult.Stopped(failure)
        // Another IP is refused uncounted; from the paired IP the right code re-pairs and a wrong one counts (2026-11-01).
        if (pairedFrom != null && pairedFrom != ip) return PairResult.Busy
        val digits = candidate.filter { it in '0'..'9' }
        if (digits.length == CODE_DIGITS && equalSecret(digits, code)) return pair(ip)
        wrongCodes++
        val left = maxWrong - wrongCodes
        if (left > 0) {
            onChange()
            return PairResult.Wrong(left)
        }
        if (rotated) {
            end(LanFailure.TOO_MANY_WRONG_CODES)
            return PairResult.Stopped(failure)
        }
        rotated = true
        wrongCodes = 0
        code = newCode()
        onChange()
        PairResult.Wrong(0)
    }

    /** Every request after pairing: the cookie must match and come from the paired IP. */
    fun authorize(cookieValue: String?, ip: String): Auth = synchronized(lock) {
        noteRequest()
        if (ended) return Auth.ENDED
        val c = cookie ?: return Auth.NO_SESSION
        if (cookieValue == null || !equalSecret(cookieValue, c)) return if (ip != pairedFrom) Auth.OTHER_PHONE else Auth.NO_SESSION
        if (ip != pairedFrom) return Auth.OTHER_PHONE
        Auth.OK
    }

    /** Checks the lifetimes; returns the failure that ended the session now, if any. */
    fun checkExpiry(): LanFailure? = synchronized(lock) {
        if (ended) return null
        val now = clock()
        val last = lastRequestAtMs ?: startedAtMs
        if (now - startedAtMs >= maxMs || now - last >= idleMs) {
            end(LanFailure.EXPIRED)
            return LanFailure.EXPIRED
        }
        null
    }

    /** Ends the session (screen left, success, app stop). [why] null = a normal close. */
    fun end(why: LanFailure?) {
        synchronized(lock) {
            if (ended) return
            ended = true
            failure = why
        }
        onChange()
    }

    private fun pair(ip: String): PairResult {
        val c = newSecret()
        cookie = c
        pairedFrom = ip
        onChange()
        return PairResult.Paired(c)
    }

    private fun noteRequest() {
        if (!ended) lastRequestAtMs = clock()
    }

    private fun newSecret(): String {
        val b = ByteArray(16)
        randomBytes(b)
        return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(b).also { Redact.registerTransient(it) }
    }

    private fun newCode(): String {
        val b = ByteArray(4)
        randomBytes(b)
        val v = ((b[0].toLong() and 0xff) shl 24 or ((b[1].toLong() and 0xff) shl 16) or ((b[2].toLong() and 0xff) shl 8) or (b[3].toLong() and 0xff))
        return (v % 1_000_000L).toString().padStart(CODE_DIGITS, '0').also { Redact.registerTransient(it) }
    }

    companion object {
        const val CODE_DIGITS = 6

        fun equalSecret(a: String, b: String): Boolean = MessageDigest.isEqual(a.encodeToByteArray(), b.encodeToByteArray())

        /** "482913" → "482 913" (the TV shows it with a space). */
        fun displayCode(code: String): String = if (code.length == CODE_DIGITS) code.substring(0, 3) + " " + code.substring(3) else code
    }
}
