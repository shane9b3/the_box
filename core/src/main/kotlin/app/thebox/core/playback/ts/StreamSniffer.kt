package app.thebox.core.playback.ts

import app.thebox.core.playback.Sniff
import app.thebox.core.playback.SniffResult
import app.thebox.core.playback.StreamSniffer

/**
 * First-bytes classifier (§3.3.3). Pure and allocation-free except for the returned [Sniff].
 *
 * Text verdicts (HLS playlist, HTML page) are checked first: they only look at the head of the body (after an
 * optional UTF-8 BOM and ASCII whitespace), and a page that happens to contain 'G' (0x47) at 188-byte spacing must
 * not read as TS. Then TS: the first offset `o < 188` with [TS_SYNC_RUN] sync bytes at 188-byte spacing. Without a
 * verdict, [SniffResult.NEED_MORE] until [SNIFF_MAX_BYTES] ([SniffResult.UNRECOGNIZED]) or end of stream
 * ([SniffResult.TINY] below [TS_MIN_SNIFF_BYTES], else [SniffResult.UNRECOGNIZED]).
 */
class StreamSnifferImpl : StreamSniffer {

    override fun sniff(buf: ByteArray, length: Int, endOfStream: Boolean): Sniff {
        require(length in 0..buf.size) { "length $length outside 0..${buf.size}" }
        val start = skipBomAndWhitespace(buf, length)

        if (prefix(buf, start, length, EXTM3U) == MATCH) return Sniff(SniffResult.HLS_PLAYLIST)
        for (tag in HTML_TAGS) if (prefix(buf, start, length, tag) == MATCH) return Sniff(SniffResult.HTML)
        if (prefix(buf, start, length, XML_DECL) == MATCH) {
            val searchEnd = minOf(length, start + XML_HTML_WINDOW)
            if (indexOfIgnoreCase(buf, start + XML_DECL.size, searchEnd, HTML_OPEN) >= 0) return Sniff(SniffResult.HTML)
        }

        val ts = tsOffset(buf, length)
        if (ts >= 0) return Sniff(SniffResult.TS, ts)

        if (endOfStream) return Sniff(if (length < TS_MIN_SNIFF_BYTES) SniffResult.TINY else SniffResult.UNRECOGNIZED)
        if (length >= SNIFF_MAX_BYTES) return Sniff(SniffResult.UNRECOGNIZED)
        // A partial "#EXTM3U"/tag, an "<?xml" prolog whose <html> is still to come, or (below TS_MIN_SNIFF_BYTES) a
        // sync run at a later offset may still decide it.
        return Sniff(SniffResult.NEED_MORE)
    }

    companion object {
        const val TS_PACKET_SIZE = 188
        const val TS_SYNC_BYTE: Byte = 0x47

        /** Bytes read before giving up with UNRECOGNIZED (§3.3.3, Appendix B). */
        const val SNIFF_MAX_BYTES = 2_048

        /** Consecutive sync bytes at 188-byte spacing that prove TS (Appendix B). */
        const val TS_SYNC_RUN = 3

        /** 188 × 3 + 1: enough to test every offset below 188 for a three-sync run (Appendix B). */
        const val TS_MIN_SNIFF_BYTES = 565

        /** `<?xml …>` must be followed by `<html` within this many bytes of the start to count as HTML. */
        const val XML_HTML_WINDOW = 512

        private const val NO = 0
        private const val PARTIAL = 1
        private const val MATCH = 2

        private val EXTM3U = ascii("#EXTM3U")
        private val XML_DECL = ascii("<?xml")
        private val HTML_OPEN = ascii("<html")
        private val HTML_TAGS = arrayOf(ascii("<html"), ascii("<!doctype"), ascii("<head"), ascii("<body"))

        private fun ascii(s: String): ByteArray = s.lowercase().toByteArray(Charsets.US_ASCII)

        private fun skipBomAndWhitespace(buf: ByteArray, length: Int): Int {
            var i = 0
            if (length >= 3 && buf[0] == 0xEF.toByte() && buf[1] == 0xBB.toByte() && buf[2] == 0xBF.toByte()) i = 3
            while (i < length) {
                val b = buf[i].toInt()
                if (b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D || b == 0x0B || b == 0x0C) i++ else break
            }
            return i
        }

        private fun lower(b: Byte): Int {
            val c = b.toInt() and 0xFF
            return if (c in 0x41..0x5A) c + 0x20 else c
        }

        /** Case-insensitive prefix test of [token] (lower case) at [start]: NO, PARTIAL (all available bytes match), MATCH. */
        private fun prefix(buf: ByteArray, start: Int, length: Int, token: ByteArray): Int {
            val avail = length - start
            if (avail <= 0) return PARTIAL
            val n = minOf(avail, token.size)
            for (i in 0 until n) if (lower(buf[start + i]) != token[i].toInt()) return NO
            return if (n == token.size) MATCH else PARTIAL
        }

        private fun indexOfIgnoreCase(buf: ByteArray, from: Int, to: Int, token: ByteArray): Int {
            var i = from
            while (i + token.size <= to) {
                var j = 0
                while (j < token.size && lower(buf[i + j]) == token[j].toInt()) j++
                if (j == token.size) return i
                i++
            }
            return -1
        }

        /** First offset below 188 that starts a [TS_SYNC_RUN] sync run within [length] bytes, or -1. */
        fun tsOffset(buf: ByteArray, length: Int): Int {
            val span = (TS_SYNC_RUN - 1) * TS_PACKET_SIZE
            var o = 0
            while (o < TS_PACKET_SIZE && o + span < length) {
                var k = 0
                while (k < TS_SYNC_RUN && buf[o + k * TS_PACKET_SIZE] == TS_SYNC_BYTE) k++
                if (k == TS_SYNC_RUN) return o
                o++
            }
            return -1
        }
    }
}
