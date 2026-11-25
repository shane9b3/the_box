package app.thebox.data.sports

import app.thebox.core.common.BoxLog
import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * (NFL hub commit 0, docs/design/m6-nfl-hub.md §5.3, §6.5) ESPN's transport, extracted verbatim from `EspnScoreSource`
 * (its former private `call`, `classify`, `CappedStream`, `isHtml` and `logTransition`), so the board, the summary and the
 * clip records share one rule set. The only change is that the decoded-body cap is a per-call parameter ([call]'s
 * `capBytes`; the board passes `ESPN_MAX_BODY_BYTES`, as before).
 *
 * - `enqueue` under the coroutine: cancelling it cancels the OkHttp call. The body is decoded on OkHttp's thread with
 *   kotlinx `decodeFromStream` (`ignoreUnknownKeys`) through a [CappedStream] counting decoded bytes, then drained to the
 *   end so the wire meter counts the whole body and the connection is reusable.
 * - 200: decoded, or `BAD_BODY` over the cap / not JSON / a spine of the wrong type; 404 JSON = `NotFound`, HTML 404 /
 *   400 / other = `HTTP_OTHER`; 429; 5xx.
 * - Logging by state change only, a path label and the result kind (never a query, a body or an id), under [tag].
 *
 * The WAF rule (403, and 202 with `x-amzn-waf-action`, → `HTTP_429` for every call) is P2's (§5.3); today the callers map
 * a 403 themselves.
 */
