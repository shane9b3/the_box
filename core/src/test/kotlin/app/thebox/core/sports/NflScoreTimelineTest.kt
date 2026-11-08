package app.thebox.core.sports

import app.thebox.core.sports.SportsConstants as K
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/design/m6-nfl.md §5.1, §5.3, §5.6, §5.7, §10.1 `NflScoreTimelineTest`: every keyframe instant and mid-segments
 * against an independent reference bezier (1e-3), the 450 ms flash grid (also across a +7/+8 pair), ball vs arc by kind,
 * the letter stagger per word, the pair's instants, the ball's ink radius and bands (B5), the still frames, and an
 * allocation-free sample.
 */
class NflScoreTimelineTest {
    private val f = NflScoreFrame()
    private val td = NflScoreKind.TOUCHDOWN
    private val xp = NflScoreKind.EXTRA_POINT
    private val fg = NflScoreKind.FIELD_GOAL
    private fun at(t: Float, kind: NflScoreKind = td) = NflScoreTimeline.sample(kind, t, f)

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
    private fun std(p: Double) = ref(0.4, 0.0, 0.2, 1.0, p)
    private fun spring(p: Double) = ref(0.2, 0.8, 0.2, 1.0, p)
    private fun ease(p: Double) = ref(0.25, 0.1, 0.25, 1.0, p)
    private fun roll(p: Double) = ref(0.3, 0.0, 0.3, 1.0, p)
    private fun easeOut(p: Double) = ref(0.0, 0.0, 0.58, 1.0, p)
    private fun lerp(a: Double, b: Double, q: Double) = a + (b - a) * q
    private fun upDown(p: Double) = if (p < 0.5) easeInOut(p * 2) else 1 - easeInOut(p * 2 - 1)

    private fun near(expected: Double, actual: Float, what: String, tol: Double = 1e-3) =
        assertTrue(abs(expected - actual) <= tol, "$what: expected $expected, got $actual")

    @Test
    fun theNewCurvesMatchTheReference() {
        val curves = listOf(
            NflScoreTimeline.EASE to doubleArrayOf(0.25, 0.1, 0.25, 1.0),
            NflScoreTimeline.ROLL to doubleArrayOf(0.3, 0.0, 0.3, 1.0),
            NflScoreTimeline.EASE_OUT to doubleArrayOf(0.0, 0.0, 0.58, 1.0),
        )
        for ((c, k) in curves) for (i in 0..1000) {
            val p = i / 1000.0
            near(ref(k[0], k[1], k[2], k[3], p), c.ease(p.toFloat()), "bezier ${k.toList()} at $p", 1e-4)
        }
    }

    @Test
    fun theWipesAreTheGoalAnimationsAndEaseEachSegment() {
        at(0f); assertEquals(0f, f.leadTo); assertEquals(0f, f.panelTo)
        at(216f); assertEquals(0f, f.leadFrom); assertEquals(1f, f.leadTo)
        at(396f); assertEquals(1f, f.panelTo)
        at(3_492f); assertEquals(1f, f.panelFrom)
        at(3_600f); assertEquals(1f, f.leadFrom)
        for (t in listOf(20f, 108f, 200f)) near(wipe(t / 216.0), at(t).leadTo, "lead in at $t")
        for (t in listOf(3_400f, 3_550f)) near(wipe((t - 3_384.0) / 216.0), at(t).leadFrom, "lead out at $t")
        for (t in listOf(150f, 300f)) near(wipe((t - 108.0) / 288.0), at(t).panelTo, "panel in at $t")
        for (t in listOf(3_200f, 3_450f)) near(wipe((t - 3_168.0) / 324.0), at(t).panelFrom, "panel out at $t")
        assertEquals(216f, NflScoreTimeline.LEAD_COVERED_MS)
    }

