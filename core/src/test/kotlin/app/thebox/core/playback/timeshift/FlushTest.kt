package app.thebox.core.playback.timeshift

import app.thebox.core.playback.timeshift.RingTestKit.BLOCK
import app.thebox.core.playback.timeshift.RingTestKit.append
import app.thebox.core.playback.timeshift.RingTestKit.drainFlush
import app.thebox.core.playback.timeshift.RingTestKit.newSlot
import app.thebox.core.playback.timeshift.RingTestKit.readAndCheck
import app.thebox.core.playback.timeshift.TimeshiftConstants.FLUSH_PARTIAL_MS
import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_EXTENT_BLOCKS
import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_HEADER_BYTES
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §17.1 `FlushTest` (fake `RingStorage`): latency, ENOSPC/EIO, unlink → the matching `RecordStop`; PAUSE_ONLY (A-S10). */
class FlushTest {
    private fun waitFor(what: String, ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.nanoTime() + ms * 1_000_000
        while (!cond()) { check(System.nanoTime() < end) { "timed out waiting for $what" }; Thread.sleep(2) }
    }

    private class Live(val ring: TsRingImpl, val storage: MemoryRingStorage, val writer: RingWriterImpl, val listener: RecordingWriterListener, val up: FakeTsUpstream)

    private fun live(storage: MemoryRingStorage = MemoryRingStorage(400 * BLOCK), pool: Int = 4): Live {
        val clock = ScaledClock(1)
        val ring = TsRingImpl(storage, clock, pool, 4_096, RingFlusher("test-flush-${System.nanoTime()}"))
        val up = FakeTsUpstream(chunk = 32 * 188).also { it.pacing = { Thread.sleep(1) } }
        val l = RecordingWriterListener()
        val w = RingWriterImpl(ring, TsIndexerImpl(), clock, "flush")
        w.start(up, l)
        return Live(ring, storage, w, l, up)
    }

    private fun Live.close() { writer.close(); writer.awaitExit(2_000); ring.close() }

    @Test
    fun aSlowDiskStopsSavingWithDiskTooSlowAndLiveGoesOnFromRam() {
        val lv = live(pool = 4)
        waitFor("bytes") { lv.ring.head > BLOCK }
        // A player at live: reads whatever arrives.
        val got = java.util.concurrent.atomic.AtomicLong()
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val reader = Thread {
            val r = lv.ring.openReader(lv.ring.head, lv.ring.generation)
            val buf = ByteArray(64 * 1024)
            try { while (!stop.get()) got.addAndGet(r.read(buf, 0, buf.size).toLong()) } catch (_: java.io.IOException) {}
            r.close()
        }
        reader.start()
        lv.storage.writeDelayMs = 3_000 // one pwrite takes longer than PUMP_BLOCK_MAX_MS
        waitFor("disk too slow", 10_000) { lv.listener.has("stopped:DISK_TOO_SLOW") }
        assertEquals(RecordStop.DISK_TOO_SLOW, lv.ring.stop)
        assertTrue(!lv.ring.saving)
        assertTrue(!lv.up.log.contains("close:up"), "the connection is kept")
        // The reader at live keeps getting bytes from RAM.
        val before = got.get()
        waitFor("live goes on from RAM") { got.get() > before + 4 * BLOCK }
        stop.set(true); reader.join(2_000)
        lv.storage.writeDelayMs = 0; lv.close()
    }

    @Test
    fun writeErrorsStopWithDiskError() {
        for (msg in listOf("ENOSPC", "EIO")) {
            val lv = live()
            waitFor("bytes") { lv.ring.head > 2 * BLOCK }
            lv.storage.failWrites = IOException(msg)
            waitFor("disk error") { lv.listener.has("stopped:DISK_ERROR") }
            assertEquals(RecordStop.DISK_ERROR, lv.ring.stop)
            assertTrue(!lv.up.log.contains("close:up"), "the connection is kept")
            val h = lv.ring.head
            waitFor("RAM keeps going") { lv.ring.head > h + 2 * BLOCK }
            lv.close()
        }
    }

    @Test
    fun anUnlinkedFileStopsWithStorageEvictedAndClosesTheFd() {
        val lv = live()
        waitFor("bytes") { lv.ring.head > BLOCK }
        lv.storage.unlinked = true
        waitFor("evicted", 3_000) { lv.listener.has("stopped:STORAGE_EVICTED") }
        assertTrue(lv.storage.closed, "fd closed at once so the space is freed")
        lv.close()
    }

