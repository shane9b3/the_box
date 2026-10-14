package app.thebox.ui.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** docs/design/m3-guide.md §3.14, §5.3 (`OklchTest`, `HueExtractorTest`). Pure JVM. */
class OklchHueTest {
    private fun rgb(argb: Int) = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    private fun assertRgb(expected: Int, actual: Int, tol: Int = 1) {
        val (er, eg, eb) = rgb(expected)
        val (ar, ag, ab) = rgb(actual)
        assertTrue("expected %06X, got %06X".format(expected and 0xFFFFFF, actual and 0xFFFFFF), abs(er - ar) <= tol && abs(eg - ag) <= tol && abs(eb - ab) <= tol)
    }

    @Test
    fun cssReferencePointsRoundTripWithinOneStep() {
        // CSS Color 4 reference conversions of the sRGB primaries and white.
        assertRgb(0xFFFFFF, Oklch.toArgb(1f, 0f, 0f))
        assertRgb(0x000000, Oklch.toArgb(0f, 0f, 0f))
        assertRgb(0xFF0000, Oklch.toArgb(0.627955f, 0.257683f, 29.2339f))
        assertRgb(0x00FF00, Oklch.toArgb(0.866440f, 0.294827f, 142.4953f))
        assertRgb(0x0000FF, Oklch.toArgb(0.452014f, 0.313214f, 264.052f))
    }

    @Test
    fun designTokenOklchValues() {
        // The design's oklch tokens, converted exactly. NOTE (hand-off): DesignTokens' "≈" fallbacks differ —
        // accent #5CA8F2 vs oklch(.72 .14 240) = #3FAFF3, accentText #B6D4F7 vs oklch(.86 .09 240) = #9AD9FF.
        assertRgb(0x3FAFF3, Oklch.toArgb(0.72f, 0.14f, 240f))
        assertRgb(0x9AD9FF, Oklch.toArgb(0.86f, 0.09f, 240f))
        // The ambient blob colours at the default hue, and alpha packing.
        assertRgb(0x005B90, Oklch.toArgb(0.45f, 0.12f, 240f))
        assertRgb(0x3E3F7C, Oklch.toArgb(0.40f, 0.10f, 280f))
        assertEquals(0x80, Oklch.toArgb(0.5f, 0.1f, 100f, alpha = 0.5f) ushr 24)
    }

    @Test
    fun outOfGamutIsClamped() {
        val c = Oklch.toArgb(0.9f, 0.4f, 140f)
        val (r, g, b) = rgb(c)
        assertTrue(r in 0..255 && g in 0..255 && b in 0..255)
        assertEquals(0xFF, c ushr 24)
    }

    private fun px(n: Int, argb: Int) = IntArray(n) { argb.toInt() }

    @Test
    fun hueOfSolidColours() {
        assertEquals(0f, HueExtractor.hue(px(64, 0xFFE02020.toInt()))!!, 1f)
        assertEquals(120f, HueExtractor.hue(px(64, 0xFF20C020.toInt()))!!, 1f)
        assertEquals(240f, HueExtractor.hue(px(64, 0xFF2020E0.toInt()))!!, 1f)
        assertEquals(230f, HueExtractor.hue(px(64, 0xFF2040E0.toInt()))!!, 1f)
    }

    @Test
    fun circularMeanWrapsAroundZero() {
        val pixels = px(32, 0xFFE0204A.toInt()) + px(32, 0xFFE04A20.toInt()) // ≈ 350° and ≈ 10°
        val h = HueExtractor.hue(pixels)!!
        assertTrue("got $h", h < 5f || h > 355f)
    }

    @Test
    fun weightedBySaturation() {
        // 32 saturated blue + 32 weakly saturated red: the mean leans to blue.
        val pixels = px(32, 0xFF1030F0.toInt()) + px(32, 0xFFA06060.toInt())
        val h = HueExtractor.hue(pixels)!!
        assertTrue("got $h", h > 200f && h < 300f)
    }

    @Test
    fun filteredPixelsAndTooFewGiveNull() {
        assertNull("transparent", HueExtractor.hue(px(64, 0x40E02020)))
        assertNull("grey", HueExtractor.hue(px(64, 0xFF808080.toInt())))
        assertNull("near black", HueExtractor.hue(px(64, 0xFF100505.toInt())))
        assertNull("near white", HueExtractor.hue(px(64, 0xFFFFF5F5.toInt())))
        assertNull("only 7 usable", HueExtractor.hue(px(7, 0xFFE02020.toInt()) + px(57, 0xFF808080.toInt())))
        assertNotNull("8 usable is enough", HueExtractor.hue(px(8, 0xFFE02020.toInt()) + px(56, 0xFF808080.toInt())))
    }

    @Test
    fun fallbackHueIsStableAndInRange() {
        val h = HueExtractor.fallbackHue(-1L)
        assertTrue(h >= 0f && h < 360f)
        assertEquals(h, HueExtractor.fallbackHue(-1L))
        assertEquals(((1234567L ushr 1) % 360L).toFloat(), HueExtractor.fallbackHue(1234567L))
    }
}
