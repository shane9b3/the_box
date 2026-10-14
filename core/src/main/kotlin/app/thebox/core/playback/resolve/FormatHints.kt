package app.thebox.core.playback.resolve

import app.thebox.core.playback.StreamFormat
import java.util.Locale

/** URL → [StreamFormat] hint (§3.2.6). Only a hint: sniffing decides (§3.3.3). */
object FormatHints {
    private const val MPEGTS_SUFFIX = "/mpegts"

    private val BY_EXTENSION: Map<String, StreamFormat> = mapOf(
        "m3u8" to StreamFormat.HLS, "m3u" to StreamFormat.HLS,
        "ts" to StreamFormat.TS, "mpegts" to StreamFormat.TS,
        "mpd" to StreamFormat.DASH,
        "mp4" to StreamFormat.PROGRESSIVE_OTHER, "mkv" to StreamFormat.PROGRESSIVE_OTHER,
        "flv" to StreamFormat.PROGRESSIVE_OTHER, "aac" to StreamFormat.PROGRESSIVE_OTHER,
        "mp3" to StreamFormat.PROGRESSIVE_OTHER,
    )

    fun hint(url: String): StreamFormat {
        val path = path(url).lowercase(Locale.ROOT)
        if (path.endsWith(MPEGTS_SUFFIX)) return StreamFormat.TS
        val lastSlash = path.lastIndexOf('/')
        val segment = path.substring(lastSlash + 1)
        val dot = segment.lastIndexOf('.')
        if (dot < 0) return StreamFormat.TS
        return BY_EXTENSION[segment.substring(dot + 1)] ?: StreamFormat.UNKNOWN
    }

    /** The path of [url]: after `scheme://authority`, before `?`/`#`. A URL without a scheme is all path. */
    internal fun path(url: String): String {
        var end = url.length
        for (i in url.indices) {
            val c = url[i]
            if (c == '?' || c == '#') {
                end = i
                break
            }
        }
        val scheme = url.indexOf("://")
        if (scheme < 0 || scheme > end) return url.substring(0, end)
        val pathStart = url.indexOf('/', scheme + 3)
        return if (pathStart < 0 || pathStart > end) "" else url.substring(pathStart, end)
    }

    /** `host[:port]` of [url] (lower-cased, userinfo dropped), or "" when it has none. Not secret. */
    fun host(url: String): String {
        val scheme = url.indexOf("://")
        if (scheme < 0) return ""
        val start = scheme + 3
        var end = url.length
        for (i in start until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#') {
                end = i
                break
            }
        }
        val authority = url.substring(start, end)
        val at = authority.lastIndexOf('@')
        return (if (at >= 0) authority.substring(at + 1) else authority).lowercase(Locale.ROOT)
    }
}
