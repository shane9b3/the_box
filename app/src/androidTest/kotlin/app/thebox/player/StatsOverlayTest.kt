package app.thebox.player

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.QuickItem
import app.thebox.core.full.StatLabel
import app.thebox.core.guide.ScreenMode
import app.thebox.player.api.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-M4-17: quick bar → Stream info: lines within 1 s that update, incl. "server 1 of 1"; Back closes; stats are disabled
 * after leaving FULL (docs/design/m4-player-ux.md §3.4.4, §5.5). Depends on WP-A (quick bar, stats toggle), WP-C
 * (StatsFormatter, serverIndex/serverCount), WP-D (stats forwarding).
 */
@RunWith(AndroidJUnit4::class)
class StatsOverlayTest : PlayerTestBase() {

    @Test
    fun streamInfoOverlay() {
        fullOn(10)
        awaitController("playing") { it is PlaybackState.Playing }
        openQuickBar()
        quickSelect(QuickItem.STREAM_INFO)
        val first = awaitPlayer("stats lines", 1_000) { !it.stats.isNullOrEmpty() }.stats!! // WP-A/WP-C behaviour
        log("I-M4-17 lines: $first")
        val server = first.firstOrNull { it.label == StatLabel.SERVER }
        assertTrue("SERVER line says \"server 1 of 1\": $server", server?.value?.contains("1 of 1") == true) // WP-C behaviour (E23)
        val changed = awaitPlayer("stats update", 4_000) { it.stats != null && it.stats != first } // M2 publishes at ≤ 2 Hz
        assertNotEquals(first, changed.stats)
        // Back closes the quick bar, then the overlay (ladder steps 4 and 8).
        press(K_BACK)
        awaitPlayer("quick bar closed, stats still on") { it.layer == PlayerLayer.NONE && it.stats != null } // WP-A behaviour
        press(K_BACK)
        awaitPlayer("stats off") { it.stats == null } // WP-A behaviour (§3.1.5 step 8)
        // On again, then leave FULL: the controller stops publishing (setStatsEnabled(false) on leaveFull).
        openQuickBar()
        quickSelect(QuickItem.STREAM_INFO)
        awaitPlayer("stats on again") { !it.stats.isNullOrEmpty() }
        toBareVideo()
        // stats on, nothing open: BACK switches stats off first; a second BACK leaves FULL.
        press(K_BACK)
        press(K_BACK)
        awaitUi("guide", 5_000) { it.mode == ScreenMode.GUIDE }
        assertNull("no stats after leaving FULL", pui().stats) // WP-A behaviour
        val a = controller.stats.value.updatedAtElapsedMs
        Thread.sleep(2_000)
        val b = controller.stats.value.updatedAtElapsedMs
        log("I-M4-17 stats updatedAt $a → $b (now ${SystemClock.elapsedRealtime()})")
        assertEquals("setStatsEnabled(false) after leaving FULL: no further stats updates", a, b) // WP-D behaviour
    }
}
