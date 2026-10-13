package app.thebox.core.source

import java.net.URLDecoder

/** An Xtream-shaped stream URL found in an M3U (protocols §1.6, §3.3; Kodi's xc regex). */
data class XtreamStreamUrl(
    val base: String,          // scheme://host[:port]
    val kind: EntryKind,       // LIVE, MOVIE or SERIES
    val username: String,      // percent-decoded
    val password: String,
    val streamId: Long,
    val extension: String?,    // "ts", "m3u8", "mp4", … or null
    val shortForm: Boolean,    // http://h/u/p/123 without /live/
) {
    companion object {
        private val LONG = Regex("""^(https?://[^/?#]+)/(live|movie|series)/([^/?#]+)/([^/?#]+)/(\d+)(?:\.([A-Za-z0-9]{2,4}))?(?:[?#].*)?$""")
        private val SHORT = Regex("""^(https?://[^/?#]+)/([^/?#]+)/([^/?#]+)/(\d+)(?:\.(ts|m3u8))?(?:[?#].*)?$""")

        /**
         * [allowShort] must only be true when the playlist as a whole is Xtream-shaped (WP-A pass 1,
         * §3.2.4): the short form also matches ordinary `http://h/hls/chan/123` URLs.
         * (reviewed 2026-10-13, blocker B6: the LONG form also matches non-Xtream CDN paths such as
         * `http://cdn/live/bbc/hd/1.m3u8` (user "bbc", pass "hd"). A parse result is only *evidence*.
         * Callers that persist anything from it (stable keys, templating, catch-up mode) must also
         * require the playlist vote, §3.2.1/§3.2.4/§3.8.1.)
         */
        fun parse(url: String, allowShort: Boolean): XtreamStreamUrl? {
            LONG.matchEntire(url)?.let { m ->
                val kind = when (m.groupValues[2].lowercase()) {
                    "movie" -> EntryKind.MOVIE
                    "series" -> EntryKind.SERIES
                    else -> EntryKind.LIVE
                }
                return XtreamStreamUrl(
                    m.groupValues[1], kind, dec(m.groupValues[3]), dec(m.groupValues[4]),
                    m.groupValues[5].toLongOrNull() ?: return null, m.groupValues[6].ifEmpty { null }, false,
                )
            }
            if (!allowShort) return null
            val m = SHORT.matchEntire(url) ?: return null
            return XtreamStreamUrl(
                m.groupValues[1], EntryKind.LIVE, dec(m.groupValues[2]), dec(m.groupValues[3]),
                m.groupValues[4].toLongOrNull() ?: return null, m.groupValues[5].ifEmpty { null }, true,
            )
        }

        private fun dec(s: String): String = try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (_: IllegalArgumentException) { s }
    }
}
