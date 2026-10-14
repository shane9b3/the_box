package app.thebox.guide

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** I-5: 200× DOWN at 25 Hz then 60× RIGHT: nothing PENDING/LOADING on screen 300 ms after the last key. */
@RunWith(AndroidJUnit4::class)
class PagingTest : GuideTestBase() {

    @Test
    fun fastScrollLeavesNoSkeletons() {
        launchGuide()
        awaitGuideReady()
        toRow(0)
        val loadTimes = mutableListOf<Long>()
        fun settle(label: String) {
            val t0 = SystemClock.elapsedRealtime()
            Thread.sleep(300)
            val at300 = visibleLoaded()
            while (!visibleLoaded() && SystemClock.elapsedRealtime() - t0 < 5_000) Thread.sleep(20)
            val ms = SystemClock.elapsedRealtime() - t0
            loadTimes += ms
            log("I-5 $label: visible rows loaded ${if (at300) "by 300 ms" else "after $ms ms"}; focus=${focus()}")
            assertTrue("$label: PENDING/LOADING visible 300 ms after the last key (loaded after $ms ms)", at300)
        }
        press(KeyEvent.KEYCODE_DPAD_DOWN, times = 200, intervalMs = 40)
        settle("200× DOWN @25 Hz")
        assertEquals("focus row after 200× DOWN", 200, focus()?.row)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, times = 60, intervalMs = 40)
        settle("60× RIGHT @25 Hz")
        // The pager logs its own window-query timings (plan §6 window query); here: time to a fully loaded screen.
        log("I-5 settle P50=${percentile(loadTimes, 0.5)} P90=${percentile(loadTimes, 0.9)} ms (see the pager's timing log for query P50/P90)")
    }
}
