package app.thebox.guide

import android.graphics.Color
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.ScreenMode
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * I-16: `lowend=1&still=1`: flat `#0A0D13` background (pixel probe), still in the mini player (viewport HIDDEN: video
 * track off, audio on), OK → FULL shows video after the scrim.
 * The logo cap (48) is internal to `LogoCache`; TODO(integrator, WP-A): expose its capacity on the probe to assert it.
 */
@RunWith(AndroidJUnit4::class)
class LowEndTest : GuideTestBase() {
    override val switches = "lowend=1&still=1"

    @Test
    fun flatBackgroundStillAndFull() {
        assumeTrue("switches not applied (WP-B InMemoryGuideSettings stub)", app.thebox.debug.GuideDebugReceiver.status.value.settingsApplied)
        launchGuide()
        awaitGuideReady()
        toRow(0)
        tuneFocusedRow()
        awaitUi("still in the mini player") { it.tuned.still && it.tuned.phase == MiniPhase.PLAYING }
        Thread.sleep(1_500)
        val shot = screenshot("i16-lowend")
        // Ambient probes: top-right corner and bottom-left, outside the grid panel and the hole.
        val probes = listOf(shot.width - 4 to 4, shot.width - 4 to shot.height / 3)
        for ((x, y) in probes) {
            val c = shot.getPixel(x, y)
            assertTrue(
                "flat #0A0D13 at ($x,$y): ${Integer.toHexString(c)}",
                abs(Color.red(c) - 0x0A) <= 4 && abs(Color.green(c) - 0x0D) <= 4 && abs(Color.blue(c) - 0x13) <= 4,
            )
        }
        val stats = main { controller.stats.value }
        log("I-16 stats in still mode: v=${stats.videoDecoder} a=${stats.audioDecoder}")
        assertTrue("audio keeps decoding", stats.audioDecoder != null)
        val holeStill = colourfulness(shot, holeRect())
        log("I-16 hole colourfulness with the still: $holeStill")

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUi("FULL") { it.mode == ScreenMode.FULL }
        Thread.sleep(2_500) // scrim until first media or 2 s
        val full = screenshot("i16-full")
        val c = colourfulness(full, intArrayOf(0, 0, full.width, full.height))
        assertTrue("FULL shows video after the scrim (colourfulness $c)", c > 20.0)
    }
}
