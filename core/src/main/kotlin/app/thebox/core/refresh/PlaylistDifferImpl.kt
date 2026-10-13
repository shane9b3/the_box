package app.thebox.core.refresh

import app.thebox.core.common.BoxLog
import app.thebox.core.common.CancelCheck
import app.thebox.core.common.Hash64
import app.thebox.core.common.ImportGate
import app.thebox.core.common.NotASourceKind
import app.thebox.core.common.SourceError
import app.thebox.core.common.SourceException
import app.thebox.core.identity.ContentHasher
import app.thebox.core.identity.GroupKeys
import app.thebox.core.source.EntryKind
import app.thebox.core.source.ParseWarning
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.PlaylistType
import app.thebox.core.source.WarningCode
import app.thebox.core.model.StableKey

/**
 * Two-pass diff-merge engine (docs/design/m1-data-layer.md §3.3).
 *
 * Pass 1 validates without writing: kinds, groups, the Xtream credential vote, the empty check and the
 * shrink guard. Pass 2 derives keys (with `~n` occurrence suffixes), templates credentials, hashes, and
 * emits inserts/updates/touches to [MergeSink.apply] in batches of [MergeContext.batchSize]. Missing rows,
 * purges and automatic key remaps are computed only at the end and handed to [MergeSink.finish].
 *
 * [gate] is awaited between batches (zap protection, architecture review B3.4). [clock] stamps
 * `finishedAtMs`. [keyHash] exists for the collision tests only.
 */
