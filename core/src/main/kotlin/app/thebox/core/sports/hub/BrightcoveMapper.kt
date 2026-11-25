package app.thebox.core.sports.hub

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §6.3; WP-2) Maps one playback-service video answer: `name`,
 * `long_description` (else `description`), `duration`, `poster`, `thumbnail`; `sources[]` in the §6.3 order (https HLS
 * without `key_systems`, https MP4 ≤ 720p, then the http variants; DASH and DRM ignored); English captions. Pure.
 *
 * Absent or oddly typed fields never throw: a field that is not what it should be counts as absent.
 */
object BrightcoveMapper {
    /** MP4 renditions above this height are skipped (the HLS ladder adapts; MP4 is the fallback). */
    const val MP4_MAX_HEIGHT = 720

    /** The metadata of [ref] from [body]; null = unmappable (no `name`). */
    fun info(ref: ClipRef, body: JsonObject): ClipInfo? {
        val name = body.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val description = sequenceOf("long_description", "description").mapNotNull { body.str(it)?.trim()?.takeIf { t -> t.isNotEmpty() } }.firstOrNull()
        val duration = (body["duration"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 }
        return ClipInfo(
            ref = ref,
            title = name,
            description = description,
            durationMs = duration,
            posterUrl = image(body, "poster", "poster_sources"),
            thumbnailUrl = image(body, "thumbnail", "thumbnail_sources"),
        )
    }

    /** The best playable source of [body] (and its captions), or null when nothing is playable. */
    fun stream(body: JsonObject): ClipStream? {
        val candidates = (body["sources"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull(::candidate)
        // §6.3: https before http; within a scheme HLS before MP4; MP4 by height, tallest (≤ 720) first.
        val best = candidates.sortedWith(
            compareBy<Candidate>({ !it.https }, { it.format != ClipFormat.HLS }, { -(it.height ?: 0) }),
        ).firstOrNull() ?: return null
        return ClipStream(best.url, best.format, captions(body))
    }

    private class Candidate(val url: String, val format: ClipFormat, val https: Boolean, val height: Int?)

    private fun candidate(s: JsonObject): Candidate? {
        val url = s.str("src")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val https = url.startsWith("https://", ignoreCase = true)
        if (!https && !url.startsWith("http://", ignoreCase = true)) return null
        // DRM: anything that names key systems is skipped, whatever its type.
        val keys = s["key_systems"]
        if (keys != null && keys !is JsonNull && !(keys is JsonObject && keys.isEmpty())) return null
        val type = s.str("type")?.lowercase()
        val container = s.str("container")?.uppercase()
        val path = url.substringBefore('?').lowercase()
        if (type == "application/dash+xml" || path.endsWith(".mpd")) return null
        if (type == "application/x-mpegurl" || type == "application/vnd.apple.mpegurl" || path.endsWith(".m3u8")) {
            return Candidate(url, ClipFormat.HLS, https, null)
        }
        if (container == "MP4" || type == "video/mp4" || path.endsWith(".mp4")) {
            val height = (s["height"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.toInt()
            if (height != null && height > MP4_MAX_HEIGHT) return null
            return Candidate(url, ClipFormat.MP4, https, height)
        }
        return null
    }

    /** The English captions track (`kind == "captions"`, `srclang` en or en-*): its https address first. */
    private fun captions(body: JsonObject): String? {
        for (t in (body["text_tracks"] as? JsonArray).orEmpty()) {
            val track = t as? JsonObject ?: continue
            if (track.str("kind")?.lowercase() != "captions") continue
            val lang = track.str("srclang")?.lowercase() ?: continue
            if (lang != "en" && !lang.startsWith("en-")) continue
            val urls = (track["sources"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("src") } + listOfNotNull(track.str("src"))
            val clean = urls.map { it.trim() }.filter { it.startsWith("https://", true) || it.startsWith("http://", true) }
            return clean.firstOrNull { it.startsWith("https://", true) } ?: clean.firstOrNull() ?: continue
        }
        return null
    }

    /** [single] when it is https, else the first https entry of [list], else whatever exists. */
    private fun image(body: JsonObject, single: String, list: String): String? {
        val one = body.str(single)?.trim()?.takeIf { it.isNotEmpty() }
        if (one != null && one.startsWith("https://", true)) return one
        val many = (body[list] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("src")?.trim() }
        return many.firstOrNull { it.startsWith("https://", true) } ?: one ?: many.firstOrNull()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
