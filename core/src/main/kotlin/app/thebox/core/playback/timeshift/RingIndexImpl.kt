// WP-A (docs/design/m6-timeshift.md §3.3, §3.5, A-S4, A-S9, A-N8).
package app.thebox.core.playback.timeshift

import app.thebox.core.playback.timeshift.TimeshiftConstants.INDEX_ENTRIES_PER_SEC
import app.thebox.core.playback.timeshift.TimeshiftConstants.INDEX_READ_RETRIES
import app.thebox.core.playback.timeshift.TimeshiftConstants.INDEX_SLACK_ENTRIES
import app.thebox.core.playback.timeshift.TimeshiftConstants.MAX_SEGMENTS
import app.thebox.core.playback.timeshift.TimeshiftConstants.PSI_MAX_PACKETS
import app.thebox.core.playback.timeshift.TimeshiftConstants.TS_PACKET
import java.util.concurrent.locks.StampedLock

/**
 * RAP index and segment table (§3.3, §3.5), in RAM only.
 *
 * Entries are a struct of arrays in a preallocated circular buffer, 28 bytes each (offset, pts, pcr: `Long`; flags and
 * segment: `Int`). A slot is a physical index into those arrays; it stays valid until the entry is evicted.
 *
 * **Threads.** Every mutation runs on the writer thread (or on the IO thread in `reset`, after the writer exited) inside
 * the [StampedLock]'s write lock, which is the seqlock of §3.3 (odd while writing). Lookups run on any thread: an
 * optimistic read, retried [INDEX_READ_RETRIES] times, then the read lock ("falls back to the ring lock"). Lookups
 * allocate nothing.
 *
 * **Time (A-S4).** Per segment, `monoOffset = min(arrivalMono − pcr/90)` over the segment's PCR samples, and
 * `ptsPcrLead` = the median of `pts − pcr` over its RAPs. An entry's mono is `pts/90 − lead + monoOffset`, clamped to at
 * least the previous segment's live edge (`floorMono`), so the mono sequence never runs backwards across a boundary.
 * Arrival-clock segments (no PCR) use the arrival time kept in `pcr`.
 */
class RingIndexImpl internal constructor(capacity: Int) : RingIndexView {
    /** An index with the slack capacity only (tests and the commit-0 shape). */
    constructor() : this(INDEX_SLACK_ENTRIES)

    private val lock = StampedLock()
    val capacity: Int = capacity.coerceAtLeast(16)

    // ---------------------------------------------------------------- entries (circular)
    private val offs = LongArray(this.capacity)
    private val ptsArr = LongArray(this.capacity)
    private val pcrArr = LongArray(this.capacity)
    private val flg = IntArray(this.capacity)
    private var first = 0
    private var count = 0

    // ---------------------------------------------------------------- segments (circular by id % MAX_SEGMENTS)
    private val segBoundary = LongArray(MAX_SEGMENTS)
    private val segReason = IntArray(MAX_SEGMENTS)
    private val segGap = LongArray(MAX_SEGMENTS)
    private val segGapStart = LongArray(MAX_SEGMENTS)
    private val segMonoOffset = LongArray(MAX_SEGMENTS)
    private val segLead = LongArray(MAX_SEGMENTS)
    private val segFloor = LongArray(MAX_SEGMENTS)
    private val segArrival = BooleanArray(MAX_SEGMENTS)
    private val segNewestPts = LongArray(MAX_SEGMENTS)
    private val segNewestArrival = LongArray(MAX_SEGMENTS)
    private val segHasEntry = BooleanArray(MAX_SEGMENTS)
    private val segPsi = ByteArray(MAX_SEGMENTS * PSI_MAX_PACKETS * TS_PACKET)
    private val segPsiCount = IntArray(MAX_SEGMENTS)
    private val segLeadSamples = LongArray(MAX_SEGMENTS * LEAD_SAMPLES)
    private val segLeadCount = IntArray(MAX_SEGMENTS)
    private var segOldest = 0
    private var segNewest = 0
    private var segLive = 0
    private val scratch = LongArray(LEAD_SAMPLES)

    init { clear() }

    // ================================================================ RingIndexView (any thread)

    override val size: Int get() = readInt { count }

