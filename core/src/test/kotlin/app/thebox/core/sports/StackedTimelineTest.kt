package app.thebox.core.sports

import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import app.thebox.core.sports.SportsConstants as K

/**
 * docs/design/m6-score-bug-stacked-theme.md §7.1, §10.1 `StackedTimelineTest`: every §7.1 row at its start, the end of
 * its delay, its midpoint and its end (against an independent reference bezier); interruption from the current value;
 * the change and text-swap sequences; the still snap; the pinned snap; the lengths; an allocation-free `sample`.
 */
class StackedTimelineTest {
    /** Reference CSS cubic-bezier: plain bisection on x(t) in doubles (independent of the solver under test). */
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

    private fun near(expected: Double, actual: Float, what: String, tol: Double = 1e-3) =
        assertTrue(abs(expected - actual) <= tol, "$what: expected $expected, got $actual")

    /** A frame with every track at rest, open (wide) or closed (narrow). */
    private fun rest(open: Boolean): StackedFrame {
        val f = StackedFrame()
        val tmp = StackedFrame()
        for (t in StackedTrack.entries) StackedTimeline.sample(t, StackedTransition.STILL, 0f, tmp, open, f)
        return f
    }

    /** One §7.1 row: a channel of [track] under [kind], from [a] to [b], after [delay] over [dur] through [curve]. */
    private class Row(
        val name: String,
        val track: StackedTrack,
        val kind: StackedTransition,
        val get: (StackedFrame) -> Float,
        val a: Float,
        val b: Float,
        val delay: Long,
        val dur: Long,
        val curve: DoubleArray,
    )

    private val rows: List<Row> by lazy {
        val o = StackedTransition.OPEN
        val c = StackedTransition.CLOSE
        val sog = StackedTrack.SOG
        val clock = StackedTrack.CLOCK
        val bar = StackedTrack.BAR
        listOf(
            Row("SOG width open", sog, o, { it.sogWidth }, 0f, 1f, 0, 450, std),
            Row("SOG width close", sog, c, { it.sogWidth }, 1f, 0f, 350, 450, std),
            Row("SOG alpha open", sog, o, { it.sogAlpha }, 0f, 1f, 300, 300, ease),
            Row("SOG x open", sog, o, { it.sogDx }, -5.4f, 0f, 300, 350, ease),
            Row("SOG alpha close", sog, c, { it.sogAlpha }, 1f, 0f, 0, 200, ease),
            Row("SOG x close", sog, c, { it.sogDx }, 0f, -5.4f, 0, 200, ease),
            Row("clock width close", clock, c, { it.clockWidth }, 1f, 0f, 0, 450, std),
            Row("clock width open", clock, o, { it.clockWidth }, 0f, 1f, 350, 450, std),
            Row("clock alpha close", clock, c, { it.clockAlpha }, 1f, 0f, 0, 150, ease),
            Row("clock alpha open", clock, o, { it.clockAlpha }, 0f, 1f, 500, 300, ease),
            Row("bar height open", bar, o, { it.barHeight }, 0f, 1f, 400, 400, std),
            Row("bar height close", bar, c, { it.barHeight }, 1f, 0f, 0, 400, std),
            Row("stripe A open", bar, o, { it.stripeAX }, -63f, 0f, 700, 450, spring),
            Row("stripe B open", bar, o, { it.stripeBX }, -63f, 0f, 780, 450, spring),
            Row("stripe A close", bar, c, { it.stripeAX }, 0f, -63f, 0, 300, easeIn),
            Row("stripe B close", bar, c, { it.stripeBX }, 0f, -63f, 0, 300, easeIn),
            Row("bar text alpha open", bar, o, { it.barTextAlpha }, 0f, 1f, 850, 350, ease),
            Row("bar text x open", bar, o, { it.barTextDx }, -10.8f, 0f, 850, 450, spring),
            Row("bar text alpha close", bar, c, { it.barTextAlpha }, 1f, 0f, 0, 150, ease),
            Row("bar text x close", bar, c, { it.barTextDx }, 0f, -10.8f, 0, 150, ease),
            Row("away stripe widen", StackedTrack.STRIPE_AWAY, o, { it.stripeAway }, 0f, 1f, 0, 400, ease),
            Row("away stripe narrow", StackedTrack.STRIPE_AWAY, c, { it.stripeAway }, 1f, 0f, 0, 400, ease),
            Row("home stripe widen", StackedTrack.STRIPE_HOME, o, { it.stripeHome }, 0f, 1f, 0, 400, ease),
            Row("home stripe narrow", StackedTrack.STRIPE_HOME, c, { it.stripeHome }, 1f, 0f, 0, 400, ease),
        )
    }

