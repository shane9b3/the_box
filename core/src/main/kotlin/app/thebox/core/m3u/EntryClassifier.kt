package app.thebox.core.m3u

import app.thebox.core.identity.XtreamUrlShape
import app.thebox.core.source.EntryKind

/**
 * Entry kind (docs/design/m1-data-layer.md §3.4 M19), in order: `tvg-type` → `radio` → Xtream URL kind
 * (long form only: no vote is known while parsing) → `/movie/` or `/series/` in the path → a VOD extension
 * (MOVIE, or SERIES when the group or name looks like a series) → LIVE. The group-name heuristic never
 * reclassifies a URL without a VOD extension.
 */
internal object EntryClassifier {
    private val VOD_EXT = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "m4v", "webm", "mpg", "mpeg", "divx", "3gp")

    fun classify(tvgType: String?, radio: String?, url: String, group: String?, name: String): EntryKind {
        when (tvgType?.trim()?.lowercase(java.util.Locale.ROOT)) {
            "live" -> return EntryKind.LIVE
            "movie", "movies", "vod" -> return EntryKind.MOVIE
            "series" -> return EntryKind.SERIES
            "radio" -> return EntryKind.RADIO
        }
        if (Attr.truthy(radio)) return EntryKind.RADIO
        val pathEnd = pathEnd(url)
        if (url.indexOf("/live/") in 0 until pathEnd || url.indexOf("/movie/") in 0 until pathEnd || url.indexOf("/series/") in 0 until pathEnd) {
            XtreamUrlShape.parse(url, allowShort = false)?.let { return it.kind }
        }
        if (containsInPath(url, "/movie/", pathEnd)) return EntryKind.MOVIE
        if (containsInPath(url, "/series/", pathEnd)) return EntryKind.SERIES
        val ext = extension(url, pathEnd)
        if (ext != null && ext in VOD_EXT) {
            return if (looksLikeSeries(group) || looksLikeSeries(name)) EntryKind.SERIES else EntryKind.MOVIE
        }
        return EntryKind.LIVE
    }

    private fun pathEnd(url: String): Int {
        for (i in url.indices) if (url[i] == '?' || url[i] == '#') return i
        return url.length
    }

    private fun containsInPath(url: String, needle: String, end: Int): Boolean {
        val i = url.indexOf(needle, ignoreCase = true)
        return i in 0 until end
    }

    /** Lower-case extension of the last path segment, or null. */
    fun extension(url: String, end: Int = pathEnd(url)): String? {
        val slash = url.lastIndexOf('/', end - 1)
        val dot = url.lastIndexOf('.', end - 1)
        if (dot <= slash || dot >= end - 1 || end - dot > 6) return null
        return url.substring(dot + 1, end).lowercase(java.util.Locale.ROOT)
    }

    /**
     * Hand-written `(?i)\b(series|tv shows?|season)\b|\bs\d{1,2}\s?e\d{1,3}\b`.
     */
    fun looksLikeSeries(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        val s = text
        val n = s.length
        var i = 0
        while (i < n) {
            if (!isWord(s[i]) || (i > 0 && isWord(s[i - 1]))) { i++; continue }
            // i is at a word start
            if (wordAt(s, i, "series") || wordAt(s, i, "season") || wordAt(s, i, "tv shows") || wordAt(s, i, "tv show")) return true
            if (s[i] == 's' || s[i] == 'S') {
                var j = i + 1
                val d1 = j
                while (j < n && j - d1 < 2 && s[j] in '0'..'9') j++
                if (j > d1) {
                    if (j < n && s[j].isWhitespace()) j++
                    if (j < n && (s[j] == 'e' || s[j] == 'E')) {
                        j++
                        val d2 = j
                        while (j < n && j - d2 < 3 && s[j] in '0'..'9') j++
                        if (j > d2 && (j >= n || !isWord(s[j]))) return true
                    }
                }
            }
            i++
        }
        return false
    }

    private fun wordAt(s: String, i: Int, w: String): Boolean {
        if (!s.regionMatches(i, w, 0, w.length, ignoreCase = true)) return false
        val e = i + w.length
        return e >= s.length || !isWord(s[e])
    }

    private fun isWord(c: Char) = c.isLetterOrDigit() || c == '_'
}
