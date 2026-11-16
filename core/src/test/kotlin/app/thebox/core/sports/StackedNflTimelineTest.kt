package app.thebox.core.sports

import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import app.thebox.core.sports.SportsConstants as K

/**
 * docs/design/m6-nfl-stacked.md §4.1, §4.4, §9.1 `StackedNflTimelineTest` (WP-2): every §4.1 row at its start, the end
 * of its delay, its midpoint and its end against an independent reference bezier; CLOCK and the row stripes bit-for-bit
 * the NHL Stacked CSS; the bar's OPEN (the REKEY intro from 300), CLOSE and REKEY (550); the balls; the pips (count
 * change, the 4 × 400 burn, still); the flag shake and wave; interruption from the current value; the still snaps; the
 * pinned snap; the lengths; monotonic channels; an allocation-free sampler.
 */
class StackedNflTimelineTest {
    private fun ref(c: DoubleArray, p: Double): Double {
        if (p <= 0.0) return 0.0
        if (p >= 1.0) return 1.0
        fun b(a: Double, d: Double, t: Double) = 3 * (1 - t) * (1 - t) * t * a + 3 * (1 - t) * t * t * d + t * t * t
        var lo = 0.0
        var hi = 1.0
        repeat(80) { val m = (lo + hi) / 2; if (b(c[0], c[2], m) < p) lo = m else hi = m }
        return b(c[1], c[3], (lo + hi) / 2)
    }

    private val std = doubleArrayOf(0.4, 0.0, 0.2, 1.0)
    private val spring = doubleArrayOf(0.2, 0.8, 0.2, 1.0)
    private val ease = doubleArrayOf(0.25, 0.1, 0.25, 1.0)
    private val easeIn = doubleArrayOf(0.42, 0.0, 1.0, 1.0)
    private val easeInOut = doubleArrayOf(0.42, 0.0, 0.58, 1.0)

    private fun near(expected: Double, actual: Float, what: String, tol: Double = 1e-3) =
        assertTrue(abs(expected - actual) <= tol, "$what: expected $expected, got $actual")

    private val S = StackedNflTransition.STILL
    private val tracks = StackedNflTrack.entries.filter { it != StackedNflTrack.PIPS_AWAY && it != StackedNflTrack.PIPS_HOME }

    /** Every non-pip track at rest, open / on / wide or closed / off / narrow. */
    private fun rest(open: Boolean): StackedNflFrame {
        val f = StackedNflFrame()
        val tmp = StackedNflFrame()
        for (t in tracks) StackedNflTimeline.sample(t, S, 0f, tmp, open, f)
        return f
    }

    private class Row(
        val name: String, val track: StackedNflTrack, val kind: StackedNflTransition, val get: (StackedNflFrame) -> Float,
        val a: Float, val b: Float, val delay: Long, val dur: Long, val curve: DoubleArray, val fromOpen: Boolean,
    )

