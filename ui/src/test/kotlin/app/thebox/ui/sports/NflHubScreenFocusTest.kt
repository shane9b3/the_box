package app.thebox.ui.sports

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.SportsDomain
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.DayState
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsPrefs
import app.thebox.core.sports.hub.DetailState
import app.thebox.core.sports.hub.FakeGameDetailRepository
import app.thebox.core.sports.hub.GameDetail
import app.thebox.ui.theme.BoxTheme
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (NFL hub code review 2026-11-26, UI M1) On the page: the Matchup sheet open at kick-off closes by itself, and focus goes
 * to the panel's first usable slot (Follow), never nowhere with Back held.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class NflHubScreenFocusTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val repo = FakeScoreRepository(SportsPrefs(league = League.NFL))
    private val store = FakeSettingsStore().also { it.update(SportsDomain) { p -> p.copy(league = League.NFL) } }
    private val detail = FakeGameDetailRepository()
    private val backFree = ArrayList<Boolean>()

    private fun board(vararg g: GameSnapshot) {
        repo.day.value = DayState.Board(DayBoard("w2026-2-13", "w2026-2-12", null, g.toList()), android.os.SystemClock.elapsedRealtime(), false, true)
        rule.waitForIdle()
    }

    private fun ready(d: GameDetail) {
        detail.detail.value = DetailState.Ready(d, fetchedAtMono = 0L, failing = false, stale = false)
        rule.waitForIdle()
    }

    private fun press(k: Key) {
        rule.onRoot().performKeyInput { pressKey(k) }
        rule.waitForIdle()
    }

    @Test fun theMatchupSheetClosesAtKickoffAndFocusGoesToTheFirstUsableSlot() {
        val pre = NflHubTestKit.pre()
        board(pre)
        val vm = SportsViewModel(repo, store, { android.os.SystemClock.elapsedRealtime() }, { NflHubTestKit.PRE_START - 600_000L }, detail, null, null, { null }) {
            LocalDate.of(2026, 11, 30)
        }
        rule.setContent { BoxTheme { SportsScreen(vm, clock24 = false, onBackFreeChanged = { backFree += it }) } }
        rule.waitForIdle()
        ready(NflHubTestKit.preDetail(pre))
        // Nothing is live or followed: the week chip has focus; Down reaches the card.
        press(Key.DirectionDown)
        rule.onNodeWithTag(sportsCardTag(pre.id.id)).assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(hubSlotTag(0)).assertIsFocused()
        press(Key.DirectionRight) // Channels is grey without a matcher: Right lands on Matchup
        rule.onNodeWithTag(hubSlotTag(2)).assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(MATCHUP_SHEET_TAG).assertExists()
        // Kick-off: the board and the summary turn live.
        val live = pre.copy(phase = GamePhase.LIVE)
        board(live)
        ready(NflHubTestKit.nflDetail(live, injuries = NflHubTestKit.PRE_INJURIES))
        rule.onNodeWithTag(MATCHUP_SHEET_TAG).assertDoesNotExist()
        assertNull(vm.sheet.value)
        rule.onNodeWithTag(hubSlotTag(0)).assertIsFocused()
        assertFalse("the panel still holds Back", backFree.last())
    }
}