    @Test
    fun theGlowIsTheGoalAnimationsTGlow() {
        at(540f); near(0.55, f.glowLowAlpha, "low at 15 %"); near(0.0, f.glowPeakAlpha, "peak at 15 %")
        at(1_800f); near(0.0, f.glowLowAlpha, "low at 50 %"); near(0.8, f.glowPeakAlpha, "peak at 50 %")
        at(3_060f); near(0.55, f.glowLowAlpha, "low at 85 %")
        at(1_170f); val m = easeInOut(630.0 / 1260.0); near(0.55 * (1 - m), f.glowLowAlpha, "low mid"); near(0.8 * m, f.glowPeakAlpha, "peak mid")
        at(3_600f); assertEquals(0f, f.glowLowAlpha); assertEquals(0f, f.glowPeakAlpha)
    }

    @Test
    fun theStreaksRunFromMinus85To616() {
        at(349f); assertFalse(f.streak1On)
        at(350f); assertTrue(f.streak1On); near(-85.0, f.streak1X, "streak 1 start")
        at(850f); near(lerp(-85.0, 616.0, std(0.5)), f.streak1X, "streak 1 mid")
        at(1_350f); near(616.0, f.streak1X, "streak 1 end")
        at(1_351f); assertFalse(f.streak1On)
        at(1_799f); assertFalse(f.streak2On)
        at(2_000f); assertTrue(f.streak2On); near(lerp(-85.0, 616.0, std(200.0 / 800.0)), f.streak2X, "streak 2")
        at(2_601f); assertFalse(f.streak2On)
    }

    @Test
    fun theBallRunsForTouchdownTwoPointAndSafetyAndArcsForTheKicks() {
        for (k in NflScoreKind.values()) {
            val run = k == td || k == NflScoreKind.TWO_POINT || k == NflScoreKind.SAFETY
            assertEquals(run, NflScoreTimeline.runsBall(k), "$k")
            NflScoreTimeline.sample(k, 900f, f)
            assertEquals(run, f.runOn, "$k run")
            assertEquals(!run, f.arcOn, "$k arc")
            assertEquals(!run, f.postsOn, "$k posts")
        }
    }

    @Test
    fun theRunsKeyframes() {
        at(299f); assertFalse(f.runOn); assertEquals(0f, f.ballAlpha)
        at(300f); assertTrue(f.runOn); near(-42.0, f.ballX, "x from"); near(0.0, f.ballAlpha, "α 0 %"); near(0.0, f.ballRotDeg, "rot 0")
        assertEquals(18f, f.ballY)
        // Opacity: three segments, each eased.
        at(300f + 0.15f * 1_300f); near(0.4, f.ballAlpha, "α 15 %")
        at(400f); near(0.4 * roll((100.0 / 1_300.0) / 0.15), f.ballAlpha, "α mid-in")
        at(300f + 0.5f * 1_300f); near(0.4, f.ballAlpha, "α held")
        // The transform is one segment (keyed at 0 % and 100 % only).
        near(lerp(-42.0, 562.0, roll(0.5)), f.ballX, "x mid"); near(900.0 * roll(0.5), f.ballRotDeg, "rot mid", 0.05)
        at(1_500f); near(0.4 * (1 - roll((1_200.0 / 1_300.0 - 0.85) / 0.15)), f.ballAlpha, "α mid-out")
        at(1_600f); near(562.0, f.ballX, "x to"); near(900.0, f.ballRotDeg, "rot to"); near(0.0, f.ballAlpha, "α 100 %")
        at(1_601f); assertFalse(f.runOn)
        assertEquals(1f, f.ballScale)
    }

