package app.thebox.core.playback.timeshift

import app.thebox.core.playback.timeshift.RingTestKit.BLOCK
import app.thebox.core.playback.timeshift.RingTestKit.append
import app.thebox.core.playback.timeshift.RingTestKit.newSlot
import app.thebox.core.playback.timeshift.RingTestKit.readAndCheck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §17.1 `RingPoolTest` (A-B2): blocks are recycled once flushed, whatever the readers do; a reader that needs bytes no
 * longer in RAM reads them from the file; a block recycled during a copy fails the stamp and is re-read from the file.
 */
class RingPoolTest {
    /** 60 s at 1 MB/s ≈ 917 blocks. */
    private val sixtySeconds = 60L * 1_000_000 / BLOCK + 1

    @Test
    fun aParkedReaderNeverHoldsThePool() {
        val (ring, _) = RingTestKit.ring(capBlocks = 1_000, poolBlocks = 8)
        val slot = newSlot(ring)
        val parked = ring.openReader(0, ring.generation) // paused player: its loader parked at the start
        append(ring, slot, sixtySeconds * BLOCK)
        assertEquals(0L, ring.poolWaits, "the pool never waited")
        assertEquals(0L, parked.cursor)
        // Everything is still readable: the parked reader reads the old part from the file, the newest from RAM.
        val gen = ring.generation
        readAndCheck(parked, gen, (ring.head - parked.cursor).toInt(), ByteArray(256 * 1024))
        val impl = parked as RingReaderImpl
        assertTrue(impl.fileReads > 0 && impl.ramCopies > 0, "file ${impl.fileReads} ram ${impl.ramCopies}")
    }

    @Test
    fun aReaderFarBehindLiveNeverHoldsThePool() {
        val (ring, _) = RingTestKit.ring(capBlocks = 2_000, poolBlocks = 8)
        val slot = newSlot(ring)
        append(ring, slot, 600 * BLOCK) // "10 min behind" in blocks: far outside the RAM window
        val behind = ring.openReader(BLOCK, ring.generation)
        val buf = ByteArray(256 * 1024)
        repeat(sixtySeconds.toInt()) {
            append(ring, slot, BLOCK)
            readAndCheck(behind, ring.generation, BLOCK.toInt(), buf) // 1× behind live
        }
        assertEquals(0L, ring.poolWaits)
        assertTrue((behind as RingReaderImpl).fileReads >= sixtySeconds)
    }

    @Test
    fun aBlockRecycledDuringACopyIsReReadFromTheFile() {
        val (ring, _) = RingTestKit.ring(capBlocks = 100, poolBlocks = 2)
        val slot = newSlot(ring)
        append(ring, slot, BLOCK) // block 0 complete and flushed, still in RAM
        val r = ring.openReader(0, ring.generation) as RingReaderImpl
        var fired = false
        r.afterRamCopyHook = {
            if (!fired) {
                fired = true
                // The writer moves on two blocks during the copy: slot 0 now holds block 2.
                append(ring, slot, 2 * BLOCK)
            }
        }
        val buf = ByteArray(1_000)
        val n = r.read(buf, 0, buf.size)
        for (i in 0 until n) assertEquals(RingTestKit.pattern(ring.generation, i.toLong()), buf[i], "byte $i")
        assertEquals(1L, r.stampMisses, "the stamp caught the recycle")
        assertEquals(1L, r.fileReads, "and the range was re-read from the file")
        assertEquals(0L, r.ramCopies)
    }
}
