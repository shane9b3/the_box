package app.thebox.core.xtream

import app.thebox.core.source.XtreamStreamUrl

/**
 * "Paste a get.php URL → Xtream" (§3.10) and the login on Xtream-shaped M3U playlists. Accepts
 * `get.php`, `player_api.php`, `panel_api.php`, `xmltv.php` and `enigma2.php` URLs with `username`/`password`
 * query parameters, and long-form stream URLs (`/live|movie|series/u/p/id.ext`).
 *
 * Query values are percent-decoded once; `+` is kept literal (pasted URLs often carry raw passwords with `+`).
 * The base is `scheme://host[:port]` (see [XtreamUrls.normalizeBase]).
 */
object XtreamLoginUrl {
    private val PHP = Regex("""^(https?://[^/?#\s]+)(?:/[^?#\s]*)?/(get|player_api|panel_api|xmltv|enigma2)\.php(?:\?([^#]*))?(?:#.*)?$""", RegexOption.IGNORE_CASE)

    /** get.php / player_api.php / xmltv.php / stream URL → login input, or null. */
    fun parse(url: String): XtreamLoginInput? {
        var s = url.trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "http://$s"
        val m = PHP.matchEntire(s)
        if (m != null) {
            val params = parseQuery(m.groupValues[3])
            val user = params["username"]?.takeIf { it.isNotEmpty() } ?: return null
            val pass = params["password"] ?: return null
            val output = if (m.groupValues[2].equals("get", ignoreCase = true)) params["output"]?.takeIf { it.isNotEmpty() } else null
            return XtreamLoginInput(XtreamUrls.normalizeBase(m.groupValues[1]), user, pass, output)
        }
        val stream = XtreamStreamUrl.parse(s, allowShort = false) ?: return null
        val output = when (stream.extension?.lowercase()) {
            "m3u8" -> "m3u8"
            "ts" -> "ts"
            else -> null
        }
        return XtreamLoginInput(XtreamUrls.normalizeBase(stream.base), stream.username, stream.password, output)
    }

    private fun parseQuery(q: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (q.isEmpty()) return out
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = (if (eq < 0) pair else pair.substring(0, eq)).lowercase()
            val v = if (eq < 0) "" else percentDecode(pair.substring(eq + 1))
            out.putIfAbsent(k, v)
        }
        return out
    }

    /** Percent-decodes as UTF-8; `+` stays literal; malformed escapes are kept as written. */
    internal fun percentDecode(s: String): String {
        if (s.indexOf('%') < 0) return s
        val bytes = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val h = Character.digit(s[i + 1], 16)
                val l = Character.digit(s[i + 2], 16)
                if (h >= 0 && l >= 0) {
                    bytes.write(h * 16 + l)
                    i += 3
                    continue
                }
            }
            val cp = s.codePointAt(i)
            val enc = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
            bytes.write(enc, 0, enc.size)
            i += Character.charCount(cp)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
}
