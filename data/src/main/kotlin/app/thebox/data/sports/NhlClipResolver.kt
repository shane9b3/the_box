package app.thebox.data.sports

import app.thebox.core.common.BoxLog
import app.thebox.core.common.Redact
import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.BrightcoveMapper
import app.thebox.core.sports.hub.ClipInfo
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.ClipResolver
import app.thebox.core.sports.hub.ClipResult
import app.thebox.core.sports.hub.ClipStream
import app.thebox.data.di.ApiClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §6.3; WP-2) Video lookups on the playback service (https only, `@ApiClient`,
 * 10 s, body cap `HUB_VIDEO_BODY_MAX`, `BrightcoveMapper`); [info] cached `HUB_CLIP_META_TTL_MS` (LRU 40), [stream]
 * always looked up again; signed tokens registered with `Redact.registerTransient`.
 *
 * - `Accept: application/json;pk={key}`; no `Origin`, no `Referer`; an https → http redirect is not followed.
 * - 401, or 403 without `CLIENT_GEO`: the key is dropped, the script re-read once and the lookup retried once; refused
 *   again → [ClipResult.Refused] and the key source's hold (§6.2). 403 `CLIENT_GEO` → [ClipResult.Geo]. 404 →
 *   [ClipResult.NotFound]. 429 / 5xx / IO / timeout → [ClipResult.Failed]. A 200 that does not map → `Failed(BAD_BODY)`,
 *   as does a [stream] answer with no playable source.
 * - Logs name the video id and the result kind only.
 */
