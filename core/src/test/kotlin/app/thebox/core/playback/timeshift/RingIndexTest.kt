package app.thebox.core.playback.timeshift

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §17.1 `RingIndexTest`: floor/ceil over wrapped circular arrays; spacing; 28 B/entry; seqlock under concurrent eviction. */
class RingIndexTest {
    /** Arrival-clock entries: mono = pcr / 90, so the test controls mono exactly. */
    private fun RingIndexImpl.addAt(offset: Long, mono: Long, rap: Boolean) {
        add(offset, mono * 90, mono * 90, RingFlags.ARRIVAL_CLOCK or (if (rap) RingFlags.RAP else 0))
    }

    @Test
    fun floorAndCeilOverAWrappedCircularIndex() {
        val ix = RingIndexImpl(32)
        // 100 entries through a 32-slot array: offsets 188·k, mono 1000·k, a RAP every third entry.
        for (k in 0 until 100) {
            if (ix.size == ix.capacity) ix.dropBefore(ix.entryOffset(1))
            ix.addAt(188L * k, 1000L * k, k % 3 == 0)
        }
        assertEquals(32, ix.size)
        val oldestK = 100 - 32
        for (target in (oldestK * 1000L - 500)..(100_000L) step 137) {
            val f = ix.floorRap(target)
            val expectF = (oldestK until 100).filter { it % 3 == 0 && 1000L * it <= target }.maxOrNull()
            if (expectF == null) assertEquals(-1, f, "floor($target)") else assertEquals(1000L * expectF, ix.ptsMonoAt(f), "floor($target)")
            val c = ix.ceilRap(target)
            val expectC = (oldestK until 100).filter { it % 3 == 0 && 1000L * it >= target }.minOrNull()
            if (expectC == null) assertEquals(-1, c, "ceil($target)") else assertEquals(1000L * expectC, ix.ptsMonoAt(c), "ceil($target)")
        }
        assertEquals(1000L * 99, ix.ptsMonoAt(ix.newestRap()))
        assertEquals(1000L * 69, ix.ptsMonoAt(ix.oldestRap()))
        assertEquals(RingFlags.RAP, ix.flagsAt(ix.oldestRap()) and RingFlags.RAP)
        assertEquals(188L * 69, ix.offsetAt(ix.oldestRap()))
        // monoOfOffset: the entry at or before the offset.
        assertEquals(1000L * 80, ix.monoOfOffset(188L * 80 + 100))
    }

    @Test
    fun emptyIndexAnswersMinusOne() {
        val ix = RingIndexImpl()
        assertEquals(-1, ix.floorRap(0)); assertEquals(-1, ix.ceilRap(0))
        assertEquals(-1, ix.newestRap()); assertEquals(-1, ix.oldestRap())
        assertEquals(0, ix.size)
        assertEquals(SegmentReason.FIRST, ix.segmentReason(0))
    }

    @Test
    fun memoryIs28BytesPerEntry() {
        assertEquals(28, RingIndexImpl.BYTES_PER_ENTRY)
        val twoHours = RingIndexImpl.capacityFor(2 * 3_600_000L)
        assertEquals(18_256, twoHours)
        assertTrue(twoHours * 28L <= 511_200L, "2 h index ≤ 511 KB")
        assertEquals(4_756, RingIndexImpl.capacityFor(30 * 60_000L))
        val ix = RingIndexImpl(twoHours)
        // Entries 28 B each (≤ 0.51 MB for 2 h); the segment table (64 × 8 PSI packets = 96 KB, plus the lead samples)
        // is ≈ 0.11 MB, the design's "segments ≤ 0.1".
        assertTrue(ix.heapBytes - twoHours * 28L <= 112_000L, "segment table ${ix.heapBytes - twoHours * 28L}")
    }

