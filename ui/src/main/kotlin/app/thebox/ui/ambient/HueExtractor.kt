package app.thebox.ui.ambient

import android.graphics.Bitmap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Our own 16×16 hue extractor (docs/design/m3-guide.md §3.14; no Palette dependency). Drops pixels with alpha < 128,
 * HSL saturation < .25 or lightness outside .15–.9, and returns the circular mean hue (degrees, 0 ≤ h < 360) weighted
 * by saturation. Fewer than [MIN_PIXELS] usable pixels → null (the caller falls back to [fallbackHue]).
 */
object HueExtractor {
    const val SAMPLE = 16
    const val MIN_PIXELS = 8

    /** Samples [bitmap] at 16×16 (software copy when needed). Call off the main thread. */
    fun hue(bitmap: Bitmap): Float? {
        val hardware = android.os.Build.VERSION.SDK_INT >= 26 && bitmap.config == Bitmap.Config.HARDWARE
        val src = if (hardware) bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null else bitmap
        val small = if (src.width == SAMPLE && src.height == SAMPLE) src else Bitmap.createScaledBitmap(src, SAMPLE, SAMPLE, true)
        val px = IntArray(SAMPLE * SAMPLE)
        small.getPixels(px, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE)
        return hue(px)
    }

    /** ARGB pixels → weighted circular-mean hue, or null. Pure (JVM-testable). */
    fun hue(argb: IntArray): Float? {
        var sx = 0.0
        var sy = 0.0
        var n = 0
        for (p in argb) {
            if ((p ushr 24) < 128) continue
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val light = (mx + mn) / 2f
            if (light < 0.15f || light > 0.9f) continue
            val d = mx - mn
            if (d <= 0f) continue
            val sat = d / (1f - kotlin.math.abs(2f * light - 1f))
            if (sat < 0.25f) continue
            val h = when (mx) {
                r -> 60f * (((g - b) / d) % 6f)
                g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
            val rad = Math.toRadians(h.toDouble())
            sx += sat * cos(rad)
            sy += sat * sin(rad)
            n++
        }
        if (n < MIN_PIXELS || sqrt(sx * sx + sy * sy) < 1e-6) return null
        val deg = Math.toDegrees(atan2(sy, sx))
        return (((deg % 360.0) + 360.0) % 360.0).toFloat()
    }

    /** §3.14 fallback: `(GuideChannel.hashOf(identity) ushr 1) % 360`. */
    fun fallbackHue(identityHash: Long): Float = ((identityHash ushr 1) % 360L).toFloat()
}
