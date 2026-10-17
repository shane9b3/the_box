package app.thebox.settings

import app.thebox.playback.FakePanelAdmin
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The M5 fake-panel admin calls (docs/design/m5-setup-settings.md §6.4) on top of the M2 [FakePanelAdmin]: raw config
 * patches (adult category, api_fault, sleep_login, dns_records, update_*), the update APK upload and key, connection
 * holds. Blocking; call from the test thread.
 */
class SettingsPanel(val base: String) {
    val admin = FakePanelAdmin(base)
    // U-S14 (code review 2026-10-16): a 120 s write timeout for the APK upload fallback (OkHttp's default 10 s per write
    // timed out over the emulator's slirp NAT on a busy host); the suite normally selects host-side APKs by name.
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()

    fun reachable() = admin.reachable()
    fun reset() = admin.reset()
    fun stats() = admin.stats()
    fun statsJson(): JSONObject = get("/admin/stats")
    fun config(): JSONObject = get("/admin/config")
    fun patch(json: JSONObject): JSONObject = post("/admin/config", json.toString())

    fun adultCategory(on: Boolean = true) = patch(JSONObject().put("adult_category", on))
    fun apiFault(fault: String?) = patch(JSONObject().put("api_fault", fault ?: JSONObject.NULL))
    fun sleepLogin(seconds: Int) = patch(JSONObject().put("sleep_login", seconds))
    fun dnsRecords(records: Map<String, String>) = patch(JSONObject().put("dns_records", JSONObject(records)))
    fun authMode(mode: String) = patch(JSONObject().put("auth_mode", mode))
    fun status(status: String) = patch(JSONObject().put("status", status))
    fun maxConnections(n: Int) = patch(JSONObject().put("max_connections", n))
    fun uaWhitelist(uas: List<String>) = patch(JSONObject().put("faults", JSONObject().put("ua_whitelist", JSONArray(uas))))
    fun streamFault(key: String, value: Any) = patch(JSONObject().put("faults", JSONObject().put(key, value)))

    fun updateMode(mode: String) = patch(JSONObject().put("update_mode", mode))
    fun updateManifest(overrides: JSONObject) = patch(JSONObject().put("update_manifest", overrides))
    fun updateApk(name: String) = patch(JSONObject().put("update_apk", name))
    fun updatePublicKey(): String = get("/admin/update_key").getString("public_key")

    /** U-S14: the update APKs the panel holds by name (`--update-apk-dir` on the host, or uploaded). */
    fun updateApkNames(): Set<String> = get("/admin/update_apks").optJSONArray("names")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()

    /**
     * U-S14: offers the host-side APK [name] (`tools/fakepanel/run-instrumented.sh --update-fixtures` loads `installed`,
     * `v2`, `other`); without it, uploads [bytes] (null = the fixture is missing) as [name]. False when neither exists.
     */
    fun offerApk(name: String, bytes: () -> ByteArray?): Boolean {
        if (name !in updateApkNames()) {
            val b = bytes() ?: return false
            uploadApk(name, b)
        }
        updateApk(name)
        return true
    }

    /** Uploads [bytes] as `/update/[name].apk`; returns the panel's SHA-256 (upper-case hex). */
    fun uploadApk(name: String, bytes: ByteArray): String =
        http.newCall(Request.Builder().url("$base/admin/update_apk?name=$name").post(bytes.toRequestBody("application/octet-stream".toMediaType())).build()).execute().use { r ->
            val body = r.body.string()
            check(r.isSuccessful) { "upload → ${r.code}: $body" }
            JSONObject(body).getString("sha256")
        }

    fun holdConnection(stream: Int = 1): Boolean = http.newCall(Request.Builder().url("$base/admin/hold_connection?stream=$stream").build()).execute().use { it.isSuccessful }
    fun releaseHolds() = post("/admin/release_holds", "{}")

    fun activeConnections(): Int = statsJson().optJSONObject("connections")?.optInt("active") ?: 0

    private fun get(path: String): JSONObject = http.newCall(Request.Builder().url(base + path).build()).execute().use { r ->
        val body = r.body.string()
        check(r.isSuccessful) { "panel $path → ${r.code}: $body" }
        JSONObject(body)
    }

    private fun post(path: String, json: String): JSONObject =
        http.newCall(Request.Builder().url(base + path).post(json.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            val body = r.body.string()
            check(r.isSuccessful) { "panel $path → ${r.code}: $body" }
            JSONObject(body)
        }
}
