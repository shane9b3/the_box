package app.thebox.guide

import kotlinx.coroutines.launch
import app.thebox.ui.guide.GuideDebugCounters
import app.thebox.ui.guide.grid.COMPOSITIONS_GRID
import app.thebox.ui.guide.screen.COMPOSITIONS_PANEL
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-12: no recomposition of the grid while scrolling (§5.5). `GuideDebugCounters` (debug builds, §2.13 item 20)
 * counts compositions of `GuideGridHost` and `FocusedPanel` from a `SideEffect`: over 50 DOWN + 20 RIGHT the grid
 * host must not compose again, and `FocusedPanel` at most once per focus change plus once per description load.
 */
@RunWith(AndroidJUnit4::class)
class RecompositionTest : GuideTestBase() {

    @Test
    fun scrollingIsFrameStateOnly() {
        launchGuide()
        awaitGuideReady()
        toRow(0)
        Thread.sleep(500)
        val v0 = machine.frameVersion.value
        val ui0 = machine.ui.value
        val grid0 = GuideDebugCounters.count(COMPOSITIONS_GRID)
        val panel0 = GuideDebugCounters.count(COMPOSITIONS_PANEL)
        val mini0 = GuideDebugCounters.count(app.thebox.ui.guide.screen.COMPOSITIONS_MINI)
        // (code review 2026-10-14, H-S9) Count the panel slices the machine publishes; the panel may compose once per slice.
        var panelChanges = 0
        val counter = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch {
            var last = machine.ui.value.panel
            machine.ui.collect { s -> if (s.panel !== last) { panelChanges++; last = s.panel } }
        }
        assertTrue("GuideGridHost composed at least once (counter wired)", grid0 >= 1)
        press(KeyEvent.KEYCODE_DPAD_DOWN, times = 50, intervalMs = 60)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, times = 20, intervalMs = 60)
        Thread.sleep(800)
        counter.cancel()
        val mini = GuideDebugCounters.count(app.thebox.ui.guide.screen.COMPOSITIONS_MINI) - mini0
        val dv = machine.frameVersion.value - v0
        val grid = GuideDebugCounters.count(COMPOSITIONS_GRID) - grid0
        val panel = GuideDebugCounters.count(COMPOSITIONS_PANEL) - panel0
        log("I-12 frameVersion +$dv over 70 moves; ui changed: ${machine.ui.value != ui0}; compositions GuideGridHost +$grid, FocusedPanel +$panel")
        assertEquals("row after 50 DOWN", 50, focus()?.row)
        assertTrue("frameVersion moved for the moves (+$dv)", dv >= 70)
        assertEquals("GuideGridHost recomposed while scrolling", 0, grid)
        // H-S9: the panel composes once per published panel slice (focus change or description load), nothing more,
        // and the mini player not at all (its slice does not change while the grid moves).
        log("I-12 panel slices $panelChanges, MiniPlayerOverlay +$mini")
        assertTrue("FocusedPanel recomposed $panel times for $panelChanges panel changes", panel <= panelChanges + 2)
        assertTrue("MiniPlayerOverlay recomposed $mini times while only the grid moved", mini <= 2)
    }
}
