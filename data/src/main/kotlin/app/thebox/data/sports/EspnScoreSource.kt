package app.thebox.data.sports

import android.content.Context
import app.thebox.core.common.BoxLog
import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.EspnBoardDto
import app.thebox.core.sports.EspnEventDto
import app.thebox.core.sports.EspnGameDto
import app.thebox.core.sports.EspnMapper
import app.thebox.core.sports.EspnStatsDto
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.FollowExtras
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.NflYards
import app.thebox.core.sports.NflYardsPlanner
import app.thebox.core.sports.PollMeter
import app.thebox.core.sports.ScoreSource
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.SportsDebugOverride
import app.thebox.core.sports.TeamLine
import app.thebox.core.sports.YardsResult
import app.thebox.core.sports.hub.EspnVideoMapper
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * ESPN's base URLs (docs/design/m6-nfl.md §1.5). A **debug** build's `files/debug/sports_base` origin (the same file as
 * [SportsEndpoints]) serves both leagues: `{origin}/apis/site/v2/sports/football/nfl/` and the logo base `{origin}/`
 * (the fake panel serves `/combiner/i`).
 */
data class EspnEndpoints(
    val apiBase: String,
    val logoBase: String,
    /** (NFL YDS §1.7) The per-team statistics base (`events/{id}/competitions/{id}/competitors/{team}/statistics`). */
    val statsBase: String = STATS_BASE,
    /**
     * (NFL hub commit 0, docs/design/m6-nfl-hub.md §4.2) The clip record base (`{clipBase}{id}`); the debug override maps it
     * to `{origin}/espn-core/v1/video/clips/`. The game summary is `apiBase + "summary?event={id}"`.
     */
    val clipBase: String = CLIP_BASE,
    /**
     * (NFL hub code review 2026-11-26, data #4) True only for endpoints built by [fromOverride] (the debug override file,
     * which only a debuggable build reads). Every other instance, [DEFAULT] or one built by hand, is false.
     */
    val debugOverride: Boolean = false,
) {
    /**
     * (Integration §18; code review 2026-11-26, data #4) Plain-http clip media and images are allowed **only** for the
     * debug override: a positive flag ([debugOverride]) and a plain-http logo base ([EspnVideoMapper.httpOkFor], the rule
     * the board mapper applies). It fails closed: any endpoints not built by [fromOverride] answer false.
     */
    val httpMediaOk: Boolean get() = debugOverride && EspnVideoMapper.httpOkFor(logoBase)

    companion object {
        const val API_BASE = "https://site.api.espn.com/apis/site/v2/sports/football/nfl/"
        const val LOGO_BASE = EspnVideoMapper.PRODUCTION_LOGO_BASE
        const val STATS_BASE = "https://sports.core.api.espn.com/v2/sports/football/leagues/nfl/"
        const val CLIP_BASE = "https://content.core.api.espn.com/v1/video/clips/"
        val DEFAULT = EspnEndpoints(API_BASE, LOGO_BASE)

        /** An override origin → the ESPN paths under it. Null for a blank or unusable text (not http/https, or not a URL). */
        fun fromOverride(text: String?): EspnEndpoints? {
            val t = text?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
            val url = t.toHttpUrlOrNull() ?: return null
            if (url.scheme != "http" && url.scheme != "https") return null
            val origin = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
            return EspnEndpoints(
                "$origin/apis/site/v2/sports/football/nfl/", "$origin/", "$origin/v2/sports/football/leagues/nfl/",
                "$origin/espn-core/v1/video/clips/", debugOverride = true,
            )
        }

        /** The endpoints for this build: [DEFAULT], or the debug override file in a debug build. Call off the main thread. */
        fun resolve(context: Context): EspnEndpoints {
            // (Integration review L4) The shared check: the installed app's FLAG_DEBUGGABLE; a release build never opens the file.
            val debuggable = SportsDebugOverride.isDebuggable(context.applicationInfo.flags)
            if (!debuggable) return DEFAULT
            val text = SportsDebugOverride.text(context.filesDir, debuggable)
            return fromOverride(text)?.also { BoxLog.w(TAG, "debug build: ESPN endpoints overridden") } ?: DEFAULT
        }

        private const val TAG = "EspnEndpoints"
    }
}

