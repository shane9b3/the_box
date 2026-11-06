package app.thebox.ui.guide.screen

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.SyncChip
import app.thebox.core.guide.Zone
import app.thebox.ui.guide.overlay.SYNC_CHIP_TAG
import app.thebox.ui.theme.BoxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeaderTabsTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val failure = SyncChip("Guide update failed. Details arrive with Settings.", failed = true)

    private fun show(date: String, time: String, sync: SyncChip?) {
        rule.setContent {
            BoxTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.width(1024.dp)) {
                        HeaderTabsRow(Zone.GRID, 0, sync) { HeaderClock(date, time) }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun assertClockIsComplete(time: String) {
        val layouts = ArrayList<TextLayoutResult>()
        rule.onNodeWithTag(HEADER_CLOCK_TAG).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(time, layout.layoutInput.text.text)
        assertFalse("time must not be clipped", layout.didOverflowWidth)
        assertFalse("time must not be ellipsized", layout.isLineEllipsized(0))
        val clock = rule.onNodeWithTag(HEADER_CLOCK_TAG).fetchSemanticsNode().boundsInRoot
        val row = rule.onNodeWithTag(HEADER_TABS_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals("clock stays at the header's right edge", row.right, clock.right, 0.5f)
        assertTrue("clock stays inside the header", clock.left >= row.left)
    }

    /** (docs/design/m6-score-bug.md §5.1, U-N10) Guide · Search · Sports · gear, and the machine's tab count agrees. */
    @Test fun fourTabsAreDrawnInOrderAndTheClockStillFitsAt1024() {
        assertEquals(listOf(GuideTab.GUIDE, GuideTab.SEARCH, GuideTab.SPORTS, GuideTab.SETTINGS), SHOWN_TABS)
        assertEquals(app.thebox.core.guide.state.GuideStateMachineImpl.TAB_COUNT, SHOWN_TABS.size)
        assertEquals(app.thebox.core.guide.state.GuideStateMachineImpl.TAB_SPORTS, SHOWN_TABS.indexOf(GuideTab.SPORTS))
        assertEquals(app.thebox.core.guide.state.GuideStateMachineImpl.TAB_SETTINGS, SHOWN_TABS.indexOf(GuideTab.SETTINGS))
        show("Wednesday 30 September", "11:59 PM", failure) // the longest sync text and the longest date and time
        rule.onNodeWithText("Sports").assertExists()
        assertClockIsComplete("11:59 PM")
        val status = rule.onNodeWithTag(SYNC_CHIP_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("the status keeps useful space beside four tabs", status.width > 40f)
    }

    @Test fun syncFailureAndLongDateCannotTruncateThe24HourClock() {
        show("Wednesday 30 September", "00:00", failure)
        assertClockIsComplete("00:00")
        val status = rule.onNodeWithTag(SYNC_CHIP_TAG).fetchSemanticsNode().boundsInRoot
        val date = rule.onNodeWithTag(HEADER_DATE_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("status still has useful space", status.width > 40f)
        assertTrue("status stays before the date", status.right <= date.left)
    }

    @Test fun syncFailureAndLongDateCannotTruncateThe12HourClock() {
        show("Wednesday 30 September", "11:59 PM", failure)
        assertClockIsComplete("11:59 PM")
        val status = rule.onNodeWithTag(SYNC_CHIP_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("the status remains visible", status.width > 40f)
    }

    @Test fun shortAndAbsentStatusKeepTheClockRightAligned() {
        val sync = mutableStateOf<SyncChip?>(null)
        rule.setContent {
            BoxTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.width(1024.dp)) {
                        HeaderTabsRow(Zone.GRID, 0, sync.value) { HeaderClock("Tue 20 Oct", "20:10") }
                    }
                }
            }
        }
        rule.waitForIdle()
        assertClockIsComplete("20:10")
        rule.runOnUiThread { sync.value = SyncChip("Updating…", failed = false) }
        rule.waitForIdle()
        assertClockIsComplete("20:10")
        rule.onNodeWithTag(SYNC_CHIP_TAG).assertExists()
    }
}
