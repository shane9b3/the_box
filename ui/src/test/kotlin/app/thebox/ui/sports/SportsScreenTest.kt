package app.thebox.ui.sports

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import app.thebox.core.sports.League
import app.thebox.core.sports.ScoreCorner
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

/** (docs/design/m6-score-bug.md §5, §8 "Sports screen") Initial focus, the chip and grid paths, the texts, the choice lists. */
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
        rule.onNodeWithTag(sportsDayTag(SportsDay.TODAY)).assertIsFocused()
    }

    @Test fun itAsksForTodayOnceAndForTheDayListWhileOpen() {
        board(live)
        show()
        assertEquals(listOf<String?>(null), repo.showDayCalls)
        assertTrue(repo.seen)
    }

    @Test fun chipAndGridPaths() {
        board(pre, future, final, live) // live first: L1, then P1, F1, X1
        show()
        rule.onNodeWithTag(sportsCardTag("L1")).assertIsFocused()
        // Right along the first row, then Up from the third column reaches the selected day chip (not a title chip)
        press(Key.DirectionRight, Key.DirectionRight)
        rule.onNodeWithTag(sportsCardTag("F1")).assertIsFocused()
        press(Key.DirectionUp)
        rule.onNodeWithTag(sportsDayTag(SportsDay.TODAY)).assertIsFocused()
        // Up from the chips: the shown league's chip (NFL §7.3, fix pass U4); Up there: nothing (no top bar is drawn)
        press(Key.DirectionUp)
        rule.onNodeWithTag(sportsLeagueTag(League.NHL)).assertIsFocused()
        press(Key.DirectionUp)
        rule.onNodeWithTag(sportsLeagueTag(League.NHL)).assertIsFocused()
        // Down reverses: the selected day chip, then the first card
        press(Key.DirectionDown)
        rule.onNodeWithTag(sportsDayTag(SportsDay.TODAY)).assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag(sportsCardTag("L1")).assertIsFocused()
        // Down to the second row
        press(Key.DirectionDown)
        rule.onNodeWithTag(sportsCardTag("X1")).assertIsFocused()
        // Left and Right between the chips
        press(Key.DirectionUp, Key.DirectionUp, Key.DirectionRight)
        rule.onNodeWithTag(sportsDayTag(SportsDay.TOMORROW)).assertIsFocused()
    }

    @Test fun okOnADayChipLoadsThatDay() {
        board(live)
        show()
        press(Key.DirectionUp)
        rule.onNodeWithTag(sportsDayTag(SportsDay.TODAY)).assertIsFocused()
        press(Key.DirectionLeft, Key.DirectionCenter)
        assertEquals("2026-11-04", repo.showDayCalls.last())
    }

    @Test fun okFollowsAndTheFooterAndTheChipsFollowTheFocusedCard() {
        board(live, live2, pre)
        show()
        rule.onNodeWithTag(sportsCardTag("L1")).assertIsFocused()
        rule.onNodeWithTag(SPORTS_ACTION_TAG + "L1", useUnmergedTree = true).assertTextEquals(str(R.string.sports_card_action_follow))
        rule.onNodeWithTag(footerHintTag(1)).assertExists()
        rule.onNodeWithText(str(R.string.sports_footer_follow), useUnmergedTree = true).assertExists()
        press(Key.DirectionCenter)
        assertEquals("L1", repo.followed.value?.gameId)
        // One chip at a time (owner request 2026-11-06): the focused followed card shows only the Stop chip
        rule.onNodeWithTag(SPORTS_FOLLOWING_TAG + "L1", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(SPORTS_ACTION_TAG + "L1", useUnmergedTree = true).assertTextEquals(str(R.string.sports_card_action_stop))
        rule.onNodeWithText(str(R.string.sports_footer_stop), useUnmergedTree = true).assertExists()
        // Moved away, the followed card shows "✓ Following"; it is on that card only, and following another game moves it
        press(Key.DirectionRight)
        rule.onNodeWithTag(SPORTS_FOLLOWING_TAG + "L1", useUnmergedTree = true).assertTextEquals(str(R.string.sports_card_following))
        rule.onNodeWithTag(SPORTS_FOLLOWING_TAG + "L2", useUnmergedTree = true).assertDoesNotExist()
        press(Key.DirectionCenter)
        assertEquals("L2", repo.followed.value?.gameId)
        rule.onNodeWithTag(SPORTS_FOLLOWING_TAG + "L1", useUnmergedTree = true).assertDoesNotExist()
        // OK on the followed game stops following
        press(Key.DirectionCenter)
        assertEquals(null, repo.followed.value)
    }

    /** (CHL emulator pass, defect 2) A follow refused as too old: the card never turns to Following. */
    @Test fun aRefusedFollowNeverShowsFollowing() {
        repo.refuseFollowsAsTooOld = true
        board(live, live2)
        show()
        press(Key.DirectionCenter)
        assertEquals(listOf<GameSnapshot?>(live), repo.followCalls)
        assertEquals(null, repo.followed.value)
        rule.onNodeWithTag(SPORTS_ACTION_TAG + "L1", useUnmergedTree = true).assertTextEquals(str(R.string.sports_card_action_follow))
        press(Key.DirectionRight)
        rule.onNodeWithTag(SPORTS_FOLLOWING_TAG + "L1", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun anUnfocusedCardHasNoActionChip() {
        board(live, live2)
        show()
        rule.onNodeWithTag(SPORTS_ACTION_TAG + "L2", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun theLiveCardShowsLiveAndTheClock() {
        board(live) // 2nd, 622 s running
        show()
        rule.onNodeWithTag("sports_state:L1", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("2nd · 10:22", useUnmergedTree = true).assertExists()
        rule.onNodeWithText(str(R.string.sports_live), useUnmergedTree = true).assertExists()
    }

    @Test fun backWithAChoiceListOpenClosesItAndNothingElse() {
        board(pre)
        show()
        assertEquals(true, backFree.last())
        press(Key.DirectionUp) // Today chip is focused already: Up reaches the NHL chip, then Right three times Corner (NFL, CHL)
        press(Key.DirectionRight, Key.DirectionRight, Key.DirectionRight)
        rule.onNodeWithTag(SPORTS_CORNER_TAG).assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(SPORTS_MENU_TAG).assertExists()
        assertFalse("the router must hold BACK while a list is open", backFree.last())
        rule.onNodeWithTag(sportsMenuItemTag(0)).assertIsFocused() // the current value
        press(Key.Back)
        rule.onNodeWithTag(SPORTS_MENU_TAG).assertDoesNotExist()
        assertTrue(backFree.last())
        rule.onNodeWithTag(SPORTS_CORNER_TAG).assertIsFocused()
    }

    @Test fun theCornerListWritesThePref() {
        board(pre)
        show()
        press(Key.DirectionUp, Key.DirectionRight)
        press(Key.DirectionRight, Key.DirectionRight, Key.DirectionCenter) // NHL, then NFL, CHL, Corner
        press(Key.DirectionDown, Key.DirectionCenter) // second entry: Top left
        assertEquals(ScoreCorner.TOP_LEFT, store.flow(SportsDomain).value.corner)
        rule.onNodeWithTag(SPORTS_MENU_TAG).assertDoesNotExist()
        rule.onNodeWithTag(SPORTS_CORNER_TAG).assertIsFocused()
    }

    @Test fun theDelayListStopsAtItsEndsAndWritesThePref() {
        board(pre)
        show()
        press(Key.DirectionUp, Key.DirectionRight)
        press(Key.DirectionRight, Key.DirectionRight, Key.DirectionRight)
        press(Key.DirectionCenter)
        rule.onNodeWithTag(SPORTS_DELAY_TAG).assertExists()
        rule.onNodeWithTag(sportsMenuItemTag(0)).assertIsFocused()
        press(Key.DirectionUp) // the first entry: nothing above
        rule.onNodeWithTag(sportsMenuItemTag(0)).assertIsFocused()
        press(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.DirectionDown) // 0, 5, 10, 15, 20, 30
        press(Key.DirectionCenter)
        assertEquals(30, store.flow(SportsDomain).value.delaySec)
    }

    @Test fun theChipsShowTheCurrentValues() {
        repo.prefs.value = repo.prefs.value.copy(corner = ScoreCorner.BOTTOM_LEFT, delaySec = 30)
        board(pre)
        show()
        rule.onNodeWithText(str(R.string.sports_chip_corner, str(R.string.sports_corner_bottom_left))).assertExists()
        rule.onNodeWithText(str(R.string.sports_chip_delay, "30 s")).assertExists()
        rule.onNodeWithText(str(R.string.sports_credit)).assertExists()
        rule.onNodeWithText(str(R.string.sports_league_nhl)).assertExists()
    }

    @Test fun loadingShowsTheSkeleton() {
        repo.day.value = DayState.Loading
        show()
        rule.onNodeWithTag(SPORTS_SKELETON_TAG).assertExists()
        rule.onNodeWithTag(SPORTS_GRID_TAG).assertDoesNotExist()
        rule.onNodeWithTag(sportsDayTag(SportsDay.TODAY)).assertIsFocused()
    }

    @Test fun anEmptyDayNamesTheDayAndTheNextStep() {
        board()
        show()
        rule.onNodeWithTag(SPORTS_MESSAGE_TAG).assertTextEquals("No NHL games today. Pick another day above.")
        press(Key.DirectionRight, Key.DirectionCenter)
        board(day = "2026-11-06")
        rule.onNodeWithTag(SPORTS_MESSAGE_TAG).assertTextEquals("No NHL games tomorrow. Pick another day above.")
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
        rule.onNodeWithTag(sportsDayTag(SportsDay.TODAY)).assertIsFocused()
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
        rule.onNodeWithTag(sportsDayTag(SportsDay.TODAY)).assertDoesNotExist()
        rule.onNodeWithTag(SPORTS_CORNER_TAG).assertDoesNotExist()
        rule.onNodeWithTag(SPORTS_DELAY_TAG).assertDoesNotExist()
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
        rule.onNodeWithTag(sportsDayTag(SportsDay.TODAY)).assertIsNotFocused()
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
