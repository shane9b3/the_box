package app.thebox.core.playback.timeshift

import app.thebox.core.playback.timeshift.RingTestKit.BLOCK
import app.thebox.core.playback.timeshift.RingTestKit.append
import app.thebox.core.playback.timeshift.RingTestKit.newSlot
import app.thebox.core.playback.timeshift.RingTestKit.readAndCheck
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** §17.1 `RingReaderTest`: blocking at head, cancel/interrupt, never 0/−1, boundary/end/lost/io exceptions, PSI first. */
class RingReaderTest {
    @Test
    fun aReaderBlockedAtTheHeadWakesWithinMillisecondsOfACommit() {
        val (ring, _) = RingTestKit.ring(capBlocks = 50, poolBlocks = 4)
        val slot = newSlot(ring)
        append(ring, slot, 10 * 188)
        val r = ring.openReader(0, ring.generation)
        readAndCheck(r, ring.generation, 10 * 188)
        val woke = ArrayBlockingQueue<Long>(1)
        val lat = ArrayList<Long>()
        repeat(30) {
            val t = Thread {
                val buf = ByteArray(64 * 1024)
                val n = r.read(buf, 0, buf.size)
                check(n > 0)
                woke.put(System.nanoTime())
            }
            t.start()
            while (!r.atHead || t.state != Thread.State.TIMED_WAITING) Thread.sleep(1)
            val t0 = System.nanoTime()
            append(ring, slot, 3 * 188)
            val t1 = woke.poll(2, TimeUnit.SECONDS) ?: error("reader did not wake")
            lat += (t1 - t0) / 1_000
            t.join()
        }
        lat.sort()
        assertTrue(lat[lat.size / 2] <= 5_000, "median wake ${lat[lat.size / 2]} µs")
        assertTrue(lat.last() <= 50_000, "max wake ${lat.last()} µs")
    }

    @Test
    fun cancelAndInterruptEndABlockedReadWithInterruptedIoException() {
        val (ring, _) = RingTestKit.ring(capBlocks = 10, poolBlocks = 2)
        append(ring, newSlot(ring), 188)
        for (mode in listOf("cancel", "interrupt", "close")) {
            val r = ring.openReader(188, ring.generation)
            val result = ArrayBlockingQueue<Any>(1)
            val t = Thread { try { r.read(ByteArray(188), 0, 188); result.offer("returned") } catch (e: Throwable) { result.offer(e) } }
            t.start()
            while (t.state != Thread.State.TIMED_WAITING) Thread.sleep(1)
            val t0 = System.nanoTime()
            when (mode) { "cancel" -> r.cancel(); "interrupt" -> t.interrupt(); else -> r.close() }
            val got = result.poll(1, TimeUnit.SECONDS)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertIs<InterruptedIOException>(got, mode)
            assertTrue(ms <= 50, "$mode took $ms ms")
            r.close()
        }
    }

    @Test
    fun psiComesFirstThenTheBytesFromTheRap() {
        val clock = FakeMonoClock()
        val (ring, _) = RingTestKit.ring(capBlocks = 200, poolBlocks = 8, clock = clock)
        val f = TsFeeder(ring, SyntheticTs(programs = 2), clock)
        f.feedSeconds(4.0)
        val rap = ring.index.offsetAt(ring.index.newestRap())
        val r = ring.openReader(rap, ring.generation)
        val buf = ByteArray(10 * 188)
        val n = r.read(buf, 0, buf.size)
        assertEquals(3 * 188, n, "PAT + 2 PMTs injected first")
        assertEquals(0, ((buf[1].toInt() and 0x1F) shl 8) or (buf[2].toInt() and 0xFF))
        val m = r.read(buf, 0, buf.size)
        assertTrue(m > 0 && m % 188 == 0)
        assertEquals(rap + m, r.cursor)
        assertEquals(0x40, buf[1].toInt() and 0x40, "the first ring packet is the RAP's PES start")
    }

