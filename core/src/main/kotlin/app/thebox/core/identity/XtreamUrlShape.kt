package app.thebox.core.identity

import app.thebox.core.source.EntryKind
import app.thebox.core.source.XtreamStreamUrl
import java.net.URLDecoder

/**
 * Hand-written equivalent of [XtreamStreamUrl.parse] for the per-row hot paths (§6.6: no regex per row).
 * It accepts exactly the same language as the contract's two regexes (a parity test enforces it) and
 * additionally reports where the two credential segments sit, so the templater can replace them in place.
 */
internal class XtreamUrlShape(
    val base: String,
    val kind: EntryKind,
    val username: String,
    val password: String,
    val streamId: Long,
    val extension: String?,
    val shortForm: Boolean,
    /** [userStart, userEnd) and [passStart, passEnd) in the original URL (raw, still percent-encoded). */
    val userStart: Int,
    val userEnd: Int,
    val passStart: Int,
    val passEnd: Int,
) {
    fun toContract() = XtreamStreamUrl(base, kind, username, password, streamId, extension, shortForm)

    fun matches(username: String, password: String) = this.username == username && this.password == password

    companion object {
        fun parse(url: String, allowShort: Boolean): XtreamUrlShape? {
            val n = url.length
            val schemeEnd = when {
                url.startsWith("http://") -> 7
                url.startsWith("https://") -> 8
                else -> return null
            }
            var i = schemeEnd
            while (i < n && !isSegStop(url[i])) i++
            if (i == schemeEnd || i >= n || url[i] != '/') return null
            val base = url.substring(0, i)
            val s1 = i + 1
            val e1 = segEnd(url, s1)
            if (e1 == s1 || e1 >= n || url[e1] != '/') return null
            // Long form: /(live|movie|series)/u/p/<id>[.ext]
            val kind = when {
                e1 - s1 == 4 && url.startsWith("live", s1) -> EntryKind.LIVE
                e1 - s1 == 5 && url.startsWith("movie", s1) -> EntryKind.MOVIE
                e1 - s1 == 6 && url.startsWith("series", s1) -> EntryKind.SERIES
                else -> null
            }
            if (kind != null) {
                longForm(url, base, kind, e1 + 1)?.let { return it }
            }
            if (!allowShort) return null
            // Short form: /u/p/<id>[.ts|.m3u8]
            val s2 = e1 + 1
            val e2 = segEnd(url, s2)
            if (e2 == s2 || e2 >= n || url[e2] != '/') return null
            val tail = tail(url, e2 + 1, shortExt = true) ?: return null
            return XtreamUrlShape(
                base, EntryKind.LIVE, dec(url.substring(s1, e1)), dec(url.substring(s2, e2)), tail.first, tail.second, true,
                s1, e1, s2, e2,
            )
        }

        private fun longForm(url: String, base: String, kind: EntryKind, s2: Int): XtreamUrlShape? {
            val n = url.length
            val e2 = segEnd(url, s2)
            if (e2 == s2 || e2 >= n || url[e2] != '/') return null
            val s3 = e2 + 1
            val e3 = segEnd(url, s3)
            if (e3 == s3 || e3 >= n || url[e3] != '/') return null
            val tail = tail(url, e3 + 1, shortExt = false) ?: return null
            return XtreamUrlShape(
                base, kind, dec(url.substring(s2, e2)), dec(url.substring(s3, e3)), tail.first, tail.second, false,
                s2, e2, s3, e3,
            )
        }

        /** `(\d+)(?:\.ext)?(?:[?#].*)?$` from [from]. */
        private fun tail(url: String, from: Int, shortExt: Boolean): Pair<Long, String?>? {
            val n = url.length
            var i = from
            while (i < n && url[i] in '0'..'9') i++
            if (i == from) return null
            // Same result as substring().toLongOrNull(): null on overflow.
            var id = 0L
            for (k in from until i) {
                val d = url[k] - '0'
                if (id > (Long.MAX_VALUE - d) / 10) return null
                id = id * 10 + d
            }
            var ext: String? = null
            if (i < n && url[i] == '.') {
                val es = i + 1
                var j = es
                while (j < n && isAlnum(url[j])) j++
                val e = url.substring(es, j)
                val ok = if (shortExt) e == "ts" || e == "m3u8" else e.length in 2..4
                if (!ok) return null
                ext = e
                i = j
            }
            if (i < n && url[i] != '?' && url[i] != '#') return null
            return id to ext
        }

        private fun segEnd(url: String, from: Int): Int {
            var i = from
            while (i < url.length && !isSegStop(url[i])) i++
            return i
        }

        private fun isSegStop(c: Char) = c == '/' || c == '?' || c == '#'

        private fun isAlnum(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

        private fun dec(s: String): String {
            if (s.indexOf('%') < 0 && s.indexOf('+') < 0) return s
            return try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (_: IllegalArgumentException) { s }
        }
    }
}
