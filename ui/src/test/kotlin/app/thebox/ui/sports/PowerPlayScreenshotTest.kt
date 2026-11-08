package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.GoalFrame
import app.thebox.core.sports.GoalTimeline
import app.thebox.core.sports.NhlTeamColours
import app.thebox.core.sports.PowerPlayKind
import app.thebox.core.sports.PowerPlayTimeline
import app.thebox.core.sports.PpContent
import app.thebox.core.sports.PpFrame
import app.thebox.core.sports.PpLabel
import app.thebox.core.sports.PpTrack
import app.thebox.core.sports.PpTransition
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.TeamColours
import app.thebox.ui.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import kotlin.math.roundToInt

/**
 * docs/design/m6-power-play.md §9.2 (WP-2): the power-play tab, tint and halo drawn through the real composite
 * [ScoreBugPainter.drawFrame] with the bundled fonts, for CGY, EDM, NSH, PHI and TOR at Standard and Large, top-right
 * (the tab below) and bottom-left (the tab above), over the dark gradient and white ice, at §9.2's fixed times plus the
 * still frames, the 5-on-3 label and PP GOAL over the takeover; 1280 × 720 frames for the seam row (N9). Every frame is
 * written under `ui/build/power-play/` with contact sheets; they are for review by eye, not goldens.
 *
 * Team colours are §6.4's rows, built directly (WP-1 derives `tab` and `tintScale` in parallel; here [NhlTeamColours]
 * still has commit 0's defaults). The logos are synthetic stand-ins, as in [GoalAnimationScreenshotTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PowerPlayScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val res = context.resources
    private val semi = sansTypeface(context, R.font.instrument_sans_semibold)
    private val bold = sansTypeface(context, R.font.instrument_sans_bold)
    private val barlow = sansTypeface(context, R.font.barlow_condensed_black_italic)
    private val out = File("build/power-play").apply { mkdirs() }

    /** A §6.4 row: glow, tab and tintScale; base, accent and pop from the table. */
    private class Team(val abbrev: String, val side: Side, glow: Int, tab: Int, tintScale: Float) {
        val colours: TeamColours = NhlTeamColours.of(abbrev).copy(tab = tab, tintScale = tintScale)

        init {
            require(colours.glow == glow) { "$abbrev glow ${Integer.toHexString(colours.glow)} is not §6.4's" }
        }
    }

    private val teams = listOf(
        Team("CGY", Side.AWAY, 0xFFD2001C.toInt(), 0xFFD2001C.toInt(), 1f),
        Team("EDM", Side.HOME, 0xFFFF4C00.toInt(), 0xFFFF4C00.toInt(), 1f),
        Team("NSH", Side.AWAY, 0xFFFFB81C.toInt(), 0xFF041E42.toInt(), 0.90f),
        Team("PHI", Side.HOME, 0xFFF74902.toInt(), 0xFFF74902.toInt(), 1f),
        Team("TOR", Side.AWAY, 0xFFFFFFFF.toInt(), 0xFF00205B.toInt(), 0.60f),
    )
    private val opponentColour = mapOf("TOR" to 0xFF00205B.toInt(), "MTL" to 0xFFAF1E2D.toInt())

    /** One frame of both tracks: a transition at a time (§9.2's list). */
    private class Shot(val kind: PpTransition, val t: Float, val to: Boolean = true) {
        val name: String get() = when (kind) {
            PpTransition.STILL -> if (to) "still-open" else "still-closed"
            else -> "${kind.name.lowercase()}-t${t.toInt()}"
        }
    }

    private val shots = listOf(0, 150, 300, 550, 700, 1_000, 1_225, 1_450, 1_900).map { Shot(PpTransition.OPEN, it.toFloat()) } +
        listOf(100, 300, 600).map { Shot(PpTransition.CLOSE, it.toFloat()) } +
        Shot(PpTransition.PULSE, 225f) + Shot(PpTransition.STILL, 0f)

    private val restOpen = frame(Shot(PpTransition.STILL, 0f, true))
    private val restClosed = frame(Shot(PpTransition.STILL, 0f, false))

    /** Both tracks at [shot]: an OPEN from closed, a CLOSE or PULSE from open (the pill's PULSE is its open rest). */
    private fun frame(shot: Shot, out: PpFrame = PpFrame()): PpFrame {
        val from = PpFrame()
        val opened = shot.kind == PpTransition.CLOSE || shot.kind == PpTransition.PULSE
        PowerPlayTimeline.sample(PpTrack.TAB, PpTransition.STILL, 0f, PpFrame(), opened, from)
        PowerPlayTimeline.sample(PpTrack.PILL, PpTransition.STILL, 0f, PpFrame(), opened, from)
        PowerPlayTimeline.sample(PpTrack.TAB, shot.kind, shot.t, from, shot.to, out)
        PowerPlayTimeline.sample(PpTrack.PILL, shot.kind, shot.t, from, shot.to, out)
        return out
    }

    /** One size: the bug's painter, the goal painter, the power-play painters (full and low-end) and the bakes. */
    private inner class Rig(val name: String, val s: Float, val d: Float, val pillW: Float, val pillH: Float, val m: Float) {
        val painter = ScoreBugPainter(s, semi, bold, pillW, pillH)
        val goal = GoalPainter(s, pillW, pillH, barlow, bold, res.getString(R.string.sports_goal_word))
        val glow: GlowMasks = goal.bakeGlow()
        val glass: Bitmap = painter.bakeGlass(withShadow = true)
        val glassFlat: Bitmap = painter.bakeGlass(withShadow = false)
        val pp = PowerPlayPainter(s, d, pillW, pillH, bold).apply { bakeSize() }
        val ppLow = PowerPlayPainter(s, d, pillW, pillH, bold).apply { bakeSize(withShadow = false) }
        val logoW = Math.round(K.SCORE_LOGO_W * s)
        val logoH = Math.round(K.SCORE_LOGO_H * s)
        /** The pill's origin in the layer: a whole device px, as the app's `offset(M.dp)` places it. */
        val origin = m.roundToInt()
        val layerW = (pillW + 2 * m).roundToInt()
        val layerH = (pillH + 2 * m).roundToInt()
        private val logoCache = HashMap<String, FollowLogos>()
        private var labelKey = ""

        fun logos(away: String, home: String): FollowLogos = logoCache.getOrPut("$away-$home") {
            FollowLogos({ url, w, h -> logoBitmap(teamLogoColour(url), w, h) }, CoroutineScope(Dispatchers.Unconfined))
                .also { it.start(away, home, logoW, logoH) }
        }

        fun labels(away: String, home: String) {
            val key = "$away-$home"
            if (key == labelKey) return
            pp.bakeLabels(away, home, res)
            ppLow.bakeLabels(away, home, res)
            labelKey = key
        }
    }

    private val std by lazy { Rig("std", 1f, 1f, K.SCORE_W, K.SCORE_H, K.GOAL_LAYER_MARGIN) }
    private val big by lazy {
        Rig("large", K.SCORE_LARGE_SCALE, 1f, K.SCORE_LARGE_W, K.SCORE_LARGE_H, K.GOAL_LAYER_MARGIN_LARGE)
    }
    private val hd by lazy {
        val d = 1280f / 1920f
        Rig("720p", d, d, K.SCORE_W * d, K.SCORE_H * d, K.GOAL_LAYER_MARGIN * d)
    }
    private val rigs get() = listOf(std, big)

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
        teams.firstOrNull { it.abbrev == abbrev }?.colours?.base ?: opponentColour[abbrev] ?: Color.GRAY

    private class Out(val bmp: Bitmap, val ox: Float, val oy: Float) {
        fun px(x: Float, y: Float): Int = bmp.getPixel((x + ox).toInt(), (y + oy).toInt())

        /** All pixels at once (a `getPixel` per pixel is slow under the native canvas). */
        private val pixels: IntArray by lazy {
            IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        }

        /** The pixel at bitmap coordinates (x, y). */
        fun at(x: Int, y: Int): Int = pixels[y * bmp.width + x]
    }

    /**
     * The composite on [bg] (DARK, TRANSPARENT or a colour). [f] null = the plain bug (no power-play painter at all).
     * [label] / [secs]: the tab's content; [pillSide] the tint's side (null = none); [goalT] a takeover at that time;
     * [lowEnd] the low-end painter (no shadow, no halo, flat glass); [halo] false = no halo baked for this frame's painter
     * side (the low-end path never bakes one); [extra] adds room around the layer.
     */
    private fun render(
        team: Team,
        r: Rig,
        f: PpFrame?,
        up: Boolean = false,
        bg: Int = DARK,
        label: PpLabel = PpLabel.ONE_MAN,
        secs: Int = 79,
        pillSide: Side? = team.side,
        goalT: Float? = null,
        lowEnd: Boolean = false,
        extra: Int = 0,
    ): Out {
        val kind = if (label == PpLabel.TWO_MAN) PowerPlayKind.TWO_MAN else PowerPlayKind.ONE_MAN
        val view = if (goalT != null) ScoreBugFixtures.ppGoalView(team.abbrev, team.side)
        else ScoreBugFixtures.ppView(team.abbrev, team.side, kind, secs)
        val frame = r.painter.frame(view, res)
        val logos = r.logos(view.away.abbrev, view.home.abbrev)
        r.labels(view.away.abbrev, view.home.abbrev)
        val pp = if (lowEnd) r.ppLow else r.pp
        pp.begin(team.side, team.colours)
        if (!lowEnd) {
            for (side in Side.entries) {
                val img = (if (side == Side.AWAY) logos.away else logos.home).image ?: continue
                pp.bakeHalo(side, img.asAndroidBitmap())
            }
        }
        var g: GoalFrame? = null
        if (goalT != null) {
            val logo = (if (team.side == Side.AWAY) logos.away else logos.home).image
            r.goal.begin(team.side, team.colours, logo?.asAndroidBitmap(), team.abbrev, view.away.score ?: 0, view.home.score ?: 0)
            g = GoalTimeline.sample(goalT, GoalFrame())
        }
        val w = r.layerW + 2 * extra
        val h = r.layerH + 2 * extra
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        when (bg) {
            DARK -> c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(20, 22, 28), Color.rgb(70, 40, 38), Shader.TileMode.CLAMP)
            })
            TRANSPARENT -> Unit
            else -> c.drawColor(bg)
        }
        val o = (extra + r.origin).toFloat()
        c.translate(o, o)
        val content = PpContent(team.side, label, secs)
        r.painter.drawFrame(
            c, if (lowEnd) r.glassFlat else r.glass, frame, logos, logoWaited = true, placing = false,
            goal = if (g != null) r.goal else null, g = g, glow = if (lowEnd) null else r.glow, glowOn = g != null,
            pp = if (f != null) pp else null, p = f, content = content, pillSide = pillSide, up = up,
        )
        return Out(bmp, o, o)
    }

    private fun save(name: String, bmp: Bitmap) {
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A contact sheet of [rows] × [cols] cells of [cw] × [ch], filled by [cell] one at a time (each cell is freed). */
    private fun sheet(name: String, rows: Int, cols: Int, cw: Int, ch: Int, cell: (Int, Int) -> Bitmap) {
        val b = Bitmap.createBitmap(cols * cw, rows * ch, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.BLACK)
        for (row in 0 until rows) for (col in 0 until cols) {
            val bmp = cell(row, col)
            c.drawBitmap(bmp, col * cw.toFloat(), row * ch.toFloat(), null)
            bmp.recycle()
        }
        save(name, b)
        b.recycle()
    }

    private fun lum(c: Int): Double {
        fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
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

    /** Independent of the painter: [rgb] × (1 − k), each channel rounded half to even (as `NhlTeamColours` mixes). */
    private fun mixBlack(rgb: Int, k: Double): Int {
        fun ch(v: Int) = kotlin.math.round(v * (1 - k)).toInt()
        return Color.rgb(ch(Color.red(rgb)), ch(Color.green(rgb)), ch(Color.blue(rgb)))
    }

    private fun corner(up: Boolean) = if (up) "bottom-left" else "top-right"

    // ------------------------------------------------------------------ the frames and contact sheets

    /**
     * Every §9.2 frame plus the 5-on-3 label, a no-seconds label, PP GOAL over the takeover (1 700) and the hold before
     * the lead covers (100), for each team and corner, per size: one dark sheet (rows = frames, columns = the five
     * teams top-right, then the five bottom-left) and one white-ice sheet of the rest and key frames.
     */
    @Test
    fun everyFrameForEveryTeamSizeAndCornerAndTheContactSheets() {
        class Row(val name: String, val f: PpFrame, val label: PpLabel = PpLabel.ONE_MAN, val secs: Int = 79, val goalT: Float? = null)
        val rows = shots.map { Row(it.name, frame(it)) } + listOf(
            Row("five-on-three", restOpen, PpLabel.TWO_MAN, 42),
            Row("no-seconds", restOpen, secs = PpContent.NO_SECS),
            Row("pp-goal-hold-t100", restOpen, goalT = 100f),
            Row("pp-goal-t1700", restOpen, PpLabel.PP_GOAL, PpContent.NO_SECS, goalT = 1_700f),
        )
        val iceRows = rows.filter { it.name in setOf("open-t700", "open-t1225", "still-open", "close-t300", "five-on-three", "pp-goal-t1700") }
        for (r in rigs) {
            val corners = listOf(false, true)
            for ((bgName, bg, list) in listOf(Triple("dark", DARK, rows), Triple("ice", Color.WHITE, iceRows))) {
                sheet(
                    "pp-contact-sheet-${r.name}" + (if (bg == DARK) "" else "-ice"),
                    list.size, teams.size * corners.size, r.layerW, r.layerH,
                ) { row, col ->
                    val team = teams[col % teams.size]
                    val up = corners[col / teams.size]
                    val spec = list[row]
                    render(team, r, spec.f, up, bg, spec.label, spec.secs, goalT = spec.goalT).bmp.also {
                        save("pp-${team.abbrev}-${r.name}-${corner(up)}-$bgName-${spec.name}", it)
                    }
                }
            }
        }
        assertTrue(File(out, "pp-contact-sheet-std.png").length() > 10_000)
        assertTrue(File(out, "pp-contact-sheet-large.png").length() > 10_000)
    }

    @Test
    fun extraFrames() {
        val edm = teams[1]
        for (r in rigs) {
            save("pp-low-end-${r.name}", render(edm, r, restOpen, lowEnd = true).bmp)
            save("pp-low-end-up-${r.name}", render(edm, r, restOpen, up = true, lowEnd = true).bmp)
            save("pp-plain-${r.name}", render(edm, r, null).bmp)
        }
        for (up in listOf(false, true)) for (bg in listOf(DARK, Color.WHITE)) {
            save("pp-EDM-720p-${corner(up)}-${if (bg == DARK) "dark" else "ice"}-still-open", render(edm, hd, restOpen, up, bg).bmp)
        }
    }

    // ------------------------------------------------------------------ §9.2 assertions

    @Test
    fun atRestTheFarEndIsMixBlack10AndC5HoldsFromThePixels() {
        val rect = RectF()
        for (r in rigs) for (team in teams) for (up in listOf(false, true)) {
            val shot = render(team, r, restOpen, up)
            r.pp.tabRest(up, rect)
            // The far end: the last row of the tab (its centre at 1 px from the edge, gradient position ≈ .99).
            val farY = if (up) rect.top else rect.bottom - 1f
            val far = shot.px(r.pillW / 2f, farY)
            val want = mixBlack(team.colours.tab, 0.10)
            assertTrue("${team.abbrev} ${r.name} ${corner(up)} far end ${hex(far)} vs ${hex(want)}", near(want, far, 3))
            // C5: white against the tab at gradient position .80 (the text's far cap edge), left of the text block.
            val pos = K.PP_C5_POINT
            val y = if (up) rect.bottom - pos * rect.height() else rect.top + pos * rect.height()
            val tabPx = shot.px(rect.left + 14f * r.s, y)
            val c5 = contrast(Color.WHITE, tabPx)
            // The text is white: the brightest pixel of the text band is a stem.
            val textCy = if (up) -K.PP_TEXT_CENTRE * r.s else r.pillH + K.PP_TEXT_CENTRE * r.s
            var best = 0
            var bl = -1.0
            for (yy in (textCy - 6f * r.s).toInt()..(textCy + 6f * r.s).toInt()) {
                for (xx in (rect.left + 30f * r.s).toInt()..(rect.right - 30f * r.s).toInt()) {
                    val p = shot.px(xx.toFloat(), yy.toFloat())
                    val v = lum(p)
                    if (v > bl) { bl = v; best = p }
                }
            }
            println("C5 from pixels ${team.abbrev} ${r.name} ${corner(up)}: tab ${hex(tabPx)} %.2f, stem ${hex(best)}".format(c5))
            assertTrue("${team.abbrev} C5 $c5", c5 >= K.PP_C5_MIN)
            assertTrue("${team.abbrev} text stem ${hex(best)}", Color.red(best) >= 245 && Color.green(best) >= 245 && Color.blue(best) >= 245)
        }
    }

    @Test
    fun c6HoldsFromThePixelsOverWhiteIce() {
        for (r in rigs) for (team in teams) {
            val shot = render(team, r, restOpen, bg = Color.WHITE)
            val x = if (team.side == Side.AWAY) K.PP_C6_X * r.s else r.pillW - K.PP_C6_X * r.s
            val p = shot.px(x, r.pillH - 8f * r.s)
            val c6 = contrast(Color.WHITE, p)
            println("C6 from pixels ${team.abbrev} ${r.name}: ${hex(p)} %.2f".format(c6))
            assertTrue("${team.abbrev} ${r.name} C6 $c6", c6 >= K.PP_C6_MIN)
        }
    }

    /** No tab pixel lands inside the pill, at any frame: every row on the pill's side of the clip edge is the plain bug's. */
    @Test
    fun noTabPixelIsInsideThePill() {
        for (r in rigs + hd) for (team in listOf(teams[0], teams[1])) for (up in listOf(false, true)) for (shot in shots) {
            val f = frame(shot).apply { pillClosed = true }
            val tab = render(team, r, f, up)
            val plain = render(team, r, null, up)
            val edge = r.pp.clipEdge
            for (y in 0 until tab.bmp.height) {
                val py = y - tab.oy
                val inside = if (up) py >= 0f else py < edge
                if (!inside) continue
                for (x in 0 until tab.bmp.width) {
                    assertEquals("${team.abbrev} ${r.name} ${corner(up)} ${shot.name} ($x,$y)", plain.at(x, y), tab.at(x, y))
                }
            }
        }
    }

    /** N9: at 1280 × 720 the clip edge is a device px, so no background row shows between the pill and the tab. */
    @Test
    fun thereIsNoSeamRowAt720p() {
        val r = hd
        val rect = RectF()
        for (up in listOf(false, true)) for (bg in listOf(Color.WHITE, Color.rgb(255, 0, 255))) {
            val shot = render(teams[1], r, restOpen, up, bg)
            r.pp.tabRest(up, rect)
            val edge = if (up) 0 else r.pp.clipEdge.toInt()
            for (x in (rect.left + K.PP_TAB_RADIUS * r.s).toInt() until (rect.right - K.PP_TAB_RADIUS * r.s).toInt()) {
                for (y in edge - 2..edge + 1) {
                    val p = shot.px(x.toFloat() + 0.5f, y.toFloat() + 0.5f)
                    assertFalse("${corner(up)} seam at ($x,$y): ${hex(p)}", near(bg, p, 24))
                }
            }
        }
    }

    @Test
    fun theTintIsGlowTimesTintScaleAtThePowerPlayEndAndPlainGlassAtTheOther() {
        val tintOnly = PpFrame().apply { copyFrom(restOpen); tabClosed = true; halo = 0f }
        for (r in rigs) for (team in teams) for (bg in listOf(DARK, Color.WHITE)) {
            val shot = render(team, r, tintOnly, bg = bg)
            val plain = render(team, r, null, bg = bg)
            val cy = r.pillH / 2f
            val near6 = 6f * r.s
            val x = if (team.side == Side.AWAY) near6 else r.pillW - near6
            val base = plain.px(x, cy)
            // The shader's alpha at the pixel's centre (.9 → .5 over the first quarter), times the paint's.
            val px = (x.toInt() + 0.5f).let { if (team.side == Side.AWAY) it else r.pillW - it } / r.pillW
            val a = (0.9f - 0.4f * (px / 0.25f)) * (team.colours.tintScale * 255f).roundToInt() / 255f
            val g = team.colours.glow
            fun ch(b: Int, t: Int) = (b * (1 - a) + t * a).roundToInt()
            val want = Color.rgb(ch(Color.red(base), Color.red(g)), ch(Color.green(base), Color.green(g)), ch(Color.blue(base), Color.blue(g)))
            val got = shot.px(x, cy)
            assertTrue("${team.abbrev} ${r.name} tint ${hex(got)} vs ${hex(want)}", near(want, got, 4))
            val fx = if (team.side == Side.AWAY) r.pillW - near6 else near6
            assertEquals("${team.abbrev} the far end is plain glass", plain.px(fx, cy), shot.px(fx, cy))
        }
    }

    @Test
    fun theHaloLiesInsideThePill() {
        val haloOn = PpFrame().apply { copyFrom(restOpen); tabClosed = true; tint = 0f }
        val haloOff = PpFrame().apply { copyFrom(haloOn); halo = 0f }
        for (r in rigs) for (team in teams) {
            val on = render(team, r, haloOn, bg = TRANSPARENT)
            val off = render(team, r, haloOff, bg = TRANSPARENT)
            var differs = 0
            for (y in 0 until on.bmp.height) for (x in 0 until on.bmp.width) {
                val a = on.at(x, y)
                val b = off.at(x, y)
                if (a == b) continue
                differs++
                val px = x - on.ox + 0.5f
                val py = y - on.oy + 0.5f
                assertTrue("${team.abbrev} ${r.name} halo outside the pill at ($px,$py)", insidePill(r, px, py, -1f))
            }
            assertTrue("${team.abbrev} ${r.name} the halo draws: $differs px", differs > 200)
        }
    }

    /** B2: a close slides back the words it opened with (checked early: by about 100 ms the slide has taken the text under the pill edge). */
    @Test
    fun closeFramesStillShowTheOpeningText() {
        for (r in rigs) for (up in listOf(false, true)) for (t in listOf(25f, 50f)) {
            val f = frame(Shot(PpTransition.CLOSE, t))
            assertTrue(f.textAlpha > 0f)
            val shot = render(teams[1], r, f, up)
            val noText = render(teams[1], r, PpFrame().apply { copyFrom(f); textAlpha = 0f }, up)
            val other = render(teams[1], r, f, up, label = PpLabel.TWO_MAN)
            var text = 0
            var diff = 0
            for (y in 0 until shot.bmp.height) for (x in 0 until shot.bmp.width) {
                val p = shot.at(x, y)
                if (p != noText.at(x, y)) text++
                if (p != other.at(x, y)) diff++
            }
            assertTrue("${r.name} ${corner(up)} text at close $t: $text px", text > 200)
            assertTrue("${r.name} ${corner(up)} the label drawn is the content's: $diff px", diff > 30)
        }
    }

    @Test
    fun afterACloseTheFrameEqualsThePlainBug() {
        val f = frame(Shot(PpTransition.CLOSE, 600f))
        assertTrue(f.tabClosed && f.pillClosed)
        for (r in rigs) for (team in teams) for (up in listOf(false, true)) {
            assertTrue("${team.abbrev} ${r.name} ${corner(up)}", render(team, r, f, up).bmp.sameAs(render(team, r, null, up).bmp))
        }
        // And the still-closed frame, and an open with no content or pill side.
        for (r in rigs) {
            assertTrue(render(teams[0], r, restClosed).bmp.sameAs(render(teams[0], r, null).bmp))
        }
    }

    @Test
    fun nothingIsDrawnOutsideThePillPlusTheLayerMargin() {
        val extra = 24
        val f = PpFrame()
        for (r in rigs) for (team in listOf(teams[0], teams[1])) for (up in listOf(false, true)) {
            val times = (0..1_900 step 50).map { Shot(PpTransition.OPEN, it.toFloat()) } +
                (0..600 step 50).map { Shot(PpTransition.CLOSE, it.toFloat()) }
            for (shot in times) {
                val out = render(team, r, frame(shot, f), up, TRANSPARENT, extra = extra)
                val hiX = out.bmp.width - extra
                val hiY = out.bmp.height - extra
                for (y in 0 until out.bmp.height) for (x in 0 until out.bmp.width) {
                    if (x in extra until hiX && y in extra until hiY) continue
                    assertEquals("${team.abbrev} ${r.name} ${corner(up)} ${shot.name} ($x,$y)", 0, Color.alpha(out.at(x, y)))
                }
            }
        }
    }

    /** §3.6: low-end mode bakes no shadow and no halo, so nothing is drawn outside the pill and the tab. */
    @Test
    fun lowEndFramesHaveNoShadowOrHalo() {
        val rect = RectF()
        for (r in rigs) {
            assertFalse(r.ppLow.shadowBaked)
            assertTrue(r.pp.shadowBaked)
            for (team in listOf(teams[0], teams[1])) for (up in listOf(false, true)) {
                val low = render(team, r, restOpen, up, TRANSPARENT, lowEnd = true)
                r.ppLow.tabRest(up, rect)
                for (y in 0 until low.bmp.height) for (x in 0 until low.bmp.width) {
                    val px = x - low.ox + 0.5f
                    val py = y - low.oy + 0.5f
                    if (px > -3f && px < r.pillW + 3f && py > -3f && py < r.pillH + 3f) continue
                    if (px > rect.left - 1f && px < rect.right + 1f && py > rect.top - 1f && py < rect.bottom + 1f) continue
                    assertEquals("${team.abbrev} ${r.name} ${corner(up)} shadow at ($px,$py)", 0, Color.alpha(low.at(x, y)))
                }
                assertFalse("${team.abbrev} no halo baked in low-end", r.ppLow.haloBaked(team.side))
                // The full painter does draw its shadow there.
                val full = render(team, r, restOpen, up, TRANSPARENT)
                val sy = if (up) rect.top - 4f * r.s else rect.bottom + 4f * r.s
                assertTrue("${team.abbrev} ${r.name} the full shadow draws", Color.alpha(full.px(r.pillW / 2f, sy)) > 0)
            }
        }
    }

    /**
     * The tab and the pill track may hold different sides at once (a side change, or a pinned PP GOAL tab while the pill
     * has moved on). Each side's colours are their own state: the tab takes `content.side`'s, the tint and halo
     * `pillSide`'s, and a later `begin` for one side never recolours the other.
     */
    @Test
    fun theTabAndTheTintCanBeDifferentTeams() {
        val cgy = teams[0]
        val edm = teams[1]
        val rect = RectF()
        for (r in rigs) for (up in listOf(false, true)) {
            val view = ScoreBugFixtures.view(awayAbbrev = "CGY", homeAbbrev = "EDM")
            val frame = r.painter.frame(view, res)
            val logos = r.logos("CGY", "EDM")
            r.labels("CGY", "EDM")
            // Begin the tab's side first, then the pill's: the second begin must leave the first side alone.
            r.pp.begin(Side.AWAY, cgy.colours)
            r.pp.begin(Side.HOME, edm.colours)
            fun draw(f: PpFrame?, pillSide: Side?): Out {
                val bmp = Bitmap.createBitmap(r.layerW, r.layerH, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.drawColor(Color.rgb(20, 22, 28))
                c.translate(r.origin.toFloat(), r.origin.toFloat())
                r.painter.drawFrame(
                    c, r.glass, frame, logos, true, false, null, null, null, false,
                    if (f != null) r.pp else null, f, PpContent(Side.AWAY, PpLabel.PP_GOAL, PpContent.NO_SECS), pillSide, up,
                )
                return Out(bmp, r.origin.toFloat(), r.origin.toFloat())
            }
            val tintOnly = PpFrame().apply { copyFrom(restOpen); halo = 0f }
            val shot = draw(tintOnly, Side.HOME)
            val plain = draw(null, null)
            // The tab is CGY's.
            r.pp.tabRest(up, rect)
            val far = shot.px(r.pillW / 2f, if (up) rect.top else rect.bottom - 1f)
            val want = mixBlack(cgy.colours.tab, 0.10)
            assertTrue("${r.name} ${corner(up)} tab ${hex(far)} vs CGY ${hex(want)}", near(want, far, 3))
            // The tint is EDM's, at the home end; the away end is plain glass.
            val cy = r.pillH / 2f
            val x = r.pillW - 6f * r.s
            val base = plain.px(x, cy)
            val got = shot.px(x, cy)
            val g = edm.colours.glow
            assertTrue("${r.name} home end ${hex(got)} leans to EDM's glow", Color.red(got) - Color.red(base) > 100 && Color.red(g) > 200)
            assertTrue("${r.name} home end is orange, not CGY red: ${hex(got)}", Color.green(got) - Color.green(base) > 25)
            assertEquals("${r.name} away end plain", plain.px(6f * r.s, cy), shot.px(6f * r.s, cy))
        }
    }

    /**
     * §4.1 and WP-1's `tabInset` / `tabDepth`: the tab's rest rect uses whole-px insets (31 / 39, width 342 / 428) and
     * depths 36 / 45, so it matches `tabBox` exactly.
     */
    @Test
    fun theTabRectMatchesTheRoundedGeometry() {
        val rect = RectF()
        for ((r, inset, depth) in listOf(Triple(std, 31f, 36f), Triple(big, 39f, 45f))) {
            r.pp.tabRest(false, rect)
            assertEquals("${r.name} inset", inset, rect.left, 1e-4f)
            assertEquals("${r.name} width", r.pillW - 2 * inset, rect.width(), 1e-4f)
            assertEquals("${r.name} depth below", depth, rect.bottom - r.pillH, 1e-4f)
            r.pp.tabRest(true, rect)
            assertEquals("${r.name} depth above", -depth, rect.top, 1e-4f)
        }
        assertEquals(342f, std.pillW - 2 * 31f, 0f)
        assertEquals(428f, big.pillW - 2 * 39f, 0f)
    }

    /** §4.1: every label of the screenshot teams, and the widest ("WPG POWER PLAY"), fits at 20 px with tracking. */
    @Test
    fun theLabelsFitAtTheirFullSize() {
        for (r in rigs) {
            for (pair in listOf("WPG" to "MTL", "CGY" to "EDM", "NSH" to "PHI")) {
                r.pp.bakeLabels(pair.first, pair.second, res)
                for (i in 0 until 5) {
                    assertEquals("${r.name} ${pair} label $i", K.PP_TEXT_PX, r.pp.labelPx(i), 0f)
                    assertTrue(r.pp.labelTracked(i))
                }
            }
            assertTrue(r.pp.labelsReady)
        }
    }

    /**
     * Fix pass S3: a halo is drawn only under the very logo bitmap it was baked from. Another bitmap on that side (the next
     * game's, or none yet) draws no halo, and [PowerPlayPainter.clearHalos] drops it.
     */
    @Test
    fun aHaloIsDrawnOnlyForTheLogoItWasBakedFrom() {
        val r = std
        val team = teams.first { it.side == Side.HOME }
        val pp = PowerPlayPainter(r.s, r.d, r.pillW, r.pillH, bold).apply { bakeSize() }
        pp.begin(Side.HOME, team.colours)
        val a = logoBitmap(team.colours.base, r.logoW, r.logoH)
        val b = logoBitmap(team.colours.base, r.logoW, r.logoH)
        pp.bakeHalo(Side.HOME, a)
        assertTrue(pp.haloBakedFor(Side.HOME, a))
        assertFalse(pp.haloBakedFor(Side.HOME, b))
        val f = PpFrame()
        PowerPlayTimeline.sample(PpTrack.PILL, PpTransition.STILL, 0f, PpFrame(), true, f)
        f.tabClosed = true
        fun draw(logo: Bitmap?): IntArray {
            val bmp = Bitmap.createBitmap(Math.round(r.pillW), Math.round(r.pillH), Bitmap.Config.ARGB_8888)
            pp.draw(Canvas(bmp), f, null, Side.HOME, false, logo)
            return IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        }
        val none = draw(null)
        val withA = draw(a)
        assertFalse("the halo is drawn under its own logo", none.contentEquals(withA))
        assertTrue("never under another bitmap", none.contentEquals(draw(b)))
        pp.clearHalos()
        assertFalse(pp.haloBaked(Side.HOME))
        assertTrue("and not after clearHalos", none.contentEquals(draw(a)))
    }

    /** §9.2: 300 `drawFrame` calls with the tab open and pulsing allocate (next to) nothing. */
    @Test
    fun drawFrameWithTheTabAllocatesLittle() {
        val r = std
        val team = teams[1]
        val view = ScoreBugFixtures.ppView(team.abbrev, team.side)
        val frame = r.painter.frame(view, res)
        val logos = r.logos(view.away.abbrev, view.home.abbrev)
        r.labels(view.away.abbrev, view.home.abbrev)
        r.pp.begin(team.side, team.colours)
        logos.home.image?.let { r.pp.bakeHalo(Side.HOME, it.asAndroidBitmap()) }
        val bmp = Bitmap.createBitmap(r.layerW, r.layerH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(r.origin.toFloat(), r.origin.toFloat())
        val frames = Array(300) { frame(Shot(PpTransition.OPEN, 500f + it * 3f)) }
        val content = PpContent(team.side, PpLabel.ONE_MAN, 79)
        fun pass() {
            for ((i, f) in frames.withIndex()) {
                content.secs = 79 - i % 60
                r.painter.drawFrame(c, r.glass, frame, logos, true, false, null, null, null, false, r.pp, f, content, team.side, i % 2 == 0)
            }
        }
        repeat(2) { pass() }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        pass()
        val after = bean.getThreadAllocatedBytes(id)
        println("ScoreBugPainter.drawFrame with the tab × 300: ${after - before} B")
        assertTrue("allocated ${after - before} B", after - before < 4_096)
    }

    /** Inside the pill's rounded rect by at least [inset] px (a negative inset allows that much outside). */
    private fun insidePill(r: Rig, x: Float, y: Float, inset: Float): Boolean {
        val rad = r.pillH / 2f
        if (y < inset || y > r.pillH - inset || x < inset || x > r.pillW - inset) return false
        val cx = x.coerceIn(rad, r.pillW - rad)
        val dx = x - cx
        val dy = y - rad
        return dx * dx + dy * dy <= (rad - inset) * (rad - inset)
    }

    private companion object {
        const val DARK = 0x01010101
        const val TRANSPARENT = 0x00000000
    }
}
