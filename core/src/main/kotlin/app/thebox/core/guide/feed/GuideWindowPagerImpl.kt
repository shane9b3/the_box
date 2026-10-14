package app.thebox.core.guide.feed

import androidx.collection.MutableIntFloatMap
import androidx.collection.MutableIntIntMap
import app.thebox.core.common.BoxLog
import app.thebox.core.common.Hash64
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.epg.EpgGenerationInfo
import app.thebox.core.epg.EpgReader
import app.thebox.core.epg.EpgStore
import app.thebox.core.epg.EpgWindow
import app.thebox.core.epg.ProgrammeDetail
import app.thebox.core.guide.CellKind
import app.thebox.core.guide.CellSink
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideMetrics
import app.thebox.core.guide.GuideRows
import app.thebox.core.guide.GuideTextMeasurer
import app.thebox.core.guide.GuideTextStyle
import app.thebox.core.guide.GuideWindowData
import app.thebox.core.guide.GuideWindowPager
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.RowCellsBuilder
import app.thebox.core.guide.RowStatus
import app.thebox.core.guide.TimeLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Bidi
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Programme windows for the guide (docs/design/m3-guide.md §3.2.3).
 *
 * Threads: [ensure], [invalidate], [trimMemory] and [close] are called on main and only compare integers, read
 * [rows] (main-only) and post at most one runnable. Everything else (the [EpgReader], the ref → generation-local id
 * map, the width/RTL/info caches, cell building and title measurement) is confined to the `box-guide-epg` thread
 * (one daemon thread per pager). `epg_channel` ints never leave that thread.
 *
 * Snapshots (review B3): every [GuideWindowData] is built from freshly allocated arrays and never written after
 * publication. Generation safety (§0.6): a new generation opens a new reader, re-resolves, re-queries, publishes, then
 * closes the old reader on this thread (M1 Later 11); a patch re-resolves and re-queries; `SQLITE_BUSY` keeps the old
 * snapshot and retries (S7).
 */
