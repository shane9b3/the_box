package app.thebox.data.sports

import app.thebox.core.common.BoxLog
import app.thebox.core.playback.MonoClock
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.state.SportsDomain
import app.thebox.core.settings.state.SportsFollowDomain
import app.thebox.core.sports.DeviceAwake
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.BoxScore
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.DetailCadence
import app.thebox.core.sports.hub.DetailPollPolicy
import app.thebox.core.sports.hub.DetailPollPolicy.DetailGate
import app.thebox.core.sports.hub.DetailState
import app.thebox.core.sports.hub.FinalFacts
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.GameDetailRepository
import app.thebox.core.sports.hub.NflHighlight
import app.thebox.core.sports.hub.VideoEntry
import app.thebox.data.di.ApplicationScope
import app.thebox.data.di.WallClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/**
 * One detail answer: the [fetch] and whether the landing half failed (the NHL detail then lacks its landing facts).
 * (NFL hub commit 0, docs/design/m6-nfl-hub.md §5.5) Moved here from `NhlDetailSource`; [box] (P2): the box score mapped
 * from the same ESPN summary, so the Box score sheet never makes a second parse.
 *
 * [partial] (CHL hub commit 0, docs/design/m6-chl-hub.md §4.1, §4.4 a): an `Ok` [fetch] that kept what loaded while a
 * secondary read failed (a CHL pre-game's `topgoalies` or league table). Not a failure: [landingFailed] stays false, the
 * repository publishes `DetailState.Ready(failing = false, partial = true)` and re-reads at
 * `min(normal next, receivedMono + HUB_CHL_PARTIAL_RETRY_MS)` (P2). The NHL and the NFL never set it.
 */
internal data class DetailAnswer(
    val fetch: Fetch<GameDetail>,
    val landingFailed: Boolean,
    val box: Fetch.Ok<BoxScore>? = null,
    val partial: Boolean = false,
)

/** The repository's view of the detail sources (a seam for the loop's tests). */
internal interface DetailFetcher {
    suspend fun detail(id: GameId, phaseHint: GamePhase?): DetailAnswer
    suspend fun boxScore(id: GameId): Fetch<BoxScore>

    companion object {
        /**
         * (NFL hub commit 0, §6.5) Routes by league: NHL → [nhl], NFL → [espn]; (CHL hub commit 0) OHL, WHL, QMJHL →
         * [chl]. The loop asks [chl] only for a detailed CHL game (`SportsConstants.CHL_HUB_ENABLED`, pre-game or final);
         * until then those games are BoardOnly and never reach it.
         */
        fun of(nhl: NhlDetailSource, espn: EspnDetailSource, chl: ChlDetailSource): DetailFetcher = object : DetailFetcher {
            override suspend fun detail(id: GameId, phaseHint: GamePhase?): DetailAnswer = when (id.league) {
                League.NHL -> nhl.detailAnswer(id, phaseHint)
                League.NFL -> espn.detailAnswer(id, phaseHint)
                League.OHL, League.WHL, League.QMJHL -> chl.detailAnswer(id, phaseHint)
            }

            override suspend fun boxScore(id: GameId): Fetch<BoxScore> = when (id.league) {
                League.NHL -> nhl.boxScore(id)
                League.NFL -> espn.boxScore(id)
                League.OHL, League.WHL, League.QMJHL -> chl.boxScore(id)
            }
        }
    }
}

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §4.3, §4.4; WP-1) The panel's game detail: its own single-threaded actor beside
 * `ScoreRepositoryImpl` (never the follow machine, its meter, demand owners or the day list), fetching only the shown
 * game by [DetailPollPolicy], an LRU of `HUB_DETAIL_LRU` details, paused while a clip plays, nothing with Show Sports off,
 * the device asleep, the app in the background or no network.
 *
 * - [show] of another game starts a `HUB_FETCH_DWELL_MS` rest (a cache hit is shown at once); a [show] of the game
 *   already resting skips the rest (OK entering the panel calls it again), as does [retry]. A move to another game cancels
 *   the in-flight call; its answer is dropped.
 * - The fetch runs in a child job; its answer comes back as an input, so all state is touched on the loop only.
 * - (NFL hub P2, docs/design/m6-nfl-hub.md §5.2, §5.5) The NHL and the NFL are fetched (the NFL through
 *   `EspnDetailSource`), each with its own [DetailCadence] through the same loop, rest and gate; CHL games are
 *   [DetailState.BoardOnly] without a request. Every per-game map is keyed `"league:id"` (an NHL and an ESPN id never
 *   collide). Back-off always wins over every other rule, the box-score request included.
 * - (NFL, arch M4) The NFL box score comes from the same summary parse: [boxScore] posts to the loop, which answers from
 *   its one-slot box cache or forces the shown game's next fetch and completes from that answer. There is never a
 *   second parse in flight. The NHL keeps its own on-demand `boxscore` request (unchanged).
 * - A refetch that fails keeps the cached detail with `failing`; with nothing cached the state is `Failed(kind)`;
 *   offline with nothing cached is `Failed(OFFLINE)`. `stale` turns on at 2 × the phase's interval (A-S7).
 * - A failure inside the loop stops the detail with a log line; it never takes the app down.
 */
