package app.thebox.data.sports

import android.content.Context
import app.thebox.core.common.BoxLog
import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.NhlMapper
import app.thebox.core.sports.ScoreSource
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.TeamLine
import app.thebox.data.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The NHL's base URLs (docs/design/m6-score-bug.md §2.1, §2.2). One place; a **debug** build can point both at the fake
 * panel (A-N3): `adb shell run-as app.thebox.debug sh -c 'mkdir -p files/debug && echo http://10.0.2.2:8080 >
 * files/debug/sports_base'`, then restart the app. The file is read only behind `BuildConfig.DEBUG` (see [resolve]).
 */
data class SportsEndpoints(val apiBase: String, val logoBase: String) {
    companion object {
        const val API_BASE = "https://api-web.nhle.com/v1/"
        const val LOGO_BASE = "https://assets.nhle.com/logos/nhl/svg/"
        val DEFAULT = SportsEndpoints(API_BASE, LOGO_BASE)

        /** Under `filesDir`; one line, an origin such as `http://10.0.2.2:8080` (the one constant in `:core`). */
        const val DEBUG_OVERRIDE_FILE = SportsConstants.DEBUG_OVERRIDE_FILE

        /**
         * An override origin → `{origin}/v1/` and `{origin}/logos/nhl/svg/` (the fake panel serves the NHL's own paths).
         * Null for a blank or unusable text (not http/https, or not a URL).
         */
        fun fromOverride(text: String?): SportsEndpoints? {
            val t = text?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
            val url = t.toHttpUrlOrNull() ?: return null
            if (url.scheme != "http" && url.scheme != "https") return null
            val origin = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
            return SportsEndpoints("$origin/v1/", "$origin/logos/nhl/svg/")
        }

        /** The endpoints for this build: [DEFAULT], or the debug override file in a debug build. Call off the main thread. */
        fun resolve(context: Context): SportsEndpoints {
            if (!BuildConfig.DEBUG) return DEFAULT
            val text = try {
                File(context.filesDir, DEBUG_OVERRIDE_FILE).takeIf { it.isFile }?.readText()
            } catch (e: IOException) {
                null
            }
            return fromOverride(text)?.also { BoxLog.w(TAG, "debug build: sports endpoints overridden") } ?: DEFAULT
        }

        private const val TAG = "SportsEndpoints"
    }
}

/**
 * The NHL [ScoreSource] over `api-web.nhle.com` (docs/design/m6-score-bug.md §2.1, §2.2; WP-2).
 *
 * - Plain GETs from the `@ApiClient` (BoxDns, the default UA, redirects, gzip) with a [SPORTS_CALL_TIMEOUT_MS] call
 *   timeout. **No ETag, no `If-None-Match`, no cache** (A-S1).
 * - `Date` → [Fetch.Ok.serverDateMs], `Age` clamped to [0, AGE_MAX_S] (A-S12); `receivedMono` is stamped here from
 *   [mono] when the response headers arrive.
 * - Bodies are capped at [MAX_BODY_BYTES] on `body.source()` before decoding (A-N5); larger, non-JSON or unmappable
 *   bodies are `Failed(BAD_BODY)`. A 404 with a JSON or empty body is [Fetch.NotFound]; an HTML 404 is
 *   `Failed(HTTP_OTHER)` (A-S12).
 * - Logging: the path only, never a body, and only when a path kind's state changes (ok ↔ failing; A-N1, A-N12).
 * - Nothing about the user, playlist or device is sent: a request carries the IP, the UA and a date or a game id.
 *
 * The mapper functions are parameters so the transport is testable on its own; production uses [NhlMapper].
 */
