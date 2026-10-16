package app.thebox.settings

import android.view.KeyEvent
import android.view.WindowManager
import app.thebox.core.guide.ScreenMode
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SourceKind
import app.thebox.core.settings.TextHint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Onboarding (docs/design/m5-setup-settings.md §3.4, §6.3): I-M5-1 first run Xtream, I-M5-2 wrong credentials, I-M5-3
 * expired account, I-M5-4 `get.php` paste, I-M5-7 M3U and guide, I-M5-30 transport failures. **Awaits WP-A** (shell,
 * editor overlay, first-run decision) **and WP-B** (FirstRun/AddSource/ImportProgress/GroupsReview pages, the mapper's
 * texts). Rows are found by kind and route (the contract), not by WP-B's row ids; texts by the §3.4 wording.
 */
class OnboardingTest : SettingsTestBase() {

    private fun firstRun() {
        wipe()
        launch()
        awaitGui("first run opens SETTINGS on FirstRun") { it.mode == ScreenMode.SETTINGS }
        awaitSettings("Welcome") { it.route == SettingsRoute.FirstRun && it.page != null }
    }

    private fun toXtreamForm() {
        val xtream = rowWhere("Xtream entry on Welcome") { r -> isLinkTo(r) { it is SettingsRoute.AddSource && (it.kind == SourceKind.XTREAM || it.kind == null) } }
        activate(xtream.id.key)
        awaitSettings("AddSource form") { s -> s.route is SettingsRoute.AddSource && rows(s).any(textRow(TextHint.URL)) }
    }

    private fun fillXtream(server: String, user: String, pass: String) {
        editText(rowWhere("server field", p = textRow(TextHint.URL)), server)
        editText(rowWhere("username field", p = textRow(TextHint.USERNAME)), user)
        editText(rowWhere("password field", p = textRow(TextHint.PASSWORD)), pass)
    }

    private fun connect() {
        val action = rowWhere("Connect action") { it.kind is RowKind.Action }
        activate(action.id.key)
    }

    /** I-M5-1 */
    @Test
    fun firstRunXtream() {
        firstRun()
        val focused = focusedRow()
        assertTrue("Welcome focus on Set up from phone: $focused", focused != null && isLinkTo(focused) { it is SettingsRoute.PhoneSetup })
        toXtreamForm()
        fillXtream(base, "test", "test")
        connect()
        val text = awaitText("result card Connected · Active", 30_000) { it.contains("Active") && it.contains("connection") }
        assertTrue(text, text.contains("0 of 1") || text.contains("0 / 1") || text.contains("of 2"))
        // Import progress reaches stage 3, Start watching → groups review → guide.
        awaitSettings("import progress", 30_000) { it.route is SettingsRoute.ImportProgress || it.route is SettingsRoute.GroupsReview }
        val start = rowWhere("Start watching", 180_000) { it.kind is RowKind.Action && it.enabled }
        activate(start.id.key)
        if (sui().route is SettingsRoute.GroupsReview) {
            activate(rowWhere("groups review continue") { it.kind is RowKind.Action && it.enabled }.id.key)
        }
        awaitGui("guide after onboarding", 30_000) { it.mode == ScreenMode.GUIDE }
        val channels = runBlocking { ep.channels().browseAll(ep.playlists().all().single().id, unlocked = true).first() }.size
        assertEquals("the catalog's 13 channels", 13, channels)
        relaunch()
        Thread.sleep(2_000)
        assertEquals("relaunch goes straight to the guide", ScreenMode.GUIDE, gui().mode)
    }

    /** I-M5-2 */
    @Test
    fun wrongCredentials() {
        panel.authMode("auth0")
        firstRun()
        toXtreamForm()
        fillXtream(base, "test", "wrong")
        connect()
        awaitText("rejected card", 30_000) { it.contains("rejected the username or password", ignoreCase = true) }
        assertTrue("no playlist row", runBlocking { ep.playlists().all() }.isEmpty())
        // Edit keeps the typed fields.
        press(KeyEvent.KEYCODE_BACK)
        val user = rowWhere("username kept") { r -> (r.kind as? RowKind.Text)?.let { it.hint == TextHint.USERNAME && it.value == "test" } == true }
        assertTrue(user.id.key.isNotEmpty())
    }

    /** I-M5-3 */
    @Test
    fun expiredAccount() {
        panel.status("Expired")
        firstRun()
        toXtreamForm()
        fillXtream(base, "test", "test")
        connect()
        awaitText("expired card with the date", 30_000) { it.contains("expired", ignoreCase = true) && Regex("""\d{1,2}\s\w+|\d{4}-\d{2}-\d{2}""").containsMatchIn(it) }
        activate(rowWhere("Save anyway") { it.kind is RowKind.Action && it.label.contains("anyway", ignoreCase = true) }.id.key)
        val created = runBlocking { kotlinx.coroutines.withTimeoutOrNull(30_000) { while (ep.playlists().all().isEmpty()) kotlinx.coroutines.delay(200); true } }
        assertTrue("Save anyway creates the playlist", created == true)
        openSettings("playlist:${runBlocking { ep.playlists().all().single().id }}")
        val card = rowWhere("account card") { it.kind is RowKind.Info }
        assertEquals("the account card is red", app.thebox.core.settings.Tone.BAD, (card.kind as RowKind.Info).tone)
    }