class PlaylistDifferImpl internal constructor(
    private val deriver: StableKeyDeriver,
    private val gate: ImportGate,
    private val clock: () -> Long,
    private val keyHash: (String) -> Long,
) : PlaylistDiffer {

    constructor(
        deriver: StableKeyDeriver,
        gate: ImportGate = ImportGate.Open,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(deriver, gate, clock, Hash64::of)

    override fun merge(
        entries: EntryProducer,
        existing: ExistingChannels,
        sink: MergeSink,
        ctx: MergeContext,
        cancel: CancelCheck,
    ): RefreshReport {
        val report = RefreshReportBuilder(ctx)
        val groupCache = GroupKeys.Cache(ctx.playlistType)

        // ---- Pass 1: validate (no writes) -------------------------------------------------------------
        val groups = LinkedHashMap<String, GroupAcc>()
        val vote = if (ctx.playlistType != PlaylistType.XTREAM) CredentialVote() else null
        var n1 = 0
        entries.produce { e ->
            if (++n1 % CANCEL_EVERY == 0) cancel.throwIfCancelled()
            when (e.kind) {
                EntryKind.LIVE -> report.live++
                EntryKind.RADIO -> report.radio++
                EntryKind.MOVIE -> report.movies++
                EntryKind.SERIES -> report.series++
            }
            val gk = groupCache.of(e)
            val gKind = GroupKeys.groupKind(e.kind)
            val mapKey = groupMapKey(gKind, gk)
            val acc = groups[mapKey] ?: GroupAcc(gk, groupName(e, ctx.playlistType), gKind, groups.size).also { groups[mapKey] = it }
            acc.total++
            if (e.adult) acc.adult++
            vote?.offer(e.url)
        }
        report.entries = n1
        if (n1 == 0) throw SourceException(SourceError.NotASource(NotASourceKind.EMPTY))
        val liveNow = report.live + report.radio
        if (!ctx.forceAcceptShrink && ctx.previousLiveCount >= SHRINK_MIN_PREVIOUS &&
            liveNow.toLong() * SHRINK_DENOMINATOR < ctx.previousLiveCount.toLong() * SHRINK_NUMERATOR
        ) {
            throw SourceException(SourceError.SuspiciousShrink(ctx.previousLiveCount, liveNow))
        }
        val detected = vote?.result()
        report.credentialsTemplated = detected != null
        report.groupsTotal = groups.size
        val groupRows = groups.values.map { GroupRow(it.key, it.name, it.kind, it.order, isAdultGroup(it)) }
        val groupKeysSeen = groups.values.mapTo(LinkedHashSet(groups.size * 2)) { it.key }
        groups.clear()

        // ---- Existing index --------------------------------------------------------------------------
        val index = ExistingIndex(keyHash, capacityHint = n1)
        existing.forEach { index.add(it) }
        cancel.throwIfCancelled()

        sink.begin(groupRows, detected)
        try {
            val result = pass2(entries, index, sink, ctx, cancel, report, groupCache, detected)
            cancel.throwIfCancelled()
            val finish = computeFinish(index, existing, ctx, result.insertsByUrl, report, groupKeysSeen)
            val stats = sink.finish(finish)
            val mass = massRemap(ctx, report)
            BoxLog.i(TAG) {
                "playlist ${ctx.playlistId} merged: ${report.entries} entries, +${report.added} ~${report.updated} " +
                    "-${report.newlyMissing} purged=${stats.purged} dup=${report.duplicateKeys} remaps=${finish.autoRemaps.size}"
            }
            return report.build(clock(), stats, mass, finish.autoRemaps.size)
        } catch (t: Throwable) {
            try { sink.abort(t) } catch (s: Throwable) { t.addSuppressed(s) }
            throw t
        }
    }

    private class Pass2Result(val insertsByUrl: HashMap<Long, String?>)

    private fun pass2(
        entries: EntryProducer,
        index: ExistingIndex,
        sink: MergeSink,
        ctx: MergeContext,
        cancel: CancelCheck,
        report: RefreshReportBuilder,
        groupCache: GroupKeys.Cache,
        detected: DetectedCredentials?,
    ): Pass2Result {
        val keyCtx = KeyContext(ctx.playlistType, xtreamShaped = detected != null, credentials = detected)
        val newKeys = NewKeySet(keyHash, if (index.size == 0) 4096 else 256)
        val dupCounts = HashMap<String, Int>()
        val insertsByUrl = HashMap<Long, String?>()
        val batchSize = ctx.batchSize.coerceAtLeast(1)
        var inserts = ArrayList<KeyedChannel>()
        var updates = ArrayList<ChannelUpdate>()
        var touches = ArrayList<ChannelTouch>()
        var n2 = 0

        fun flush() {
            if (inserts.isEmpty() && updates.isEmpty() && touches.isEmpty()) return
            gate.awaitClear()
            cancel.throwIfCancelled()
            sink.apply(MergeBatch(inserts, updates, touches))
            inserts = ArrayList()
            updates = ArrayList()
            touches = ArrayList()
        }

        entries.produce { raw ->
            if (++n2 % CANCEL_EVERY == 0) cancel.throwIfCancelled()
            val dk = deriver.derive(raw, keyCtx)
            report.rule(dk.rule)

            // Occurrence suffix (§3.2.1): the first occurrence keeps the key, the n-th becomes key~n.
            val base = dk.key.value
            var key = base
            var slot = index.lookup(base)
            if (slot >= 0 && !index.seen[slot]) {
                index.seen.set(slot)
            } else if (slot < 0 && !newKeys.contains(base)) {
                newKeys.add(base)
            } else {
                var n = (dupCounts[base] ?: 1) + 1
                while (true) {
                    key = "$base~$n"
                    slot = index.lookup(key)
                    if (slot >= 0) {
                        if (!index.seen[slot]) { index.seen.set(slot); break }
                    } else if (!newKeys.contains(key)) {
                        newKeys.add(key)
                        break
                    }
                    n++
                }
                dupCounts[base] = n
                report.duplicateKeys++
                report.warning(ParseWarning(raw.position.toLong(), WarningCode.DUPLICATE_KEY, null))
            }

            val stored = CredentialTemplater.apply(raw, detected)
            val groupKey = groupCache.of(stored)
            val urlHash = ContentHasher.urlHash(stored.url)
            val nameHash = ContentHasher.nameHash(stored.name)
            val contentHash = ContentHasher.contentHash(stored, groupKey)
            val kc = KeyedChannel(
                stableKey = if (key === base) dk.key else StableKey(key),
                rule = dk.rule,
                groupKey = groupKey,
                urlTemplate = stored.url,
                contentHash = contentHash,
                urlHash = urlHash,
                nameHash = nameHash,
                sortIndex = raw.position,
                channel = stored,
            )
            if (slot < 0) {
                inserts += kc
                report.added(stored.name)
                if (raw.kind == EntryKind.LIVE || raw.kind == EntryKind.RADIO) report.addedLive++
                if (index.size > 0 && index.hasUrl(urlHash)) {
                    insertsByUrl[urlHash] = if (insertsByUrl.containsKey(urlHash)) null else key
                }
            } else {
                val missing = index.missingSince(slot) != null
                if (index.contentHash(slot) != contentHash) {
                    updates += ChannelUpdate(index.id(slot), kc, restored = missing)
                    report.updated++
                    if (index.urlHash(slot) != urlHash) report.urlChanged++
                    if (index.nameHash32(slot) != nameHash.toInt()) report.renamed++
                    if (missing) report.restored++
                } else if (missing || index.sortIndex(slot) != raw.position) {
                    touches += ChannelTouch(index.id(slot), raw.position, restored = missing)
                    if (index.sortIndex(slot) != raw.position) report.moved++
                    if (missing) report.restored++
                } else {
                    report.unchanged++
                }
            }
            if (inserts.size + updates.size + touches.size >= batchSize) flush()
        }
        if (n2 != report.entries) {
            // The producer must replay the same file; a different count means it changed under us.
            throw SourceException(SourceError.Parse("pass 2 saw $n2 entries, pass 1 saw ${report.entries}"))
        }
        flush()
        return Pass2Result(insertsByUrl)
    }

    private fun computeFinish(
        index: ExistingIndex,
        existing: ExistingChannels,
        ctx: MergeContext,
        insertsByUrl: HashMap<Long, String?>,
        report: RefreshReportBuilder,
        groupKeysSeen: Set<String>,
    ): MergeFinish {
        val newly = LongList()
        val newlySlots = IntList()
        val still = LongList()
        val purge = LongList()
        val purgeSlots = IntList()
        val unseenPerUrl = HashMap<Long, Int>()
        var slot = index.seen.nextClearBit(0)
        while (slot < index.size) {
            val since = index.missingSince(slot)
            if (since == null) {
                newly.add(index.id(slot))
                newlySlots.add(slot)
            } else if (ctx.nowMs - since >= ctx.graceMs && index.missingCount(slot) + 1 >= ctx.purgeAfterMissingRefreshes) {
                purge.add(index.id(slot))
                purgeSlots.add(slot)
            } else {
                still.add(index.id(slot))
            }
            if (insertsByUrl.isNotEmpty()) {
                val u = index.urlHash(slot)
                if (insertsByUrl.containsKey(u)) unseenPerUrl[u] = (unseenPerUrl[u] ?: 0) + 1
            }
            slot = index.seen.nextClearBit(slot + 1)
        }
        report.newlyMissing = newly.size

        // S7: a newly-missing slot and an inserted entry with the same url hash, unique on both sides.
        val remapSlots = HashMap<Long, String>() // existing row id -> new key
        if (insertsByUrl.isNotEmpty()) {
            for (i in 0 until newlySlots.size) {
                val s = newlySlots[i]
                val u = index.urlHash(s)
                val newKey = insertsByUrl[u] ?: continue
                if (unseenPerUrl[u] == 1) remapSlots[index.id(s)] = newKey
            }
        }
        // Key strings are not retained per row; fetch the few that are needed with one more scan.
        val needed = HashMap<Long, String?>()
        for (id in remapSlots.keys) needed[id] = null
        for (i in 0 until purge.size) needed[purge[i]] = null
        if (needed.isNotEmpty()) {
            existing.forEach { e -> if (needed.containsKey(e.id)) needed[e.id] = e.stableKey }
        }
        val remaps = ArrayList<KeyRemap>(remapSlots.size)
        for ((id, newKey) in remapSlots) {
            val old = needed[id] ?: continue
            if (old != newKey) remaps += KeyRemap(old, newKey, sameGroup = false)
        }
        remaps.sortBy { it.oldKey }
        val purgedKeys = ArrayList<String>(purge.size)
        for (i in 0 until purge.size) needed[purge[i]]?.let { purgedKeys += it }
        return MergeFinish(
            nowMs = ctx.nowMs,
            newlyMissingIds = newly.toArray(),
            stillMissingIds = still.toArray(),
            purgeIds = purge.toArray(),
            groupKeysSeen = groupKeysSeen,
            autoRemaps = remaps,
            purgedKeys = purgedKeys,
        )
    }

    /** §3.3.6 with S18: every term counts LIVE+RADIO only. */
    private fun massRemap(ctx: MergeContext, r: RefreshReportBuilder): Boolean {
        val before = ctx.previousLiveCount
        if (before < MASS_REMAP_MIN_ACTIVE) return false
        val liveNow = r.live + r.radio
        return r.newlyMissing.toLong() * 10 >= before.toLong() * 7 && r.addedLive.toLong() * 10 >= liveNow.toLong() * 7
    }

    private class GroupAcc(val key: String, val name: String, val kind: EntryKind, val order: Int) {
        var total = 0
        var adult = 0
    }

    private fun isAdultGroup(g: GroupAcc): Boolean = ADULT_NAME.containsMatchIn(g.name) || g.adult * 2 > g.total

    private fun groupName(e: ParsedChannel, type: PlaylistType): String =
        e.groupTitle?.takeIf { it.isNotBlank() } ?: if (type == PlaylistType.XTREAM) "Uncategorised" else ""

    private fun groupMapKey(kind: EntryKind, key: String): String = kind.ordinal.toString() + ":" + key

    /** Growable primitive lists (no boxing). */
    private class LongList {
        private var a = LongArray(16)
        var size = 0
            private set
        fun add(v: Long) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        operator fun get(i: Int) = a[i]
        fun toArray(): LongArray = a.copyOf(size)
    }

    private class IntList {
        private var a = IntArray(16)
        var size = 0
            private set
        fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        operator fun get(i: Int) = a[i]
    }

    companion object {
        private const val TAG = "PlaylistDiffer"
        const val CANCEL_EVERY = 1_000
        /** §3.3.5: guard only when the previous refresh had at least this many live+radio channels. */
        const val SHRINK_MIN_PREVIOUS = 50
        /** §3.3.5: fail when live+radio < 20 % of the previous count (1/5). */
        const val SHRINK_NUMERATOR = 1
        const val SHRINK_DENOMINATOR = 5
        /** §3.3.6. */
        const val MASS_REMAP_MIN_ACTIVE = 20
        /** `channel_group.is_adult` name rule (§2.1.2): `\b(xxx|adult|18\+|porn)\b`, once per group, never per row. */
        private val ADULT_NAME = Regex("""(?i)(?<![\p{L}\p{N}])(?:xxx|adult|porn|18\+)(?![\p{L}\p{N}])""")
    }
}
