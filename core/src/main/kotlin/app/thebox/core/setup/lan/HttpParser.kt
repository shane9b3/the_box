package app.thebox.core.setup.lan

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A request the parser refuses; the server answers [status] and closes. Never carries request bytes in the message. */
class HttpParseException(val status: Int, message: String) : IOException(message)

/** Request line and headers. Header names are lower-cased; [path] and [query] are percent-decoded. */
data class RequestHead(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    /** null = no body (GET without Content-Length). */
    val contentLength: Long?,
)

/**
 * Minimal HTTP/1.1 request parser for the phone page (docs/design/m5-setup-settings.md §3.5.2): request line + headers
 * ≤ [LanLimits.HTTP_MAX_HEADER_BYTES] (431), ≤ [LanLimits.HTTP_MAX_HEADERS] headers (431), `Content-Length` required for
 * POST and no `Transfer-Encoding` (411), raw non-ASCII or control bytes in the request line (400), conflicting lengths
 * (400). Reads byte by byte from a buffered stream; the body stays in the stream ([StreamBody]).
 */
object HttpParser {
    private val METHODS = setOf("GET", "POST", "HEAD")

    fun readHead(
        input: InputStream,
        maxHeaderBytes: Int = LanLimits.HTTP_MAX_HEADER_BYTES,
        maxHeaders: Int = LanLimits.HTTP_MAX_HEADERS,
    ): RequestHead {
        val lines = readHeadLines(input, maxHeaderBytes)
        val requestLine = lines.firstOrNull() ?: throw HttpParseException(400, "empty request")
        val parts = requestLine.split(' ')
        if (parts.size != 3) throw HttpParseException(400, "bad request line")
        val (method, target, version) = parts
        if (version != "HTTP/1.1" && version != "HTTP/1.0") throw HttpParseException(505, "bad version")
        if (method.isEmpty() || method.any { it !in 'A'..'Z' }) throw HttpParseException(400, "bad method")
        if (method !in METHODS) throw HttpParseException(405, "method not allowed")
        if (!target.startsWith("/")) throw HttpParseException(400, "bad target")

        if (lines.size - 1 > maxHeaders) throw HttpParseException(431, "too many headers")
        val headers = LinkedHashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.startsWith(" ") || line.startsWith("\t")) throw HttpParseException(400, "folded header")
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpParseException(400, "bad header")
            val name = line.substring(0, colon)
            if (name.any { !isTokenChar(it) }) throw HttpParseException(400, "bad header name")
            val key = name.lowercase()
            val value = line.substring(colon + 1).trim()
            val prev = headers[key]
            if (prev != null) {
                if (key == "content-length" && prev != value) throw HttpParseException(400, "conflicting length")
                if (key == "host") throw HttpParseException(400, "two hosts")
                headers[key] = "$prev, $value"
            } else {
                headers[key] = value
            }
        }
        if (headers.containsKey("transfer-encoding")) throw HttpParseException(411, "chunked bodies are not accepted")
        val lengthText = headers["content-length"]
        val length = lengthText?.let {
            if (it.isEmpty() || it.length > 18 || it.any { c -> c !in '0'..'9' }) throw HttpParseException(400, "bad length")
            it.toLong()
        }
        if (method == "POST" && length == null) throw HttpParseException(411, "length required")
        if (method != "POST" && length != null && length > 0) throw HttpParseException(400, "body not allowed")

        val q = target.indexOf('?')
        val rawPath = if (q < 0) target else target.substring(0, q)
        val path = percentDecode(rawPath, plusIsSpace = false)
        if (path.any { it < ' ' }) throw HttpParseException(400, "control character in path")
        val query = if (q < 0) emptyMap() else parseQuery(target.substring(q + 1))
        return RequestHead(method, path, query, headers, length)
    }

    private fun readHeadLines(input: InputStream, maxBytes: Int): List<String> {
        val lines = ArrayList<String>()
        val line = StringBuilder()
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (total == 0) throw HttpParseException(400, "connection closed before a request")
                throw HttpParseException(400, "truncated head")
            }
            if (++total > maxBytes) throw HttpParseException(431, "head too large")
            when {
                b == '\n'.code -> {
                    if (line.isNotEmpty() && line[line.length - 1] == '\r') line.setLength(line.length - 1)
                    if (line.isEmpty()) {
                        if (lines.isEmpty()) continue // tolerate a leading empty line (RFC 9112 §2.2)
                        return lines
                    }
                    lines += line.toString()
                    line.setLength(0)
                }
                b >= 0x80 -> throw HttpParseException(400, "non-ASCII byte in head")
                b < 0x20 && b != '\t'.code && b != '\r'.code -> throw HttpParseException(400, "control byte in head")
                else -> line.append(b.toChar())
            }
        }
    }

    private fun isTokenChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "!#$%&'*+-.^_`|~"

    fun parseQuery(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (raw.isEmpty()) return out
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = percentDecode(if (eq < 0) pair else pair.substring(0, eq), plusIsSpace = true)
            val v = if (eq < 0) "" else percentDecode(pair.substring(eq + 1), plusIsSpace = true)
            out.putIfAbsent(k, v)
        }
        return out
    }

    /** UTF-8 percent-decoding; a malformed escape is a 400. */
    fun percentDecode(s: String, plusIsSpace: Boolean): String {
        if (s.indexOf('%') < 0 && (!plusIsSpace || s.indexOf('+') < 0)) return s
        val bytes = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '%' -> {
                    if (i + 2 >= s.length) throw HttpParseException(400, "bad escape")
                    val hi = Character.digit(s[i + 1], 16)
                    val lo = Character.digit(s[i + 2], 16)
                    if (hi < 0 || lo < 0) throw HttpParseException(400, "bad escape")
                    val v = hi * 16 + lo
                    if (v == 0) throw HttpParseException(400, "NUL in request")
                    bytes.write(v)
                    i += 3
                }
                c == '+' && plusIsSpace -> { bytes.write(' '.code); i++ }
                else -> { bytes.write(c.code); i++ }
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw HttpParseException(400, "bad UTF-8")
        }
    }
}

/** The request body still in the connection's stream: exactly [length] bytes, copied in 64 KB chunks. */
class StreamBody(
    private val input: InputStream,
    override val length: Long,
    /** Called after every chunk (the server's idle watchdog). */
    private val onProgress: () -> Unit = {},
) : BodySource {
    private var consumed = false

    override fun copyTo(sink: OutputStream, max: Long): Long {
        check(!consumed) { "body already read" }
        consumed = true
        if (length > max) throw HttpParseException(413, "body too large")
        val buf = ByteArray(LanLimits.UPLOAD_CHUNK_BYTES)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw HttpParseException(400, "truncated body")
            sink.write(buf, 0, n)
            left -= n
            onProgress()
        }
        return length
    }
}
