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
        assertEquals("The server's name does not exist. Check the address.", pages.verdictText(Verdict(VerdictKey.SERVER_UNREACHABLE, emptyList())))
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
