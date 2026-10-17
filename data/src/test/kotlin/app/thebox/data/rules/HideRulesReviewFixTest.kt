package app.thebox.data.rules

import app.thebox.core.settings.tree.RuleDraft
import app.thebox.core.settings.tree.RuleTarget
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-S5 (M5 code review 2026-10-16, adopted from the data reviewer): §3.7.3 refuses polynomial shapes up front. */
class HideRulesReviewFixTest {
    /** Twelve `.*` before a literal is O(n^12): the JVM time box catches it, Android's ICU would not. */
    @Test
    fun polynomialRegexIsRefusedAsTooSlow() {
        val draft = RuleDraft(RuleTarget.CHANNEL_NAME, (1..12).joinToString("") { ".*" } + "!", isRegex = true)
        val r = RuleMatcher.compile(1, draft)
        val slowOnJvm = (r as? RuleMatcher.Result.Ok)?.matcher?.let { m ->
            runCatching { m.matches("UK | Some Channel Name HD Backup Feed 1080p", System.nanoTime() + 50_000_000) }.isFailure
        }
        assertTrue("compile accepted it (catastrophic on the JVM time box: $slowOnJvm)", r is RuleMatcher.Result.Problem)
    }
}