    @Test
    fun segmentsAreDroppedWithTheTail() {
        val ix = RingIndexImpl(64)
        ix.addAt(0, 0, true)
        assertTrue(ix.beginSegment(1880, SegmentReason.INLINE_PCR_JUMP, 0))
        ix.addAt(1880, 10_000, true)
        assertTrue(ix.beginSegment(3760, SegmentReason.GAP, 4_000))
        ix.addAt(3760, 20_000, true)
        assertEquals(3, ix.liveSegments)
        assertEquals(SegmentReason.GAP, ix.segmentReason(ix.segmentAt(ix.newestRap())))
        assertEquals(4_000L, ix.segmentGapMs(2))
        assertEquals(1880L, ix.nextBoundary(0))
        assertEquals(Long.MAX_VALUE, ix.nextBoundary(2))
        assertEquals(1880L, ix.firstRapOffset(1))
        assertEquals(1, ix.segmentOf(2000))
        ix.dropBefore(1880)
        assertEquals(2, ix.liveSegments)
        assertEquals(1, ix.oldestSegment)
        // A second boundary at the same head with no byte since reuses the empty segment.
        assertTrue(ix.beginSegment(5640, SegmentReason.WRITER_RESTART, 0))
        assertTrue(ix.beginSegment(5640, SegmentReason.GAP, 1_234))
        assertEquals(3, ix.liveSegments)
        assertEquals(SegmentReason.GAP, ix.segmentReason(3))
        // The table holds MAX_SEGMENTS at most.
        var off = 6000L
        var ok = true
        while (ok) { off += 188; ix.addAt(off, off, true); ok = ix.beginSegment(off + 188, SegmentReason.PSI_CHANGE, 0) }
        assertEquals(TimeshiftConstants.MAX_SEGMENTS, ix.liveSegments)
    }

    @Test
    fun lookupsStayConsistentUnderConcurrentEviction() {
        val ix = RingIndexImpl(1024)
        val stop = AtomicBoolean(false)
        val newest = AtomicLong(0)
        val error = AtomicReference<Throwable?>(null)
        val snapshots = AtomicLong()
        val started = CountDownLatch(1)
        val writer = Thread {
            var k = 0L
            started.countDown()
            while (!stop.get()) {
                if (ix.size >= 1000) ix.dropBefore(ix.entryOffset(16))
                ix.addAt(188 * k, 10 * k, k % 2 == 0L)
                newest.set(10 * k)
                k++
                // ~20 µs per entry: the 1,000-entry window lives ≥ 20 ms, far longer than one lookup.
                val until = System.nanoTime() + 20_000
                while (System.nanoTime() < until) { /* spin: keep the writer hot against the readers */ }
            }
        }
        writer.start()
        started.await()
        val readers = (0 until 3).map { seed ->
            Thread {
                val rnd = Random(seed)
                val out = LongArray(2)
                try {
                    repeat(200_000) {
                        val n = newest.get()
                        // Recent half of the window: those slots are not evicted while we look at them.
                        val target = n - rnd.nextLong(0, 4_000)
                        // One seqlock section: offset and mono of the same entry, never torn.
                        val f = ix.floorRapSnapshot(target, out)
                        if (f >= 0) {
                            check(out[1] <= target) { "floor($target) gave ${out[1]}" }
                            check(target - out[1] <= 20) { "floor($target) gave ${out[1]}, too far" }
                            check(out[0] == out[1] / 10 * 188) { "torn entry: offset ${out[0]} mono ${out[1]}" }
                        }
                        // Separate view calls: the slot may have been reused a lap later, never torn or in between.
                        val g = ix.floorRap(target)
                        if (g >= 0) {
                            val m = ix.ptsMonoAt(g)
                            check(m <= target || m - target >= 5_000) { "floor($target) gave $m" }
                        }
                        val c = ix.ceilRap(target)
                        if (c >= 0) check(ix.ptsMonoAt(c) >= target) { "ceil($target)" }
                        snapshots.incrementAndGet()
                    }
                } catch (t: Throwable) { error.compareAndSet(null, t) }
            }
        }
        readers.forEach { it.start() }
        readers.forEach { it.join() }
        stop.set(true)
        writer.join()
        error.get()?.let { throw it }
        assertEquals(600_000L, snapshots.get())
    }
}