    @Test
    fun theArcsKeyframes() {
        val k = fg
        NflScoreTimeline.sample(k, 300f, f)
        assertTrue(f.arcOn); near(33.0, f.ballX, "P0 x"); near(44.0, f.ballY, "P0 y"); near(1.0, f.ballScale, "×1"); near(0.0, f.ballAlpha, "α 0")
        NflScoreTimeline.sample(k, 300f + 0.1f * 1_400f, f); near(0.55, f.ballAlpha, "α 10 %")
        NflScoreTimeline.sample(k, 1_000f, f)
        near(202.0, f.ballX, "P1 x"); near(-7.0, f.ballY, "P1 y"); near(0.75, f.ballScale, "×.75"); near(360.0, f.ballRotDeg, "360")
        NflScoreTimeline.sample(k, 650f, f)
        val q = ease(0.5)
        near(lerp(33.0, 202.0, q), f.ballX, "x seg 1"); near(lerp(44.0, -7.0, q), f.ballY, "y seg 1"); near(lerp(1.0, 0.75, q), f.ballScale, "scale seg 1")
        NflScoreTimeline.sample(k, 1_350f, f)
        near(lerp(202.0, 366.0, q), f.ballX, "x seg 2"); near(lerp(0.75, 0.45, q), f.ballScale, "scale seg 2")
        NflScoreTimeline.sample(k, 1_700f, f)
        near(366.0, f.ballX, "P2 x"); near(12.0, f.ballY, "P2 y"); near(0.45, f.ballScale, "×.45"); near(720.0, f.ballRotDeg, "720"); near(0.0, f.ballAlpha, "α 100 %")
        NflScoreTimeline.sample(k, 1_701f, f); assertFalse(f.arcOn)
    }

    @Test
    fun theGoalpostsDropInThenPulseOnTheGrid() {
        NflScoreTimeline.sample(xp, 200f, f); near(0.0, f.postsAlpha, "α at 200"); near(8.0, f.postsDy, "dy at 200")
        NflScoreTimeline.sample(xp, 400f, f); near(0.4 * easeOut(0.5), f.postsAlpha, "α mid-in"); near(8.0 * (1 - easeOut(0.5)), f.postsDy, "dy mid-in")
        NflScoreTimeline.sample(xp, 600f, f); near(0.4, f.postsAlpha, "α at rest"); near(0.0, f.postsDy, "dy at rest")
        for (k in 0 until 3) {
            val c = 1_800f + 450f * k
            NflScoreTimeline.sample(xp, c, f); near(0.4, f.postsAlpha, "hit $k start")
            NflScoreTimeline.sample(xp, c + 225f, f); near(0.95, f.postsAlpha, "hit $k peak")
            NflScoreTimeline.sample(xp, c + 100f, f); near(0.4 + 0.55 * upDown(100.0 / 450.0), f.postsAlpha, "hit $k mid")
        }
        NflScoreTimeline.sample(xp, 3_200f, f); near(0.4, f.postsAlpha, "after the hits")
        NflScoreTimeline.sample(xp, 3_600f, f); assertFalse(f.postsOn)
    }

    @Test
    fun thePanelFlashIsThreeCyclesOn450() {
        for (t in listOf(449f, 450f, 900f, 1_350f, 1_800f, 2_500f)) near(0.0, at(t).flashAlpha, "flash at $t")
        for (t in listOf(675f, 1_125f, 1_575f)) near(0.42, at(t).flashAlpha, "flash peak at $t")
        near(0.42 * upDown(100.0 / 450.0), at(550f).flashAlpha, "flash mid")
    }

    @Test
    fun theBadgeKeyframes() {
        at(450f); near(0.0, f.badgeAlpha, "α"); near(-32.0, f.badgeDx, "dx"); near(0.4, f.badgeScale, "×"); near(-25.0, f.badgeRotDeg, "deg")
        at(450f + 0.7f * 600f); near(1.0, f.badgeAlpha, "α 70 %"); near(2.0, f.badgeDx, "dx 70 %"); near(1.1, f.badgeScale, "× 70 %"); near(4.0, f.badgeRotDeg, "deg 70 %")
        at(660f); val q = spring((210.0 / 600.0) / 0.7); near(q, f.badgeAlpha, "α mid"); near(lerp(-32.0, 2.0, q), f.badgeDx, "dx mid")
        at(1_050f); near(1.0, f.badgeScale, "rest"); near(0.0, f.badgeDx, "rest dx"); near(0.0, f.badgeRotDeg, "rest deg")
    }

