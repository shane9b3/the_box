package app.thebox.core.xmltv

/**
 * Ranks `lang` attributes against the preferred languages: index in the list (exact tag first, then the
 * primary subtag, case-insensitive), else [UNRANKED]. Callers keep the lowest rank and, on ties, the
 * first element (§3.5.4).
 */
class LangPicker(preferred: List<String>) {
    private val tags: Array<String> = preferred.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toTypedArray()

    val isEmpty: Boolean get() = tags.isEmpty()

    fun rank(lang: String?): Int {
        if (lang == null || tags.isEmpty()) return UNRANKED
        for (i in tags.indices) if (tags[i].equals(lang, ignoreCase = true)) return i
        val primaryEnd = primaryEnd(lang)
        for (i in tags.indices) {
            val t = tags[i]
            if (primaryEnd(t) == primaryEnd && t.regionMatches(0, lang, 0, primaryEnd, ignoreCase = true)) return i
        }
        return UNRANKED
    }

    /** Stable order: preferred languages first. */
    fun <T> order(items: List<T>, lang: (T) -> String?): List<T> =
        if (tags.isEmpty() || items.size < 2) {
            items
        } else {
            items.withIndex().sortedWith(compareBy({ rank(lang(it.value)) }, { it.index })).map { it.value }
        }

    private fun primaryEnd(s: String): Int {
        for (i in s.indices) if (s[i] == '-' || s[i] == '_') return i
        return s.length
    }

    companion object {
        const val UNRANKED = Int.MAX_VALUE
        val NONE = LangPicker(emptyList())
    }
}
