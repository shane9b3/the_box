package app.thebox.ui.sports

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.SportsDomain
import app.thebox.core.sports.DayState
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import androidx.compose.ui.test.assertContentDescriptionEquals
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsFixtures
import app.thebox.ui.R
import app.thebox.ui.guide.screen.footerHintTag
import app.thebox.ui.theme.BoxTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** (docs/design/m6-sports-hub.md §1, §2) Initial focus, the chip and grid paths, OK / Menu / Left into the panel and back, Follow as slot 1, the texts. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class SportsScreenTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val live = SportsFixtures.game(id = "L1", phase = GamePhase.LIVE, startUtcMs = 3_000)
    private val live2 = SportsFixtures.game(id = "L2", phase = GamePhase.LIVE, startUtcMs = 4_000)
    private val pre = SportsFixtures.game(id = "P1", phase = GamePhase.PRE, startUtcMs = 9_000, period = null, periodKind = null, clock = null)
    private val future = SportsFixtures.game(id = "F1", phase = GamePhase.SCHEDULED, startUtcMs = 10_000, period = null, periodKind = null, clock = null)
    private val final = SportsFixtures.game(id = "X1", phase = GamePhase.FINAL, startUtcMs = 1_000)

    private val repo = FakeScoreRepository()
    private val store = FakeSettingsStore()
    private val backFree = ArrayList<Boolean>()

    private fun board(vararg g: GameSnapshot, day: String = "2026-11-05", failing: Boolean = false, at: Long = android.os.SystemClock.elapsedRealtime()) {
        repo.day.value = DayState.Board(SportsFixtures.board(*g, dayKey = day), at, failing)
    }

    private fun show() {
        val vm = SportsViewModel(repo, store, { android.os.SystemClock.elapsedRealtime() }) { LocalDate.of(2026, 11, 5) }
        rule.setContent { BoxTheme { SportsScreen(vm, clock24 = false, onBackFreeChanged = { backFree += it }) } }
        rule.waitForIdle()
    }

    private fun press(a: Key) = pressAll(listOf(a))
    private fun press(a: Key, b: Key) = pressAll(listOf(a, b))
    private fun press(a: Key, b: Key, c: Key) = pressAll(listOf(a, b, c))
    private fun press(a: Key, b: Key, c: Key, d: Key, e: Key) = pressAll(listOf(a, b, c, d, e))
    private fun pressAll(keys: List<Key>) {
        for (k in keys) rule.onRoot().performKeyInput { pressKey(k) }
        rule.waitForIdle()
    }

    private fun str(id: Int, vararg args: Any) = rule.activity.getString(id, *args)

    @Test fun initialFocusIsTheFirstLiveCard() {
        board(pre, future, live, final)
        show()
        rule.onNodeWithTag(sportsCardTag("L1")).assertIsFocused()
    }

    @Test fun initialFocusIsTheFollowedGameBeforeALiveOne() {
        repo.followed.value = SportsFixtures.followedOf(final)
        board(live, final)
        show()
        rule.onNodeWithTag(sportsCardTag("X1")).assertIsFocused()
    }

    @Test fun initialFocusIsTheTodayChipWhenNothingIsLive() {
        board(pre, future, final)
        show()
        rule.onNodeWithTag(sportsDayTag(0)).assertIsFocused()
    }

    @Test fun itAsksForTodayOnceAndForTheDayListWhileOpen() {
        board(live)
        show()
        assertEquals(listOf<String?>(null), repo.showDayCalls)
        assertTrue(repo.seen)
    }

    @Test fun theStripHasFifteenDaysAndLabelsTheNeighbours() {
        board(live)
        show()
        for (o in -7..7) rule.onNodeWithTag(sportsDayTag(o)).assertExists()
        rule.onNodeWithText("Today · Nov 5").assertExists()
        rule.onNodeWithText("Yesterday · Nov 4").assertExists()
        rule.onNodeWithText("Tomorrow · Nov 6").assertExists()
        rule.onNodeWithText("Mon · Nov 2").assertExists()
    }

    @Test fun chipAndGridPaths() {
        board(pre, future, final, live) // live first: L1, P1 / F1, X1 (two columns)
        show()
        rule.onNodeWithTag(sportsCardTag("L1")).assertIsFocused()
        // Right to the second column; Up from the first row reaches the selected day chip (not a title chip)
        press(Key.DirectionRight)
        rule.onNodeWithTag(sportsCardTag("P1")).assertIsFocused()
        press(Key.DirectionUp)
        rule.onNodeWithTag(sportsDayTag(0)).assertIsFocused()
        // Up from the chips: the shown league's chip; Up there: nothing (no top bar is drawn)
        press(Key.DirectionUp)
        rule.onNodeWithTag(sportsLeagueTag(League.NHL)).assertIsFocused()
        press(Key.DirectionUp)
        rule.onNodeWithTag(sportsLeagueTag(League.NHL)).assertIsFocused()
        // Down reverses: the selected day chip, then the card focused last on this day (§2.7)
        press(Key.DirectionDown)
        rule.onNodeWithTag(sportsDayTag(0)).assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag(sportsCardTag("P1")).assertIsFocused()
        // Down to the second row, Left to the first column
        press(Key.DirectionDown)
        rule.onNodeWithTag(sportsCardTag("X1")).assertIsFocused()
        // Left and Right between the chips
        press(Key.DirectionUp, Key.DirectionUp, Key.DirectionRight)
        rule.onNodeWithTag(sportsDayTag(1)).assertIsFocused()
    }

    @Test fun okOnADayChipLoadsThatDayAndFocusCostsNothing() {
        board(live)
        show()
        press(Key.DirectionUp)
        rule.onNodeWithTag(sportsDayTag(0)).assertIsFocused()
        press(Key.DirectionLeft)
        rule.onNodeWithTag(sportsDayTag(-1)).assertIsFocused()
        assertEquals("focus alone asks for nothing", listOf<String?>(null), repo.showDayCalls)
        press(Key.DirectionCenter)
        assertEquals("2026-11-04", repo.showDayCalls.last())
        press(Key.DirectionLeft, Key.DirectionLeft, Key.DirectionLeft, Key.DirectionLeft, Key.DirectionLeft)
        press(Key.DirectionLeft)
        rule.onNodeWithTag(sportsDayTag(-7)).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals("2026-10-29", repo.showDayCalls.last())
    }

    // ------------------------------------------------------------------ OK / Menu / Left enter the panel (§2.2, §2.3)

    @Test fun okOnACardEntersTheFirstSlotAndBackReturnsToTheSameCard() {
        board(live, live2, pre)
        show()
        rule.onNodeWithTag(sportsCardTag("L1")).assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(hubSlotTag(0)).assertIsFocused()
        assertFalse("the router holds BACK while focus is in the panel", backFree.last())
        press(Key.Back)
        rule.onNodeWithTag(sportsCardTag("L1")).assertIsFocused()
        assertTrue(backFree.last())
        assertTrue("nothing was followed by entering the panel", repo.followCalls.isEmpty())
    }

    @Test fun menuAndLeftFromTheLeftColumnEnterThePanelToo() {
        board(live, live2, pre)
        show()
        press(Key.Menu)
        rule.onNodeWithTag(hubSlotTag(0)).assertIsFocused()
        press(Key.Back)
        rule.onNodeWithTag(sportsCardTag("L1")).assertIsFocused()
        press(Key.DirectionLeft)
        rule.onNodeWithTag(hubSlotTag(0)).assertIsFocused()
    }

    @Test fun aRightColumnCardEntersItsOwnPanelAndRightFromTheLastSlotReturnsToIt() {
        board(live, live2, pre)
        show()
        press(Key.DirectionRight)
        rule.onNodeWithTag(sportsCardTag("L2")).assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(hubSlotTag(0)).assertIsFocused()
        // Follow is the only enabled slot of a live game with no channel and no goal video: Right returns to the card.
        press(Key.DirectionRight)
        rule.onNodeWithTag(sportsCardTag("L2")).assertIsFocused()
        // Left from the right column is the grid's own move, not the panel.
        press(Key.DirectionLeft)
        rule.onNodeWithTag(sportsCardTag("L1")).assertIsFocused()
    }

    @Test fun thePanelSwitchesWithFocusInTheSameFrame() {
        val other = SportsFixtures.game(
            id = "L9", phase = GamePhase.LIVE, startUtcMs = 4_000,
            away = SportsFixtures.team("TOR", "Maple Leafs", 1), home = SportsFixtures.team("MTL", "Canadiens", 0),
        )
        board(live, other)
        show()
        val first = rule.onNodeWithTag(HUB_HEADLINE_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        press(Key.DirectionRight)
        rule.onNodeWithTag(sportsCardTag("L9")).assertIsFocused()
        val second = rule.onNodeWithTag(HUB_HEADLINE_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue("$first -> $second", first != second && second.contains("Maple Leafs") && second.contains("Canadiens"))
        assertTrue("the headline carries both scores: $second", second.contains("(1)") && second.contains("(0)"))
    }

    @Test fun followIsSlotOneAndTheCardThenShowsFollowing() {
        board(live, live2, pre)
        show()
        press(Key.DirectionCenter, Key.DirectionCenter) // OK enters the panel, OK on Follow
        assertEquals("L1", repo.followed.value?.gameId)
        rule.onNodeWithTag(hubSlotTag(0)).assertContentDescriptionEquals(str(R.string.sports_hub_slot_following))
        // Back to the card: it carries "✓ Following"; the other cards do not.
        press(Key.Back)
        rule.onNodeWithTag(SPORTS_FOLLOWING_TAG + "L1", useUnmergedTree = true).assertTextEquals(str(R.string.sports_hub_chip_following))
        rule.onNodeWithTag(SPORTS_FOLLOWING_TAG + "L2", useUnmergedTree = true).assertDoesNotExist()
        // Following another game replaces it
        press(Key.DirectionRight, Key.DirectionCenter, Key.DirectionCenter)
        assertEquals("L2", repo.followed.value?.gameId)
        // OK on Following ✓ stops following
        press(Key.DirectionCenter)
        assertEquals(null, repo.followed.value)
    }

    /** (CHL emulator pass, defect 2) A follow refused as too old: the card never turns to Following. */
    @Test fun aRefusedFollowNeverShowsFollowing() {
        repo.refuseFollowsAsTooOld = true
        board(live, live2)
        show()
        press(Key.DirectionCenter, Key.DirectionCenter)
        assertEquals(listOf<GameSnapshot?>(live), repo.followCalls)
        assertEquals(null, repo.followed.value)
        rule.onNodeWithTag(hubSlotTag(0)).assertContentDescriptionEquals(str(R.string.sports_hub_slot_follow))
        press(Key.Back)
        rule.onNodeWithTag(SPORTS_FOLLOWING_TAG + "L1", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun theFooterHintsFollowTheZone() {
        board(live, live2)
        show()
        rule.onNodeWithText(str(R.string.sports_hub_hint_options), useUnmergedTree = true).assertExists()
        rule.onNodeWithText(str(R.string.sports_hub_hint_guide), useUnmergedTree = true).assertExists()
        press(Key.DirectionCenter)
        rule.onNodeWithText(str(R.string.sports_hub_hint_choose), useUnmergedTree = true).assertExists()
        rule.onNodeWithText(str(R.string.sports_hub_hint_games), useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText(str(R.string.sports_hub_slot_follow), useUnmergedTree = true).assertCountEquals(2) // the button and the footer's hint
        press(Key.Back, Key.DirectionUp)
        rule.onNodeWithText(str(R.string.sports_hub_hint_show), useUnmergedTree = true).assertExists()
    }

    @Test fun aPostponedCardHasNoOptionsAndOkDoesNothing() {
        val ppd = SportsFixtures.game(id = "PP", phase = GamePhase.POSTPONED, startUtcMs = 5_000, period = null, periodKind = null, clock = null)
        board(ppd)
        show()
        rule.onNodeWithTag(sportsDayTag(0)).assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag(sportsCardTag("PP")).assertIsFocused()
        rule.onNodeWithText(str(R.string.sports_hub_hint_options), useUnmergedTree = true).assertDoesNotExist()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(sportsCardTag("PP")).assertIsFocused()
    }

    @Test fun theLiveCardShowsLiveAndTheClock() {
        board(live) // 2nd, 622 s running
        show()
        rule.onNodeWithTag(SPORTS_STATE_TAG + "L1", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText("LIVE · 2ND 10:22", useUnmergedTree = true).onFirst().assertExists() // the card's pill, the panel's and its art
    }

    @Test fun theChipsShowTheCreditAndTheTitle() {
        board(pre)
        show()
        rule.onNodeWithText(str(R.string.sports_hub_credit_nhl)).assertExists()
        rule.onNodeWithText(str(R.string.sports_league_nhl)).assertExists()
        rule.onNodeWithText(str(R.string.sports_hub_title)).assertExists()
        rule.onNodeWithTag(SPORTS_HEADING_TAG).assertTextEquals("NHL SCORES & SCHEDULE")
        // The mock-up's NEW ribbon, PREVIEW, NBA, Soccer, All Sports, search and profile are not built; nor are Corner and Delay.
        for (word in listOf("NEW", "PREVIEW", "NBA", "Soccer", "All Sports")) rule.onNodeWithText(word).assertDoesNotExist()
        rule.onNodeWithTag(SPORTS_DATE_TAG).assertExists()
        rule.onNodeWithTag(SPORTS_TIME_TAG).assertExists()
    }

    @Test fun loadingShowsTheSkeleton() {
        repo.day.value = DayState.Loading
        show()
        rule.onNodeWithTag(SPORTS_SKELETON_TAG).assertExists()
        rule.onNodeWithTag(SPORTS_GRID_TAG).assertDoesNotExist()
        rule.onNodeWithTag(sportsDayTag(0)).assertIsFocused()
    }

    @Test fun anEmptyDayNamesTheDayAndTheNextStep() {
        board()
        show()
        rule.onNodeWithTag(SPORTS_MESSAGE_TAG).assertTextEquals("No NHL games today. Pick another day above.")
        press(Key.DirectionRight, Key.DirectionCenter)
        board(day = "2026-11-06")
        rule.onNodeWithTag(SPORTS_MESSAGE_TAG).assertTextEquals("No NHL games tomorrow. Pick another day above.")
        // The panel is an empty plate with no buttons
        rule.onNodeWithTag(hubSlotTag(0)).assertDoesNotExist()
    }

    @Test fun noNetworkAndFailureCarryAReasonAndARetry() {
        repo.day.value = DayState.Failed(FailKind.OFFLINE)
        show()
        rule.onNodeWithTag(SPORTS_MESSAGE_TAG).assertTextEquals("No internet connection. Check the network, then press OK to try again.")
        rule.onNodeWithTag(SPORTS_RETRY_TAG).assertExists()
        repo.day.value = DayState.Failed(FailKind.TIMEOUT)
        rule.waitForIdle()
        rule.onNodeWithTag(SPORTS_MESSAGE_TAG).assertTextEquals("NHL.com did not answer. Press OK to try again; scores may be down for a few minutes.")
        // Down from the Today chip reaches Retry; OK retries (and so does OK on the Today chip)
        press(Key.DirectionDown)
        rule.onNodeWithTag(SPORTS_RETRY_TAG).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, repo.retries)
        // U-S3: the Retry chip goes away with the Failed body, so focus is already on the selected day chip.
        rule.onNodeWithTag(sportsDayTag(0)).assertIsFocused()
        press(Key.DirectionCenter) // OK on the Today chip retries too
        assertEquals(2, repo.retries)
    }

    @Test fun afterARetryTheInitialFocusRuleRunsForTheNewBoard() {
        repo.day.value = DayState.Failed(FailKind.TIMEOUT)
        show()
        press(Key.DirectionDown, Key.DirectionCenter)
        repo.day.value = DayState.Loading
        rule.waitForIdle()
        board(pre, live)
        rule.waitForIdle()
        rule.onNodeWithTag(sportsCardTag("L1")).assertIsFocused()
    }

    @Test fun withSportsOffThereAreNoChipsToPick() {
        store.update(SportsDomain) { it.copy(enabled = false) }
        repo.prefs.value = repo.prefs.value.copy(enabled = false)
        board(live)
        show()
        rule.onNodeWithTag(SPORTS_MESSAGE_TAG).assertExists()
        rule.onNodeWithTag(sportsDayTag(0)).assertDoesNotExist()
        rule.onNodeWithTag(HUB_PANEL_TAG).assertDoesNotExist()
        rule.onNodeWithTag(SPORTS_LEAGUE_PILL_TAG).assertExists()
    }

    @Test fun aFailingBoardStaysAndSaysHowOldItIs() {
        board(live, failing = true, at = android.os.SystemClock.elapsedRealtime())
        show()
        rule.onNodeWithTag(sportsCardTag("L1")).assertExists()
        rule.onNodeWithTag(SPORTS_UPDATED_TAG).assertTextEquals(str(R.string.sports_updated_now))
    }

    @Test fun theUpdatedLineIsAbsentWhileTheBoardIsFresh() {
        board(live)
        show()
        rule.onNodeWithTag(SPORTS_UPDATED_TAG).assertDoesNotExist()
        rule.onNodeWithTag(sportsDayTag(0)).assertIsNotFocused()
    }

    @Test fun leavingTheScreenDropsTheDemand() {
        board(live)
        val vm = SportsViewModel(repo, store, { 0L }) { LocalDate.of(2026, 11, 5) }
        val on = androidx.compose.runtime.mutableStateOf(true)
        rule.setContent { BoxTheme { if (on.value) SportsScreen(vm, clock24 = false) } }
        rule.waitForIdle()
        assertTrue(repo.seen)
        rule.runOnUiThread { on.value = false }
        rule.waitForIdle()
        assertFalse(repo.seen)
    }
}
