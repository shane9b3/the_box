package app.thebox.core.settings.state

import app.thebox.core.guide.ExitConfirmation
import app.thebox.core.guide.PerformanceMode
import app.thebox.core.settings.ClockMode
import app.thebox.core.settings.GuidePrefs
import app.thebox.core.settings.SettingsRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** S12: the automatic mini still / ambient mapping and the changed-fields-only write-back (S1), in one place. */
class GuidePrefsMappingTest {
    @Test
    fun automaticValuesFollowTheDeviceAndPerformance() {
        val auto = GuidePrefs()
        assertFalse(GuidePrefsMapping.toSettings(auto, system24h = true, deviceLowEnd = false).miniStill)
        assertTrue(GuidePrefsMapping.toSettings(auto, system24h = true, deviceLowEnd = false).ambient)
        assertTrue(GuidePrefsMapping.toSettings(auto, system24h = true, deviceLowEnd = true).miniStill)
        assertFalse(GuidePrefsMapping.toSettings(auto, system24h = true, deviceLowEnd = true).ambient)
        assertTrue(GuidePrefsMapping.toSettings(auto.copy(performance = PerformanceMode.LOW), true, deviceLowEnd = false).miniStill)
        assertFalse(GuidePrefsMapping.toSettings(auto.copy(performance = PerformanceMode.HIGH), true, deviceLowEnd = true).miniStill)
        assertTrue(GuidePrefsMapping.toSettings(auto.copy(miniStill = false, ambient = true), true, deviceLowEnd = true).ambient)
    }

    @Test
    fun clockSystemFollowsTheSystem() {
        assertTrue(GuidePrefsMapping.toSettings(GuidePrefs(), system24h = true, deviceLowEnd = false).clock24)
        assertFalse(GuidePrefsMapping.toSettings(GuidePrefs(), system24h = false, deviceLowEnd = false).clock24)
        assertTrue(GuidePrefsMapping.toSettings(GuidePrefs(clock = ClockMode.H24), system24h = false, deviceLowEnd = false).clock24)
    }

    @Test
    fun writeBackAppliesOnlyChangedFields() {
        val stored = GuidePrefs(showLogos = false, exitConfirmation = ExitConfirmation.ASK)
        val before = GuidePrefsMapping.toSettings(GuidePrefs(), true, false) // a caller that saw defaults (before the read, S1)
        val after = before.copy(debugUnlocked = true, reminderLeadSec = 120)
        val out = GuidePrefsMapping.fromSettings(stored, before, after)
        assertEquals(stored.copy(reminderLeadSec = 120), out, "stored choices the caller did not change survive")
        assertEquals(ClockMode.SYSTEM, out.clock)
        assertEquals(null, out.miniStill)
    }

    /** (owner request 2026-11-09) "Scroll long descriptions": On by default, mapped both ways. */
    @Test
    fun scrollDescriptionsMapsBothWays() {
        assertTrue(GuidePrefs().scrollDescriptions)
        assertTrue(GuidePrefsMapping.toSettings(GuidePrefs(), true, false).scrollDescriptions)
        assertFalse(GuidePrefsMapping.toSettings(GuidePrefs(scrollDescriptions = false), true, false).scrollDescriptions)
        val before = GuidePrefsMapping.toSettings(GuidePrefs(), true, false)
        assertFalse(GuidePrefsMapping.fromSettings(GuidePrefs(), before, before.copy(scrollDescriptions = false)).scrollDescriptions)
        val stored = GuidePrefs(scrollDescriptions = false)
        assertFalse(GuidePrefsMapping.fromSettings(stored, before, before.copy(reminderLeadSec = 120)).scrollDescriptions, "untouched: the stored Off survives")
    }

    @Test
    fun parentRoutes() {
        assertEquals(SettingsRoute.Playlist(4), SettingsMachineImpl.parentOf(SettingsRoute.PlaylistSource(4)))
        assertEquals(SettingsRoute.FirstRun, SettingsMachineImpl.parentOf(SettingsRoute.AddSource(firstRun = true)))
        assertEquals(SettingsRoute.FixGuide(null), SettingsMachineImpl.parentOf(SettingsRoute.FixGuide(SettingsWorld.ID)))
        assertEquals(SettingsRoute.ButtonMapping, SettingsMachineImpl.parentOf(SettingsRoute.ActionBindings(app.thebox.core.input.BoxAction.AUDIO)))
    }
}
