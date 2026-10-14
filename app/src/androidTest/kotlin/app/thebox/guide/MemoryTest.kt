package app.thebox.guide

import android.os.Debug
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-15: the guide journey ×5 with GC between: Java heap growth < 2 MB from run 1 to run 5. The instrumentation runs
 * in the app process, so `Runtime` is the app's heap. "Guide retained ≤ §4.6" (5 MB) needs a heap dump diff and is
 * logged as the heap delta from before the guide opened (an upper bound: it includes the player).
 */
@RunWith(AndroidJUnit4::class)
class MemoryTest : GuideTestBase() {

    private fun usedAfterGc(): Long {
        repeat(3) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            Thread.sleep(200)
        }
        return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    }

    @Test
    fun journeyDoesNotGrowTheHeap() {
        val beforeGuide = usedAfterGc()
        launchGuide()
        awaitGuideReady()
        val samples = mutableListOf<Long>()
        repeat(5) { run ->
            goHome()
            toRow(0)
            press(KeyEvent.KEYCODE_DPAD_DOWN, times = 60, intervalMs = 40)
            press(KeyEvent.KEYCODE_DPAD_RIGHT, times = 20, intervalMs = 40)
            press(KeyEvent.KEYCODE_PAGE_DOWN, times = 10, intervalMs = 80)
            press(KeyEvent.KEYCODE_DPAD_UP) // header
            goHome()
            Thread.sleep(500)
            samples += usedAfterGc()
            log("I-15 run ${run + 1}: heap used ${samples.last() / 1024} KiB, native ${Debug.getNativeHeapAllocatedSize() / 1024} KiB")
        }
        val growth = samples.last() - samples.first()
        log("I-15 growth run1→run5 ${growth / 1024} KiB; guide+player retained vs before the guide ${(samples.last() - beforeGuide) / 1024} KiB")
        assertTrue("Java heap growth < 2 MB (${growth / 1024} KiB)", growth < 2L * 1024 * 1024)
    }
}
