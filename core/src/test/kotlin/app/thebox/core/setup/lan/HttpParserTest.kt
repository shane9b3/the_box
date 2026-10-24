package app.thebox.core.setup.lan

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/design/m5-setup-settings.md §3.5.2 parser limits, §6.1 WP-D `HttpParserTest`. */
class HttpParserTest {
    private fun head(raw: String) = HttpParser.readHead(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)))
    private fun status(raw: String): Int = assertFailsWith<HttpParseException> { head(raw) }.status

    @Test
    fun parsesAGetWithQueryAndLowerCasedHeaders() {
        val h = head("GET /api/upload?kind=backup&name=My%20file+x HTTP/1.1\r\nHost: 192.168.1.20:47100\r\nX-TheBox: 1\r\n\r\n")
        assertEquals("GET", h.method)
        assertEquals("/api/upload", h.path)
        assertEquals(mapOf("kind" to "backup", "name" to "My file x"), h.query)
        assertEquals("192.168.1.20:47100", h.headers["host"])
        assertEquals("1", h.headers["x-thebox"])
        assertNull(h.contentLength)
    }

    @Test
    fun bareLineFeedsAndALeadingEmptyLineAreTolerated() {
        val h = head("\r\nPOST /pair HTTP/1.1\nHost: a\nContent-Length: 4\n\n")
        assertEquals(4L, h.contentLength)
    }

    @Test
    fun malformedRequestLinesAre400() {
        assertEquals(400, status("GET\r\n\r\n"))
        assertEquals(400, status("GET /a b HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("GET a HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("get / HTTP/1.1\r\n\r\n"))
        assertEquals(505, status("GET / HTTP/2.0\r\n\r\n"))
        assertEquals(405, status("PUT / HTTP/1.1\r\nContent-Length: 0\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nNoColon\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nA: b\r\n folded\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nBad Name: x\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: a\r\n")) // truncated head
        assertEquals(400, status(""))
    }

    @Test
    fun nonAsciiPaths() {
        // Raw bytes above 0x7F in the head are refused; percent-encoded UTF-8 decodes.
        assertEquals(400, status("GET /café HTTP/1.1\r\n\r\n"))
        assertEquals("/café", head("GET /caf%C3%A9 HTTP/1.1\r\n\r\n").path)
        assertEquals(400, status("GET /%C3 HTTP/1.1\r\n\r\n")) // broken UTF-8
        assertEquals(400, status("GET /%zz HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("GET /a%00b HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("GET /a%0Ab HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("GET /a\u0001b HTTP/1.1\r\n\r\n"))
    }

    @Test
    fun headerLimits() {
        val big = "GET / HTTP/1.1\r\nX-A: " + "a".repeat(LanLimits.HTTP_MAX_HEADER_BYTES) + "\r\n\r\n"
        assertEquals(431, status(big))
        val many = "GET / HTTP/1.1\r\n" + (1..LanLimits.HTTP_MAX_HEADERS + 1).joinToString("") { "X-$it: v\r\n" } + "\r\n"
        assertEquals(431, status(many))
        val exactly = "GET / HTTP/1.1\r\n" + (1..LanLimits.HTTP_MAX_HEADERS).joinToString("") { "X-$it: v\r\n" } + "\r\n"
        assertEquals(LanLimits.HTTP_MAX_HEADERS, head(exactly).headers.size)
    }

    @Test
    fun postNeedsContentLengthAndNoChunking() {
        assertEquals(411, status("POST /pair HTTP/1.1\r\nHost: a\r\n\r\n"))
        assertEquals(411, status("POST /pair HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"))
        assertEquals(411, status("POST /pair HTTP/1.1\r\nContent-Length: 3\r\nTransfer-Encoding: chunked\r\n\r\n"))
        assertEquals(400, status("POST /pair HTTP/1.1\r\nContent-Length: -1\r\n\r\n"))
        assertEquals(400, status("POST /pair HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nContent-Length: 5\r\n\r\n"))
    }

    @Test
    fun bodyIsCopiedExactlyAndOversizeIs413() {
        val input = ByteArrayInputStream("hello world".toByteArray())
        val out = ByteArrayOutputStream()
        assertEquals(5L, StreamBody(input, 5).copyTo(out, 10))
        assertEquals("hello", out.toString())
        assertEquals(413, assertFailsWith<HttpParseException> { StreamBody(ByteArrayInputStream(ByteArray(20)), 20).copyTo(ByteArrayOutputStream(), 10) }.status)
        assertEquals(400, assertFailsWith<HttpParseException> { StreamBody(ByteArrayInputStream(ByteArray(3)), 5).copyTo(ByteArrayOutputStream(), 10) }.status)
    }

    /** Save-location integration (S6-Q2): the chunk buffer that held the body is all zeros afterwards, also on a 400. */
    @Test
    fun theChunkBufferIsZeroedAfterTheCopy() {
        for (truncated in listOf(false, true)) {
            val seen = mutableListOf<ByteArray>()
            val source = ByteArrayInputStream("user=a&password=secret".toByteArray())
            val recording = object : InputStream() {
                override fun read(): Int = source.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int { seen += b; return source.read(b, off, len) }
            }
            val len = if (truncated) 64L else 22L
            runCatching { StreamBody(recording, len).copyTo(ByteArrayOutputStream(), 1_000) }
            assertTrue(seen.isNotEmpty())
            seen.forEach { buf -> assertTrue(buf.all { it == 0.toByte() }, "buffer not zeroed (truncated=$truncated)") }
        }
        val wiping = WipingBufferedInputStream(ByteArrayInputStream("secret".toByteArray()), 64)
        wiping.read(ByteArray(2))
        wiping.wipe()
        // The rest of "secret" was already read ahead; after the wipe the buffer hands back zeros, never the bytes.
        val rest = ByteArray(4).also { assertEquals(4, wiping.read(it)) }
        assertTrue(rest.all { it == 0.toByte() })
    }

    @Test
    fun slowSenderTimesOut() {
        // A socket read timeout surfaces as an IOException from the stream; the parser must not swallow it.
        val stalling = object : InputStream() {
            var sent = 0
            val head = "GET / HTTP/1.1\r\n".toByteArray()
            override fun read(): Int = if (sent < head.size) head[sent++].toInt() else throw java.net.SocketTimeoutException("read timed out")
        }
        assertFailsWith<java.net.SocketTimeoutException> { HttpParser.readHead(stalling) }
    }
}
