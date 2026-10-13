package app.thebox.core.m3u

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * Auxiliary lines that attach to the next entry (docs/design/m1-data-layer.md §3.4 M11–M14):
 * `#EXTVLCOPT:k=v`, `#KODIPROP:k=v`, `#EXTHTTP:{json}` and `#EXTGRP:name`.
 * One instance lives for the whole parse; [reset] runs after each emitted entry.
 */
internal class AuxDirectives(private val extgrpSticky: Boolean) {
    private var vlc: LinkedHashMap<String, String>? = null
    private var kodi: LinkedHashMap<String, String>? = null
    /** Headers from VLC options, KODIPROP header props and EXTHTTP, in line order (later wins). */
    private var headers: HeaderPrecedence.Builder? = null
    private var pendingGroup: String? = null
    private var stickyGroup: String? = null

    val vlcOpts: Map<String, String> get() = vlc ?: emptyMap()
    val kodiProps: Map<String, String> get() = kodi ?: emptyMap()

    /** The `#EXTGRP` value for the next entry (M14), or null. */
    val group: String? get() = if (extgrpSticky) stickyGroup else pendingGroup

    fun hasHeaders(): Boolean = headers != null

    fun applyHeadersTo(b: HeaderPrecedence.Builder) {
        val h = headers ?: return
        b.putAll(h.build())
    }

    /** Returns false when the value was malformed (→ BAD_ATTRIBUTE). */
    fun vlcOpt(body: String): Boolean {
        val eq = body.indexOf('=')
        if (eq <= 0) return false
        val key = body.substring(0, eq).trim().lowercase(Locale.ROOT)
        val value = body.substring(eq + 1).trim()
        if (key.isEmpty()) return false
        (vlc ?: LinkedHashMap<String, String>(4).also { vlc = it })[key] = value
        if (HeaderPrecedence.isAlias(key)) headerBuilder().put(key, value)
        return true
    }

    fun kodiProp(body: String): Boolean {
        val eq = body.indexOf('=')
        if (eq <= 0) return false
        var key = body.substring(0, eq).trim().lowercase(Locale.ROOT)
        if (key == "inputstreamaddon") key = "inputstream"
        val value = body.substring(eq + 1).trim()
        if (key.isEmpty()) return false
        (kodi ?: LinkedHashMap<String, String>(4).also { kodi = it })[key] = value
        var ok = true
        if (key == "inputstream.adaptive.stream_headers" || key == "inputstream.adaptive.common_headers" ||
            key == "inputstream.adaptive.manifest_headers"
        ) {
            ok = parseEncodedHeaders(value)
        }
        return ok
    }

    /** `a=b&c=d` with URL-encoded values; a fully-encoded `a%3Db%26c%3Dd` is decoded once first. */
    private fun parseEncodedHeaders(raw: String): Boolean {
        val v = if (raw.indexOf('=') < 0 && raw.contains("%3D", ignoreCase = true)) PipeHeaders.percentDecode(raw) else raw
        var ok = true
        for (pair in v.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq <= 0) { ok = false; continue }
            headerBuilder().put(pair.substring(0, eq), PipeHeaders.percentDecode(pair.substring(eq + 1)))
        }
        return ok
    }

    fun extHttp(body: String): Boolean {
        val obj = try { Json.parseToJsonElement(body.trim()) as? JsonObject } catch (_: Exception) { null } ?: return false
        for ((k, v) in obj) {
            val p = v as? JsonPrimitive ?: continue
            if (!p.isString) continue
            headerBuilder().put(k, p.content)
        }
        return true
    }

    fun extGrp(body: String) {
        val g = body.trim().takeIf { it.isNotEmpty() }
        if (extgrpSticky) stickyGroup = g else pendingGroup = g
    }

    private fun headerBuilder() = headers ?: HeaderPrecedence.Builder().also { headers = it }

    /** After an entry is emitted. The sticky `#EXTGRP` value survives. */
    fun reset() {
        vlc = null
        kodi = null
        headers = null
        pendingGroup = null
    }
}
