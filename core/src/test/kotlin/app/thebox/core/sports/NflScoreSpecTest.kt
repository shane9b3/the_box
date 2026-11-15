package app.thebox.core.sports

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/design/m6-nfl-stacked.md §4.3, §4.4, §9.1 `NflScoreSpecTest` (WP-2; the PILL freeze is commit 0's
 * `NflScoreSpecLegacyTest`): every [NflScoreSpec.STACKED] value against the §4.3 STACKED column; the STACKED samples at
 * key instants (streaks, run, arc, posts, flash, badge, letters, points, the row flash, the end, the pair, the still
 * frames); the shared skeleton equal under both specs; ≤ one flash peak per 450 ms under both specs, single and pair;
 * PILL's `rowFlashAlpha` 0 even after a STACKED sample into the same frame.
 */
class NflScoreSpecTest {
    private val s = NflScoreSpec.STACKED
    private val p = NflScoreSpec.PILL

    private fun near(expected: Double, actual: Float, what: String, tol: Double = 1e-3) =
        assertTrue(abs(expected - actual) <= tol, "$what: expected $expected, got $actual")

    /** Reference CSS cubic-bezier (bisection in doubles). */
    private fun ref(x1: Double, y1: Double, x2: Double, y2: Double, q: Double): Double {
        if (q <= 0.0) return 0.0
        if (q >= 1.0) return 1.0
        fun b(a: Double, c: Double, t: Double) = 3 * (1 - t) * (1 - t) * t * a + 3 * (1 - t) * t * t * c + t * t * t
        var lo = 0.0
        var hi = 1.0
        repeat(80) { val m = (lo + hi) / 2; if (b(x1, x2, m) < q) lo = m else hi = m }
        return b(y1, y2, (lo + hi) / 2)
    }
    private fun std(q: Double) = ref(0.4, 0.0, 0.2, 1.0, q)
    private fun roll(q: Double) = ref(0.3, 0.0, 0.3, 1.0, q)
    private fun ease(q: Double) = ref(0.25, 0.1, 0.25, 1.0, q)
    private fun easeOut(q: Double) = ref(0.0, 0.0, 0.58, 1.0, q)

    private fun st(kind: NflScoreKind, t: Float) = NflScoreTimeline.sample(s, kind, t, NflScoreFrame())
    private fun pl(kind: NflScoreKind, t: Float) = NflScoreTimeline.sample(p, kind, t, NflScoreFrame())

