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
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Guide scroll and zap benchmarks (docs/design/m3-guide.md §5.7, S14; owner WP-D), each run with `PerformanceMode`
 * Auto and forced High (`perf` extra to `BenchSeedActivity`, applied to the in-memory guide settings).
 *
 * Journeys: (a) 100× DOWN at 25 Hz, (b) 100× RIGHT at 10 Hz, (c) 20× CH+ (pages the grid), (d) OK twice (tune, then
 * FULL) and 20× CH+ at 1 Hz. The mini player decodes the fake panel's clip throughout.
 *
 * Metrics: `FrameTimingMetric` reports `frameDurationCpuMs` (P50/P90/P95/P99) on every API level and `frameOverrunMs`
 * only on API 31+ (benchmark-macro 1.5.0 `FrameTimingQuery.SubMetric.supportedOnApiLevel`), so the API 30 emulator
 * numbers exclude GPU time and are not comparable to the plan §6 gate. `TraceSectionMetric("guide-draw")` gives the
 * main-thread draw time of the grid (§5.9) when WP-A's renderer emits that section. Zap P50/P90 (journey d) lives in
 * the app's `controller.diagnostics.zaps()`, which a macrobenchmark (separate process, release-like app) cannot read:
 * the journey logs CH+ timestamps under [TAG] and the zap gate is measured by I-17 (`ZapThroughGuideTest`).
 *
 * Run (panel up with `--synthetic 2000`):
 * `./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
 *  -Pandroid.testInstrumentationRunnerArguments.class=app.thebox.benchmark.GuideScrollBenchmark` (instrumentation
 * argument `fakepanel`, default `http://10.0.2.2:18080`).
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(Parameterized::class)
class GuideScrollBenchmark(private val perf: String) {
    @get:Rule
    val rule = MacrobenchmarkRule()

    private val panel = InstrumentationRegistry.getArguments().getString("fakepanel") ?: "http://10.0.2.2:18080"

    /**
     * Target package (instrumentation argument `targetPackage`, default [TARGET_PACKAGE]). `app.thebox.debug` lets the
     * pipeline run on the x86-only API 30 emulator, where the ARM-only benchmark APK cannot install (seeded through the
     * debug deep link instead of BenchSeedActivity; needs `suppressErrors=EMULATOR,DEBUGGABLE`; the numbers are then
     * debug-build numbers and only the metric names and the pipeline are meaningful).
     */
    private val target = InstrumentationRegistry.getArguments().getString("targetPackage") ?: TARGET_PACKAGE
    private val debugTarget get() = target.endsWith(".debug")

    @Test
    fun scrollDown25Hz() = journey { repeatKey(KeyEvent.KEYCODE_DPAD_DOWN, 100, 40) }

    @Test
    fun scrollRight10Hz() = journey { repeatKey(KeyEvent.KEYCODE_DPAD_RIGHT, 100, 100) }

    @Test
    fun pageChannelUp() = journey {
        repeatKey(KeyEvent.KEYCODE_CHANNEL_DOWN, 20, 250) // page down first, so CH+ has rows to page back over
        repeatKey(KeyEvent.KEYCODE_CHANNEL_UP, 20, 250)
    }

    @Test
    fun fullScreenZap1Hz() = journey {
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER) // tune the focused row
        Thread.sleep(3_000)
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER) // OK on the playing row → FULL
        Thread.sleep(1_000)
        repeat(20) { i ->
            device.pressKeyCode(KeyEvent.KEYCODE_CHANNEL_UP)
            Log.i(TAG, "zap ${i + 1} key at ${SystemClock.elapsedRealtime()}")
            Thread.sleep(1_000)
        }
        device.pressBack()
    }

    private fun journey(block: MacrobenchmarkScope.() -> Unit) = rule.measureRepeated(
        packageName = target,
        metrics = listOf(FrameTimingMetric(), TraceSectionMetric("guide-draw", TraceSectionMetric.Mode.Sum)),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.UseIfAvailable),
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = { seedAndOpen() },
        measureBlock = block,
    )

    /** Seeds idempotently through BenchSeedActivity, which then opens MainActivity; waits until the guide is up. */
    private fun MacrobenchmarkScope.seedAndOpen() {
        pressHome()
        val cmd = if (debugTarget) {
            "am start -W -a android.intent.action.VIEW -n $target/app.thebox.debug.GuideDebugReceiver " +
                // UiAutomation.executeShellCommand splits on spaces without a shell: no quoting, '&' is literal.
                "-d thebox-debug://guide?seed=${java.net.URLEncoder.encode(panel, "UTF-8")}&perf=$perf&refresh=0"
        } else {
            "am start -W -n $target/app.thebox.bench.BenchSeedActivity --es panel $panel --es perf $perf"
        }
        val out = device.executeShellCommand(cmd)
        Log.i(TAG, "seed start: ${out.trim()}")
        // The seeding activity shows its text while it imports (a minute or more on the first run), then opens MainActivity.
        device.wait(Until.gone(By.textStartsWith(if (debugTarget) "The Box debug" else "Seeding")), SEED_TIMEOUT_MS)
        check(!device.hasObject(By.textStartsWith("Seed failed"))) { "seeding: ${device.findObject(By.textStartsWith("Seed failed"))?.text}" }
        device.wait(Until.hasObject(By.pkg(target).depth(0)), 10_000)
        device.waitForIdle()
        Thread.sleep(2_000) // first tune + first window snapshot
    }

    private fun MacrobenchmarkScope.repeatKey(keyCode: Int, times: Int, intervalMs: Long) {
        repeat(times) {
            device.pressKeyCode(keyCode)
            Thread.sleep(intervalMs)
        }
    }

    companion object {
        const val TARGET_PACKAGE = "app.thebox"
        const val TAG = "GuideBench"
        const val ITERATIONS = 3
        const val SEED_TIMEOUT_MS = 600_000L

        @JvmStatic
        @Parameterized.Parameters(name = "perf={0}")
        fun params() = listOf("auto", "high")
    }
}
