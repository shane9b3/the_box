package app.thebox.ui.search

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.thebox.core.guide.fakes.FakeGuideLibrary
import app.thebox.core.guide.fakes.FakeGuideSearch
import app.thebox.core.guide.fakes.FakeGuideSessionStore
import app.thebox.core.guide.fakes.FakeGuideSettings
import app.thebox.core.guide.fakes.FakeReminderBook
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.ui.theme.BoxTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Device bug G5 (docs/design/m3-guide.md §5.11): on the TV emulator D-pad Down never left the search field, so no result
 * could be reached with a remote. Down in the field must move focus to the first result row.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class SearchScreenFocusTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun downLeavesTheFieldForTheResults() {
        val channel = GuideFixtures.channel(1, name = "BBC News")
        val vm = SearchViewModel(FakeGuideSearch(listOf(channel)), FakeReminderBook(), FakeGuideLibrary(listOf(channel)), FakeGuideSettings(), FakeGuideSessionStore()) { GuideFixtures.NOW_MIN * 60_000L }
        rule.setContent { BoxTheme { SearchScreen(vm) } }
        rule.waitForIdle()
        rule.onNodeWithTag(SEARCH_FIELD_TAG).assertIsFocused()
        rule.runOnUiThread { vm.onQuery("news") }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SearchViewModel.DEBOUNCE_MS + 100))
        rule.waitForIdle()
        assertEquals(listOf(channel), vm.state.value.results?.channels)

        rule.onNodeWithTag(SEARCH_FIELD_TAG).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithTag(SEARCH_FIELD_TAG).assertIsNotFocused()
        assertEquals("one result card holds focus", 1, rule.onAllNodes(isFocused()).fetchSemanticsNodes().size)
    }

    private fun showResults(): FakeGuideLibrary {
        val channel = GuideFixtures.channel(1, name = "BBC News")
        val library = FakeGuideLibrary(listOf(channel))
        val vm = SearchViewModel(FakeGuideSearch(listOf(channel)), FakeReminderBook(), library, FakeGuideSettings(), FakeGuideSessionStore()) { GuideFixtures.NOW_MIN * 60_000L }
        rule.setContent { BoxTheme { SearchScreen(vm) } }
        rule.waitForIdle()
        rule.runOnUiThread { vm.onQuery("news") }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SearchViewModel.DEBOUNCE_MS + 100))
        rule.waitForIdle()
        rule.onNodeWithTag(SEARCH_FIELD_TAG).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        return library
    }

    private fun openMenuWithoutRepeats() {
        rule.onRoot().performKeyInput { keyDown(Key.DirectionCenter) }
        rule.mainClock.advanceTimeBy(SearchOkGesture.LONG_PRESS_MS + 20)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SearchOkGesture.LONG_PRESS_MS + 20))
        rule.waitForIdle()
        rule.onRoot().performKeyInput { keyUp(Key.DirectionCenter) }
        rule.waitForIdle()
        rule.onNodeWithTag(SEARCH_MENU_TAG).assertExists()
    }

    @Test fun menuKeepsDpadFocusAndBackReturnsToItsSourceCard() {
        showResults()
        openMenuWithoutRepeats()
        rule.onNodeWithText("Add to favourites").assertIsFocused()
        rule.onRoot().performKeyInput {
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionRight)
        }
        rule.onNodeWithText("Add to favourites").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.Back) }
        rule.waitForIdle()
        rule.onNodeWithTag(SEARCH_MENU_TAG).assertDoesNotExist()
        rule.onNodeWithText("BBC News").assertIsFocused()
        rule.onNodeWithTag(SEARCH_FIELD_TAG).assertIsNotFocused()
    }

    @Test fun menuCloseCanBeActivatedWithOkAndRestoresTheCard() {
        showResults()
        openMenuWithoutRepeats()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionDown) }
        rule.onNodeWithText("Close").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        rule.onNodeWithTag(SEARCH_MENU_TAG).assertDoesNotExist()
        rule.onNodeWithText("BBC News").assertIsFocused()
    }

    /**
     * U2 (code review 2026-11-09): a content change keeps the results; an equal answer keeps the menu (its snapshot is
     * still current), a changed answer closes it and focus returns to its card.
     */
    @Test fun invalidatingResultsDismissesTheMenuAndReturnsToItsCard() {
        val channel = GuideFixtures.channel(1, name = "BBC News")
        val library = FakeGuideLibrary(listOf(channel))
        val search = FakeGuideSearch(listOf(channel))
        val vm = SearchViewModel(search, FakeReminderBook(), library, FakeGuideSettings(), FakeGuideSessionStore()) { GuideFixtures.NOW_MIN * 60_000L }
        rule.setContent { BoxTheme { SearchScreen(vm) } }
        rule.waitForIdle()
        rule.runOnUiThread { vm.onQuery("news") }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SearchViewModel.DEBOUNCE_MS + 100))
        rule.waitForIdle()
        rule.onNodeWithTag(SEARCH_FIELD_TAG).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        openMenuWithoutRepeats()
        rule.runOnUiThread { library.tickRowContent() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SearchViewModel.DEBOUNCE_MS + 100))
        rule.waitForIdle()
        rule.onNodeWithTag(SEARCH_MENU_TAG).assertExists()
        search.programmes = listOf(app.thebox.core.guide.ProgrammeHit(channel, GuideFixtures.info(GuideFixtures.NOW_MIN + 60, GuideFixtures.NOW_MIN + 90, "News at Ten"), GuideFixtures.NOW_MIN + 90, false))
        rule.runOnUiThread { library.tickRowContent() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SearchViewModel.DEBOUNCE_MS + 100))
        rule.waitForIdle()
        rule.onNodeWithTag(SEARCH_MENU_TAG).assertDoesNotExist()
        rule.onNodeWithTag(SEARCH_CARD_TAG + channel.rowId).assertIsFocused()
    }

    /** U2: a content refresh with the same query keeps the cards and the focused one. */
    @Test fun contentRefreshKeepsTheFocusedCard() {
        val library = showResults()
        rule.onNodeWithText("BBC News").assertIsFocused()
        rule.runOnUiThread { library.tickRowContent() }
        rule.waitForIdle()
        rule.onNodeWithText("BBC News").assertIsFocused()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SearchViewModel.DEBOUNCE_MS + 100))
        rule.waitForIdle()
        rule.onNodeWithText("BBC News").assertIsFocused()
    }

    /** U1 (code review 2026-11-09): new results that drop the focused card give focus to the field, not the root sink. */
    @Test fun resultsThatRemoveTheFocusedCardReturnFocusToTheField() {
        val bbc = GuideFixtures.channel(1, name = "BBC News")
        val sky = GuideFixtures.channel(2, name = "Sky News")
        var channels = listOf(bbc)
        val search = object : app.thebox.core.guide.GuideSearch {
            override suspend fun search(query: String, nowMs: Long, access: app.thebox.core.parental.ParentalAccess) =
                app.thebox.core.guide.SearchResults(query, channels, emptyList())
        }
        val library = FakeGuideLibrary(listOf(bbc, sky))
        val vm = SearchViewModel(search, FakeReminderBook(), library, FakeGuideSettings(), FakeGuideSessionStore()) { GuideFixtures.NOW_MIN * 60_000L }
        rule.setContent { BoxTheme { SearchScreen(vm) } }
        rule.waitForIdle()
        rule.runOnUiThread { vm.onQuery("news") }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SearchViewModel.DEBOUNCE_MS + 100))
        rule.waitForIdle()
        rule.onNodeWithTag(SEARCH_FIELD_TAG).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithText("BBC News").assertIsFocused()
        channels = listOf(sky)
        rule.runOnUiThread { library.tickRowContent() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SearchViewModel.DEBOUNCE_MS + 100))
        rule.waitForIdle()
        rule.onNodeWithText("BBC News").assertDoesNotExist()
        rule.onNodeWithTag(SEARCH_FIELD_TAG).assertIsFocused()
    }

    @Test fun reopeningSearchRequeriesItsPreservedTextWithoutAnotherEdit() {
        val channel = GuideFixtures.channel(1, name = "BBC News")
        val search = FakeGuideSearch(listOf(channel))
        val vm = SearchViewModel(search, FakeReminderBook(), FakeGuideLibrary(listOf(channel)), FakeGuideSettings(), FakeGuideSessionStore()) { GuideFixtures.NOW_MIN * 60_000L }
        val visible = mutableStateOf(true)
        rule.setContent { BoxTheme { if (visible.value) SearchScreen(vm) } }
        rule.waitForIdle()
        rule.runOnUiThread { vm.onQuery("news") }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SearchViewModel.DEBOUNCE_MS + 100))
        rule.waitForIdle()
        rule.onNodeWithText("BBC News").assertExists()
        rule.runOnUiThread { vm.onBack(); visible.value = false }
        rule.waitForIdle()
        rule.onNodeWithTag(SEARCH_FIELD_TAG).assertDoesNotExist()
        rule.runOnUiThread { visible.value = true }
        rule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SearchViewModel.DEBOUNCE_MS + 100))
        rule.waitForIdle()
        assertEquals("news", vm.state.value.query)
        assertEquals(listOf("news", "news"), search.queries)
        rule.onNodeWithText("BBC News").assertExists()
        rule.onNodeWithTag(SEARCH_FIELD_TAG).assertIsFocused()
    }
}
