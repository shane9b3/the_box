package app.thebox.core.playback.timeshift

import app.thebox.core.playback.timeshift.RingTestKit.BLOCK
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §17.1 `RingAllocationTest` (§15): 0 bytes per packet and per block in steady state on the write path (claim → read →
 * scan → commit → flush) and the read path, measured with `ThreadMXBean.getThreadAllocatedBytes` as
 * `RendererAllocationTest` does; and the copy count: one copy per byte range read (RAM `arraycopy`, or `pread` + one
 * `arraycopy`).
 */
class RingAllocationTest {
    private val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    @Test
    fun tenThousandBlocksAllocateNothingOnTheWriteAndReadPaths() {
        val clock = FakeMonoClock()
        val ring = TsRingImpl(MemoryRingStorage(64 * BLOCK), clock, 16, RingIndexImpl.capacityFor(60_000), null)
        val ts = SyntheticTs()
        val ix = TsIndexerImpl()
        val slot = RingSlot().also { it.generation = ring.generation }
        val reader = ring.openReader(0, ring.generation) as RingReaderImpl
        val buf = ByteArray(BLOCK.toInt())
        fun oneBlock() {
            val target = (ring.head / BLOCK + 1) * BLOCK
            while (ring.head < target) {
                check(ring.claim(188, 0, false, slot) == ClaimResult.OK)
                val n = ts.fill(slot.array, slot.offset, minOf(slot.length, 32 * 188))
                clock.advance(n / 1_000L + 1)
                ring.beginScan(clock.nowMs())
                ix.scan(slot.array, slot.offset, n, ring.head, clock.nowMs(), ring.sink)
                ring.commit(slot, n, clock.nowMs())
            }
            // The player behind live by a few blocks: block-aligned reads, one copy each.
            while (ring.head - reader.cursor > 6 * BLOCK) {
                val want = (BLOCK - reader.cursor % BLOCK).toInt()
                check(reader.read(buf, 0, want) == want)
            }
            ring.setPin(PinOwner.PLAYHEAD, reader.cursor)
        }
        repeat(3_000) { oneBlock() } // warm-up: JIT, staging buffer, index wrap
        val id = Thread.currentThread().id
        val copiesBefore = reader.ramCopies + reader.fileReads
        val readsBefore = reader.cursor
        val before = bean.getThreadAllocatedBytes(id)
        repeat(10_000) { oneBlock() }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        assertTrue(allocated < 1_024, "10,000 blocks allocated $allocated bytes")
        assertTrue(ring.head > 100 * ring.capacityBytes, "wrapped many times")
        // Copy count: one copy per block-sized range read (A-S8).
        val ranges = (reader.cursor - readsBefore) / BLOCK
        assertEquals(ranges, reader.ramCopies + reader.fileReads - copiesBefore)
    }

    @Test
    fun theThreadedPumpAndFlusherAllocateNothingPerBlock() {
        val flusher = RingFlusher("test-flush-alloc")
        val clock = ScaledClock(1)
        val ring = TsRingImpl(MemoryRingStorage(64 * BLOCK), clock, 16, RingIndexImpl.capacityFor(60_000), flusher)
        val up = FakeTsUpstream(SyntheticTs(), chunk = 64 * 1024)
        val writer = RingWriterImpl(ring, TsIndexerImpl(), clock, "alloc")
        writer.start(up, RecordingWriterListener())
        while (ring.head < 3_000 * BLOCK) Thread.sleep(5) // warm-up
        val pump = Thread.getAllStackTraces().keys.first { it.name == "box-ts-pump-alloc" }
        val pumpBefore = bean.getThreadAllocatedBytes(pump.id)
        val flushBefore = bean.getThreadAllocatedBytes(flusher.threadId)
        val headBefore = ring.head
        while (ring.head < headBefore + 5_000 * BLOCK) Thread.sleep(5)
        val blocks = (ring.head - headBefore) / BLOCK
        val pumpBytes = bean.getThreadAllocatedBytes(pump.id) - pumpBefore
        val flushBytes = bean.getThreadAllocatedBytes(flusher.threadId) - flushBefore
        writer.close(); writer.awaitExit(2_000); ring.close()
        println("threaded: $blocks blocks, pump $pumpBytes B, flusher $flushBytes B")
        // A queued lock hand-off between the two threads may allocate an AQS node now and then: < 1 byte per block.
        assertTrue(pumpBytes < blocks, "pump allocated $pumpBytes bytes over $blocks blocks")
        assertTrue(flushBytes < blocks, "flusher allocated $flushBytes bytes over $blocks blocks")
    }
}
