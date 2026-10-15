package app.thebox.core.full.feed

import app.thebox.core.common.BoxLog
import app.thebox.core.common.Hash64
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.epg.EpgGenerationInfo
import app.thebox.core.epg.EpgReader
import app.thebox.core.epg.EpgStore
import app.thebox.core.epg.EpgWindow
import app.thebox.core.epg.ProgrammeFlags
import app.thebox.core.full.ProgrammeFeed
import app.thebox.core.full.ProgrammeSlice
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.feed.SqliteBusy
import app.thebox.core.guide.feed.toProgrammeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `ProgrammeFeed` over [store] (docs/design/m4-player-ux.md §3.3.3, review S4/N1).
 *
 * Threads: every query, the [EpgReader], the `EpgChannelRef → epg_channel id` cache and the last-result cache live on the
 * one `box-player-epg` daemon thread. Callers get plain [ProgrammeInfo]s; no reader and no generation-local int ever
 * leaves that thread. Each call checks [EpgStore.info] first, so a new generation opens a new reader before the query
 * and the old one is closed **on this thread** (M3 decision 6, M1 Later 11); a patch (`patchSeq`) clears the id cache so
 * channels the patch imported resolve. A collector on the same thread does the same swap when the feed is idle, so an old
 * generation file is not kept open. A call that is already running finishes on the reader it started with (the handle is
 * ref-counted, so a swap mid-call cannot close it under the query).
 *
 * Statements: `range`, `nowFor` and `around` are one `EpgReader.window` each (plus one `resolve` per channel the first
 * time it is seen in a generation); `at` is one `EpgReader.at`. Rows from `window` carry title and flags only
 * (`subtitle`, `description`, `genre`, `episodeLabel` are null, like the guide's cells); `at` returns the full detail.
 * Programmes split at 6 h (`ProgrammeFlags.CONTINUATION`/`CONTINUED`) are merged into one entry; only when a split crosses
 * the queried edge does the feed run an extra `at` per extra segment to find the head or the tail.
 *
 * `SQLITE_BUSY`: retried after [busyRetryMs] up to [BUSY_RETRIES] times; then the call answers from the last programmes
 * it read for that channel (the M3 "keep the last snapshot" rule), or empty/null when it has none. No exception reaches the
 * caller except cancellation.
 */
class ProgrammeFeedImpl(
    private val store: EpgStore,
    /** Test hook: the reader factory (defaults to [EpgStore.openReader]). */
    private val openReader: () -> EpgReader? = store::openReader,
    private val busyRetryMs: Long = BUSY_RETRY_MS,
) : ProgrammeFeed {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, THREAD_NAME).apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val closed = AtomicBoolean(false)

    // ---- box-player-epg thread only
    private var reader: EpgReader? = null
    private var readerGeneration = Long.MIN_VALUE
    private var readerPatch = Int.MIN_VALUE
    private val ids = object : LinkedHashMap<EpgChannelRef, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<EpgChannelRef, Int>?) = size > ID_CACHE
    }
    /** Last programmes read per channel (identityHash), for the busy fallback. Cleared with the generation. */
    private val last = object : LinkedHashMap<Long, List<ProgrammeInfo>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<ProgrammeInfo>>?) = size > LAST_CACHE
    }
    private val window = EpgWindow(256)

    /** Test/diagnostic counters (any thread). */
    @Volatile var windowQueries: Int = 0
        private set
    @Volatile var atQueries: Int = 0
        private set

    init {
        scope.launch { store.info.collect { sync(it) } }
    }

    // ------------------------------------------------------------------------------------------------ API

    override suspend fun range(channel: GuideChannel, fromMin: Int, toMin: Int, limit: Int): List<ProgrammeInfo> =
        query(
            onBusy = { cachedOf(channel).filter { it.endMin > fromMin && it.startMin < toMin }.take(limit) },
            empty = emptyList(),
        ) { r ->
            if (limit <= 0 || toMin <= fromMin) return@query emptyList()
            val id = idOf(r, channel) ?: return@query emptyList()
            val list = programmes(r, id, channel.epgShiftMin, fromMin, toMin)
            remember(channel, list)
            list.take(limit)
        }

    override suspend fun nowFor(channels: List<GuideChannel>, atMin: Int): List<ProgrammeInfo?> =
        query(
            onBusy = { channels.map { ch -> cachedOf(ch).firstOrNull { it.startMin <= atMin && atMin < it.endMin } } },
            empty = List(channels.size) { null },
        ) { r ->
            val n = channels.size
            if (n == 0) return@query emptyList()
            val chIds = IntArray(n) { -1 }
            val shifts = IntArray(n)
            resolveAll(r, channels, chIds)
            for (i in 0 until n) shifts[i] = channels[i].epgShiftMin
            val out = arrayOfNulls<ProgrammeInfo>(n)
            if (chIds.any { it >= 0 }) {
                windowQueries++
                r.window(chIds, shifts, atMin, atMin + 1, window)
                val w = window
                for (k in 0 until w.size) {
                    val i = w.channelIndex[k]
                    if (out[i] != null || w.startMin[k] > atMin || w.endMin[k] <= atMin) continue
                    val seg = Seg(w.rawStartMin[k], w.startMin[k], w.endMin[k], w.titles[k] ?: "", w.flags[k])
                    out[i] = complete(r, chIds[i], shifts[i], arrayListOf(seg)).single().toInfo()
                }
            }
            out.asList()
        }

    override suspend fun at(channel: GuideChannel, atMin: Int): ProgrammeInfo? =
        query(onBusy = { cachedOf(channel).firstOrNull { it.startMin <= atMin && atMin < it.endMin } }, empty = null) { r ->
            val id = idOf(r, channel) ?: return@query null
            val shift = channel.epgShiftMin
            atQueries++
            val d = r.at(id, atMin, shift) ?: return@query null
            if (d.flags and (ProgrammeFlags.CONTINUATION or ProgrammeFlags.CONTINUED) == 0) return@query d.toProgrammeInfo()
            // A split programme: the head carries the reminder/catch-up key (raw start); the tail the real end.
            val merged = complete(r, id, shift, arrayListOf(Seg(d.rawStartMin, d.startMin, d.endMin, d.title, d.flags))).single()
            d.toProgrammeInfo().copy(rawStartMin = merged.rawStart, startMin = merged.start, endMin = merged.end, flags = merged.cleanFlags)
        }

    override suspend fun around(channel: GuideChannel, atMin: Int, fromMin: Int, before: Int, after: Int): ProgrammeSlice =
        query(onBusy = { slice(cachedOf(channel), atMin, before, after) }, empty = EMPTY_SLICE) { r ->
            val id = idOf(r, channel) ?: return@query EMPTY_SLICE
            val list = programmes(r, id, channel.epgShiftMin, minOf(fromMin, atMin), atMin + AROUND_AHEAD_MIN)
            remember(channel, list)
            slice(list, atMin, before, after)
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        executor.execute {
            closeQuietly(reader)
            reader = null
            ids.clear()
            last.clear()
        }
        executor.shutdown()
    }

    // ------------------------------------------------------------------------------------------------ feed thread

    /**
     * Runs [block] on the feed thread with the current generation's reader. Busy → [busyRetryMs] and again, up to
     * [BUSY_RETRIES] retries, then [onBusy] (on the feed thread). No reader, closed or any other failure → [empty].
     */
    private suspend fun <T> query(onBusy: () -> T, empty: T, block: (EpgReader) -> T): T {
        if (closed.get()) return empty
        return try {
            withContext(dispatcher) { withRetries(onBusy, empty, block) }
        } catch (e: CancellationException) {
            if (closed.get()) empty else throw e
        } catch (e: Exception) {
            BoxLog.w(TAG, "programme query failed: ${e.javaClass.simpleName}")
            empty
        }
    }

    private suspend fun <T> withRetries(onBusy: () -> T, empty: T, block: (EpgReader) -> T): T {
        var attempt = 0
        while (true) {
            if (closed.get()) return empty
            val r = sync(store.info.value) ?: return empty
            try {
                return block(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!SqliteBusy.isBusy(e)) throw e
                if (attempt >= BUSY_RETRIES) {
                    BoxLog.w(TAG, "epg.db busy after $BUSY_RETRIES retries; answering from the last programmes")
                    return onBusy()
                }
                attempt++
                BoxLog.i(TAG) { "epg.db busy; retry $attempt in $busyRetryMs ms" }
                delay(busyRetryMs)
            }
        }
    }

    /** Brings the reader in line with [info] (feed thread). Returns the reader to use, or null without a generation. */
    private fun sync(info: EpgGenerationInfo?): EpgReader? {
        if (closed.get()) return null
        val old = reader
        when {
            info == null -> {
                if (old != null) {
                    reader = null
                    readerGeneration = Long.MIN_VALUE
                    readerPatch = Int.MIN_VALUE
                    ids.clear()
                    last.clear()
                    closeQuietly(old)
                }
            }
            old == null || info.generation != readerGeneration -> {
                val fresh = try {
                    openReader()
                } catch (e: Exception) {
                    BoxLog.w(TAG, "opening the player reader failed: ${e.javaClass.simpleName}")
                    null
                }
                reader = fresh
                readerGeneration = fresh?.info?.generation ?: Long.MIN_VALUE
                readerPatch = fresh?.info?.patchSeq ?: Int.MIN_VALUE
                ids.clear()
                last.clear()
                // The old generation's file is deleted on the closing thread: this one, never main.
                if (old != null && old !== fresh) closeQuietly(old)
            }
            info.patchSeq != readerPatch -> {
                readerPatch = info.patchSeq
                ids.clear()
                last.clear()
            }
        }
        return reader
    }

    private fun closeQuietly(r: EpgReader?) {
        if (r == null) return
        try {
            r.close()
        } catch (e: Exception) {
            BoxLog.w(TAG, "closing the player reader failed: ${e.javaClass.simpleName}")
        }
    }

    /** The generation-local id of [channel]'s EPG, or null (no EPG, or not in this generation). */
    private fun idOf(r: EpgReader, channel: GuideChannel): Int? {
        val ref = channel.epg ?: return null
        val id = ids[ref] ?: r.resolve(listOf(ref))[0].also { ids[ref] = it }
        return id.takeIf { it >= 0 }
    }

    /** Fills [out] with the ids of [channels], resolving every cache miss in one call. */
    private fun resolveAll(r: EpgReader, channels: List<GuideChannel>, out: IntArray) {
        var misses: ArrayList<EpgChannelRef>? = null
        for (ch in channels) {
            val ref = ch.epg ?: continue
            if (!ids.containsKey(ref)) (misses ?: ArrayList<EpgChannelRef>().also { misses = it }) += ref
        }
        misses?.let { m ->
            val distinct = m.distinct()
            val resolved = r.resolve(distinct)
            for (k in distinct.indices) ids[distinct[k]] = resolved[k]
        }
        for (i in channels.indices) out[i] = channels[i].epg?.let { ids[it] } ?: -1
    }

    /** Programmes of one channel overlapping [fromMin, toMin) (display minutes), split programmes merged. */
    private fun programmes(r: EpgReader, id: Int, shift: Int, fromMin: Int, toMin: Int): List<ProgrammeInfo> {
        windowQueries++
        r.window(intArrayOf(id), intArrayOf(shift), fromMin, toMin, window)
        val w = window
        val segs = ArrayList<Seg>(w.size)
        for (k in 0 until w.size) segs += Seg(w.rawStartMin[k], w.startMin[k], w.endMin[k], w.titles[k] ?: "", w.flags[k])
        if (segs.isEmpty()) return emptyList()
        return complete(r, id, shift, segs).map { it.toInfo() }
    }

    /** One stored row (display minutes). */
    private class Seg(var rawStart: Int, var start: Int, var end: Int, val title: String, var flags: Int) {
        val continuation: Boolean get() = flags and ProgrammeFlags.CONTINUATION != 0
        val continued: Boolean get() = flags and ProgrammeFlags.CONTINUED != 0
        val cleanFlags: Int get() = flags and (ProgrammeFlags.CONTINUATION or ProgrammeFlags.CONTINUED).inv()

        fun toInfo() = ProgrammeInfo(
            rawStartMin = rawStart, startMin = start, endMin = end, title = title, subtitle = null, description = null,
            genre = null, episodeLabel = null, flags = cleanFlags, titleHash = Hash64.of(title),
        )
    }

    /**
     * Merges CONTINUATION rows into the segment before them (M3 open item "films over 6 h") and, when the first row is a
     * continuation or the last is continued past the queried range, looks up the missing head or tail with `at`.
     */
    private fun complete(r: EpgReader, id: Int, shift: Int, segs: ArrayList<Seg>): List<Seg> {
        val out = ArrayList<Seg>(segs.size)
        for (s in segs) {
            val prev = out.lastOrNull()
            if (s.continuation && prev != null && prev.continued && prev.end == s.start) {
                prev.end = s.end
                prev.flags = (prev.flags and ProgrammeFlags.CONTINUED.inv()) or (s.flags and ProgrammeFlags.CONTINUED)
            } else {
                out += s
            }
        }
        val head = out.first()
        var hops = 0
        while (head.continuation && hops++ < MAX_SPLIT_HOPS) {
            atQueries++
            val d = r.at(id, head.start - 1, shift) ?: break
            if (d.endMin != head.start) break
            head.rawStart = d.rawStartMin
            head.start = d.startMin
            head.flags = (d.flags and ProgrammeFlags.CONTINUED.inv()) or (head.flags and ProgrammeFlags.CONTINUED)
        }
        val tail = out.last()
        hops = 0
        while (tail.continued && hops++ < MAX_SPLIT_HOPS) {
            atQueries++
            val d = r.at(id, tail.end, shift) ?: break
            if (d.startMin != tail.end || d.flags and ProgrammeFlags.CONTINUATION == 0) break
            tail.end = d.endMin
            tail.flags = (tail.flags and ProgrammeFlags.CONTINUED.inv()) or (d.flags and ProgrammeFlags.CONTINUED)
        }
        return out
    }

    private fun remember(channel: GuideChannel, list: List<ProgrammeInfo>) {
        if (list.isEmpty()) return
        val prev = last[channel.identityHash]
        // Keep the larger picture when a narrow query (range) follows a wide one (around) over the same programmes.
        last[channel.identityHash] = if (prev == null) list else (prev + list).distinctBy { it.startMin }.sortedBy { it.startMin }.takeLast(LAST_PER_CHANNEL)
    }

    private fun cachedOf(channel: GuideChannel): List<ProgrammeInfo> = last[channel.identityHash] ?: emptyList()

    companion object {
        private const val TAG = "PlayerEpgFeed"
        const val THREAD_NAME = "box-player-epg"
        /** `around` reads one window from `fromMin` to `atMin` + this (S4). */
        const val AROUND_AHEAD_MIN = 1_440
        /** `EpgChannelRef` → generation-local channel id cache size, per generation. */
        const val ID_CACHE = 1_024
        /** M3 constants (GuideWindowPagerImpl). */
        const val BUSY_RETRY_MS = 500L
        const val BUSY_RETRIES = 3
        /** Channels whose last programmes are kept for the busy fallback, and programmes per channel. */
        const val LAST_CACHE = 32
        const val LAST_PER_CHANNEL = 96
        /** A split programme has at most this many extra segments looked up across a query edge (6 h each). */
        private const val MAX_SPLIT_HOPS = 4

        private val EMPTY_SLICE = ProgrammeSlice(emptyList(), -1)

        /**
         * Cuts [list] (start order) around [atMin]: up to [before] programmes before the one airing at [atMin], that one,
         * then up to [after] later ones. Gap at [atMin]: up to [before] starting before it and [after] from it,
         * `nowIndex = −1`.
         */
        fun slice(list: List<ProgrammeInfo>, atMin: Int, before: Int, after: Int): ProgrammeSlice {
            if (list.isEmpty()) return EMPTY_SLICE
            val b = before.coerceAtLeast(0)
            val a = after.coerceAtLeast(0)
            val now = list.indexOfFirst { it.startMin <= atMin && atMin < it.endMin }
            return if (now >= 0) {
                val from = (now - b).coerceAtLeast(0)
                val to = minOf(list.size, now + a + 1)
                ProgrammeSlice(list.subList(from, to).toList(), now - from)
            } else {
                val firstAfter = list.indexOfFirst { it.startMin >= atMin }.let { if (it < 0) list.size else it }
                val from = (firstAfter - b).coerceAtLeast(0)
                val to = minOf(list.size, firstAfter + a)
                ProgrammeSlice(list.subList(from, to).toList(), -1)
            }
        }
    }
}
