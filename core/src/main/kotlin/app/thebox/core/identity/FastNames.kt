package app.thebox.core.identity

import app.thebox.core.text.NameNormalizer

/**
 * Allocation-light, regex-free equivalents of [NameNormalizer.forKey] and [NameNormalizer.forGroup] for
 * ASCII input, used on the per-row key path (§6.6). `NameNormalizer` (lead-owned) runs four regexes per
 * call and compiles a new `Regex` in `collapse()` on every call, which dominated the 200k import.
 *
 * Non-ASCII input always delegates to [NameNormalizer], so NFKD folding stays exactly the shared one.
 * For ASCII input the output is identical by construction; `FastNamesParityTest` checks it against
 * [NameNormalizer] on the §3.1 table and on 50k random strings. Persisted keys depend on this: never
 * change one side without the other.
 */
internal object FastNames {
    /**
     * `NameNormalizer.QUALITY` alternatives in regex order, each expanded into its literal variants in the
     * regex's greedy order (`h\.?26[45]` → `h.264`, `h.265`, `h264`, `h265`; `hdr10\+?` → `hdr10+`, `hdr10`).
     * At each position the regex tries them in this order and keeps the first whose end is not followed
     * by a letter or digit.
     */
    private val QUALITY: Array<String> = arrayOf(
        "uhd", "fhd", "qhd", "hd", "sd", "4k", "8k", "hevc", "h.264", "h.265", "h264", "h265", "x264", "x265", "avc", "2160p",
        "1080p", "1080i", "720p", "576p", "576i", "50 fps", "50fps", "60 fps", "60fps", "hdr10+", "hdr10", "hdr", "hlg", "backup",
        "vip", "raw",
    )
    private val QUALITY_FIRST = BooleanArray(128).also { t -> for (q in QUALITY) t[q[0].code] = true }

    fun forKey(name: String): String {
        if (!isAscii(name)) return NameNormalizer.forKey(name)
        val n = name.length
        val sb = StringBuilder(n)
        var pendingSpace = false
        var i = 0
        while (i < n) {
            val c = lower(name[i])
            if (QUALITY_FIRST[c.code] && (i == 0 || !isAlnum(lower(name[i - 1])))) {
                val end = qualityEnd(name, i)
                if (end > i) {
                    pendingSpace = true // replaced by " ", then folded into the NON_WORD run
                    i = end
                    continue
                }
            }
            if (isAlnum(c) || c == '+') {
                if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                pendingSpace = false
                sb.append(c)
            } else {
                pendingSpace = true
            }
            i++
        }
        if (sb.isNotEmpty()) return sb.toString()
        // Fallback of forKey: collapse(NON_WORD(folded)), else folded.trim().
        val words = forGroup(name)
        return words
    }

    /** `fold` + NON_WORD → " " + collapse; `ifEmpty { folded.trim() }`. */
    fun forGroup(name: String): String {
        if (!isAscii(name)) return NameNormalizer.forGroup(name)
        val n = name.length
        val sb = StringBuilder(n)
        var pendingSpace = false
        for (i in 0 until n) {
            val c = lower(name[i])
            if (isAlnum(c) || c == '+') {
                if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                pendingSpace = false
                sb.append(c)
            } else {
                pendingSpace = true
            }
        }
        if (sb.isNotEmpty()) return sb.toString()
        return lowerAll(name).trim()
    }

    /** End index of the first QUALITY variant matching at [i] with no letter/digit after it, or -1. */
    private fun qualityEnd(s: String, i: Int): Int {
        for (q in QUALITY) {
            val len = q.length
            if (i + len > s.length) continue
            var ok = true
            for (j in 0 until len) {
                if (lower(s[i + j]) != q[j]) { ok = false; break }
            }
            if (!ok) continue
            val e = i + len
            if (e < s.length && isAlnum(lower(s[e]))) continue
            return e
        }
        return -1
    }

    private fun isAscii(s: String): Boolean {
        for (ch in s) if (ch.code >= 0x80) return false
        return true
    }

    private fun lower(c: Char): Char = if (c in 'A'..'Z') (c.code + 32).toChar() else c

    private fun lowerAll(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(lower(c))
        return sb.toString()
    }

    private fun isAlnum(c: Char): Boolean = c in 'a'..'z' || c in '0'..'9'
}
