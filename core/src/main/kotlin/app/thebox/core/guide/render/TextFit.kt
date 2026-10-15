package app.thebox.core.guide.render

import app.thebox.core.guide.GuideTextMeasurer
import app.thebox.core.guide.GuideTextStyle
import java.text.BreakIterator

/**
 * Ellipsis cache (docs/design/m3-guide.md §3.6.4; review B4, S6). Direct-mapped, [slots] entries (rounded up to a
 * power of two), main thread only.
 *
 * - **Key:** `(content hash of the string, available px (floored), style)`. Never a generation-local `textId` or a
 *   `rowId` (B4). Each entry also stores the string length it was computed for and is ignored on a mismatch, and
 *   callers always draw `min(n, text.length)`, so a hash collision can never index past the string.
 * - **Value:** `n` (chars of the logical start that fit before "…"), the width of `text[0, n)` ([lastWidth]) so no
 *   `measureText` runs per frame (S6), and whether the string is right-to-left ([lastRtl]).
 * - **Grapheme guard (S6):** `n` never splits a surrogate pair, a combining sequence, a variation selector, an
 *   emoji modifier or a ZWJ sequence; strings flagged complex (or containing regional indicators) are cut at the
 *   previous `BreakIterator.getCharacterInstance` boundary (allocates, only on a miss).
 * - **RTL:** detected on a miss from the first strong directional character; the renderer right-aligns such text
 *   with the ellipsis on the left.
 *
 * Allocation: none on a hit; none on a miss for plain strings.
 */
class TextFit(private val measurer: GuideTextMeasurer, slots: Int) {
    private val size: Int = Integer.highestOneBit(maxOf(2, slots) - 1) shl 1
    private val mask = size - 1
    private val keys = LongArray(size)
    private val px = IntArray(size)
    private val style = ByteArray(size)
    private val len = IntArray(size) { -1 }
    private val cut = IntArray(size)
    private val cutW = FloatArray(size)
    private val rtl = BooleanArray(size)

    /** Width (device px, at the measurer's scale × [widthScale]) of `text[0, n)` from the last [fit]. */
    var lastWidth: Float = 0f
        private set

    /** Whether the text of the last [fit] is right-to-left. */
    var lastRtl: Boolean = false
        private set

    var hits: Long = 0; private set
    var misses: Long = 0; private set

    /** Measurer px → frame px (the renderer sets it when the measurer's scale differs from the metrics'). */
    var widthScale: Float = 1f
        set(value) { if (value != field) { field = value; clear() } }

    /**
     * Chars of [text] to draw in [availPx]: `text.length` when the whole string fits ([lastWidth] = its width), else
     * the count that fits in `availPx − ellipsisPx` at a grapheme boundary ([lastWidth] = width of that prefix).
     * [complex]: the string needs the `BreakIterator` path (`CellFlags.COMPLEX`).
     */
    fun fit(text: String, key: Long, style: GuideTextStyle, availPx: Float, ellipsisPx: Float, complex: Boolean = false): Int {
        val p = availPx.toInt()
        val st = style.ordinal.toByte()
        val slot = slotOf(key, p, st)
        if (keys[slot] == key && px[slot] == p && this.style[slot] == st && len[slot] == text.length) {
            hits++
            lastWidth = cutW[slot]; lastRtl = rtl[slot]
            return minOf(cut[slot], text.length)
        }
        misses++
        val ws = widthScale
        val full = measurer.width(text, 0, text.length, style) * ws
        val n: Int
        val w: Float
        if (full <= p) {
            n = text.length; w = full
        } else {
            val room = p - ellipsisPx
            if (room <= 0f) {
                n = 0; w = 0f
            } else {
                var k = measurer.fit(text, 0, text.length, style, room / ws)
                k = backOff(text, k, complex)
                n = k
                w = if (k == 0) 0f else measurer.width(text, 0, k, style) * ws
            }
        }
        val r = isRtl(text)
        keys[slot] = key; px[slot] = p; this.style[slot] = st; len[slot] = text.length
        cut[slot] = n; cutW[slot] = w; rtl[slot] = r
        lastWidth = w; lastRtl = r
        return n
    }

    fun clear() {
        len.fill(-1)
    }

    private fun slotOf(key: Long, p: Int, st: Byte): Int {
        var h = key xor (p.toLong() * -0x61c8864680b583ebL) xor (st.toLong() shl 56)
        h = h xor (h ushr 29)
        h *= -0x4b47d5b1b2f6b8b5L
        h = h xor (h ushr 32)
        return h.toInt() and mask
    }

    companion object {
        private const val ZWJ = '‍'

        /** Backs [n] off to a grapheme boundary of [text] (S6). */
        fun backOff(text: String, n: Int, complex: Boolean): Int {
            var k = n.coerceIn(0, text.length)
            if (k == 0 || k == text.length) return k
            if (complex || needsBreakIterator(text, k)) {
                val bi = BreakIterator.getCharacterInstance()
                bi.setText(text)
                return if (bi.isBoundary(k)) k else maxOf(0, bi.preceding(k))
            }
            while (k > 0 && k < text.length && !isBoundary(text, k)) k--
            return k
        }

        /** A cut at [k] is safe when it splits neither a surrogate pair nor a combining/ZWJ/modifier sequence. */
        private fun isBoundary(text: String, k: Int): Boolean {
            val c = text[k]
            val prev = text[k - 1]
            if (Character.isLowSurrogate(c) && Character.isHighSurrogate(prev)) return false
            if (prev == ZWJ || c == ZWJ) return false
            if (c in '︀'..'️') return false
            when (Character.getType(c).toByte()) {
                Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> return false
            }
            if (Character.isHighSurrogate(c) && k + 1 < text.length) {
                val cp = Character.toCodePoint(c, text[k + 1])
                if (cp in 0x1F3FB..0x1F3FF) return false                   // emoji skin-tone modifier
                if (cp in 0xE0020..0xE007F) return false                   // emoji tag sequence
            }
            return true
        }

        /** Regional-indicator pairs (flags) need pair counting: leave them to BreakIterator. */
        private fun needsBreakIterator(text: String, k: Int): Boolean {
            if (k + 1 < text.length && Character.isHighSurrogate(text[k])) {
                val cp = Character.toCodePoint(text[k], text[k + 1])
                if (cp in 0x1F1E6..0x1F1FF) return true
            }
            return false
        }

        /** True when the first strong directional character is right-to-left. Allocation-free. */
        fun isRtl(text: String): Boolean {
            for (i in 0 until text.length) {
                when (Character.getDirectionality(text[i])) {
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING, Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE -> return true
                    Character.DIRECTIONALITY_LEFT_TO_RIGHT, Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
                    Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE -> return false
                }
            }
            return false
        }
    }
}