    private val rows: List<Row> by lazy {
        val o = StackedNflTransition.OPEN
        val c = StackedNflTransition.CLOSE
        val r = StackedNflTransition.REKEY
        val bar = StackedNflTrack.BAR
        val travel = K.STACKED_BAR_STRIPE_TRAVEL
        val dx = K.STACKED_NFL_REKEY_TEXT_DX
        listOf(
            Row("clock close width", StackedNflTrack.CLOCK, c, { it.clockWidth }, 1f, 0f, 0, 450, std, true),
            Row("clock close α", StackedNflTrack.CLOCK, c, { it.clockAlpha }, 1f, 0f, 0, 150, ease, true),
            Row("clock open width", StackedNflTrack.CLOCK, o, { it.clockWidth }, 0f, 1f, 350, 450, std, false),
            Row("clock open α", StackedNflTrack.CLOCK, o, { it.clockAlpha }, 0f, 1f, 500, 300, ease, false),
            Row("stripe away widen", StackedNflTrack.STRIPE_AWAY, o, { it.stripeAway }, 0f, 1f, 0, 400, ease, false),
            Row("stripe home narrow", StackedNflTrack.STRIPE_HOME, c, { it.stripeHome }, 1f, 0f, 0, 400, ease, true),
            Row("ball away on α", StackedNflTrack.BALL_AWAY, o, { it.ballAwayAlpha }, 0f, 1f, 0, 300, ease, false),
            Row("ball away on scale", StackedNflTrack.BALL_AWAY, o, { it.ballAwayScale }, 0.4f, 1f, 0, 400, spring, false),
            Row("ball away on deg", StackedNflTrack.BALL_AWAY, o, { it.ballAwayDeg }, -40f, 0f, 0, 400, spring, false),
            Row("ball home off α", StackedNflTrack.BALL_HOME, c, { it.ballHomeAlpha }, 1f, 0f, 0, 300, ease, true),
            Row("ball home off scale", StackedNflTrack.BALL_HOME, c, { it.ballHomeScale }, 1f, 0.4f, 0, 400, spring, true),
            Row("ball home off deg", StackedNflTrack.BALL_HOME, c, { it.ballHomeDeg }, 0f, -40f, 0, 400, spring, true),
            Row("bar open height", bar, o, { it.barHeight }, 0f, 1f, 0, 400, std, false),
            Row("bar open stripe A", bar, o, { it.stripeAX }, travel, 0f, 300, 450, spring, false),
            Row("bar open stripe B", bar, o, { it.stripeBX }, travel, 0f, 380, 450, spring, false),
            Row("bar open text α", bar, o, { it.barTextAlpha }, 0f, 1f, 400, 450, spring, false),
            Row("bar open text dx", bar, o, { it.barTextDx }, dx, 0f, 400, 450, spring, false),
            Row("bar close height", bar, c, { it.barHeight }, 1f, 0f, 0, 400, std, true),
            Row("bar close stripe A", bar, c, { it.stripeAX }, 0f, travel, 0, 300, easeIn, true),
            Row("bar close stripe B", bar, c, { it.stripeBX }, 0f, travel, 0, 300, easeIn, true),
            Row("bar close text α", bar, c, { it.barTextAlpha }, 1f, 0f, 0, 150, ease, true),
            Row("bar close text dx", bar, c, { it.barTextDx }, 0f, dx, 0, 150, ease, true),
            Row("rekey bg", bar, r, { it.barBgMix }, 0f, 1f, 0, 350, ease, true),
            Row("rekey stripe A", bar, r, { it.stripeAX }, travel, 0f, 0, 450, spring, true),
            Row("rekey stripe B", bar, r, { it.stripeBX }, travel, 0f, 80, 450, spring, true),
            Row("rekey text α", bar, r, { it.barTextAlpha }, 0f, 1f, 100, 450, spring, true),
            Row("rekey text dx", bar, r, { it.barTextDx }, dx, 0f, 100, 450, spring, true),
            Row("rekey height (open)", bar, r, { it.barHeight }, 1f, 1f, 0, 400, std, true),
        )
    }

    @Test
    fun everySection41RowAtItsKeyInstants() {
        for (row in rows) {
            val from = rest(row.fromOpen)
            val out = rest(row.fromOpen)
            fun at(t: Float) = row.get(StackedNflTimeline.sample(row.track, row.kind, t, from, false, out))
            near(row.a.toDouble(), at(0f), "${row.name} at 0")
            near(row.a.toDouble(), at(row.delay.toFloat()), "${row.name} at its delay ${row.delay}")
            val mid = row.delay + row.dur / 2f
            near(row.a + (row.b - row.a) * ref(row.curve, 0.5), at(mid), "${row.name} at $mid")
            val q = row.delay + row.dur / 4f
            near(row.a + (row.b - row.a) * ref(row.curve, 0.25), at(q), "${row.name} at $q")
            near(row.b.toDouble(), at((row.delay + row.dur).toFloat()), "${row.name} at its end")
            near(row.b.toDouble(), at(Float.POSITIVE_INFINITY), "${row.name} at +∞")
            near(row.a.toDouble(), at(Float.NaN), "${row.name} at NaN")
            near(row.a.toDouble(), at(-5f), "${row.name} at −5")
        }
    }

