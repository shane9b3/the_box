package app.thebox.settings

import android.view.KeyEvent
import app.thebox.core.diag.NetworkTestEvent
import app.thebox.core.diag.NetworkTests
import app.thebox.core.diag.TestStep
import app.thebox.core.diag.VerdictKey
import app.thebox.core.guide.ScreenMode
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.tree.AboutRows
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.TuneTarget
import app.thebox.ui.diag.DiagPages
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * I-M5-18 "Diagnostics and logs" (docs/design/m5-setup-settings.md §6.3, §3.11): the connection test against the
 * panel (DNS ✓, connect ✓, HTTP 200), the slow-provider verdict with `slow`/`stall` while playing, and an exported
 * bundle that contains neither `test` as a password value, nor `username=`, nor `/live/test/test/`.
 *
 * [connectionTestAgainstThePanel], [slowProviderVerdictWhilePlaying] and [exportedBundleIsRedacted] drive WP-E's code
 * directly (runnable on commit 0 + WP-E). [diagnosticsPagesThroughTheShell] opens About → Diagnostics → the connection
 * test in the settings UI and awaits WP-A's shell (`SettingsEffectsImpl` collecting `DiagPages.page`).
 */
class DiagnosticsTest : SettingsTestBase() {
    private val tests: NetworkTests get() = probe.networkTests

    @Test
    fun connectionTestAgainstThePanel() {
        val pid = seedXtream()
        val events = runBlocking { withTimeoutOrNull(60_000) { tests.run(pid).toList() } } ?: error("connection test did not finish in 60 s")
        val steps = events.filterIsInstance<NetworkTestEvent.Step>().map { it.result }.associateBy { it.step }
        log("steps: ${steps.values.joinToString { "${it.step} ok=${it.ok} ${it.ms} ${it.detail}" }}")
        assertTrue("DNS ✓", steps.getValue(TestStep.DNS_SYSTEM).ok)
        assertTrue("connect ✓", steps.getValue(TestStep.TCP_CONNECT).ok)
        val http = steps.getValue(TestStep.HTTP)
        assertTrue("HTTP ✓ ${http.detail}", http.ok)
        assertTrue(http.detail, http.detail.startsWith("status=200"))
        assertTrue("response time measured", steps.getValue(TestStep.API_LATENCY).ms != null)
        val done = events.last() as NetworkTestEvent.Done
        assertEquals(VerdictKey.ALL_GOOD, done.result.verdict.key)
        for (s in steps.values) assertFalse("no login in a step detail: ${s.detail}", s.detail.contains("test"))
    }

    @Test
    fun authFailureVerdict() {
        val pid = seedXtream()
        panel.authMode("401")
        val done = runBlocking { withTimeoutOrNull(60_000) { tests.run(pid).toList() } }!!.last() as NetworkTestEvent.Done
        assertEquals(VerdictKey.AUTH_PROBLEM, done.result.verdict.key)
    }

    @Test
    fun slowProviderVerdictWhilePlaying() {
        val pid = seedXtream()
        launch()
        // Measure the clip's rate unthrottled, then throttle new connections (`slow`, bytes/s) to 70 % of it and re-tune.
        val channel = runBlocking { ep.channels().browseAll(pid, unlocked = true).first() }.first()
        val identity = app.thebox.core.refresh.ChannelIdentity(channel.playlistId, channel.stableKey)
        onMain { controller.setStatsEnabled(true); controller.tune(TuneTarget.Channel(identity)) }
        runBlocking { withTimeoutOrNull(30_000) { controller.state.first { it is PlaybackState.Playing } } } ?: error("channel did not start: ${controller.state.value}")
        // The first seconds carry the panel's 1 s start burst: average the steady delivery after it.
        Thread.sleep(4_000)
        val samples = (1..16).map { Thread.sleep(500); controller.stats.value.streamBitrateBps }.filter { it > 0 }
        val rate = if (samples.isEmpty()) 1_500_000L else samples.sum() / samples.size
        log("unthrottled steady delivery ${rate / 1000} kbit/s")
        onMain { controller.stop(app.thebox.player.api.StopReason.USER) }
        panel.streamFault("slow", (rate * 0.5 / 8).toLong())
        onMain { controller.tune(TuneTarget.Channel(identity)) }
        runBlocking { withTimeoutOrNull(60_000) { controller.state.first { it is PlaybackState.Playing } } } ?: error("throttled channel did not start: ${controller.state.value}")
        val events = runBlocking { withTimeoutOrNull(90_000) { tests.run(pid).toList() } } ?: error("connection test did not finish")
        val stream = events.filterIsInstance<NetworkTestEvent.Step>().map { it.result }.first { it.step == TestStep.STREAM_THROUGHPUT }
        log("stream step: ${stream.detail}")
        assertFalse("the stream step ran (a channel of this playlist plays)", stream.detail == "skipped=1")
        val verdict = (events.last() as NetworkTestEvent.Done).result.verdict
        log("verdict: $verdict")
        assertEquals("slow-provider verdict", VerdictKey.SLOW_PROVIDER, verdict.key)
    }

