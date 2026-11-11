package app.thebox.core.sports

import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/design/m6-score-bug-stacked-theme.md §7.2, §7.3, §10.1 `GoalSpecTest` (S9):
 * - [GoalSpec.CLASSIC] is bit-for-bit the pre-spec timeline ([LegacyGoalTimeline], frozen at `87e22d4`) on every
 *   [GoalFrame] field, through the old overloads and the spec ones, on a 0.25-ms grid from −1 to 4 700 plus NaN and +∞;
 * - CLASSIC writes `rowFlashAlpha = 0` even after a STACKED sample into the same frame;
 * - every §7.2 STACKED difference, the row-flash envelope, the still frames;
 * - the flash is exactly 3 cycles of 400 ms for both specs (≤ 3 Hz).
 *
 * `GoalSampler.Real` and `ScoreBug.kt`'s `TAKEOVER_TOTAL_MS` live in `:ui`; the `:core` side of that pin is that
 * [GoalSpec.CLASSIC] ends at `GOAL_TAKEOVER_MS + GOAL_POP_MS` = 4 500, which is the value `TAKEOVER_TOTAL_MS` is built from.
 */
class GoalSpecTest {
    /** The golden's instants: −1 .. 4 700 in 0.25-ms steps, then NaN and +∞ (18 807 instants). */
    private val instants: FloatArray = run {
        val n = ((4_700f - -1f) / 0.25f).toInt() + 1
        FloatArray(n + 2) { i -> if (i < n) -1f + i * 0.25f else if (i == n) Float.NaN else Float.POSITIVE_INFINITY }
    }

    /** Every `87e22d4` field of [a] and [b] equal bitwise (floats by `toBits`, booleans and `done` by equality). */
    private fun assertSameBits(a: GoalFrame, b: GoalFrame, what: String) {
        fun f(name: String, x: Float, y: Float) =
            assertEquals(x.toBits(), y.toBits(), "$what: $name legacy $x vs new $y")
        f("leadFrom", a.leadFrom, b.leadFrom); f("leadTo", a.leadTo, b.leadTo)
        f("panelFrom", a.panelFrom, b.panelFrom); f("panelTo", a.panelTo, b.panelTo)
        f("glowLowAlpha", a.glowLowAlpha, b.glowLowAlpha); f("glowPeakAlpha", a.glowPeakAlpha, b.glowPeakAlpha)
        assertEquals(a.streak1On, b.streak1On, "$what: streak1On"); f("streak1X", a.streak1X, b.streak1X)
        assertEquals(a.streak2On, b.streak2On, "$what: streak2On"); f("streak2X", a.streak2X, b.streak2X)
        f("flashAlpha", a.flashAlpha, b.flashAlpha)
        f("badgeAlpha", a.badgeAlpha, b.badgeAlpha); f("badgeScale", a.badgeScale, b.badgeScale)
        f("badgeRotDeg", a.badgeRotDeg, b.badgeRotDeg)
        for (i in 0 until GoalFrame.LETTERS) {
            f("letterAlpha[$i]", a.letterAlpha[i], b.letterAlpha[i])
            f("letterDy[$i]", a.letterDy[i], b.letterDy[i])
            f("letterScale[$i]", a.letterScale[i], b.letterScale[i])
        }
        f("wordPulse", a.wordPulse, b.wordPulse)
        f("tallyAlpha", a.tallyAlpha, b.tallyAlpha); f("tallyDx", a.tallyDx, b.tallyDx)
        assertEquals(a.popOn, b.popOn, "$what: popOn"); f("popScale", a.popScale, b.popScale); f("popMix", a.popMix, b.popMix)
        assertEquals(a.done, b.done, "$what: done")
        f("rowFlashAlpha", 0f, b.rowFlashAlpha)
    }

