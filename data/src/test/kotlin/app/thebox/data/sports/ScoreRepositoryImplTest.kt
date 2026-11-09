package app.thebox.data.sports

import app.thebox.core.playback.MonoClock
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.SportsDomain
import app.thebox.core.settings.state.SportsFollowDomain
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.DayState
import app.thebox.core.sports.Demand
import app.thebox.core.sports.DemandOwner
import app.thebox.core.sports.DeviceAwake
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.FollowedGame
import app.thebox.core.sports.GameClock
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.RemoveReason
import app.thebox.core.sports.ScoreBugMachine
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.ScoreEvent
import app.thebox.core.sports.ScoreSource
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.SportsFollowDoc
import app.thebox.core.sports.SportsPrefs
import app.thebox.core.sports.TeamLine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The repository's actor (docs/design/m6-score-bug.md §2.3–§2.7, §8 `:data`), under `runTest` with a `MonoClock` on the
 * test scheduler (A-S8). The followed game's machine and the day policy are fakes of their contracts (WP-1 fills the real
 * ones); the `realMachine…` cases run the same loop against WP-1's code and are skipped while it is a stub.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScoreRepositoryImplTest {

    // ---------------------------------------------------------------- fakes

    private class Signals(fg: Boolean = true, on: Boolean = true) : SportsSignals {
        override val foreground = MutableStateFlow(fg)
        override val online = MutableStateFlow(on)
    }

    private class Awake(on: Boolean = true) : DeviceAwake {
        val flow = MutableStateFlow(on)
        override val awake: StateFlow<Boolean> = flow
    }

    /** Records every request with its instant; answers are scripted. */
    private class FakeSource(private val mono: MonoClock) : ScoreSource {
        val calls = ArrayList<Pair<String, Long>>()
        var todayAnswer: () -> Fetch<DayBoard> = { Fetch.Ok(SportsFixtures.board(), mono.nowMs(), null, 0) }
        var dayAnswer: (String) -> Fetch<DayBoard> = { k -> Fetch.Ok(SportsFixtures.board(dayKey = k), mono.nowMs(), null, 0) }
        var gameAnswer: (GameId) -> Fetch<GameSnapshot> = { id -> Fetch.Ok(SportsFixtures.game(id = id.id), mono.nowMs(), null, 0) }
        override val league = League.NHL
        override suspend fun today(): Fetch<DayBoard> { calls += "today" to mono.nowMs(); return todayAnswer() }
        override suspend fun day(dayKey: String): Fetch<DayBoard> { calls += "day:$dayKey" to mono.nowMs(); return dayAnswer(dayKey) }
        override suspend fun game(id: GameId): Fetch<GameSnapshot> { calls += "game:${id.id}" to mono.nowMs(); return gameAnswer(id) }
        override fun logoUrl(team: TeamLine): String = team.logo ?: ""
        fun games() = calls.filter { it.first.startsWith("game:") }
        fun days() = calls.filter { !it.first.startsWith("game:") }
    }

    /**
     * A small stand-in for WP-1's machine with the contract's shape: polls once on follow and then every [cadenceMs] while
     * demand is not NONE and online; two NotFounds remove the follow; [extraWake] is a time-driven change (expiry, stale).
     */
    private class FakeMachine(private val mono: MonoClock, val phaseMs: Long) : FollowMachine {
        var game: FollowedGame? = null
        var demand = Demand.NONE
        var online = false
        var delaySec = -1
        var cadenceMs = 20_000L
        var nextPoll: Long? = null
        var extraWake: Long? = null
        var onExtraWake: () -> Unit = {}
        var view: ScoreBugView? = null
        val steps = ArrayList<Long>()
        val fetches = ArrayList<Fetch<GameSnapshot>>()
        val demands = ArrayList<Demand>()
        val follows = ArrayList<FollowedGame?>()
        private var notFound = 0
        private val events = ArrayList<ScoreEvent>()

        private fun active() = game != null && demand != Demand.NONE && online

        override fun follow(game: FollowedGame?) { follows += game; this.game = game; nextPoll = game?.let { mono.nowMs() }; notFound = 0; view = null }
        override fun prefs(delaySec: Int) { this.delaySec = delaySec }
        override fun demand(d: Demand) { demands += d; demand = d }
        override fun online(on: Boolean) { online = on }
        override fun fetched(f: Fetch<GameSnapshot>) {
            fetches += f
            nextPoll = mono.nowMs() + cadenceMs
            when (f) {
                is Fetch.Ok -> { notFound = 0; view = SportsFixtures.liveView(f.value) }
                Fetch.NotFound -> if (++notFound >= 2) {
                    events += ScoreEvent.Removed(RemoveReason.NOT_LISTED, game!!)
                    game = null
                    nextPoll = null
                }
                is Fetch.Failed -> Unit
            }
        }
        override fun step() {
            steps += mono.nowMs()
            val w = extraWake
            if (w != null && mono.nowMs() >= w) { extraWake = null; onExtraWake() }
        }
        override fun pollDue(): Boolean = active() && (nextPoll ?: Long.MAX_VALUE) <= mono.nowMs()
        override fun nextWakeAtMono(): Long? = listOfNotNull(if (active()) nextPoll else null, extraWake).minOrNull()
        override fun view(): ScoreBugView? = view
        override fun knownView(): ScoreBugView? = view
        override fun followed(): FollowedGame? = game
        override fun drainEvents(): List<ScoreEvent> = events.toList().also { events.clear() }
        fun raise(e: ScoreEvent) { events += e }
    }

    /** The §2.3 day cadence in miniature: now when never loaded, 30 s after an Ok, back-off 20 s × failures. */
    private class FakePolicy : DayPolicy {
        val calls = ArrayList<Int>()
        override fun nextAt(
            lastOk: Fetch.Ok<DayBoard>?, demand: Demand, failures: Int, lastFailMono: Long?, retryAfterS: Int?,
            pollPhaseMs: Long, jitter: Double, nowMono: Long,
        ): Long? {
            calls += failures
            if (demand == Demand.NONE) return null
            if (failures > 0) return lastFailMono!! + 20_000L * failures
            return lastOk?.let { it.receivedMono + 30_000L } ?: nowMono
        }
    }

    private class Harness(
        val repo: ScoreRepositoryImpl,
        val store: FakeSettingsStore,
        val source: FakeSource,
        val signals: Signals,
        val awake: Awake,
        val machines: List<FakeMachine>,
        val policy: FakePolicy,
        val events: List<ScoreEvent>,
    ) {
        val machine: FakeMachine get() = machines.single()
        val doc: SportsFollowDoc get() = store.flow(SportsFollowDomain).value
    }

    private val cgyEdm = FollowedGame(League.NHL, "2026020999", 1_793_923_200_000L, "CGY", "EDM")

    private fun TestScope.harness(
        doc: SportsFollowDoc = SportsFollowDoc(pollPhaseMs = 1_500L),
        prefs: SportsPrefs = SportsPrefs(),
        signals: Signals = Signals(),
        awake: Awake = Awake(),
        setup: FakeMachine.() -> Unit = {},
    ): Harness {
        val mono = MonoClock { testScheduler.currentTime }
        val store = FakeSettingsStore()
        store.update(SportsFollowDomain) { doc }
        store.update(SportsDomain) { prefs }
        store.updates.clear()
        val source = FakeSource(mono)
        val machines = ArrayList<FakeMachine>()
        val policy = FakePolicy()
        val repo = ScoreRepositoryImpl(
            source, store, awake, signals, backgroundScope, mono,
            machineFactory = { phase -> FakeMachine(mono, phase).also { it.setup(); machines += it } },
            dayPolicy = policy, random = Random(7),
        )
        val events = ArrayList<ScoreEvent>()
        backgroundScope.launch { repo.events.collect { events += it } }
        runCurrent()
        return Harness(repo, store, source, signals, awake, machines, policy, events)
    }

    // ---------------------------------------------------------------- zero traffic (§2.3, A-S5)

    @Test fun zeroRequestsWithNothingFollowedAndNoSportsUi() = runTest {
        val h = harness()
        h.repo.reportDemand(DemandOwner.FULL_WITH_FOLLOW, true) // FULL, but nothing is followed
        advanceTimeBy(3_600_000L)
        runCurrent()
        assertTrue(h.source.calls.isEmpty())
        assertEquals(Demand.NONE, h.machine.demand)
        assertTrue("the actor sleeps on its inputs: no periodic wake-ups", h.machine.steps.size < 5)
    }

    @Test fun zeroRequestsWhileTheDeviceIsAsleepEvenWhenStarted() = runTest {
        val awake = Awake(on = false)
        val h = harness(doc = SportsFollowDoc(game = cgyEdm, pollPhaseMs = 1_500L), awake = awake)
        h.repo.reportDemand(DemandOwner.FULL_WITH_FOLLOW, true)
        h.repo.reportDemand(DemandOwner.SPORTS_TAB, true)
        advanceTimeBy(600_000L)
        runCurrent()
        assertTrue("display off / CEC standby: nothing (A-S5)", h.source.calls.isEmpty())
        assertEquals(Demand.NONE, h.machine.demand)

        awake.flow.value = true
        runCurrent()
        assertEquals(Demand.SEEN, h.machine.demand)
        assertEquals(listOf("game:2026020999", "today"), h.source.calls.map { it.first })

        awake.flow.value = false
        runCurrent()
        val before = h.source.calls.size
        advanceTimeBy(600_000L)
        runCurrent()
        assertEquals(before, h.source.calls.size)
    }

    @Test fun zeroRequestsWhileTheProcessIsNotStarted() = runTest {
        val signals = Signals(fg = false)
        val h = harness(doc = SportsFollowDoc(game = cgyEdm, pollPhaseMs = 1_500L), signals = signals)
        advanceTimeBy(600_000L)
        runCurrent()
        assertTrue(h.source.calls.isEmpty())
        signals.foreground.value = true
        runCurrent()
        assertEquals("followed but not in FULL", Demand.UNSEEN, h.machine.demand)
        assertEquals(listOf("game:2026020999"), h.source.calls.map { it.first })
    }

    @Test fun offlineSendsNothingAndTheDayListSaysOffline() = runTest {
        val signals = Signals(on = false)
        val h = harness(doc = SportsFollowDoc(game = cgyEdm, pollPhaseMs = 1_500L), signals = signals)
        h.repo.reportDemand(DemandOwner.SPORTS_TAB, true)
        advanceTimeBy(300_000L)
        runCurrent()
        assertTrue(h.source.calls.isEmpty())
        assertFalse(h.machine.online)
        assertEquals(DayState.Failed(FailKind.OFFLINE), h.repo.day.value)

        signals.online.value = true // a default network, VALIDATED or not (A-S12)
        runCurrent()
        assertTrue(h.machine.online)
        assertEquals(listOf("game:2026020999", "today"), h.source.calls.map { it.first })
        assertTrue(h.repo.day.value is DayState.Board)
    }

    @Test fun sportsOffEndsTheFollowAndStopsEveryRequest() = runTest {
        val h = harness(doc = SportsFollowDoc(game = cgyEdm, pollPhaseMs = 1_500L, firstFollowShown = true))
        h.repo.reportDemand(DemandOwner.FULL_WITH_FOLLOW, true)
        runCurrent()
        assertEquals(1, h.source.games().size)
        h.store.update(SportsDomain) { it.copy(enabled = false) }
        runCurrent()
        assertEquals(listOf(ScoreEvent.Removed(RemoveReason.SPORTS_OFF, cgyEdm)), h.events)
        assertNull(h.doc.game)
        assertTrue("install facts survive a clear", h.doc.firstFollowShown)
        assertNull(h.repo.followed.value)
        h.repo.reportDemand(DemandOwner.SPORTS_TAB, true)
        advanceTimeBy(600_000L)
        runCurrent()
        assertEquals(1, h.source.calls.size)
    }

    @Test fun aRestoredSportsOffClearsAPersistedFollowQuietly() = runTest {
        val h = harness(doc = SportsFollowDoc(game = cgyEdm, pollPhaseMs = 1_500L), prefs = SportsPrefs(enabled = false))
        assertNull(h.doc.game)
        assertTrue(h.events.isEmpty())
        assertNull(h.machine.follows.last())
    }

    // ---------------------------------------------------------------- the wake-up contract (A-B1)

    @Test fun theLoopSleepsUntilTheMachinesNextWakeWithNoInput() = runTest {
        var fired = -1L
        val h = harness { extraWake = 90_000L; onExtraWake = { fired = testScheduler.currentTime } }
        val stepsBefore = h.machine.steps.size
        advanceTimeBy(89_999L)
        runCurrent()
        assertEquals("nothing wakes the loop before the instant", stepsBefore, h.machine.steps.size)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(90_000L, fired)
        assertEquals(90_000L, h.machine.steps.last())
        assertEquals(stepsBefore + 1, h.machine.steps.size)
        assertTrue(h.source.calls.isEmpty())
    }

    @Test fun aMachineExpiryWithNoInputIsPersistedAndPublished() = runTest {
        // A delayed FINAL clears itself 5 min after the final (§2.6): the machine wakes, the repository persists.
        val h = harness(doc = SportsFollowDoc(game = cgyEdm, pollPhaseMs = 1_500L)) {
            extraWake = 300_000L
            onExtraWake = { game = null }
        }
        assertEquals(cgyEdm, h.repo.followed.value)
        advanceTimeBy(300_001L)
        runCurrent()
        assertNull(h.repo.followed.value)
        assertNull(h.doc.game)
        assertTrue("expiry after the final is silent", h.events.isEmpty())
    }

    @Test fun aWakeThatStaysInThePastNeverSpins() = runTest {
        val h = harness { extraWake = 10_000L; onExtraWake = { extraWake = 10_000L } } // a buggy machine
        advanceTimeBy(15_000L)
        runCurrent()
        assertTrue("held off, not a busy loop: ${h.machine.steps.size}", h.machine.steps.size < 15)
    }

    // ---------------------------------------------------------------- the followed game

    @Test fun theFollowedGameIsPolledOnTheMachinesScheduleAndResultsReachIt() = runTest {
        val h = harness(doc = SportsFollowDoc(game = cgyEdm, pollPhaseMs = 1_500L))
        assertEquals(1_500L, h.machine.phaseMs)
        h.repo.reportDemand(DemandOwner.FULL_WITH_FOLLOW, true)
        runCurrent()
        advanceTimeBy(45_000L)
        runCurrent()
        assertEquals(listOf(0L, 20_000L, 40_000L), h.source.games().map { it.second })
        assertEquals(3, h.machine.fetches.size)
        assertEquals(SportsFixtures.liveView(SportsFixtures.game()), h.repo.bug.value)
        assertEquals("the snapshot's own clock (owner ruling 6)", 622, h.repo.bug.value!!.clockSecs)
        assertEquals(h.repo.bug.value, h.repo.lastScore.value)
    }

    @Test fun theScreenOffLayerSendsNothing() = runTest {
        // C-S5: a follow, FULL, then the quick bar's screen-off for audio overnight: zero requests.
        val h = harness(doc = SportsFollowDoc(game = cgyEdm, pollPhaseMs = 1_500L))
        h.repo.reportDemand(DemandOwner.FULL_WITH_FOLLOW, true)
        runCurrent()
        val before = h.source.calls.size
        h.repo.reportDemand(DemandOwner.FULL_WITH_FOLLOW, false)
        h.repo.reportScreenOff(true)
        runCurrent()
        assertEquals(Demand.NONE, h.machine.demand)
        advanceTimeBy(600_000L)
        runCurrent()
        assertEquals("0 requests over 10 min with the screen off", before, h.source.calls.size)
        h.repo.reportScreenOff(false)
        h.repo.reportDemand(DemandOwner.FULL_WITH_FOLLOW, true)
        runCurrent()
        assertEquals(Demand.SEEN, h.machine.demand)
    }

    @Test fun demandIsSeenUnseenOrNone() = runTest {
        val h = harness(doc = SportsFollowDoc(game = cgyEdm, pollPhaseMs = 1_500L))
        assertEquals(Demand.UNSEEN, h.machine.demand)
        h.repo.reportDemand(DemandOwner.FULL_WITH_FOLLOW, true); runCurrent()
        assertEquals(Demand.SEEN, h.machine.demand)
        h.repo.reportDemand(DemandOwner.FULL_WITH_FOLLOW, false); runCurrent()
        assertEquals(Demand.UNSEEN, h.machine.demand)
        h.repo.reportDemand(DemandOwner.SCORE_PICKER, true); runCurrent()
        assertEquals("the picker is SEEN too", Demand.SEEN, h.machine.demand)
        h.repo.follow(null); runCurrent()
        assertEquals(Demand.NONE, h.machine.demand)
        assertEquals("pushed only on change", listOf(Demand.UNSEEN, Demand.SEEN, Demand.UNSEEN, Demand.SEEN, Demand.NONE), h.machine.demands)
    }

    @Test fun twoNotFoundsRemoveTheFollowThroughTheMachine() = runTest {
        val signals = Signals(fg = false)
        val h = harness(doc = SportsFollowDoc(game = cgyEdm, pollPhaseMs = 1_500L), signals = signals)
        h.source.gameAnswer = { Fetch.NotFound }
        h.repo.reportDemand(DemandOwner.FULL_WITH_FOLLOW, true)
        signals.foreground.value = true
        advanceTimeBy(25_000L)
        runCurrent()
        assertEquals(listOf<Fetch<GameSnapshot>>(Fetch.NotFound, Fetch.NotFound), h.machine.fetches)
        assertEquals(listOf(ScoreEvent.Removed(RemoveReason.NOT_LISTED, cgyEdm)), h.events)
        assertNull(h.doc.game)
        assertNull(h.repo.followed.value)
        assertEquals(Demand.NONE, h.machine.demand)
        advanceTimeBy(600_000L)
        runCurrent()
        assertEquals(2, h.source.calls.size)
    }

    @Test fun machineEventsAndFollowRewritesArePublished() = runTest {
        val h = harness(doc = SportsFollowDoc(game = cgyEdm, pollPhaseMs = 1_500L)) {
            extraWake = 5_000L
            onExtraWake = {
                game = game!!.copy(startUtcMs = 1_793_924_000_000L, finalSeenUtcMs = 1_793_930_000_000L)
                raise(ScoreEvent.Removed(RemoveReason.POSTPONED, cgyEdm))
            }
        }
        advanceTimeBy(5_001L)
        runCurrent()
        assertEquals(1_793_924_000_000L, h.doc.game!!.startUtcMs)
        assertEquals(1_793_930_000_000L, h.doc.game!!.finalSeenUtcMs)
        assertEquals(h.doc.game, h.repo.followed.value)
        assertEquals(listOf(ScoreEvent.Removed(RemoveReason.POSTPONED, cgyEdm)), h.events)
    }

    // ---------------------------------------------------------------- follow (§2.6, §5.3)

    @Test fun followPersistsAndRaisesTheToastEvents() = runTest {
        val h = harness()
        val live = SportsFixtures.game()
        h.repo.follow(live)
        runCurrent()
        val f = SportsFixtures.followedOf(live)
        assertEquals(f, h.doc.game)
        assertEquals(f, h.repo.followed.value)
        assertTrue(h.doc.firstFollowShown)
        assertEquals(f, h.machine.game)

        h.repo.follow(live) // the same game again: nothing new
        runCurrent()
        val other = SportsFixtures.game(id = "2026020998", away = SportsFixtures.team("MTL", "Canadiens", 1))
        h.repo.follow(other)
        runCurrent()
        val final = SportsFixtures.game(id = "2026020997", phase = GamePhase.FINAL, finalKind = PeriodKind.OT)
        h.repo.follow(final)
        runCurrent()
        assertEquals(
            listOf(
                ScoreEvent.Followed(f, first = true),
                ScoreEvent.Followed(SportsFixtures.followedOf(other), first = false),
                ScoreEvent.FollowedFinal(SportsFixtures.followedOf(final)),
            ),
            h.events,
        )
        assertEquals("following another game replaces the current one", SportsFixtures.followedOf(final), h.machine.game)
    }

    @Test fun aFinalFirstFollowKeepsTheLongToastForLater() = runTest {
        val h = harness()
        h.repo.follow(SportsFixtures.game(phase = GamePhase.FINAL, finalKind = PeriodKind.REG))
        runCurrent()
        assertFalse(h.doc.firstFollowShown)
        h.repo.follow(SportsFixtures.game(id = "2026020998"))
        runCurrent()
        assertTrue((h.events.last() as ScoreEvent.Followed).first)
    }

    @Test fun stopFollowingClearsOnlyTheGame() = runTest {
        val h = harness(doc = SportsFollowDoc(game = cgyEdm, pollPhaseMs = 1_234L, firstFollowShown = true))
        h.repo.follow(null)
        runCurrent()
        assertEquals(SportsFollowDoc(game = null, pollPhaseMs = 1_234L, firstFollowShown = true), h.doc)
        assertNull(h.repo.followed.value)
        assertNull(h.repo.bug.value)
        assertTrue(h.events.isEmpty())
    }

    @Test fun pollPhaseIsDrawnOncePerInstall() = runTest {
        val h = harness(doc = SportsFollowDoc())
        val drawn = h.doc.pollPhaseMs
        assertNotNull(drawn)
        assertTrue(drawn!! in SportsConstants.POLL_PHASE_MIN_MS..SportsConstants.POLL_PHASE_MAX_MS)
        assertEquals(drawn, h.machine.phaseMs)

        val kept = harness(doc = SportsFollowDoc(pollPhaseMs = 1_234L))
        assertEquals(1_234L, kept.machine.phaseMs)
        assertEquals(1_234L, kept.doc.pollPhaseMs)
        assertFalse("not rewritten", kept.store.updates.contains(SportsFollowDomain.name))
    }

    @Test fun theDelayReachesTheMachine() = runTest {
        val h = harness(prefs = SportsPrefs(delaySec = 30))
        assertEquals(30, h.machine.delaySec)
        h.store.update(SportsDomain) { it.copy(delaySec = 90) }
        runCurrent()
        assertEquals(90, h.machine.delaySec)
    }

    // ---------------------------------------------------------------- the day list (§2.3, A-S7, A-N10, A-N11)

    @Test fun theDayListLoadsTodayOncePerOpenThenPollsTheDatedDay() = runTest {
        val h = harness()
        assertEquals(DayState.Loading, h.repo.day.value)
        h.repo.reportDemand(DemandOwner.SPORTS_TAB, true)
        runCurrent()
        assertEquals(listOf("today" to 0L), h.source.days())
        val board = h.repo.day.value as DayState.Board
        assertEquals("2026-11-05", board.board.dayKey)
        assertFalse(board.failing)

        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals("then the dated currentDate", "day:2026-11-05" to 30_000L, h.source.days().last())

        h.repo.reportDemand(DemandOwner.SPORTS_TAB, false)
        runCurrent()
        val closed = h.source.calls.size
        advanceTimeBy(600_000L)
        runCurrent()
        assertEquals("nothing once the tab and picker are closed", closed, h.source.calls.size)

        h.repo.reportDemand(DemandOwner.SCORE_PICKER, true)
        runCurrent()
        assertEquals("a new open asks /score/now again", "today", h.source.days().last().first)
        assertTrue(h.repo.day.value is DayState.Board)
        assertTrue((h.repo.day.value as DayState.Board).isToday)
    }

    @Test fun aPickedDayStaysPickedWhenTheDeviceWakesWithTheTabOpen() = runTest {
        // Only a new open resets the day; the tab on Yesterday through a standby keeps Yesterday.
        val h = harness()
        h.repo.reportDemand(DemandOwner.SPORTS_TAB, true)
        h.repo.showDay(null)
        runCurrent()
        h.repo.showDay("2026-11-04")
        runCurrent()
        h.awake.flow.value = false
        runCurrent()
        h.awake.flow.value = true
        runCurrent()
        assertEquals("day:2026-11-04", h.source.days().last().first)
        assertEquals("2026-11-04", (h.repo.day.value as DayState.Board).board.dayKey)
    }

    @Test fun thePickerAsksForTodayAfterTheTabShowedAnotherDay() = runTest {
        // C-S6 / U-B1: Sports tab → Yesterday → leave → full screen → Score bug sheet.
        val h = harness()
        h.repo.reportDemand(DemandOwner.SPORTS_TAB, true)
        h.repo.showDay(null)
        runCurrent()
        h.repo.showDay("2026-11-04")
        runCurrent()
        val other = h.repo.day.value as DayState.Board
        assertEquals("2026-11-04", other.board.dayKey)
        assertFalse("a picked day is not today", other.isToday)
        h.repo.reportDemand(DemandOwner.SPORTS_TAB, false)
        runCurrent()
        h.repo.reportDemand(DemandOwner.SCORE_PICKER, true)
        runCurrent()
        assertEquals("the picker opens on /score/now", "today", h.source.days().last().first)
        val today = h.repo.day.value as DayState.Board
        assertEquals("2026-11-05", today.board.dayKey)
        assertTrue(today.isToday)
        // And it keeps polling today's dated key, not the other day.
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals("day:2026-11-05", h.source.days().last().first)
    }

    @Test fun anotherDayIsLoadingThenItsBoard() = runTest {
        val h = harness()
        h.repo.reportDemand(DemandOwner.SPORTS_TAB, true)
        runCurrent()
        h.source.dayAnswer = { Fetch.Failed(FailKind.IO, null, testScheduler.currentTime) }
        h.repo.showDay("2026-11-04")
        runCurrent()
        assertEquals("day:2026-11-04", h.source.days().last().first)
        assertEquals("no board for that day yet", DayState.Failed(FailKind.IO), h.repo.day.value)
        h.source.dayAnswer = { k -> Fetch.Ok(SportsFixtures.board(dayKey = k), testScheduler.currentTime, null, 0) }
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals("2026-11-04", (h.repo.day.value as DayState.Board).board.dayKey)
        h.repo.showDay(null)
        runCurrent()
        assertEquals("today", h.source.days().last().first)
    }

    @Test fun dayFailuresBackOffKeepTheBoardAndRetryIsImmediate() = runTest {
        val h = harness()
        h.repo.reportDemand(DemandOwner.SPORTS_TAB, true)
        runCurrent()
        h.source.dayAnswer = { Fetch.Failed(FailKind.HTTP_5XX, 30, testScheduler.currentTime) }
        advanceTimeBy(30_000L)
        runCurrent()
        val failing = h.repo.day.value as DayState.Board
        assertTrue("the board stays, marked failing (A-N11)", failing.failing)
        assertEquals(1, h.policyFailuresSeenMax())
        advanceTimeBy(19_000L)
        runCurrent()
        assertEquals("the back-off holds", 2, h.source.days().size)
        h.repo.retry()
        runCurrent()
        assertEquals(3, h.source.days().size)
        assertEquals(49_000L, h.source.days().last().second)
    }

    private fun Harness.policyFailuresSeenMax(): Int = policy.calls.maxOrNull() ?: 0

    @Test fun theDayListDoesNotTouchTheFollowedGamesSchedule() = runTest {
        val h = harness(doc = SportsFollowDoc(game = cgyEdm, pollPhaseMs = 1_500L))
        h.repo.reportDemand(DemandOwner.SPORTS_TAB, true)
        advanceTimeBy(61_000L)
        runCurrent()
        assertEquals(listOf(0L, 20_000L, 40_000L, 60_000L), h.source.games().map { it.second })
        assertEquals(listOf("today" to 0L, "day:2026-11-05" to 30_000L, "day:2026-11-05" to 60_000L), h.source.days())
    }

    // ---------------------------------------------------------------- robustness

    @Test fun aMachineFailureStopsTheFeatureWithoutCrashing() = runTest {
        val mono = MonoClock { testScheduler.currentTime }
        val store = FakeSettingsStore()
        store.update(SportsFollowDomain) { SportsFollowDoc(game = cgyEdm, pollPhaseMs = 1_500L) }
        val source = FakeSource(mono)
        val repo = ScoreRepositoryImpl(
            source, store, Awake(), Signals(), backgroundScope, mono,
            machineFactory = { FollowMachineStub },
        )
        runCurrent()
        repo.reportDemand(DemandOwner.FULL_WITH_FOLLOW, true)
        advanceTimeBy(60_000L)
        runCurrent()
        assertNull(repo.bug.value)
        assertTrue(source.calls.isEmpty())
    }

    private object FollowMachineStub : FollowMachine {
        override fun follow(game: FollowedGame?) = TODO("WP-1")
        override fun prefs(delaySec: Int) = TODO("WP-1")
        override fun demand(d: Demand) = TODO("WP-1")
        override fun online(on: Boolean) = TODO("WP-1")
        override fun fetched(f: Fetch<GameSnapshot>) = TODO("WP-1")
        override fun step() = TODO("WP-1")
        override fun pollDue(): Boolean = TODO("WP-1")
        override fun nextWakeAtMono(): Long? = TODO("WP-1")
        override fun view(): ScoreBugView? = TODO("WP-1")
        override fun knownView(): ScoreBugView? = TODO("WP-1")
        override fun followed(): FollowedGame? = TODO("WP-1")
        override fun drainEvents(): List<ScoreEvent> = TODO("WP-1")
    }

    // ---------------------------------------------------------------- against WP-1's code (C-N5: no skip guards)

    /** Wall time: 2026-11-06T00:00:00Z (puck drop of [SportsFixtures.game]) at mono 0. */
    private val wallBase = 1_793_923_200_000L

    /**
     * [configure] runs before the repository starts: a restored follow polls at once (UNSEEN demand, §2.3), so an answer
     * set after `realRepo` returns would only reach the second poll (integration pass 2026-11-05).
     */
    private fun TestScope.realRepo(
        doc: SportsFollowDoc,
        signals: Signals = Signals(),
        configure: (FakeSource) -> Unit = {},
    ): Triple<ScoreRepositoryImpl, FakeSource, FakeSettingsStore> {
        val mono = MonoClock { testScheduler.currentTime }
        val wall = { wallBase + testScheduler.currentTime }
        val store = FakeSettingsStore()
        store.update(SportsFollowDomain) { doc }
        val source = FakeSource(mono)
        configure(source)
        val repo = ScoreRepositoryImpl(
            source, store, Awake(), signals, backgroundScope, mono,
            machineFactory = { phase -> FollowMachine.of(ScoreBugMachine(mono, wall, phase)) },
        )
        runCurrent()
        return Triple(repo, source, store)
    }

    @Test fun realMachine_zeroRequestsWithNothingFollowed() = runTest {
        val (repo, source, _) = realRepo(SportsFollowDoc(pollPhaseMs = 1_500L))
        repo.reportDemand(DemandOwner.FULL_WITH_FOLLOW, true)
        advanceTimeBy(3_600_000L)
        runCurrent()
        assertTrue(source.calls.isEmpty())
    }

    @Test fun realMachine_aLivePollIsPhaseAlignedToTheCdnBucket() = runTest {
        val live = SportsFixtures.game(clock = GameClock(622, running = true, inIntermission = false))
        val signals = Signals(fg = false) // SEEN from the first poll on
        val (repo, source, _) = realRepo(SportsFollowDoc(game = SportsFixtures.followedOf(live), pollPhaseMs = 1_500L), signals)
        // Age 18 at Date D: the bucket started at D − 18 s and ends at D + 2 s; the next poll is 2 s + 1.5 s after receipt.
        source.gameAnswer = { Fetch.Ok(live, testScheduler.currentTime, wallBase + testScheduler.currentTime, 18) }
        repo.reportDemand(DemandOwner.FULL_WITH_FOLLOW, true)
        signals.foreground.value = true
        runCurrent()
        val first = source.games().single().second
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals("A-S2: receivedMono + (bucketEnd − Date) + POLL_PHASE_MS", first + 3_500L, source.games()[1].second)
    }

    @Test fun realMachine_aFinalClearsFiveMinutesLaterWithNoInput() = runTest {
        val final = SportsFixtures.game(phase = GamePhase.FINAL, finalKind = PeriodKind.REG, clock = null)
        val (repo, source, store) = realRepo(SportsFollowDoc(game = SportsFixtures.followedOf(final), pollPhaseMs = 1_500L)) {
            it.gameAnswer = { Fetch.Ok(final, testScheduler.currentTime, wallBase + testScheduler.currentTime, 0) }
        }
        repo.reportDemand(DemandOwner.FULL_WITH_FOLLOW, true)
        runCurrent()
        val polls = source.calls.size
        assertNotNull(repo.followed.value)
        advanceTimeBy(SportsConstants.EXPIRE_AFTER_FINAL_MS - 1_000L)
        runCurrent()
        assertNotNull(repo.followed.value)
        advanceTimeBy(2_000L)
        runCurrent()
        assertNull("A-B1: expiry with polling stopped and no input", repo.followed.value)
        assertNull(store.flow(SportsFollowDomain).value.game)
        assertEquals("a final game stops polling", polls, source.calls.size)
    }

    // ---------------------------------------------------------------- today across the rollover (correctness review F5)

    /** The real policy's shape for an all-final day: polls once, then nothing. */
    private val allFinalPolicy = DayPolicy { lastOk, demand, failures, lastFailMono, _, _, _, now ->
        when {
            demand == Demand.NONE -> null
            failures > 0 -> lastFailMono!! + 20_000L * failures
            lastOk == null -> now
            else -> null
        }
    }

    private fun TestScope.dayRepo(source: FakeSource): ScoreRepositoryImpl {
        val mono = MonoClock { testScheduler.currentTime }
        val store = FakeSettingsStore()
        store.update(SportsFollowDomain) { SportsFollowDoc(pollPhaseMs = 1_500L) }
        return ScoreRepositoryImpl(
            source, store, Awake(), Signals(), backgroundScope, mono,
            machineFactory = { phase -> FakeMachine(mono, phase) }, dayPolicy = allFinalPolicy, random = Random(7),
        ).also { runCurrent() }
    }

    @Test fun anAllFinalTodayBoardReResolvesTodaySoItMovesOnPastTheRollover() = runTest {
        val source = FakeSource(MonoClock { testScheduler.currentTime })
        val repo = dayRepo(source)
        source.todayAnswer = { Fetch.Ok(SportsFixtures.board(dayKey = "2026-11-08"), testScheduler.currentTime, null, 0) }
        repo.reportDemand(DemandOwner.SPORTS_TAB, true)
        runCurrent()
        assertEquals("2026-11-08", (repo.day.value as DayState.Board).board.dayKey)

        // The server's today moves on; the tab is still open.
        source.todayAnswer = { Fetch.Ok(SportsFixtures.board(dayKey = "2026-11-09"), testScheduler.currentTime, null, 0) }
        advanceTimeBy(SportsConstants.DAY_POLL_IDLE_MS + 1)
        runCurrent()
        val b = repo.day.value as DayState.Board
        assertEquals("2026-11-09", b.board.dayKey)
        assertTrue(b.isToday)
        assertEquals(listOf("today", "today"), source.days().map { it.first })
    }

    @Test fun anAllFinalPickedDayIsNotReResolved() = runTest {
        val source = FakeSource(MonoClock { testScheduler.currentTime })
        val repo = dayRepo(source)
        repo.reportDemand(DemandOwner.SPORTS_TAB, true)
        runCurrent()
        repo.showDay("2026-11-01")
        runCurrent()
        advanceTimeBy(3 * SportsConstants.DAY_POLL_IDLE_MS)
        runCurrent()
        assertEquals(listOf("today", "day:2026-11-01"), source.days().map { it.first })
    }
}
