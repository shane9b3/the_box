package app.thebox.core.playback.timeshift

import app.thebox.core.playback.timeshift.RingTestKit.BLOCK
import app.thebox.core.playback.timeshift.RingTestKit.append
import app.thebox.core.playback.timeshift.RingTestKit.checkInvariants
import app.thebox.core.playback.timeshift.RingTestKit.newSlot
import app.thebox.core.playback.timeshift.RingTestKit.pattern
import app.thebox.core.playback.timeshift.RingTestKit.readAndCheck
import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_HEADER_BYTES
import app.thebox.core.playback.timeshift.TimeshiftConstants.TS_PACKET
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** §17.1 `TsRingWrapTest`: wrap at capacity and ±188, no straddling, entries before tail dropped, tail on the oldest RAP. */
class TsRingWrapTest {
    @Test
    fun capacityIsWholeBlocksForPlusMinusOnePacket() {
        for ((bytes, blocks) in listOf(4 * BLOCK to 4L, 4 * BLOCK + TS_PACKET to 4L, 4 * BLOCK - TS_PACKET to 3L)) {
            val ring = TsRingImpl(MemoryRingStorage(bytes), FakeMonoClock(), 2, 64, null)
            assertEquals(blocks * BLOCK, ring.capacityBytes, "storage $bytes")
            assertEquals(0L, ring.capacityBytes % TS_PACKET)
        }
    }

    @Test
    fun logicalOffsetMapsToHeaderPlusModuloAndNeverStraddles() {
        val (ring, storage) = RingTestKit.ring(capBlocks = 4, poolBlocks = 2)
        val slot = newSlot(ring)
        append(ring, slot, 11 * BLOCK + 37 * TS_PACKET, rapEveryPackets = 100)
        checkInvariants(ring, storage)
        val cap = ring.capacityBytes
        // Every flushed packet start sits at HEADER + L mod cap and begins with the sync byte: no packet straddles the wrap.
        var l = maxOf(ring.tail, ring.fileValidFrom)
        while (l < ring.flushed) {
            val pos = (RING_HEADER_BYTES + l % cap).toInt()
            assertEquals(0x47.toByte(), storage.data[pos], "packet at $l")
            assertTrue(pos + TS_PACKET <= RING_HEADER_BYTES + cap, "packet at $l straddles the end of the file")
            l += TS_PACKET
        }
    }

    @Test
    fun tailLandsOnTheOldestRapAndEntriesBeforeItAreDropped() {
        val (ring, _) = RingTestKit.ring(capBlocks = 4, poolBlocks = 2)
        val slot = newSlot(ring)
        append(ring, slot, 9 * BLOCK, rapEveryPackets = 250)
        val t = ring.tail
        assertTrue(t > 0)
        val oldest = ring.index.oldestRap()
        assertEquals(t, ring.index.offsetAt(oldest), "tail is the oldest RAP (A-N8)")
        assertEquals(0L, (t / TS_PACKET) % 250, "tail on a RAP packet")
        for (i in 0 until ring.index.size) assertTrue(ring.index.entryOffset(i) >= t)
        assertTrue(ring.head - t <= ring.capacityBytes)
    }

    @Test
    fun readerAcrossTheWrapReadsEveryByteAsWritten() {
        val (ring, _) = RingTestKit.ring(capBlocks = 5, poolBlocks = 2)
        val slot = newSlot(ring)
        append(ring, slot, 3 * BLOCK, rapEveryPackets = 50)
        val r = ring.openReader(ring.tail, ring.generation)
        readAndCheck(r, ring.generation, (ring.head - ring.tail).toInt())
        // Keep writing across several wraps; the reader keeps up (its pin holds the tail).
        repeat(12) {
            append(ring, slot, BLOCK + 3 * TS_PACKET, rapEveryPackets = 50)
            readAndCheck(r, ring.generation, (ring.head - r.cursor).toInt())
            checkInvariants(ring)
        }
        assertTrue(ring.head > 3 * ring.capacityBytes)
        r.close()
    }

    @Test
    fun readerBelowTailGetsPositionLost() {
        val (ring, _) = RingTestKit.ring(capBlocks = 3, poolBlocks = 2)
        val slot = newSlot(ring)
        append(ring, slot, 8 * BLOCK, rapEveryPackets = 40)
        val r = ring.openReader(0, ring.generation)
        val e = assertFailsWith<RingPositionLostException> { r.read(ByteArray(TS_PACKET), 0, TS_PACKET) }
        assertEquals(0L, e.requested)
        assertEquals(ring.tail, e.tail)
    }

    @Test
    fun patternSelfCheck() {
        assertEquals(0x47.toByte(), pattern(3, 188 * 5))
    }
}
