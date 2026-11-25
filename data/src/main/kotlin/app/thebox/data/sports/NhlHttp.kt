package app.thebox.data.sports

import app.thebox.core.common.BoxLog
import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.SportsConstants
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * (Sports Hub commit 0, docs/design/m6-sports-hub.md §4.2, A-S6) The NHL transport, extracted from `NhlScoreSource`
 * without a behaviour change; `NhlScoreSource` (scores) and WP-1's `NhlDetailSource` (game detail) each hold one. WP-1
 * owns this file afterwards.
 *
 * - Plain GETs from the `@ApiClient` (BoxDns, the default UA, redirects, gzip) with a [callTimeoutMs] call timeout and the
 *   [WireMeter] listener. **No ETag, no `If-None-Match`, no cache** (score-bug A-S1).
 * - `Date` → [Fetch.Ok.serverDateMs], `Age` clamped to [0, AGE_MAX_S] (A-S12); `receivedMono` is stamped from [mono] when
 *   the response headers arrive.
 * - Bodies are capped at [SportsConstants.MAX_BODY_BYTES] on `body.source()` before decoding (A-N5); larger, non-JSON or
 *   unmappable bodies are `Failed(BAD_BODY)`. A 404 with a JSON or empty body is [Fetch.NotFound]; an HTML 404 is
 *   `Failed(HTTP_OTHER)` (A-S12).
 * - Logging under [tag]: the path only, never a body, and only when a path kind's state changes (ok ↔ failing).
 */
internal class NhlHttp(
    client: OkHttpClient,
    private val mono: MonoClock,
    private val tag: String,
    callTimeoutMs: Long = SportsConstants.SPORTS_CALL_TIMEOUT_MS,
) {
    private val client: OkHttpClient = WireMeter.install(client.newBuilder(), client)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .cache(null)
        .build()

    /** The last logged state per path kind ("score", "gamecenter"), for state-change logging. */
    private val lastState = HashMap<String, String>()

    /** A [Fetch] plus what the log line may say about it (content type and length, never the body). */
    class Answer<T>(val fetch: Fetch<T>, val contentType: String?, val length: Long?)

    /**
     * GET [url] (logged as [path] under [kind]) and map a 200's JSON object with [map] (null or a throw = `BAD_BODY`).
     * The result carries the wire bytes.
     */
    suspend fun <T> get(url: String, path: String, kind: String, map: (JsonObject) -> T?): Fetch<T> {
        val wire = WireBytes()
        val request = WireMeter.tag(Request.Builder().url(url).header("Accept", "application/json"), wire).build()
        val answer = call(request) { res -> classify(res, map) }
        logTransition(kind, path, answer)
        // (Update interval §3.2) The wire bytes: the listener wrote them when `response.use` closed the body.
        return WireMeter.withWire(answer.fetch, wire)
    }

    fun <T> classify(res: Response, map: (JsonObject) -> T?): Answer<T> {
        val received = mono.nowMs()
        val type = res.header("Content-Type")
        fun failed(kind: FailKind, length: Long? = null) =
            Answer<T>(Fetch.Failed(kind, retryAfterS(res), received), type, length)
        return when (val code = res.code) {
            200 -> {
                val text = readCapped(res) ?: return failed(FailKind.BAD_BODY)
                val json = try {
                    Json.parseToJsonElement(text) as? JsonObject
                } catch (e: IllegalArgumentException) { // SerializationException is one
                    null
                } ?: return failed(FailKind.BAD_BODY, text.length.toLong())
                val value = try {
                    map(json)
                } catch (e: RuntimeException) {
                    null
                } ?: return failed(FailKind.BAD_BODY, text.length.toLong())
                Answer(Fetch.Ok(value, received, serverDateMs(res), ageS(res), WireMeter.maxAgeS(res)), type, text.length.toLong())
            }
            404 -> if (isHtml(res, type)) failed(FailKind.HTTP_OTHER) else Answer(Fetch.NotFound, type, null)
            429 -> failed(FailKind.HTTP_429)
            in 500..599 -> failed(FailKind.HTTP_5XX)
            else -> failed(FailKind.HTTP_OTHER).also { if (code in 300..399) BoxLog.w(tag, "unexpected redirect $code") }
        }
    }

    /** `enqueue` under the coroutine: cancelling it cancels the call. [read] runs on OkHttp's thread with the response open. */
    suspend fun <T> call(request: Request, read: (Response) -> Answer<T>): Answer<T> =
        suspendCancellableCoroutine { cont ->
            val c = client.newCall(request)
            cont.invokeOnCancellation { c.cancel() }
            c.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(Answer(Fetch.Failed(ioKind(e), null, mono.nowMs()), null, null))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { Result.success(read(it)) }
                    } catch (e: IOException) { // the body broke off mid-read
                        Result.success(Answer<T>(Fetch.Failed(ioKind(e), null, mono.nowMs()), null, null))
                    } catch (t: Throwable) { // never let a mapper bug kill OkHttp's thread; the caller decides
                        Result.failure(t)
                    }
                    if (!cont.isActive) return
                    result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                }
            })
        }

    fun logTransition(kind: String, path: String, answer: Answer<*>) {
        val state = when (val f = answer.fetch) {
            is Fetch.Ok -> "ok"
            Fetch.NotFound -> "not found"
            is Fetch.Failed -> f.kind.name
        }
        val previous = synchronized(lastState) { lastState.put(kind, state) }
        if (previous == state) return
        val detail = "${answer.contentType ?: "-"}, ${answer.length?.let { "$it B" } ?: "-"}"
        if (state == "ok") BoxLog.i(tag) { "$path → ok ($detail)" } else BoxLog.w(tag, "$path → $state ($detail)")
    }

    companion object {
        private const val HTML_SNIFF_BYTES = 512L

        /** The body as text, or null when it is over [SportsConstants.MAX_BODY_BYTES] (checked before decoding, A-N5). */
        fun readCapped(res: Response): String? {
            val source = res.body.source()
            if (source.request(SportsConstants.MAX_BODY_BYTES + 1)) return null
            return source.buffer.readUtf8()
        }

        /** An HTML 404 (a captive portal, a CDN error page) is not the API saying "no such game" (A-S12). */
        fun isHtml(res: Response, type: String?): Boolean {
            if (type != null && type.contains("html", ignoreCase = true)) return true
            val source = res.body.source()
            source.request(HTML_SNIFF_BYTES)
            val head = source.buffer.snapshot(minOf(source.buffer.size, HTML_SNIFF_BYTES).toInt()).utf8()
            return head.trimStart().startsWith("<")
        }

        fun ioKind(e: IOException): FailKind = when {
            e is SocketTimeoutException -> FailKind.TIMEOUT
            e is InterruptedIOException && e.message?.contains("timeout", ignoreCase = true) == true -> FailKind.TIMEOUT
            else -> FailKind.IO
        }

        fun serverDateMs(res: Response): Long? = res.headers.getDate("Date")?.time

        /** `Age`, clamped to [0, AGE_MAX_S]; absent or unreadable = 0 (A-S12). */
        fun ageS(res: Response): Int =
            res.header("Age")?.trim()?.toLongOrNull()?.coerceIn(0L, SportsConstants.AGE_MAX_S.toLong())?.toInt() ?: 0

        /** `Retry-After` in seconds (delta form only), clamped to [0, BACKOFF_MAX_MS]. */
        fun retryAfterS(res: Response): Int? =
            res.header("Retry-After")?.trim()?.toLongOrNull()
                ?.coerceIn(0L, SportsConstants.BACKOFF_MAX_MS / 1000)?.toInt()
    }
}
