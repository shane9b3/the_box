package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.BarKind
import app.thebox.core.sports.CentreText
import app.thebox.core.sports.GameId
import app.thebox.core.sports.League
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.TeamLine
import app.thebox.ui.R
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * (CHL WP-5, docs/design/m6-chl-leagues.md §6.4, §7.2) The owner's screenshot sheets for the bugs: a Pill and a Stacked bug
 * for a CHL game with BRAM (a 4-letter code whose logo falls back to the capsule), a 2-letter code (ER, OS) and a logo-less
 * 3-letter code (the disc), at Standard and Large, and the Stacked rows at both the left- and the right-hand corners (the
 * mirrored rows, the exact reflection `[score][code][logo][stripe]`; the Pill is not mirrored). Drawn by the real painters
 * with the bundled fonts over a dark frame. Written to `ui/build/chl-ui/` and the review scratchpad; not goldens.
 *
 * The Stacked colours are the real OHL table's (`StackedColourTable.pair(OHL, …)`); the logos are synthetic stand-ins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChlBugScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val res = context.resources
    private val semi = sansTypeface(context, R.font.instrument_sans_semibold)
    private val bold = sansTypeface(context, R.font.instrument_sans_bold)
    private val italic = StackedPainter.barTypeface(context)

    private val outs = listOf(
        File("build/chl-ui"),
        File("/private/tmp/claude-501/-Users-shawnjohnson-Projects-The-Box/dedf5c50-b6f4-4a8c-a23c-fadfe67ec05f/scratchpad/chl-ui"),
    ).onEach { runCatching { it.mkdirs() } }

    private fun save(name: String, bmp: Bitmap) {
        for (o in outs) runCatching { File(o, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun background(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(20, 22, 28), Color.rgb(70, 40, 38), Shader.TileMode.CLAMP)
        })
    }

    private fun logoBitmap(colour: Int, w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        val c = Canvas(this)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFFF2F2F2.toInt(); c.drawOval(w * 0.1f, 1f, w * 0.9f, h - 1f, p)
        p.color = colour; c.drawOval(w * 0.1f + 3, 4f, w * 0.9f - 3, h - 4f, p)
    }

    private fun contactSheet(name: String, cells: List<Bitmap>, cols: Int) {
        val cw = cells.maxOf { it.width }
        val ch = cells.maxOf { it.height }
        val rows = (cells.size + cols - 1) / cols
        val b = Bitmap.createBitmap(cols * cw, rows * ch, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.BLACK)
        cells.forEachIndexed { i, bmp -> c.drawBitmap(bmp, (i % cols) * cw.toFloat(), (i / cols) * ch.toFloat(), null) }
        save(name, b)
    }

    // ------------------------------------------------------------------ Pill

    private fun chlView(away: String, home: String, a: Int, h: Int, league: League = League.OHL): ScoreBugView {
        val g = GameId(league, "29191")
        return ScoreBugView(
            away = TeamLine(away, away, a, null), home = TeamLine(home, home, h, null),
            top = CentreText.Clock, bottom = CentreText.Period(PeriodLabel(PeriodKind.REG, 2)), dim = false, goalSeq = 0, goalSide = null,
            clockSecs = 622, gameId = g, league = league, period = PeriodLabel(PeriodKind.REG, 2),
        )
    }

    private fun pill(view: ScoreBugView, large: Boolean, logos: Boolean): Bitmap {
        val margin = 40
        val p = if (large) ScoreBugPainter(1.25f, semi, bold, K.SCORE_LARGE_W, K.SCORE_LARGE_H) else ScoreBugPainter(1f, semi, bold)
        val w = (p.pillW + 2 * margin).toInt()
        val h = (p.pillH + 2 * margin).toInt()
        val bmp = background(w, h)
        val c = Canvas(bmp)
        c.translate(margin.toFloat(), margin.toFloat())
        p.drawGlass(c, p.bakeGlass(withShadow = true))
        val l = if (logos) {
            FollowLogos({ url, lw, lh -> logoBitmap(if (url == "away") 0xFF1B3A6B.toInt() else 0xFFB5121B.toInt(), lw, lh) }, CoroutineScope(Dispatchers.Unconfined))
                .also { it.start("away", "home", 64, 46) }
        } else null
        p.drawBody(c, p.frame(view, res), l, logoWaited = true, placing = false)
        return bmp
    }

    @Test
    fun pillBugsForAChlGame() {
        val cells = ArrayList<Bitmap>()
        // BRAM (4 letters, no logo: the capsule), a 2-letter code (ER, OS), a 3-letter fallback (the disc), and logos.
        for ((away, home) in listOf("BRAM" to "FLNT", "BRAM" to "KIT", "ER" to "OS", "KIT" to "LDN")) {
            for (large in listOf(false, true)) {
                val logos = away == "KIT"
                val b = pill(chlView(away, home, 1, 10), large, logos)
                save("pill-$away-$home-${if (large) "large" else "std"}", b)
                cells += b
            }
        }
        // a QMJHL game with the Newfoundland Regiment, "NFL": a team code, a disc
        val nfl = pill(chlView("NFL", "CHA", 2, 3, League.QMJHL), false, false)
        save("pill-NFL-CHA-std", nfl)
        cells += nfl
        contactSheet("pill-contact-sheet", cells, 2)
        assertTrue(cells.all { it.width > 100 })
    }

    // ------------------------------------------------------------------ Stacked

    private class Rig(val large: Boolean, val bold: android.graphics.Typeface?, val italic: android.graphics.Typeface?) {
        val s = if (large) K.SCORE_LARGE_SCALE else 1f
        val m = 60f
        val painter = StackedPainter(s, large, bold, italic)
        val layerW = Math.round(K.STACKED_W_MAX * s + 2 * m)
        val layerH = Math.round((K.STACKED_H + K.STACKED_BAR_H) * s + 2 * m)
    }

    private fun stacked(away: String, home: String, large: Boolean, mirrored: Boolean, pp: Boolean, logos: Boolean): Bitmap {
        val r = Rig(large, bold, italic)
        val bmp = background(r.layerW, r.layerH)
        val c = Canvas(bmp)
        val a = StackedDrawArgs()
        a.body = StackedFixtures.body(r.painter, awayAbbrev = away, homeAbbrev = home, away = 1, home = 10)
        a.colours = app.thebox.core.sports.StackedColourTable.pair(League.OHL, away, home)
        a.f = if (pp) StackedFixtures.frame(1f, 1f, 1f, stripeAway = 1f) else StackedFixtures.frame(1f, 1f, 0f)
        if (pp) {
            a.bar = BarContent().also {
                it.kind = BarKind.POWER_PLAY; it.side = Side.AWAY; it.secs = 92; it.period = null
                r.painter.fillBar(it, a.colours, away, home, res)
            }
        }
        if (logos) {
            val px = Math.round(K.STACKED_GOAL_LOGO * r.s)
            a.logos = FollowLogos({ url, w, h -> logoBitmap(if (url == "away") 0xFF1B3A6B.toInt() else 0xFFB5121B.toInt(), w, h) }, CoroutineScope(Dispatchers.Unconfined))
                .also { it.start("away", "home", px, px) }
        }
        a.logoWaited = true
        a.mirrored = mirrored
        // Mirrored (a right-hand corner): the rows hug the footprint's right edge, as the runner places them.
        c.translate(r.m + if (mirrored) (K.STACKED_W_MAX - K.STACKED_ROWS_W) * r.s else 0f, r.m)
        r.painter.drawFrame(c, a)
        return bmp
    }

    @Test
    fun stackedBugsForAChlGameBothCorners() {
        val cells = ArrayList<Bitmap>()
        for ((away, home) in listOf("BRAM" to "FLNT", "BRAM" to "KIT", "ER" to "OS", "KIT" to "LDN")) {
            for (large in listOf(false, true)) for (mirrored in listOf(false, true)) {
                val b = stacked(away, home, large, mirrored, pp = away == "BRAM" && home == "KIT", logos = away == "KIT")
                save("stacked-$away-$home-${if (large) "large" else "std"}-${if (mirrored) "right" else "left"}", b)
                if (!large) cells += b
            }
        }
        contactSheet("stacked-contact-sheet", cells, 2)
        assertTrue(cells.all { it.width > 100 })
    }
}