@Singleton
class NhlClipResolver internal constructor(
    client: OkHttpClient,
    private val keys: NhlVideoKeySource,
    private val mono: MonoClock,
) : ClipResolver {
    @Inject constructor(@ApiClient client: OkHttpClient, keys: NhlVideoKeySource) : this(client, keys, ScoreRepositoryImpl.ELAPSED)

    private val client: OkHttpClient = client.newBuilder()
        .callTimeout(LOOKUP_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        // (Sports Hub code review 2026-11-25, L2) No redirect at all: OkHttp keeps the `Accept: …;pk=` header across a
        // host change (it strips only `Authorization`), and the lookup never expects one (`classify` treats 3xx as such).
        .followRedirects(false)
        .followSslRedirects(false)
        .cache(null)
        .build()

    private class Cached(val info: ClipInfo, val atMs: Long)

    /** Access-ordered LRU of [HUB_CLIP_META_LRU] entries by video id. */
    private val cache = object : LinkedHashMap<Long, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Cached>?): Boolean = size > HUB_CLIP_META_LRU
    }

    override suspend fun info(ref: ClipRef): ClipResult<ClipInfo> {
        val now = mono.nowMs()
        synchronized(cache) {
            cache[ref.videoId]?.takeIf { now - it.atMs < SportsConstants.HUB_CLIP_META_TTL_MS }?.let { return ClipResult.Ok(it.info.copy(ref = ref)) }
        }
        return when (val r = lookup(ref.videoId)) {
            is ClipResult.Ok -> {
                val info = BrightcoveMapper.info(ref, r.value) ?: return failed(ref, FailKind.BAD_BODY, "info")
                remember(info)
                logResult(ref, "info", "ok")
                ClipResult.Ok(info)
            }
            else -> r.relay(ref, "info")
        }
    }

    override suspend fun stream(ref: ClipRef): ClipResult<Pair<ClipInfo, ClipStream>> =
        when (val r = lookup(ref.videoId)) {
            is ClipResult.Ok -> {
                val info = BrightcoveMapper.info(ref, r.value)
                val stream = BrightcoveMapper.stream(r.value)
                // A-S4: the signed tokens are masked before anything can print them.
                stream?.let { registerTokens(it.url); it.captionsUrl?.let(::registerTokens) }
                when {
                    info == null -> failed(ref, FailKind.BAD_BODY, "stream")
                    stream == null -> failed(ref, FailKind.BAD_BODY, "stream (nothing playable)")
                    else -> {
                        remember(info)
                        logResult(ref, "stream", "ok ${stream.format}")
                        ClipResult.Ok(info to stream)
                    }
                }
            }
            else -> r.relay(ref, "stream")
        }

    /** One lookup with the §6.2 refusal rule; [ClipResult.Ok] carries the answer's JSON. */
    private suspend fun lookup(videoId: Long): ClipResult<JsonObject> {
        val ep = keys.endpoints()
        val url = ep.playbackBase + videoId
        if (!ep.allowed(url)) return ClipResult.Refused
        val first = when (val k = keys.key()) {
            is NhlVideoKeySource.Answer.Ok -> k.value
            is NhlVideoKeySource.Answer.Unavailable -> return unavailable(k.kind)
        }
        val r = fetch(url, first)
        if (r !is Denied) return r.result
        keys.invalidate(first)
        val second = when (val k = keys.key()) {
            is NhlVideoKeySource.Answer.Ok -> k.value
            is NhlVideoKeySource.Answer.Unavailable -> return unavailable(k.kind)
        }
        val again = fetch(url, second)
        if (again !is Denied) return again.result
        keys.refused()
        return ClipResult.Refused
    }

    private fun unavailable(kind: FailKind): ClipResult<Nothing> = if (kind == FailKind.REFUSED) ClipResult.Refused else ClipResult.Failed(kind)

    /** A fetch's outcome; [Denied] = 401 or 403 without `CLIENT_GEO` (the key was refused). */
    private sealed interface Outcome { val result: ClipResult<JsonObject> }
    private class Answered(override val result: ClipResult<JsonObject>) : Outcome
    private data object Denied : Outcome { override val result: ClipResult<JsonObject> get() = ClipResult.Refused }

    private suspend fun fetch(url: String, key: String): Outcome {
        val request = Request.Builder().url(url).header("Accept", "application/json;pk=$key").build()
        return try {
            client.awaitVideoCall(request, ::classify)
        } catch (e: IOException) {
            Answered(ClipResult.Failed(NhlVideoKeySource.ioKind(e)))
        }
    }

    private fun classify(res: Response): Outcome = when (val code = res.code) {
        200 -> {
            val text = capped(res)
            val json = text?.let {
                try {
                    Json.parseToJsonElement(it) as? JsonObject
                } catch (e: IllegalArgumentException) { // SerializationException is one
                    null
                }
            }
            Answered(json?.let { ClipResult.Ok(it) } ?: ClipResult.Failed(FailKind.BAD_BODY))
        }
        401 -> Denied
        403 -> if (capped(res)?.contains(GEO_SUBCODE) == true) Answered(ClipResult.Geo) else Denied
        404 -> Answered(ClipResult.NotFound)
        429 -> Answered(ClipResult.Failed(FailKind.HTTP_429))
        in 500..599 -> Answered(ClipResult.Failed(FailKind.HTTP_5XX))
        else -> Answered(ClipResult.Failed(FailKind.HTTP_OTHER)).also { if (code in 300..399) BoxLog.w(TAG, "video lookup: unexpected redirect $code") }
    }

    private fun remember(info: ClipInfo) {
        val now = mono.nowMs()
        synchronized(cache) { cache[info.ref.videoId] = Cached(info, now) }
    }

    private fun <T> ClipResult<JsonObject>.relay(ref: ClipRef, what: String): ClipResult<T> {
        @Suppress("UNCHECKED_CAST")
        val out = this as ClipResult<T> // every non-Ok case carries no value
        logResult(ref, what, when (this) {
            is ClipResult.Failed -> kind.name
            ClipResult.Geo -> "geo"
            ClipResult.NotFound -> "not found"
            ClipResult.Refused -> "refused"
            is ClipResult.Ok<*> -> "ok"
        })
        return out
    }

    private fun failed(ref: ClipRef, kind: FailKind, what: String): ClipResult<Nothing> {
        logResult(ref, what, kind.name)
        return ClipResult.Failed(kind)
    }

    private fun logResult(ref: ClipRef, what: String, result: String) {
        if (result.startsWith("ok")) BoxLog.i(TAG) { "video ${ref.videoId} $what: $result" } else BoxLog.w(TAG, "video ${ref.videoId} $what: $result")
    }

    internal companion object {
        private const val TAG = "SportsVideo"
        const val LOOKUP_CALL_TIMEOUT_MS = 10_000L
        /** §6.3: the clip-info cache holds this many videos. */
        const val HUB_CLIP_META_LRU = 40
        /** The playback service's geo refusal (`error_subcode`). */
        const val GEO_SUBCODE = "CLIENT_GEO"
        /** Query values shorter than this are not token-like (`?redacted=1`, `v=2`). */
        private const val TOKEN_MIN = 8

        /** The body as text, or null when it is over [SportsConstants.HUB_VIDEO_BODY_MAX] (checked before decoding). */
        fun capped(res: Response): String? {
            val source = res.body.source()
            if (source.request(SportsConstants.HUB_VIDEO_BODY_MAX + 1)) return null
            return source.buffer.readUtf8()
        }

        /**
         * (A-S4) Registers the signed parts of [url] as transient secrets: the whole query and each token-like value in
         * it, so a URL cut or re-encoded by a library still prints masked.
         */
        fun registerTokens(url: String) {
            val query = url.substringAfter('?', "").substringBefore('#')
            if (query.isEmpty()) return
            if (query.length >= TOKEN_MIN) Redact.registerTransient(query)
            for (part in query.split('&')) {
                val value = part.substringAfter('=', "")
                if (value.length >= TOKEN_MIN) Redact.registerTransient(value)
            }
        }
    }
}