    @Test
    fun stackedHoldsTheSection43Values() {
        near(0.55, s.streak1Alpha, "streak 1 α"); near(0.30, s.streak2Alpha, "streak 2 α")
        near(-90.0, s.streakFrom, "streak from"); near(450.0, s.streakTo, "streak to")
        near(-36.0, s.runFrom, "run from"); near(450.0, s.runTo, "run to"); near(38.25, s.runY, "run y")
        near(31.5, s.runPx, "run px"); near(0.45, s.runAlpha, "run α")
        near(13.5, s.arcP0X, "arc p0 x"); near(72.0, s.arcP0Y, "arc p0 y")
        near(135.0, s.arcP1X, "arc p1 x"); near(-4.5, s.arcP1Y, "arc p1 y")
        near(238.5, s.arcP2X, "arc p2 x"); near(23.85, s.arcP2Y, "arc p2 y")
        assertEquals(listOf(1f, 0.75f, 0.45f), listOf(s.arcS0, s.arcS1, s.arcS2))
        assertEquals(listOf(0f, 360f, 720f), listOf(s.arcR0, s.arcR1, s.arcR2))
        near(0.60, s.arcAlpha, "arc α")
        near(234.0, s.postsX, "posts x"); near(22.5, s.postsY, "posts y")
        near(40.5, s.postsW, "posts w"); near(54.0, s.postsH, "posts h"); near(7.2, s.postsDy, "posts dy")
        near(0.40, s.flashAlpha, "flash α")
        near(-22.5, s.badgeDx, "badge dx"); near(1.8, s.badgeMidDx, "badge mid dx")
        near(27.0, s.letterDrop, "letter drop"); near(-2.25, s.letterOvershoot, "letter overshoot")
        near(16.2, s.pointsDx, "points dx"); near(-1.35, s.pointsMidDx, "points mid dx")
        assertEquals(0, s.tabPulseCycles, "no bar pulse")
        assertEquals(1_200f, s.rowFlashMs)
        assertEquals(4_800f, s.endMs, "takeover ends 4 800")
        assertEquals(4_800f, s.pairSecondMs, "the pair's second score starts at 4 800")
        assertEquals(9_600f, s.pairEndMs)
        assertEquals(1_200f, s.stillFrameBMs)
        assertEquals(s.endMs - NflScoreTimeline.TAKEOVER, s.stillFrameBMs, "frame B ends at the end")
        // The posts sit inside the 333 × 108 top part; the run and arc stay in it vertically (§9.2 "no ball pixel outside").
        assertTrue(s.postsX + s.postsW <= SportsConstants.STACKED_NFL_W_MAX && s.postsY + s.postsH <= SportsConstants.STACKED_H)
        assertTrue(s.runY >= 0f && s.runY + s.runPx <= SportsConstants.STACKED_H)
        // PILL is unchanged (its freeze is NflScoreSpecLegacyTest's): the old constants.
        assertEquals(SportsConstants.NFL_STREAK_FROM, p.streakFrom)
        assertEquals(4_500f, p.endMs); assertEquals(4_500f, p.pairSecondMs); assertEquals(9_000f, p.pairEndMs)
        assertEquals(0f, p.rowFlashMs); assertEquals(SportsConstants.NFL_TAB_PULSE_CYCLES, p.tabPulseCycles)
    }

    @Test
    fun theSharedSkeletonIsEqualUnderBothSpecs() {
        // Wipes, glow, the letters' alpha and scale, the badge's alpha / scale / rotation, the pulse, the pop: one skeleton.
        var t = 0f
        while (t <= 4_500f) {
            for (k in NflScoreKind.entries) {
                val a = st(k, t)
                val b = pl(k, t)
                val w = "$k t=$t"
                assertEquals(b.leadFrom, a.leadFrom, w); assertEquals(b.leadTo, a.leadTo, w)
                assertEquals(b.panelFrom, a.panelFrom, w); assertEquals(b.panelTo, a.panelTo, w)
                assertEquals(b.glowLowAlpha, a.glowLowAlpha, w); assertEquals(b.glowPeakAlpha, a.glowPeakAlpha, w)
                assertEquals(b.streak1On, a.streak1On, w); assertEquals(b.streak2On, a.streak2On, w)
                assertEquals(b.runOn, a.runOn, w); assertEquals(b.arcOn, a.arcOn, w); assertEquals(b.postsOn, a.postsOn, w)
                assertEquals(b.badgeAlpha, a.badgeAlpha, w); assertEquals(b.badgeScale, a.badgeScale, w); assertEquals(b.badgeRotDeg, a.badgeRotDeg, w)
                assertEquals(b.wordPulse, a.wordPulse, w)
                assertEquals(b.blockAlpha, a.blockAlpha, w); assertEquals(b.blockScale, a.blockScale, w)
                assertEquals(b.popOn, a.popOn, w); assertEquals(b.popScale, a.popScale, w); assertEquals(b.popMix, a.popMix, w)
                assertEquals(b.ballRotDeg, a.ballRotDeg, w) // the same rotation keyframes (900°; 0 / 360 / 720)
                for (i in 0 until NflScoreFrame.LETTERS) {
                    assertEquals(b.letterAlpha[i], a.letterAlpha[i], w); assertEquals(b.letterScale[i], a.letterScale[i], w)
                }
            }
            t += 5f
        }
    }