    @Test
    fun theConstantsAreTheMocksScaled() {
        assertEquals(-12f * K.STACKED_SCALE, K.STACKED_SOG_DX, 1e-5f)
        assertEquals(-140f * K.STACKED_SCALE, K.STACKED_BAR_STRIPE_TRAVEL, 1e-4f)
        assertEquals(-24f * K.STACKED_SCALE, K.STACKED_BAR_TEXT_DX, 1e-5f)
        assertEquals(58.5f, K.STACKED_SOG_W); assertEquals(90f, K.STACKED_CLOCK_W); assertEquals(243f, K.STACKED_ROWS_W)
    }

    @Test
    fun everyRowAtStartDelayEndMidpointAndEnd() {
        val out = StackedFrame()
        for (r in rows) {
            val from = rest(r.kind == StackedTransition.CLOSE)
            assertEquals(r.a, r.get(from), "${r.name}: the rest it starts from")
            fun at(t: Float) = r.get(StackedTimeline.sample(r.track, r.kind, t, from, false, out))
            assertEquals(r.a, at(0f), "${r.name} at 0")
            assertEquals(r.a, at(r.delay.toFloat()), "${r.name} at the delay's end")
            if (r.delay > 0) assertEquals(r.a, at(r.delay / 2f), "${r.name} during the delay")
            val mid = r.delay + r.dur / 2f
            near(r.a + (r.b - r.a) * ref(r.curve, 0.5), at(mid), "${r.name} at the midpoint")
            near(r.a + (r.b - r.a) * ref(r.curve, 0.25), at(r.delay + r.dur * 0.25f), "${r.name} at a quarter")
            assertEquals(r.b, at((r.delay + r.dur).toFloat()), "${r.name} at the end")
            assertEquals(r.b, at(Float.POSITIVE_INFINITY), "${r.name} at +∞")
            assertEquals(r.a, at(Float.NaN), "${r.name} at NaN")
            assertEquals(r.a, at(-5f), "${r.name} at −5")
        }
    }

    @Test
    fun theOrderFallsOutOfTheDelays() {
        // Into a power play: SOG opens (0–450), the bar drops (400–800), stripes slide in (700–1 230), text (850–1 300).
        assertEquals(650f, StackedTimeline.totalMs(StackedTrack.SOG, StackedTransition.OPEN))
        assertEquals(1_300f, StackedTimeline.totalMs(StackedTrack.BAR, StackedTransition.OPEN))
        // Out of it: text and stripes leave, the bar lifts (0–400), SOG closes (350–800).
        assertEquals(400f, StackedTimeline.totalMs(StackedTrack.BAR, StackedTransition.CLOSE))
        assertEquals(800f, StackedTimeline.totalMs(StackedTrack.SOG, StackedTransition.CLOSE))
        assertEquals(450f, StackedTimeline.totalMs(StackedTrack.CLOCK, StackedTransition.CLOSE))
        assertEquals(800f, StackedTimeline.totalMs(StackedTrack.CLOCK, StackedTransition.OPEN))
        assertEquals(900f, StackedTimeline.totalMs(StackedTrack.BAR, StackedTransition.CHANGE))
        assertEquals(600f, StackedTimeline.totalMs(StackedTrack.BAR, StackedTransition.SWAP))
        for (t in listOf(StackedTrack.STRIPE_AWAY, StackedTrack.STRIPE_HOME)) for (k in listOf(StackedTransition.OPEN, StackedTransition.CLOSE)) {
            assertEquals(400f, StackedTimeline.totalMs(t, k))
        }
        for (t in StackedTrack.entries) assertEquals(0f, StackedTimeline.totalMs(t, StackedTransition.STILL))
        // Every transition is at rest at its length: the sample there equals the STILL rest.
        val out = StackedFrame()
        val want = StackedFrame()
        for (t in StackedTrack.entries) for (k in StackedTransition.entries) {
            if (k == StackedTransition.STILL) continue
            val open = k != StackedTransition.CLOSE
            val from = rest(!open)
            out.copyFrom(from); want.copyFrom(from)
            StackedTimeline.sample(t, k, StackedTimeline.totalMs(t, k), from, false, out)
            StackedTimeline.sample(t, StackedTransition.STILL, 0f, from, open, want)
            assertFrames(want, out, "$t $k at its length")
        }
    }

