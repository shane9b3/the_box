package app.thebox.data.sports

import app.thebox.core.common.BoxLog
import app.thebox.core.playback.MonoClock
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.state.SportsDomain
import app.thebox.core.settings.state.SportsFollowDomain
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.DayPollPolicy
import app.thebox.core.sports.DayState
import app.thebox.core.sports.Demand
import app.thebox.core.sports.DemandOwner
import app.thebox.core.sports.DeviceAwake
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.FollowedGame
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.LeagueGroup
import app.thebox.core.sports.PollTiming
import app.thebox.core.sports.group
import app.thebox.core.sports.timing
import app.thebox.core.sports.PollMeasure
import app.thebox.core.sports.PollMeter
import app.thebox.core.sports.RemoveReason
import app.thebox.core.sports.ScoreBugMachine
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.ScoreEvent
import app.thebox.core.sports.ScoreRepository
import app.thebox.core.sports.ScoreSource
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.SportsFollowDoc
import app.thebox.core.sports.SportsPrefs
import app.thebox.data.di.ApplicationScope
import app.thebox.data.di.WallClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * Platform facts the repository needs besides [DeviceAwake] (docs/design/m6-score-bug.md §2.3, §2.4): [foreground] is the
 * process at least STARTED (`ProcessLifecycleOwner`); [online] is "a default network exists" (the default-network
 * callback, as in `SmbSessionKeeper`; `VALIDATED` is not required, A-S12). Android binding: `AndroidSportsSignals`.
 */
interface SportsSignals {
    val foreground: StateFlow<Boolean>
    val online: StateFlow<Boolean>
}

/**
 * The followed game's machine as the actor drives it: [ScoreBugMachine]'s surface (§2.7), as an interface so the loop is
 * tested on its own. Production wraps the real machine ([of]).
 */
internal interface FollowMachine {
    fun follow(game: FollowedGame?)
    fun prefs(delaySec: Int)
    fun demand(d: Demand)
    fun online(on: Boolean)
    fun fetched(f: Fetch<GameSnapshot>)
    fun step()
    fun pollDue(): Boolean
    fun nextWakeAtMono(): Long?
    fun view(): ScoreBugView?
    /** The last known view while the bug is hidden for want of updates (U-S4); null before LIVE. */
    fun knownView(): ScoreBugView?
    fun followed(): FollowedGame?
    fun drainEvents(): List<ScoreEvent>
    /**
     * (Update interval §3.5) The followed league's live interval in ms (null = today's timing). A default no-op so the
     * loop's fakes compile; [of] forwards it (review B2: production must never silently drop it).
     */
    fun interval(liveMs: Long?) {}

    companion object {
        fun of(m: ScoreBugMachine): FollowMachine = object : FollowMachine {
            override fun follow(game: FollowedGame?) = m.follow(game)
            override fun prefs(delaySec: Int) = m.prefs(delaySec)
            override fun demand(d: Demand) = m.demand(d)
            override fun online(on: Boolean) = m.online(on)
            override fun fetched(f: Fetch<GameSnapshot>) = m.fetched(f)
            override fun step() = m.step()
            override fun pollDue(): Boolean = m.pollDue()
            override fun nextWakeAtMono(): Long? = m.nextWakeAtMono()
            override fun view(): ScoreBugView? = m.view()
            override fun knownView(): ScoreBugView? = m.knownView()
            override fun followed(): FollowedGame? = m.followed()
            override fun drainEvents(): List<ScoreEvent> = m.drainEvents()
            override fun interval(liveMs: Long?) = m.interval(liveMs)
        }
    }
}

/** [DayPollPolicy.nextAt]'s signature, as a seam for the loop's tests. */
internal fun interface DayPolicy {
    fun nextAt(
        lastOk: Fetch.Ok<DayBoard>?, demand: Demand, failures: Int, lastFailMono: Long?, retryAfterS: Int?,
        pollPhaseMs: Long, jitter: Double, nowMono: Long,
    ): Long?

    companion object {
        val REAL = DayPolicy(DayPollPolicy::nextAt)
    }
}

