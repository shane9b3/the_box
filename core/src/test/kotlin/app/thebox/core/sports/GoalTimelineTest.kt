package app.thebox.core.sports

import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/design/m6-goal-animation.md §6.2, §7, §8.1 `GoalTimelineTest`: every keyframe instant, mid-segments against an
 * independent reference bezier (1e-3), the fills before and after, the wipe edges, the flash-frequency rule (B3: no 1 s
 * window holds more than 3 flash cycles), the still frames, and an allocation-free [GoalTimeline.sample].
 */
class GoalTimelineTest {
    private val f = GoalFrame()
    private fun at(t: Float) = GoalTimeline.sample(t, f)

    /** Reference CSS cubic-bezier: plain bisection on x(t) in doubles (independent of the solver under test). */
    private fun ref(x1: Double, y1: Double, x2: Double, y2: Double, p: Double): Double {
        if (p <= 0.0) return 0.0
        if (p >= 1.0) return 1.0
        fun b(a: Double, c: Double, t: Double) = 3 * (1 - t) * (1 - t) * t * a + 3 * (1 - t) * t * t * c + t * t * t
        var lo = 0.0
        var hi = 1.0
        repeat(80) { val m = (lo + hi) / 2; if (b(x1, x2, m) < p) lo = m else hi = m }
        return b(y1, y2, (lo + hi) / 2)
    }

    private fun wipe(p: Double) = ref(0.7, 0.0, 0.2, 1.0, p)
    private fun easeInOut(p: Double) = ref(0.42, 0.0, 0.58, 1.0, p)
    private fun streak(p: Double) = ref(0.4, 0.0, 0.2, 1.0, p)
    private fun spring(p: Double) = ref(0.2, 0.8, 0.2, 1.0, p)

    private fun near(expected: Double, actual: Float, what: String, tol: Double = 1e-3) =
        assertTrue(abs(expected - actual) <= tol, "$what: expected $expected, got $actual")

    @Test
    fun theSolverMatchesTheReferenceEverywhere() {
        val curves = listOf(
            GoalTimeline.WIPE to doubleArrayOf(0.7, 0.0, 0.2, 1.0),
            GoalTimeline.EASE_IN_OUT to doubleArrayOf(0.42, 0.0, 0.58, 1.0),
            GoalTimeline.STREAK to doubleArrayOf(0.4, 0.0, 0.2, 1.0),
            GoalTimeline.SPRING to doubleArrayOf(0.2, 0.8, 0.2, 1.0),
        )
        for ((c, k) in curves) for (i in 0..1000) {
            val p = i / 1000.0
            near(ref(k[0], k[1], k[2], k[3], p), c.ease(p.toFloat()), "bezier ${k.toList()} at $p", 1e-4)
        }
    }

    @Test
    fun theWipeEdgesAtEveryKeyframe() {
        // Lead: in 0..216, full, out 3 384..3 600. Panel: hidden to 108, in 108..396, full, out 3 168..3 492.
        at(0f); assertEquals(0f, f.leadFrom); assertEquals(0f, f.leadTo); assertEquals(0f, f.panelTo)
        at(108f); assertEquals(0f, f.panelFrom); assertEquals(0f, f.panelTo, 1e-6f)
        at(216f); assertEquals(0f, f.leadFrom); assertEquals(1f, f.leadTo)
        at(396f); assertEquals(0f, f.panelFrom); assertEquals(1f, f.panelTo)
        at(3_168f); assertEquals(0f, f.panelFrom, 1e-6f); assertEquals(1f, f.panelTo)
        assertEquals(0f, f.leadFrom); assertEquals(1f, f.leadTo)
        at(3_384f); assertEquals(0f, f.leadFrom, 1e-6f); assertEquals(1f, f.leadTo)
        at(3_492f); assertEquals(1f, f.panelFrom); assertEquals(1f, f.panelTo)
        at(3_600f); assertEquals(1f, f.leadFrom); assertEquals(1f, f.leadTo)
    }

