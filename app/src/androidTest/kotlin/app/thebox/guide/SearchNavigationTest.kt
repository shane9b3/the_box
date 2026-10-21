package app.thebox.guide

import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Navigation only: no decoder/first-frame dependency, including a remote with no repeated OK events. */
@RunWith(AndroidJUnit4::class)
class SearchNavigationTest : GuideTestBase() {
    private val device = UiDevice.getInstance(instrumentation)

    @Test fun heldOkOpensMenuAndBackRestoresTheResultWithoutLeavingSearch() {
        launchGuide()
        awaitGuideReady()
        stopPlayback()
        goHome()
        toRow(0)
        press(KeyEvent.KEYCODE_DPAD_UP, times = 3)
        awaitUi("top navigation") { it.zone == Zone.TABS }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUi("Search") { it.mode == ScreenMode.SEARCH }
        assertTrue("search field", device.wait(Until.hasObject(By.clazz("android.widget.EditText").pkg(context.packageName)), 15_000))
        instrumentation.sendStringSync("news")
        assertTrue("channel results", device.wait(Until.hasObject(By.text("Channels")), 10_000))
        main {
            scenario!!.onActivity { activity ->
                activity.getSystemService(InputMethodManager::class.java)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            }
        }
        device.waitForIdle()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        val focused = device.findObject(By.focused(true))
        assertTrue("a result receives focus", focused != null)

        holdOkWithoutRepeats()
        assertTrue("context menu", device.wait(Until.hasObject(By.text("Close")), 5_000))
        press(KeyEvent.KEYCODE_DPAD_UP, times = 3)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, times = 2)
        assertEquals(ScreenMode.SEARCH, ui().mode)
        assertTrue("menu remains open at its navigation edges", device.hasObject(By.text("Close")))
        press(KeyEvent.KEYCODE_BACK)
        assertTrue("Back closes menu", device.wait(Until.gone(By.text("Close")), 5_000))
        assertEquals("Back stays in Search", ScreenMode.SEARCH, ui().mode)
        // A second hold proves focus returned to the result rather than the field or an invisible menu item.
        holdOkWithoutRepeats()
        assertTrue("result regains focus", device.wait(Until.hasObject(By.text("Close")), 5_000))
        press(KeyEvent.KEYCODE_BACK)
        assertTrue(device.wait(Until.gone(By.text("Close")), 5_000))
    }

    private fun holdOkWithoutRepeats() {
        val down = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        SystemClock.sleep(650) // Deliberately models a physical hold with no native repeat events.
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
    }
}
