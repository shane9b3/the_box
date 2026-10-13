package app.thebox.core.m3u

import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LineReaderTest {
    private fun lines(text: String, max: Int = 64 * 1024): List<String> {
        val r = LineReader(Buffer().writeUtf8(text), max)
        val out = ArrayList<String>()
        while (r.next()) out += String(r.bytes, 0, r.length, Charsets.UTF_8)
        return out
    }

    @Test fun `LF, CRLF and lone CR all end a line (M4)`() {
        assertEquals(listOf("a", "b", "c", "d"), lines("a\nb\r\nc\rd"))
    }

    @Test fun `trailing CR at EOF and CR split across reads never reach the line`() {
        assertEquals(listOf("a", "b"), lines("a\r\nb\r"))
        assertEquals(listOf("x"), lines("x\r"))
    }

    @Test fun `blank lines are skipped and lines are trimmed`() {
        assertEquals(listOf("a", "b c"), lines("\n\n  a \t\n\r\n\t b c  \r\n\n"))
    }

    @Test fun `line numbers count every physical line`() {
        val r = LineReader(Buffer().writeUtf8("a\n\nb\r\nc"), 100)
        val nums = ArrayList<Long>()
        while (r.next()) nums += r.lineNumber
        assertEquals(listOf(1L, 3L, 4L), nums)
    }

    @Test fun `over-long line is skipped and reported with its head (M5)`() {
        val text = "first\n#EXTINF:-1 tvg-logo=\"" + "x".repeat(200) + "\",Big\nnext\n"
        val r = LineReader(Buffer().writeUtf8(text), 64)
        assertTrue(r.next())
        assertEquals("first", String(r.bytes, 0, r.length))
        assertTrue(r.next())
        assertEquals("next", String(r.bytes, 0, r.length))
        assertEquals(1, r.skipped.size)
        assertEquals(2L, r.skipped[0].line)
        assertTrue(r.skipped[0].head.startsWith("#EXTINF"))
        assertFalse(r.next())
    }

    @Test fun `a 70 KB line is skipped without buffering it (M5, allocation via a counting source)`() {
        // A generating source: 70 KB line, then a short line. It counts how many bytes were pulled per read.
        val big = 70 * 1024
        val src = object : Source {
            var produced = 0L
            var maxRead = 0L
            val total = big + 1 + "#EXTINF:-1,After\nhttp://h/x.ts\n".length
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (produced >= total) return -1
                val n = minOf(byteCount, 4096L, total - produced)
                maxRead = maxOf(maxRead, n)
                for (i in 0 until n) {
                    val pos = produced + i
                    val b = when {
                        pos < big -> 'a'.code
                        pos == big.toLong() -> '\n'.code
                        else -> "#EXTINF:-1,After\nhttp://h/x.ts\n"[(pos - big - 1).toInt()].code
                    }
                    sink.writeByte(b)
                }
                produced += n
                return n
            }
            override fun timeout() = Timeout.NONE
            override fun close() {}
        }
        val buffered = src.buffer()
        val r = LineReader(buffered, 64 * 1024)
        assertTrue(r.next())
        assertEquals("#EXTINF:-1,After", String(r.bytes, 0, r.length))
        assertEquals(1, r.skipped.size)
        // Never more than the limit plus one segment's worth of look-ahead was held.
        assertTrue(r.peakBuffered <= 64 * 1024 + 8192 + 4096, "peak ${r.peakBuffered}")
        assertTrue(r.bytes.size <= 64 * 1024, "line array grew to ${r.bytes.size}")
        assertTrue(r.next())
        assertFalse(r.next())
    }

    @Test fun `UTF-16 transcoder handles surrogate pairs and lone surrogates`() {
        val text = "abé📺"
        val le = Buffer().write(text.toByteArray(Charsets.UTF_16LE)).writeShortLe(0xD800)
        val out = Utf16ToUtf8Source(le, bigEndian = false).buffer().readUtf8()
        assertEquals(text + "�", out)
        val be = Buffer().write(text.toByteArray(Charsets.UTF_16BE))
        assertEquals(text, Utf16ToUtf8Source(be, bigEndian = true).buffer().readUtf8())
    }
}
