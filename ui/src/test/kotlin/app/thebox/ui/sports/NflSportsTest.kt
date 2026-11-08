package app.thebox.ui.sports

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.SportsDomain
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.DayState
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.GameClock
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.LeagueDetail
import app.thebox.core.sports.NflBreak
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.SportsPrefs
import app.thebox.ui.R
import app.thebox.ui.theme.BoxTheme
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (NFL WP-5, docs/design/m6-nfl.md §7, §10.2) The league chips, the week chips (no date fallback, S6), the NFL card
 * words ("Sun 1:00 PM", "Halftime", "End 3rd") and the credit. The followed game's row and the picker are in
 * [NflPickerTest].
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class NflSportsTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val savedZone = TimeZone.getDefault()
    @Before fun zone() = TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    @After fun unzone() = TimeZone.setDefault(savedZone)

    private val live = SportsFixtures.nflGame(id = "N1")
    private val pre = SportsFixtures.nflGame(
        id = "N2", phase = GamePhase.PRE, period = null, periodKind = null, clock = null, detail = null,
        away = SportsFixtures.nflTeam("SF", "San Francisco 49ers", "49ers"), home = SportsFixtures.nflTeam("SEA", "Seattle Seahawks", "Seahawks"),
        startUtcMs = ZonedDateTime.of(2026, 11, 8, 21, 25, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli(), // 4:25 PM ET Sunday
    )

    private val repo = FakeScoreRepository(SportsPrefs(league = League.NFL))
    private val store = FakeSettingsStore().also { it.update(SportsDomain) { p -> p.copy(league = League.NFL) } }

    private fun week(vararg g: GameSnapshot, key: String = "w2026-2-9", prev: String? = "w2026-2-8", next: String? = "w2026-2-10", isToday: Boolean = true) {
        repo.day.value = DayState.Board(DayBoard(key, prev, next, g.toList()), android.os.SystemClock.elapsedRealtime(), false, isToday)
    }

    private fun vm() = SportsViewModel(repo, store, { android.os.SystemClock.elapsedRealtime() }) { LocalDate.of(2026, 11, 8) }

    private fun show(vm: SportsViewModel = vm()) {
        rule.setContent { BoxTheme { SportsScreen(vm, clock24 = false) } }
        rule.waitForIdle()
    }

    private fun press(a: Key, b: Key? = null) {
        for (k in listOfNotNull(a, b)) rule.onRoot().performKeyInput { pressKey(k) }
        rule.waitForIdle()
    }

    private fun str(id: Int, vararg a: Any) = rule.activity.getString(id, *a)

    // ------------------------------------------------------------------ the league chips

    @Test fun bothLeagueChipsShowAndTheShownOneIsSelectedLeagueSwitchWritesThePref() {
        store.update(SportsDomain) { it.copy(league = League.NHL) }
        repo.prefs.value = SportsPrefs(league = League.NHL)
        repo.day.value = DayState.Board(SportsFixtures.board(SportsFixtures.game(id = "H1")), 0L, false)
        show()
        rule.onNodeWithTag(sportsLeagueTag(League.NHL)).assertExists()
        rule.onNodeWithTag(sportsLeagueTag(League.NFL)).assertExists()
        rule.onNodeWithText(str(R.string.sports_credit)).assertExists()
        // Fix pass U4 (§7.3): Up from the day chips lands on the shown league's chip; Right reaches NFL; OK switches.
        press(Key.DirectionUp, Key.DirectionUp)
        rule.onNodeWithTag(sportsLeagueTag(League.NHL)).assertIsFocused()
        press(Key.DirectionRight)
        rule.onNodeWithTag(sportsLeagueTag(League.NFL)).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(League.NFL, store.flow(SportsDomain).value.league)
        // Down from a league chip reaches the selected day chip; Up is consumed.
        press(Key.DirectionUp)
        rule.onNodeWithTag(sportsLeagueTag(League.NFL)).assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag(sportsDayTag(SportsDay.TODAY)).assertIsFocused()
    }

    @Test fun anNflBoardReadsLastThisNextWeekWithTheEspnCreditAndTheWeekLabel() {
        week(live, pre)
        show()
        rule.onNodeWithText(str(R.string.sports_credit_espn)).assertExists()
        rule.onNodeWithText(str(R.string.sports_week_last)).assertExists()
        rule.onNodeWithText(str(R.string.sports_week_this)).assertExists()
        rule.onNodeWithText(str(R.string.sports_week_next)).assertExists()
        rule.onNodeWithTag(SPORTS_WEEK_TAG).assertTextEquals("Week 9")
        // the cards use the short names
        rule.onNodeWithText("Chiefs").assertExists()
        rule.onNodeWithText("Seahawks").assertExists()
        rule.onNodeWithTag(sportsDayTag(SportsDay.YESTERDAY)).assertIsEnabled()
        rule.onNodeWithTag(sportsDayTag(SportsDay.TOMORROW)).assertIsEnabled()
    }

    @Test fun theWeekChipsAskForTheKeysTheBoardNamedNeverADate() {
        week(live)
        val m = vm()
        show(m)
        m.onDay(SportsDay.YESTERDAY)
        assertEquals("w2026-2-8", repo.showDayCalls.last())
        m.onDay(SportsDay.TOMORROW)
        assertEquals("w2026-2-10", repo.showDayCalls.last())
        m.onDay(SportsDay.TODAY)
        assertNull(repo.showDayCalls.last())
        assertTrue(repo.showDayCalls.filterNotNull().none { Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(it) })
    }

    @Test fun aWeekRolloverWhileTheViewModelLivesShowsTheNewWeekNotTheSkeleton() {
        // Fix pass U2: the activity-scoped view model learned Week 9's neighbours; on Wednesday ESPN's current week moves
        // to Week 10, the key it had learned as Next week. The new This week must show, and teach Week 9 / Week 11.
        week(live)
        val m = vm()
        show(m)
        rule.onNodeWithTag(SPORTS_WEEK_TAG).assertTextEquals("Week 9")
        m.onLeft()
        week(pre, key = "w2026-2-10", prev = "w2026-2-9", next = "w2026-2-11")
        m.onEntered()
        rule.waitForIdle()
        rule.onNodeWithTag(SPORTS_SKELETON_TAG).assertDoesNotExist()
        rule.onNodeWithTag(SPORTS_WEEK_TAG).assertTextEquals("Week 10")
        rule.onNodeWithText("Seahawks").assertExists()
        m.onDay(SportsDay.TOMORROW)
        assertEquals("w2026-2-11", repo.showDayCalls.last())
        m.onDay(SportsDay.TODAY)
        m.onDay(SportsDay.YESTERDAY)
        assertEquals("w2026-2-9", repo.showDayCalls.last())
    }

    @Test fun aMissingNeighbourDisablesItsChipAtAnEdge() {
        week(live, key = "w2026-3-5", prev = "w2026-3-3", next = null)
        show()
        rule.onNodeWithTag(sportsDayTag(SportsDay.YESTERDAY)).assertIsEnabled()
        rule.onNodeWithTag(sportsDayTag(SportsDay.TOMORROW)).assertIsNotEnabled()
        rule.onNodeWithTag(SPORTS_WEEK_TAG).assertTextEquals("Super Bowl")
    }

    @Test fun beforeTheFirstBoardBothNeighboursAreDisabledAndNothingIsRequested() {
        repo.day.value = DayState.Loading
        val m = vm()
        show(m)
        rule.onNodeWithTag(sportsDayTag(SportsDay.YESTERDAY)).assertIsNotEnabled()
        rule.onNodeWithTag(sportsDayTag(SportsDay.TOMORROW)).assertIsNotEnabled()
        m.onDay(SportsDay.YESTERDAY)
        m.onDay(SportsDay.TOMORROW)
        assertEquals("only the open's own request", listOf<String?>(null), repo.showDayCalls)
    }

    @Test fun anEmptyWeekAndTheOffSeasonCarryTheirOwnWords() {
        week(key = "w2026-2-9") // empty, neighbours named by the feed: the chips are still disabled on an empty board (§7.2)
        show()
        rule.onNodeWithTag(sportsDayTag(SportsDay.YESTERDAY)).assertIsNotEnabled()
        rule.onNodeWithTag(SPORTS_MESSAGE_TAG).assertTextEquals("No NFL games this week.")
        rule.onNodeWithTag(sportsDayTag(SportsDay.TODAY)).assertIsEnabled()
    }

    @Test fun anNflFailureSaysEspn() {
        repo.day.value = DayState.Failed(FailKind.TIMEOUT)
        show()
        rule.onNodeWithTag(SPORTS_MESSAGE_TAG).assertTextEquals(str(R.string.sports_failed_espn))
        // Integration-pass note: with no board ever loaded no neighbour is known, so both week chips are disabled.
        rule.onNodeWithTag(sportsDayTag(SportsDay.YESTERDAY)).assertIsNotEnabled()
        rule.onNodeWithTag(sportsDayTag(SportsDay.TOMORROW)).assertIsNotEnabled()
        rule.onNodeWithTag(sportsDayTag(SportsDay.TODAY)).assertIsEnabled()
    }

    @Test fun aStaleBoardOfTheOtherLeagueNeverPassesForTheShownOne() {
        repo.day.value = DayState.Board(SportsFixtures.board(SportsFixtures.game(id = "H1")), 0L, false)
        show()
        rule.onNodeWithTag(SPORTS_SKELETON_TAG).assertExists()
    }

    // ------------------------------------------------------------------ the words

    @Test fun upFromTheWeekChipsLandsOnTheNflChip() {
        week(live)
        show()
        press(Key.DirectionUp) // the card → This week
        rule.onNodeWithTag(sportsDayTag(SportsDay.TODAY)).assertIsFocused()
        press(Key.DirectionRight)
        rule.onNodeWithTag(sportsDayTag(SportsDay.TOMORROW)).assertIsFocused()
        press(Key.DirectionUp)
        rule.onNodeWithTag(sportsLeagueTag(League.NFL)).assertIsFocused()
    }

    @Test fun withShowSportsOffThePillNamesTheShownLeague() {
        store.update(SportsDomain) { it.copy(enabled = false, league = League.NFL) }
        repo.prefs.value = repo.prefs.value.copy(enabled = false, league = League.NFL)
        show()
        rule.onNodeWithTag(SPORTS_LEAGUE_PILL_TAG).assertTextEquals(str(R.string.sports_league_nfl))
    }

    @Test fun cardWords() {
        val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val sunday = ZonedDateTime.of(2026, 11, 8, 13, 0, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        val sched = SportsFixtures.nflGame(phase = GamePhase.SCHEDULED, period = null, periodKind = null, clock = null, detail = null, startUtcMs = sunday)
        val wed = sunday - 4 * 86_400_000L
        val sameDay = sunday - 3 * 3_600_000L
        assertEquals("Sun 1:00 PM", SportsText.state(res, sched, clock24 = false, nowMs = wed))
        assertEquals("1:00 PM", SportsText.state(res, sched, clock24 = false, nowMs = sameDay))
        assertEquals("Sun 13:00", SportsText.state(res, sched, clock24 = true, nowMs = wed))
        assertEquals("4th 3:42", SportsText.state(res, live, clock24 = false))
        val half = SportsFixtures.nflGame(
            phase = GamePhase.INTERMISSION, period = 2, clock = GameClock(0, false, true),
            detail = SportsFixtures.nflDetail(breakKind = NflBreak.HALFTIME),
        )
        assertEquals("Halftime", SportsText.state(res, half, clock24 = false))
        val end3 = SportsFixtures.nflGame(
            phase = GamePhase.INTERMISSION, period = 3, clock = GameClock(0, false, true),
            detail = SportsFixtures.nflDetail(breakKind = NflBreak.END_PERIOD),
        )
        assertEquals("End 3rd", SportsText.state(res, end3, clock24 = false))
        val ot = SportsFixtures.nflGame(phase = GamePhase.FINAL, period = 5, periodKind = PeriodKind.OT, otNumber = 1, finalKind = PeriodKind.OT, detail = null)
        assertEquals("Final/OT", SportsText.state(res, ot, clock24 = false))
        // the NHL keeps "P4"; the NFL says "4th"
        val p4 = app.thebox.core.sports.PeriodLabel(PeriodKind.REG, 4)
        assertEquals("P4", SportsText.period(res, p4))
        assertEquals("4th", SportsText.period(res, p4, League.NFL))
        assertEquals("ESPN did not answer. Press OK to try again; scores may be down for a few minutes.", SportsText.failure(res, FailKind.TIMEOUT, League.NFL))
        assertEquals("No internet connection. Check the network, then press OK to try again.", SportsText.failure(res, FailKind.OFFLINE, League.NFL))
        assertEquals("Week 9", SportsText.weekLabel(res, "w2026-2-9"))
        assertEquals("Wild Card", SportsText.weekLabel(res, "w2026-3-1"))
        assertEquals("NFL", SportsText.weekLabel(res, "2026-11-08"))
        assertTrue(SportsText.isWeekKey("w2026-2-9"))
        assertFalse(SportsText.isWeekKey("2026-11-08"))
        assertEquals(Locale.US, Locale.getDefault())
    }

    @Test fun theLiveCardDetailUsesTheNflQuarter() {
        week(live)
        show()
        rule.onNodeWithText("4th · 3:42", useUnmergedTree = true).assertExists()
    }

    @Test fun orderIsLiveThenUpcomingThenFinalThenPostponed() {
        val fin = SportsFixtures.nflGame(id = "F", phase = GamePhase.FINAL, startUtcMs = 1_000, detail = null)
        val ppd = SportsFixtures.nflGame(id = "P", phase = GamePhase.POSTPONED, startUtcMs = 500, detail = null)
        val up = SportsFixtures.nflGame(id = "U", phase = GamePhase.SCHEDULED, startUtcMs = 9_000, detail = null)
        val pre1 = SportsFixtures.nflGame(id = "R", phase = GamePhase.PRE, startUtcMs = 8_000, detail = null)
        val ids = SportsViewModel.order(listOf(ppd, fin, up, live, pre1), League.NFL).map { it.id.id }
        assertEquals(listOf("N1", "R", "U", "F", "P"), ids)
        assertTrue(LeagueDetail.Nfl::class.java.isInstance(live.detail))
    }
}
