package app.thebox.core.playback.resolve

import java.util.Locale

/** Case-insensitive header precedence and UA defaulting (§3.2.5). */
object HeaderPolicy {
    const val USER_AGENT = "User-Agent"

    private val CANONICAL: Map<String, String> = mapOf(
        "user-agent" to USER_AGENT, "http-user-agent" to USER_AGENT,
        "referer" to "Referer", "referrer" to "Referer", "http-referrer" to "Referer", "http-referer" to "Referer",
        "origin" to "Origin", "http-origin" to "Origin",
        "cookie" to "Cookie", "http-cookie" to "Cookie",
    )

    /** Headers the transport owns; dropped from every user-supplied map (lower case). */
    private val DROPPED: Set<String> = setOf("range", "accept-encoding", "host", "connection", "icy-metadata")

    /** Canonical spelling for the four well-known headers (and their M3U aliases); other names as written. */
    fun canonical(name: String): String = CANONICAL[name.trim().lowercase(Locale.ROOT)] ?: name.trim()

    /**
     * Merges, highest priority first (§3.2.5):
     * 1. [channel] headers, 2. [secret] playlist headers, 3. [playlist] `extra_headers_json`,
     * 4. `User-Agent` = [userAgentOverride] (only when the channel sets no UA), 5. [playlistUserAgent], 6. [defaultUserAgent].
     * Keys come out canonical-cased; for other names the highest-priority spelling wins. Transport-owned headers are
     * dropped. Values are returned as given (still templated); the caller expands them.
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
