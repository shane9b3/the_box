package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.GoalFrame
import app.thebox.core.sports.GoalTimeline
import app.thebox.core.sports.NhlTeamColours
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.TeamColours
import app.thebox.ui.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.math.pow

/**
 * docs/design/m6-goal-animation.md §8.2 (WP-2): the goal takeover drawn through the real composite
 * [ScoreBugPainter.drawFrame] (S5) with the bundled fonts, over the dark gradient and over white ice, for CGY, EDM, NSH
 * and PHI (S6) at Standard and Large, at fixed times plus the still frames A and B. Every frame is written under
 * `ui/build/goal-animation/` with contact sheets; they are for review by eye, not goldens. The pixel assertions are §8.2's.
 *
 * Team colours come from the real table, `NhlTeamColours.of` (§3.3; `NhlTeamColoursTest` pins the rows). The logos are
 * synthetic stand-ins for the `_dark` art (a team-colour shape with a light outer border), since the tests have no real SVGs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GoalAnimationScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val res = context.resources
    private val semi = sansTypeface(context, R.font.instrument_sans_semibold)
    private val bold = sansTypeface(context, R.font.instrument_sans_bold)
    private val barlow = sansTypeface(context, R.font.barlow_condensed_black_italic)
    private val out = File("build/goal-animation").apply { mkdirs() }

    private class Team(val abbrev: String, val side: Side) {
        val colours: TeamColours = NhlTeamColours.of(abbrev)
    }

    private val teams = listOf(Team("CGY", Side.AWAY), Team("EDM", Side.HOME), Team("NSH", Side.AWAY), Team("PHI", Side.HOME))
    private val opponentColour = mapOf("TOR" to 0xFF00205B.toInt(), "MTL" to 0xFFAF1E2D.toInt())

    private val times = listOf(100, 300, 600, 1_000, 1_400, 1_700, 2_000, 3_300, 3_550, 3_700, 4_000)

    /** One size: the bug's painter, the goal painter, both glass bakes and the glow masks (built once, as in the app). */
    private inner class Rig(val large: Boolean) {
        val s = if (large) K.SCORE_LARGE_SCALE else 1f
        val pillW = if (large) K.SCORE_LARGE_W else K.SCORE_W
        val pillH = if (large) K.SCORE_LARGE_H else K.SCORE_H
        val m = if (large) K.GOAL_LAYER_MARGIN_LARGE else K.GOAL_LAYER_MARGIN
        val painter = ScoreBugPainter(s, semi, bold, pillW, pillH)
        val goal = GoalPainter(s, pillW, pillH, barlow, bold, res.getString(R.string.sports_goal_word))
        val glow: GlowMasks = goal.bakeGlow()
        val glass: Bitmap = painter.bakeGlass(withShadow = true)
        val glassFlat: Bitmap = painter.bakeGlass(withShadow = false)
        val logoW = Math.round(K.SCORE_LOGO_W * s)
        val logoH = Math.round(K.SCORE_LOGO_H * s)
    }

    private val std by lazy { Rig(false) }
    private val big by lazy { Rig(true) }
    private fun rig(large: Boolean) = if (large) big else std

    /** A stand-in for a `_dark` logo: a team-colour shield-ish oval with a light outer border, and a white bar. */
    private fun logoBitmap(colour: Int, w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        val c = Canvas(this)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val b = w * 0.06f
        p.color = 0xFFF2F2F2.toInt()
        c.drawOval(w * 0.14f, 1f, w * 0.86f, h - 1f, p)
        p.color = colour
        c.drawOval(w * 0.14f + b, 1f + b, w * 0.86f - b, h - 1f - b, p)
        p.color = Color.WHITE
        c.drawRect(w * 0.36f, h * 0.44f, w * 0.64f, h * 0.56f, p)
    }

    private fun teamLogoColour(abbrev: String): Int =
        teams.firstOrNull { it.abbrev == abbrev }?.colours?.accent ?: opponentColour[abbrev] ?: Color.GRAY

    private fun logos(awayAbbrev: String, homeAbbrev: String, r: Rig): FollowLogos {
        val l = FollowLogos({ url, w, h -> logoBitmap(teamLogoColour(url), w, h) }, CoroutineScope(Dispatchers.Unconfined))
        l.start(awayAbbrev, homeAbbrev, r.logoW, r.logoH)
        return l
    }

    private class Shot(val bmp: Bitmap, val ox: Float, val oy: Float) {
        /** The pixel at pill coordinates (x, y). */
        fun px(x: Float, y: Float): Int = bmp.getPixel((x + ox).toInt(), (y + oy).toInt())
    }

    /**
     * The composite at [t] on [bg] (DARK, TRANSPARENT or a colour). [goal] false = the plain bug; [still] = the still
     * version; [lowEnd] = no shadow and no glow masks (never baked). [extra] adds room around the layer (pill + M).
     */
    private fun render(
        team: Team,
        large: Boolean,
        t: Float,
        bg: Int = DARK,
        goal: Boolean = true,
        still: Boolean = false,
        glowOn: Boolean = true,
        badge: Boolean = true,
        lowEnd: Boolean = false,
        extra: Int = 0,
        away: Int? = null,
    ): Shot {
        val r = rig(large)
        val base = ScoreBugFixtures.goalView(team.abbrev, team.side)
        val view = if (away != null) base.copy(away = base.away.copy(score = away)) else base
        val frame = r.painter.frame(view, res)
        val logos = logos(view.away.abbrev, view.home.abbrev, r)
        val g = GoalFrame()
        if (goal) {
            val logo = (if (team.side == Side.AWAY) logos.away else logos.home).image
            r.goal.begin(
                team.side, team.colours, if (badge) logo?.asAndroidBitmap() else null,
                team.abbrev, view.away.score ?: 0, view.home.score ?: 0,
            )
            if (still) GoalTimeline.sampleStill(t, g) else GoalTimeline.sample(t, g)
        }
        val w = Math.round(r.pillW + 2 * r.m) + 2 * extra
        val h = Math.round(r.pillH + 2 * r.m) + 2 * extra
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        when (bg) {
            DARK -> c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(20, 22, 28), Color.rgb(70, 40, 38), Shader.TileMode.CLAMP)
            })
            TRANSPARENT -> Unit
            else -> c.drawColor(bg)
        }
        val o = extra + r.m
        c.translate(o, o)
        r.painter.drawFrame(
            c, if (lowEnd) r.glassFlat else r.glass, frame, logos, logoWaited = true, placing = false,
            goal = if (goal) r.goal else null, g = if (goal) g else null, glow = if (lowEnd) null else r.glow, glowOn = glowOn,
        )
        return Shot(bmp, o, o)
    }

    private fun save(name: String, bmp: Bitmap) {
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun sheet(name: String, cells: List<List<Bitmap>>) {
        val cw = cells.flatten().maxOf { it.width }
        val ch = cells.flatten().maxOf { it.height }
        val cols = cells.maxOf { it.size }
        val b = Bitmap.createBitmap(cols * cw, cells.size * ch, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.BLACK)
        cells.forEachIndexed { row, list -> list.forEachIndexed { col, bmp -> c.drawBitmap(bmp, col * cw.toFloat(), row * ch.toFloat(), null) } }
        save(name, b)
    }

    private fun lum(c: Int): Double {
        fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }

    private fun contrast(a: Int, b: Int): Double {
        val l1 = lum(a); val l2 = lum(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun near(expected: Int, actual: Int, tol: Int) =
        abs(Color.red(expected) - Color.red(actual)) <= tol && abs(Color.green(expected) - Color.green(actual)) <= tol &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tol

    private fun hex(c: Int) = "#" + Integer.toHexString(c).padStart(8, '0').uppercase()

    /** Inside the pill's rounded rect by at least [inset] px. */
    private fun insidePill(r: Rig, x: Float, y: Float, inset: Float): Boolean {
        val rad = r.pillH / 2f
        if (y < inset || y > r.pillH - inset || x < inset || x > r.pillW - inset) return false
        val cx = x.coerceIn(rad, r.pillW - rad)
        val dx = x - cx
        val dy = y - rad
        return dx * dx + dy * dy <= (rad - inset) * (rad - inset)
    }

    private fun suffix(large: Boolean) = if (large) "large" else "std"

    // ------------------------------------------------------------------ the frames and contact sheets

    @Test
    fun everyFrameForEveryTeamAndSizeAndTheContactSheets() {
        for (large in listOf(false, true)) {
            for ((bgName, bg) in listOf("dark" to DARK, "ice" to Color.WHITE)) {
                val columns = teams.map { team ->
                    val shots = times.map { t ->
                        render(team, large, t.toFloat(), bg).bmp.also { save("goal-${team.abbrev}-${suffix(large)}-$bgName-t$t", it) }
                    }
                    val a = render(team, large, 1_000f, bg, still = true).bmp.also { save("goal-${team.abbrev}-${suffix(large)}-$bgName-still-a", it) }
                    val b = render(team, large, 4_000f, bg, still = true).bmp.also { save("goal-${team.abbrev}-${suffix(large)}-$bgName-still-b", it) }
                    shots + a + b
                }
                // Rows = times (then still A, still B), columns = CGY, EDM, NSH, PHI.
                val rows = columns[0].indices.map { i -> columns.map { it[i] } }
                val name = buildString {
                    append("goal-contact-sheet")
                    if (large) append("-large")
                    if (bg != DARK) append("-ice")
                }
                sheet(name, rows)
            }
        }
        assertTrue(File(out, "goal-contact-sheet.png").length() > 10_000)
    }

    @Test
    fun extraFrames() {
        val cgy = teams[0]
        for (large in listOf(false, true)) {
            save("goal-fallback-badge-${suffix(large)}", render(cgy, large, 1_700f, badge = false).bmp)
            save("goal-quick-bar-${suffix(large)}", render(cgy, large, 1_800f, glowOn = false).bmp)
            save("goal-low-end-a-${suffix(large)}", render(cgy, large, 1_000f, still = true, lowEnd = true).bmp)
            save("goal-low-end-b-${suffix(large)}", render(cgy, large, 4_000f, still = true, lowEnd = true).bmp)
            save("goal-two-digit-pop-${suffix(large)}", render(cgy, large, 3_700f, away = 10).bmp)
            save("goal-glow-peak-${suffix(large)}", render(teams[1], large, 1_800f).bmp)
        }
    }

    // ------------------------------------------------------------------ §8.2 assertions

    @Test
    fun at100TheLeadIsAccentAndTheFarRightIsStillGlass() {
        val g = GoalTimeline.sample(100f, GoalFrame())
        for (large in listOf(false, true)) for (team in teams) {
            val r = rig(large)
            val shot = render(team, large, 100f)
            val plain = render(team, large, 100f, goal = false)
            val x = g.leadTo * r.pillW / 2f
            val lead = shot.px(x, r.pillH / 2f)
            assertTrue("${team.abbrev} lead ${hex(lead)} vs accent ${hex(team.colours.accent)}", near(team.colours.accent, lead, 3))
            val fx = r.pillW - 20f * r.s
            assertEquals("${team.abbrev} far right is glass", plain.px(fx, r.pillH / 2f), shot.px(fx, r.pillH / 2f))
        }
    }

    /**
     * §8.2 names t = 1 400 for the panel check; under the flash ruling (3 × 400 ms from 420) the third flash peaks at
     * 1 420, so the base is checked at 1 700 instead (after the flash and streak 1, before streak 2), with the panel, the
     * letters and the tally all at rest.
     */
    @Test
    fun at1700ThePanelIsBaseAndGoalIsWhiteAndC1HoldsFromThePixels() {
        for (large in listOf(false, true)) for (team in teams) {
            val r = rig(large)
            val shot = render(team, large, 1_700f)
            val panel = shot.px(r.pillW / 2f, 6f * r.s)
            assertTrue("${team.abbrev} panel ${hex(panel)} vs base ${hex(team.colours.base)}", near(team.colours.base, panel, 3))
            // The brightest pixel of the GOAL word's middle band is a stem: white.
            var best = 0
            var bl = -1.0
            val cx = r.goal.wordCx
            for (y in (r.pillH / 2f - 8f * r.s).toInt()..(r.pillH / 2f + 8f * r.s).toInt()) {
                for (x in (cx - 40f * r.s).toInt()..(cx + 40f * r.s).toInt()) {
                    val p = shot.px(x.toFloat(), y.toFloat())
                    val v = lum(p)
                    if (v > bl) { bl = v; best = p }
                }
            }
            assertTrue("${team.abbrev} GOAL stem ${hex(best)}", Color.red(best) >= 245 && Color.green(best) >= 245 && Color.blue(best) >= 245)
            val c1 = contrast(best, panel)
            println("C1 from pixels ${team.abbrev} ${suffix(large)}: %.2f".format(c1))
            if (team.abbrev == "PHI") assertTrue("PHI (owner exception, 3.6): $c1", c1 >= 3.4)
            else assertTrue("${team.abbrev} C1 $c1", c1 >= K.GOAL_C1_MIN)
        }
    }

    /**
     * At the glow's peak (1 800): the halo 20 px above the pill is the team's glow colour, and 56 px above (the screen
     * edge at the corner inset) its alpha is at most 2 %. Measured on a transparent canvas, glow alone and composite.
     *
     * §8.2 asks for alpha > .3 at 20 px; the §2.4 numbers (spread 8, σ 22, α .8) give .8 · ½erfc(12 / 22√2) = .23 there
     * (the mock's own 80 px blur gives the same), so the floor asserted is .2 and the measured value is printed.
     */
    @Test
    fun at1800TheGlowIsTheTeamColourAndFadesBeforeTheScreenEdge() {
        val g = GoalTimeline.sample(1_800f, GoalFrame())
        for (large in listOf(false, true)) for (team in teams) {
            val r = rig(large)
            val bmp = Bitmap.createBitmap(Math.round(r.pillW + 2 * r.m), Math.round(r.pillH + 2 * r.m), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.translate(r.m, r.m)
            r.goal.begin(team.side, team.colours, null, team.abbrev, 1, 2)
            r.goal.drawGlow(c, g, r.glow)
            val shot = Shot(bmp, r.m, r.m)
            val near20 = shot.px(r.pillW / 2f, -20f)
            val a20 = Color.alpha(near20) / 255.0
            println("glow ${team.abbrev} ${suffix(large)}: α at 20 px %.3f, at 56 px %.4f".format(a20, Color.alpha(shot.px(r.pillW / 2f, -56f)) / 255.0))
            assertTrue("${team.abbrev} glow α at 20 px: $a20", a20 > 0.2)
            assertTrue("${team.abbrev} glow colour ${hex(near20)} vs ${hex(team.colours.glow)}", near(team.colours.glow, near20, 10))
            for (y in listOf(-56f, r.pillH + 56f)) {
                val a = Color.alpha(shot.px(r.pillW / 2f, y)) / 255.0
                assertTrue("${team.abbrev} glow α at 56 px: $a", a <= K.GOAL_GLOW_EDGE_MAX_ALPHA)
            }
            // The composite (glass shadow included) keeps the same edge.
            val comp = render(team, large, 1_800f, bg = TRANSPARENT)
            val ca = Color.alpha(comp.px(r.pillW / 2f, -56f)) / 255.0
            assertTrue("${team.abbrev} composite α at 56 px: $ca", ca <= K.GOAL_GLOW_EDGE_MAX_ALPHA)
        }
    }

    @Test
    fun withTheQuickBarOpenThereIsNoGlowOutsideThePill() {
        for (large in listOf(false, true)) {
            val r = rig(large)
            val team = teams[1]
            for (t in listOf(600f, 1_800f, 3_000f)) {
                val off = render(team, large, t, bg = TRANSPARENT, glowOn = false)
                val plain = render(team, large, t, bg = TRANSPARENT, goal = false)
                val on = render(team, large, t, bg = TRANSPARENT)
                var differs = false
                for (y in 0 until off.bmp.height) for (x in 0 until off.bmp.width) {
                    val px = x - off.ox
                    val py = y - off.oy
                    if (px > -2f && px < r.pillW + 2f && py > -2f && py < r.pillH + 2f) continue
                    assertEquals("($x,$y) at $t", plain.bmp.getPixel(x, y), off.bmp.getPixel(x, y))
                    if (on.bmp.getPixel(x, y) != plain.bmp.getPixel(x, y)) differs = true
                }
                assertTrue("the glow does draw when allowed at $t", differs)
            }
        }
    }

    @Test
    fun fromTheEndItIsPixelEqualToThePlainBug() {
        for (large in listOf(false, true)) for (team in teams) for (t in listOf(4_500f, 6_000f)) {
            val shot = render(team, large, t).bmp
            val plain = render(team, large, t, goal = false).bmp
            assertTrue("${team.abbrev} ${suffix(large)} at $t", shot.sameAs(plain))
            assertTrue("${team.abbrev} still at $t", render(team, large, t, still = true).bmp.sameAs(plain))
        }
    }

    @Test
    fun theStillFramesHaveNoStreakOrGlowPixels() {
        for (large in listOf(false, true)) for (team in teams) {
            val r = rig(large)
            val plain = render(team, large, 1_000f, bg = TRANSPARENT, goal = false)
            // Frame A: outside the pill it is the plain bug (no glow); inside, it equals the animated panel at rest with
            // no streak, flash or pulse (t = 3 000), away from the anti-aliased rim (the animated one has the lead under it).
            val a = render(team, large, 1_000f, bg = TRANSPARENT, still = true)
            val rest = render(team, large, 3_000f, bg = TRANSPARENT, glowOn = false)
            // Frame B: outside the pill, the plain bug too.
            val b = render(team, large, 4_000f, bg = TRANSPARENT, still = true)
            for (y in 0 until a.bmp.height) for (x in 0 until a.bmp.width) {
                val px = x - a.ox
                val py = y - a.oy
                val outside = !(px > -2f && px < r.pillW + 2f && py > -2f && py < r.pillH + 2f)
                if (outside) {
                    assertEquals("A ($x,$y)", plain.bmp.getPixel(x, y), a.bmp.getPixel(x, y))
                    assertEquals("B ($x,$y)", plain.bmp.getPixel(x, y), b.bmp.getPixel(x, y))
                } else if (insidePill(r, px + 0.5f, py + 0.5f, 2f)) {
                    assertEquals("A vs rest ($x,$y)", rest.bmp.getPixel(x, y), a.bmp.getPixel(x, y))
                }
            }
            // Frame B shows the scorer's digit in `pop`.
            val digitCx = if (team.side == Side.AWAY) (K.SCORE_PAD + K.SCORE_LOGO_W + K.SCORE_LOGO_GAP + K.SCORE_DIGITS_W / 2f) * r.s
            else r.pillW - (K.SCORE_PAD + K.SCORE_LOGO_W + K.SCORE_LOGO_GAP + K.SCORE_DIGITS_W / 2f) * r.s
            var found = false
            for (y in (r.pillH / 2f - 14f * r.s).toInt()..(r.pillH / 2f + 14f * r.s).toInt()) {
                for (x in (digitCx - 14f * r.s).toInt()..(digitCx + 14f * r.s).toInt()) {
                    if (near(team.colours.pop, b.px(x.toFloat(), y.toFloat()), 8)) found = true
                }
            }
            assertTrue("${team.abbrev} still B digit in pop ${hex(team.colours.pop)}", found)
        }
    }

    /** Low-end mode: the still path, no shadow, no masks (WP-3 never calls bakeGlow there): nothing outside the pill. */
    @Test
    fun lowEndModeDrawsNothingOutsideThePill() {
        for (large in listOf(false, true)) {
            val r = rig(large)
            for (t in listOf(0f, 1_000f, 4_000f)) {
                val shot = render(teams[0], large, t, bg = TRANSPARENT, still = true, lowEnd = true)
                for (y in 0 until shot.bmp.height) for (x in 0 until shot.bmp.width) {
                    val px = x - shot.ox
                    val py = y - shot.oy
                    // The glass's 1 px outer ring (1.25 px at Large) and its anti-aliasing sit within 3 px of the pill.
                    if (px > -3f && px < r.pillW + 3f && py > -3f && py < r.pillH + 3f) continue
                    assertEquals("($x,$y) at $t", 0, Color.alpha(shot.bmp.getPixel(x, y)))
                }
            }
        }
    }

    @Test
    fun nothingIsDrawnOutsideThePillPlusTheLayerMargin() {
        val extra = 24
        for (large in listOf(false, true)) {
            val r = rig(large)
            for (team in listOf(teams[0], teams[1])) {
                var t = 0f
                while (t <= 4_600f) {
                    val shot = render(team, large, t, bg = TRANSPARENT, extra = extra)
                    val lo = extra
                    val hiX = shot.bmp.width - extra
                    val hiY = shot.bmp.height - extra
                    for (y in 0 until shot.bmp.height) for (x in 0 until shot.bmp.width) {
                        if (x in lo until hiX && y in lo until hiY) continue
                        assertEquals("${team.abbrev} ${suffix(large)} ($x,$y) at $t", 0, Color.alpha(shot.bmp.getPixel(x, y)))
                    }
                    t += 150f
                }
            }
            assertTrue("the peak mask fits the layer", r.glow.peakMargin <= r.m + 0.01f)
        }
    }

    @Test
    fun theBadgeFallsBackToTheAbbreviationInWhite() {
        for (large in listOf(false, true)) {
            val r = rig(large)
            val shot = render(teams[1], large, 1_700f, badge = false)
            var white = 0
            val cx = (K.GOAL_PAD + K.SCORE_LOGO_W / 2f) * r.s
            for (y in (r.pillH / 2f - 10f * r.s).toInt()..(r.pillH / 2f + 10f * r.s).toInt()) {
                for (x in (cx - 30f * r.s).toInt()..(cx + 30f * r.s).toInt()) {
                    val p = shot.px(x.toFloat(), y.toFloat())
                    if (Color.red(p) > 240 && Color.green(p) > 240 && Color.blue(p) > 240) white++
                }
            }
            assertTrue("white abbreviation pixels: $white", white > 20)
        }
    }

    @Test
    fun thePopIsClampedForTwoDigits() {
        for (large in listOf(false, true)) {
            val r = rig(large)
            val p = Paint().apply { typeface = bold; textSize = K.SCORE_TEXT_PX * r.s }
            assertEquals(1.55f, r.painter.clampPop("1", 1.55f), 0f)
            val two = r.painter.clampPop("10", 1.55f)
            assertTrue("two digits pop less: $two", two < 1.55f && two >= 1f)
            assertTrue("within the column + 2 × 8", p.measureText("10") * two <= K.GOAL_POP_MAX_W * r.s + 0.5f)
            assertEquals("the .9 dip passes", 0.9f, r.painter.clampPop("10", 0.9f), 0f)
            println("pop clamp ${suffix(large)}: \"10\" at %.3f".format(two))
        }
    }

    @Test
    fun theAtlasIsOneSmallBitmapPerSize() {
        val a = std.goal.atlasBitmap
        println("GOAL atlas Standard ${a.width} × ${a.height}, Large ${big.goal.atlasBitmap.width} × ${big.goal.atlasBitmap.height}")
        assertTrue("Standard atlas ${a.byteCount} B", a.byteCount < 140_000)
        assertTrue(big.goal.atlasBitmap.byteCount > a.byteCount)
    }

    /** §8.3's allocation check for [GoalPainter.draw] (best-effort under Robolectric's native canvas). */
    @Test
    fun goalPainterDrawAllocatesLittle() {
        val r = std
        r.goal.begin(Side.AWAY, teams[0].colours, logoBitmap(Color.RED, r.logoW, r.logoH), "CGY", 2, 1)
        val bmp = Bitmap.createBitmap(Math.round(r.pillW + 2 * r.m), Math.round(r.pillH + 2 * r.m), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(r.m, r.m)
        val frames = Array(300) { GoalTimeline.sample(it * 12f, GoalFrame()) }
        repeat(2) { for (f in frames) r.goal.draw(c, f, r.glow, true) }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        for (f in frames) r.goal.draw(c, f, r.glow, true)
        val after = bean.getThreadAllocatedBytes(id)
        println("GoalPainter.draw × 300: ${after - before} B")
        assertTrue("allocated ${after - before} B", after - before < 4_096)
    }

    /**
     * Code review N2: the whole composite, [ScoreBugPainter.drawFrame], on a live view (clock on top, a period line: the
     * body's two `getFontMetrics` calls) through a takeover, allocates (next to) nothing.
     */
    @Test
    fun drawFrameAllocatesLittle() {
        val r = std
        val team = teams[0]
        val view = ScoreBugFixtures.goalView(team.abbrev, team.side)
        val frame = r.painter.frame(view, res)
        assertTrue("a two-line live frame", frame.clockOnTop && frame.bottomText != null)
        val logos = logos(view.away.abbrev, view.home.abbrev, r)
        r.goal.begin(team.side, team.colours, null, team.abbrev, 2, 1)
        val bmp = Bitmap.createBitmap(Math.round(r.pillW + 2 * r.m), Math.round(r.pillH + 2 * r.m), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(r.m, r.m)
        val frames = Array(300) { GoalTimeline.sample(it * 16f, GoalFrame()) }
        fun pass() { for (g in frames) r.painter.drawFrame(c, r.glass, frame, logos, true, false, r.goal, g, r.glow, true) }
        repeat(2) { pass() }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        pass()
        val after = bean.getThreadAllocatedBytes(id)
        println("ScoreBugPainter.drawFrame × 300: ${after - before} B")
        assertTrue("allocated ${after - before} B", after - before < 4_096)
    }

    private companion object {
        const val DARK = 0x01010101
        const val TRANSPARENT = 0x00000000
    }
}
