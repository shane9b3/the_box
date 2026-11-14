package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.BarKind
import app.thebox.core.sports.CHL_LEAGUES
import app.thebox.core.sports.ChlTeamColours
import app.thebox.core.sports.League
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.PowerPlayKind
import app.thebox.core.sports.PowerPlayTimeline
import app.thebox.core.sports.PpContent
import app.thebox.core.sports.PpFrame
import app.thebox.core.sports.PpLabel
import app.thebox.core.sports.PpTrack
import app.thebox.core.sports.PpTransition
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.StackedColourTable
import app.thebox.core.sports.TeamColours
import app.thebox.ui.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * (CHL WP-4, docs/design/m6-chl-leagues.md §6.2, §7.4) The owner's colour sign-off sheets: every CHL team of a league as a
 * Stacked row pair (the team away, the league's next team home, the pair after the look-alike rule, with a power-play bar)
 * and as a Pill with its power-play tab, tint and halo, drawn by the real [StackedPainter] and [ScoreBugPainter] /
 * [PowerPlayPainter] with the bundled fonts. One PNG per league, written to the directory in the `chl.sheets.dir` system
 * property, or `build/chl-colour-sheets/` (for review by eye; not goldens). The logos are flat stand-ins in the team's base.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChlColourSheetTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val res = context.resources
    private val semi = sansTypeface(context, R.font.instrument_sans_semibold)
    private val bold = sansTypeface(context, R.font.instrument_sans_bold)
    private val italic = StackedPainter.barTypeface(context)
    private val out = File(System.getProperty("chl.sheets.dir") ?: "build/chl-colour-sheets").apply { mkdirs() }

    private val stacked = StackedPainter(1f, false, bold, italic)
    private val shadow = stacked.bakeShadow()!!
    private val m = K.GOAL_LAYER_MARGIN
    private val pill = ScoreBugPainter(1f, semi, bold)
    private val glass = pill.bakeGlass(withShadow = true)
    private val pp = PowerPlayPainter(1f, 1f, K.SCORE_W, K.SCORE_H, bold).apply { bakeSize() }
    private val restOpen: PpFrame = PpFrame().also {
        val from = PpFrame()
        PowerPlayTimeline.sample(PpTrack.TAB, PpTransition.STILL, 0f, PpFrame(), false, from)
        PowerPlayTimeline.sample(PpTrack.PILL, PpTransition.STILL, 0f, PpFrame(), false, from)
        PowerPlayTimeline.sample(PpTrack.TAB, PpTransition.STILL, 0f, from, true, it)
        PowerPlayTimeline.sample(PpTrack.PILL, PpTransition.STILL, 0f, from, true, it)
    }

    private fun logoBitmap(colour: Int, w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        val c = Canvas(this)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val b = w * 0.06f
        p.color = 0xFFF2F2F2.toInt()
        c.drawOval(w * 0.14f, 1f, w * 0.86f, h - 1f, p)
        p.color = colour
        c.drawOval(w * 0.14f + b, 1f + b, w * 0.86f - b, h - 1f - b, p)
    }

    private fun background(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(20, 22, 28), Color.rgb(70, 40, 38), Shader.TileMode.CLAMP)
        })
    }

    /** The Stacked rows for [l]'s [away] at [home], with the away team's power-play bar open. */
    private fun stackedCell(l: League, away: String, home: String): Bitmap {
        val pair = StackedColourTable.pair(l, away, home)
        val logoPx = Math.round(K.STACKED_GOAL_LOGO)
        val logos = FollowLogos({ url, w, h -> logoBitmap(ChlTeamColours.of(l, url).base, w, h) }, CoroutineScope(Dispatchers.Unconfined))
            .apply { start(away, home, logoPx, logoPx) }
        val w = Math.round(K.STACKED_W_MAX + 2 * m)
        val h = Math.round(K.STACKED_H + K.STACKED_BAR_H + 2 * m)
        val bmp = background(w, h)
        val a = StackedDrawArgs()
        a.body = StackedFixtures.body(stacked, awayAbbrev = away, homeAbbrev = home)
        a.colours = pair
        a.f = StackedFixtures.frame(1f, 1f, 1f, stripeAway = 1f)
        a.bar = BarContent().also {
            it.kind = BarKind.POWER_PLAY; it.side = Side.AWAY; it.secs = 97; it.period = null; it.dim = false
            stacked.fillBar(it, pair, away, home, res)
        }
        a.logos = logos
        a.logoWaited = true
        a.shadow = shadow
        val c = Canvas(bmp)
        c.translate(m, m)
        stacked.drawFrame(c, a)
        return Bitmap.createBitmap(bmp, (m - 16f).toInt(), (m - 16f).toInt(), Math.round(K.STACKED_W_MAX) + 32, Math.round(K.STACKED_H + K.STACKED_BAR_H) + 32)
    }

    /** The Pill with [team] (away) on a one-man power play: the tab, the tint and the halo in its colours. */
    private fun pillCell(l: League, team: String, opp: String): Bitmap {
        val colours: TeamColours = ChlTeamColours.of(l, team)
        val view = ScoreBugFixtures.ppView(team, Side.AWAY, PowerPlayKind.ONE_MAN, 79, opponent = opp)
        val frame = pill.frame(view, res)
        val logos = FollowLogos({ url, w, h -> logoBitmap(ChlTeamColours.of(l, url).base, w, h) }, CoroutineScope(Dispatchers.Unconfined))
            .apply { start(team, opp, Math.round(K.SCORE_LOGO_W), Math.round(K.SCORE_LOGO_H)) }
        pp.bakeLabels(team, opp, res)
        pp.begin(Side.AWAY, colours)
        logos.away.image?.let { pp.bakeHalo(Side.AWAY, it.asAndroidBitmap()) }
        logos.home.image?.let { pp.bakeHalo(Side.HOME, it.asAndroidBitmap()) }
        val w = Math.round(K.SCORE_W + 2 * m)
        val h = Math.round(K.SCORE_H + 2 * m)
        val bmp = background(w, h)
        val c = Canvas(bmp)
        c.translate(m, m)
        pill.drawFrame(
            c, glass, frame, logos, logoWaited = true, placing = false, goal = null, g = null, glow = null, glowOn = false,
            pp = pp, p = restOpen, content = PpContent(Side.AWAY, PpLabel.ONE_MAN, 79), pillSide = Side.AWAY, up = false,
        )
        return Bitmap.createBitmap(bmp, (m - 16f).toInt(), (m - 16f).toInt(), Math.round(K.SCORE_W) + 32, Math.round(K.SCORE_H) + 32 + 50)
    }

    @Test
    fun theColourSheetsForTheThreeLeagues() {
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26f; typeface = bold }
        val note = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 175, 185); textSize = 20f; typeface = semi }
        for (l in CHL_LEAGUES) {
            val teams = ChlTeamColours.codes(l).sorted()
            val rows = teams.mapIndexed { i, t ->
                val opp = teams[(i + 1) % teams.size]
                Triple(t, stackedCell(l, t, opp), pillCell(l, t, opp))
            }
            val cw1 = rows[0].second.width
            val cw2 = rows[0].third.width
            val ch = maxOf(rows[0].second.height, rows[0].third.height)
            val labelW = 160
            val cols = 2 // two teams per sheet row
            val cellW = labelW + cw1 + cw2
            val perCol = (rows.size + cols - 1) / cols
            val sheet = Bitmap.createBitmap(cols * cellW, 60 + perCol * ch, Bitmap.Config.ARGB_8888)
            val c = Canvas(sheet)
            c.drawColor(Color.rgb(10, 11, 14))
            c.drawText("$l: ${teams.size} teams; Stacked pair with power-play bar (away = the team, home = the next team), and Pill with power-play tab", 16f, 38f, note)
            rows.forEachIndexed { i, (t, s, p) ->
                val x = (i / perCol) * cellW
                val y = 60 + (i % perCol) * ch
                c.drawText(t, x + 12f, y + ch / 2f, label)
                c.drawText(ChlTeamColours.of(l, t).base.let { "#%06X".format(it and 0xFFFFFF) }, x + 12f, y + ch / 2f + 26f, note)
                c.drawBitmap(s, (x + labelW).toFloat(), y.toFloat(), null)
                c.drawBitmap(p, (x + labelW + cw1).toFloat(), y.toFloat(), null)
            }
            File(out, "chl-colours-${l.name.lowercase()}.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(File(out, "chl-colours-${l.name.lowercase()}.png").length() > 10_000)
        }
    }
}
