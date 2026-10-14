package app.thebox.guide

import app.thebox.playback.FakePanelAdmin
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The M3 additions to the fake-panel admin API (`tools/fakepanel`, §5.6) on top of the M2 [FakePanelAdmin]: synthetic
 * lineup facts (`/admin/stats` → `synthetic`), the `epg_version` bump (I-6), the server `timezone` and
 * `timeshift_expect` (I-9). Blocking; call from the test thread.
 */
class GuidePanel(val base: String) {
    val admin = FakePanelAdmin(base)
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    /** `/admin/stats.synthetic` (null when the panel was not started with `--synthetic N`). */
    class Synthetic(val json: JSONObject) {
        val n: Int get() = json.getInt("n")
        /** Server start floored to the minute (unix s). */
        val startUnix: Long get() = json.getLong("start_unix")
        /** [S + 120, S + 300] (unix s): channel 1's "Reminder Test Start" / "Reminder Test Next" programmes. */
        val reminderStarts: List<Long> get() = json.getJSONArray("reminder_starts").let { a -> (0 until a.length()).map { a.getLong(it) } }
        val epgVersion: Int get() = json.optInt("epg_version")
        val groupSize: Int get() = json.optInt("group_size", 50)
        override fun toString() = json.toString()
    }

    fun reachable() = admin.reachable()
    fun reset() = admin.reset()
    fun stats() = admin.stats()

    fun synthetic(): Synthetic? = admin.stats().json.optJSONObject("synthetic")?.let(::Synthetic)

    fun config(): JSONObject = get("/admin/config")

    /** Raw config patch (the panel rejects unknown keys with 400; [FakePanelAdmin.Config] has no M3 keys). */
    fun patch(json: JSONObject): JSONObject = post("/admin/config", json.toString())

    fun setEpgVersion(v: Int) = patch(JSONObject().put("epg_version", v))
    fun setTimezone(zone: String) = patch(JSONObject().put("timezone", zone))
    fun expectTimeshift(unixStarts: List<Long>, strict: Boolean = false) =
        patch(JSONObject().put("timeshift_expect", JSONArray(unixStarts)).put("strict_timeshift_tz", strict))

    /** Stream request log entries whose path is a timeshift/archive request. */
    fun timeshiftRequests(): List<JSONObject> =
        stats().requestLog.filter { it.optString("path").let { p -> p.contains("/timeshift") || p.contains("timeshift.php") } }

    fun timeshiftWarnings(): Int = stats().json.optInt("timeshift_tz_warnings")

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
