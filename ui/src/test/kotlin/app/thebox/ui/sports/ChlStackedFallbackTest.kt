package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.StackedMock as M
import app.thebox.core.sports.StackedPair
import app.thebox.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * (CHL WP-5 fix pass, review S1/R1, lead ruling) In the Stacked bug the logo fallback of a code longer than 3 characters
 * stays inside the 50.4 px logo slot, with at least 6 design px of air to the stripe and to the code's ink: a plain 44 px
 * disc without text, filled with the team's stripe colour. Measured on the pixels the real [StackedPainter] draws, for
 * BRAM, FLNT and NIAG in all four geometries (unmirrored / mirrored, with and without the power-play stripe), at Standard and
 * Large, on both rows. A 3-letter fallback is untouched (the disc with its text).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChlStackedFallbackTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val bold = sansTypeface(context, R.font.instrument_sans_bold)
    private val italic = StackedPainter.barTypeface(context)

    private val awayStripe = 0xFF00E000.toInt()
    private val homeStripe = 0xFFE000E0.toInt()
    private val pair = StackedPair(
        StackedFixtures.EDM.copy(stripe = awayStripe), StackedFixtures.TOR.copy(stripe = homeStripe),
    )

    private class Gaps(var stripe: Float = Float.MAX_VALUE, var code: Float = Float.MAX_VALUE, var discW: Float = 0f, var slotOverhang: Float = -99f)

    private fun near(c: Int, k: Int, tol: Int = 40) =
        Math.abs(Color.red(c) - Color.red(k)) <= tol && Math.abs(Color.green(c) - Color.green(k)) <= tol && Math.abs(Color.blue(c) - Color.blue(k)) <= tol

    /** The painted gaps (design px) of one row's fallback; [row] 0 = away, 1 = home. */
    private fun measure(code: String, large: Boolean, mirrored: Boolean, pp: Boolean, row: Int): Gaps {
        val s = if (large) K.SCORE_LARGE_SCALE else 1f
        val p = StackedPainter(s, large, bold, italic)
        val margin = 20f
        val w = Math.round(K.STACKED_W_MAX * s + 2 * margin)
        val h = Math.round((K.STACKED_H + K.STACKED_BAR_H) * s + 2 * margin)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val a = StackedDrawArgs()
        a.body = StackedFixtures.body(p, awayAbbrev = if (row == 0) code else "KIT", homeAbbrev = if (row == 1) code else "LDN", away = 1, home = 2)
        a.colours = pair
        a.f = StackedFixtures.frame(1f, 1f, 0f, stripeAway = if (pp) 1f else 0f, stripeHome = if (pp) 1f else 0f)
        a.logoWaited = true
        a.mirrored = mirrored
        val ox = margin + if (mirrored) (K.STACKED_W_MAX - K.STACKED_ROWS_W) * s else 0f
        c.translate(ox, margin)
        p.drawFrame(c, a)
        val rowH = M.ROW_H * K.STACKED_SCALE * s
        val cy = Math.round(margin + rowH * (row + 0.5f))
        val colour = if (row == 0) awayStripe else homeStripe
        val x0 = Math.round(ox)
        val x1 = Math.round(ox + K.STACKED_ROWS_W * s)
        // Runs of the stripe colour along the row's centre line, from the screen edge inwards: the stripe, then the disc.
        val xs = if (mirrored) (x1 - 1 downTo x0) else (x0 until x1)
        val runs = ArrayList<IntRange>()
        var start = -1
        var prev = -1
        for (x in xs) {
            if (near(bmp.getPixel(x, cy), colour)) {
                if (start < 0) start = x
                prev = x
            } else if (start >= 0) { runs += minOf(start, prev)..maxOf(start, prev); start = -1 }
        }
        if (start >= 0) runs += minOf(start, prev)..maxOf(start, prev)
        assertTrue("$code row $row mirrored=$mirrored pp=$pp: stripe and disc runs ${runs}", runs.size >= 2)
        val stripe = runs[0]
        val disc = runs[1]
        val g = Gaps()
        g.stripe = (if (mirrored) stripe.first - disc.last - 1 else disc.first - stripe.last - 1) / s
        g.discW = (disc.last - disc.first + 1) / s
        // The code's ink: the nearest white pixel beyond the disc on the code's side, in a band around the centre line.
        var codeX = if (mirrored) -1 else Int.MAX_VALUE
        for (y in (cy - Math.round(10 * s))..(cy + Math.round(10 * s))) {
            if (mirrored) {
                for (x in (disc.first - 1) downTo x0) if (near(bmp.getPixel(x, y), Color.WHITE, 30)) { codeX = maxOf(codeX, x); break }
            } else {
                for (x in (disc.last + 1) until x1) if (near(bmp.getPixel(x, y), Color.WHITE, 30)) { codeX = minOf(codeX, x); break }
            }
        }
        assertTrue("$code row $row: the code is drawn beside the disc", if (mirrored) codeX >= 0 else codeX < Int.MAX_VALUE)
        g.code = (if (mirrored) disc.first - codeX - 1 else codeX - disc.last - 1) / s
        // Inside the slot: the slot is [stripe edge + LOGO_ML, + LOGO_SLOT_W] design px (x 0.45); overhang = disc beyond it.
        val slotL = (M.LOGO_ML * K.STACKED_SCALE)
        val edge = (if (mirrored) stripe.first - disc.last - 1 else disc.first - stripe.last - 1) / s
        g.slotOverhang = slotL - edge // > 0: the disc's inner edge is past the slot's start
        return g
    }

    @Test
    fun theStackedFallbackKeepsSixPxFromTheStripeAndTheCodeInAllGeometries() {
        var minStripe = Float.MAX_VALUE
        var minCode = Float.MAX_VALUE
        for (code in listOf("BRAM", "FLNT", "NIAG")) for (large in listOf(false, true)) for (mirrored in listOf(false, true)) for (pp in listOf(false, true)) for (row in 0..1) {
            val g = measure(code, large, mirrored, pp, row)
            val tag = "$code large=$large mirrored=$mirrored pp=$pp row=$row"
            println("stacked fallback $tag: disc ${"%.1f".format(g.discW)} wide, gap to the stripe ${"%.1f".format(g.stripe)}, to the code ink ${"%.1f".format(g.code)}")
            assertEquals("$tag: a plain 44 px disc", 44f, g.discW, 1.5f)
            assertTrue("$tag: disc inside the ${M.LOGO_SLOT_W * K.STACKED_SCALE} px slot", g.discW <= M.LOGO_SLOT_W * K.STACKED_SCALE)
            assertTrue("$tag: gap to the stripe ${g.stripe}", g.stripe >= K.LOGO_CAPSULE_SCORE_GAP_MIN)
            assertTrue("$tag: gap to the code ink ${g.code}", g.code >= K.LOGO_CAPSULE_SCORE_GAP_MIN)
            minStripe = minOf(minStripe, g.stripe)
            minCode = minOf(minCode, g.code)
        }
        println("stacked fallback least gaps: stripe %.1f, code %.1f design px".format(minStripe, minCode))
    }

    @Test
    fun aThreeLetterFallbackStillDrawsItsTextDisc() {
        val s = 1f
        val p = StackedPainter(s, false, bold, italic)
        val disc = LogoDisc(s, bold)
        assertTrue(!disc.isCapsule("KIT") && !disc.isCapsule("ER") && disc.isCapsule("BRAM"))
        val bmp = Bitmap.createBitmap(300, 120, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val a = StackedDrawArgs()
        a.body = StackedFixtures.body(p, awayAbbrev = "KIT", homeAbbrev = "LDN")
        a.colours = pair
        a.f = StackedFixtures.frame(1f, 1f, 0f)
        a.logoWaited = true
        c.translate(20f, 20f)
        p.drawFrame(c, a)
        // the KIT disc has its text: bright pixels inside the disc around the logo slot's centre
        val cx = 20 + Math.round((M.STRIPE_W + M.LOGO_ML + M.LOGO_SLOT_W / 2f) * K.STACKED_SCALE)
        val cy = 20 + Math.round(M.ROW_H * K.STACKED_SCALE / 2f)
        var white = 0
        for (y in cy - 8..cy + 8) for (x in cx - 18..cx + 18) if (near(bmp.getPixel(x, y), Color.WHITE, 60)) white++
        assertTrue("the 3-letter disc keeps its code text ($white white pixels)", white > 20)
    }
}
