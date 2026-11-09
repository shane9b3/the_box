package app.thebox.ui.guide.screen

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
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
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.PanelState
import app.thebox.ui.guide.GuideDebugCounters
import app.thebox.ui.theme.BoxTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * (owner request 2026-11-09, docs/decisions/2026-11-09-guide-panel-description.md) The focused panel sits at the top
 * of the header column, shows no channel number, name or catch-up days, and its description fills the rest of the
 * column: whole when it fits; otherwise scrolled slowly (setting On) or ellipsized (Off, or system animations off).
 * The guide is drawn on the 1920 × 1080 design canvas at density 1 (1 dp = 1 design px).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FocusedPanelTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val long = (1..40).joinToString(" ") { "Sentence $it of a very long programme description that keeps going." }
    private val short = "A short description."

    private fun panel(desc: String?, title: String = "Evening News", genre: String? = "News", onNow: Boolean = true, catchup: Int = 7) =
        PanelState(101, "Channel Seven", genre, onNow, catchup, title, "19:30 – 20:00 · 30 min", desc, emptyList(), false)

    private lateinit var state: MutableState<GuideUiState>

    private fun show(p: PanelState, scroll: Boolean, autoAdvance: Boolean = true) {
        rule.mainClock.autoAdvance = autoAdvance
        state = mutableStateOf(GuideUiState(panel = p))
        rule.setContent {
            BoxTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.size(1920.dp, 1080.dp)) {
                        GuideScreen(state.value, clock24 = true, poster = false, anchor = null, onHoleMeasured = {}, scrollDescriptions = scroll) {}
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @After fun motionOn() {
        Settings.Global.putFloat(rule.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    private fun layout(): TextLayoutResult {
        val out = ArrayList<TextLayoutResult>()
        rule.onNodeWithTag(PANEL_DESCRIPTION_TAG).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(out) }
        return out.single()
    }

    /** The description text's unclipped top (moves with the scroll offset). */
    private fun descTop() = rule.onNodeWithTag(PANEL_DESCRIPTION_TAG).fetchSemanticsNode().positionInRoot.y

    /** Advances the test clock by about [ms] in frame-sized steps (the clock rounds each step up to a whole frame). */
    private fun advance(ms: Long) {
        val end = rule.mainClock.currentTime + ms
        while (rule.mainClock.currentTime < end) rule.mainClock.advanceTimeBy(minOf(96L, end - rule.mainClock.currentTime))
        rule.waitForIdle()
    }

    /** Advances to [ms] on the test clock (the composition starts at about 0). */
    private fun at(ms: Long) = advance(ms - rule.mainClock.currentTime)

    @Test fun noChannelNumberNameOrCatchupDaysAndThePanelIsAtTheTop() {
        show(panel(short), scroll = true)
        rule.onNodeWithText("Channel Seven").assertDoesNotExist()
        rule.onNodeWithText("101").assertDoesNotExist()
        rule.onNodeWithText("Catch-up", substring = true).assertDoesNotExist()
        rule.onNodeWithText("News").assertExists() // the genre stays
        rule.onNodeWithText("ON NOW", ignoreCase = true).assertExists()
        val tabs = rule.onNodeWithTag(HEADER_TABS_TAG).fetchSemanticsNode().boundsInRoot
        val panel = rule.onNodeWithTag(FOCUSED_PANEL_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals("the panel starts 16 px under the tabs", tabs.bottom + 16f, panel.top, 0.5f)
        val meta = rule.onNodeWithTag(PANEL_META_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals("the meta row is the panel's first line", panel.top, meta.top, 0.5f)
        assertEquals("the panel fills the column to its bottom (y 461)", 461f, panel.bottom, 0.5f)
        val l = layout()
        assertFalse("a short description is shown whole", l.hasVisualOverflow)
    }

    @Test fun anEmptyMetaRowTakesNoSpace() {
        show(panel(short, genre = null, onNow = false), scroll = true)
        rule.onNodeWithTag(PANEL_META_TAG).assertDoesNotExist()
        val panel = rule.onNodeWithTag(FOCUSED_PANEL_TAG).fetchSemanticsNode().boundsInRoot
        val title = rule.onNodeWithText("Evening News").fetchSemanticsNode().boundsInRoot
        assertEquals("the title is the panel's first line", panel.top, title.top, 0.5f)
    }

    @Test fun offShowsEveryLineThatFitsAndEllipsizesTheLast() {
        show(panel(long), scroll = false)
        val l = layout()
        assertTrue("not capped at two lines: ${l.lineCount}", l.lineCount > 2)
        assertTrue("the last visible line ends in an ellipsis", l.isLineEllipsized(l.lineCount - 1))
        val desc = rule.onNodeWithTag(PANEL_DESCRIPTION_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("the description stays inside the header (bottom ${desc.bottom})", desc.bottom <= 461.5f)
        assertTrue("the description reaches the header's bottom: ${desc.bottom}", desc.bottom > 461f - 40f)
        val top0 = descTop()
        advance(10_000)
        assertEquals("Off never scrolls", top0, descTop(), 0.01f)
    }

    @Test fun onHoldsThenScrollsSlowlyToTheEndAndRestartsOnAFocusChange() {
        show(panel(long), scroll = true, autoAdvance = false)
        advance(100)
        val l = layout()
        assertFalse("On lays the text out whole, no ellipsis", (0 until l.lineCount).any { l.isLineEllipsized(it) })
        val view = rule.onNodeWithTag(FOCUSED_PANEL_TAG).fetchSemanticsNode().boundsInRoot
        val top0 = descTop()
        val overflow = l.size.height - (view.bottom - top0)
        assertTrue("the long description overflows: $overflow", overflow > 100f)
        val comps0 = GuideDebugCounters.count(COMPOSITIONS_PANEL)

        at(2_900)
        assertEquals("holds 3 s at the top", top0, descTop(), 0.01f)
        at(5_000) // about 2 s of scrolling at 24 px/s
        val moved = top0 - descTop()
        assertTrue("about 48 px after 2 s of scroll: $moved", moved in 40f..56f)
        val endMs = 3_000 + (overflow / 24f * 1000).toLong()
        at(endMs + 200)
        assertEquals("the last line is in view", top0 - overflow, descTop(), 1.5f)
        at(endMs + 2_800)
        assertEquals("holds 3 s at the end", top0 - overflow, descTop(), 1.5f)
        at(endMs + 3_000 + 400 + 200)
        assertEquals("faded back to the top", top0, descTop(), 0.01f)
        assertEquals("the scroll never recomposes the panel", comps0, GuideDebugCounters.count(COMPOSITIONS_PANEL))

        at(endMs + 3_000 + 400 + 3_500)
        assertTrue("and repeats", descTop() < top0)
        state.value = state.value.copy(panel = panel(long, title = "Late News"))
        advance(100)
        assertEquals("a focus change restarts at the top", top0, descTop(), 0.01f)
        advance(2_500)
        assertEquals("with the 3 s hold", top0, descTop(), 0.01f)
    }

    @Test fun aShortDescriptionNeverScrolls() {
        show(panel(short), scroll = true, autoAdvance = false)
        advance(100)
        val top0 = descTop()
        advance(10_000)
        assertEquals(top0, descTop(), 0.01f)
    }

    @Test fun systemAnimationsOffMeansEllipsisAndNoScroll() {
        Settings.Global.putFloat(rule.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        show(panel(long), scroll = true, autoAdvance = false)
        advance(100)
        val l = layout()
        assertTrue("ellipsized like Off", l.isLineEllipsized(l.lineCount - 1))
        val top0 = descTop()
        advance(10_000)
        assertEquals("no scroll", top0, descTop(), 0.01f)
    }
}