    @Test
    fun theLettersStagger55MsPerCharacterForEachWord() {
        val words = mapOf(td to "TOUCHDOWN", xp to "EXTRA POINT", fg to "FIELD GOAL", NflScoreKind.SAFETY to "SAFETY", NflScoreKind.TWO_POINT to "TWO-POINT")
        for ((k, w) in words) {
            assertEquals(w.length, NflScoreTimeline.letterCount(k), "$k count")
            assertTrue(w.length <= NflScoreFrame.LETTERS)
            NflScoreTimeline.sample(k, 0f, f)
            assertEquals(w.length, f.letterCount)
            for (i in 0 until w.length) {
                val start = 420f + 55f * i
                NflScoreTimeline.sample(k, start, f)
                near(0.0, f.letterAlpha[i], "$k[$i] α at its start"); near(22.0, f.letterDy[i], "$k[$i] dy"); near(1.9, f.letterScale[i], "$k[$i] ×")
                NflScoreTimeline.sample(k, start + 0.6f * 420f, f)
                near(1.0, f.letterAlpha[i], "$k[$i] α 60 %"); near(-2.0, f.letterDy[i], "$k[$i] dy 60 %"); near(0.94, f.letterScale[i], "$k[$i] × 60 %")
                NflScoreTimeline.sample(k, start + 420f, f)
                near(0.0, f.letterDy[i], "$k[$i] rest"); near(1.0, f.letterScale[i], "$k[$i] rest ×")
            }
        }
        // §5.1: TOUCHDOWN's last letter starts at 860, EXTRA POINT's at 970.
        assertEquals(860f, NflScoreTimeline.letterDelay(8))
        assertEquals(970f, NflScoreTimeline.letterDelay(10))
        at(500f); near(spring((80.0 / 420.0) / 0.6), f.letterAlpha[0], "letter 0 mid")
    }

    @Test
    fun theWordPulseAndTheScoreBlock() {
        at(1_399f); assertEquals(1f, f.wordPulse)
        at(1_650f); near(1.05, f.wordPulse, "pulse peak 1")
        at(2_650f); near(1.05, f.wordPulse, "pulse peak 3")
        at(2_900f); assertEquals(1f, f.wordPulse)
        at(1_100f); near(0.0, f.blockAlpha, "block α"); near(19.0, f.blockDx, "block dx"); near(0.6, f.blockScale, "block ×")
        at(1_450f); near(1.0, f.blockAlpha, "block 70 %"); near(-1.6, f.blockDx, "dx 70 %"); near(1.12, f.blockScale, "× 70 %")
        at(1_200f); val q = spring((100.0 / 500.0) / 0.7); near(lerp(19.0, -1.6, q), f.blockDx, "dx mid"); near(lerp(0.6, 1.12, q), f.blockScale, "× mid")
        at(1_600f); near(1.0, f.blockScale, "rest"); near(0.0, f.blockDx, "rest dx")
    }

    @Test
    fun theTabPulsesEightTimesOn450AndThePopFollows() {
        for (k in 0 until 8) {
            near(1.0, at(225f + 450f * k).tabPulse, "tab peak $k")
            near(0.0, at(450f * k).tabPulse, "tab trough $k")
        }
        near(0.0, at(3_600f).tabPulse, "no pulse after the takeover")
        at(3_167f); assertFalse(f.popOn)
        at(3_168f); assertTrue(f.popOn); assertEquals(1.5f, f.popScale); assertEquals(0f, f.popMix)
        at(3_600f + 0.4f * 900f); near(0.9, f.popScale, "pop 40 %")
        at(3_600f + 0.7f * 900f); near(1.06, f.popScale, "pop 70 %")
        at(3_780f); near(lerp(1.5, 0.9, spring(0.2 / 0.4)), f.popScale, "pop mid"); near(spring(0.2), f.popMix, "pop colour")
        at(4_499f); assertTrue(f.popOn); assertFalse(f.done)
        at(4_500f); assertFalse(f.popOn); assertTrue(f.done)
    }

    // ------------------------------------------------------------------ §5.3 the flash grid

