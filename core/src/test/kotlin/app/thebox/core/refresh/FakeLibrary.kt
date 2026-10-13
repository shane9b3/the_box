package app.thebox.core.refresh

import app.thebox.core.common.Hash64
import app.thebox.core.m3u.M3u
import app.thebox.core.m3u.M3uParseOptions
import app.thebox.core.m3u.M3uParserImpl
import app.thebox.core.source.EntryKind
import app.thebox.core.source.PlaylistType

/**
 * An in-memory stand-in for WP-D's library.db + RoomMergeSink: it applies batches and finishes the way
 * §3.3 describes, keeps favourites keyed by stable key, and streams its rows back as [ExistingChannels].
 */
class FakeLibrary(private val playlistType: PlaylistType = PlaylistType.M3U_URL) : MergeSink, ExistingChannels {
    class Row(
        val id: Long,
        var key: String,
        var channel: KeyedChannel,
        var sortIndex: Int,
        var missingSince: Long? = null,
        var missingCount: Int = 0,
    )

    val rows = LinkedHashMap<Long, Row>()
    val favourites = LinkedHashSet<String>()
    private var nextId = 1L
    var refreshSeq = 0L
    var liveCount = 0
    var begins = 0
    val batches = ArrayList<MergeBatch>()
    var lastFinish: MergeFinish? = null
    var lastGroups: List<GroupRow> = emptyList()
    var lastDetected: DetectedCredentials? = null
    var aborted: Throwable? = null
    var rowsWritten = 0
    var forEachCalls = 0
    var failOnApply: Int = -1

    override fun forEach(block: (ExistingChannel) -> Unit) {
        forEachCalls++
        for (r in rows.values) {
            block(
                ExistingChannel(
                    r.id, r.key, r.channel.contentHash, Hash64.of(r.channel.urlTemplate), Hash64.of(r.channel.channel.name),
                    r.sortIndex, r.missingSince, r.missingCount,
                    kind = r.channel.channel.kind, groupKey = r.channel.groupKey,
                ),
            )
        }
    }

    override fun begin(groups: List<GroupRow>, detected: DetectedCredentials?) {
        begins++
        lastGroups = groups
        lastDetected = detected
    }

    override fun apply(batch: MergeBatch) {
        if (failOnApply >= 0 && batches.size == failOnApply) throw IllegalStateException("disk full")
        batches += batch
        for (c in batch.inserts) {
            check(rows.values.none { it.key == c.stableKey.value }) { "duplicate key ${c.stableKey}" }
            val id = nextId++
            rows[id] = Row(id, c.stableKey.value, c, c.sortIndex)
        }
        for (u in batch.updates) {
            val r = rows.getValue(u.id)
            r.channel = u.channel
            r.sortIndex = u.channel.sortIndex
            if (u.restored) { r.missingSince = null; r.missingCount = 0 }
        }
        for (t in batch.touches) {
            val r = rows.getValue(t.id)
            r.sortIndex = t.sortIndex
            if (t.restored) { r.missingSince = null; r.missingCount = 0 }
        }
        rowsWritten += batch.inserts.size + batch.updates.size + batch.touches.size
    }

    override fun finish(finish: MergeFinish): FinishStats {
        lastFinish = finish
        val removed = ArrayList<String>()
        for (id in finish.newlyMissingIds) rows.getValue(id).let { it.missingSince = finish.nowMs; it.missingCount = 1; if (removed.size < 10) removed += it.channel.channel.name }
        for (id in finish.stillMissingIds) rows.getValue(id).missingCount++
        for (id in finish.purgeIds) rows.remove(id)
        for (r in finish.autoRemaps) if (favourites.remove(r.oldKey)) favourites += r.newKey
        refreshSeq++
        liveCount = rows.values.count { it.missingSince == null && (it.channel.channel.kind == EntryKind.LIVE || it.channel.channel.kind == EntryKind.RADIO) }
        val active = rows.values.filter { it.missingSince == null }.map { it.key }.toSet()
        val kept = favourites.count { it in active }
        return FinishStats(finish.purgeIds.size, kept, favourites.size - kept, removed)
    }

    override fun abort(error: Throwable) { aborted = error }

    fun activeKeys(): List<String> = rows.values.filter { it.missingSince == null }.sortedBy { it.sortIndex }.map { it.key }
    fun row(key: String): Row = rows.values.single { it.key == key }

    /** Runs one refresh of [path] (a classpath fixture) against this library. */
    fun refresh(
        path: String,
        nowMs: Long = 1_000_000L,
        force: Boolean = false,
        differ: PlaylistDiffer = PlaylistDifferImpl(app.thebox.core.identity.StableKeyDeriverImpl(), clock = { nowMs + 5 }),
        batchSize: Int = 2_000,
    ): RefreshReport = refreshProducer(fixtureProducer(path), nowMs, force, differ, batchSize)

    fun refreshProducer(
        producer: EntryProducer,
        nowMs: Long = 1_000_000L,
        force: Boolean = false,
        differ: PlaylistDiffer = PlaylistDifferImpl(app.thebox.core.identity.StableKeyDeriverImpl(), clock = { nowMs + 5 }),
        batchSize: Int = 2_000,
    ): RefreshReport {
        batches.clear()
        rowsWritten = 0
        val ctx = MergeContext(
            playlistId = 7, playlistType = playlistType, refreshSeq = refreshSeq + 1, nowMs = nowMs,
            previousLiveCount = liveCount, forceAcceptShrink = force, batchSize = batchSize,
        )
        return differ.merge(producer, this, this, ctx)
    }

    companion object {
        fun fixtureProducer(path: String, options: M3uParseOptions = M3uParseOptions()) = EntryProducer { consumer ->
            M3uParserImpl().parse(M3u.fixture(path), options, object : app.thebox.core.m3u.M3uSink {
                override fun onEntry(entry: app.thebox.core.source.ParsedChannel) = consumer(entry)
            })
        }

        fun listProducer(list: List<app.thebox.core.source.ParsedChannel>) = EntryProducer { consumer -> list.forEach(consumer) }
    }
}
