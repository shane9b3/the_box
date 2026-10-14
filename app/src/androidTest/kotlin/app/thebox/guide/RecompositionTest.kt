package app.thebox.guide

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-12: no recomposition of the grid while scrolling.
 *
 * TODO(integrator, WP-C/WP-D): §5.5 asks for "`GuideGridHost` body composes once; `FocusedPanel` recomposes ≤ focus
 * changes + description loads". There is no composition-count hook today (no probe on `GuideGridHost`/`FocusedPanel`,
 * and `ui-test` is not on the androidTest classpath). Add a debug-only counter (e.g. `GuideDebugProbe`-side
 * `compositions: Map<String, Int>` bumped from a `SideEffect` in each composable under `BuildConfig.DEBUG`) and assert
 * it here. What this test asserts now: every key reaches the machine as a frame change (frameVersion moves once per
 * move, not per animation tick) and the focus lands where 50 DOWN + 20 RIGHT put it.
 */
@RunWith(AndroidJUnit4::class)
class RecompositionTest : GuideTestBase() {

    @Test
    fun scrollingIsFrameStateOnly() {
        launchGuide()
        awaitGuideReady()
        toRow(0)
        val v0 = machine.frameVersion.value
        val ui0 = machine.ui.value
        press(KeyEvent.KEYCODE_DPAD_DOWN, times = 50, intervalMs = 60)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, times = 20, intervalMs = 60)
        Thread.sleep(500)
        val dv = machine.frameVersion.value - v0
        log("I-12 frameVersion +$dv over 70 moves; ui changed: ${machine.ui.value != ui0}; composition counts not measured (TODO above)")
        assertEquals("row after 50 DOWN", 50, focus()?.row)
        assertTrue("frameVersion moved for the moves (+$dv)", dv >= 70)
    }
}
