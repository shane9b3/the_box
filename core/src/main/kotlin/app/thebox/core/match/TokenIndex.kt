package app.thebox.core.match

/**
 * Inverted token index over library subjects (docs/design/m1-data-layer.md §3.6.4). Tokens present in more
 * than `max(50, 2 %)` of the subjects are "common" and are not used for blocking, unless every token of
 * the query is common (then the rarest one is used).
 *
 * Recall addition: tokens of [AFFIX_MIN] or more characters are also indexed by their 3-character prefix
 * and suffix. A query token that is unknown to the index (typically a typo, "discvery") falls back to its
 * prefix/suffix postings, so a one-letter slip in the only distinctive word still finds its subject.
 */
class TokenIndex(subjectTokens: List<Array<String>>, commonMin: Int = 50, commonFraction: Double = 0.02) {
    private val postings: HashMap<String, IntArray>
    val size: Int = subjectTokens.size
    val commonThreshold: Int = maxOf(commonMin, (size * commonFraction).toInt())

    /** Per-query de-duplication stamps (no per-query allocation of a set). */
    private val stamp = IntArray(size)
    private var epoch = 0

    init {
        val counts = HashMap<String, Int>()
        val keys = ArrayList<String>(4)
        for (tokens in subjectTokens) {
            keysOf(tokens, keys)
            for (t in keys) counts[t] = (counts[t] ?: 0) + 1
        }
        postings = HashMap(counts.size * 2)
        val fill = HashMap<String, Int>(counts.size * 2)
        for ((t, c) in counts) postings[t] = IntArray(c)
        subjectTokens.forEachIndexed { idx, tokens ->
            keysOf(tokens, keys)
            for (t in keys) {
                val at = fill[t] ?: 0
                postings.getValue(t)[at] = idx
                fill[t] = at + 1
            }
        }
    }

    fun frequency(token: String): Int = postings[token]?.size ?: 0

    fun isCommon(token: String): Boolean = frequency(token) > commonThreshold

    /**
     * Writes the candidate subject indices for [tokens] into [out] (union of the postings of non-common
     * tokens, or of the rarest token if all are common), at most [cap]. Returns the count. Not thread-safe.
     */
    fun candidates(
        tokens: Array<String>,
        out: IntArray,
        cap: Int = minOf(DEFAULT_CAP, out.size),
        /** Applied to subjects found only through an affix of an unknown token: (subject, unknown token) → keep. */
        affixFilter: ((Int, String) -> Boolean)? = null,
    ): Int {
        if (tokens.isEmpty() || size == 0) return 0
        epoch++
        if (epoch == Int.MAX_VALUE) { stamp.fill(0); epoch = 1 }
        var n = 0
        var anyRare = false
        fun take(p: IntArray, viaAffixOf: String?): Boolean {
            for (s in p) {
                if (stamp[s] == epoch) continue
                if (viaAffixOf != null && affixFilter != null && !affixFilter(s, viaAffixOf)) continue
                stamp[s] = epoch
                out[n++] = s
                if (n >= cap) return true
            }
            return false
        }
        for (t in tokens) {
            val p = postings[t]
            if (p == null) {
                // A 3-letter unknown token is usually a 4-letter word with a slip at either end.
                if (t.length < 3) continue
                for (key in arrayOf(prefixKey(t), suffixKey(t))) {
                    val a = postings[key] ?: continue
                    if (a.size > commonThreshold) continue
                    anyRare = true
                    if (take(a, t)) return n
                }
                continue
            }
            if (p.size > commonThreshold) continue
            anyRare = true
            if (take(p, null)) return n
        }
        if (!anyRare) {
            var rarest: IntArray? = null
            for (t in tokens) {
                val p = postings[t] ?: continue
                if (rarest == null || p.size < rarest.size) rarest = p
            }
            if (rarest != null) take(rarest, null)
        }
        return n
    }

    private fun keysOf(tokens: Array<String>, into: ArrayList<String>) {
        into.clear()
        for (t in tokens) {
            into += t
            if (t.length >= AFFIX_MIN) {
                into += prefixKey(t)
                into += suffixKey(t)
            }
        }
        if (into.size > 1) {
            val distinct = into.distinct()
            into.clear(); into.addAll(distinct)
        }
    }

    companion object {
        const val DEFAULT_CAP = 200

        /** Tokens at least this long are also indexed by their 3-character prefix and suffix. */
        const val AFFIX_MIN = 4

        // '<' and '>' never occur in forMatch tokens, so affix keys cannot collide with real tokens.
        private fun prefixKey(t: String) = "<" + t.substring(0, 3)
        private fun suffixKey(t: String) = t.substring(t.length - 3) + ">"
    }
}