    @Test
    fun theWipesEaseEachSegmentOnItsOwn() {
        for (t in listOf(20f, 54f, 108f, 150f, 200f)) near(wipe(t / 216.0), at(t).leadTo, "lead in at $t")
        for (t in listOf(3_400f, 3_492f, 3_550f)) near(wipe((t - 3_384.0) / 216.0), at(t).leadFrom, "lead out at $t")
        for (t in listOf(150f, 252f, 350f)) near(wipe((t - 108.0) / 288.0), at(t).panelTo, "panel in at $t")
        for (t in listOf(3_200f, 3_330f, 3_450f)) near(wipe((t - 3_168.0) / 324.0), at(t).panelFrom, "panel out at $t")
        // Mid-hold: full.
        at(1_800f); assertEquals(0f, f.leadFrom); assertEquals(1f, f.leadTo); assertEquals(0f, f.panelFrom); assertEquals(1f, f.panelTo)
    }

    @Test
    fun theGlowCrossFadesItsMasks() {
        at(0f); assertEquals(0f, f.glowLowAlpha); assertEquals(0f, f.glowPeakAlpha)
        at(540f); near(0.55, f.glowLowAlpha, "low at 540"); near(0.0, f.glowPeakAlpha, "peak at 540")
        at(1_800f); near(0.0, f.glowLowAlpha, "low at 1800"); near(0.8, f.glowPeakAlpha, "peak at 1800")
        at(3_060f); near(0.55, f.glowLowAlpha, "low at 3060"); near(0.0, f.glowPeakAlpha, "peak at 3060")
        at(3_600f); assertEquals(0f, f.glowLowAlpha); assertEquals(0f, f.glowPeakAlpha)
        at(270f); near(0.55 * easeInOut(0.5), f.glowLowAlpha, "low mid-in")
        val m = easeInOut((1_000.0 - 540.0) / 1_260.0)
        at(1_000f); near(0.55 * (1 - m), f.glowLowAlpha, "low mid"); near(0.8 * m, f.glowPeakAlpha, "peak mid")
        val m2 = 1 - easeInOut((2_500.0 - 1_800.0) / 1_260.0)
        at(2_500f); near(0.8 * m2, f.glowPeakAlpha, "peak mid-out")
        at(3_330f); near(0.55 * (1 - easeInOut(0.5)), f.glowLowAlpha, "low mid-out")
        at(5_000f); assertEquals(0f, f.glowLowAlpha); assertEquals(0f, f.glowPeakAlpha)
    }

    @Test
    fun theStreaksRunOnlyInTheirWindows() {
        at(349f); assertFalse(f.streak1On)
        at(350f); assertTrue(f.streak1On); near(-83.0, f.streak1X, "streak 1 start")
        at(850f); near(-83.0 + 591.0 * streak(0.5), f.streak1X, "streak 1 mid")
        at(1_350f); near(508.0, f.streak1X, "streak 1 end")
        at(1_351f); assertFalse(f.streak1On)
        at(1_749f); assertFalse(f.streak2On)
        at(1_750f); assertTrue(f.streak2On); near(-83.0, f.streak2X, "streak 2 start")
        at(2_000f); near(-83.0 + 591.0 * streak(250.0 / 800.0), f.streak2X, "streak 2 mid")
        at(2_550f); near(508.0, f.streak2X, "streak 2 end")
        at(2_551f); assertFalse(f.streak2On)
    }

    @Test
    fun theFlashIsThreeCyclesOf400msWithNoFill() {
        at(419f); assertEquals(0f, f.flashAlpha)
        for (c in 0 until 3) {
            val s = 420f + c * 400f
            near(0.0, at(s).flashAlpha, "cycle $c start")
            near(0.45, at(s + 200f).flashAlpha, "cycle $c peak")
            near(0.45 * easeInOut(0.5), at(s + 100f).flashAlpha, "cycle $c rising mid")
            near(0.45 * (1 - easeInOut(0.5)), at(s + 300f).flashAlpha, "cycle $c falling mid")
        }
        at(1_620f); assertEquals(0f, f.flashAlpha)
        at(2_000f); assertEquals(0f, f.flashAlpha)
    }

    @Test
    fun noOneSecondWindowHoldsMoreThanThreeFlashCycles() {
        // Sample every 1 ms; a cycle is counted at its peak (a local maximum above half the peak alpha).
        val n = 5_000
        val a = FloatArray(n) { at(it.toFloat()).flashAlpha }
        val peaks = ArrayList<Int>()
        for (i in 1 until n - 1) if (a[i] > 0.225f && a[i] >= a[i - 1] && a[i] > a[i + 1]) peaks += i
        assertEquals(3, peaks.size, "peaks at $peaks")
        for (start in 0 until n - 1000) {
            val inWindow = peaks.count { it >= start && it < start + 1000 }
            assertTrue(inWindow <= 3, "window from $start ms holds $inWindow flash cycles")
        }
        // And the frequency itself: peaks 400 ms apart (2.5 Hz).
        for (i in 1 until peaks.size) assertEquals(400, peaks[i] - peaks[i - 1])
    }

