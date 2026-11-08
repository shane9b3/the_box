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
import app.thebox.core.sports.CentreText
import app.thebox.core.sports.DownText
import app.thebox.core.sports.FieldZone
import app.thebox.core.sports.League
import app.thebox.core.sports.NflScore
import app.thebox.core.sports.NflScoreFrame
import app.thebox.core.sports.NflScoreKind
import app.thebox.core.sports.NflScoreTimeline
import app.thebox.core.sports.NflTab
import app.thebox.core.sports.NflView
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.PowerPlayTimeline
import app.thebox.core.sports.PpFrame
import app.thebox.core.sports.PpTrack
import app.thebox.core.sports.PpTransition
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.TabLead
import app.thebox.core.sports.TabTail
import app.thebox.core.sports.TeamColours
import app.thebox.core.sports.TeamLine
import app.thebox.ui.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
import kotlin.math.sqrt

/**
 * docs/design/m6-nfl.md §10.2 (WP-3): the NFL bug drawn through the real composite [NflBugPainter.drawFrame] with the
 * bundled fonts: the plain bug in each §4.2 state, the possession tab (BALL, KICKOFF, EXTRA POINT, RED ZONE and its
 * cross-fade midpoint; top and bottom corners; Standard and Large), and the takeover at §10.2's instants for every kind
 * and for KC, BUF, CIN, LV and DAL over dark video and white. Frames go to `ui/build/nfl-screenshots/` with contact
 * sheets, for review by eye; the pixel assertions are §10.2's.
 *
 * Team colours are passed explicitly (the §6 rows; `NflTeamColours` is WP-1's): `tab = base` (S1). The logos are
 * synthetic stand-ins (a team-colour oval with a light border).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NflScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val res = context.resources
    private val semi = sansTypeface(context, R.font.instrument_sans_semibold)
    private val bold = sansTypeface(context, R.font.instrument_sans_bold)
    private val barlow = sansTypeface(context, R.font.barlow_condensed_black_italic)
    private val out = File("build/nfl-screenshots").apply { mkdirs() }

    private class Team(val abbrev: String, val name: String, base: Int, accent: Int, glow: Int, pop: Int) {
        val colours = TeamColours(base or OPAQUE, accent or OPAQUE, glow or OPAQUE, pop or OPAQUE, tab = base or OPAQUE)
    }

    // §6 rows: base, accent, glow, pop.
    private val kc = Team("KC", "Chiefs", 0xE31837, 0xFFB81C, 0xE31837, 0xFFB81C)
    private val buf = Team("BUF", "Bills", 0x00338D, 0xC60C30, 0xC60C30, 0xE0798D)
    private val cin = Team("CIN", "Bengals", 0xFB4F14, 0x000000, 0xFB4F14, 0xFC6937)
    private val lv = Team("LV", "Raiders", 0x000000, 0xA5ACAF, 0xA5ACAF, 0xA5ACAF)
    private val dal = Team("DAL", "Cowboys", 0x041E42, 0x869397, 0x869397, 0x8C989C)
    private val teams = listOf(kc, buf, cin, lv, dal)
    private fun team(abbrev: String) = teams.first { it.abbrev == abbrev }

    private val times = listOf(0, 216, 400, 700, 1_000, 1_400, 1_800, 2_500, 3_168, 3_600, 4_000, 4_500)
    private val kinds = NflScoreKind.values().toList()
    private fun points(k: NflScoreKind) = when (k) {
        NflScoreKind.TOUCHDOWN -> 6; NflScoreKind.EXTRA_POINT -> 1; NflScoreKind.FIELD_GOAL -> 3
        NflScoreKind.SAFETY, NflScoreKind.TWO_POINT -> 2
    }

    /** One size: the painters, the glass bakes, the glow masks and the tab bakes (built once, as in the app). */
    private inner class Rig(val large: Boolean, val d: Float = 1f) {
        val s = (if (large) K.SCORE_LARGE_SCALE else 1f) * d
        val pillW = (if (large) K.NFL_SCORE_LARGE_W else K.NFL_SCORE_W) * d
        val pillH = (if (large) K.SCORE_LARGE_H else K.SCORE_H) * d
        val m = (if (large) K.GOAL_LAYER_MARGIN_LARGE else K.GOAL_LAYER_MARGIN) * d
        val painter = NflBugPainter(s, semi, bold, pillW, pillH)
        val score = NflScorePainter(s, pillW, pillH, barlow, bold, large)
        val glow: GlowMasks by lazy { score.bakeGlow() }
        val glass: Bitmap by lazy { painter.bakeGlass(withShadow = true) }
        val tab = NflTabPainter(s, d, pillW, pillH, bold, semi).apply { bakeSize(true) }
        val logoW = Math.round(K.SCORE_LOGO_W * s)
        val logoH = Math.round(K.SCORE_LOGO_H * s)
        fun labels(away: String, home: String) { if (!tab.labelsFor(away, home)) tab.bakeLabels(away, home, res) }
    }

    private val std by lazy { Rig(false) }
    private val big by lazy { Rig(true) }
    private fun rig(large: Boolean) = if (large) big else std

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

    private fun logos(away: String, home: String, r: Rig): FollowLogos {
        val l = FollowLogos({ url, w, h -> logoBitmap(teams.firstOrNull { it.abbrev == url }?.colours?.base ?: Color.GRAY, w, h) }, CoroutineScope(Dispatchers.Unconfined))
        l.start(away, home, r.logoW, r.logoH)
        return l
    }

    // ------------------------------------------------------------------ views

    private fun view(
        away: Team = kc,
        home: Team = buf,
        awayScore: Int = 24,
        homeScore: Int = 17,
        top: CentreText = CentreText.Period(PeriodLabel(PeriodKind.REG, 2)),
        bottom: CentreText? = CentreText.Clock,
        dim: Boolean = false,
        clock: Int? = 222,
        nfl: NflView? = NflView(DownText.Down(1, 10, false), NflTab(Side.AWAY, TabLead.BALL, TabTail.Field(FieldZone.OWN, 35))),
    ) = ScoreBugView(
        away = TeamLine(away.abbrev, away.name, awayScore, null, shortName = away.name),
        home = TeamLine(home.abbrev, home.name, homeScore, null, shortName = home.name),
        top = top, bottom = bottom, dim = dim, goalSeq = 0, goalSide = null, clockSecs = clock,
        league = League.NFL, nfl = nfl,
    )

    private class Shot(val bmp: Bitmap, val ox: Float, val oy: Float) {
        fun px(x: Float, y: Float): Int = bmp.getPixel((x + ox).toInt(), (y + oy).toInt())
    }

    private fun restTab(open: Boolean = true) =
        PowerPlayTimeline.sample(PpTrack.TAB, PpTransition.STILL, 0f, PpFrame(), open, PpFrame())

    /**
     * The composite. [scorer] non-null: a takeover of [kind] by [scorer] at [t] ([still] = the still version), the tab
     * pinned to the score label and pulsing; [content] null with no scorer = no tab.
     */
    private fun render(
        large: Boolean,
        v: ScoreBugView = view(),
        scorer: Side? = null,
        kind: NflScoreKind = NflScoreKind.TOUCHDOWN,
        t: Float = 0f,
        bg: Int = DARK,
        still: Boolean = false,
        content: NflTabContent? = null,
        up: Boolean = false,
        redZone: Float = 0f,
        glowOn: Boolean = true,
        extra: Int = 0,
        frameEdit: (NflScoreFrame) -> Unit = {},
        popDigits: String? = null,
    ): Shot {
        val r = rig(large)
        val f = r.painter.frame(v, res)
        val lg = logos(v.away.abbrev, v.home.abbrev, r)
        r.labels(v.away.abbrev, v.home.abbrev)
        r.tab.begin(Side.AWAY, team(v.away.abbrev).colours)
        r.tab.begin(Side.HOME, team(v.home.abbrev).colours)
        var g: NflScoreFrame? = null
        var tabContent = content
        var pulse = 0f
        if (scorer != null) {
            val tm = team(if (scorer == Side.AWAY) v.away.abbrev else v.home.abbrev)
            val logo = (if (scorer == Side.AWAY) lg.away else lg.home).image?.asAndroidBitmap()
            r.score.begin(scorer, tm.colours, logo, tm.abbrev, NflScore(kind, points(kind), v.away.score ?: 0, v.home.score ?: 0))
            g = if (still) NflScoreTimeline.sampleStill(kind, null, t, NflScoreFrame()) else NflScoreTimeline.sample(kind, t, NflScoreFrame())
            frameEdit(g)
            if (content == null && t < K.NFL_TAKEOVER_MS) tabContent = NflTabContent(scorer, score = kind, points = points(kind))
            pulse = g.tabPulse
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
            c, r.glass, f, lg, logoWaited = true, placing = false,
            score = if (scorer != null) r.score else null, g = g, glow = r.glow, glowOn = glowOn,
            tab = if (tabContent != null) r.tab else null, p = restTab(), content = tabContent, up = up, redZone = redZone,
            pulse = pulse, popDigits = popDigits,
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

    /** The pixel centre (x, y), pill coordinates, inside the pill's round rect. */
    private fun insidePill(r: Rig, x: Float, y: Float): Boolean {
        val rad = r.pillH / 2f
        if (y < 0f || y > r.pillH || x < 0f || x > r.pillW) return false
        val cx = x.coerceIn(rad, r.pillW - rad)
        val dx = x - cx
        val dy = y - rad
        return dx * dx + dy * dy <= rad * rad
    }

    private fun suffix(large: Boolean) = if (large) "large" else "std"

    private fun tabContents(side: Side = Side.AWAY) = listOf(
        "ball" to NflTabContent(side, TabLead.BALL, TabTail.Field(FieldZone.OWN, 35)),
        "ball-opp" to NflTabContent(side, TabLead.BALL, TabTail.Field(FieldZone.OPP, 8)),
        "midfield" to NflTabContent(side, TabLead.BALL, TabTail.Field(FieldZone.MIDFIELD, 50)),
        "kickoff" to NflTabContent(side, TabLead.KICKOFF, TabTail.Field(FieldZone.OWN, 35)),
        "extra-point" to NflTabContent(side, TabLead.BALL, TabTail.ExtraPoint),
        "red-zone" to NflTabContent(side, TabLead.BALL, TabTail.RedZone),
        "lead-alone" to NflTabContent(side, TabLead.BALL, null),
        "pinned" to NflTabContent(side, score = NflScoreKind.TOUCHDOWN, points = 6),
    )

    // ------------------------------------------------------------------ frames and contact sheets

    @Test
    fun takeoverFramesForEveryKindTeamAndSizeAndTheContactSheets() {
        for (kind in kinds) for (large in listOf(false, true)) for ((bgName, bg) in listOf("dark" to DARK, "white" to Color.WHITE)) {
            // Columns = the five teams (each scoring at home against KC, KC against BUF); rows = §10.2's instants.
            val columns = teams.map { tm ->
                val v = if (tm == kc) view(kc, buf, awayScore = 7, homeScore = 3) else view(kc, tm, awayScore = 3, homeScore = 7)
                val side = if (tm == kc) Side.AWAY else Side.HOME
                times.map { t ->
                    render(large, v, side, kind, t.toFloat(), bg).bmp.also {
                        if (tm == kc && bg == DARK) save("nfl-${kind.name.lowercase()}-${tm.abbrev}-${suffix(large)}-t$t", it)
                    }
                } + render(large, v, side, kind, 1_000f, bg, still = true).bmp + render(large, v, side, kind, 4_000f, bg, still = true).bmp
            }
            val rows = columns[0].indices.map { i -> columns.map { it[i] } }
            sheet("nfl-sheet-${kind.name.lowercase()}-${suffix(large)}-$bgName", rows)
        }
        assertTrue(File(out, "nfl-sheet-touchdown-std-dark.png").length() > 10_000)
    }

    @Test
    fun thePlainBugInEachState() {
        val states = listOf(
            "live-no-situation" to view(nfl = NflView(null, null)),
            "live-down" to view(),
            "live-red-zone" to view(nfl = NflView(DownText.Down(1, null, true), NflTab(Side.AWAY, TabLead.BALL, TabTail.RedZone))),
            "fourth-and-goal" to view(top = CentreText.Period(PeriodLabel(PeriodKind.REG, 3)), nfl = NflView(DownText.Down(4, 13, true), null)),
            "pat" to view(nfl = NflView(DownText.Pat, NflTab(Side.AWAY, TabLead.BALL, TabTail.ExtraPoint))),
            "kickoff" to view(nfl = NflView(DownText.Kickoff, NflTab(Side.AWAY, TabLead.KICKOFF, TabTail.Field(FieldZone.OWN, 35)))),
            "end-1st" to view(top = CentreText.EndOf(PeriodLabel(PeriodKind.REG, 1)), bottom = null, nfl = null),
            "halftime" to view(top = CentreText.Halftime, bottom = null, nfl = null),
            "final" to view(top = CentreText.Final(PeriodKind.REG), bottom = null, nfl = null),
            "final-ot" to view(top = CentreText.Final(PeriodKind.OT), bottom = null, nfl = null),
            "ot" to view(top = CentreText.Period(PeriodLabel(PeriodKind.OT, 1)), clock = 594),
            "stale" to view(top = CentreText.NoUpdate, dim = true, nfl = null),
            "delayed" to view(top = CentreText.Delayed, dim = true, nfl = null),
            "stale-in-a-break" to view(top = CentreText.NoUpdate, bottom = null, dim = true, nfl = null),
        )
        for (large in listOf(false, true)) {
            val shots = states.map { (name, v) ->
                val tab = v.nfl?.tab?.let { NflTabContent(it.side, it.lead, it.tail) }
                render(large, v, content = tab, redZone = if (tab?.tail == TabTail.RedZone) 1f else 0f).bmp
                    .also { save("nfl-plain-$name-${suffix(large)}", it) }
            }
            sheet("nfl-plain-sheet-${suffix(large)}", shots.map { listOf(it) })
        }
        // The words, the one-line merge and the fit (type ≥ 18 everywhere).
        val r = std
        val live = r.painter.frame(view(), res)
        assertEquals("2nd", live.topText); assertTrue(live.clockOnBottom); assertEquals("1st & 10", live.down); assertEquals(22f, live.downPx)
        val goal = r.painter.frame(view(nfl = NflView(DownText.Down(4, 13, true), null)), res)
        assertEquals("4th & Goal", goal.down)
        assertTrue("4th & Goal steps down: ${goal.downPx}", goal.downPx in 18f..22f)
        val m = Paint().apply { typeface = bold; textSize = goal.downPx }
        assertTrue(m.measureText("4th & Goal") <= K.NFL_DOWN_W - K.NFL_DOWN_FIT_AIR)
        val half = r.painter.frame(view(top = CentreText.Halftime, bottom = null, nfl = NflView(DownText.Pat, null)), res)
        assertTrue(half.oneLine); assertEquals("Halftime", half.topText); assertNull(half.down); assertEquals(24f, half.topPx)
        assertEquals("End 1st", r.painter.frame(view(top = CentreText.EndOf(PeriodLabel(PeriodKind.REG, 1)), bottom = null), res).topText)
        assertEquals("PAT", r.painter.frame(view(nfl = NflView(DownText.Pat, null)), res).down)
        assertEquals("KICKOFF", r.painter.frame(view(nfl = NflView(DownText.Kickoff, null)), res).down)
        assertEquals("3rd", r.painter.frame(view(nfl = NflView(DownText.Down(3, null, false), null)), res).down)
        for ((_, v) in states) {
            val f = r.painter.frame(v, res)
            assertTrue(f.topPx >= 18f && f.downPx >= 18f)
        }
        // §4.1: the columns.
        val cols = r.painter.columns
        assertEquals(listOf(48f, 113f, 150f, 204f, 257f, 313.5f, 369f, 407f, 472f), cols.toList())
    }

    @Test
    fun theTabInEveryContentCornerAndSize() {
        for (large in listOf(false, true)) for (up in listOf(false, true)) {
            val shots = tabContents().map { (name, content) ->
                render(large, content = content, up = up, redZone = if (content.tail == TabTail.RedZone) 1f else 0f).bmp
                    .also { save("nfl-tab-$name-${if (up) "bottom-corner" else "top-corner"}-${suffix(large)}", it) }
            } + render(large, content = NflTabContent(Side.AWAY, TabLead.BALL, TabTail.Field(FieldZone.OPP, 12)), up = up, redZone = 0.5f).bmp
                .also { save("nfl-tab-red-zone-crossfade-${if (up) "bottom-corner" else "top-corner"}-${suffix(large)}", it) } +
                render(large, v = view(kc, buf), content = NflTabContent(Side.HOME, TabLead.BALL, TabTail.RedZone), up = up, redZone = 1f).bmp
            sheet("nfl-tab-sheet-${if (up) "bottom" else "top"}-${suffix(large)}", shots.map { listOf(it) })
        }
        // §4.3 fit: every content fits the room at 20 px.
        for (large in listOf(false, true)) {
            val r = rig(large)
            r.labels("WSH", "BUF")
            for (side in Side.values()) for ((name, content) in tabContents(side) + ("two-point" to NflTabContent(side, score = NflScoreKind.TWO_POINT, points = 2))) {
                val w = r.tab.blockWidth(content)!!
                assertTrue("$name ${suffix(large)} $w ≤ ${r.tab.room}", w <= r.tab.room)
            }
            val widest = r.tab.blockWidth(NflTabContent(Side.AWAY, TabLead.BALL, TabTail.ExtraPoint))!! / r.s
            println("widest tab content \"WSH BALL | EXTRA POINT\" with the icon: %.1f design px (room %.1f)".format(widest, r.tab.room / r.s))
        }
    }

    // ------------------------------------------------------------------ §10.2 assertions

    @Test
    fun noTabPixelIsInsideThePill() {
        for (large in listOf(false, true)) for (up in listOf(false, true)) {
            val r = rig(large)
            val plain = render(large, bg = TRANSPARENT)
            for ((name, content) in tabContents()) {
                val shot = render(large, bg = TRANSPARENT, content = content, up = up, redZone = 1f)
                var differs = 0
                for (y in 0 until shot.bmp.height) for (x in 0 until shot.bmp.width) {
                    if (shot.bmp.getPixel(x, y) == plain.bmp.getPixel(x, y)) continue
                    differs++
                    val py = y - shot.oy
                    assertTrue("$name ${suffix(large)} up=$up tab pixel ($x,$y) in the pill", if (up) py < 0f else py >= r.tab.clipEdge)
                }
                assertTrue("$name draws", differs > 100)
            }
        }
    }

    @Test
    fun noBallOrPostPixelIsOutsideTheRoundRect() {
        for (large in listOf(false, true)) {
            val r = rig(large)
            for (kind in listOf(NflScoreKind.TOUCHDOWN, NflScoreKind.FIELD_GOAL, NflScoreKind.EXTRA_POINT)) {
                r.score.begin(Side.AWAY, kc.colours, null, "KC", NflScore(kind, points(kind), 7, 0))
                var t = 0f
                var seen = 0
                while (t <= 3_600f) {
                    val g = NflScoreTimeline.sample(kind, t, NflScoreFrame())
                    val without = NflScoreFrame().apply { copyFrom(g); runOn = false; arcOn = false; postsOn = false }
                    val a = takeoverOnly(r, g)
                    val b = takeoverOnly(r, without)
                    for (y in 0 until a.height) for (x in 0 until a.width) {
                        if (a.getPixel(x, y) == b.getPixel(x, y)) continue
                        seen++
                        val px = x + 0.5f - r.m
                        val py = y + 0.5f - r.m
                        assertTrue("$kind ${suffix(large)} at $t: ball/post pixel ($px,$py) outside the round rect", insidePill(r, px, py))
                    }
                    t += 25f
                }
                assertTrue("$kind draws its ball", seen > 500)
            }
        }
    }

    private fun takeoverOnly(r: Rig, g: NflScoreFrame): Bitmap {
        val bmp = Bitmap.createBitmap(Math.round(r.pillW + 2 * r.m), Math.round(r.pillH + 2 * r.m), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(r.m, r.m)
        r.score.drawTakeover(c, g)
        return bmp
    }

    @Test
    fun theBallSpritesInkIsWithinItsRadius() {
        for (large in listOf(false, true)) {
            val r = rig(large)
            val b = r.score.ballSprite
            val c = b.width / 2f
            var max = 0f
            for (y in 0 until b.height) for (x in 0 until b.width) {
                if (Color.alpha(b.getPixel(x, y)) == 0) continue
                max = maxOf(max, sqrt((x + 0.5f - c) * (x + 0.5f - c) + (y + 0.5f - c) * (y + 0.5f - c)))
            }
            val design = max / (K.NFL_ATLAS_SCALE * r.s) * (if (large) K.SCORE_LARGE_SCALE else 1f)
            println("ball ink ${suffix(large)}: %.2f px (limit %.2f)".format(design, K.NFL_BALL_INK_R * (if (large) 1.25f else 1f)))
            assertTrue("ink $design", design <= K.NFL_BALL_INK_R * (if (large) K.SCORE_LARGE_SCALE else 1f))
        }
    }

    @Test
    fun nothingIsDrawnOutsideThePillPlusTheLayerMargin() {
        val extra = 24
        for (large in listOf(false, true)) for (up in listOf(false, true)) {
            var t = 0f
            while (t <= 4_600f) {
                val shot = render(large, view(kc, buf, 7, 0), Side.AWAY, NflScoreKind.TOUCHDOWN, t, bg = TRANSPARENT, up = up, extra = extra)
                for (y in 0 until shot.bmp.height) for (x in 0 until shot.bmp.width) {
                    if (x in extra until shot.bmp.width - extra && y in extra until shot.bmp.height - extra) continue
                    assertEquals("${suffix(large)} up=$up ($x,$y) at $t", 0, Color.alpha(shot.bmp.getPixel(x, y)))
                }
                t += 300f
            }
            assertTrue(rig(large).glow.peakMargin <= rig(large).m + 0.01f)
        }
    }

    @Test
    fun theTabsFarStopAndContrastFromThePixels() {
        for (large in listOf(false, true)) for (tm in teams) {
            val r = rig(large)
            val v = if (tm == kc) view(kc, buf) else view(kc, tm)
            val side = if (tm == kc) Side.AWAY else Side.HOME
            val depth = K.PP_TAB_DEPTH * r.s
            val far = NflTabPainter.mixBlack(tm.colours.tab, 0.10f)
            for (up in listOf(false, true)) {
                val shot = render(large, v, content = NflTabContent(side, TabLead.BALL, TabTail.Field(FieldZone.OWN, 35)), up = up)
                val y = if (up) -depth + 1.5f else r.pillH + depth - 1.5f
                val px = shot.px(r.pillW / 2f, y)
                assertTrue("${tm.abbrev} ${suffix(large)} up=$up far stop ${hex(px)} vs ${hex(far)}", near(far, px, 8))
            }
            // C5: the brightest pixel of the lead's text band against the tab behind it, at the same height.
            val rest = RectF()
            r.tab.tabRest(false, rest)
            val shot = render(large, v, content = NflTabContent(side, TabLead.BALL, TabTail.Field(FieldZone.OWN, 35)))
            val cy = r.pillH + K.PP_TEXT_CENTRE * r.s
            val bgPx = shot.px(rest.left + K.PP_TAB_RADIUS * r.s + 2f, cy)
            var best = 0
            var bl = -1.0
            for (yy in (cy - 6f * r.s).toInt()..(cy + 6f * r.s).toInt()) for (xx in (rest.left + 40f * r.s).toInt()..(rest.centerX()).toInt()) {
                val p = shot.px(xx.toFloat(), yy.toFloat())
                if (lum(p) > bl) { bl = lum(p); best = p }
            }
            val c5 = contrast(best, bgPx)
            println("C5 from pixels ${tm.abbrev} ${suffix(large)}: %.2f".format(c5))
            assertTrue("${tm.abbrev} C5 $c5", c5 >= K.PP_C5_MIN)
        }
        // The red half: white "RED ZONE" against the red, from the pixels.
        for (large in listOf(false, true)) {
            val r = rig(large)
            val rest = RectF()
            r.tab.tabRest(false, rest)
            val shot = render(large, content = NflTabContent(Side.AWAY, TabLead.BALL, TabTail.RedZone), redZone = 1f)
            val cy = r.pillH + K.PP_TEXT_CENTRE * r.s
            val red = shot.px(rest.right - K.PP_TAB_RADIUS * r.s - 2f, cy)
            val redStop = NflTabPainter.mixBlack(K.NFL_RED_ZONE, 0.10f)
            assertTrue("the red far stop", near(redStop, shot.px(rest.right - 60f * r.s, r.pillH + K.PP_TAB_DEPTH * r.s - 1.5f), 8))
            var best = 0
            var bl = -1.0
            for (yy in (cy - 6f * r.s).toInt()..(cy + 6f * r.s).toInt()) for (xx in rest.centerX().toInt()..(rest.right - 30f * r.s).toInt()) {
                val p = shot.px(xx.toFloat(), yy.toFloat())
                if (lum(p) > bl) { bl = lum(p); best = p }
            }
            val cr = contrast(best, red)
            println("RED ZONE contrast from pixels ${suffix(large)}: %.2f (bg ${hex(red)})".format(cr))
            assertTrue("RED ZONE $cr", cr >= 4.5)
        }
    }

    @Test
    fun theRedZoneCrossFadeAndThePulse() {
        val r = std
        val field = NflTabContent(Side.AWAY, TabLead.BALL, TabTail.Field(FieldZone.OPP, 12))
        val none = render(false, content = field, redZone = 0f)
        val mid = render(false, content = field, redZone = 0.5f)
        val full = render(false, content = NflTabContent(Side.AWAY, TabLead.BALL, TabTail.RedZone), redZone = 1f)
        val rest = RectF().also { r.tab.tabRest(false, it) }
        val x = rest.right - K.PP_TAB_RADIUS * r.s - 2f
        val y = r.pillH + K.PP_TAB_DEPTH * r.s - 6f
        val a = none.px(x, y); val b = mid.px(x, y); val c = full.px(x, y)
        assertTrue("the midpoint is between ${hex(a)} ${hex(b)} ${hex(c)}", Color.red(b) in minOf(Color.red(a), Color.red(c))..maxOf(Color.red(a), Color.red(c)))
        // The pinned pulse brightens the tab (ADD = brightness(1 + .45 p)); unpinned content ignores the pulse.
        val pinned = NflTabContent(Side.AWAY, score = NflScoreKind.TOUCHDOWN, points = 6)
        val base = renderTabPulse(pinned, 0f).px(x, y)
        val peak = renderTabPulse(pinned, 1f).px(x, y)
        val expect = Color.rgb(minOf(255, Math.round(Color.red(base) * 1.45f)), minOf(255, Math.round(Color.green(base) * 1.45f)), minOf(255, Math.round(Color.blue(base) * 1.45f)))
        assertTrue("pulse ${hex(base)} → ${hex(peak)} ≈ ${hex(expect)}", near(expect, peak, 4))
        assertEquals(renderTabPulse(field, 0f).px(x, y), renderTabPulse(field, 1f).px(x, y))
    }

    private fun renderTabPulse(content: NflTabContent, pulse: Float): Shot {
        val r = std
        r.labels("KC", "BUF")
        r.tab.begin(Side.AWAY, kc.colours)
        val bmp = Bitmap.createBitmap(Math.round(r.pillW + 2 * r.m), Math.round(r.pillH + 2 * r.m), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        c.translate(r.m, r.m)
        r.tab.draw(c, restTab(), content, false, 0f, pulse)
        return Shot(bmp, r.m, r.m)
    }

    @Test
    fun atRestTheFrameIsThePlainBug() {
        for (large in listOf(false, true)) for (kind in kinds) for (t in listOf(4_500f, 6_000f)) {
            val v = view(kc, buf, 7, 0)
            val tab = NflTabContent(Side.AWAY, TabLead.BALL, TabTail.ExtraPoint)
            val plain = render(large, v, content = tab).bmp
            assertTrue("$kind ${suffix(large)} at $t", render(large, v, Side.AWAY, kind, t, content = tab).bmp.sameAs(plain))
            assertTrue("$kind still at $t", render(large, v, Side.AWAY, kind, t, still = true, content = tab).bmp.sameAs(plain))
        }
    }

    @Test
    fun thePairsDigitOverrideIsDrawn() {
        val v = view(kc, buf, 7, 0)
        val own = render(false, v, Side.AWAY, NflScoreKind.TOUCHDOWN, 3_800f).bmp
        val six = render(false, v, Side.AWAY, NflScoreKind.TOUCHDOWN, 3_800f, popDigits = "6").bmp
        assertTrue("the intermediate digit differs from the final", !own.sameAs(six))
        // After the takeover, the override still holds the digit (the plain bug with "6").
        val after = render(false, v, Side.AWAY, NflScoreKind.TOUCHDOWN, 4_600f, popDigits = "6").bmp
        val plain6 = render(false, view(kc, buf, 6, 0)).bmp
        assertTrue(after.sameAs(plain6))
    }

    @Test
    fun theWordsAreTheStringsAndTheAtlasIsSmall() {
        val ids = listOf(R.string.sports_nfl_td, R.string.sports_nfl_xp, R.string.sports_nfl_fg, R.string.sports_nfl_safety, R.string.sports_nfl_2pt)
        assertEquals(ids.map { res.getString(it) }, NflScorePainter.WORDS.toList())
        for (k in kinds) assertEquals(NflScorePainter.WORDS[k.ordinal].length, NflScoreTimeline.letterCount(k))
        val glyphs = NflScorePainter.WORDS.joinToString("").filter { it != ' ' }.toSet()
        assertEquals(20, glyphs.size)
        val d1 = std.score.atlasBitmap
        val d2 = Rig(false, d = 2f).score.atlasBitmap
        println("NFL glyph atlas d=1 ${d1.width} × ${d1.height} = ${d1.byteCount} B; d=2 ${d2.width} × ${d2.height} = ${d2.byteCount} B")
        assertTrue("d=1 ${d1.byteCount}", d1.byteCount < 400_000)
        assertTrue("d=2 ${d2.byteCount}", d2.byteCount < 1_600_000)
        for ((k, w) in std.score.wordWidth.withIndex()) println("word ${NflScorePainter.WORDS[k]}: %.1f px".format(w))
        assertTrue(std.score.wordWidth.all { it <= 310f })
        assertEquals(Bitmap.Config.ALPHA_8, std.score.ballSprite.config)
        assertEquals(Bitmap.Config.ALPHA_8, std.score.postsSprite.config)
    }

    @Test
    fun drawFrameAllocatesLittleMidTakeoverWithTheTabPulsing() {
        val r = std
        val v = view(kc, buf, 7, 0)
        val f = r.painter.frame(v, res)
        val lg = logos("KC", "BUF", r)
        r.labels("KC", "BUF")
        r.tab.begin(Side.AWAY, kc.colours)
        r.score.begin(Side.AWAY, kc.colours, lg.away.image?.asAndroidBitmap(), "KC", NflScore(NflScoreKind.FIELD_GOAL, 3, 7, 0))
        val content = NflTabContent(Side.AWAY, score = NflScoreKind.FIELD_GOAL, points = 3)
        val p = restTab()
        val bmp = Bitmap.createBitmap(Math.round(r.pillW + 2 * r.m), Math.round(r.pillH + 2 * r.m), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(r.m, r.m)
        val frames = Array(300) { NflScoreTimeline.sample(if (it % 2 == 0) NflScoreKind.FIELD_GOAL else NflScoreKind.TOUCHDOWN, 300f + it * 11f, NflScoreFrame()) }
        fun pass() {
            for (g in frames) r.painter.drawFrame(c, r.glass, f, lg, true, false, r.score, g, r.glow, true, r.tab, p, content, false, 0f, g.tabPulse)
        }
        repeat(2) { pass() }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        pass()
        val after = bean.getThreadAllocatedBytes(id)
        println("NflBugPainter.drawFrame × 300: ${after - before} B")
        assertTrue("allocated ${after - before} B", after - before < 4_096)
    }

    private companion object {
        const val DARK = 0x01010101
        const val TRANSPARENT = 0x00000000
        const val OPAQUE = 0xFF shl 24
    }
}