    override fun floorRap(monoMs: Long): Int = readInt {
        var lo = 0; var hi = count - 1; var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (mono(phys(mid)) <= monoMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        var i = found; var r = -1
        while (i >= 0) { val p = phys(i); if (isRap(p)) { r = p; break }; i-- }
        r
    }

    override fun ceilRap(monoMs: Long): Int = readInt { ceilRapPhys(monoMs) }

    override fun newestRap(): Int = readInt { newestRapPhys() }

    override fun oldestRap(): Int = readInt { oldestRapPhys() }

    // Unlocked bodies (call inside a read section).
    private fun ceilRapPhys(monoMs: Long): Int {
        var lo = 0; var hi = count - 1; var found = count
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (mono(phys(mid)) >= monoMs) { found = mid; hi = mid - 1 } else lo = mid + 1
        }
        var i = found; var r = -1
        while (i < count) { val p = phys(i); if (isRap(p)) { r = p; break }; i++ }
        return r
    }

    private fun newestRapPhys(): Int {
        var i = count - 1; var r = -1
        while (i >= 0) { val p = phys(i); if (isRap(p)) { r = p; break }; i-- }
        return r
    }

    private fun oldestRapPhys(): Int {
        var i = 0; var r = -1
        while (i < count) { val p = phys(i); if (isRap(p)) { r = p; break }; i++ }
        return r
    }

    override fun offsetAt(slot: Int): Long = if (slot !in 0 until capacity) -1L else readLong { offs[slot] }
    override fun ptsMonoAt(slot: Int): Long = if (slot !in 0 until capacity) 0L else readLong { mono(slot) }
    override fun flagsAt(slot: Int): Int = if (slot !in 0 until capacity) 0 else readInt { flg[slot] ushr 24 }
    override fun segmentAt(slot: Int): Int = if (slot !in 0 until capacity) -1 else readInt { flg[slot] and SEG_MASK }

    override fun newestPtsMono(): Long = readLong { segEdgeMono(segNewest) }

    override fun monoOfOffset(offset: Long): Long = readLong {
        var lo = 0; var hi = count - 1; var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (offs[phys(mid)] <= offset) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        when {
            found >= 0 -> mono(phys(found))
            count > 0 -> mono(phys(0))
            else -> 0L
        }
    }

    override fun segmentReason(segment: Int): SegmentReason =
        REASONS[readInt { if (isLiveSeg(segment)) segReason[segment % MAX_SEGMENTS] else 0 }]

    override fun segmentGapMs(segment: Int): Long = readLong { if (isLiveSeg(segment)) segGap[segment % MAX_SEGMENTS] else 0L }

    // ================================================================ ring-internal reads (any thread)

    /** The newest segment's id. */
    internal val currentSegment: Int get() = readInt { segNewest }

    /** The live segment holding [offset] (the last one whose boundary is ≤ it). */
    internal fun segmentOf(offset: Long): Int = readInt {
        var s = segNewest; var n = segLive
        while (n > 1 && segBoundary[s % MAX_SEGMENTS] > offset) { s = prevSeg(s); n-- }
        s
    }

    /** Boundary of the segment after [segment], or `Long.MAX_VALUE` while [segment] is the newest. */
    internal fun nextBoundary(segment: Int): Long = readLong {
        if (segment == segNewest || !isLiveSeg(segment)) Long.MAX_VALUE else segBoundary[nextSeg(segment) % MAX_SEGMENTS]
    }

    internal fun boundaryOf(segment: Int): Long = readLong { segBoundary[segment % MAX_SEGMENTS] }
    internal fun gapStartMono(segment: Int): Long = readLong { segGapStart[segment % MAX_SEGMENTS] }

