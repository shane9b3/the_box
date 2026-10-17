package app.thebox.core.text

/**
 * Refuses the regex shapes that backtrack exponentially (docs/design/m5-setup-settings.md §3.7.3), because Android's
 * `java.util.regex` runs ICU on `input.toString()` and cannot be stopped mid-match: a quantified group that itself
 * contains a quantifier (`(a+)+`, `(.*a){20}`, `(\w+\s?)*`), a quantified group with alternation (`(a|aa)+`), and
 * back-references (`\1`, `\k<name>`). A rule a user wants hardly ever needs these; the message suggests Contains.
 * Conservative by design: a false positive only means "use a simpler pattern".
 *
 * (M5 integration 2026-10-16) Moved from `data/.../rules/` to `:core` so M3's custom channel-name prefix regex
 * (`ChannelNamePrefixImpl`, G-L19) and its editor (WP-B's playlist page) apply the same check as WP-D's hide rules.
 */
object RegexSafety {
    /**
     * D-S5 (code review 2026-10-16): more unbounded quantifiers than this in one pattern are refused (each one multiplies
     * the work of a failing match on Android's uninterruptible ICU).
     */
    const val MAX_UNBOUNDED = 4

    fun isRisky(pattern: String): Boolean = isNested(pattern) || isPolynomial(pattern)

    /**
     * D-S5 (code review 2026-10-16): polynomial backtracking without a quantified group: two unbounded quantifiers
     * (`*`, `+`, `{n,}`) on overlapping atoms directly in sequence (`.*.*`, `\w+\w*`, `a*a+`, `[a-z]+.*`), or more than
     * [MAX_UNBOUNDED] unbounded quantifiers in total. Conservative: a character class overlaps everything.
     */
    fun isPolynomial(pattern: String): Boolean {
        var unbounded = 0
        var prev: Char? = null // the atom of the previous token when it was unbounded-quantified ('.' dot, '[' class, 'w' …)
        var prevEscaped = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            // One atom: its kind and where it ends.
            var atom: Char? = null
            var escaped = false
            var end = i + 1
            when (c) {
                '\\' -> { atom = pattern.getOrNull(i + 1); escaped = true; end = i + 2 }
                '[' -> {
                    var j = i + 1
                    if (pattern.getOrNull(j) == '^') j++
                    if (pattern.getOrNull(j) == ']') j++
                    while (j < pattern.length && pattern[j] != ']') { if (pattern[j] == '\\') j++; j++ }
                    atom = '['; end = j + 1
                }
                '(', ')', '|', '^', '$' -> {
                    // Group edges and anchors break a sequence; a quantified group still counts toward the total.
                    if (c == ')' && pattern.getOrNull(i + 1)?.let { quantifierAt(pattern, i + 1) } == true) unbounded++
                    prev = null
                    i = if (c == ')' ) skipQuantifier(pattern, i + 1) else i + 1
                    if (c == '(' && pattern.getOrNull(i) == '?') { i++; while (i < pattern.length && pattern[i] != ':' && pattern[i] != '>' && pattern[i] != ')') i++; if (pattern.getOrNull(i) == ':' || pattern.getOrNull(i) == '>') i++ }
                    continue
                }
                '*', '+', '?', '{' -> { i++; continue } // a stray quantifier (already counted with its atom)
                else -> atom = c
            }
            val unboundedHere = quantifierAt(pattern, end)
            if (unboundedHere) {
                unbounded++
                if (unbounded > MAX_UNBOUNDED) return true
                val p0 = prev
                if (p0 != null && atom != null && overlaps(p0, prevEscaped, atom, escaped)) return true
                prev = atom; prevEscaped = escaped
            } else {
                prev = null
            }
            i = skipQuantifier(pattern, end)
        }
        return false
    }

    /** True when an unbounded quantifier (`*`, `+`, `{n,}`) starts at [at]. */
    private fun quantifierAt(p: String, at: Int): Boolean = when (p.getOrNull(at)) {
        '*', '+' -> true
        '{' -> p.substring(at + 1).substringBefore('}').let { body -> ',' in body && body.substringAfter(',').isBlank() }
        else -> false
    }

    /** Index after a quantifier at [at] (and its lazy/possessive suffix), or [at] when none. */
    private fun skipQuantifier(p: String, at: Int): Int {
        var i = at
        when (p.getOrNull(i)) {
            '*', '+', '?' -> i++
            '{' -> { val close = p.indexOf('}', i); if (close < 0) return i; i = close + 1 }
            else -> return i
        }
        if (p.getOrNull(i) == '?' || p.getOrNull(i) == '+') i++
        return i
    }

    /** Whether two atoms can match the same character (conservative). */
    private fun overlaps(a: Char, aEsc: Boolean, b: Char, bEsc: Boolean): Boolean {
        fun kind(c: Char, esc: Boolean): String = when {
            !esc && c == '.' -> "any"
            !esc && c == '[' -> "any"
            esc && c in "wWsSdD" -> c.toString()
            else -> "lit"
        }
        val ka = kind(a, aEsc)
        val kb = kind(b, bEsc)
        if (ka == "any" || kb == "any") return true
        if (ka == "lit" && kb == "lit") return a == b
        fun litIn(l: Char, cls: String): Boolean = when (cls) {
            "w" -> l.isLetterOrDigit() || l == '_'
            "W" -> !(l.isLetterOrDigit() || l == '_')
            "s" -> l.isWhitespace()
            "S" -> !l.isWhitespace()
            "d" -> l.isDigit()
            "D" -> !l.isDigit()
            else -> true
        }
        if (ka == "lit") return litIn(a, kb)
        if (kb == "lit") return litIn(b, ka)
        // Two shorthand classes: only the complementary pairs and \d / \s, \w / \s are disjoint.
        val disjoint = setOf("wW", "Ww", "sS", "Ss", "dD", "Dd", "ds", "sd", "ws", "sw")
        return (ka + kb) !in disjoint
    }

    private fun isNested(pattern: String): Boolean {
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