    @Test
    fun theClockAndStripesAreTheNhlStackedCssBitForBit() {
        val pairs = listOf(
            Triple(StackedNflTrack.CLOCK, StackedTrack.CLOCK, true), Triple(StackedNflTrack.CLOCK, StackedTrack.CLOCK, false),
            Triple(StackedNflTrack.STRIPE_AWAY, StackedTrack.STRIPE_AWAY, true), Triple(StackedNflTrack.STRIPE_HOME, StackedTrack.STRIPE_HOME, false),
        )
        val nf = StackedNflFrame(); val no = StackedNflFrame()
        val hf = StackedFrame(); val ho = StackedFrame()
        for ((nt, ht, opening) in pairs) {
            // From a mid-way state, so the interruption path is compared too.
            nf.clockWidth = 0.37f; nf.clockAlpha = 0.61f; nf.stripeAway = 0.42f; nf.stripeHome = 0.58f
            hf.clockWidth = 0.37f; hf.clockAlpha = 0.61f; hf.stripeAway = 0.42f; hf.stripeHome = 0.58f
            val nk = if (opening) StackedNflTransition.OPEN else StackedNflTransition.CLOSE
            val hk = if (opening) StackedTransition.OPEN else StackedTransition.CLOSE
            var t = -1f
            while (t <= 1_000f) {
                StackedNflTimeline.sample(nt, nk, t, nf, false, no)
                StackedTimeline.sample(ht, hk, t, hf, false, ho)
                assertEquals(ho.clockWidth.toBits(), no.clockWidth.toBits(), "$nt $nk t=$t width")
                assertEquals(ho.clockAlpha.toBits(), no.clockAlpha.toBits(), "$nt $nk t=$t α")
                assertEquals(ho.stripeAway.toBits(), no.stripeAway.toBits(), "$nt $nk t=$t away")
                assertEquals(ho.stripeHome.toBits(), no.stripeHome.toBits(), "$nt $nk t=$t home")
                t += 0.5f
            }
            assertEquals(StackedTimeline.totalMs(ht, hk), StackedNflTimeline.totalMs(nt, nk), "$nt $nk length")
        }
    }

    @Test
    fun theBarOpenRunsTheRekeyIntroFrom300() {
        val from = rest(false)
        val out = StackedNflFrame()
        fun at(t: Float) = StackedNflTimeline.sample(StackedNflTrack.BAR, StackedNflTransition.OPEN, t, from, false, out)
        assertTrue(at(0f).barClosed)
        assertFalse(at(1f).barClosed)
        assertEquals(1f, at(0f).barBgMix, "nothing to lerp from")
        // At 300 the height is near done and the intro starts; the words are still hidden until 400.
        near(ref(std, 0.75), at(300f).barHeight, "height at 300")
        assertEquals(K.STACKED_BAR_STRIPE_TRAVEL, at(300f).stripeAX)
        assertEquals(0f, at(400f).barTextAlpha)
        val end = at(850f)
        assertEquals(1f, end.barHeight); assertEquals(0f, end.stripeAX); assertEquals(0f, end.stripeBX)
        assertEquals(1f, end.barTextAlpha); assertEquals(0f, end.barTextDx); assertFalse(end.barClosed)
        assertEquals(850f, StackedNflTimeline.totalMs(StackedNflTrack.BAR, StackedNflTransition.OPEN))
    }

    @Test
    fun theRekeyIs550AndRestartsTheContentFromItsKeyframe() {
        // From a fully shown bar: the old stripes and words are gone at 0 (the re-mount), the background lerps 0 → 1.
        val from = rest(true)
        val out = StackedNflFrame()
        val r0 = StackedNflTimeline.sample(StackedNflTrack.BAR, StackedNflTransition.REKEY, 0f, from, false, out)
        assertEquals(K.STACKED_BAR_STRIPE_TRAVEL, r0.stripeAX); assertEquals(0f, r0.barTextAlpha); assertEquals(0f, r0.barBgMix)
        assertEquals(1f, r0.barHeight); assertFalse(r0.barClosed)
        // The words' α and slide share the keyframe's Spring.
        for (t in listOf(150f, 325f, 500f)) {
            val f = StackedNflTimeline.sample(StackedNflTrack.BAR, StackedNflTransition.REKEY, t, from, false, out)
            near(K.STACKED_NFL_REKEY_TEXT_DX * (1.0 - f.barTextAlpha), f.barTextDx, "dx follows α at $t", 1e-5)
        }
        val r1 = StackedNflTimeline.sample(StackedNflTrack.BAR, StackedNflTransition.REKEY, 550f, from, false, out)
        assertEquals(0f, r1.stripeAX); assertEquals(0f, r1.stripeBX); assertEquals(1f, r1.barTextAlpha); assertEquals(1f, r1.barBgMix)
        assertEquals(550f, StackedNflTimeline.totalMs(StackedNflTrack.BAR, StackedNflTransition.REKEY))
        // A REKEY caught mid-open finishes the height from where it was (400 Std, no delay).
        val half = StackedNflFrame().also { it.barHeight = 0.5f }
        near(0.5 + 0.5 * ref(std, 0.5), StackedNflTimeline.sample(StackedNflTrack.BAR, StackedNflTransition.REKEY, 200f, half, false, out).barHeight, "mid-open")
        // A REKEY interrupting a REKEY restarts the intro (the mock bumps barKey again).
        val mid = StackedNflTimeline.sample(StackedNflTrack.BAR, StackedNflTransition.REKEY, 250f, from, false, StackedNflFrame())
        val again = StackedNflTimeline.sample(StackedNflTrack.BAR, StackedNflTransition.REKEY, 0f, mid, false, StackedNflFrame())
        assertEquals(K.STACKED_BAR_STRIPE_TRAVEL, again.stripeAX); assertEquals(0f, again.barTextAlpha); assertEquals(0f, again.barBgMix)
    }

