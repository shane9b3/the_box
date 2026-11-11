package app.thebox.ui.diag

import androidx.test.core.app.ApplicationProvider
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.diag.DiagSources
import app.thebox.core.diag.ExportDone
import app.thebox.core.diag.ExportTarget
import app.thebox.core.diag.NetworkTestEvent
import app.thebox.core.diag.NetworkTestResult
import app.thebox.core.diag.NetworkTests
import app.thebox.core.diag.PlaylistBrief
import app.thebox.core.diag.StepResult
import app.thebox.core.diag.SyncRow
import app.thebox.core.diag.SyncRowState
import app.thebox.core.diag.TestStep
import app.thebox.core.diag.Verdict
import app.thebox.core.diag.VerdictKey
import app.thebox.core.diag.fakes.FakeDiagnostics
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.fakes.FakeParentalSession
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.tree.AboutRows
import app.thebox.core.update.UpdateCheckScheduler
import app.thebox.core.update.UpdateDomain
import app.thebox.core.update.fakes.FakeUpdater
import app.thebox.ui.shelltest.FakePlaybackController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import app.thebox.core.settings.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** WP-E's page builders (docs/design/m5-setup-settings.md §3.3.10, §3.11): rows, timings, texts and intents. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DiagPagesTest {
    private val updater = FakeUpdater()
    private val store = FakeSettingsStore()
    private var scheduled = 0
    private val sources = FakeSources()
    private var testEvents: List<NetworkTestEvent> = emptyList()

    private val pages = DiagPages(
        ApplicationProvider.getApplicationContext(), updater, FakeDiagnostics(), NetworkTests { flowOf(*testEvents.toTypedArray()) }, sources,
        FakePlaybackController(), store, UpdateCheckScheduler { scheduled++ }, FakeParentalSession(),
    )

    class FakeSources : DiagSources {
        val rows = MutableStateFlow(listOf(SyncRow("epg", null, "", SyncRowState.DONE), SyncRow("playlist:3", 3, "Main", SyncRowState.FAILED, errorCode = "http.403", failureLogId = 9)))
        val calls = ArrayList<String>()
        var exportResult: Outcome<ExportDone> = Outcome.Ok(ExportDone("TheBox-logs.txt", 10, 0, "Download/TheBox"))
        override suspend fun playlists() = listOf(PlaylistBrief(3, "Main", true, "panel.example.com:8080"))
        override fun syncRows() = rows
        override fun updateGuideNow() { calls += "guide" }
        override fun refreshPlaylist(playlistId: Long) { calls += "refresh $playlistId" }
        override suspend fun acknowledge(failureLogId: Long) { calls += "ack $failureLogId" }
        override suspend fun export(target: ExportTarget, nowMs: Long): Outcome<ExportDone> { calls += "export $target"; return exportResult }
        override fun resetApp(): Boolean { calls += "reset"; return false }
    }

    @Test
    fun aboutPageHasEveryRegisteredRowWithItsTimingAndVisibility() = runTest {
        val page = pages.page(SettingsRoute.Section(SectionId.ABOUT)).first()
        assertEquals(AboutRows.HEADER, page.rows.first().id)
        for (spec in AboutRows.rows) {
            val r = page.rows.single { it.id == spec.id }
            assertEquals(spec.id.key, spec.effect, r.effect)
            assertEquals(spec.id.key, spec.visibility, r.visibility)
        }
        val reset = page.rows.single { it.id == AboutRows.RESET }.kind as RowKind.Action
        assertTrue(reset.destructive)
        assertTrue("reset confirms first", reset.confirm != null)
        assertFalse("no PIN asked while none is set", reset.needsPin)
        assertTrue((page.rows.single { it.id == AboutRows.DIAGNOSTICS }.kind as RowKind.Link).route == SettingsRoute.Diagnostics)
        val export = page.rows.single { it.id == AboutRows.EXPORT_LOGS }.kind as RowKind.Choice
        assertFalse("the placeholder option cannot be chosen", export.options[export.selected].enabled)
    }

    @Test
    fun theUpdateRowInstallsAReleaseThatIsOnOffer() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val manifest = app.thebox.core.update.UpdateManifest(1, 5, "app.thebox", "stable", 5, "0.1.3", 25, "", "Fixes", emptyList(), listOf("A"))
        val offered = app.thebox.core.update.UpdateState.Available(manifest, app.thebox.core.update.ApkEntry("universal", "https://x/a.apk", 10, "0".repeat(64)))
        updater.state.value = offered
        assertTrue(pages.onIntent(RowIntent.Run(AboutRows.UPDATE), scope) {})
        assertTrue("the row says what OK does", pages.updateDesc(offered).contains("0.1.3"))
        updater.state.value = app.thebox.core.update.UpdateState.ReadyToInstall("0.1.3")
        pages.onIntent(RowIntent.Run(AboutRows.UPDATE), scope) {}
        updater.state.value = app.thebox.core.update.UpdateState.UpToDate(0)
        pages.onIntent(RowIntent.Run(AboutRows.UPDATE), scope) {}
        assertEquals(listOf("download", "install", "check(true)"), updater.calls)
    }

    /** Review S9 (2026-11-10): Not installed installs on OK; the busy rule (S1) disables the row while checking the file. */
    @Test
    fun theUpdateRowKnowsVerifyingAndNotInstalled() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        updater.state.value = app.thebox.core.update.UpdateState.NotInstalled("0.1.5")
        pages.onIntent(RowIntent.Run(AboutRows.UPDATE), scope) {}
        assertEquals(listOf("install"), updater.calls)
        // The check row points down to Install update (2026-11-11); OK on it still installs.
        assertEquals("Version 0.1.5 is ready. Choose Install update below.", pages.updateDesc(updater.state.value))
        assertEquals("0.1.5 not installed · OK to try again", pages.updateValue(updater.state.value))
        assertTrue(pages.page(SettingsRoute.Section(SectionId.ABOUT)).first().rows.single { it.id == AboutRows.UPDATE }.enabled)
        updater.state.value = app.thebox.core.update.UpdateState.Verifying("0.1.5")
        assertEquals("Checking the download…", pages.updateValue(updater.state.value))
        assertFalse("busy while the file is checked", pages.page(SettingsRoute.Section(SectionId.ABOUT)).first().rows.single { it.id == AboutRows.UPDATE }.enabled)
        // Review N4: a failed install is not a failed check.
        assertEquals("The update failed · OK to try again", pages.updateValue(app.thebox.core.update.UpdateState.Failed(app.thebox.core.update.UpdateFailure.INSTALL_FAILED, null)))
        assertTrue(pages.updateValue(app.thebox.core.update.UpdateState.Failed(app.thebox.core.update.UpdateFailure.NETWORK, null)).startsWith("Last check failed"))
    }

    private fun offer() = app.thebox.core.update.UpdateState.Available(
        app.thebox.core.update.UpdateManifest(1, 5, "app.thebox", "stable", 5, "1.4.0", 25, "", "Fixes", emptyList(), listOf("A")),
        app.thebox.core.update.ApkEntry("universal", "https://x/a.apk", 10, "0".repeat(64)),
    )

    private suspend fun aboutRows(state: app.thebox.core.update.UpdateState): List<app.thebox.core.settings.SettingsRow> {
        updater.state.value = state
        return pages.page(SettingsRoute.Section(SectionId.ABOUT)).first().rows
    }

    @Test
    fun installRowIsAbsentUnlessAReleaseIsOnOffer() = runTest {
                for (s in listOf(app.thebox.core.update.UpdateState.Idle, app.thebox.core.update.UpdateState.Checking, app.thebox.core.update.UpdateState.UpToDate(0), app.thebox.core.update.UpdateState.Failed(app.thebox.core.update.UpdateFailure.NETWORK, null), app.thebox.core.update.UpdateState.Failed(app.thebox.core.update.UpdateFailure.HASH_MISMATCH, null))) {
            assertTrue("absent in $s", aboutRows(s).none { it.id == AboutRows.INSTALL })
        }
        for (s in listOf(offer(), app.thebox.core.update.UpdateState.Downloading("1.4.0", 4, 10), app.thebox.core.update.UpdateState.Verifying("1.4.0"), app.thebox.core.update.UpdateState.NeedsPermission("1.4.0"), app.thebox.core.update.UpdateState.ReadyToInstall("1.4.0"), app.thebox.core.update.UpdateState.Installing, app.thebox.core.update.UpdateState.NotInstalled("1.4.0"))) {
            val rows = aboutRows(s)
            val i = rows.indexOfFirst { it.id == AboutRows.UPDATE }
            assertEquals("directly below the check row in $s", AboutRows.INSTALL, rows[i + 1].id)
            assertEquals("Install update", rows[i + 1].label)
            assertTrue(rows[i + 1].kind is RowKind.Action)
            assertEquals(Visibility.SIMPLE, rows[i + 1].visibility)
        }
    }

    @Test
    fun aFailureAsksForFocusOnTheCheckRow() = runTest {
        val about = SettingsRoute.Section(SectionId.ABOUT)
        updater.state.value = offer()
        assertEquals(null, pages.page(about).first().focus)
        updater.state.value = app.thebox.core.update.UpdateState.Failed(app.thebox.core.update.UpdateFailure.NETWORK, null)
        assertEquals(AboutRows.UPDATE, pages.page(about).first().focus)
    }

    @Test
    fun installRowTextsAndEnabledPerState() = runTest {
                fun row(s: app.thebox.core.update.UpdateState) = pages.installRow(s)!!
        assertEquals("1.4.0", row(offer()).value)
        assertEquals("Downloads version 1.4.0 and installs it.", row(offer()).description)
        assertEquals("Downloading 40 %", row(app.thebox.core.update.UpdateState.Downloading("1.4.0", 4, 10)).value)
        assertEquals("Checking the download…", row(app.thebox.core.update.UpdateState.Verifying("1.4.0")).value)
        assertEquals("Installing…", row(app.thebox.core.update.UpdateState.Installing).value)
        assertEquals("1.4.0", row(app.thebox.core.update.UpdateState.ReadyToInstall("1.4.0")).value)
        assertEquals("Version 1.4.0 is downloaded. Press OK to install.", row(app.thebox.core.update.UpdateState.ReadyToInstall("1.4.0")).description)
        assertTrue(row(app.thebox.core.update.UpdateState.NeedsPermission("1.4.0")).description!!.contains("allow"))
        assertEquals("Version 1.4.0 was not installed. Press OK to try again.", row(app.thebox.core.update.UpdateState.NotInstalled("1.4.0")).description)
        for (s in listOf(offer(), app.thebox.core.update.UpdateState.NeedsPermission("1.4.0"), app.thebox.core.update.UpdateState.ReadyToInstall("1.4.0"), app.thebox.core.update.UpdateState.NotInstalled("1.4.0"))) assertTrue("enabled in $s", row(s).enabled)
        for (s in listOf(app.thebox.core.update.UpdateState.Downloading("1.4.0", 4, 10), app.thebox.core.update.UpdateState.Verifying("1.4.0"), app.thebox.core.update.UpdateState.Installing)) assertFalse("disabled in $s", row(s).enabled)
        // The check row keeps its value and points down.
        val check = aboutRows(offer()).single { it.id == AboutRows.UPDATE }
        assertEquals("1.4.0 available", check.value)
        assertEquals("Version 1.4.0 is available. Choose Install update below.", check.description)
    }

    @Test
    fun installRowOkDownloadsOrInstalls() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
                for (s in listOf(offer(), app.thebox.core.update.UpdateState.ReadyToInstall("1.4.0"), app.thebox.core.update.UpdateState.NeedsPermission("1.4.0"), app.thebox.core.update.UpdateState.NotInstalled("1.4.0"))) {
            updater.state.value = s
            assertTrue(pages.onIntent(RowIntent.Run(AboutRows.INSTALL), scope) {})
        }
        assertEquals(listOf("download", "install", "install", "install"), updater.calls)
    }

    @Test
    fun aboutIntents() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val out = ArrayList<DiagOutcome>()
        assertTrue(pages.onIntent(RowIntent.Run(AboutRows.UPDATE), scope) { out += it })
        assertEquals(listOf("check(true)"), updater.calls)
        assertTrue(pages.onIntent(RowIntent.Toggle(AboutRows.AUTO_CHECK, false), scope) { out += it })
        assertFalse(store.flow(UpdateDomain).value.checkAutomatically)
        assertEquals(1, scheduled)
        assertTrue(pages.onIntent(RowIntent.Choose(AboutRows.EXPORT_LOGS, DiagPages.OPT_PHONE), scope) { out += it })
        assertTrue(sources.calls.contains("export PHONE"))
        assertTrue("the phone page opens for the download", out.any { it is DiagOutcome.Open && (it.route as SettingsRoute.PhoneSetup).purpose == app.thebox.core.settings.PhonePurpose.LOGS_DOWNLOAD })
        sources.exportResult = Outcome.Err(SourceError.Unsupported("storage permission"))
        pages.onIntent(RowIntent.Choose(AboutRows.EXPORT_LOGS, DiagPages.OPT_DOWNLOADS), scope) { out += it }
        assertTrue("a reason and a next step", (out.last() as DiagOutcome.Notice).text.contains("export to your phone"))
        pages.onIntent(RowIntent.Run(AboutRows.RESET), scope) { out += it }
        assertTrue("a failed reset says what to do instead", (out.last() as DiagOutcome.Notice).text.contains("Android Settings"))
        assertFalse(pages.onIntent(RowIntent.Run(app.thebox.core.settings.RowId("playback.buffer")), scope) { out += it })
    }

    @Test
    fun connectionTestPageStreamsStepsThenTheVerdictAndItsAction() = runTest {
        val steps = listOf(
            StepResult(TestStep.DNS_SYSTEM, false, 5, "error=blocked;n=1"),
            StepResult(TestStep.DNS_SECURE, true, 30, "n=1;v4=1;v6=0;agree=0"),
            StepResult(TestStep.TCP_CONNECT, true, 42, "v4=42;v6=none;port=8080"),
            StepResult(TestStep.HTTP, true, 120, "status=200;active=1"),
            StepResult(TestStep.API_LATENCY, true, 310, "median=310;calls=3"),
            StepResult(TestStep.STREAM_THROUGHPUT, true, null, "skipped=1"),
        )
        testEvents = steps.map { NetworkTestEvent.Step(it) } + NetworkTestEvent.Done(NetworkTestResult(3, steps, Verdict(VerdictKey.DNS_BLOCKED_TRY_SECURE, emptyList())))
        // The page flow never completes ("Test again" re-runs it): collect until the finished model.
        val models = pages.page(SettingsRoute.NetworkTest(3)).transformWhile { emit(it); it.rows.none { r -> r.id == DiagPages.NETTEST_AGAIN } }.toList()
        val last = models.last()
        assertEquals("Your internet provider seems to block this server's name. Turn on secure DNS.", (last.rows.single { it.id == DiagPages.NETTEST_VERDICT }.kind as RowKind.Info).lines.single())
        assertTrue("one-tap secure DNS", last.rows.any { it.id == DiagPages.NETTEST_SECURE_DNS })
        val connect = last.rows.single { it.id.key == "nettest.tcp_connect" }.kind as RowKind.Info
        assertEquals("✓ IPv4 42 ms · IPv6 no address · port 8080", connect.lines.single())
        assertTrue("versions grow", models.zipWithNext().all { (a, b) -> b.version > a.version })
        for (m in models) for (r in m.rows) for (l in (r.kind as? RowKind.Info)?.lines.orEmpty()) assertFalse(l, l.contains("="))
    }

    @Test
    fun verdictTexts() {
        assertEquals("The server's name does not exist. Check the address.", pages.verdictText(Verdict(VerdictKey.NAME_NOT_FOUND, emptyList())))
        assertEquals("The server does not answer on port 8080. It may be down, or the address changed.", pages.verdictText(Verdict(VerdictKey.SERVER_UNREACHABLE, listOf("8080"))))
        assertTrue(pages.verdictText(Verdict(VerdictKey.SLOW_PROVIDER, listOf("6.1", "8.0"))).contains("6.1 of 8.0 Mbit/s"))
    }

    @Test
    fun syncStatusPageAndItsMenu() = runTest {
        val page = pages.page(SettingsRoute.SyncStatus).first()
        assertEquals(DiagPages.SYNC_NOW, page.rows.first().id)
        val failed = page.rows.single { it.id.key == "sync.playlist:3" }
        assertTrue(failed.value!!.contains("The URL may have changed"))
        val item = failed.kind as RowKind.Item
        assertEquals(listOf(DiagPages.MENU_EDIT, DiagPages.MENU_RETRY, DiagPages.MENU_DISMISS), item.menu.map { it.id })
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val out = ArrayList<DiagOutcome>()
        pages.onIntent(RowIntent.Menu(failed.id, DiagPages.MENU_DISMISS), scope) { out += it }
        pages.onIntent(RowIntent.Menu(failed.id, DiagPages.MENU_RETRY), scope) { out += it }
        pages.onIntent(RowIntent.Run(DiagPages.SYNC_NOW), scope) { out += it }
        assertEquals(listOf("ack 9", "refresh 3", "guide"), sources.calls)
    }

    @Test
    fun licencesCarryEveryN16Text() {
        val page = pages.licencesPage()
        val text = page.rows.flatMap { (it.kind as? RowKind.Info)?.lines.orEmpty() }.joinToString("\n")
        for (needle in listOf("LESSER GENERAL PUBLIC LICENSE", "FFmpeg 6.0.2", "build-ffmpeg-aar.sh", "Apache License", "Tink", "OkHttp", "Media3", "Project Nayuki")) {
            assertTrue(needle, text.contains(needle))
        }
        assertTrue(page.rows.all { it.effect == EffectTiming.NONE })
    }
}
