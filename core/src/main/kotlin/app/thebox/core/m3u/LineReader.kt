package app.thebox.core.m3u

import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import okio.Source
import okio.Timeout

/**
 * Byte-level line reader (docs/design/m1-data-layer.md §3.4 M1, M4, M5).
 *
 * Lines end at LF, CR or CRLF. Leading and trailing ASCII whitespace is trimmed on the bytes, so no
 * `\r` or tab ever reaches a decoded name or URL. A line longer than [maxLineBytes] is skipped to the
 * next terminator without being buffered (at most [maxLineBytes] plus one okio segment is ever held).
 *
 * The current line is exposed through [bytes] / [length] (a reused array, valid until the next [next]
 * call) instead of allocating a `ByteString` per line: the parser decodes straight from it.
 */
internal class LineReader(private val source: BufferedSource, private val maxLineBytes: Int) {
    var bytes: ByteArray = ByteArray(512)
        private set
    var length: Int = 0
        private set

    /** 1-based number of the line last returned or skipped. */
    var lineNumber: Long = 0
        private set

    /** Over-long lines skipped by the last [next] call, with their first bytes (is it an `#EXTINF` or a URL?). */
    val skipped = ArrayList<SkippedLine>(1)

    /** Largest number of bytes held in the source buffer while scanning (test hook for the M5 "no buffering" rule). */
    var peakBuffered: Long = 0
        private set

    class SkippedLine(val line: Long, val head: String)

    /** Advances to the next non-blank line. Returns false at EOF. */
    fun next(): Boolean {
        if (skipped.isNotEmpty()) skipped.clear()
        while (true) {
            val r = readRawLine()
            if (r == EOF) return false
            if (r == LINE && length > 0) return true
        }
    }

    private fun readRawLine(): Int {
        val buf = source.buffer
        var scanFrom = 0L
        while (true) {
            val idx = if (buf.size > scanFrom) buf.indexOfElement(TERMINATORS, scanFrom) else -1L
            if (buf.size > peakBuffered) peakBuffered = buf.size
            if (idx in 0..maxLineBytes.toLong()) {
                lineNumber++
                copyLine(buf, idx.toInt())
                consumeTerminator(buf)
                return LINE
            }
            if (idx > maxLineBytes || buf.size > maxLineBytes) {
                lineNumber++
                skipLongLine(buf)
                return SKIPPED
            }
            scanFrom = buf.size
            if (!source.request(buf.size + 1)) {
                if (buf.size == 0L) return EOF
                lineNumber++
                copyLine(buf, buf.size.toInt())
                return LINE
            }
        }
    }

    private fun consumeTerminator(buf: Buffer) {
        val t = buf.readByte()
        if (t == CR && source.request(1) && buf[0] == LF) buf.skip(1)
    }

    private fun copyLine(buf: Buffer, n: Int) {
        if (bytes.size < n) bytes = ByteArray(maxOf(n, minOf(bytes.size * 2, maxLineBytes)))
        var off = 0
        while (off < n) off += buf.read(bytes, off, n - off)
        // Trim ASCII whitespace and NULs on both ends. U+00A0 is trimmed after decoding.
        var start = 0
        var end = n
        while (start < end && isWs(bytes[start])) start++
        while (end > start && isWs(bytes[end - 1])) end--
        if (start > 0) System.arraycopy(bytes, start, bytes, 0, end - start)
        length = end - start
    }

    private fun skipLongLine(buf: Buffer) {
        skipped += SkippedLine(lineNumber, buf.snapshot(minOf(buf.size, 16L).toInt()).utf8().trimStart())
        while (true) {
            val idx = buf.indexOfElement(TERMINATORS, 0)
            if (idx >= 0) {
                buf.skip(idx)
                consumeTerminator(buf)
                return
            }
            buf.skip(buf.size)
            if (!source.request(1)) return
        }
    }

    private fun isWs(b: Byte): Boolean = b == SP || b == TAB || b == VT || b == FF || b == NUL

    companion object {
        private const val LINE = 0
        private const val SKIPPED = 1
        private const val EOF = 2
        private const val CR = '\r'.code.toByte()
        private const val LF = '\n'.code.toByte()
        private const val SP = ' '.code.toByte()
        private const val TAB = '\t'.code.toByte()
        private const val VT = 0x0B.toByte()
        private const val FF = 0x0C.toByte()
        private const val NUL = 0.toByte()
        private val TERMINATORS = "\r\n".encodeUtf8()
    }
}

/**
 * Transcodes a UTF-16 stream (after its BOM) to UTF-8 on the fly, so [LineReader] and the strict UTF-8
 * path handle UTF-16 playlists unchanged (§3.4 M1, the design's "Utf16LineReader"). Unpaired
 * surrogates become U+FFFD. A trailing odd byte is dropped.
 */
internal class Utf16ToUtf8Source(private val upstream: BufferedSource, private val bigEndian: Boolean) : Source {
    private var pendingHigh = -1

    override fun read(sink: Buffer, byteCount: Long): Long {
        val before = sink.size
        while (sink.size == before) {
            if (!upstream.request(2)) {
                if (pendingHigh >= 0) {
                    sink.writeUtf8CodePoint(0xFFFD)
                    pendingHigh = -1
                    break
                }
                return -1
            }
            val units = minOf(upstream.buffer.size / 2, maxOf(byteCount / 3, 1L))
            for (i in 0 until units) {
                val unit = if (bigEndian) upstream.readShort().toInt() and 0xFFFF else upstream.readShortLe().toInt() and 0xFFFF
                if (pendingHigh >= 0) {
                    if (unit in 0xDC00..0xDFFF) {
                        sink.writeUtf8CodePoint(0x10000 + ((pendingHigh - 0xD800) shl 10) + (unit - 0xDC00))
                        pendingHigh = -1
                        continue
                    }
                    sink.writeUtf8CodePoint(0xFFFD)
                    pendingHigh = -1
                }
                when (unit) {
                    in 0xD800..0xDBFF -> pendingHigh = unit
                    in 0xDC00..0xDFFF -> sink.writeUtf8CodePoint(0xFFFD)
                    else -> sink.writeUtf8CodePoint(unit)
                }
            }
        }
        return sink.size - before
    }

    override fun timeout(): Timeout = upstream.timeout()
    override fun close() = upstream.close()
}
