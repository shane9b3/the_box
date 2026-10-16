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

    // ---------------------------------------------------------------- M4 code review, U-S5

    /** U-S5 (L-4): ten archive statuses in GUIDE recompose the mini player only (the static local recomposed the root). */
    @Test
    fun archiveStatusesInGuideRecomposeTheMiniPlayerOnly() {
        val ch = GuideFixtures.channel(1, archiveDays = 7)
        val t = TunedState(identity = ch.identity, rowId = ch.rowId, identityHash = ch.identityHash, phase = MiniPhase.PLAYING, isArchive = true)
        val machine = FakeGuideStateMachine(GuideUiState(tuned = t, tunedChannel = ch, footer = footer, panel = panel(0)))
        val host = FakeShellHost(machine)
        rule.setContent { BoxTheme { AppRoot(host, grid = { m -> Spacer(m.graphicsLayer()) }) } }
        rule.waitForIdle()
        val mini0 = GuideDebugCounters.count(COMPOSITIONS_MINI)
        val panel0 = GuideDebugCounters.count(COMPOSITIONS_PANEL)
        for (i in 1..10) {
            val st = app.thebox.core.catchup.archive.ArchiveStatus(
                if (i % 2 == 0) app.thebox.core.catchup.archive.ArchivePhase.PLAYING else app.thebox.core.catchup.archive.ArchivePhase.PAUSED,
                channel = ch, identityHash = ch.identityHash, wallMs = 1_000L * i, atMs = 1_000L * i, liveEdgeMs = 60_000L + i, timeShift = true,
            )
            machine.mutableUi.value = machine.mutableUi.value.copy(archive = st)
            rule.waitForIdle()
        }
        val minis = GuideDebugCounters.count(COMPOSITIONS_MINI) - mini0
        assertTrue("the mini player follows the status ($minis)", minis >= 1)
        assertEquals("the focused panel never recomposes", 0, GuideDebugCounters.count(COMPOSITIONS_PANEL) - panel0)
    }

    /** U-S5: cue lists (CEA-608 paint-on emits per character) re-run SubtitleLayer's update, never FullScreenHost. */
    @Test
    fun cueListsDoNotRecomposeTheFullScreen() {
        val ch = GuideFixtures.channel(1)
        val t = TunedState(identity = ch.identity, rowId = ch.rowId, identityHash = ch.identityHash, phase = MiniPhase.PLAYING)
        val machine = FakeGuideStateMachine(GuideUiState(mode = app.thebox.core.guide.ScreenMode.FULL, tuned = t, tunedChannel = ch))
        val player = app.thebox.ui.shelltest.FakePlayerHost()
        val host = FakeShellHost(machine, player = player)
        rule.setContent { BoxTheme { AppRoot(host, grid = { m -> Spacer(m) }) } }
        rule.waitForIdle()
        val full0 = GuideDebugCounters.count(app.thebox.ui.full.COMPOSITIONS_FULL)
        val root0 = GuideDebugCounters.count(COMPOSITIONS_ROOT)
        for (i in 1..10) {
            player.cuesFlow.value = listOf(androidx.media3.common.text.Cue.Builder().setText("caption ".repeat(i)).build())
            rule.waitForIdle()
        }
        assertEquals("FullScreenHost compositions over 10 cue lists", 0, GuideDebugCounters.count(app.thebox.ui.full.COMPOSITIONS_FULL) - full0)
        assertEquals("root compositions over 10 cue lists", 0, GuideDebugCounters.count(COMPOSITIONS_ROOT) - root0)
    }

    /** U-S5: one scrolling channel-list step recomposes at most the rows whose focus changed, plus the row that enters. */
    @Test
    fun oneListStepRecomposesAtMostTwoRows() {
        val channels = GuideFixtures.channels(30)
        val rows = object : app.thebox.core.guide.GuideRows {
            override val size = channels.size
            override val version = 1L
            override fun channel(index: Int) = channels.getOrNull(index)
            override fun indexOf(rowId: Long) = channels.indexOfFirst { it.rowId == rowId }
        }
        val ch = channels[0]
        val t = TunedState(identity = ch.identity, rowId = ch.rowId, identityHash = ch.identityHash, phase = MiniPhase.PLAYING)
        val machine = FakeGuideStateMachine(GuideUiState(mode = app.thebox.core.guide.ScreenMode.FULL, tuned = t, tunedChannel = ch))
        val player = app.thebox.ui.shelltest.FakePlayerHost(listRows = rows)
        val host = FakeShellHost(machine, player = player)
        val list = app.thebox.ui.full.PlayerOverlayStates.channels().copy(size = channels.size, focused = 9, top = 0, tunedRowId = ch.rowId)
        player.machine.mutableUi.value = app.thebox.core.full.PlayerUiState(layer = app.thebox.core.full.PlayerLayer.CHANNELS, channels = list)
        rule.setContent { BoxTheme { AppRoot(host, grid = { m -> Spacer(m) }) } }
        rule.waitForIdle()
        val rows0 = GuideDebugCounters.count(app.thebox.ui.full.COMPOSITIONS_LIST_ROW)
        // A step that scrolls the window by one row: nine rows keep their channel, one leaves and one enters.
        player.machine.mutableUi.value = player.machine.mutableUi.value.copy(channels = list.copy(focused = 10, top = 1))
        rule.waitForIdle()
        val n = GuideDebugCounters.count(app.thebox.ui.full.COMPOSITIONS_LIST_ROW) - rows0
        assertTrue("rows (re)composed by one scrolling step: $n (the two whose focus changed and the new one)", n <= 3)
    }
}
