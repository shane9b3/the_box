package app.thebox.settings

import app.thebox.core.guide.ScreenMode
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.setup.lan.LanFailure
import app.thebox.core.setup.lan.ServerPhase
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Phone-assisted setup (docs/design/m5-setup-settings.md §3.5, §6.3): I-M5-9 pairing and the server's refusals, I-M5-17
 * backup via the phone. The test acts as the phone with OkHttp in the test process against the emulator's LAN
 * address (the token and code come from `SettingsDebugProbe.lan()`). **Awaits WP-D** (LAN server, PhoneLink, page
 * assets, backup codec) **and WP-A/WP-B** (PhoneSetup route in the shell; import after `POST /api/xtream`).
 */
class PhoneSetupTest : SettingsTestBase() {
    private val http = OkHttpClient.Builder().followRedirects(false).connectTimeout(5, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    private fun startPhoneSetup(route: String = "phone"): String {
        openSettings(route)
        awaitSettings("PhoneSetup route") { it.route is SettingsRoute.PhoneSetup }
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val s = probe.lan()
            if (s.phase == ServerPhase.WAITING && s.baseUrl != null) return s.baseUrl!!
            if (s.phase == ServerPhase.FAILED) error("LAN server failed: ${s.failure}")
            Thread.sleep(200)
        }
        error("LAN server not waiting: ${probe.lan()}")
    }

    private fun req(base: String, path: String, cookie: String? = null, host: String? = null, json: JSONObject? = null, xTheBox: Boolean = true): Response {
        val b = Request.Builder().url(base + path)
        cookie?.let { b.header("Cookie", it) }
        host?.let { b.header("Host", it) }
        if (json != null) {
            if (xTheBox) b.header("X-TheBox", "1")
            b.post(json.toString().toRequestBody("application/json".toMediaType()))
        }
        return http.newCall(b.build()).execute()
    }

    private fun pairWithToken(base: String): String {
        val qr = probe.lan().qrUrl ?: error("no QR URL")
        val path = qr.substringAfter(base.removeSuffix("/"))
        req(base, path).use { r ->
            assertEquals("GET /s/{token} → 303", 303, r.code)
            return r.header("Set-Cookie")!!.substringBefore(';')
        }
    }

    /** I-M5-9 */
    @Test
    fun phoneSetupPairsSubmitsAndRefuses() {
        debugLink("phone_port=47100")
        val base = startPhoneSetup()
        val host = base.removePrefix("http://").trimEnd('/')
        // Wrong Host → 421 (DNS rebinding).
        req(base, "/", host = "evil.example:47100").use { assertEquals(421, it.code) }
        // Wrong code × 5 → new code.
        val code0 = probe.lan().code
        repeat(5) { req(base, "/pair", json = JSONObject().put("code", "000000")).use { r -> assertTrue("wrong code refused: ${r.code}", r.code == 403) } }
        val code1 = probe.lan().code
        assertTrue("the code rotated after 5 wrong codes", code1 != null && code1 != code0)
        // No cookie → 401.
        req(base, "/api/state").use { assertEquals(401, it.code) }
        val cookie = pairWithToken(base)
        // Missing X-TheBox → 403.
        req(base, "/api/xtream", cookie, json = JSONObject().put("server", panel.base), xTheBox = false).use { assertEquals(403, it.code) }
        // Body over the JSON limit → 413.
        req(base, "/api/xtream", cookie, json = JSONObject().put("server", "x".repeat(70_000))).use { assertEquals(413, it.code) }
        // POST /api/xtream → the TV shows "Received from your phone" and imports.
        val body = JSONObject().put("name", "Phone").put("server", base(panel)).put("username", "test").put("password", "test").put("output", "ts")
        req(base, "/api/xtream", cookie, json = body).use { assertEquals(202, it.code) }
        awaitText("Received from your phone", 15_000) { it.contains("Received from your phone") }
        val imported = runBlocking { kotlinx.coroutines.withTimeoutOrNull(120_000) { while (ep.playlists().all().isEmpty()) kotlinx.coroutines.delay(500); true } }
        assertEquals("the TV imported the playlist", true, imported)
        assertNotNull(host)
    }

