package app.thebox.settings

import android.os.Debug
import android.view.KeyEvent
import app.thebox.core.guide.ScreenMode
import app.thebox.core.net.DnsChoice
import app.thebox.core.playback.AudioOutputMode
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.TextHint
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.TuneTarget
import app.thebox.ui.diag.DiagPages
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I-M5-25 secure DNS plumbing (**awaits WP-A**: `BoxDns` on the base client, Network page), I-M5-26 memory over 20
 * settings journeys (**awaits WP-A, WP-C, WP-D**: hidden list, PIN pad, capture overlay), I-M5-27 Always passthrough on
 * the emulator's PCM-only sink (**awaits WP-A**, B2). docs/design/m5-setup-settings.md §6.3.
 */
class NetworkAndPlaybackTest : SettingsTestBase() {

    /** I-M5-25 */
    @Test
    fun customDohResolvesThePanelName() {
        panel.dnsRecords(mapOf("panel.test" to "10.0.2.2"))
        val port = base.substringAfterLast(':')
        // Without the custom DoH the name does not resolve: adding the playlist fails.
        seedXtream()
        launch()
        openSettings("network")
        choose("network.dns", DnsChoice.CUSTOM.name)
        editText(rowWhere("custom DoH URL", p = { r -> (r.kind as? RowKind.Text)?.hint == TextHint.URL }), "$base/dns-query")
        leaveSettings()
        val docs = runBlocking { probe.documents() }
        assertTrue("network document says CUSTOM: $docs", docs["network"].toString().contains("CUSTOM"))
        // A playlist on http://panel.test:<port> logs in and plays only with the custom DoH on.
        val added = runBlocking { ep.playlists().addXtream("DoH", listOf("http://panel.test:$port"), "test", "test") }
        assertTrue("added: $added", added is app.thebox.core.common.Outcome.Ok)
        val id = (added as app.thebox.core.common.Outcome.Ok).value
        val rr = runBlocking { ep.refresher().refresh(id, app.thebox.data.repo.Trigger.MANUAL) }
        assertTrue("logs in through the custom DoH: ${rr.error}", rr.ok)
        assertTrue("the panel answered DoH queries", panel.statsJson().optInt("dns_queries") > 0)
        val ch = runBlocking { ep.channels().browseAll(id, unlocked = true).first() }.first()
        onMain { controller.tune(TuneTarget.Channel(app.thebox.core.refresh.ChannelIdentity(ch.playlistId, ch.stableKey))) }
        assertNotNull("plays through the custom DoH", runBlocking { withTimeoutOrNull(30_000) { controller.state.first { it is PlaybackState.Playing } } })
    }

    /** I-M5-26 */
    @Test
    fun heapGrowthUnderTwoMegabytesOverTwentyJourneys() {
        val pid = seedXtream()
        launch()
        fun heapKb(): Long {
            repeat(3) { Runtime.getRuntime().gc(); System.runFinalization(); Thread.sleep(200) }
            return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024
        }
        fun journey() {
            openSettings("hidden:$pid")
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            repeat(30) { press(KeyEvent.KEYCODE_PAGE_DOWN, intervalMs = 40) }
            leaveSettings()
            openSettings("parental")
            activate("parental.pin")
            awaitGui("PIN pad") { it.pin != null }
            press(KeyEvent.KEYCODE_BACK)
            leaveSettings()
            openSettings("mapping")
            activate(rowWhere("first action") { r -> isLinkTo(r) { it is app.thebox.core.settings.SettingsRoute.ActionBindings } }.id.key)
            activate(rowWhere("Add a button") { it.kind is RowKind.Action }.id.key)
            awaitSettings("capture") { it.capture != null }
            press(KeyEvent.KEYCODE_BACK)
            leaveSettings()
        }
        journey() // warm-up
        val before = heapKb()
        repeat(20) { journey() }
        val after = heapKb()
        log("heap ${before} KiB → ${after} KiB (native ${Debug.getNativeHeapAllocatedSize() / 1024} KiB)")
        assertTrue("Java heap growth < 2 MB after GC: ${after - before} KiB", after - before < 2_048)
    }

    /** I-M5-27 (B2) */
    @Test
    fun alwaysPassthroughFallsBackToPcmOnTheEmulatorSink() {
        val pid = seedXtream()
        launch()
        openSettings("playback")
        choose("playback.audio_output", AudioOutputMode.FORCE_PASSTHROUGH.name)
        leaveSettings()
        assertEquals(ScreenMode.GUIDE, gui().mode)
        // The AC-3 fixture (§6.4 addition): channel 1 ("News One") serves the panel's AC-3 clip.
        panel.patch(org.json.JSONObject().put("media_override", org.json.JSONObject().put("1", "ac3")))
        val ch = runBlocking { ep.channels().browseAll(pid, unlocked = true).first() }.first { it.name == "News One" }
        val fallbacksBefore = controller.diagnostics.events().count { it.type == PlaybackEventType.AUDIO_FALLBACK }
        onMain { controller.tune(TuneTarget.Channel(app.thebox.core.refresh.ChannelIdentity(ch.playlistId, ch.stableKey))) }
        runBlocking { withTimeoutOrNull(30_000) { controller.state.first { it is PlaybackState.Playing } } }
        Thread.sleep(5_000)
        val fallbacks = controller.diagnostics.events().count { it.type == PlaybackEventType.AUDIO_FALLBACK } - fallbacksBefore
        assertTrue("L34 or AUDIO_NOT_STARTED → PCM (an AUDIO_FALLBACK event)", fallbacks >= 1)
        // Back to Auto restores AUTO.
        openSettings("playback")
        choose("playback.audio_output", AudioOutputMode.AUTO.name)
        leaveSettings()
        assertTrue(runBlocking { probe.documents() }["playback"]?.toString()?.contains("FORCE_PASSTHROUGH") != true)
        assertNotNull(DiagPages.NETTEST_AGAIN)
    }
}