    /** Fills [f] with values no sampler writes, so a field a sampler leaves alone shows up as a mismatch. */
    private fun dirty(f: GoalFrame) {
        GoalTimeline.sample(GoalSpec.STACKED, 3_900f, f) // a STACKED sample: rowFlashAlpha is non-zero here
        assertTrue(f.rowFlashAlpha > 0f)
        f.leadFrom = 7f; f.leadTo = 7f; f.panelFrom = 7f; f.panelTo = 7f; f.glowLowAlpha = 7f; f.glowPeakAlpha = 7f
        f.streak1On = true; f.streak1X = 7f; f.streak2On = true; f.streak2X = 7f; f.flashAlpha = 7f
        f.badgeAlpha = 7f; f.badgeScale = 7f; f.badgeRotDeg = 7f
        for (i in 0 until GoalFrame.LETTERS) { f.letterAlpha[i] = 7f; f.letterDy[i] = 7f; f.letterScale[i] = 7f }
        f.wordPulse = 7f; f.tallyAlpha = 7f; f.tallyDx = 7f; f.popOn = true; f.popScale = 7f; f.popMix = 7f; f.done = true
    }

    @Test
    fun classicIsBitForBitTheLegacyTimeline() {
        val legacy = GoalFrame()
        val viaOld = GoalFrame()
        val viaSpec = GoalFrame()
        for (t in instants) {
            LegacyGoalTimeline.sample(t, legacy)
            dirty(viaOld); GoalTimeline.sample(t, viaOld)
            dirty(viaSpec); GoalTimeline.sample(GoalSpec.CLASSIC, t, viaSpec)
            assertSameBits(legacy, viaOld, "sample(t=$t)")
            assertSameBits(legacy, viaSpec, "sample(CLASSIC, t=$t)")
        }
    }

    @Test
    fun classicStillIsBitForBitTheLegacyStill() {
        val legacy = GoalFrame()
        val viaOld = GoalFrame()
        val viaSpec = GoalFrame()
        for (t in instants) {
            LegacyGoalTimeline.sampleStill(t, legacy)
            dirty(viaOld); GoalTimeline.sampleStill(t, viaOld)
            dirty(viaSpec); GoalTimeline.sampleStill(GoalSpec.CLASSIC, t, viaSpec)
            assertSameBits(legacy, viaOld, "sampleStill(t=$t)")
            assertSameBits(legacy, viaSpec, "sampleStill(CLASSIC, t=$t)")
        }
    }

    @Test
    fun classicHoldsTheOldConstantsAndEndsAt4500() {
        val c = GoalSpec.CLASSIC
        assertEquals(4_500f, c.endMs)
        assertEquals((SportsConstants.GOAL_TAKEOVER_MS + SportsConstants.GOAL_POP_MS).toFloat(), c.endMs, "TAKEOVER_TOTAL_MS")
        assertEquals(LegacyGoalTimeline.END, c.endMs)
        assertEquals(900f, c.popMs)
        assertEquals(900f, c.stillFrameBMs)
        assertEquals(0f, c.rowFlashMs)
        assertEquals(0.6f, c.streak1Alpha); assertEquals(0.35f, c.streak2Alpha)
        assertEquals(-83f, c.streakFrom); assertEquals(508f, c.streakTo)
        assertEquals(0.45f, c.flashAlpha)
        assertEquals(550f, c.badgeMs); assertEquals(1.12f, c.badgeMidScale); assertEquals(8f, c.badgeMidDeg)
        assertEquals(420f, c.letterMs); assertEquals(25f, c.letterDrop); assertEquals(-2f, c.letterOvershoot)
        assertEquals(1.07f, c.pulsePeak); assertEquals(20f, c.tallySlide); assertEquals(1.55f, c.popFromScale)
    }

    @Test
    fun stackedHoldsTheSection72Values() {
        val s = GoalSpec.STACKED
        assertEquals(0.55f, s.streak1Alpha); assertEquals(0.30f, s.streak2Alpha)
        assertEquals(-90f, s.streakFrom, 1e-4f); assertEquals(450f, s.streakTo, 1e-3f)
        assertEquals(0.40f, s.flashAlpha)
        assertEquals(600f, s.badgeMs); assertEquals(1.1f, s.badgeMidScale); assertEquals(6f, s.badgeMidDeg)
        assertEquals(450f, s.letterMs); assertEquals(27f, s.letterDrop, 1e-4f); assertEquals(-2.25f, s.letterOvershoot, 1e-5f)
        assertEquals(1.06f, s.pulsePeak); assertEquals(16.2f, s.tallySlide, 1e-4f); assertEquals(1.5f, s.popFromScale)
        assertEquals(900f, s.popMs)
        assertEquals(1_200f, s.rowFlashMs)
        assertEquals(4_800f, s.endMs)
        assertEquals(1_200f, s.stillFrameBMs)
    }

