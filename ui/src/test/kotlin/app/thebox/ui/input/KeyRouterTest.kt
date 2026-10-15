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

    // ---- code review 2026-10-14 (docs/review/m3-code-review.md)

    /** H-S4: keys the machine ignores in FULL go on to M2's media session (the activity does not consume them). */
    @Test fun mediaKeysNotHandledInFullPassThrough() {
        mode(ScreenMode.FULL)
        machine.handlesFn = { it !in setOf(BoxAction.PLAY_PAUSE, BoxAction.SEEK_BACK, BoxAction.SEEK_FORWARD) }
        for (code in listOf(Keys.MEDIA_PLAY_PAUSE, Keys.MEDIA_PLAY, Keys.MEDIA_PAUSE, Keys.MEDIA_FAST_FORWARD, Keys.MEDIA_REWIND)) {
            assertFalse("$code DOWN passes through", down(code))
            assertFalse("$code UP passes through", up(code))
        }
        assertTrue(machine.events.isEmpty())
        assertTrue("CH+ still zaps", down(Keys.CHANNEL_UP))
        assertTrue("⏭ zaps in the player", down(Keys.MEDIA_NEXT))
        assertEquals(listOf(BoxAction.ZAP_UP, BoxAction.ZAP_DOWN), acts().map { it.action })
    }

    @Test fun mediaNextIsUnboundInTheGuide() {
        assertFalse(down(Keys.MEDIA_NEXT))
        assertFalse(down(Keys.MEDIA_PREVIOUS))
    }

    /** H-S5: the old view model's onCleared must not detach the new one's machine. */
    @Test fun detachOfAStaleMachineKeepsTheCurrent() {
        val newer = app.thebox.core.guide.fakes.FakeGuideStateMachine()
        router.attach(newer) { true }
        router.detach(machine) // the old VM clears after the new activity attached
        assertTrue(router.attached === newer)
        assertTrue(down(Keys.DPAD_DOWN))
        assertEquals(1, newer.events.size)
        router.detach(newer)
        assertTrue(router.attached == null)
    }

    /** H-S11: focus loss ends the holds of keys whose UP will never arrive. */
    @Test fun focusLossReleasesHeldActions() {
        down(Keys.DPAD_RIGHT)
        down(Keys.DPAD_RIGHT, repeat = 3, at = 1_200)
        machine.events.clear()
        router.onFocusLost()
        assertEquals(listOf<GuideEvent>(GuideEvent.Release(BoxAction.RIGHT)), machine.events)
        assertFalse("the late UP is not ours any more", up(Keys.DPAD_RIGHT))
    }

    /** H-S11: a held key that stops repeating for 1.5 s lost its UP: the watchdog ends the hold. */
    @Test fun holdWatchdogReleasesAfterSilence() {
        down(Keys.DPAD_DOWN)
        down(Keys.DPAD_DOWN, repeat = 1, at = 1_050)
        assertEquals(KeyRouterImpl.HOLD_WATCHDOG_MS, timers.pending.values.single().first)
        down(Keys.DPAD_DOWN, repeat = 2, at = 1_100)
        assertEquals("re-armed on every repeat", 1, timers.pending.size)
        machine.events.clear()
        timers.fireAll()
        assertEquals(listOf<GuideEvent>(GuideEvent.Release(BoxAction.DOWN)), machine.events)
        assertFalse(down(Keys.DPAD_DOWN, repeat = 3, at = 3_000))
    }

    @Test fun watchdogIsCancelledByTheUp() {
        down(Keys.DPAD_DOWN)
        down(Keys.DPAD_DOWN, repeat = 1, at = 1_050)
        up(Keys.DPAD_DOWN, at = 1_100)
        assertTrue(timers.pending.isEmpty())
    }
}
