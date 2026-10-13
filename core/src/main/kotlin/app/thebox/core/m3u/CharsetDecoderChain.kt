package app.thebox.core.m3u

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Per-line text decoding (docs/design/m1-data-layer.md §4.3; reviewed 2026-10-13, S15).
 *
 * Order: forced charset → (BOM, handled by the parser before lines are read) → a non-UTF-8 charset hint →
 * strict UTF-8 per line. On the first line that is not valid UTF-8 a fallback is chosen once
 * (windows-1251 when the high bytes look Cyrillic, else windows-1252) and [onFallback] fires. Every
 * later line is still tried as strict UTF-8 first, so one corrupt line never garbles a UTF-8 file.
 */
internal class CharsetDecoderChain(forced: Charset?, hint: Charset?) {
    /** Non-null when every line is decoded with one fixed charset (forced, or a non-UTF-8 hint). */
    private val fixed: Charset? = forced ?: hint?.takeUnless { isUtf8(it) }
    private val fixedDecoder: CharsetDecoder? = fixed?.newDecoder()
        ?.onMalformedInput(CodingErrorAction.REPLACE)?.onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val utf8: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    private var fallbackDecoder: CharsetDecoder? = null
    private var chars: CharBuffer = CharBuffer.allocate(512)

    /** The fallback charset once chosen. */
    var fallback: Charset? = null
        private set

    /** Lines decoded with the fallback. */
    var fallbackLines: Int = 0
        private set

    /** Name reported in the summary: the fixed charset, the fallback if any line used it, else UTF-8. */
    val reportedName: String get() = (fixed ?: fallback?.takeIf { fallbackLines > 0 } ?: StandardCharsets.UTF_8).name()

    fun decode(bytes: ByteArray, length: Int, onFallback: (Charset) -> Unit): String {
        if (fixedDecoder != null) {
            return if (isAscii(bytes, length) && asciiCompatible(fixed!!)) String(bytes, 0, length, StandardCharsets.ISO_8859_1)
            else run(fixedDecoder, bytes, length)
        }
        if (isAscii(bytes, length)) return String(bytes, 0, length, StandardCharsets.ISO_8859_1)
        try {
            return run(utf8, bytes, length)
        } catch (_: CharacterCodingException) {
            // fall through
        }
        var fb = fallbackDecoder
        if (fb == null) {
            val cs = chooseFallback(bytes, length)
            fallback = cs
            fb = cs.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
            fallbackDecoder = fb
            onFallback(cs)
        }
        fallbackLines++
        return run(fb!!, bytes, length)
    }

    private fun run(decoder: CharsetDecoder, bytes: ByteArray, length: Int): String {
        decoder.reset()
        if (chars.capacity() < length) chars = CharBuffer.allocate(maxOf(length, chars.capacity() * 2))
        chars.clear()
        val input = ByteBuffer.wrap(bytes, 0, length)
        var r = decoder.decode(input, chars, true)
        if (r.isError) r.throwException()
        r = decoder.flush(chars)
        if (r.isError) r.throwException()
        chars.flip()
        return chars.toString()
    }

    companion object {
        val CP1251: Charset = Charset.forName("windows-1251")
        val CP1252: Charset = Charset.forName("windows-1252")

        fun isAscii(bytes: ByteArray, length: Int): Boolean {
            for (i in 0 until length) if (bytes[i] < 0) return false
            return true
        }

        private fun isUtf8(cs: Charset) = cs == StandardCharsets.UTF_8 || cs == StandardCharsets.US_ASCII

        private fun asciiCompatible(cs: Charset): Boolean {
            val n = cs.name().lowercase()
            return !(n.startsWith("utf-16") || n.startsWith("utf-32") || n.startsWith("x-utf-16") || n.startsWith("x-utf-32"))
        }

        /**
         * §4.3: windows-1251 when ≥ 70 % of the high bytes are 0xC0–0xFF and there are at least 3 of them.
         *
         * Added condition (WP-A deviation, reported to the lead): at least half of those 0xC0–0xFF bytes
         * must sit next to another high byte. Cyrillic words are runs of high bytes, while Latin accents
         * (`Français … Télé Québec`: four bytes, all in 0xE0–0xFF) are isolated between ASCII letters. Without
         * it the §4.3 rule decodes ordinary French or Spanish lines as Cyrillic.
         */
        fun chooseFallback(bytes: ByteArray, length: Int): Charset {
            var high = 0
            var cyr = 0
            var cyrInRuns = 0
            var i = 0
            while (i < length) {
                if (bytes[i] >= 0) { i++; continue }
                val start = i
                var runCyr = 0
                while (i < length && bytes[i] < 0) {
                    high++
                    if ((bytes[i].toInt() and 0xFF) >= 0xC0) runCyr++
                    i++
                }
                cyr += runCyr
                if (i - start >= 2) cyrInRuns += runCyr
            }
            return if (cyr >= 3 && cyr * 10 >= high * 7 && cyrInRuns * 2 >= cyr) CP1251 else CP1252
        }

        /** Accepts a bare charset name or a `Content-Type` value with `charset=`. Unknown names give null. */
        fun parseCharset(value: String?): Charset? {
            if (value.isNullOrBlank()) return null
            val i = value.indexOf("charset=", ignoreCase = true)
            var name = if (i >= 0) value.substring(i + 8) else if (value.contains('/') || value.contains(';')) return null else value
            val end = name.indexOfFirst { it == ';' || it == ' ' || it == ',' }
            if (end >= 0) name = name.substring(0, end)
            name = name.trim().trim('"', '\'')
            return try { Charset.forName(name) } catch (_: Exception) { null }
        }
    }
}
