package app.thebox.core.sports

import java.io.File
import java.nio.ByteBuffer

/**
 * (NFL Stacked §2.2, WP-2 tests) Text widths in `:core` from the bundled fonts' own `hmtx` advances (`cmap` format 4), no
 * kerning: the method the design measured with ("TOUCHDOWN 229 at 48", NFL §5.2; "WSH 62.7 at 25.2", §2.3), so the
 * pure fit rules can be checked against the real glyph widths without Android. `:ui`'s fit tests measure the real
 * `Paint` on top of this.
 *
 * [width] is the advance sum at [px] plus the CSS letter-spacing ([trackEm] × px per character).
 */
class FontAdvances private constructor(
    private val upem: Int,
    private val advances: IntArray,
    private val glyphOf: Map<Int, Int>,
    /** The tabular figures' advance (the `zero.tf` glyph, `'tnum'`), or null when the font has none. */
    private val tabularUnits: Int?,
) {
    /** [tnum]: digits at the tabular advance, as the painters' `'tnum'` paints (scores, the clock). */
    fun width(text: String, px: Float, trackEm: Float = 0f, tnum: Boolean = false): Float {
        var units = 0L
        for (ch in text) {
            val tab = tabularUnits
            if (tnum && tab != null && ch in '0'..'9') { units += tab; continue }
            val g = glyphOf[ch.code] ?: 0
            units += advances[minOf(g, advances.size - 1)]
        }
        return units.toFloat() / upem * px + trackEm * px * text.length
    }

    companion object {
        private val cache = HashMap<String, FontAdvances>()

        /** The bundled font [name] (`ui/src/main/res/font/<name>.ttf`). */
        fun of(name: String): FontAdvances = cache.getOrPut(name) { load(find(name)) }

        val BOLD: FontAdvances get() = of("instrument_sans_bold")
        val BOLD_ITALIC: FontAdvances get() = of("instrument_sans_bold_italic")
        val BARLOW: FontAdvances get() = of("barlow_condensed_black_italic")

        private fun find(name: String): File {
            val rel = "ui/src/main/res/font/$name.ttf"
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                val f = File(dir, rel)
                if (f.isFile) return f
                dir = dir.parentFile
            }
            error("font $rel not found above ${File("").absolutePath}")
        }

        private fun load(file: File): FontAdvances {
            val b = ByteBuffer.wrap(file.readBytes())
            val n = b.getShort(4).toInt() and 0xFFFF
            val tables = HashMap<String, Int>()
            for (i in 0 until n) {
                val o = 12 + 16 * i
                val tag = String(ByteArray(4) { b.get(o + it) }, Charsets.US_ASCII)
                tables[tag] = b.getInt(o + 8)
            }
            fun u16(at: Int) = b.getShort(at).toInt() and 0xFFFF
            val upem = u16(tables.getValue("head") + 18)
            val nh = u16(tables.getValue("hhea") + 34)
            val hmtx = tables.getValue("hmtx")
            val adv = IntArray(nh) { u16(hmtx + 4 * it) }
            val cmap = tables.getValue("cmap")
            val glyphs = HashMap<Int, Int>()
            val nt = u16(cmap + 2)
            for (i in 0 until nt) {
                val pid = u16(cmap + 4 + 8 * i)
                val sub = cmap + b.getInt(cmap + 8 + 8 * i)
                if (u16(sub) != 4 || (pid != 0 && pid != 3)) continue
                val seg = u16(sub + 6) / 2
                val ends = sub + 14
                val starts = ends + 2 * seg + 2
                val deltas = starts + 2 * seg
                val ranges = deltas + 2 * seg
                for (k in 0 until seg) {
                    val s = u16(starts + 2 * k)
                    val e = minOf(u16(ends + 2 * k), 0x2100)
                    val d = b.getShort(deltas + 2 * k).toInt()
                    val r = u16(ranges + 2 * k)
                    for (c in s..e) {
                        val g = if (r == 0) (c + d) and 0xFFFF else {
                            val raw = u16(ranges + 2 * k + r + 2 * (c - s))
                            if (raw == 0) 0 else (raw + d) and 0xFFFF
                        }
                        glyphs[c] = g
                    }
                }
                break
            }
            // `post` format 2: the glyph names, for the tabular figures (`zero.tf`).
            var tabular: Int? = null
            val post = tables["post"]
            if (post != null && b.getInt(post) == 0x00020000) {
                val ng = u16(post + 32)
                val names = ArrayList<String>()
                val needed = (0 until ng).maxOf { u16(post + 34 + 2 * it) } - 257
                var p = post + 34 + 2 * ng
                while (p < b.capacity() && names.size < needed) {
                    val len = b.get(p).toInt() and 0xFF
                    if (p + 1 + len > b.capacity()) break
                    names += String(ByteArray(len) { b.get(p + 1 + it) }, Charsets.US_ASCII)
                    p += 1 + len
                }
                for (g in 0 until ng) {
                    val i = u16(post + 34 + 2 * g)
                    if (i >= 258 && names.getOrNull(i - 258) == "zero.tf") { tabular = adv[minOf(g, adv.size - 1)]; break }
                }
            }
            return FontAdvances(upem, adv, glyphs, tabular)
        }
    }
}
