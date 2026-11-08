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
     * [league]'s source. A league with no source (a test built with one source) falls back to the first one, so the
     * NHL-only graphs behave as before.
     */
    private fun sourceOf(league: League): ScoreSource = sources[league] ?: sources.values.first()

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
    /** The tab or the picker is open (whatever the platform facts): its rising edge resets the day to today (C-S6). */
    private var dayUiOpen = false

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
                machine.fetched(sourceOf(game.league).game(game.id))
                // The answer took time: advance the machine to now, so what the answer made due is applied at once.
                machine.step()
                lastStepAt = mono.nowMs()
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
            Input.Retry -> {
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
        if (dayForce) return now
        return dayPolicy.nextAt(dayOk, dayDemand, dayFailures, dayLastFailMono, dayRetryAfterS, phaseMs, dayJitter, now)
    }

    private fun dayDue(now: Long): Boolean {
        val at = dayNextAt(now) ?: return false
        return at <= now
    }

    private suspend fun fetchDay() {
        dayForce = false
        val key = dayKey
        val source = sourceOf(sports.league)
        val f = if (key == null) source.today() else source.day(key)
        when (f) {
            is Fetch.Ok -> {
                dayOk = f
                dayFailures = 0
                dayLastFailMono = null
                dayRetryAfterS = null
                if (dayKey == null) dayKey = f.value.dayKey
                _day.value = DayState.Board(f.value, f.receivedMono, failing = false, isToday = dayWanted == null)
            }
            Fetch.NotFound -> dayFailed(FailKind.HTTP_OTHER, mono.nowMs(), null)
            is Fetch.Failed -> dayFailed(f.kind, f.receivedMono, f.retryAfterS)
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
        doc = d
        _followed.value = d.game
        store.update(SportsFollowDomain) { it.copy(game = d.game, pollPhaseMs = d.pollPhaseMs, firstFollowShown = d.firstFollowShown) }
    }

    companion object {
        private const val TAG = "ScoreRepo"
        /** The hold when a wake-up instant stays in the past after a step (a machine bug); never a busy loop. */
        internal const val STUCK_WAKE_HOLD_MS = 1_000L
        internal val ELAPSED = MonoClock { android.os.SystemClock.elapsedRealtime() }

        private fun minOfNullable(a: Long?, b: Long?): Long? = when {
            a == null -> b
            b == null -> a
            else -> minOf(a, b)
        }
    }
}
