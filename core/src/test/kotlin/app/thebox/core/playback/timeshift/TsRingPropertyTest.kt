package app.thebox.core.playback.timeshift

import app.thebox.core.playback.timeshift.RingTestKit.BLOCK
import app.thebox.core.playback.timeshift.RingTestKit.append
import app.thebox.core.playback.timeshift.RingTestKit.checkInvariants
import app.thebox.core.playback.timeshift.RingTestKit.newSlot
import app.thebox.core.playback.timeshift.RingTestKit.readAndCheck
import app.thebox.core.playback.timeshift.TimeshiftConstants.TS_PACKET
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * §17.1 `TsRingPropertyTest` (seeded, 10,000 runs): random packet runs, pins, readers at random positions, mode and
 * pause changes, resets. After every operation the §3.2 invariants hold, and every byte any reader gets equals the byte
 * written at that logical offset (no torn packet, ever).
 */
class TsRingPropertyTest {
    @Test
    fun tenThousandSeededRuns() {
        val rnd = Random(0x7B0C5)
        var runs = 0
        var fulls = 0
        var bytesRead = 0L
        while (runs < RUNS) {
            val capBlocks = 3 + rnd.nextInt(6)
            val poolBlocks = 2 + rnd.nextInt(3)
            val (ring, storage) = RingTestKit.ring(capBlocks, poolBlocks, indexEntries = 64 + rnd.nextInt(512))
            repeat(RUNS_PER_RING) {
                if (runs >= RUNS) return@repeat
                runs++
                val gen = ring.reset()
                val slot = newSlot(ring)
                val readers = ArrayList<RingReader>()
                var paused = false
                ring.mode = if (rnd.nextInt(5) == 0) BufferMode.PAUSE_ONLY else BufferMode.PERSIST
                val rapEvery = 10 + rnd.nextInt(300)
                repeat(OPS_PER_RUN) {
                    when (rnd.nextInt(100)) {
                        in 0 until 40 -> {
                            val bytes = if (rnd.nextInt(10) < 7) (1 + rnd.nextInt(60)) * TS_PACKET.toLong() else rnd.nextLong(1, BLOCK + 1)
                            val r = append(ring, slot, bytes, rapEveryPackets = rapEvery, paused = paused)
                            if (r == ClaimResult.FULL) {
                                fulls++
                                assertTrue(ring.minPinOrMax() != Long.MAX_VALUE, "FULL without a pin")
                            } else assertEquals(ClaimResult.OK, r)
                        }
                        in 40 until 50 -> {
                            val off = pick(rnd, ring.tail, ring.head)
                            ring.setPin(PinOwner.PLAYHEAD, off)
                            assertTrue(ring.pinOf(PinOwner.PLAYHEAD) in ring.tail..ring.head)
                        }
                        in 50 until 55 -> ring.clearPin(PinOwner.PLAYHEAD)
                        in 55 until 65 -> if (readers.size < 3) readers += ring.openReader(pick(rnd, ring.tail, ring.head), gen)
                        in 65 until 88 -> if (readers.isNotEmpty()) {
                            val r = readers[rnd.nextInt(readers.size)]
                            val avail = ring.head - r.cursor
                            if (avail > 0) {
                                val n = minOf(avail, 1L + rnd.nextInt(3 * BLOCK.toInt())).toInt()
                                bytesRead += readAndCheck(r, gen, n)
                            }
                        }
                        in 88 until 93 -> if (readers.isNotEmpty()) readers.removeAt(rnd.nextInt(readers.size)).close()
                        in 93 until 97 -> paused = !paused
                        else -> {
                            // Invariant 2 in full: every byte of [tail, head) reads back as written.
                            val r = ring.openReader(ring.tail, gen)
                            bytesRead += readAndCheck(r, gen, (ring.head - ring.tail).toInt())
                            r.close()
                        }
                    }
                    checkInvariants(ring, storage, gen)
                }
                // A reader of the old generation is fenced (A-S1).
                if (readers.isNotEmpty()) {
                    ring.reset()
                    assertFailsWith<RingEndException> { readers[0].read(ByteArray(TS_PACKET), 0, TS_PACKET) }.also {
                        assertEquals(RecordStop.SESSION_END, it.stop)
                    }
                }
                readers.forEach { it.close() }
            }
            ring.close()
        }
        assertEquals(RUNS, runs)
        assertTrue(bytesRead > 0)
        assertTrue(fulls > 0, "the runs should hit FULL at least once")
    }

    private fun pick(rnd: Random, from: Long, to: Long): Long {
        if (to <= from) return from
        val packets = (to - from) / TS_PACKET
        return from + rnd.nextLong(0, packets + 1) * TS_PACKET
    }

    private companion object {
        const val RUNS = 10_000
        const val RUNS_PER_RING = 200
        const val OPS_PER_RUN = 24
    }
}
