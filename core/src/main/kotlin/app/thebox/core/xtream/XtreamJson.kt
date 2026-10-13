package app.thebox.core.xtream

import app.thebox.core.common.NotASourceKind
import app.thebox.core.common.SourceError
import app.thebox.core.common.SourceException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.decodeToSequence
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/** What the first significant byte of a response says it is (§3.7.1). Content-Type is never consulted. */
enum class XtreamPayloadKind { ARRAY, OBJECT, HTML, EMPTY, OTHER }

/**
 * Transport-level JSON handling for Xtream responses (docs/design/m1-data-layer.md §3.7.1): BOM and whitespace
 * stripping, first-byte sniffing, and tree-per-item list streaming. One `JsonElement` is alive per item, for
 * both the array form and the `{"0":{…},"1":{…}}` object form.
 */
object XtreamJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowSpecialFloatingPointValues = true
    }

    /** A stream positioned at the first significant byte, plus what that byte says. */
    class Sniffed(val stream: InputStream, val kind: XtreamPayloadKind)

    /** Strips a UTF-8 BOM and leading whitespace without consuming the first significant byte. */
    fun sniff(input: InputStream): Sniffed {
        val s = if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024)
        s.mark(3)
        val b0 = s.read()
        val b1 = s.read()
        val b2 = s.read()
        if (!(b0 == 0xEF && b1 == 0xBB && b2 == 0xBF)) s.reset()
        while (true) {
            s.mark(1)
            val b = s.read()
            if (b == -1) return Sniffed(s, XtreamPayloadKind.EMPTY)
            if (b == ' '.code || b == '\t'.code || b == '\n'.code || b == '\r'.code) continue
            s.reset()
            val kind = when (b) {
                '['.code -> XtreamPayloadKind.ARRAY
                '{'.code -> XtreamPayloadKind.OBJECT
                '<'.code -> XtreamPayloadKind.HTML
                else -> XtreamPayloadKind.OTHER
            }
            return Sniffed(s, kind)
        }
    }

    /** Sniff from a byte prefix (used by the client on a downloaded file). */
    fun sniffKind(prefix: ByteArray, length: Int = prefix.size): XtreamPayloadKind {
        var i = 0
        if (length >= 3 && prefix[0] == 0xEF.toByte() && prefix[1] == 0xBB.toByte() && prefix[2] == 0xBF.toByte()) i = 3
        while (i < length) {
            when (prefix[i].toInt().toChar()) {
                ' ', '\t', '\n', '\r' -> i++
                '[' -> return XtreamPayloadKind.ARRAY
                '{' -> return XtreamPayloadKind.OBJECT
                '<' -> return XtreamPayloadKind.HTML
                else -> return XtreamPayloadKind.OTHER
            }
        }
        return XtreamPayloadKind.EMPTY
    }

    /**
     * Lazily yields every item of a list response (array or object form). The sequence must be consumed while
     * [input] is open. HTML → `NotASource(HTML_PAGE)`, empty → `NotASource(EMPTY)`, anything else that is not
     * JSON → `NotASource(WRONG_FORMAT)`. A syntax error mid-list → `Parse`.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun items(input: InputStream): Sequence<JsonElement> {
        val sniffed = sniff(input)
        val stream = when (sniffed.kind) {
            XtreamPayloadKind.ARRAY -> sniffed.stream
            XtreamPayloadKind.OBJECT -> ObjectValuesAsArrayStream(sniffed.stream)
            XtreamPayloadKind.HTML -> throw SourceException(SourceError.NotASource(NotASourceKind.HTML_PAGE))
            XtreamPayloadKind.EMPTY -> throw SourceException(SourceError.NotASource(NotASourceKind.EMPTY))
            XtreamPayloadKind.OTHER -> throw SourceException(SourceError.NotASource(NotASourceKind.WRONG_FORMAT))
        }
        val inner = guard { json.decodeToSequence(stream, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED) }
        return Sequence {
            val it = inner.iterator()
            object : Iterator<JsonElement> {
                override fun hasNext(): Boolean = guard { it.hasNext() }
                override fun next(): JsonElement = guard { it.next() }
            }
        }
    }

    /** Decodes a whole (small) response as one tree: login, series/vod info, EPG listings. */
    fun tree(input: InputStream): JsonElement {
        val sniffed = sniff(input)
        when (sniffed.kind) {
            XtreamPayloadKind.HTML -> throw SourceException(SourceError.NotASource(NotASourceKind.HTML_PAGE))
            XtreamPayloadKind.EMPTY -> throw SourceException(SourceError.NotASource(NotASourceKind.EMPTY))
            XtreamPayloadKind.OTHER -> throw SourceException(SourceError.NotASource(NotASourceKind.WRONG_FORMAT))
            else -> Unit
        }
        return guard { decodeTree(sniffed.stream) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun decodeTree(stream: InputStream): JsonElement =
        json.decodeFromStream(JsonElement.serializer(), stream)

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: SerializationException) {
        throw SourceException(SourceError.Parse(e.message?.take(200) ?: "invalid JSON"), e)
    } catch (e: IllegalArgumentException) {
        throw SourceException(SourceError.Parse(e.message?.take(200) ?: "invalid JSON"), e)
    } catch (e: IOException) {
        throw SourceException(SourceError.Parse(e.message?.take(200) ?: "read failed"), e)
    }
}

