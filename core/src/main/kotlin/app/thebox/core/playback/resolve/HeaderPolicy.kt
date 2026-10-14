package app.thebox.core.playback.resolve

import java.util.Locale

/**
 * Case-insensitive header precedence and UA defaulting (§3.2.5), and header validation (code review 2026-10-14, N-S1):
 * names must be RFC 7230 tokens and values printable ASCII (0x20–0x7E, plus tab). Anything else (CR/LF, NUL, other
 * control characters, non-ASCII from a percent-decoded pipe header or a raw password) would make OkHttp throw an
 * `IllegalArgumentException` whose message carries the value, so such a header is dropped, never sent or repaired.
 */
object HeaderPolicy {
    const val USER_AGENT = "User-Agent"

    private val CANONICAL: Map<String, String> = mapOf(
        "user-agent" to USER_AGENT, "http-user-agent" to USER_AGENT,
        "referer" to "Referer", "referrer" to "Referer", "http-referrer" to "Referer", "http-referer" to "Referer",
        "origin" to "Origin", "http-origin" to "Origin",
        "cookie" to "Cookie", "http-cookie" to "Cookie",
    )

    /** Headers the transport owns; dropped from every user-supplied map (lower case). */
    private val DROPPED: Set<String> = setOf(
        "range", "accept-encoding", "host", "connection", "icy-metadata",
        // (code review 2026-10-14, Later N-16) framing headers OkHttp owns.
        "content-length", "transfer-encoding", "te", "upgrade", "expect",
    )

    private const val TCHAR_SYMBOLS = "!#$%&'*+-.^_`|~"

    /** RFC 7230 `token`: the characters a header name may contain. */
    fun isValidName(name: String): Boolean {
        if (name.isEmpty()) return false
        for (c in name) {
            val ok = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in TCHAR_SYMBOLS
            if (!ok) return false
        }
        return true
    }

    /** Printable ASCII and tab only (what OkHttp's `Headers.checkValue` accepts, minus obs-text). */
    fun isValidValue(value: String): Boolean {
        for (c in value) if (c != '\t' && (c < ' ' || c > '~')) return false
        return true
    }

    /**
     * Drops every header whose name or value is invalid ([isValidName], [isValidValue]). Returns the kept headers (order
     * preserved) and the **names** of the dropped ones (never a value: callers log the names only).
     */
    fun sanitize(headers: Map<String, String>): Pair<Map<String, String>, List<String>> {
        var dropped: MutableList<String>? = null
        for ((k, v) in headers) if (!isValidName(k) || !isValidValue(v)) (dropped ?: mutableListOf<String>().also { dropped = it }) += k
        val names = dropped ?: return headers to emptyList()
        val kept = LinkedHashMap<String, String>(headers.size)
        for ((k, v) in headers) if (k !in names) kept[k] = v
        return kept to names.map(::printableName)
    }

    /** A dropped header's name for a log line: invalid characters replaced, so the log line itself stays clean. */
    private fun printableName(name: String): String = buildString(name.length) {
        for (c in name) append(if (c in '!'..'~') c else '?')
    }

    /** Canonical spelling for the four well-known headers (and their M3U aliases); other names as written. */
    fun canonical(name: String): String = CANONICAL[name.trim().lowercase(Locale.ROOT)] ?: name.trim()

    /**
     * Merges, highest priority first (§3.2.5):
     * 1. [channel] headers, 2. [secret] playlist headers, 3. [playlist] `extra_headers_json`,
     * 4. `User-Agent` = [userAgentOverride] (only when the channel sets no UA), 5. [playlistUserAgent], 6. [defaultUserAgent].
     * Keys come out canonical-cased; for other names the highest-priority spelling wins. Transport-owned headers are
     * dropped. Values are returned as given (still templated); the caller expands them and then runs [sanitize] on the
     * final map (a raw password can only be checked after expansion).
     */
    fun merge(
        playlist: Map<String, String>,
        channel: Map<String, String>,
        playlistUserAgent: String?,
        defaultUserAgent: String,
        userAgentOverride: String? = null,
        secret: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val seen = HashSet<String>()
        fun offer(name: String, value: String) {
            val canon = canonical(name)
            if (canon.isEmpty()) return
            val lower = canon.lowercase(Locale.ROOT)
            if (lower in DROPPED || !seen.add(lower)) return
            out[canon] = value
        }
        for ((k, v) in channel) offer(k, v)
        val channelSetUa = USER_AGENT.lowercase(Locale.ROOT) in seen
        for ((k, v) in secret) offer(k, v)
        for ((k, v) in playlist) offer(k, v)
        if (!channelSetUa && !userAgentOverride.isNullOrBlank()) offer(USER_AGENT, userAgentOverride)
        if (!playlistUserAgent.isNullOrBlank()) offer(USER_AGENT, playlistUserAgent)
        offer(USER_AGENT, defaultUserAgent)
        return out
    }
}