    @Test
    fun interruptionsStartFromTheCurrentValue() {
        // A close caught 200 ms into an open: every channel starts where the open left it, full duration and delay.
        val open = StackedNflTimeline.sample(StackedNflTrack.BAR, StackedNflTransition.OPEN, 600f, rest(false), false, StackedNflFrame())
        val c0 = StackedNflTimeline.sample(StackedNflTrack.BAR, StackedNflTransition.CLOSE, 0f, open, false, StackedNflFrame())
        assertEquals(open.barHeight, c0.barHeight); assertEquals(open.stripeAX, c0.stripeAX); assertEquals(open.barTextAlpha, c0.barTextAlpha)
        near(open.barHeight * (1 - ref(std, 0.5)), StackedNflTimeline.sample(StackedNflTrack.BAR, StackedNflTransition.CLOSE, 200f, open, false, StackedNflFrame()).barHeight, "close from mid")
        // The clock folding mid-open.
        val clk = StackedNflFrame().also { it.clockWidth = 0.4f; it.clockAlpha = 0.2f }
        near(0.4 * (1 - ref(std, 0.5)), StackedNflTimeline.sample(StackedNflTrack.CLOCK, StackedNflTransition.CLOSE, 225f, clk, false, StackedNflFrame()).clockWidth, "fold from 0.4")
        // A ball turning off mid-way keeps its transform; a hidden ball pops in from .4 / −40°.
        val ball = StackedNflFrame().also { it.ballAwayAlpha = 0.5f; it.ballAwayScale = 0.8f; it.ballAwayDeg = -10f }
        val b0 = StackedNflTimeline.sample(StackedNflTrack.BALL_AWAY, StackedNflTransition.CLOSE, 0f, ball, false, StackedNflFrame())
        assertEquals(0.5f, b0.ballAwayAlpha); assertEquals(0.8f, b0.ballAwayScale); assertEquals(-10f, b0.ballAwayDeg)
        val hidden = StackedNflFrame() // the frame's defaults: α 0
        val h0 = StackedNflTimeline.sample(StackedNflTrack.BALL_HOME, StackedNflTransition.OPEN, 0f, hidden, false, StackedNflFrame())
        assertEquals(0.4f, h0.ballHomeScale); assertEquals(-40f, h0.ballHomeDeg)
    }

