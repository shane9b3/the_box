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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performSemanticsAction
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

    @Test fun thePageHasNoCreditLine() {
        board(live, live2)
        show()
        rule.onNodeWithText("NHL.com", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun aPostponedCardHasNoOptionsAndOkDoesNothing() {
        val ppd = SportsFixtures.game(id = "PP", phase = GamePhase.POSTPONED, startUtcMs = 5_000, period = null, periodKind = null, clock = null)
        board(ppd)
        show()
        rule.onNodeWithTag(sportsDayTag(0)).assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag(sportsCardTag("PP")).assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(sportsCardTag("PP")).assertIsFocused()
    }

    @Test fun theLiveCardShowsLiveAndTheClock() {
        board(live) // 2nd, 622 s running
        show()
        rule.onNodeWithTag(SPORTS_STATE_TAG + "L1", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText("LIVE · 2ND 10:22", useUnmergedTree = true).onFirst().assertExists() // the card's pill, the panel's and its art
    }

    @Test fun theChipsShowTheTitle() {
        board(pre)
        show()
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
        // It sits on the heading's row, at the right end of the right column (owner change 2026-11-25: the footer is gone).
        val h = rule.onNodeWithTag(SPORTS_HEADING_TAG).fetchSemanticsNode().boundsInRoot
        val u = rule.onNodeWithTag(SPORTS_UPDATED_TAG).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("updated $u right of the heading $h", u.left >= h.right && u.center.y > h.top && u.center.y < h.bottom)
    }

    @Test fun theUpdatedLineIsAbsentWhileTheBoardIsFresh() {
        board(live)
        show()
        rule.onNodeWithTag(SPORTS_UPDATED_TAG).assertDoesNotExist()
        rule.onNodeWithTag(sportsDayTag(0)).assertIsNotFocused()
    }

    // ------------------------------------------------------------------ review 2026-11-25: focus behind sheets and under clips

    private val hubDetail = app.thebox.core.sports.hub.FakeGameDetailRepository()
    /** A clip lookup that never answers (the clip stays Resolving: the layer is up), or fails when [failLookups]. */
    private val lookup = kotlinx.coroutines.CompletableDeferred<Unit>()
    private var failLookups = false
    private val resolver = object : app.thebox.core.sports.hub.ClipResolver {
        override suspend fun info(ref: app.thebox.core.sports.hub.ClipRef) = app.thebox.core.sports.hub.ClipResult.NotFound
        override suspend fun stream(ref: app.thebox.core.sports.hub.ClipRef): app.thebox.core.sports.hub.ClipResult<Pair<app.thebox.core.sports.hub.ClipInfo, app.thebox.core.sports.hub.ClipStream>> {
            if (failLookups) return app.thebox.core.sports.hub.ClipResult.NotFound
            lookup.await()
            return app.thebox.core.sports.hub.ClipResult.NotFound
        }
    }
    private lateinit var hubVm: SportsViewModel

    private fun showHub(matcher: app.thebox.core.sports.hub.GameChannelMatcher? = null, slotsRefuseFocus: Boolean = false) {
        hubVm = SportsViewModel(
            repo, store, { android.os.SystemClock.elapsedRealtime() }, { 1_000_000L }, hubDetail, matcher, null,
            { scope -> ClipSession(app.thebox.ui.shelltest.FakePlaybackController(), app.thebox.core.full.fakes.FakeArchivePlayback(), resolver, scope) },
        ) { LocalDate.of(2026, 11, 5) }
        rule.setContent {
            BoxTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalHubSlotsRefuseFocus provides slotsRefuseFocus) {
                    SportsScreen(hubVm, clock24 = false, onBackFreeChanged = { backFree += it })
                }
            }
        }
        rule.waitForIdle()
    }

    private fun hubGoal(n: Int, clip: Long?) = app.thebox.core.sports.hub.GoalItem(
        app.thebox.core.sports.PeriodLabel(app.thebox.core.sports.PeriodKind.REG, 1), "5:1$n", app.thebox.core.sports.Side.HOME, "G. Scorer$n", "Scorer$n", n,
        emptyList(), app.thebox.core.sports.hub.Strength.EV, false, 0, n, clip, null,
    )

    private fun hubDetailOf(g: GameSnapshot, goals: List<app.thebox.core.sports.hub.GoalItem>, recap: Long? = null) = app.thebox.core.sports.hub.GameDetail(
        g.id, g.phase, null, null, null, null, emptyList(), null, emptyList(), goals, emptyList(), emptyList(), null, null, recap, null, emptyList(),
    )

    private fun ready(d: app.thebox.core.sports.hub.GameDetail) {
        hubDetail.detail.value = app.thebox.core.sports.hub.DetailState.Ready(d, fetchedAtMono = 0L, failing = false, stale = false)
        rule.waitForIdle()
    }

    /** OK on the live card, then Right to Goals (Channels is disabled without a matcher), OK: the Goals sheet. */
    private fun openGoals() {
        press(Key.DirectionCenter)
        rule.onNodeWithTag(hubSlotTag(0)).assertIsFocused()
        press(Key.DirectionRight)
        rule.onNodeWithTag(hubSlotTag(2)).assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(GOALS_SHEET_TAG).assertExists()
    }

    /** (Review H2) A clip plays over the open Goals sheet; a new goal changes the sheet's rows: the clip layer keeps focus. */
    @Test fun aNewGoalUnderAClipNeverPullsFocusIntoTheHiddenSheet() {
        board(live, live2)
        showHub()
        ready(hubDetailOf(live, listOf(hubGoal(1, 11), hubGoal(2, 12))))
        openGoals()
        rule.onNodeWithTag(hubSheetRowTag("goal:1"), useUnmergedTree = true).assertIsFocused()
        press(Key.DirectionCenter) // plays goal 2: the lookup hangs, the layer is up
        rule.onNodeWithTag(CLIP_LAYER_TAG).assertIsFocused()
        ready(hubDetailOf(live, listOf(hubGoal(1, 11), hubGoal(2, 12), hubGoal(3, 13))))
        rule.onNodeWithTag(CLIP_LAYER_TAG).assertIsFocused()
        // Nothing under the layer can be focused either: a card or a hidden row refuses a focus request.
        rule.onNodeWithTag(sportsCardTag("L2")).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        rule.onNodeWithTag(hubSheetRowTag("goal:0"), useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        rule.waitForIdle()
        rule.onNodeWithTag(CLIP_LAYER_TAG).assertIsFocused()
        assertFalse("Back stays held under the clip", backFree.last())
    }

    /** (Review L5, M8) A lookup that fails plays nothing: focus returns to the row the user pressed, not the latest goal. */
    @Test fun afterAFailedLookupFocusReturnsToThePressedRow() {
        failLookups = true
        board(live, live2)
        showHub()
        ready(hubDetailOf(live, listOf(hubGoal(1, 11), hubGoal(2, 12), hubGoal(3, 13))))
        openGoals()
        rule.onNodeWithTag(hubSheetRowTag("goal:2"), useUnmergedTree = true).assertIsFocused()
        press(Key.DirectionUp, Key.DirectionCenter) // goal 2's lookup fails at once
        rule.onNodeWithTag(GOALS_SHEET_TAG).assertExists()
        rule.onNodeWithTag(hubSheetRowTag("goal:1"), useUnmergedTree = true).assertIsFocused()
    }

    /** (Review M8) Back during a recap's lookup returns to slot 1 (Watch recap). */
    @Test fun afterARecapFocusReturnsToSlotOne() {
        val withRecap = final.copy(media = app.thebox.core.sports.GameMedia(recapId = 70L, condensedId = null, goalClips = 0))
        repo.followed.value = SportsFixtures.followedOf(withRecap)
        board(withRecap)
        showHub()
        rule.onNodeWithTag(sportsCardTag("X1")).assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(hubSlotTag(0)).assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(CLIP_LAYER_TAG).assertIsFocused()
        press(Key.Back)
        rule.onNodeWithTag(CLIP_LAYER_TAG).assertDoesNotExist()
        rule.onNodeWithTag(hubSlotTag(0)).assertIsFocused()
        assertFalse("the panel still holds Back", backFree.last())
    }

    /** (Review H1) While a sheet is open nothing behind it takes focus; Retry keeps focus in the sheet and Back still closes it. */
    @Test fun theBoxScoreRetryKeepsFocusAndThePageBehindIsNotFocusable() {
        board(final, live)
        showHub()
        rule.onNodeWithTag(sportsCardTag("L1")).assertIsFocused()
        press(Key.DirectionRight)
        rule.onNodeWithTag(sportsCardTag("X1")).assertIsFocused()
        press(Key.DirectionCenter) // final, no recap: Box score is the first enabled slot
        rule.onNodeWithTag(hubSlotTag(2)).assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(BOX_SCORE_SHEET_TAG).assertExists()
        rule.onNodeWithTag(boxScoreTabTag(app.thebox.core.sports.Side.AWAY), useUnmergedTree = true).assertIsFocused()
        rule.onNodeWithTag(sportsCardTag("L1")).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        rule.onNodeWithTag(sportsDayTag(0)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        rule.waitForIdle()
        rule.onNodeWithTag(sportsCardTag("L1")).assertIsNotFocused()
        rule.onNodeWithTag(boxScoreTabTag(app.thebox.core.sports.Side.AWAY), useUnmergedTree = true).assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag(hubSheetRowTag("retry"), useUnmergedTree = true).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(2, hubDetail.boxScoreCalls.size)
        assertTrue("focus stays in the sheet", rule.onAllNodes(androidx.compose.ui.test.isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag(sportsCardTag("L1")).assertIsNotFocused()
        press(Key.Back)
        rule.onNodeWithTag(BOX_SCORE_SHEET_TAG).assertDoesNotExist()
        rule.onNodeWithTag(hubSlotTag(2)).assertIsFocused()
    }

    /**
     * (Verification review HIGH-1) The Goals slot is usable from the board's goal count while the detail still loads: the
     * sheet opens with its note, its frame holds focus, and Back closes it back to the Goals slot.
     */
    @Test fun goalsWithoutTheDetailHoldFocusAndBackReturnsToTheSlot() {
        val withGoals = live.copy(media = app.thebox.core.sports.GameMedia(null, null, goalClips = 2))
        board(withGoals, live2)
        showHub()
        hubDetail.detail.value = app.thebox.core.sports.hub.DetailState.Loading(withGoals.id)
        rule.waitForIdle()
        openGoals()
        rule.onNodeWithTag(HUB_SHEET_FRAME_TAG, useUnmergedTree = true).assertIsFocused()
        rule.onNodeWithText(str(R.string.sports_hub_goals_loading), useUnmergedTree = true).assertExists()
        assertFalse("Back is held by the sheet", backFree.last())
        press(Key.Back)
        rule.onNodeWithTag(GOALS_SHEET_TAG).assertDoesNotExist()
        rule.onNodeWithTag(hubSlotTag(2)).assertIsFocused()
    }

    /**
     * (Verification review MED-1) A re-match after a phase change empties the open Channels sheet under focus: the frame
     * takes focus and Back closes the sheet onto a card (the Channels slot is now disabled).
     */
    @Test fun aChannelsSheetEmptiedByARematchKeepsBackWorking() {
        val matcher = app.thebox.core.sports.hub.FakeGameChannelMatcher()
        fun cand(name: String) = app.thebox.core.sports.hub.ChannelCandidate(
            app.thebox.core.guide.fakes.GuideFixtures.channel(name.length, name = name), "Main", app.thebox.core.sports.hub.MatchConfidence.LIKELY,
            listOf(app.thebox.core.sports.hub.MatchReason.Broadcaster("SN", app.thebox.core.sports.Market.NATIONAL, "CA")), emptyList(), null,
        )
        matcher.answers[pre.id] = listOf(cand("Sportsnet"), cand("TVA Sports"))
        board(pre)
        showHub(matcher)
        rule.onNodeWithTag(sportsDayTag(0)).assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag(sportsCardTag("P1")).assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(hubSlotTag(0)).assertIsFocused()
        press(Key.DirectionRight)
        rule.onNodeWithTag(hubSlotTag(1)).assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(CHANNEL_SHEET_TAG).assertExists()
        rule.onNodeWithTag(hubSheetRowTag("ch:0"), useUnmergedTree = true).assertIsFocused()
        matcher.answers[pre.id] = emptyList()
        board(pre.copy(phase = GamePhase.LIVE, period = 1, periodKind = app.thebox.core.sports.PeriodKind.REG, clock = app.thebox.core.sports.GameClock(1_100, true, false)))
        rule.waitForIdle()
        rule.onNodeWithTag(hubSheetRowTag("ch:0"), useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(HUB_SHEET_FRAME_TAG, useUnmergedTree = true).assertIsFocused()
        press(Key.Back)
        rule.onNodeWithTag(CHANNEL_SHEET_TAG).assertDoesNotExist()
        rule.onNodeWithTag(sportsCardTag("P1")).assertIsFocused()
    }

    /** (Review M2) The enter request fails for three frames: the page leaves the panel and focus goes back to the card. */
    @Test fun aSlotThatNeverTakesFocusSendsFocusBackToTheCard() {
        board(live, live2)
        showHub(slotsRefuseFocus = true)
        rule.onNodeWithTag(sportsCardTag("L1")).assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(sportsCardTag("L1")).assertIsFocused()
        for (i in 0..2) assertFalse("slot $i", focusedNow(hubSlotTag(i)))
        assertFalse("the page left the panel", hubVm.panelFocused.value)
        assertTrue("Back is free again", backFree.last())
    }

    private fun focusedNow(tag: String): Boolean =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }

    /** (Integration focus trap, review M8) Left from the first title chip or the first day chip never lands on a slot. */
    @Test fun leftFromTheFirstChipsNeverEntersThePanel() {
        board(pre, future)
        show()
        rule.onNodeWithTag(sportsDayTag(0)).assertIsFocused()
        press(Key.DirectionUp)
        rule.onNodeWithTag(sportsLeagueTag(League.NHL)).assertIsFocused()
        press(Key.DirectionLeft)
        for (i in 0..2) assertFalse("slot $i", focusedNow(hubSlotTag(i)))
        press(Key.DirectionDown)
        rule.onNodeWithTag(sportsDayTag(0)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        rule.waitForIdle()
        for (k in 0 until 8) press(Key.DirectionLeft)
        rule.onNodeWithTag(sportsDayTag(-7)).assertIsFocused()
        for (i in 0..2) assertFalse("slot $i", focusedNow(hubSlotTag(i)))
        assertTrue("Back is free: the page never entered the panel", backFree.last())
    }

    /** (Review M6) The focused card leaves the board on a re-poll: its neighbour by index takes focus. */
    @Test fun aFocusedCardThatLeavesTheBoardHandsFocusToItsNeighbour() {
        board(live, live2, pre)
        show()
        rule.onNodeWithTag(sportsCardTag("L1")).assertIsFocused()
        board(live2, pre)
        rule.waitForIdle()
        rule.onNodeWithTag(sportsCardTag("L2")).assertIsFocused()
    }

    /** (Review M1, M6) The panel's game leaves the board while focus is in its panel: the page leaves the panel for the neighbour. */
    @Test fun thePanelsGameLeavingTheBoardReturnsFocusToACard() {
        board(live, live2, pre)
        show()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(hubSlotTag(0)).assertIsFocused()
        board(live2, pre)
        rule.waitForIdle()
        rule.onNodeWithTag(sportsCardTag("L2")).assertIsFocused()
        assertTrue("Back is free again", backFree.last())
        press(Key.DirectionCenter, Key.DirectionCenter)
        assertEquals("slot 1 acts on the game shown, not the vanished one", "L2", repo.followed.value?.gameId)
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