    @Test
    fun theStreaksAndTheRun() {
        // Streak 1: 350 + 1 000 Std, −90 → 450; streak 2 from 1 800 over 800.
        near(-90.0, st(NflScoreKind.TOUCHDOWN, 350f).streak1X, "streak 1 at 350")
        near(-90.0 + 540.0 * std(0.5), st(NflScoreKind.TOUCHDOWN, 850f).streak1X, "streak 1 at 850")
        near(450.0, st(NflScoreKind.TOUCHDOWN, 1_350f).streak1X, "streak 1 at 1 350")
        assertFalse(st(NflScoreKind.TOUCHDOWN, 1_351f).streak1On)
        near(-90.0 + 540.0 * std(0.5), st(NflScoreKind.TOUCHDOWN, 2_200f).streak2X, "streak 2 at 2 200")
        // Run: 300 + 1 300 roll, x −36 → 450 at y 38.25, α .45 from 15 % to 85 %.
        val r0 = st(NflScoreKind.TOUCHDOWN, 300f)
        assertTrue(r0.runOn); near(-36.0, r0.ballX, "run x at 300"); near(38.25, r0.ballY, "run y"); near(0.0, r0.ballAlpha, "α at 300")
        val mid = st(NflScoreKind.TOUCHDOWN, 950f)
        near(-36.0 + 486.0 * roll(0.5), mid.ballX, "run x at 950"); near(0.45, mid.ballAlpha, "α mid"); near(900.0 * roll(0.5), mid.ballRotDeg, "rot")
        near(450.0, st(NflScoreKind.TOUCHDOWN, 1_600f).ballX, "run x at 1 600")
        for (k in listOf(NflScoreKind.TWO_POINT, NflScoreKind.SAFETY)) assertTrue(st(k, 900f).runOn, "$k runs")
    }

    @Test
    fun theArcAndThePosts() {
        // Arc: 300 + 1 400 ease, (13.5, 72) ×1 → 50 % (135, −4.5) ×.75 360° → (238.5, 23.85) ×.45 720°.
        val a0 = st(NflScoreKind.FIELD_GOAL, 300f)
        assertTrue(a0.arcOn); near(13.5, a0.ballX, "x0"); near(72.0, a0.ballY, "y0"); near(1.0, a0.ballScale, "s0")
        val a1 = st(NflScoreKind.FIELD_GOAL, 1_000f)
        near(135.0, a1.ballX, "x1"); near(-4.5, a1.ballY, "y1"); near(0.75, a1.ballScale, "s1"); near(360.0, a1.ballRotDeg, "r1")
        near(0.6, a1.ballAlpha, "α1")
        val q = st(NflScoreKind.EXTRA_POINT, 650f)
        near(13.5 + (135.0 - 13.5) * ease(0.5), q.ballX, "x at 25 %"); near(72.0 + (-4.5 - 72.0) * ease(0.5), q.ballY, "y at 25 %")
        val a2 = st(NflScoreKind.FIELD_GOAL, 1_700f)
        near(238.5, a2.ballX, "x2"); near(23.85, a2.ballY, "y2"); near(0.45, a2.ballScale, "s2"); near(720.0, a2.ballRotDeg, "r2")
        near(0.0, a2.ballAlpha, "α2")
        // Posts: in from 200 over 400 ease-out (α 0 → .4, dy 7.2 → 0); hits .4 → .95 on the grid from 1 800.
        near(7.2, st(NflScoreKind.FIELD_GOAL, 200f).postsDy, "dy at 200")
        near(7.2 * (1 - easeOut(0.5)), st(NflScoreKind.FIELD_GOAL, 400f).postsDy, "dy at 400")
        near(0.4 * easeOut(0.5), st(NflScoreKind.FIELD_GOAL, 400f).postsAlpha, "α at 400")
        near(0.0, st(NflScoreKind.FIELD_GOAL, 600f).postsDy, "dy at 600")
        for (peak in listOf(2_025f, 2_475f, 2_925f)) near(0.95, st(NflScoreKind.EXTRA_POINT, peak).postsAlpha, "posts peak $peak")
        near(0.4, st(NflScoreKind.EXTRA_POINT, 3_250f).postsAlpha, "posts after the hits")
        assertFalse(st(NflScoreKind.EXTRA_POINT, 3_600f).postsOn)
        assertFalse(st(NflScoreKind.TOUCHDOWN, 1_000f).postsOn)
    }

