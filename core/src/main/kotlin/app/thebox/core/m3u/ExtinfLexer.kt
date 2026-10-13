package app.thebox.core.m3u

/**
 * Hand-written, quote-aware attribute lexer for `#EXTINF` and `#EXTM3U` lines
 * (docs/design/m1-data-layer.md §3.4 M7, M8). No regex.
 *
 * - Keys are `[A-Za-z0-9_.:\-]+`, lower-cased. A bare key (no `=`) gives `""`.
 * - Values are `"…"` (no escapes), `'…'`, unquoted (up to whitespace or the delimiting comma), or empty.
 * - An unterminated quote takes the value to the end of the attribute region (the last comma of the line,
 *   so the display name survives; the whole line if there is none) and reports [Result.badAttribute].
 * - Duplicate keys: the first wins. Values over [MAX_VALUE_CHARS] are dropped (inline `data:` logos).
 * - The display name is the trimmed text after the first comma outside quotes.
 */
internal object ExtinfLexer {
    const val MAX_VALUE_CHARS = 4 * 1024

    class Result(
        /** Leading duration number, or null when the line has none. */
        val duration: Double?,
        val attributes: LinkedHashMap<String, String>,
        /** Null when the line has no comma outside quotes (header lines, broken entries). */
        val name: String?,
        val badAttribute: Boolean,
        val droppedLongValue: Boolean,
    )

    /** Lexes [s] from [from] (just after `#EXTINF:`), reading a leading duration when [withDuration]. */
    fun lex(s: String, from: Int, withDuration: Boolean): Result {
        val n = s.length
        var i = from
        var duration: Double? = null
        if (withDuration) {
            while (i < n && s[i] == ' ') i++
            val start = i
            if (i < n && (s[i] == '-' || s[i] == '+')) i++
            while (i < n && (s[i].isAsciiDigit() || s[i] == '.')) i++
            if (i > start) {
                duration = parseDouble(s, start, i)
                if (duration == null) i = start
            }
        }
        val attrs = LinkedHashMap<String, String>(16)
        var bad = false
        var dropped = false
        var name: String? = null
        while (i < n) {
            val c = s[i]
            if (c == ',') {
                name = trimName(s, i + 1, n)
                break
            }
            if (c == ' ' || c == '\t') { i++; continue }
            if (!isKeyChar(c)) { i++; continue } // stray junk between attributes
            val ks = i
            while (i < n && isKeyChar(s[i])) i++
            val key = KeyIntern.lower(s, ks, i)
            var value: String
            if (i < n && s[i] == '=') {
                i++
                if (i < n && (s[i] == '"' || s[i] == '\'')) {
                    val q = s[i]
                    val close = s.indexOf(q, i + 1)
                    if (close >= 0) {
                        value = s.substring(i + 1, close)
                        i = close + 1
                    } else {
                        bad = true
                        var end = s.lastIndexOf(',')
                        if (end <= i) end = n
                        value = s.substring(i + 1, end)
                        i = end
                    }
                } else {
                    val vs = i
                    while (i < n && s[i] != ' ' && s[i] != '\t' && s[i] != ',') i++
                    value = s.substring(vs, i)
                }
            } else {
                value = ""
            }
            if (value.length > MAX_VALUE_CHARS) { dropped = true; continue }
            if (!attrs.containsKey(key)) attrs[key] = value
        }
        return Result(duration, attrs, name, bad, dropped)
    }

    private fun trimName(s: String, from: Int, to: Int): String {
        var a = from
        var b = to
        while (a < b && isTrim(s[a])) a++
        while (b > a && isTrim(s[b - 1])) b--
        return s.substring(a, b)
    }

    fun isTrim(c: Char) = c == ' ' || c == '\t' || c == ' '

    fun isKeyChar(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '-' || c == '_' || c == '.' || c == ':'

    private fun Char.isAsciiDigit() = this in '0'..'9'

    /** `-1`, `0`, `3600` without allocation; anything else via java.lang.Double (Kotlin's toDoubleOrNull screens with a regex). */
    private fun parseDouble(s: String, a: Int, b: Int): Double? {
        var i = a
        val neg = i < b && s[i] == '-'
        if (i < b && (s[i] == '-' || s[i] == '+')) i++
        if (i < b && b - i <= 15) {
            var v = 0L
            var j = i
            while (j < b && s[j] in '0'..'9') { v = v * 10 + (s[j] - '0'); j++ }
            if (j == b) return (if (neg) -v else v).toDouble()
        }
        return try { java.lang.Double.parseDouble(s.substring(a, b)) } catch (_: NumberFormatException) { null }
    }
}

/** Returns shared constants for the common attribute keys, so 200k entries don't allocate the same key strings. */
internal object KeyIntern {
    private val KNOWN: Array<String> = arrayOf(
        "tvg-id", "tvg-name", "tvg-logo", "group-title", "tvg-chno", "ch-number", "channel-number", "tvg-num",
        "tvg-shift", "tvg-type", "tvg-language", "tvg-country", "tvg-url", "url-tvg", "x-tvg-url", "url-logo",
        "catchup", "catchup-type", "catchup-source", "catchup-days", "catchup-correction", "catchup-time", "timeshift",
        "tvg-rec", "radio", "adult", "censored", "parent-code", "type", "xui-id", "xtream-id",
        "http-user-agent", "http-referrer", "http-referer", "http-origin", "http-cookie", "user-agent", "referer",
        "referrer", "origin", "cookie", "refresh", "max-conn", "tvg-epgid", "tvg-logo-small", "provider", "media",
    )
    private val byLength: Array<Array<String>> = run {
        val max = KNOWN.maxOf { it.length }
        Array(max + 1) { len -> KNOWN.filter { it.length == len }.toTypedArray() }
    }

    fun lower(s: String, a: Int, b: Int): String {
        val len = b - a
        if (len < byLength.size) {
            for (k in byLength[len]) if (s.regionMatches(a, k, 0, len, ignoreCase = true)) return k
        }
        return s.substring(a, b).lowercase(java.util.Locale.ROOT)
    }
}
