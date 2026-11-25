package app.thebox.data.sports

import android.content.Context
import app.thebox.core.common.BoxLog
import app.thebox.core.common.Redact
import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.SportsConstants
import app.thebox.data.di.ApiClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §6.2, A-S4, A-N6; WP-2) The playback key, read at run time from the public
 * player script: memory only, once per process on first need, `Redact.registerSecret` at once, a hold of
 * `HUB_VIDEO_KEY_REREAD_MS` after a read that found no key or was refused, a short backoff after a transient failure,
 * one re-read after a 401 / 403. Never logged, stored or written down.
 *
 * - The script is fetched over **https only** (the debug build's fake-panel override excepted, [NhlVideoEndpoints.allowed]),
 *   with no `Origin` and no `Referer`, and an https → http redirect is not followed.
 * - The scan ([KeyScan]) reads `body.source()` (OkHttp's transparent gzip, so the cap counts decoded bytes) up to
 *   [SportsConstants.HUB_VIDEO_SCRIPT_MAX] and keeps the first **valid** candidate: an empty default or a placeholder is
 *   skipped and the scan continues.
 * - A read that found no valid key, or a refused re-read, holds every further read for
 *   [SportsConstants.HUB_VIDEO_KEY_REREAD_MS] (10 min), answering [FailKind.REFUSED] meanwhile.
 * - (Sports Hub code review 2026-11-25, M1) A transient failure (timeout, IO, 5xx, 429, another status) backs off instead:
 *   [SportsConstants.HUB_VIDEO_KEY_BACKOFF_MIN_MS] doubling to [SportsConstants.HUB_VIDEO_KEY_BACKOFF_MAX_MS], with
 *   `Retry-After` as a floor (bounded by the 10 min hold), answering the failure's kind meanwhile. A Fire TV that wakes
 *   before its Wi-Fi does no longer turns video off for 10 minutes.
 * - Log lines: "video key: read" / "video key: not found" / "video key: refused" / "video key: script <kind>"; never a
 *   value, a prefix or a length.
 */
@Singleton
class NhlVideoKeySource internal constructor(
    client: OkHttpClient,
    private val endpointsOf: () -> NhlVideoEndpoints,
    private val mono: MonoClock,
) {
    @Inject constructor(@ApiClient client: OkHttpClient, @ApplicationContext context: Context) :
        this(client, { SportsEndpoints.resolveVideo(context) }, ScoreRepositoryImpl.ELAPSED)

    /** What [key] answers. [Unavailable.kind] is [FailKind.REFUSED] when the service is not usable (no key, refused). */
    sealed interface Answer {
        class Ok(val value: String) : Answer {
            override fun toString() = "Ok(***)"
        }
        data class Unavailable(val kind: FailKind, val retryAfterS: Int? = null) : Answer
    }

    private val client: OkHttpClient = client.newBuilder()
        .callTimeout(SCRIPT_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followSslRedirects(false)
        .cache(null)
        .build()

    private val lock = Mutex()
    @Volatile private var key: String? = null
    @Volatile private var endpoints: NhlVideoEndpoints? = null
    /** Mono ms until which no read is made, and what is answered meanwhile. */
    private var holdUntilMs = Long.MIN_VALUE
    private var holdKind = FailKind.REFUSED
    /** (L-a) The hold was set by the service's `Retry-After`: answers inside it carry the rest of the wait. */
    private var holdByServer = false
    /** The next transient backoff (M1); reset by a successful read or a refusal hold. */
    private var nextBackoffMs = SportsConstants.HUB_VIDEO_KEY_BACKOFF_MIN_MS

    /** The endpoints of this build, resolved once (the debug override file is read on IO). */
    suspend fun endpoints(): NhlVideoEndpoints =
        endpoints ?: withContext(Dispatchers.IO) { endpointsOf() }.also { endpoints = it }

    /** The key, reading the script if needed; [Answer.Unavailable] when it cannot be read (or inside the hold). */
    suspend fun key(): Answer {
        key?.let { return Answer.Ok(it) }
        return lock.withLock {
            key?.let { return@withLock Answer.Ok(it) }
            val now = mono.nowMs()
            if (now < holdUntilMs) return@withLock Answer.Unavailable(holdKind, if (holdByServer) secondsUntil(now) else null)
            when (val r = read()) {
                is Answer.Ok -> {
                    Redact.registerSecret(r.value)
                    key = r.value
                    nextBackoffMs = SportsConstants.HUB_VIDEO_KEY_BACKOFF_MIN_MS
                    BoxLog.i(TAG) { "video key: read" }
                    r
                }
                is Answer.Unavailable -> {
                    if (r.kind == FailKind.REFUSED) hold(r.kind) else backOff(r.kind, r.retryAfterS)
                    if (r.kind == FailKind.REFUSED) BoxLog.w(TAG, "video key: not found") else BoxLog.w(TAG, "video key: script ${r.kind.name}")
                    // (L-a) The wait that now applies, when the service set it (the floor may be capped below its ask).
                    if (holdByServer) r.copy(retryAfterS = secondsUntil(now)) else r
                }
            }
        }
    }

    /**
     * Drop [stale] after a 401 / 403, so the next [key] re-reads the script (once). A key that another lookup already
     * replaced is left alone, so two refused lookups cause one read.
     */
    fun invalidate(stale: String) {
        synchronized(this) { if (key == stale) key = null }
    }

    /** The re-read key was refused too: no key, and the hold starts (§6.2). */
    suspend fun refused() {
        lock.withLock {
            key = null
            hold(FailKind.REFUSED)
        }
        BoxLog.w(TAG, "video key: refused")
    }

    private fun secondsUntil(now: Long): Int = ((holdUntilMs - now + 999) / 1000).coerceAtLeast(0L).toInt()

    private fun hold(kind: FailKind) {
        holdUntilMs = mono.nowMs() + SportsConstants.HUB_VIDEO_KEY_REREAD_MS
        holdKind = kind
        holdByServer = false
        nextBackoffMs = SportsConstants.HUB_VIDEO_KEY_BACKOFF_MIN_MS
    }

    /** (M1) A transient failure: wait [nextBackoffMs] (at least `Retry-After`), then double it up to the cap. */
    private fun backOff(kind: FailKind, retryAfterS: Int?) {
        val floor = (retryAfterS ?: 0).toLong() * 1000
        val wait = maxOf(nextBackoffMs, floor).coerceAtMost(SportsConstants.HUB_VIDEO_KEY_REREAD_MS)
        holdUntilMs = mono.nowMs() + wait
        holdKind = kind
        holdByServer = floor > nextBackoffMs
        nextBackoffMs = (nextBackoffMs * 2).coerceAtMost(SportsConstants.HUB_VIDEO_KEY_BACKOFF_MAX_MS)
    }

    private suspend fun read(): Answer {
        val ep = endpoints()
        if (!ep.allowed(ep.playerScript)) return Answer.Unavailable(FailKind.REFUSED)
        val request = Request.Builder().url(ep.playerScript).header("Accept", "*/*").build()
        return try {
            client.awaitVideoCall(request) { res ->
                when (val code = res.code) {
                    200 -> KeyScan.scan(res.body.source(), SportsConstants.HUB_VIDEO_SCRIPT_MAX)
                        ?.let { Answer.Ok(it) } ?: Answer.Unavailable(FailKind.REFUSED)
                    429 -> Answer.Unavailable(FailKind.HTTP_429, NhlHttp.retryAfterS(res))
                    in 500..599 -> Answer.Unavailable(FailKind.HTTP_5XX, NhlHttp.retryAfterS(res))
                    else -> Answer.Unavailable(if (code in 300..399 || code == 404) FailKind.REFUSED else FailKind.HTTP_OTHER)
                }
            }
        } catch (e: IOException) {
            Answer.Unavailable(ioKind(e))
        }
    }

    internal companion object {
        private const val TAG = "SportsVideo"
        /** The script is one larger body (≈ 0.5 MB decoded); the lookups keep their 10 s. */
        const val SCRIPT_CALL_TIMEOUT_MS = 20_000L

        fun ioKind(e: IOException): FailKind = when {
            e is SocketTimeoutException -> FailKind.TIMEOUT
            e is InterruptedIOException && e.message?.contains("timeout", ignoreCase = true) == true -> FailKind.TIMEOUT
            else -> FailKind.IO
        }
    }
}

/**
 * (§6.2, A-S4) The bounded streaming scan for the player's key: `policyKey:"…"`, `policyKey:'…'` and `"policyKey":"…"`
 * (whitespace around `:` allowed). A candidate that is not [MIN_LENGTH]–[MAX_LENGTH] characters of `[A-Za-z0-9_\-.]` (an
 * empty default, a `{{placeholder}}`) is skipped and the scan continues. Matches across read boundaries are found; at most
 * `maxBytes` decoded bytes are read. Pure apart from reading [BufferedSource].
 */
internal object KeyScan {
    const val NAME = "policyKey"
    const val MIN_LENGTH = 20
    const val MAX_LENGTH = 400
    private const val CHUNK = 8_192L
    private const val MAX_SPACE = 16

    private sealed interface Step
    private class Found(val value: String) : Step
    private data object Invalid : Step
    private data object NeedMore : Step

    /** The first valid key in [source] within [maxBytes] decoded bytes, or null. */
    fun scan(source: BufferedSource, maxBytes: Long): String? {
        val window = StringBuilder()
        val chunk = Buffer()
        var read = 0L
        var eof = false
        var from = 0
        while (true) {
            scanWindow@ while (true) {
                val idx = window.indexOf(NAME, from)
                if (idx < 0) {
                    // Keep a possible split name at the end.
                    from = maxOf(from, window.length - (NAME.length - 1)).coerceAtLeast(0)
                    break@scanWindow
                }
                when (val step = parseAt(window, idx, eof)) {
                    is Found -> return step.value
                    Invalid -> from = idx + NAME.length
                    NeedMore -> {
                        from = idx
                        break@scanWindow
                    }
                }
            }
            if (eof || read >= maxBytes) return null
            if (from > 0) {
                window.delete(0, from)
                from = 0
            }
            val n = source.read(chunk, minOf(CHUNK, maxBytes - read))
            if (n < 0) {
                eof = true
            } else {
                read += n
                // Latin-1: one char per byte, so the cap and the window stay in step; the key alphabet is ASCII.
                window.append(chunk.readString(Charsets.ISO_8859_1))
            }
        }
    }

    private fun parseAt(w: CharSequence, idx: Int, eof: Boolean): Step {
        val more: Step = if (eof) Invalid else NeedMore
        val before = if (idx > 0) w[idx - 1] else ' '
        val nameQuote = if (before == '"' || before == '\'') before else null
        if (nameQuote == null && isIdent(before)) return Invalid
        var i = idx + NAME.length
        if (nameQuote != null) {
            if (i >= w.length) return more
            if (w[i] != nameQuote) return Invalid
            i++
        } else {
            if (i >= w.length) return more
            if (isIdent(w[i])) return Invalid
        }
        i = skipSpace(w, i) ?: return Invalid
        if (i >= w.length) return more
        if (w[i] != ':') return Invalid
        i = skipSpace(w, i + 1) ?: return Invalid
        if (i >= w.length) return more
        val q = w[i]
        if (q != '"' && q != '\'') return Invalid
        val start = i + 1
        var j = start
        while (j < w.length && w[j] != q) {
            if (j - start > MAX_LENGTH) return Invalid
            j++
        }
        if (j >= w.length) return if (j - start > MAX_LENGTH) Invalid else more
        val value = w.subSequence(start, j)
        if (value.length !in MIN_LENGTH..MAX_LENGTH || !value.all(::isKeyChar)) return Invalid
        return Found(value.toString())
    }

    /** The index after up to [MAX_SPACE] whitespace characters from [i]; null when there are more. */
    private fun skipSpace(w: CharSequence, i: Int): Int? {
        var k = i
        while (k < w.length && (w[k] == ' ' || w[k] == '\t' || w[k] == '\n' || w[k] == '\r')) {
            k++
            if (k - i > MAX_SPACE) return null
        }
        return k
    }

    private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    private fun isKeyChar(c: Char) = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '_' || c == '-' || c == '.'
}

/**
 * `enqueue` under the coroutine: cancelling it cancels the call. [read] runs on OkHttp's thread with the response open;
 * an [IOException] (connect, timeout, a body that broke off) is thrown to the caller.
 */
internal suspend fun <T> OkHttpClient.awaitVideoCall(request: Request, read: (Response) -> T): T =
    suspendCancellableCoroutine { cont ->
        val c = newCall(request)
        cont.invokeOnCancellation { c.cancel() }
        c.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { Result.success(read(it)) }
                } catch (t: Throwable) { // IOException mid-body, or a mapper bug: never kill OkHttp's thread
                    Result.failure(t)
                }
                if (!cont.isActive) return
                result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
            }
        })
    }
