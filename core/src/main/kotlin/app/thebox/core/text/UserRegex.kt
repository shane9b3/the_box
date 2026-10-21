package app.thebox.core.text

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException

/**
 * The only engine for user-supplied regexes: RE2/J's regular-language subset, with input-linear matching on both
 * JVM and Android. Unsupported constructs (lookaround, backreferences, possessive quantifiers, etc.) fail compilation;
 * there is no fallback to java.util.regex. Structural budgets bound compilation and the per-character work too.
 * See docs/decisions/2026-10-20-bounded-user-regex-and-lan-overload.md.
 */
class UserRegex private constructor(private val pattern: Pattern) {
    fun find(text: CharSequence): Boolean = pattern.matcher(text).find()

    /** End of a non-empty match at the start, in UTF-16 indices as required by String.substring. */
    fun prefixEnd(text: CharSequence): Int? {
        val matcher = pattern.matcher(text)
        return if (matcher.lookingAt() && matcher.end() > 0) matcher.end() else null
    }

    companion object {
        /** Includes assertions/captures; the source and repeat-product bounds are checked before compiling. */
        private const val MAX_PROGRAM_SIZE = 4_096

        /** Throws IllegalArgumentException for an unsupported, malformed or over-budget pattern. Never logs its text. */
        fun compile(source: String, ignoreCase: Boolean = false): UserRegex {
            require(!RegexSafety.isRisky(source)) { "pattern exceeds structural budget" }
            val pattern = try {
                Pattern.compile(source, if (ignoreCase) Pattern.CASE_INSENSITIVE else 0)
            } catch (_: PatternSyntaxException) {
                // RE2's exception is a RuntimeException and embeds the pattern; normalize the public contract without
                // retaining that text in an exception that another layer could log.
                throw IllegalArgumentException("unsupported or malformed regular expression")
            }
            require(pattern.programSize() <= MAX_PROGRAM_SIZE) { "compiled pattern exceeds structural budget" }
            return UserRegex(pattern)
        }
    }
}