    @Test
    fun exportedBundleIsRedacted() {
        val pid = seedXtream()
        launch()
        // Make sure the ring has stream URLs with credentials in them: tune a channel, then export.
        val channel = runBlocking { ep.channels().browseAll(pid, unlocked = true).first() }.first()
        onMain { controller.tune(TuneTarget.Channel(app.thebox.core.refresh.ChannelIdentity(channel.playlistId, channel.stableKey))) }
        runBlocking { withTimeoutOrNull(30_000) { controller.state.first { it is PlaybackState.Playing } } }
        app.thebox.core.common.BoxLog.w("M5Suite", "probe line http://10.0.2.2/live/test/test/1.ts?username=test&password=test")
        val out = ByteArrayOutputStream()
        val r = runBlocking { probe.diagnostics.exportLogs(out) }
        assertTrue("export ok: $r", r is app.thebox.core.common.Outcome.Ok)
        val text = out.toString(Charsets.UTF_8.name())
        log("bundle ${text.length} chars; footer: ${text.lines().last { it.isNotBlank() }}")
        assertTrue(text.contains("== Device"))
        assertTrue(text.contains("== Playlists"))
        assertFalse("username= in the bundle", Regex("""(?i)username=(?!\*)""").containsMatchIn(text))
        assertFalse("password= in the bundle", Regex("""(?i)password=(?!\*)""").containsMatchIn(text))
        assertFalse("/live/test/test/ in the bundle", text.contains("/live/test/test/"))
        assertFalse("'test' as a password value", Regex("""(?i)(pass(word)?|pwd)\s*[=:]\s*test\b""").containsMatchIn(text))
    }

    @Test
    fun aboutPageModelsWithoutTheShell() {
        // The page builders alone (WP-E): every About row of §3.3.10 is built with its registered timing and visibility.
        val pages = probe.diagPages
        val about = runBlocking { withTimeoutOrNull(10_000) { pages.page(SettingsRoute.Section(SectionId.ABOUT)).first() } }!!
        val ids = about.rows.map { it.id }
        for (spec in AboutRows.rows) {
            val r = about.rows.first { it.id == spec.id }
            assertEquals(spec.effect, r.effect)
            assertEquals(spec.visibility, r.visibility)
        }
        assertTrue(ids.first() == AboutRows.HEADER)
        val licences = pages.licencesPage()
        assertTrue("LGPL text present", licences.rows.any { r -> (r.kind as? RowKind.Info)?.lines?.any { it.contains("LESSER GENERAL PUBLIC LICENSE") } == true })
        assertTrue("FFmpeg version and build script", licences.rows.any { r -> (r.kind as? RowKind.Info)?.lines?.any { it.contains("build-ffmpeg-aar.sh") } == true })
    }

    /** Awaits WP-A: the shell must collect `DiagPages.page(route)` for About, Diagnostics and NetworkTest (§1.8a). */
    @Test
    fun diagnosticsPagesThroughTheShell() {
        val pid = seedXtream()
        openSettings("about")
        awaitPage("About page with the update row") { p -> p.rows.any { it.id == AboutRows.UPDATE } }
        activate(AboutRows.DIAGNOSTICS.key)
        awaitSettings("Diagnostics page") { it.route == SettingsRoute.Diagnostics && it.page?.rows?.isNotEmpty() == true }
        assertNotNull(row("diag.device"))
        activate("diag.test.$pid")
        awaitSettings("connection test verdict", 60_000) { s -> s.page?.rows?.any { it.id == DiagPages.NETTEST_VERDICT } == true }
        val verdict = row(DiagPages.NETTEST_VERDICT.key)!!
        log("verdict row: ${(verdict.kind as RowKind.Info).lines}")
        leaveSettings()
        assertEquals(ScreenMode.GUIDE, gui().mode)
        press(KeyEvent.KEYCODE_BACK, intervalMs = 0)
    }

    @Suppress("unused")
    private fun json(s: String) = JSONObject(s)

    @Suppress("unused")
    private val unusedRow = RowId("")
}
