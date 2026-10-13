package app.thebox.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the app's baseline profile: `./gradlew :app:generateBaselineProfile` with a device
 * attached. M0 covers startup only; the guide-scroll journey is added in M3.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "app.thebox") {
        pressHome()
        startActivityAndWait()
    }
}
