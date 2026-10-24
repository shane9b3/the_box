package app.thebox.core.storage.smb

import app.thebox.core.playback.timeshift.ClaimResult
import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.RingSlot
import app.thebox.core.playback.timeshift.RingTestKit
import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_BLOCK_BYTES
import app.thebox.core.playback.timeshift.TimeshiftConstants.RING_HEADER_BYTES
import app.thebox.core.playback.timeshift.TimeshiftConstants.TS_PACKET
import app.thebox.core.playback.timeshift.TsRingImpl
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.StorageLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Writes the RingTestKit pattern into [ring] at [bytesPerSec] on its own thread (the pump of a live cycle), in real
 * time. A DISK_STALLED claim stops saving with DISK_TOO_SLOW, as `RingWriterImpl` does; FULL waits.
 */
internal class PatternPump(private val ring: TsRingImpl, private val bytesPerSec: Long, name: String = "test-pump") : Thread(name) {
    @Volatile var running = true
    @Volatile var stalls = 0
    init { isDaemon = true }

    override fun run() {
        val slot = RingSlot().also { it.generation = ring.generation }
        val chunk = 32 * TS_PACKET
        val t0 = System.nanoTime()
        var sent = 0L
        while (running) {
            when (ring.claim(TS_PACKET, 100, false, slot)) {
                ClaimResult.OK -> {
                    val n = minOf(chunk, slot.length)
                    val start = ring.head
                    for (i in 0 until n) slot.array[slot.offset + i] = RingTestKit.pattern(ring.generation, start + i)
                    ring.commit(slot, n, System.nanoTime() / 1_000_000L)
                    sent += n
                    val dueNs = t0 + sent * 1_000_000_000L / bytesPerSec
                    val waitNs = dueNs - System.nanoTime()
                    if (waitNs > 0) sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
                }
                ClaimResult.DISK_STALLED -> { stalls++; ring.stopRecording(RecordStop.DISK_TOO_SLOW) }
                ClaimResult.FULL -> sleep(5)
                else -> return
            }
        }
    }

    fun halt() { running = false; join(3_000) }
}

internal fun waitUntil(what: String, ms: Long, cond: () -> Boolean) {
    val end = System.nanoTime() + ms * 1_000_000
    while (!cond()) { check(System.nanoTime() < end) { "timed out waiting for $what" }; Thread.sleep(5) }
}

/**
 * §16.1 `TsRingOverSmbTest` (WP-S1, A-S9): the real ring with its real dedicated flusher thread over [SmbRingStorage] and
 * [FakeRemoteLink], in real time. The pool is the stall budget (§4.4).
 */
class TsRingOverSmbTest {
    private val rate = 1_000_000L // 1 MB/s, the HD assumption
    private val block = RING_BLOCK_BYTES.toLong()

    private fun smbRing(link: FakeRemoteLink, poolBlocks: Int, capBlocks: Int = 256): Pair<TsRingImpl, SmbRingStorage> {
        val st = SmbRingStorage(link, "TheBox-rewind/ring-abcdef012345-1-1.bin", capBlocks * block, StorageLimits.SMB, link.clock)
        assertTrue(st.create(link.clock.nowMs() + 5_000))
        return TsRingImpl(st, link.clock, poolBlocks, 4_096) to st
    }

    @Test
    fun aThreeSecondStallIsAbsorbedByAProportionalPool() {
        val link = FakeRemoteLink()
        val (ring, st) = smbRing(link, poolBlocks = 64) // 64 blocks ≈ 4.2 s at 1 MB/s
        val pump = PatternPump(ring, rate).also { it.start() }
        try {
            waitUntil("first flushes", 3_000) { ring.flushed > 4 * block }
            link.stallUntil(link.clock.nowMs() + 3_000)
            Thread.sleep(3_000)
            waitUntil("catch-up", 4_000) { ring.head - ring.flushed < 2 * block }
            assertNull(ring.stop, "no stop: the pool absorbed the stall")
            assertTrue(ring.saving)
            assertEquals(0, link.reconnects.get(), "a stall is not a transport loss")
            assertNull(st.lastFailure)
            // The flushed part is in the server file, as written.
            val data = link.contents(st.relativePath)!!
            for (l in longArrayOf(0L, block + 17, 5 * block + 4_001)) {
                assertEquals(RingTestKit.pattern(ring.generation, l), data[(RING_HEADER_BYTES + l).toInt()], "byte at $l")
            }
        } finally { pump.halt(); ring.close(); st.close() }
    }

    @Test
    fun theSameStallWithASmallerPoolEndsOnTheSlowPath() {
        val link = FakeRemoteLink()
        val (ring, st) = smbRing(link, poolBlocks = 8) // ≈ 0.5 s at 1 MB/s: full, then PUMP_BLOCK_MAX_MS, inside the 3 s
        val pump = PatternPump(ring, rate).also { it.start() }
        try {
            waitUntil("first flushes", 3_000) { ring.flushed > 2 * block }
            link.stallUntil(link.clock.nowMs() + 3_000)
            waitUntil("slow stop", 6_000) { ring.stop != null }
            assertEquals(RecordStop.DISK_TOO_SLOW, ring.stop, "the pool ran out first")
            assertTrue(!ring.saving)
            val h = ring.head
            waitUntil("live goes on from RAM", 3_000) { ring.head > h + 4 * block }
            assertEquals(0, link.reconnects.get())
        } finally { pump.halt(); ring.close(); st.close() }
    }

    @Test
    fun aTransportLossPastTheBudgetIsDiskErrorAndTheSavedPartReadsAfterRecovery() {
        val link = FakeRemoteLink(ioTimeoutMs = 1_500) // budget = min(SMB_RECONNECT_BUDGET_MS, 1.5 s)
        val (ring, st) = smbRing(link, poolBlocks = 16)
        val pump = PatternPump(ring, rate).also { it.start() }
        try {
            waitUntil("saved part", 5_000) { ring.flushed > 40 * block }
            // A reader pinned behind live keeps the saved part (§4.6 "behind live").
            val reader = ring.openReader(2 * block, ring.generation)
            link.reconnectFails = true
            link.dropTransport()
            waitUntil("disk error", 6_000) { ring.stop != null }
            assertEquals(RecordStop.DISK_ERROR, ring.stop)
            assertEquals(StorageFailure.UNREACHABLE, st.lastFailure)
            assertTrue(st.needsFreshFile)
            assertTrue(!ring.saving)
            // The server is back: the reader's file read reconnects and reopens without truncating.
            link.reconnectFails = false
            assertEquals(4 * RING_BLOCK_BYTES, RingTestKit.readAndCheck(reader, ring.generation, 4 * RING_BLOCK_BYTES))
            assertTrue(link.opens.last().second == OpenMode.OPEN_EXISTING)
            reader.close()
        } finally { pump.halt(); ring.close(); st.close() }
    }
}
