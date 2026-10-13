package app.thebox.core.source

import kotlinx.serialization.Serializable

enum class PlaylistType { M3U_URL, M3U_FILE, XTREAM }

/** Persisted as ordinal in channel.kind: append-only. */
enum class EntryKind { LIVE, RADIO, MOVIE, SERIES }

/** Catch-up attributes exactly as the source gave them; resolution is CatchupResolver's job (§3.8.1). */
@Serializable
data class RawCatchup(
    val type: String? = null,             // catchup= / catchup-type=
    val source: String? = null,           // catchup-source=
    val days: Int? = null,                // catchup-days=
    val correctionHours: Double? = null,  // catchup-correction=
    val timeshiftDays: Int? = null,       // timeshift=
    val tvgRecDays: Int? = null,          // tvg-rec=
    val catchupTimeSec: Long? = null,     // catchup-time=
    val xtreamTvArchive: Boolean? = null, // Xtream tv_archive
    val xtreamArchiveDays: Int? = null,   // Xtream tv_archive_duration
) {
    companion object {
        val NONE = RawCatchup()
    }
}

/** One playlist entry from M3U (WP-A) or the Xtream API (WP-C). Never materialised as a list. */
data class ParsedChannel(
    val kind: EntryKind,
    val name: String,
    /** Absolute, pipe headers removed, credentials NOT yet templated (the differ templates). */
    val url: String,
    /** 0-based position among emitted entries (all kinds). */
    val position: Int,
    /** Primary group (first of a `;`-separated group-title, or the Xtream category name). */
    val groupTitle: String? = null,
    val extraGroups: List<String> = emptyList(),
    val xtreamCategoryId: String? = null,
    /** Junk-filtered (§3.4 rule M15), trimmed, case preserved. */
    val tvgId: String? = null,
    val tvgName: String? = null,
    val logoUrl: String? = null,
    val number: Int? = null,
    /** `tvg-shift` converted to minutes (§3.4 rule M17). */
    val epgShiftMinutes: Int? = null,
    /** Canonical header names (User-Agent, Referer, Origin, Cookie, …) merged by precedence (§3.4 rule M10). */
    val headers: Map<String, String> = emptyMap(),
    val catchup: RawCatchup = RawCatchup.NONE,
    /** Xtream API stream_id, or detected from a long-form Xtream URL. */
    val xtreamStreamId: Long? = null,
    val containerExtension: String? = null,
    val adult: Boolean = false,
    /** EXTINF duration when > 0 (VOD hint). */
    val durationSec: Long? = null,
    val kodiProps: Map<String, String> = emptyMap(),
    val vlcOpts: Map<String, String> = emptyMap(),
    /** Every other attribute, lower-case keys (protocols §1.3: never drop data). */
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * `#EXTM3U` header attributes (protocols §1.2). Stored as playlist.header_json.
 * (reviewed 2026-10-13, blocker B3: a get.php header carries `url-tvg=…/xmltv.php?username=U&password=P`.
 * Before storing, WP-D replaces [epgUrls] with `Redact.text(url)` forms for display. The real URLs go
 * only into each epg_source's credential blob, and [attributes] values pass through the same templating.)
 */
@Serializable
data class M3uHeader(
    val epgUrls: List<String> = emptyList(),
    val tvgShiftHours: Double? = null,
    val catchup: RawCatchup = RawCatchup.NONE,
    val urlLogo: String? = null,
    val refreshSec: Long? = null,
    val maxConnections: Int? = null,
    val attributes: Map<String, String> = emptyMap(),
)

data class ParseWarning(val line: Long, val code: WarningCode, val detail: String? = null)

enum class WarningCode {
    LINE_TOO_LONG, EXTINF_WITHOUT_URL, URL_WITHOUT_EXTINF, BAD_ATTRIBUTE, CHARSET_FALLBACK,
    UNSUPPORTED_SCHEME, DUPLICATE_KEY, RELATIVE_URL_UNRESOLVED, XML_RECOVERED, BAD_TIME, UNKNOWN_CHANNEL,
}
