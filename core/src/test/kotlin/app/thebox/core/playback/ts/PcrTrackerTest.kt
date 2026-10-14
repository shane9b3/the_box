package app.thebox.core.playback.ts

import app.thebox.core.playback.ts.TsTestSupport.PKT
import app.thebox.core.playback.ts.TsTestSupport.packet
import app.thebox.core.playback.ts.TsTestSupport.pcr
import app.thebox.core.playback.ts.TsTestSupport.res
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §5.3 WP-B `PcrTracker`: PCR PID choice, continuity across a gap, jumps with sign, 33-bit wrap, no PCR. */
class PcrTrackerTest {
    private val wrap = 1L shl 33

    private fun PcrTrackerImpl.feed(pkt: ByteArray, now: Long) = onPacket(pkt, 0, now)

    @Test
    fun picksThePcrPidOfTheFixture() {
        val ts = res("ts/h264_aac_2s.ts")
        val t = PcrTrackerImpl()
        var off = 0
        while (off < ts.size) { t.onPacket(ts, off, 0); off += PKT }
        assertEquals(0x100, t.pid)
    }

    @Test
    fun onlyTheFirstPcrPidIsTracked() {
        val t = PcrTrackerImpl()
        t.feed(packet(0x100, 0, pcr90k = 90_000), 1_000)
        t.markReconnect(1_000)
        t.feed(packet(0x200, 0, pcr90k = 900_000_000), 1_000) // another PID's PCR is ignored
        assertTrue(t.awaitingPcr())
        assertNull(t.pendingJumpMs())
        t.feed(packet(0x100, 1, pcr90k = 90_000 + 90 * 50), 1_050)
        assertFalse(t.awaitingPcr())
        assertNull(t.pendingJumpMs())
    }

    @Test
    fun continuousAfterAOneSecondGapIsNoJump() {
        val t = PcrTrackerImpl()
        t.feed(packet(0x100, 0, pcr90k = 5_000_000), 10_000)
        t.markReconnect(10_400)
        t.feed(packet(0x100, 1, pcr90k = 5_000_000 + 90_000), 11_000)
        assertFalse(t.awaitingPcr())
        assertNull(t.pendingJumpMs())
    }

    @Test
    fun serverResendingItsBufferWithinTheToleranceIsNoJump() {
        val t = PcrTrackerImpl()
        t.feed(packet(0x100, 0, pcr90k = 5_000_000), 10_000)
        t.markReconnect(10_000)
        // 1.9 s of wall time passed, the server restarts 0.0 s later than our last PCR: delta -1,900 ms.
        t.feed(packet(0x100, 1, pcr90k = 5_000_000), 11_900)
        assertNull(t.pendingJumpMs())
    }

    @Test
    fun forwardJumpOfTenSecondsIsPositive() {
        val t = PcrTrackerImpl()
        t.feed(packet(0x100, 0, pcr90k = 1_000_000), 0)
        t.markReconnect(0)
        t.feed(packet(0x100, 1, pcr90k = 1_000_000 + 90L * 10_000), 0)
        assertEquals(10_000L, t.pendingJumpMs())
    }

    @Test
    fun backwardJumpOfAnHourIsNegative() {
        val t = PcrTrackerImpl()
        val last = 90L * 7_200_000 // 2 h
        t.feed(packet(0x100, 0, pcr90k = last), 50_000)
        t.markReconnect(50_000)
        t.feed(packet(0x100, 1, pcr90k = last - 90L * 3_600_000), 50_000)
        assertEquals(-3_600_000L, t.pendingJumpMs())
    }

    @Test
    fun thresholdIsStrictlyMoreThanTwoSeconds() {
        fun delta(ms: Long): Long? {
            val t = PcrTrackerImpl()
            t.feed(packet(0x100, 0, pcr90k = 9_000_000), 0)
            t.markReconnect(0)
            t.feed(packet(0x100, 1, pcr90k = 9_000_000 + 90 * ms), 0)
            return t.pendingJumpMs()
        }
        assertNull(delta(2_000))
        assertNull(delta(-2_000))
        assertEquals(2_001L, delta(2_001))
        assertEquals(-2_001L, delta(-2_001))
    }

    @Test
    fun wrapNearTwoToThe33IsNoFalseJump() {
        val t = PcrTrackerImpl()
        val last = wrap - 90L * 100 // 100 ms before the wrap
        t.feed(packet(0x100, 0, pcr90k = last), 1_000)
        t.markReconnect(1_500)
        t.feed(packet(0x100, 1, pcr90k = (last + 90L * 1_000) % wrap), 2_000) // 0.9 s after the wrap
        assertNull(t.pendingJumpMs())
        // And a real jump across the wrap is still seen with its sign.
        t.markReconnect(2_000)
        t.feed(packet(0x100, 2, pcr90k = wrap - 90L * 5_000), 2_000)
        assertEquals(-5_900L, t.pendingJumpMs())
    }

    @Test
    fun withoutAnyPcrTheCheckIsDisabled() {
        val t = PcrTrackerImpl()
        t.feed(packet(0x101, 0), 0)
        t.markReconnect(0)
        assertFalse(t.awaitingPcr())
        assertNull(t.pendingJumpMs())
    }

    @Test
    fun markReconnectClearsAnOldVerdict() {
        val t = PcrTrackerImpl()
        t.feed(packet(0x100, 0, pcr90k = 0), 0)
        t.markReconnect(0)
        t.feed(packet(0x100, 1, pcr90k = 90L * 60_000), 0)
        assertNotNull(t.pendingJumpMs())
        t.markReconnect(0)
        assertNull(t.pendingJumpMs())
        assertTrue(t.awaitingPcr())
    }

    @Test
    fun pcrJumpFixtureIsSeenAtTheSplice() {
        val bin = res("ts/pcr_jump.bin")
        val half = res("ts/h264_aac_2s.ts").size
        val t = PcrTrackerImpl()
        var off = 0
        var lastPcr = -1L
        while (off < half) { t.onPacket(bin, off, 0); pcr(bin, off)?.let { lastPcr = it }; off += PKT }
        assertEquals(0x100, t.pid)
        t.markReconnect(0)
        var firstAfter = -1L
        while (off < bin.size && t.awaitingPcr()) { pcr(bin, off)?.let { if (firstAfter < 0) firstAfter = it }; t.onPacket(bin, off, 0); off += PKT }
        val jump = assertNotNull(t.pendingJumpMs())
        assertEquals((firstAfter - lastPcr) / 90, jump)
        assertTrue(jump in 8_000..8_200, "jump $jump ms (README: PCR 2.643 s → 10.723 s)")
    }
}
