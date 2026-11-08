package app.thebox.core.sports

import app.thebox.core.sports.SportsConstants as K
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/design/m6-power-play.md §3.3, §3.6, §9.1 `PowerPlayTimelineTest`: every instant of the §3.3 table, mid-segments
 * against an independent reference bezier (1e-3), the PULSE, interrupted transitions starting from `from`, no shine on a
 * reversed open, STILL, each track writing only its own channels, and an allocation-free [PowerPlayTimeline.sample].
 */
class PowerPlayTimelineTest {
    private val closed = PpFrame().also { PowerPlayTimeline.sample(PpTrack.TAB, PpTransition.STILL, 0f, PpFrame(), false, it) }
    private val open = PpFrame().also {
        PowerPlayTimeline.sample(PpTrack.TAB, PpTransition.STILL, 0f, PpFrame(), true, it)
        PowerPlayTimeline.sample(PpTrack.PILL, PpTransition.STILL, 0f, PpFrame(), true, it)
    }
    private val out = PpFrame()

    private fun tab(kind: PpTransition, t: Float, from: PpFrame = if (kind == PpTransition.CLOSE) open else closed) =
        PowerPlayTimeline.sample(PpTrack.TAB, kind, t, from, false, out)

    private fun pill(kind: PpTransition, t: Float, from: PpFrame) = PowerPlayTimeline.sample(PpTrack.PILL, kind, t, from, false, out)

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

    private fun ease(p: Double) = ref(0.25, 0.1, 0.25, 1.0, p)
    private fun easeInOut(p: Double) = ref(0.42, 0.0, 0.58, 1.0, p)
    private fun snap(p: Double) = ref(0.2, 0.8, 0.2, 1.0, p)
    private fun std(p: Double) = ref(0.4, 0.0, 0.2, 1.0, p)

    private fun near(expected: Double, actual: Float, what: String, tol: Double = 1e-3) =
        assertTrue(abs(expected - actual) <= tol, "$what: expected $expected, got $actual")

    private fun lerp(a: Float, b: Float, e: Double) = a + (b - a) * e

    @Test
    fun theRestStatesAreTheTablesEnds() {
        assertEquals(0f, closed.slide); assertEquals(0f, closed.shadowAlpha); assertEquals(0f, closed.textAlpha)
        assertEquals(-K.PP_TEXT_DY, closed.textDy); assertFalse(closed.shineOn); assertEquals(1f, closed.labelScale)
        assertTrue(closed.tabClosed)
        assertEquals(1f, open.slide); assertEquals(K.PP_SHADOW_ALPHA, open.shadowAlpha); assertEquals(1f, open.textAlpha)
        assertEquals(0f, open.textDy); assertFalse(open.shineOn); assertEquals(1f, open.labelScale); assertFalse(open.tabClosed)
        assertEquals(1f, open.tint); assertEquals(1f, open.halo); assertFalse(open.pillClosed)
    }

    @Test
    fun theOpenAtEveryInstantAndMidSegment() {
        // Slide 0 / 550 snap; shadow 0 / 400 ease; text alpha 380 / 300 ease; text offset 380 / 350 ease.
        for (i in 0..1900 step 5) {
            val t = i.toFloat()
            tab(PpTransition.OPEN, t)
            near(snap((t / 550.0).coerceIn(0.0, 1.0)), out.slide, "slide at $t")
            near(lerp(0f, 0.5f, ease((t / 400.0).coerceIn(0.0, 1.0))), out.shadowAlpha, "shadow at $t")
            near(ease(((t - 380) / 300.0).coerceIn(0.0, 1.0)), out.textAlpha, "text alpha at $t")
            near(lerp(-4f, 0f, ease(((t - 380) / 350.0).coerceIn(0.0, 1.0))), out.textDy, "text dy at $t")
            assertFalse(out.tabClosed, "open at $t is not closed")
        }
        // The keyframe instants exactly.
        tab(PpTransition.OPEN, 0f); assertEquals(0f, out.slide); assertEquals(0f, out.textAlpha); assertEquals(-4f, out.textDy)
        tab(PpTransition.OPEN, 380f); assertEquals(0f, out.textAlpha); assertEquals(-4f, out.textDy)
        tab(PpTransition.OPEN, 400f); assertEquals(0.5f, out.shadowAlpha)
        tab(PpTransition.OPEN, 550f); assertEquals(1f, out.slide)
        tab(PpTransition.OPEN, 680f); assertEquals(1f, out.textAlpha)
        tab(PpTransition.OPEN, 730f); assertEquals(0f, out.textDy)
        tab(PpTransition.OPEN, 1_900f)
        assertEquals(1f, out.slide); assertEquals(0.5f, out.shadowAlpha); assertEquals(1f, out.labelScale); assertFalse(out.shineOn)
        tab(PpTransition.OPEN, 5_000f); assertEquals(1f, out.slide); assertFalse(out.shineOn); assertEquals(1f, out.labelScale)
    }