    private fun assertFrames(a: StackedFrame, b: StackedFrame, what: String) {
        assertEquals(a.sogWidth, b.sogWidth, "$what sogWidth"); assertEquals(a.sogAlpha, b.sogAlpha, "$what sogAlpha")
        assertEquals(a.sogDx, b.sogDx, "$what sogDx"); assertEquals(a.clockWidth, b.clockWidth, "$what clockWidth")
        assertEquals(a.clockAlpha, b.clockAlpha, "$what clockAlpha"); assertEquals(a.barHeight, b.barHeight, "$what barHeight")
        assertEquals(a.stripeAX, b.stripeAX, "$what stripeAX"); assertEquals(a.stripeBX, b.stripeBX, "$what stripeBX")
        assertEquals(a.barTextAlpha, b.barTextAlpha, "$what barTextAlpha"); assertEquals(a.barTextDx, b.barTextDx, "$what barTextDx")
        assertEquals(a.barBgMix, b.barBgMix, "$what barBgMix"); assertEquals(a.barSwapped, b.barSwapped, "$what barSwapped")
        assertEquals(a.barClosed, b.barClosed, "$what barClosed")
        assertEquals(a.stripeAway, b.stripeAway, "$what stripeAway"); assertEquals(a.stripeHome, b.stripeHome, "$what stripeHome")
    }

    @Test
    fun sampleWritesOnlyItsTracksChannels() {
        for (t in StackedTrack.entries) for (k in StackedTransition.entries) {
            val out = StackedFrame()
            // Poison every channel, sample one track, and check the others were left alone.
            out.sogWidth = 9f; out.sogAlpha = 9f; out.sogDx = 9f; out.clockWidth = 9f; out.clockAlpha = 9f
            out.barHeight = 9f; out.stripeAX = 9f; out.stripeBX = 9f; out.barTextAlpha = 9f; out.barTextDx = 9f
            out.barBgMix = 9f; out.barSwapped = false; out.barClosed = false; out.stripeAway = 9f; out.stripeHome = 9f
            StackedTimeline.sample(t, k, 200f, rest(false), true, out)
            if (t != StackedTrack.SOG) { assertEquals(9f, out.sogWidth); assertEquals(9f, out.sogAlpha); assertEquals(9f, out.sogDx) }
            if (t != StackedTrack.CLOCK) { assertEquals(9f, out.clockWidth); assertEquals(9f, out.clockAlpha) }
            if (t != StackedTrack.BAR) {
                assertEquals(9f, out.barHeight); assertEquals(9f, out.stripeAX); assertEquals(9f, out.barTextAlpha)
                assertEquals(9f, out.barBgMix); assertFalse(out.barClosed)
            }
            if (t != StackedTrack.STRIPE_AWAY) assertEquals(9f, out.stripeAway, "$t $k")
            if (t != StackedTrack.STRIPE_HOME) assertEquals(9f, out.stripeHome, "$t $k")
        }
    }

    @Test
    fun anInterruptionStartsFromTheCurrentValueWithFullDurationAndDelay() {
        // A SOG open interrupted at 200 ms by a close: the width starts from where it was and holds through the 350 delay.
        val a = StackedFrame()
        val mid = StackedTimeline.sample(StackedTrack.SOG, StackedTransition.OPEN, 200f, rest(false), false, StackedFrame())
        val w0 = mid.sogWidth
        near(ref(std, 200.0 / 450), w0, "open at 200")
        StackedTimeline.sample(StackedTrack.SOG, StackedTransition.CLOSE, 0f, mid, false, a)
        assertEquals(w0, a.sogWidth)
        StackedTimeline.sample(StackedTrack.SOG, StackedTransition.CLOSE, 350f, mid, false, a)
        assertEquals(w0, a.sogWidth, "the full delay")
        StackedTimeline.sample(StackedTrack.SOG, StackedTransition.CLOSE, 350f + 225f, mid, false, a)
        near(w0 * (1 - ref(std, 0.5)), a.sogWidth, "the full duration from the current value")
        StackedTimeline.sample(StackedTrack.SOG, StackedTransition.CLOSE, 800f, mid, false, a)
        assertEquals(0f, a.sogWidth)
        // A bar close interrupted at 100 ms by an open: the height re-opens from where it was after the full 400 delay.
        val closing = StackedTimeline.sample(StackedTrack.BAR, StackedTransition.CLOSE, 100f, rest(true), false, StackedFrame())
        val h0 = closing.barHeight
        assertTrue(h0 in 0.01f..0.99f)
        StackedTimeline.sample(StackedTrack.BAR, StackedTransition.OPEN, 399f, closing, false, a)
        assertEquals(h0, a.barHeight)
        assertFalse(a.barClosed)
        StackedTimeline.sample(StackedTrack.BAR, StackedTransition.OPEN, 600f, closing, false, a)
        near(h0 + (1 - h0) * ref(std, 0.5), a.barHeight, "re-open midpoint")
        // No reversing-shortening: a stripe narrowing 10 % of the way still takes 400 ms back.
        val widened = StackedTimeline.sample(StackedTrack.STRIPE_HOME, StackedTransition.OPEN, 40f, rest(false), false, StackedFrame())
        StackedTimeline.sample(StackedTrack.STRIPE_HOME, StackedTransition.CLOSE, 399f, widened, false, a)
        assertTrue(a.stripeHome > 0f)
        StackedTimeline.sample(StackedTrack.STRIPE_HOME, StackedTransition.CLOSE, 400f, widened, false, a)
        assertEquals(0f, a.stripeHome)
    }

