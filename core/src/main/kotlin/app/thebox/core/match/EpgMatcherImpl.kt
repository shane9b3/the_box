package app.thebox.core.match

import app.thebox.core.epg.EpgChannelInfo
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.text.NameNormalizer
import java.util.Locale

/**
 * EPG matcher (docs/design/m1-data-layer.md §3.6).
 *
 * Stages, lower rank wins: OVERRIDE → ID_EXACT (0) → ID_CASE_INSENSITIVE (1) → ID_BASE (2, iptv-org
 * `Name.cc@Feed`) → TVG_NAME (3) → DISPLAY_NAME (4) → FUZZY (5). Name stages are AUTO only when
 * unambiguous (one xmltv id, or one iptv-org base id, after dropping country conflicts); fuzzy matches are
 * AUTO only at [MatchLevel.AUTO_FUZZY] with score ≥ autoThreshold and a clear margin, otherwise they are
 * suggestions (≥ suggestThreshold). Fuzzy candidates come from a [TokenIndex] (blocking), so one source is
 * matched in near-linear time; per subject only the top `maxSuggestions + 1` scores are kept, and a
 * comparison stops early once an upper bound shows it cannot enter that list.
 *
 * Not thread-safe: one instance per matching run (index and scorer keep scratch state).
 *
 * @param blocking test hook: false scores every subject against every EPG name (brute force), used to
 *   measure the recall of the blocked path.
 */
class EpgMatcher(private val settings: MatchSettings, private val blocking: Boolean = true) {

    /** Subjects indexed for repeated [matchSource] calls (one per EPG source). */
    class Prepared internal constructor(
        val subjects: List<MatchSubject>,
        internal val hints: Array<String?>,
        internal val exactId: LongIndex,
        internal val ciId: LongIndex,
        internal val baseId: LongIndex,
        internal val tvgNameKey: LongIndex,
        internal val tvgNameMatch: LongIndex,
        internal val nameKey: LongIndex,
        internal val nameMatch: LongIndex,
        internal val simName: Array<Similarity.Prepared>,
        internal val simTvgName: Array<Similarity.Prepared?>,
        internal val tokenIndex: TokenIndex,
    ) {
        val size: Int get() = subjects.size
    }

    private class Cand(val ref: EpgChannelRef, val displayName: String, val hasData: Boolean, val order: Int, val hint: String?)

    private class NameHit(val cand: Cand, val rank: Int, val viaKey: Boolean)

    private val scorer = Similarity.Scorer()

    fun prepare(subjects: List<MatchSubject>): Prepared {
        val n = subjects.size
        val hints = arrayOfNulls<String>(n)
        val exact = LongIndex(n); val ci = LongIndex(n); val base = LongIndex(n)
        val tvgKey = LongIndex(n / 4); val tvgMatch = LongIndex(n / 4); val nKey = LongIndex(n); val nMatch = LongIndex(n)
        val simName = arrayOfNulls<Similarity.Prepared>(n)
        val simTvg = arrayOfNulls<Similarity.Prepared>(n)
        val tokenLists = ArrayList<Array<String>>(n)
        // One String instance per distinct token/hint across all subjects (§3.6.4 memory budget).
        val pool = HashMap<String, String>()
        val intern: (String) -> String = { s -> pool.getOrPut(s) { s } }
        for (i in 0 until n) {
            val s = subjects[i]
            val hint = (s.countryHint ?: NameNormalizer.countryHint(s.name, s.tvgId))?.let(intern)
            hints[i] = hint
            s.tvgId?.takeIf { it.isNotBlank() }?.let { id ->
                exact.add(id, i)
                val lower = id.lowercase(Locale.ROOT)
                ci.add(lower, i)
                base.add(baseOf(lower), i)
            }
            val nameNorm = NameNormalizer.forMatch(s.name)
            nKey.add(NameNormalizer.forKey(s.name), i)
            nMatch.add(nameNorm, i)
            val pn = Similarity.prepare(nameNorm, hint, intern)
            simName[i] = pn
            var tokens = pn.tokens
            s.tvgName?.takeIf { it.isNotBlank() }?.let { tn ->
                val spaced = tn.replace('_', ' ')
                tvgKey.add(NameNormalizer.forKey(spaced), i)
                val tvgNorm = NameNormalizer.forMatch(spaced)
                tvgMatch.add(tvgNorm, i)
                if (tvgNorm != nameNorm) {
                    val pt = Similarity.prepare(tvgNorm, hint, intern)
                    simTvg[i] = pt
                    tokens = (tokens + pt.tokens).distinct().toTypedArray()
                }
            }
            tokenLists += tokens
        }
        @Suppress("UNCHECKED_CAST")
        return Prepared(
            subjects, hints, exact, ci, base, tvgKey, tvgMatch, nKey, nMatch,
            simName as Array<Similarity.Prepared>, simTvg, TokenIndex(tokenLists),
        )
    }

