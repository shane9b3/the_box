package app.thebox.settings

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
        awaitSettings("PhoneSetup for the download", 20_000) { (it.route as? SettingsRoute.PhoneSetup)?.purpose == app.thebox.core.settings.PhonePurpose.BACKUP_DOWNLOAD }
        val base = startPhoneSetupFromCurrent()
        val cookie = pairWithToken(base)
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
        awaitText("restored", 60_000) { it.contains("Restored", ignoreCase = true) }
        assertNotNull(pid)
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
                j.optString("download").takeIf { it.isNotBlank() }?.let { return it }
            }
            Thread.sleep(500)
        }
        error("no download offered")
    }
}
