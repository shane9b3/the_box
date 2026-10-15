package app.thebox.ui.root

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.thebox.core.guide.FooterHint
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.PanelButton
import app.thebox.core.guide.PanelState
import app.thebox.core.guide.TunedState
import app.thebox.core.guide.fakes.FakeGuideStateMachine
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.ui.guide.GuideDebugCounters
import app.thebox.ui.guide.screen.COMPOSITIONS_MINI
import app.thebox.ui.guide.screen.COMPOSITIONS_PANEL
import app.thebox.ui.guide.screen.LIVE_DOT_DRAWS
import app.thebox.ui.shelltest.FakeShellHost
import app.thebox.ui.theme.BoxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Code review 2026-10-14: what the shell redraws when nothing it shows changed.
 * - H-S9: a grid move (a new panel, the same mini-player slice) recomposes the panel only, never the mini overlay;
 * - H-S8: in low-end mode the LIVE dot updates its layer once a second, not every frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class ShellRedrawTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val tuned = TunedState(identity = GuideFixtures.identity(1), rowId = 1, phase = MiniPhase.PLAYING)
    private val footer = listOf(FooterHint("↑↓←→", "Browse"))

    private fun panel(i: Int) = PanelState(101 + i, "Channel $i", null, true, 0, "Show $i", "19:30 – 20:00 · 30 min", null, listOf(PanelButton.WATCH_NOW, PanelButton.MORE), false)

    @Test
    fun aGridMoveRecomposesThePanelNotTheMiniPlayer() {
        val machine = FakeGuideStateMachine(GuideUiState(tuned = tuned, footer = footer, panel = panel(0)))
        val host = FakeShellHost(machine)
        rule.setContent { BoxTheme { AppRoot(host, grid = { m -> Spacer(m.graphicsLayer()) }) } }
        rule.waitForIdle()
        val mini0 = GuideDebugCounters.count(COMPOSITIONS_MINI)
        val panel0 = GuideDebugCounters.count(COMPOSITIONS_PANEL)
        val moves = 20
        for (i in 1..moves) {
            // What the machine does on a move: a new UiState and a new panel; tuned, footer and chips keep their instances.
            machine.mutableUi.value = machine.mutableUi.value.copy(panel = panel(i))
            rule.waitForIdle()
        }
        assertEquals("MiniPlayerOverlay compositions over $moves moves", 0, GuideDebugCounters.count(COMPOSITIONS_MINI) - mini0)
        val panelComps = GuideDebugCounters.count(COMPOSITIONS_PANEL) - panel0
        assertTrue("FocusedPanel compositions ($panelComps) <= panel changes ($moves)", panelComps <= moves)
    }

    @Test
    fun lowEndLiveDotStepsOncePerSecond() {
        val machine = FakeGuideStateMachine(GuideUiState(tuned = tuned))
        val host = FakeShellHost(machine).also { it.lowEndFlow.value = true }
        rule.mainClock.autoAdvance = false
        rule.setContent { BoxTheme { AppRoot(host, grid = { m -> Spacer(m.graphicsLayer()) }) } }
        rule.mainClock.advanceTimeBy(500)
        val d0 = GuideDebugCounters.count(LIVE_DOT_DRAWS)
        rule.mainClock.advanceTimeBy(3_000)
        val draws = GuideDebugCounters.count(LIVE_DOT_DRAWS) - d0
        assertTrue("LIVE dot layer updates in 3 s of low-end MINI: $draws (a per-frame animation gives ~180)", draws in 1..6)
    }
}
