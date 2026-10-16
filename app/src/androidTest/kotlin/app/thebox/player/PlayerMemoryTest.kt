package app.thebox.player

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.QuickItem
import app.thebox.player.api.PlaybackState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-M4-18: 5 × (zap ×10, channel list open/scroll, quick bar, picker, scrub, stats) with GC between: Java heap growth
 * < 2 MB from run 1 to run 5 (docs/design/m4-player-ux.md §4.3, §5.5, §5.9 "Soak (partial)"). The instrumentation runs
 * in the app process, so `Runtime` is the app's heap (M3 I-15 method). Depends on every WP (no leaks per journey).
 */
@RunWith(AndroidJUnit4::class)
class PlayerMemoryTest : PlayerTestBase() {

    private fun usedAfterGc(): Long {
        repeat(3) {
            Runtime.getRuntime().gc()
            Thread.sleep(300)
        }
        return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    }

    private fun journey() {
        // Scrub on live with archive first, on channel 1: quick bar → Pause / rewind → LEFT ×3 → BACK cancels the preview.
        // U-S8 (e): asserted, not skipped (the leg used to run after ten zaps, on a channel the test did not choose, and
        // skipped itself silently when that channel had no archive).
        toBareVideo()
        openQuickBar()
        assertTrue("the quick bar offers Pause / rewind on channel 1: ${pui().quick?.items}", pui().quick?.items?.contains(QuickItem.TIME_SHIFT) == true)
        quickSelect(QuickItem.TIME_SHIFT)
        awaitPlayer("scrub bar") { it.layer == PlayerLayer.SCRUB }
        press(K_LEFT, times = 3, intervalMs = 100)
        press(K_BACK) // cancel the preview (nothing sent)
        toBareVideo()
        repeat(10) { press(K_CH_UP, intervalMs = 700) }
        awaitController("playing after the zaps", 20_000) { it is PlaybackState.Playing }
        toBareVideo()
        press(K_OK)
        awaitPlayer("channel list") { it.layer == PlayerLayer.CHANNELS }
        press(K_DOWN, times = 40, intervalMs = 40)
        press(K_BACK)
        openQuickBar()
        quickSelect(QuickItem.ASPECT)
        awaitPlayer("aspect picker") { it.layer == PlayerLayer.PICKER }
        press(K_BACK) // to the quick bar
        quickSelect(QuickItem.STREAM_INFO)
        awaitPlayer("stats on") { it.stats != null }
        toBareVideo()
        press(K_BACK) // stats off
        toBareVideo()
    }

    @Test
    fun playerJourneyDoesNotGrowTheHeap() {
        fullOn(1)
        awaitController("playing") { it is PlaybackState.Playing }
        val samples = ArrayList<Long>()
        repeat(5) { run ->
            journey()
            // Back on channel 1 (archive) for the next run's scrub.
            press(K_CH_DOWN, times = 10, intervalMs = 400)
            awaitController("back on the start channel", 20_000) { it is PlaybackState.Playing }
            samples += usedAfterGc()
            log("I-M4-18 run ${run + 1}: heap used ${samples.last() / 1024} KiB, native ${Debug.getNativeHeapAllocatedSize() / 1024} KiB")
        }
        val growth = samples.last() - samples.first()
        log("I-M4-18 growth ${growth / 1024} KiB over ${samples.size} runs")
        assertTrue("Java heap growth < 2 MB (${growth / 1024} KiB)", growth < 2L * 1024 * 1024)
    }
}