    /** The local maxima of [channel] (sampled every ms over [range]) above [floor], ms. */
    private fun peaks(range: IntRange, floor: Float, channel: (Float) -> Float): List<Int> {
        val out = mutableListOf<Int>()
        for (t in range) {
            val v = channel(t.toFloat())
            if (v > floor && v >= channel(t - 1f) && v > channel(t + 1f)) out += t
        }
        return out
    }

    private fun assertOnOneGrid(all: List<Int>, what: String) {
        val distinct = all.sorted().fold(mutableListOf<Int>()) { acc, t -> if (acc.isEmpty() || t - acc.last() > 2) acc += t; acc }
        for (i in 1 until distinct.size) {
            assertTrue(distinct[i] - distinct[i - 1] >= 450 - 2, "$what: peaks ${distinct[i - 1]} and ${distinct[i]} closer than 450 ms ($distinct)")
        }
        // ≤ 3 flashes in any one-second window.
        for (t in distinct) assertTrue(distinct.count { it >= t && it < t + 1_000 } <= 3, "$what: > 3 flashes in a second from $t")
    }

    @Test
    fun everyFlashIsOnTheOne450MsGrid() {
        for (k in NflScoreKind.values()) {
            val g = NflScoreFrame()
            val panel = peaks(0..4_500, 0.01f) { NflScoreTimeline.sample(k, it, g).flashAlpha }
            val posts = peaks(0..4_500, 0.41f) { NflScoreTimeline.sample(k, it, g).postsAlpha }
            val tab = peaks(0..4_500, 0.01f) { NflScoreTimeline.sample(k, it, g).tabPulse }
            assertEquals(listOf(675, 1_125, 1_575), panel, "$k panel")
            assertEquals((0 until 8).map { 225 + 450 * it }, tab, "$k tab")
            if (NflScoreTimeline.runsBall(k)) assertTrue(posts.isEmpty(), "$k posts") else assertEquals(listOf(2_025, 2_475, 2_925), posts, "$k posts")
            for (p in panel + posts) assertEquals(0, (p - 225) % 450, "$k in phase with the tab at $p")
            assertOnOneGrid(panel + posts + tab, "$k")
        }
    }

    @Test
    fun theGridHoldsAcrossAPair() {
        for (second in listOf(xp, NflScoreKind.TWO_POINT)) {
            val g = NflScoreFrame()
            val panel = peaks(0..9_000, 0.01f) { NflScoreTimeline.samplePair(td, second, it, g).flashAlpha }
            val posts = peaks(0..9_000, 0.41f) { NflScoreTimeline.samplePair(td, second, it, g).postsAlpha }
            val tab = peaks(0..9_000, 0.01f) { NflScoreTimeline.samplePair(td, second, it, g).tabPulse }
            // B's grid restarts at 4 500: its first tab peak is 4 725, 1 350 ms after A's last (3 375).
            assertEquals((0 until 8).map { 225 + 450 * it } + (0 until 8).map { 4_725 + 450 * it }, tab, "$second tab")
            assertEquals(listOf(675, 1_125, 1_575, 5_175, 5_625, 6_075), panel, "$second panel")
            assertOnOneGrid(panel + posts + tab, "pair TD + $second")
        }
    }

    // ------------------------------------------------------------------ §5.7 the pair