    /** Offset of [segment]'s first RAP; −1 while it has none (the segment is readable from there, §3.4). */
    internal fun firstRapOffset(segment: Int): Long = readLong {
        var r = -1L
        if (isLiveSeg(segment)) {
            val b = segBoundary[segment % MAX_SEGMENTS]
            var lo = 0; var hi = count - 1; var found = count
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (offs[phys(mid)] >= b) { found = mid; hi = mid - 1 } else lo = mid + 1
            }
            var i = found
            while (i < count) {
                val p = phys(i)
                if ((flg[p] and SEG_MASK) != segment) break
                if (isRap(p)) { r = offs[p]; break }
                i++
            }
        }
        r
    }

    /**
     * [floorRap] with the entry's offset and mono read in the same seqlock section ([out] = offset, mono): a slot can be
     * reused by a newer entry between two separate view calls, a snapshot cannot. −1 when there is none.
     */
    override fun floorRapEntry(monoMs: Long, out: LongArray): Int = floorRapSnapshot(monoMs, out)

    override fun ceilRapEntry(monoMs: Long, out: LongArray): Int = readInt {
        val r = ceilRapPhys(monoMs)
        if (r >= 0) { out[0] = offs[r]; out[1] = mono(r) }
        r
    }

    override fun newestRapEntry(out: LongArray): Int = readInt {
        val r = newestRapPhys()
        if (r >= 0) { out[0] = offs[r]; out[1] = mono(r) }
        r
    }

    override fun oldestRapEntry(out: LongArray): Int = readInt {
        val r = oldestRapPhys()
        if (r >= 0) { out[0] = offs[r]; out[1] = mono(r) }
        r
    }

    internal fun floorRapSnapshot(monoMs: Long, out: LongArray): Int = readInt {
        var lo = 0; var hi = count - 1; var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (mono(phys(mid)) <= monoMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        var i = found; var r = -1
        while (i >= 0) { val p = phys(i); if (isRap(p)) { r = p; break }; i-- }
        if (r >= 0) { out[0] = offs[r]; out[1] = mono(r) }
        r
    }

    /** First RAP entry at or after [offset] (any segment), else −1. Used for `tail` (A-N8). */
    internal fun firstRapAtOrAfter(offset: Long): Long = readLong {
        var i = lowerBound(offset); var r = -1L
        while (i < count) { val p = phys(i); if (isRap(p)) { r = offs[p]; break }; i++ }
        r
    }

    /** First entry offset at or after [offset], else −1. */
    internal fun firstEntryAtOrAfter(offset: Long): Long = readLong {
        val i = lowerBound(offset)
        if (i < count) offs[phys(i)] else -1L
    }

    /** Offset of the [i]-th oldest entry (0 = oldest), −1 if none. */
    internal fun entryOffset(i: Int): Long = readLong { if (i in 0 until count) offs[phys(i)] else -1L }

    internal val isFull: Boolean get() = readInt { if (count == capacity) 1 else 0 } == 1
    internal val liveSegments: Int get() = readInt { segLive }
    internal val oldestSegment: Int get() = readInt { segOldest }

    /** Copies [segment]'s PSI packets into [dst]; returns the packet count (§5.1 PSI injection). */
    internal fun copyPsi(segment: Int, dst: ByteArray): Int {
        while (true) {
            val st = lock.tryOptimisticRead()
            var n = 0
            if (isLiveSeg(segment)) {
                val ti = segment % MAX_SEGMENTS
                n = segPsiCount[ti].coerceIn(0, PSI_MAX_PACKETS)
                System.arraycopy(segPsi, ti * PSI_BYTES, dst, 0, n * TS_PACKET)
            }
            if (st != 0L && lock.validate(st)) return n
            val rs = lock.readLock()
            try {
                if (!isLiveSeg(segment)) return 0
                val ti = segment % MAX_SEGMENTS
                val c = segPsiCount[ti].coerceIn(0, PSI_MAX_PACKETS)
                System.arraycopy(segPsi, ti * PSI_BYTES, dst, 0, c * TS_PACKET)
                return c
            } finally { lock.unlockRead(rs) }
        }
    }

    /** Arrays held by the index and the segment table (§15: 28 B per entry). */
    internal val heapBytes: Long
        get() = capacity.toLong() * BYTES_PER_ENTRY + MAX_SEGMENTS * (11L * 8 + 3 * 4 + 2) +
            segPsi.size + segLeadSamples.size * 8L

    // ================================================================ mutations (writer thread)

    internal fun clear() = write {
        first = 0; count = 0
        segOldest = 0; segNewest = 0; segLive = 1
        initSeg(0, 0L, SegmentReason.FIRST, 0L, 0L, Long.MIN_VALUE)
    }

    /**
     * Starts a segment at [boundary]. An empty newest segment (no bytes since its boundary) is reused, so a writer that
     * restarts twice before data never leaves a zero-length segment. Returns false when the table is full (the caller
     * must evict the oldest segment first).
     */
    internal fun beginSegment(boundary: Long, reason: SegmentReason, gapMs: Long): Boolean {
        val gapStart = newestPtsMono()
        var ok = true
        write {
            val cur = segNewest % MAX_SEGMENTS
            if (segBoundary[cur] >= boundary && !segHasEntry[cur]) {
                // No byte since the newest boundary: reuse that segment with the new reason.
                segReason[cur] = reason.ordinal; segGap[cur] = gapMs; segBoundary[cur] = boundary
            } else if (segLive >= MAX_SEGMENTS) {
                ok = false
            } else {
                val floor = segEdgeMono(segNewest)
                segNewest = nextSeg(segNewest); segLive++
                initSeg(segNewest, boundary, reason, gapMs, gapStart, floor)
            }
        }
        return ok
    }

    /** Appends an entry (the writer guarantees non-decreasing offsets). False when the index is full. */
    internal fun add(offset: Long, pts: Long, pcr: Long, flags: Int): Boolean {
        if (count == capacity) return false
        write {
            val seg = segNewest
            val ti = seg % MAX_SEGMENTS
            var f = flags
            if (!segHasEntry[ti]) { f = f or RingFlags.SEGMENT_START; segHasEntry[ti] = true }
            segArrival[ti] = f and RingFlags.ARRIVAL_CLOCK != 0
            val p = (first + count) % capacity
            offs[p] = offset; ptsArr[p] = pts; pcrArr[p] = pcr; flg[p] = (f shl 24) or (seg and SEG_MASK)
            count++
        }
        return true
    }

    /** One PCR sample of the newest segment (§3.3 Time). */
    internal fun onClock(pcr90k: Long, arrivalMonoMs: Long) {
        val ti = segNewest % MAX_SEGMENTS
        val v = arrivalMonoMs - pcr90k / 90
        if (v < segMonoOffset[ti]) write { segMonoOffset[ti] = v }
    }

    /** Newest PTS of the newest segment (the live edge, A-S4). */
    internal fun onPts(pts90k: Long) {
        val ti = segNewest % MAX_SEGMENTS
        if (pts90k > segNewestPts[ti] || segNewestPts[ti] == Long.MIN_VALUE) write { segNewestPts[ti] = pts90k }
    }

    /** Newest arrival of an arrival-clock segment. */
    internal fun onArrival(arrivalMonoMs: Long) {
        val ti = segNewest % MAX_SEGMENTS
        if (segArrival[ti]) write { segNewestArrival[ti] = arrivalMonoMs }
    }

    /** Adds a `pts − pcr` sample (90 kHz) of a RAP; the lead is their median. */
    internal fun addLeadSample(lead90k: Long) {
        val ti = segNewest % MAX_SEGMENTS
        val n = segLeadCount[ti]
        val base = ti * LEAD_SAMPLES
        segLeadSamples[base + (n % LEAD_SAMPLES)] = lead90k
        val m = minOf(n + 1, LEAD_SAMPLES)
        System.arraycopy(segLeadSamples, base, scratch, 0, m)
        for (i in 1 until m) { val v = scratch[i]; var j = i - 1; while (j >= 0 && scratch[j] > v) { scratch[j + 1] = scratch[j]; j-- }; scratch[j + 1] = v }
        val median = scratch[m / 2] / 90
        write { segLeadCount[ti] = n + 1; segLead[ti] = median }
    }

    /** The newest segment's current lead in ms (anchors interpolate their PTS with it). */
    internal fun currentLeadMs(): Long = segLead[segNewest % MAX_SEGMENTS]

    internal fun setPsi(packets: ByteArray, packetCount: Int) {
        val n = packetCount.coerceIn(0, PSI_MAX_PACKETS)
        val ti = segNewest % MAX_SEGMENTS
        write {
            System.arraycopy(packets, 0, segPsi, ti * PSI_BYTES, n * TS_PACKET)
            segPsiCount[ti] = n
        }
    }

    /** Drops entries before [tail] and segments that end at or before it (a pointer move). */
    internal fun dropBefore(tail: Long) = write {
        while (count > 0 && offs[first] < tail) { first = (first + 1) % capacity; count-- }
        while (segLive > 1 && segBoundary[nextSeg(segOldest) % MAX_SEGMENTS] <= tail) { segOldest = nextSeg(segOldest); segLive-- }
    }

    // ================================================================ internals

    private fun initSeg(id: Int, boundary: Long, reason: SegmentReason, gapMs: Long, gapStart: Long, floor: Long) {
        val ti = id % MAX_SEGMENTS
        segBoundary[ti] = boundary; segReason[ti] = reason.ordinal; segGap[ti] = gapMs; segGapStart[ti] = gapStart
        segMonoOffset[ti] = UNKNOWN_OFFSET; segLead[ti] = 0L; segFloor[ti] = floor; segArrival[ti] = false
        segNewestPts[ti] = Long.MIN_VALUE; segNewestArrival[ti] = Long.MIN_VALUE; segHasEntry[ti] = false
        segPsiCount[ti] = 0; segLeadCount[ti] = 0
    }

    private fun phys(i: Int): Int = (first + i) % capacity
    private fun isRap(p: Int): Boolean = (flg[p] ushr 24) and RingFlags.RAP != 0
    private fun nextSeg(s: Int): Int = (s + 1) and SEG_MASK
    private fun prevSeg(s: Int): Int = (s - 1) and SEG_MASK
    private fun isLiveSeg(s: Int): Boolean = s >= 0 && ((segNewest - s) and SEG_MASK) < segLive

    private fun lowerBound(offset: Long): Int {
        var lo = 0; var hi = count - 1; var found = count
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (offs[phys(mid)] >= offset) { found = mid; hi = mid - 1 } else lo = mid + 1
        }
        return found
    }

    private fun mono(p: Int): Long {
        val f = flg[p]
        val ti = (f and SEG_MASK) % MAX_SEGMENTS
        val floor = segFloor[ti]
        val m = when {
            (f ushr 24) and RingFlags.ARRIVAL_CLOCK != 0 -> pcrArr[p] / 90
            segMonoOffset[ti] == UNKNOWN_OFFSET -> floor
            else -> ptsArr[p] / 90 - segLead[ti] + segMonoOffset[ti]
        }
        return if (m < floor) floor else m
    }

    /** Live edge of segment [s]: the mono of its newest PTS (or arrival), never below its floor. */
    private fun segEdgeMono(s: Int): Long {
        val ti = s % MAX_SEGMENTS
        val floor = segFloor[ti]
        val m = when {
            segArrival[ti] -> if (segNewestArrival[ti] == Long.MIN_VALUE) floor else segNewestArrival[ti]
            segNewestPts[ti] == Long.MIN_VALUE || segMonoOffset[ti] == UNKNOWN_OFFSET -> floor
            else -> segNewestPts[ti] / 90 - segLead[ti] + segMonoOffset[ti]
        }
        val r = if (m < floor) floor else m
        return if (r == Long.MIN_VALUE) 0L else r
    }

    private inline fun write(block: () -> Unit) {
        val st = lock.writeLock()
        try { block() } finally { lock.unlockWrite(st) }
    }

    private inline fun readLong(block: () -> Long): Long {
        repeat(INDEX_READ_RETRIES) {
            val st = lock.tryOptimisticRead()
            if (st != 0L) {
                val v = try { block() } catch (_: RuntimeException) { if (lock.validate(st)) throw IllegalStateException("index read failed"); 0L }
                if (lock.validate(st)) return v
            }
        }
        val rs = lock.readLock()
        try { return block() } finally { lock.unlockRead(rs) }
    }

    private inline fun readInt(block: () -> Int): Int {
        repeat(INDEX_READ_RETRIES) {
            val st = lock.tryOptimisticRead()
            if (st != 0L) {
                val v = try { block() } catch (_: RuntimeException) { if (lock.validate(st)) throw IllegalStateException("index read failed"); 0 }
                if (lock.validate(st)) return v
            }
        }
        val rs = lock.readLock()
        try { return block() } finally { lock.unlockRead(rs) }
    }

    companion object {
        /** offset + pts + pcr (8 each) + flags/segment (4) (§3.3). */
        const val BYTES_PER_ENTRY = 28
        internal const val SEG_MASK = 0xFFFFFF
        internal const val LEAD_SAMPLES = 16
        private const val PSI_BYTES = PSI_MAX_PACKETS * TS_PACKET
        private const val UNKNOWN_OFFSET = Long.MAX_VALUE
        private val REASONS = SegmentReason.entries.toTypedArray()

        /** Index capacity for a window of [lengthMs]: `ceil(lengthSec × 2.5) + 256` (§3.3). */
        fun capacityFor(lengthMs: Long): Int =
            Math.ceil(lengthMs / 1000.0 * INDEX_ENTRIES_PER_SEC).toInt() + INDEX_SLACK_ENTRIES
    }
}
