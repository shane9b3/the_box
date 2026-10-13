package app.thebox.core.m3u

import java.util.Locale

/**
 * Canonical HTTP header names and the merge order (docs/design/m1-data-layer.md §3.4 M10):
 * pipe > `#EXTVLCOPT`/`#EXTHTTP`/`#KODIPROP …headers` > EXTINF attributes. The playlist-level UA and the
 * app default are applied at play time by M2, not here.
 */
internal object HeaderPrecedence {
    private val CANONICAL: Map<String, String> = mapOf(
        "user-agent" to "User-Agent", "http-user-agent" to "User-Agent",
        "referer" to "Referer", "referrer" to "Referer", "http-referrer" to "Referer", "http-referer" to "Referer",
        "origin" to "Origin", "http-origin" to "Origin",
        "cookie" to "Cookie", "http-cookie" to "Cookie",
    )

    /** The attribute / option names that are header aliases (lower case). */
    val ALIASES: Set<String> = CANONICAL.keys

    /** Canonical name for an alias; other names are kept as written. */
    fun canonical(name: String): String = CANONICAL[name.lowercase(Locale.ROOT)] ?: name

    /** True when [name] (any case) is one of the header aliases. */
    fun isAlias(name: String): Boolean = CANONICAL.containsKey(name.lowercase(Locale.ROOT))

    /**
     * Case-insensitive header accumulator. Layers are applied lowest first; a later [put] overrides an
     * earlier one with the same name (any case), keeping the canonical spelling.
     */
    class Builder {
        private var map: LinkedHashMap<String, Pair<String, String>>? = null

        fun put(name: String, value: String) {
            val canon = canonical(name.trim())
            if (canon.isEmpty()) return
            val m = map ?: LinkedHashMap<String, Pair<String, String>>(4).also { map = it }
            val k = canon.lowercase(Locale.ROOT)
            m.remove(k)
            m[k] = canon to value
        }

        fun putAll(headers: Map<String, String>) { for ((k, v) in headers) put(k, v) }

        fun build(): Map<String, String> {
            val m = map ?: return emptyMap()
            val out = LinkedHashMap<String, String>(m.size * 2)
            for ((_, p) in m) out[p.first] = p.second
            return out
        }
    }
}