    @Test
    fun theFlashBadgeLettersAndPoints() {
        // Flash: 3 × 450 from 450 at .40 (peaks 675, 1 125, 1 575); nothing outside.
        for (peak in listOf(675f, 1_125f, 1_575f)) near(0.40, st(NflScoreKind.TOUCHDOWN, peak).flashAlpha, "flash $peak")
        for (zero in listOf(449f, 900f, 1_350f, 1_800f, 2_000f)) near(0.0, st(NflScoreKind.TOUCHDOWN, zero).flashAlpha, "flash $zero", 1e-4)
        // Badge: 450 + 600 spring, dx −22.5 → 70 % +1.8 → 0.
        near(-22.5, st(NflScoreKind.TOUCHDOWN, 450f).badgeDx, "badge dx at 450")
        near(1.8, st(NflScoreKind.TOUCHDOWN, 870f).badgeDx, "badge dx at 70 %")
        near(0.0, st(NflScoreKind.TOUCHDOWN, 1_050f).badgeDx, "badge dx at rest")
        // Letters: 420 + 55 i, 420 spring; dy +27 → 60 % −2.25 → 0.
        near(27.0, st(NflScoreKind.TOUCHDOWN, 420f).letterDy[0], "letter 0 at 420")
        near(-2.25, st(NflScoreKind.TOUCHDOWN, 420f + 252f).letterDy[0], "letter 0 at 60 %")
        near(-2.25, st(NflScoreKind.TOUCHDOWN, 420f + 55f * 8 + 252f).letterDy[8], "letter 8 at 60 %")
        near(0.0, st(NflScoreKind.TOUCHDOWN, 1_400f).letterDy[8], "letter 8 at rest")
        // +N and tally: 1 100 + 500 spring; dx +16.2 → 70 % −1.35 → 0.
        near(16.2, st(NflScoreKind.TOUCHDOWN, 1_100f).blockDx, "block dx at 1 100")
        near(-1.35, st(NflScoreKind.TOUCHDOWN, 1_450f).blockDx, "block dx at 70 %")
        near(0.0, st(NflScoreKind.TOUCHDOWN, 1_600f).blockDx, "block dx at rest")
        // No bar pulse under STACKED; the Pill's tab pulses.
        var t = 0f
        while (t < 4_800f) { assertEquals(0f, st(NflScoreKind.TOUCHDOWN, t).tabPulse, "t=$t"); t += 12.5f }
        near(1.0, pl(NflScoreKind.TOUCHDOWN, 225f).tabPulse, "PILL's tab pulse peak")
    }

    @Test
    fun theRowFlashAndTheEnd() {
        // 1 at 3 600, eased out to 0 over 1 200; 0 before and after.
        near(0.0, st(NflScoreKind.TOUCHDOWN, 3_599f).rowFlashAlpha, "before", 1e-6)
        near(1.0, st(NflScoreKind.TOUCHDOWN, 3_600f).rowFlashAlpha, "at 3 600")
        near(1.0 - easeOut(0.5), st(NflScoreKind.TOUCHDOWN, 4_200f).rowFlashAlpha, "at 4 200")
        near(0.0, st(NflScoreKind.TOUCHDOWN, 4_800f).rowFlashAlpha, "at 4 800", 1e-6)
        // Monotonic non-increasing through the flash.
        var last = 2f
        var t = 3_600f
        while (t <= 4_800f) { val a = st(NflScoreKind.TOUCHDOWN, t).rowFlashAlpha; assertTrue(a <= last + 1e-6f, "t=$t"); last = a; t += 1f }
        // Pop: from 3 168 (under the lead) at ×1.5, keyframes over 900 to 4 500; done at 4 800.
        assertTrue(st(NflScoreKind.TOUCHDOWN, 3_200f).popOn)
        assertTrue(st(NflScoreKind.TOUCHDOWN, 4_499f).popOn)
        assertFalse(st(NflScoreKind.TOUCHDOWN, 4_500f).popOn)
        assertFalse(st(NflScoreKind.TOUCHDOWN, 4_799f).done)
        assertTrue(st(NflScoreKind.TOUCHDOWN, 4_800f).done)
        assertTrue(st(NflScoreKind.TOUCHDOWN, Float.POSITIVE_INFINITY).done)
        // PILL: no row flash at any instant, even into a frame a STACKED sample left non-zero.
        val f = NflScoreFrame()
        NflScoreTimeline.sample(s, NflScoreKind.TOUCHDOWN, 3_700f, f)
        assertTrue(f.rowFlashAlpha > 0f)
        NflScoreTimeline.sample(p, NflScoreKind.TOUCHDOWN, 3_700f, f)
        assertEquals(0f, f.rowFlashAlpha)
        NflScoreTimeline.sampleStill(s, NflScoreKind.TOUCHDOWN, null, 3_700f, f)
        assertEquals(1f, f.rowFlashAlpha)
        NflScoreTimeline.sampleStill(p, NflScoreKind.TOUCHDOWN, null, 3_700f, f)
        assertEquals(0f, f.rowFlashAlpha)
    }