    /**
     * Streams [channels] (one EPG source) once and returns one [MatchResult] per prepared subject, in
     * subject order. [overrides]: a present key means the user decided for that channel (epg_mapping). A ref
     * in [sourceId] yields an AUTO `OVERRIDE`; a null ref ("no guide data") or a ref in another source
     * yields no match in this source.
     */
    fun matchSource(
        prepared: Prepared,
        sourceId: Long,
        channels: Sequence<EpgChannelInfo>,
        overrides: Map<ChannelIdentity, EpgChannelRef?> = emptyMap(),
    ): List<MatchResult> {
        val run = Run(prepared, sourceId, overrides)
        for (ch in channels) run.offer(ch)
        return run.results()
    }

    /** Per-source state; one channel at a time through [offer]. */
    private inner class Run(val p: Prepared, val sourceId: Long, val overrides: Map<ChannelIdentity, EpgChannelRef?>) {
        val n = p.size
        val names = settings.level >= MatchLevel.EXACT_AND_NAMES
        val fuzzy = settings.level >= MatchLevel.SUGGEST_FUZZY
        val k = settings.maxSuggestions + 1
        val idRank = IntArray(n) { Int.MAX_VALUE }
        val idCand = arrayOfNulls<Cand>(n)
        val nameHits = arrayOfNulls<ArrayList<NameHit>>(n)
        val topScore = if (fuzzy) FloatArray(n * k) { -1f } else FloatArray(0)
        val topCand = if (fuzzy) arrayOfNulls<Cand>(n * k) else arrayOfNulls(0)
        val overrideIds = HashSet<String>().apply { for ((_, ref) in overrides) if (ref != null && ref.sourceId == sourceId) add(ref.xmltvId) }
        val overrideNames = HashMap<String, String>()
        val buf = IntArray(TokenIndex.DEFAULT_CAP)
        var order = 0

        // Current channel.
        private var ch: EpgChannelInfo? = null
        private var cand: Cand? = null
        private var idx = 0

        private fun cand(): Cand = cand ?: run {
            val c = ch!!
            val first = c.displayNames.firstOrNull() ?: c.xmltvId
            Cand(EpgChannelRef(sourceId, c.xmltvId), first, c.programmeCount > 0, idx, NameNormalizer.countryHint(first, c.xmltvId))
        }.also { cand = it }

        fun offer(c: EpgChannelInfo) {
            ch = c; cand = null; idx = order++
            val xid = c.xmltvId
            if (overrideIds.isNotEmpty() && xid in overrideIds) overrideNames.putIfAbsent(xid, c.displayNames.firstOrNull() ?: xid)
            val hasData = c.programmeCount > 0

            p.exactId.forEach(xid) { s -> if (p.subjects[s].tvgId == xid) offerId(s, 0, hasData) }
            val lower = xid.lowercase(Locale.ROOT)
            if (settings.caseInsensitiveIds) {
                p.ciId.forEach(lower) { s ->
                    val tvg = p.subjects[s].tvgId!!
                    if (tvg != xid && tvg.lowercase(Locale.ROOT) == lower) offerId(s, 1, hasData)
                }
            }
            val xidHasAt = xid.indexOf('@') >= 0
            val xBase = baseOf(lower)
            p.baseId.forEach(xBase) { s ->
                val tvg = p.subjects[s].tvgId!!
                if (tvg != xid && (xidHasAt || tvg.indexOf('@') >= 0) && baseOf(tvg.lowercase(Locale.ROOT)) == xBase) offerId(s, 2, hasData)
            }

            val dnCount = minOf(MAX_DISPLAY_NAMES, c.displayNames.size)
            for (d in 0 until dnCount) {
                val dn = c.displayNames[d]
                val norm = NameNormalizer.forMatch(dn)
                if (names) {
                    val key = NameNormalizer.forKey(dn)
                    p.tvgNameKey.forEach(key) { hit(it, 3, true) }
                    p.tvgNameMatch.forEach(norm) { hit(it, 3, false) }
                    p.nameKey.forEach(key) { hit(it, 4, true) }
                    p.nameMatch.forEach(norm) { hit(it, 4, false) }
                }
                if (fuzzy) {
                    val pd = Similarity.prepare(norm, NameNormalizer.countryHint(dn, xid))
                    if (blocking) {
                        val count = p.tokenIndex.candidates(pd.tokens, buf, affixFilter = nearTypo)
                        for (j in 0 until count) scoreFuzzy(buf[j], pd)
                    } else {
                        for (s in 0 until n) scoreFuzzy(s, pd)
                    }
                }
            }
        }

        /**
         * Keeps a subject reached only through the prefix/suffix of an unknown EPG token when one of its own
         * tokens is a likely typo of it (≤ 1 edit, ≤ 2 for tokens longer than 5). Unrelated words that merely
         * share three letters are not scored.
         */
        private val nearTypo: (Int, String) -> Boolean = { s, token ->
            hasNearToken(p.simName[s].tokens, token) || p.simTvgName[s]?.let { hasNearToken(it.tokens, token) } == true
        }

        private fun hasNearToken(tokens: Array<String>, token: String): Boolean {
            val max = if (token.length <= 5) 1 else 2
            for (t in tokens) if (scorer.withinEdits(t, token, max)) return true
            return false
        }

        private fun offerId(s: Int, rank: Int, hasData: Boolean) {
            val cur = idRank[s]
            if (rank < cur || (rank == cur && hasData && idCand[s]?.hasData == false)) {
                idRank[s] = rank
                idCand[s] = cand()
            }
        }

        private fun hit(s: Int, rank: Int, viaKey: Boolean) {
            if (idRank[s] <= 2) return
            val list = nameHits[s] ?: ArrayList<NameHit>(2).also { nameHits[s] = it }
            val c = cand()
            var i = -1
            for (x in list.indices) if (list[x].cand === c) { i = x; break }
            if (i < 0) {
                list += NameHit(c, rank, viaKey)
            } else {
                val old = list[i]
                if (rank < old.rank || (rank == old.rank && viaKey && !old.viaKey)) list[i] = NameHit(c, rank, viaKey)
            }
        }

        private fun scoreFuzzy(s: Int, pd: Similarity.Prepared) {
            if (idRank[s] <= 2 || nameHits[s] != null) return
            val base = s * k
            val c = cand
            // An existing entry for this channel can only be improved; otherwise beat the k-th score.
            var floor = maxOf(settings.suggestThreshold, topScore[base + k - 1])
            if (c != null) for (i in base until base + k) if (topCand[i] === c) { floor = maxOf(settings.suggestThreshold, topScore[i]); break }
            var score = scorer.sim(p.simName[s], pd, floor)
            p.simTvgName[s]?.let { score = maxOf(score, scorer.sim(it, pd, maxOf(floor, score))) }
            if (score < settings.suggestThreshold) return
            offerTop(base, cand(), score)
        }

        /** Keeps the top-[k] distinct candidates in descending score order inside [base, base + k). */
        private fun offerTop(base: Int, c: Cand, score: Float) {
            var at = -1
            for (i in base until base + k) if (topCand[i] === c) { at = i; break }
            if (at >= 0) {
                if (score <= topScore[at]) return
                topScore[at] = score
            } else {
                val last = base + k - 1
                if (score <= topScore[last]) return
                topScore[last] = score
                topCand[last] = c
                at = last
            }
            while (at > base && topScore[at] > topScore[at - 1]) {
                val ts = topScore[at]; topScore[at] = topScore[at - 1]; topScore[at - 1] = ts
                val tc = topCand[at]; topCand[at] = topCand[at - 1]; topCand[at - 1] = tc
                at--
            }
        }

        fun results(): List<MatchResult> {
            val out = ArrayList<MatchResult>(n)
            for (s in 0 until n) {
                val identity = p.subjects[s].identity
                if (overrides.containsKey(identity)) {
                    val ref = overrides[identity]
                    out += if (ref != null && ref.sourceId == sourceId) {
                        val c = MatchCandidate(ref, overrideNames[ref.xmltvId] ?: ref.xmltvId, 1f, MatchMethod.OVERRIDE)
                        MatchResult(identity, sourceId, c, MatchStatus.AUTO, emptyList())
                    } else {
                        none(identity, sourceId)
                    }
                    continue
                }
                val rank = idRank[s]
                if (rank <= 2) {
                    val c = idCand[s]!!
                    val method = when (rank) { 0 -> MatchMethod.ID_EXACT; 1 -> MatchMethod.ID_CASE_INSENSITIVE; else -> MatchMethod.ID_BASE }
                    out += MatchResult(identity, sourceId, MatchCandidate(c.ref, c.displayName, if (rank == 2) 0.99f else 1f, method), MatchStatus.AUTO, emptyList())
                    continue
                }
                val hits = nameHits[s]
                out += when {
                    hits != null && names -> nameResult(identity, sourceId, p.hints[s], hits)
                    fuzzy -> fuzzyResult(identity, sourceId, p.hints[s], s * k)
                    else -> none(identity, sourceId)
                }
            }
            return out
        }

        private fun fuzzyResult(identity: ChannelIdentity, sourceId: Long, hint: String?, base: Int): MatchResult {
            val best = topCand[base] ?: return none(identity, sourceId)
            val s = topScore[base]
            val s2 = if (k > 1 && topCand[base + 1] != null) topScore[base + 1] else 0f
            val list = ArrayList<MatchCandidate>(k)
            for (i in base until base + k) {
                val c = topCand[i] ?: break
                list += MatchCandidate(c.ref, c.displayName, topScore[i], MatchMethod.FUZZY)
            }
            val auto = settings.level == MatchLevel.AUTO_FUZZY && s >= settings.autoThreshold &&
                s - s2 >= settings.ambiguityMargin && !Similarity.countryConflict(hint, best.hint)
            return when {
                auto -> MatchResult(identity, sourceId, list[0], MatchStatus.AUTO, emptyList())
                s >= settings.suggestThreshold -> MatchResult(identity, sourceId, list[0], MatchStatus.SUGGESTED, list.drop(1).take(settings.maxSuggestions))
                else -> none(identity, sourceId)
            }
        }
    }

