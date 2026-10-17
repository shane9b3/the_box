package app.thebox.core.guide.state

import app.thebox.core.full.fakes.FakePlayerStateMachine
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.ScreenMode
import app.thebox.core.input.BoxAction
import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.ParentalState
import app.thebox.core.parental.PinPurpose
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.state.QueueEffects
import app.thebox.core.settings.state.SettingsMachineImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §3.15 composed tests (guide machine + settings machine) added by the M5 code review (2026-10-16). */
class ComposedSettingsGuideTest {
    private val lockSettings = ParentalState(pinSet = true, unlocked = false, policy = ParentalPolicy(lockSettings = true))

    /**
     * C-S1 / U-S8 (adopted from the core reviewer): §3.2.5 / S2: an error-card route opens detached and needs no PIN,
     * even with "Lock settings" on; BACK returns to FULL.
     */
    @Test
    fun errorCardRouteIsDetachedAndPinFree() {
        val w = GuideWorld(startTuned = false)
        w.machine.full = FakePlayerStateMachine()
        val s = SettingsMachineImpl(QueueEffects(), { w.nowMs })
        w.machine.settings = s
        s.attachGuide { w.send(it) }
        w.send(GuideEvent.Parental(lockSettings))
        w.send(GuideEvent.StartFull)
        w.tuneTo(w.channels[2])
        w.flush()
        assertEquals(ScreenMode.FULL, w.ui.mode)
        // PlayerEffects.openSettings(route) → SettingsNavigatorImpl → SettingsShellLink.openFromFull(route, detached = true)
        s.openFromFull(SettingsRoute.PlaylistSource(1), detached = true)
        assertNull(w.ui.pin, "no PIN for the error card's own route (§3.2.5)")
        assertEquals(ScreenMode.SETTINGS, w.ui.mode)
        assertEquals(SettingsRoute.PlaylistSource(1), s.ui.value.route)
        assertTrue(s.ui.value.sections.isEmpty(), "detached: no sections pane")
        w.press(BoxAction.BACK)
        assertEquals(ScreenMode.FULL, w.ui.mode, "BACK from the detached route returns to FULL")
    }

    /**
     * C-S1 / U-S8 (adopted from the shell reviewer's `ReviewErrorCardRouteGateTest`): the event `openFromFull` sends for
     * an error-card route does not ask for the PIN; the quick bar's `OpenSettings` still does.
     */
    @Test
    fun theEventOpenFromFullSendsDoesNotAskForThePin() {
        val w = GuideWorld()
        w.send(GuideEvent.Parental(lockSettings))
        w.send(GuideEvent.OpenSettingsDetached(SettingsRoute.PlaylistSource(1)))
        assertNull(w.ui.pin, "an error-card route asked for the PIN: ${w.ui.pin?.purpose}")
        assertEquals(ScreenMode.SETTINGS, w.ui.mode)

        val q = GuideWorld()
        q.send(GuideEvent.Parental(lockSettings))
        q.send(GuideEvent.OpenSettings)
        assertEquals(PinPurpose.EnterSettings(SettingsRoute.Root), q.ui.pin?.purpose, "the quick bar keeps its PIN (§3.2.5)")
    }
}
