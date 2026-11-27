package app.thebox.data.sports

import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsConstants
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

/**
 * (CHL hub commit 0, docs/design/m6-chl-hub.md §4.4 b; review N3) HockeyTech's transport, moved verbatim from
 * `HockeyTechScoreSource` (its former private `call`, `read`, `overCap`, `Raw`, and the companion's `statusKind`,
 * `clampRetryAfter`, `headerBytes`), on the `EspnHttp` precedent, so the score source and `ChlDetailSource` share one rule
 * set. No behaviour change: the score source's requests, caps, wire bytes and errors are byte-for-byte as before.
 *
 * Added for the hub (not used by the score source):
 * - [lscluster]: a keyed `lscluster` URL, adding only `key` and `client_code` after the caller's own parameters;
 * - [element]: the one body decode, which also strips the `statviewfeed` JSONP wrapper `( … )` around an **array**;
 * - [json]: one `lscluster` GET → a decoded [JsonElement] or a [Fetch.Failed], under the body cap.
 *
 * Never logs: the callers log `{kind} → {state}` themselves. Nothing here keeps a URL, header or body past the call.
 */
internal class HockeyTechHttp(
    /** The built client (the caller installs `WireMeter`, the call timeout and no cache). */
    private val client: OkHttpClient,
    private val mono: MonoClock,
) {
    sealed interface Raw {
        /** Bytes on the wire: the body off the socket plus the request and response header bytes (lead ruling S2). */
        val wire: Long

        class Body(val text: String, val etag: String?, val receivedMono: Long, val serverDateMs: Long?, override val wire: Long) : Raw
        class NotModified(val receivedMono: Long, val serverDateMs: Long?, override val wire: Long) : Raw

        /** Another status; [retryAfterRawS] unclamped (the kind decides the clamp, [failed]). */
        class Status(val code: Int, val retryAfterRawS: Long?, val receivedMono: Long, override val wire: Long) : Raw {
            fun failed(refusedOn: Set<Int>): Fetch.Failed {
                val kind = statusKind(code, refusedOn)
                return Fetch.Failed(kind, clampRetryAfter(retryAfterRawS, kind), receivedMono)
            }
        }

        /** No usable answer: no response (TIMEOUT / IO, [cause] the exception's class name) or a body over the cap. */
        class Failed(val fetch: Fetch.Failed, val cause: String?, override val wire: Long = 0) : Raw
    }

    /**
     * `enqueue` under the coroutine (cancelling it cancels the call); the body is read on OkHttp's thread with the response
     * open, capped at [cap] before decoding. Never throws but cancellation: any other failure is [Raw.Failed].
     */
    suspend fun call(url: HttpUrl, cap: Long, headers: Map<String, String>): Raw {
        val wire = WireBytes()
        val b = Request.Builder().url(url).header("Accept", "application/json")
        for ((k, v) in headers) b.header(k, v)
        val request = WireMeter.tag(b, wire).build()
        val raw: Raw = suspendCancellableCoroutine { cont ->
            val c = client.newCall(request)
            cont.invokeOnCancellation { c.cancel() }
            c.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(Raw.Failed(Fetch.Failed(NhlScoreSource.ioKind(e), null, mono.nowMs()), e.javaClass.simpleName))
                }

                override fun onResponse(call: Call, response: Response) {
                    val r = try {
                        response.use { read(it, cap) }
                    } catch (e: IOException) { // the body broke off mid-read
                        Raw.Failed(Fetch.Failed(NhlScoreSource.ioKind(e), null, mono.nowMs()), e.javaClass.simpleName)
                    } catch (e: RuntimeException) { // never let a bug kill OkHttp's thread, and never carry its message
                        Raw.Failed(Fetch.Failed(FailKind.BAD_BODY, null, mono.nowMs()), e.javaClass.simpleName)
                    }
                    if (cont.isActive) cont.resume(r)
                }
            })
        }
        // (Update interval §3.2) The listener wrote the body's wire bytes when `response.use` closed it.
        val body = wire.bytes ?: 0L
        return when (raw) {
            is Raw.Body -> Raw.Body(raw.text, raw.etag, raw.receivedMono, raw.serverDateMs, raw.wire + body)
            is Raw.NotModified -> Raw.NotModified(raw.receivedMono, raw.serverDateMs, raw.wire + body)
            is Raw.Status -> Raw.Status(raw.code, raw.retryAfterRawS, raw.receivedMono, raw.wire + body)
            is Raw.Failed -> raw
        }
    }

    /** The response, with its header bytes in `wire` (the body's are added by [call]). */
    private fun read(res: Response, cap: Long): Raw {
        val received = mono.nowMs()
        val date = NhlScoreSource.serverDateMs(res)
        val headers = headerBytes(res)
        return when (val code = res.code) {
            200 -> {
                if (res.body.contentLength() > cap) return overCap(received, headers)
                val source = res.body.source()
                if (source.request(cap + 1)) return overCap(received, headers)
                Raw.Body(source.buffer.readUtf8(), res.header("ETag")?.trim()?.takeIf { it.isNotEmpty() }, received, date, headers)
            }
            304 -> Raw.NotModified(received, date, headers)
            else -> Raw.Status(code, res.header("Retry-After")?.trim()?.toLongOrNull()?.coerceAtLeast(0L), received, headers)
        }
    }

    private fun overCap(received: Long, headers: Long) =
        Raw.Failed(Fetch.Failed(FailKind.BAD_BODY, null, received), "over cap", headers)

    // ------------------------------------------------------------------ (CHL hub commit 0) the hub's lscluster reads

    /**
     * One decoded `lscluster` answer. [Ok.element] is a `JsonObject` or a `JsonArray` (the caller requires the type it
     * expects: `JsonArray` for `statviewfeed`, `JsonObject` otherwise; a wrong type is the caller's `BAD_BODY`). No URL,
     * header or body text is kept; [toString] prints the kind only.
     */
    sealed interface JsonAnswer {
        val wire: Long

        class Ok(val element: JsonElement, val receivedMono: Long, val serverDateMs: Long?, override val wire: Long) : JsonAnswer {
            override fun toString() = "JsonAnswer.Ok(${element::class.simpleName}, $wire B)"
        }

        /** [cause]: the exception's class name or "over cap" (for the caller's log line), never a message. */
        class Failed(val fetch: Fetch.Failed, val cause: String? = null, override val wire: Long = 0) : JsonAnswer {
            override fun toString() = "JsonAnswer.Failed(${fetch.kind}${cause?.let { ", $it" } ?: ""})"
        }
    }

    /**
     * (§4.1 errors, `HockeyTechScoreSource.feed`'s mapping) One `lscluster` GET of [url] (built by [lscluster]), capped at
     * [cap] bytes (default `MAX_BODY_BYTES`, 512 KB):
     * - no response → TIMEOUT / IO; over the cap → BAD_BODY; 401 / 403 → REFUSED; 429 → HTTP_429; 5xx → HTTP_5XX;
     *   any other status (the 404 `{"error":"No such game"}`) → HTTP_OTHER; a 304 → HTTP_OTHER;
     * - a 200 whose text starts with "Invalid key" / "Client access denied" → REFUSED (`Retry-After` none: the caller's
     *   refused floor applies);
     * - a 200 that [element] cannot decode → BAD_BODY.
     * `Retry-After` is clamped by [clampRetryAfter]. Never throws but cancellation.
     */
    suspend fun json(url: HttpUrl, cap: Long = SportsConstants.MAX_BODY_BYTES): JsonAnswer =
        when (val raw = call(url, cap, emptyMap())) {
            is Raw.Failed -> JsonAnswer.Failed(raw.fetch, raw.cause, raw.wire)
            is Raw.NotModified -> JsonAnswer.Failed(Fetch.Failed(FailKind.HTTP_OTHER, null, raw.receivedMono), wire = raw.wire)
            is Raw.Status -> JsonAnswer.Failed(raw.failed(refusedOn = LSCLUSTER_REFUSED_ON), wire = raw.wire)
            is Raw.Body -> when {
                refusedText(raw.text) -> JsonAnswer.Failed(Fetch.Failed(FailKind.REFUSED, null, raw.receivedMono), wire = raw.wire)
                else -> when (val e = element(raw.text)) {
                    null -> JsonAnswer.Failed(Fetch.Failed(FailKind.BAD_BODY, null, raw.receivedMono), wire = raw.wire)
                    else -> JsonAnswer.Ok(e, raw.receivedMono, raw.serverDateMs, raw.wire)
                }
            }
        }

    /** Prints nothing about the client, a URL or a key. */
    override fun toString(): String = "HockeyTechHttp"

    companion object {
        /** (Lead ruling S1) The longest `Retry-After` honoured on a REFUSED answer; every other kind is clamped to 300 s. */
        internal const val REFUSED_RETRY_AFTER_MAX_S = 3_600L

        /** The statuses `lscluster` refuses a key with (as the score source's `feed`). */
        internal val LSCLUSTER_REFUSED_ON = setOf(401, 403)

        /** `lscluster`'s 200 bodies for a refused key. */
        internal val REFUSED_TEXTS = listOf("Invalid key", "Client access denied")

        internal fun refusedText(text: String): Boolean {
            val head = text.trimStart()
            return REFUSED_TEXTS.any { head.startsWith(it) }
        }

        internal fun statusKind(code: Int, refusedOn: Set<Int>): FailKind = when (code) {
            in refusedOn -> FailKind.REFUSED
            429 -> FailKind.HTTP_429
            in 500..599 -> FailKind.HTTP_5XX
            else -> FailKind.HTTP_OTHER
        }

        /**
         * (Lead ruling S1) `Retry-After` in s: REFUSED waits `max(300 s, Retry-After)` (the machine and the repository take
         * the max), so up to [REFUSED_RETRY_AFTER_MAX_S] gets through; every other kind is clamped to
         * [SportsConstants.BACKOFF_MAX_MS], as the NHL and NFL sources do.
         */
        internal fun clampRetryAfter(rawS: Long?, kind: FailKind): Int? {
            rawS ?: return null
            val max = if (kind == FailKind.REFUSED) REFUSED_RETRY_AFTER_MAX_S else SportsConstants.BACKOFF_MAX_MS / 1000
            return rawS.coerceIn(0L, max).toInt()
        }

        /**
         * (Lead ruling S2) The header bytes of one exchange as HTTP/1.1 puts them on the wire: the request line and headers
         * actually sent (the network request, after the interceptors and OkHttp's bridge headers), the status line and the
         * network response's headers, and the two blank lines.
         */
        internal fun headerBytes(res: Response): Long {
            val net = res.networkResponse ?: res
            val req = net.request
            val target = req.url.encodedPath.length + (req.url.encodedQuery?.let { it.length + 1 } ?: 0)
            val requestLine = req.method.length + 1 + target + 1 + HTTP11.length + 2
            val statusLine = HTTP11.length + 1 + 3 + 1 + net.message.length + 2
            return requestLine + req.headers.byteCount() + 2 + statusLine + net.headers.byteCount() + 2
        }

        private const val HTTP11 = "HTTP/1.1"

        /**
         * (§4.4 b) A keyed `lscluster` URL: [HockeyTechEndpoints.feedUrl], then the caller's own parameters in its order
         * ([params]: `feed`, `view` / `tab`, ids, `fmt`, and `lang` — or `lang_code` for `feed=gc`), then only `key` and
         * `client_code`. The result carries the key: it is passed to [json] and never logged, kept or put in a message.
         */
        fun lscluster(endpoints: HockeyTechEndpoints, league: League, params: HttpUrl.Builder.() -> Unit): HttpUrl =
            endpoints.feedUrl.toHttpUrl().newBuilder()
                .apply(params)
                .addQueryParameter("key", endpoints.key(league))
                .addQueryParameter("client_code", HockeyTechEndpoints.client(league))
                .build()

        /**
         * (§4.4 b) The one decode of an `lscluster` body: trims whitespace, strips **one** leading `(` with its matching
         * trailing `)` (the `statviewfeed` JSONP wrapper, `([ … ])`), then parses. Null when the text is not a JSON object
         * or array (a primitive, an HTML page, a `(` without its `)`). The decoder's message, which can quote the body, is
         * never kept.
         */
        fun element(raw: String): JsonElement? {
            var t = raw.trim()
            if (t.startsWith("(")) {
                if (!t.endsWith(")")) return null
                t = t.substring(1, t.length - 1).trim()
            }
            if (t.isEmpty()) return null
            val e = try {
                Json.parseToJsonElement(t)
            } catch (e: IllegalArgumentException) { // SerializationException is one
                null
            }
            // The decoder reads a bare word ("<html>") as a primitive: only an object or an array is a body.
            return e?.takeIf { it is JsonObject || it is JsonArray }
        }
    }
}