    /**
     * The S13 picker: scores one subject against every EPG channel by brute force (no blocking) and returns
     * the best [limit] distinct channels, best first. Exact/name equalities score as their stage.
     */
    fun suggest(subject: MatchSubject, channels: Sequence<EpgChannelInfo>, limit: Int = settings.maxSuggestions + 1): List<MatchCandidate> {
        val hint = subject.countryHint ?: NameNormalizer.countryHint(subject.name, subject.tvgId)
        val nameNorm = NameNormalizer.forMatch(subject.name)
        val pn = Similarity.prepare(nameNorm, hint)
        val tvgNorm = subject.tvgName?.takeIf { it.isNotBlank() }?.let { NameNormalizer.forMatch(it.replace('_', ' ')) }
        val pt = tvgNorm?.takeIf { it != nameNorm }?.let { Similarity.prepare(it, hint) }
        val tvgLower = subject.tvgId?.lowercase(Locale.ROOT)
        val best = HashMap<EpgChannelRef, MatchCandidate>()
        for (ch in channels) {
            val ref = EpgChannelRef(ch.sourceId, ch.xmltvId)
            val lower = ch.xmltvId.lowercase(Locale.ROOT)
            val first = ch.displayNames.firstOrNull() ?: ch.xmltvId
            var c: MatchCandidate? = when {
                subject.tvgId != null && subject.tvgId == ch.xmltvId -> MatchCandidate(ref, first, 1f, MatchMethod.ID_EXACT)
                tvgLower != null && tvgLower == lower -> MatchCandidate(ref, first, 1f, MatchMethod.ID_CASE_INSENSITIVE)
                tvgLower != null && baseOf(tvgLower) == baseOf(lower) && (tvgLower.contains('@') || lower.contains('@')) ->
                    MatchCandidate(ref, first, 0.99f, MatchMethod.ID_BASE)
                else -> null
            }
            for (d in 0 until minOf(MAX_DISPLAY_NAMES, ch.displayNames.size)) {
                if (c != null && c.score >= 0.99f) break
                val dn = ch.displayNames[d]
                val norm = NameNormalizer.forMatch(dn)
                val candidate = when {
                    tvgNorm != null && norm == tvgNorm -> MatchCandidate(ref, dn, 0.98f, MatchMethod.TVG_NAME)
                    norm == nameNorm -> MatchCandidate(ref, dn, 0.97f, MatchMethod.DISPLAY_NAME)
                    else -> {
                        val pd = Similarity.prepare(norm, NameNormalizer.countryHint(dn, ch.xmltvId))
                        val score = maxOf(scorer.sim(pn, pd), pt?.let { scorer.sim(it, pd) } ?: 0f)
                        MatchCandidate(ref, dn, score, MatchMethod.FUZZY)
                    }
                }
                if (c == null || candidate.score > c.score) c = candidate
            }
            if (c != null && c.score > 0f) {
                val prev = best[ref]
                if (prev == null || c.score > prev.score) best[ref] = c
            }
        }
        return best.values.sortedByDescending { it.score }.take(limit)
    }

