package app.thebox.guide

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.guide.ScreenMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-9: OK on an archive cell sends one XC timeshift request whose start is the programme start in the **panel's** zone.
 *
 * The panel's zone is switched to Asia/Tokyo at runtime (`timezone` is an `/admin/config` key) and the playlist is
 * refreshed so `server_info.timezone` (the playlist's TimeBase) follows; `timeshift_expect` + `strict_timeshift_tz`
 * make the panel 404 (and count a warning) for any other start.
 */
@RunWith(AndroidJUnit4::class)
class CatchupTest : GuideTestBase() {

    @Test
    fun archiveCellPlaysCatchupInPanelZone() {
        val cfg = panel.setTimezone("Asia/Tokyo")
        assumeTrue("the panel does not accept a runtime timezone", cfg.optString("timezone") == "Asia/Tokyo")
        seed(forceRefresh = true) // re-read server_info.timezone
        launchGuide()
        awaitGuideReady()
        stopPlayback()
        toRow(0) // synthetic channel 1: xc catch-up, 7 days
        val ch = channelAt(0)!!
        assumeTrue("row 0 has no archive (${ch.displayName})", ch.archiveDays > 0)
        press(KeyEvent.KEYCODE_DPAD_LEFT) // the programme before the one on now: finished, in the archive
        val f = focus()!!
        assertTrue("focused a finished programme ($f)", f.cellEnd <= nowMin())
        val startUnix = f.cellStart.toLong() * 60 // epg shift is 0 on the synthetic panel, so display = raw
        panel.expectTimeshift(listOf(startUnix), strict = true)
        val before = panel.timeshiftRequests().size
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        val deadline = System.currentTimeMillis() + 10_000
        while (panel.timeshiftRequests().size <= before && System.currentTimeMillis() < deadline) Thread.sleep(200)
        val reqs = panel.timeshiftRequests().drop(before)
        log("I-9 timeshift requests: ${reqs.map { it.optString("path") }}")
        assertTrue("a timeshift request reached the panel", reqs.isNotEmpty())
        assertEquals("timeshift starts the panel flagged (wrong zone)", 0, panel.timeshiftWarnings())
        awaitUi("FULL with an archive tune", 15_000) { it.mode == ScreenMode.FULL && it.tuned.isArchive }
        assertEquals(ch.identity, controller.state.value.target?.identity)
    }
}
