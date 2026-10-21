package app.thebox.core.playback.timeshift

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §17.1 `InlineJumpTest` (A-S5): stream-domain jump detection. */
class InlineJumpTest {
    @Test
    fun aFiveSecondConnectBurstMakesNoSegment() {
        val ts = SyntheticTs()
        val sink = RecordingSink()
        val clock = FakeMonoClock()
        val ix = TsIndexerImpl()
        scanStream(ts, ix, sink, 5.0, clock, paced = false) // 5 s of media arriving at one instant
        scanStream(ts, ix, sink, 10.0, clock, paced = true)
        assertTrue(sink.boundaries.isEmpty(), "${sink.boundaries}")
        assertTrue(sink.raps.size >= 14)
    }

    @Test
    fun aPtsStepBeforeThePcrStepPlacesOneBoundaryAtThePts() {
        val ts = SyntheticTs()
        val frames = HashMap<Long, Long>()
        ts.onFrame = { off, _, f -> frames[f] = off }
        val sink = RecordingSink()
        val clock = FakeMonoClock()
        val ix = TsIndexerImpl()
        scanStream(ts, ix, sink, 4.0, clock)
        val jumpFrame = ts.frame
        ts.jump(3_600L * 90_000, ptsFirst = true) // +1 h, PTS one frame before the PCR
        scanStream(ts, ix, sink, 6.0, clock)
        assertEquals(1, sink.boundaries.size, "${sink.boundaries}")
        val (off, reason) = sink.boundaries[0]
        assertEquals(frames[jumpFrame], off, "boundary at the first PES whose PTS stepped")
        assertEquals(SegmentReason.INLINE_PCR_JUMP, reason)
        // The new segment indexes again, with the small lead (the PCR step was absorbed as the new base).
        val after = sink.raps.filter { it.offset > off }
        assertTrue(after.size >= 4)
        for (r in after) assertEquals(700L * 90, r.pts - r.pcr)
    }

    @Test
    fun aPcrAndPtsStepTogetherIsOneBoundary() {
        val ts = SyntheticTs()
        val sink = RecordingSink()
        val clock = FakeMonoClock()
        val ix = TsIndexerImpl()
        scanStream(ts, ix, sink, 3.0, clock)
        ts.jump(-500_000L, ptsFirst = false) // backwards
        scanStream(ts, ix, sink, 3.0, clock)
        assertEquals(1, sink.boundaries.size)
    }

    @Test
    fun theDiscontinuityIndicatorIsAJumpEvenWhenSmall() {
        val ts = SyntheticTs()
        val sink = RecordingSink()
        val clock = FakeMonoClock()
        val ix = TsIndexerImpl()
        scanStream(ts, ix, sink, 3.0, clock)
        ts.jump(300L * 90, ptsFirst = false, discontinuity = true)
        scanStream(ts, ix, sink, 3.0, clock)
        assertEquals(1, sink.boundaries.size)
        assertEquals(SegmentReason.INLINE_PCR_JUMP, sink.boundaries[0].second)
    }

    @Test
    fun aJumpRightAfterASpliceIsAPcrJump() {
        val ts = SyntheticTs()
        val sink = RecordingSink()
        val clock = FakeMonoClock()
        val ix = TsIndexerImpl()
        scanStream(ts, ix, sink, 3.0, clock)
        ix.onSplice()
        ts.jump(20L * 90_000, ptsFirst = false)
        scanStream(ts, ix, sink, 3.0, clock)
        assertEquals(listOf(SegmentReason.PCR_JUMP), sink.boundaries.map { it.second })
        val spliced = sink.raps.firstOrNull { it.flags and RingFlags.SPLICE != 0 }
        assertTrue(spliced != null, "the entry after the splice is marked SPLICE")
    }

    @Test
    fun aSpliceWithContinuousTimestampsIsNotABoundary() {
        val ts = SyntheticTs()
        val sink = RecordingSink()
        val clock = FakeMonoClock()
        val ix = TsIndexerImpl()
        scanStream(ts, ix, sink, 3.0, clock)
        ix.onSplice()
        scanStream(ts, ix, sink, 3.0, clock)
        assertTrue(sink.boundaries.isEmpty())
    }
}
