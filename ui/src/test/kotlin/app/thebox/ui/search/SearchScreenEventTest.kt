package app.thebox.ui.search

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.thebox.core.guide.GuideSearch
import app.thebox.core.guide.ProgrammeHit
import app.thebox.core.guide.SearchResults
import app.thebox.core.guide.fakes.FakeGuideLibrary
import app.thebox.core.guide.fakes.FakeGuideSessionStore
import app.thebox.core.guide.fakes.FakeGuideSettings
import app.thebox.core.guide.fakes.FakeReminderBook
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.ui.theme.BoxTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Event channels, docs/design/m6-event-channels.md §7 step 5 (U-E-S5): with no guide data the programme row is still
 * drawn when it has event hits (events read from channel names need no guide); "Program search needs guide data." shows
 * only when the row is empty.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class SearchScreenEventTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val channel = GuideFixtures.channel(1, name = "Sportsnet+ 09")
    private val hit = ProgrammeHit(
        channel, GuideFixtures.info(GuideFixtures.NOW_MIN + 60, GuideFixtures.NOW_MIN + 240, "Vegas @ Edmonton"), GuideFixtures.NOW_MIN + 240, false,
    )

    private fun show(programmes: List<ProgrammeHit>) {
        val search = object : GuideSearch {
            override suspend fun search(query: String, nowMs: Long, access: app.thebox.core.parental.ParentalAccess) =
                SearchResults(query, emptyList(), programmes, guideDataMissing = true)
        }
        val vm = SearchViewModel(search, FakeReminderBook(), FakeGuideLibrary(listOf(channel)), FakeGuideSettings(), FakeGuideSessionStore()) { GuideFixtures.NOW_MIN * 60_000L }
        rule.setContent { BoxTheme { SearchScreen(vm) } }
        rule.waitForIdle()
        rule.runOnUiThread { vm.onQuery("oilers") }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SearchViewModel.DEBOUNCE_MS + 100))
        rule.waitForIdle()
    }

    @Test
    fun eventHitsAreDrawnWithoutGuideData() {
        show(listOf(hit))
        rule.onNodeWithText("Vegas @ Edmonton").assertExists()
        rule.onNodeWithText("Program search needs guide data.").assertDoesNotExist()
    }

    @Test
    fun anEmptyRowWithoutGuideDataStillSaysSo() {
        show(emptyList())
        rule.onNodeWithText("Program search needs guide data.").assertExists()
    }
}