    @Test
    fun pauseOnlyWritesNothingAtLiveAndPersistsFromTheOldestPoolBlockOnPause() {
        val (ring, storage) = RingTestKit.ring(capBlocks = 200, poolBlocks = 4)
        ring.mode = BufferMode.PAUSE_ONLY
        val slot = newSlot(ring)
        append(ring, slot, 3 * BLOCK, rapEveryPackets = 20)
        val reader = ring.openReader(ring.head, ring.generation)
        repeat(30) { // a reader at live keeps up block by block
            append(ring, slot, BLOCK, rapEveryPackets = 20)
            readAndCheck(reader, ring.generation, (ring.head - reader.cursor).toInt())
        }
        assertEquals(0L, storage.bytesWritten.get(), "nothing written at live")
        assertTrue(ring.tail >= ring.head - 4 * BLOCK, "tail follows the RAM window: tail ${ring.tail} head ${ring.head}")
        val oldestInRam = (ring.tail / BLOCK) * BLOCK
        // Pause: saving starts from the oldest block still in the pool.
        append(ring, slot, 30 * BLOCK, rapEveryPackets = 20, paused = true)
        assertEquals(oldestInRam, ring.fileValidFrom)
        assertTrue(storage.bytesWritten.get() >= 30 * BLOCK)
        assertTrue(ring.tail <= oldestInRam + BLOCK, "the paused part is kept")
        val r = ring.openReader(ring.tail, ring.generation)
        readAndCheck(r, ring.generation, (ring.head - ring.tail).toInt(), ByteArray(256 * 1024))
    }

    @Test
    fun aPartialBlockIsFlushedAfterFlushPartialMs() {
        val clock = FakeMonoClock()
        val (ring, storage) = RingTestKit.ring(capBlocks = 20, poolBlocks = 3, clock = clock)
        append(ring, newSlot(ring), 100 * 188, flushEachBlock = false)
        drainFlush(ring)
        assertEquals(0L, ring.flushed)
        clock.advance(FLUSH_PARTIAL_MS)
        drainFlush(ring)
        assertEquals(100L * 188, ring.flushed)
        assertEquals(100L * 188, storage.bytesWritten.get())
    }

    @Test
    fun theFileGrowsByExtentsAndStopsGrowingOnLowSpace() {
        val storage = MemoryRingStorage(1_200 * BLOCK)
        val (ring, _) = RingTestKit.ring(capBlocks = 1_200, poolBlocks = 4, storage = storage)
        val slot = newSlot(ring)
        append(ring, slot, 10 * BLOCK, rapEveryPackets = 20)
        assertEquals(RING_HEADER_BYTES + RING_EXTENT_BLOCKS * BLOCK, storage.allocated)
        // Free space drops under reserve/2 before the next extent: the ring wraps at its current size (§3.1).
        storage.available = 100L * 1024 * 1024
        append(ring, slot, (RING_EXTENT_BLOCKS - 8) * BLOCK, rapEveryPackets = 20)
        append(ring, slot, 5 * BLOCK, rapEveryPackets = 20)
        assertTrue(ring.capacityBytes < 1_200 * BLOCK, "capacity ${ring.capacityBytes / BLOCK} blocks")
        assertTrue(ring.capacityBytes >= RING_EXTENT_BLOCKS * BLOCK)
        RingTestKit.checkInvariants(ring, storage)
        val r = ring.openReader(ring.tail, ring.generation)
        readAndCheck(r, ring.generation, (ring.head - ring.tail).toInt(), ByteArray(256 * 1024))
    }

    @Test
    fun persistedBytesFeedTheDailyCounter() {
        val (ring, _) = RingTestKit.ring(capBlocks = 1_100, poolBlocks = 4)
        val reports = ArrayList<Long>()
        ring.persistListener = { reports += it }
        append(ring, newSlot(ring), 1_050 * BLOCK)
        assertEquals(1_050 * BLOCK, ring.persistedBytes)
        assertEquals(1, reports.size)
        assertTrue(reports[0] >= TimeshiftConstants.DAILY_COUNTER_FLUSH_BYTES)
    }

    @Test
    fun theHeaderIsWrittenOnCreateAndOnEachReset() {
        val (ring, storage) = RingTestKit.ring(capBlocks = 10, poolBlocks = 2)
        assertEquals(1L, storage.headers.get())
        val g = ring.reset()
        assertEquals(2L, storage.headers.get())
        assertEquals(g, storage.lastHeaderGeneration)
        val h = RingHeaderCodec.decode(java.nio.ByteBuffer.wrap(storage.data, 0, RING_HEADER_BYTES))!!
        assertEquals(g, h.generation)
        assertEquals(10 * BLOCK, h.capacity)
    }
}
