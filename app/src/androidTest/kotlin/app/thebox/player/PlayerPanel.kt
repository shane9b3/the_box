package app.thebox.player

import app.thebox.guide.GuidePanel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The M4 additions to the fake-panel admin API (`tools/fakepanel`, docs/design/m4-player-ux.md §5.6) on top of the M3
 * [GuidePanel]: player fixtures, timeshift modes and faults, the archive window and lag, variant rejection, the panel
 * clock (`/admin/now`), `timeshift_expect` computed by the panel (`/admin/timeshift_expect`) and the timeshift request
 * log (`/admin/stats.timeshift_requests`). Blocking; call it from the test thread.
 */
class PlayerPanel(val guide: GuidePanel) {
    val base: String get() = guide.base
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    /** One entry of `/admin/stats.timeshift_requests` (panel clock, unix seconds). */
    data class TsRequest(
        val form: String,
        val channel: Int,
        val start: String,
        val startUnix: Double,
        val durationS: Int?,
        val range: String?,
        val status: Int,
        val at: Double,
        val ext: String,
    ) {
        /** A ranged reopen of an earlier request (the finite-TS prepare's tail/head reads, a native seek). */
        val ranged: Boolean get() = range != null && range != "bytes=0-"
        override fun toString() = "$form ch$channel start=$start(${startUnix.toLong()}) dur=$durationS range=$range -> $status @${at.toLong()}"
    }

    data class PanelNow(val unix: Double, val wall: String, val timezone: String) {
        val ms: Long get() = (unix * 1000).toLong()
    }

    /** `synthetic.player_fixtures` channel ids. */
    data class Fixtures(val short: Int, val plus2: Int, val minus15: Int, val multiTs: Int, val multiHls: Int)

    fun patch(json: JSONObject): JSONObject = guide.patch(json)

    /** The M4 panel baseline: synthetic player fixtures and duration-sized timeshift bodies. */
    fun enableFixtures(): JSONObject = patch(JSONObject().put("player_fixtures", true).put("timeshift_mode", "duration"))

    fun fixtures(): Fixtures? = guide.stats().json.optJSONObject("synthetic")?.optJSONObject("player_fixtures")?.let {
        Fixtures(it.getInt("short"), it.getInt("plus2"), it.getInt("minus15"), it.getInt("multi_ts"), it.getInt("multi_hls"))
    }

    fun now(): PanelNow = get("/admin/now").let { PanelNow(it.getDouble("unix"), it.optString("panel_wall"), it.optString("timezone")) }

    /**
     * Sets `timeshift_expect` to `startUnix − corr(channel)·3600 − paddingBefore` (the panel computes it) and returns the
     * panel's answer (`expect_unix`, `panel_wall`, `xc_start`).
     */
    fun expectTimeshift(startUnix: Long, channel: Int? = null, paddingBefore: Int = 0, applyCorrection: Boolean = true, strict: Boolean = false): JSONObject {
        val body = JSONObject().put("start_unix", startUnix).put("padding_before", paddingBefore)
            .put("apply_correction", applyCorrection).put("strict", strict)
        channel?.let { body.put("channel", it) }
        return post("/admin/timeshift_expect", body.toString())
    }

    fun timeshiftRequests(): List<TsRequest> {
        val a = guide.stats().json.optJSONArray("timeshift_requests") ?: return emptyList()
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            TsRequest(
                form = o.optString("form"),
                channel = o.optInt("channel"),
                start = o.optString("start"),
                startUnix = o.optDouble("start_unix"),
                durationS = if (o.isNull("duration_s")) null else o.optInt("duration_s"),
                range = if (o.isNull("range")) null else o.optString("range"),
                status = o.optInt("status"),
                at = o.optDouble("at"),
                ext = o.optString("ext"),
            )
        }
    }

    /** Timeshift requests that reached the panel at or after panel time [sinceUnix]. */
    fun timeshiftSince(sinceUnix: Double): List<TsRequest> = timeshiftRequests().filter { it.at >= sinceUnix }

    /**
     * (Integration 2026-10-15) One entry per **logical** archive request since [sinceUnix]: unranged opens with
     * consecutive duplicates of the same (form, channel, start, duration) merged. A finite TS with a Content-Length is
     * opened twice without a Range (ExoPlayer's first read, then again from byte 0 after TsDurationReader's tail read),
     * with the ranged tail read in between, so counting raw unranged opens doubles every request.
     */
    fun timeshiftLogical(sinceUnix: Double): List<TsRequest> {
        val out = ArrayList<TsRequest>()
        for (r in timeshiftSince(sinceUnix)) {
            if (r.ranged) continue
            val last = out.lastOrNull()
            if (last != null && last.form == r.form && last.channel == r.channel && last.startUnix == r.startUnix && last.durationS == r.durationS && last.status == r.status) continue
            out += r
        }
        return out
    }

    fun timeshiftCount(): Int = guide.stats().json.optInt("timeshift_request_count")
    fun timeshiftWarnings(): Int = guide.timeshiftWarnings()
    fun rejections(): Map<String, Int> = guide.stats().rejections

    /** `timeshift_reject_forms` for every channel, or as a fault of [streamId] only. */
    fun rejectForms(forms: Map<String, Any>, streamId: Int? = null): JSONObject {
        val m = JSONObject().apply { forms.forEach { (k, v) -> put(k, v) } }
        return if (streamId == null) patch(JSONObject().put("timeshift_reject_forms", m))
        else patch(JSONObject().put("stream_faults", JSONObject().put(streamId.toString(), JSONObject().put("timeshift_reject_forms", m))))
    }

    fun archiveLag(sec: Int) = patch(JSONObject().put("archive_lag_sec", sec))
    fun timeshiftWindow(on: Boolean) = patch(JSONObject().put("timeshift_window", on))
    fun rangeIgnored(on: Boolean) = patch(JSONObject().put("timeshift_range_ignored", on))
    fun maxConnections(n: Int) = patch(JSONObject().put("max_connections", n))
    fun faults(f: JSONObject) = patch(JSONObject().put("faults", f))
    fun streamFaults(id: Int, f: JSONObject) = patch(JSONObject().put("stream_faults", JSONObject().put(id.toString(), f)))
    fun mediaOverride(id: Int, clip: String) = patch(JSONObject().put("media_override", JSONObject().put(id.toString(), clip)))

    /** Open (non-HLS) connections the panel currently holds for the test user. */
    fun openConnections(): Int = guide.stats().openConnections

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
