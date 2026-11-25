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
import app.thebox.core.sports.hub.DetailPollPolicy
import app.thebox.core.sports.hub.DetailPollPolicy.DetailGate
import app.thebox.core.sports.hub.DetailState
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.GameDetailRepository
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

/** The repository's view of `NhlDetailSource` (a seam for the loop's tests). */
internal interface DetailFetcher {
    suspend fun detail(id: GameId, phaseHint: GamePhase?): NhlDetailSource.DetailAnswer
    suspend fun boxScore(id: GameId): Fetch<BoxScore>

    companion object {
        fun of(source: NhlDetailSource): DetailFetcher = object : DetailFetcher {
            override suspend fun detail(id: GameId, phaseHint: GamePhase?) = source.detailAnswer(id, phaseHint)
            override suspend fun boxScore(id: GameId): Fetch<BoxScore> = source.boxScore(id)
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
 * - NFL and CHL games are [DetailState.BoardOnly] without a request.
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
) : GameDetailRepository {
    @Inject constructor(
        source: NhlDetailSource,
        store: SettingsStore,
        awake: Optional<DeviceAwake>,
        signals: SportsSignals,
        @ApplicationScope scope: CoroutineScope,
        wall: WallClock,
    ) : this(DetailFetcher.of(source), store, awake.orElse(DeviceAwake.ALWAYS), signals, scope, ScoreRepositoryImpl.ELAPSED, wall::now)

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
        data class Fetched(val gen: Int, val id: GameId, val answer: NhlDetailSource.DetailAnswer) : Input
    }

    private val inputs = Channel<Input>(Channel.UNLIMITED)
    private val _detail = MutableStateFlow<DetailState>(DetailState.None)
    override val detail: StateFlow<DetailState> = _detail.asStateFlow()

    override fun show(game: GameSnapshot?) = post(Input.Show(game))
    override fun pause(paused: Boolean) = post(Input.Pause(paused))
    override fun retry() = post(Input.Retry)
    override fun trim() = post(Input.Trim)

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
        if (!g.open || id.league != League.NHL) return Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs())
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

    private class Entry(val ok: Fetch.Ok<GameDetail>, val landingFailed: Boolean)

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
                val keep = shown?.id?.id
                cache.keys.retainAll { it == keep }
                endedAt.keys.retainAll { it == keep }
                seenUnfinished.retainAll { it == keep }
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
        }
        updateGate()
    }

    private fun applyShow(game: GameSnapshot?) {
        val was = shown
        shown = game
        if (game != null) noteEnd(game)
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
        dwellUntil = if (game != null && game.id.league == League.NHL) mono.nowMs() + dwellMs else null
    }

    private fun cancelInFlight() {
        inFlight?.cancel()
        inFlight = null
        gen++
    }

    private fun fetched(id: GameId, a: NhlDetailSource.DetailAnswer) {
        inFlight = null
        when (val f = a.fetch) {
            is Fetch.Ok -> {
                cache[id.id] = Entry(if (a.landingFailed) keepLanding(f, cache[id.id]) else f, a.landingFailed)
                failures = 0
                lastFailMono = null
                retryAfterS = null
                lastFailKind = null
                if (f.value.fetchedPhase != GamePhase.FINAL) seenUnfinished += id.id
                shown?.let(::noteEnd)
            }
            Fetch.NotFound -> failed(FailKind.HTTP_OTHER, mono.nowMs(), null)
            is Fetch.Failed -> failed(f.kind, f.receivedMono, f.retryAfterS)
        }
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
        if (game.id.league != League.NHL) {
            _detail.value = DetailState.BoardOnly(game.id)
            return
        }
        val now = mono.nowMs()
        if (inFlight == null && fetchDue(game, now)) launchFetch(children, game)
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
        val entry = cache[game.id.id]
        val phase = phase(game, entry)
        return DetailPollPolicy.nextAt(
            gate(), entry?.ok, phase, recapPosted(game, entry), endedAgoMs(game), failures, lastFailMono, retryAfterS,
            refused(), pollPhaseMs, jitter, now,
        )
    }

    private fun launchFetch(children: CoroutineScope, game: GameSnapshot) {
        force = false
        val myGen = ++gen
        val id = game.id
        val hint = game.phase
        inFlight = children.launch {
            val answer = try {
                fetcher.detail(id, hint)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                BoxLog.w(TAG, "detail failed: ${e.javaClass.simpleName}")
                NhlDetailSource.DetailAnswer(Fetch.Failed(FailKind.BAD_BODY, null, mono.nowMs()), false)
            }
            inputs.trySend(Input.Fetched(myGen, id, answer))
        }
    }

    private fun publish(game: GameSnapshot, now: Long) {
        val entry = cache[game.id.id]
        val kind = lastFailKind
        _detail.value = when {
            entry != null -> {
                val phase = phase(game, entry)
                DetailState.Ready(
                    detail = entry.ok.value,
                    fetchedAtMono = entry.ok.receivedMono,
                    failing = failures > 0 || entry.landingFailed || !isOnline,
                    stale = DetailPollPolicy.isStale(entry.ok.receivedMono, phase, recapPosted(game, entry), endedAgoMs(game), now),
                )
            }
            !isOnline -> DetailState.Failed(game.id, FailKind.OFFLINE)
            kind != null && failures > 0 -> DetailState.Failed(game.id, kind)
            else -> DetailState.Loading(game.id)
        }
    }

    private fun nextWake(now: Long): Long? {
        val game = shown ?: return null
        if (game.id.league != League.NHL) return null
        val entry = cache[game.id.id]
        val stale = entry?.let {
            val phase = phase(game, it)
            DetailPollPolicy.staleAt(it.ok.receivedMono, phase, recapPosted(game, it), endedAgoMs(game))?.takeIf { at -> at > now }
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

    private fun recapPosted(game: GameSnapshot, entry: Entry?): Boolean =
        entry?.ok?.value?.recapId != null || game.media?.recapId != null

    /** Remembers when a game seen unfinished in this process turned FINAL (board or detail). */
    private fun noteEnd(game: GameSnapshot) {
        val id = game.id.id
        val detailPhase = cache[id]?.ok?.value?.fetchedPhase
        val final = game.phase == GamePhase.FINAL || detailPhase == GamePhase.FINAL
        if (!final) {
            seenUnfinished += id
        } else if (id in seenUnfinished && id !in endedAt) {
            endedAt[id] = wallNow()
        }
    }

    /**
     * How long ago the game ended (the recap wait, §4.3): from the moment this process saw it turn FINAL, else estimated
     * as the listed start + [GAME_LENGTH_MS] (a game first seen already final). Null without a usable start.
     */
    private fun endedAgoMs(game: GameSnapshot): Long? {
        val end = endedAt[game.id.id] ?: game.startUtcMs.takeIf { it > 0 }?.plus(GAME_LENGTH_MS) ?: return null
        return (wallNow() - end).coerceAtLeast(0L)
    }

    companion object {
        private const val TAG = "GameDetail"
        /** A regulation NHL game's usual length, start to final horn, for a game first seen already final. */
        internal const val GAME_LENGTH_MS = 9_000_000L

        private fun phaseOf(ms: Long?): Long =
            ms?.takeIf { it in SportsConstants.POLL_PHASE_MIN_MS..SportsConstants.POLL_PHASE_MAX_MS } ?: SportsConstants.POLL_PHASE_MIN_MS
    }
}
