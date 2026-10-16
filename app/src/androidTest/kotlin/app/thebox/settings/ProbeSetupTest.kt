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
        activate(rowWhere("Connect") { it.kind is RowKind.Action }.id.key)
    }

    /** I-M5-5 */
    @Test
    fun uaProbeSwitchesTheUserAgentToVlc() {
        panel.uaWhitelist(listOf("VLC"))
        onboardXtream()
        awaitText("VLC note", 120_000) { it.contains("only answers VLC", ignoreCase = true) }
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
            awaitText("LIMIT note", 120_000) { it.contains("connection", ignoreCase = true) && (it.contains("in use", ignoreCase = true) || it.contains("limit", ignoreCase = true)) }
            awaitSettings("onboarding continues", 120_000) { it.route is SettingsRoute.ImportProgress || it.route is SettingsRoute.GroupsReview }
            assertTrue("no PLAYBACK lease was pre-empted", controller.state.value !is app.thebox.player.api.PlaybackState.Error)
        } finally {
            panel.releaseHolds()
        }
    }
}
