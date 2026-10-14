package app.thebox.core.guide.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §3.9.4 (pure part; the machine's use is in [GuideNumberEntryTest]). */
class NumberEntryImplTest {
    private val n = NumberEntryImpl()

    @Test fun digitsCommitAfter1500Ms() {
        assertTrue(n.digit(1, 0)); n.digit(2, 400); n.digit(3, 800)
        assertEquals("123", n.digits)
        assertFalse(n.due(2_299))
        assertTrue(n.due(2_300))
        assertEquals(123, n.commitValue())
    }

    @Test fun previewAfter150Ms() {
        n.digit(4, 1_000)
        assertFalse(n.previewDue(1_149))
        assertTrue(n.previewDue(1_150))
    }

    @Test fun atMostFourDigits() {
        repeat(4) { assertTrue(n.digit(9, it.toLong())) }
        assertFalse(n.digit(1, 10))
        assertEquals("9999", n.digits)
    }

    @Test fun dpadEntry() {
        n.startCursor()
        assertEquals("0", n.digits); assertEquals(0, n.cursorDigit)
        n.adjust(1); n.adjust(1)
        n.advance(); n.adjust(-1)
        assertEquals("29", n.digits)
        assertEquals(9, n.cursorDigit)
        assertFalse(n.due(Long.MAX_VALUE), "D-pad entry has no auto-commit")
        assertEquals(29, n.commitValue())
    }

    @Test fun clear() {
        n.digit(1, 0); n.clear()
        assertNull(n.commitValue()); assertFalse(n.due(10_000))
    }
}
