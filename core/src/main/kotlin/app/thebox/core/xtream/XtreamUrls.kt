package app.thebox.core.xtream

/**
 * Xtream URL building with the §3.7.5 encoding rules (docs/design/m1-data-layer.md; protocols §3.1, §3.3).
 *
 * - The base is normalised: trimmed, `http://` added when there is no scheme, any path, query or fragment
 *   after `host[:port]` dropped (a pasted `…/player_api.php` / `get.php` URL is reduced to its origin).
 * - Username and password in **path** segments: everything outside RFC 3986 `pchar` is percent-encoded
 *   (`/ ? # [ ] % space`, non-ASCII as UTF-8 …); `@ & + = : ! $ ' ( ) * , ;` stay literal.
 * - **Query** values: everything outside the unreserved set is encoded (`@` → `%40`, `+` → `%2B`, `&` → `%26`).
 */
class XtreamUrls(base: String, private val username: String, private val password: String) {
    val base: String = normalizeBase(base)

    private val u = query(username)
    private val p = query(password)
    private val uPath = pathSegment(username)
    private val pPath = pathSegment(password)

    fun playerApi(action: String?, params: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder(base).append("/player_api.php?username=").append(u).append("&password=").append(p)
        if (action != null) sb.append("&action=").append(query(action))
        for ((k, v) in params) sb.append('&').append(query(k)).append('=').append(query(v))
        return sb.toString()
    }

    fun getPhp(type: String = "m3u_plus", output: String = "ts"): String =
        "$base/get.php?username=$u&password=$p&type=${query(type)}&output=${query(output)}"

    fun xmltv(): String = "$base/xmltv.php?username=$u&password=$p"

    fun live(streamId: Long, ext: String): String = "$base/live/$uPath/$pPath/$streamId${dotExt(ext)}"

    fun movie(streamId: Long, ext: String): String = "$base/movie/$uPath/$pPath/$streamId${dotExt(ext)}"

    fun episode(episodeId: Long, ext: String): String = "$base/series/$uPath/$pPath/$episodeId${dotExt(ext)}"

    /**
     * `{base}/timeshift/{u}/{p}/{durationMin}/{start}/{id}.{ext}`; [start] is already formatted in the panel zone
     * (`yyyy-MM-dd:HH-mm` or `yyyy-MM-dd:HH-mm-ss`).
     */
    fun timeshiftPath(durationMin: Long, start: String, streamId: Long, ext: String): String =
        "$base/timeshift/$uPath/$pPath/$durationMin/${pathSegment(start)}/$streamId${dotExt(ext)}"

    /** `{base}/streaming/timeshift.php?username=&password=&stream=&start=&duration=` (panel-zone [start]). */
    fun timeshiftPhp(streamId: Long, start: String, durationMin: Long): String =
        "$base/streaming/timeshift.php?username=$u&password=$p&stream=$streamId&start=${query(start, keepColon = true)}&duration=$durationMin"

    companion object {
        private const val HEX = "0123456789ABCDEF"

        /** Trim, add `http://`, keep `scheme://host[:port]` only. */
        fun normalizeBase(raw: String): String {
            var s = raw.trim()
            if (!Regex("^[A-Za-z][A-Za-z0-9+.\\-]*://").containsMatchIn(s)) s = "http://$s"
            val schemeEnd = s.indexOf("://") + 3
            var end = s.length
            for (i in schemeEnd until s.length) {
                val c = s[i]
                if (c == '/' || c == '?' || c == '#') {
                    end = i
                    break
                }
            }
            val scheme = s.substring(0, schemeEnd).lowercase()
            return scheme + s.substring(schemeEnd, end)
        }

        private fun dotExt(ext: String): String {
            val e = ext.trim().trimStart('.')
            return if (e.isEmpty()) "" else ".$e"
        }

        private fun isUnreserved(c: Char): Boolean =
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~'

        private fun isPchar(c: Char): Boolean = isUnreserved(c) || c in "!$&'()*+,;=:@"

        /** Percent-encodes everything outside RFC 3986 `pchar`, as UTF-8. */
        fun pathSegment(s: String): String = encode(s) { isPchar(it) }

        /** Percent-encodes everything outside the unreserved set, as UTF-8. */
        fun query(s: String, keepColon: Boolean = false): String = encode(s) { isUnreserved(it) || (keepColon && it == ':') }

        private inline fun encode(s: String, keep: (Char) -> Boolean): String {
            var plain = true
            for (c in s) if (c.code >= 0x80 || !keep(c)) { plain = false; break }
            if (plain) return s
            val sb = StringBuilder(s.length + 16)
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xFF)
                if (c < 0x80 && keep(c.toChar())) {
                    sb.append(c.toChar())
                } else {
                    sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
                }
            }
            return sb.toString()
        }
    }
}
