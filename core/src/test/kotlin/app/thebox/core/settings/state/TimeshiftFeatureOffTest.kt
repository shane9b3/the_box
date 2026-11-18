package app.thebox.core.settings.state

import app.thebox.core.settings.PlaybackPrefs
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.TimeshiftFeature
import app.thebox.core.settings.TimeshiftLength
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.timeshiftInUse
import app.thebox.core.settings.tree.PlaybackRows
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Owner ruling 2026-11-18 (docs/decisions/2026-11-18-pause-rewind-off.md): pause and rewind is switched off in the app.
 * The suites run with it available (system property `thebox.timeshift.tests`); these tests switch it off as the app has it.
 */
class TimeshiftFeatureOffTest {
    private var before = false

    @BeforeTest fun off() { before = TimeshiftFeature.available; TimeshiftFeature.available = false }
    @AfterTest fun restore() { TimeshiftFeature.available = before }

    @Test
    fun theAppShipsWithItOff() {
        assertFalse(TimeshiftFeature.SHIPPED)
    }

    @Test
    fun nothingIsSavedWhateverTheStoredSettingSays() {
        for (stored in TimeshiftLength.entries) assertEquals(TimeshiftLength.OFF, PlaybackPrefs(timeshift = stored).timeshiftInUse, "$stored")
    }

    @Test
    fun theSettingsRowIsGreyedOutAsComingSoon() {
        val store = FakeSettingsStore()
        store.update(PlaybackDomain) { it.copy(timeshift = TimeshiftLength.AUTO) } // an older install's choice
        val pages = WpaPages(store, SettingsTexts.KEYS, FakePageEnv())
        val row = pages.build(SettingsRoute.Section(SectionId.PLAYBACK), advanced = true).rows.first { it.id == PlaybackRows.PAUSE_REWIND }
        assertFalse(row.enabled)
        assertEquals("COMING_SOON", row.value)
        assertEquals("PB_PAUSE_REWIND_SOON_DESC", row.description)
        assertEquals("PB_PAUSE_REWIND_SOON_DESC", row.disabledReason)
    }
}
