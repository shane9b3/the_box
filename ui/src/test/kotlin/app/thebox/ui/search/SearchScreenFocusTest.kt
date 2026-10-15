package app.thebox.ui.search

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
}
