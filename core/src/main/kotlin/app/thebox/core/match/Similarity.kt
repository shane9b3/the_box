package app.thebox.core.match

import app.thebox.core.text.NameNormalizer

/**
 * String similarity for the EPG matcher (docs/design/m1-data-layer.md §3.6.3). Inputs are
 * [NameNormalizer.forMatch] strings. Callers prepare each name once ([Prepared]) and compare prepared
 * forms many times through a [Scorer], which owns its scratch buffers (no allocation per comparison).
 *
 * ```
 * sim(a, b) = 0                                          if digitSet(a) != digitSet(b)
 *           = max(jaroWinkler(compact a, compact b), tokenSetRatio(tokens a, tokens b))
 *             × 0.85 when both country hints are known and differ
 * ```
 */
object Similarity {
    /** Multiplier applied when both sides carry a country hint and the hints differ. */
    const val COUNTRY_PENALTY = 0.85f

    /** One normalised name, ready for repeated comparisons. */
    class Prepared(
        /** forMatch form without spaces. */
        val compact: String,
        /** Distinct tokens, sorted. */
        val tokens: Array<String>,
        /** Canonical signature of the digit set: equal signatures ⇔ equal digit sets. */
        val digitSig: String,
        val countryHint: String?,
    )

    fun prepare(norm: String, countryHint: String?, intern: ((String) -> String)? = null): Prepared {
        val raw = NameNormalizer.tokens(norm)
        val tokens = if (raw.size <= 1) raw.toTypedArray() else raw.distinct().sorted().toTypedArray()
        if (intern != null) for (i in tokens.indices) tokens[i] = intern(tokens[i])
        return Prepared(NameNormalizer.compact(norm), tokens, digitSignature(norm), countryHint)
    }

    /** Convenience for tests and one-off comparisons (allocates a [Scorer]). */
    fun sim(a: Prepared, b: Prepared): Float = Scorer().sim(a, b)

    fun sim(a: String, b: String): Float = sim(prepare(a, null), prepare(b, null))

    fun countryConflict(a: String?, b: String?): Boolean = a != null && b != null && a != b