    @Test
    fun theShineSweepsOnceFrom500To1400() {
        tab(PpTransition.OPEN, 499f); assertFalse(out.shineOn)
        tab(PpTransition.OPEN, 500f); assertTrue(out.shineOn); assertEquals(K.PP_SHINE_FROM, out.shineX)
        for (i in 500..1400 step 10) {
            val t = i.toFloat()
            tab(PpTransition.OPEN, t)
            assertTrue(out.shineOn, "shine on at $t")
            // Normalised over the 497 px travel, so the 1e-3 is on the easing.
            near(std((t - 500) / 900.0), (out.shineX + 72f) / 497f, "shine progress at $t")
        }
        tab(PpTransition.OPEN, 1_400f); assertEquals(K.PP_SHINE_TO, out.shineX)
        tab(PpTransition.OPEN, 1_401f); assertFalse(out.shineOn)
    }

    @Test
    fun theOpensPulseIsTwoIterationsFrom1000() {
        for (i in 0..1900 step 5) {
            val t = i.toFloat()
            tab(PpTransition.OPEN, t)
            val expected = if (t <= 1000 || t >= 1900) 1.0 else {
                val p = (t - 1000) % 450
                if (p < 225) lerp(1f, 1.07f, easeInOut(p / 225.0)) else lerp(1.07f, 1f, easeInOut((p - 225) / 225.0))
            }
            near(expected, out.labelScale, "label scale at $t")
        }
        tab(PpTransition.OPEN, 1_225f); near(1.07, out.labelScale, "first peak")
        tab(PpTransition.OPEN, 1_450f); near(1.0, out.labelScale, "between the pulses")
        tab(PpTransition.OPEN, 1_675f); near(1.07, out.labelScale, "second peak")
    }

    @Test
    fun theCloseFollowsTheCss() {
        for (i in 0..600 step 5) {
            val t = i.toFloat()
            tab(PpTransition.CLOSE, t)
            near(lerp(1f, 0f, snap((t / 550.0).coerceIn(0.0, 1.0))), out.slide, "slide at $t")
            near(lerp(0.5f, 0f, ease((t / 400.0).coerceIn(0.0, 1.0))), out.shadowAlpha, "shadow at $t")
            near(lerp(1f, 0f, ease((t / 200.0).coerceIn(0.0, 1.0))), out.textAlpha, "text alpha at $t")
            near(lerp(0f, -4f, ease((t / 200.0).coerceIn(0.0, 1.0))), out.textDy, "text dy at $t")
            assertFalse(out.shineOn, "no shine on a close")
            assertEquals(1f, out.labelScale)
            assertEquals(t >= 600f, out.tabClosed, "closed at $t")
        }
        tab(PpTransition.CLOSE, 200f); assertEquals(0f, out.textAlpha)
        tab(PpTransition.CLOSE, 550f); assertEquals(0f, out.slide)
    }

    @Test
    fun thePulseIsTheLabelOnceWithEverythingElseAtRest() {
        for (i in 0..600 step 5) {
            val t = i.toFloat()
            tab(PpTransition.PULSE, t, from = closed)
            assertEquals(1f, out.slide); assertEquals(0.5f, out.shadowAlpha); assertEquals(1f, out.textAlpha); assertEquals(0f, out.textDy)
            assertFalse(out.shineOn); assertFalse(out.tabClosed)
            val expected = when {
                t >= 450 -> 1.0
                t < 225 -> lerp(1f, 1.07f, easeInOut(t / 225.0))
                else -> lerp(1.07f, 1f, easeInOut((t - 225) / 225.0))
            }
            near(expected, out.labelScale, "pulse at $t")
        }
        tab(PpTransition.PULSE, 225f); near(1.07, out.labelScale, "pulse peak")
    }

