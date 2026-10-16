package app.thebox.player

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.full.PlayerLayer
import app.thebox.player.api.PlaybackState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * §5.9 "FULL idle frames ~0" as an in-process, asserted gate (code review 2026-10-16, U-S6, lead decision 9; replaces
 * `PlayerOverlayBenchmark` journey (a), whose empty `FrameTimingMetric` made Macrobenchmark throw, L-16). Live video in
 * FULL with no overlay for 10 s: the app window draws (almost) nothing, because the picture is the `SurfaceView`'s and
 * no composable ticks. Counted with a `Window.OnFrameMetricsAvailableListener` on the activity's window. Gate: ≤ 5
 * frames on the emulator (§5.9), ≤ 2 on hardware.
 */
@RunWith(AndroidJUnit4::class)
class FullIdleFramesTest : PlayerTestBase() {

    @Test
    fun fullIdleDrawsNoFrames() {
        fullOn(10)
        awaitController("playing") { it is PlaybackState.Playing }
        // The enter-FULL passive bar hides INFO_AFTER_ZAP_MS after PLAYING; wait for bare video, then a quiet second.
        awaitPlayer("bare video, no overlay", 15_000) { it.layer == PlayerLayer.NONE && it.info == null && it.scrub == null && it.stats == null }
        Thread.sleep(1_500)
        val frames = AtomicInteger()
        val thread = HandlerThread("idle-frames").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) frames.incrementAndGet()
        }
        main { activity().window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
        try {
            Thread.sleep(10_000)
        } finally {
            main { activity().window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
        }
        val n = frames.get()
        val ceiling = if (isEmulator()) 5 else 2
        log("FULL idle 10 s: $n app-window frames (ceiling $ceiling); player=${pui().brief()}")
        assertTrue("FULL idle drew $n frames in 10 s (gate ≤ $ceiling)", n <= ceiling)
    }
}
