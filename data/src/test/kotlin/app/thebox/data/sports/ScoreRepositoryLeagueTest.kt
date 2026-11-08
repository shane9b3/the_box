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
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.FollowedGame
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.ScoreEvent
import app.thebox.core.sports.ScoreSource
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.SportsFollowDoc
import app.thebox.core.sports.SportsPrefs
import app.thebox.core.sports.TeamLine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The league switch and the per-league sources (docs/design/m6-nfl.md §7.1, §2 S7, §10.3): the day list follows
 * `SportsPrefs.league`, the followed game polls its own league's source. The machine and the day policy are small fakes
 * of their contracts, as in [ScoreRepositoryImplTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScoreRepositoryLeagueTest {

    private class Signals : SportsSignals {
        override val foreground = MutableStateFlow(true)
        override val online = MutableStateFlow(true)
    }

    private class Awake : DeviceAwake {
        override val awake: StateFlow<Boolean> = MutableStateFlow(true)
    }

    /** Records every request; its boards are keyed `{league}-today` / the asked key so the league is visible. */
    private class FakeSource(override val league: League, private val mono: MonoClock) : ScoreSource {
        val calls = ArrayList<String>()
        /** How long `today()` takes (on the test clock). */
        var todayDelayMs = 0L
        override suspend fun today(): Fetch<DayBoard> {
            calls += "today"
            if (todayDelayMs > 0) delay(todayDelayMs)
            return Fetch.Ok(SportsFixtures.board(dayKey = "${league.name.lowercase()}-today"), mono.nowMs(), null, 0)
        }
        override suspend fun day(dayKey: String): Fetch<DayBoard> {
            calls += "day:$dayKey"
            return Fetch.Ok(SportsFixtures.board(dayKey = dayKey), mono.nowMs(), null, 0)
        }
        override suspend fun game(id: GameId): Fetch<GameSnapshot> {
            calls += "game:${id.league}:${id.id}"
            val g = if (id.league == League.NFL) SportsFixtures.nflGame(id = id.id) else SportsFixtures.game(id = id.id)
            return Fetch.Ok(g, mono.nowMs(), null, 0)
        }
        override fun logoUrl(team: TeamLine): String = team.logo.orEmpty()
        fun games() = calls.filter { it.startsWith("game:") }
        fun days() = calls.filterNot { it.startsWith("game:") }
    }

    /** Polls on follow, then every 20 s while there is demand and a network. */
    private class FakeMachine(private val mono: MonoClock) : FollowMachine {
        var game: FollowedGame? = null
        var demand = Demand.NONE
        var online = false
        var nextPoll: Long? = null
        val follows = ArrayList<FollowedGame?>()
        private var view: ScoreBugView? = null
        private fun active() = game != null && demand != Demand.NONE && online
        override fun follow(game: FollowedGame?) { follows += game; this.game = game; nextPoll = game?.let { mono.nowMs() }; view = null }
        override fun prefs(delaySec: Int) = Unit
        override fun demand(d: Demand) { demand = d }
        override fun online(on: Boolean) { online = on }
        override fun fetched(f: Fetch<GameSnapshot>) {
            nextPoll = mono.nowMs() + 20_000L
            if (f is Fetch.Ok) view = SportsFixtures.liveView(f.value)
        }
        override fun step() = Unit
        override fun pollDue(): Boolean = active() && (nextPoll ?: Long.MAX_VALUE) <= mono.nowMs()
        override fun nextWakeAtMono(): Long? = if (active()) nextPoll else null
        override fun view(): ScoreBugView? = view
        override fun knownView(): ScoreBugView? = view
        override fun followed(): FollowedGame? = game
        override fun drainEvents(): List<ScoreEvent> = emptyList()
    }

    /** Now when never loaded, 30 s after an Ok; nothing without demand. */
    private val policy = DayPolicy { lastOk, demand, _, _, _, _, _, now ->
        if (demand == Demand.NONE) null else lastOk?.let { it.receivedMono + 30_000L } ?: now
    }

    private class Harness(
        val repo: ScoreRepositoryImpl,
        val store: FakeSettingsStore,
        val nhl: FakeSource,
        val nfl: FakeSource,
        val machine: () -> FakeMachine,
        val events: List<ScoreEvent>,
    ) {
        val doc: SportsFollowDoc get() = store.flow(SportsFollowDomain).value
        fun league(l: League) = store.update(SportsDomain) { it.copy(league = l) }
    }

    private val cgyEdm = FollowedGame(League.NHL, "2026020999", 1_793_923_200_000L, "CGY", "EDM")
    private val kcBuf = FollowedGame(League.NFL, "401899001", 1_794_157_200_000L, "KC", "BUF")

    private fun TestScope.harness(
        doc: SportsFollowDoc = SportsFollowDoc(pollPhaseMs = 1_500L),
        prefs: SportsPrefs = SportsPrefs(),
    ): Harness {
        val mono = MonoClock { testScheduler.currentTime }
        val store = FakeSettingsStore()
        store.update(SportsFollowDomain) { doc }
        store.update(SportsDomain) { prefs }
        val nhl = FakeSource(League.NHL, mono)
        val nfl = FakeSource(League.NFL, mono)
        var machine: FakeMachine? = null
        val repo = ScoreRepositoryImpl(
            mapOf(League.NHL to nhl, League.NFL to nfl), store, Awake(), Signals(), backgroundScope, mono,
            machineFactory = { FakeMachine(mono).also { machine = it } },
            dayPolicy = policy, random = Random(7),
        )
        val events = ArrayList<ScoreEvent>()
        backgroundScope.launch { repo.events.collect { events += it } }
        runCurrent()
        return Harness(repo, store, nhl, nfl, { machine!! }, events)
    }

    private fun boardKey(h: Harness): String? = (h.repo.day.value as? DayState.Board)?.board?.dayKey

    // ---------------------------------------------------------------- the switch (§7.1)

    @Test fun aLeagueSwitchResetsTheDayListToTheNewLeaguesToday() = runTest {
        val h = harness()
        h.repo.reportDemand(DemandOwner.SPORTS_TAB, true)
        runCurrent()
        assertEquals("nhl-today", boardKey(h))
        assertEquals(listOf("today"), h.nhl.days())

        h.league(League.NFL)
        runCurrent()
        assertEquals("the NFL's today, at once", listOf("today"), h.nfl.days())
        assertEquals("nfl-today", boardKey(h))
        advanceTimeBy(30_001L)
        runCurrent()
        assertEquals("then its own key", listOf("today", "day:nfl-today"), h.nfl.days())
        assertEquals("the NHL source is not asked again", listOf("today"), h.nhl.days())

        h.league(League.NHL)
        runCurrent()
        assertEquals("back again: the NHL's today, not the NFL's key", listOf("today", "today"), h.nhl.days())
        assertEquals("nhl-today", boardKey(h))
    }

    @Test fun theSwitchShowsLoadingBeforeTheNewBoard() = runTest {
        val h = harness()
        h.repo.reportDemand(DemandOwner.SPORTS_TAB, true)
        runCurrent()
        assertEquals("nhl-today", boardKey(h))
        h.nfl.todayDelayMs = 1_000L
        h.league(League.NFL)
        runCurrent()
        assertEquals("the old league's board is gone at once", DayState.Loading, h.repo.day.value)
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals("nfl-today", boardKey(h))
        assertTrue((h.repo.day.value as DayState.Board).isToday)
    }

    @Test fun aDayPickedInOneLeagueIsNotSentToTheOther() = runTest {
        val h = harness()
        h.repo.reportDemand(DemandOwner.SPORTS_TAB, true)
        runCurrent()
        h.repo.showDay("2026-11-04")
        runCurrent()
        assertEquals(listOf("today", "day:2026-11-04"), h.nhl.days())
        h.league(League.NFL)
        runCurrent()
        assertEquals(listOf("today"), h.nfl.days())
        assertTrue("today again", (h.repo.day.value as DayState.Board).isToday)
    }

    @Test fun aSwitchWithTheTabClosedSendsNothingUntilItOpens() = runTest {
        val h = harness()
        h.league(League.NFL)
        advanceTimeBy(600_000L)
        runCurrent()
        assertTrue(h.nhl.calls.isEmpty())
        assertTrue(h.nfl.calls.isEmpty())
        h.repo.reportDemand(DemandOwner.SCORE_PICKER, true)
        runCurrent()
        assertEquals(listOf("today"), h.nfl.days())
        assertTrue(h.nhl.calls.isEmpty())
    }

    @Test fun nothingIsFetchedWithNothingFollowedAndSportsClosed() = runTest {
        val h = harness(prefs = SportsPrefs(league = League.NFL))
        h.repo.reportDemand(DemandOwner.FULL_WITH_FOLLOW, true)
        advanceTimeBy(3_600_000L)
        h.league(League.NHL)
        advanceTimeBy(3_600_000L)
        runCurrent()
        assertTrue(h.nhl.calls.isEmpty())
        assertTrue(h.nfl.calls.isEmpty())
        assertEquals(Demand.NONE, h.machine().demand)
    }

    // ---------------------------------------------------------------- the follow's own league (§7.1)

    @Test fun theFollowPollsItsOwnLeaguesSource() = runTest {
        val h = harness(doc = SportsFollowDoc(game = kcBuf, pollPhaseMs = 1_500L))
        h.repo.reportDemand(DemandOwner.FULL_WITH_FOLLOW, true)
        h.repo.reportDemand(DemandOwner.SPORTS_TAB, true)
        runCurrent()
        assertEquals("the NFL game from the NFL source", listOf("game:NFL:401899001"), h.nfl.games())
        assertEquals("the NHL tab's day list from the NHL source", listOf("today"), h.nhl.days())
        assertTrue(h.nhl.games().isEmpty())
        assertTrue(h.nfl.days().isEmpty())
        assertEquals(League.NFL, h.repo.bug.value?.gameId?.league)
    }

    @Test fun theFollowSurvivesALeagueSwitch() = runTest {
        val h = harness(doc = SportsFollowDoc(game = cgyEdm, pollPhaseMs = 1_500L))
        h.repo.reportDemand(DemandOwner.FULL_WITH_FOLLOW, true)
        runCurrent()
        h.league(League.NFL)
        advanceTimeBy(20_001L)
        runCurrent()
        assertEquals(cgyEdm, h.doc.game)
        assertEquals(listOf(cgyEdm), h.machine().follows)
        assertEquals("still polled from the NHL source", 2, h.nhl.games().size)
        assertTrue(h.nfl.games().isEmpty())
    }

    @Test fun anNflFollowReplacesAnNhlOne() = runTest {
        val h = harness(doc = SportsFollowDoc(game = cgyEdm, pollPhaseMs = 1_500L, firstFollowShown = true))
        h.repo.reportDemand(DemandOwner.FULL_WITH_FOLLOW, true)
        runCurrent()
        assertEquals(1, h.nhl.games().size)

        h.repo.follow(SportsFixtures.nflGame())
        runCurrent()
        assertEquals(kcBuf.copy(startUtcMs = SportsFixtures.nflGame().startUtcMs), h.doc.game)
        assertEquals(League.NFL, h.machine().follows.last()?.league)
        assertEquals(listOf<ScoreEvent>(ScoreEvent.Followed(h.doc.game!!, first = false)), h.events)
        assertEquals(listOf("game:NFL:401899001"), h.nfl.games())
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals("the NHL game is not polled again", 1, h.nhl.games().size)
        assertTrue(h.nfl.games().size >= 3)
    }

    // ---------------------------------------------------------------- the single-source constructor (S7)

    @Test fun theSingleSourceConstructorKeysTheSourceByItsLeague() = runTest {
        val mono = MonoClock { testScheduler.currentTime }
        val store = FakeSettingsStore()
        store.update(SportsFollowDomain) { SportsFollowDoc(game = kcBuf, pollPhaseMs = 1_500L) }
        store.update(SportsDomain) { SportsPrefs(league = League.NFL) }
        val nfl = FakeSource(League.NFL, mono)
        val repo = ScoreRepositoryImpl(
            nfl, store, Awake(), Signals(), backgroundScope, mono,
            machineFactory = { FakeMachine(mono) }, dayPolicy = policy, random = Random(7),
        )
        runCurrent()
        repo.reportDemand(DemandOwner.FULL_WITH_FOLLOW, true)
        repo.reportDemand(DemandOwner.SPORTS_TAB, true)
        runCurrent()
        assertEquals(listOf("game:NFL:401899001", "today"), nfl.calls)
        assertEquals("nfl-today", (repo.day.value as DayState.Board).board.dayKey)
    }
}