    @Test
    fun theBarClosedFlagFollowsTheHeight() {
        val out = StackedFrame()
        StackedTimeline.sample(StackedTrack.BAR, StackedTransition.OPEN, 0f, rest(false), false, out)
        assertTrue(out.barClosed, "nothing to draw during the open's delay")
        StackedTimeline.sample(StackedTrack.BAR, StackedTransition.OPEN, 401f, rest(false), false, out)
        assertFalse(out.barClosed)
        StackedTimeline.sample(StackedTrack.BAR, StackedTransition.CLOSE, 399f, rest(true), false, out)
        assertFalse(out.barClosed)
        StackedTimeline.sample(StackedTrack.BAR, StackedTransition.CLOSE, 400f, rest(true), false, out)
        assertTrue(out.barClosed)
    }

    @Test
    fun theChangeSequence() {
        // From an open bar: stripes and text out, background lerp and content swap at 300, stripes back at 300 / 380,
        // text at 450; ≈ 900.
        val from = rest(true)
        val out = StackedFrame()
        fun at(t: Float) = StackedTimeline.sample(StackedTrack.BAR, StackedTransition.CHANGE, t, from, false, out)
        at(0f)
        assertEquals(1f, out.barHeight); assertFalse(out.barClosed); assertFalse(out.barSwapped)
        assertEquals(0f, out.barBgMix); assertEquals(0f, out.stripeAX); assertEquals(1f, out.barTextAlpha)
        at(75f)
        near(1 - ref(ease, 0.5), out.barTextAlpha, "text out midpoint at 75")
        near(-63.0 * ref(easeIn, 0.25), out.stripeAX, "stripe A out at 75")
        at(150f)
        assertEquals(0f, out.barTextAlpha); assertEquals(-10.8f, out.barTextDx)
        near(ref(ease, 0.5), out.barBgMix, "bg mix at 150")
        at(299.9f)
        assertFalse(out.barSwapped)
        near(-63.0, out.stripeAX, "stripe A hidden just before the swap", 0.05)
        at(300f)
        assertTrue(out.barSwapped, "the content swaps at 300")
        assertEquals(1f, out.barBgMix)
        assertEquals(-63f, out.stripeAX); assertEquals(-63f, out.stripeBX); assertEquals(0f, out.barTextAlpha)
        at(300f + 225f)
        near(-63.0 * (1 - ref(spring, 0.5)), out.stripeAX, "stripe A back, midpoint")
        assertEquals(-63f + 63f * StackedTimeline.SPRING.ease(145f / 450f), out.stripeBX, 1e-4f)
        at(380f); assertEquals(-63f, out.stripeBX, "B waits for 380")
        at(450f); assertEquals(0f, out.barTextAlpha); assertEquals(-10.8f, out.barTextDx)
        at(450f + 175f); near(ref(ease, 0.5), out.barTextAlpha, "text alpha back, midpoint")
        at(450f + 225f); near(-10.8 * (1 - ref(spring, 0.5)), out.barTextDx, "text x back, midpoint")
        at(800f); assertEquals(1f, out.barTextAlpha)
        at(830f); assertEquals(0f, out.stripeBX)
        at(900f)
        assertEquals(0f, out.stripeAX); assertEquals(0f, out.stripeBX); assertEquals(1f, out.barTextAlpha); assertEquals(0f, out.barTextDx)
        assertEquals(1f, out.barBgMix); assertTrue(out.barSwapped); assertEquals(1f, out.barHeight)
        // A change that interrupts an open still mid-height finishes the height (400 Std, no delay).
        val half = StackedTimeline.sample(StackedTrack.BAR, StackedTransition.OPEN, 600f, rest(false), false, StackedFrame())
        StackedTimeline.sample(StackedTrack.BAR, StackedTransition.CHANGE, 400f, half, false, out)
        assertEquals(1f, out.barHeight)
    }