/**
 * The NFL [ScoreSource] over ESPN's site scoreboard (docs/design/m6-nfl.md §1.5, §1.4, §7.2; WP-2).
 *
 * - **Transport as [NhlScoreSource]:** plain GETs from the `@ApiClient` with a [SportsConstants.SPORTS_CALL_TIMEOUT_MS]
 *   call timeout, no cache, no ETag; `Date` → `serverDateMs`, `Age` clamped (ESPN sends none, so 0); 404 JSON/empty =
 *   [Fetch.NotFound], HTML 404 / 400 / other = `HTTP_OTHER`, 429, 5xx; logging on a state change only, and only a path
 *   label (never a query, a body or an id).
 * - **Streaming decode** into the commit-0 DTO ([EspnBoardDto], or [EspnGameDto] for `game()`, N10) through a counting
 *   stream that throws past [SportsConstants.ESPN_MAX_BODY_BYTES] (1 MiB): over the cap, non-JSON, or a spine of the
 *   wrong type is `Failed(BAD_BODY)`. The whole body is never held as text or as a tree.
 * - **Endpoints:** `today()` = `scoreboard` (ESPN's current week); `day(key)` = `scoreboard?seasontype=&week=` for a
 *   week key or `scoreboard?dates=yyyymmdd` for a date key, anything else `HTTP_OTHER` without a request; `game(id)` =
 *   the event's remembered ET date's board, filtered to the id, then **once more against the week board** when the event
 *   is absent (N7). With nothing remembered (an app restart) the follow's start hint gives the date (C1); with no hint
 *   the week board is read first. Absent from every board tried = NotFound.
 * - **(Update interval §4.2) The production follow** ([production]) reads the per-event `scoreboard/{id}` first, with
 *   that board path as its fallback (see [eventGame]); `Fetch.Ok` carries `max-age` and the wire bytes ([WireMeter]).
 * - **Remembered dates:** every decoded body's events (`id` → ET date of `date`), read from the DTO itself.
 * - **Logos:** each mapped [TeamLine.logo] is replaced by ESPN's combiner URL for the dark variant at
 *   [SportsConstants.ESPN_LOGO_PX] under the logo base (A-N2), or null for an abbreviation that is not `[a-z]{2,3}`.
 *
 * The mapper functions are parameters so the transport is testable on its own; production uses [EspnMapper].
 */
