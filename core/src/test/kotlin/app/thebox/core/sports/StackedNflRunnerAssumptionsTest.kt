package app.thebox.core.sports

import app.thebox.core.sports.SportsConstants as K
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * NFL Stacked integration pass (2026-11-15): the WP-4 runner's assumptions about the WP-2 timelines, table and spec,
 * checked against the real code (the integration notes' list, items 1–6). Items 7–11 are covered end to end in `:ui`
 * (`StackedNflEndToEndTest`, `StackedNflBoundsTest`, `StackedNflDrawTest`).
 */
class StackedNflRunnerAssumptionsTest {
    private val tracks = StackedNflTrack.entries
    private val T = StackedNflTimeline

    /** 1. Every transition the runner plays has a length (its driver's target); STILL is a snap (0). */
    @Test
    fun everyPlayedTransitionHasALengthAndBarOpenIncludesTheIntro() {
        for (track in tracks) {
            assertEquals(0f, T.totalMs(track, StackedNflTransition.STILL), "$track STILL")
            assertTrue(T.totalMs(track, StackedNflTransition.OPEN) > 0f, "$track OPEN")
            assertTrue(T.totalMs(track, StackedNflTransition.CLOSE) > 0f, "$track CLOSE")
        }
        assertTrue(T.totalMs(StackedNflTrack.BAR, StackedNflTransition.REKEY) > 0f)
        assertEquals(2_000f, T.totalMs(StackedNflTrack.PIPS_AWAY, StackedNflTransition.BURN))
        assertEquals(2_000f, T.totalMs(StackedNflTrack.PIPS_HOME, StackedNflTransition.BURN))
        // BAR OPEN = the 400 height, its REKEY intro from 300: 300 + 550 = 850; the words are fully in by then.
        val open = T.totalMs(StackedNflTrack.BAR, StackedNflTransition.OPEN)
        assertEquals((T.OPEN_INTRO_MS + K.STACKED_NFL_REKEY_MS).toFloat(), open)
        val out = T.sample(StackedNflTrack.BAR, StackedNflTransition.OPEN, open, StackedNflFrame(), true, StackedNflFrame())
        assertEquals(1f, out.barHeight); assertEquals(1f, out.barTextAlpha); assertEquals(0f, out.stripeAX); assertEquals(0f, out.stripeBX)
    }

    /**
     * 2. A bar pinned mid-OPEN is sampled by `samplePinned` (height 1); the runner's REKEY to the score label starts from
     * that sample, so the height stays 1 throughout (REKEY itself continues a partly open height toward 1, never down).
     */
    @Test
    fun aRekeyFromThePinnedBarKeepsItsFullHeight() {
        val mid = T.sample(StackedNflTrack.BAR, StackedNflTransition.OPEN, 100f, StackedNflFrame(), true, StackedNflFrame())
        assertTrue(mid.barHeight in 0.01f..0.99f)
        val pinned = T.samplePinned(StackedNflTrack.BAR, StackedNflTransition.OPEN, 100f, mid, true, StackedNflFrame())
        assertEquals(1f, pinned.barHeight)
        var t = 0f
        while (t <= 600f) {
            val f = T.sample(StackedNflTrack.BAR, StackedNflTransition.REKEY, t, pinned, true, StackedNflFrame())
            assertEquals(1f, f.barHeight, "t=$t"); assertTrue(!f.barClosed)
            t += 25f
        }
        // From a half-open bar a REKEY only rises.
        var last = mid.barHeight
        t = 0f
        while (t <= 600f) {
            val h = T.sample(StackedNflTrack.BAR, StackedNflTransition.REKEY, t, mid, true, StackedNflFrame()).barHeight
            assertTrue(h >= last - 1e-6f, "t=$t"); last = h; t += 25f
        }
    }

    /** 3. `samplePips` takes (fromLit, toLit) as passed; the burnt pip is index toLit; STILL snaps (the burnt pip used). */
    @Test
    fun theBurnIsAtTheNewCountAndStillSnaps() {
        for (side in Side.entries) for (from in 1..3) {
            val to = from - 1
            val rest = T.samplePips(side, StackedNflTransition.STILL, from, from, 0f, StackedNflFrame(), StackedNflFrame())
            val f = T.samplePips(side, StackedNflTransition.BURN, from, to, 600f, rest, StackedNflFrame())
            val burn = if (side == Side.AWAY) f.pipAwayBurn else f.pipHomeBurn
            val lit = if (side == Side.AWAY) f.pipAway else f.pipHome
            for (i in 0 until StackedNflFrame.PIPS) {
                if (i == to) { assertTrue(burn[i] > 0f, "$side $from: pip $to flashes"); assertEquals(1f, lit[i]) }
                else assertEquals(0f, burn[i], "$side $from: only pip $to")
            }
            val end = T.samplePips(side, StackedNflTransition.BURN, from, to, 2_000f, rest, StackedNflFrame())
            assertEquals(0f, (if (side == Side.AWAY) end.pipAway else end.pipHome)[to], "used at the end")
            val still = T.samplePips(side, StackedNflTransition.STILL, from, to, 0f, rest, StackedNflFrame())
            assertEquals(0f, (if (side == Side.AWAY) still.pipAway else still.pipHome)[to], "STILL: straight to used")
            assertEquals(0f, (if (side == Side.AWAY) still.pipAwayBurn else still.pipHomeBurn)[to])
        }
    }

    /**
     * 4. The runner samples the flag sub-track from 0 to the 8-s hold, then `still` (icon at 0°). At 8 000 the wave is not
     * at 0° (a jump), but the flag mark ends at 8 s on the machine clock, so the bar re-keys away at that moment (the
     * REKEY hides the old words at its 0 %): accepted (integration ruling 5).
     */
    @Test
    fun theFlagSubTrackRestsAtZeroWhenStill() {
        val f = T.sampleFlag(K.NFL_FLAG_MARK_MS.toFloat(), true, StackedNflFrame())
        assertEquals(0f, f.waveDeg); assertEquals(0f, f.shakeDx)
        assertEquals(0f, T.sampleFlag(0f, false, StackedNflFrame()).shakeDx)
        assertTrue(T.sampleFlag(K.NFL_FLAG_MARK_MS.toFloat(), false, StackedNflFrame()).waveDeg != 0f)
        // The REKEY away hides the flag words at once.
        val rest = T.sample(StackedNflTrack.BAR, StackedNflTransition.STILL, 0f, StackedNflFrame(), true, StackedNflFrame())
        assertEquals(0f, T.sample(StackedNflTrack.BAR, StackedNflTransition.REKEY, 0f, rest, true, StackedNflFrame()).barTextAlpha)
    }

    /** 5. A timeout's burn side rides on every held view; the runner burns only on a count change. A break folds. */
    @Test
    fun theTableNamesTheBurnSideOnTheMarkAndFoldsBreaks() {
        val mark = NflTimeoutMark(Side.HOME, 1, confirmed = true)
        val v = SportsFixtures.nflStackedView(homeTimeouts = 1, timeout = mark)
        val t = StackedNflTargets.of(v)
        assertEquals(Side.HOME, t.burnSide)
        assertEquals(NflBarKey(NflBarKind.TIMEOUT, Side.HOME, left = 1), t.bar)
        assertEquals(1, t.homeTimeouts)
        val half = SportsFixtures.nflStackedView(down = null, tab = null).copy(top = CentreText.Halftime, bottom = null)
        assertEquals(false, StackedNflTargets.of(half).clockOpen)
        assertEquals(null, StackedNflTargets.of(half).burnSide)
    }

    /** 6. The lengths the runner sequences the takeover on. */
    @Test
    fun theStackedSpecLengths() {
        val s = NflScoreSpec.STACKED
        assertEquals(4_800f, s.endMs); assertEquals(4_800f, s.pairSecondMs); assertEquals(9_600f, s.pairEndMs)
        assertEquals(1_200f, s.stillFrameBMs)
        assertEquals(K.NFL_TAKEOVER_MS.toFloat() + s.stillFrameBMs, s.endMs)
        // The row flash and the pop run 3 600 → 4 800, inside takeoverOn (the runner holds it until endMs).
        val f = NflScoreTimeline.sample(s, NflScoreKind.TOUCHDOWN, 4_000f, NflScoreFrame())
        assertTrue(!f.done && f.popOn && f.rowFlashAlpha > 0f)
        assertTrue(NflScoreTimeline.sample(s, NflScoreKind.TOUCHDOWN, 4_800f, NflScoreFrame()).done)
    }
}
