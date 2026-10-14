package app.thebox.ui.root

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.thebox.core.guide.DayItem
import app.thebox.core.guide.EmptyKind
import app.thebox.core.guide.GroupsShortcut
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.GuideToast
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.MenuItem
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.ReminderAlert
import app.thebox.core.guide.ReminderCardState
import app.thebox.core.guide.SourceSection
import app.thebox.core.guide.TunedState
import app.thebox.core.guide.fakes.FakeGuideStateMachine
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.ui.guide.overlay.CONTEXT_MENU_TAG
import app.thebox.ui.guide.overlay.DAY_STRIP_TAG
import app.thebox.ui.guide.overlay.EMPTY_STATE_TAG
import app.thebox.ui.guide.overlay.EXIT_DIALOG_TAG
import app.thebox.ui.guide.overlay.EXIT_HINT_TAG
import app.thebox.ui.guide.overlay.GROUPS_PANEL_TAG
import app.thebox.ui.guide.overlay.MIN_TOP
import app.thebox.ui.guide.overlay.PROGRAMME_SHEET_TAG
import app.thebox.ui.guide.overlay.anchoredOrigin
import app.thebox.ui.guide.screen.ERROR_CARD_TAG
import app.thebox.ui.guide.screen.HOLE_SLOT_TAG
import app.thebox.ui.reminder.REMINDER_CARD_TAG
import app.thebox.ui.shelltest.FakeShellHost
import app.thebox.ui.theme.BoxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * No guide overlay intersects the hole in GUIDE (docs/design/m3-guide.md §3.13.1, §4.5; the JVM side of I-3), the hole
 * slot publishes the §3.1 rect, and the anchoring rule of §3.15. 1920 × 1080 window, so 1 design px = 1 px.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class OverlayBoundsTest {
    @Suppress("DEPRECATION") // v1 rule: UnconfinedTestDispatcher, matching how these tests synchronise
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val hole = Rect(64f, 56f, 784f, 461f)
    private val tuned = TunedState(identity = GuideFixtures.identity(1), rowId = 1, phase = MiniPhase.PLAYING)
    private val machine = FakeGuideStateMachine(GuideUiState(tuned = tuned))
    private val host = FakeShellHost(machine)

    private fun render() {
        rule.setContent { BoxTheme { AppRoot(host, grid = { m -> Spacer(m) }) } }
        rule.waitForIdle()
    }

    private fun show(state: GuideUiState) {
        machine.mutableUi.value = state
        rule.waitForIdle()
    }

    private fun assertOutsideHole(tag: String) {
        val b = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("$tag has a size: $b", b.width > 0f && b.height > 0f)
        assertFalse("$tag $b overlaps the hole $hole", b.overlaps(hole))
    }

    @Test
    fun holeSlotIsTheDesignRect() {
        render()
        assertEquals(hole, rule.onNodeWithTag(HOLE_SLOT_TAG).fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun noOverlayIntersectsTheHole() {
        render()
        val base = GuideUiState(tuned = tuned)
        val entries = (1..30).map { GuideSourceEntry(GuideSource.Group(1, "g$it"), "Group $it", it, SourceSection.GROUP, null) }
        show(base.copy(overlay = Overlay.Groups(listOf(GroupsShortcut.SEARCH, GroupsShortcut.SETTINGS), entries, focused = 20, current = 3, menu = null, menuFocused = 0)))
        assertOutsideHole(GROUPS_PANEL_TAG)
        show(base.copy(overlay = Overlay.Days(List(15) { DayItem(it, "Day $it", it % 4 != 0) }, focused = 5)))
        assertOutsideHole(DAY_STRIP_TAG)
        show(base.copy(overlay = Overlay.Menu(MenuItem.entries, focused = 2)))
        assertOutsideHole(CONTEXT_MENU_TAG)
        show(base.copy(overlay = Overlay.Sheet(reminderSet = true, focused = 1)))
        assertOutsideHole(PROGRAMME_SHEET_TAG)
        show(base.copy(overlay = Overlay.Exit(dialog = true, focused = 0)))
        assertOutsideHole(EXIT_DIALOG_TAG)
        show(base.copy(overlay = Overlay.Exit(dialog = false, focused = 0)))
        assertOutsideHole(EXIT_HINT_TAG)
        show(base.copy(empty = EmptyKind.NO_GUIDE_DATA))
        assertOutsideHole(EMPTY_STATE_TAG)
        show(base.copy(reminder = ReminderCardState(ReminderAlert(1, tuned.identity!!, "Northline", "News", 0, late = true, autoSwitch = false), 0, 30, 0)))
        assertOutsideHole(REMINDER_CARD_TAG)
        host.eventsFlow.tryEmit(ShellEvent.Toast(GuideToast.NO_FAVOURITES, null))
        rule.waitForIdle()
        assertOutsideHole(TOAST_TAG)
    }

    @Test
    fun miniErrorCardShowsReasonAndNextStep() {
        render()
        show(GuideUiState(tuned = tuned.copy(phase = MiniPhase.ERROR, error = PlaybackErrorCode.CHANNEL_OFFLINE)))
        rule.onNodeWithTag(ERROR_CARD_TAG, useUnmergedTree = true).fetchSemanticsNode()
        rule.onNodeWithText("This channel is offline").assertExists()
        rule.onNodeWithText("Try another channel, or come back later").assertExists()
    }

    @Test
    fun anchoringRule() {
        // Below the cell when it fits above y 960.
        val top = anchoredOrigin(Rect(500f, 549f, 760f, 609f), 380f, 200f)
        assertEquals(617f, top.y)
        assertEquals(500f, top.x)
        // Else above, never above y 470.
        val low = anchoredOrigin(Rect(1700f, 889f, 1830f, 949f), 380f, 300f)
        assertEquals(581f, low.y)
        assertEquals("clamped to the panel", 1856f - 380f, low.x)
        val tall = anchoredOrigin(Rect(300f, 821f, 500f, 881f), 380f, 400f)
        assertEquals(MIN_TOP, tall.y)
        // No anchor: centred in the grid panel.
        val c = anchoredOrigin(null, 380f, 200f)
        assertEquals(64f + (1792f - 380f) / 2f, c.x)
        assertEquals(500f + (460f - 200f) / 2f, c.y)
    }
}
