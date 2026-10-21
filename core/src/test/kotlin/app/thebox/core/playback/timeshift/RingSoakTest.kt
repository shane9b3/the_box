package app.thebox.core.playback.timeshift

import app.thebox.core.playback.timeshift.RingTestKit.BLOCK
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Soak: 10 minutes of synthetic 1 MB/s through the real indexer and ring in a few seconds of CPU, the ring capped at
 * 40 blocks (2.6 MB) so it wraps hundreds of times, a player reading at 1× a random distance behind live, the PLAYHEAD
 * pin following it; the §3.2 invariants are asserted after every block and every byte read is checked.
 */
class RingSoakTest {
    @Test
    fun tenMinutesAtOneMegabytePerSecond() {
        val clock = FakeMonoClock()
        val storage = MemoryRingStorage(40 * BLOCK)
        val ring = TsRingImpl(storage, clock, 8, RingIndexImpl.capacityFor(120_000), null)
        val ts = SyntheticTs(bytesPerSec = 1_000_000)
        val ix = TsIndexerImpl()
        val slot = RingSlot().also { it.generation = ring.generation }
        val rnd = Random(10)
        val reader = ring.openReader(0, ring.generation)
        val buf = ByteArray(256 * 1024)
        var lagBlocks = 5
        var checkedPackets = 0L
        var frac = 0.0
        val totalBytes = (600 * ts.actualBytesPerSec).toLong()
        var fulls = 0
        while (ring.head < totalBytes) {
            val r = ring.claim(188, 0, false, slot)
            if (r == ClaimResult.FULL) {
                fulls++
                readUpTo(reader, ring.head - BLOCK, buf).also { checkedPackets += it }
                ring.setPin(PinOwner.PLAYHEAD, reader.cursor)
                continue
            }
            assertEquals(ClaimResult.OK, r)
            val n = ts.fill(slot.array, slot.offset, minOf(slot.length, 64 * 188))
            frac += n * 1000.0 / ts.actualBytesPerSec
            val w = frac.toLong(); frac -= w; clock.advance(w)
            ring.beginScan(clock.nowMs())
            ix.scan(slot.array, slot.offset, n, ring.head, clock.nowMs(), ring.sink)
            ring.commit(slot, n, clock.nowMs())
            if (ring.head % BLOCK == 0L) {
                if (rnd.nextInt(50) == 0) lagBlocks = rnd.nextInt(0, 20)
                checkedPackets += readUpTo(reader, ring.head - lagBlocks * BLOCK, buf)
                val pin = maxOf(ring.tail, reader.cursor - rnd.nextLong(0, 2 * BLOCK) / 188 * 188)
                ring.setPin(PinOwner.PLAYHEAD, pin)
                RingTestKit.drainFlush(ring)
                checkBlockInvariants(ring)
            }
        }
        val wraps = ring.head / ring.capacityBytes
        assertTrue(wraps >= 200, "wrapped $wraps times")
        assertTrue(checkedPackets > 1_000_000, "checked $checkedPackets filler packets")
        assertEquals(1, ring.index.liveSegments)
        assertTrue(ring.index.size > 0)
        println("soak: ${ring.head / 1_000_000} MB, $wraps wraps, $fulls FULL waits, ${ring.index.size} entries")
    }

    private fun readUpTo(reader: RingReader, target: Long, buf: ByteArray): Int {
        var checked = 0
        while (reader.cursor < target) {
            val start = reader.cursor
            val n = reader.read(buf, 0, minOf(buf.size.toLong(), target - start).toInt().coerceAtLeast(188))
            if (reader.cursor == start) continue // the injected PSI
            checked += checkSynthetic(buf, 0, n, start)
        }
        return checked
    }

    private fun checkBlockInvariants(ring: TsRingImpl) {
        val t = ring.tail; val h = ring.head
        check(t in 0..h && h - t <= ring.capacityBytes) { "tail $t head $h" }
        val mp = ring.minPinOrMax()
        check(mp == Long.MAX_VALUE || mp in t..h) { "pin $mp outside [$t, $h]" }
        check(ring.index.size == 0 || ring.index.entryOffset(0) >= t) { "entry before tail" }
        val oldest = ring.index.oldestRap()
        check(oldest < 0 || ring.index.offsetAt(oldest) >= t)
        if (t > 0 && oldest >= 0) check(ring.index.firstRapAtOrAfter(t) == t || ring.index.entryOffset(0) == t) { "tail $t not on an entry" }
        check(ring.flushed <= h && ring.fileValidFrom <= maxOf(t, ring.fileValidFrom))
    }
}