    @Test
    fun theStillSnapsEachTrackToRest() {
        val f = PpFrame()
        PowerPlayTimeline.sample(PpTrack.TAB, PpTransition.STILL, 123f, open, false, f)
        assertTrue(f.tabClosed); assertEquals(0f, f.slide); assertEquals(0f, f.textAlpha)
        PowerPlayTimeline.sample(PpTrack.TAB, PpTransition.STILL, 0f, closed, true, f)
        assertFalse(f.tabClosed); assertEquals(1f, f.slide); assertEquals(1f, f.textAlpha); assertFalse(f.shineOn); assertEquals(1f, f.labelScale)
        PowerPlayTimeline.sample(PpTrack.PILL, PpTransition.STILL, 0f, closed, true, f)
        assertEquals(1f, f.tint); assertEquals(1f, f.halo); assertFalse(f.pillClosed)
        PowerPlayTimeline.sample(PpTrack.PILL, PpTransition.STILL, 0f, open, false, f)
        assertEquals(0f, f.tint); assertEquals(0f, f.halo); assertTrue(f.pillClosed)
    }

    @Test
    fun thePillTrackFadesInAndOut() {
        val off = PpFrame()
        for (i in 0..700 step 5) {
            val t = i.toFloat()
            pill(PpTransition.OPEN, t, off)
            val e = ease((t / 600.0).coerceIn(0.0, 1.0))
            near(e, out.tint, "tint in at $t"); near(e, out.halo, "halo in at $t"); assertFalse(out.pillClosed)
            pill(PpTransition.CLOSE, t, open)
            near(1 - e, out.tint, "tint out at $t"); near(1 - e, out.halo, "halo out at $t")
            assertEquals(t >= 600f, out.pillClosed, "pill closed at $t")
        }
    }

    @Test
    fun anInterruptedOpenClosesFromWhereItWas() {
        val mid = PpFrame()
        PowerPlayTimeline.sample(PpTrack.TAB, PpTransition.OPEN, 300f, closed, false, mid)
        val f = PpFrame()
        for (i in 0..600 step 10) {
            val t = i.toFloat()
            PowerPlayTimeline.sample(PpTrack.TAB, PpTransition.CLOSE, t, mid, false, f)
            near(lerp(mid.slide, 0f, snap((t / 550.0).coerceIn(0.0, 1.0))), f.slide, "slide at $t")
            near(lerp(mid.shadowAlpha, 0f, ease((t / 400.0).coerceIn(0.0, 1.0))), f.shadowAlpha, "shadow at $t")
            near(lerp(mid.textAlpha, 0f, ease((t / 200.0).coerceIn(0.0, 1.0))), f.textAlpha, "text at $t")
            assertFalse(f.shineOn)
        }
        PowerPlayTimeline.sample(PpTrack.TAB, PpTransition.CLOSE, 0f, mid, false, f)
        assertEquals(mid.slide, f.slide); assertEquals(mid.shadowAlpha, f.shadowAlpha)
    }

    @Test
    fun anInterruptedCloseReopensFromWhereItWasWithNoShineOrPulse() {
        val mid = PpFrame()
        PowerPlayTimeline.sample(PpTrack.TAB, PpTransition.CLOSE, 100f, open, false, mid)
        assertTrue(mid.slide > K.PP_REVERSE_SLIDE_MAX, "the close has not gone far: ${mid.slide}")
        val f = PpFrame()
        for (i in 0..1900 step 10) {
            val t = i.toFloat()
            PowerPlayTimeline.sample(PpTrack.TAB, PpTransition.OPEN, t, mid, false, f)
            near(lerp(mid.slide, 1f, snap((t / 550.0).coerceIn(0.0, 1.0))), f.slide, "slide at $t")
            near(lerp(mid.textAlpha, 1f, ease(((t - 380) / 300.0).coerceIn(0.0, 1.0))), f.textAlpha, "text at $t")
            near(lerp(mid.textDy, 0f, ease(((t - 380) / 350.0).coerceIn(0.0, 1.0))), f.textDy, "text dy at $t")
            assertFalse(f.shineOn, "no shine on a reversed close at $t")
            assertEquals(1f, f.labelScale, "no pulse on a reversed close at $t")
        }
        // A close that ran nearly to the end (slide ≤ .05) does play the shine on the reopen.
        PowerPlayTimeline.sample(PpTrack.TAB, PpTransition.CLOSE, 500f, open, false, mid)
        assertTrue(mid.slide <= K.PP_REVERSE_SLIDE_MAX, "near closed: ${mid.slide}")
        PowerPlayTimeline.sample(PpTrack.TAB, PpTransition.OPEN, 800f, mid, false, f)
        assertTrue(f.shineOn)
    }