    // ---- the STACKED samples (§7.2)

    private val f = GoalFrame()
    private fun st(t: Float) = GoalTimeline.sample(GoalSpec.STACKED, t, f)
    private fun cl(t: Float) = GoalTimeline.sample(GoalSpec.CLASSIC, t, GoalFrame())

    /** Reference CSS cubic-bezier (bisection in doubles), independent of the solver under test. */
    private fun ref(x1: Double, y1: Double, x2: Double, y2: Double, p: Double): Double {
        if (p <= 0.0) return 0.0
        if (p >= 1.0) return 1.0
        fun b(a: Double, c: Double, t: Double) = 3 * (1 - t) * (1 - t) * t * a + 3 * (1 - t) * t * t * c + t * t * t
        var lo = 0.0
        var hi = 1.0
        repeat(80) { val m = (lo + hi) / 2; if (b(x1, x2, m) < p) lo = m else hi = m }
        return b(y1, y2, (lo + hi) / 2)
    }

    private fun near(expected: Double, actual: Float, what: String, tol: Double = 1e-3) =
        assertTrue(abs(expected - actual) <= tol, "$what: expected $expected, got $actual")

    @Test
    fun theSharedSkeletonIsUnchanged() {
        // Lead, panel and glow are the same functions of t under both specs (§7.2 "same").
        var t = 0f
        while (t <= 4_000f) {
            val c = cl(t)
            st(t)
            assertEquals(c.leadFrom, f.leadFrom, "leadFrom $t"); assertEquals(c.leadTo, f.leadTo, "leadTo $t")
            assertEquals(c.panelFrom, f.panelFrom, "panelFrom $t"); assertEquals(c.panelTo, f.panelTo, "panelTo $t")
            assertEquals(c.glowLowAlpha, f.glowLowAlpha, "glowLow $t"); assertEquals(c.glowPeakAlpha, f.glowPeakAlpha, "glowPeak $t")
            assertEquals(c.streak1On, f.streak1On, "streak1On $t"); assertEquals(c.streak2On, f.streak2On, "streak2On $t")
            t += 12.5f
        }
    }

    @Test
    fun theStreaksTravelMinus90To450() {
        st(350f); assertEquals(-90f, f.streak1X, 1e-3f); assertTrue(f.streak1On)
        st(1_350f); assertEquals(450f, f.streak1X, 1e-3f)
        st(850f); near(-90.0 + 540.0 * ref(0.4, 0.0, 0.2, 1.0, 0.5), f.streak1X, "streak 1 mid", 1e-2)
        st(1_750f); assertEquals(-90f, f.streak2X, 1e-3f); assertTrue(f.streak2On)
        st(2_550f); assertEquals(450f, f.streak2X, 1e-3f)
        st(2_551f); assertFalse(f.streak2On)
    }

    @Test
    fun theFlashPeaksAt40() {
        // 420 + 200 is the middle of the first cycle.
        st(620f); assertEquals(0.40f, f.flashAlpha, 1e-5f)
        assertEquals(0.45f, cl(620f).flashAlpha, 1e-5f)
        st(419.9f); assertEquals(0f, f.flashAlpha)
        st(1_620f); assertEquals(0f, f.flashAlpha, "no fourth cycle")
    }

    @Test
    fun theFlashIsThreeCyclesOf400ForBothSpecs() {
        for (spec in listOf(GoalSpec.CLASSIC, GoalSpec.STACKED)) {
            val g = GoalFrame()
            var peaks = 0
            var prev = 0f
            var rising = false
            var firstOn = Float.NaN
            var lastOn = Float.NaN
            var t = 0f
            while (t <= 4_800f) {
                val a = GoalTimeline.sample(spec, t, g).flashAlpha
                if (a > 0f) { if (firstOn.isNaN()) firstOn = t; lastOn = t }
                if (a > prev) rising = true
                if (a < prev && rising) { peaks++; rising = false }
                prev = a
                t += 1f
            }
            assertEquals(3, peaks, "$spec: 3 flashes")
            assertTrue(firstOn >= 420f && firstOn <= 421f, "first lit at $firstOn")
            assertTrue(lastOn < 420f + 3 * 400f, "last lit at $lastOn")
            // ≤ 3 Hz: the cycle is 400 ms (2.5 Hz).
            assertEquals(400L, SportsConstants.GOAL_FLASH_CYCLE_MS)
        }
    }