class EspnScoreSource internal constructor(
    client: OkHttpClient,
    private val mono: MonoClock,
    endpoints: () -> EspnEndpoints,
    private val mapBoard: (EspnBoardDto, Long?, String) -> DayBoard? = EspnMapper::board,
    private val mapGame: (EspnGameDto, GameId, Long?, String) -> GameSnapshot? = EspnMapper::game,
    private val parseWeekKey: (String) -> Triple<Int, Int, Int>? = EspnMapper::parseWeekKey,
    callTimeoutMs: Long = SportsConstants.SPORTS_CALL_TIMEOUT_MS,
    /** (NFL YDS §1.7) One team's total yards from a statistics body; a parameter so the S5 catch-all is testable. */
    private val statsYards: (EspnStatsDto) -> Int? = NflYards::totalYards,
    /**
     * (Update interval §4.2) The follow reads the per-event endpoint `scoreboard/{id}`, with the dated board as the
     * fallback. Off = the board path only (NFL §1.5, the transport tests). Production turns it on through [production].
     */
    private val perEvent: Boolean = false,
) : ScoreSource {
    private val client: OkHttpClient = WireMeter.install(client.newBuilder(), client)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .cache(null)
        .build()

    /**
     * (NFL YDS §1.7, S4) The statistics client: the built [client] (so the [WireMeter] listener factory is kept) with the
     * yards' own [SportsConstants.NFL_YDS_CALL_TIMEOUT_MS] call timeout.
     */
    private val statsClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(SportsConstants.NFL_YDS_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    }

    /** Resolved on first use (the actor's first request, off the main thread). */
    private val endpoints: EspnEndpoints by lazy(endpoints)

    override val league: League = League.NFL

    /** Event id → its ET date (`yyyyMMdd`), from every decoded body; bounded, least recently used out first. */
    private val dates = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > DATES_MAX
    }

    override suspend fun today(): Fetch<DayBoard> = board(url { }, "scoreboard")

    override suspend fun day(dayKey: String): Fetch<DayBoard> {
        val week = if (DATE_KEY.matches(dayKey)) null else parseWeekKey(dayKey)
        return when {
            week != null -> {
                val (_, type, number) = week
                if (type !in 1..9 || number !in 1..99) return failedNow()
                board(url { addQueryParameter("seasontype", "$type").addQueryParameter("week", "$number") }, "scoreboard?week")
            }
            DATE_KEY.matches(dayKey) ->
                board(url { addQueryParameter("dates", dayKey.replace("-", "")) }, "scoreboard?dates")
            else -> failedNow()
        }
    }

    override suspend fun game(id: GameId): Fetch<GameSnapshot> = game(id, null)

    /**
     * (NFL YDS §1.3, §1.7) The follow poll with [extras]: with `nflYards`, a good answer runs the yards step (a stoppage
     * snapshot gets both teams' total yards attached). The board's answer is returned whatever the step does (S5).
     */
    override suspend fun game(id: GameId, startUtcMs: Long?, extras: FollowExtras): Fetch<GameSnapshot> {
        val f = game(id, startUtcMs)
        if (!extras.nflYards || f !is Fetch.Ok) return f
        return withYards(id, f)
    }

    /** (Update interval §4.2) The per-event read with the board fallback when [perEvent]; else the board path. */
    override suspend fun game(id: GameId, startUtcMs: Long?): Fetch<GameSnapshot> {
        if (id.league != League.NFL || !GAME_ID.matches(id.id)) return failedNow()
        if (!perEvent) return boardGame(id, startUtcMs)
        return eventGame(id, startUtcMs)
    }

    /** One follow's per-event state (one follow at a time; a new id starts fresh, so the fallback ends with the follow). */
    private class FollowState(val id: String) {
        var shapeFails = 0
        var fallbackAt: Long? = null
        /** The meter endpoint of the last answer; null = a one-off board check while still on per-event (fix pass 2). */
        var endpoint: String? = PollMeter.NFL_EVENT
    }

    private var followState: FollowState? = null

    /** (NFL YDS §1.6) One follow's yards state: its planner and the competitor ids of its last decoded event. */
    private class YardsFollow(val id: String) {
        val planner = NflYardsPlanner()
        var teamIds: Pair<String, String>? = null
        var noIdsLogged = false
    }

    private var yardsFollow: YardsFollow? = null

    private fun yardsOf(id: GameId): YardsFollow =
        yardsFollow?.takeIf { it.id == id.id } ?: YardsFollow(id.id).also { yardsFollow = it }

    /** (§1.7) Keep [e]'s competitor ids for [id]'s follow (never logged). */
    private fun captureIds(id: GameId, e: EspnEventDto) {
        val ids = NflYards.teamIds(e) ?: return
        yardsOf(id).teamIds = ids
    }

    /**
     * (NFL YDS §1.6, §1.7) The yards step on the board's [f]: ask the planner, fetch both teams in parallel when due, tell
     * the planner (only after both calls finished), attach. Every throwable but cancellation is caught (S5): the board's
     * answer is returned in any case, its `wireBytes` untouched and the step's time in `sideMs`.
     */
    private suspend fun withYards(id: GameId, f: Fetch.Ok<GameSnapshot>): Fetch<GameSnapshot> {
        val t0 = mono.nowMs()
        return try {
            val y = yardsOf(id)
            val ids = y.teamIds
            if (ids == null) {
                if (!y.noIdsLogged && NflYards.stoppageKey(f.value) != null) {
                    y.noIdsLogged = true
                    BoxLog.w(TAG, "yards: no competitor ids for the follow")
                }
            } else {
                val key = y.planner.due(f.value, f.receivedMono)
                if (key != null) {
                    val r = fetchYards(id, ids)
                    y.planner.fetched(key, mono.nowMs(), r)
                }
            }
            f.copy(value = y.planner.attach(f.value, f.receivedMono), sideMs = (mono.nowMs() - t0).coerceAtLeast(0L))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            BoxLog.w(TAG, "yards step failed: ${t.javaClass.simpleName}")
            f.copy(sideMs = (mono.nowMs() - t0).coerceAtLeast(0L))
        }
    }

    /** Both teams' statistics in parallel; any throwable is `Failed(BAD_BODY)` (S5). */
    private suspend fun fetchYards(id: GameId, ids: Pair<String, String>): YardsResult = try {
        val (a, h) = coroutineScope {
            val away = async { stats(id, ids.first) }
            val home = async { stats(id, ids.second) }
            away.await() to home.await()
        }
        val av = (a as? Fetch.Ok)?.let { statsYards(it.value) }
        val hv = (h as? Fetch.Ok)?.let { statsYards(it.value) }
        if (av != null && hv != null) {
            YardsResult.Ok(av, hv)
        } else {
            val kinds = listOf(a to av, h to hv).mapNotNull { (fetch, v) ->
                when (fetch) {
                    is Fetch.Ok -> if (v == null) FailKind.BAD_BODY else null
                    Fetch.NotFound -> FailKind.HTTP_OTHER
                    is Fetch.Failed -> fetch.kind
                }
            }
            YardsResult.Failed(
                when {
                    FailKind.HTTP_429 in kinds -> FailKind.HTTP_429
                    FailKind.TIMEOUT in kinds -> FailKind.TIMEOUT
                    FailKind.IO in kinds -> FailKind.IO
                    else -> kinds.firstOrNull() ?: FailKind.BAD_BODY
                },
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        YardsResult.Failed(FailKind.BAD_BODY)
    }

    /** One team's statistics (§1.7): the usual capped, drained decode; a 403 is a 429. Logged by state change, no id. */
    private suspend fun stats(id: GameId, teamId: String): Fetch<EspnStatsDto> {
        val url = endpoints.statsBase.toHttpUrl().newBuilder()
            .addPathSegment("events").addPathSegment(id.id)
            .addPathSegment("competitions").addPathSegment(id.id)
            .addPathSegment("competitors").addPathSegment(teamId)
            .addPathSegment("statistics")
            .build()
        val answer = call(url, EspnStatsDto.serializer(), statsClient) { null }
        // (Integration §18) A 403 is already HTTP_429 from EspnHttp's WAF rule (every ESPN call).
        val fetch: Fetch<EspnStatsDto> = answer.fetch
        logTransition("competitors/{team}/statistics", fetch, answer)
        return fetch
    }

    private fun stateOf(id: GameId): FollowState =
        followState?.takeIf { it.id == id.id } ?: FollowState(id.id).also { followState = it }

    /**
     * (Update interval §3.2) The endpoint the last [game] answer for [id] came from: the board only once the follow has
     * switched to it; null after the one-off S4 board check (fix pass 2), which is a sample of neither window.
     */
    override fun followEndpoint(id: GameId): String? {
        if (!perEvent) return PollMeter.NFL_BOARD
        val st = followState?.takeIf { it.id == id.id } ?: return PollMeter.NFL_EVENT
        return st.endpoint
    }

    /**
     * (Update interval §4.2; review S3, S4) The per-event read:
     * - **200** that decodes to the event: `Ok`. A 200 that does not (or names another event) is a *shape* failure.
     * - **JSON 404** ("No event found", cached up to an hour): the dated board once (S4). Absent there too = `NotFound`;
     *   present = the board's snapshot, counted as a shape failure.
     * - **JSON 400**: a shape failure.
     * - **403** (a WAF block of a hot client): treated as a 429 (the 20 s back-off base and the 10 s floor), no switch;
     *   the board path's follow reads map it the same way (fix pass 3).
     * - An HTML 404, a 30x, 429, 5xx, IO and timeouts never switch; the back-off handles them.
     * - [SportsConstants.PER_EVENT_SWITCH_AFTER] shape failures in a row switch this follow to the board path at once;
     *   the per-event endpoint is tried again every [SportsConstants.PER_EVENT_RETRY_MS] (one more shape failure goes back).
     */
    private suspend fun eventGame(id: GameId, startUtcMs: Long?): Fetch<GameSnapshot> {
        val st = stateOf(id)
        val now = mono.nowMs()
        st.fallbackAt?.let { at ->
            if (now - at < SportsConstants.PER_EVENT_RETRY_MS) {
                st.endpoint = PollMeter.NFL_BOARD
                return boardGame(id, startUtcMs)
            }
            st.fallbackAt = null
            st.shapeFails = SportsConstants.PER_EVENT_SWITCH_AFTER - 1
        }
        st.endpoint = PollMeter.NFL_EVENT
        val logoBase = endpoints.logoBase
        val url = endpoints.apiBase.toHttpUrl().newBuilder().addPathSegment("scoreboard").addPathSegment(id.id).build()
        val answer = call(url, EspnEventDto.serializer()) { listOf(it) }
        val fetch: Fetch<GameSnapshot> = when (val f = answer.fetch) {
            is Fetch.Ok -> {
                val mapped = if (idOf(f.value) != id.id) null else mapSafely { mapGame(EspnGameDto(listOf(f.value)), id, f.serverDateMs, logoBase) }
                if (mapped != null) captureIds(id, f.value)
                if (mapped == null) {
                    Fetch.Failed(FailKind.BAD_BODY, null, f.receivedMono)
                } else {
                    st.shapeFails = 0
                    Fetch.Ok(withLogos(mapped, logoBase), f.receivedMono, f.serverDateMs, f.ageS, f.maxAgeS, f.wireBytes)
                }
            }
            Fetch.NotFound -> Fetch.NotFound
            // (Integration §18) A 403 is already HTTP_429 from EspnHttp's WAF rule.
            is Fetch.Failed -> f
        }
        logTransition("scoreboard/{event}", fetch, answer)
        val shape = (fetch is Fetch.Failed && fetch.kind == FailKind.BAD_BODY && answer.code == 200) ||
            (answer.code == 400 && !answer.html)
        return when {
            fetch == Fetch.NotFound -> {
                // S4: the 404 may be a cached edge glitch; only "absent from the board too" removes the follow. The board
                // read is a one-off check (endpoint null, no meter sample) unless it is the shape failure that switches.
                val board = boardGame(id, startUtcMs)
                val switched = board is Fetch.Ok && shapeFailed(st, now)
                st.endpoint = if (switched) PollMeter.NFL_BOARD else null
                board
            }
            shape -> if (shapeFailed(st, now)) {
                st.endpoint = PollMeter.NFL_BOARD
                boardGame(id, startUtcMs)
            } else {
                fetch
            }
            else -> fetch
        }
    }

    /** Counts one shape failure; true when it switched the follow to the board. */
    private fun shapeFailed(st: FollowState, now: Long): Boolean {
        st.shapeFails++
        if (st.shapeFails < SportsConstants.PER_EVENT_SWITCH_AFTER) return false
        if (st.fallbackAt == null) BoxLog.w(TAG, "per-event feed not usable; following on the dated board")
        st.fallbackAt = now
        return true
    }

    /**
     * The board path (NFL §1.5, unchanged; the per-event fallback): the remembered ET date's board, else the hinted
     * start's ET date (an app restart forgets the dates; C1), then the week board once (N7), then the hinted date if it
     * was not tried yet: never NotFound before a dated board was read when a hint exists.
     */
    private suspend fun boardGame(id: GameId, startUtcMs: Long?): Fetch<GameSnapshot> {
        val remembered = synchronized(dates) { dates[id.id] }
        val hinted = startUtcMs?.takeIf { it > 0L }?.let { etDate(it) }
        val first = remembered ?: hinted
        if (first != null) {
            when (val dated = gameFrom(url { addQueryParameter("dates", first) }, "scoreboard?dates", id)) {
                is GameAnswer.Found -> return dated.fetch
                is GameAnswer.Failed -> return dated.fetch
                GameAnswer.Absent -> Unit // re-dated (weather, N7): the week board once more
            }
        }
        when (val week = gameFrom(url { }, "scoreboard", id)) {
            is GameAnswer.Found -> return week.fetch
            is GameAnswer.Failed -> return week.fetch
            GameAnswer.Absent -> Unit
        }
        if (hinted == null || hinted == first) return Fetch.NotFound
        return when (val dated = gameFrom(url { addQueryParameter("dates", hinted) }, "scoreboard?dates", id)) {
            is GameAnswer.Found -> dated.fetch
            is GameAnswer.Failed -> dated.fetch
            GameAnswer.Absent -> Fetch.NotFound
        }
    }

    /** ESPN's combiner URL for [team]'s dark logo under the logo base (A-N2), or empty for an unusable abbreviation. */
    override fun logoUrl(team: TeamLine): String = combinerLogo(team.abbrev, endpoints.logoBase).orEmpty()

    // ---- the calls

    private fun url(query: HttpUrl.Builder.() -> Unit): HttpUrl =
        endpoints.apiBase.toHttpUrl().newBuilder().addPathSegment("scoreboard").apply(query).build()

    private suspend fun board(url: HttpUrl, label: String): Fetch<DayBoard> {
        val logoBase = endpoints.logoBase
        val answer = call(url, EspnBoardDto.serializer()) { it.events }
        val fetch: Fetch<DayBoard> = when (val f = answer.fetch) {
            is Fetch.Ok -> {
                val mapped = mapSafely { mapBoard(f.value, f.serverDateMs, logoBase) }
                if (mapped == null) {
                    Fetch.Failed(FailKind.BAD_BODY, null, f.receivedMono)
                } else {
                    Fetch.Ok(mapped.copy(games = mapped.games.map { withLogos(it, logoBase) }), f.receivedMono, f.serverDateMs, f.ageS)
                }
            }
            Fetch.NotFound -> Fetch.NotFound
            is Fetch.Failed -> f
        }
        logTransition(label, fetch, answer)
        return fetch
    }

    private sealed interface GameAnswer {
        class Found(val fetch: Fetch.Ok<GameSnapshot>) : GameAnswer
        class Failed(val fetch: Fetch.Failed) : GameAnswer
        data object Absent : GameAnswer
    }

    /** One board read for [id]: the mapped event, a failure, or [GameAnswer.Absent] (not on that board, or a JSON 404). */
    private suspend fun gameFrom(url: HttpUrl, label: String, id: GameId): GameAnswer {
        val logoBase = endpoints.logoBase
        val answer = call(url, EspnGameDto.serializer()) { it.events }
        val result: GameAnswer = when (val f = answer.fetch) {
            is Fetch.Ok -> if (f.value.events.orEmpty().none { idOf(it) == id.id }) {
                GameAnswer.Absent
            } else {
                val mapped = mapSafely { mapGame(f.value, id, f.serverDateMs, logoBase) }
                if (mapped != null) f.value.events.orEmpty().firstOrNull { idOf(it) == id.id }?.let { captureIds(id, it) }
                if (mapped == null) {
                    GameAnswer.Failed(Fetch.Failed(FailKind.BAD_BODY, null, f.receivedMono))
                } else {
                    GameAnswer.Found(Fetch.Ok(withLogos(mapped, logoBase), f.receivedMono, f.serverDateMs, f.ageS, f.maxAgeS, f.wireBytes))
                }
            }
            Fetch.NotFound -> GameAnswer.Absent
            // (Fix pass 3) A 403 on the follow's board read is the same WAF slow-down as on per-event: a 429 (the 20 s
            // back-off base, the 10 s floor and "slowed"). (Integration §18) EspnHttp's WAF rule already answers it so.
            is Fetch.Failed -> GameAnswer.Failed(f)
        }
        val logged: Fetch<*> = when (result) {
            is GameAnswer.Found -> result.fetch
            is GameAnswer.Failed -> result.fetch
            GameAnswer.Absent -> Fetch.NotFound
        }
        logTransition("$label (game)", logged, answer)
        return result
    }

    private inline fun <T> mapSafely(map: () -> T?): T? = try {
        map()
    } catch (e: RuntimeException) { // a mapper bug is a bad body, never a crash on the actor
        null
    }

    private fun withLogos(g: GameSnapshot, logoBase: String): GameSnapshot = g.copy(
        away = g.away.copy(logo = combinerLogo(g.away.abbrev, logoBase)),
        home = g.home.copy(logo = combinerLogo(g.home.abbrev, logoBase)),
    )

    private fun failedNow(): Fetch<Nothing> = Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs())

    // ---- transport (NFL hub commit 0: [EspnHttp], extracted with no behaviour change)

    private val http = EspnHttp(client, mono, TAG)

    private suspend fun <T> call(
        url: HttpUrl,
        serializer: DeserializationStrategy<T>,
        http: OkHttpClient = client,
        events: (T) -> List<EspnEventDto>?,
    ): EspnHttp.Answer<T> = this.http.call(url, serializer, SportsConstants.ESPN_MAX_BODY_BYTES, http) { remember(events(it)) }

    private fun logTransition(label: String, fetch: Fetch<*>, answer: EspnHttp.Answer<*>) = http.logTransition(label, fetch, answer)

    /** Each event's ET date, so a later `game()` asks for the right dated board (§1.5). */
    private fun remember(events: List<EspnEventDto>?) {
        if (events.isNullOrEmpty()) return
        val found = events.mapNotNull { e -> idOf(e)?.let { id -> etDate(e.date)?.let { id to it } } }
        if (found.isEmpty()) return
        synchronized(dates) { for ((id, d) in found) dates[id] = d }
    }

    companion object {
        private const val TAG = "EspnScores"

        /** (Update interval §4.2) The production source: the per-event follow, with the board fallback. */
        fun production(client: OkHttpClient, mono: MonoClock, endpoints: () -> EspnEndpoints): EspnScoreSource =
            EspnScoreSource(client, mono, endpoints, perEvent = true)
        private const val DATES_MAX = 512
        private val DATE_KEY = Regex("""\d{4}-\d{2}-\d{2}""")
        private val GAME_ID = Regex("""\d{1,12}""")
        private val ABBREV = Regex("""[a-z]{2,3}""")
        private val EASTERN: ZoneId = ZoneId.of("America/New_York")
        private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)


        /** A lenient leaf: a string or a number, else null. */
        private fun text(e: JsonElement?): String? =
            (e as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

        internal fun idOf(e: EspnEventDto): String? = text(e.id)

        /** ESPN's `date` ("2026-11-08T14:30Z", seconds optional) → its US Eastern date `yyyyMMdd`; null when unreadable. */
        internal fun etDate(date: JsonElement?): String? {
            val t = text(date) ?: return null
            val instant = try {
                OffsetDateTime.parse(t, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
            } catch (e: DateTimeParseException) {
                try {
                    Instant.parse(t)
                } catch (e2: DateTimeParseException) {
                    return null
                }
            }
            return instant.atZone(EASTERN).toLocalDate().format(YMD)
        }

        /** A UTC instant (ms) → its US Eastern date `yyyyMMdd`. */
        internal fun etDate(utcMs: Long): String = Instant.ofEpochMilli(utcMs).atZone(EASTERN).toLocalDate().format(YMD)

        /** `{base}combiner/i?img=/i/teamlogos/nfl/500-dark/scoreboard/{abbr}.png&h=200&w=200`, or null (§1.5). */
        internal fun combinerLogo(abbrev: String, logoBase: String): String? {
            val a = abbrev.trim().lowercase(Locale.ROOT)
            if (!ABBREV.matches(a)) return null
            val px = SportsConstants.ESPN_LOGO_PX
            return "${logoBase}combiner/i?img=/i/teamlogos/nfl/500-dark/scoreboard/$a.png&h=$px&w=$px"
        }
    }
}
