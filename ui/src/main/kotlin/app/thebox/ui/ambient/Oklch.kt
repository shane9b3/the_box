package app.thebox.ui.ambient

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * oklch → sRGB for the ambient blobs (docs/design/m3-guide.md §3.14): OKLCH → OKLab → linear sRGB (Björn Ottosson's
 * matrices, as CSS Color 4 uses) → sRGB gamma. Out-of-gamut channels are clamped. Pure, allocation-free.
 */
object Oklch {
    /** [l] 0..1, [c] chroma (≈ 0..0.4), [h] hue in degrees, [alpha] 0..1. Returns ARGB. */
    fun toArgb(l: Float, c: Float, h: Float, alpha: Float = 1f): Int {
        val hr = Math.toRadians(h.toDouble())
        val a = c * cos(hr)
        val b = c * sin(hr)
        val l1 = l + 0.3963377774 * a + 0.2158037573 * b
        val m1 = l - 0.1055613458 * a - 0.0638541728 * b
        val s1 = l - 0.0894841775 * a - 1.2914855480 * b
        val ll = l1 * l1 * l1
        val mm = m1 * m1 * m1
        val ss = s1 * s1 * s1
        val r = 4.0767416621 * ll - 3.3077115913 * mm + 0.2309699292 * ss
        val g = -1.2684380046 * ll + 2.6097574011 * mm - 0.3413193965 * ss
        val bl = -0.0041960863 * ll - 0.7034186147 * mm + 1.7076147010 * ss
        val ai = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        return (ai shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(bl)
    }

    private fun channel(linear: Double): Int {
        val x = linear.coerceIn(0.0, 1.0)
        val v = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1.0 / 2.4) - 0.055
        return (v * 255.0).roundToInt().coerceIn(0, 255)
    }
}
