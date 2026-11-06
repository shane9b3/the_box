package app.thebox.ui.sports

import app.thebox.core.sports.DayState
import app.thebox.core.sports.DemandOwner
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.GameClock
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.SportsFixtures
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** (docs/design/m6-score-bug.md §5, §8) The Sports tab's view model against the core `FakeScoreRepository`. */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val live = SportsFixtures.game(id = "L1", phase = GamePhase.LIVE, startUtcMs = 3_000)
    private val intermission = SportsFixtures.game(id = "L2", phase = GamePhase.INTERMISSION, startUtcMs = 2_000, clock = GameClock(600, false, true))
    private val pre = SportsFixtures.game(id = "P1", phase = GamePhase.PRE, startUtcMs = 9_000, period = null, periodKind = null, clock = null)
    private val future = SportsFixtures.game(id = "F2", phase = GamePhase.SCHEDULED, startUtcMs = 20_000, period = null, periodKind = null, clock = null)
    private val future1 = SportsFixtures.game(id = "F1", phase = GamePhase.SCHEDULED, startUtcMs = 10_000, period = null, periodKind = null, clock = null)
    private val final = SportsFixtures.game(id = "X1", phase = GamePhase.FINAL, startUtcMs = 1_000, finalKind = PeriodKind.OT)

    private fun vm(repo: FakeScoreRepository, store: app.thebox.core.settings.SettingsStore? = null, mono: () -> Long = { 0L }) =
        SportsViewModel(repo, store, mono) { LocalDate.of(2026, 11, 5) }

    private fun FakeScoreRepository.board(vararg g: GameSnapshot, day: String = "2026-11-05", at: Long = 0L, failing: Boolean = false) {
        this.day.value = DayState.Board(SportsFixtures.board(*g, dayKey = day), at, failing)
    }

    private fun SportsViewModel.cards(): List<GameCardModel> = (state.value.body as SportsBody.Games).cards

    @Test fun orderIsLiveThenPreThenScheduledByStartThenFinal() {
        val repo = FakeScoreRepository()
        val m = vm(repo)
        repo.board(final, future, live, pre, future1, intermission)
        // live and intermission together, by start; then pre; then scheduled by start; then final
        assertEquals(listOf("L2", "L1", "P1", "F1", "F2", "X1"), m.cards().map { it.game.id.id })
    }

    @Test fun enteringAsksForTodayAndSeenDemandAndLeavingDropsIt() {
        val repo = FakeScoreRepository()
        val m = vm(repo)
        m.onEntered()
        assertEquals(listOf<String?>(null), repo.showDayCalls)
        assertTrue(DemandOwner.SPORTS_TAB in repo.demandOn)
        m.onLeft()
        assertTrue(repo.demandOn.isEmpty())
    }

    @Test fun okOnACardFollowsAndOkOnTheFollowedGameStopsFollowing() {
        val repo = FakeScoreRepository()
        val m = vm(repo)
        repo.board(live, pre)
        m.onCard(m.cards().first { it.game.id.id == "L1" })
        assertEquals(live, repo.followCalls.last())
        assertEquals("L1", repo.followed.value?.gameId)
        assertTrue(m.cards().first { it.game.id.id == "L1" }.followed)
        // following another game replaces the current one
        m.onCard(m.cards().first { it.game.id.id == "P1" })
        assertEquals("P1", repo.followed.value?.gameId)
        assertEquals(listOf("P1"), m.cards().filter { it.followed }.map { it.game.id.id })
        // OK on the followed game again stops
        m.onCard(m.cards().first { it.game.id.id == "P1" })
        assertNull(repo.followCalls.last())
        assertNull(repo.followed.value)
    }

    @Test fun initialFocusIsTheFollowedGameThenTheFirstLiveCardThenNone() {
        val repo = FakeScoreRepository()
        val m = vm(repo)
        repo.board(pre, live, final)
        assertEquals(0, SportsViewModel.initialFocusIndex(m.cards())) // live first
        repo.followed.value = SportsFixtures.followedOf(final)
        assertEquals(2, SportsViewModel.initialFocusIndex(m.cards())) // followed wins
        repo.followed.value = null
        repo.board(pre, future, final)
        assertNull(SportsViewModel.initialFocusIndex(m.cards()))
    }

    @Test fun dayChipsAskForTheNeighbourDaysOfTheTodayBoard() {
        val repo = FakeScoreRepository()
        val m = vm(repo)
        m.onEntered()
        repo.board(live)
        m.onDay(SportsDay.YESTERDAY)
        assertEquals("2026-11-04", repo.showDayCalls.last())
        m.onDay(SportsDay.TOMORROW)
        assertEquals("2026-11-06", repo.showDayCalls.last())
        m.onDay(SportsDay.TODAY)
        assertNull(repo.showDayCalls.last())
        assertEquals(SportsDay.TODAY, m.state.value.day)
    }

    @Test fun beforeAnyBoardTheNeighbourDaysComeFromTheEasternDate() {
        val repo = FakeScoreRepository()
        val m = vm(repo)
        m.onEntered()
        m.onDay(SportsDay.YESTERDAY)
        assertEquals("2026-11-04", repo.showDayCalls.last())
    }

    @Test fun aBoardOfAnotherDayNeverPassesForTheChosenDay() {
        val repo = FakeScoreRepository()
        val m = vm(repo)
        m.onEntered()
        repo.board(live)
        m.onDay(SportsDay.YESTERDAY)
        // the repository still holds today's board while yesterday's loads
        assertEquals(SportsBody.Loading, m.state.value.body)
        repo.board(final, day = "2026-11-04")
        assertEquals(listOf("X1"), m.cards().map { it.game.id.id })
        m.onDay(SportsDay.TODAY)
        assertEquals(SportsBody.Loading, m.state.value.body) // yesterday's board is not today's
    }

    @Test fun emptyDayCarriesItsDay() {
        val repo = FakeScoreRepository()
        val m = vm(repo)
        repo.board()
        assertEquals(SportsBody.Empty("2026-11-05", SportsDay.TODAY), m.state.value.body)
        m.onDay(SportsDay.TOMORROW)
        repo.board(day = "2026-11-06")
        assertEquals(SportsBody.Empty("2026-11-06", SportsDay.TOMORROW), m.state.value.body)
    }

    @Test fun failuresKeepTheirKindAndRetryAsksTheRepository() {
        val repo = FakeScoreRepository()
        val m = vm(repo)
        repo.day.value = DayState.Failed(FailKind.OFFLINE)
        assertEquals(SportsBody.Failed(FailKind.OFFLINE), m.state.value.body)
        m.onRetry()
        assertEquals(1, repo.retries)
        // OK on the day chip you are on, while a failure shows, retries too
        m.onDay(SportsDay.TODAY)
        assertEquals(2, repo.retries)
    }

    @Test fun aFailingBoardStaysAndCarriesItsAge() {
        val repo = FakeScoreRepository()
        val m = vm(repo)
        repo.board(live, at = 5_000L, failing = true)
        assertEquals(1, m.cards().size)
        assertEquals(5_000L, m.state.value.updatedMono)
        repo.board(live, at = 9_000L, failing = false)
        assertNull(m.state.value.updatedMono)
    }

    @Test fun withoutARepositoryTheTabSaysSoAndMakesNoRequest() {
        val m = SportsViewModel(null, null, { 0L }) { LocalDate.of(2026, 11, 5) }
        m.onEntered(); m.onDay(SportsDay.YESTERDAY); m.onRetry(); m.onLeft()
        assertEquals(SportsBody.Failed(FailKind.IO), m.state.value.body)
    }

    @Test fun cardClockIsTheSnapshotValueAndNeverTicks() {
        // Owner ruling 6: the card shows the clock exactly as the day list last reported it.
        val running = GameCardModel(SportsFixtures.game(clock = GameClock(622, true, false)), false, anchorMono = 1_000L)
        assertEquals(622, SportsViewModel.clockSecs(running))
        val stopped = running.copy(game = SportsFixtures.game(clock = GameClock(610, false, false)))
        assertEquals(610, SportsViewModel.clockSecs(stopped))
        val negative = running.copy(game = SportsFixtures.game(clock = GameClock(-2, false, false)))
        assertEquals(0, SportsViewModel.clockSecs(negative))
        assertNull(SportsViewModel.clockSecs(running.copy(game = SportsFixtures.game(clock = null))))
    }

    @Test fun aBoardThatIsNotTodaysNeverTeachesTheTodayChip() {
        // U-N5: re-entering with a stale Yesterday board still in the repository must not learn its neighbours.
        val repo = FakeScoreRepository()
        val m = vm(repo)
        repo.day.value = DayState.Board(SportsFixtures.board(live, dayKey = "2026-11-04").copy(prevDay = "2026-11-03", nextDay = "2026-11-05"), 0L, false, isToday = false)
        assertEquals("not today's: skeletons until today's board lands", SportsBody.Loading, m.state.value.body)
        repo.day.value = DayState.Board(SportsFixtures.board(live, dayKey = "2026-11-05"), 0L, false, isToday = true)
        assertEquals(listOf("L1"), m.cards().map { it.game.id.id })
    }

    @Test fun minutesSinceFloorsToWholeMinutes() {
        assertEquals(0, SportsViewModel.minutesSince(0, 59_999))
        assertEquals(2, SportsViewModel.minutesSince(0, 150_000))
        assertEquals(0, SportsViewModel.minutesSince(10, 0))
    }

    @Test fun showSportsOffKeepsTheTabAndSaysHowToTurnItOn() {
        val repo = FakeScoreRepository(app.thebox.core.sports.SportsPrefs(enabled = false))
        val m = vm(repo)
        repo.board(live)
        assertEquals(SportsBody.Off, m.state.value.body)
        repo.prefs.value = app.thebox.core.sports.SportsPrefs(enabled = true)
        assertEquals(listOf("L1"), m.cards().map { it.game.id.id })
    }
}
