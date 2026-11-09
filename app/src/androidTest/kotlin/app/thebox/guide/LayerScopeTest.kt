package app.thebox.guide

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-18 (B8; criterion owned by WP-A): capture app trace sections with `atrace` during 50× DOWN and count them.
 * WP-A's criterion: on grid-only frames the panel's and the ambient's draw sections do not fire; only `guide-draw`
 * does. This harness captures and logs the section counts; the only assertion is that `guide-draw` was traced at all
 * (otherwise the capture proves nothing). The panel column (`guide-panel`) and the ambient (`guide-ambient`) carry
 * their own trace sections (§2.13 item 20): the ambient must not redraw at all while scrolling, and the panel at most
 * once per focus change (its content changes with the focus) plus description loads, so most `guide-draw` frames are
 * grid-only. (2026-11-09, docs/decisions/2026-11-09-guide-panel-description.md) A long description that scrolls
 * fires `guide-panel` on every scroll frame, legitimately: the scroll starts only after a 3 s hold on one programme, so
 * it never fires during this run of moves, and it never makes a grid-only frame draw the panel or the grid redraw.
 */
@RunWith(AndroidJUnit4::class)
class LayerScopeTest : GuideTestBase() {

    @Test
    fun gridOnlyFramesDrawOnlyTheGrid() {
        launchGuide()
        awaitGuideReady()
        toRow(0)
        val pkg = context.packageName
        shell("atrace --async_stop")
        shell("atrace --async_start -a $pkg -b 16384 gfx view")
        Thread.sleep(300)
        press(KeyEvent.KEYCODE_DPAD_DOWN, times = 50, intervalMs = 40)
        Thread.sleep(500)
        val dump = shell("atrace --async_stop -a $pkg")
        val names = Regex("""tracing_mark_write: B\|\d+\|([^\n|]+)""").findAll(dump).map { it.groupValues[1].trim() }.toList()
        val counts = names.groupingBy { it }.eachCount()
        val frames = counts.filterKeys { it.startsWith("Choreographer#doFrame") }.values.sum()
        val grid = counts[GRID_SECTION] ?: 0
        val panel = PANEL_SECTIONS.sumOf { counts[it] ?: 0 }
        val ambient = AMBIENT_SECTIONS.sumOf { counts[it] ?: 0 }
        log("I-18 trace ${dump.length} chars, frames=$frames $GRID_SECTION=$grid panel=$panel ambient=$ambient")
        log("I-18 top sections: ${counts.entries.sortedByDescending { it.value }.take(25)}")
        assertTrue("no '$GRID_SECTION' section traced (WP-A renderer section missing, or atrace capture failed: ${dump.take(200)})", grid > 0)
        assertEquals("ambient redrawn while scrolling the grid", 0, ambient)
        assertTrue("panel redrawn $panel times for 50 moves", panel <= 100)
        assertTrue("grid-only frames exist (guide-draw $grid > panel $panel)", grid > panel)
    }

    companion object {
        const val GRID_SECTION = "guide-draw"
        val PANEL_SECTIONS = listOf(app.thebox.ui.guide.screen.TRACE_PANEL)
        val AMBIENT_SECTIONS = listOf(app.thebox.ui.ambient.TRACE_AMBIENT)
    }
}
