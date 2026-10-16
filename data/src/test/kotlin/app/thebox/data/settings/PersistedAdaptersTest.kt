package app.thebox.data.settings

import app.thebox.core.guide.ExitConfirmation
import app.thebox.core.match.MatchLevel
import app.thebox.core.settings.ClockMode
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.DataDomain
import app.thebox.core.settings.state.GuideDomain
import app.thebox.core.settings.state.SetupDomain
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.newPlaylist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The persisted adapters (docs/design/m5-setup-settings.md §1.4, S12): `PersistedDataSettingsTest` (Auto past days),
 * `PersistedGuideSettingsTest` (automatic mini still / ambient, debugUnlocked never stored), and the first-run decision.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersistedAdaptersTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    @Test fun autoPastDaysIsTheMostCatchupDaysClampedToOneToSeven() {
        assertEquals(1, PersistedDataSettings.autoPastDays(null))
        assertEquals(1, PersistedDataSettings.autoPastDays(0))
        assertEquals(3, PersistedDataSettings.autoPastDays(3))
        assertEquals(7, PersistedDataSettings.autoPastDays(14))
        val store = FakeSettingsStore()
        val days = MutableStateFlow<Int?>(null)
        val s = PersistedDataSettings(store, scope, days)
        assertEquals(1, s.current.epgPastDays)
        days.value = 5
        assertEquals(5, s.current.epgPastDays)
        store.update(DataDomain) { it.copy(epgPastDays = 2) }
        assertEquals(2, s.current.epgPastDays)
    }

    @Test fun dataUpdateWritesOnlyChangedFields() {
        val store = FakeSettingsStore()
        store.update(DataDomain) { it.copy(epgFutureDays = 14, matchLevel = MatchLevel.EXACT_ONLY) }
        val s = PersistedDataSettings(store, scope, MutableStateFlow(4))
        s.update { it.copy(globalShiftMin = 60, maxDescriptionChars = 99) }
        val p = store.flow(DataDomain).value
        assertEquals(60, p.globalShiftMin)
        assertEquals(14, p.epgFutureDays)
        assertEquals("Auto stays Auto", 0, p.epgPastDays)
        assertEquals("non-user fields stay in memory", 99, s.current.maxDescriptionChars)
        assertEquals(MatchLevel.EXACT_ONLY, s.current.match.level)
    }

    @Test fun dailyAtASetTimeRunsAsATwentyFourHourPeriod() {
        val store = FakeSettingsStore()
        val s = PersistedDataSettings(store, scope, MutableStateFlow(null))
        store.update(DataDomain) { it.copy(epgDailyAtHour = 4) }
        assertEquals(24, s.current.epgRefreshIntervalH)
    }

    @Test fun guideAutomaticValuesAndDebugUnlockedStayOutOfTheStore() {
        val store = FakeSettingsStore()
        var lowEnd = true
        val g = PersistedGuideSettings(store, scope, { false }, { lowEnd })
        assertTrue(g.settings.value.miniStill)
        assertFalse(g.settings.value.ambient)
        assertFalse(g.settings.value.clock24)
        g.update { it.copy(debugUnlocked = true, exitConfirmation = ExitConfirmation.NONE) }
        assertTrue(g.settings.value.debugUnlocked)
        val p = store.flow(GuideDomain).value
        assertEquals(ExitConfirmation.NONE, p.exitConfirmation)
        assertNull("untouched automatic values stay automatic", p.miniStill)
        assertEquals(ClockMode.SYSTEM, p.clock)
        store.update(GuideDomain) { it.copy(clock = ClockMode.H24) }
        assertTrue("a store change reaches the guide", g.settings.value.clock24)
        assertTrue("debugUnlocked survives a store change", g.settings.value.debugUnlocked)
    }

    @Test fun firstRunOnlyWithoutPlaylistsAndBeforeSetupCompleted() = runBlocking {
        val db = TestDb.create()
        try {
            val store = FakeSettingsStore()
            val decider = FirstRunDeciderImpl(store, db.playlistDao())
            assertEquals(SettingsRoute.FirstRun, decider.route())
            store.update(SetupDomain) { it.copy(completed = true) }
            assertNull(decider.route())
            store.update(SetupDomain) { it.copy(completed = false) }
            db.newPlaylist()
            assertNull(decider.route())
        } finally {
            db.close()
        }
    }
}
