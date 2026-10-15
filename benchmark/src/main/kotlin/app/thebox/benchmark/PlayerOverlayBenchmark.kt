package app.thebox.benchmark

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full-screen player overlay benchmarks (docs/design/m4-player-ux.md §5.7, §5.9; owner WP-E), with the M3 setup
 * ([GuideScrollBenchmark]: seeding, `androidx.benchmark.suppressErrors=EMULATOR`, emulator `-gpu host`).
 *
 * Journeys, each starting in FULL on the guide's focused row (channel 1 of the synthetic panel: live with archive):
 * (a) FULL idle 10 s, no overlay — the gate is ~0 frames (≤ 5 on the emulator, §5.9 "FULL idle frames"): read
 *     `frameCount` from the `FrameTimingMetric` output; a macrobenchmark cannot assert on its own metrics in-process,
 *     so the integration pass checks the JSON result and records it in §5.8;
 * (b) scrub: RW opens the scrub bar on live with archive (rewindLive), then ← for 5 s at ~20 Hz (see [holdKey]);
 * (c) channel list: OK, then 100 × DOWN at 25 Hz;
 * (d) 20 × CH+ at 1 Hz with the passive bar — zap P50/P90 live in the app's `controller.diagnostics.zaps()` (filtered to
 *     live tunes, N14), which this separate process cannot read: the CH+ timestamps are logged under [TAG] and the zap
 *     gate is measured in-process by I-M4-1 (`InfoBarTest`) and M3 I-17;
 * (e) quick bar open + 12 × RIGHT.
 * Metrics: `FrameTimingMetric` (`frameDurationCpuMs` P50/P90/P99; API 30 reports CPU time only, M3 §5.11) and the
 * draw-phase trace sections `player-scrub`, `player-info` (§5.9 "Overlay draw ≤ 1 ms P90", when WP-D emits them).
 *
 * Run (panel up with `--synthetic 2000`):
 * `./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
 *  -Pandroid.testInstrumentationRunnerArguments.class=app.thebox.benchmark.PlayerOverlayBenchmark` (instrumentation
 * arguments `fakepanel`, default `http://10.0.2.2:18080`, and `targetPackage`, as in [GuideScrollBenchmark]).
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class PlayerOverlayBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    private val panel = InstrumentationRegistry.getArguments().getString("fakepanel") ?: "http://10.0.2.2:18080"
    private val target = InstrumentationRegistry.getArguments().getString("targetPackage") ?: GuideScrollBenchmark.TARGET_PACKAGE
    private val debugTarget get() = target.endsWith(".debug")

    /** (a) FULL idle for 10 s with no overlay: frame count must be ~0 (≤ 5 on the emulator). */
    @Test
    fun fullIdle10s() = journey {
        Log.i(TAG, "idle start ${SystemClock.elapsedRealtime()}")
        Thread.sleep(10_000)
        Log.i(TAG, "idle end ${SystemClock.elapsedRealtime()} (gate: frameCount ≤ 5 in the result JSON)")
    }

    /** (b) Scrub hold ← for 5 s. */
    @Test
    fun scrubHoldLeft5s() = journey {
        device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_REWIND) // live with archive: rewindLive + scrub bar focused
        Thread.sleep(3_000)
        holdKey(KeyEvent.KEYCODE_DPAD_LEFT, 5_000)
        Thread.sleep(1_000)
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_UP) // jump to live
        Thread.sleep(2_000)
    }

    /** (c) Channel list: 100 × DOWN at 25 Hz. */
    @Test
    fun channelList25Hz() = journey {
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)
        Thread.sleep(800)
        repeatKey(KeyEvent.KEYCODE_DPAD_DOWN, 100, 40)
        device.pressBack()
    }

    /** (d) 20 × CH+ at 1 Hz with the passive info bar (zap timestamps logged). */
    @Test
    fun zap1HzWithPassiveBar() = journey {
        repeat(20) { i ->
            device.pressKeyCode(KeyEvent.KEYCODE_CHANNEL_UP)
            Log.i(TAG, "zap ${i + 1} key at ${SystemClock.elapsedRealtime()}")
            Thread.sleep(1_000)
        }
    }

    /** (e) Quick bar open + 12 × RIGHT. */
    @Test
    fun quickBarRight() = journey {
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_UP)
        Thread.sleep(500)
        repeatKey(KeyEvent.KEYCODE_DPAD_RIGHT, 12, 150)
        device.pressBack()
    }

    private fun journey(block: MacrobenchmarkScope.() -> Unit) = rule.measureRepeated(
        packageName = target,
        metrics = listOf(
            FrameTimingMetric(),
            TraceSectionMetric("player-scrub", TraceSectionMetric.Mode.Sum),
            TraceSectionMetric("player-info", TraceSectionMetric.Mode.Sum),
        ),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.UseIfAvailable),
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = { seedAndOpenFull() },
        measureBlock = block,
    )

    /** Seeds idempotently (as [GuideScrollBenchmark]), tunes the focused row and goes FULL (OK twice). */
    private fun MacrobenchmarkScope.seedAndOpenFull() {
        pressHome()
        val cmd = if (debugTarget) {
            "am start -W -a android.intent.action.VIEW -n $target/app.thebox.debug.GuideDebugReceiver " +
                "-d thebox-debug://guide?seed=${java.net.URLEncoder.encode(panel, "UTF-8")}&perf=high&refresh=0"
        } else {
            "am start -W -n $target/app.thebox.bench.BenchSeedActivity --es panel $panel --es perf high"
        }
        Log.i(TAG, "seed start: ${device.executeShellCommand(cmd).trim()}")
        device.wait(Until.gone(By.textStartsWith(if (debugTarget) "The Box debug" else "Seeding")), GuideScrollBenchmark.SEED_TIMEOUT_MS)
        check(!device.hasObject(By.textStartsWith("Seed failed"))) { "seeding: ${device.findObject(By.textStartsWith("Seed failed"))?.text}" }
        device.wait(Until.hasObject(By.pkg(target).depth(0)), 10_000)
        device.waitForIdle()
        Thread.sleep(2_000)
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER) // tune the focused row (or FULL when it already plays)
        Thread.sleep(3_000)
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER) // FULL
        Thread.sleep(6_000) // let the enter-FULL passive bar time out (INFO_AFTER_ZAP_MS)
    }

    private fun MacrobenchmarkScope.repeatKey(keyCode: Int, times: Int, intervalMs: Long) {
        repeat(times) {
            device.pressKeyCode(keyCode)
            Thread.sleep(intervalMs)
        }
    }

    /**
     * Approximates a held key with separate presses at ~20 Hz (UiAutomator has no timed auto-repeat), so every event is a
     * single press (10 s scrub step, §3.5.2): the draw load matches a hold, the step sizes do not.
     */
    private fun MacrobenchmarkScope.holdKey(keyCode: Int, durationMs: Long) {
        val end = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < end) {
            device.pressKeyCode(keyCode)
            Thread.sleep(50)
        }
    }

    companion object {
        const val TAG = "PlayerBench"
        const val ITERATIONS = 3
    }
}