    @Test
    fun theLettersDropInStaggered() {
        val delays = floatArrayOf(420f, 500f, 580f, 660f)
        for (i in 0 until 4) {
            val d = delays[i]
            assertEquals(d, GoalTimeline.letterDelay(i))
            at(d - 1f); assertEquals(0f, f.letterAlpha[i]); assertEquals(25f, f.letterDy[i]); assertEquals(1.9f, f.letterScale[i])
            at(d + 0.6f * 420f)
            near(1.0, f.letterAlpha[i], "letter $i α at 60 %"); near(-2.0, f.letterDy[i], "letter $i dy at 60 %")
            near(0.94, f.letterScale[i], "letter $i scale at 60 %")
            val q = spring(0.5)
            at(d + 0.3f * 420f)
            near(q, f.letterAlpha[i], "letter $i α mid 1"); near(25 + (-27) * q, f.letterDy[i], "letter $i dy mid 1")
            near(1.9 + (0.94 - 1.9) * q, f.letterScale[i], "letter $i scale mid 1")
            at(d + 0.8f * 420f)
            near(-2.0 * (1 - q), f.letterDy[i], "letter $i dy mid 2"); near(0.94 + 0.06 * q, f.letterScale[i], "letter $i scale mid 2")
            at(d + 420f); assertEquals(1f, f.letterAlpha[i]); near(0.0, f.letterDy[i], "rest dy"); near(1.0, f.letterScale[i], "rest")
            at(3_000f); assertEquals(1f, f.letterAlpha[i]); assertEquals(0f, f.letterDy[i]); assertEquals(1f, f.letterScale[i])
        }
    }

    @Test
    fun theBadgeSpinsIn() {
        at(0f); assertEquals(0f, f.badgeAlpha); assertEquals(0.3f, f.badgeScale); assertEquals(-120f, f.badgeRotDeg)
        at(450f + 0.7f * 550f)
        near(1.0, f.badgeAlpha, "badge α 70 %"); near(1.12, f.badgeScale, "badge ×70 %"); near(8.0, f.badgeRotDeg, "badge ° 70 %")
        val q = spring(0.5)
        at(450f + 0.35f * 550f)
        near(q, f.badgeAlpha, "α mid"); near(0.3 + 0.82 * q, f.badgeScale, "× mid"); near(-120 + 128 * q, f.badgeRotDeg, "° mid", 2e-3)
        at(450f + 0.85f * 550f); near(1.12 - 0.12 * q, f.badgeScale, "× mid 2"); near(8 * (1 - q), f.badgeRotDeg, "° mid 2")
        at(1_000f); assertEquals(1f, f.badgeAlpha); assertEquals(1f, f.badgeScale); assertEquals(0f, f.badgeRotDeg)
    }

    @Test
    fun theTallySlidesIn() {
        at(899f); assertEquals(0f, f.tallyAlpha); assertEquals(20f, f.tallyDx)
        at(1_125f); near(spring(0.5), f.tallyAlpha, "tally α mid"); near(20 * (1 - spring(0.5)), f.tallyDx, "tally dx mid")
        at(1_350f); assertEquals(1f, f.tallyAlpha); assertEquals(0f, f.tallyDx)
    }

    @Test
    fun thePulseHasNoFill() {
        at(1_149f); assertEquals(1f, f.wordPulse)
        for (c in 0 until 3) {
            val s = 1_150f + c * 500f
            near(1.0, at(s).wordPulse, "pulse $c start")
            near(1.07, at(s + 250f).wordPulse, "pulse $c peak")
            near(1 + 0.07 * easeInOut(0.4), at(s + 100f).wordPulse, "pulse $c rising")
        }
        at(2_650f); assertEquals(1f, f.wordPulse)
    }