    @Test
    fun thePairRunsAThen4800B() {
        val f = NflScoreFrame()
        fun pair(t: Float) = NflScoreTimeline.samplePair(s, NflScoreKind.TOUCHDOWN, NflScoreKind.EXTRA_POINT, t, f)
        assertEquals(0, pair(4_799f).segment)
        near(1.0 - easeOut(1_199.0 / 1_200.0), pair(4_799f).rowFlashAlpha, "A's row flash to 4 800", 1e-3)
        assertEquals(1, pair(4_800f).segment)
        assertEquals(NflScoreTimeline.letterCount(NflScoreKind.EXTRA_POINT), pair(4_800f).letterCount)
        // B's own skeleton from 4 800: posts in at 5 000, its flash peaks at 5 475 / 5 925 / 6 375, its release at 8 400.
        near(7.2, pair(5_000f).postsDy, "B posts dy at 5 000")
        for (peak in listOf(5_475f, 5_925f, 6_375f)) near(0.40, pair(peak).flashAlpha, "B flash $peak")
        near(1.0, pair(8_400f).rowFlashAlpha, "B row flash at 8 400")
        assertFalse(pair(9_599f).done)
        assertTrue(pair(9_600f).done)
        // The lead covers the box from 216 of each segment (the no-spoiler hold, D10).
        assertEquals(0f, pair(4_800f + NflScoreTimeline.LEAD_COVERED_MS).leadFrom)
        assertEquals(1f, pair(4_800f + NflScoreTimeline.LEAD_COVERED_MS).leadTo)
    }

    @Test
    fun theStillFrames() {
        val f = NflScoreFrame()
        // One score: A 0–3 600 (panel at rest; posts .4 for XP / FG), B 3 600–4 800 (pop at ×1, row tint), then plain.
        val a = NflScoreTimeline.sampleStill(s, NflScoreKind.FIELD_GOAL, null, 1_000f, f)
        assertEquals(0f, a.panelFrom); assertEquals(1f, a.panelTo); assertTrue(a.postsOn); assertEquals(0.4f, a.postsAlpha)
        assertEquals(0f, a.rowFlashAlpha); assertFalse(a.popOn); assertEquals(0f, a.tabPulse)
        val b = NflScoreTimeline.sampleStill(s, NflScoreKind.FIELD_GOAL, null, 4_000f, f)
        assertTrue(b.popOn); assertEquals(1f, b.popScale); assertEquals(0f, b.popMix); assertEquals(1f, b.rowFlashAlpha)
        assertTrue(NflScoreTimeline.sampleStill(s, NflScoreKind.FIELD_GOAL, null, 4_799f, f).popOn)
        val done = NflScoreTimeline.sampleStill(s, NflScoreKind.FIELD_GOAL, null, 4_800f, f)
        assertTrue(done.done); assertFalse(done.popOn); assertEquals(0f, done.rowFlashAlpha)
        // A pair: A1 0–3 600, B1 –4 800, A2 –8 400, B2 –9 600.
        fun pr(t: Float) = NflScoreTimeline.sampleStill(s, NflScoreKind.TOUCHDOWN, NflScoreKind.TWO_POINT, t, f)
        assertEquals(0, pr(3_599f).segment); assertFalse(pr(3_599f).popOn)
        assertTrue(pr(3_600f).popOn); assertTrue(pr(4_799f).popOn); assertEquals(0, pr(4_799f).segment)
        assertEquals(1, pr(4_800f).segment); assertFalse(pr(4_800f).popOn)
        assertEquals(NflScoreTimeline.letterCount(NflScoreKind.TWO_POINT), pr(4_800f).letterCount)
        assertTrue(pr(8_400f).popOn); assertTrue(pr(9_599f).popOn); assertFalse(pr(9_599f).done)
        assertTrue(pr(9_600f).done)
    }

