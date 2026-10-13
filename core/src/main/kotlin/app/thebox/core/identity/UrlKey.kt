package app.thebox.core.identity

import app.thebox.core.m3u.PipeHeaders
import app.thebox.core.refresh.DetectedCredentials
import java.util.Locale

/**
 * Host- and credential-agnostic URL key (docs/design/m1-data-layer.md §3.2.2). Returns the part after
 * `url:`, or null when the URL gives no usable identity (the caller falls through to rule 4).
 */
object UrlKey {
    private val MULTICAST = setOf("udp", "rtp", "igmp")
    private val DROP_QUERY = setOf(
        "token", "auth", "key", "sig", "signature", "expires", "exp", "e", "st", "hash", "wmsauthsign", "hdnts", "hdnea",
        "username", "password", "user", "pass", "u", "p", "session", "sid", "t", "ts", "time", "nonce",
    )
    private val GENERIC = setOf(
        "index.m3u8", "playlist.m3u8", "mono.m3u8", "video.m3u8", "chunklist.m3u8", "mpegts", "live", "stream", "play", "index.html",
    )

    fun of(url: String, credentials: DetectedCredentials? = null): String? {
        val sep = url.indexOf("://")
        if (sep <= 0) return null
        val scheme = url.substring(0, sep).lowercase(Locale.ROOT)
        if (!scheme[0].isLetter() || scheme.any { !(it.isLetterOrDigit() || it == '+' || it == '.' || it == '-') }) return null
        val authStart = sep + 3
        var authEnd = authStart
        while (authEnd < url.length && url[authEnd] != '/' && url[authEnd] != '?' && url[authEnd] != '#') authEnd++
        if (scheme in MULTICAST) {
            val auth = url.substring(authStart, authEnd)
            val hostPort = auth.substring(auth.lastIndexOf('@') + 1)
            return if (hostPort.isEmpty()) null else "$scheme/$hostPort"
        }
        var pathEnd = authEnd
        while (pathEnd < url.length && url[pathEnd] != '?' && url[pathEnd] != '#') pathEnd++
        val hash = url.indexOf('#', pathEnd)
        val queryStart = if (pathEnd < url.length && url[pathEnd] == '?') pathEnd + 1 else -1
        val queryEnd = if (hash >= 0) hash else url.length

        // B3: drop the credential segments of any Xtream-shaped URL, whatever the vote said.
        val xc = if (scheme == "http" || scheme == "https") XtreamUrlShape.parse(url, allowShort = true) else null

        // Keep the last two surviving segments: scan backwards.
        var last: String? = null
        var prev: String? = null
        var end = pathEnd
        var i = pathEnd - 1
        while (i >= authEnd - 1 && i >= 0) {
            if (i == authEnd - 1 || url[i] == '/') {
                val s = i + 1
                if (s < end && !isCredential(url, s, end, xc, credentials)) {
                    val seg = upperEscapes(url.substring(s, end))
                    if (last == null) last = seg else { prev = seg; break }
                }
                end = i
            }
            i--
        }
        val path = when {
            last == null -> ""
            prev == null -> last
            else -> "$prev/$last"
        }

        var query = ""
        if (queryStart in 0 until queryEnd) {
            val kept = ArrayList<String>(2)
            for (pair in url.substring(queryStart, queryEnd).split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val k = (if (eq >= 0) pair.substring(0, eq) else pair).lowercase(Locale.ROOT)
                if (k in DROP_QUERY) continue
                kept += upperEscapes(pair)
            }
            if (kept.isNotEmpty()) {
                kept.sort()
                query = "?" + kept.joinToString("&")
            }
        }
        if (path.isEmpty() && query.isEmpty()) return null
        if (query.isEmpty() && prev == null && last != null && last.lowercase(Locale.ROOT) in GENERIC) return null
        return path + query
    }

    private fun isCredential(url: String, s: Int, e: Int, xc: XtreamUrlShape?, cred: DetectedCredentials?): Boolean {
        if (xc != null && ((s == xc.userStart && e == xc.userEnd) || (s == xc.passStart && e == xc.passEnd))) return true
        if (cred == null) return false
        val seg = url.substring(s, e)
        if (seg == cred.username || seg == cred.password) return true
        if (seg.indexOf('%') >= 0) {
            val d = PipeHeaders.percentDecode(seg)
            return d == cred.username || d == cred.password
        }
        return false
    }

    /** `%2f` → `%2F`; everything else unchanged. */
    private fun upperEscapes(s: String): String {
        if (s.indexOf('%') < 0) return s
        val sb = StringBuilder(s)
        var i = s.indexOf('%')
        while (i >= 0 && i + 2 < sb.length) {
            sb.setCharAt(i + 1, sb[i + 1].uppercaseChar())
            sb.setCharAt(i + 2, sb[i + 2].uppercaseChar())
            i = s.indexOf('%', i + 3)
        }
        return sb.toString()
    }
}
