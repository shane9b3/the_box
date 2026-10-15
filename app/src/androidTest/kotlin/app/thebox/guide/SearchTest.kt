package app.thebox.guide

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.Zone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-11: "news" gives channel and programme rows; OK on an archive hit plays catch-up. The data side is read through
 * `GuideSearch` (the same call the screen makes), the screen through UiAutomator and the key path of §3.10.3:
 * field → DOWN → Channels row → DOWN → Programmes row → RIGHT × index → OK.
 */
@RunWith(AndroidJUnit4::class)
class SearchTest : GuideTestBase() {
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun newsFindsChannelsAndProgrammesAndPlaysArchive() {
        val results = runBlocking { ep.search().search("news", System.currentTimeMillis(), unlocked = false) }
        log("I-11 data: ${results.channels.size} channels, ${results.programmes.size} programmes, ${results.programmes.count { it.inArchive }} in archive")
        assertTrue("channel hits", results.channels.isNotEmpty())
        assertTrue("programme hits", results.programmes.isNotEmpty())

        launchGuide()
        awaitGuideReady()
        stopPlayback()
        openSearch()
        instrumentation.sendStringSync("news")
        assertTrue("result rows on screen", device.wait(Until.hasObject(By.textContains("News")), 10_000))
        log("I-11 screen objects with 'News': ${device.findObjects(By.textContains("News")).size}")

        val k = results.programmes.indexOfFirst { it.inArchive }
        assumeTrue("no archive hit for \"news\" right now", k >= 0)
        // The TV IME keeps the D-pad while it shows (a user presses its ✓ or Back first): hide it, then navigate.
        hideIme()
        Thread.sleep(500)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        if (results.channels.isNotEmpty()) press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, times = k)
        val before = panel.timeshiftRequests().size
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUi("archive hit → FULL catch-up", 15_000) { it.mode == ScreenMode.FULL && it.tuned.isArchive }
        // FULL follows the first archive Tuned (CONNECTING), which can come before the request reaches the panel.
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (panel.timeshiftRequests().size <= before && SystemClock.elapsedRealtime() < deadline) Thread.sleep(200)
        assertTrue("timeshift request seen", panel.timeshiftRequests().size > before)
        // The screen ran its own search a moment later, so the k-th card can be a different archive airing of the
        // same ranking bucket: the criterion (I-11) is that an archive hit plays catch-up on that hit's channel.
        val archiveChannels = results.programmes.filter { it.inArchive }.map { it.channel.identity }.toSet()
        assertTrue("tuned an archive hit's channel", controller.state.value.target?.identity in archiveChannels)
    }

    private fun hideIme() = main {
        var activity: android.app.Activity? = null
        scenario!!.onActivity { activity = it }
        val a = activity!!
        val imm = a.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(a.window.decorView.windowToken, 0)
    }

    private fun openSearch() {
        press(KeyEvent.KEYCODE_DPAD_UP, times = 3)
        awaitUi("TABS") { it.zone == Zone.TABS }
        press(KeyEvent.KEYCODE_DPAD_RIGHT) // Search tab
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUi("SEARCH") { it.mode == ScreenMode.SEARCH }
        Thread.sleep(500) // field focus + IME
    }
}
