package app.thebox.core.playback.timeshift

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** §17.1 `PsiChangeTest` (A-S5): a PMT version change ends the segment; a reader never crosses it; the new PSI is injected. */
class PsiChangeTest {
    @Test
    fun pmtChangeIsABoundaryAndTheNextSegmentGetsTheNewPsi() {
        val clock = FakeMonoClock()
        val (ring, _) = RingTestKit.ring(capBlocks = 400, poolBlocks = 8, clock = clock)
        val ts = SyntheticTs(psiEveryFrames = 5)
        val f = TsFeeder(ring, ts, clock)
        f.feedSeconds(5.0)
        val oldAudio = ts.audioPid
        ts.changePmt()
        f.feedSeconds(5.0)
        val ix = ring.index
        assertEquals(2, ix.liveSegments)
        assertEquals(SegmentReason.PSI_CHANGE, ix.segmentReason(1))
        val nextRap = ix.firstRapOffset(1)
        assertTrue(nextRap > ix.boundaryOf(1))

        // A reader in the old segment stops at the boundary.
        val r = ring.openReader(ix.offsetAt(ix.oldestRap()), ring.generation)
        val buf = ByteArray(64 * 1024)
        val e = assertFailsWith<RingBoundaryException> { while (true) r.read(buf, 0, buf.size) }
        assertEquals(ix.boundaryOf(1), r.cursor, "never crosses the boundary")
        assertEquals(nextRap, e.nextRapOffset)
        assertEquals(SegmentReason.PSI_CHANGE, e.reason)
        assertEquals(0L, e.gapMs)

        // A seek after it injects the new PSI: the PMT lists the new audio PID with version 1.
        val r2 = ring.openReader(nextRap, ring.generation)
        val n = r2.read(buf, 0, buf.size)
        assertEquals(2 * 188, n)
        val pmt = 188
        val version = (buf[pmt + 5 + 5].toInt() ushr 1) and 0x1F
        assertEquals(1, version)
        val pids = HashSet<Int>()
        var q = pmt + 5 + 12
        while (q + 5 <= pmt + 5 + 3 + 9 + 10) { pids += ((buf[q + 1].toInt() and 0x1F) shl 8) or (buf[q + 2].toInt() and 0xFF); q += 5 }
        assertTrue(ts.audioPid in pids && oldAudio !in pids, "new PSI: $pids")
    }
}