    private fun base(p: SettingsPanel) = p.base

    /**
     * I-M5-9 with `auth_mode auth0` (U-S4 / U-S5, code review 2026-10-16): a login the panel rejects, sent from the phone,
     * runs Connect first: no playlist row; the phone page reads the reason and the next step; the TV shows the Xtream
     * form filled from the phone with Connect's result card.
     */
    @Test
    fun phoneLoginRejectedCreatesNoPlaylist() {
        wipe()
        debugLink("phone_port=47100")
        val base = startPhoneSetup()
        val cookie = pairWithToken(base)
        panel.authMode("auth0")
        val body = JSONObject().put("name", "Phone").put("server", base(panel)).put("username", "test").put("password", "test").put("output", "ts")
        req(base, "/api/xtream", cookie, json = body).use { assertEquals(202, it.code) }
        // The phone polls /api/state every second; the TV keeps the page up ~1.5 s after a failure for that read.
        var message = ""
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val m = runCatching { req(base, "/api/state", cookie).use { r -> JSONObject(r.body.string()).optString("message") } }.getOrNull().orEmpty()
            if (m.isNotBlank() && !m.contains("Checking", ignoreCase = true)) { message = m; break }
            Thread.sleep(250)
        }
        log("phone message: $message")
        // The reason (`setup_card_bad_login`) and its next step (`setup_card_bad_login_next`).
        assertTrue("the phone reads the reason: '$message'", message.contains("rejected", ignoreCase = true))
        assertTrue("the phone reads the next step: '$message'", message.contains("Check", ignoreCase = true))
        awaitSettings("the TV opens the Xtream form", 20_000) { it.route is SettingsRoute.AddSource }
        awaitText("Connect's result card on the TV", 20_000) { it.contains("rejected", ignoreCase = true) }
        Thread.sleep(2_000)
        assertTrue("no playlist row on a rejected login", runBlocking { ep.playlists().all() }.isEmpty())
        panel.authMode("ok")
    }

    /**
     * U-S7 (code review 2026-10-16, Blocker): §3.5.2 lifetime. HOME (the activity reaches CREATED) stops the LAN server:
     * a GET is refused at the socket. Back in front (STARTED) the page opens again with a fresh code.
     */
    @Test
    fun serverStopsWhenTheAppStopsAndReopensWithANewCode() {
        debugLink("phone_port=47100")
        val base = startPhoneSetup()
        val code0 = probe.lan().code
        req(base, "/").use { r -> assertTrue("served while in front: ${r.code}", r.code in 200..499) }
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        val d = android.os.SystemClock.elapsedRealtime() + 10_000
        while (android.os.SystemClock.elapsedRealtime() < d && probe.lan().phase != ServerPhase.CLOSED) Thread.sleep(100)
        assertEquals("server closed on ON_STOP", ServerPhase.CLOSED, probe.lan().phase)
        val refused = runCatching { req(base, "/").use { it.code } }
        assertTrue("a GET after ON_STOP is refused: $refused", refused.isFailure && refused.exceptionOrNull() is java.io.IOException)
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        val again = startPhoneSetupFromCurrent()
        assertNotNull(again)
        assertTrue("a fresh code after ON_START (${probe.lan().code} vs $code0)", probe.lan().code != null && probe.lan().code != code0)
    }

    @Test
    fun idleExpiryWithTheDebugClock() {
        startPhoneSetup()
        debugLink("clock_offset_ms=${600_000 + 5_000}")
        val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
        while (android.os.SystemClock.elapsedRealtime() < deadline && probe.lan().failure != LanFailure.EXPIRED && probe.lan().phase != ServerPhase.CLOSED) Thread.sleep(250)
        debugLink("clock_offset_ms=0")
        assertTrue("idle expiry: ${probe.lan()}", probe.lan().failure == LanFailure.EXPIRED || probe.lan().phase == ServerPhase.CLOSED)
    }

    /** I-M5-17 */
    @Test
    fun backupViaThePhone() {
        val pid = seedXtream()
        launch()
        // BACKUP_DOWNLOAD: the TV offers the file; GET /api/download/{name} once, a second GET is 404.
        openSettings("backup")
        val toPhone = rowWhere("Back up now") { it.id.key == "backup.now" }
        activate(toPhone.id.key)
        // (M5 integration) Back up now → choose the target (WP-D's flow stages): "To your phone" opens the phone page.
        activate(rowWhere("To your phone") { it.id.key == "backup.now.phone" }.id.key)
        awaitSettings("PhoneSetup for the download", 20_000) { (it.route as? SettingsRoute.PhoneSetup)?.purpose == app.thebox.core.settings.PhonePurpose.BACKUP_DOWNLOAD }
        val base = startPhoneSetupFromCurrent()
        val cookie = pairWithToken(base)
        // (M5 integration) WP-D's BACKUP_DOWNLOAD takes the passphrase on the phone first, then exports and offers it.
        req(base, "/api/passphrase", cookie, json = JSONObject().put("passphrase", "correct horse 42")).use { assertEquals(202, it.code) }
        val name = awaitOfferedName(base, cookie)
        val bytes = req(base, "/api/download/$name", cookie).use { r -> assertEquals(200, r.code); r.body.bytes() }
        assertTrue(bytes.isNotEmpty())
        req(base, "/api/download/$name", cookie).use { assertEquals("second GET", 404, it.code) }
        // RESTORE: upload the file, passphrase, restored.
        leaveSettings()
        openSettings("phone")
        val b2 = startPhoneSetupFromCurrent()
        val c2 = pairWithToken(b2)
        http.newCall(Request.Builder().url("$b2/api/upload?kind=backup&name=$name").header("Cookie", c2).header("X-TheBox", "1")
            .post(bytes.toRequestBody("application/octet-stream".toMediaType())).build()).execute().use { assertEquals(202, it.code) }
        req(b2, "/api/passphrase", c2, json = JSONObject().put("passphrase", "correct horse 42")).use { assertEquals(202, it.code) }
        // (M5 integration) WP-D: the passphrase is taken on the phone, Merge / Replace on the TV's RestorePreview.
        awaitSettings("restore preview", 60_000) { it.route is SettingsRoute.RestorePreview }
        // "How" is Merge by default; "Restore now" runs it.
        val go = rowWhere("Restore now", 15_000) { it.kind is app.thebox.core.settings.RowKind.Action && it.label.contains("Restore now", ignoreCase = true) }
        activate(go.id.key)
        if (sui().confirm != null) { press(android.view.KeyEvent.KEYCODE_DPAD_LEFT); press(android.view.KeyEvent.KEYCODE_DPAD_CENTER) }
        awaitText("restored", 60_000) { it.contains("Restored", ignoreCase = true) }
        assertNotNull(pid)
    }

    /**
     * I-M5-16 variant (C-S8, code review 2026-10-16): from a fresh install's Welcome, a restore through the phone page
     * ends with **Start watching** (→ the guide) and, because the backup carried a group lock and no PIN exists, the
     * §3.4.6 PIN offer.
     */
    @Test
    fun restoreFromAFreshWelcomeStartsWatchingAndOffersThePin() {
        wipe()
        val pid = seedXtream()
        launch()
        // An explicit group lock without a PIN (a filter, B3): Settings → Channels → Groups → a group's menu → Lock.
        openSettings("groups:$pid")
        val g = rowWhere("a group row", 20_000) { it.id.key.startsWith(app.thebox.core.settings.tree.ChannelsRows.GROUP_PREFIX) && it.kind is app.thebox.core.settings.RowKind.Item }
        activate(g.id.key)
        awaitSettings("the group's menu") { it.menu != null }
        val lockAt = sui().menu!!.options.indexOfFirst { it.id == app.thebox.core.settings.tree.ChannelsRows.M_LOCK }
        assertTrue("Lock in the group's menu", lockAt >= 0)
        var guard = 0
        while (sui().menu?.focused != lockAt && guard++ < 10) press(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        awaitSettings("the group is locked", 10_000) { st ->
            rows(st).firstOrNull { it.id == g.id }?.let { (it.kind as? app.thebox.core.settings.RowKind.Item)?.badges?.contains(app.thebox.core.settings.Badge.LOCKED) } == true
        }
        leaveSettings()
        val file = java.io.File(context.filesDir, "m5-welcome.thebox")
        val exported = runBlocking { file.outputStream().use { probe.backup.export(it, "correct horse 42".toCharArray()) } }
        assertTrue("export: $exported", exported is app.thebox.core.common.Outcome.Ok)
        val bytes = file.readBytes()
        // Fresh install: Welcome.
        wipe()
        launch()
        awaitGui("first run (Welcome)", 15_000) { it.mode == ScreenMode.SETTINGS }
        awaitSettings("Welcome") { it.route == SettingsRoute.FirstRun }
        debugLink("phone_port=47100")
        debugLink("settings=phone&open=0")
        awaitSettings("PhoneSetup route") { it.route is SettingsRoute.PhoneSetup }
        val b = startPhoneSetupFromCurrent()
        val c = pairWithToken(b)
        http.newCall(Request.Builder().url("$b/api/upload?kind=backup&name=m5-welcome.thebox").header("Cookie", c).header("X-TheBox", "1")
            .post(bytes.toRequestBody("application/octet-stream".toMediaType())).build()).execute().use { assertEquals(202, it.code) }
        req(b, "/api/passphrase", c, json = JSONObject().put("passphrase", "correct horse 42")).use { assertEquals(202, it.code) }
        awaitSettings("restore preview", 60_000) { it.route is SettingsRoute.RestorePreview }
        val go = rowWhere("Restore now", 15_000) { it.kind is app.thebox.core.settings.RowKind.Action && it.label.contains("Restore now", ignoreCase = true) }
        activate(go.id.key)
        if (sui().confirm != null) { press(android.view.KeyEvent.KEYCODE_DPAD_LEFT); press(android.view.KeyEvent.KEYCODE_DPAD_CENTER) }
        awaitText("restored", 60_000) { it.contains("Restored", ignoreCase = true) }
        assertNotNull("the PIN offer after a restore that carried a lock", rowWhere("PIN offer", 15_000) { it.id == app.thebox.core.settings.tree.BackupRows.PREVIEW_PIN_OFFER || it.id == app.thebox.core.settings.tree.BackupRows.PREVIEW_PIN_SET })
        assertNotNull(rowWhere("Start watching") { it.id == app.thebox.core.settings.tree.BackupRows.PREVIEW_START })
        activate(app.thebox.core.settings.tree.BackupRows.PREVIEW_START.key)
        awaitGui("Start watching → the guide", 20_000) { it.mode == ScreenMode.GUIDE }
        assertTrue(runBlocking { ep.playlists().all() }.isNotEmpty())
    }

    private fun startPhoneSetupFromCurrent(): String {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val s = probe.lan()
            if (s.phase == ServerPhase.WAITING && s.baseUrl != null) return s.baseUrl!!
            Thread.sleep(200)
        }
        error("LAN server not waiting: ${probe.lan()}")
    }

    private fun awaitOfferedName(base: String, cookie: String): String {
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            req(base, "/api/state", cookie).use { r ->
                val j = JSONObject(r.body.string())
                // (M5 integration) WP-D's state carries {"download": {"name", "url", "bytes"}}.
                j.optJSONObject("download")?.optString("name")?.takeIf { it.isNotBlank() }?.let { return it }
            }
            Thread.sleep(500)
        }
        error("no download offered")
    }
}
