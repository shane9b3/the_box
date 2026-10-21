package app.thebox.core.playback.timeshift

import app.thebox.core.playback.timeshift.TimeshiftConstants.INDEX_MAX_GAP_MS
import app.thebox.core.playback.timeshift.TimeshiftConstants.INDEX_MIN_SPACING_MS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §17.1 `TsIndexerTest`: RAI; NAL fallback (H.264/H.265/MPEG-2); PSI capture incl. 2 PMTs; no PCR → arrival clock. */
class TsIndexerTest {
    private fun keyframes(ts: SyntheticTs): MutableList<Pair<Long, Long>> =
        ArrayList<Pair<Long, Long>>().also { list -> ts.onKeyframe = { o, p -> list += o to p } }

    @Test
    fun raiGivesOneRapPerKeyframeWithItsPts() {
        val ts = SyntheticTs(gopFrames = 25)
        val keys = keyframes(ts)
        val sink = RecordingSink()
        scanStream(ts, TsIndexerImpl(), sink, 10.0, FakeMonoClock())
        assertTrue(sink.raps.size >= 9)
        val byOffset = keys.toMap()
        for (r in sink.raps) {
            val pts = byOffset[r.offset] ?: error("RAP at ${r.offset} is not a keyframe")
            assertEquals(pts, r.pts and SyntheticTs.MASK33, "PTS of the RAP at ${r.offset}")
            assertEquals(RingFlags.RAP, r.flags and RingFlags.RAP)
            // pts − pcr is the 700 ms lead.
            assertEquals(700L * 90, r.pts - r.pcr)
        }
        assertTrue(sink.boundaries.isEmpty())
    }

    @Test
    fun nalFallbackFindsKeyframesWithoutRai() {
        for ((type, nal) in listOf(SyntheticTs.H264 to 5, SyntheticTs.H264 to 7, SyntheticTs.H265 to 0, SyntheticTs.MPEG2 to 0)) {
            val ts = SyntheticTs(gopFrames = 25, rai = false, streamType = type, h264KeyNal = if (nal == 0) 7 else nal)
            val keys = keyframes(ts)
            val sink = RecordingSink()
            scanStream(ts, TsIndexerImpl(), sink, 8.0, FakeMonoClock())
            val keyOffsets = keys.map { it.first }.toSet()
            assertTrue(sink.raps.size >= 7, "type $type/$nal: ${sink.raps.size} RAPs")
            for (r in sink.raps) assertTrue(r.offset in keyOffsets, "type $type/$nal: RAP at a non-key frame ${r.offset}")
        }
    }

    @Test
    fun fallbackStartsAfterTenSecondsWithoutRai() {
        val ts = SyntheticTs(gopFrames = 25)
        val sink = RecordingSink()
        val ix = TsIndexerImpl()
        val clock = FakeMonoClock()
        scanStream(ts, ix, sink, 5.0, clock)
        val withRai = sink.raps.size
        ts.rai = false // the panel stops sending RAI (fake panel `strip_rai` mid-stream)
        scanStream(ts, ix, sink, 9.0, clock)
        assertEquals(withRai, sink.raps.size, "no NAL fallback while an RAI was seen within RAI_FALLBACK_MS")
        scanStream(ts, ix, sink, 6.0, clock)
        assertTrue(sink.raps.size > withRai, "fallback after 10 s of stream time without RAI")
    }

    @Test
    fun psiCaptureHoldsPatAndBothPmts() {
        val ts = SyntheticTs(programs = 2)
        val sink = RecordingSink()
        scanStream(ts, TsIndexerImpl(), sink, 2.0, FakeMonoClock())
        assertTrue(sink.psi.isNotEmpty())
        val psi = sink.psi.last()
        assertEquals(3 * 188, psi.size, "PAT + 2 PMTs")
        assertEquals(0, ((psi[1].toInt() and 0x1F) shl 8) or (psi[2].toInt() and 0xFF), "first packet is the PAT")
        assertEquals(ts.pmtPid, ((psi[189].toInt() and 0x1F) shl 8) or (psi[190].toInt() and 0xFF))
        assertEquals(ts.pmtPid + 1, ((psi[377].toInt() and 0x1F) shl 8) or (psi[378].toInt() and 0xFF))
        // Repeats with other continuity counters are not changes: PSI was reported once.
        assertEquals(1, sink.psi.size)
        assertTrue(sink.boundaries.isEmpty())
    }