    @Test
    fun readsAreWholePacketsAndNeverZeroOrMinusOne() {
        val (ring, _) = RingTestKit.ring(capBlocks = 20, poolBlocks = 3)
        append(ring, newSlot(ring), 3 * BLOCK)
        val r = ring.openReader(0, ring.generation)
        val buf = ByteArray(100_000)
        var total = 0L
        for (len in listOf(188, 189, 1000, 65_424, 100_000, 376, 5000)) {
            val n = r.read(buf, 0, len)
            assertTrue(n > 0 && n % 188 == 0 && n <= len, "len $len → $n")
            total += n
        }
        // len < 188 still returns bytes (a partial packet), then realigns.
        val small = r.read(buf, 0, 50)
        assertEquals(50, small)
        val rest = r.read(buf, 0, 1000)
        assertEquals(0L, (r.cursor) % 188, "realigned after a partial read: $rest")
        assertEquals(0, r.read(buf, 0, 0))
    }

    @Test
    fun endOfRingAfterSavingStoppedOnlyOnceDrained() {
        val (ring, _) = RingTestKit.ring(capBlocks = 20, poolBlocks = 3)
        append(ring, newSlot(ring), 5 * 188)
        val r = ring.openReader(0, ring.generation)
        ring.stopRecording(RecordStop.FULL_WHILE_PAUSED)
        assertEquals(5 * 188, readAndCheck(r, ring.generation, 5 * 188))
        val e = assertFailsWith<RingEndException> { r.read(ByteArray(188), 0, 188) }
        assertEquals(RecordStop.FULL_WHILE_PAUSED, e.stop)
        // A reader blocked at the head wakes with the end.
        val (ring2, _) = RingTestKit.ring(capBlocks = 20, poolBlocks = 3)
        append(ring2, newSlot(ring2), 188)
        val r2 = ring2.openReader(188, ring2.generation)
        val q = ArrayBlockingQueue<Any>(1)
        val t = Thread { try { r2.read(ByteArray(188), 0, 188) } catch (x: Throwable) { q.put(x) } }
        t.start()
        while (t.state != Thread.State.TIMED_WAITING) Thread.sleep(1)
        ring2.stopRecording(RecordStop.HOLD_LIMIT)
        val got = q.poll(1, TimeUnit.SECONDS)
        assertIs<RingEndException>(got); assertEquals(RecordStop.HOLD_LIMIT, got.stop)
    }

    @Test
    fun aDiskStopAtLiveDoesNotEndTheReader() {
        val (ring, _) = RingTestKit.ring(capBlocks = 20, poolBlocks = 3)
        val slot = newSlot(ring)
        append(ring, slot, 2 * 188)
        val r = ring.openReader(0, ring.generation)
        readAndCheck(r, ring.generation, 2 * 188)
        ring.stopRecording(RecordStop.DISK_ERROR)
        assertEquals(RecordStop.DISK_ERROR, ring.stop)
        assertTrue(!ring.saving)
        append(ring, slot, 4 * BLOCK) // RAM only; the reader keeps up
        // The reader has pinned its cursor, so the RAM window cannot pass it: it reads on.
        readAndCheck(r, ring.generation, (ring.head - r.cursor).toInt())
    }

    @Test
    fun ioErrorsSurfaceAsRingIoException() {
        val (ring, storage) = RingTestKit.ring(capBlocks = 40, poolBlocks = 2)
        val r = ring.openReader(0, ring.generation)
        append(ring, newSlot(ring), 6 * BLOCK) // block 0 left RAM long ago; it is only in the file
        storage.failReads = IOException("EIO")
        val e = assertFailsWith<RingIoException> { r.read(ByteArray(188), 0, 188) }
        assertEquals("EIO", e.cause?.message)
    }

    @Test
    fun aResetFencesOldReaders() {
        val (ring, _) = RingTestKit.ring(capBlocks = 20, poolBlocks = 3)
        append(ring, newSlot(ring), 3 * 188)
        val r = ring.openReader(0, ring.generation)
        ring.reset()
        assertEquals(RecordStop.SESSION_END, assertFailsWith<RingEndException> { r.read(ByteArray(188), 0, 188) }.stop)
        val stale = ring.openReader(0, ring.generation - 1)
        assertEquals(RecordStop.SESSION_END, assertFailsWith<RingEndException> { stale.read(ByteArray(188), 0, 188) }.stop)
        r.close(); r.close() // idempotent
    }
}
