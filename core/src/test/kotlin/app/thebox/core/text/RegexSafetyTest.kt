package app.thebox.core.text

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D-S5 (M5 code review 2026-10-16): the shapes `RegexSafety` refuses up front (ICU cannot be stopped on Android). */
class RegexSafetyTest {
    /** D-S5 (adopted from the data reviewer): polynomial backtracking without a quantified group. */
    @Test
    fun polynomialBacktrackingIsRefused() {
        val p = (1..12).joinToString("") { ".*" } + "!"
        assertTrue(RegexSafety.isRisky(p), "'$p' is O(n^12) on a name without '!' and is accepted")
    }

    /** D-S5: two unbounded quantifiers on overlapping atoms in sequence, or more than four in total. */
    @Test
    fun adjacentUnboundedQuantifiersAreRisky() {
        for (p in listOf(".*.*x", "\\w+\\w*", "a*a+b", "[a-z]+.*!", "\\d+\\w+", "\\S+\\S+$")) assertTrue(RegexSafety.isRisky(p), p)
        assertTrue(RegexSafety.isRisky("a+x b+y c+z d+w e+v"), "five unbounded quantifiers")
    }

    /** The shapes users write keep working (D-S5 must not refuse ordinary rules). */
    @Test
    fun ordinaryRulesStayAllowed() {
        for (p in listOf("^UK: .*", "\\bHD\\b", ".*(sport|news).*", "^\\[[A-Z]{2}\\] ", "\\d+\\s+\\w+", "^[A-Z]+:\\s*", "a+b+", "(?i)adult")) {
            assertFalse(RegexSafety.isRisky(p), p)
        }
        // The exponential shapes stay refused.
        for (p in listOf("(a+)+", "(.*a){20}", "(a|aa)+", "(x)\\1")) assertTrue(RegexSafety.isRisky(p), p)
    }
}
