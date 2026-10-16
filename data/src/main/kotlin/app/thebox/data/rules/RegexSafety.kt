package app.thebox.data.rules

/**
 * Refuses the regex shapes that backtrack exponentially (docs/design/m5-setup-settings.md §3.7.3), because Android's
 * `java.util.regex` runs ICU on `input.toString()` and cannot be stopped mid-match: a quantified group that itself
 * contains a quantifier (`(a+)+`, `(.*a){20}`, `(\w+\s?)*`), a quantified group with alternation (`(a|aa)+`), and
 * back-references (`\1`, `\k<name>`). A rule a user wants hardly ever needs these; the message suggests Contains.
 * Conservative by design: a false positive only means "use a simpler pattern".
 */
object RegexSafety {
    fun isRisky(pattern: String): Boolean {
        // Per open group: whether it contains a quantifier or an alternation.
        val quant = ArrayList<Boolean>()
        val alt = ArrayList<Boolean>()
        var i = 0
        var inClass = false
        var lastClosedRisky = false // the group that just closed had a quantifier or alternation inside
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\\') {
                val n = pattern.getOrNull(i + 1)
                if (!inClass && n != null && (n in '1'..'9' || n == 'k')) return true
                i += 2
                lastClosedRisky = false
                if (quant.isNotEmpty() && pattern.getOrNull(i)?.let(::isQuantifier) == true) quant[quant.size - 1] = true
                continue
            }
            if (inClass) {
                if (c == ']') inClass = false
                i++
                continue
            }
            when (c) {
                '[' -> { inClass = true; lastClosedRisky = false }
                '(' -> { quant += false; alt += false; lastClosedRisky = false }
                ')' -> {
                    val q = quant.removeLastOrNull() ?: false
                    val a = alt.removeLastOrNull() ?: false
                    lastClosedRisky = q || a
                    // A group inside a group counts as a quantifier of the outer one when it is quantified.
                    if (quant.isNotEmpty() && (q || pattern.getOrNull(i + 1)?.let(::isQuantifier) == true)) quant[quant.size - 1] = quant.last() || q
                    i++
                    if (pattern.getOrNull(i)?.let(::isQuantifier) == true) {
                        if (lastClosedRisky && isRepeating(pattern, i)) return true
                        if (quant.isNotEmpty()) quant[quant.size - 1] = true
                    }
                    continue
                }
                '|' -> if (alt.isNotEmpty()) alt[alt.size - 1] = true
                '*', '+', '{' -> if (quant.isNotEmpty()) quant[quant.size - 1] = true
            }
            i++
        }
        return false
    }

    private fun isQuantifier(c: Char) = c == '*' || c == '+' || c == '?' || c == '{'

    /** `?` alone (0 or 1) cannot repeat; `*`, `+` and `{n,m}` with m > 1 can. */
    private fun isRepeating(p: String, at: Int): Boolean = when (p[at]) {
        '*', '+' -> true
        '{' -> {
            val body = p.substring(at + 1).substringBefore('}')
            val max = if (',' in body) body.substringAfter(',').ifEmpty { "2" } else body
            (max.trim().toIntOrNull() ?: 2) > 1
        }
        else -> false
    }
}
