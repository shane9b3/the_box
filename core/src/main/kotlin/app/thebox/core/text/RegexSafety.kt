package app.thebox.core.text

/**
 * Structural budgets for [UserRegex], not a guess about backtracking. Every accepted user regex executes through
 * RE2/J, so grouping/alternation/optional separators cannot bypass the input-linear matching guarantee. The former
 * Java/ICU shape blacklist is intentionally retired: those shapes are safe with this engine.
 */
object RegexSafety {
    const val MAX_PATTERN_CHARS = 200

    /**
     * Bound expansion before RE2/J compiles counted repeats. Multiplying every written repeat's largest finite count
     * is conservative (even independent or quoted counts share the budget), and bounds nested expansion without
     * implementing another regex parser. Unicode properties such as \p{L} have no numeric count and are ignored.
     */
    const val MAX_REPEAT_PRODUCT = 1_000
    private const val MAX_EXPANDED_CHARS = 4_096

    fun isRisky(pattern: String): Boolean {
        if (pattern.length > MAX_PATTERN_CHARS) return true
        var product = 1
        var i = 0
        while (i < pattern.length) {
            if (pattern[i++] != '{') continue
            var count = 0
            var digits = false
            while (i < pattern.length && pattern[i] in '0'..'9') {
                digits = true
                count = count * 10 + (pattern[i++] - '0')
                if (count > MAX_REPEAT_PRODUCT) return true
            }
            if (!digits) continue
            if (pattern.getOrNull(i) == ',') {
                i++
                var upper = 0
                while (i < pattern.length && pattern[i] in '0'..'9') {
                    upper = upper * 10 + (pattern[i++] - '0')
                    if (upper > MAX_REPEAT_PRODUCT) return true
                }
                count = maxOf(count, upper)
            }
            if (pattern.getOrNull(i) != '}') continue // The engine rejects malformed syntax itself.
            val factor = count.coerceAtLeast(1)
            if (product > MAX_REPEAT_PRODUCT / factor) return true
            product *= factor
            if (pattern.length * product > MAX_EXPANDED_CHARS) return true
        }
        return false
    }
}