    @Test
    fun theBadgeIs600To1_1And6Degrees() {
        // 70 % of 600 after the 450 start = 870.
        st(870f); assertEquals(1f, f.badgeAlpha); assertEquals(1.1f, f.badgeScale, 1e-5f); assertEquals(6f, f.badgeRotDeg, 1e-5f)
        st(450f); assertEquals(0f, f.badgeAlpha); assertEquals(0.3f, f.badgeScale, 1e-6f); assertEquals(-120f, f.badgeRotDeg)
        st(1_050f); assertEquals(1f, f.badgeScale); assertEquals(0f, f.badgeRotDeg)
        // CLASSIC's badge rests at 1 000; STACKED's is still settling there.
        assertEquals(1f, cl(1_000f).badgeScale)
        st(1_000f); assertTrue(f.badgeScale > 1f, "still settling at 1 000: ${f.badgeScale}")
    }

    @Test
    fun theLettersAre450With27And225() {
        st(420f); assertEquals(27f, f.letterDy[0], 1e-4f); assertEquals(0f, f.letterAlpha[0]); assertEquals(1.9f, f.letterScale[0])
        // Letter 0: 60 % of 450 = 270 → 690.
        st(690f); assertEquals(-2.25f, f.letterDy[0], 1e-4f); assertEquals(0.94f, f.letterScale[0], 1e-5f)
        st(870f); assertEquals(0f, f.letterDy[0]); assertEquals(1f, f.letterScale[0])
        // Letter 3 starts at 660 and rests at 1 110.
        st(660f); assertEquals(27f, f.letterDy[3], 1e-4f)
        st(1_110f); assertEquals(0f, f.letterDy[3]); assertEquals(1f, f.letterAlpha[3])
    }

    @Test
    fun thePulseTallyAndPop() {
        st(1_400f); assertEquals(1.06f, f.wordPulse, 1e-5f)
        st(900f); assertEquals(0f, f.tallyAlpha); assertEquals(16.2f, f.tallyDx, 1e-4f)
        st(1_350f); assertEquals(1f, f.tallyAlpha); assertEquals(0f, f.tallyDx)
        st(3_200f); assertTrue(f.popOn); assertEquals(1.5f, f.popScale); assertEquals(0f, f.popMix)
        st(3_600f); assertEquals(1.5f, f.popScale, 1e-5f)
        st(3_600f + 360f); assertEquals(0.9f, f.popScale, 1e-5f)
        st(3_600f + 630f); assertEquals(1.06f, f.popScale, 1e-5f)
        st(4_499f); assertTrue(f.popOn)
        st(4_500f); assertFalse(f.popOn); assertEquals(1f, f.popScale); assertEquals(1f, f.popMix)
    }

    @Test
    fun theRowFlashEnvelope() {
        // 0 through the takeover, 1 at 3 600, ease-out to 0 at 4 800, 0 after.
        for (t in listOf(0f, 216f, 1_800f, 3_599.75f)) { st(t); assertEquals(0f, f.rowFlashAlpha, "t=$t") }
        st(3_600f); assertEquals(1f, f.rowFlashAlpha)
        for (k in 1..9) {
            val p = k / 10.0
            st(3_600f + 1_200f * p.toFloat())
            near(1.0 - ref(0.0, 0.0, 0.58, 1.0, p), f.rowFlashAlpha, "row flash at $p")
        }
        st(4_799.75f); assertTrue(f.rowFlashAlpha in 0f..0.01f)
        st(4_800f); assertEquals(0f, f.rowFlashAlpha)
        st(Float.POSITIVE_INFINITY); assertEquals(0f, f.rowFlashAlpha)
        st(Float.NaN); assertEquals(0f, f.rowFlashAlpha)
        // Monotonic down.
        var prev = 1f
        var t = 3_600f
        while (t < 4_800f) { st(t); assertTrue(f.rowFlashAlpha <= prev, "monotonic at $t"); prev = f.rowFlashAlpha; t += 5f }
    }

