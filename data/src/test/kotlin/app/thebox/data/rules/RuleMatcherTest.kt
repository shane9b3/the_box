package app.thebox.data.rules

import app.thebox.core.settings.tree.RuleDraft
import app.thebox.core.settings.tree.RuleProblem
import app.thebox.core.settings.tree.RuleTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleMatcherTest {
    private fun compile(source: String) = RuleMatcher.compile(1, RuleDraft(RuleTarget.CHANNEL_NAME, source, true))

    @Test
    fun groupedAdversarialNamesUseTheLinearEngine() {
        for (source in listOf("^(a+)(a+)(a+)(a+)$", "^((a+)?)(a+)(a+)$", "^(a+)+$", "^(a|aa)+$", "a+b")) {
            val matcher = (compile(source) as RuleMatcher.Result.Ok).matcher
            assertFalse(source, matcher.matches("a".repeat(20_000) + "!", Long.MAX_VALUE))
        }
        val ordinary = (compile("^(sport|news) \\d$") as RuleMatcher.Result.Ok).matcher
        assertTrue(ordinary.matches("NEWS 1", Long.MAX_VALUE))
    }

    @Test
    fun unsupportedSavedRulesAreInvalidAndExpansionIsRefused() {
        for (source in listOf("(?<=UK):", "(?=a)a", "(a)\\1", "a++")) {
            assertEquals(source, RuleMatcher.Result.Problem(RuleProblem.BAD_REGEX), compile(source))
        }
        assertEquals(RuleMatcher.Result.Problem(RuleProblem.TOO_SLOW), compile("(a{1000}){1000}"))
    }

    @Test
    fun pageDeadlineIsObservedInsideMatchingWithoutIcuConversion() {
        val matcher = (compile("^(a+)(a+)(a+)(a+)$") as RuleMatcher.Result.Ok).matcher
        var clockReads = 0
        var expired = false
        try {
            matcher.matches("a".repeat(20_000) + "!", 10) { if (++clockReads == 1) 0 else 11 }
        } catch (_: RegexBudgetExceeded) {
            expired = true
        }
        assertTrue(expired)
        assertTrue(clockReads >= 2)
    }
}
