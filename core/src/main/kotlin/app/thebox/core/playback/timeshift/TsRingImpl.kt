// WP-A (docs/design/m6-timeshift.md §3, §4.1, §4.4–§4.6, A-S1, A-S3, A-S10, A-B2).
package app.thebox.core.playback.timeshift

import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.timeshift.TimeshiftConstants.FLUSH_PARTIAL_MS
import app.thebox.core.playback.timeshift.TimeshiftConstants.FREE_SPACE_CHECK_MS
import app.thebox.core.playback.timeshift.TimeshiftConstants.FREE_SPACE_RESERVE_MIN
import app.thebox.core.playback.timeshift.TimeshiftConstants.NLINK_CHECK_MS
import app.thebox.core.playback.timeshift.TimeshiftConstants.PUMP_BLOCK_MAX_MS
import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_BLOCK_BYTES
import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_EXTENT_BLOCKS
import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_HEADER_BYTES
import app.thebox.core.playback.timeshift.TimeshiftConstants.DAILY_COUNTER_FLUSH_BYTES
import java.io.IOException
import java.nio.Buffer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock

/**
 * The time-shift ring (§3): logical 64-bit offsets over a [RingStorage] file of `capacity` bytes (a multiple of
 * RING_BLOCK_BYTES), with a RAM [BlockPool] in front of it and an in-memory [RingIndexImpl].
 *
 * **Layout.** Logical offset `L` lives at file position `RING_HEADER_BYTES + L mod capacity`. Blocks are whole packets
 * and capacity is whole blocks, so a wrap never splits a packet or a block (§3.1).
 *
 * **Invariants (§3.2), asserted in the tests:** `0 ≤ tail ≤ min(pins) ≤ head`; `head − tail ≤ capacity`; every byte
 * of `[tail, head)` reads back as written (from RAM or from the file); `tail` only moves forward, onto an index entry
 * (the oldest RAP where one exists, A-N8).
 *
 * **Readability.** RAM holds the newest `ramBlocks` blocks; the file holds `[fileValidFrom, flushed)`. A RAM block is
 * reused for block `b + ramBlocks` once it is flushed (A-B2), or, when nothing is persisted (PAUSE_ONLY at live, or after
 * a disk stop), once `tail` has moved past it, which the pins may forbid (then `claim` answers FULL).
 *
 * **FULL (A-S3)** is decided in [claim] before the upstream read: the next block would push `tail` past `min(pins)`.
 * Paused: FULL at once (the writer stops with `FULL_WHILE_PAUSED`). Playing: [claim] waits for a pin to move for at most
 * its `timeoutMs`, so backpressure reaches TCP; the writer turns a long stall into a gap (`PIN_STALL_MS`).
 *
 * **Disk stops** (`DISK_ERROR`, `STORAGE_EVICTED`, `DISK_TOO_SLOW`, §4.6) make the ring RAM-only for the rest of the
 * generation: `stop` is set and `saving` is false, but the writer keeps committing into RAM so a reader at live goes on,
 * and the file part already saved stays readable while the pins hold it. Any other stop ends the data ([ended]): a reader
 * at `head` then gets `RingEndException(stop)`.
 *
 * **Threads.** One writer thread calls claim/commit/startSegment and the [sink]; the flusher calls [flushStep]; any thread
 * may pin, open readers, read the index, set `mode` and `stopRecording`. State changes take [lock]; waits park outside it
 * (no allocation per wait, §15). With no [flusher] (tests), [claim] flushes inline when it needs a RAM block back, so a
 * single thread can drive the ring deterministically.
 *
 * [persistedBytes] and [persistListener] are the daily write counter's source (§8.4, contract since the lead
 * reconciliation R4).
 *
 * **Time limit (§3.2, R5).** With [lengthMs] > 0, each commit also moves `tail` to the first RAP whose PTS mono is
 * ≥ newest − [lengthMs], never past `min(pins)`: a pin (the paused playhead, a reader behind) keeps older media; the
 * time limit never answers FULL.
 */
