package app.thebox.data.sports

import app.thebox.core.common.BoxLog
import app.thebox.core.playback.MonoClock
import app.thebox.core.sports.ChlBoard
import app.thebox.core.sports.ChlGameFacts
import app.thebox.core.sports.ChlNodes
import app.thebox.core.sports.ChlTeam
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.HockeyTechMapper
import app.thebox.core.sports.League
import app.thebox.core.sports.PollMeter
import app.thebox.core.sports.ScoreSource
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.SportsDays
import app.thebox.core.sports.TeamLine
import app.thebox.core.sports.isChl
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * (CHL §2, docs/design/m6-chl-leagues.md) One CHL league's [ScoreSource]: HockeyTech's keyed `lscluster` for the day list,
 * teams and seasons, and its Firebase database for the followed game and Today's live clocks. One class, three instances
 * (OHL, WHL, QMJHL), bound with `@LeagueKey` in `SportsModule`. Nothing above [ScoreSource] learns about HockeyTech.
 *
 * **Day list** (§2.3): `gamesbydate&fetch_date=D`, the last body per date kept with its receive time and not requested
 * again inside [BOARD_CACHE_MS] (OkHttp's cache stays off; an expired body is dropped, and at most [BOARDS_MAX] are
 * kept); `teamsbyseason` once per process per season and `seasons` at most once a day ([SEASONS_REFRESH_MS], so a
 * process that lives into the playoffs learns the new season); a failure retries on the next board, and cards show the
 * code plate meanwhile. **Today only**, while a game is live or about to start, the Firebase overlay (`runningclock` +
 * `goalssummary` date queries, in parallel) replaces the clocks and scores (`HockeyTechMapper.overlay`). A failed overlay
 * keeps the last board on screen: the answer is `Failed` when this source already served an overlaid board of that date
 * (the repository marks its board failing, "Updated 2 min ago"), else the plain board. A refused overlay (Firebase
 * 401/403) is skipped for [SportsConstants.BACKOFF_MAX_MS] and the plain board is served, so a closed database never
 * fails the board (§2.6). A day answer's `receivedMono` is this answer's, even from a cached body (the day policy
 * schedules from it; the tab's "Updated" is then up to 240 s optimistic, review L9).
 *
 * **Followed game** (§2.4, §2.5):
 * - **Board mode** for a board-SCHEDULED game until `start − PRE_WAKE_MS` (a "Time TBD" game until the board reports it
 *   started), or (S5) a board-FINAL game Firebase no longer lists. The answer is the board's game with the facts'
 *   full-name lines; [followEndpoint] is then null (not a meter sample).
 * - **Live mode** otherwise: all six Firebase nodes **every poll**, at most [LIVE_PARALLEL] at a time (clock and score
 *   first) on the shared client's keep-alive connections, each with `X-Firebase-ETag: true` and `If-None-Match` when an
 *   ETag is known (S4); a 304 passes the body it confirmed. `shootouts` is added for the WHL and the QMJHL when
 *   `HockeyTechMapper.wantsShootouts(previous snapshot, facts)` says so. `runningclock` and `goalssummary` are required
 *   (their failure fails the poll); a failed `penalties` / `goals` request is passed as null with its `…Failed` flag
 *   (H8); a failed `publishedclock` / `shotssummary` request passes its last confirmed body (review L7).
 * - **S5:** `runningclock == null` in live mode: the board (re-read when older than [BOARD_CACHE_MS]) decides: SCHEDULED →
 *   board mode for this poll; FINAL → board mode (evicted); anything else → `Failed(BAD_BODY)`, a visible failure; the
 *   board without the game → [Fetch.NotFound].
 *
 * **Errors** (§2.6): no response → TIMEOUT / IO; 429 → HTTP_429; 5xx; `lscluster`'s 200 "Invalid key." / "Client access
 * denied." bodies and Firebase 401 / 403 → [FailKind.REFUSED]; over the cap ([SportsConstants.MAX_BODY_BYTES] for
 * `lscluster`, [SportsConstants.CHL_NODE_MAX_BYTES] per node), non-JSON or unmappable → BAD_BODY; anything else
 * HTTP_OTHER. `Retry-After` is clamped to [SportsConstants.BACKOFF_MAX_MS] like the NHL's, except on REFUSED, which waits
 * `max(300 s, Retry-After)` up to [REFUSED_RETRY_AFTER_MAX_S] (lead ruling S1).
 *
 * **Wire bytes** (lead ruling S2): every answer's `wireBytes` is the body bytes off the socket **plus the request and
 * response header bytes** (request line, status line, headers, blank lines), 304s included: Firebase is HTTP/1.1 and
 * uncompressed, so the headers are most of a CHL poll. The NHL and NFL count bodies only, unchanged.
 *
 * **Redaction and telemetry** (§2.2, L6): the keys live only in [HockeyTechKeys] and are registered with `Redact` before
 * the first request (this class's companion touches them). Log lines are `{league} {kind} → {state}` on a state change,
 * never a URL, a header, a body or an exception message (an IOException's or a JSON decoder's message can quote the URL or
 * the echoed key); a failure names the exception class only. No exception leaves this class except cancellation. A
 * request carries the default UA, a date, a game or season id and the public key, nothing about the user or the device.
 *
 * **Transport:** the `@ApiClient` (its interceptors, DNS, dispatcher and pool) with a
 * [SportsConstants.SPORTS_CALL_TIMEOUT_MS] call timeout and no cache. Not the `StreamConnectionBroker` (it is for stream
 * connections).
 */
class HockeyTechScoreSource internal constructor(
    override val league: League,
    client: OkHttpClient,
    private val mono: MonoClock,
    endpoints: () -> HockeyTechEndpoints = { HockeyTechEndpoints.DEFAULT },
    /** Wall time (UTC ms), for "today" (§2.3) and the pre-game window (§2.5). */
    private val wall: () -> Long = System::currentTimeMillis,
    callTimeoutMs: Long = SportsConstants.SPORTS_CALL_TIMEOUT_MS,
) : ScoreSource {
    init {
        require(league.isChl) { "HockeyTechScoreSource serves the CHL leagues only, not $league" }
    }

    private val client: OkHttpClient = WireMeter.install(client.newBuilder(), client)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .cache(null)
        .build()

    private val endpointsOf = endpoints

    /** Resolved on the actor's first request (off the main thread); [logoUrl] reads it without resolving (review L5). */
    @Volatile private var resolved: HockeyTechEndpoints? = null

    private val endpoints: HockeyTechEndpoints
        get() = resolved ?: endpointsOf().also { resolved = it }

    // ------------------------------------------------------------------ state (touched by the one caller, the actor)

    private class CachedBody(val body: JsonObject, val receivedMono: Long, val serverDateMs: Long?)

    /** The last `gamesbydate` body per date (§2.3 step 1), least recently used out first; expired ones are dropped. */
    private val boards = lru<String, CachedBody>(BOARDS_MAX)

    /** Teams by season id, once per process (§2.3 step 2). */
    private val teams = HashMap<String, Map<String, ChlTeam>>()

    /** Season id → playoff (§2.3 step 3); null = not loaded yet. Re-read at most once per [SEASONS_REFRESH_MS]. */
    private var seasons: Map<String, Boolean>? = null
    private var seasonsAt: Long? = null

    /** What the latest mapped board said about a game: its facts, its board snapshot, and whether teams were known. */
    private class Known(val facts: ChlGameFacts, val boardGame: GameSnapshot, val withTeams: Boolean)

    private val known = lru<String, Known>(KNOWN_MAX)

    /** (Review L4) When facts built without teams last asked for a board again, per game. */
    private val teamsRetryAt = HashMap<String, Long>()

    /** Dates whose board this source has served with a successful overlay (so a failed one keeps the board). */
    private val overlaid = lru<String, Unit>(BOARDS_MAX)

    /** A refused overlay is not requested again before this instant (S7). */
    private var overlayRefusedUntil: Long? = null

    /** The followed game's per-node `(ETag, last 200 body)` (S4), for one game at a time. */
    private class Tagged(val etag: String, val body: JsonObject?)

    private var tagsFor: String? = null
    private val tags = HashMap<Node, Tagged>()

    /** The previous live snapshot of the followed game ([HockeyTechMapper.wantsShootouts]). */
    private var previous: GameSnapshot? = null

    /** The meter endpoint of the last [game] answer per game id; null = a board-mode answer (not a live sample). */
    private var lastEndpoint: Pair<String, String?>? = null

    /** The last logged state per kind, for state-change logging. */
    private val lastState = HashMap<String, String>()

    /** (Review L5) Logo URLs already logged as rejected (by hash), so a card recomposing never repeats the line. */
    private val rejectedLogos = HashSet<Int>()

    /** (Lead ruling L11) The followed poll's requests to Firebase in flight at once. */
    private val livePermits = Semaphore(LIVE_PARALLEL)

    // ------------------------------------------------------------------ ScoreSource

    override suspend fun today(): Fetch<DayBoard> = day(todayDate().toString())

    override suspend fun day(dayKey: String): Fetch<DayBoard> {
        if (!DAY_KEY.matches(dayKey) || parseDay(dayKey) == null) return failedNow()
        return when (val a = assemble(dayKey)) {
            is Assembled.Failed -> a.fetch
            is Assembled.Ok -> withOverlay(dayKey, a)
        }
    }

    override suspend fun game(id: GameId): Fetch<GameSnapshot> = game(id, null)

    override suspend fun game(id: GameId, startUtcMs: Long?): Fetch<GameSnapshot> {
        if (id.league != league || !GAME_ID.matches(id.id)) return failedNow()
        if (tagsFor != id.id) {
            tagsFor = id.id
            tags.clear()
            previous = null
        }
        var k = when (val r = knownOrLoad(id.id, startUtcMs)) {
            is Lookup.Found -> r.known
            is Lookup.Answer -> return r.fetch.also { lastEndpoint = id.id to null }
        }
        k = withTeamsRetried(id.id, k)
        if (boardModeAhead(k)) return boardMode(id.id, k.facts.dayKey)
        val facts = refreshedForOvertime(id.id, k)
        return when (val live = live(id.id, facts)) {
            is Live.Answer -> live.fetch.also { lastEndpoint = id.id to PollMeter.CHL_LIVE }
            Live.NeedsBoard -> s5(id.id, facts)
        }
    }

    /**
     * (§2.5, lead ruling L6) Board mode before the game: a board-SCHEDULED game until `start − PRE_WAKE_MS`; a "Time TBD"
     * game (its start is a placeholder) until the board reports it started.
     */
    private fun boardModeAhead(k: Known): Boolean {
        if (k.boardGame.phase != GamePhase.SCHEDULED) return false
        if (k.boardGame.startTbd) return true
        return wall() < k.facts.startUtcMs - SportsConstants.PRE_WAKE_MS
    }

    /**
     * (Review L4) Facts mapped while `teamsbyseason` had failed carry code-only names; a follow re-asks for the board (and
     * so for the teams) at most once per [BOARD_CACHE_MS] until they are known.
     */
    private suspend fun withTeamsRetried(id: String, k: Known): Known {
        if (k.withTeams) return k
        val now = mono.nowMs()
        if (teamsRetryAt.size > KNOWN_MAX) teamsRetryAt.clear()
        val last = teamsRetryAt[id] ?: return k.also { teamsRetryAt[id] = now } // the board was just read
        if (now - last < BOARD_CACHE_MS) return k
        teamsRetryAt[id] = now
        assemble(k.facts.dayKey)
        return known[id] ?: k
    }

    /**
     * The facts as the latest board has them. A live follow normally keeps the facts it has (no `lscluster` request per
     * poll); once the previous snapshot is in a period whose length the facts lack (the OT length is listed only once OT
     * starts, §3.3), the board is re-read, at most once per [BOARD_CACHE_MS].
     */
    private suspend fun refreshedForOvertime(id: String, k: Known): Known {
        val p = previous?.period ?: return k
        if (p < 4 || k.facts.periodLengthsS.containsKey(p)) return k
        assemble(k.facts.dayKey)
        return known[id] ?: k
    }

    /**
     * (§3.5) The line's logo only when it is a validated PNG under this league's logo folder; else none. Called from the
     * UI: it never resolves the endpoints itself (no file read on the main thread) and logs a rejection once per URL.
     */
    override fun logoUrl(team: TeamLine): String {
        val url = team.logo ?: return ""
        val prefix = (resolved ?: return "").logoPrefix(league)
        if (url.startsWith(prefix) && LOGO_FILE.matches(url.substring(prefix.length))) return url
        val first = synchronized(rejectedLogos) { rejectedLogos.size < LOGO_REJECTS_MAX && rejectedLogos.add(url.hashCode()) }
        if (first) BoxLog.w(TAG, "$league logo → rejected")
        return ""
    }

    /** (§1.6 #10) A live-mode follow poll is the CHL meter endpoint; a board-mode answer is no sample (null). */
    override fun followEndpoint(id: GameId): String? {
        val last = lastEndpoint ?: return PollMeter.CHL_LIVE
        return if (last.first == id.id) last.second else PollMeter.CHL_LIVE
    }

    // ------------------------------------------------------------------ the day list (§2.3)

    private sealed interface Assembled {
        class Ok(val board: ChlBoard, val fresh: CachedBody?, val wire: Long) : Assembled
        class Failed(val fetch: Fetch.Failed) : Assembled
    }

    /** The mapped board of [dayKey] with its teams and seasons; remembers every game's facts. */
    private suspend fun assemble(dayKey: String): Assembled {
        var wire = 0L
        val now = mono.nowMs()
        // (Review L2) An expired body is never read again: drop every one, so only live dates stay in memory.
        boards.entries.removeAll { now - it.value.receivedMono >= BOARD_CACHE_MS }
        var fresh: CachedBody? = null
        val body = boards[dayKey]?.body ?: run {
            val r = feed("board") { addQueryParameter("view", "gamesbydate").addQueryParameter("fetch_date", dayKey) }
            when (r) {
                is Feed.Failed -> return Assembled.Failed(r.fetch)
                is Feed.Ok -> {
                    wire += r.wire
                    fresh = CachedBody(r.body, r.receivedMono, r.serverDateMs)
                    boards[dayKey] = fresh!!
                    r.body
                }
            }
        }
        val first = mapSafely { HockeyTechMapper.board(league, dayKey, body, null, null) }
            ?: return Assembled.Failed(badBody("board")).also { boards.remove(dayKey) }
        wire += loadSeasons()
        wire += loadTeams(first.seasonIds)
        val teamMaps = first.seasonIds.mapNotNull { teams[it] }
        val withTeams = teamMaps.size == first.seasonIds.size
        val teamMap = teamMaps.takeIf { it.isNotEmpty() }?.fold(emptyMap<String, ChlTeam>()) { acc, m -> acc + m }
        val board = mapSafely { HockeyTechMapper.board(league, dayKey, body, teamMap, seasons) } ?: first
        for (g in board.board.games) board.facts[g.id.id]?.let { known[g.id.id] = Known(it, g, withTeams) }
        return Assembled.Ok(board, fresh, wire)
    }

    private suspend fun loadSeasons(): Long {
        val now = mono.nowMs()
        val at = seasonsAt
        if (seasons != null && at != null && now - at < SEASONS_REFRESH_MS) return 0
        return when (val r = feed("seasons") { addQueryParameter("view", "seasons") }) {
            is Feed.Failed -> 0L.also { if (seasons != null) seasonsAt = now } // keep the old map; ask again tomorrow
            is Feed.Ok -> {
                val m = mapSafely { HockeyTechMapper.seasons(r.body) }
                if (m == null) logState("seasons", FailKind.BAD_BODY.name) else seasons = m
                if (seasons != null) seasonsAt = now
                r.wire
            }
        }
    }

    private suspend fun loadTeams(seasonIds: Set<String>): Long {
        var wire = 0L
        for (s in seasonIds) {
            if (teams.containsKey(s) || teams.size >= SEASONS_MAX) continue
            when (val r = feed("teams") { addQueryParameter("view", "teamsbyseason").addQueryParameter("season_id", s) }) {
                is Feed.Failed -> Unit
                is Feed.Ok -> {
                    wire += r.wire
                    val m = mapSafely { HockeyTechMapper.teams(r.body, endpoints.logoPrefix(league)) }
                    if (m == null) logState("teams", FailKind.BAD_BODY.name) else teams[s] = m
                }
            }
        }
        return wire
    }

    /** §2.3 step 4: Today's overlay while a game is live or about to start. */
    private suspend fun withOverlay(dayKey: String, a: Assembled.Ok): Fetch<DayBoard> {
        val now = mono.nowMs()
        // receivedMono = this answer (a cached body is still this answer's board, and the day policy schedules from it);
        // no `Date`, so the policy never aligns a CHL board to the NHL CDN's buckets.
        fun ok(b: ChlBoard, wire: Long) = Fetch.Ok(b.board, mono.nowMs(), null, 0, wireBytes = wire)
        val wanted = dayKey == todayDate().toString() && needsOverlay(a.board.board.games)
        val refused = overlayRefusedUntil?.let { now < it } == true
        if (!wanted || refused) return ok(a.board, a.wire)
        val base = endpoints.firebase(league)
        val (rc, gs) = coroutineScope {
            val rc = async { node(dateQuery(base, "runningclock/games.json", dayKey), etags = false) }
            val gs = async { node(dateQuery(base, "goalssummary/1/games.json", dayKey), etags = false) }
            rc.await() to gs.await()
        }
        val wire = a.wire + rc.wire + gs.wire
        val failure = listOf(rc, gs).filterIsInstance<NodeAnswer.Failed>().let { fs ->
            fs.firstOrNull { it.fetch.kind == FailKind.REFUSED } ?: fs.firstOrNull()
        }
        logState("overlay", failure?.fetch?.kind?.name ?: "ok", failure?.cause)
        if (failure == null) {
            val board = mapSafely {
                HockeyTechMapper.overlay(a.board, (rc as NodeAnswer.Ok).body, (gs as NodeAnswer.Ok).body)
            } ?: a.board
            overlaid[dayKey] = Unit
            return ok(board, wire)
        }
        if (failure.fetch.kind == FailKind.REFUSED) {
            overlayRefusedUntil = failure.fetch.receivedMono + SportsConstants.BACKOFF_MAX_MS
            return ok(a.board, wire)
        }
        return if (overlaid[dayKey] != null) failure.fetch else ok(a.board, wire)
    }

    private fun needsOverlay(games: List<GameSnapshot>): Boolean {
        val now = wall()
        return games.any { g ->
            when (g.phase) {
                GamePhase.LIVE, GamePhase.INTERMISSION, GamePhase.PRE -> true
                // (CHL integration review L5) A "Time TBD" game's start is a midnight placeholder, not a start: it asks for
                // the overlay only once the board reports it started.
                GamePhase.SCHEDULED -> !g.startTbd && g.startUtcMs - now <= OVERLAY_LEAD_MS && now - g.startUtcMs <= OVERLAY_LATE_MS
                else -> false
            }
        }
    }

    private fun dateQuery(base: String, path: String, dayKey: String): HttpUrl = (base + path).toHttpUrl().newBuilder()
        .addQueryParameter("orderBy", "\"DatePlayed\"")
        .addQueryParameter("equalTo", "\"$dayKey\"")
        .build()

    // ------------------------------------------------------------------ the followed game (§2.4)

    private sealed interface Lookup {
        class Found(val known: Known) : Lookup
        class Answer(val fetch: Fetch<Nothing>) : Lookup
    }

    /**
     * The game's facts: remembered from a board, else from the board of the start hint's date (in the league's reference
     * zone), else today's. NotFound only when a board was read and none lists the game.
     */
    private suspend fun knownOrLoad(id: String, startUtcMs: Long?): Lookup {
        known[id]?.let { return Lookup.Found(it) }
        val days = listOfNotNull(startUtcMs?.takeIf { it > 0L }?.let { dayOf(it).toString() }, todayDate().toString()).distinct()
        var failure: Fetch.Failed? = null
        for (d in days) {
            when (val a = assemble(d)) {
                is Assembled.Failed -> if (failure == null) failure = a.fetch
                is Assembled.Ok -> known[id]?.let { return Lookup.Found(it) }
            }
        }
        return Lookup.Answer(failure ?: Fetch.NotFound.also { logState("board (game)", "not found") })
    }

    /** Board mode: the board's game of [dayKey] (cached up to [BOARD_CACHE_MS]) with the facts' full-name lines. */
    private suspend fun boardMode(id: String, dayKey: String): Fetch<GameSnapshot> {
        lastEndpoint = id to null
        return when (val a = assemble(dayKey)) {
            is Assembled.Failed -> a.fetch
            is Assembled.Ok -> {
                val g = a.board.board.games.firstOrNull { it.id.id == id }
                val f = a.board.facts[id]
                if (g == null || f == null) {
                    logState("board (game)", "not found")
                    known.remove(id)
                    Fetch.NotFound
                } else {
                    logState("board (game)", "ok")
                    Fetch.Ok(g.copy(away = f.away, home = f.home), mono.nowMs(), a.fresh?.serverDateMs, 0, wireBytes = a.wire)
                }
            }
        }
    }

    /** (S5) `runningclock` answered `null`: the board decides between board mode and a visible failure. */
    private suspend fun s5(id: String, k: Known): Fetch<GameSnapshot> {
        val phase = when (val a = assemble(k.facts.dayKey)) {
            is Assembled.Failed -> k.boardGame.phase // the last board we had
            is Assembled.Ok -> a.board.board.games.firstOrNull { it.id.id == id }?.phase
                ?: return Fetch.NotFound.also {
                    lastEndpoint = id to null
                    known.remove(id)
                    logState("board (game)", "not found")
                }
        }
        return when (phase) {
            GamePhase.SCHEDULED, GamePhase.FINAL -> boardMode(id, k.facts.dayKey)
            else -> badBody("live").also { lastEndpoint = id to PollMeter.CHL_LIVE }
        }
    }

    /** The nodes in request order: the clock and the score first (lead ruling L11), then the rest. */
    private enum class Node(val path: String) {
        RUNNINGCLOCK("runningclock/games/"),
        GOALSSUMMARY("goalssummary/1/games/"),
        PUBLISHEDCLOCK("publishedclock/1/games/"),
        SHOTSSUMMARY("shotssummary/1/games/"),
        PENALTIES("penalties/1/games/"),
        GOALS("goals/1/games/"),
        SHOOTOUTS("shootouts/1/games/"),
    }

    private sealed interface Live {
        class Answer(val fetch: Fetch<GameSnapshot>) : Live
        /** S5: `runningclock` is `null`, or the game has no `goalssummary` and did not map (a forfeit): the board decides. */
        data object NeedsBoard : Live
    }

    /** One live-mode poll: the six nodes (plus `shootouts` when wanted), conditional, [LIVE_PARALLEL] at a time. */
    private suspend fun live(id: String, k: Known): Live {
        val wantsSo = when (league) {
            League.WHL, League.QMJHL -> mapSafely { HockeyTechMapper.wantsShootouts(previous, k.facts) } == true
            League.OHL, League.NHL, League.NFL -> false
        }
        val nodes = Node.entries.filter { it != Node.SHOOTOUTS || wantsSo }
        val base = endpoints.firebase(league)
        val answers: Map<Node, NodeAnswer> = coroutineScope {
            val pending = nodes.map { n ->
                async { livePermits.withPermit { node("$base${n.path}$id.json".toHttpUrl(), etags = true, known = tags[n]) } }
            }
            nodes.zip(pending.awaitAll()).toMap()
        }
        // The conditional cache: a 200 replaces the node's (ETag, body); a 304 keeps it; a failure keeps it too.
        for ((n, a) in answers) if (a is NodeAnswer.Ok && a.etag != null) tags[n] = Tagged(a.etag, a.body)
        val wire = answers.values.sumOf { it.wire }
        val clock = answers.getValue(Node.RUNNINGCLOCK)
        val summary = answers.getValue(Node.GOALSSUMMARY)
        val required = listOf(clock, summary).filterIsInstance<NodeAnswer.Failed>()
        if (required.isNotEmpty()) {
            val f = required.firstOrNull { it.fetch.kind == FailKind.REFUSED } ?: required.first()
            logState("live", f.fetch.kind.name, f.cause)
            return Live.Answer(f.fetch)
        }
        clock as NodeAnswer.Ok
        val rc = clock.body ?: return Live.NeedsBoard.also { logState("live", "no clock") }
        fun body(n: Node): JsonObject? = (answers[n] as? NodeAnswer.Ok)?.body
        /** (Review L7) A failed optional display node keeps its last confirmed body (never penalties / goals: H8). */
        fun bodyOrLast(n: Node): JsonObject? = if (answers[n] is NodeAnswer.Failed) tags[n]?.body else body(n)
        val nodesIn = ChlNodes(
            runningclock = rc,
            publishedclock = bodyOrLast(Node.PUBLISHEDCLOCK),
            goalssummary = (summary as NodeAnswer.Ok).body,
            shotssummary = bodyOrLast(Node.SHOTSSUMMARY),
            penalties = body(Node.PENALTIES),
            goals = body(Node.GOALS),
            shootouts = body(Node.SHOOTOUTS),
            penaltiesFailed = answers[Node.PENALTIES] is NodeAnswer.Failed,
            goalsFailed = answers[Node.GOALS] is NodeAnswer.Failed,
        )
        val snap = mapSafely { HockeyTechMapper.followed(league, k.facts, nodesIn) }
            ?: return if (nodesIn.goalssummary == null) {
                // A started game without a score summary (a forfeit, status 5): only a FINAL board may answer (S5).
                Live.NeedsBoard.also { logState("live", "no summary") }
            } else {
                Live.Answer(badBody("live"))
            }
        val out = snap.copy(startTbd = k.boardGame.startTbd)
        previous = out
        val partial = answers.filterValues { it is NodeAnswer.Failed }.keys
        logState("live", if (partial.isEmpty()) "ok" else "ok, partial")
        return Live.Answer(Fetch.Ok(out, clock.receivedMono, clock.serverDateMs, 0, null, wire))
    }

    // ------------------------------------------------------------------ the two hosts

    private sealed interface Feed {
        class Ok(val body: JsonObject, val receivedMono: Long, val serverDateMs: Long?, val wire: Long) : Feed
        class Failed(val fetch: Fetch.Failed) : Feed
    }

    /** One `lscluster` modulekit request (§2.1); [kind] names it in the log. */
    private suspend fun feed(kind: String, view: HttpUrl.Builder.() -> Unit): Feed {
        val e = endpoints
        val url = e.feedUrl.toHttpUrl().newBuilder()
            .addQueryParameter("feed", "modulekit")
            .apply(view)
            .addQueryParameter("key", e.key(league))
            .addQueryParameter("client_code", HockeyTechEndpoints.client(league))
            .addQueryParameter("fmt", "json")
            .addQueryParameter("lang", "en")
            .build()
        val raw = call(url, SportsConstants.MAX_BODY_BYTES, emptyMap())
        val out: Feed = when (raw) {
            is Raw.Failed -> Feed.Failed(raw.fetch)
            is Raw.NotModified -> Feed.Failed(Fetch.Failed(FailKind.HTTP_OTHER, null, raw.receivedMono))
            is Raw.Status -> Feed.Failed(raw.failed(refusedOn = setOf(401, 403)))
            is Raw.Body -> {
                val head = raw.text.trimStart()
                when {
                    REFUSED_TEXTS.any { head.startsWith(it) } -> Feed.Failed(Fetch.Failed(FailKind.REFUSED, null, raw.receivedMono))
                    else -> when (val j = parse(raw.text)) {
                        is JsonObject -> Feed.Ok(j, raw.receivedMono, raw.serverDateMs, raw.wire)
                        else -> Feed.Failed(Fetch.Failed(FailKind.BAD_BODY, null, raw.receivedMono))
                    }
                }
            }
        }
        when (out) {
            is Feed.Ok -> logState(kind, "ok")
            is Feed.Failed -> logState(kind, out.fetch.kind.name, (raw as? Raw.Failed)?.cause)
        }
        return out
    }

    private sealed interface NodeAnswer {
        val wire: Long

        /** [body] null = the node answered JSON `null`; [etag] the answer's (or the confirmed one's on a 304). */
        class Ok(val body: JsonObject?, val etag: String?, val receivedMono: Long, val serverDateMs: Long?, override val wire: Long) : NodeAnswer
        class Failed(val fetch: Fetch.Failed, val cause: String? = null, override val wire: Long = 0) : NodeAnswer
    }

    /**
     * One Firebase request. [etags]: a node read, sent with `X-Firebase-ETag: true` (Firebase returns an ETag only then)
     * and `If-None-Match` for the [known] ETag; false for the date queries, which Firebase refuses to combine with ETags
     * (400 "Mixing querying parameters and ETag requests is not supported", checked 2026-11-14). A 304 answers the [known]
     * body.
     */
    private suspend fun node(url: HttpUrl, etags: Boolean, known: Tagged? = null): NodeAnswer {
        val headers = HashMap<String, String>()
        if (etags) {
            headers["X-Firebase-ETag"] = "true"
            known?.let { headers["If-None-Match"] = it.etag }
        }
        return when (val raw = call(url, SportsConstants.CHL_NODE_MAX_BYTES, headers)) {
            is Raw.Failed -> NodeAnswer.Failed(raw.fetch, raw.cause, raw.wire)
            is Raw.NotModified -> if (etags && known != null) {
                NodeAnswer.Ok(known.body, known.etag, raw.receivedMono, raw.serverDateMs, raw.wire)
            } else {
                NodeAnswer.Failed(Fetch.Failed(FailKind.HTTP_OTHER, null, raw.receivedMono), wire = raw.wire)
            }
            is Raw.Status -> NodeAnswer.Failed(raw.failed(refusedOn = setOf(401, 403)), wire = raw.wire)
            is Raw.Body -> when (val j = parse(raw.text)) {
                is JsonObject -> NodeAnswer.Ok(j, raw.etag?.takeIf { etags }, raw.receivedMono, raw.serverDateMs, raw.wire)
                JsonNull -> NodeAnswer.Ok(null, raw.etag?.takeIf { etags }, raw.receivedMono, raw.serverDateMs, raw.wire)
                else -> NodeAnswer.Failed(Fetch.Failed(FailKind.BAD_BODY, null, raw.receivedMono), wire = raw.wire)
            }
        }
    }

    // ------------------------------------------------------------------ transport

    private sealed interface Raw {
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
    private suspend fun call(url: HttpUrl, cap: Long, headers: Map<String, String>): Raw {
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

    // ------------------------------------------------------------------ helpers

    private fun todayDate(): LocalDate = SportsDays.today(league, wall())

    private fun dayOf(utcMs: Long): LocalDate = Instant.ofEpochMilli(utcMs).atZone(SportsDays.zone(league)).toLocalDate()

    private fun parseDay(s: String): LocalDate? = try {
        LocalDate.parse(s)
    } catch (e: java.time.DateTimeException) {
        null
    }

    /** The decoded body, or null when it is not JSON. The decoder's message (it can quote the body) is never kept. */
    private fun parse(text: String): JsonElement? = try {
        Json.parseToJsonElement(text)
    } catch (e: IllegalArgumentException) { // SerializationException is one
        null
    }

    private inline fun <T> mapSafely(map: () -> T?): T? = try {
        map()
    } catch (e: RuntimeException) { // a mapper bug is a bad body, never a crash on the actor
        null
    }

    private fun badBody(kind: String): Fetch.Failed {
        logState(kind, FailKind.BAD_BODY.name)
        return Fetch.Failed(FailKind.BAD_BODY, null, mono.nowMs())
    }

    private fun failedNow(): Fetch.Failed = Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs())

    /** One line per state change of a [kind]: `{league} {kind} → {state}` (+ the exception class), never a URL or body. */
    private fun logState(kind: String, state: String, cause: String? = null) {
        val previous = synchronized(lastState) { lastState.put(kind, state) }
        if (previous == state) return
        val line = "$league $kind → $state" + (cause?.let { " ($it)" } ?: "")
        if (state.startsWith("ok")) BoxLog.i(TAG) { line } else BoxLog.w(TAG, line)
    }

    companion object {
        init {
            HockeyTechKeys.touch() // (L6) the keys are registered with Redact before any request
        }

        private const val TAG = "ChlScores"

        /** (§2.3) A `gamesbydate` body is not requested again inside this (its `max-age`). */
        internal const val BOARD_CACHE_MS = 240_000L

        /** (Review L3) `seasons` is re-read at most this often (a process may live into the playoffs). */
        internal const val SEASONS_REFRESH_MS = 24 * 3_600_000L

        /** Today's overlay runs from this long before a listed start … */
        internal const val OVERLAY_LEAD_MS = 15 * 60_000L

        /** … until this long after it while the board still says SCHEDULED (a late or postponed start). */
        internal const val OVERLAY_LATE_MS = 6 * 3_600_000L

        /** (Lead ruling L11) At most this many of a followed poll's requests at once (connections to Firebase). */
        internal const val LIVE_PARALLEL = 3

        /** (Lead ruling S1) The longest `Retry-After` honoured on a REFUSED answer; every other kind is clamped to 300 s. */
        internal const val REFUSED_RETRY_AFTER_MAX_S = 3_600L

        /** (Review L2) Yesterday, today and tomorrow. */
        private const val BOARDS_MAX = 3
        private const val KNOWN_MAX = 256
        private const val SEASONS_MAX = 8
        private const val LOGO_REJECTS_MAX = 256

        private val DAY_KEY = Regex("""\d{4}-\d{2}-\d{2}""")
        private val GAME_ID = Regex("""\d{1,12}""")
        /** (§3.5) After the JPEG swap every CHL logo is a PNG named by team id (and a season suffix). */
        private val LOGO_FILE = Regex("""[0-9]+(_[0-9]+)?\.png""")
        private val REFUSED_TEXTS = listOf("Invalid key", "Client access denied")

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

        private fun <K, V> lru(max: Int) = object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
        }
    }
}
