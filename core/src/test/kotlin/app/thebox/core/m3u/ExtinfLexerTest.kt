package app.thebox.core.m3u

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtinfLexerTest {
    private fun lex(line: String) = ExtinfLexer.lex(line, line.indexOf(':') + 1, withDuration = true)

    @Test fun `double, single, unquoted and empty values`() {
        val r = lex("#EXTINF:-1 a=\"x y\" b='p q' c=plain d=\"\" e=,Name")
        assertEquals(mapOf("a" to "x y", "b" to "p q", "c" to "plain", "d" to "", "e" to ""), r.attributes)
        assertEquals("Name", r.name)
        assertEquals(-1.0, r.duration)
        assertFalse(r.badAttribute)
    }

    @Test fun `comma inside quotes does not end the attribute region`() {
        val r = lex("#EXTINF:-1 tvg-name=\"News, UK\" group-title='A, B',Real, Name")
        assertEquals("News, UK", r.attributes["tvg-name"])
        assertEquals("A, B", r.attributes["group-title"])
        assertEquals("Real, Name", r.name)
    }

    @Test fun `keys are case-insensitive and lower-cased`() {
        val r = lex("#EXTINF:-1 tvg-ID=\"x\" Group-Title=\"G\" X-Custom.Key:1=\"v\",N")
        assertEquals(setOf("tvg-id", "group-title", "x-custom.key:1"), r.attributes.keys)
    }

    @Test fun `bare flags give empty values`() {
        val r = lex("#EXTINF:-1 catchup radio tvg-id=\"a\",N")
        assertEquals("", r.attributes["catchup"])
        assertEquals("", r.attributes["radio"])
        assertEquals("a", r.attributes["tvg-id"])
    }

    @Test fun `duplicate keys - the first wins`() {
        assertEquals("1", lex("#EXTINF:-1 tvg-id=\"1\" TVG-ID=\"2\",N").attributes["tvg-id"])
    }

    @Test fun `unterminated quote takes the value to the attribute-region end and is reported`() {
        val r = lex("#EXTINF:-1 tvg-id=\"a\" tvg-logo=\"http://x/l.png,Channel Name")
        assertTrue(r.badAttribute)
        assertEquals("http://x/l.png", r.attributes["tvg-logo"])
        assertEquals("Channel Name", r.name)
        val noComma = lex("#EXTINF:-1 tvg-name=\"broken")
        assertEquals("broken", noComma.attributes["tvg-name"])
        assertNull(noComma.name)
    }

    @Test fun `values over 4 KB are dropped`() {
        val big = "data:image/png;base64," + "A".repeat(5000)
        val r = lex("#EXTINF:-1 tvg-logo=\"$big\" tvg-id=\"x\",N")
        assertNull(r.attributes["tvg-logo"])
        assertTrue(r.droppedLongValue)
        assertEquals("x", r.attributes["tvg-id"])
        val ok = lex("#EXTINF:-1 tvg-logo=\"${"B".repeat(4096)}\",N")
        assertEquals(4096, ok.attributes["tvg-logo"]!!.length)
    }

    @Test fun `durations - int, float, zero, missing`() {
        assertEquals(3600.5, lex("#EXTINF:3600.5,Movie").duration)
        assertEquals(0.0, lex("#EXTINF:0,Zero").duration)
        val none = lex("#EXTINF:tvg-id=\"a\",No Duration")
        assertNull(none.duration)
        assertEquals("a", none.attributes["tvg-id"])
        assertEquals("No Duration", none.name)
    }

    @Test fun `display name is trimmed, including NBSP`() {
        assertEquals("Spaced", lex("#EXTINF:-1 a=\"b\",  Spaced  ").name)
        assertEquals("", lex("#EXTINF:-1,").name)
    }

    @Test fun `stray junk between attributes is skipped`() {
        val r = lex("#EXTINF:-1 \"oops\" tvg-id=\"x\" ;; group-title=\"G\",N")
        assertEquals("x", r.attributes["tvg-id"])
        assertEquals("G", r.attributes["group-title"])
        assertEquals("N", r.name)
    }

    @Test fun `common keys are interned`() {
        val a = lex("#EXTINF:-1 TVG-ID=\"1\",A").attributes.keys.first()
        val b = lex("#EXTINF:-1 tvg-id=\"2\",B").attributes.keys.first()
        assertTrue(a === b)
    }
}