    @Test
    fun eachSampleWritesOnlyItsTrack() {
        for (tr in tracks) for (k in StackedNflTransition.entries) {
            val sentinel = StackedNflFrame()
            sentinel.clockWidth = 0.123f; sentinel.clockAlpha = 0.123f; sentinel.barHeight = 0.123f; sentinel.stripeAX = 0.123f
            sentinel.stripeBX = 0.123f; sentinel.barTextAlpha = 0.123f; sentinel.barTextDx = 0.123f; sentinel.barBgMix = 0.123f
            sentinel.stripeAway = 0.123f; sentinel.stripeHome = 0.123f; sentinel.ballAwayAlpha = 0.123f; sentinel.ballAwayScale = 0.123f
            sentinel.ballAwayDeg = 0.123f; sentinel.ballHomeAlpha = 0.123f; sentinel.ballHomeScale = 0.123f; sentinel.ballHomeDeg = 0.123f
            sentinel.shakeDx = 0.123f; sentinel.waveDeg = 0.123f; sentinel.pipAway.fill(0.123f); sentinel.pipHome.fill(0.123f)
            val out = StackedNflFrame().also { it.copyFrom(sentinel) }
            StackedNflTimeline.sample(tr, k, 200f, rest(true), true, out)
            fun untouched(name: String, mine: Boolean, v: Float) { if (!mine) assertEquals(0.123f, v, "$tr $k wrote $name") }
            untouched("clock", tr == StackedNflTrack.CLOCK, out.clockWidth)
            untouched("bar", tr == StackedNflTrack.BAR, out.barHeight)
            untouched("bar text", tr == StackedNflTrack.BAR, out.barTextAlpha)
            untouched("stripe away", tr == StackedNflTrack.STRIPE_AWAY, out.stripeAway)
            untouched("stripe home", tr == StackedNflTrack.STRIPE_HOME, out.stripeHome)
            untouched("ball away", tr == StackedNflTrack.BALL_AWAY, out.ballAwayAlpha)
            untouched("ball home", tr == StackedNflTrack.BALL_HOME, out.ballHomeScale)
            untouched("shake", false, out.shakeDx)
            untouched("wave", false, out.waveDeg)
            untouched("pips", false, out.pipAway[0])
        }
    }

    // ------------------------------------------------------------------ the pips

    @Test
    fun aCountChangeMovesEachPipOver400Ease() {
        val from = StackedNflFrame()
        StackedNflTimeline.samplePips(Side.AWAY, S, 0, 2, 0f, from, from) // 2 lit: [1, 1, 0]
        val out = StackedNflFrame()
        fun at(t: Float) = StackedNflTimeline.samplePips(Side.AWAY, StackedNflTransition.OPEN, 2, 3, t, from, out)
        assertEquals(listOf(1f, 1f, 0f), at(0f).pipAway.toList())
        near(ref(ease, 0.5), at(200f).pipAway[2], "pip 2 at 200")
        assertEquals(listOf(1f, 1f, 1f), at(400f).pipAway.toList())
        // Down: 3 → 1 without a burn (a fallback or a re-sync).
        val three = StackedNflFrame().also { StackedNflTimeline.samplePips(Side.HOME, S, 0, 3, 0f, it, it) }
        val d = StackedNflTimeline.samplePips(Side.HOME, StackedNflTransition.CLOSE, 3, 1, 200f, three, StackedNflFrame())
        assertEquals(1f, d.pipHome[0]); near(1 - ref(ease, 0.5), d.pipHome[1], "pip 1"); near(1 - ref(ease, 0.5), d.pipHome[2], "pip 2")
        assertTrue(d.pipHomeBurn.all { it == 0f })
        assertEquals(400f, StackedNflTimeline.totalMs(StackedNflTrack.PIPS_AWAY, StackedNflTransition.OPEN))
        // Only that side's arrays are written.
        assertTrue(d.pipAway.all { it == 0f })
    }

