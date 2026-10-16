package app.thebox.settings

import app.thebox.core.guide.ScreenMode
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SourceKind
import app.thebox.core.settings.TextHint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The onboarding UA probe (docs/design/m5-setup-settings.md §3.4.5, §6.3): I-M5-5 UA whitelist and I-M5-6 probe with no
 * free slot. **Awaits WP-B** (StreamProbe, import stage 3) **and WP-A** (shell).
 */
class ProbeSetupTest : SettingsTestBase() {

    private fun onboardXtream() {
        wipe()
        launch()
        awaitGui("first run") { it.mode == ScreenMode.SETTINGS }
        activate(rowWhere("Xtream entry") { r -> isLinkTo(r) { it is SettingsRoute.AddSource && (it.kind == SourceKind.XTREAM || it.kind == null) } }.id.key)
        editText(rowWhere("server", p = textRow(TextHint.URL)), base)
        editText(rowWhere("username", p = textRow(TextHint.USERNAME)), "test")
        editText(rowWhere("password", p = textRow(TextHint.PASSWORD)), "test")
        // (M5 integration) Connect by WP-B's id (More options is also an Action), then the result card's primary button
        // (Continue) saves and starts the import (§3.4.4).
        activate(rowWhere("Connect") { it.kind is RowKind.Action && it.id.key == "setup.xtream.connect" }.id.key)
        rowWhere("result card", 30_000) { it.id.key == "setup.card.primary" }
        activate("setup.card.primary")
    }

    /** I-M5-5 */
    @Test
    fun uaProbeSwitchesTheUserAgentToVlc() {
        panel.uaWhitelist(listOf("VLC"))
        onboardXtream()
        awaitText("VLC note", 120_000) { it.contains("identify as VLC", ignoreCase = true) } // (M5 integration) WP-B's wording
        val p = runBlocking { ep.playlists().all().single() }
        assertTrue("playlist.user_agent = VLC preset: ${p.userAgent}", p.userAgent?.contains("VLC") == true)
        // The probe connection closed: active 0 within linger + 1 s.
        val deadline = android.os.SystemClock.elapsedRealtime() + 3_000
        while (panel.activeConnections() > 0 && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(200)
        assertEquals("probe connection released", 0, panel.activeConnections())
    }

    /** I-M5-6 */
    @Test
    fun probeWithNoFreeSlotReportsLimitAndContinues() {
        panel.maxConnections(1)
        assertTrue("admin holds the only slot", panel.holdConnection(1))
        try {
            onboardXtream()
            // (M5 integration) WP-B's note: "Another device is using your connection; the stream test was skipped".
            awaitText("LIMIT note", 120_000) { it.contains("connection", ignoreCase = true) && (it.contains("in use", ignoreCase = true) || it.contains("limit", ignoreCase = true) || it.contains("another device", ignoreCase = true)) }
            awaitSettings("onboarding continues", 120_000) { it.route is SettingsRoute.ImportProgress || it.route is SettingsRoute.GroupsReview }
            assertTrue("no PLAYBACK lease was pre-empted", controller.state.value !is app.thebox.player.api.PlaybackState.Error)
        } finally {
            panel.releaseHolds()
        }
    }
}
