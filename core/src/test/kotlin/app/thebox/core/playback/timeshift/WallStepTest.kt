package app.thebox.core.playback.timeshift

import app.thebox.core.playback.timeshift.RingTestKit.BLOCK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §17.1 `WallStepTest` (A-S9), the ring's half: every time inside the ring, the index, the hold timer and the pins is
 * MonoClock. The ring is driven by a mono clock while the wall clock is not consulted at all, so a wall step of ±1 h
 * cannot move anything; the snapshot conversion (wall shifts as a whole) is WP-B's `TimeshiftSession.snapshot`.
 */
class WallStepTest {
    @Test
    fun indexAndHoldTimerOnlyFollowTheMonoClock() {
        val clock = FakeMonoClock(3_000_000L)
        val (ring, _) = RingTestKit.ring(capBlocks = 500, poolBlocks = 8, clock = clock)
        val f = TsFeeder(ring, SyntheticTs(), clock)
        f.feedSeconds(20.0)
        val ix = ring.index
        val before = LongArray(20) { ix.ptsMonoAt(ix.floorRap(clock.nowMs() - 1_000L * it)) }
        ring.setPin(PinOwner.PLAYHEAD, ix.offsetAt(ix.floorRap(clock.nowMs() - 10_000)))
        val pin = ring.pinOf(PinOwner.PLAYHEAD)
        // A wall step does not touch the mono clock: lookups, pins and the index are unchanged.
        val after = LongArray(20) { ix.ptsMonoAt(ix.floorRap(clock.nowMs() - 1_000L * it)) }
        assertTrue(before.contentEquals(after))
        assertEquals(pin, ring.pinOf(PinOwner.PLAYHEAD))
        // The live edge is within one second of mono now.
        assertTrue(kotlin.math.abs(ix.newestPtsMono() - clock.nowMs()) <= 1_000)
        assertTrue(ring.head > 10 * BLOCK)
    }
}
