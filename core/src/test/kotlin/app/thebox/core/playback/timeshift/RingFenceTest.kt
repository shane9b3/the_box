package app.thebox.core.playback.timeshift

import app.thebox.core.playback.timeshift.RingTestKit.append
import app.thebox.core.playback.timeshift.RingTestKit.newSlot
import app.thebox.core.playback.timeshift.RingTestKit.readAndCheck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** §17.1 `RingFenceTest` (A-S1): a late commit of an old pump is dropped; an old reader ends with SESSION_END. */
class RingFenceTest {
    @Test
    fun anOldPumpBlockedAcrossResetCannotTouchTheNewGeneration() {
        val clock = FakeMonoClock()
        val (ring, _) = RingTestKit.ring(capBlocks = 50, poolBlocks = 2, clock = clock)
        val up = FakeTsUpstream(SyntheticTs(), chunk = 8 * 188)
        val listener = RecordingWriterListener()
        val writer = RingWriterImpl(ring, TsIndexerImpl(), clock, "fence")
        writer.start(up, listener)
        while (up.delivered.get() < 4 * 188) Thread.sleep(1)
        val oldReader = ring.openReader(0, ring.generation)
        up.blockReads = true
        while (!up.blockedNow) Thread.sleep(1) // the pump holds a claimed slot and sits in read()
        val oldGen = ring.generation

        // The join timed out (PUMP_JOIN_MAX_MS): reset anyway; the new generation starts writing.
        assertTrue(!writer.awaitExit(50))
        val newGen = ring.reset()
        assertTrue(newGen > oldGen)
        val slot = newSlot(ring)
        append(ring, slot, 3 * 188)
        val headBefore = ring.head

        up.release() // the old pump's read returns into its (quarantined) block and commits late
        assertTrue(writer.awaitExit(2_000), "the old pump exits on STALE_GENERATION")
        assertEquals(headBefore, ring.head, "the late commit was dropped")
        readAndCheck(ring.openReader(0, newGen), newGen, (ring.head).toInt())
        assertEquals(RecordStop.SESSION_END, assertFailsWith<RingEndException> { oldReader.read(ByteArray(188), 0, 188) }.stop)
        writer.close()
    }

    /** Integration 2026-10-19: a session-end stop for a generation that a reset already replaced does nothing. */
    @Test
    fun aStaleSessionEndNeverStopsTheNewGeneration() {
        val (ring, _) = RingTestKit.ring(capBlocks = 50, poolBlocks = 2, clock = FakeMonoClock())
        val oldGen = ring.generation
        val newGen = ring.reset()
        ring.stopRecordingFor(oldGen, RecordStop.SESSION_END)
        assertEquals(null, ring.stop)
        val slot = newSlot(ring)
        append(ring, slot, 3 * 188)
        assertEquals(3L * 188, ring.head)
        ring.stopRecordingFor(newGen, RecordStop.SESSION_END)
        assertEquals(RecordStop.SESSION_END, ring.stop)
    }
}
