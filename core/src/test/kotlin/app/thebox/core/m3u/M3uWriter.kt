package app.thebox.core.m3u

import app.thebox.core.source.EntryKind
import app.thebox.core.source.M3uHeader
import app.thebox.core.source.ParsedChannel
import java.net.URLEncoder

/** Test-only M3U serialiser: the inverse of the parser for the fields it can express. */
object M3uWriter {
    fun write(header: M3uHeader, entries: List<ParsedChannel>): String {
        val sb = StringBuilder("#EXTM3U")
        if (header.epgUrls.isNotEmpty()) sb.append(" x-tvg-url=\"").append(header.epgUrls.joinToString(",")).append('"')
        sb.append('\n')
        for (e in entries) append(sb, e)
        return sb.toString()
    }

    fun append(sb: StringBuilder, e: ParsedChannel) {
        sb.append("#EXTINF:").append(e.durationSec ?: -1)
        attr(sb, "tvg-id", e.tvgId)
        attr(sb, "tvg-name", e.tvgName)
        attr(sb, "tvg-logo", e.logoUrl)
        attr(sb, "tvg-chno", e.number?.toString())
        if (e.groupTitle != null) attr(sb, "group-title", (listOf(e.groupTitle) + e.extraGroups).joinToString(";"))
        attr(sb, "tvg-type", when (e.kind) { EntryKind.LIVE -> "live"; EntryKind.RADIO -> "radio"; EntryKind.MOVIE -> "movie"; EntryKind.SERIES -> "series" })
        if (e.adult) attr(sb, "adult", "1")
        e.epgShiftMinutes?.let { attr(sb, "tvg-shift", (it / 60.0).toString()) }
        e.catchup.type?.let { attr(sb, "catchup", it) }
        e.catchup.source?.let { attr(sb, "catchup-source", it) }
        e.catchup.days?.let { attr(sb, "catchup-days", it.toString()) }
        for ((k, v) in e.attributes) if (k != "tvg-type") attr(sb, k, v)
        sb.append(',').append(e.name).append('\n')
        for ((k, v) in e.vlcOpts) sb.append("#EXTVLCOPT:").append(k).append('=').append(v).append('\n')
        for ((k, v) in e.kodiProps) sb.append("#KODIPROP:").append(k).append('=').append(v).append('\n')
        sb.append(e.url)
        if (e.headers.isNotEmpty()) {
            sb.append('|')
            sb.append(e.headers.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20") })
        }
        sb.append('\n')
    }

    private fun attr(sb: StringBuilder, k: String, v: String?) {
        if (v == null) return
        val q = if (v.contains('"')) '\'' else '"'
        require(!(v.contains('"') && v.contains('\''))) { "value with both quote kinds cannot be written: $v" }
        sb.append(' ').append(k).append('=').append(q).append(v).append(q)
    }
}
