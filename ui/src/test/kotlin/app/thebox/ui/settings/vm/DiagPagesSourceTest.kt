package app.thebox.ui.settings.vm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.diag.DiagSources
import app.thebox.core.diag.ExportDone
import app.thebox.core.diag.ExportTarget
import app.thebox.core.diag.NetworkTests
import app.thebox.core.diag.PlaylistBrief
import app.thebox.core.diag.SyncRow
import app.thebox.core.diag.SyncRowState
import app.thebox.core.diag.fakes.FakeDiagnostics
import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.ParentalState
import app.thebox.core.parental.PinGate
import app.thebox.core.parental.PinPurpose
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsUiState
import app.thebox.core.settings.fakes.FakeParentalSession
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.PageContext
import app.thebox.core.update.UpdateCheckScheduler
import app.thebox.core.update.fakes.FakeUpdater
import app.thebox.ui.diag.DiagPages
import app.thebox.ui.settings.FakeSettingsUiMachine
import app.thebox.ui.shelltest.FakePlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M5 code review 2026-10-16 (U-S6 adopted from the shell reviewer's `ReviewPhoneAndDiagTest`; U-L2): WP-E's page source. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DiagPagesSourceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class Rig(context: Context, lockSettings: Boolean, shownRoute: SettingsRoute) {
        val test = TestScope(UnconfinedTestDispatcher())
        private val d = UnconfinedTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(Job() + d)
        val parental = FakeParentalSession(ParentalState(pinSet = true, unlocked = false, policy = ParentalPolicy(lockSettings = lockSettings)))
        val sources = object : DiagSources {
            override suspend fun playlists() = listOf(PlaylistBrief(3, "Main", true, "panel.example.com:8080"))
            override fun syncRows() = MutableStateFlow(listOf(SyncRow("playlist:3", 3, "Main", SyncRowState.FAILED, errorCode = "auth.bad_credentials", failureLogId = 9)))
            override fun updateGuideNow() = Unit
            override fun refreshPlaylist(playlistId: Long) = Unit
            override suspend fun acknowledge(failureLogId: Long) = Unit
            override suspend fun export(target: ExportTarget, nowMs: Long): Outcome<ExportDone> = Outcome.Err(SourceError.Internal("n/a"))
            override fun resetApp() = false
        }
        val pages = DiagPages(context, FakeUpdater(), FakeDiagnostics(), NetworkTests { flowOf() }, sources, FakePlaybackController(), FakeSettingsStore(), UpdateCheckScheduler { }, parental)
        val shell = FakeSettingsUiMachine(SettingsUiState(route = shownRoute))
        val asked = ArrayList<PinPurpose>()
        val source = DiagPagesSource(pages, { shell }, scope, d, d)

        init { shell.pinGate = PinGate { p -> asked += p; true } }

        fun edit() {
            runBlocking { source.onIntent(SettingsRoute.SyncStatus, RowIntent.Menu(RowId("sync.playlist:3"), DiagPages.MENU_EDIT), PageContext(false, 0)) }
            test.runCurrent()
        }
    }

    /** U-S6: S2 "PIN-free entries … their links into gated routes ask": Sync status → Edit (detached, PIN-free) opens the login form. */
    @Test
    fun syncStatusEditAsksForThePinWithLockSettingsOn() {
        val r = Rig(context, lockSettings = true, shownRoute = SettingsRoute.SyncStatus)
        r.edit()
        assertEquals("Edit went through the PIN gate", listOf<SettingsRoute>(SettingsRoute.PlaylistSource(3)), r.shell.gatedPushed)
        assertTrue("PlaylistSource pushed (${r.shell.pushed}) without asking for the PIN (asked=${r.asked})", r.shell.pushed.isEmpty() && r.asked.isNotEmpty())
    }

    /** U-S6: with "Lock settings" off the login form opens at once (as the row does). */
    @Test
    fun syncStatusEditIsUngatedWithLockSettingsOff() {
        val r = Rig(context, lockSettings = false, shownRoute = SettingsRoute.SyncStatus)
        r.edit()
        assertEquals(listOf<SettingsRoute>(SettingsRoute.PlaylistSource(3)), r.shell.pushed)
        assertTrue(r.asked.isEmpty())
    }

    /** U-L2: an Open that arrives after the user left the page is dropped. */
    @Test
    fun anOpenFromAPageNoLongerShownIsDropped() {
        val r = Rig(context, lockSettings = false, shownRoute = SettingsRoute.Diagnostics)
        r.edit()
        assertTrue("pushed onto the wrong page: ${r.shell.pushed}", r.shell.pushed.isEmpty() && r.shell.gatedPushed.isEmpty())
    }
}