/**
 * The one [ScoreRepository] (docs/design/m6-score-bug.md §2.3–§2.7; WP-2): a single actor on `@ApplicationScope` that
 * serialises every input (A-S8) and owns the [FollowMachine], the day list and the `sports_follow` document.
 *
 * The loop:
 * ```
 * for (;;) {
 *     select { inputs.onReceive { apply(it) }; onTimeout(min(machine.nextWakeAtMono(), day next) − now) { } }
 *     machine.step()
 *     if (machine.pollDue()) machine.fetched(source.game(id))
 *     if (day due) fetch the day list
 *     publish(view, events, followed)
 * }
 * ```
 * Both fetches run inline in the actor (A-S8), so an input posted during a stalled request (Stop following, Show Sports
 * off, `Awake(false)`) waits for it: at most `SPORTS_CALL_TIMEOUT_MS` per request (C-N3, accepted).
 * With no wake-up pending it waits on inputs only, so nothing is sent while nothing is followed and no Sports UI is open,
 * while the process is not STARTED, while the device is not awake (A-S5), while offline, and while Show Sports is off.
 *
 * - **Demand** (§2.3): `NONE` unless Sports is on, the process is STARTED and the device awake. The followed game is
 *   `SEEN` when a followed game meets an owner (FULL, the tab, the picker), else `UNSEEN`. The day list runs only while
 *   the tab or the picker is open; `/score/now` once per open, then the dated `currentDate` (A-N10), paced by
 *   [DayPollPolicy] with the §2.4 back-off.
 * - **Follow** (§2.6): persisted at once; `Followed` / `FollowedFinal` events. The machine's removals and expiry (two
 *   404s, postponed, final + 5 min, 8 h) come back through [FollowMachine.followed] and [FollowMachine.drainEvents] and
 *   are persisted here. Show Sports turning off ends a follow (`Removed(SPORTS_OFF)`).
 * - `POLL_PHASE_MS` is drawn once per install into `SportsFollowDoc.pollPhaseMs`.
 * - A failure inside the loop (a machine bug) stops the feature with a log line; it never takes the app down.
 */