    /**
     * Set of maximal digit runs (leading zeros dropped) plus "+N"-style tokens, as a canonical string.
     * "sky sports 1" → "1", "channel 4 +1" → "+1,1,4", "channel 4" → "4", "bbc one" → "".
     */
    fun digitSignature(s: String): String {
        var parts: MutableList<String>? = null
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c.isDigit()) {
                val start = i
                while (i < n && s[i].isDigit()) i++
                val run = s.substring(start, i).trimStart('0').ifEmpty { "0" }
                if (parts == null) parts = ArrayList(2)
                parts += run
                if (start > 0 && s[start - 1] == '+' && (start == 1 || s[start - 2] == ' ')) parts += "+$run"
            } else {
                i++
            }
        }
        if (parts == null) return ""
        if (parts.size == 1) return parts[0]
        return parts.distinct().sorted().joinToString(",")
    }

    fun jaroWinkler(a: String, b: String, p: Float = 0.1f, maxPrefix: Int = 4): Float = Scorer().jaroWinkler(a, b, p, maxPrefix)

    /** `1 − levenshtein(x, y) / max(|x|, |y|)`. */
    fun ratio(x: String, y: String): Float {
        val max = maxOf(x.length, y.length)
        if (max == 0) return 1f
        return 1f - levenshtein(x, y).toFloat() / max
    }

    fun levenshtein(x: String, y: String): Int = Scorer().levenshtein(x.toCharArray(), x.length, y.toCharArray(), y.length, Int.MAX_VALUE)

    /** fuzzywuzzy token-set ratio over sorted distinct token arrays. */
    fun tokenSetRatio(a: Array<String>, b: Array<String>): Float = Scorer().tokenSetRatio(a, b, 0f)

    /**
     * Allocation-free comparisons. [sim] with a `floor` may return any value below `floor` instead of the
     * exact score once an upper bound proves the exact score cannot reach it; scores at or above `floor`
     * are exact. Not thread-safe.
     */
    class Scorer {
        private var ma = BooleanArray(64)
        private var mb = BooleanArray(64)
        private var row0 = IntArray(64)
        private var row1 = IntArray(64)
        private var ra = CharArray(128)
        private var rb = CharArray(128)
        private val hist = IntArray(HIST)

        fun sim(a: Prepared, b: Prepared, floor: Float = 0f): Float {
            if (a.digitSig != b.digitSig) return 0f
            val penalty = if (countryConflict(a.countryHint, b.countryHint)) COUNTRY_PENALTY else 1f
            if (a.compact == b.compact) return penalty
            // Floor on the unpenalised score.
            val need = if (floor <= 0f) 0f else floor / penalty
            val jwUb = jaroWinklerUpperBound(a.compact, b.compact)
            var best = 0f
            if (jwUb >= need) best = jaroWinkler(a.compact, b.compact)
            if (best < 1f) {
                val ts = tokenSetRatio(a.tokens, b.tokens, maxOf(need, best))
                if (ts > best) best = ts
            }
            return best * penalty
        }

        /** Exact Jaro-Winkler (p = 0.1, prefix ≤ 4 unless given). */
        fun jaroWinkler(a: String, b: String, p: Float = 0.1f, maxPrefix: Int = 4): Float {
            if (a == b) return 1f
            val la = a.length
            val lb = b.length
            if (la == 0 || lb == 0) return 0f
            if (ma.size < la) ma = BooleanArray(la * 2) else ma.fill(false, 0, la)
            if (mb.size < lb) mb = BooleanArray(lb * 2) else mb.fill(false, 0, lb)
            val window = maxOf(0, maxOf(la, lb) / 2 - 1)
            var matches = 0
            for (i in 0 until la) {
                val lo = maxOf(0, i - window)
                val hi = minOf(lb - 1, i + window)
                val c = a[i]
                var j = lo
                while (j <= hi) {
                    if (!mb[j] && b[j] == c) {
                        ma[i] = true; mb[j] = true; matches++
                        break
                    }
                    j++
                }
            }
            if (matches == 0) return 0f
            var t = 0
            var k = 0
            for (i in 0 until la) {
                if (!ma[i]) continue
                while (!mb[k]) k++
                if (a[i] != b[k]) t++
                k++
            }
            val m = matches.toFloat()
            val jaro = (m / la + m / lb + (m - t / 2f) / m) / 3f
            return jaro + commonPrefix(a, b, maxPrefix) * p * (1f - jaro)
        }

        /** Upper bound from the character multiset overlap (m ≤ Σ min counts, t ≥ 0) and the exact prefix. */
        private fun jaroWinklerUpperBound(a: String, b: String): Float {
            val la = a.length
            val lb = b.length
            if (la == 0 || lb == 0) return 0f
            hist.fill(0)
            for (i in 0 until la) hist[a[i].code and (HIST - 1)]++
            var m = 0
            for (i in 0 until lb) {
                val h = b[i].code and (HIST - 1)
                if (hist[h] > 0) { hist[h]--; m++ }
            }
            if (m == 0) return 0f
            val mf = m.toFloat()
            val jaro = (mf / la + mf / lb + 1f) / 3f
            return jaro + commonPrefix(a, b, 4) * 0.1f * (1f - jaro)
        }

        private fun commonPrefix(a: String, b: String, max: Int): Int {
            var prefix = 0
            val lim = minOf(max, a.length, b.length)
            while (prefix < lim && a[prefix] == b[prefix]) prefix++
            return prefix
        }

        /**
         * fuzzywuzzy token-set ratio. Exact when the result is ≥ [floor]. Uses ratio(I, RA) = |I| / |RA|
         * (I is a prefix of RA) and computes the Levenshtein of RA/RB only when its bound can still win.
         */
        fun tokenSetRatio(a: Array<String>, b: Array<String>, floor: Float): Float {
            // Pass 1: lengths only.
            var interLen = 0; var interN = 0
            var aLen = 0; var aN = 0
            var bLen = 0; var bN = 0
            var i = 0; var j = 0
            while (i < a.size || j < b.size) {
                val cmp = when { i == a.size -> 1; j == b.size -> -1; else -> a[i].compareTo(b[j]) }
                when {
                    cmp == 0 -> { interLen += a[i].length; interN++; i++; j++ }
                    cmp < 0 -> { aLen += a[i].length; aN++; i++ }
                    else -> { bLen += b[j].length; bN++; j++ }
                }
            }
            val lI = if (interN == 0) 0 else interLen + interN - 1
            val lDa = if (aN == 0) 0 else aLen + aN - 1
            val lDb = if (bN == 0) 0 else bLen + bN - 1
            val lRa = lI + lDa + if (lI > 0 && lDa > 0) 1 else 0
            val lRb = lI + lDb + if (lI > 0 && lDb > 0) 1 else 0
            var best = 0f
            if (interN > 0) best = maxOf(ratioOfPrefix(lI, lRa), ratioOfPrefix(lI, lRb))
            val maxLen = maxOf(lRa, lRb)
            if (maxLen == 0) return 1f
            val levUb = 1f - kotlin.math.abs(lRa - lRb).toFloat() / maxLen
            val target = maxOf(best, floor)
            if (levUb <= best || levUb < target) return best
            // Pass 2: materialise RA and RB into the scratch buffers, then a banded Levenshtein.
            if (ra.size < lRa) ra = CharArray(lRa * 2)
            if (rb.size < lRb) rb = CharArray(lRb * 2)
            var pa = 0; var pb = 0
            i = 0; j = 0
            // Intersection first (sorted), into both.
            while (i < a.size && j < b.size) {
                val cmp = a[i].compareTo(b[j])
                when {
                    cmp == 0 -> { pa = put(ra, pa, a[i]); pb = put(rb, pb, a[i]); i++; j++ }
                    cmp < 0 -> i++
                    else -> j++
                }
            }
            pa = appendDiff(a, b, ra, pa)
            pb = appendDiff(b, a, rb, pb)
            val maxDist = ((1f - target) * maxLen).toInt()
            val d = levenshtein(ra, pa, rb, pb, maxDist)
            val lev = 1f - d.toFloat() / maxLen
            return maxOf(best, lev)
        }

        private fun ratioOfPrefix(lI: Int, lR: Int): Float = if (lR == 0) 1f else lI.toFloat() / lR

        private fun put(buf: CharArray, at: Int, token: String): Int {
            var p = at
            if (p > 0) buf[p++] = ' '
            token.toCharArray(buf, p, 0, token.length)
            return p + token.length
        }

        /** Appends the sorted tokens of [x] that are not in [y]. */
        private fun appendDiff(x: Array<String>, y: Array<String>, buf: CharArray, at: Int): Int {
            var p = at
            var i = 0; var j = 0
            while (i < x.size) {
                val cmp = if (j == y.size) -1 else x[i].compareTo(y[j])
                when {
                    cmp == 0 -> { i++; j++ }
                    cmp < 0 -> { p = put(buf, p, x[i]); i++ }
                    else -> j++
                }
            }
            return p
        }

        /** True when [a] and [b] are within [max] edits (insert, delete, substitute). */
        fun withinEdits(a: String, b: String, max: Int): Boolean {
            if (kotlin.math.abs(a.length - b.length) > max) return false
            if (ra.size < a.length) ra = CharArray(a.length * 2)
            if (rb.size < b.length) rb = CharArray(b.length * 2)
            a.toCharArray(ra, 0, 0, a.length)
            b.toCharArray(rb, 0, 0, b.length)
            return levenshtein(ra, a.length, rb, b.length, max) <= max
        }

        /** Levenshtein distance of x[0, lx) and y[0, ly); returns any value > [maxDist] once it is exceeded. */
        fun levenshtein(x: CharArray, lx: Int, y: CharArray, ly: Int, maxDist: Int): Int {
            if (lx == 0) return ly
            if (ly == 0) return lx
            if (kotlin.math.abs(lx - ly) > maxDist) return maxDist + 1
            if (row0.size <= ly) { row0 = IntArray(ly * 2 + 1); row1 = IntArray(ly * 2 + 1) }
            var prev = row0
            var cur = row1
            for (j in 0..ly) prev[j] = j
            for (i in 1..lx) {
                cur[0] = i
                var rowMin = i
                val cx = x[i - 1]
                for (j in 1..ly) {
                    val cost = if (cx == y[j - 1]) 0 else 1
                    var v = prev[j - 1] + cost
                    val del = prev[j] + 1
                    if (del < v) v = del
                    val ins = cur[j - 1] + 1
                    if (ins < v) v = ins
                    cur[j] = v
                    if (v < rowMin) rowMin = v
                }
                if (rowMin > maxDist) return maxDist + 1
                val tmp = prev; prev = cur; cur = tmp
            }
            return prev[ly]
        }

        private companion object {
            const val HIST = 64
        }
    }
}
