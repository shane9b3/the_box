package app.thebox.core.identity

import app.thebox.core.source.XtreamStreamUrl
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The hand-written matcher must accept exactly what the contract's regexes accept (§6.6 no regex per row). */
class XtreamUrlShapeParityTest {
    private fun check(url: String) {
        for (short in listOf(false, true)) {
            val expected = XtreamStreamUrl.parse(url, short)
            val actual = XtreamUrlShape.parse(url, short)?.toContract()
            assertEquals(expected, actual, "url=$url allowShort=$short")
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://h:8080/live/u/p/101.ts", "https://h/live/u/p/101", "http://h/live/u/p/101.m3u8?token=1", "http://h/live/u/p/101#x",
            "http://h/movie/u/p/2001.mkv", "http://h/series/u/p/3001.mp4", "http://h/live/u%40x/p%23w/1.ts", "http://h/live/a+b/c/1.ts",
            "http://h/u/p/102", "http://h/u/p/102.ts", "http://h/u/p/102.m3u8", "http://h/u/p/102.mp4", "http://h/u/p/102.tsx",
            "http://h/live/u/p/101.mpegts", "http://h/live/u/p/abc.ts", "http://h/live/u/p", "http://h/live/u/p/", "http://h/LIVE/u/p/1.ts",
            "HTTP://h/live/u/p/1.ts", "ftp://h/live/u/p/1.ts", "http:///live/u/p/1", "http://h//p/1", "http://h/hls/chan/123",
            "http://cdn.example.com/live/bbc/hd/1.m3u8", "http://h/live/u/p/1.ts/extra", "http://h/live/u/p/1.t", "http://h/live/u/p/1.abcd",
            "http://h/live/u/p/1.ab12?x=/y", "http://h/live/u/p/99999999999999999999.ts", "http://h?x/u/p/1", "http://h/a/b/c/1.ts",
            "http://h/u/p/1.TS", "http://h/live/u/p/1.", "http://h/series/u/p/1?", "http://h:80/movie/%E2%9C%93/p/7.mkv",
        ],
    )
    fun `parity on hand-picked URLs`(url: String) = check(url)

    @Test fun `parity on 20k random URL-like strings`() {
        val rnd = Random(42)
        val parts = listOf("http://", "https://", "h", "h:8080", "/", "live", "movie", "series", "u", "p%40", "123", "7", ".", "ts", "m3u8",
            "mkv", "abcde", "?", "#", "x=1", "+", "a")
        repeat(20_000) {
            val sb = StringBuilder()
            if (rnd.nextInt(4) != 0) sb.append(if (rnd.nextBoolean()) "http://" else "https://")
            repeat(rnd.nextInt(3, 12)) { sb.append(parts[rnd.nextInt(parts.size)]) }
            check(sb.toString())
        }
    }

    @Test fun `credential offsets point at the raw segments`() {
        val url = "http://h:8080/live/u%40x/p%23w/101.ts"
        val x = XtreamUrlShape.parse(url, false)!!
        assertEquals("u%40x", url.substring(x.userStart, x.userEnd))
        assertEquals("p%23w", url.substring(x.passStart, x.passEnd))
        assertEquals("u@x", x.username)
        assertEquals("p#w", x.password)
        val s = XtreamUrlShape.parse("http://h/uu/pp/5", true)!!
        assertEquals("uu", "http://h/uu/pp/5".substring(s.userStart, s.userEnd))
    }
}
