package app.thebox.core.text

import java.text.Normalizer
import java.util.Locale

/**
 * Two levels of channel-name normalisation:
 *  - [forKey]: identity. Strips case, diacritics, quality tokens and punctuation. Keeps bracket
 *    content ("HBO (East)" != "HBO (West)") and country prefixes.
 *  - [forMatch]: EPG matching. Additionally drops bracket groups and a leading ISO country prefix.
 * Both are pure and allocation-light; callers cache results per row, never per frame.
 */
object NameNormalizer {
    private val MARKS = Regex("""\p{M}+""")
    private val QUALITY = Regex(
        """(?<![\p{L}\p{N}])(?:uhd|fhd|qhd|hd|sd|4k|8k|hevc|h\.?26[45]|x26[45]|avc|2160p|1080[pi]|720p|576[pi]|50 ?fps|60 ?fps|hdr10\+?|hdr|hlg|backup|vip|raw)(?![\p{L}\p{N}])""",
    )
    private val BRACKETS = Regex("""\([^)]*\)|\[[^\]]*\]|\{[^}]*\}""") // `]`/`}` escaped: Android ICU regex rejects a bare `}`
    private val NON_WORD = Regex("""[^\p{L}\p{N}+]+""")
    private val PREFIX = Regex("""^\s*\|?\s*([a-z]{2})\s*[:|\-–]\s*(?=\S)""")
    private val TVG_COUNTRY = Regex("""\.([a-z]{2})(?:@|$)""")
    private val ISO2: Set<String> = Locale.getISOCountries().mapTo(HashSet()) { it.lowercase(Locale.ROOT) } + "uk"

    fun fold(s: String): String {
        // ASCII fast path (most channel names): no NFKD, no regex. Behaviour-identical for ASCII input.
        var ascii = true
        for (i in s.indices) if (s[i].code >= 0x80) { ascii = false; break }
        if (ascii) return s.lowercase(Locale.ROOT)
        return MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFKD), "").lowercase(Locale.ROOT)
    }

    fun forKey(name: String): String {
        val folded = fold(name)
        val out = collapse(NON_WORD.replace(QUALITY.replace(folded, " "), " "))
        if (out.isNotEmpty()) return out
        return collapse(NON_WORD.replace(folded, " ")).ifEmpty { folded.trim() }
    }

    fun forMatch(name: String): String {
        var s = fold(name)
        PREFIX.find(s)?.let { m -> if (m.groupValues[1] in ISO2) s = s.substring(m.range.last + 1) }
        s = QUALITY.replace(BRACKETS.replace(s, " "), " ")
        return collapse(NON_WORD.replace(s, " ")).ifEmpty { forKey(name) }
    }

    /**
     * Group identity (reviewed 2026-10-13, blocker B5): case, diacritics and punctuation only. Quality
     * tokens and brackets are KEPT, because "Sports HD"/"Sports SD" and "UK HD"/"UK SD" are different
     * provider groups (forKey folded both pairs together in the review probe). Persisted via group_key: frozen after M1.
     */
    fun forGroup(name: String): String {
        val folded = fold(name)
        return collapse(NON_WORD.replace(folded, " ")).ifEmpty { folded.trim() }
    }

    fun compact(normalized: String): String = normalized.replace(" ", "")

    fun tokens(normalized: String): List<String> = if (normalized.isEmpty()) emptyList() else normalized.split(' ')

    /** Lower-case ISO-3166 alpha-2 hint ("gb" folded to "uk") from a name prefix or an iptv-org tvg-id. */
    fun countryHint(name: String?, tvgId: String?): String? {
        val fromName = name?.let { PREFIX.find(fold(it))?.groupValues?.get(1) }?.takeIf { it in ISO2 }
        val fromId = tvgId?.let { TVG_COUNTRY.find(it.lowercase(Locale.ROOT))?.groupValues?.get(1) }?.takeIf { it in ISO2 }
        return (fromName ?: fromId)?.let { if (it == "gb") "uk" else it }
    }

    /** Trim and collapse runs of spaces in one pass (no regex; this ran per row in the M3U import). */
    private fun collapse(s: String): String {
        val sb = StringBuilder(s.length)
        var pendingSpace = false
        for (c in s) {
            if (c == ' ') {
                if (sb.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) { sb.append(' '); pendingSpace = false }
                sb.append(c)
            }
        }
        return sb.toString()
    }
}
