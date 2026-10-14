package app.thebox.core.playback.ts

import app.thebox.core.playback.ts.TsTestSupport.PKT
import app.thebox.core.playback.ts.TsTestSupport.cc
import app.thebox.core.playback.ts.TsTestSupport.concat
import app.thebox.core.playback.ts.TsTestSupport.drain
import app.thebox.core.playback.ts.TsTestSupport.hasPayload
import app.thebox.core.playback.ts.TsTestSupport.packet
import app.thebox.core.playback.ts.TsTestSupport.pid
import app.thebox.core.playback.ts.TsTestSupport.res
import app.thebox.core.playback.ts.TsTestSupport.restartCcs
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §5.3 WP-B `TsPacketAligner`, including B1 (CC gap per PID after a resync) and S1 (post-splice hold). */
class TsPacketAlignerTest {
    private val ts = res("ts/h264_aac_2s.ts")

    private fun feed(a: TsPacketAlignerImpl, data: ByteArray, chunk: Int, readLen: Int = 4096): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var off = 0
        while (off < data.size) {
            val n = minOf(chunk, data.size - off)
            a.write(data, off, n)
            off += n
            out.write(drain(a, readLen))
        }
        return out.toByteArray()
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 187, 188, 189, 4096, 16_384, 100_000])
    fun arbitraryChunkSizesReproduceThePackets(chunk: Int) {
        assertEquals(0, ts.size % PKT)
        assertContentEquals(ts, feed(TsPacketAlignerImpl(), ts, chunk))
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 97, 500, 4096])
    fun garbagePrefixIsDroppedAndThePacketsFollow(chunk: Int) {
        val a = TsPacketAlignerImpl()
        assertContentEquals(ts, feed(a, res("ts/garbage_prefix.ts"), chunk))
        assertEquals(97L, a.bytesSkipped)
    }

    @Test
    fun neverEmitsAPartialPacketForReadsOfAtLeast188() {
        val a = TsPacketAlignerImpl()
        a.write(ts, 0, 5_000)
        val buf = ByteArray(1_000)
        while (true) {
            val n = a.read(buf, 0, buf.size)
            if (n == 0) break
            assertEquals(0, n % PKT, "read $n")
            assertEquals(0x47.toByte(), buf[0])
        }
        // 5,000 bytes hold 26 whole packets; the partial 27th is not readable.
        assertEquals(0, a.available())
    }

    @Test
    fun smallReadsMayReturnPartsOfAPacket() {
        val a = TsPacketAlignerImpl()
        a.write(ts, 0, 3 * PKT)
        assertContentEquals(ts.copyOf(3 * PKT), drain(a, 50))
    }

    @Test
    fun onlyWholePacketsBecomeAvailable() {
        val a = TsPacketAlignerImpl()
        assertEquals(0, a.write(ts, 0, 2 * PKT)) // the third sync byte (offset 376) is not there yet: no lock
        assertEquals(2 * PKT, a.write(ts, 2 * PKT, 1))
        assertEquals(2 * PKT, a.write(ts, 2 * PKT + 1, PKT - 2))
        assertEquals(3 * PKT, a.write(ts, 3 * PKT - 1, 1))
    }

    @Test
    fun resyncDropsThePartialPacketAndRelocks() {
        val a = TsPacketAlignerImpl()
        a.write(ts, 0, 10 * PKT + 60)
        val first = drain(a)
        assertContentEquals(ts.copyOf(10 * PKT), first)
        assertEquals(60, a.resync())
        // New connection: some garbage, then packets from the middle of the stream.
        val second = concat(ByteArray(33) { 0x11 }, ts.copyOfRange(50 * PKT, 60 * PKT))
        a.write(second, 0, second.size)
        assertEquals(0, a.available()) // held until releaseHeld
        a.releaseHeld()
        val out = drain(a)
        assertEquals(10 * PKT, out.size)
        for (i in 0 until 10) {
            val exp = ts.copyOfRange((50 + i) * PKT, (51 + i) * PKT)
            val got = out.copyOfRange(i * PKT, (i + 1) * PKT)
            exp[3] = (exp[3].toInt() and 0xF0).toByte(); got[3] = (got[3].toInt() and 0xF0).toByte()
            assertContentEquals(exp, got, "packet $i (CC masked)")
        }
    }

    @Test
    fun heldPacketsAreNotReadableUntilReleasedAndDropHeldDiscardsThem() {
        val a = TsPacketAlignerImpl()
        a.write(ts, 0, 20 * PKT)
        drain(a)
        a.resync()
        a.write(ts, 20 * PKT, 10 * PKT + 5)
        assertEquals(0, a.available())
        assertEquals(10 * PKT + 5, a.heldBytes())
        assertEquals(0, a.read(ByteArray(4096), 0, 4096))
        a.dropHeld()
        assertEquals(0, a.available())
        assertEquals(0, a.heldBytes())
        // The partial packet survives a drop; the next bytes complete it.
        a.write(ts, 30 * PKT + 5, PKT - 5)
        assertEquals(PKT, a.available())
    }

    @Test
    fun theHoldGrowsTheBufferBeyondItsInitialSize() {
        val a = TsPacketAlignerImpl()
        a.write(ts, 0, 3 * PKT)
        drain(a)
        a.resync()
        val big = ts.copyOfRange(3 * PKT, ts.size)
        var off = 0
        while (off < big.size) {
            val n = minOf(16_384, big.size - off)
            a.write(big, off, n)
            off += n
        }
        assertEquals(big.size, a.heldBytes())
        a.releaseHeld()
        assertEquals(big.size, drain(a).size)
    }

    @Test
    fun theListenerSeesEveryValidatedPacketOnceHeldOrNot() {
        var count = 0
        val a = TsPacketAlignerImpl(onPacket = { _, _ -> count++ })
        a.write(ts, 0, 100 * PKT)
        a.resync()
        a.write(ts, 200 * PKT, 50 * PKT)
        assertEquals(150, count)
        a.dropHeld()
        assertEquals(150, count)
    }

    /**
     * (reviewed B1) For every restart CC the server may pick, the first payload packet of each PID after the splice
     * has CC = last emitted + 2, and every later packet of that PID (adaptation-only included) keeps the same offset.
     */
    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15])
    fun ccGapOnEveryPidForEveryRestartCc(restart: Int) {
        val a = TsPacketAlignerImpl()
        val partA = ts.copyOf(137 * PKT + 71)
        a.write(partA, 0, partA.size)
        val outA = drain(a)
        val lastCc = HashMap<Int, Int>()
        forEachPacket(outA) { off -> if (hasPayload(outA, off)) lastCc[pid(outA, off)] = cc(outA, off) }

        a.resync()
        val partB = restartCcs(ts.copyOfRange(300 * PKT, ts.size), restart)
        a.write(partB, 0, partB.size)
        a.releaseHeld()
        val outB = drain(a)
        assertEquals(partB.size, outB.size)

        val offset = HashMap<Int, Int>()
        val seenPayload = HashSet<Int>()
        forEachPacket(outB) { off ->
            val p = pid(outB, off)
            if (p == 0x1FFF) return@forEachPacket
            val inCc = cc(partB, off)
            val outCc = cc(outB, off)
            if (hasPayload(outB, off) && p !in seenPayload && lastCc.containsKey(p)) {
                assertEquals((lastCc.getValue(p) + 2) and 0xF, outCc, "pid $p first payload after splice, restart $restart")
                seenPayload += p
                offset[p] = (outCc - inCc) and 0xF
            } else if (offset.containsKey(p)) {
                assertEquals(offset.getValue(p), (outCc - inCc) and 0xF, "pid $p keeps its CC offset")
            }
            // Everything but the CC nibble is untouched.
            assertEquals(partB[off + 3].toInt() and 0xF0, outB[off + 3].toInt() and 0xF0)
        }
        assertTrue(seenPayload.containsAll(listOf(0x0000, 0x1000, 0x100, 0x101)), "PAT, PMT, video, audio all spliced: $seenPayload")
    }

    @Test
    fun adaptationOnlyPacketsKeepTheOffsetAndNullPacketsAreUntouched() {
        val a = TsPacketAlignerImpl()
        val before = concat(packet(0x100, 4), packet(0x100, 5), packet(0x100, 6), packet(0x1FFF, 9))
        a.write(before, 0, before.size)
        drain(a)
        a.resync()
        val after = concat(
            packet(0x100, 3, payload = false, pcr90k = 1), // adaptation-only before the first payload: old offset (0)
            packet(0x100, 7), packet(0x100, 7, payload = false, pcr90k = 2), packet(0x100, 8),
            packet(0x1FFF, 3), packet(0x1FFF, 3), packet(0x1FFF, 3),
        )
        a.write(after, 0, after.size)
        a.releaseHeld()
        val out = drain(a)
        val ccs = (0 until out.size / PKT).map { cc(out, it * PKT) }
        // First payload after the splice: 6 + 2 = 8 (offset +1); the adaptation-only repeat and the next packet follow it.
        assertEquals(listOf(3, 8, 8, 9, 3, 3, 3), ccs)
    }

    @Test
    fun aSecondSpliceBuildsOnTheLastPublishedCcNotOnDroppedPackets() {
        val a = TsPacketAlignerImpl()
        val first = concat(packet(0x100, 0), packet(0x100, 1), packet(0x100, 2), packet(0x100, 3))
        a.write(first, 0, first.size)
        drain(a) // last published CC = 3
        a.resync()
        val dropped = concat(*Array(13) { packet(0x100, it) }) // would have been 5, 6, ... if published
        a.write(dropped, 0, dropped.size)
        a.dropHeld()
        a.resync()
        val again = concat(packet(0x100, 9), packet(0x100, 10), packet(0x100, 11))
        a.write(again, 0, again.size)
        a.releaseHeld()
        val out = drain(a)
        assertEquals(listOf(5, 6, 7), (0 until 3).map { cc(out, it * PKT) })
    }

    private inline fun forEachPacket(b: ByteArray, f: (Int) -> Unit) {
        var off = 0
        while (off + PKT <= b.size) { f(off); off += PKT }
    }
}
