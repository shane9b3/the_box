package app.thebox.ui.guide.grid

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.guide.GuideAsset
import app.thebox.core.guide.GuideCanvas
import app.thebox.core.guide.GuideColors
import app.thebox.core.guide.GuideTextStyle
import app.thebox.core.guide.GuideImage
import app.thebox.core.guide.GuideImages
import app.thebox.core.guide.render.GuideReferenceFrame
import app.thebox.core.guide.render.GuideRendererImpl
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * docs/design/m3-guide.md §5.1 "GoldenFrameTest" (N10): the §4.1 reference frame rendered by the real renderer
 * through [AndroidGuideCanvas] with the bundled fonts into a 1920×1080 `Bitmap`, compared with
 * `src/test/resources/golden/guide-reference-1080p.png`.
 *
 * Deterministic per Robolectric version and host OS font rasteriser, so the comparison tolerates small anti-aliasing
 * differences: a pixel differs when a channel (alpha included) is off by more than 24. (code review 2026-10-14, G-S5)
 * At most 0.05 % of pixels may differ (was 0.5 %, which let a lost focus ring, ruler or time lines pass), and the
 * mutation tests below prove a frame without the ring, the ruler labels, the cell time lines or the now line fails.
 * Re-record with `THEBOX_RECORD_GOLDEN=1 ./gradlew :ui:testDebugUnitTest`; a failing run writes the actual image to
 * `ui/build/golden/`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GoldenFrameTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class Logos(private val image: GuideImage) : GuideImages {
        override fun get(url: String): GuideImage? = if (url.removePrefix("logo://").toInt() % 3 == 0) image else null
        override fun failed(url: String): Boolean = false
        override var onLoaded: (() -> Unit)? = null
        override fun trim() {}
    }

    private var focusX = 0
    private var focusY = 0

    private fun render(wrap: (GuideCanvas) -> GuideCanvas = { it }): Bitmap {
        val measurer = AndroidTextMeasurer(context, 1f)
        val logo = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(200, 60, 60))
            Canvas(this).drawCircle(80f, 45f, 30f, android.graphics.Paint().apply { color = Color.WHITE })
        }
        val frame = GuideReferenceFrame.build(measurer, images = Logos(BitmapGuideImage(logo)))
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(GuideColors.CANVAS)
        val gc = AndroidGuideCanvas(context)
        gc.bind(Canvas(bmp), 1f)
        val renderer = GuideRendererImpl(measurer)
        renderer.draw(wrap(gc), frame, frame.windowStartMin, 0f, GuideReferenceFrame.SCROLL_ROW)
        val b = FloatArray(4)
        assertTrue(renderer.focusedCellBounds(frame, frame.windowStartMin, 0f, GuideReferenceFrame.SCROLL_ROW, b))
        focusX = (b[0] + 6).toInt(); focusY = ((b[1] + b[3]) / 2).toInt()
        return bmp
    }

    @Test
    fun referenceFrameMatchesGolden() {
        val actual = render()
        val record = System.getenv("THEBOX_RECORD_GOLDEN") == "1"
        val golden = javaClass.getResourceAsStream("/golden/$NAME")?.use { BitmapFactory.decodeStream(it) }
        if (record || golden == null) {
            val dir = File(if (record) "src/test/resources/golden" else "build/golden").apply { mkdirs() }
            File(dir, NAME).outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!record) fail("no golden image; wrote ${File(dir, NAME).absolutePath} (re-run with THEBOX_RECORD_GOLDEN=1 to accept)")
            return
        }
        assertTrue(golden.width == actual.width && golden.height == actual.height)
        val differing = differing(actual, golden)
        val allowed = allowed(actual)
        if (differing > allowed) {
            val dir = File("build/golden").apply { mkdirs() }
            File(dir, NAME).outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fail("$differing pixels differ (allowed $allowed); actual written to ${File(dir, NAME).absolutePath}")
        }
    }

    @Test
    fun gridPixelsLandWhereTheGeometrySays() {
        val bmp = render()
        // Above the panel: untouched canvas.
        assertTrue(bmp.getPixel(960, 400) == GuideColors.CANVAS)
        // Now line at x ≈ 717.4 below the header (label 240, owner ruling 2026-10-17).
        val now = bmp.getPixel(717, 700)
        assertTrue("now line accent (${Integer.toHexString(now)})", Color.blue(now) > 180 && Color.red(now) < 150)
        // The focused cell is filled with FG (left padding, clear of text).
        val fp = bmp.getPixel(focusX, focusY)
        assertTrue("focused fill (${Integer.toHexString(fp)})", Color.red(fp) > 220 && Color.green(fp) > 220)
    }

    /** G-S5 mutation checks: each of these regressions must exceed the tolerance against the golden image. */
    @Test
    fun goldenCatchesALostFocusRing() = assertMutationFails("focus ring") { inner ->
        object : GuideCanvas by inner {
            override fun strokeRoundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, width: Float, argb: Int) {
                if (argb != GuideColors.ACCENT) inner.strokeRoundRect(l, t, r, b, radius, width, argb)
            }
        }
    }

    @Test
    fun goldenCatchesLostRulerLabels() = assertMutationFails("ruler labels") { inner ->
        object : GuideCanvas by inner {
            override fun drawText(text: String, start: Int, end: Int, x: Float, baseline: Float, style: GuideTextStyle, argb: Int) {
                if (style != GuideTextStyle.RULER && style != GuideTextStyle.RULER_CONTEXT) inner.drawText(text, start, end, x, baseline, style, argb)
            }
        }
    }

    @Test
    fun goldenCatchesLostCellTitles() = assertMutationFails("cell titles") { inner ->
        object : GuideCanvas by inner {
            override fun drawText(text: String, start: Int, end: Int, x: Float, baseline: Float, style: GuideTextStyle, argb: Int) {
                if (style != GuideTextStyle.CELL_TITLE) inner.drawText(text, start, end, x, baseline, style, argb)
            }
        }
    }

    @Test
    fun goldenCatchesALostNowLine() = assertMutationFails("now line") { inner ->
        object : GuideCanvas by inner {
            override fun fillRect(l: Float, t: Float, r: Float, b: Float, argb: Int) {
                if (argb != GuideColors.ACCENT) inner.fillRect(l, t, r, b, argb)
            }
            override fun drawAsset(asset: GuideAsset, l: Float, t: Float, r: Float, b: Float, argb: Int) {
                if (asset != GuideAsset.NOW_GLOW) inner.drawAsset(asset, l, t, r, b, argb)
            }
        }
    }

    private fun assertMutationFails(what: String, wrap: (GuideCanvas) -> GuideCanvas) {
        val golden = javaClass.getResourceAsStream("/golden/$NAME")?.use { BitmapFactory.decodeStream(it) } ?: return fail("no golden image")
        val mutated = render(wrap)
        val d = differing(mutated, golden)
        assertTrue("a frame without the $what differs in $d px, within the tolerance ${allowed(mutated)}", d > allowed(mutated))
    }

    private fun differing(actual: Bitmap, golden: Bitmap): Int {
        var differing = 0
        for (y in 0 until actual.height) for (x in 0 until actual.width) {
            val a = actual.getPixel(x, y); val g = golden.getPixel(x, y)
            if (abs(Color.red(a) - Color.red(g)) > TOLERANCE || abs(Color.green(a) - Color.green(g)) > TOLERANCE ||
                abs(Color.blue(a) - Color.blue(g)) > TOLERANCE || abs(Color.alpha(a) - Color.alpha(g)) > TOLERANCE
            ) differing++
        }
        return differing
    }

    /** 0.05 % of the frame (1,036 px at 1920 × 1080). */
    private fun allowed(b: Bitmap): Int = b.width * b.height / 2000

    private companion object {
        const val NAME = "guide-reference-1080p.png"
        const val TOLERANCE = 24
    }
}
