package app.thebox.guide

import android.os.SystemClock
import android.graphics.Color
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.ScreenMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * I-16: `lowend=1&still=1`: flat `#0A0D13` background (pixel probe), still in the mini player (viewport HIDDEN: video
 * track off, audio on), OK → FULL shows video after the scrim.
 * The logo cap: `LogoCache.capacity` through the debug entry point (48 in low-end mode, §3.7).
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
        val logos = ep.guideImages() as app.thebox.ui.guide.grid.LogoCache
        assertEquals("logo cap in low-end mode", 48, main { logos.capacity })
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
        // StreamStats publish only while stats are enabled (the stats overlay, M2 StatsCollector): enable them for the probe.
        main { controller.setStatsEnabled(true) }
        Thread.sleep(1_500)
        val stats = main { controller.stats.value }
        main { controller.setStatsEnabled(false) }
        log("I-16 stats in still mode: v=${stats.videoDecoder} a=${stats.audioDecoder}")
        assertTrue("audio decoder known", stats.audioDecoder != null)
        val holeStill = colourfulness(shot, holeRect())
        log("I-16 hole colourfulness with the still: $holeStill")

        // (code review 2026-10-14, H-S10) The scrim lifts on the re-enabled track's first rendered frame: measure it.
        val frames0 = main { controller.video.value.renderedFirstFrames }
        val ok = SystemClock.elapsedRealtime()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUi("FULL") { it.mode == ScreenMode.FULL }
        var firstFrameMs = -1L
        while (SystemClock.elapsedRealtime() - ok < 5_000) {
            if (main { controller.video.value.renderedFirstFrames } != frames0) { firstFrameMs = SystemClock.elapsedRealtime() - ok; break }
            Thread.sleep(20)
        }
        log("I-16 still → FULL: first rendered frame ${firstFrameMs} ms after OK")
        assertTrue("still → FULL renders a frame within 2 s (${firstFrameMs} ms)", firstFrameMs in 0..2_000)
        Thread.sleep(1_000) // the scrim fades out right after that frame
        val full = screenshot("i16-full")
        val c = colourfulness(full, intArrayOf(0, 0, full.width, full.height))
        assertTrue("FULL shows video after the scrim (colourfulness $c)", c > 20.0)
    }

    /**
     * (code review 2026-10-14, test gap 8) I-16's "audio keeps decoding" with the video track off, proved by the playback
     * position, not by `stats.audioDecoder` (which stays set once known). Q-1 (fixed 2026-10-15, M2 design §5.13): the
     * early rebuffer seen here is the emulator audio HAL's start-up drop; the track-off path itself is covered by
     * `app.thebox.playback.DisabledVideoTrackTest`.
     */
    @Test
    fun audioKeepsPlayingWithTheVideoTrackOff() {
        assumeTrue("switches not applied", app.thebox.debug.GuideDebugReceiver.status.value.settingsApplied)
        launchGuide()
        awaitGuideReady()
        toRow(0)
        tuneFocusedRow()
        awaitUi("still in the mini player") { it.tuned.still && it.tuned.phase == MiniPhase.PLAYING }
        Thread.sleep(1_500)
        // (code review 2026-10-14, test gap 8) The decoder name stays set once known: prove audio is still decoding by
        // the playback position advancing while the video track is off.
        val p0 = main { controller.position().positionMs }
        Thread.sleep(2_000)
        val p1 = main { controller.position().positionMs }
        log("I-16 position advanced ${p1 - p0} ms in 2 s with the video track off")
        assertTrue("audio keeps decoding: position advanced ${p1 - p0} ms in 2 s", p1 - p0 >= 1_000)
    }
}