    @Test
    fun theBurnIsFourCyclesOf400ThenAFadeToUsed() {
        // BUF 3 → 2: pip index 2 (the new count) burns; pips 0 and 1 stay lit.
        val from = StackedNflFrame().also { StackedNflTimeline.samplePips(Side.HOME, S, 0, 3, 0f, it, it) }
        val out = StackedNflFrame()
        fun at(t: Float) = StackedNflTimeline.samplePips(Side.HOME, StackedNflTransition.BURN, 3, 2, t, from, out)
        for (k in 0 until 4) {
            val c = k * 400f
            near(0.0, at(c).pipHomeBurn[2], "burn start of cycle $k", 1e-5)
            near(ref(easeInOut, 0.5), at(c + 100f).pipHomeBurn[2], "burn quarter of cycle $k")
            near(1.0, at(c + 200f).pipHomeBurn[2], "burn peak of cycle $k (white α .15)")
            assertEquals(1f, at(c + 200f).pipHome[2], "held lit during the burn")
        }
        assertEquals(0f, at(1_600f).pipHomeBurn[2])
        near(1 - ref(ease, 0.5), at(1_800f).pipHome[2], "fading to used")
        assertEquals(0f, at(2_000f).pipHome[2])
        for (t in listOf(0f, 900f, 2_000f)) { assertEquals(1f, at(t).pipHome[0]); assertEquals(1f, at(t).pipHome[1]); assertEquals(0f, at(t).pipHomeBurn[0]) }
        assertEquals(2_000f, StackedNflTimeline.totalMs(StackedNflTrack.PIPS_HOME, StackedNflTransition.BURN))
        // Burn peaks on the 400 grid: 4 peaks, 400 apart (2.5 Hz, §4.2).
        val peaks = mutableListOf<Float>()
        var prev = -1f; var cur = at(0f).pipHomeBurn[2]; var t = 1f
        while (t < 2_000f) { val n = at(t + 1f).pipHomeBurn[2]; if (cur > prev && cur >= n && cur > 0.5f) peaks += t; prev = cur; cur = n; t += 1f }
        assertEquals(listOf(200f, 600f, 1_000f, 1_400f), peaks.map { it - 1f + 1f }.map { Math.round(it).toFloat() })
        // The still version: the burnt pip is used at once, no flash.
        val still = StackedNflTimeline.samplePips(Side.HOME, S, 3, 2, 0f, from, StackedNflFrame())
        assertEquals(listOf(1f, 1f, 0f), still.pipHome.toList()); assertTrue(still.pipHomeBurn.all { it == 0f })
        // 1 → 0: index 0 burns.
        val one = StackedNflFrame().also { StackedNflTimeline.samplePips(Side.AWAY, S, 0, 1, 0f, it, it) }
        val z = StackedNflTimeline.samplePips(Side.AWAY, StackedNflTransition.BURN, 1, 0, 200f, one, StackedNflFrame())
        assertEquals(1f, z.pipAwayBurn[0]); assertEquals(1f, z.pipAway[0])
    }

    // ------------------------------------------------------------------ the flag

    @Test
    fun theFlagShakeAndWave() {
        val f = StackedNflFrame()
        fun shake(t: Float) = StackedNflTimeline.sampleFlag(t, false, f).shakeDx
        fun wave(t: Float) = StackedNflTimeline.sampleFlag(t, false, f).waveDeg
        assertEquals(0f, shake(0f)); assertEquals(0f, shake(150f))
        // Each cycle: −2.7 at 20 %, +2.7 at 40 %, −1.8 at 60 %, +1.8 at 80 %, 0 at 100 % (500 ms).
        for (c in 0 until 2) {
            val o = 150f + c * 500f
            near(-2.7, shake(o + 100f), "20 % of cycle $c"); near(2.7, shake(o + 200f), "40 %")
            near(-1.8, shake(o + 300f), "60 %"); near(1.8, shake(o + 400f), "80 %")
            near(-2.7 * ref(easeInOut, 0.5), shake(o + 50f), "10 % (ease-in-out per segment)")
            near(-2.7 + 5.4 * ref(easeInOut, 0.5), shake(o + 150f), "30 %")
        }
        near(0.0, shake(1_150f), "after two cycles", 1e-5); assertEquals(0f, shake(2_000f))
        // The wave: −8° at 0, +8° at 600, −8° at 1 200, alternate, ease-in-out.
        near(-8.0, wave(0f), "wave 0"); near(8.0, wave(600f), "wave 600"); near(-8.0, wave(1_200f), "wave 1 200")
        near(-8.0 + 16.0 * ref(easeInOut, 0.5), wave(300f), "wave 300"); near(8.0 - 16.0 * ref(easeInOut, 0.25), wave(750f), "wave 750")
        near(8.0, wave(7_800f), "still waving at 7 800"); assertTrue(abs(wave(7_999f)) <= 8f)
        // Still: no shake, icon at 0°.
        StackedNflTimeline.sampleFlag(400f, true, f)
        assertEquals(0f, f.shakeDx); assertEquals(0f, f.waveDeg)
        // Review fix C-N5: +∞ (the end) and NaN never write NaN; the end rests the icon at 0°.
        assertEquals(0f, wave(Float.POSITIVE_INFINITY)); assertEquals(0f, shake(Float.POSITIVE_INFINITY))
        assertTrue(wave(Float.NaN).isFinite()); assertTrue(shake(Float.NaN).isFinite())
        // The keyframes in the constants (the mock's ±6 / ±4 at .45).
        near(6.0 * 0.45, K.STACKED_NFL_SHAKE_DX1, "dx1"); near(4.0 * 0.45, K.STACKED_NFL_SHAKE_DX2, "dx2")
        // The pivot (the mock's transform-origin 4px 26px in the 30 × 34 icon) inside the 15.8 × 17.9 icon: x at 15.8 / 30;
        // y as commit 0 set it (13.8; 26 × 17.9 / 34 is 13.69, a 0.11-px difference, reported to the lead).
        near(4.0 * 15.8 / 30.0, K.STACKED_NFL_WAVE_PIVOT_X, "pivot x", 0.01); near(26.0 * 17.9 / 34.0, K.STACKED_NFL_WAVE_PIVOT_Y, "pivot y (integration ruling: the mock 13.69)", 0.01)
    }

