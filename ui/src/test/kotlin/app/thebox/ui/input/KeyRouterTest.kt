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

    /** M5 commit 0 (docs/design/m5-setup-settings.md §2.12 item 5, S3): the editor owns every key but BACK. */
    @Test fun textInputActivePassesEveryKeyButBackWhichBecomesEditorBack() {
        val settings = app.thebox.core.settings.fakes.RecordingSettingsMachine()
        machine.settings = settings
        mode(ScreenMode.SETTINGS)
        machine.textInput = true
        for (code in intArrayOf(Keys.DPAD_DOWN, Keys.DPAD_CENTER, Keys.DIGIT_0 + 5, Keys.CHANNEL_UP, Keys.MENU)) {
            assertFalse("$code goes to Compose", down(code))
            assertFalse(up(code))
        }
        assertTrue(machine.events.isEmpty())
        for (code in intArrayOf(Keys.BACK, Keys.ESCAPE, Keys.BUTTON_B)) {
            assertTrue(down(code))
            assertTrue("repeats consumed", down(code, repeat = 1, at = 1_050))
            assertTrue(up(code))
        }
        assertEquals(List(3) { app.thebox.core.settings.SettingsEvent.EditorBack }, settings.events)
        assertTrue("BACK never reaches the guide machine as an Act", machine.events.isEmpty())
        machine.textInput = false
        assertTrue(down(Keys.BACK))
        assertTrue(up(Keys.BACK))
        assertEquals(BoxAction.BACK, acts().single().action)
    }

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

    @Test fun sportsPassesEverythingThroughExceptBackWhileNoChoiceListIsOpen() {
        mode(ScreenMode.SPORTS)
        for (code in listOf(Keys.DPAD_DOWN, Keys.DPAD_CENTER, Keys.DPAD_LEFT, Keys.DIGIT_0 + 1, Keys.CHANNEL_UP)) {
            assertFalse(down(code)); assertFalse(up(code))
        }
        searchIdle = false // a Corner / Delay choice list is open: the screen closes it
        assertFalse(down(Keys.BACK)); assertFalse(up(Keys.BACK))
        searchIdle = true
        assertTrue(down(Keys.BACK)); assertTrue(up(Keys.BACK))
        assertEquals(listOf(GuideEvent.Act(BoxAction.BACK, 0, -1, 0)), machine.events)
    }

    /** U1 (code review 2026-11-09): focus fell to the sink, so the screen never sees BACK; the router must not drop it. */
    @Test fun searchAndSportsTakeBackWhileTheSinkItselfHoldsFocus() {
        searchIdle = false // query typed / choice list open
        for (m in listOf(ScreenMode.SEARCH, ScreenMode.SPORTS)) {
            mode(m)
            machine.events.clear()
            router.sinkFocused = false
            assertFalse(down(Keys.BACK)); assertFalse(up(Keys.BACK))
            router.sinkFocused = true
            assertTrue("$m: BACK on the sink is handled", down(Keys.BACK)); assertTrue(up(Keys.BACK))
            assertFalse("other keys still pass", down(Keys.DPAD_DOWN))
            assertEquals(listOf(GuideEvent.Act(BoxAction.BACK, 0, -1, 0)), machine.events)
        }
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
        // M4: the machine decides (FULL now handles these itself); the router's pass-through rule is what is tested here.
        machine.handlesFn = { it !in setOf(BoxAction.PLAY_PAUSE, BoxAction.PLAY, BoxAction.PAUSE, BoxAction.SEEK_BACK, BoxAction.SEEK_FORWARD) }
        for (code in listOf(Keys.MEDIA_PLAY_PAUSE, Keys.MEDIA_PLAY, Keys.MEDIA_PAUSE, Keys.MEDIA_FAST_FORWARD, Keys.MEDIA_REWIND)) {
            assertFalse("$code DOWN passes through", down(code))
            assertFalse("$code UP passes through", up(code))
        }
        assertTrue(machine.events.isEmpty())
        assertTrue("CH+ still zaps", down(Keys.CHANNEL_UP))
        assertTrue("⏭ zaps in the player", down(Keys.MEDIA_NEXT))
        // M4 S6 + owner ruling: ⏭ follows CH+ (ZAP_UP = +1).
        assertEquals(listOf(BoxAction.ZAP_UP, BoxAction.ZAP_UP), acts().map { it.action })
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

    // ---- M4 (docs/design/m4-player-ux.md §3.1.7, reviewed 2026-10-15, S5)

    /** A machine whose [horizontalRepeat] the test drives (the fake's contract default is false). */
    private class RepeatMachine(val inner: FakeGuideStateMachine) : app.thebox.core.guide.GuideStateMachine by inner {
        var repeat = false
        override val horizontalRepeat: Boolean get() = repeat
    }

    @Test fun leftWithHorizontalRepeatRepeatsAndNeverFiresGroupPrev() {
        val m = RepeatMachine(machine).also { it.repeat = true }
        router.attach(m) { true }
        mode(ScreenMode.FULL)
        assertTrue(down(Keys.DPAD_LEFT))
        down(Keys.DPAD_LEFT, repeat = 1, at = 1_050)
        down(Keys.DPAD_LEFT, repeat = 2, at = 1_100)
        clock = 1_600
        val longTimers = timers.pending.values.filter { it.first == KeyRouterImpl.LONG_PRESS_MS }
        assertTrue("no long-press timer armed", longTimers.isEmpty())
        assertTrue(up(Keys.DPAD_LEFT, at = 1_150))
        assertEquals(listOf(BoxAction.LEFT, BoxAction.LEFT, BoxAction.LEFT), acts().map { it.action })
        assertEquals(listOf(0, 1, 2), acts().map { it.repeat })
        assertEquals(GuideEvent.Release(BoxAction.LEFT), machine.events.last())
    }

    @Test fun decisionIsFixedAtTheFirstDown() {
        val m = RepeatMachine(machine)
        router.attach(m) { true }
        mode(ScreenMode.FULL)
        // Off at the first DOWN: the long binding stands even if the machine raises the flag mid-hold.
        down(Keys.DPAD_RIGHT)
        m.repeat = true
        down(Keys.DPAD_RIGHT, repeat = 1, at = 1_050)
        assertEquals(listOf(BoxAction.GROUP_NEXT), acts().map { it.action })
        up(Keys.DPAD_RIGHT, at = 1_100)
        machine.events.clear()
        // On at the first DOWN: a later drop of the flag (the scrub bar closing) does not turn the hold into a long press.
        down(Keys.DPAD_RIGHT, at = 2_000, downAt = 2_000)
        m.repeat = false
        down(Keys.DPAD_RIGHT, repeat = 1, at = 2_050, downAt = 2_000)
        assertEquals(listOf(BoxAction.RIGHT, BoxAction.RIGHT), acts().map { it.action })
        up(Keys.DPAD_RIGHT, at = 2_100)
        assertEquals(GuideEvent.Release(BoxAction.RIGHT), machine.events.last())
    }

    @Test fun horizontalRepeatLeavesOkAndUpDownAlone() {
        val m = RepeatMachine(machine).also { it.repeat = true }
        router.attach(m) { true }
        mode(ScreenMode.FULL)
        down(Keys.DPAD_CENTER)
        assertTrue("OK keeps its long binding", machine.events.isEmpty())
        clock = 1_500
        timers.fireAll()
        assertEquals(BoxAction.CONTEXT_MENU, acts().single().action)
    }
}
