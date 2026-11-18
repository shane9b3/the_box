package app.thebox.core.xmltv

/**
 * Providers that do not send XMLTV `<new/>` write "new" into the title instead ("Chicago Fire (New)", "NEW: Chicago
 * Fire", "Chicago Fire ᴺᵉʷ"). [strip] takes that marker out so the guide can draw its own tag from
 * `ProgrammeFlags.NEW` (decision 2026-11-18).
 *
 * Only forms that cannot be part of a real title are taken, so "New Amsterdam", "Brand New Day" and "What's New
 * Scooby-Doo?" stay whole:
 * - the superscript `ᴺᵉʷ` (any case of its letters), anywhere;
 * - "new" in brackets at either end: `(New)`, `[NEW]`, `{new}`, `*New*`;
 * - a prefix "New:" / "NEW -" / "New |";
 * - a suffix "- New" / "| NEW";
 * - a last word `NEW` in capitals after a title that is not all capitals ("Chicago Fire NEW").
 *
 * Every bracket is escaped: Android's ICU regex rejects a bare `]` or `}` that the desktop JVM accepts.
 *
 * Allocation-free when there is no marker (the common case): one scan for the letter pair before any regex runs.
 */
object NewMarker {
    private const val SUPER = "ᴺᵉʷ|ᴺᴱᵂ|ᶰᵉʷ"
    private val SUPERSCRIPT = Regex("\\s*(?:$SUPER)\\s*")
    private val BRACKET_END = Regex("\\s*(?:\\(new\\)|\\[new\\]|\\{new\\}|\\*new\\*)\\s*$", RegexOption.IGNORE_CASE)
    private val BRACKET_START = Regex("^\\s*(?:\\(new\\)|\\[new\\]|\\{new\\}|\\*new\\*)\\s*[:\\-–|]?\\s*", RegexOption.IGNORE_CASE)
    private val PREFIX = Regex("^\\s*new\\s*[:\\-–|]\\s+", RegexOption.IGNORE_CASE)
    private val SUFFIX = Regex("\\s+[\\-–|]\\s*new\\s*$", RegexOption.IGNORE_CASE)
    private val CAPS_LAST = Regex("\\s+NEW$")

    /** [title] without its "new" marker, or null when it has none (or nothing else would be left). */
    fun strip(title: String): String? {
        if (!mayHold(title)) return null
        var t = title
        var found = false
        fun take(r: Regex, with: String = "") {
            val n = r.replace(t, with)
            if (n != t) { t = n; found = true }
        }
        take(SUPERSCRIPT, " ")
        take(BRACKET_END)
        take(BRACKET_START)
        take(PREFIX)
        take(SUFFIX)
        if (!found && CAPS_LAST.containsMatchIn(t)) {
            val rest = t.substring(0, t.length - 3).trimEnd()
            // "BREAKING NEWS NEW" is ambiguous in an all-capitals guide; a mixed-case title before NEW is not.
            if (rest.any { it.isLowerCase() }) { t = rest; found = true }
        }
        if (!found) return null
        t = t.trim()
        return t.takeIf { it.isNotEmpty() }
    }

    /** Cheap gate: "ne"/"NE"/"Ne" followed by w/W, or a superscript N. */
    private fun mayHold(s: String): Boolean {
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c == 'ᴺ' || c == 'ᶰ') return true
            if ((c == 'n' || c == 'N') && i + 2 < n) {
                val e = s[i + 1]
                val w = s[i + 2]
                if ((e == 'e' || e == 'E') && (w == 'w' || w == 'W')) return true
            }
            i++
        }
        return false
    }
}
