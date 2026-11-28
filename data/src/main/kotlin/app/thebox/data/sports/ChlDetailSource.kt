package app.thebox.data.sports

import android.content.Context
import app.thebox.core.common.BoxLog
import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.BoxScore
import app.thebox.core.sports.hub.ChlDetailMapper
import app.thebox.core.sports.hub.ChlRanks
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.GameDetailSource
import app.thebox.data.di.ApiClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * (CHL hub, docs/design/m6-chl-hub.md §4.1, §4.4) The OHL, WHL and QMJHL game detail from HockeyTech `lscluster`, for
 * the pre-game and the final only. The repository asks only while `SportsConstants.CHL_HUB_ENABLED` is on. The
 * constructor, the mapper parameters and the entry points are frozen (commit 0); P2 filled the bodies.
 *
 * - **Pre-game** ([detailAnswer] with an effective phase SCHEDULED / PRE): `feed=gc&tab=preview` first (its failure
 *   fails the answer); then, at most [MAX_IN_FLIGHT] in flight (one semaphore for the whole source), `topgoalies` × 2
 *   (`pv.*.team_id`, `pv.current_season.id`) and, unless `pv.current_season.playoff == "1"`, the two
 *   `statviewfeed&view=teams` tables when the (league, season)'s cached [ChlRanks] is older than `HUB_CHL_TABLES_TTL_MS`.
 *   A failed goalie or table read → [DetailAnswer.partial] true, [DetailAnswer.landingFailed] false; a failed table read
 *   reuses the last good [ChlRanks] of that (league, season), however old. Only mapped [ChlRanks] are cached, never a
 *   body. A fresh preview + 2 goalies + 2 tables = 5 requests; with the ranks cached, 3.
 * - **Final** ([detailAnswer] with FINAL): `feed=gc&tab=gamesummary` only; [DetailAnswer.box] is the [BoxScore] of the
 *   same parse. [boxScore] is never a request (the repository's loop answers it from that parse).
 * - **Anything else** (LIVE, INTERMISSION, POSTPONED, no hint): `Failed(HTTP_OTHER)` without a request.
 * - **Transport:** [HockeyTechHttp.lscluster] (`gc` takes `lang_code=en`, `modulekit` / `statviewfeed` take `lang=en`;
 *   the requests are the fixtures' README's), [HockeyTechHttp.json] under `MAX_BODY_BYTES`; `statviewfeed` must decode
 *   to a `JsonArray`, every other view to a `JsonObject` (else BAD_BODY / a failed secondary read); the mapper's null or
 *   throw → BAD_BODY. The detail's `serverDateMs` is the primary response's `Date`.
 * - **Headshots:** `{ endpoints.headshot(league, it) }` is the builder passed to the mapper.
 * - **Logging:** `"{league} chl-detail → state"` on a change only, with an exception class name at most; never a URL,
 *   header, body, key or game id. Detail requests are not `PollMeter` samples.
 * - Nothing is persisted; the decoded bodies are dropped when [detailAnswer] returns.
 */
@Singleton
class ChlDetailSource internal constructor(
    client: OkHttpClient,
    private val mono: MonoClock,
    endpoints: () -> HockeyTechEndpoints,
    /** `ChlDetailMapper.preview`: `(id, previewBody, awayTopGoalies, homeTopGoalies, ranks, playoff, headshot)`. */
    private val mapPreview: (GameId, JsonObject, JsonObject?, JsonObject?, ChlRanks?, Boolean, (String) -> String?) -> GameDetail? =
        ChlDetailMapper::preview,
    /** `ChlDetailMapper.summary`: `(id, gamesummaryBody, headshot)` → the detail and the box score of one parse. */
    private val mapSummary: (GameId, JsonObject, (String) -> String?) -> Pair<GameDetail, BoxScore>? = ChlDetailMapper::summary,
    /** `ChlDetailMapper.ranks`: `(standardTable, specialTable)`, JSONP already stripped. */
    private val mapRanks: (JsonArray?, JsonArray?) -> ChlRanks? = ChlDetailMapper::ranks,
    callTimeoutMs: Long = SportsConstants.SPORTS_CALL_TIMEOUT_MS,
) : GameDetailSource {
    @Inject constructor(@ApiClient client: OkHttpClient, @ApplicationContext context: Context) : this(
        client, ScoreRepositoryImpl.ELAPSED, endpoints = { HockeyTechEndpoints.resolve(context) },
    )

    /** The transport: the score source's client rules (`WireMeter`, the call timeout, no cache). */
    private val http = HockeyTechHttp(
        WireMeter.install(client.newBuilder(), client).callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS).cache(null).build(),
        mono,
    )

    /** Resolved on first use (off the main thread: the repository's first request). */
    private val endpoints: HockeyTechEndpoints by lazy(endpoints)

    /** (§4.1) At most [MAX_IN_FLIGHT] `lscluster` reads of this source at once, the preview included. */
    private val permits = Semaphore(MAX_IN_FLIGHT)

    /** (§4.1, §4.4 a) The last good **mapped** ranks of one (league, season), with the mono instant they were read. */
    private class CachedRanks(val ranks: ChlRanks, val atMono: Long)

    private val ranksLock = Any()

    /** `"league:season"` → [CachedRanks]; never a table body. Bounded; dropped by [trimMemory]. */
    private val ranksCache = object : LinkedHashMap<String, CachedRanks>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedRanks>?): Boolean = size > RANKS_CACHE_MAX
    }

    /** The last logged state per league (one line per change). */
    private val lastState = HashMap<League, String>()

    override suspend fun detail(id: GameId): Fetch<GameDetail> = detailAnswer(id, null).fetch

    /**
     * The shown CHL game's detail. [phaseHint]: the repository's **effective** phase (`DetailPollPolicy.effectivePhase`
     * of the board's and the cached detail's; C0 ruling R6): SCHEDULED / PRE → the pre-game reads; FINAL →
     * `gamesummary`; anything else (the repository never asks: BoardOnly) → `Failed(HTTP_OTHER)` without a request.
     */
    internal suspend fun detailAnswer(id: GameId, phaseHint: GamePhase?): DetailAnswer {
        if (!valid(id)) return DetailAnswer(Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs()), false)
        return when (phaseHint) {
            GamePhase.SCHEDULED, GamePhase.PRE -> pre(id)
            GamePhase.FINAL -> final(id)
            GamePhase.LIVE, GamePhase.INTERMISSION, GamePhase.POSTPONED, null ->
                DetailAnswer(Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs()), false)
        }
    }

    // ------------------------------------------------------------------ pre-game (§4.1)

    private suspend fun pre(id: GameId): DetailAnswer {
        val league = id.league
        val pvOk = when (val a = read(gcUrl(league, "preview", id.id))) {
            is HockeyTechHttp.JsonAnswer.Failed -> return failed(league, a.fetch, a.cause)
            is HockeyTechHttp.JsonAnswer.Ok -> a
        }
        val body = pvOk.element as? JsonObject ?: return badBody(league, pvOk.receivedMono)
        val pv = (body["GC"] as? JsonObject)?.get("Preview") as? JsonObject ?: return badBody(league, pvOk.receivedMono)
        val awayTeam = digits((pv["visitor_team"] as? JsonObject)?.get("team_id"))
        val homeTeam = digits((pv["home_team"] as? JsonObject)?.get("team_id"))
        val season = pv["current_season"] as? JsonObject
        val seasonId = digits(season?.get("id"))
        val playoff = text(season?.get("playoff")) == "1"
        val ranksKey = seasonId?.takeUnless { playoff }?.let { "$league:$it" }
        val cached = ranksKey?.let { k -> synchronized(ranksLock) { ranksCache[k] } }
        val tablesDue = ranksKey != null && (cached == null || mono.nowMs() - cached.atMono >= SportsConstants.HUB_CHL_TABLES_TTL_MS)

        // Step 2: the goalies and (when due) the two tables, under the shared permits (≤ MAX_IN_FLIGHT at once).
        val reads = coroutineScope {
            val away = if (awayTeam != null && seasonId != null) async { read(goaliesUrl(league, seasonId, awayTeam)) } else null
            val home = if (homeTeam != null && seasonId != null) async { read(goaliesUrl(league, seasonId, homeTeam)) } else null
            val standard = if (tablesDue && seasonId != null) async { read(tableUrl(league, seasonId, special = false)) } else null
            val special = if (tablesDue && seasonId != null) async { read(tableUrl(league, seasonId, special = true)) } else null
            Reads(away?.await(), home?.await(), standard?.await(), special?.await())
        }
        val awayGoalies = reads.away?.let(::objectOf)
        val homeGoalies = reads.home?.let(::objectOf)
        var partial = awayGoalies == null || homeGoalies == null
        val ranks: ChlRanks? = when {
            ranksKey == null -> null // the playoffs (no ranks, §2.5) or no season id
            !tablesDue -> cached?.ranks
            else -> {
                val std = reads.standard?.let(::arrayOf)
                val spc = reads.special?.let(::arrayOf)
                val fresh = if (std != null && spc != null) mapSafely { mapRanks(std, spc) } else null
                if (fresh != null) {
                    synchronized(ranksLock) { ranksCache[ranksKey] = CachedRanks(fresh, mono.nowMs()) }
                    fresh
                } else {
                    // A failed (or unmappable) table: the last good ranks of this (league, season), however old; else what
                    // the table that loaded gives, uncached (the partial re-read asks again).
                    partial = true
                    cached?.ranks ?: if (std != null || spc != null) mapSafely { mapRanks(std, spc) } else null
                }
            }
        }
        val wire = pvOk.wire + listOfNotNull(reads.away, reads.home, reads.standard, reads.special).sumOf { it.wire }
        val detail = mapSafely { mapPreview(id, body, awayGoalies, homeGoalies, ranks, playoff, headshot(league)) }
            ?: return badBody(league, pvOk.receivedMono)
        val ok = Fetch.Ok(detail.copy(serverDateMs = pvOk.serverDateMs), pvOk.receivedMono, pvOk.serverDateMs, 0, wireBytes = wire)
        logState(league, if (partial) "partial" else "ok")
        // (CHL hub fix pass, C4) A secondary read the host refused (429 first, for its Retry-After): the repository backs off.
        val refused = listOfNotNull(reads.away, reads.home, reads.standard, reads.special)
            .mapNotNull { (it as? HockeyTechHttp.JsonAnswer.Failed)?.fetch }
            .filter { it.kind == FailKind.HTTP_429 || it.kind == FailKind.REFUSED }
            .sortedBy { if (it.kind == FailKind.HTTP_429) 0 else 1 }
            .firstOrNull()
        return DetailAnswer(ok, landingFailed = false, partial = partial, secondaryRefused = refused?.takeIf { partial })
    }

    private class Reads(
        val away: HockeyTechHttp.JsonAnswer?,
        val home: HockeyTechHttp.JsonAnswer?,
        val standard: HockeyTechHttp.JsonAnswer?,
        val special: HockeyTechHttp.JsonAnswer?,
    )

    private fun objectOf(a: HockeyTechHttp.JsonAnswer): JsonObject? = (a as? HockeyTechHttp.JsonAnswer.Ok)?.element as? JsonObject

    private fun arrayOf(a: HockeyTechHttp.JsonAnswer): JsonArray? = (a as? HockeyTechHttp.JsonAnswer.Ok)?.element as? JsonArray

    // ------------------------------------------------------------------ final (§4.1)

    private suspend fun final(id: GameId): DetailAnswer {
        val league = id.league
        val ok = when (val a = read(gcUrl(league, "gamesummary", id.id))) {
            is HockeyTechHttp.JsonAnswer.Failed -> return failed(league, a.fetch, a.cause)
            is HockeyTechHttp.JsonAnswer.Ok -> a
        }
        val body = ok.element as? JsonObject ?: return badBody(league, ok.receivedMono)
        val mapped = mapSafely { mapSummary(id, body, headshot(league)) } ?: return badBody(league, ok.receivedMono)
        logState(league, "ok")
        return DetailAnswer(
            Fetch.Ok(mapped.first.copy(serverDateMs = ok.serverDateMs), ok.receivedMono, ok.serverDateMs, 0, wireBytes = ok.wire),
            landingFailed = false,
            box = Fetch.Ok(mapped.second, ok.receivedMono, ok.serverDateMs, 0),
        )
    }

    // ------------------------------------------------------------------ transport

    private suspend fun read(url: HttpUrl): HockeyTechHttp.JsonAnswer = permits.withPermit { http.json(url) }

    /** `feed=gc&tab={tab}&game_id={id}&fmt=json&lang_code=en` (the fixtures' requests; `gc` takes `lang_code`). */
    private fun gcUrl(league: League, tab: String, gameId: String): HttpUrl = HockeyTechHttp.lscluster(endpoints, league) {
        addQueryParameter("feed", "gc").addQueryParameter("tab", tab).addQueryParameter("game_id", gameId)
            .addQueryParameter("fmt", "json").addQueryParameter("lang_code", "en")
    }

    /** `feed=modulekit&view=statviewtype&type=topgoalies&season_id&team_id&first=0&limit=10&fmt=json&lang=en`. */
    private fun goaliesUrl(league: League, seasonId: String, teamId: String): HttpUrl = HockeyTechHttp.lscluster(endpoints, league) {
        addQueryParameter("feed", "modulekit").addQueryParameter("view", "statviewtype").addQueryParameter("type", "topgoalies")
            .addQueryParameter("season_id", seasonId).addQueryParameter("team_id", teamId)
            .addQueryParameter("first", "0").addQueryParameter("limit", "10")
            .addQueryParameter("fmt", "json").addQueryParameter("lang", "en")
    }

    /**
     * `feed=statviewfeed&view=teams&groupTeamsBy=league&context=overall&season&special&sort&lang=en`, as captured (the
     * fixtures' README): no `fmt`; the answer is the JSONP array `([ … ])`, which [HockeyTechHttp.element] strips.
     */
    private fun tableUrl(league: League, seasonId: String, special: Boolean): HttpUrl = HockeyTechHttp.lscluster(endpoints, league) {
        addQueryParameter("feed", "statviewfeed").addQueryParameter("view", "teams").addQueryParameter("groupTeamsBy", "league")
            .addQueryParameter("context", "overall").addQueryParameter("season", seasonId)
            .addQueryParameter("special", if (special) "true" else "false")
            .addQueryParameter("sort", if (special) "power_play_pct" else "points")
            .addQueryParameter("lang", "en")
    }

    private fun headshot(league: League): (String) -> String? = { endpoints.headshot(league, it) }

    /** A mapper call off the caller's thread; null or a throw is a bad body (never a crash on the loop). */
    private suspend fun <T : Any> mapSafely(block: () -> T?): T? = try {
        withContext(Dispatchers.Default) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        null
    }

    private fun badBody(league: League, atMono: Long): DetailAnswer = failed(league, Fetch.Failed(FailKind.BAD_BODY, null, atMono), null)

    private fun failed(league: League, f: Fetch.Failed, cause: String?): DetailAnswer {
        logState(league, f.kind.name, cause)
        return DetailAnswer(f, false)
    }

    /** One line per state change: `{league} chl-detail → {state}` (+ an exception class name); never a URL, key, id or body. */
    private fun logState(league: League, state: String, cause: String? = null) {
        val previous = synchronized(lastState) { lastState.put(league, state) }
        if (previous == state) return
        val line = "$league $LOG_KIND → $state" + (cause?.let { " ($it)" } ?: "")
        if (state == "ok") BoxLog.i(TAG) { line } else BoxLog.w(TAG, line)
    }

    /** Never a request of its own (§4.1): the repository answers the CHL box score from the final's parse. */
    override suspend fun boxScore(id: GameId): Fetch<BoxScore> = Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs())

    /** Drops the cached ranks ([GameDetailRepositoryImpl]'s trim); nothing else is kept. */
    fun trimMemory() {
        synchronized(ranksLock) { ranksCache.clear() }
    }

    /** For tests: how many (league, season) ranks are cached. */
    internal val cachedRanksCount: Int get() = synchronized(ranksLock) { ranksCache.size }

    private fun valid(id: GameId): Boolean =
        (id.league == League.OHL || id.league == League.WHL || id.league == League.QMJHL) && ID.matches(id.id)

    companion object {
        init {
            HockeyTechKeys.touch() // (L6) the keys are registered with Redact before any request
        }

        internal const val TAG = "ChlDetail"
        private const val LOG_KIND = "chl-detail"

        /** (§4.1) At most this many `lscluster` reads in flight. */
        internal const val MAX_IN_FLIGHT = 3

        private const val RANKS_CACHE_MAX = 6
        private val ID = Regex("""\d{1,12}""")

        /** A digits-only id (1–12 digits) from a JSON int or string, else null (it goes into a URL). */
        private fun digits(e: JsonElement?): String? = text(e)?.takeIf { ID.matches(it) }

        private fun text(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim()
    }
}
