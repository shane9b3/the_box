package app.thebox.data.rules

import app.thebox.core.settings.tree.RuleDraft
import app.thebox.core.settings.tree.RuleTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Former Java/ICU polynomial shapes now run safely through the input-linear UserRegex engine. */
class HideRulesReviewFixTest {
    /** Keep ordinary regex semantics on a long failing input, rather than depending on the retired shape blacklist. */
    @Test(timeout = 10_000)
    fun repeatedWildcardsMatchLongNamesWithoutBacktracking() {
        val draft = RuleDraft(RuleTarget.CHANNEL_NAME, (1..12).joinToString("") { ".*" } + "!", isRegex = true)
        val r = RuleMatcher.compile(1, draft)
        assertTrue("regular-language patterns are supported by the bounded engine", r is RuleMatcher.Result.Ok)
        val matcher = (r as RuleMatcher.Result.Ok).matcher
        val name = "UK | Some Channel Name HD Backup Feed 1080p ".repeat(500)
        assertFalse(matcher.matches(name, Long.MAX_VALUE))
        assertTrue(matcher.matches(name + "!", Long.MAX_VALUE))
    }
}