    @Test
    fun doneAt4800() {
        st(4_799f); assertFalse(f.done)
        st(4_800f); assertTrue(f.done)
        assertTrue(cl(4_500f).done)
        assertFalse(cl(4_499f).done)
    }

    @Test
    fun classicWritesZeroRowFlashAfterAStackedSample() {
        st(3_700f)
        assertTrue(f.rowFlashAlpha > 0f)
        GoalTimeline.sample(GoalSpec.CLASSIC, 3_700f, f)
        assertEquals(0f, f.rowFlashAlpha)
        GoalTimeline.sampleStill(GoalSpec.STACKED, 3_700f, f)
        assertEquals(1f, f.rowFlashAlpha)
        GoalTimeline.sampleStill(GoalSpec.CLASSIC, 3_700f, f)
        assertEquals(0f, f.rowFlashAlpha)
        st(3_700f)
        GoalTimeline.sample(3_700f, f)
        assertEquals(0f, f.rowFlashAlpha, "the old overload is CLASSIC")
        st(3_700f)
        GoalTimeline.sampleStill(3_700f, f)
        assertEquals(0f, f.rowFlashAlpha, "the old still overload is CLASSIC")
    }

    @Test
    fun theStackedStillFrames() {
        val g = GoalFrame()
        fun still(t: Float) = GoalTimeline.sampleStill(GoalSpec.STACKED, t, g)
        // Frame A: the panel at rest, nothing moving, no row tint.
        still(0f)
        assertEquals(0f, g.panelFrom); assertEquals(1f, g.panelTo); assertEquals(0f, g.leadTo); assertEquals(0f, g.flashAlpha)
        assertEquals(0f, g.glowLowAlpha); assertEquals(0f, g.glowPeakAlpha); assertFalse(g.streak1On); assertFalse(g.streak2On)
        assertEquals(1f, g.badgeAlpha); assertEquals(1f, g.tallyAlpha); assertEquals(1f, g.wordPulse)
        assertEquals(0f, g.rowFlashAlpha); assertFalse(g.popOn); assertFalse(g.done)
        assertEquals(-90f, g.streak1X, 1e-3f)
        still(3_599.75f); assertEquals(0f, g.rowFlashAlpha); assertFalse(g.popOn)
        // Frame B: 3 600 .. 4 800, the plain box, the digit in pop at × 1, the row tinted (envelope 1).
        still(3_600f)
        assertEquals(1f, g.panelFrom); assertTrue(g.popOn); assertEquals(1f, g.popScale); assertEquals(0f, g.popMix)
        assertEquals(1f, g.rowFlashAlpha); assertFalse(g.done)
        still(4_500f); assertTrue(g.popOn); assertEquals(1f, g.rowFlashAlpha); assertFalse(g.done)
        still(4_799.75f); assertTrue(g.popOn); assertEquals(1f, g.rowFlashAlpha)
        // Then the plain box.
        still(4_800f); assertFalse(g.popOn); assertEquals(1f, g.popMix); assertEquals(0f, g.rowFlashAlpha); assertTrue(g.done)
        // CLASSIC's frame B ends at 4 500.
        GoalTimeline.sampleStill(GoalSpec.CLASSIC, 4_500f, g); assertTrue(g.done); assertEquals(0f, g.rowFlashAlpha)
    }

    @Test
    fun stackedSamplingAllocatesNothing() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        repeat(10_000) { GoalTimeline.sample(GoalSpec.STACKED, (it % 4_900).toFloat(), f) }
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        for (i in 0 until 10_000) {
            GoalTimeline.sample(GoalSpec.STACKED, (i % 4_900).toFloat() + 0.5f, f)
            GoalTimeline.sampleStill(GoalSpec.STACKED, (i % 4_900).toFloat(), f)
        }
        val after = bean.getThreadAllocatedBytes(id)
        assertTrue(after - before < 1024, "allocated ${after - before} bytes")
    }
}