    @Test
    fun theTextSwapSequence() {
        // Text out (α, x) 150, swap, text in 350 / 450; stripes and background untouched; 600.
        val from = rest(true)
        val out = StackedFrame()
        fun at(t: Float) = StackedTimeline.sample(StackedTrack.BAR, StackedTransition.SWAP, t, from, false, out)
        for (t in listOf(0f, 75f, 149f, 150f, 300f, 599f, 600f)) {
            at(t)
            assertEquals(0f, out.stripeAX, "stripe A at $t"); assertEquals(0f, out.stripeBX, "stripe B at $t")
            assertEquals(1f, out.barBgMix, "bg at $t"); assertEquals(1f, out.barHeight, "height at $t")
            assertEquals(t >= 150f, out.barSwapped, "swapped at $t")
        }
        at(0f); assertEquals(1f, out.barTextAlpha)
        at(75f); near(1 - ref(ease, 0.5), out.barTextAlpha, "out midpoint"); near(-10.8 * ref(ease, 0.5), out.barTextDx, "out x midpoint")
        at(150f); assertEquals(0f, out.barTextAlpha); assertEquals(-10.8f, out.barTextDx)
        at(150f + 175f); near(ref(ease, 0.5), out.barTextAlpha, "in α midpoint")
        at(150f + 225f); near(-10.8 * (1 - ref(spring, 0.5)), out.barTextDx, "in x midpoint")
        at(500f); assertEquals(1f, out.barTextAlpha)
        at(600f); assertEquals(0f, out.barTextDx)
        // A swap that interrupts a change mid-lerp finishes the background and the stripes toward rest.
        val changing = StackedTimeline.sample(StackedTrack.BAR, StackedTransition.CHANGE, 500f, from, false, StackedFrame())
        assertTrue(changing.stripeBX < 0f)
        StackedTimeline.sample(StackedTrack.BAR, StackedTransition.SWAP, 600f, changing, false, out)
        assertEquals(0f, out.stripeAX); assertEquals(0f, out.stripeBX); assertEquals(1f, out.barBgMix)
    }

    @Test
    fun stillSnapsToRest() {
        for (t in StackedTrack.entries) for (open in listOf(true, false)) {
            val out = StackedFrame()
            val from = rest(!open)
            for (time in listOf(0f, 100f, Float.NaN)) {
                StackedTimeline.sample(t, StackedTransition.STILL, time, from, open, out)
                when (t) {
                    StackedTrack.SOG -> { assertEquals(if (open) 1f else 0f, out.sogWidth); assertEquals(if (open) 1f else 0f, out.sogAlpha) }
                    StackedTrack.CLOCK -> { assertEquals(if (open) 1f else 0f, out.clockWidth); assertEquals(if (open) 1f else 0f, out.clockAlpha) }
                    StackedTrack.BAR -> {
                        assertEquals(if (open) 1f else 0f, out.barHeight); assertEquals(!open, out.barClosed)
                        assertEquals(if (open) 0f else -63f, out.stripeAX); assertEquals(if (open) 1f else 0f, out.barTextAlpha)
                    }
                    StackedTrack.STRIPE_AWAY -> assertEquals(if (open) 1f else 0f, out.stripeAway)
                    StackedTrack.STRIPE_HOME -> assertEquals(if (open) 1f else 0f, out.stripeHome)
                }
            }
        }
    }