    /** Every flashing channel's local maxima (the flash, the posts' hit, the tab pulse) of a sampled sequence. */
    private fun peaks(sample: (Float) -> NflScoreFrame, end: Float): List<Float> {
        val out = mutableListOf<Float>()
        val step = 1f
        fun chans(fr: NflScoreFrame) = floatArrayOf(fr.flashAlpha, if (fr.postsAlpha > 0.4001f) fr.postsAlpha else 0f, fr.tabPulse)
        var prev = chans(sample(0f))
        var cur = chans(sample(step))
        var t = step
        while (t + step <= end) {
            val next = chans(sample(t + step))
            for (c in prev.indices) if (cur[c] > 0.01f && cur[c] > prev[c] && cur[c] >= next[c]) out += t
            prev = cur; cur = next; t += step
        }
        // Channels peaking at the same instant (PILL's tab pulse and the flash share the grid) are one flash.
        return out.distinct().sorted()
    }

    @Test
    fun atMostOneFlashPeakPer450MsUnderBothSpecs() {
        for (spec in listOf(p, s)) {
            for (k in NflScoreKind.entries) {
                val ps = peaks({ t -> NflScoreTimeline.sample(spec, k, t, NflScoreFrame()) }, spec.endMs)
                for (i in 1 until ps.size) assertTrue(ps[i] - ps[i - 1] >= 449f, "$spec $k: peaks ${ps[i - 1]} and ${ps[i]}")
                if (spec === s) {
                    val want = if (NflScoreTimeline.runsBall(k)) listOf(675f, 1_125f, 1_575f) else listOf(675f, 1_125f, 1_575f, 2_025f, 2_475f, 2_925f)
                    assertEquals(want, ps, "$k STACKED peaks")
                }
            }
            for (second in listOf(NflScoreKind.EXTRA_POINT, NflScoreKind.TWO_POINT)) {
                val ps = peaks({ t -> NflScoreTimeline.samplePair(spec, NflScoreKind.TOUCHDOWN, second, t, NflScoreFrame()) }, spec.pairEndMs)
                for (i in 1 until ps.size) assertTrue(ps[i] - ps[i - 1] >= 449f, "$spec pair $second: ${ps[i - 1]} and ${ps[i]}")
            }
        }
    }

    @Test
    fun stackedSamplesAreAllocationFree() {
        val f = NflScoreFrame()
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        repeat(2_000) { NflScoreTimeline.samplePair(s, NflScoreKind.TOUCHDOWN, NflScoreKind.EXTRA_POINT, it * 5f, f) }
        val before = bean.getThreadAllocatedBytes(id)
        repeat(20_000) {
            NflScoreTimeline.samplePair(s, NflScoreKind.TOUCHDOWN, NflScoreKind.EXTRA_POINT, it * 0.5f, f)
            NflScoreTimeline.sampleStill(s, NflScoreKind.FIELD_GOAL, null, it * 0.5f, f)
        }
        val after = bean.getThreadAllocatedBytes(id)
        assertTrue(after - before < 4_096, "allocated ${after - before} bytes")
    }
}
