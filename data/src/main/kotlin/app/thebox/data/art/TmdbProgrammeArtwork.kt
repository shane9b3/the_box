package app.thebox.data.art

import android.content.Context
import app.thebox.core.art.ArtworkHint
import app.thebox.core.art.ArtworkStatus
import app.thebox.core.art.ProgrammeArtwork
import app.thebox.core.common.BoxLog
import app.thebox.core.common.Redact
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.state.GuideDomain
import app.thebox.data.di.ApiClient
import app.thebox.data.di.ApplicationScope
import app.thebox.data.di.IoDispatcher
import app.thebox.data.di.WallClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [ProgrammeArtwork] over the TMDB v3 API (docs/decisions/2026-10-18-tmdb-programme-posters.md).
 *
 * - The key: the app's own only ([BuiltInKeys.tmdb], compiled in from `local.properties`; owner ruling 2026-10-19).
 *   A v3 key goes as `api_key=`, a v4 read token (`eyJ…`) as `Authorization: Bearer`. A build without one is OFF and
 *   makes no request. The key state is decided once per process and again whenever the `GuidePrefs.posters` switch
 *   changes (a restore can flip it underneath a running process), so [status] is right before the guide asks for
 *   anything. The key is registered with [Redact].
 * - One lookup at a time (the panel asks after its 150 ms debounce; the guide cancels the previous one): the cache
 *   first, then at most two requests (`search/tv`, `search/movie` or `search/multi` by the EPG's hint, in the device's
 *   language so a localised poster is preferred; the title's head when the full title matched nothing). The first
 *   result with a poster wins unless a later one matches the title exactly *and* is not a fringe entry
 *   ([EXACT_MIN_POPULARITY_SHARE]: "Ocean's 11" must find the 2001 film, not the Takarazuka stage recording of
 *   that exact name). Every call has an [CALL_TIMEOUT_MS] call timeout and is cancelled with its coroutine.
 * - 401 stops every lookup ([ArtworkStatus.APP_KEY_REJECTED]: only an app update fixes it); 429 and connection, server
 *   or body failures back off ([RATE_LIMIT_BACKOFF_MS], [FAILURE_BACKOFF_MS]) and are not cached. Nothing is answered,
 *   not even from the cache, while the lookup is off; switching posters off empties the cache (a list of focused titles).
 * - What leaves the device: the cleaned programme title, the device language, the app's generic User-Agent and, of
 *   course, the address. No channel, playlist or device identity, and no TMDB session or account.
 */
@Singleton
class TmdbProgrammeArtwork internal constructor(
    client: OkHttpClient,
    private val store: SettingsStore,
    cacheDir: File,
    private val io: CoroutineDispatcher,
    private val clock: WallClock,
    scope: CoroutineScope,
    builtInKey: String? = null,
    private val apiBase: String = API_BASE,
    private val imageBase: String = IMAGE_BASE,
    /** `language=` on every search: the poster in the viewer's language when TMDB has one. */
    private val language: String = Locale.getDefault().toLanguageTag(),
) : ProgrammeArtwork {
    @Inject constructor(
        @ApiClient client: OkHttpClient,
        store: SettingsStore,
        @ApplicationContext context: Context,
        @IoDispatcher io: CoroutineDispatcher,
        clock: WallClock,
        @ApplicationScope scope: CoroutineScope,
        keys: BuiltInKeys,
    ) : this(client, store, File(context.filesDir, DIR), io, clock, scope, keys.tmdb)

    private val client: OkHttpClient = client.newBuilder().callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    private val _status = MutableStateFlow(ArtworkStatus.OFF)
    override val status: StateFlow<ArtworkStatus> = _status.asStateFlow()

    private val builtIn: String? = builtInKey?.trim()?.takeIf { it.isNotEmpty() }?.also { Redact.registerSecret(it) }

    private val lock = Mutex()
    internal val cache = ArtworkCache(File(cacheDir, CACHE_FILE), clock)
    private var key: String? = null
    private var keyLoaded = false
    private var backoffUntilMs = 0L

    /** Requests made (test hook). */
    @Volatile var requests: Int = 0
        private set

    init {
        // The pre-2026-10-18 cache file (title-only keys, the old picker): its answers are not trusted any more.
        File(cacheDir, LEGACY_CACHE_FILE).delete()
        // Eager, and again on every switch change: the settings row reads [status] before any lookup happens.
        scope.launch(io) {
            store.awaitReady()
            store.flow(GuideDomain).map { it.posters }.distinctUntilChanged().collect { on ->
                lock.withLock {
                    keyLoaded = false
                    key = null
                    ensureKey()
                    if (!on) cache.clear()
                }
            }
        }
    }

    override suspend fun poster(title: String, hint: ArtworkHint): String? = withContext(io) {
        try {
            lock.withLock { lookup(title, hint) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BoxLog.w(TAG, "lookup failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private suspend fun lookup(title: String, hint: ArtworkHint): String? {
        val query = TitleNormaliser.clean(title) ?: return null
        val k = ensureKey() ?: return null
        if (_status.value != ArtworkStatus.ON) return null
        val cacheKey = cacheKey(hint, query)
        cache.get(cacheKey)?.let { return it.ifEmpty { null }?.let { p -> imageBase + p } }
        if (clock.now() < backoffUntilMs) return null
        val endpoint = when (hint) {
            ArtworkHint.SERIES -> "search/tv"
            ArtworkHint.MOVIE -> "search/movie"
            ArtworkHint.ANY -> "search/multi"
        }
        var reply = search(k, endpoint, query)
        if (reply is Reply.Hits && reply.path == null) {
            TitleNormaliser.head(query)?.let { head -> reply = search(k, endpoint, head) }
        }
        val hits = reply as? Reply.Hits ?: return null
        cache.put(cacheKey, hits.path ?: "")
        return hits.path?.let { imageBase + it }
    }

    private sealed interface Reply {
        /** A completed search: [path] null = nothing with a poster matched. */
        data class Hits(val path: String?) : Reply
        /** Not an answer (401, 429, 5xx, a non-JSON body, no connection): nothing is cached. */
        data object Failed : Reply
    }

    private suspend fun search(key: String, endpoint: String, query: String): Reply {
        val url = (apiBase + endpoint).toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("include_adult", "false")
            .addQueryParameter("language", language)
            .addQueryParameter("page", "1")
        val req = Request.Builder().header("Accept", "application/json")
        auth(key, url, req)
        requests++
        try {
            call(req.url(url.build()).build()).use { res ->
                when (res.code) {
                    200 -> return Reply.Hits(pick(res.body.string(), query))
                    401 -> {
                        BoxLog.w(TAG, "app's key rejected (401): posters off until an app update")
                        _status.value = ArtworkStatus.APP_KEY_REJECTED
                    }
                    429 -> backoffUntilMs = clock.now() + RATE_LIMIT_BACKOFF_MS
                    else -> {
                        BoxLog.w(TAG, "search answered ${res.code}")
                        backoffUntilMs = clock.now() + FAILURE_BACKOFF_MS
                    }
                }
            }
        } catch (e: IOException) {
            BoxLog.w(TAG, "search failed: ${e.javaClass.simpleName}")
            backoffUntilMs = clock.now() + FAILURE_BACKOFF_MS
        } catch (e: IllegalArgumentException) {
            // A 200 that is not JSON (a captive portal, a proxy page): back off like any other failure.
            BoxLog.w(TAG, "search body unreadable: ${e.javaClass.simpleName}")
            backoffUntilMs = clock.now() + FAILURE_BACKOFF_MS
        }
        return Reply.Failed
    }

    /** The poster path of the best result: an exact title match with a poster, else the first with one. */
    /**
     * The first result with a poster (TMDB orders by popularity), unless a later one matches [query] exactly and has at
     * least [EXACT_MIN_POPULARITY_SHARE] of the first's popularity. People and posterless entries are skipped.
     */
    private fun pick(body: String, query: String): String? {
        val results = Json.parseToJsonElement(body).jsonObject["results"]?.jsonArray ?: return null
        var first: String? = null
        var firstPopularity = 0.0
        for (r in results) {
            val o = r.jsonObject
            val type = o["media_type"]?.jsonPrimitive?.content
            if (type != null && type != "movie" && type != "tv") continue
            val path = o["poster_path"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content ?: continue
            if (path.isEmpty()) continue
            val popularity = o["popularity"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            if (first == null) {
                first = path
                firstPopularity = popularity
            }
            val exact = NAME_FIELDS.any { field ->
                o[field]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.equals(query, ignoreCase = true) == true
            }
            if (exact && popularity >= firstPopularity * EXACT_MIN_POPULARITY_SHARE) return path
        }
        return first
    }

    private fun auth(key: String, url: okhttp3.HttpUrl.Builder, req: Request.Builder) {
        if (isV4Token(key)) req.header("Authorization", "Bearer $key") else url.addQueryParameter("api_key", key)
    }

    /** `enqueue` under the coroutine: cancelling the lookup cancels the call. */
    private suspend fun call(request: Request): Response = suspendCancellableCoroutine { cont ->
        val c = client.newCall(request)
        cont.invokeOnCancellation { c.cancel() }
        c.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    /** Decides the key once: posters off → OFF; the app's key present → ON; else (a build without one) OFF. */
    private suspend fun ensureKey(): String? {
        if (keyLoaded) return key
        store.awaitReady()
        key = if (store.flow(GuideDomain).value.posters) builtIn else null
        keyLoaded = true
        _status.value = if (key != null) ArtworkStatus.ON else ArtworkStatus.OFF
        return key
    }

    companion object {
        private const val TAG = "TmdbArtwork"
        const val API_BASE = "https://api.themoviedb.org/3/"
        /** w342 covers the panel's 200 × 300 design px card at 1080p with margin and stays small on the disk cache. */
        const val IMAGE_BASE = "https://image.tmdb.org/t/p/w342"
        const val DIR = "artwork"
        const val CACHE_FILE = "tmdb-cache-2.json"
        internal const val LEGACY_CACHE_FILE = "tmdb-cache.json"
        /** An exact title match this much less popular than the first result is a namesake, not the programme. */
        const val EXACT_MIN_POPULARITY_SHARE = 0.1
        const val RATE_LIMIT_BACKOFF_MS = 10_000L
        const val FAILURE_BACKOFF_MS = 30_000L
        /** Whole-call limit (connect + read): a dead network must not hold the guide's lookup. */
        const val CALL_TIMEOUT_MS = 8_000L
        private val NAME_FIELDS = listOf("title", "name", "original_title", "original_name")

        /** A v4 read access token is a JWT; a v3 key is 32 hex characters. */
        fun isV4Token(key: String): Boolean = key.startsWith("eyJ")

        /** The cache is keyed by hint too: a `search/tv` miss must not hide a later `search/movie` hit. */
        internal fun cacheKey(hint: ArtworkHint, cleanTitle: String): String = hint.name.lowercase() + ":" + TitleNormaliser.key(cleanTitle)
    }
}
