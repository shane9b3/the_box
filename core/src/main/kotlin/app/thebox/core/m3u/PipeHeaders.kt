package app.thebox.core.m3u

import java.io.ByteArrayOutputStream

/**
 * Kodi-style pipe headers on a stream URL (docs/design/m1-data-layer.md §3.4 M9, protocols §1.5):
 * `http://h/s.m3u8|User-Agent=Foo%20Bar&!X-Token=abc`.
 */
object PipeHeaders {
    /** Result of [splitDetailed]: [badPairs] counts malformed `name=value` pairs that were ignored. */
    class Split(val url: String, val headers: Map<String, String>, val badPairs: Int)

    /** Splits the URL from its pipe headers; header names are canonicalised (M10). */
    fun split(url: String): Pair<String, Map<String, String>> = splitDetailed(url).let { it.url to it.headers }

    fun splitDetailed(url: String): Split {
        var end = url.length
        // A bare trailing pipe (or several) carries no headers.
        while (end > 0 && url[end - 1] == '|') end--
        var p = url.lastIndexOf('|', end - 1)
        while (p >= 0) {
            if (looksLikeHeaders(url, p + 1, end)) break
            p = if (p == 0) -1 else url.lastIndexOf('|', p - 1)
        }
        if (p < 0) return Split(if (end == url.length) url else url.substring(0, end), emptyMap(), 0)
        val b = HeaderPrecedence.Builder()
        var bad = 0
        var i = p + 1
        while (i < end) {
            var amp = url.indexOf('&', i)
            if (amp < 0 || amp > end) amp = end
            val eq = url.indexOf('=', i)
            if (eq in (i + 1) until amp) {
                var ns = i
                if (url[ns] == '!') ns++
                val name = url.substring(ns, eq).trim()
                if (name.isEmpty() || !name.all { isNameChar(it) }) bad++
                else b.put(name, percentDecode(url.substring(eq + 1, amp)))
            } else if (amp > i) {
                bad++
            }
            i = amp + 1
        }
        return Split(url.substring(0, p).trimEnd(), b.build(), bad)
    }

    /** `^!?[A-Za-z0-9_\-]+=` on url[from, to). */
    private fun looksLikeHeaders(s: String, from: Int, to: Int): Boolean {
        var i = from
        if (i < to && s[i] == '!') i++
        val start = i
        while (i < to && isNameChar(s[i])) i++
        return i > start && i < to && s[i] == '='
    }

    private fun isNameChar(c: Char) = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '_' || c == '-'

    /** Decodes `%XX` escapes once as UTF-8. `+` stays literal; malformed escapes are kept as written. */
    fun percentDecode(s: String): String {
        if (s.indexOf('%') < 0) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val h = hex(s[i + 1])
                val l = hex(s[i + 2])
                if (h >= 0 && l >= 0) {
                    out.write(h * 16 + l)
                    i += 3
                    continue
                }
            }
            val step = if (c.isHighSurrogate() && i + 1 < s.length) 2 else 1
            out.write(s.substring(i, i + step).toByteArray(Charsets.UTF_8))
            i += step
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
