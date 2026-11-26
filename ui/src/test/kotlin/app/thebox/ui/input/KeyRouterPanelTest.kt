package app.thebox.ui.input

import app.thebox.core.full.fakes.FakePlayerStateMachine
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.TunedState
import app.thebox.core.guide.fakes.FakeGuideRenderer
import app.thebox.core.guide.fakes.FakeGuideStateMachine
import app.thebox.core.guide.fakes.FakeKeyCapabilities
import app.thebox.core.guide.fakes.FakeNavigator
import app.thebox.core.guide.fakes.FakeTimeLabels
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.guide.fakes.RecordingGuideEffects
import app.thebox.core.guide.state.GuideStateMachineImpl
import app.thebox.core.input.BoxAction
import app.thebox.core.input.DefaultKeyMap
import app.thebox.core.input.KeyGestureDetectorImpl
import app.thebox.core.input.Keys
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.fakes.RecordingSettingsMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (Settings panel A1, docs/design/m6-player-settings-panel.md §2.2, §2.4, S1; §10 `KeyRouterPanelTest`) With the panel
 * open over FULL the router reads the GUIDE key table (`GuideStateMachine.keyContext`): a held BACK has its long binding
 * (PLAY) and never repeat-pops out of the panel and FULL, and D-pad LEFT/RIGHT act on key-down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyRouterPanelTest {
    private class FakeTimers : KeyRouterImpl.Timers {
        val pending = LinkedHashMap<Any, Pair<Long, () -> Unit>>()
        override fun post(delayMs: Long, block: () -> Unit): Any = Any().also { pending[it] = delayMs to block }
        override fun cancel(token: Any) { pending.remove(token) }
        fun fireAll() { val p = pending.values.toList(); pending.clear(); p.forEach { it.second() } }
    }

    private val timers = FakeTimers()
    private var clock = 0L
    private fun router() = KeyRouterImpl(DefaultKeyMap(), KeyGestureDetectorImpl(), FakeKeyCapabilities(), timers) { clock }

    @Test
    fun leftAndRightActOnKeyDownWhileThePanelIsOpen() {
        val machine = FakeGuideStateMachine(GuideUiState(mode = ScreenMode.FULL, settingsPanel = true))
        val r = router().also { it.attach(machine) { true } }
        assertTrue(r.onNative(Keys.DPAD_LEFT, true, 0, 1_000, 1_000))
        assertEquals("LEFT on DOWN, no GROUP_PREV long press", listOf(GuideEvent.Act(BoxAction.LEFT, 0, -1, 0)), machine.events)
        assertTrue(timers.pending.isEmpty())
        assertTrue(r.onNative(Keys.DPAD_LEFT, false, 0, 1_000, 1_100))
        machine.events.clear()
        r.onNative(Keys.DPAD_RIGHT, true, 0, 2_000, 2_000)
        r.onNative(Keys.DPAD_RIGHT, true, 3, 2_000, 2_300)
        assertEquals(listOf(BoxAction.RIGHT, BoxAction.RIGHT), machine.events.filterIsInstance<GuideEvent.Act>().map { it.action })
        // Closed: FULL reads the PLAYER table again (LEFT waits for its long binding).
        machine.mutableUi.value = GuideUiState(mode = ScreenMode.FULL)
        r.onNative(Keys.DPAD_RIGHT, false, 0, 2_000, 2_400)
        machine.events.clear()
        r.onNative(Keys.DPAD_LEFT, true, 0, 3_000, 3_000)
        assertTrue(machine.events.isEmpty())
    }

    /** §12 state 4 at the router: a held BACK three pages deep pops nothing; three presses back to the root list, one more closes. */
    @Test
    fun aHeldBackThreePagesDeepNeitherPopsNorLeavesFull() {
        var now = 1_000_000L
        val effects = RecordingGuideEffects()
        val guide = GuideStateMachineImpl(effects, FakeNavigator(), FakeTimeLabels(), FakeGuideRenderer()) { now }
        val player = FakePlayerStateMachine()
        guide.full = player
        val stack = ArrayList<SettingsRoute>()
        val s = RecordingSettingsMachine()
        s.consume = { a ->
            when (a.action) {
                BoxAction.BACK -> if (stack.isEmpty()) false else { stack.removeAt(stack.lastIndex); s.showRoute(stack.lastOrNull() ?: SettingsRoute.Root); true }
                BoxAction.OPEN_GUIDE, BoxAction.EXIT -> false
                else -> true
            }
        }
        guide.settings = s
        val ch = GuideFixtures.channel(1)
        guide.dispatch(GuideEvent.StartFull)
        guide.dispatch(GuideEvent.Tuned(TunedState(ch.identity, ch.rowId, ch.identityHash, MiniPhase.PLAYING), ch))
        assertEquals(ScreenMode.FULL, guide.ui.value.mode)
        guide.dispatch(GuideEvent.OpenSettings)
        assertTrue(guide.ui.value.settingsPanel)
        for (route in listOf(SettingsRoute.Section(SectionId.PLAYBACK), SettingsRoute.Diagnostics, SettingsRoute.ButtonMapping)) { stack += route; s.showRoute(route) }

        val r = router().also { it.attach(guide) { true } }
        // Hold BACK: DOWN, auto-repeats for two seconds, UP.
        assertTrue(r.onNative(Keys.BACK, true, 0, 5_000, 5_000))
        for (i in 1..40) assertTrue(r.onNative(Keys.BACK, true, i, 5_000, 5_000 + 50L * i))
        assertTrue(r.onNative(Keys.BACK, false, 0, 5_000, 7_100))
        timers.fireAll()
        val acts = s.acts.map { it.action }
        assertEquals("one long press (PLAY), no BACK repeats: $acts", listOf(BoxAction.PLAY), acts)
        assertEquals(ScreenMode.FULL, guide.ui.value.mode)
        assertTrue(guide.ui.value.settingsPanel)
        assertEquals(3, stack.size)
        assertTrue("nothing reached the player", player.acts.isEmpty())

        // Four presses: three pages, then the root list's BACK closes the panel once; FULL stays.
        repeat(4) { i ->
            val t = 10_000L + i * 1_000
            r.onNative(Keys.BACK, true, 0, t, t)
            r.onNative(Keys.BACK, false, 0, t, t + 80)
        }
        assertFalse(guide.ui.value.settingsPanel)
        assertEquals(ScreenMode.FULL, guide.ui.value.mode)
        assertEquals(1, s.leaves)
        assertEquals("the player is told (A2 shows the quick bar on Settings)", false, player.panelCalls.last())
        assertTrue(player.acts.isEmpty())
    }
}
