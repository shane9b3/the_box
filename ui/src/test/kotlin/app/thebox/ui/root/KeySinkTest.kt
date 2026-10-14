package app.thebox.ui.root

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.thebox.core.guide.GuideEffects
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideFrame
import app.thebox.core.guide.GuideStateMachine
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.TunedState
import app.thebox.core.guide.fakes.FakeGuideStateMachine
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.guide.fakes.RecordingGuideEffects
import app.thebox.core.input.BoxAction
import app.thebox.core.playback.Viewport
import app.thebox.ui.full.FULL_SCREEN_TAG
import app.thebox.ui.guide.overlay.EXIT_HINT_TAG
import app.thebox.ui.guide.screen.GUIDE_SCREEN_TAG
import app.thebox.ui.shelltest.FakeShellHost
import app.thebox.ui.shelltest.TestKeyRouter
import app.thebox.ui.theme.BoxTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B1 (`KeySinkModeTest`): the one key sink at `AppRoot` keeps focus across GUIDE → FULL → GUIDE and BACK in FULL
 * reaches the state machine; SEARCH passes keys through except BACK. B2: the Back ladder as the shell plays it, with a
 * scripted machine (the real ladder is WP-D's `BackLadderTest`): home → FULL → home → exit hint → exit, never FULL
 * again, with the viewport following each step.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class KeySinkTest {
    @Suppress("DEPRECATION") // v1 rule: UnconfinedTestDispatcher, matching how these tests synchronise
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val playing = TunedState(identity = GuideFixtures.identity(1), rowId = 1, phase = MiniPhase.PLAYING)

    private fun pressBack() = rule.onNodeWithTag(KEY_SINK_TAG).performKeyInput { pressKey(Key.Back) }

    @Test
    fun sinkKeepsFocusAcrossGuideFullGuideAndBackReachesTheMachineInFull() {
        val machine = FakeGuideStateMachine(GuideUiState(tuned = playing))
        val router = TestKeyRouter()
        val host = FakeShellHost(machine, router).also { router.attach(machine) { true } }
        rule.setContent { BoxTheme { AppRoot(host, grid = { m -> Spacer(m) }) } }
        rule.waitForIdle()
        rule.onNodeWithTag(KEY_SINK_TAG).assertIsFocused()
        val lostBefore = router.focusLost // onFocusChanged reports the initial (unfocused) state once on attach

        machine.mutableUi.update { it.copy(mode = ScreenMode.FULL) }
        rule.waitForIdle()
        rule.onNodeWithTag(FULL_SCREEN_TAG).fetchSemanticsNode()
        rule.onNodeWithTag(KEY_SINK_TAG).assertIsFocused()

        pressBack()
        rule.waitForIdle()
        assertEquals(GuideEvent.Act(BoxAction.BACK), machine.events.last())
        assertEquals("the matching KeyUp did not reach the Activity", listOf(BoxAction.BACK), router.seen)

        machine.mutableUi.update { it.copy(mode = ScreenMode.GUIDE) }
        rule.waitForIdle()
        rule.onNodeWithTag(GUIDE_SCREEN_TAG).fetchSemanticsNode()
        rule.onNodeWithTag(KEY_SINK_TAG).assertIsFocused()
        assertEquals("focus never left the sink", lostBefore, router.focusLost)
    }

    @Test
    fun searchPassesKeysThroughExceptBack() {
        val machine = FakeGuideStateMachine(GuideUiState(mode = ScreenMode.SEARCH))
        val router = TestKeyRouter()
        val host = FakeShellHost(machine, router).also { router.attach(machine) { true } }
        rule.setContent { BoxTheme { AppRoot(host, grid = { m -> Spacer(m) }) } }
        rule.waitForIdle()
        rule.onNodeWithTag(KEY_SINK_TAG).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        assertTrue(machine.events.isEmpty())
        pressBack()
        rule.waitForIdle()
        assertEquals(GuideEvent.Act(BoxAction.BACK), machine.events.single())
    }

    /** B2's ladder from home, scripted: the shell must render each step and keep routing BACK to the machine. */
    private class LadderMachine(private val effects: GuideEffects, initial: GuideUiState) : GuideStateMachine {
        private val state = MutableStateFlow(initial)
        override val ui: StateFlow<GuideUiState> = state
        override val frame = GuideFrame()
        override val frameVersion: StateFlow<Long> = MutableStateFlow(0L)
        var scrolled = true
        var returnedFromFull = false
        override fun dispatch(event: GuideEvent) {
            if (event !is GuideEvent.Act || event.action != BoxAction.BACK) return
            val s = state.value
            when {
                s.overlay is Overlay.Exit -> effects.exitApp()
                s.mode == ScreenMode.FULL -> {
                    returnedFromFull = true
                    effects.setFullScreen(false)
                    state.value = s.copy(mode = ScreenMode.GUIDE)
                }
                scrolled -> scrolled = false
                s.tuned.phase != MiniPhase.IDLE && !returnedFromFull -> {
                    effects.setFullScreen(true)
                    state.value = s.copy(mode = ScreenMode.FULL)
                }
                else -> state.value = s.copy(overlay = Overlay.Exit(dialog = false, focused = 0))
            }
        }
    }

    @Test
    fun backLadderHomeFullHomeExitHintExit() {
        val effects = object : GuideEffects by RecordingGuideEffects() {
            lateinit var host: FakeShellHost
            val calls = ArrayList<String>()
            override fun setFullScreen(full: Boolean) { calls += "full:$full" }
            override fun exitApp() { calls += "exit"; host.eventsFlow.tryEmit(ShellEvent.Exit) }
        }
        val machine = LadderMachine(effects, GuideUiState(tuned = playing))
        val router = TestKeyRouter()
        val host = FakeShellHost(machine, router).also { router.attach(machine) { true }; effects.host = it }
        var exited = 0
        rule.setContent { BoxTheme { AppRoot(host, onExit = { exited++ }, grid = { m -> Spacer(m) }) } }
        rule.waitForIdle()

        pressBack(); rule.waitForIdle() // scrolled → home
        rule.onNodeWithTag(GUIDE_SCREEN_TAG).fetchSemanticsNode()
        pressBack(); rule.waitForIdle() // home → FULL
        rule.onNodeWithTag(FULL_SCREEN_TAG).fetchSemanticsNode()
        assertEquals(Viewport.FULL, host.viewports.last())
        pressBack(); rule.waitForIdle() // FULL → home (returnedFromFull)
        rule.onNodeWithTag(GUIDE_SCREEN_TAG).fetchSemanticsNode()
        assertEquals(Viewport.MINI, host.viewports.last())
        pressBack(); rule.waitForIdle() // → exit hint, never FULL again
        rule.onNodeWithTag(EXIT_HINT_TAG).fetchSemanticsNode()
        pressBack(); rule.waitForIdle() // → exit
        assertEquals(listOf("full:true", "full:false", "exit"), effects.calls)
        assertEquals(1, exited)
        assertEquals(listOf(Viewport.MINI, Viewport.FULL, Viewport.MINI), host.viewports)
    }
}
