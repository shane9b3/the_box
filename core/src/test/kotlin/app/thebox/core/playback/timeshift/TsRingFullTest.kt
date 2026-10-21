package app.thebox.core.playback.timeshift

import app.thebox.core.playback.timeshift.RingTestKit.BLOCK
import app.thebox.core.playback.timeshift.RingTestKit.append
import app.thebox.core.playback.timeshift.RingTestKit.newSlot
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §17.1 `TsRingFullTest` (A-S3): FULL is decided in claim; paused stops at once; playing waits; PIN_STALL_MS → gap. */
class TsRingFullTest {
    @Test
    fun pausedFullIsAnsweredAtOnceAndTheTailNeverPassesThePin() {
        val (ring, _) = RingTestKit.ring(capBlocks = 6, poolBlocks = 2)
        val slot = newSlot(ring)
        append(ring, slot, 2 * BLOCK, rapEveryPackets = 30)
        ring.setPin(PinOwner.PLAYHEAD, ring.tail)
        val t0 = System.nanoTime()
        val r = append(ring, slot, 20 * BLOCK, rapEveryPackets = 30, paused = true, timeoutMs = 5_000)
        assertEquals(ClaimResult.FULL, r)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 500, "no wait while paused")
        assertEquals(0L, ring.tail)
        assertTrue(ring.head <= ring.capacityBytes)
    }

    @Test
    fun playingFullWaitsForThePinThenGoesOn() {
        val (ring, _) = RingTestKit.ring(capBlocks = 6, poolBlocks = 2)
        val slot = newSlot(ring)
        append(ring, slot, 6 * BLOCK, rapEveryPackets = 30)
        ring.setPin(PinOwner.PLAYHEAD, 0)
        val mover = Thread { Thread.sleep(150); ring.setPin(PinOwner.PLAYHEAD, 2 * BLOCK) }
        mover.start()
        val t0 = System.nanoTime()
        assertEquals(ClaimResult.OK, ring.claim(188, 2_000, false, slot))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms in 100..1_500, "waited $ms ms for the pin")
        assertTrue(ring.fullWaits >= 1)
        mover.join()
        // With no pin movement the claim times out with FULL.
        ring.commit(slot, 0, 0)
        append(ring, slot, ring.capacityBytes - (ring.head - ring.tail), rapEveryPackets = 30)
        assertEquals(ClaimResult.FULL, ring.claim(188, 100, false, slot))
    }

    /** A real writer, flusher and 1× reader on a 10× clock: a 15 MB burst into a 10 MB ring gives no gap. */
    @Test
    fun aBurstWithAOneTimesReaderGivesNoGap() {
        val result = runBurst(stallReaderForRealMs = 0)
        assertFalse(result.listener.has("segment:GAP"), "${result.listener.events}")
        assertEquals(1, result.ring.index.liveSegments)
    }

    @Test
    fun aStuckPinBecomesAGapAfterPinStall() {
        val result = runBurst(stallReaderForRealMs = 2_500) // 25 s on the 10× clock > PIN_STALL_MS
        assertTrue(result.listener.has("segment:GAP"), "${result.listener.events}")
        assertTrue(result.boundary.get() != null, "the reader reached the GAP boundary")
        assertEquals(SegmentReason.GAP, result.boundary.get()!!.reason)
        assertTrue(result.boundary.get()!!.gapMs >= 1_000)
    }

    private class Burst(val ring: TsRingImpl, val listener: RecordingWriterListener, val boundary: AtomicReference<RingBoundaryException?>)

    private fun runBurst(stallReaderForRealMs: Long): Burst {
        val clock = ScaledClock(10)
        val flusher = RingFlusher("test-flush-full")
        val storage = MemoryRingStorage(153 * BLOCK) // ≈ 10 MB
        val ring = TsRingImpl(storage, clock, 16, 8_192, flusher)
        val ts = SyntheticTs(bytesPerSec = 1_000_000)
        val up = FakeTsUpstream(ts, chunk = 64 * 1024)
        val burst = 15L * 1_000_000
        var pacedFrom = 0L
        up.pacing = {
            // After the 15 MB burst: 1 MB/s on the 10× clock = 1 MB per 100 ms real.
            if (up.delivered.get() > burst) {
                if (pacedFrom == 0L) pacedFrom = System.nanoTime()
                while (up.delivered.get() > burst + (System.nanoTime() - pacedFrom) / 100 && !up.isTerminated) Thread.sleep(2)
            }
        }
        val listener = RecordingWriterListener()
        val writer = RingWriterImpl(ring, TsIndexerImpl(), clock, "full")
        writer.start(up, listener)
        val boundary = AtomicReference<RingBoundaryException?>(null)
        val done = AtomicBoolean(false)
        val reader = Thread {
            val r = ring.openReader(0, ring.generation)
            val buf = ByteArray(100_000)
            val t0 = System.nanoTime()
            var stalled = false
            try {
                while (!done.get()) {
                    if (!stalled && stallReaderForRealMs > 0 && System.nanoTime() - t0 > 300_000_000L) {
                        Thread.sleep(stallReaderForRealMs); stalled = true
                    }
                    // 1×: 100 KB per 10 ms real = 1 MB per 100 ms real.
                    r.read(buf, 0, buf.size)
                    ring.setPin(PinOwner.PLAYHEAD, r.cursor)
                    Thread.sleep(10)
                }
            } catch (e: RingBoundaryException) { boundary.set(e) } catch (_: Exception) { }
            r.close()
        }
        reader.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(if (stallReaderForRealMs > 0) 8 else 4)
        while (System.nanoTime() < deadline && up.delivered.get() < burst + 3_000_000L) Thread.sleep(20)
        val elapsedMs = (System.nanoTime() - (deadline - TimeUnit.SECONDS.toNanos(if (stallReaderForRealMs > 0) 8 else 4))) / 1_000_000
        println("burst: delivered ${up.delivered.get()} in $elapsedMs ms, fullWaits ${ring.fullWaits}, poolWaits ${ring.poolWaits}, events ${listener.events} head ${ring.head} tail ${ring.tail} pin ${ring.minPinOrMax()} stop ${ring.stop}")
        if (stallReaderForRealMs > 0) reader.join(5_000)
        done.set(true)
        reader.join(2_000)
        writer.close(); writer.awaitExit(2_000)
        ring.close()
        return Burst(ring, listener, boundary)
    }
}
