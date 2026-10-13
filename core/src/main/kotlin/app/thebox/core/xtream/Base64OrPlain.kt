package app.thebox.core.xtream

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.io.encoding.Base64

/**
 * Per-response base64 detection for Xtream EPG `title`/`description` (docs/design/m1-data-layer.md §3.7.3).
 * Pure Kotlin `kotlin.io.encoding.Base64` (API 25 safe); `java.util.Base64` is API 26+ and is not used.
 */
object Base64OrPlain {
    private val SHAPE = Regex("^[A-Za-z0-9+/\\r\\n]+={0,2}$")
    private const val SAMPLE = 5

    /** True when a majority of the first ≤ 5 non-empty [titles] decode as base64 text. */
    fun isBase64Response(titles: Sequence<String?>): Boolean {
        val sample = titles.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.take(SAMPLE).toList()
        if (sample.isEmpty()) return false
        val decodable = sample.count { tryDecode(it) != null }
        return decodable * 2 > sample.size
    }

    /** In base64 mode: decode, falling back to the plain value on failure. Otherwise the value as is. */
    fun field(value: String?, base64: Boolean): String? {
        if (value == null) return null
        if (!base64) return value
        return tryDecode(value.trim()) ?: value
    }

    /** Decoded text when [s] is well-formed base64 of valid, mostly printable UTF-8; else null. */
    fun tryDecode(s: String): String? {
        if (s.isEmpty() || !SHAPE.matches(s)) return null
        val compact = s.filter { it != '\r' && it != '\n' }
        if (compact.length % 4 != 0) return null
        val bytes = try {
            Base64.Default.decode(compact)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        if (text.isEmpty()) return text
        var total = 0
        var control = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            total++
            if (Character.isISOControl(cp) && cp != '\t'.code && cp != '\n'.code && cp != '\r'.code) control++
            i += Character.charCount(cp)
        }
        return if ((total - control) * 10 >= total * 9) text else null
    }
}
