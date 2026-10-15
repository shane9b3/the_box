package app.thebox.guide

import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.ScreenMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** I-7 Groups and the three accelerators; I-8 the day strip and the back ladder (B2). */
@RunWith(AndroidJUnit4::class)
class GroupsAndBackTest : GuideTestBase() {

    /** I-7: Left at the edge opens Groups; CH± switches groups with the panel open; long ←/→ in FULL cycles with the card. */
    @Test
    fun groupsAndAccelerators() {
        launchGuide()
        awaitGuideReady()
        stopPlayback()
        toRow(findRow { it.archiveDays == 0 })
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        val g0 = awaitUi("Left at the edge opens Groups") { it.overlay is Overlay.Groups }.overlay as Overlay.Groups
        val label0 = ui().sourceLabel
        press(KeyEvent.KEYCODE_CHANNEL_DOWN)
        val g1 = awaitUi("CH− applies the next entry, panel open") { (it.overlay as? Overlay.Groups)?.current != g0.current }.overlay as Overlay.Groups
        awaitUi("source label changed") { it.sourceLabel != label0 }
        // `focused` indexes the shortcuts first, then the entries; `current` indexes the entries (Overlay.Groups KDoc).
        assertEquals("focused follows the applied entry", g1.shortcuts.size + g1.current, g1.focused)
        press(KeyEvent.KEYCODE_CHANNEL_UP)
        awaitUi("CH+ applies the previous entry") { (it.overlay as? Overlay.Groups)?.current == g0.current }
        press(KeyEvent.KEYCODE_BACK)
        awaitUi("Groups closed") { it.overlay == null }

        // Long ←/→ in FULL: GROUP_PREV/NEXT with the group card; the channel keeps playing.
        goHome()
        toRow(0)
        val ch = tuneFocusedRow()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUi("FULL") { it.mode == ScreenMode.FULL }
        val src = ui().sourceLabel
        longPress(KeyEvent.KEYCODE_DPAD_RIGHT)
        val card = awaitUi("group card after long →") { it.groupCard != null }.groupCard
        log("I-7 group card: $card (was $src)")
        // The card names the new group at once; the header label follows when its lineup arrives (async).
        awaitUi("source changed") { it.sourceLabel != src }
        assertEquals("the channel keeps playing", ch.identity, controller.state.value.target?.identity)
        longPress(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitUi("long ← back to the first source") { it.sourceLabel == src }
        press(KeyEvent.KEYCODE_BACK)
        awaitUi("GUIDE") { it.mode == ScreenMode.GUIDE }
    }

    /** I-8: the day strip keeps the clock time; Back ladder: home → FULL → GUIDE (home) → exit hint → exit. */
    @Test
    fun dayStripAndBackLadder() {
        launchGuide()
        awaitGuideReady()
        stopPlayback()
        toRow(0)
        val start = focus()!!
        press(KeyEvent.KEYCODE_DPAD_UP)
        press(KeyEvent.KEYCODE_DPAD_RIGHT) // DAY chip (NOW is hidden at home)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        val days = awaitUi("Days") { it.overlay is Overlay.Days }.overlay as Overlay.Days
        log("I-8 days: ${days.items.map { it.label }} focused=${days.focused}")
        // §3.5.1: "Tonight 20:00" sits right after Today, so "next day" is the Tomorrow item, not the next item.
        val tomorrow = days.items.indexOfFirst { it.label == "Tomorrow" }
        assertTrue("a Tomorrow item after the focused day", tomorrow > days.focused)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, times = tomorrow - days.focused)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUi("Days closed") { it.overlay == null }
        val jumped = focus()!!
        val delta = jumped.anchorMin - start.anchorMin
        assertTrue("next day keeps the clock time (Δ $delta min)", delta in 1440 - 30..1440 + 30)

        // Back ladder (B2): scrolled → home → FULL → GUIDE home → exit hint → exit.
        goHome()
        // The day jump left the focus on tomorrow: one Back is "scrolled → home" (focus at now), then OK tunes.
        if (!focusContainsNow()) {
            press(KeyEvent.KEYCODE_BACK)
            awaitUi("Back → home after the day jump") { it.overlay == null && focusContainsNow() }
        }
        val ch = tuneFocusedRow()
        press(KeyEvent.KEYCODE_DPAD_RIGHT, 3)
        press(KeyEvent.KEYCODE_BACK)
        awaitUi("Back → home") { it.mode == ScreenMode.GUIDE && it.overlay == null }
        awaitFrame("home: tuned row at now") { f -> f.focus?.let { f.rows.channel(it.row)?.identity == ch.identity && nowMin() in it.cellStart until it.cellEnd } == true }
        press(KeyEvent.KEYCODE_BACK)
        awaitUi("Back → FULL") { it.mode == ScreenMode.FULL }
        press(KeyEvent.KEYCODE_BACK)
        awaitUi("Back → GUIDE (home)") { it.mode == ScreenMode.GUIDE }
        press(KeyEvent.KEYCODE_BACK)
        awaitUi("Back → exit hint, not FULL again") { (it.overlay as? Overlay.Exit)?.dialog == false && it.mode == ScreenMode.GUIDE }
        press(KeyEvent.KEYCODE_BACK)
        val deadline = System.currentTimeMillis() + 5_000
        while (scenario!!.state != Lifecycle.State.DESTROYED && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertEquals("second Back exits", Lifecycle.State.DESTROYED, scenario!!.state)
    }
}
