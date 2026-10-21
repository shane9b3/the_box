package app.thebox.core.playback.timeshift

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lead reconciliation R5 (§3.2): `tail` honours the rewind time limit as well as the capacity, never past a pin.
 * Lead reconciliation R1: the `*Entry` forms answer the same slot and entry as the two-step lookups.
 */
class TsRingTimeLimitTest {
    private fun fixture(lengthMs: Long): Triple<TsRingImpl, FakeMonoClock, TsFeeder> {
        val clock = FakeMonoClock(20_000_000L)
        val storage = MemoryRingStorage(1_000 * RingTestKit.BLOCK)
        val ring = TsRingImpl(storage, clock, 8, 4_096, null, lengthMs)
        return Triple(ring, clock, TsFeeder(ring, SyntheticTs(gopFrames = 25), clock))
    }

    @Test
    fun tailFollowsTheWindowLengthWhileTheCapacityWouldHoldMore() {
        val (ring, _, f) = fixture(lengthMs = 20_000)
        f.feedSeconds(50.0)
        val ix = ring.index
        val oldest = ix.ptsMonoAt(ix.oldestRap())
        val newest = ix.newestPtsMono()
        assertTrue(newest - oldest <= 20_000 + 100, "window ${newest - oldest} ms")
        assertTrue(newest - oldest >= 20_000 - 1_100, "at most one GOP short: ${newest - oldest} ms")
        assertEquals(ix.offsetAt(ix.oldestRap()), ring.tail, "tail on the oldest RAP (A-N8)")
        assertTrue(ring.head - ring.tail < ring.capacityBytes / 2, "the capacity is not what bounds it")
        RingTestKit.checkInvariants(ring)
    }

    @Test
    fun noLengthMeansTheCapacityAlone() {
        val (ring, _, f) = fixture(lengthMs = 0)
        f.feedSeconds(50.0)
        val ix = ring.index
        assertEquals(0L, ring.tail)
        assertTrue(ix.newestPtsMono() - ix.ptsMonoAt(ix.oldestRap()) >= 48_000)
    }

    @Test
    fun aPinKeepsOlderMediaThanTheWindowAndReleasingItCatchesUp() {
        val (ring, _, f) = fixture(lengthMs = 10_000)
        f.feedSeconds(5.0)
        val e = LongArray(2)
        assertTrue(ring.index.newestRapEntry(e) >= 0)
        ring.setPin(PinOwner.PLAYHEAD, e[0])
        f.feedSeconds(30.0)
        assertTrue(ring.tail <= e[0], "tail ${ring.tail} passed the pin ${e[0]}")
        RingTestKit.checkInvariants(ring)
        ring.clearPin(PinOwner.PLAYHEAD)
        f.feedSeconds(1.0)
        val ix = ring.index
        assertTrue(ix.newestPtsMono() - ix.ptsMonoAt(ix.oldestRap()) <= 10_000 + 100)
        assertTrue(ring.tail > e[0])
    }

    @Test
    fun entryFormsMatchTheTwoStepLookups() {
        val (ring, _, f) = fixture(lengthMs = 0)
        f.feedSeconds(20.0)
        val ix = ring.index
        val e = LongArray(2)
        val mid = (ix.ptsMonoAt(ix.oldestRap()) + ix.newestPtsMono()) / 2
        for ((slot, entrySlot) in listOf(
            ix.floorRap(mid) to ix.floorRapEntry(mid, e),
            ix.ceilRap(mid) to ix.ceilRapEntry(mid, e),
            ix.newestRap() to ix.newestRapEntry(e),
            ix.oldestRap() to ix.oldestRapEntry(e),
        )) assertEquals(slot, entrySlot)
        assertEquals(ix.offsetAt(ix.floorRap(mid)), e.also { ix.floorRapEntry(mid, it) }[0])
        assertEquals(ix.ptsMonoAt(ix.floorRap(mid)), e[1])
        assertEquals(ix.offsetAt(ix.ceilRap(mid)), e.also { ix.ceilRapEntry(mid, it) }[0])
        assertEquals(ix.ptsMonoAt(ix.ceilRap(mid)), e[1])
        assertEquals(-1, ix.floorRapEntry(Long.MIN_VALUE, e))
    }
}