    @Test
    fun thePairsInstants() {
        val g = NflScoreFrame()
        NflScoreTimeline.samplePair(td, xp, 0f, g)
        assertEquals(0, g.segment); assertFalse(g.done); assertEquals(9, g.letterCount); assertEquals(0f, g.leadTo)
        NflScoreTimeline.samplePair(td, xp, 900f, g); assertTrue(g.runOn); assertFalse(g.arcOn)
        NflScoreTimeline.samplePair(td, xp, 3_600f, g)
        assertEquals(0, g.segment); assertTrue(g.popOn); assertEquals(1.5f, g.popScale); assertEquals(0f, g.tabPulse); assertFalse(g.done)
        NflScoreTimeline.samplePair(td, xp, 4_499f, g); assertEquals(0, g.segment); assertTrue(g.popOn)
        NflScoreTimeline.samplePair(td, xp, 4_500f, g)
        assertEquals(1, g.segment); assertEquals(11, g.letterCount); assertEquals(0f, g.leadTo); assertFalse(g.popOn); assertFalse(g.done)
        NflScoreTimeline.samplePair(td, xp, 4_716f, g)
        assertEquals(1f, g.leadTo, "B's lead covers the pill at 4 716"); assertTrue(g.postsOn)
        NflScoreTimeline.samplePair(td, xp, 5_400f, g); assertTrue(g.arcOn); assertFalse(g.runOn)
        NflScoreTimeline.samplePair(td, xp, 8_100f, g); assertEquals(1, g.segment); assertTrue(g.popOn); assertFalse(g.done)
        NflScoreTimeline.samplePair(td, xp, 8_999f, g); assertFalse(g.done)
        NflScoreTimeline.samplePair(td, xp, 9_000f, g); assertTrue(g.done); assertFalse(g.popOn)
        // The second segment is exactly a single takeover, shifted.
        val one = NflScoreFrame()
        for (t in listOf(4_600f, 5_000f, 6_000f, 7_700f, 8_400f)) {
            NflScoreTimeline.samplePair(td, NflScoreKind.TWO_POINT, t, g)
            NflScoreTimeline.sample(NflScoreKind.TWO_POINT, t - 4_500f, one)
            assertEquals(one.ballX, g.ballX); assertEquals(one.letterAlpha.toList(), g.letterAlpha.toList()); assertEquals(one.tabPulse, g.tabPulse)
        }
    }

    // ------------------------------------------------------------------ §5.6 the still version

    @Test
    fun theStillFramesForOneScore() {
        for (k in NflScoreKind.values()) {
            val g = NflScoreTimeline.sampleStill(k, null, 1_000f, NflScoreFrame())
            assertEquals(0f, g.leadTo); assertEquals(0f, g.panelFrom); assertEquals(1f, g.panelTo)
            assertEquals(0f, g.glowLowAlpha + g.glowPeakAlpha + g.flashAlpha + g.tabPulse + g.ballAlpha)
            assertFalse(g.streak1On || g.streak2On || g.runOn || g.arcOn)
            assertEquals(1f, g.badgeAlpha); assertEquals(1f, g.blockAlpha); assertEquals(1f, g.wordPulse)
            assertEquals(NflScoreTimeline.letterCount(k), g.letterCount)
            for (i in 0 until g.letterCount) { assertEquals(1f, g.letterAlpha[i]); assertEquals(0f, g.letterDy[i]); assertEquals(1f, g.letterScale[i]) }
            assertEquals(!NflScoreTimeline.runsBall(k), g.postsOn, "$k posts in frame A")
            if (g.postsOn) assertEquals(0.4f, g.postsAlpha)
            assertFalse(g.popOn)
            NflScoreTimeline.sampleStill(k, null, 3_600f, g)
            assertTrue(g.popOn); assertEquals(1f, g.popScale); assertEquals(0f, g.popMix); assertEquals(1f, g.panelFrom); assertFalse(g.postsOn)
            NflScoreTimeline.sampleStill(k, null, 4_500f, g)
            assertTrue(g.done); assertFalse(g.popOn)
        }
    }

    @Test
    fun theStillFramesForAPairAreA1B1A2B2() {
        val g = NflScoreFrame()
        NflScoreTimeline.sampleStill(td, xp, 100f, g); assertEquals(0, g.segment); assertEquals(9, g.letterCount); assertFalse(g.postsOn); assertFalse(g.popOn)
        NflScoreTimeline.sampleStill(td, xp, 4_000f, g); assertEquals(0, g.segment); assertTrue(g.popOn); assertFalse(g.done)
        NflScoreTimeline.sampleStill(td, xp, 4_500f, g); assertEquals(1, g.segment); assertEquals(11, g.letterCount); assertTrue(g.postsOn); assertFalse(g.popOn)
        NflScoreTimeline.sampleStill(td, xp, 8_100f, g); assertEquals(1, g.segment); assertTrue(g.popOn); assertFalse(g.done)
        NflScoreTimeline.sampleStill(td, xp, 9_000f, g); assertTrue(g.done)
    }

