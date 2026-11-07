package app.thebox.core.sports

/** (Goal animation §3.1; WP-1) WCAG 2.x relative luminance and contrast ratio over sRGB; alpha is ignored. */
object Contrast {
    @Suppress("UNUSED_PARAMETER")
    fun luminance(argb: Int): Double = TODO("WP-1")

    /** (L1 + 0.05) / (L2 + 0.05), lighter over darker: 1.0 … 21.0. */
    @Suppress("UNUSED_PARAMETER")
    fun ratio(a: Int, b: Int): Double = TODO("WP-1")
}