class GuideWindowPagerImpl(
    private val store: EpgStore,
    private val rows: GuideRows,
    private val metrics: () -> GuideMetrics?,
    private val measurer: () -> GuideTextMeasurer,
    private val builder: RowCellsBuilder,
    private val labels: TimeLabels,
    /** Test hook: the reader factory (defaults to [EpgStore.openReader]). */
    private val openReader: () -> EpgReader? = store::openReader,
    private val busyRetryMs: Long = BUSY_RETRY_MS,
) : GuideWindowPager {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, THREAD_NAME).apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val snaps = MutableStateFlow<GuideWindowData?>(null)
    private val gen = MutableStateFlow<EpgGenerationInfo?>(null)
    override val snapshots: StateFlow<GuideWindowData?> = snaps.asStateFlow()
    override val generation: StateFlow<EpgGenerationInfo?> = gen.asStateFlow()

    /** One target: rows [firstRow, firstRow + channels.size) × display minutes [fromMin, toMin). Immutable. */
    private class Request(
        val firstRow: Int,
        val channels: Array<GuideChannel?>,
        val fromMin: Int,
        val toMin: Int,
        val rowsVersion: Long,
        val lineupVersion: Long,
        val seq: Long,
    )

    // ---- main thread
    private var covFirst = 0
    private var covEnd = 0
    private var covFrom = 0
    private var covTo = 0
    private var covRowsVersion = -1L
    private var covScale = Float.NaN
    private var covValid = false
    private var lastTop = -1
    private var lastWs = 0
    private var lastHold = 0
    private val requestSeq = AtomicLong()

    // ---- handoff
    private val pending = AtomicReference<Request?>(null)
    private val scheduled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    // ---- box-guide-epg thread only
    private var reader: EpgReader? = null
    private var readerGeneration = Long.MIN_VALUE
    private var readerPatch = Int.MIN_VALUE
    private var lastRun: Request? = null
    /** Highest request seq started on this thread: a busy retry of an older request never overwrites a newer one. */
    private var startedSeq = 0L
    private val refIds = HashMap<EpgChannelRef, Int>()
    private val widths = MutableIntFloatMap()
    private val textFlags = MutableIntIntMap()
    private var widthScale = Float.NaN
    private val epgWindow = EpgWindow(512)
    private val sink = Sink()
    private val infoCache = object : LinkedHashMap<InfoKey, ProgrammeInfo>(INFO_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<InfoKey, ProgrammeInfo>?) = size > INFO_CACHE
    }
    private val snapshotSeq = AtomicLong()

    /** Test/diagnostic counters (read from any thread). */
    @Volatile var queries: Int = 0
        private set
    @Volatile var lastBuildMs: Double = 0.0
        private set

    private data class InfoKey(val generation: Long, val identityHash: Long, val rawStart: Int)

    init {
        scope.launch { store.info.collect { onInfo(it) } }
    }

    // ------------------------------------------------------------------------------------------------ main thread

    override fun ensure(topRow: Int, windowStartMin: Int, holdDirection: Int) {
        if (closed.get()) return
        lastTop = topRow
        lastWs = windowStartMin
        lastHold = holdDirection
        val size = rows.size
        if (size == 0) return
        val marginRows = if (holdDirection != 0) HOLD_MARGIN_ROWS else MARGIN_ROWS
        val needFirst = (topRow - marginRows).coerceIn(0, size)
        val needEnd = (topRow + VISIBLE_ROWS + marginRows).coerceIn(0, size)
        val needFrom = windowStartMin - MARGIN_MIN
        val needTo = windowStartMin + VISIBLE_MIN + MARGIN_MIN
        val scale = metrics()?.scale ?: Float.NaN
        if (covValid && needFirst >= covFirst && needEnd <= covEnd && needFrom >= covFrom && needTo <= covTo &&
            (scale == covScale || (scale.isNaN() && covScale.isNaN())) && !rowsChanged(needFirst, needEnd)
        ) {
            return
        }
        val (tFirst, tEnd) = when {
            holdDirection > 0 -> topRow - HOLD_BEHIND to topRow + HOLD_AHEAD
            holdDirection < 0 -> topRow + VISIBLE_ROWS - HOLD_AHEAD to topRow + VISIBLE_ROWS + HOLD_BEHIND
            else -> topRow - TARGET_BEFORE to topRow + TARGET_AFTER
        }
        val first = tFirst.coerceIn(0, size)
        val end = tEnd.coerceIn(first, size)
        val from = windowStartMin - TARGET_MIN_BEFORE
        val to = windowStartMin + TARGET_MIN_AFTER
        val channels = arrayOfNulls<GuideChannel>(end - first)
        for (i in channels.indices) channels[i] = rows.channel(first + i)
        covFirst = first; covEnd = end; covFrom = from; covTo = to
        covRowsVersion = rows.version; covScale = scale; covValid = true
        val lineupVersion = (rows as? GuideRowsCacheImpl)?.lineupVersion ?: 0L
        post(Request(first, channels, from, to, rows.version, lineupVersion, requestSeq.incrementAndGet()))
    }

    /**
     * Rows changed since the covered request: re-query only when a row of the margin need was PENDING then and is loaded
     * now, or its `(epg, epgShiftMin)` basis differs (B5, S10a). A display-name change alone does not re-query.
     */
    private fun rowsChanged(needFirst: Int, needEnd: Int): Boolean {
        if (rows.version == covRowsVersion) return false
        val snap = snaps.value
        for (r in needFirst until needEnd) {
            val now = basisOf(rows.channel(r))
            val then = if (snap != null && r >= snap.firstRow && r < snap.firstRow + snap.rowCount) snap.rowEpgBasis[r - snap.firstRow] else PENDING_BASIS
            if (now != then) return true
        }
        covRowsVersion = rows.version
        return false
    }

    override fun invalidate() {
        covValid = false
        if (lastTop >= 0) ensure(lastTop, lastWs, lastHold)
    }

    /**
     * §4.7 trim (called by the VM's `onTrimMemory` fan-out): clears the width cache and the detail LRU; with
     * [dropSnapshot] (`TRIM_MEMORY_UI_HIDDEN`) also the current snapshot, which the next [ensure] rebuilds.
     */
    fun trimMemory(dropSnapshot: Boolean) {
        if (closed.get()) return
        if (dropSnapshot) {
            covValid = false
            snaps.value = null
        }
        scope.launch {
            widths.clear()
            textFlags.clear()
            infoCache.clear()
        }
    }

    private fun post(r: Request) {
        pending.set(r)
        if (!scheduled.getAndSet(true)) {
            scope.launch {
                scheduled.set(false)
                val next = pending.getAndSet(null) ?: return@launch
                run(next, attempt = 0)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        executor.execute {
            try {
                reader?.close()
            } catch (e: Exception) {
                BoxLog.w(TAG, "closing the guide reader failed: ${e.javaClass.simpleName}")
            }
            reader = null
        }
        executor.shutdown()
    }

    // ------------------------------------------------------------------------------------------------ epg thread

    override suspend fun info(channel: GuideChannel, rawStartMin: Int): ProgrammeInfo? = onEpg {
        val r = reader ?: return@onEpg null
        val key = InfoKey(r.info.generation, channel.identityHash, rawStartMin)
        infoCache[key]?.let { return@onEpg it }
        val id = idOf(r, channel.epg ?: return@onEpg null)
        if (id < 0) return@onEpg null
        val info = r.detail(id, rawStartMin, channel.epgShiftMin)?.toInfo() ?: return@onEpg null
        infoCache[key] = info
        info
    }

    override suspend fun at(channel: GuideChannel, atMin: Int): ProgrammeInfo? = onEpg {
        val r = reader ?: return@onEpg null
        val id = idOf(r, channel.epg ?: return@onEpg null)
        if (id < 0) null else r.at(id, atMin, channel.epgShiftMin)?.toInfo()
    }

    private suspend fun <T> onEpg(block: () -> T?): T? {
        if (closed.get()) return null
        return try {
            withContext(dispatcher) { if (closed.get()) null else block() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (closed.get()) null else throw e
        } catch (e: Exception) {
            if (SqliteBusy.isBusy(e)) null else throw e
        }
    }

    private fun onInfo(info: EpgGenerationInfo?) {
        gen.value = info
        val old = reader
        when {
            info == null -> {
                reader = null
                readerGeneration = Long.MIN_VALUE
                readerPatch = Int.MIN_VALUE
                clearGenerationCaches()
                lastRun?.let { rebuild(it) }
                old?.close()
            }
            old == null || info.generation != readerGeneration -> {
                val fresh = try {
                    openReader()
                } catch (e: Exception) {
                    BoxLog.w(TAG, "opening the guide reader failed: ${e.javaClass.simpleName}")
                    null
                }
                reader = fresh
                readerGeneration = fresh?.info?.generation ?: Long.MIN_VALUE
                readerPatch = fresh?.info?.patchSeq ?: Int.MIN_VALUE
                clearGenerationCaches()
                lastRun?.let { rebuild(it) }
                // M1 Later 11: the old generation's file is deleted on the closing thread, never main.
                if (old != null && old !== fresh) old.close()
            }
            info.patchSeq != readerPatch -> {
                readerPatch = info.patchSeq
                refIds.clear()
                infoCache.clear()
                lastRun?.let { rebuild(it) }
            }
        }
    }

    private fun clearGenerationCaches() {
        refIds.clear()
        widths.clear()
        textFlags.clear()
        infoCache.clear()
    }

    private fun rebuild(r: Request) {
        scope.launch { run(r, attempt = 0) }
    }

    private suspend fun run(r: Request, attempt: Int) {
        if (closed.get()) return
        val newer = pending.get()
        if (newer != null && newer.seq > r.seq) return
        if (r.seq < startedSeq) return
        startedSeq = r.seq
        lastRun = r
        try {
            val t0 = System.nanoTime()
            val data = build(r)
            lastBuildMs = (System.nanoTime() - t0) / 1e6
            if (lastBuildMs > BUILD_LOG_MS) BoxLog.i(TAG) { "snapshot ${data.rowCount} rows, ${data.cellCount} cells in ${"%.1f".format(lastBuildMs)} ms" }
            if (!closed.get()) snaps.value = data
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (SqliteBusy.isBusy(e) && attempt < BUSY_RETRIES) {
                BoxLog.i(TAG) { "epg.db busy; retry ${attempt + 1} in $busyRetryMs ms" }
                delay(busyRetryMs)
                run(r, attempt + 1)
            } else if (SqliteBusy.isBusy(e)) {
                BoxLog.w(TAG, "epg.db busy after $BUSY_RETRIES retries; keeping the last snapshot")
            } else {
                BoxLog.e(TAG, "guide window query failed", e)
            }
        }
    }

    /** One query and the cell pass (§3.2.3). Allocates the snapshot's arrays fresh (B3). */
    private fun build(r: Request): GuideWindowData {
        queries++
        val n = r.channels.size
        val rd = reader
        val generation = rd?.info?.generation ?: -1L
        val patchSeq = rd?.info?.patchSeq ?: 0
        val scale = metrics()?.scale ?: measurer().scale
        if (scale != widthScale) {
            widths.clear()
            widthScale = scale
        }
        if (widths.size > WIDTH_CACHE_MAX) widths.clear()
        if (textFlags.size > WIDTH_CACHE_MAX) textFlags.clear()

        val ids = IntArray(n) { -1 }
        val shifts = IntArray(n)
        if (rd != null) {
            var misses: ArrayList<EpgChannelRef>? = null
            for (i in 0 until n) {
                val ref = r.channels[i]?.epg ?: continue
                if (!refIds.containsKey(ref)) (misses ?: ArrayList<EpgChannelRef>().also { misses = it }) += ref
            }
            misses?.let { m ->
                val distinct = m.distinct()
                val resolved = rd.resolve(distinct)
                for (k in distinct.indices) refIds[distinct[k]] = resolved[k]
            }
            for (i in 0 until n) {
                val ch = r.channels[i] ?: continue
                shifts[i] = ch.epgShiftMin
                ids[i] = ch.epg?.let { refIds[it] } ?: -1
            }
            rd.window(ids, shifts, r.fromMin, r.toMin, epgWindow)
        } else {
            epgWindow.clear()
        }

        sink.reset()
        val status = ByteArray(n)
        val firstCell = IntArray(n)
        val cellCount = IntArray(n)
        val basis = LongArray(n)
        val w = epgWindow
        var k = 0
        for (i in 0 until n) {
            val ch = r.channels[i]
            basis[i] = basisOf(ch)
            firstCell[i] = sink.size
            if (ch == null) {
                status[i] = RowStatus.PENDING
                cellCount[i] = 0
                continue
            }
            val start = k
            while (k < w.size && w.channelIndex[k] == i) k++
            val noEpg = ids[i] < 0 || (start == k && !ch.epgHasData)
            status[i] = if (noEpg) RowStatus.NO_EPG else RowStatus.OK
            cellCount[i] = builder.build(w, start, k, r.fromMin, r.toMin, noEpg, sink)
        }
        return sink.snapshot(r, generation, patchSeq, scale, status, firstCell, cellCount, basis)
    }

    private fun idOf(r: EpgReader, ref: EpgChannelRef): Int {
        refIds[ref]?.let { return it }
        val id = r.resolve(listOf(ref))[0]
        refIds[ref] = id
        return id
    }

    private fun ProgrammeDetail.toInfo() = ProgrammeInfo(
        rawStartMin = rawStartMin, startMin = startMin, endMin = endMin, title = title, subtitle = subtitle,
        description = description, genre = categories.firstOrNull(), episodeLabel = episodeLabel, flags = flags, titleHash = titleHash,
    )

    /** Growable scratch arrays on the epg thread; [snapshot] copies them into exact-size fresh arrays. */
    private inner class Sink : CellSink {
        var size = 0
        private var start = IntArray(1024)
        private var end = IntArray(1024)
        private var raw = IntArray(1024)
        private var kind = ByteArray(1024)
        private var flags = IntArray(1024)
        private var textId = IntArray(1024)
        private var title = arrayOfNulls<String>(1024)

        fun reset() {
            title.fill(null, 0, size)
            size = 0
        }

        override fun add(start: Int, end: Int, rawStart: Int, kind: Byte, flags: Int, textId: Int, title: String?) {
            if (size == this.start.size) grow()
            val i = size++
            this.start[i] = start; this.end[i] = end; raw[i] = rawStart; this.kind[i] = kind
            this.flags[i] = flags; this.textId[i] = textId; this.title[i] = title
        }

        private fun grow() {
            val c = start.size * 2
            start = start.copyOf(c); end = end.copyOf(c); raw = raw.copyOf(c); kind = kind.copyOf(c)
            flags = flags.copyOf(c); textId = textId.copyOf(c); title = title.copyOf(c)
        }

        fun snapshot(
            r: Request, generation: Long, patchSeq: Int, scale: Float,
            status: ByteArray, firstCell: IntArray, cellCount: IntArray, basis: LongArray,
        ): GuideWindowData {
            val n = size
            val m = measurer()
            val outFlags = flags.copyOf(n)
            val outTitle = title.copyOf(n)
            val titleWidth = FloatArray(n) { -1f }
            val titleKey = LongArray(n)
            val timeText = arrayOfNulls<String>(n)
            val timeWidth = FloatArray(n) { -1f }
            for (i in 0 until n) {
                if (kind[i] != CellKind.PROGRAMME) continue
                val t = outTitle[i]
                if (t != null) {
                    val id = textId[i]
                    titleKey[i] = Hash64.of(t)
                    titleWidth[i] = if (id >= 0) {
                        val cached = widths.getOrDefault(id, -1f)
                        if (cached >= 0f) cached else m.width(t, 0, t.length, GuideTextStyle.CELL_TITLE).also { widths[id] = it }
                    } else {
                        m.width(t, 0, t.length, GuideTextStyle.CELL_TITLE)
                    }
                    val tf = if (id >= 0) textFlags.getOrDefault(id, -1) else -1
                    val f = if (tf >= 0) tf else textFlagsOf(t).also { if (id >= 0) textFlags[id] = it }
                    outFlags[i] = outFlags[i] or f
                }
                val tt = labels.hm(start[i]) + TIME_SEPARATOR + labels.hm(end[i])
                timeText[i] = tt
                timeWidth[i] = m.width(tt, 0, tt.length, GuideTextStyle.CELL_TIME)
            }
            return GuideWindowData(
                seq = snapshotSeq.incrementAndGet(), lineupVersion = r.lineupVersion, generation = generation, patchSeq = patchSeq,
                firstRow = r.firstRow, rowCount = r.channels.size, fromMin = r.fromMin, toMin = r.toMin, rowStatus = status,
                rowFirstCell = firstCell, rowCellCount = cellCount, cellStart = start.copyOf(n), cellEnd = end.copyOf(n),
                cellRawStart = raw.copyOf(n), cellKind = kind.copyOf(n), cellFlags = outFlags, cellTextId = textId.copyOf(n),
                cellTitle = outTitle, cellTitleWidth = titleWidth, cellTitleKey = titleKey, cellTimeText = timeText,
                cellTimeWidth = timeWidth, metricsScale = scale, rowsVersion = r.rowsVersion, rowEpgBasis = basis,
            )
        }
    }

    companion object {
        private const val TAG = "GuidePager"
        const val THREAD_NAME = "box-guide-epg"
        const val VISIBLE_ROWS = 6
        const val VISIBLE_MIN = 180
        const val MARGIN_ROWS = 2
        const val MARGIN_MIN = 60
        const val TARGET_BEFORE = 6
        const val TARGET_AFTER = 12
        const val TARGET_MIN_BEFORE = 180
        const val TARGET_MIN_AFTER = 360
        /** S16: hold target `[top − 2, top + 24)` going down (mirrored going up) and a 6-row margin. */
        const val HOLD_BEHIND = 2
        const val HOLD_AHEAD = 24
        const val HOLD_MARGIN_ROWS = 6
        const val BUSY_RETRY_MS = 500L
        const val BUSY_RETRIES = 3
        const val INFO_CACHE = 32
        const val WIDTH_CACHE_MAX = 8_192
        private const val BUILD_LOG_MS = 8.0
        private const val TIME_SEPARATOR = " – "

        /** Title needs bidi layout (S6): the renderer right-aligns it. Not yet in the contract (hand-off note). */
        const val CELL_RTL = 1 shl 29
        /** Title has a combining mark, variation selector or ZWJ: TextFit backs off to a grapheme boundary (S6). */
        const val CELL_COMPLEX = 1 shl 28

        /** Row basis (B5): 0 = not loaded; otherwise a hash of (epg ref, epgShiftMin). */
        const val PENDING_BASIS = 0L

        fun basisOf(ch: GuideChannel?): Long {
            if (ch == null) return PENDING_BASIS
            val ref = ch.epg
            var h = Hash64.long(Hash64.of(null), ch.epgShiftMin.toLong())
            h = Hash64.long(h, ref?.sourceId ?: -1L)
            h = Hash64.str(h, ref?.xmltvId)
            return if (h == PENDING_BASIS) 1L else h
        }

        internal fun textFlagsOf(t: String): Int {
            var f = 0
            val chars = t.toCharArray()
            if (Bidi.requiresBidi(chars, 0, chars.size)) f = f or CELL_RTL
            var i = 0
            while (i < t.length) {
                val cp = t.codePointAt(i)
                val type = Character.getType(cp)
                if (type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() ||
                    type == Character.COMBINING_SPACING_MARK.toInt() || cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF
                ) {
                    f = f or CELL_COMPLEX
                    break
                }
                i += Character.charCount(cp)
            }
            return f
        }
    }
}
