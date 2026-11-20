package app.thebox.core.xmltv

/**
 * Providers that do not send XMLTV `<live/>` write "live" into the title instead ("NHL Hockey ᴸᶦᵛᵉ",
 * "NHL Hockey (Live)"). [strip] takes that marker out so the guide can draw its own tag from `ProgrammeFlags.LIVE`
 * (decision 2026-11-19), as [NewMarker] does for "new".
 *
 * "Live" is a common word in real titles ("Saturday Night Live", "Live: PGA Tour", "Live with Kelly and Mark"), so
 * fewer forms are taken than for "new": no bare prefix, suffix or last word.
 * - the superscript `ᴸᶦᵛᵉ` (and its `ᴸᴵⱽᴱ` / `ˡⁱᵛᵉ` spellings), anywhere;
 * - "live" in brackets at either end: `(Live)`, `[LIVE]`, `{live}`, `*Live*`.
 *
 * Every bracket is escaped: Android's ICU regex rejects a bare `]` or `}` that the desktop JVM accepts.
 *
 * Allocation-free when there is no marker (the common case): one scan before any regex runs.
 */
object LiveMarker {
    private const val SUPER = "ᴸᶦᵛᵉ|ᴸᴵⱽᴱ|ˡⁱᵛᵉ|ᴸⁱᵛᵉ"
    private val SUPERSCRIPT = Regex("\\s*(?:$SUPER)\\s*")
    private val BRACKET_END = Regex("\\s*(?:\\(live\\)|\\[live\\]|\\{live\\}|\\*live\\*)\\s*$", RegexOption.IGNORE_CASE)
    private val BRACKET_START = Regex("^\\s*(?:\\(live\\)|\\[live\\]|\\{live\\}|\\*live\\*)\\s*[:\\-–|]?\\s*", RegexOption.IGNORE_CASE)

    /** [title] without its "live" marker, or null when it has none (or nothing else would be left). */
    fun strip(title: String): String? {
        if (!mayHold(title)) return null
        var t = SUPERSCRIPT.replace(title, " ")
        t = BRACKET_END.replace(t, "")
        t = BRACKET_START.replace(t, "")
        t = t.trim()
        return t.takeIf { it.isNotEmpty() && it != title.trim() }
    }

    /** Cheap gate: a superscript L, or an opening bracket or star followed by l/L. */
    private fun mayHold(s: String): Boolean {
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c == 'ᴸ' || c == 'ˡ') return true
            if ((c == '(' || c == '[' || c == '{' || c == '*') && i + 1 < n && (s[i + 1] == 'l' || s[i + 1] == 'L')) return true
            i++
        }
        return false
    }
}
