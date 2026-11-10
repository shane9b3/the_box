package app.thebox.core.guide.state

import app.thebox.core.full.EnterReason
import app.thebox.core.full.fakes.FakePlayerStateMachine
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.ScreenMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** M4 commit 0 (docs/design/m4-player-ux.md §1.7 items 7 and 10, review S9): attach order and the StartFull path. */
class GuideFullAttachTest {
    @Test
    fun attachInGuideDoesNotEnterFull() {
        val w = GuideWorld()
        val player = FakePlayerStateMachine()
        w.machine.full = player
        assertSame(player, w.machine.full)
        assertEquals(ScreenMode.GUIDE, w.ui.mode)
        assertTrue(player.entered.isEmpty())
    }

    @Test
    fun startFullGoesFullOnTheFirstActiveTunedAndALateAttachEntersIt() {
        val w = GuideWorld(startTuned = false)
        w.send(GuideEvent.StartFull)
        assertEquals(ScreenMode.GUIDE, w.ui.mode)
        w.tuneTo(w.channels[2])
        w.flush()
        assertEquals(ScreenMode.FULL, w.ui.mode)
        val player = FakePlayerStateMachine()
        w.machine.full = player
        assertEquals(listOf(EnterReason.LAUNCH), player.entered)
    }

    /** Owner ruling 2026-10-17: the shell is blank until the startup decision, so FULL_LAST never flashes the guide. */
    @Test
    fun bootBlankEndsOnTheStartupDecision() {
        val w = GuideWorld(startTuned = false)
        assertTrue(w.ui.booting, "blank from creation")
        w.send(GuideEvent.StartGuide)
        assertTrue(!w.ui.booting, "StartGuide shows the guide")

        val f = GuideWorld(startTuned = false)
        f.send(GuideEvent.StartFull)
        assertTrue(f.ui.booting, "StartFull keeps the blank until the tune lands")
        f.tuneTo(f.channels[2])
        f.flush()
        assertEquals(ScreenMode.FULL, f.ui.mode)
        assertTrue(!f.ui.booting)

        val k = GuideWorld(startTuned = false)
        k.press(app.thebox.core.input.BoxAction.BACK)
        assertTrue(!k.ui.booting, "any key ends the blank")
    }

    /**
     * Owner report 2026-11-10 (docs/decisions/2026-11-10-guide-start-back-audio.md): a warm launch (the process outlived
     * Exit, so the controller is Stopped BACKGROUND on the last channel) forwards that leftover STOPPED Tuned first. It
     * must not end the blank, or the guide shows until the startup tune connects and goes FULL.
     */
    @Test
    fun aLeftoverStoppedTunedKeepsTheBootBlankUntilStartFullLands() {
        for (stale in listOf(app.thebox.core.guide.MiniPhase.STOPPED, app.thebox.core.guide.MiniPhase.ERROR)) {
            val w = GuideWorld(startTuned = false)
            w.tuneTo(w.channels[2], stale); w.flush() // the previous session's state, before the startup decision
            assertTrue(w.ui.booting, "a leftover $stale is not the startup decision")
            w.send(GuideEvent.StartFull)
            if (stale == app.thebox.core.guide.MiniPhase.STOPPED) assertTrue(w.ui.booting, "StartFull still waits for the tune")
            w.tuneTo(w.channels[2], app.thebox.core.guide.MiniPhase.CONNECTING); w.flush()
            assertEquals(ScreenMode.FULL, w.ui.mode)
            assertTrue(!w.ui.booting)
        }
        // Order the other way round: StartFull first, then the leftover STOPPED.
        val s = GuideWorld(startTuned = false)
        s.send(GuideEvent.StartFull)
        s.tuneTo(s.channels[2], app.thebox.core.guide.MiniPhase.STOPPED); s.flush()
        assertTrue(s.ui.booting, "a leftover STOPPED after StartFull keeps the blank")
        // GUIDE_WITH_LAST: StartGuide ends it at once, whatever arrived before.
        val g = GuideWorld(startTuned = false)
        g.tuneTo(g.channels[2], app.thebox.core.guide.MiniPhase.STOPPED); g.flush()
        g.send(GuideEvent.StartGuide)
        assertTrue(!g.ui.booting)
        assertEquals(ScreenMode.GUIDE, g.ui.mode)
        // A live session (recreated activity over a playing controller) still ends it on its own.
        val p = GuideWorld(startTuned = false)
        p.tuneTo(p.channels[2], app.thebox.core.guide.MiniPhase.PLAYING); p.flush()
        assertTrue(!p.ui.booting)
    }

    /** WP-A gave the commit-0 no-op events their bodies: Archive and Player keep the mode, OpenSettings opens SETTINGS. */
    @Test
    fun newEventsHaveTheirM4Bodies() {
        val w = GuideWorld()
        val before = w.ui
        w.send(GuideEvent.Archive(app.thebox.core.catchup.archive.ArchiveStatus()))
        w.send(GuideEvent.Player(app.thebox.core.full.PlayerSettings()))
        assertEquals(before.mode, w.ui.mode)
        w.send(GuideEvent.OpenSettings)
        assertEquals(ScreenMode.SETTINGS, w.ui.mode)
        w.press(app.thebox.core.input.BoxAction.BACK)
        assertEquals(ScreenMode.GUIDE, w.ui.mode, "opened from the guide: BACK returns to the guide")
    }
}
