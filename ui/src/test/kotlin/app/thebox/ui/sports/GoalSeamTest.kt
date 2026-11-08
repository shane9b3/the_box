package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.NhlTeamColours
import app.thebox.core.sports.PpLabel
import app.thebox.core.sports.ScoreBugPlacement
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.SportsPrefs
import app.thebox.ui.theme.BoxDesignScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.MotionDurationScale
import app.thebox.core.sports.GoalFrame
import app.thebox.core.sports.GoalTimeline
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.abs
import app.thebox.core.sports.GameId
import app.thebox.core.sports.League
import app.thebox.core.sports.ScoreBugView
import kotlinx.coroutines.awaitCancellation

/**
 * Code review 2026-11-07 S1: the real WP-3 → WP-2 path, end to end. [ScoreBugWith] runs with the production hooks
 * ([GoalHooks.Default]'s colour table, sampler, `begin`, `bakeGlow` and `glowAllowed`); the only changes are the probe,
 * a `begin` wrapper that records whether `takeoverOn` was already set when the painter was begun (and then calls the real
 * one), and the painter setup on the test thread (so it lands on the compose test clock).
 *
 * Robolectric runs no draw pass, so the probe hands out the drawing node's own draw ([GoalProbe.draw]) and the test
 * renders it into a bitmap with native graphics, at sampled driver values. The pixels show that a goal draws with the
 * scorer's colours (the panel is CGY's base, the glow is on), and that the pop lands on the scorer's side only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GoalSeamTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val repo = FakeScoreRepository(SportsPrefs())
    private val probe = GoalProbe()
    /** `takeoverOn` as the real `begin` saw it, once per begin: it must be false (the painter is begun first). */
    private val onAtBegin = mutableListOf<Boolean>()

    /** [glow] false: no goal glow outside the pill, so the tab region holds only the tab (the power-play seam test). */
    private fun hooks(context: CoroutineContext, glow: Boolean = true) = GoalHooks(
        begin = { p, side, c, badge, abbrev, a, h ->
            onAtBegin += probe.takeoverOn!!.value
            GoalHooks.Default.begin(p, side, c, badge, abbrev, a, h)
        },
        probe = probe,
        glowAllowed = { p, g, b, m -> glow && ScoreBugPlacement.glowAllowed(p, g, b, m) },
        // Power play: the real timeline (PowerPlayTimeline) and the real painter.
        ppSampler = PpSampler.Real,
        tabAllowed = { _, _, _ -> true },
        // Logos: a filled disc per URL, so the real halo bake runs; a "slow" URL never loads (S3).
        logoFetch = { url, w, h -> if (url.contains("slow")) awaitCancellation() else disc(w, h) },
        context = context,
        background = EmptyCoroutineContext,
    )

    private fun disc(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawOval(1f, 1f, w - 1f, h - 1f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    }

    private fun withLogos(v: ScoreBugView, tag: String) =
        v.copy(away = v.away.copy(logo = "logo/${v.away.abbrev}$tag"), home = v.home.copy(logo = "logo/${v.home.abbrev}$tag"))

    /** [context] = a `MotionDurationScale` of 0 for the still path. */
    private fun start(
        context: CoroutineContext = EmptyCoroutineContext,
        glow: Boolean = true,
        first: ScoreBugView = ScoreBugFixtures.view(clockSecs = 1200),
    ) {
        repo.bug.value = first
        rule.mainClock.autoAdvance = false
        val hooks = hooks(context, glow)
        rule.setContent {
            BoxDesignScale {
                ScoreBugWith(repo, ScoreBugFixtures.input(), ScoreBugFixtures.noGeometry, false, Modifier, ScoreBugPlacement::place, hooks)
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2000)
        rule.waitForIdle()
    }

    private fun t() = probe.driver!!.value
    private fun on() = probe.takeoverOn!!.value

    /** Advances the test clock a frame at a time until the driver reaches [ms]. */
    private fun advanceTo(ms: Float) {
        var guard = 0
        while (t() < ms) {
            rule.mainClock.advanceTimeBy(16)
            rule.waitForIdle()
            check(++guard < 1_000) { "the driver stopped at ${t()}" }
        }
    }

    private class Shot(val bmp: Bitmap, val o: Float) {
        fun px(x: Float, y: Float): Int = bmp.getPixel((x + o).toInt(), (y + o).toInt())
    }

    /** The bug's own draw on a transparent bitmap of the pill + `M`, as its clipped layer would hold it. */
    private fun shot(p: GoalPainter): Shot = shot(p.s, p.pillW, p.pillH)

    /** At Standard under these qualifiers (1920 px wide), 1 design px = 1 device px. */
    private fun shot(s: Float = 1f, pillW: Float = K.SCORE_W * s, pillH: Float = K.SCORE_H * s): Shot {
        val m = K.GOAL_LAYER_MARGIN * s
        val bmp = Bitmap.createBitmap(Math.round(pillW + 2 * m), Math.round(pillH + 2 * m), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(m, m)
        probe.draw!!(c)
        return Shot(bmp, m)
    }

    private fun near(expected: Int, actual: Int, tol: Int) =
        abs(Color.red(expected) - Color.red(actual)) <= tol && abs(Color.green(expected) - Color.green(actual)) <= tol &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tol

    private fun hex(c: Int) = "#" + Integer.toHexString(c).padStart(8, '0').uppercase()

    /** How many pixels of a [halfW] × [halfH] (pill px) box about ([cx], [cy]) differ between [a] and [b]. */
    private fun diff(a: Shot, b: Shot, cx: Float, cy: Float, halfW: Float, halfH: Float): Int {
        var n = 0
        var y = cy - halfH
        while (y <= cy + halfH) {
            var x = cx - halfW
            while (x <= cx + halfW) { if (a.px(x, y) != b.px(x, y)) n++; x += 1f }
            y += 1f
        }
        return n
    }

    @Test
    fun aGoalDrawsWithTheScorersSideColoursAndTally() {
        start()
        // CGY (away) scores: 2 – 1. An asymmetric tally, so a swapped away / home shows.
        repo.bug.value = ScoreBugFixtures.goalView("CGY", Side.AWAY, away = 2, home = 1)
        advanceTo(1_660f)
        assertTrue(on())
        assertEquals("the painter is begun before takeoverOn is set", listOf(false), onAtBegin)
        val p = checkNotNull(probe.painter) { "no painter was begun" }
        val cgy = NhlTeamColours.of("CGY")
        assertEquals(Side.AWAY, p.side)
        assertEquals(cgy, p.colours)
        assertEquals("2 – 1", p.tallyText)

        // t ≈ 1 700 (after the flash and streak 1, before streak 2): the panel is CGY's base, and the glow is drawn.
        assertTrue("sampled before streak 2: ${t()}", t() < 1_745f)
        val mid = shot(p)
        val panel = mid.px(p.pillW / 2f, 6f * p.s)
        assertTrue("panel ${hex(panel)} vs CGY base ${hex(cgy.base)}", near(cgy.base, panel, 3))
        val halo = Color.alpha(mid.px(p.pillW / 2f, -30f * p.s))
        assertTrue("the glow is on and baked: α $halo at 30 px above the pill", halo > 12)

        // t ≈ 3 800: the pop, on the scorer's (away) side only.
        advanceTo(3_800f)
        val pop = shot(p)
        advanceTo(K.GOAL_TAKEOVER_MS + K.GOAL_POP_MS + 0f)
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
        assertFalse("the takeover is over", on())
        val plain = shot(p)
        assertEquals("the plain bug has no glow", 0, Color.alpha(plain.px(p.pillW / 2f, -30f * p.s)))
        val awayCx = (K.SCORE_PAD + K.SCORE_LOGO_W + K.SCORE_LOGO_GAP + K.SCORE_DIGITS_W / 2f) * p.s
        val homeCx = p.pillW - awayCx
        val cy = p.pillH / 2f
        val half = K.SCORE_DIGITS_W / 2f * p.s
        assertTrue("the away digit pops", diff(pop, plain, awayCx, cy, half, half) > 20)
        assertEquals("the home digit does not", 0, diff(pop, plain, homeCx, cy, half, half))
    }

    @Test
    fun aSecondGoalIsBegunWithTheNewTeamNotThePreviousOne() {
        start()
        repo.bug.value = ScoreBugFixtures.goalView("CGY", Side.AWAY, opponent = "EDM", away = 2, home = 1, seq = 1)
        advanceTo(1_000f)
        repo.bug.value = ScoreBugFixtures.goalView("EDM", Side.HOME, opponent = "CGY", away = 2, home = 3, seq = 2)
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
        advanceTo(1_660f)
        assertEquals("each begin ran before takeoverOn was set", listOf(false, false), onAtBegin)
        val p = probe.painter!!
        val edm = NhlTeamColours.of("EDM")
        assertEquals(Side.HOME, p.side)
        assertEquals(edm, p.colours)
        assertEquals("2 – 3", p.tallyText)
        val panel = shot(p).px(p.pillW / 2f, 6f * p.s)
        assertTrue("panel ${hex(panel)} vs EDM base ${hex(edm.base)}", near(edm.base, panel, 3))
    }

    /**
     * Fix pass (emulator finding): the plain bug never shows the new score before the takeover hides it. From the very
     * first frame after the goal view arrives (the start effect may not have run yet) until the lead covers the pill,
     * every pixel of the pill right of the lead is the previous frame's: old score, old centre text, old clock.
     */
    private fun theNewScoreIsNeverSeenBeforeTheTakeoverCoversIt(still: Boolean) {
        start(if (still) FixedScale(0f) else EmptyCoroutineContext)
        // The same teams as the start view (CGY 0 – EDM 2), so only the score, the clock and the mark differ.
        repo.bug.value = ScoreBugFixtures.view(away = 0, home = 2, clockSecs = 1200)
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
        val before = shot()
        repo.bug.value = ScoreBugFixtures.goalView("CGY", Side.AWAY, opponent = "EDM", away = 1, home = 2)
        val cy = K.SCORE_H / 2f
        var frames = 0
        val g = GoalFrame()
        while (true) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            val on = on()
            val tNow = if (on) t() else 0f
            if (on && (still || tNow >= GoalTimeline.LEAD_COVERED_MS)) break
            val shot = shot()
            val leadTo = if (on) GoalTimeline.sample(tNow, g).leadTo else 0f
            val x0 = maxOf(12f, leadTo * K.SCORE_W + 3f)
            var bad = 0
            var y = cy - 12f
            while (y <= cy + 12f) {
                var x = x0
                while (x <= K.SCORE_W - 12f) { if (shot.px(x, y) != before.px(x, y)) bad++; x += 1f }
                y += 1f
            }
            assertEquals("frame $frames (takeover ${if (on) "on at $tNow" else "pending"}): pixels right of the lead differ from the previous frame", 0, bad)
            frames++
            check(frames < 100) { "the takeover never covered the pill" }
        }
        assertEquals("the begin ran", 1, probe.begins.size)
        assertEquals(still, probe.begins[0].still)
        // Not vacuous: once the takeover is over, the new score is drawn.
        repeat(50) { rule.mainClock.advanceTimeBy(100) }
        rule.waitForIdle()
        assertFalse(on())
        val after = shot()
        val awayCx = (K.SCORE_PAD + K.SCORE_LOGO_W + K.SCORE_LOGO_GAP + K.SCORE_DIGITS_W / 2f)
        assertTrue("the away score changed", diff(before, after, awayCx, cy, K.SCORE_DIGITS_W / 2f, 14f) > 20)
    }

    @Test
    fun theNewScoreIsNeverOnThePlainBugBeforeTheLeadCoversIt() = theNewScoreIsNeverSeenBeforeTheTakeoverCoversIt(still = false)

    @Test
    fun theStillPathNeverShowsTheNewScoreBeforeFrameA() = theNewScoreIsNeverSeenBeforeTheTakeoverCoversIt(still = true)

    /**
     * Power play §9.4: a real power-play goal through [ScoreBugWith]. From the goal view's first frame until the lead covers
     * the pill, every pixel of the tab region equals the frame before (the tab shows the words it had, pinned); then the
     * tab says PP GOAL. The painter is the real [PowerPlayPainter] and the sampler the real timeline; the region is first
     * asserted to hold drawn tab pixels, so the equality below is not vacuous.
     */
    private fun aPowerPlayGoalHoldsTheTabThenSaysPpGoal(still: Boolean) {
        start(if (still) FixedScale(0f) else EmptyCoroutineContext, glow = false)
        val plain = shot() // no power play yet: no tint
        repo.bug.value = ScoreBugFixtures.ppView("EDM", Side.HOME, opponent = "CGY", secs = 79)
        rule.mainClock.advanceTimeBy(2500)
        rule.waitForIdle()
        val before = shot()
        assertEquals("the tab is drawn at rest", PpLabel.ONE_MAN, probe.pp.lastDrawContent!!.label)
        // The tab below the pill (top right): x 31..373, y pillH .. pillH + 36.
        val cx = K.SCORE_W / 2f
        val cy = K.SCORE_H + 18f
        var inked = 0
        run {
            var y = cy - 14f
            while (y <= cy + 14f) {
                var x = cx - 160f
                while (x <= cx + 160f) { if ((before.px(x, y) ushr 24) != 0) inked++; x += 1f }
                y += 1f
            }
        }
        assertTrue("the tab region holds drawn tab pixels ($inked)", inked > 1_000)
        // S2 (fix pass): the pill right of the lead, the tint's end (EDM is home, the right) included; first shown to be
        // tinted, so the hold below is not vacuous.
        val py = K.SCORE_H / 2f
        val tintX = K.SCORE_W - 40f
        assertTrue("the pill's home end is tinted", diff(before, plain, tintX, py, 24f, 12f) > 200)
        repo.bug.value = ScoreBugFixtures.ppGoalView("EDM", Side.HOME, opponent = "CGY") // the minor ends in the goal
        val g = GoalFrame()
        var frames = 0
        while (true) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            val covered = on() && (still || t() >= GoalTimeline.LEAD_COVERED_MS)
            val shot = shot()
            if (covered) {
                assertEquals("PP GOAL once the lead covers the pill (t = ${t()})", PpLabel.PP_GOAL, probe.pp.lastDrawContent!!.label)
                break
            }
            assertEquals("frame $frames: the held words (t = ${if (on()) t() else -1f})", PpLabel.ONE_MAN, probe.pp.lastDrawContent!!.label)
            assertEquals("frame $frames: the tab region is the previous frame's", 0, diff(shot, before, cx, cy, 160f, 14f))
            // S2: every pixel of the pill right of the lead (tint, halo and body) is the previous frame's.
            val leadTo = if (on()) GoalTimeline.sample(t(), g).leadTo else 0f
            val x0 = maxOf(12f, leadTo * K.SCORE_W + 3f)
            var bad = 0
            var y = py - 12f
            while (y <= py + 12f) {
                var x = x0
                while (x <= K.SCORE_W - 12f) { if (shot.px(x, y) != before.px(x, y)) bad++; x += 1f }
                y += 1f
            }
            assertEquals("frame $frames: the pill (tint included) right of the lead is the previous frame's", 0, bad)
            check(++frames < 100) { "the takeover never covered the pill" }
        }
        // And it stays PP GOAL until the release at 3 600.
        advanceTo(3_000f)
        shot()
        assertEquals(PpLabel.PP_GOAL, probe.pp.lastDrawContent!!.label)
    }

    @Test
    fun aPowerPlayGoalHoldsTheTabUntilTheLeadCoversThenDrawsPpGoal() = aPowerPlayGoalHoldsTheTabThenSaysPpGoal(still = false)

    @Test
    fun theStillPathSwapsTheTabToPpGoalAtFrameA() = aPowerPlayGoalHoldsTheTabThenSaysPpGoal(still = true)

    /**
     * S3/S5 (fix pass): with a logo loader, the real halo bake runs per logo bitmap; a game change clears the halos, and
     * a side whose new logo has not loaded draws none (never the previous game's shape).
     */
    @Test
    fun theHaloIsBakedFromTheLogoAndClearedOnAGameChange() {
        start(glow = false, first = withLogos(ScoreBugFixtures.view(clockSecs = 1200), ""))
        repo.bug.value = withLogos(ScoreBugFixtures.ppView("EDM", Side.HOME, opponent = "CGY", secs = 79), "")
        rule.mainClock.advanceTimeBy(2500)
        rule.waitForIdle()
        val pp = checkNotNull(probe.pp.painter!!()) { "no power-play painter" }
        assertTrue("the real halo bake ran for the home logo", pp.haloBaked(Side.HOME))
        assertEquals(Side.HOME, run { shot(); probe.pp.lastDrawSide })
        // A new game whose logos never load: the old halos are dropped at once.
        val next = GameId(League.NHL, "2025020999")
        repo.bug.value = withLogos(ScoreBugFixtures.ppView("EDM", Side.HOME, opponent = "CGY", secs = 79).copy(gameId = next), "-slow")
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        val pp2 = checkNotNull(probe.pp.painter!!())
        assertFalse("cleared on the game change", pp2.haloBaked(Side.HOME) || pp2.haloBaked(Side.AWAY))
    }

    private class FixedScale(override val scaleFactor: Float) : MotionDurationScale
}
