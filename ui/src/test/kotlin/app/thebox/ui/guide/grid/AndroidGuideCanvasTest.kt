package app.thebox.ui.guide.grid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.guide.GuideAsset
import app.thebox.core.guide.GuideTextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** docs/design/m3-guide.md §5.1 "AndroidGuideCanvasTest": draws into a Bitmap with real (native) graphics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidGuideCanvasTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun surface(w: Int = 400, h: Int = 200): Pair<Bitmap, AndroidGuideCanvas> {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.BLACK)
        val gc = AndroidGuideCanvas(context)
        gc.bind(Canvas(bmp), 1f)
        return bmp to gc
    }

    private fun close(expected: Int, actual: Int, tol: Int = 3): Boolean =
        abs(Color.red(expected) - Color.red(actual)) <= tol && abs(Color.green(expected) - Color.green(actual)) <= tol &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tol

    @Test
    fun fillsRectsRoundRectsAndCircles() {
        val (bmp, gc) = surface()
        gc.fillRect(10f, 10f, 50f, 50f, 0xFFFF0000.toInt())
        gc.fillRoundRect(100f, 10f, 200f, 70f, 12f, 0xFF00FF00.toInt())
        gc.fillCircle(300f, 40f, 10f, 0xFF0000FF.toInt())
        assertTrue(close(Color.RED, bmp.getPixel(30, 30)))
        assertTrue(close(Color.GREEN, bmp.getPixel(150, 40)))
        assertTrue("corner is rounded", close(Color.BLACK, bmp.getPixel(100, 10)))
        assertTrue(close(Color.BLUE, bmp.getPixel(300, 40)))
        assertTrue(close(Color.BLACK, bmp.getPixel(5, 5)))
    }

    @Test
    fun strokesARingOnly() {
        val (bmp, gc) = surface()
        gc.strokeRoundRect(20f, 20f, 180f, 120f, 12f, 3f, 0xFF5CA8F2.toInt())
        assertTrue("ring on the edge", close(0xFF5CA8F2.toInt(), bmp.getPixel(100, 20), 12))
        assertTrue("hollow inside", close(Color.BLACK, bmp.getPixel(100, 70)))
    }

    @Test
    fun clipsAndRestores() {
        val (bmp, gc) = surface()
        val sv = gc.save()
        gc.clipRect(0f, 0f, 50f, 50f)
        gc.fillRect(0f, 0f, 400f, 200f, Color.WHITE)
        gc.restoreToCount(sv)
        gc.fillRect(300f, 150f, 310f, 160f, Color.WHITE)
        assertTrue(close(Color.WHITE, bmp.getPixel(25, 25)))
        assertTrue(close(Color.BLACK, bmp.getPixel(100, 100)))
        assertTrue(close(Color.WHITE, bmp.getPixel(305, 155)))
    }

    @Test
    fun drawsTextWithTheBundledFont() {
        val (bmp, gc) = surface()
        gc.drawText("Six O'Clock News", 0, 16, 10f, 50f, GuideTextStyle.CELL_TITLE, Color.WHITE)
        var lit = 0
        for (y in 30 until 55) for (x in 10 until 200) if (Color.red(bmp.getPixel(x, y)) > 128) lit++
        assertTrue("text pixels present ($lit)", lit > 100)
        // Ranged draw: only the first 3 chars.
        val (bmp2, gc2) = surface()
        gc2.drawText("Six O'Clock News", 0, 3, 10f, 50f, GuideTextStyle.CELL_TITLE, Color.WHITE)
        var right = 0
        for (y in 30 until 55) for (x in 60 until 200) if (Color.red(bmp2.getPixel(x, y)) > 128) right++
        assertEquals(0, right)
    }

    @Test
    fun assetsAreTintedMasks() {
        val (bmp, gc) = surface()
        gc.drawAsset(GuideAsset.ARCHIVE_GLYPH, 10f, 10f, 58f, 58f, 0xFFFF0000.toInt())
        var red = 0
        for (y in 10 until 58) for (x in 10 until 58) {
            val p = bmp.getPixel(x, y)
            if (Color.red(p) > 200 && Color.green(p) < 40) red++
        }
        assertTrue("glyph tinted red ($red px)", red > 50)
        // The focus shadow is a nine-slice: the middle of a long target is as dark as near its ends.
        val (bmp2, gc2) = surface(800, 200)
        bmp2.eraseColor(Color.WHITE)
        gc2.drawAsset(GuideAsset.FOCUS_SHADOW, 10f, 10f, 790f, 190f, 0xFF000000.toInt())
        val mid = Color.red(bmp2.getPixel(400, 100))
        val nearEnd = Color.red(bmp2.getPixel(120, 100))
        assertTrue("shadow covers the middle ($mid vs $nearEnd)", mid < 80 && abs(mid - nearEnd) < 30)
        assertTrue("soft edge", Color.red(bmp2.getPixel(12, 100)) > mid)
        // The now glow fades out sideways.
        val (bmp3, gc3) = surface()
        gc3.drawAsset(GuideAsset.NOW_GLOW, 100f, 0f, 124f, 200f, 0xFF5CA8F2.toInt())
        assertTrue(Color.blue(bmp3.getPixel(112, 100)) > Color.blue(bmp3.getPixel(101, 100)))
        assertTrue(gc3.assetBytes() in 1..50_000)
    }

    @Test
    fun chevronsAreLeftPointingTintedMasksAtTheirSize() {
        // Decision 2026-10-19: 12 × 24 design px, polyline (9.5, 2.5) → (2.5, 12) → (9.5, 21.5), stroke 3.
        for ((asset, argb) in listOf(GuideAsset.EDGE_CHEVRON to 0xFF5CA8F2.toInt(), GuideAsset.CLIP_CHEVRON to 0xFFFF0000.toInt())) {
            val (bmp, gc) = surface()
            gc.drawAsset(asset, 20f, 20f, 32f, 44f, argb)
            assertTrue("$asset tip of the point is inked", close(argb, bmp.getPixel(20 + 3, 20 + 12), 40))
            assertTrue("$asset top arm end is inked", close(argb, bmp.getPixel(20 + 9, 20 + 3), 60))
            assertTrue("$asset bottom arm end is inked", close(argb, bmp.getPixel(20 + 9, 20 + 21), 60))
            assertTrue("$asset open on the right at mid height", close(Color.BLACK, bmp.getPixel(20 + 10, 20 + 12)))
            assertTrue("$asset nothing outside the box", close(Color.BLACK, bmp.getPixel(35, 32)) && close(Color.BLACK, bmp.getPixel(17, 32)))
            var lit = 0
            for (y in 20 until 44) for (x in 20 until 32) if (!close(Color.BLACK, bmp.getPixel(x, y), 40)) lit++
            assertTrue("$asset stroke ink ($lit px)", lit in 40..160)
        }
        // At 2× the mask is rasterised at 24 × 48, not stretched from 12 × 24.
        val big = GuideAssets(2f)
        val m = big.bitmap(GuideAsset.EDGE_CHEVRON)
        assertEquals(24, m.width); assertEquals(48, m.height)
        assertTrue("both chevrons share one mask", big.bitmap(GuideAsset.CLIP_CHEVRON) === m)
        assertEquals("the ↺ mask is 2× the 20 px label glyph", 80, big.bitmap(GuideAsset.ARCHIVE_GLYPH).width)
    }

    @Test
    fun drawsImagesWithAlpha() {
        val (bmp, gc) = surface()
        val logo = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        gc.drawImage(BitmapGuideImage(logo), 10f, 10f, 110f, 60f, 1f)
        gc.drawImage(BitmapGuideImage(logo), 200f, 10f, 300f, 60f, 0.5f)
        assertTrue(close(Color.WHITE, bmp.getPixel(60, 35)))
        assertTrue(close(Color.rgb(128, 128, 128), bmp.getPixel(250, 35), 4))
    }

    @Test
    fun measurerAgreesWithPaintBreakTextAndScales() {
        val m = AndroidTextMeasurer(context, 1f)
        val p = GuidePaints.textPaints(context, 1f)[GuideTextStyle.CELL_TITLE.ordinal]
        val text = "Match of the Day: highlights from every ground"
        for (w in listOf(10f, 55.5f, 100f, 240f, 1000f)) {
            assertEquals("fit at $w", p.breakText(text, 0, text.length, true, w, null), m.fit(text, 0, text.length, GuideTextStyle.CELL_TITLE, w))
        }
        assertEquals(p.measureText(text), m.width(text, 0, text.length, GuideTextStyle.CELL_TITLE), 0.01f)
        val w1 = m.width(text, 0, text.length, GuideTextStyle.CELL_TITLE)
        val a1 = m.ascent(GuideTextStyle.CELL_TITLE)
        m.rescale(2f)
        assertEquals(2f, m.scale)
        assertEquals(2 * w1, m.width(text, 0, text.length, GuideTextStyle.CELL_TITLE), w1 * 0.03f)
        assertEquals(2 * a1, m.ascent(GuideTextStyle.CELL_TITLE), a1 * 0.1f)
        // Mono styles use tabular digits: every digit has the same advance.
        val mono = GuideTextStyle.CELL_TIME
        val d1 = m.width("1111", 0, 4, mono)
        val d8 = m.width("8888", 0, 4, mono)
        assertEquals(d1, d8, 0.01f)
        assertEquals(0, m.fit(text, 0, text.length, mono, 0f))
        assertTrue(Paint().apply { textSize = 10f }.measureText("x") > 0f)
    }
}
