package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.ui.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.pow

/**
 * docs/design/m6-score-bug.md §4.2, §8 (U-B3): the bug's pixels, drawn by the real [ScoreBugPainter] with the bundled
 * fonts into bitmaps, over a dark frame, a white-ice frame and a 50 % grey frame. The period line must keep 4.5 : 1 and
 * the digits 7 : 1 over pure white. Every case writes a PNG to `ui/build/score-bug/` (review them by eye; they are not
 * goldens) and `contact-sheet.png` puts every state side by side.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScoreBugScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val semi = sansTypeface(context, R.font.instrument_sans_semibold)
    private val bold = sansTypeface(context, R.font.instrument_sans_bold)
    private val out = File("build/score-bug").apply { mkdirs() }

    private val margin = 60
    private val whiteIce = Color.WHITE
    private val grey50 = Color.rgb(128, 128, 128)

    private fun painter(large: Boolean = false) =
        if (large) ScoreBugPainter(1.25f, semi, bold, K.SCORE_LARGE_W, K.SCORE_LARGE_H) else ScoreBugPainter(1f, semi, bold)

    private fun logoBitmap(color: Int, w: Int = 64, h: Int = 46): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).drawOval(2f, 2f, w - 2f, h - 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    private fun logos(): FollowLogos {
        val l = FollowLogos({ url, w, h -> logoBitmap(if (url == "away") Color.rgb(200, 30, 40) else Color.rgb(20, 50, 110), w, h) },
            CoroutineScope(Dispatchers.Unconfined))
        l.start("away", "home", 64, 46)
        return l
    }

    /** The bug on [background] (a colour, or [DARK] for a murky gradient), with the wanted extras. */
    private fun render(
        view: ScoreBugView,
        background: Int = DARK,
        large: Boolean = false,
        secs: Int = 622,
        goalAlpha: Float = 0f,
        placing: Boolean = false,
        lowEnd: Boolean = false,
        withLogos: Boolean = false,
    ): Bitmap {
        val p = painter(large)
        val w = (p.pillW + 2 * margin).toInt()
        val h = (p.pillH + 2 * margin).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (background == DARK) {
            val paint = Paint().apply {
                shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(20, 22, 28), Color.rgb(70, 40, 38), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        } else {
            c.drawColor(background)
        }
        c.translate(margin.toFloat(), margin.toFloat())
        p.drawGlass(c, p.bakeGlass(withShadow = !lowEnd))
        // Owner ruling 6: the clock is the view's own value, drawn by drawBody.
        val frame = p.frame(if (view.top == app.thebox.core.sports.CentreText.Clock) view.copy(clockSecs = secs) else view, context.resources)
        p.drawBody(c, frame, if (withLogos) logos() else null, logoWaited = true, goalAlpha, view.goalSide, placing)
        return bmp
    }

    private fun save(name: String, bmp: Bitmap) {
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun lum(c: Int): Double {
        fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }

    private fun contrast(a: Int, b: Int): Double {
        val l1 = lum(a); val l2 = lum(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    /** The brightest pixel inside a pill-coordinate region (the glyph core), and the pill colour beside it. */
    private fun brightest(b: Bitmap, l: Float, t: Float, r: Float, bt: Float): Int {
        var best = 0; var bl = -1.0
        for (y in (t + margin).toInt() until (bt + margin).toInt()) for (x in (l + margin).toInt() until (r + margin).toInt()) {
            val px = b.getPixel(x, y)
            val v = lum(px)
            if (v > bl) { bl = v; best = px }
        }
        return best
    }

    private fun px(b: Bitmap, x: Float, y: Float) = b.getPixel((x + margin).toInt(), (y + margin).toInt())

    @Test
    fun everyStateOverDarkAndWriteTheContactSheet() {
        val shots = ScoreBugFixtures.all.map { (name, v) ->
            val b = render(v, goalAlpha = if (v.goalSide != null) 1f else 0f, withLogos = name != "ot")
            save("state-$name", b)
            name to b
        }
        val cols = 2
        val cw = shots[0].second.width
        val ch = shots[0].second.height
        val rows = (shots.size + cols - 1) / cols
        val sheet = Bitmap.createBitmap(cols * cw, rows * ch, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        shots.forEachIndexed { i, (_, b) -> c.drawBitmap(b, (i % cols) * cw.toFloat(), (i / cols) * ch.toFloat(), null) }
        save("contact-sheet", sheet)
        assertTrue(File(out, "contact-sheet.png").length() > 1000)
    }

    @Test
    fun sizesAndExtras() {
        save("large-live", render(ScoreBugFixtures.live, large = true, withLogos = true))
        save("place-mode-sample", render(ScoreBugFixtures.view(away = 0, home = 0, awayAbbrev = "AWY", homeAbbrev = "HME", bottom = null), placing = true, secs = 0))
        save("low-end-live", render(ScoreBugFixtures.live, lowEnd = true, withLogos = true))
        val big = render(ScoreBugFixtures.live, large = true)
        assertEquals((506 + 2 * margin), big.width)
        assertEquals((88 + 2 * margin), big.height)
    }

    @Test
    fun pillFillOverPureWhiteIsTheDocumentedComposite() {
        val b = render(ScoreBugFixtures.live, whiteIce)
        // A spot in the centre column away from the text: #0A0D13 at .86 over white is about #2C2F34 (§4.2).
        val c = px(b, 154f, 35f)
        assertTrue("R ${Color.red(c)}", kotlin.math.abs(Color.red(c) - 0x2C) <= 8)
        assertTrue("G ${Color.green(c)}", kotlin.math.abs(Color.green(c) - 0x2F) <= 8)
        assertTrue("B ${Color.blue(c)}", kotlin.math.abs(Color.blue(c) - 0x34) <= 8)
        save("white-ice-live", render(ScoreBugFixtures.live, whiteIce, withLogos = true))
    }

    @Test
    fun brightFramesKeepTheContrastFloors() {
        for ((name, bg) in listOf("white" to whiteIce, "grey50" to grey50)) {
            val b = render(ScoreBugFixtures.live, bg)
            save("bright-$name-live", b)
            val pill = px(b, 154f, 35f)
            // Period "1st" (20/600 at .70): at least 4.5 : 1 against the pill, highlight included (the top half is lighter).
            val periodText = brightest(b, 170f, 42f, 235f, 60f)
            val periodC = contrast(periodText, pill)
            assertTrue("period contrast over $name: $periodC", periodC >= 4.5)
            // The highlight lifts the top of the pill: measure against the lightest pill pixel in the clock's rows too.
            val clockText = brightest(b, 160f, 10f, 245f, 38f)
            val clockC = contrast(clockText, px(b, 154f, 14f))
            assertTrue("clock contrast over $name: $clockC", clockC >= 7.0)
            val scoreText = brightest(b, 98f, 14f, 126f, 56f)
            assertTrue("score contrast over $name: ${contrast(scoreText, pill)}", contrast(scoreText, pill) >= 7.0)
        }
    }

    @Test
    fun theOuterRingHoldsTheEdgeOverWhiteIce() {
        val b = render(ScoreBugFixtures.live, whiteIce, lowEnd = true)
        // Just outside the pill's left end: a 1 px black .30 ring, so darker than the white frame.
        val ring = px(b, -0.5f, 35f)
        assertTrue("ring pixel ${Integer.toHexString(ring)}", Color.red(ring) < 230)
        val away = px(b, -12f, 35f)
        assertEquals("no shadow in low-end mode", 255, Color.red(away))
    }

    @Test
    fun theShadowIsBakedOnlyOutsideThePillAndSkippedInLowEnd() {
        val p = painter()
        val with = p.bakeGlass(withShadow = true)
        val without = p.bakeGlass(withShadow = false)
        val belowY = (24 + 70 + 8).toInt() // 8 px under the pill, in the bitmap
        assertTrue("shadow alpha under the pill", (with.getPixel(with.width / 2, belowY) ushr 24) > 20)
        assertEquals(0, without.getPixel(without.width / 2, belowY) ushr 24)
        // Inside, the fill is the same either way: the shadow is cut out of the pill.
        val mid = with.getPixel(with.width / 2, 24 + 35)
        assertEquals(without.getPixel(without.width / 2, 24 + 35), mid)
    }

    @Test
    fun theGoalBoxTintsTheScoringSidesScoreAndOnlyThatSide() {
        val v = ScoreBugFixtures.goalHome
        val off = render(v, goalAlpha = 0f)
        val on = render(v, goalAlpha = 1f)
        save("goal-box-on", on)
        val homeSpot = 268f + 4f // inside the home score slot, left of the digit
        val awaySpot = 88f + 4f
        assertTrue("accent blue rose at the home slot", Color.blue(px(on, homeSpot, 14f)) > Color.blue(px(off, homeSpot, 14f)) + 15)
        assertEquals("away slot unchanged", px(off, awaySpot, 14f), px(on, awaySpot, 14f))
        // The tag reads GOAL EDM in the accent text colour (bottom line), not the period grey.
        val tag = brightest(on, 160f, 42f, 245f, 60f)
        assertTrue("goal tag ${Integer.toHexString(tag)} is blue-tinted", Color.blue(tag) > Color.red(tag) + 20)
    }

    @Test
    fun theStaleColumnIsDimmedButTheScoresAreNot() {
        val live = render(ScoreBugFixtures.live)
        val stale = render(ScoreBugFixtures.stale)
        val liveClock = lum(brightest(live, 160f, 10f, 245f, 38f))
        val staleClock = lum(brightest(stale, 160f, 10f, 245f, 38f))
        assertTrue("clock dimmed: $staleClock < $liveClock", staleClock < liveClock * 0.6)
        assertEquals(lum(brightest(live, 98f, 14f, 126f, 56f)), lum(brightest(stale, 98f, 14f, 126f, 56f)), 0.02)
    }

    @Test
    fun placeModeDrawsTheAccentRingOutsideThePill() {
        val on = render(ScoreBugFixtures.live, placing = true)
        val ring = px(on, 404f / 2f, -1.5f)
        assertTrue("accent ring ${Integer.toHexString(ring)}", Color.blue(ring) > 150 && Color.red(ring) < 140)
        save("place-mode-live", on)
        val off = render(ScoreBugFixtures.live)
        assertNotEquals(ring, px(off, 404f / 2f, -1.5f))
    }

    @Test
    fun theClockIsTabularADigitChangeNeverMovesTheOthers() {
        val a = render(ScoreBugFixtures.live, secs = 10 * 60 + 22)
        val c = render(ScoreBugFixtures.live, secs = 12 * 60 + 22)
        // The last three characters ":22" are identical pixels in both frames.
        for (x in margin + 202 until margin + 236) for (y in margin + 10 until margin + 38) {
            assertEquals("pixel ($x,$y)", a.getPixel(x, y), c.getPixel(x, y))
        }
    }

    @Test
    fun everyCentreStringFitsBothSizesWithoutAnEllipsis() {
        val strings = listOf(
            "Final", "Final/OT", "Final/SO", "SO", "No update", "Delayed", "GOAL CGY", "GOAL MTL", "GOAL WPG", "GOAL NYR",
            "1st", "2nd", "3rd", "OT", "2OT", "3OT", "1st INT", "2nd INT", "3rd INT", "End 1st", "End 3rd", "7:00 PM", "10:22", "19:59",
            "WWWWWWW", // beyond any real label: must step down to the floor, not overflow
        )
        for (large in listOf(false, true)) {
            val p = painter(large)
            val paint = Paint().apply { typeface = semi }
            for (text in strings) for (start in listOf(K.SCORE_ONE_LINE_PX, K.SCORE_CLOCK_PX, K.SCORE_PERIOD_PX)) {
                val px = p.fit(text, start.toFloat())
                assertTrue("$text at $start -> $px", px >= K.SCORE_TEXT_FLOOR_PX && px <= start)
                assertEquals("steps of 2: $text", 0, (start - px).toInt() % K.SCORE_FIT_STEP_PX)
                paint.textSize = px * p.s
                val w = paint.measureText(text)
                if (px > K.SCORE_TEXT_FLOOR_PX || text != "WWWWWWW") {
                    assertTrue("$text at $px: width $w > ${K.SCORE_CENTRE_W * p.s} (large=$large)", w <= K.SCORE_CENTRE_W * p.s + 0.5f)
                }
                if (px > K.SCORE_TEXT_FLOOR_PX) {
                    // Integration pass 2026-11-05: 8 design px clear of each divider ("Final/OT" read as touching them).
                    val room = (K.SCORE_CENTRE_W - 16f) * p.s
                    assertTrue("$text at $px: width $w > $room, closer than 8 px to a divider (large=$large)", w <= room + 0.5f)
                }
            }
        }
        assertEquals("the clock itself keeps 26", 26f, painter().clockPx, 0f)
        assertEquals("long text stops at the floor", 18f, painter().fit("WWWWWWWWWW", 24f), 0f)
    }

    private companion object {
        const val DARK = 0x01010101
    }
}
