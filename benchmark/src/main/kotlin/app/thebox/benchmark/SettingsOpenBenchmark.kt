package app.thebox.benchmark

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
 * §6.7 "Settings open → first page drawn ≤ 150 ms" (docs/design/m5-setup-settings.md; the U-S1 measurement item of the
 * code review 2026-10-16), with the M3 setup ([GuideScrollBenchmark]: seeding, `androidx.benchmark.suppressErrors=EMULATOR`).
 *
 * Journey: from the guide, the Settings key, then BACK. Metrics: the app's async trace section `settings-open`
 * (`SettingsScreen`'s first composition → the first frame that draws a real, non-placeholder page: Playlists, WP-B's
 * push-model page) and `FrameTimingMetric`. The key → composition part (one frame) is not in the section; the
 * instrumented `GatesTest` measures key → the machine's first real page.
 *
 * Run (panel up with `--synthetic 2000`): `./gradlew :benchmark:connectedBenchmarkAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
 * -Pandroid.testInstrumentationRunnerArguments.class=app.thebox.benchmark.SettingsOpenBenchmark`.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsOpenBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    private val panel = InstrumentationRegistry.getArguments().getString("fakepanel") ?: "http://10.0.2.2:18080"
    private val target = InstrumentationRegistry.getArguments().getString("targetPackage") ?: GuideScrollBenchmark.TARGET_PACKAGE
    private val debugTarget get() = target.endsWith(".debug")

    @Test
    fun settingsOpenToFirstPage() = rule.measureRepeated(
        packageName = target,
        metrics = listOf(
            TraceSectionMetric("settings-open", TraceSectionMetric.Mode.First),
            FrameTimingMetric(),
        ),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.UseIfAvailable),
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = { seedToGuide() },
    ) {
        device.pressKeyCode(KeyEvent.KEYCODE_SETTINGS)
        device.wait(Until.hasObject(By.text("Playlists")), 5_000)
        Thread.sleep(1_500)
        device.pressBack()
        Thread.sleep(1_000)
    }

    private fun MacrobenchmarkScope.seedToGuide() {
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
        Thread.sleep(3_000)
    }

    companion object {
        const val TAG = "SettingsBench"
        const val ITERATIONS = 5
    }
}
