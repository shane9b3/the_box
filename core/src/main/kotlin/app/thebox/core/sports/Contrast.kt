package app.thebox.core.sports

import kotlin.math.pow

/** (Goal animation §3.1; WP-1) WCAG 2.x relative luminance and contrast ratio over sRGB; alpha is ignored. */
object Contrast {
    /** WCAG 2.x relative luminance, 0.0 (black) … 1.0 (white). */
    fun luminance(argb: Int): Double =
        0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)

    /** (L1 + 0.05) / (L2 + 0.05), lighter over darker: 1.0 … 21.0. */
    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** The sRGB transfer function, linearised (WCAG 2.x uses the 0.04045 threshold). */
    private fun channel(c8: Int): Double {
        val c = c8 / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
}