    @Test
    fun noPcrFallsBackToTheArrivalClock() {
        val ts = SyntheticTs(pcr = false)
        val sink = RecordingSink()
        val clock = FakeMonoClock(5_000_000L)
        scanStream(ts, TsIndexerImpl(), sink, 10.0, clock)
        assertTrue(sink.raps.isNotEmpty())
        val late = sink.raps.filter { it.flags and RingFlags.ARRIVAL_CLOCK != 0 }
        assertTrue(late.size >= 6, "arrival-clock RAPs: ${late.size}")
        for (r in late) assertTrue(r.pcr / 90 in 5_000_000L..clock.nowMs(), "arrival ms in pcr")
    }

    /**
     * Integration 2026-10-19 (device I-2, one-connection zaps: "no RAP within 10000 ms"): a GOP of exactly
     * INDEX_MAX_GAP_MS. The time-only anchor fires on the PCR packet just before the keyframe's PES (same lead, so the PCR
     * crosses lastEntryPcr + 2 s a few packets early); the anchor's estimated PTS then sat within INDEX_MIN_SPACING_MS
     * before the keyframe and the spacing rule dropped the RAP, and every following one with it (the 2 s cadence locks).
     * An anchor must never suppress a RAP.
     */
    @Test
    fun aGopOfExactlyTheAnchorGapKeepsEveryRap() {
        for (fps in listOf(25, 50)) {
            val ts = SyntheticTs(gopFrames = fps * 2, fps = fps, pcrPacketFirst = true)
            val keys = keyframes(ts)
            val sink = RecordingSink()
            scanStream(ts, TsIndexerImpl(), sink, 20.0, FakeMonoClock())
            val keyOffsets = keys.map { it.first }.toSet()
            val raps = sink.raps.filter { it.offset in keyOffsets }
            assertTrue(raps.size >= keys.size - 1, "fps $fps: ${raps.size} RAPs for ${keys.size} keyframes, anchors ${sink.anchors.size}")
        }
    }

    @Test
    fun spacingAndAnchors() {
        // 5-frame GOP (200 ms): at most one entry per 400 ms.
        val fast = SyntheticTs(gopFrames = 5)
        val s1 = RecordingSink()
        scanStream(fast, TsIndexerImpl(), s1, 10.0, FakeMonoClock())
        for (i in 1 until s1.raps.size) assertTrue(s1.raps[i].pts - s1.raps[i - 1].pts >= INDEX_MIN_SPACING_MS * 90)
        assertTrue(s1.raps.size in 20..26, "${s1.raps.size}")
        // 5 s GOP: anchors at least every 2 s between RAPs.
        val slow = SyntheticTs(gopFrames = 125)
        val s2 = RecordingSink()
        scanStream(slow, TsIndexerImpl(), s2, 20.0, FakeMonoClock())
        assertTrue(s2.anchors.size >= 5, "anchors ${s2.anchors.size}")
        val all = (s2.raps.map { it.offset to it.pcr } + s2.anchors.map { it.offset to it.pcr }).sortedBy { it.first }
        for (i in 1 until all.size) assertTrue(all[i].second - all[i - 1].second <= (INDEX_MAX_GAP_MS + 100) * 90, "gap between entries")
    }

    @Test
    fun radioAnchorsSitOnAudioPesStarts() {
        val ts = SyntheticTs(video = false, rai = false)
        val sink = RecordingSink()
        scanStream(ts, TsIndexerImpl(), sink, 10.0, FakeMonoClock())
        assertTrue(sink.raps.size >= 4, "radio RAPs ${sink.raps.size}")
        for (r in sink.raps) assertEquals(RingFlags.RAP, r.flags and RingFlags.RAP)
    }

    @Test
    fun misalignedPacketsAreSkipped() {
        val ts = SyntheticTs()
        val buf = ByteArray(188 * 400)
        ts.fill(buf, 0, buf.size)
        for (i in 0 until 400 step 3) buf[i * 188] = 0x00 // break every third sync byte
        val sink = RecordingSink()
        TsIndexerImpl().scan(buf, 0, buf.size, 0, 0, sink) // must not throw
        assertTrue(sink.raps.size <= 2)
    }
}
