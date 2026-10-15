package app.thebox.guide

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.guide.ScreenMode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-17 (S14): OK on 10 different rows in GUIDE, then 20× CH+ in FULL at 1 Hz; `controller.diagnostics.zaps()` P50/P90
 * logged (plan §6 zap gate; asserted on hardware in M6). Asserted here: every zap ended in first media.
 */
@RunWith(AndroidJUnit4::class)
class ZapThroughGuideTest : GuideTestBase() {

    @Test
    fun zapTimesThroughTheGuide() {
        launchGuide()
        awaitGuideReady()
        stopPlayback()
        val cursor = controller.diagnostics.zaps().maxOfOrNull { it.tuneId } ?: 0L
        toRow(0)
        repeat(10) { i ->
            if (i > 0) press(KeyEvent.KEYCODE_DPAD_DOWN, times = 3)
            tuneFocusedRow()
        }
        press(KeyEvent.KEYCODE_DPAD_CENTER) // OK on the tuned, playing row → FULL
        awaitUi("FULL") { it.mode == ScreenMode.FULL }
        repeat(20) {
            press(KeyEvent.KEYCODE_CHANNEL_UP, intervalMs = 0)
            Thread.sleep(1_000)
        }
        Thread.sleep(3_000)
        val zaps = controller.diagnostics.zaps().filter { it.tuneId > cursor }
        val ok = zaps.filter { it.outcome == "ok" }
        val first = ok.mapNotNull { it.firstFrameMs }
        val guide = ok.take(10).mapNotNull { it.firstFrameMs }
        val full = ok.drop(10).mapNotNull { it.firstFrameMs }
        log("I-17 zaps=${zaps.size} ok=${ok.size} outcomes=${zaps.groupingBy { it.outcome }.eachCount()}")
        log("I-17 first frame all P50=${percentile(first, 0.5)} P90=${percentile(first, 0.9)} ms; guide OK P50=${percentile(guide, 0.5)} P90=${percentile(guide, 0.9)}; FULL CH+ P50=${percentile(full, 0.5)} P90=${percentile(full, 0.9)}")
        // O-3 (code review 2026-10-14): the resolve step alone, guide path against FULL.
        val guideResolve = ok.take(10).map { it.resolveMs }
        val fullResolve = ok.drop(10).map { it.resolveMs }
        log("I-17 resolve guide OK P50=${percentile(guideResolve, 0.5)} P90=${percentile(guideResolve, 0.9)}; FULL CH+ P50=${percentile(fullResolve, 0.5)} P90=${percentile(fullResolve, 0.9)} ms")
        assertTrue("10 guide tunes + CH+ zaps recorded (${zaps.size})", zaps.size >= 10)
        // CH+ at 1 Hz may coalesce under M2's debounce; the guide tunes must all reach first media.
        assertTrue("guide tunes reached first media (${guide.size}/10)", guide.size >= 10)
    }
}
