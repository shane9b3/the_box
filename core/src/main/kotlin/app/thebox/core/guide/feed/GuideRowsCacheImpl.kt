package app.thebox.core.guide.feed

import androidx.collection.MutableIntIntMap
import androidx.collection.MutableIntObjectMap
import app.thebox.core.common.BoxLog
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideLibrary
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideRowsCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Row blocks over [GuideLibrary.channels] (docs/design/m3-guide.md §3.2.1). Main-thread API: every field is read and
 * written on the thread of [scope] (the VM's main scope); [GuideLibrary.channels] switches to IO itself. Blocks of
 * [ROW_BLOCK] lineup positions, at most [ROW_BLOCKS_MAX], evicting the least recently ensured. Block results live in a
 * primitive map keyed by block index, so [channel] never boxes (N3).
 *
 * Each load carries a token; a result is applied only when it is still the newest load for that block of the current
 * lineup, so a [reloadLoaded] (B5) or a [setLineup] supersedes loads in flight.
 */
class GuideRowsCacheImpl(private val library: GuideLibrary, private val scope: CoroutineScope) : GuideRowsCache {
    private var lineup: GuideLineup? = null
    private var rowIds: LongArray = EMPTY_IDS
    private val blocks = MutableIntObjectMap<Array<GuideChannel?>>()
    /** Newest load token per block; a result with another token is dropped. */
    private val tokens = MutableIntIntMap()
    private val jobs = MutableIntObjectMap<Job>()
    /** Block indices, least recently ensured first (≤ [ROW_BLOCKS_MAX] + the ensure window). */
    private val lru = ArrayList<Int>(ROW_BLOCKS_MAX + 4)
    private var nextToken = 1
    private val versionFlow = MutableStateFlow(0L)

    override val size: Int get() = rowIds.size
    override val version: Long get() = versionFlow.value
    override val changes: StateFlow<Long> = versionFlow.asStateFlow()

    /** The current lineup's [GuideLineup.version] (0 before the first); the pager stamps snapshots with it (§3.2.3). */
    override val lineupVersion: Long get() = lineup?.version ?: 0L

    override fun channel(index: Int): GuideChannel? {
        if (index < 0 || index >= rowIds.size) return null
        val block = blocks[index / ROW_BLOCK] ?: return null
        return block[index % ROW_BLOCK]
    }

    override fun indexOf(rowId: Long): Int {
        val ids = rowIds
        for (i in ids.indices) if (ids[i] == rowId) return i
        return -1
    }

    override fun setLineup(lineup: GuideLineup) {
        val old = this.lineup
        if (old != null && old.source == lineup.source && old.version == lineup.version && old.rowIds.contentEquals(lineup.rowIds)) {
            this.lineup = lineup
            return
        }
        this.lineup = lineup
        rowIds = lineup.rowIds
        jobs.forEachValue { it.cancel() }
        jobs.clear()
        blocks.clear()
        tokens.clear()
        lru.clear()
        bump()
    }

    override fun ensure(from: Int, to: Int) {
        val n = rowIds.size
        if (n == 0) return
        val lo = (from - ROW_BLOCK).coerceAtLeast(0)
        val hi = (to + ROW_BLOCK).coerceAtMost(n)
        if (lo >= hi) return
        for (b in lo / ROW_BLOCK..(hi - 1) / ROW_BLOCK) {
            touch(b)
            if (!blocks.containsKey(b) && !jobs.containsKey(b)) load(b)
        }
        evict()
    }

    override suspend fun channelAt(index: Int): GuideChannel? {
        channel(index)?.let { return it }
        val ids = rowIds
        if (index < 0 || index >= ids.size) return null
        ensure(index, index + 1)
        return library.channels(ids, index, index + 1).firstOrNull()
    }

    override fun reloadLoaded() {
        val loaded = ArrayList<Int>(blocks.size)
        blocks.forEachKey { loaded += it }
        jobs.forEachKey { if (it !in loaded) loaded += it }
        for (b in loaded) load(b)
    }

    /** §4.7 trim: drops blocks that do not intersect `[keepFrom, keepTo)` (the VM passes `[top − 6, top + 12)`). */
    override fun trim(keepFrom: Int, keepTo: Int) {
        val lo = keepFrom.coerceAtLeast(0) / ROW_BLOCK
        val hi = (keepTo - 1).coerceAtLeast(0) / ROW_BLOCK
        val drop = ArrayList<Int>()
        blocks.forEachKey { if (it < lo || it > hi) drop += it }
        if (drop.isEmpty()) return
        for (b in drop) drop(b)
        bump()
    }

    private fun load(b: Int) {
        val ids = rowIds
        val from = b * ROW_BLOCK
        val to = minOf(ids.size, from + ROW_BLOCK)
        if (from >= to) return
        val token = nextToken++
        tokens[b] = token
        jobs[b]?.cancel()
        jobs[b] = scope.launch {
            val loaded = try {
                library.channels(ids, from, to)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.w(TAG, "row block $b failed: ${e.javaClass.simpleName}")
                if (tokens.getOrDefault(b, 0) == token) jobs.remove(b)
                return@launch
            }
            if (ids !== rowIds || tokens.getOrDefault(b, 0) != token) return@launch
            jobs.remove(b)
            val arr = arrayOfNulls<GuideChannel>(ROW_BLOCK)
            for (i in 0 until minOf(loaded.size, to - from)) arr[i] = loaded[i]
            blocks[b] = arr
            if (b !in lru) lru += b
            evict()
            bump()
        }
    }

    private fun touch(b: Int) {
        lru.remove(b)
        lru += b
    }

    private fun evict() {
        while (lru.size > ROW_BLOCKS_MAX) drop(lru[0])
    }

    private fun drop(b: Int) {
        lru.remove(b)
        blocks.remove(b)
        jobs.remove(b)?.cancel()
        tokens.remove(b)
    }

    private fun bump() {
        versionFlow.value = versionFlow.value + 1
    }

    companion object {
        private const val TAG = "GuideRows"
        const val ROW_BLOCK = 32
        const val ROW_BLOCKS_MAX = 8
        private val EMPTY_IDS = LongArray(0)
    }
}
