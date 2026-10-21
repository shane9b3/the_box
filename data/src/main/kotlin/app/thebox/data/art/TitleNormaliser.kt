package app.thebox.data.art

import java.util.Locale

/**
 * Turns an EPG title into a search query and a cache key (docs/decisions/2026-10-18-tmdb-programme-posters.md).
 * Broadcast titles carry markers the service does not know: "New: ", "(2019)", "(R)", ": Episode 3", "- S2 E4".
 */
internal object TitleNormaliser {
    private val LEADING = Regex("^(?:new|live|premiere|repeat|film)\\s*[:\\-–]\\s*", RegexOption.IGNORE_CASE)
    private val TRAILING_PARENS = Regex("\\s*\\((?:\\d{4}|r|hd|uhd|4k|new|live|repeat|s\\d+\\s*,?\\s*e(?:p)?\\s*\\d+)\\)\\s*$", RegexOption.IGNORE_CASE)
    private val EPISODE = Regex("\\s*[:\\-–]\\s*(?:s\\d+\\s*,?\\s*e(?:p)?\\.?\\s*\\d+|series\\s+\\d+|season\\s+\\d+|episode\\s+\\d+|ep\\.?\\s*\\d+|part\\s+\\d+)\\b.*$", RegexOption.IGNORE_CASE)
    private val SEPARATOR = Regex("\\s+[-–]\\s+|:\\s+")
    private val WS = Regex("\\s+")

    /** The query text, or null when nothing searchable is left. */
    fun clean(title: String): String? {
        var t = title.trim()
        t = t.replace(LEADING, "")
        t = t.replace(TRAILING_PARENS, "")
        t = t.replace(EPISODE, "")
        t = t.replace(WS, " ").trim()
        return t.takeIf { it.length >= 2 }
    }

    fun key(clean: String): String = clean.lowercase(Locale.ROOT)

    /** The part before a title/subtitle separator ("Doctor Who: The Giggle" → "Doctor Who"), for a second attempt. */
    fun head(clean: String): String? {
        val m = SEPARATOR.find(clean) ?: return null
        val h = clean.substring(0, m.range.first).trim()
        return h.takeIf { it.length >= 2 && it != clean }
    }
}
