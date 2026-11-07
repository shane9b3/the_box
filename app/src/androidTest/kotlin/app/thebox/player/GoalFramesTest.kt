package app.thebox.player

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.thebox.core.full.PlayerLayer
import app.thebox.player.api.PlaybackState
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Goal animation docs/design/m6-goal-animation.md §8.4: frame cost while the takeover plays over live video in FULL.
 *
 * **Not run by the agent that wrote it** (instrumented tests wipe the app on the attached device); the lead runs it.
 * Prerequisites, since the test cannot inject a score source into the real activity:
 * - the debug build points at the fake panel's sports API (`files/debug/sports_base`, tools/fakepanel/README.md) with
 *   `--sports`, and the featured game (CGY @ EDM) is followed with Show Sports on;
 * - run it with `-e goalFrames 1` while the game is within about 4 s of the CGY goal in the 2nd period (the panel's
 *   `POST /admin/sports {"anchor_unix": T}` re-anchors it), so the takeover falls inside the 5 s window.
 * Without `goalFrames=1` the test is skipped.
 *
 * Records, over 5 s: the P90 `TOTAL_DURATION`, the count of frames over 33 ms, and the frame count. Gates: emulator
 * ≥ 200 frames; device P90 < 16.7 ms and ≤ 3 frames over 33 ms. Damage area is read from `dumpsys gfxinfo` by hand (S3).
 * Afterwards the window must go idle: ≤ 1 frame per 10 s within 1 s of the end of the takeover.
 */
@RunWith(AndroidJUnit4::class)
class GoalFramesTest : PlayerTestBase() {

    @Test
    fun goalTakeoverFrameCost() {
        assumeTrue(
            "pass -e goalFrames 1 with the fake panel's sports game followed and a goal about to land",
            InstrumentationRegistry.getArguments().getString("goalFrames") == "1",
        )
        fullOn(10)
        awaitController("playing") { it is PlaybackState.Playing }
        awaitPlayer("bare video, no overlay", 15_000) { it.layer == PlayerLayer.NONE && it.info == null && it.scrub == null && it.stats == null }

        val totals = Collections.synchronizedList(ArrayList<Long>())
        val thread = HandlerThread("goal-frames").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            totals.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
        }
        main { activity().window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
        try {
            Thread.sleep(5_000)
        } finally {
            main { activity().window.removeOnFrameMetricsAvailableListener(listener) }
        }
        val frames = totals.toList().sorted()
        val p90Ms = if (frames.isEmpty()) Double.NaN else frames[((frames.size - 1) * 0.9).toInt()] / 1e6
        val slow = frames.count { it > 33_000_000L }
        log("goal takeover 5 s: ${frames.size} frames, P90 total $p90Ms ms, $slow frames over 33 ms")

        if (isEmulator()) {
            assertTrue("emulator: ${frames.size} frames in 5 s (gate ≥ 200)", frames.size >= 200)
        } else {
            assertTrue("device P90 $p90Ms ms (gate < 16.7)", p90Ms < 16.7)
            assertTrue("device: $slow frames over 33 ms (gate ≤ 3)", slow <= 3)
        }

        // Idle again: the takeover must leave nothing ticking (≤ 1 frame per 10 s, §8.4).
        Thread.sleep(1_000)
        val idle = Collections.synchronizedList(ArrayList<Long>())
        val idleListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) idle.add(1L)
        }
        main { activity().window.addOnFrameMetricsAvailableListener(idleListener, Handler(thread.looper)) }
        try {
            Thread.sleep(10_000)
        } finally {
            main { activity().window.removeOnFrameMetricsAvailableListener(idleListener) }
            thread.quitSafely()
        }
        assertTrue("idle after the takeover drew ${idle.size} frames in 10 s (gate ≤ 1)", idle.size <= 1)
    }
}