    /** I-M5-4 */
    @Test
    fun getPhpPaste() {
        firstRun()
        toXtreamForm()
        val url = "$base/get.php?username=test&password=test&type=m3u_plus&output=ts"
        editText(rowWhere("server field", p = textRow(TextHint.URL)), url)
        awaitText("Filled from the link") { it.contains("Filled from the link") }
        val user = rowWhere("username filled") { r -> (r.kind as? RowKind.Text)?.let { it.hint == TextHint.USERNAME && it.value == "test" } == true }
        val pass = rowWhere("password filled") { r -> (r.kind as? RowKind.Text)?.let { it.hint == TextHint.PASSWORD && it.value.isNotEmpty() } == true }
        assertTrue(user.id != pass.id)
        assertTrue("output TS", pageText().contains("TS"))
        // The same URL in the M3U form offers "Use Xtream login instead".
        leaveToWelcome()
        activate(rowWhere("M3U entry") { r -> isLinkTo(r) { it is SettingsRoute.AddSource && it.kind == SourceKind.M3U } }.id.key)
        editText(rowWhere("playlist URL", p = textRow(TextHint.URL)), url)
        val offer = rowWhere("Use Xtream login instead") { it.label.contains("Xtream login", ignoreCase = true) }
        assertEquals("focused", offer.id, focusedRow()?.id)
    }

    private fun leaveToWelcome() {
        repeat(6) {
            if (sui().route == SettingsRoute.FirstRun) return
            press(KeyEvent.KEYCODE_BACK, intervalMs = 300)
        }
    }

    /** I-M5-7 */
    @Test
    fun m3uAndGuide() {
        firstRun()
        activate(rowWhere("M3U entry") { r -> isLinkTo(r) { it is SettingsRoute.AddSource && it.kind == SourceKind.M3U } }.id.key)
        editText(rowWhere("playlist URL", p = textRow(TextHint.URL)), "$base/get.php?username=test&password=test&type=m3u_plus&output=ts")
        connect()
        awaitText("EPG field prefilled from url-tvg", 30_000) { it.contains("xmltv.php") || it.contains("guide", ignoreCase = true) }
        // A URL serving HTML → "web page instead of data"; an HLS manifest → "Add as a single channel".
        leaveToWelcome()
        activate(rowWhere("M3U entry") { r -> isLinkTo(r) { it is SettingsRoute.AddSource && it.kind == SourceKind.M3U } }.id.key)
        editText(rowWhere("playlist URL", p = textRow(TextHint.URL)), "$base/logo/1.png")
        connect()
        awaitText("not a playlist", 30_000) { it.contains("web page instead of data") || it.contains("not a playlist", ignoreCase = true) }
        leaveToWelcome()
        activate(rowWhere("M3U entry") { r -> isLinkTo(r) { it is SettingsRoute.AddSource && it.kind == SourceKind.M3U } }.id.key)
        editText(rowWhere("playlist URL", p = textRow(TextHint.URL)), "$base/live/test/test/1.m3u8")
        connect()
        val single = rowWhere("Add as a single channel", 30_000) { it.label.contains("single channel", ignoreCase = true) }
        activate(single.id.key)
        val added = runBlocking { kotlinx.coroutines.withTimeoutOrNull(30_000) { while (ep.playlists().all().isEmpty()) kotlinx.coroutines.delay(200); true } }
        assertTrue("single-channel playlist added", added == true)
    }

    /** I-M5-30 (S16) */
    @Test
    fun transportFailures() {
        firstRun()
        for ((fault, text) in listOf("html_splash" to "web page instead of data", "timeout" to "did not answer", "429" to "Retry")) {
            panel.apiFault(fault)
            leaveToWelcome()
            toXtreamForm()
            fillXtream(base, "test", "test")
            connect()
            awaitText("$fault → $text", 90_000) { it.contains(text, ignoreCase = true) }
        }
        panel.apiFault(null)
        panel.sleepLogin(8)
        leaveToWelcome()
        toXtreamForm()
        fillXtream(base, "test", "test")
        connect()
        val busy = awaitText("checking state while the login sleeps", 5_000) { it.contains("Checking", ignoreCase = true) || it.contains("Connecting", ignoreCase = true) }
        assertFalse(busy.isEmpty())
        // Keep-screen-on during ImportProgress (S4).
        awaitSettings("import progress", 60_000) { it.route is SettingsRoute.ImportProgress }
        var keepOn = false
        scenario!!.onActivity { a -> keepOn = a.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0 }
        assertTrue("FLAG_KEEP_SCREEN_ON during ImportProgress", keepOn)
    }
}