internal class EspnHttp(
    /** The built client (the caller installs `WireMeter`, the call timeout and no cache). */
    val client: OkHttpClient,
    private val mono: MonoClock,
    private val tag: String,
) {
    /** The last logged state per path label, for state-change logging. */
    private val lastState = HashMap<String, String>()

    /** A decoded [Fetch] plus what the log line may say about it (content type and decoded length, never the body). */
    class Answer<T>(
        val fetch: Fetch<T>,
        val contentType: String?,
        val length: Long?,
        /** The HTTP status (0 = no response) and whether an error body was HTML, for the per-event shape rule. */
        val code: Int = 0,
        val html: Boolean = false,
    ) {
        fun withFetch(f: Fetch<T>) = Answer(f, contentType, length, code, html)
    }

    /**
     * `enqueue` under the coroutine: cancelling it cancels the call. The body is decoded on OkHttp's thread, capped at
     * [capBytes] decoded bytes. [onDecoded] runs on a good decode before the `Ok` is built (the board remembers its events'
     * dates there).
     */
    suspend fun <T> call(
        url: HttpUrl,
        serializer: DeserializationStrategy<T>,
        capBytes: Long,
        http: OkHttpClient = client,
        onDecoded: (T) -> Unit = {},
    ): Answer<T> {
        val wire = WireBytes()
        val request = WireMeter.tag(Request.Builder().url(url).header("Accept", "application/json"), wire).build()
        val answer: Answer<T> = suspendCancellableCoroutine { cont ->
            val c = http.newCall(request)
            cont.invokeOnCancellation { c.cancel() }
            c.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(Answer(Fetch.Failed(NhlScoreSource.ioKind(e), null, mono.nowMs()), null, null))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { Result.success(classify(it, serializer, capBytes, onDecoded)) }
                    } catch (e: IOException) { // the body broke off mid-read
                        Result.success(Answer<T>(Fetch.Failed(NhlScoreSource.ioKind(e), null, mono.nowMs()), null, null))
                    } catch (t: Throwable) { // never let a bug kill OkHttp's thread; the caller decides
                        Result.failure(t)
                    }
                    if (!cont.isActive) return
                    result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                }
            })
        }
        // (Update interval §3.2) The listener wrote the wire bytes when `response.use` closed the body.
        return answer.withFetch(WireMeter.withWire(answer.fetch, wire))
    }

    private fun <T> classify(res: Response, serializer: DeserializationStrategy<T>, cap: Long, onDecoded: (T) -> Unit): Answer<T> {
        val received = mono.nowMs()
        val type = res.header("Content-Type")
        fun failed(kind: FailKind, length: Long? = null, html: Boolean = false) =
            Answer<T>(Fetch.Failed(kind, NhlScoreSource.retryAfterS(res), received), type, length, res.code, html)
        return when (val code = res.code) {
            200 -> {
                val declared = res.body.contentLength()
                if (declared > cap) return failed(FailKind.BAD_BODY, declared)
                val stream = CappedStream(res.body.byteStream(), cap)
                val dto = try {
                    decode(serializer, stream)
                } catch (e: IOException) {
                    if (stream.overCap) return failed(FailKind.BAD_BODY, stream.count)
                    throw e // a real read failure: IO / TIMEOUT
                } catch (e: SerializationException) {
                    return failed(FailKind.BAD_BODY, stream.count)
                } catch (e: IllegalArgumentException) { // a lexer error that is not a SerializationException
                    return failed(FailKind.BAD_BODY, stream.count)
                }
                // (Update interval §3.2) Read to the end (whitespace, the gzip trailer), so the listener's wire bytes are
                // the whole body and the connection is reusable.
                try {
                    val rest = ByteArray(DRAIN_BYTES)
                    while (stream.read(rest) >= 0) Unit
                } catch (e: IOException) {
                    if (stream.overCap) return failed(FailKind.BAD_BODY, stream.count)
                    throw e
                }
                if (stream.overCap) return failed(FailKind.BAD_BODY, stream.count)
                onDecoded(dto)
                val ok = Fetch.Ok(dto, received, NhlScoreSource.serverDateMs(res), NhlScoreSource.ageS(res), WireMeter.maxAgeS(res))
                Answer(ok, type, stream.count, code)
            }
            404 -> if (isHtml(res, type)) failed(FailKind.HTTP_OTHER, html = true) else Answer(Fetch.NotFound, type, null, code)
            400 -> failed(FailKind.HTTP_OTHER, html = isHtml(res, type))
            429 -> failed(FailKind.HTTP_429)
            in 500..599 -> failed(FailKind.HTTP_5XX)
            else -> failed(FailKind.HTTP_OTHER).also { if (code in 300..399) BoxLog.w(tag, "unexpected redirect $code") }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun <T> decode(serializer: DeserializationStrategy<T>, stream: InputStream): T =
        JSON.decodeFromStream(serializer, stream)

    /** An HTML 404 (a captive portal, ESPN's CDN error page for an unknown logo) is not the API saying "no such game". */
    private fun isHtml(res: Response, type: String?): Boolean {
        if (type != null && type.contains("html", ignoreCase = true)) return true
        val source = res.body.source()
        source.request(HTML_SNIFF_BYTES)
        val head = source.buffer.snapshot(minOf(source.buffer.size, HTML_SNIFF_BYTES).toInt()).utf8()
        return head.trimStart().startsWith("<")
    }

    fun logTransition(label: String, fetch: Fetch<*>, answer: Answer<*>) {
        val state = when (fetch) {
            is Fetch.Ok -> "ok"
            Fetch.NotFound -> "not found"
            is Fetch.Failed -> fetch.kind.name
        }
        val previous = synchronized(lastState) { lastState.put(label, state) }
        if (previous == state) return
        val detail = "${answer.contentType ?: "-"}, ${answer.length?.let { "$it B" } ?: "-"}"
        if (state == "ok") BoxLog.i(tag) { "$label → ok ($detail)" } else BoxLog.w(tag, "$label → $state ($detail)")
    }

    /** Counts the decoded bytes and throws once they pass [cap] (the decoder never sees more than a read past it). */
    class CappedStream(input: InputStream, private val cap: Long) : FilterInputStream(input) {
        var count = 0L
            private set
        var overCap = false
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) add(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) add(n.toLong())
            return n
        }

        override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) add(it) }

        private fun add(n: Long) {
            count += n
            if (count > cap) {
                overCap = true
                throw IOException("body over ${cap} B")
            }
        }
    }

    private companion object {
        const val DRAIN_BYTES = 512
        const val HTML_SNIFF_BYTES = 512L
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
