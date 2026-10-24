package app.thebox.core.playback.timeshift

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Zap-1h investigation (2026-10-24): on the emulator a 1-h ring looked ≈ 0.9 s slower to first frame than a 30-min one.
 * The cause was the fake panel (each channel resumes where its last connection stopped, so whether the first IDR falls
 * inside the 1-s connect burst is set by the block's history, not by the ring). This test pins the ring half of that
 * finding: from a zap's reset to the first readable RAP, the ring does the same work and exposes the same bytes whatever
 * the length (index capacity `ceil(lengthSec × 2.5) + 256`, time limit, file capacity). If a length-proportional step
 * ever lands on the tune path (an index fill, an eager extent, a scan), the counts below diverge.
 */
class ZapLengthIndependenceTest {
    private data class Tune(val rapOffset: Long, val headAtRap: Long, val readableAtRap: Long, val writes: Long, val allocated: Long, val indexSize: Int)

    private fun zap(lengthMin: Int, capBlocks: Int, skipFrames: Int): List<Tune> {
        val clock = FakeMonoClock()
        val storage = MemoryRingStorage(capBlocks * RingTestKit.BLOCK)
        val ring = TsRingImpl(storage, clock, 64, RingIndexImpl.capacityFor(lengthMin * 60_000L), null, lengthMin * 60_000L)
        val out = ArrayList<Tune>()
        repeat(3) { // the first tune builds the ring; the next two reuse it (logical reset, §8.3)
            ring.reset()
            val ts = SyntheticTs(gopFrames = 50, bytesPerSec = 300_000)
            // The connection starts mid-GOP: the panel resumes where the channel's last connection stopped.
            val scratch = ByteArray(64 * 1024)
            var skip = (ts.actualBytesPerSec * skipFrames / ts.fps).toLong() / 188 * 188
            while (skip > 0) skip -= ts.fill(scratch, 0, minOf(scratch.size.toLong(), skip).toInt())
            val writes0 = storage.writes.get()
            val feeder = TsFeeder(ring, ts, clock)
            val e = LongArray(2)
            feeder.paced = false
            var guard = 0
            while (ring.index.oldestRapEntry(e) < 0 && guard++ < 10_000) feeder.feed(188L * 7)
            val head = ring.head
            out += Tune(e[0], head, head - e[0], storage.writes.get() - writes0, storage.allocated, ring.index.size)
        }
        return out
    }

    @Test
    fun theFirstReadableRapAndTheWorkBeforeItDoNotDependOnTheRingLength() {
        for (skip in listOf(0, 13, 37)) {
            val half = zap(lengthMin = 30, capBlocks = 64, skipFrames = skip)
            val hour = zap(lengthMin = 60, capBlocks = 128, skipFrames = skip)
            val twoHours = zap(lengthMin = 120, capBlocks = 256, skipFrames = skip)
            for (i in half.indices) {
                // The file is never extended before the first RAP is readable (prepare allocates the first extent, §3.1).
                assertEquals(half[i].copy(allocated = 0), hour[i].copy(allocated = 0), "skip $skip tune $i: 30 min vs 1 h")
                assertEquals(half[i].copy(allocated = 0), twoHours[i].copy(allocated = 0), "skip $skip tune $i: 30 min vs 2 h")
                assertTrue(half[i].indexSize in 1..2, "skip $skip tune $i: one RAP (and at most one anchor) before the first read: ${half[i]}")
            }
            // A mid-GOP start waits for the next IDR: the RAP sits after the skipped part of the GOP, never at the start.
            if (skip > 0) assertTrue(half[0].rapOffset > 0, "skip $skip: ${half[0]}")
        }
    }
}