    // ------------------------------------------------------------------ still, pinned, lengths

    @Test
    fun stillSnapsEveryTrackToItsRest() {
        val open = rest(true)
        assertEquals(1f, open.clockWidth); assertEquals(1f, open.barHeight); assertFalse(open.barClosed)
        assertEquals(0f, open.stripeAX); assertEquals(1f, open.barTextAlpha); assertEquals(0f, open.barTextDx)
        assertEquals(1f, open.stripeAway); assertEquals(1f, open.ballAwayAlpha); assertEquals(1f, open.ballHomeScale)
        val closed = rest(false)
        assertEquals(0f, closed.clockWidth); assertEquals(0f, closed.barHeight); assertTrue(closed.barClosed)
        assertEquals(K.STACKED_BAR_STRIPE_TRAVEL, closed.stripeBX); assertEquals(K.STACKED_NFL_REKEY_TEXT_DX, closed.barTextDx)
        assertEquals(0f, closed.ballAwayAlpha); assertEquals(0.4f, closed.ballAwayScale); assertEquals(-40f, closed.ballHomeDeg)
        for (tr in StackedNflTrack.entries) assertEquals(0f, StackedNflTimeline.totalMs(tr, S), "$tr still")
        // The frame's boxW / barH: 333 / 243 and 33 / 0.
        assertEquals(333f, open.boxW); assertEquals(243f, closed.boxW); assertEquals(33f, open.barH); assertEquals(0f, closed.barH)
    }

    @Test
    fun thePinSnapsTheLayoutChannelsOnly() {
        val from = rest(false)
        val out = StackedNflFrame()
        StackedNflTimeline.samplePinned(StackedNflTrack.BAR, StackedNflTransition.OPEN, 100f, from, false, out)
        assertEquals(1f, out.barHeight); assertFalse(out.barClosed)
        assertEquals(K.STACKED_BAR_STRIPE_TRAVEL, out.stripeAX, "the intro runs on its own clock")
        StackedNflTimeline.samplePinned(StackedNflTrack.CLOCK, StackedNflTransition.CLOSE, 100f, rest(true), false, out)
        assertEquals(0f, out.clockWidth)
        near(1 - ref(ease, 100.0 / 150.0), out.clockAlpha, "contents on their own clock")
        StackedNflTimeline.samplePinned(StackedNflTrack.BAR, StackedNflTransition.CLOSE, 100f, rest(true), false, out)
        assertEquals(0f, out.barHeight); assertTrue(out.barClosed)
        // After every transition's end the pinned and plain samples agree (all over by 2 000 < 3 600).
        for (tr in tracks) for (k in listOf(StackedNflTransition.OPEN, StackedNflTransition.CLOSE, StackedNflTransition.REKEY)) {
            val a = StackedNflTimeline.samplePinned(tr, k, 3_600f, rest(k == StackedNflTransition.CLOSE), false, StackedNflFrame())
            val b = StackedNflTimeline.sample(tr, k, 3_600f, rest(k == StackedNflTransition.CLOSE), false, StackedNflFrame())
            assertEquals(b.clockWidth, a.clockWidth, "$tr $k"); assertEquals(b.barHeight, a.barHeight, "$tr $k")
            assertTrue(StackedNflTimeline.totalMs(tr, k) <= 2_000f)
        }
    }