    // ------------------------------------------------------------------ B5: the ball inside the stadium

    @Test
    fun theBallsInkRadiusAndTheBands() {
        val units = NflShapes.ballInkRadiusUnits()
        assertTrue(abs(units - (sqrt(7.5 * 7.5 * 2).toFloat() + 0.8f)) < 1e-3f, "the tips (4.5, 19.5) / (19.5, 4.5) + half the stroke: $units")
        val r = NflShapes.BALL_UNIT_PX * units
        println("ball ink radius: Standard %.2f, Large %.2f (unit %.4f px, mock %.4f)".format(r, r * 1.25f, NflShapes.BALL_UNIT_PX, 34f / 24f))
        assertTrue(r <= K.NFL_BALL_INK_R - NflShapes.BALL_AA_PX + 1e-4f, "Standard $r")
        assertTrue(r * K.SCORE_LARGE_SCALE <= 18.75f, "Large ${r * 1.25f}")
        assertTrue(NflShapes.BALL_UNIT_PX * NflShapes.BALL_VIEWBOX <= K.NFL_BALL_PX)

        // The run's band: inside x ≥ 4.4 the stadium's half-height is ≥ 17, more than the ink radius plus its AA.
        val rad = K.SCORE_RADIUS
        val half = sqrt(rad * rad - (rad - K.NFL_RUN_BAND) * (rad - K.NFL_RUN_BAND))
        assertTrue(half >= K.NFL_BALL_INK_R, "half-height at the band $half")
        // The run's centre line is the pill's (y 35) at every instant.
        val g = NflScoreFrame()
        var t = 300f
        while (t <= 1_600f) {
            NflScoreTimeline.sample(td, t, g)
            assertEquals(35f, g.ballY + K.NFL_BALL_PX / 2f, "run centre y at $t")
            t += 10f
        }
        // The arc: wherever the ball shows, its ink is inside the straight section (x 35 … 485).
        t = 300f
        while (t <= 1_700f) {
            NflScoreTimeline.sample(fg, t, g)
            if (g.ballAlpha > 0f) {
                val cx = g.ballX + K.NFL_BALL_PX / 2f
                val ink = r * g.ballScale
                assertTrue(cx - ink >= K.NFL_ARC_BAND - 1e-3f, "arc ink left ${cx - ink} at $t")
                assertTrue(cx + ink <= K.NFL_SCORE_W - K.NFL_ARC_BAND, "arc ink right ${cx + ink} at $t")
            }
            t += 5f
        }
        // The posts sit in the straight section too.
        assertTrue(K.NFL_POSTS_X >= K.NFL_ARC_BAND && K.NFL_POSTS_X + K.NFL_POSTS_W <= K.NFL_SCORE_W - K.NFL_ARC_BAND)
    }

    // ------------------------------------------------------------------ allocation

    @Test
    fun sampleAllocatesNothing() {
        val g = NflScoreFrame()
        fun pass() {
            for (i in 0 until 10_000) {
                val t = (i % 950) * 10f
                NflScoreTimeline.sample(fg, t, g)
                NflScoreTimeline.samplePair(td, xp, t, g)
                NflScoreTimeline.sampleStill(td, xp, t, g)
            }
        }
        pass()
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        pass()
        val after = bean.getThreadAllocatedBytes(id)
        println("NflScoreTimeline × 30 000: ${after - before} B")
        assertTrue(after - before < 1_024, "allocated ${after - before} B")
    }

    @Test
    fun copyFromCopiesEveryChannel() {
        val a = NflScoreTimeline.sample(fg, 2_025f, NflScoreFrame())
        val b = NflScoreFrame().apply { copyFrom(a) }
        assertEquals(a.postsAlpha, b.postsAlpha); assertEquals(a.letterScale.toList(), b.letterScale.toList()); assertEquals(a.tabPulse, b.tabPulse)
    }
}
