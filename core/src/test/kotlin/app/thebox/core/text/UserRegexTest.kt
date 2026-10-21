package app.thebox.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserRegexTest {
    @Test
    fun groupedOptionalBoundedAndUnanchoredVariantsHaveLinearInputWork() {
        val text = "a".repeat(20_000) + "!"
        for (source in listOf(
            "^(a+)(a+)(a+)(a+)$", "^((a+))(?:(a+))(a+)$", "^(a+)(a?)(a+)(a+)$",
            "^((a+)?)(a+)(a+)$", "^([a]+)([a]+)([a]+)$", "^(a+)(a{0,4})(a+)$",
            "^(a+)+$", "^(a|aa)+$", "a+b",
        )) {
            var reads = 0
            val chars = object : CharSequence {
                override val length get() = text.length
                override fun get(index: Int): Char { reads++; return text[index] }
                override fun subSequence(startIndex: Int, endIndex: Int) = text.subSequence(startIndex, endIndex)
                override fun toString(): String = error("matching must not bypass a budgeted CharSequence")
            }
            assertFalse(UserRegex.compile(source).find(chars), source)
            assertTrue(reads < text.length * 100, "$source read $reads characters")
        }
    }

    @Test
    fun commonSyntaxUnicodeCaseAndPrefixIndicesArePreserved() {
        assertTrue(UserRegex.compile("^(sport|news) \\d$", ignoreCase = true).find("NEWS 1"))
        assertTrue(UserRegex.compile("école", ignoreCase = true).find("ÉCOLE"))
        assertTrue(UserRegex.compile("\\bHD\\b").find("News HD"))
        assertEquals(5, UserRegex.compile("\\[[A-Z]+\\]\\s*").prefixEnd("[US] CNN"))
        assertEquals(2, UserRegex.compile(".").prefixEnd("😀 News"), "substring offsets are UTF-16")
        assertNull(UserRegex.compile("x*").prefixEnd("News"), "an empty prefix never strips text")
    }

    @Test
    fun unsupportedSyntaxAndOversizedProgramsNeverFallBackToJavaRegex() {
        for (source in listOf("(?=a)a", "(?<=a)b", "(a)\\1", "a++", "[unclosed", "x".repeat(201), "(a{1000}){1000}")) {
            assertFailsWith<IllegalArgumentException>(source) { UserRegex.compile(source) }
        }
    }
}