/**
 * Rewrites a top-level JSON object `{"k1":v1,"k2":v2}` into the array `[v1,v2]` on the fly, byte by byte, so the
 * object form of an Xtream list streams through the same `decodeToSequence` path as the array form and never
 * builds the whole tree (review S5). Keys are dropped. The input must be positioned at the opening `{`.
 * All structural characters are ASCII, so UTF-8 multi-byte sequences pass through untouched.
 */
internal class ObjectValuesAsArrayStream(private val src: InputStream) : InputStream() {
    private enum class State { START, KEY_EXPECT, IN_KEY, COLON, VALUE, DONE }

    private var state = State.START
    private var depth = 0
    private var inString = false
    private var escape = false

    override fun read(): Int {
        while (true) {
            val b = src.read()
            if (b == -1) {
                // Unterminated object: close the array so the decoder reports what it has, or a clean error.
                if (state != State.DONE && state != State.START) {
                    state = State.DONE
                    return ']'.code
                }
                return -1
            }
            when (state) {
                State.START -> {
                    if (b == '{'.code) {
                        state = State.KEY_EXPECT
                        depth = 1
                        return '['.code
                    }
                    if (isWs(b)) continue
                    state = State.DONE
                    return b
                }
                State.KEY_EXPECT -> when (b) {
                    '"'.code -> { state = State.IN_KEY; escape = false }
                    '}'.code -> { state = State.DONE; return ']'.code }
                    else -> Unit // whitespace, stray commas, unquoted junk: skipped
                }
                State.IN_KEY -> when {
                    escape -> escape = false
                    b == '\\'.code -> escape = true
                    b == '"'.code -> state = State.COLON
                }
                State.COLON -> if (b == ':'.code) { state = State.VALUE; inString = false; escape = false }
                State.VALUE -> {
                    if (inString) {
                        when {
                            escape -> escape = false
                            b == '\\'.code -> escape = true
                            b == '"'.code -> inString = false
                        }
                        return b
                    }
                    when (b) {
                        '"'.code -> { inString = true; return b }
                        '{'.code, '['.code -> { depth++; return b }
                        '}'.code, ']'.code -> {
                            if (depth == 1) { state = State.DONE; return ']'.code }
                            depth--
                            return b
                        }
                        ','.code -> {
                            if (depth == 1) state = State.KEY_EXPECT
                            return b
                        }
                        else -> return b
                    }
                }
                State.DONE -> return b
            }
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var n = 0
        while (n < len) {
            val c = read()
            if (c == -1) break
            b[off + n] = c.toByte()
            n++
        }
        return if (n == 0) -1 else n
    }

    override fun close() = src.close()

    private fun isWs(b: Int) = b == ' '.code || b == '\t'.code || b == '\n'.code || b == '\r'.code
}