    @Test
    fun thePopIsRevealedThenPopsThenEnds() {
        at(3_167f); assertFalse(f.popOn)
        at(3_168f); assertTrue(f.popOn); assertEquals(1.55f, f.popScale); assertEquals(0f, f.popMix)
        at(3_599f); assertTrue(f.popOn); assertEquals(1.55f, f.popScale); assertEquals(0f, f.popMix)
        at(3_600f); near(1.55, f.popScale, "pop 0 %"); near(0.0, f.popMix, "mix 0 %")
        at(3_600f + 360f); near(0.9, f.popScale, "pop 40 %")
        at(3_600f + 630f); near(1.06, f.popScale, "pop 70 %")
        at(3_600f + 180f); near(1.55 + (0.9 - 1.55) * spring(0.5), f.popScale, "pop mid 1")
        at(3_600f + 495f); near(0.9 + 0.16 * spring(0.5), f.popScale, "pop mid 2")
        at(3_600f + 765f); near(1.06 - 0.06 * spring(0.5), f.popScale, "pop mid 3")
        at(3_600f + 450f); near(spring(0.5), f.popMix, "colour eased once over the pop")
        at(4_499f); assertTrue(f.popOn); assertFalse(f.done)
        at(4_500f); assertFalse(f.popOn); assertTrue(f.done); assertEquals(1f, f.popScale)
    }

    @Test
    fun fillsBeforeAndAfter() {
        at(-50f)
        assertEquals(0f, f.leadTo); assertEquals(0f, f.panelTo); assertEquals(0f, f.badgeAlpha); assertEquals(0f, f.tallyAlpha)
        assertFalse(f.streak1On); assertFalse(f.popOn); assertFalse(f.done)
        at(Float.NaN); assertEquals(0f, f.leadTo)
        at(10_000f)
        assertEquals(f.leadFrom, f.leadTo); assertEquals(f.panelFrom, f.panelTo)
        assertEquals(0f, f.glowLowAlpha); assertEquals(0f, f.glowPeakAlpha); assertEquals(0f, f.flashAlpha)
        assertFalse(f.streak1On); assertFalse(f.streak2On); assertFalse(f.popOn); assertTrue(f.done)
        // Fill forwards: the panel's contents are at rest (clipped away by the empty wipe).
        assertEquals(1f, f.badgeAlpha); assertEquals(1f, f.tallyAlpha); assertEquals(1f, f.wordPulse)
    }

    @Test
    fun theStillFrames() {
        val s = GoalFrame()
        for (t in listOf(0f, 1_000f, 3_599f)) {
            GoalTimeline.sampleStill(t, s)
            assertEquals(0f, s.leadTo - s.leadFrom, "no lead at $t")
            assertEquals(0f, s.panelFrom); assertEquals(1f, s.panelTo)
            assertEquals(0f, s.glowLowAlpha); assertEquals(0f, s.glowPeakAlpha); assertEquals(0f, s.flashAlpha)
            assertFalse(s.streak1On); assertFalse(s.streak2On)
            assertEquals(1f, s.badgeAlpha); assertEquals(1f, s.badgeScale); assertEquals(0f, s.badgeRotDeg)
            for (i in 0 until 4) { assertEquals(1f, s.letterAlpha[i]); assertEquals(0f, s.letterDy[i]); assertEquals(1f, s.letterScale[i]) }
            assertEquals(1f, s.wordPulse); assertEquals(1f, s.tallyAlpha); assertEquals(0f, s.tallyDx)
            assertFalse(s.popOn); assertFalse(s.done)
        }
        for (t in listOf(3_600f, 4_000f, 4_499f)) {
            GoalTimeline.sampleStill(t, s)
            assertEquals(s.panelFrom, s.panelTo, "panel gone at $t")
            assertTrue(s.popOn); assertEquals(1f, s.popScale); assertEquals(0f, s.popMix); assertFalse(s.done)
        }
        GoalTimeline.sampleStill(4_500f, s)
        assertFalse(s.popOn); assertTrue(s.done)
    }

    @Test
    fun sampleAllocatesNothing() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        repeat(10_000) { GoalTimeline.sample((it % 4_600).toFloat(), f) }
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        for (i in 0 until 10_000) {
            GoalTimeline.sample((i % 4_600).toFloat() + 0.5f, f)
            GoalTimeline.sampleStill((i % 4_600).toFloat(), f)
        }
        val after = bean.getThreadAllocatedBytes(id)
        assertTrue(after - before < 1024, "allocated ${after - before} bytes")
    }
}
