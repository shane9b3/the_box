package app.thebox.core.xtream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Base64OrPlainTest {
    @Test
    fun tryDecode() {
        assertEquals("Morning News", Base64OrPlain.tryDecode("TW9ybmluZyBOZXdz"))
        assertEquals("ABC", Base64OrPlain.tryDecode("QUJD"))
        assertNull(Base64OrPlain.tryDecode("Test"), "decodes to invalid UTF-8")
        assertNull(Base64OrPlain.tryDecode("News at Ten"))
        assertNull(Base64OrPlain.tryDecode("QUJ"), "length not a multiple of 4")
        assertNull(Base64OrPlain.tryDecode("AAAAAAAA"), "control characters")
        assertEquals("Héllo\nworld", Base64OrPlain.tryDecode("SMOpbGxv\r\nCndvcmxk"))
    }

    @Test
    fun responseDecision() {
        assertTrue(Base64OrPlain.isBase64Response(sequenceOf("TW9ybmluZyBOZXdz", "", null, "UXVpeg==")))
        assertFalse(Base64OrPlain.isBase64Response(sequenceOf("Test", "QUJD", "News", "Weather", "Film")))
        assertFalse(Base64OrPlain.isBase64Response(sequenceOf("QUJD", "Test")), "1 of 2 is not a majority")
        assertFalse(Base64OrPlain.isBase64Response(emptySequence()))
        // Only the first 5 non-empty titles are sampled.
        assertTrue(Base64OrPlain.isBase64Response(sequenceOf("QUJD", "QUJD", "QUJD", "x y", "x y", "x y", "x y")))
    }

    @Test
    fun fieldFallsBackPerField() {
        assertEquals("Plain text", Base64OrPlain.field("Plain text", base64 = true))
        assertEquals("ABC", Base64OrPlain.field("QUJD", base64 = true))
        assertEquals("QUJD", Base64OrPlain.field("QUJD", base64 = false))
        assertNull(Base64OrPlain.field(null, base64 = true))
    }
}
