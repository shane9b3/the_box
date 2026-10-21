package app.thebox.core.text

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Compilation budgets; matching itself is input-linear through UserRegex on JVM and Android. */
class RegexSafetyTest {
    @Test
    fun sourceAndRepeatExpansionAreBoundedBeforeCompilation() {
        assertTrue(RegexSafety.isRisky("x".repeat(201)))
        for (p in listOf("a{1001}", "a{1,1001}", "a{1000000000000}", "(a{1000}){1000}", "a{100}b{100}", "(" + "a".repeat(100) + "){1000}")) {
            assertTrue(RegexSafety.isRisky(p), p)
        }
    }

    @Test
    fun formerlyBacktrackingShapesNeedNoBlacklistWithTheLinearEngine() {
        for (p in listOf(".*.*x", "\\w+\\w*", "a*a+b", "[a-z]+.*!", "\\d+\\w+", "\\S+\\S+$", "(a+)+", "(a|aa)+", "^(a+)(a+)(a+)(a+)$")) {
            assertFalse(RegexSafety.isRisky(p), p)
            UserRegex.compile(p)
        }
    }

    @Test
    fun ordinaryRulesStayAllowed() {
        for (p in listOf("^UK: .*", "\\bHD\\b", ".*(sport|news).*", "^\\[[A-Z]{2}\\] ", "\\d+\\s+\\w+", "^[A-Z]+:\\s*", "a+b+", "(?i)adult", "a{300}", "(ab){2}", "\\p{L}+")) {
            assertFalse(RegexSafety.isRisky(p), p)
            UserRegex.compile(p)
        }
    }
}
