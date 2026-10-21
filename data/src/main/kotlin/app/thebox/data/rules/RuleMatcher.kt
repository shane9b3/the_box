package app.thebox.data.rules

import app.thebox.core.text.RegexSafety
import app.thebox.core.text.UserRegex

import app.thebox.core.settings.tree.RuleDraft
import app.thebox.core.settings.tree.RuleProblem
import app.thebox.core.settings.tree.RuleTarget

/** Appendix B (docs/design/m5-setup-settings.md §3.7.3). */
const val RULE_REGEX_MAX_CHARS = 200
const val RULE_REGEX_BUDGET_MS = 50L

/** Thrown by [TimeBoxedCharSequence] when matching runs past its deadline. */
class RegexBudgetExceeded : RuntimeException("pattern too slow") {
    override fun fillInStackTrace(): Throwable = this // thrown on a hot path; the stack is useless
}

/**
 * A [CharSequence] that throws [RegexBudgetExceeded] once [deadlineNs] (System.nanoTime) has passed. The pure-Java
 * [UserRegex] engine reads through this sequence on Android too; no platform ICU conversion bypasses the deadline.
 */
class TimeBoxedCharSequence(
    private val inner: CharSequence,
    private val deadlineNs: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) : CharSequence {
    private var calls = 0
    override val length: Int get() = inner.length
    override fun get(index: Int): Char {
        if (++calls and CHECK_MASK == 0 && nanoTime() > deadlineNs) throw RegexBudgetExceeded()
        return inner[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        TimeBoxedCharSequence(inner.subSequence(startIndex, endIndex), deadlineNs, nanoTime)
    override fun toString(): String = inner.toString()

    private companion object { const val CHECK_MASK = 0xFF }
}

/** One compiled rule. Contains is case-insensitive; a regex is compiled once, case-insensitive, and searched (`find`). */
class RuleMatcher private constructor(val id: Long, val target: RuleTarget, private val needle: String?, private val regex: UserRegex?) {
    /**
     * True when [text] matches. Regex matching is input-linear, with a cooperative [deadlineNs] page budget on JVM
     * and Android. [RegexBudgetExceeded] still disables a rule that consumes too much of a large page's time.
     */
    fun matches(text: String, deadlineNs: Long, nanoTime: () -> Long = System::nanoTime): Boolean {
        if (regex == null) return text.contains(needle!!, ignoreCase = true)
        if (nanoTime() > deadlineNs) throw RegexBudgetExceeded()
        val hit = regex.find(TimeBoxedCharSequence(text, deadlineNs, nanoTime))
        if (nanoTime() > deadlineNs) throw RegexBudgetExceeded()
        return hit
    }

    val isRegex: Boolean get() = regex != null

    companion object {
        /** Compiles [draft]; returns the problem instead when the pattern is empty, too long or not a valid regex. */
        fun compile(id: Long, draft: RuleDraft): Result {
            val p = draft.pattern
            if (p.isBlank()) return Result.Problem(RuleProblem.EMPTY)
            if (p.length > RULE_REGEX_MAX_CHARS) return Result.Problem(RuleProblem.TOO_LONG)
            if (!draft.isRegex) return Result.Ok(RuleMatcher(id, draft.target, p, null))
            if (RegexSafety.isRisky(p)) return Result.Problem(RuleProblem.TOO_SLOW)
            return try {
                Result.Ok(RuleMatcher(id, draft.target, null, UserRegex.compile(p, ignoreCase = true)))
            } catch (e: IllegalArgumentException) {
                Result.Problem(RuleProblem.BAD_REGEX)
            } catch (e: StackOverflowError) {
                Result.Problem(RuleProblem.BAD_REGEX)
            }
        }
    }

    sealed interface Result {
        data class Ok(val matcher: RuleMatcher) : Result
        data class Problem(val problem: RuleProblem) : Result
    }
}
