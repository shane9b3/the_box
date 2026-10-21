package app.thebox.core.playback.timeshift

import app.thebox.core.playback.timeshift.TimeshiftConstants.LIVE_LANDING_EXTRA_MS
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §17.1 `PcrUnwrapTest`, `PtsOriginTest` (A-S4) and `DirectionStepTest` (A-S4): the index answers what the seek strategy
 * of §6 needs, through the real indexer and ring.
 */
class PcrUnwrapTest {
    @Test
    fun monoIsContinuousAcrossThe33BitWrapAndBehindIsRight() {
        val start = (1L shl 33) - 20L * 90_000 // wraps 20 s in (fake panel `pts_offset`)
        val ts = SyntheticTs(startPcr90k = start, gopFrames = 25)
        val clock = FakeMonoClock(10_000_000L)
        val (ring, _) = RingTestKit.ring(capBlocks = 2_000, poolBlocks = 8, clock = clock)
        val f = TsFeeder(ring, ts, clock)
        f.feedSeconds(60.0)
        val ix = ring.index
        assertEquals(0, ix.segmentAt(ix.newestRap()), "the wrap is not a boundary")
        val monos = ArrayList<Long>()
        var s = ix.oldestRap()
        var k = 0
        while (k < ix.size) {
            val slot = ix.ceilRap(if (monos.isEmpty()) Long.MIN_VALUE else monos.last() + 1)
            if (slot < 0) break
            monos += ix.ptsMonoAt(slot); k++
        }
        assertTrue(monos.size >= 58, "${monos.size} RAPs")
        for (i in 1 until monos.size) assertTrue(abs(monos[i] - monos[i - 1] - 1000) <= 50, "RAP spacing at $i: ${monos[i] - monos[i - 1]}")
        // BEHIND = newest PTS mono − the RAP's mono: correct across the wrap (the RAP 50 s back reads ≈ 50 s).
        val newest = ix.newestPtsMono()
        assertTrue(abs(newest - clock.nowMs()) <= 1_000, "live edge ${newest} vs now ${clock.nowMs()}")
        val back = ix.floorRap(newest - 50_000)
        assertTrue(abs((newest - ix.ptsMonoAt(back)) - 50_000) <= 1_000)
        s = back
        assertTrue(s >= 0)
    }
}

class PtsOriginTest {
    private fun fixture(gop: Int = 25): Triple<TsRingImpl, FakeMonoClock, TsFeeder> {
        val ts = SyntheticTs(gopFrames = gop, leadMs = 700)
        val clock = FakeMonoClock(50_000_000L)
        val (ring, _) = RingTestKit.ring(capBlocks = 2_000, poolBlocks = 8, clock = clock)
        val f = TsFeeder(ring, ts, clock)
        return Triple(ring, clock, f)
    }

    @Test
    fun landedPositionIsWithinOneGopBeforeTheTarget() {
        val (ring, clock, f) = fixture()
        val keyArrival = HashMap<Long, Long>() // keyframe offset → mono clock when its bytes were fed
        f.ts.onKeyframe = { off, _ -> keyArrival[off] = clock.nowMs() }
        f.feedSeconds(90.0)
        val ix = ring.index
        val rnd = Random(4)
        val oldest = ix.ptsMonoAt(ix.oldestRap())
        val newest = ix.newestPtsMono()
        repeat(500) {
            val target = rnd.nextLong(oldest + 1_000, newest)
            val slot = ix.floorRap(target)
            val landed = ix.ptsMonoAt(slot)
            assertTrue(landed <= target, "landed $landed after target $target")
            assertTrue(target - landed <= 1_000 + 50, "landed ${target - landed} ms before the target (GOP 1 s + 50 ms)")
            // The PTS-based mono of a RAP is the arrival time of its bytes (the 700 ms PTS−PCR lead is removed).
            val arrived = keyArrival[ix.offsetAt(slot)] ?: error("no keyframe at ${ix.offsetAt(slot)}")
            assertTrue(abs(arrived - landed) <= 60, "RAP mono $landed vs its arrival $arrived")
        }
    }

    @Test
    fun backToLiveLandsAtMostLiveLandingPlusOneSecondBehind() {
        val (ring, _, f) = fixture()
        f.feedSeconds(40.0)
        val ix = ring.index
        val startMs = 2_000L // BufferProfile.startMs of the tune
        val liveLandingMs = maxOf(startMs + LIVE_LANDING_EXTRA_MS, 1_500L)
        val newest = ix.newestPtsMono()
        val slot = ix.floorRap(newest - liveLandingMs)
        val behind = newest - ix.ptsMonoAt(slot)
        assertTrue(behind >= liveLandingMs, "lands at least liveLandingMs behind")
        assertTrue(behind <= liveLandingMs + 1_000, "BEHIND after Back to live: $behind")
    }
}

class DirectionStepTest {
    @Test
    fun tenSecondGopForwardAlwaysAdvancesBackwardFloors() {
        val ts = SyntheticTs(gopFrames = 250) // 10 s GOP, anchors every 2 s in between
        val clock = FakeMonoClock(7_000_000L)
        val (ring, _) = RingTestKit.ring(capBlocks = 2_000, poolBlocks = 8, clock = clock)
        TsFeeder(ring, ts, clock).feedSeconds(120.0)
        val ix = ring.index
        var landed = ix.ptsMonoAt(ix.floorRap(ix.newestPtsMono() - 100_000))
        // → +10 s: ceil(landed + 10 s); if that is not later than landed, the next RAP after it (§6). Always advances.
        var steps = 0
        while (true) {
            var slot = ix.ceilRap(landed + 10_000)
            if (slot < 0) break
            if (ix.ptsMonoAt(slot) <= landed) slot = ix.ceilRap(landed + 1)
            val next = ix.ptsMonoAt(slot)
            assertTrue(next > landed, "→ is never a no-op")
            assertTrue(next - landed in 9_900..10_100, "one GOP: ${next - landed}")
            landed = next; steps++
        }
        assertTrue(steps >= 8)
        // A short → (+3 s inside a 10 s GOP) still advances to the next RAP.
        val mid = ix.ptsMonoAt(ix.floorRap(ix.newestPtsMono() - 60_000))
        val short = ix.ceilRap(mid + 3_000)
        assertTrue(ix.ptsMonoAt(short) > mid)
        // ← floors: −3 s lands on the RAP at or before the target, never after it.
        val back = ix.floorRap(mid - 3_000)
        assertTrue(ix.ptsMonoAt(back) <= mid - 3_000)
        assertTrue(mid - 3_000 - ix.ptsMonoAt(back) <= 10_050)
        // Anchors are in the index but never chosen.
        assertTrue(ix.size > 30)
        assertEquals(RingFlags.RAP, ix.flagsAt(back) and RingFlags.RAP)
    }
}
