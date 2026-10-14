package app.thebox.reminder

import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideSettings
import app.thebox.core.guide.PendingReminder
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.ReminderBook
import app.thebox.core.guide.ReminderIndex
import app.thebox.core.guide.fakes.FakeGuideSettings
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.guide.reminder.ReminderPlannerImpl
import app.thebox.core.refresh.ChannelIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/design/m3-guide.md §3.11.3, §5.4: the foreground reminder runtime on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderSchedulerTest {

    /** Minimal book: pending rows set directly; records every write. Writes only affect pending rows (setStateIfPending). */
    private class TestBook : ReminderBook {
        val rows = MutableStateFlow<List<PendingReminder>>(emptyList())
        val fired = ArrayList<Long>()
        val dismissed = ArrayList<Long>()
        override val index: StateFlow<ReminderIndex> = MutableStateFlow(ReminderIndex.EMPTY)
        override val pending: StateFlow<List<PendingReminder>> get() = rows

        fun add(r: PendingReminder) { rows.value = rows.value + r }
        fun remove(id: Long) { rows.value = rows.value.filter { it.id != id } }

        override suspend fun set(channel: GuideChannel, info: ProgrammeInfo, leadSec: Int): Boolean = error("unused")
        override suspend fun cancel(identity: ChannelIdentity, rawStartMin: Int) {
            rows.value = rows.value.filter { !(it.identity == identity && it.rawStartMin == rawStartMin) }
        }
        override suspend fun markFired(id: Long) { if (rows.value.any { it.id == id }) fired += id; remove(id) }
        override suspend fun dismiss(id: Long) { if (rows.value.any { it.id == id }) dismissed += id; remove(id) }
        override suspend fun markOrphaned(id: Long) = remove(id)
    }

    private val base = GuideFixtures.NOW_MIN // minutes; virtual time 0 = base
    private val baseMs = base * 60_000L

    private fun reminder(id: Long, startOffsetMin: Int, durMin: Int = 30, shiftMin: Int = 0, leadSec: Int = 60) = PendingReminder(
        id = id, identity = GuideFixtures.identity(id.toInt()), rawStartMin = base + startOffsetMin,
        rawEndMin = base + startOffsetMin + durMin, shiftMin = shiftMin, title = "P$id", channelName = "C$id", leadSec = leadSec,
    )

    private class Rig(
        val book: TestBook,
        val fg: MutableStateFlow<Boolean>,
        val settings: FakeGuideSettings,
        val scheduler: ReminderSchedulerImpl,
        val missed: List<Int>,
    )

    private fun TestScope.rig(foreground: Boolean = true, autoSwitch: Boolean = false, setup: TestBook.() -> Unit = {}): Rig {
        val book = TestBook().apply(setup)
        val fg = MutableStateFlow(foreground)
        val settings = FakeGuideSettings(GuideSettings(reminderAutoSwitch = autoSwitch))
        val s = ReminderSchedulerImpl(book, ReminderPlannerImpl(), settings, fg, backgroundScope) { baseMs + testScheduler.currentTime }
        val missed = ArrayList<Int>()
        backgroundScope.launch { s.missed.collect { missed += it } }
        runCurrent()
        return Rig(book, fg, settings, s, missed)
    }

    private fun TestScope.at(minute: Int, second: Int = 0) {
        val target = minute * 60_000L + second * 1_000L
        advanceTimeBy(target - testScheduler.currentTime)
        runCurrent()
    }

    @Test
    fun runsOnlyWhileForeground() = runTest {
        val r = rig(foreground = false) { add(reminder(1, startOffsetMin = 5)) }
        at(10)
        assertNull(r.scheduler.current.value)
        assertTrue(r.book.fired.isEmpty())
        r.fg.value = true
        runCurrent()
        assertEquals(1L, r.scheduler.current.value?.reminderId)
        assertTrue(r.scheduler.current.value!!.late)
    }

    @Test
    fun firesAtDisplayedTimeOnShiftedReminder() = runTest {
        // Raw start at +5, airs at +65 on a "+1" channel; lead 60 s → fires at +64.
        val r = rig { add(reminder(1, startOffsetMin = 5, shiftMin = 60)) }
        at(4)
        assertNull("not at the raw time", r.scheduler.current.value)
        at(63, 59)
        assertNull("not before the displayed fire time", r.scheduler.current.value)
        at(64)
        val a = r.scheduler.current.value!!
        assertEquals(1L, a.reminderId)
        assertEquals((base + 65) * 60_000L, a.startAtMs)
        assertFalse(a.late)
        assertEquals("C1", a.channelName)
        assertEquals("P1", a.title)
    }

    @Test
    fun leadTimeAndAutoSwitchFlag() = runTest {
        val r = rig(autoSwitch = true) { add(reminder(1, startOffsetMin = 10, leadSec = 300)) }
        at(4, 59)
        assertNull(r.scheduler.current.value)
        at(5)
        assertEquals(1L, r.scheduler.current.value?.reminderId)
        assertTrue(r.scheduler.current.value!!.autoSwitch)
    }

    @Test
    fun lateFlagWhenShownMoreThanAMinuteAfterStart() = runTest {
        val r = rig(foreground = false) { add(reminder(1, startOffsetMin = 2)) }
        at(3)
        r.fg.value = true; runCurrent()
        assertFalse("1 min after start is not late", r.scheduler.current.value!!.late)
        r.fg.value = false; runCurrent()
        assertNull(r.scheduler.current.value)
        at(3, 1)
        r.fg.value = true; runCurrent()
        assertTrue(r.scheduler.current.value!!.late)
    }

    @Test
    fun catchUpOnStartLateCardAndMissedCountOnce() = runTest {
        val r = rig(foreground = false) {
            add(reminder(1, startOffsetMin = 1, durMin = 60)) // still on at +20
            add(reminder(2, startOffsetMin = 1, durMin = 10)) // ended at +11
            add(reminder(3, startOffsetMin = 2, durMin = 5)) // ended at +7
        }
        at(20)
        r.fg.value = true; runCurrent()
        assertEquals(1L, r.scheduler.current.value?.reminderId)
        assertTrue(r.scheduler.current.value!!.late)
        assertEquals(listOf(2L, 3L), r.book.fired.sorted())
        assertEquals(listOf(2), r.missed)
        at(30)
        assertEquals("emitted once", listOf(2), r.missed)
    }

    @Test
    fun missedCountsLatePendingLoad() = runTest {
        // The book's pending list arrives after the first pass (Room loads asynchronously).
        val r = rig(foreground = false)
        at(20)
        r.fg.value = true; runCurrent()
        r.book.add(reminder(5, startOffsetMin = 1, durMin = 5))
        runCurrent()
        assertEquals(listOf(5L), r.book.fired)
        assertEquals(listOf(1), r.missed)
    }

    @Test
    fun oneCardAtATimeFifo() = runTest {
        val r = rig {
            add(reminder(1, startOffsetMin = 5))
            add(reminder(2, startOffsetMin = 5))
            add(reminder(3, startOffsetMin = 6))
        }
        at(4)
        assertEquals(1L, r.scheduler.current.value?.reminderId)
        r.scheduler.dismiss(1); runCurrent()
        assertEquals(2L, r.scheduler.current.value?.reminderId)
        at(5, 30)
        r.scheduler.accept(2); runCurrent()
        assertEquals(3L, r.scheduler.current.value?.reminderId)
        r.scheduler.accept(3); runCurrent()
        assertNull(r.scheduler.current.value)
        assertEquals(listOf(1L), r.book.dismissed)
        assertEquals(listOf(2L, 3L), r.book.fired)
    }

    @Test
    fun autoDismissAfterCardLifetimeMarksFired() = runTest {
        val r = rig {
            add(reminder(1, startOffsetMin = 10, leadSec = 120)) // fires at +8, card until +11
            add(reminder(2, startOffsetMin = 10, leadSec = 120))
        }
        at(8)
        assertEquals(1L, r.scheduler.current.value?.reminderId)
        at(10, 59)
        assertEquals(1L, r.scheduler.current.value?.reminderId)
        assertTrue(r.book.fired.isEmpty())
        at(11)
        assertEquals(listOf(1L), r.book.fired)
        val next = r.scheduler.current.value!!
        assertEquals(2L, next.reminderId)
        // The next card's countdown starts when it is shown: until +12.
        at(11, 59)
        assertEquals(2L, r.scheduler.current.value?.reminderId)
        at(12)
        assertNull(r.scheduler.current.value)
        assertEquals(listOf(1L, 2L), r.book.fired)
    }

    @Test
    fun acceptAndDismissWriteTheRightBookCalls() = runTest {
        val r = rig { add(reminder(1, startOffsetMin = 5)); add(reminder(2, startOffsetMin = 5)) }
        at(4)
        r.scheduler.accept(1); runCurrent()
        assertEquals(listOf(1L), r.book.fired)
        assertTrue(r.book.dismissed.isEmpty())
        r.scheduler.dismiss(2); runCurrent()
        assertEquals(listOf(2L), r.book.dismissed)
        assertEquals(listOf(1L), r.book.fired)
        assertNull(r.scheduler.current.value)
        at(20)
        assertNull("never shown again", r.scheduler.current.value)
    }

    @Test
    fun cancellingAQueuedOrShownReminderRemovesIt() = runTest {
        val r = rig {
            add(reminder(1, startOffsetMin = 5))
            add(reminder(2, startOffsetMin = 5))
            add(reminder(3, startOffsetMin = 5))
        }
        at(4)
        assertEquals(1L, r.scheduler.current.value?.reminderId)
        r.book.cancel(GuideFixtures.identity(2), base + 5) // queued
        r.book.cancel(GuideFixtures.identity(1), base + 5) // shown
        runCurrent()
        assertEquals(3L, r.scheduler.current.value?.reminderId)
        r.scheduler.dismiss(3); runCurrent()
        assertNull(r.scheduler.current.value)
        assertEquals(listOf(3L), r.book.dismissed)
        assertTrue(r.book.fired.isEmpty())
    }

    @Test
    fun pendingChangeWakesTheLoopEarly() = runTest {
        val r = rig()
        at(10, 30)
        // Added mid-sleep, already due: shown without waiting for the 60 s tick.
        r.book.add(reminder(7, startOffsetMin = 10, leadSec = 60))
        runCurrent()
        assertEquals(7L, r.scheduler.current.value?.reminderId)
    }

    @Test
    fun backgroundClearsTheCardAndRestartShowsItAgain() = runTest {
        val r = rig { add(reminder(1, startOffsetMin = 5)) }
        at(4)
        assertEquals(1L, r.scheduler.current.value?.reminderId)
        r.fg.value = false; runCurrent()
        assertNull(r.scheduler.current.value)
        assertTrue("no write on background", r.book.fired.isEmpty())
        at(10)
        assertNull(r.scheduler.current.value)
        r.fg.value = true; runCurrent()
        assertEquals(1L, r.scheduler.current.value?.reminderId)
        assertTrue(r.scheduler.current.value!!.late)
        assertTrue(r.missed.isEmpty())
    }
}