class NhlScoreSource internal constructor(
    client: OkHttpClient,
    private val mono: MonoClock,
    endpoints: () -> SportsEndpoints,
    private val mapDay: (JsonObject, String) -> DayBoard? = NhlMapper::day,
    private val mapLanding: (JsonObject, String) -> GameSnapshot? = NhlMapper::landing,
    private val deriveLogo: (String, String) -> String = NhlMapper::darkLogoUrl,
    callTimeoutMs: Long = SportsConstants.SPORTS_CALL_TIMEOUT_MS,
) : ScoreSource {
    private val client: OkHttpClient = WireMeter.install(client.newBuilder(), client)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .cache(null)
        .build()

    /** Resolved on first use (the actor's first request, off the main thread). */
    private val endpoints: SportsEndpoints by lazy(endpoints)

    override val league: League = League.NHL

    /** The last logged state per path kind ("score", "gamecenter"), for state-change logging. */
    private val lastState = HashMap<String, String>()

    override suspend fun today(): Fetch<DayBoard> = get("score/now", "score", mapDay)

    override suspend fun day(dayKey: String): Fetch<DayBoard> {
        if (!DAY_KEY.matches(dayKey)) return Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs())
        return get("score/$dayKey", "score", mapDay)
    }

    override suspend fun game(id: GameId): Fetch<GameSnapshot> {
        if (id.league != League.NHL || !GAME_ID.matches(id.id)) return Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs())
        return get("gamecenter/${id.id}/landing", "gamecenter", mapLanding)
    }

    /** (Update interval §3.2) The follow always reads the landing. */
    override fun followEndpoint(id: GameId): String = app.thebox.core.sports.PollMeter.NHL_LANDING

    /** The landing's `darkLogo` only when it is under the logo base (A-N2); else the URL derived from the abbreviation. */
    override fun logoUrl(team: TeamLine): String {
        val base = endpoints.logoBase
        return team.logo?.takeIf { it.startsWith(base) } ?: deriveLogo(team.abbrev, base)
    }

    private suspend fun <T> get(path: String, kind: String, map: (JsonObject, String) -> T?): Fetch<T> {
        val e = endpoints
        val wire = WireBytes()
        val request = WireMeter.tag(Request.Builder().url(e.apiBase + path).header("Accept", "application/json"), wire).build()
        val answer = call(request) { res -> classify(res, map, e.logoBase) }
        logTransition(kind, path, answer)
        // (Update interval §3.2) The wire bytes: the listener wrote them when `response.use` closed the body.
        return WireMeter.withWire(answer.fetch, wire)
    }

    /** A [Fetch] plus what the log line may say about it (content type and length, never the body). */
    private class Answer<T>(val fetch: Fetch<T>, val contentType: String?, val length: Long?)

    private fun <T> classify(res: Response, map: (JsonObject, String) -> T?, logoBase: String): Answer<T> {
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
                    map(json, logoBase)
                } catch (e: RuntimeException) {
                    null
                } ?: return failed(FailKind.BAD_BODY, text.length.toLong())
                Answer(Fetch.Ok(value, received, serverDateMs(res), ageS(res), WireMeter.maxAgeS(res)), type, text.length.toLong())
            }
            404 -> if (isHtml(res, type)) failed(FailKind.HTTP_OTHER) else Answer(Fetch.NotFound, type, null)
            429 -> failed(FailKind.HTTP_429)
            in 500..599 -> failed(FailKind.HTTP_5XX)
            else -> failed(FailKind.HTTP_OTHER).also { if (code in 300..399) BoxLog.w(TAG, "unexpected redirect $code") }
        }
    }

    /** The body as text, or null when it is over [SportsConstants.MAX_BODY_BYTES] (checked before decoding, A-N5). */
    private fun readCapped(res: Response): String? {
        val source = res.body.source()
        if (source.request(SportsConstants.MAX_BODY_BYTES + 1)) return null
        return source.buffer.readUtf8()
    }

    /** An HTML 404 (a captive portal, a CDN error page) is not the API saying "no such game" (A-S12). */
    private fun isHtml(res: Response, type: String?): Boolean {
        if (type != null && type.contains("html", ignoreCase = true)) return true
        val source = res.body.source()
        source.request(HTML_SNIFF_BYTES)
        val head = source.buffer.snapshot(minOf(source.buffer.size, HTML_SNIFF_BYTES).toInt()).utf8()
        return head.trimStart().startsWith("<")
    }

    /** `enqueue` under the coroutine: cancelling it cancels the call. [read] runs on OkHttp's thread with the response open. */
    private suspend fun <T> call(request: Request, read: (Response) -> Answer<T>): Answer<T> =
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

    private fun logTransition(kind: String, path: String, answer: Answer<*>) {
        val state = when (val f = answer.fetch) {
            is Fetch.Ok -> "ok"
            Fetch.NotFound -> "not found"
            is Fetch.Failed -> f.kind.name
        }
        val previous = synchronized(lastState) { lastState.put(kind, state) }
        if (previous == state) return
        val detail = "${answer.contentType ?: "-"}, ${answer.length?.let { "$it B" } ?: "-"}"
        if (state == "ok") BoxLog.i(TAG) { "$path → ok ($detail)" } else BoxLog.w(TAG, "$path → $state ($detail)")
    }

    companion object {
        private const val TAG = "NhlScores"
        private const val HTML_SNIFF_BYTES = 512L
        private val DAY_KEY = Regex("""\d{4}-\d{2}-\d{2}""")
        private val GAME_ID = Regex("""\d{1,12}""")

        internal fun ioKind(e: IOException): FailKind = when {
            e is SocketTimeoutException -> FailKind.TIMEOUT
            e is InterruptedIOException && e.message?.contains("timeout", ignoreCase = true) == true -> FailKind.TIMEOUT
            else -> FailKind.IO
        }

        internal fun serverDateMs(res: Response): Long? = res.headers.getDate("Date")?.time

        /** `Age`, clamped to [0, AGE_MAX_S]; absent or unreadable = 0 (A-S12). */
        internal fun ageS(res: Response): Int =
            res.header("Age")?.trim()?.toLongOrNull()?.coerceIn(0L, SportsConstants.AGE_MAX_S.toLong())?.toInt() ?: 0

        /** `Retry-After` in seconds (delta form only), clamped to [0, BACKOFF_MAX_MS]. */
        internal fun retryAfterS(res: Response): Int? =
            res.header("Retry-After")?.trim()?.toLongOrNull()
                ?.coerceIn(0L, SportsConstants.BACKOFF_MAX_MS / 1000)?.toInt()
    }
}
