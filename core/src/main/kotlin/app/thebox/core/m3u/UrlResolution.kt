package app.thebox.core.m3u

import java.net.URI
import java.util.Locale

/** Scheme checks and relative-URL resolution (docs/design/m1-data-layer.md §3.4 M20–M22). */
internal object UrlResolution {
    val ACCEPTED_SCHEMES: Set<String> = setOf("http", "https", "rtmp", "rtmps", "rtmpt", "rtmpe", "rtsp", "udp", "rtp", "mms")

    /** Lower-case scheme of [url] (`[A-Za-z][A-Za-z0-9+.-]*:`), or null when it has none. */
    fun scheme(url: String): String? {
        if (url.isEmpty() || !url[0].isAsciiLetter()) return null
        for (i in 1 until url.length) {
            val c = url[i]
            // "C:\path" yields scheme "c", which is unknown and therefore skipped: the intended result.
            if (c == ':') return url.substring(0, i).lowercase(Locale.ROOT)
            if (!(c.isAsciiLetter() || c in '0'..'9' || c == '+' || c == '.' || c == '-')) return null
        }
        return null
    }

    /** Resolves a relative [ref] against [base] (RFC 3986). Returns null when it cannot be resolved. */
    fun resolve(base: String?, ref: String): String? {
        if (base == null) return null
        return try {
            val b = URI(escape(base))
            if (!b.isAbsolute) return null
            val r = b.resolve(URI(escape(ref)))
            r.toString().takeIf { scheme(it) != null }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * M22: a relative logo becomes `urlLogo + value` (+ `.png` when the value has no extension, Kodi),
     * or is resolved against [base] when there is no `url-logo`. Absolute and `data:` logos are unchanged.
     */
    fun logo(value: String?, urlLogo: String?, base: String?): String? {
        if (value.isNullOrEmpty()) return null
        if (scheme(value) != null) return value
        if (value.startsWith("//")) return resolve(base, value) ?: "http:$value"
        if (!urlLogo.isNullOrEmpty()) {
            val withExt = if (hasExtension(value)) value else "$value.png"
            return urlLogo + withExt
        }
        return resolve(base, value) ?: value
    }

    private fun hasExtension(path: String): Boolean {
        val q = path.indexOfFirst { it == '?' || it == '#' }
        val p = if (q >= 0) path.substring(0, q) else path
        val dot = p.lastIndexOf('.')
        return dot > p.lastIndexOf('/') && dot < p.length - 1
    }

    /** Last path segment, percent-decoded, extension removed (M7/M23 name fallback). */
    fun lastSegmentName(url: String): String {
        var end = url.length
        val q = url.indexOfFirst { it == '?' || it == '#' }
        if (q >= 0) end = q
        while (end > 0 && url[end - 1] == '/') end--
        val slash = url.lastIndexOf('/', end - 1)
        var seg = url.substring(slash + 1, end)
        seg = PipeHeaders.percentDecode(seg)
        val dot = seg.lastIndexOf('.')
        if (dot > 0) seg = seg.substring(0, dot)
        return seg.ifEmpty { url }
    }

    private fun escape(s: String): String {
        if (s.none { it == ' ' || it == '|' || it == '"' || it == '<' || it == '>' || it == '{' || it == '}' || it == '^' || it == '`' || it == '\\' }) return s
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            ' ' -> sb.append("%20")
            '|' -> sb.append("%7C")
            '"' -> sb.append("%22")
            '<' -> sb.append("%3C")
            '>' -> sb.append("%3E")
            '{' -> sb.append("%7B")
            '}' -> sb.append("%7D")
            '^' -> sb.append("%5E")
            '`' -> sb.append("%60")
            '\\' -> sb.append("%5C")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
}
