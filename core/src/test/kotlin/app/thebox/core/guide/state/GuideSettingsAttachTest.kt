package app.thebox.core.guide.state

import app.thebox.core.full.fakes.FakePlayerStateMachine
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.ScreenMode
import app.thebox.core.input.BoxAction
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.fakes.RecordingSettingsMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M5 commit 0 (docs/design/m5-setup-settings.md §1.9 item 5, §2.12 item 2, S9, S11): the SETTINGS delegation seam. */
class GuideSettingsAttachTest {
    private fun opened(route: SettingsRoute, fromFull: Boolean, detached: Boolean) = Triple(route, fromFull, detached)

    @Test
    fun openSetupOpensSettingsOnItsRouteAndALateAttachOpensTheMachine() {
        val w = GuideWorld()
        w.send(GuideEvent.OpenSetup(SettingsRoute.FirstRun))
        assertEquals(ScreenMode.SETTINGS, w.ui.mode)
        assertEquals<Any?>(SettingsRoute.FirstRun, w.calls.last("openSettingsRoute")?.args?.single())
        val s = RecordingSettingsMachine()
        w.machine.settings = s
        assertEquals(listOf(opened(SettingsRoute.FirstRun, false, false)), s.opened, "S11: attached while in SETTINGS, it opens at once")
        w.machine.settings = s
        assertEquals(1, s.opened.size, "re-attaching the same machine does nothing")
    }

    @Test
    fun withoutAMachineFirstRunWaitsAndBackExits() {
        val w = GuideWorld()
        w.send(GuideEvent.OpenSetup(SettingsRoute.FirstRun))
        w.press(BoxAction.DOWN)
        w.press(BoxAction.SELECT)
        assertEquals(ScreenMode.SETTINGS, w.ui.mode)
        assertFalse(w.exited)
        w.press(BoxAction.BACK)
        assertTrue(w.exited, "S11: BACK on FirstRun exits even while the settings machine is missing")
    }

    @Test
    fun theMachineActsFirstAndLeavingCallsOnLeaveOnce() {
        val w = GuideWorld()
        val s = RecordingSettingsMachine()
        w.machine.settings = s
        w.send(GuideEvent.OpenSettings)
        assertEquals(opened(SettingsRoute.Root, false, false), s.opened.single())
        assertEquals(listOf("openSettings"), w.calls.names().filter { it.startsWith("openSettings") }, "Root keeps M4's effect")
        w.press(BoxAction.DOWN)
        assertEquals(ScreenMode.SETTINGS, w.ui.mode)
        assertEquals(BoxAction.DOWN, s.acts.single().action)
        w.press(BoxAction.BACK) // declined by the machine at its root: the guide leaves SETTINGS
        assertEquals(ScreenMode.GUIDE, w.ui.mode)
        assertEquals(1, s.leaves, "S9: onLeave on the exit from SETTINGS")
        w.press(BoxAction.DOWN)
        assertEquals(2, s.acts.size, "outside SETTINGS the settings machine sees nothing")
    }

    @Test
    fun leavingToFullFromTheQuickBarAlsoCallsOnLeave() {
        val w = GuideWorld(startTuned = false)
        w.machine.full = FakePlayerStateMachine()
        val s = RecordingSettingsMachine()
        w.machine.settings = s
        w.send(GuideEvent.StartFull)
        w.tuneTo(w.channels[2])
        w.flush()
        assertEquals(ScreenMode.FULL, w.ui.mode)
        w.send(GuideEvent.OpenSettings)
        assertEquals(opened(SettingsRoute.Root, true, false), s.opened.single(), "fromFull")
        w.press(BoxAction.BACK)
        assertEquals(ScreenMode.FULL, w.ui.mode, "owner ruling 2026-10-15: back to FULL")
        assertEquals(1, s.leaves)
    }

    @Test
    fun textInputActiveFollowsTheMachineOnlyInSettings() {
        val w = GuideWorld()
        val s = RecordingSettingsMachine().apply { textInputActive = true }
        w.machine.settings = s
        assertFalse(w.machine.textInputActive, "GUIDE: never")
        w.send(GuideEvent.OpenSettings)
        assertTrue(w.machine.textInputActive)
        s.textInputActive = false
        assertFalse(w.machine.textInputActive)
    }

    @Test
    fun newEventsAreNoOps() {
        val w = GuideWorld()
        val before = w.ui
        w.send(GuideEvent.KeyHints(emptyMap()))
        w.send(GuideEvent.Update(null))
        w.send(GuideEvent.PinChecked(1, app.thebox.core.parental.PinCheck.Ok))
        w.send(GuideEvent.Parental(app.thebox.core.parental.ParentalState(false, false, app.thebox.core.parental.ParentalPolicy())))
        assertEquals(before, w.ui)
    }
}