    private fun nameResult(identity: ChannelIdentity, sourceId: Long, hint: String?, hits: List<NameHit>): MatchResult {
        val keyHits = hits.filter { it.viaKey }
        val use = keyHits.ifEmpty { hits }
        val compatible = use.filter { !Similarity.countryConflict(hint, it.cand.hint) }
        val pool = compatible.ifEmpty { use }
        val ordered = pool.sortedWith(compareBy<NameHit> { it.rank }.thenByDescending { it.cand.hasData }.thenBy { it.cand.order })
        val bases = compatible.mapTo(HashSet()) { baseOf(it.cand.ref.xmltvId.lowercase(Locale.ROOT)) }
        if (compatible.isNotEmpty() && bases.size == 1) {
            // Unambiguous: one xmltv id, or several feeds of one iptv-org channel (tie-break: data, then first seen).
            return MatchResult(identity, sourceId, ordered.first().toCandidate(), MatchStatus.AUTO, emptyList())
        }
        val list = ordered.map { it.toCandidate() }
        return MatchResult(identity, sourceId, list.first(), MatchStatus.SUGGESTED, list.drop(1).take(settings.maxSuggestions))
    }

    private fun NameHit.toCandidate() = MatchCandidate(
        cand.ref, cand.displayName,
        if (rank == 3) 0.98f else 0.97f,
        if (rank == 3) MatchMethod.TVG_NAME else MatchMethod.DISPLAY_NAME,
    )

    private fun none(identity: ChannelIdentity, sourceId: Long) = MatchResult(identity, sourceId, null, null, emptyList())

    companion object {
        /** Bumped whenever matching rules change; part of epg_match.inputs_hash (§3.6.5). */
        const val VERSION = 1

        /** Display names per EPG channel that take part in matching. */
        const val MAX_DISPLAY_NAMES = 3

        /** iptv-org feed ids: `BBCOne.uk@HD` → `bbcone.uk` (input already lower-cased). */
        fun baseOf(lowerId: String): String = lowerId.substringBefore('@')
    }
}