    @Test
    fun anInterruptedPillStartsFromFrom() {
        val mid = PpFrame()
        PowerPlayTimeline.sample(PpTrack.PILL, PpTransition.OPEN, 200f, PpFrame(), false, mid)
        val f = PpFrame()
        PowerPlayTimeline.sample(PpTrack.PILL, PpTransition.CLOSE, 0f, mid, false, f)
        assertEquals(mid.tint, f.tint); assertEquals(mid.halo, f.halo)
        PowerPlayTimeline.sample(PpTrack.PILL, PpTransition.CLOSE, 300f, mid, false, f)
        near(lerp(mid.tint, 0f, ease(0.5)), f.tint, "tint")
    }

    @Test
    fun eachTrackWritesOnlyItsOwnChannels() {
        val sentinel = PpFrame().apply {
            slide = 0.33f; shadowAlpha = 0.11f; textAlpha = 0.22f; textDy = -1.5f; shineOn = true; shineX = 77f
            labelScale = 1.03f; tint = 0.44f; halo = 0.55f; tabClosed = true; pillClosed = true
        }
        for (kind in PpTransition.entries) for (t in listOf(0f, 300f, 1_000f, 2_000f)) for (to in listOf(false, true)) {
            val f = PpFrame().apply { copyFrom(sentinel) }
            PowerPlayTimeline.sample(PpTrack.TAB, kind, t, open, to, f)
            assertEquals(sentinel.tint, f.tint, "TAB $kind wrote tint"); assertEquals(sentinel.halo, f.halo, "TAB $kind wrote halo")
            assertEquals(sentinel.pillClosed, f.pillClosed, "TAB $kind wrote pillClosed")
            val g = PpFrame().apply { copyFrom(sentinel) }
            PowerPlayTimeline.sample(PpTrack.PILL, kind, t, open, to, g)
            assertEquals(sentinel.slide, g.slide); assertEquals(sentinel.shadowAlpha, g.shadowAlpha)
            assertEquals(sentinel.textAlpha, g.textAlpha); assertEquals(sentinel.textDy, g.textDy)
            assertEquals(sentinel.shineOn, g.shineOn); assertEquals(sentinel.shineX, g.shineX)
            assertEquals(sentinel.labelScale, g.labelScale); assertEquals(sentinel.tabClosed, g.tabClosed, "PILL $kind wrote tabClosed")
        }
    }

    @Test
    fun negativeAndNanTimesAreTheStart() {
        val a = PpFrame()
        val b = PpFrame()
        PowerPlayTimeline.sample(PpTrack.TAB, PpTransition.OPEN, 0f, closed, false, a)
        PowerPlayTimeline.sample(PpTrack.TAB, PpTransition.OPEN, -50f, closed, false, b)
        assertEquals(a.slide, b.slide); assertEquals(a.textAlpha, b.textAlpha)
        PowerPlayTimeline.sample(PpTrack.TAB, PpTransition.OPEN, Float.NaN, closed, false, b)
        assertEquals(a.slide, b.slide)
    }

    @Test
    fun copyFromCopiesEveryChannel() {
        PowerPlayTimeline.sample(PpTrack.TAB, PpTransition.OPEN, 1_225f, closed, false, out)
        PowerPlayTimeline.sample(PpTrack.PILL, PpTransition.OPEN, 300f, closed, false, out)
        val c = PpFrame().apply { copyFrom(out) }
        assertEquals(out.slide, c.slide); assertEquals(out.labelScale, c.labelScale); assertEquals(out.shineX, c.shineX)
        assertEquals(out.shineOn, c.shineOn); assertEquals(out.tint, c.tint); assertEquals(out.tabClosed, c.tabClosed)
    }

    @Test
    fun sampleAllocatesNothing() {
        val from = PpFrame()
        val f = PpFrame()
        val kinds = PpTransition.entries.toTypedArray()
        fun pass() {
            for (i in 0 until 10_000) {
                val k = kinds[i % kinds.size]
                PowerPlayTimeline.sample(PpTrack.TAB, k, (i % 2_000).toFloat(), from, i % 2 == 0, f)
                PowerPlayTimeline.sample(PpTrack.PILL, k, (i % 700).toFloat(), from, i % 2 == 0, f)
            }
        }
        pass()
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        pass()
        val after = bean.getThreadAllocatedBytes(id)
        assertTrue(after - before < 1_024, "allocated ${after - before} B")
    }
}