class TsRingImpl internal constructor(
    private val storage: RingStorage,
    private val clock: MonoClock,
    ramBlocks: Int,
    indexEntries: Int,
    private val flusher: RingFlusher?,
    /** The rewind window (§3.2, R5); 0 = no time limit. */
    private val lengthMs: Long = 0L,
) : TsRing {
    /**
     * Production shape ([TsRingFactory]): the process's shared `box-ts-flush` thread, or, for a [RingStorage.blocking]
     * storage, a dedicated flusher owned by this ring (storage locations §4.2, A-B2: [RingFlusher.forStorage]).
     */
    constructor(storage: RingStorage, clock: MonoClock, ramBlocks: Int, indexEntries: Int, lengthMs: Long = 0L) :
        this(storage, clock, ramBlocks, indexEntries, RingFlusher.forStorage(storage), lengthMs)

    internal val pool = BlockPool(ramBlocks.coerceAtLeast(2))
    private val nBlocks = pool.blocks
    private val lock = ReentrantLock()
    override val index = RingIndexImpl(indexEntries)

    private val storageCapacity: Long = (storage.capacityBytes / BLOCK) * BLOCK
    @Volatile private var cap: Long = if (storageCapacity >= 2 * BLOCK) storageCapacity else nBlocks * BLOCK
    @Volatile private var headV = 0L
    @Volatile private var tailV = 0L
    @Volatile private var gen = 1L
    @Volatile private var stopV: RecordStop? = null
    @Volatile private var savingV = false
    @Volatile private var modeV = BufferMode.PERSIST
    /** No more data will be committed in this generation (a non-disk stop, or close). */
    @Volatile internal var ended = false; private set
    /** Storage is unusable for this generation (§4.6): RAM only. */
    @Volatile internal var ramOnly = false; private set
    /** The file holds `[fileValidFrom, flushed)`. */
    @Volatile internal var flushed = 0L; private set
    @Volatile internal var fileValidFrom = 0L; private set
    @Volatile internal var persisting = true; private set
    @Volatile private var closed = false
    @Volatile private var storageBroken = storageCapacity < 2 * BLOCK

    /**
     * (Fix pass — playback, S4) False once a disk stop (or a capacity under two blocks) made the ring RAM-only. `reset()`
     * keeps that state, so the session factory rebuilds the storage and the ring instead of reusing this one.
     */
    val storageUsable: Boolean get() = !storageBroken

    /** (Fix pass — playback, B2 "also") The writer that currently records into this ring; set by `RingWriterImpl.start`. */
    @Volatile internal var owner: Any? = null

    /** (L4) The generation of the batch being scanned; the sink drops calls of a stale pump. −1 = unfenced (tests). */
    @Volatile private var scanGeneration = -1L

    private var flushRequested = 0L
    private var unflushedSince = Long.MIN_VALUE
    private var flushEpoch = 0L
    private var currentBlock = -1L
    private var claimOutstanding = false
    private var paused = false
    private var growthStop = false
    private var lastCommitArrival = Long.MIN_VALUE
    private var pendingArrival = Long.MIN_VALUE

    // flusher-thread state
    private var allocatedData = 0L
    private var lastSpaceCheck = Long.MIN_VALUE
    @Volatile private var growthHalted = false
    private var lastNlinkCheck = Long.MIN_VALUE
    private var persistedSinceReport = 0L

    /** Bytes written to the file over the ring's life (the daily write counter's source, §8.4). */
    @Volatile override var persistedBytes = 0L; private set
    /** Called on the flusher thread with the bytes persisted since the last call, every DAILY_COUNTER_FLUSH_BYTES. */
    @Volatile override var persistListener: ((Long) -> Unit)? = null

    /** Writer hook: every new segment (pump thread). */
    @Volatile internal var segmentListener: ((SegmentReason, Long) -> Unit)? = null

    // pins and readers
    private val pins = AtomicLongArray(PIN_SLOTS).also { for (i in 0 until PIN_SLOTS) it.set(i, UNSET) }
    private val readers = arrayOfNulls<RingReaderImpl>(PIN_SLOTS)
    private val waiters = AtomicReferenceArray<Thread?>(PIN_SLOTS)
    @Volatile private var writerWaiter: Thread? = null

    // counters (tests, stats)
    @Volatile internal var poolWaits = 0L; private set
    @Volatile internal var fullWaits = 0L; private set

    /** Writer → index (§3.3, §3.4). Called on the writer thread from [TsIndexer.scan], before the commit. */
    internal val sink: IndexSink = Sink()

    init {
        ramOnly = storageBroken
        persisting = !ramOnly
        if (!storageBroken) {
            try { storage.writeHeader(gen, cap) } catch (e: IOException) { markBroken() }
        }
        flusher?.register(this)
    }

    // ================================================================ TsRing

    override val capacityBytes: Long get() = cap
    override val tail: Long get() = tailV
    override val head: Long get() = headV
    override val generation: Long get() = gen
    override val saving: Boolean get() = savingV
    override val stop: RecordStop? get() = stopV
    override var mode: BufferMode
        get() = modeV
        set(value) { modeV = value }

    override fun claim(minBytes: Int, timeoutMs: Long, paused: Boolean, slot: RingSlot): ClaimResult {
        val startNs = System.nanoTime()
        var poolWaitStart = -1L
        var counted = false
        while (true) {
            var waitNs: Long
            lockHot()
            try {
                writerWaiter = null
                if (slot.generation != gen) return ClaimResult.STALE_GENERATION
                if (closed || ended) return ClaimResult.STOPPED
                this.paused = paused
                val h = headV
                val b = h / BLOCK
                if (h % BLOCK != 0L || currentBlock == b) {
                    fill(slot, b, h); claimOutstanding = true
                    return ClaimResult.OK
                }
                updatePersistence(b)
                maybeShrink(b)
                val prev = b - nBlocks
                val recycleEnd = (prev + 1) * BLOCK
                var bound = (b + 1) * BLOCK - cap
                var flushWait = false
                if (prev >= 0 && recycleEnd > tailV) {
                    val inFile = persisting && flushed >= recycleEnd && fileValidFrom <= prev * BLOCK
                    if (!inFile) { if (persisting) flushWait = true else if (recycleEnd > bound) bound = recycleEnd }
                }
                val newTail = alignTail(bound)
                if (newTail > tailV && newTail > minPin()) {
                    if (paused) return ClaimResult.FULL
                    val left = timeoutMs * 1_000_000L - (System.nanoTime() - startNs)
                    if (left <= 0) return ClaimResult.FULL
                    if (!counted) { fullWaits++; counted = true }
                    writerWaiter = Thread.currentThread()
                    waitNs = minOf(left, WAIT_SLICE_NS)
                } else if (flushWait && flusher == null) {
                    // No flusher thread (tests, deterministic mode): flush inline under the (reentrant) lock.
                    if (!flushStep()) return ClaimResult.DISK_STALLED
                    continue
                } else if (flushWait) {
                    val now = clock.nowMs()
                    if (poolWaitStart < 0) { poolWaitStart = now; poolWaits++ }
                    val realMs = (System.nanoTime() - startNs) / 1_000_000L
                    if (now - poolWaitStart >= PUMP_BLOCK_MAX_MS || realMs >= PUMP_BLOCK_MAX_MS) return ClaimResult.DISK_STALLED
                    writerWaiter = Thread.currentThread()
                    flusher?.wake()
                    waitNs = POOL_SLICE_NS
                } else {
                    if (newTail > tailV) advanceTail(newTail)
                    pool.slotFor(b).assign(b)
                    currentBlock = b
                    fill(slot, b, h); claimOutstanding = true
                    return ClaimResult.OK
                }
            } finally { lock.unlock() }
            LockSupport.parkNanos(this, waitNs)
        }
    }

    override fun commit(slot: RingSlot, length: Int, arrivalMonoMs: Long) {
        var wakeFlusher = false
        lockHot()
        try {
            if (slot.generation != gen || closed) return
            claimOutstanding = false
            if (length <= 0) return
            val h = headV
            val room = BLOCK - h % BLOCK
            val n = minOf(length.toLong(), room)
            val nh = h + n
            headV = nh
            lastCommitArrival = arrivalMonoMs
            if (!savingV && !ended && !ramOnly) savingV = true
            index.onArrival(arrivalMonoMs)
            if (lengthMs > 0) applyTimeLimit()
            if (nh % BLOCK == 0L) { flushRequested = nh; unflushedSince = Long.MIN_VALUE; wakeFlusher = persisting }
            else if (unflushedSince == Long.MIN_VALUE) unflushedSince = clock.nowMs()
        } finally { lock.unlock() }
        wakeReaders()
        if (wakeFlusher) flusher?.wake()
    }

    override fun startSegment(reason: SegmentReason, gapMs: Long) {
        lock.lock()
        try {
            if (closed) return
            if (ended) {
                ended = false
                val s = stopV
                if (s != null && !isDiskStop(s)) stopV = null
            }
        } finally { lock.unlock() }
        beginSegment(headV, reason, gapMs)
    }

    override fun stopRecording(reason: RecordStop) {
        lock.lock()
        try {
            if (closed) return
            if (isDiskStop(reason)) {
                if (!ramOnly) {
                    ramOnly = true; storageBroken = true; persisting = false; flushEpoch++; savingV = false
                    if (stopV == null) stopV = reason
                }
            } else {
                if (stopV == null) stopV = reason
                ended = true; savingV = false
            }
        } finally { lock.unlock() }
        wakeAll()
    }

    /**
     * (Integration 2026-10-19) [stopRecording] for [generation] only, checked under the lock: a closing pump's
     * "generation still mine, then stop" was two steps, and a `reset()` between them ended the next cycle's generation.
     */
    internal fun stopRecordingFor(generation: Long, reason: RecordStop) {
        lock.lock()
        try {
            if (closed || generation != gen) return
            if (stopV == null) stopV = reason
            ended = true; savingV = false
        } finally { lock.unlock() }
        wakeAll()
    }

    override fun setPin(owner: Int, offset: Long) {
        require(owner in 0 until PIN_SLOTS) { "pin owner $owner" }
        lock.lock()
        try { pins.set(owner, offset.coerceIn(tailV, headV)) } finally { lock.unlock() }
        writerWaiter?.let { LockSupport.unpark(it) }
    }

    override fun clearPin(owner: Int) {
        require(owner in 0 until PIN_SLOTS) { "pin owner $owner" }
        pins.set(owner, UNSET)
        writerWaiter?.let { LockSupport.unpark(it) }
    }

    override fun openReader(fromOffset: Long, generation: Long): RingReader {
        val aligned = fromOffset - Math.floorMod(fromOffset, TimeshiftConstants.TS_PACKET.toLong())
        lock.lock()
        try {
            if (generation != gen || closed) return RingReaderImpl(this, generation, aligned, -1, fenced = true)
            var id = -1
            for (i in PinOwner.FIRST_READER until PIN_SLOTS) if (readers[i] == null) { id = i; break }
            check(id >= 0) { "too many ring readers" }
            val r = RingReaderImpl(this, generation, aligned, id, fenced = false)
            readers[id] = r
            if (aligned >= tailV) pins.set(id, minOf(aligned, headV))
            return r
        } finally { lock.unlock() }
    }

    override fun reset(): Long {
        val g: Long
        lock.lock()
        try {
            g = ++gen
            if (claimOutstanding && currentBlock >= 0) pool.quarantine(pool.slotIndex(currentBlock))
            claimOutstanding = false
            pool.clear()
            index.clear()
            headV = 0; tailV = 0; flushed = 0; fileValidFrom = 0; flushRequested = 0; unflushedSince = Long.MIN_VALUE
            stopV = null; ended = false; savingV = false; currentBlock = -1; paused = false; growthStop = false; growthHalted = false
            ramOnly = storageBroken; persisting = !ramOnly && modeV == BufferMode.PERSIST; flushEpoch++
            cap = if (storageCapacity >= 2 * BLOCK) storageCapacity else nBlocks * BLOCK
            lastCommitArrival = Long.MIN_VALUE; pendingArrival = Long.MIN_VALUE
            for (i in 0 until PIN_SLOTS) { pins.set(i, UNSET); readers[i] = null }
        } finally { lock.unlock() }
        if (!storageBroken) try { storage.writeHeader(g, cap) } catch (e: IOException) { stopRecording(RecordStop.DISK_ERROR) }
        wakeAll()
        return g
    }

    override fun close() {
        lock.lock()
        try {
            if (closed) return
            closed = true; ended = true; savingV = false
            if (stopV == null) stopV = RecordStop.SESSION_END
        } finally { lock.unlock() }
        flusher?.unregister(this)
        wakeAll()
    }

    // ================================================================ writer internals

    /** The arrival time of the batch the writer is about to scan (PCR_JUMP gaps, §3.5). */
    internal fun beginScan(arrivalMonoMs: Long) { scanGeneration = -1L; pendingArrival = arrivalMonoMs }

    /** (L4) As [beginScan], fenced: until the next call, index sink calls are dropped unless [generation] is current. */
    internal fun beginScan(arrivalMonoMs: Long, generation: Long) {
        scanGeneration = generation
        if (generation == gen) pendingArrival = arrivalMonoMs
    }

    private fun staleScan(): Boolean { val g = scanGeneration; return g >= 0 && g != gen }

    internal val isClosed: Boolean get() = closed

    /** True when some open reader is at the head (§4.4: the reconnect cap applies only while behind). */
    internal fun anyReaderAtHead(): Boolean? {
        // L1: the slots are written under the lock; this runs only before a reconnect attempt, never per packet.
        lock.lock()
        try {
            var any = false
            for (i in PinOwner.FIRST_READER until PIN_SLOTS) {
                val r = readers[i] ?: continue
                any = true
                if (r.atHead) return true
            }
            return if (any) false else null
        } finally { lock.unlock() }
    }

    private fun beginSegment(boundary: Long, reason: SegmentReason, gapMs: Long) {
        if (!index.beginSegment(boundary, reason, gapMs)) {
            // 64 segments in the window: the oldest one goes, if the pins allow it.
            val next = index.boundaryOf((index.oldestSegment + 1) and RingIndexImpl.SEG_MASK)
            lock.lock()
            try {
                val nt = alignTail(next)
                if (nt > tailV && nt <= minPin() && nt <= headV) advanceTail(nt)
            } finally { lock.unlock() }
            if (!index.beginSegment(boundary, reason, gapMs)) return
        }
        segmentListener?.invoke(reason, gapMs)
    }

    private fun addEntry(offset: Long, pts: Long, pcr: Long, flags: Int) {
        if (offset < tailV) return
        if (index.isFull) {
            lock.lock()
            try {
                val second = index.entryOffset(1)
                val nt = if (second >= 0) alignTail(second) else -1L
                if (nt > tailV && nt <= minPin() && nt <= headV) advanceTail(nt) else return
            } finally { lock.unlock() }
        }
        index.add(offset, pts, pcr, flags)
    }

    private inner class Sink : IndexSink, IndexClockSink {
        override fun onRap(offset: Long, pts90k: Long, pcr90k: Long, flags: Int) {
            if (staleScan()) return
            if (flags and RingFlags.ARRIVAL_CLOCK == 0) index.addLeadSample(pts90k - pcr90k)
            addEntry(offset, pts90k, pcr90k, flags or RingFlags.RAP)
        }

        override fun onAnchor(offset: Long, pcr90k: Long, flags: Int) {
            if (staleScan()) return
            val pts = if (flags and RingFlags.ARRIVAL_CLOCK != 0) pcr90k else pcr90k + index.currentLeadMs() * 90
            addEntry(offset, pts, pcr90k, flags)
        }

        override fun onBoundary(offset: Long, reason: SegmentReason) {
            if (staleScan()) return
            // A reconnect with a timestamp jump: the media missing is the time from the old segment's live edge to this
            // batch's arrival. Inline jumps and PSI changes lose nothing.
            val gap = if (reason == SegmentReason.PCR_JUMP && pendingArrival != Long.MIN_VALUE)
                maxOf(0L, pendingArrival - index.newestPtsMono()) else 0L
            beginSegment(offset, reason, gap)
        }

        override fun onPsi(packets: ByteArray, count: Int) { if (!staleScan()) index.setPsi(packets, count) }
        override fun onClock(pcr90k: Long, arrivalMonoMs: Long) { if (!staleScan()) index.onClock(pcr90k, arrivalMonoMs) }
        override fun onPts(pts90k: Long) { if (!staleScan()) index.onPts(pts90k) }
    }

    // ================================================================ reader internals

    internal fun registerWaiter(id: Int, t: Thread?) { if (id >= 0) waiters.set(id, t) }

    internal fun advancePin(id: Int, reader: RingReaderImpl, offset: Long) {
        if (id < 0 || readers[id] !== reader) return
        if (pins.get(id) < offset) {
            pins.set(id, offset)
            writerWaiter?.let { LockSupport.unpark(it) }
        }
    }

    internal fun releaseReader(id: Int, reader: RingReaderImpl) {
        if (id < 0) return
        lock.lock()
        try {
            if (readers[id] === reader) { readers[id] = null; pins.set(id, UNSET); waiters.set(id, null) }
        } finally { lock.unlock() }
        writerWaiter?.let { LockSupport.unpark(it) }
    }

    /** `pread` of logical `[pos, pos + dst.remaining())`, which must not cross a block. */
    @Throws(IOException::class)
    internal fun readFile(dst: ByteBuffer, pos: Long) {
        val filePos = RING_HEADER_BYTES + pos % cap
        while (dst.hasRemaining()) {
            val n = storage.pread(dst, filePos + dst.position())
            if (n < 0) throw IOException("ring file ended at ${filePos + dst.position()}")
        }
    }

    // ================================================================ flusher internals

    /** One flush step on the flusher thread; true when it did work (§4.1). */
    internal fun flushStep(now: Long = clock.nowMs()): Boolean {
        var nlinkDue = false
        val from: Long
        val to: Long
        val block: BlockPool.Block
        val epoch: Long
        val capNow: Long
        lockHot()
        try {
            if (closed) return false
            if (lastNlinkCheck == Long.MIN_VALUE || now - lastNlinkCheck >= NLINK_CHECK_MS) { lastNlinkCheck = now; nlinkDue = !storageBroken }
            if (!persisting || ramOnly) {
                from = -1; to = -1; block = pool.slotFor(0); epoch = 0; capNow = cap
            } else {
                if (headV > flushRequested && unflushedSince != Long.MIN_VALUE && now - unflushedSince >= FLUSH_PARTIAL_MS) {
                    flushRequested = headV; unflushedSince = Long.MIN_VALUE
                }
                if (flushed >= flushRequested) {
                    from = -1; to = -1; block = pool.slotFor(0); epoch = 0; capNow = cap
                } else {
                    from = flushed
                    val b = from / BLOCK
                    to = minOf(flushRequested, (b + 1) * BLOCK)
                    block = pool.slotFor(b)
                    epoch = flushEpoch
                    capNow = cap
                    if (block.blockNo != b) { flushed = to; return true } // cannot happen: recycling waits for the flush
                }
            }
        } finally { lock.unlock() }

        if (nlinkDue && storage.isUnlinked()) {
            stopRecording(RecordStop.STORAGE_EVICTED)
            storage.close() // A-S7: the space is only freed once the fd is closed
            return true
        }
        if (from < 0) return false

        val n = (to - from).toInt()
        val inBlock = (from % BLOCK).toInt()
        val filePos = RING_HEADER_BYTES + from % capNow
        try {
            ensureAllocated(filePos + n - RING_HEADER_BYTES, capNow, now)
            val view = block.flushView
            (view as Buffer).limit(inBlock + n)
            (view as Buffer).position(inBlock)
            var written = 0
            while (view.hasRemaining()) {
                val w = storage.pwrite(view, filePos + written)
                if (w < 0) throw IOException("pwrite returned $w")
                written += w
            }
        } catch (e: IOException) {
            stopRecording(RecordStop.DISK_ERROR)
            return true
        }
        var report = 0L
        lockHot()
        try {
            if (epoch == flushEpoch && persisting && !closed) {
                flushed = to
                persistedBytes += n
                persistedSinceReport += n
                if (persistedSinceReport >= DAILY_COUNTER_FLUSH_BYTES) { report = persistedSinceReport; persistedSinceReport = 0 }
            }
        } finally { lock.unlock() }
        writerWaiter?.let { LockSupport.unpark(it) }
        if (report > 0) persistListener?.invoke(report)
        return true
    }

    /** Extends the file ahead of the head in RING_EXTENT_BLOCKS extents; asks the writer to stop growing on low space (§3.1). */
    private fun ensureAllocated(dataEnd: Long, capNow: Long, now: Long) {
        if (dataEnd <= allocatedData || allocatedData >= capNow) return
        if (growthHalted && now - lastSpaceCheck < FREE_SPACE_CHECK_MS) return
        lastSpaceCheck = now
        var stop = storage.availableBytes() < FREE_SPACE_RESERVE_MIN / 2
        if (!stop) {
            try {
                val target = minOf(capNow, maxOf(dataEnd, allocatedData + RING_EXTENT_BLOCKS * BLOCK))
                allocatedData = storage.allocate(RING_HEADER_BYTES + target) - RING_HEADER_BYTES
            } catch (e: IOException) { stop = true }
        }
        growthHalted = stop
        if (stop) {
            lock.lock()
            try { growthStop = true } finally { lock.unlock() }
        }
    }

    // ================================================================ helpers (lock held)

    private fun fill(slot: RingSlot, b: Long, h: Long) {
        val blk = pool.slotFor(b)
        val inBlock = (h % BLOCK).toInt()
        slot.array = blk.array
        slot.offset = blk.arrayOffset + inBlock
        slot.length = RING_BLOCK_BYTES - inBlock
    }

    /** PAUSE_ONLY: persist only while paused or while a pin nears the RAM window's end (§4.5). */
    private fun updatePersistence(b: Long) {
        if (ramOnly) { persisting = false; return }
        val mp = minPin()
        val nearEdge = mp != Long.MAX_VALUE && mp < (b - nBlocks + 1 + nBlocks / 2) * BLOCK
        val want = modeV == BufferMode.PERSIST || paused || nearEdge
        if (want && !persisting) {
            val p = (tailV / BLOCK) * BLOCK
            if (!(flushed >= p && fileValidFrom <= p)) { flushed = p; fileValidFrom = p }
            flushRequested = maxOf(flushRequested, (headV / BLOCK) * BLOCK)
            persisting = true; flushEpoch++
        } else if (!want && persisting) {
            persisting = false; flushEpoch++
        }
    }

    /** Free space ran low before the first wrap: the ring wraps at the current head (§3.1). */
    private fun maybeShrink(b: Long) {
        if (!growthStop) return
        val h = b * BLOCK
        if (h < 2 * BLOCK) return
        if (h < cap) cap = h
        growthStop = false
    }

    /** Where `tail` lands for [bound]: the first RAP at or after it, else the first entry, else the bound (A-N8). */
    private fun alignTail(bound: Long): Long {
        if (bound <= tailV) return tailV
        val rap = index.firstRapAtOrAfter(bound)
        if (rap >= 0) return rap
        val e = index.firstEntryAtOrAfter(bound)
        return if (e >= 0) e else bound
    }

    /**
     * §3.2 time limit (R5), lock held, writer thread (the only index writer, so slot then offset is safe here): `tail`
     * moves to the first RAP at or after newest − [lengthMs], when that is ahead of `tail` and not past a pin.
     */
    private fun applyTimeLimit() {
        val newest = index.newestPtsMono()
        val slot = index.ceilRap(newest - lengthMs)
        if (slot < 0) return
        val t = index.offsetAt(slot)
        if (t > tailV && t <= minPin() && t <= headV) advanceTail(t)
    }

    private fun advanceTail(newTail: Long) {
        tailV = newTail
        index.dropBefore(newTail)
    }

    private fun minPin(): Long {
        var m = Long.MAX_VALUE
        for (i in 0 until PIN_SLOTS) { val v = pins.get(i); if (v != UNSET && v < m) m = v }
        return m
    }

    /**
     * `lock()` after a short `tryLock` spin: the pump and the flusher hold the lock for a few hundred nanoseconds, and a
     * queued AQS acquire allocates a node, which the steady-state budget of §15 does not allow.
     */
    private fun lockHot() {
        var i = 0
        while (i < LOCK_SPINS) { if (lock.tryLock()) return; i++ }
        lock.lock()
    }

    private fun markBroken() { storageBroken = true; ramOnly = true; persisting = false }

    private fun wakeReaders() {
        for (i in 0 until PIN_SLOTS) { val t = waiters.get(i); if (t != null) LockSupport.unpark(t) }
    }

    private fun wakeAll() {
        wakeReaders()
        writerWaiter?.let { LockSupport.unpark(it) }
    }

    // ================================================================ test hooks

    internal fun pinOf(owner: Int): Long = pins.get(owner)
    internal fun minPinOrMax(): Long = minPin()

    companion object {
        internal const val BLOCK = RING_BLOCK_BYTES.toLong()
        internal const val PIN_SLOTS = 32
        internal const val UNSET = -1L
        private const val WAIT_SLICE_NS = 50_000_000L
        private const val POOL_SLICE_NS = 5_000_000L
        private const val LOCK_SPINS = 200

        internal fun isDiskStop(r: RecordStop): Boolean =
            r == RecordStop.DISK_ERROR || r == RecordStop.STORAGE_EVICTED || r == RecordStop.DISK_TOO_SLOW

        /** The [TsRingFactory] WP-B's Hilt module binds. */
        val FACTORY = TsRingFactory { storage, clock, ramBlocks, indexEntries, lengthMs -> TsRingImpl(storage, clock, ramBlocks, indexEntries, lengthMs) }
    }
}
