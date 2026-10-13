package app.thebox.data.testutil

import app.thebox.core.common.CancelCheck
import app.thebox.core.common.Hash64
import app.thebox.core.common.NotASourceKind
import app.thebox.core.common.SourceError
import app.thebox.core.common.SourceException
import app.thebox.core.model.StableKey
import app.thebox.core.refresh.ChannelTouch
import app.thebox.core.refresh.ChannelUpdate
import app.thebox.core.refresh.DetectedCredentials
import app.thebox.core.refresh.EntryProducer
import app.thebox.core.refresh.ExistingChannels
import app.thebox.core.refresh.GroupRow
import app.thebox.core.refresh.KeyRemap
import app.thebox.core.refresh.KeyRule
import app.thebox.core.refresh.KeyedChannel
import app.thebox.core.refresh.MergeBatch
import app.thebox.core.refresh.MergeContext
import app.thebox.core.refresh.MergeFinish
import app.thebox.core.refresh.MergeSink
import app.thebox.core.refresh.PlaylistDiffer
import app.thebox.core.refresh.RefreshOutcome
import app.thebox.core.refresh.RefreshReport
import app.thebox.core.source.EntryKind
import app.thebox.core.source.ParsedChannel
import app.thebox.core.text.NameNormalizer

/**
 * Stand-in for WP-A's differ in :data unit tests (§1.7). Follows the §3.3.3 contract closely enough to drive
 * RoomMergeSink: two passes, batches of `ctx.batchSize`, missing/purge only at finish, shrink guard, and
 * credential templating when [detected] is set. Keys: `tvg:<tvgId>` when present, else `url:<url template>`.
 */
class FakeDiffer(
    private val detected: DetectedCredentials? = null,
    private val autoRemaps: List<KeyRemap> = emptyList(),
) : PlaylistDiffer {
    var lastBatchSizes = mutableListOf<Int>()

    override fun merge(entries: EntryProducer, existing: ExistingChannels, sink: MergeSink, ctx: MergeContext, cancel: CancelCheck): RefreshReport {
        // Pass 1.
        var count = 0
        var live = 0
        val groups = LinkedHashMap<String, GroupRow>()
        entries.produce { e ->
            count++
            if (e.kind == EntryKind.LIVE || e.kind == EntryKind.RADIO) live++
            val gk = groupKey(e)
            groups.getOrPut("${e.kind}|$gk") { GroupRow(gk, e.groupTitle ?: "", e.kind, groups.size, false) }
        }
        if (count == 0) throw SourceException(SourceError.NotASource(NotASourceKind.EMPTY))
        if (!ctx.forceAcceptShrink && ctx.previousLiveCount >= 50 && live < 0.2 * ctx.previousLiveCount) {
            throw SourceException(SourceError.SuspiciousShrink(ctx.previousLiveCount, live))
        }
        class Old(val id: Long, val hash: Long, val sort: Int, val missingSince: Long?, val missingCount: Int, val key: String)
        val old = HashMap<String, Old>()
        existing.forEach { old[it.stableKey] = Old(it.id, it.contentHash, it.sortIndex, it.missingSince, it.missingCount, it.stableKey) }
        try {
            sink.begin(groups.values.toList(), detected)
            // Pass 2.
            val seen = HashSet<String>()
            val ins = ArrayList<KeyedChannel>(); val upd = ArrayList<ChannelUpdate>(); val tch = ArrayList<ChannelTouch>()
            var added = 0; var updated = 0; var unchanged = 0; var restored = 0
            fun flush() {
                if (ins.isEmpty() && upd.isEmpty() && tch.isEmpty()) return
                cancel.throwIfCancelled()
                lastBatchSizes += ins.size + upd.size + tch.size
                sink.apply(MergeBatch(ArrayList(ins), ArrayList(upd), ArrayList(tch)))
                ins.clear(); upd.clear(); tch.clear()
            }
            entries.produce { e ->
                val kc = keyed(e)
                seen += kc.stableKey.value
                val o = old[kc.stableKey.value]
                when {
                    o == null -> { ins += kc; added++ }
                    o.hash != kc.contentHash -> { upd += ChannelUpdate(o.id, kc, o.missingSince != null); updated++; if (o.missingSince != null) restored++ }
                    o.sort != kc.sortIndex || o.missingSince != null -> { tch += ChannelTouch(o.id, kc.sortIndex, o.missingSince != null); if (o.missingSince != null) restored++ }
                    else -> unchanged++
                }
                if (ins.size + upd.size + tch.size >= ctx.batchSize) flush()
            }
            flush()
            val newly = ArrayList<Long>(); val still = ArrayList<Long>(); val purge = ArrayList<Long>(); val purgedKeys = ArrayList<String>()
            for (o in old.values) {
                if (o.key in seen) continue
                when {
                    o.missingSince == null -> newly += o.id
                    ctx.nowMs - o.missingSince >= ctx.graceMs && o.missingCount + 1 >= ctx.purgeAfterMissingRefreshes -> { purge += o.id; purgedKeys += o.key }
                    else -> still += o.id
                }
            }
            val stats = sink.finish(
                MergeFinish(ctx.nowMs, newly.toLongArray(), still.toLongArray(), purge.toLongArray(), groups.values.mapTo(HashSet()) { it.groupKey }, autoRemaps, purgedKeys),
            )
            return RefreshReport(
                ctx.playlistId, ctx.refreshSeq, ctx.nowMs, ctx.nowMs, RefreshOutcome.SUCCESS,
                entries = count, live = live, added = added, updated = updated, unchanged = unchanged, restored = restored,
                newlyMissing = newly.size, purged = stats.purged, favouritesKept = stats.favouritesKept,
                favouritesOrphaned = stats.favouritesOrphaned, removedSample = stats.removedSample, keysMigrated = autoRemaps.size,
                credentialsTemplated = detected != null,
            )
        } catch (t: Throwable) {
            sink.abort(t)
            throw t
        }
    }

    fun keyed(e: ParsedChannel): KeyedChannel {
        val template = template(e.url)
        val ch = e.copy(catchup = e.catchup.copy(source = e.catchup.source?.let(::template)))
        val key = if (e.tvgId != null) StableKey("tvg:${e.tvgId}") else StableKey("url:${template.substringAfter("://").substringAfter('/')}")
        val rule = if (e.tvgId != null) KeyRule.TVG else KeyRule.URL
        val hash = Hash64.str(Hash64.str(Hash64.str(Hash64.OFFSET, e.name), template), e.groupTitle)
        return KeyedChannel(key, rule, groupKey(e), template, hash, Hash64.of(template), Hash64.of(e.name), e.position, ch)
    }

    private fun template(url: String): String {
        val d = detected ?: return url
        return url.replace(d.password, "{{p}}").replace(d.username, "{{u}}")
    }

    private fun groupKey(e: ParsedChannel) = e.xtreamCategoryId?.let { "xcat:$it" } ?: "g:" + NameNormalizer.forGroup(e.groupTitle ?: "")
}

/** Builds a ParsedChannel quickly. */
fun pc(name: String, url: String = "http://h/live/$name.ts", position: Int = 0, group: String? = "News", tvgId: String? = null, kind: EntryKind = EntryKind.LIVE, adult: Boolean = false) =
    ParsedChannel(kind = kind, name = name, url = url, position = position, groupTitle = group, tvgId = tvgId, adult = adult)
