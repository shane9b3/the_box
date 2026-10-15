package app.thebox.guide

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.guide.CellKind
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.ScreenMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * I-10: a reminder on a programme starting in ~2 min shows its card at T − 60 s ± 2 s; "Switch now" tunes and goes
 * FULL; a reminder found late (the missed-while-closed path) shows a "started" (late) card.
 */
@RunWith(AndroidJUnit4::class)
class ReminderTest : GuideTestBase() {

    @Test
    fun cardAtLeadTimeThenSwitch() {
        launchGuide()
        awaitGuideReady()
        stopPlayback()
        // Target: the soonest PROGRAMME starting ≥ 90 s from now on a loaded row (channel 1's "Reminder Test Start"
        // at panel start + 120 s when the panel was just started, else any upcoming one within 15 min).
        val now = nowMin()
        val target = onFrame { f ->
            val d = f.data ?: return@onFrame null
            (0 until d.cellCount).filter { i ->
                d.cellKind[i] == CellKind.PROGRAMME && d.cellStart[i] * 60_000L - System.currentTimeMillis() >= 90_000 && d.cellStart[i] <= now + 15
            }.minByOrNull { d.cellStart[it] }?.let { i ->
                val row = (0 until d.rowCount).first { r -> i >= d.rowFirstCell[r] && i < d.rowFirstCell[r] + d.rowCellCount[r] } + d.firstRow
                row to d.cellStart[i]
            }
        }
        assumeTrue("no programme starts within 15 min on the loaded rows", target != null)
        val (row, startMin) = target!!
        toRow(row)
        var guard = 0
        while (focus()!!.cellStart < startMin && guard++ < 20) press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("focused the target programme", startMin, focus()!!.cellStart)
        press(KeyEvent.KEYCODE_MEDIA_RECORD) // REMIND
        var stored = false
        repeat(30) { if (!stored) { stored = ep.reminderBook().pending.value.any { it.rawStartMin == startMin }; if (!stored) Thread.sleep(100) } }
        assertTrue("reminder stored", stored)
        val fireAt = startMin * 60_000L - 60_000
        log("I-10 waiting ${(fireAt - System.currentTimeMillis()) / 1000} s for the card")
        val card = awaitUi("reminder card", fireAt - System.currentTimeMillis() + 10_000) { it.reminder != null }
        val delta = System.currentTimeMillis() - fireAt
        log("I-10 card shown ${delta} ms after T − 60 s")
        assertTrue("card at T − 60 s ± 2 s (Δ $delta ms)", abs(delta) <= 2_000)
        assertTrue("not late", !card.reminder!!.alert.late)
        // Input guard (600 ms), then Switch now (first button).
        Thread.sleep(700)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUi("Switch now → FULL", 10_000) { it.mode == ScreenMode.FULL && it.reminder == null }
        assertEquals(channelAt(row)!!.identity, controller.state.value.target?.identity)
    }

    /**
     * (code review 2026-10-14, H-B3, test gap 7) "Missed while The Box was closed": the activity is closed (the process
     * goes to the background and the reminder runtime stops), a reminder whose programme has already ended is stored,
     * and the guide is started again. The runtime's first pass at ON_START counts it as missed; the toast is sent before
     * the new root composes and must still be shown.
     */
    @Test
    fun missedToastAfterARestart() {
        launchGuide()
        awaitGuideReady()
        stopPlayback()
        toRow(0)
        val ch = channelAt(0)!!
        closeGuide()
        Thread.sleep(2_000) // ProcessLifecycleOwner ON_STOP (700 ms) stops the runtime and forgets what it saw
        val now = nowMin()
        val info = ProgrammeInfo(now - 40, now - 40, now - 10, "Missed test", null, null, null, null, 0, 0)
        runBlocking { ep.reminderBook().set(ch, info, 60) }
        val t0 = SystemClock.elapsedRealtime()
        launchGuide()
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        val shown = device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.textContains("Missed while The Box was closed")), 10_000)
        log("I-10 missed toast ${if (shown) "shown" else "NOT shown"} ${SystemClock.elapsedRealtime() - t0} ms after the restart")
        assertTrue("the missed-reminders toast is shown after a restart", shown == true)
        assertTrue("the missed reminder is no longer pending", ep.reminderBook().pending.value.none { it.rawStartMin == now - 40 && it.identity == ch.identity })
    }

    /**
     * The late path of "missed while closed": a reminder whose programme is already running is caught up by the
     * scheduler's first pass as a late card. A real process restart cannot be done from inside the instrumented
     * process; the scheduler's start-up pass and this in-process pass are the same `decide` call (§3.11.3).
     */
    @Test
    fun lateReminderShowsStarted() {
        launchGuide()
        awaitGuideReady()
        stopPlayback()
        toRow(0)
        val ch = channelAt(0)!!
        val f = focus()!!
        assumeTrue("the on-now programme started over a minute ago", nowMin() - f.cellStart >= 2)
        val info = ProgrammeInfo(f.cellStart, f.cellStart, f.cellEnd, "Late test", null, null, null, null, 0, 0)
        val t0 = SystemClock.elapsedRealtime()
        runBlocking { ep.reminderBook().set(ch, info, 60) }
        val card = awaitUi("late card", 65_000) { it.reminder != null }.reminder!!
        log("I-10 late card after ${SystemClock.elapsedRealtime() - t0} ms: $card")
        assertTrue("late (\"started … ago\")", card.alert.late)
        Thread.sleep(700)
        press(KeyEvent.KEYCODE_BACK) // Dismiss
        awaitUi("dismissed") { it.reminder == null }
    }
}
