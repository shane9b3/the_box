package app.thebox.core.xmltv

import app.thebox.core.common.SourceError
import app.thebox.core.common.SourceException
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Fails the parse when more than [maxGap] bytes pass without an element tag (security review F1).
 *
 * A pull parser buffers one whole text token, and `next()` joins text across comments, CDATA and
 * processing instructions. So the counter is reset only by `<` followed by a name or `/`, and never
 * inside a comment, CDATA section or processing instruction. That bounds every text event and every
 * tag's attributes the parser can hand back. Zero bytes are ignored when looking for markup, so
 * UTF-16 input is recognised too. A UTF-16 guide can be rejected wrongly only if a rare CJK character whose
 * bytes read as `<!` or `<?` (U+3C21, U+3C3F) is followed by one that completes a comment or CDATA opener.
 */
internal class MarkupGapGuard(input: InputStream, private val maxGap: Int) : FilterInputStream(input) {
    private enum class State { TEXT, LT, BANG, BANG_DASH, COMMENT, CDATA, PI }

    private var state = State.TEXT
    private var gap = 0L
    private var p1 = 0
    private var p2 = 0

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) see(b)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        for (k in off until off + n) see(b[k].toInt() and 0xFF)
        return n
    }

    override fun skip(n: Long): Long {
        val buf = ByteArray(minOf(n, 8192L).toInt().coerceAtLeast(1))
        var left = n
        while (left > 0) {
            val r = read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (r < 0) break
            left -= r
        }
        return n - left
    }

    override fun markSupported(): Boolean = false

    private fun see(b: Int) {
        if (++gap > maxGap) {
            throw SourceException(SourceError.Unsupported("guide has over ${maxGap / 1024} KiB of text without a tag"))
        }
        if (b == 0) return
        val before = state
        when (state) {
            State.TEXT -> if (b == '<'.code) state = State.LT
            State.LT -> state = when (b) {
                '!'.code -> State.BANG
                '?'.code -> State.PI
                else -> {
                    gap = 0
                    State.TEXT
                }
            }
            State.BANG -> state = when (b) {
                '-'.code -> State.BANG_DASH
                '['.code -> State.CDATA
                else -> State.TEXT
            }
            State.BANG_DASH -> state = if (b == '-'.code) State.COMMENT else State.TEXT
            State.COMMENT -> if (b == '>'.code && p1 == '-'.code && p2 == '-'.code) state = State.TEXT
            State.CDATA -> if (b == '>'.code && p1 == ']'.code && p2 == ']'.code) state = State.TEXT
            State.PI -> if (b == '>'.code && p1 == '?'.code) state = State.TEXT
        }
        if (state != before && (state == State.COMMENT || state == State.CDATA || state == State.PI)) {
            p1 = 0
            p2 = 0
            return
        }
        p2 = p1
        p1 = b
    }
}
