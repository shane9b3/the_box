package app.thebox.ui.input

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import androidx.compose.ui.input.key.KeyEvent
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.fakes.FakeGuideStateMachine
import app.thebox.core.guide.fakes.FakeKeyCapabilities
import app.thebox.core.input.BoxAction
import app.thebox.core.input.DefaultKeyMap
import app.thebox.core.input.KeyGestureDetectorImpl
import app.thebox.core.input.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** docs/design/m3-guide.md §5.4 `KeyRouterTest` (B1, N7, §3.18.1). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyRouterTest {
    private class FakeTimers : KeyRouterImpl.Timers {
        val pending = LinkedHashMap<Any, Pair<Long, () -> Unit>>()
        override fun post(delayMs: Long, block: () -> Unit): Any = Any().also { pending[it] = delayMs to block }
        override fun cancel(token: Any) { pending.remove(token) }
        fun fireAll() { val p = pending.values.toList(); pending.clear(); p.forEach { it.second() } }
    }

    private val machine = FakeGuideStateMachine()
    private val caps = FakeKeyCapabilities()
    private val timers = FakeTimers()
    private var clock = 0L
    private var searchIdle = true
    private val router = KeyRouterImpl(DefaultKeyMap(), KeyGestureDetectorImpl(), caps, timers) { clock }.also {
        it.attach(machine) { searchIdle }
    }

    private fun mode(m: ScreenMode) { machine.mutableUi.value = GuideUiState(mode = m) }
    private fun down(code: Int, repeat: Int = 0, at: Long = 1_000, downAt: Long = 1_000) = router.onNative(code, true, repeat, downAt, at)
    private fun up(code: Int, at: Long = 1_100) = router.onNative(code, false, 0, 1_000, at)
    private fun acts() = machine.events.filterIsInstance<GuideEvent.Act>()

    @Test fun dpadActsOnDownAndConsumesItsUp() {
        assertTrue(down(Keys.DPAD_DOWN))
        assertTrue(up(Keys.DPAD_DOWN))
        assertEquals(listOf(GuideEvent.Act(BoxAction.DOWN, 0, -1, 0)), machine.events)
    }

    @Test fun repeatsCarryHeldMsAndReleaseAfterRepeat() {
        down(Keys.DPAD_RIGHT)
        down(Keys.DPAD_RIGHT, repeat = 5, at = 2_200)
        up(Keys.DPAD_RIGHT, at = 2_300)
        assertEquals(GuideEvent.Act(BoxAction.RIGHT, 5, -1, 1_200), machine.events[1])
        assertEquals(GuideEvent.Release(BoxAction.RIGHT), machine.events[2])
    }

    @Test fun okShortOnUpLongByTimerSwallowsUp() {
        down(Keys.DPAD_CENTER)
        assertTrue(machine.events.isEmpty())
        up(Keys.DPAD_CENTER)
        assertEquals(BoxAction.SELECT, acts().single().action)
        machine.events.clear()
        down(Keys.DPAD_CENTER)
        clock = 1_500
        timers.fireAll()
        assertEquals(BoxAction.CONTEXT_MENU, acts().single().action)
        assertTrue(up(Keys.DPAD_CENTER, at = 1_700))
        assertEquals(1, acts().size)
    }

    @Test fun okFirstRepeatIsLongAndCancelsTheTimer() {
        down(Keys.DPAD_CENTER)
        down(Keys.DPAD_CENTER, repeat = 1, at = 1_400)
        assertEquals(BoxAction.CONTEXT_MENU, acts().single().action)
        assertTrue(timers.pending.isEmpty())
    }

    @Test fun playerContextInFull() {
        mode(ScreenMode.FULL)
        down(Keys.DPAD_LEFT)
        assertTrue(machine.events.isEmpty())
        clock = 1_500
        timers.fireAll()
        assertEquals(BoxAction.GROUP_PREV, acts().single().action)
        machine.events.clear()
        down(Keys.PAGE_UP)
        assertEquals(BoxAction.ZAP_UP, acts().single().action)
        machine.events.clear()
        mode(ScreenMode.GUIDE)
        down(Keys.DPAD_LEFT)
        assertEquals(BoxAction.LEFT, acts().single().action)
    }

    @Test fun digitsCarryTheirValue() {
        down(Keys.NUMPAD_0 + 7)
        assertEquals(GuideEvent.Act(BoxAction.DIGIT, 0, 7, 0), machine.events.single())
    }

    @Test fun searchPassesEverythingThroughExceptBackWhenIdle() {
        mode(ScreenMode.SEARCH)
        for (code in listOf(Keys.DPAD_DOWN, Keys.DPAD_CENTER, Keys.DIGIT_0 + 1, Keys.CHANNEL_UP)) {
            assertFalse(down(code)); assertFalse(up(code))
        }
        searchIdle = false
        assertFalse(down(Keys.BACK)); assertFalse(up(Keys.BACK))
        searchIdle = true
        assertTrue(down(Keys.BACK)); assertTrue(up(Keys.BACK))
        assertEquals(listOf(GuideEvent.Act(BoxAction.BACK, 0, -1, 0)), machine.events)
    }

    @Test fun unboundKeysAreNotHandledButRecorded() {
        assertFalse(down(24)) // VOLUME_UP
        assertFalse(up(24))
        assertTrue(24 in caps.seen.value)
    }

    @Test fun capabilitiesRecordOnlyNewCodes() {
        down(Keys.PROG_RED); up(Keys.PROG_RED)
        down(Keys.PROG_RED); up(Keys.PROG_RED)
        down(Keys.PROG_RED, repeat = 1)
        assertEquals(1, caps.writes)
    }

    @Test fun upWithoutOurDownIsNotHandled() {
        assertFalse(up(Keys.DPAD_DOWN))
        assertFalse(down(Keys.DPAD_DOWN, repeat = 3))
    }

    @Test fun focusLossResetsAndCancelsTimers() {
        down(Keys.DPAD_CENTER)
        router.onFocusLost()
        assertTrue(timers.pending.isEmpty())
        assertFalse(up(Keys.DPAD_CENTER))
        assertTrue(machine.events.isEmpty())
    }

    @Test fun noMachineNoHandling() {
        router.attach(null) { true }
        assertFalse(down(Keys.DPAD_DOWN))
    }

    @Test fun composeEventPath() {
        val e = android.view.KeyEvent(1_000, 1_000, ACTION_DOWN, Keys.DPAD_UP, 0)
        assertTrue(router.onKey(KeyEvent(e)))
        assertTrue(router.onKey(KeyEvent(android.view.KeyEvent(1_000, 1_050, ACTION_UP, Keys.DPAD_UP, 0))))
        assertEquals(BoxAction.UP, acts().single().action)
    }
}