    @Test
    fun theLengths() {
        val o = StackedNflTransition.OPEN
        val c = StackedNflTransition.CLOSE
        assertEquals(800f, StackedNflTimeline.totalMs(StackedNflTrack.CLOCK, o))
        assertEquals(450f, StackedNflTimeline.totalMs(StackedNflTrack.CLOCK, c))
        assertEquals(850f, StackedNflTimeline.totalMs(StackedNflTrack.BAR, o))
        assertEquals(400f, StackedNflTimeline.totalMs(StackedNflTrack.BAR, c))
        assertEquals(550f, StackedNflTimeline.totalMs(StackedNflTrack.BAR, StackedNflTransition.REKEY))
        assertEquals(400f, StackedNflTimeline.totalMs(StackedNflTrack.STRIPE_AWAY, o))
        assertEquals(400f, StackedNflTimeline.totalMs(StackedNflTrack.BALL_HOME, c))
        // At the length every channel is at its end (sampled 1 ms later equals sampled at the length).
        for (tr in tracks) for (k in listOf(o, c, StackedNflTransition.REKEY)) {
            val len = StackedNflTimeline.totalMs(tr, k)
            val a = StackedNflTimeline.sample(tr, k, len, rest(k == c), false, StackedNflFrame())
            val b = StackedNflTimeline.sample(tr, k, len + 500f, rest(k == c), false, StackedNflFrame())
            assertEquals(b.clockWidth, a.clockWidth); assertEquals(b.clockAlpha, a.clockAlpha)
            assertEquals(b.barHeight, a.barHeight); assertEquals(b.stripeAX, a.stripeAX); assertEquals(b.stripeBX, a.stripeBX)
            assertEquals(b.barTextAlpha, a.barTextAlpha); assertEquals(b.barTextDx, a.barTextDx); assertEquals(b.barBgMix, a.barBgMix)
            assertEquals(b.ballAwayAlpha, a.ballAwayAlpha); assertEquals(b.ballHomeScale, a.ballHomeScale)
            assertEquals(b.stripeAway, a.stripeAway); assertEquals(b.stripeHome, a.stripeHome)
        }
    }

    @Test
    fun theLayoutChannelsAreMonotonic() {
        // Width, height and stripe channels never reverse within one transition (Std / ease / ease-in never overshoot).
        for (k in listOf(StackedNflTransition.OPEN, StackedNflTransition.CLOSE)) {
            val from = rest(k == StackedNflTransition.CLOSE)
            val sign = if (k == StackedNflTransition.OPEN) 1f else -1f
            var last = StackedNflTimeline.sample(StackedNflTrack.BAR, k, 0f, from, false, StackedNflFrame()).barHeight
            var lastW = StackedNflTimeline.sample(StackedNflTrack.CLOCK, k, 0f, from, false, StackedNflFrame()).clockWidth
            var t = 1f
            while (t <= 900f) {
                val h = StackedNflTimeline.sample(StackedNflTrack.BAR, k, t, from, false, StackedNflFrame()).barHeight
                val w = StackedNflTimeline.sample(StackedNflTrack.CLOCK, k, t, from, false, StackedNflFrame()).clockWidth
                assertTrue(sign * (h - last) >= -1e-6f, "$k bar height at $t"); assertTrue(sign * (w - lastW) >= -1e-6f, "$k clock at $t")
                assertTrue(h in 0f..1f && w in 0f..1f)
                last = h; lastW = w; t += 1f
            }
        }
        // The REKEY background mix rises 0 → 1.
        var last = -1f
        var t = 0f
        while (t <= 550f) {
            val m = StackedNflTimeline.sample(StackedNflTrack.BAR, StackedNflTransition.REKEY, t, rest(true), false, StackedNflFrame()).barBgMix
            assertTrue(m >= last); last = m; t += 1f
        }
    }

    private val trackArray: Array<StackedNflTrack> = StackedNflTrack.entries.toTypedArray()

    @Test
    fun samplingIsAllocationFree() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val from = rest(true)
        val out = StackedNflFrame()
        fun run(n: Int) {
            for (i in 0 until n) {
                val t = (i % 2_200).toFloat()
                for (j in trackArray.indices) {
                    val tr = trackArray[j]
                    StackedNflTimeline.sample(tr, StackedNflTransition.REKEY, t, from, true, out)
                    StackedNflTimeline.samplePinned(tr, StackedNflTransition.CLOSE, t, from, false, out)
                }
                StackedNflTimeline.samplePips(Side.HOME, StackedNflTransition.BURN, 3, 2, t, from, out)
                StackedNflTimeline.sampleFlag(t, false, out)
            }
        }
        run(2_000)
        val before = bean.getThreadAllocatedBytes(id)
        run(20_000)
        val after = bean.getThreadAllocatedBytes(id)
        assertTrue(after - before < 4_096, "allocated ${after - before} bytes")
    }
}