@Singleton
class GameDetailRepositoryImpl internal constructor(
    private val fetcher: DetailFetcher,
    private val store: SettingsStore,
    private val awake: DeviceAwake,
    private val signals: SportsSignals,
    scope: CoroutineScope,
    private val mono: MonoClock,
    private val wallNow: () -> Long,
    private val random: Random = Random.Default,
    private val dwellMs: Long = SportsConstants.HUB_FETCH_DWELL_MS,
    /** (NFL hub §5.4) Extra memory to drop on [trim] (the ESPN clip catalog, except the playing clip). */
    private val onTrim: () -> Unit = {},
    /** (NFL hub code review 2026-11-26, data #6) `SportsConstants.ESPN_VIDEO_ENABLED`: off, no NFL final waits for a video. */
    private val espnVideo: Boolean = SportsConstants.ESPN_VIDEO_ENABLED,
    /**
     * (NFL hub §20) The shown NFL game's board highlight, handed to the clip catalog (`EspnDetailSource.noteBoardHighlight`)
     * so `info()` stays catalog-only and `stream()` plays it without a record lookup, even when the summary never lists it.
     */
    private val onBoardHighlight: (VideoEntry) -> Unit = {},
    /**
     * (CHL hub P2, docs/design/m6-chl-hub.md §4.1, D5, D7) `SportsConstants.CHL_HUB_ENABLED`: on, an OHL / WHL / QMJHL game
     * is fetched through [ChlDetailSource] before the game and at the final (BoardOnly live, in a break and postponed);
     * off, every CHL game is BoardOnly without a request, exactly as before.
     */
    private val chlHub: Boolean = SportsConstants.CHL_HUB_ENABLED,
) : GameDetailRepository {
    /** (NFL hub) Built with the sources; [DetailFetcher.of] routes by league. (CHL hub commit 0) + [ChlDetailSource]. */
    @Inject constructor(
        source: NhlDetailSource,
        espn: EspnDetailSource,
        chl: ChlDetailSource,
        store: SettingsStore,
        awake: Optional<DeviceAwake>,
        signals: SportsSignals,
        @ApplicationScope scope: CoroutineScope,
        wall: WallClock,
    ) : this(
        DetailFetcher.of(source, espn, chl), store, awake.orElse(DeviceAwake.ALWAYS), signals, scope, ScoreRepositoryImpl.ELAPSED, wall::now,
        onTrim = {
            espn.trimMemory()
            chl.trimMemory()
        },
        onBoardHighlight = espn::noteBoardHighlight,
    )

    private sealed interface Input {
        data class Show(val game: GameSnapshot?) : Input
        data class Pause(val on: Boolean) : Input
        data object Retry : Input
        data object Trim : Input
        data class Sports(val on: Boolean) : Input
        data class Phase(val ms: Long?) : Input
        data class Awake(val on: Boolean) : Input
        data class Foreground(val on: Boolean) : Input
        data class Online(val on: Boolean) : Input
        data class Fetched(val gen: Int, val id: GameId, val answer: DetailAnswer) : Input
        /** (NFL hub §5.5) The box score of [id] from the loop's slot or the shown game's next parse. */
        data class Box(val id: GameId, val answer: CompletableDeferred<Fetch<BoxScore>>) : Input
    }

    private val inputs = Channel<Input>(Channel.UNLIMITED)
    private val _detail = MutableStateFlow<DetailState>(DetailState.None)
    override val detail: StateFlow<DetailState> = _detail.asStateFlow()

    override fun show(game: GameSnapshot?) = post(Input.Show(game))
    override fun pause(paused: Boolean) = post(Input.Pause(paused))
    override fun retry() = post(Input.Retry)
    override fun trim() = post(Input.Trim)

    /** Set when the loop stopped on a failure: a box-score caller is answered at once instead of waiting. */
    @Volatile private var stopped = false

    /** The gate as the loop last saw it, for [boxScore] (called from the view model, off the loop). */
    @Volatile private var gateSnapshot = DetailGate(sportsOn = false, awake = false, foreground = false, online = false, paused = false)

    /**
     * (Sports Hub code review 2026-11-25, L6) One box-score call per game at a time: a second caller for the same game
     * (the sheet opened twice quickly) waits for the first one's answer instead of sending its own request. Each call
     * still runs in its caller's coroutine, so the caller's scope (the sheet, a game switch) cancels it; a waiter whose
     * owner was cancelled makes the call itself.
     */
    private class BoxFlight(val id: GameId, val answer: CompletableDeferred<Fetch<BoxScore>?>)
    private val boxLock = Any()
    private var boxInFlight: BoxFlight? = null

    override suspend fun boxScore(id: GameId): Fetch<BoxScore> {
        val g = gateSnapshot
        if (!g.online) return Fetch.Failed(FailKind.OFFLINE, null, mono.nowMs())
        if (!g.open || !isDetailed(id.league)) return Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs())
        if (id.league != League.NHL) {
            // (NFL hub §5.5, arch M4) Through the loop: the slot, or the shown game's next (shared) parse.
            val answer = CompletableDeferred<Fetch<BoxScore>>()
            post(Input.Box(id, answer))
            if (stopped) answer.complete(Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs())) // the loop is gone
            return answer.await()
        }
        while (true) {
            var mine = false
            val flight = synchronized(boxLock) {
                boxInFlight?.takeIf { it.id == id } ?: BoxFlight(id, CompletableDeferred()).also {
                    boxInFlight = it
                    mine = true
                }
            }
            if (!mine) {
                flight.answer.await()?.let { return it }
                continue // the owner was cancelled: try again (as the owner, or behind a newer one)
            }
            try {
                val r = fetchBox(id)
                flight.answer.complete(r)
                return r
            } catch (e: Throwable) {
                // (Verification review L-b) Cancelled or anything else thrown past fetchBox (an Error, a checked
                // IOException from a fetcher): the waiters are released to try again, never left suspended.
                flight.answer.complete(null)
                throw e
            } finally {
                synchronized(boxLock) { if (boxInFlight === flight) boxInFlight = null }
            }
        }
    }

    private suspend fun fetchBox(id: GameId): Fetch<BoxScore> = try {
        fetcher.boxScore(id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        BoxLog.w(TAG, "box score failed: ${e.javaClass.simpleName}")
        Fetch.Failed(FailKind.BAD_BODY, null, mono.nowMs())
    }

    private fun post(i: Input) {
        inputs.trySend(i)
    }

    // ---- loop state (touched only inside the loop)

    /** [partial] (CHL hub §4.1): the answer kept what loaded while a secondary read failed (Ready, not failing). */
    private class Entry(val ok: Fetch.Ok<GameDetail>, val landingFailed: Boolean, val partial: Boolean = false)

    /** (NFL hub §5.5) The last parsed box score, keyed `league:id`; one slot (≤ 32 KB). */
    private var boxSlot: Pair<String, Fetch.Ok<BoxScore>>? = null
    /** (NFL hub §5.5) Box-score callers waiting for the shown game's next answer. */
    private val boxWaiters = ArrayList<CompletableDeferred<Fetch<BoxScore>>>()

    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > SportsConstants.HUB_DETAIL_LRU
    }
    /** Game id → wall ms the game was first seen FINAL after being seen unfinished (the recap wait's start). */
    private val endedAt = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > SportsConstants.HUB_DETAIL_LRU
    }
    /**
     * Game ids seen unfinished in this process (the recap wait's trigger). (Sports Hub code review 2026-11-25, L7) An
     * access-ordered LRU of the same size as [endedAt], so it is bounded without a [trim].
     */
    private val seenUnfinished: MutableSet<String> = java.util.Collections.newSetFromMap(
        object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > SportsConstants.HUB_DETAIL_LRU
        },
    )

    /**
     * (NFL hub code review 2026-11-26, data #7) NFL game id → the last wall ms it was seen unfinished (board or detail), so
     * a game whose end this process did not watch is not "ended" at the moment it is next seen. Same bound as [endedAt].
     */
    private val lastUnfinishedAt = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > SportsConstants.HUB_DETAIL_LRU
    }

    /** For tests (L7): the size of [seenUnfinished]. Read on the loop's thread only. */
    internal val seenUnfinishedCount: Int get() = seenUnfinished.size

    private var shown: GameSnapshot? = null
    private var dwellUntil: Long? = null
    private var force = false
    private var gen = 0
    private var inFlight: Job? = null
    private var failures = 0
    private var lastFailMono: Long? = null
    private var retryAfterS: Int? = null
    private var lastFailKind: FailKind? = null
    private var jitter = 0.0
    /**
     * (Integration §18) Good answers in a row for the shown game whose phase still lags the board's (ESPN's summary header
     * behind the board's FINAL); `DetailPollPolicy.nextAt` stops forcing the 15 s re-read after `HUB_NFL_PHASE_LAG_TRIES`.
     */
    private var lagTries = 0

    private var sportsOn = false
    private var pollPhaseMs = SportsConstants.POLL_PHASE_MIN_MS
    private var isAwake = true
    private var isForeground = false
    private var isOnline = false
    private var paused = false

    init {
        scope.launch { runGuarded() }
    }

    private suspend fun runGuarded() {
        try {
            store.awaitReady()
            coroutineScope {
                sportsOn = store.flow(SportsDomain).value.enabled
                pollPhaseMs = phaseOf(store.flow(SportsFollowDomain).value.pollPhaseMs)
                isAwake = awake.awake.value
                isForeground = signals.foreground.value
                isOnline = signals.online.value
                updateGate()
                launch { store.flow(SportsDomain).collect { post(Input.Sports(it.enabled)) } }
                launch { store.flow(SportsFollowDomain).collect { post(Input.Phase(it.pollPhaseMs)) } }
                launch { awake.awake.collect { post(Input.Awake(it)) } }
                launch { signals.foreground.collect { post(Input.Foreground(it)) } }
                launch { signals.online.collect { post(Input.Online(it)) } }
                loop(this)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            BoxLog.e(TAG, "detail loop stopped: ${t.javaClass.simpleName}", t)
            _detail.value = DetailState.None
            stopped = true
            val gone = Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs())
            releaseBoxWaiters(gone)
            while (true) (inputs.tryReceive().getOrNull() ?: break).let { if (it is Input.Box) it.answer.complete(gone) }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun loop(children: CoroutineScope) {
        while (true) {
            val now = mono.nowMs()
            val wake = nextWake(now)
            val input: Input? = if (wake == null) {
                inputs.receive()
            } else {
                select {
                    inputs.onReceive { it }
                    onTimeout((wake - now).coerceAtLeast(0L)) { null }
                }
            }
            if (input != null) apply(input)
            while (true) apply(inputs.tryReceive().getOrNull() ?: break)
            step(children)
        }
    }

    // ---- inputs

    private fun apply(i: Input) {
        when (i) {
            is Input.Show -> applyShow(i.game)
            is Input.Pause -> paused = i.on
            Input.Retry -> if (!refused()) {
                failures = 0
                lastFailMono = null
                retryAfterS = null
                dwellUntil = null
                force = true
            }
            Input.Trim -> {
                val keep = shown?.id?.let(::key)
                cache.keys.retainAll { it == keep }
                endedAt.keys.retainAll { it == keep }
                seenUnfinished.retainAll { it == keep }
                lastUnfinishedAt.keys.retainAll { it == keep }
                if (boxSlot?.first != keep) boxSlot = null
                onTrim()
            }
            is Input.Sports -> {
                sportsOn = i.on
                if (!i.on) cancelInFlight()
            }
            is Input.Phase -> pollPhaseMs = phaseOf(i.ms)
            is Input.Awake -> isAwake = i.on
            is Input.Foreground -> isForeground = i.on
            is Input.Online -> isOnline = i.on
            is Input.Fetched -> if (i.gen == gen) fetched(i.id, i.answer)
            is Input.Box -> box(i.id, i.answer)
        }
        updateGate()
    }

    private fun applyShow(game: GameSnapshot?) {
        val was = shown
        shown = game
        if (game != null) noteEnd(game)
        // (NFL hub §20) The board's highlight joins the catalog the resolver plays from (a newer snapshot of the shown game
        // comes here too: the view model forwards every change of its board media).
        if (game != null && game.id.league == League.NFL && espnVideo) game.media?.highlight?.let(onBoardHighlight)
        if (game?.id == was?.id) {
            // The same game again: a newer board snapshot (its phase), or OK entering the panel during the rest.
            if (game != null && dwellUntil != null) dwellUntil = null
            return
        }
        cancelInFlight()
        failures = 0
        lastFailMono = null
        retryAfterS = null
        lastFailKind = null
        force = false
        lagTries = 0
        dwellUntil = if (game != null && isDetailed(game.id.league)) mono.nowMs() + dwellMs else null
    }

    private fun cancelInFlight() {
        inFlight?.cancel()
        inFlight = null
        gen++
        // (NFL hub §5.5) A box-score caller waits for an answer that will not come now (another game, Sports off).
        releaseBoxWaiters(Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs()))
    }

    // ---- the box score (NFL hub §5.5, arch M4)

    private fun box(id: GameId, answer: CompletableDeferred<Fetch<BoxScore>>) {
        val game = shown
        val now = mono.nowMs()
        if (game == null || game.id != id || !gate().open) {
            answer.complete(Fetch.Failed(FailKind.HTTP_OTHER, null, now))
            return
        }
        val k = key(id)
        // (CHL hub §4.1) The CHL box score exists only in the final's parse: never a request for it before the game.
        if (isChl(id.league) && phase(game, cache[k]) != GamePhase.FINAL) {
            answer.complete(Fetch.Failed(FailKind.HTTP_OTHER, null, now))
            return
        }
        val slot = boxSlot?.takeIf { it.first == k }?.second
        if (slot != null && boxFresh(game, slot, now)) {
            answer.complete(slot)
            return
        }
        // Back-off always wins: no forced request while failing; the sheet shows its Retry row.
        if (failures > 0) {
            answer.complete(Fetch.Failed(lastFailKind ?: FailKind.HTTP_OTHER, retryAfterS, now))
            return
        }
        boxWaiters += answer
        if (inFlight == null) {
            dwellUntil = null
            force = true
        } // else: the parse in flight answers it
    }

    /** A slot answers while no older than the phase's interval; a final's (or a phase without one) at any age. */
    private fun boxFresh(game: GameSnapshot, slot: Fetch.Ok<BoxScore>, now: Long): Boolean {
        val entry = cache[key(game.id)]
        val phase = phase(game, entry)
        if (phase == GamePhase.FINAL) return true
        val interval = DetailPollPolicy.intervalMs(cadence(game.id.league), phase, finalFacts(game, entry)) ?: return true
        return now - slot.receivedMono <= interval
    }

    private fun releaseBoxWaiters(f: Fetch<BoxScore>) {
        if (boxWaiters.isEmpty()) return
        val waiting = boxWaiters.toList()
        boxWaiters.clear()
        for (w in waiting) w.complete(f)
    }

    private fun fetched(id: GameId, a: DetailAnswer) {
        inFlight = null
        val k = key(id)
        when (val f = a.fetch) {
            is Fetch.Ok -> {
                cache[k] = Entry(if (a.landingFailed) keepLanding(f, cache[k]) else f, a.landingFailed, a.partial)
                failures = 0
                lastFailMono = null
                retryAfterS = null
                lastFailKind = null
                if (f.value.fetchedPhase != GamePhase.FINAL) {
                    seenUnfinished += k
                    if (id.league == League.NFL) lastUnfinishedAt[k] = wallNow()
                }
                shown?.let(::noteEnd)
                val g = shown
                lagTries = if (g != null && g.id == id && DetailPollPolicy.lagging(phase(g, cache[k]), f.value.fetchedPhase)) lagTries + 1 else 0
            }
            Fetch.NotFound -> failed(FailKind.HTTP_OTHER, mono.nowMs(), null)
            is Fetch.Failed -> failed(f.kind, f.receivedMono, f.retryAfterS)
        }
        // (NFL hub §5.5) The box from the same parse: the slot, and every caller waiting for it.
        a.box?.let { boxSlot = k to it }
        releaseBoxWaiters(
            a.box ?: when (val f = a.fetch) {
                is Fetch.Failed -> f
                Fetch.NotFound -> Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs())
                is Fetch.Ok -> Fetch.Failed(FailKind.BAD_BODY, null, f.receivedMono)
            },
        )
    }

    /**
     * (Sports Hub code review 2026-11-25, L4) A refetch whose landing call failed keeps the landing facts of the cached
     * answer (goals, stars, leaders, goalies, venue, broadcasts, records) instead of emptying them until the next poll;
     * the entry is still `landingFailed`, so the panel shows `failing`. The landing scores are not carried: they would
     * pass for the newer answer's under the newest-`Date` score rule (§4.3), so the board's scores are used meanwhile.
     */
    private fun keepLanding(f: Fetch.Ok<GameDetail>, old: Entry?): Fetch.Ok<GameDetail> {
        val prev = old?.ok?.value ?: return f
        val now = f.value
        val season = now.season?.let { s ->
            s.copy(awayRecord = s.awayRecord ?: prev.season?.awayRecord, homeRecord = s.homeRecord ?: prev.season?.homeRecord)
        } ?: prev.season
        return f.copy(
            value = now.copy(
                venue = now.venue ?: prev.venue,
                season = season,
                goals = now.goals.ifEmpty { prev.goals },
                stars = now.stars.ifEmpty { prev.stars },
                leaders = now.leaders.ifEmpty { prev.leaders },
                goalies = now.goalies ?: prev.goalies,
                broadcasts = now.broadcasts.ifEmpty { prev.broadcasts },
            ),
        )
    }

    private fun failed(kind: FailKind, atMono: Long, retryAfter: Int?) {
        failures++
        lastFailMono = atMono
        retryAfterS = retryAfter
        lastFailKind = kind
        jitter = random.nextDouble(-1.0, 1.0)
    }

    // ---- the step

    private fun gate(): DetailGate = DetailGate(sportsOn, isAwake, isForeground, isOnline, paused)

    private fun isDetailed(league: League): Boolean = detailed(league, chlHub)

    /**
     * The shown game gets no detail request: a league without a source, or (CHL hub D5, §2.5) a CHL game whose effective
     * phase is LIVE, INTERMISSION or POSTPONED (the live read is a later step behind `CHL_HUB_LIVE_ENABLED`, §7, with
     * its own source branch). Checked before the policy, so a postponed game's first-fetch rule never applies.
     */
    private fun boardOnly(game: GameSnapshot): Boolean {
        if (!isDetailed(game.id.league)) return true
        if (!isChl(game.id.league)) return false
        return when (phase(game, cache[key(game.id)])) {
            GamePhase.LIVE, GamePhase.INTERMISSION, GamePhase.POSTPONED -> true
            GamePhase.SCHEDULED, GamePhase.PRE, GamePhase.FINAL -> false
        }
    }

    private fun updateGate() {
        gateSnapshot = gate()
    }

    private fun refused(): Boolean = lastFailKind == FailKind.HTTP_429 || lastFailKind == FailKind.REFUSED

    private fun step(children: CoroutineScope) {
        val game = shown
        if (game == null) {
            cancelInFlight()
            _detail.value = DetailState.None
            return
        }
        if (boardOnly(game)) {
            // (CHL hub D5) A CHL game that went live (or postponed) while a pre-game read was in flight: dropped.
            if (inFlight != null) cancelInFlight()
            _detail.value = DetailState.BoardOnly(game.id)
            return
        }
        val now = mono.nowMs()
        if (inFlight == null && fetchDue(game, now)) launchFetch(children, game)
        // (NFL hub §5.5) A box-score caller never waits on a fetch that cannot start (the gate closed meanwhile).
        if (inFlight == null && boxWaiters.isNotEmpty() && !gate().open) releaseBoxWaiters(Fetch.Failed(FailKind.HTTP_OTHER, null, now))
        publish(game, now)
    }

    private fun fetchDue(game: GameSnapshot, now: Long): Boolean {
        val g = gate()
        if (!g.open) return false
        val rest = dwellUntil
        if (rest != null) {
            if (now < rest) return false
            dwellUntil = null
        }
        if (force) return true
        val at = policyNextAt(game, now) ?: return false
        return at <= now
    }

    private fun policyNextAt(game: GameSnapshot, now: Long): Long? {
        val entry = cache[key(game.id)]
        val phase = phase(game, entry)
        val at = DetailPollPolicy.nextAt(
            cadence(game.id.league), gate(), entry?.ok, phase, entry?.ok?.value?.fetchedPhase, finalFacts(game, entry), failures, lastFailMono,
            retryAfterS, refused(), pollPhaseMs, jitter, now, lagTries,
        )
        // (CHL hub §4.1) A partial answer re-reads at min(normal next, received + HUB_CHL_PARTIAL_RETRY_MS); back-off
        // (failures > 0) and the closed gate (at == null) still win.
        if (entry == null || !entry.partial || failures > 0 || !gate().open) return at
        val retry = entry.ok.receivedMono + SportsConstants.HUB_CHL_PARTIAL_RETRY_MS
        return if (at == null) retry else minOf(at, retry)
    }

    private fun launchFetch(children: CoroutineScope, game: GameSnapshot) {
        force = false
        val myGen = ++gen
        val id = game.id
        // (CHL hub C0 ruling R6) The CHL source is told the effective phase (board ∨ cached detail); the NHL and NFL keep
        // the board's.
        val hint = if (isChl(id.league)) phase(game, cache[key(id)]) else game.phase
        inFlight = children.launch {
            val answer = try {
                fetcher.detail(id, hint)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                BoxLog.w(TAG, "detail failed: ${e.javaClass.simpleName}")
                DetailAnswer(Fetch.Failed(FailKind.BAD_BODY, null, mono.nowMs()), false)
            }
            inputs.trySend(Input.Fetched(myGen, id, answer))
        }
    }

    private fun publish(game: GameSnapshot, now: Long) {
        val entry = cache[key(game.id)]
        val kind = lastFailKind
        _detail.value = when {
            entry != null -> {
                val phase = phase(game, entry)
                DetailState.Ready(
                    detail = published(game, entry.ok.value),
                    fetchedAtMono = entry.ok.receivedMono,
                    failing = failures > 0 || entry.landingFailed || !isOnline,
                    stale = DetailPollPolicy.isStale(cadence(game.id.league), entry.ok.receivedMono, phase, finalFacts(game, entry), now),
                    partial = entry.partial,
                )
            }
            !isOnline -> DetailState.Failed(game.id, FailKind.OFFLINE)
            kind != null && failures > 0 -> DetailState.Failed(game.id, kind)
            else -> DetailState.Loading(game.id)
        }
    }

    /**
     * (NFL hub §20) What the panel is given: the NFL detail's clips ∪ the board's highlight (`NflHighlight.clips`), so a
     * highlight ESPN lists only on the scoreboard is in the shown game's one clip list. The cache keeps the summary's own.
     */
    private fun published(game: GameSnapshot, d: GameDetail): GameDetail {
        if (game.id.league != League.NFL || !espnVideo) return d
        val merged = NflHighlight.clips(d.clips, game.media?.highlight?.clip)
        return if (merged === d.clips) d else d.copy(clips = merged)
    }

    private fun nextWake(now: Long): Long? {
        val game = shown ?: return null
        if (boardOnly(game)) return null
        val entry = cache[key(game.id)]
        val stale = entry?.let {
            val phase = phase(game, it)
            DetailPollPolicy.staleAt(cadence(game.id.league), it.ok.receivedMono, phase, finalFacts(game, it))?.takeIf { at -> at > now }
        }
        if (inFlight != null || !gate().open) return stale
        val fetch = when {
            dwellUntil != null -> dwellUntil
            force -> now
            else -> policyNextAt(game, now)
        }
        return listOfNotNull(fetch, stale).minOrNull()
    }

    // ---- facts for the policy

    private fun phase(game: GameSnapshot, entry: Entry?): GamePhase =
        DetailPollPolicy.effectivePhase(game.phase, entry?.ok?.value?.fetchedPhase)

    /**
     * The NHL: a recap id in the detail or on the board. (NFL hub §4.3, §5.2, §20) The NFL: the highlight the UI offers,
     * `NflHighlight.offered` over the detail's clips ∪ the board's highlight at `now + skew` (an expired highlight is not
     * "posted"; the NFL detail never sets `recapId`). A highlight on the board alone counts, so the final wait never
     * re-reads the summary for a highlight the summary may never list. [game] is the newest board snapshot: the view
     * model forwards every change of the shown game's board media ([show]).
     */
    private fun recapPosted(game: GameSnapshot, entry: Entry?): Boolean {
        val d = entry?.ok?.value
        // (CHL hub §4.2) The CHL's final wait is for the three stars (no video feed).
        if (isChl(game.id.league)) return d?.stars?.isNotEmpty() == true
        if (game.id.league != League.NFL) return d?.recapId != null || game.media?.recapId != null
        // (Code review 2026-11-26, data #6) The kill switch: the app offers no ESPN video, so there is nothing to wait for.
        if (!espnVideo) return true
        val at = wallNow() + (d?.clockSkewMs ?: 0L)
        return NflHighlight.offered(game, d, at) != null
    }

    /** The policy's FINAL facts; (NFL hub §5.2) [FinalFacts.playClips]: the NFL detail lists a PLAY clip. */
    private fun finalFacts(game: GameSnapshot, entry: Entry?): FinalFacts = FinalFacts(
        recapPosted(game, entry),
        playClips = (game.id.league != League.NFL || espnVideo) && entry?.ok?.value?.clips?.any { it.kind == ClipKind.PLAY } == true,
        endedAgoMs = endedAgoMs(game),
    )

    /** Remembers when a game seen unfinished in this process turned FINAL (board or detail). */
    private fun noteEnd(game: GameSnapshot) {
        val id = key(game.id)
        val detailPhase = cache[id]?.ok?.value?.fetchedPhase
        val final = game.phase == GamePhase.FINAL || detailPhase == GamePhase.FINAL
        if (!final) {
            seenUnfinished += id
            if (game.id.league == League.NFL) lastUnfinishedAt[id] = wallNow()
        } else if (id in seenUnfinished && id !in endedAt) {
            endedAt[id] = endInstant(game, id)
        }
    }

    /**
     * When the game seen turning FINAL ended. The NHL (unchanged): now. (NFL hub code review 2026-11-26, data #7) The
     * NFL: now, unless the last unfinished sighting is more than `HUB_NFL_END_SIGHTING_GAP_MS` old (the viewer left the
     * page during the game); then the start + game-length estimate, kept between that sighting and now.
     */
    private fun endInstant(game: GameSnapshot, id: String): Long {
        val now = wallNow()
        if (game.id.league != League.NFL) return now
        val last = lastUnfinishedAt[id] ?: return now
        if (now - last <= SportsConstants.HUB_NFL_END_SIGHTING_GAP_MS) return now
        val estimate = game.startUtcMs.takeIf { it > 0 }?.plus(cadence(League.NFL).gameLengthMs) ?: return now
        return estimate.coerceIn(last, now)
    }

    /**
     * How long ago the game ended (the recap wait, §4.3): from the moment this process saw it turn FINAL, else estimated
     * as the listed start + the league's `gameLengthMs` (the NHL's [GAME_LENGTH_MS]; the NFL's 3 h 15 min) for a game first
     * seen already final. Null without a usable start.
     */
    private fun endedAgoMs(game: GameSnapshot): Long? {
        val length = cadence(game.id.league).gameLengthMs
        val end = endedAt[key(game.id)] ?: game.startUtcMs.takeIf { it > 0 }?.plus(length) ?: return null
        return (wallNow() - end).coerceAtLeast(0L)
    }

    companion object {
        private const val TAG = "GameDetail"
        /** A regulation NHL game's usual length, start to final horn, for a game first seen already final. */
        internal val GAME_LENGTH_MS = DetailCadence.NHL.gameLengthMs

        /**
         * (NFL hub §5.2) The leagues with a detail source; every other league is BoardOnly. (CHL hub P2) The CHL leagues
         * too while [chlHub] (`SportsConstants.CHL_HUB_ENABLED`) is on.
         */
        internal fun detailed(league: League, chlHub: Boolean = SportsConstants.CHL_HUB_ENABLED): Boolean =
            league == League.NHL || league == League.NFL || (chlHub && isChl(league))

        internal fun isChl(league: League): Boolean = league == League.OHL || league == League.WHL || league == League.QMJHL

        /** (NFL hub §5.2) The league's detail cadence; (CHL hub §4.2) [DetailCadence.CHL] for OHL, WHL, QMJHL. */
        internal fun cadence(league: League): DetailCadence = when (league) {
            League.NFL -> DetailCadence.NFL
            League.OHL, League.WHL, League.QMJHL -> DetailCadence.CHL
            League.NHL -> DetailCadence.NHL
        }

        /** (NFL hub §5.2, arch m8) The key of every per-game map: an NHL and an ESPN id never collide. */
        internal fun key(id: GameId): String = "${id.league}:${id.id}"

        private fun phaseOf(ms: Long?): Long =
            ms?.takeIf { it in SportsConstants.POLL_PHASE_MIN_MS..SportsConstants.POLL_PHASE_MAX_MS } ?: SportsConstants.POLL_PHASE_MIN_MS
    }
}
