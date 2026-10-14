package app.thebox.baselineprofile

import android.view.KeyEvent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the app's baseline profile: `./gradlew :app:generateBaselineProfile` with a device attached and the fake
 * panel up (`fakepanel` instrumentation argument, default `http://10.0.2.2:18080`). Startup, then the M3 guide
 * journey (docs/design/m3-guide.md §5.7): 30× DOWN, 10× RIGHT, open Groups, open Search. The guide is seeded through
 * the benchmark build's `BenchSeedActivity` (idempotent).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    private val panel = InstrumentationRegistry.getArguments().getString("fakepanel") ?: "http://10.0.2.2:18080"

    @Test
    fun generate() = rule.collect(packageName = PACKAGE) {
        pressHome()
        startActivityAndWait()

        // Guide journey.
        device.executeShellCommand("am start -W -n $PACKAGE/app.thebox.bench.BenchSeedActivity --es panel $panel")
        device.wait(Until.gone(By.textStartsWith("Seeding")), 600_000)
        device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), 10_000)
        device.waitForIdle()
        repeat(30) { device.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN); Thread.sleep(60) }
        repeat(10) { device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT); Thread.sleep(100) }
        // Groups: Up to the header (Group chip), OK.
        device.pressKeyCode(KeyEvent.KEYCODE_PAGE_UP)
        repeat(6) { device.pressKeyCode(KeyEvent.KEYCODE_PAGE_UP); Thread.sleep(80) }
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_UP)
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)
        Thread.sleep(500)
        device.pressBack()
        // Search: Up to the tabs, Right to Search, OK, type, Back out.
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_UP)
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_UP)
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)
        Thread.sleep(500)
        device.executeShellCommand("input text news")
        Thread.sleep(1_000)
        repeat(3) { device.pressBack(); Thread.sleep(300) }
    }

    private companion object {
        const val PACKAGE = "app.thebox"
    }
}