    @Test
    fun thePinnedSnap() {
        // A takeover starts while the SOG column opens, the clock folds and the bar drops: the widths and the bar height
        // snap to the ends of their transitions; contents, stripes and text finish on their own clock.
        val out = StackedFrame()
        val closed = rest(false)
        for (t in listOf(0f, 100f, 420f, 900f)) {
            StackedTimeline.samplePinned(StackedTrack.SOG, StackedTransition.OPEN, t, closed, false, out)
            assertEquals(1f, out.sogWidth, "SOG width pinned open at $t")
            assertEquals(StackedTimeline.sample(StackedTrack.SOG, StackedTransition.OPEN, t, closed, false, StackedFrame()).sogAlpha, out.sogAlpha)
            StackedTimeline.samplePinned(StackedTrack.CLOCK, StackedTransition.CLOSE, t, rest(true), false, out)
            assertEquals(0f, out.clockWidth, "clock width pinned folded at $t")
            StackedTimeline.samplePinned(StackedTrack.BAR, StackedTransition.OPEN, t, closed, false, out)
            assertEquals(1f, out.barHeight, "bar height pinned open at $t")
            assertFalse(out.barClosed)
            val plain = StackedTimeline.sample(StackedTrack.BAR, StackedTransition.OPEN, t, closed, false, StackedFrame())
            assertEquals(plain.stripeAX, out.stripeAX, "stripes finish their own transition")
            assertEquals(plain.barTextAlpha, out.barTextAlpha, "text finishes its own transition")
            StackedTimeline.samplePinned(StackedTrack.BAR, StackedTransition.CLOSE, t, rest(true), false, out)
            assertEquals(0f, out.barHeight)
            assertTrue(out.barClosed)
            // The row stripes have no layout channel.
            StackedTimeline.samplePinned(StackedTrack.STRIPE_HOME, StackedTransition.OPEN, t, closed, false, out)
            assertEquals(StackedTimeline.sample(StackedTrack.STRIPE_HOME, StackedTransition.OPEN, t, closed, false, StackedFrame()).stripeHome, out.stripeHome)
        }
        // So the box size is fixed for the takeover: boxW and barH are their end values from the first pinned frame.
        val f = StackedFrame() // the play rest: clock open
        StackedTimeline.samplePinned(StackedTrack.SOG, StackedTransition.OPEN, 10f, rest(false), false, f)
        StackedTimeline.samplePinned(StackedTrack.BAR, StackedTransition.OPEN, 10f, rest(false), false, f)
        assertEquals(391.5f, f.boxW)
        assertEquals(K.STACKED_BAR_H, f.barH)
        // By the 3 600 release every transition is over, so the pinned and the plain samples agree.
        for (t in StackedTrack.entries) for (k in StackedTransition.entries) {
            val a = StackedTimeline.samplePinned(t, k, 3_600f, closed, true, StackedFrame())
            val b = StackedTimeline.sample(t, k, 3_600f, closed, true, StackedFrame())
            assertFrames(b, a, "$t $k at 3 600")
            assertTrue(StackedTimeline.totalMs(t, k) < 3_600f)
        }
    }

    @Test
    fun theBoxWidthAndBarHeight() {
        assertEquals(243f, rest(false).boxW, "every column folded: the rows alone")
        assertEquals(333f, StackedFrame().boxW, "a new frame is the play rest: rows + clock")
        val g = StackedFrame()
        StackedTimeline.sample(StackedTrack.SOG, StackedTransition.STILL, 0f, StackedFrame(), true, g)
        assertEquals(391.5f, g.boxW, "power play")
        StackedTimeline.sample(StackedTrack.CLOCK, StackedTransition.STILL, 0f, StackedFrame(), false, g)
        assertEquals(301.5f, g.boxW, "break")
        assertEquals(0f, g.barH)
        StackedTimeline.sample(StackedTrack.BAR, StackedTransition.STILL, 0f, StackedFrame(), true, g)
        assertEquals(33f, g.barH)
    }

    @Test
    fun sampleAllocatesNothing() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val from = rest(true)
        val out = StackedFrame()
        val tracks = StackedTrack.entries.toTypedArray()
        val kinds = StackedTransition.entries.toTypedArray()
        fun run(n: Int, off: Float) {
            for (i in 0 until n) {
                val t = tracks[i % tracks.size]
                val k = kinds[(i / tracks.size) % kinds.size]
                StackedTimeline.sample(t, k, (i % 1_400).toFloat() + off, from, (i and 1) == 0, out)
                StackedTimeline.samplePinned(t, k, (i % 1_400).toFloat() + off, from, (i and 1) == 0, out)
            }
        }
        run(20_000, 0f)
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        run(20_000, 0.5f)
        val after = bean.getThreadAllocatedBytes(id)
        assertTrue(after - before < 1024, "allocated ${after - before} bytes")
    }
}