@Singleton
class ScoreRepositoryImpl internal constructor(
    /** (NFL §7.1, S7) One source per league; the day list uses `prefs.league`'s, the follow its own league's. */
    private val sources: Map<League, ScoreSource>,
    private val store: SettingsStore,
    private val awake: DeviceAwake,
    private val signals: SportsSignals,
    scope: CoroutineScope,
    private val mono: MonoClock,
    private val machineFactory: (pollPhaseMs: Long) -> FollowMachine,
    private val dayPolicy: DayPolicy = DayPolicy.REAL,
    private val random: Random = Random.Default,
) : ScoreRepository {
    @Inject constructor(
        sources: Map<League, @JvmSuppressWildcards ScoreSource>,
        store: SettingsStore,
        awake: Optional<DeviceAwake>,
        signals: SportsSignals,
        @ApplicationScope scope: CoroutineScope,
        wall: WallClock,
    ) : this(
        sources, store, awake.orElse(DeviceAwake.ALWAYS), signals, scope, ELAPSED,
        machineFactory = { phase -> FollowMachine.of(ScoreBugMachine(ELAPSED, wall::now, phase)) },
    )

    /** (NFL §2, S7) One source: `mapOf(source.league to source)`, so the single-league call sites compile unchanged. */
    internal constructor(
        source: ScoreSource,
        store: SettingsStore,
        awake: DeviceAwake,
        signals: SportsSignals,
        scope: CoroutineScope,
        mono: MonoClock,
        machineFactory: (pollPhaseMs: Long) -> FollowMachine,
        dayPolicy: DayPolicy = DayPolicy.REAL,
        random: Random = Random.Default,
    ) : this(mapOf(source.league to source), store, awake, signals, scope, mono, machineFactory, dayPolicy, random)

    /**
     * [league]'s source, or null. (CHL §1.6 #35) **No silent fallback**: a league with no source never reads another
     * league's source (a WHL game id sent to the OHL's, or the NHL's). The caller answers `Failed(HTTP_OTHER)`, and the
     * missing binding is logged once per league.
     */
    private fun sourceOf(league: League): ScoreSource? = sources[league]
        ?: null.also { if (unbound.add(league)) BoxLog.w(TAG, "no score source bound for $league") }

    /** Leagues already logged as unbound (#35). */
    private val unbound = HashSet<League>()

    /** (#35) The answer when [league] has no source: a plain failure, so the usual back-off and texts apply. */
    private fun unboundFailure(): Fetch.Failed = Fetch.Failed(FailKind.HTTP_OTHER, null, mono.nowMs())

    private sealed interface Input {
        data class Follow(val game: GameSnapshot?) : Input
        data class DemandReport(val owner: DemandOwner, val on: Boolean) : Input
        data class Prefs(val prefs: SportsPrefs) : Input
        data class Awake(val on: Boolean) : Input
        data class Foreground(val on: Boolean) : Input
        data class Online(val on: Boolean) : Input
        data class ShowDay(val dayKey: String?) : Input
        data class ScreenOff(val on: Boolean) : Input
        data object Retry : Input
    }

    private val inputs = Channel<Input>(Channel.UNLIMITED)

    private val _bug = MutableStateFlow<ScoreBugView?>(null)
    private val _lastScore = MutableStateFlow<ScoreBugView?>(null)
    private val _day = MutableStateFlow<DayState>(DayState.Loading)
    private val _followed = MutableStateFlow<FollowedGame?>(null)
    private val _events = MutableSharedFlow<ScoreEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val bug: StateFlow<ScoreBugView?> = _bug.asStateFlow()
    override val lastScore: StateFlow<ScoreBugView?> = _lastScore.asStateFlow()
    override val prefs: StateFlow<SportsPrefs> = store.flow(SportsDomain)
    override val day: StateFlow<DayState> = _day.asStateFlow()
    override val followed: StateFlow<FollowedGame?> = _followed.asStateFlow()
    override val events: Flow<ScoreEvent> = _events.asSharedFlow()

    override fun reportDemand(owner: DemandOwner, on: Boolean) = post(Input.DemandReport(owner, on))
    override fun reportScreenOff(on: Boolean) = post(Input.ScreenOff(on))
    override fun follow(game: GameSnapshot?) = post(Input.Follow(game))
    override fun showDay(dayKey: String?) = post(Input.ShowDay(dayKey))
    override fun retry() = post(Input.Retry)

    private fun post(i: Input) {
        inputs.trySend(i)
    }

    // ---- actor state (touched only inside the loop)

    private lateinit var machine: FollowMachine
    private var doc = SportsFollowDoc()
    private var phaseMs = SportsConstants.POLL_PHASE_MIN_MS
    private var sports = SportsPrefs()
    private val owners = HashSet<DemandOwner>()
    private var isAwake = true
    private var isForeground = false
    private var isOnline = false
    /** The player's screen-off layer (C-S5): demand NONE while on. */
    private var isScreenOff = false
    private var followDemand: Demand? = null
    private var machineOnline: Boolean? = null

    /** The day the UI asked for; null = today (`/score/now`). */
    private var dayWanted: String? = null
    /** The dated key being polled; null = resolve today with `/score/now` first (once per open, A-N10). */
    private var dayKey: String? = null
    private var dayOk: Fetch.Ok<DayBoard>? = null
    private var dayFailures = 0
    private var dayLastFailMono: Long? = null
    private var dayRetryAfterS: Int? = null
    private var dayJitter = 0.0
    private var dayForce = false
    private var dayDemand = Demand.NONE
    /** (CHL #37, S7) Per league: the earliest next day request after a `FailKind.REFUSED`; cleared by its next answer. */
    private val dayRefused = HashMap<League, Long>()
    /** The tab or the picker is open (whatever the platform facts): its rising edge resets the day to today (C-S6). */
    private var dayUiOpen = false

    // ---- the poll meter (update interval §3.2, §4.3)
    /** Per league; loaded from `SportsFollowDoc.meters`, written at most once a minute (at once on slowed/endpoint). */
    private val meters = HashMap<League, PollMeasure>()
    /** Leagues whose first sample of this process was skipped (cold DNS/TLS/class loading, review S6). */
    private val meterWarm = HashSet<League>()
    private var meterPersistAt: Long? = null
    private var metersDirty = false
    /** The interval last sent to the machine; [intervalSent] false = none yet. */
    private var intervalSentMs: Long? = null
    private var intervalSent = false

    /** When the machine was last stepped, so a wake-up that stays in the past never spins the loop. */
    private var lastStepAt: Long? = null
    private var holding = false

    init {
        scope.launch { runGuarded() }
    }

    private suspend fun runGuarded() {
        try {
            store.awaitReady()
            coroutineScope {
                start()
                launch { store.flow(SportsDomain).collect { post(Input.Prefs(it)) } }
                launch { awake.awake.collect { post(Input.Awake(it)) } }
                launch { signals.foreground.collect { post(Input.Foreground(it)) } }
                launch { signals.online.collect { post(Input.Online(it)) } }
                loop()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            BoxLog.e(TAG, "score loop stopped: ${t.javaClass.simpleName}", t)
            _bug.value = null
            _lastScore.value = null
        }
    }

    private fun start() {
        doc = store.flow(SportsFollowDomain).value
        meters.putAll(doc.meters)
        // (Fix pass 1) A new process's ESPN source starts every follow on per-event, so a board meter left by the last
        // process (a follow that ended, or the app stopped, on the fallback) is stale: back to a fresh per-event window.
        if (resetNflFallback(keepSlowed = true)) persistMeters(mono.nowMs())
        phaseMs = doc.pollPhaseMs?.takeIf { it in SportsConstants.POLL_PHASE_MIN_MS..SportsConstants.POLL_PHASE_MAX_MS }
            ?: (SportsConstants.POLL_PHASE_MIN_MS + random.nextLong(SportsConstants.POLL_PHASE_MAX_MS - SportsConstants.POLL_PHASE_MIN_MS + 1))
                .also { drawn -> persist(doc.copy(pollPhaseMs = drawn)) }
        sports = store.flow(SportsDomain).value
        isAwake = awake.awake.value
        isForeground = signals.foreground.value
        isOnline = signals.online.value
        machine = machineFactory(phaseMs)
        // A follow survives a restart (§2.6), unless Sports was turned off meanwhile (a restore): then it ends quietly.
        if (doc.game != null && !sports.enabled) persist(doc.copy(game = null))
        machine.prefs(sports.delaySec)
        pushInterval()
        machine.follow(doc.game)
        _followed.value = doc.game
        pushDemand()
        publish()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun loop() {
        while (true) {
            val now = mono.nowMs()
            var wake = minOfNullable(machine.nextWakeAtMono(), dayNextAt(now))
            val stepped = lastStepAt
            if (wake != null && stepped != null && wake <= stepped) {
                // Still due at or before the last step, so the step did not act on it (a machine bug, or a poll the
                // guard below refused): hold off rather than spin. Inputs still wake the loop at once.
                if (!holding) BoxLog.w(TAG, "wake-up not consumed by a step; holding off $STUCK_WAKE_HOLD_MS ms")
                holding = true
                wake = stepped + STUCK_WAKE_HOLD_MS
            } else {
                holding = false
            }
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

            machine.step()
            lastStepAt = mono.nowMs()
            val game = doc.game
            if (machine.pollDue() && game != null && followDemand != Demand.NONE && isOnline) {
                val source = sourceOf(game.league)
                val t0 = mono.nowMs()
                val f = source?.game(game.id, game.startUtcMs) ?: unboundFailure()
                machine.fetched(f)
                // The answer took time: advance the machine to now, so what the answer made due is applied at once.
                machine.step()
                // (Update interval §3.2, review S6) The work the bug needs: fetch, decode, map, the machine and its view.
                // The day list (fetchDay, below) is never inside a sample.
                machine.view()
                val workMs = mono.nowMs() - t0
                lastStepAt = mono.nowMs()
                if (source != null) measure(game, source.followEndpoint(game.id), f, workMs)
            }
            if (dayDue(mono.nowMs())) fetchDay()
            publish()
        }
    }

    // ---- inputs

    private fun apply(i: Input) {
        when (i) {
            is Input.Follow -> applyFollow(i.game)
            is Input.DemandReport -> if (i.on) owners += i.owner else owners -= i.owner
            is Input.Prefs -> applyPrefs(i.prefs)
            is Input.Awake -> isAwake = i.on
            is Input.Foreground -> isForeground = i.on
            is Input.Online -> isOnline = i.on
            is Input.ScreenOff -> isScreenOff = i.on
            is Input.ShowDay -> {
                dayWanted = i.dayKey
                dayKey = i.dayKey
                resetDay(loading = true)
            }
            // (CHL S7) A refused league is not asked again before its 300 s, whatever the user presses.
            Input.Retry -> if (sports.league !in dayRefused) {
                dayFailures = 0
                dayLastFailMono = null
                dayRetryAfterS = null
                dayForce = true
                if (_day.value is DayState.Failed) _day.value = DayState.Loading
            }
        }
        pushDemand()
    }

    private fun applyFollow(game: GameSnapshot?) {
        if (game == null) {
            if (doc.game == null) return
            machine.follow(null)
            persist(doc.copy(game = null))
            return
        }
        if (!sports.enabled) return
        val f = FollowedGame(game.id.league, game.id.id, game.startUtcMs, game.away.abbrev, game.home.abbrev)
        if (doc.game?.id == f.id) return
        val final = game.phase == GamePhase.FINAL
        val first = !doc.firstFollowShown
        machine.follow(f)
        persist(doc.copy(game = f, firstFollowShown = doc.firstFollowShown || !final))
        _events.tryEmit(if (final) ScoreEvent.FollowedFinal(f) else ScoreEvent.Followed(f, first))
    }

    private fun applyPrefs(p: SportsPrefs) {
        val was = sports
        sports = p
        if (p.delaySec != was.delaySec) machine.prefs(p.delaySec)
        // (CHL §1.6 #36) The CHL's one setting too; pushInterval reads the followed league's own.
        if (p.nhlIntervalS != was.nhlIntervalS || p.nflIntervalS != was.nflIntervalS || p.chlIntervalS != was.chlIntervalS) {
            pushInterval()
        }
        if (p.league != was.league) {
            // (NFL §7.1) The switch: the day list goes back to the new league's today (Loading, then its board). A day
            // key of the old league is never sent to the new source. The follow is untouched: it polls its own league.
            dayWanted = null
            dayKey = null
            resetDay(loading = true)
        }
        val game = doc.game
        if (!p.enabled && game != null) {
            machine.follow(null)
            persist(doc.copy(game = null))
            _events.tryEmit(ScoreEvent.Removed(RemoveReason.SPORTS_OFF, game))
        }
    }

    /** §2.3: the followed game's demand and the day list's, from the owners and the platform facts. */
    private fun pushDemand() {
        // C-S5: the screen-off layer is NONE (zero requests), like display off.
        val live = sports.enabled && isForeground && isAwake && !isScreenOff
        val fd = when {
            doc.game == null || !live -> Demand.NONE
            owners.isNotEmpty() -> Demand.SEEN
            else -> Demand.UNSEEN
        }
        // Online first, so a rise to SEEN in the same batch sees the current network (C-S2).
        if (isOnline != machineOnline) {
            machineOnline = isOnline
            machine.online(isOnline)
        }
        if (fd != followDemand) {
            followDemand = fd
            machine.demand(fd)
        }
        val uiOpen = DemandOwner.SPORTS_TAB in owners || DemandOwner.SCORE_PICKER in owners
        val dd = if (live && uiOpen) Demand.SEEN else Demand.NONE
        if (uiOpen && !dayUiOpen) {
            // A new open of the tab or the picker is always today: `/score/now` again (A-N10). A day the Sports tab
            // picked earlier never sticks (C-S6 / U-B1); today's board stays on screen meanwhile, another day's does not.
            val wasOtherDay = dayWanted != null || (_day.value as? DayState.Board)?.isToday == false
            dayWanted = null
            dayKey = null
            dayOk = null
            if (wasOtherDay) resetDay(loading = true)
        } else if (dd == Demand.SEEN && dayDemand == Demand.NONE && dayWanted == null) {
            // The same open coming back (awake, foreground): re-resolve today; a day the tab picked stays picked.
            dayKey = null
            dayOk = null
        }
        dayUiOpen = uiOpen
        dayDemand = dd
    }

    // ---- the day list

    private fun resetDay(loading: Boolean) {
        dayOk = null
        dayFailures = 0
        dayLastFailMono = null
        dayRetryAfterS = null
        if (loading) _day.value = DayState.Loading
    }

    private fun dayNextAt(now: Long): Long? {
        if (dayDemand == Demand.NONE) return null
        if (!isOnline) {
            markDayOffline()
            return null
        }
        // (CHL §1.6 #37, S7; lead ruling) REFUSED: the next day request of that league at `fail + max(BACKOFF_MAX_MS,
        // Retry-After)`, never sooner (no Retry, no day switch, no reopen of the tab brings it closer). A reset meanwhile
        // shows the refusal, not Loading.
        dayRefused[sports.league]?.let { nextAt ->
            if (_day.value == DayState.Loading) _day.value = DayState.Failed(FailKind.REFUSED)
            return nextAt
        }
        if (dayForce) return now
        val at = dayPolicy.nextAt(dayOk, dayDemand, dayFailures, dayLastFailMono, dayRetryAfterS, phaseMs, dayJitter, now)
        val ok = dayOk
        if (at != null || dayWanted != null || ok == null || dayFailures != 0) return at
        // Today's board is done (the policy has no next poll): forget the dated key, so the next fetch (the idle
        // recheck, or a Retry after it failed) re-resolves today with `/score/now` and a tab left open past the
        // rollover moves on to the new day (correctness review F5).
        dayKey = null
        return ok.receivedMono + SportsConstants.DAY_POLL_IDLE_MS
    }

    private fun dayDue(now: Long): Boolean {
        val at = dayNextAt(now) ?: return false
        return at <= now
    }

    private suspend fun fetchDay() {
        dayForce = false
        val key = dayKey
        val league = sports.league
        val source = sourceOf(league)
        val f = when {
            source == null -> unboundFailure()
            key == null -> source.today()
            else -> source.day(key)
        }
        when (f) {
            is Fetch.Ok -> {
                dayRefused.remove(league)
                dayOk = f
                dayFailures = 0
                dayLastFailMono = null
                dayRetryAfterS = null
                if (key == null) dayKey = f.value.dayKey
                _day.value = DayState.Board(f.value, f.receivedMono, failing = false, isToday = dayWanted == null)
            }
            Fetch.NotFound -> dayFailed(FailKind.HTTP_OTHER, mono.nowMs(), null)
            is Fetch.Failed -> {
                if (f.kind == FailKind.REFUSED) {
                    val server = (f.retryAfterS ?: 0).coerceAtLeast(0) * 1000L
                    dayRefused[league] = f.receivedMono + maxOf(SportsConstants.BACKOFF_MAX_MS, server)
                } else {
                    dayRefused.remove(league)
                }
                dayFailed(f.kind, f.receivedMono, f.retryAfterS)
            }
        }
    }

    private fun dayFailed(kind: FailKind, atMono: Long, retryAfterS: Int?) {
        dayFailures++
        dayLastFailMono = atMono
        dayRetryAfterS = retryAfterS
        dayJitter = random.nextDouble(-1.0, 1.0)
        _day.value = when (val d = _day.value) {
            is DayState.Board -> d.copy(failing = true)
            else -> DayState.Failed(kind)
        }
    }

    private fun markDayOffline() {
        _day.value = when (val d = _day.value) {
            is DayState.Board -> if (d.failing) d else d.copy(failing = true)
            is DayState.Failed -> if (d.kind == FailKind.OFFLINE) d else DayState.Failed(FailKind.OFFLINE)
            DayState.Loading -> DayState.Failed(FailKind.OFFLINE)
        }
    }

    // ---- outputs

    private fun publish() {
        for (e in machine.drainEvents()) _events.tryEmit(e)
        val now = machine.followed()
        if (now != doc.game) {
            persist(doc.copy(game = now))
            pushDemand()
        }
        _followed.value = doc.game
        _bug.value = if (sports.enabled && doc.game != null) machine.view() else null
        _lastScore.value = if (sports.enabled && doc.game != null) machine.knownView() else null
    }

    private fun persist(d: SportsFollowDoc) {
        val followChanged = d.game?.id != doc.game?.id
        var next = d
        if (followChanged) {
            // (Update interval §3.2, review S2/S10) The slowed state belongs to one follow; the meters are written when a
            // follow ends (or another starts), so the settings row shows the latest measurement.
            for ((l, m) in meters.entries.toList()) if (m.slowed) meters[l] = m.copy(slowed = false)
            // (Fix pass 1) The board fallback belongs to one follow too: the next follow starts on per-event, so the
            // meter (Auto, the settings row's fallback line) goes back to a fresh per-event window.
            resetNflFallback(keepSlowed = false)
            if (metersDirty || next.meters != meters) next = next.copy(meters = meters.toMap())
            metersDirty = false
            meterPersistAt = mono.nowMs()
        }
        doc = next
        _followed.value = next.game
        store.update(SportsFollowDomain) {
            it.copy(game = next.game, pollPhaseMs = next.pollPhaseMs, firstFollowShown = next.firstFollowShown, meters = next.meters)
        }
        if (followChanged) pushInterval()
    }

    // ---- the update interval (docs/design/m6-sports-update-interval.md §3.2–§3.5, §4.3)

    /** Sends the followed league's effective interval (pref → Auto → the 429 floor) to the machine when it changed. */
    private fun pushInterval() {
        if (!::machine.isInitialized) return // start() sends it once the machine exists
        val g = doc.game ?: return
        val league = g.league
        val ms = PollMeter.effectiveS(sports.intervalS(league), league, meters[league]) * 1000L
        if (intervalSent && intervalSentMs == ms) return
        intervalSent = true
        intervalSentMs = ms
        machine.interval(ms)
    }

    /**
     * One follow poll into [game]'s league meter: an accepted `Ok` (still this follow) at its work time, wire bytes and
     * `max-age`; a timeout or IO failure at its elapsed time, capped (S6: a failing network recommends slower); a 429 (or
     * a 403, which the ESPN source reports as one) marks the NFL follow slowed (S2). The first sample of a process is
     * skipped. Persisted at most once per [SportsConstants.METER_PERSIST_MS], at once when slowed or the endpoint changes.
     */
    private fun measure(game: FollowedGame, endpoint: String?, f: Fetch<GameSnapshot>, workMs: Long) {
        val league = game.league
        val was = meters[league]
        var m = was
        when (f) {
            // (Fix pass 2) A null endpoint is a one-off check on another endpoint: never a sample (a 429 still slows).
            is Fetch.Ok -> if (endpoint != null && f.value.id == game.id && machine.followed()?.id == game.id) {
                if (meterWarm.add(league)) return
                m = PollMeter.add(m, league, endpoint, workMs, f.wireBytes, f.maxAgeS)
            }
            Fetch.NotFound -> Unit
            is Fetch.Failed -> when (f.kind) {
                FailKind.TIMEOUT, FailKind.IO -> if (endpoint == null) {
                    Unit
                } else if (league in meterWarm) {
                    m = PollMeter.add(m, league, endpoint, workMs, null, null, failed = true)
                } else {
                    meterWarm += league
                }
                // (CHL §1.6 #39) Every league timed from completion (the NFL and the CHL) is slowed by a 429.
                FailKind.HTTP_429 -> if (league.timing == PollTiming.COMPLETION && m?.slowed != true) {
                    m = (m ?: PollMeasure(endpoint = endpoint ?: defaultEndpoint(league))).copy(slowed = true)
                }
                else -> Unit
            }
        }
        if (m == null || m == was) return
        meters[league] = m
        metersDirty = true
        val now = mono.nowMs()
        val urgent = was == null || was.workMs.isEmpty() || was.slowed != m.slowed || was.endpoint != m.endpoint
        val at = meterPersistAt
        if (urgent || at == null || now - at >= SportsConstants.METER_PERSIST_MS) persistMeters(now)
        pushInterval()
    }

    /**
     * (Fix pass 1) The NFL meter on the board fallback → a fresh [PollMeter.NFL_EVENT] window (the per-event window was
     * already dropped when it switched). True when it changed.
     */
    private fun resetNflFallback(keepSlowed: Boolean): Boolean {
        val m = meters[League.NFL] ?: return false
        if (m.endpoint != PollMeter.NFL_BOARD) return false
        meters[League.NFL] = PollMeasure(endpoint = PollMeter.NFL_EVENT, slowed = keepSlowed && m.slowed)
        metersDirty = true
        return true
    }

    private fun persistMeters(now: Long) {
        metersDirty = false
        meterPersistAt = now
        val snapshot = meters.toMap()
        doc = doc.copy(meters = snapshot)
        store.update(SportsFollowDomain) { it.copy(meters = snapshot) }
    }

    companion object {
        private const val TAG = "ScoreRepo"
        /** The hold when a wake-up instant stays in the past after a step (a machine bug); never a busy loop. */
        internal const val STUCK_WAKE_HOLD_MS = 1_000L
        internal val ELAPSED = MonoClock { android.os.SystemClock.elapsedRealtime() }

        /** A meter's endpoint before any sample named one (#39). */
        private fun defaultEndpoint(league: League): String = when (league.group) {
            LeagueGroup.NHL -> PollMeter.NHL_LANDING
            LeagueGroup.NFL -> PollMeter.NFL_EVENT
            LeagueGroup.CHL -> PollMeter.CHL_LIVE
        }

        private fun minOfNullable(a: Long?, b: Long?): Long? = when {
            a == null -> b
            b == null -> a
            else -> minOf(a, b)
        }
    }
}
