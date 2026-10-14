package app.thebox.guide

import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.Zone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** I-19 (B1): the key sink survives GUIDE ↔ FULL ↔ SEARCH: Back in FULL does not exit, CH+ zaps, Search → Back → grid. */
@RunWith(AndroidJUnit4::class)
class ModeSurvivalTest : GuideTestBase() {

    @Test
    fun keysSurviveModeChanges() {
        launchGuide()
        awaitGuideReady()
        stopPlayback()
        toRow(0)
        val ch = tuneFocusedRow()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUi("OK on the playing row → FULL") { it.mode == ScreenMode.FULL }

        press(KeyEvent.KEYCODE_CHANNEL_UP)
        val zapped = runBlocking { withTimeoutOrNull(10_000) { controller.state.first { it.target?.identity != null && it.target?.identity != ch.identity } } }
        assertNotNull("CH+ in FULL zaps", zapped)

        press(KeyEvent.KEYCODE_BACK)
        awaitUi("Back in FULL → GUIDE") { it.mode == ScreenMode.GUIDE }
        Thread.sleep(500)
        assertEquals("the app did not exit", Lifecycle.State.RESUMED, scenario!!.state)
        assertNotEquals(Lifecycle.State.DESTROYED, scenario!!.state)

        // Search → type → Back (IME) → Back (field) → grid.
        goHome()
        press(KeyEvent.KEYCODE_DPAD_UP, times = 3)
        awaitUi("TABS") { it.zone == Zone.TABS }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUi("SEARCH") { it.mode == ScreenMode.SEARCH }
        Thread.sleep(500)
        instrumentation.sendStringSync("news")
        Thread.sleep(800)
        repeat(4) { if (ui().mode == ScreenMode.SEARCH) press(KeyEvent.KEYCODE_BACK, intervalMs = 400) }
        awaitUi("Back from Search → GUIDE grid") { it.mode == ScreenMode.GUIDE && it.zone == Zone.GRID }
        assertEquals("still running", Lifecycle.State.RESUMED, scenario!!.state)
    }
}
