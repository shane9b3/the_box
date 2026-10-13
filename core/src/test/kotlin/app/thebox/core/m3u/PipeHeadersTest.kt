package app.thebox.core.m3u

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipeHeadersTest {
    @Test fun `splits at the last pipe followed by key=value pairs and decodes values once`() {
        val (url, h) = PipeHeaders.split("http://h/s.m3u8|User-Agent=Foo%20Bar&Referer=https://x/")
        assertEquals("http://h/s.m3u8", url)
        assertEquals(mapOf("User-Agent" to "Foo Bar", "Referer" to "https://x/"), h)
    }

    @Test fun `leading bang is stripped and other names are kept as written`() {
        val (url, h) = PipeHeaders.split("http://h/s|!X-Token=abc")
        assertEquals("http://h/s", url)
        assertEquals(mapOf("X-Token" to "abc"), h)
    }

    @Test fun `a pipe in the query without key= after it is part of the URL`() {
        assertEquals("http://h/play?ids=1|2|3" to emptyMap(), PipeHeaders.split("http://h/play?ids=1|2|3"))
    }

    @Test fun `the last qualifying pipe wins over an earlier pipe in the URL`() {
        val (url, h) = PipeHeaders.split("http://h/a?x=1|2/b.m3u8|User-Agent=U")
        assertEquals("http://h/a?x=1|2/b.m3u8", url)
        assertEquals(mapOf("User-Agent" to "U"), h)
    }

    @Test fun `an empty trailing pipe is removed`() {
        assertEquals("http://h/s.m3u8" to emptyMap(), PipeHeaders.split("http://h/s.m3u8|"))
    }

    @Test fun `plus stays literal, malformed pairs are counted and ignored`() {
        val s = PipeHeaders.splitDetailed("http://h/s|User-Agent=A+B&=novalue&broken")
        assertEquals("http://h/s", s.url)
        assertEquals(mapOf("User-Agent" to "A+B"), s.headers)
        assertEquals(2, s.badPairs)
    }

    @Test fun `aliases are canonicalised and de-duplicated case-insensitively (M10)`() {
        val (_, h) = PipeHeaders.split("http://h/s|http-referrer=a&referer=b&USER-AGENT=c&cookie=d&http-origin=e")
        assertEquals(mapOf("Referer" to "b", "User-Agent" to "c", "Cookie" to "d", "Origin" to "e"), h)
    }

    @Test fun `percentDecode handles UTF-8 escapes and leaves malformed ones`() {
        assertEquals("é x", PipeHeaders.percentDecode("%C3%A9%20x"))
        assertEquals("100%", PipeHeaders.percentDecode("100%"))
        assertEquals("%zz", PipeHeaders.percentDecode("%zz"))
        assertTrue(PipeHeaders.percentDecode("a+b") == "a+b")
    }
}
