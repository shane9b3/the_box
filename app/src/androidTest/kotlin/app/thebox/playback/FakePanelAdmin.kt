package app.thebox.playback

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client for the fake panel's admin API (`tools/fakepanel`, §5.5): `POST /admin/reset`, `POST /admin/config`,
 * `POST /admin/kick`, `GET /admin/stats`. Blocking; call it from the test thread, never the main thread.
 *
 * (reviewed 2026-10-13, S13) The panel rejects unknown top-level keys with HTTP 400, and fault keys must sit under
 * `faults` (all streams) or `stream_faults: {"<id>": {…}}`. [Config] and [Faults] are typed so a test cannot get the
 * nesting wrong.
 */
class FakePanelAdmin(val base: String) {
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    /** Fault keys (FAULT_DOC in fakepanel.py). Only non-null values are sent. */
    data class Faults(
        val redirect: Boolean? = null,
        val tokenTtl: Int? = null,
        val linger: Int? = null,
        /** 403, 458, 509, 503 or "close". */
        val lingerStatus: Any? = null,
        val closeEvery: Int? = null,
        /** "continue", "reset" or "jump". */
        val reconnectMode: String? = null,
        val stall: Int? = null,
        /** [after_s, duration_s]. */
        val stallAfter: Pair<Int, Int>? = null,
        /** Bytes per second. */
        val slow: Int? = null,
        val uaWhitelist: List<String>? = null,
        val wrongContentType: Boolean? = null,
        val htmlSplash: Boolean? = null,
        val reject405OnM3u8: Boolean? = null,
        val rangeRejects: Any? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            redirect?.let { put("redirect", it) }
            tokenTtl?.let { put("token_ttl", it) }
            linger?.let { put("linger", it) }
            lingerStatus?.let { put("linger_status", it) }
            closeEvery?.let { put("close_every", it) }
            reconnectMode?.let { put("reconnect_mode", it) }
            stall?.let { put("stall", it) }
            stallAfter?.let { put("stall_after", JSONArray(listOf(it.first, it.second))) }
            slow?.let { put("slow", it) }
            uaWhitelist?.let { put("ua_whitelist", JSONArray(it)) }
            wrongContentType?.let { put("wrong_content_type", it) }
            htmlSplash?.let { put("html_splash", it) }
            reject405OnM3u8?.let { put("405_on_m3u8", it) }
            rangeRejects?.let { put("range_rejects", it) }
        }
    }

    /** Top-level config keys used by the suite, plus the nested fault maps. */
    data class Config(
        val maxConnections: Int? = null,
        /** "Active", "Expired", "Banned", "Disabled". */
        val status: String? = null,
        val allowedOutputFormats: List<String>? = null,
        val tokenTtl: Int? = null,
        val hlsTimeout: Int? = null,
        val activeConsOverride: Int? = null,
        val faults: Faults? = null,
        val streamFaults: Map<Int, Faults> = emptyMap(),
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            maxConnections?.let { put("max_connections", it) }
            status?.let { put("status", it) }
            allowedOutputFormats?.let { put("allowed_output_formats", JSONArray(it)) }
            tokenTtl?.let { put("token_ttl", it) }
            hlsTimeout?.let { put("hls_timeout", it) }
            activeConsOverride?.let { put("active_cons_override", it) }
            faults?.let { put("faults", it.toJson()) }
            if (streamFaults.isNotEmpty()) put("stream_faults", JSONObject().apply { streamFaults.forEach { (id, f) -> put(id.toString(), f.toJson()) } })
        }
    }

    /** `/admin/stats` (counters, rejections by reason, request log, connections). */
    class Stats(val json: JSONObject) {
        val requests: Int get() = json.optInt("requests")
        val streamOpens: Int get() = json.optInt("stream_opens")
        val rangeRequestsSeen: Int get() = json.optInt("range_requests_seen")
        val clientEof: Int get() = json.optInt("client_eof")
        val activeConnections: Int get() = json.optJSONObject("connections")?.optInt("active") ?: 0
        val openConnections: Int get() = json.optJSONObject("connections")?.optInt("open") ?: 0
        val maxOpenSeen: Int get() = json.optJSONObject("connections")?.optInt("max_open_seen") ?: 0
        val rejections: Map<String, Int>
            get() = json.optJSONObject("rejections")?.let { o -> o.keys().asSequence().associateWith { o.optInt(it) } } ?: emptyMap()

        /** Stream request log (credentials masked by the panel): path, ua, token_present, end_reason. */
        val requestLog: List<JSONObject>
            get() = json.optJSONArray("request_log")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

        /** Live stream requests for [streamId] (any extension). */
        fun streamRequests(streamId: Int): List<JSONObject> =
            requestLog.filter { it.optString("path").let { p -> p.contains("/live/") && (p.endsWith("/$streamId.ts") || p.endsWith("/$streamId.m3u8")) } }

        override fun toString(): String = json.toString()
    }

    fun reset() = post("/admin/reset", "{}")

    fun config(config: Config): JSONObject = post("/admin/config", config.toJson().toString())

    fun kick() = post("/admin/kick", "{}")

    fun stats(): Stats = Stats(get("/admin/stats"))

    /** True when `GET /admin/config` answers (the panel is up and reachable from the device). */
    fun reachable(): Boolean = try {
        get("/admin/config")
        true
    } catch (e: Exception) {
        false
    }

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
