package app.thebox.core.sports.hub

import app.thebox.core.sports.SportsConstants
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** (NFL hub §6.5) One offered ESPN video: its metadata and the HLS master (which never leaves `:data`'s catalog). */
data class VideoEntry(val clip: GameClip, val hlsUrl: String) {
    /** The URL is never printed: a log or a test failure shows the clip id only. */
    override fun toString(): String = "VideoEntry(${clip.videoId}, ${clip.kind})"
}

/**
 * (NFL hub commit 0, docs/design/m6-nfl-hub.md §4.2, §4.3, §4.5, §6.5) The one mapping of an ESPN video object (the
 * summary's `videos[]`, the board's `highlights[]`, the clip record's `videos[]`). Pure. Used by `EspnDetailMapper`
 * (detail clips), `EspnMapper` (the board highlight), `EspnDetailSource` (the catalog) and `EspnClipResolver` (records).
 *
 * An entry is returned only when every rule holds; otherwise null:
 * - the id is a positive whole number;
 * - `tracking.coverageType` is "Final Game Highlight" ([ClipKind.RECAP]) or "OnePlay" ([ClipKind.PLAY]); every other
 *   coverage type is dropped;
 * - `links.source.HLS.href` is https (plain http only with [httpOk], the debug override origin);
 * - `deviceRestrictions` is absent, or its whitelist lists `settop` (a blacklist must not);
 * - it has an effective expiry: `expirationDate`, else `originalPublishDate + HUB_CLIP_DEFAULT_LIFE_MS`;
 * - with [atMs] (the reference time `now + skew`, or the board's server date), [ClipOffer.offered] holds at it; a null
 *   [atMs] skips the time check.
 */
object EspnVideoMapper {
    const val COVERAGE_HIGHLIGHT = "Final Game Highlight"
    const val COVERAGE_ONE_PLAY = "OnePlay"
    private const val SETTOP = "settop"

    /** ESPN's own logo base (production). `EspnEndpoints.LOGO_BASE` is this constant. */
    const val PRODUCTION_LOGO_BASE = "https://a.espncdn.com/"

    /**
     * (Integration §18) **The one rule for plain-http clip media and images**: allowed only when the ESPN endpoints are not
     * ESPN's own, i.e. the debug endpoint override (the fake panel), which only a debuggable build can load. Production
     * never offers an http clip. Every caller decides by this function from the endpoints' logo base: the board's
     * highlight (`EspnMapper`), the detail's clips and headshots (`EspnDetailSource` → `EspnDetailMapper.detail(…,
     * httpOk)`), the catalog, and the clip records (`EspnClipResolver`).
     */
    fun httpOkFor(logoBase: String): Boolean = logoBase.trim() != PRODUCTION_LOGO_BASE

    fun entry(dto: EspnVideoDto, atMs: Long?, httpOk: Boolean): VideoEntry? {
        val id = wholeNumber(dto.id)?.takeIf { it > 0 } ?: return null
        val kind = when (dto.tracking?.coverageType?.trim()) {
            COVERAGE_HIGHLIGHT -> ClipKind.RECAP
            COVERAGE_ONE_PLAY -> ClipKind.PLAY
            else -> return null
        }
        val hls = dto.links?.source?.hls?.href?.trim()?.takeIf { usable(it, httpOk) } ?: return null
        if (!settopAllowed(dto.deviceRestrictions)) return null
        val published = dto.originalPublishDate?.let(::parseUtc)
        val embargo = dto.timeRestrictions?.embargoDate?.let(::parseUtc)
        val expires = dto.timeRestrictions?.expirationDate?.let(::parseUtc)
            ?: published?.plus(SportsConstants.HUB_CLIP_DEFAULT_LIFE_MS)
            ?: return null
        if (atMs != null && !ClipOffer.offered(embargo, expires, atMs)) return null
        val clip = GameClip(
            videoId = id,
            kind = kind,
            title = dto.headline?.trim().orEmpty(),
            durationMs = wholeNumber(dto.duration)?.takeIf { it > 0 }?.times(1_000L),
            publishedMs = published,
            embargoMs = embargo,
            expiresMs = expires,
            thumbUrl = dto.thumbnail?.trim()?.takeIf { usable(it, httpOk) },
        )
        return VideoEntry(clip, hls)
    }

    /** https always; http only with [httpOk]. */
    private fun usable(url: String, httpOk: Boolean): Boolean =
        url.startsWith("https://", ignoreCase = true) || (httpOk && url.startsWith("http://", ignoreCase = true))

    private fun settopAllowed(r: EspnDeviceRestrictionsDto?): Boolean {
        val devices = r?.devices ?: return true
        val listed = devices.any { it.trim().equals(SETTOP, ignoreCase = true) }
        return if (r.type?.trim().equals("blacklist", ignoreCase = true)) !listed else listed
    }

    /** A whole number from a JSON number or a digit string (50578630, "50578630", 74.0); else null. */
    private fun wholeNumber(e: JsonElement?): Long? {
        val p = (e as? JsonPrimitive)?.takeUnless { it is JsonNull } ?: return null
        val c = p.content.trim()
        c.toLongOrNull()?.let { return it }
        val d = c.toDoubleOrNull() ?: return null
        return if (d.isFinite() && d == Math.floor(d) && d >= Long.MIN_VALUE.toDouble() && d <= Long.MAX_VALUE.toDouble()) d.toLong() else null
    }

    /** ESPN writes "2026-11-24T04:32:11Z" (seconds optional); both forms are accepted. */
    private fun parseUtc(s: String): Long? = try {
        OffsetDateTime.parse(s.trim()).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        try {
            Instant.parse(s.trim()).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
