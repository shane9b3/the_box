package app.thebox.ui.guide.grid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import app.thebox.core.guide.GuideAsset
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Pre-rendered guide assets (docs/design/m3-guide.md §4.4; no runtime blur, B1.3/B2.6). Every asset is an
 * `ALPHA_8` mask: drawing an alpha-only bitmap with a `Paint` paints the paint's colour through the mask, so the
 * canvas tints them per call without a `ColorFilter` allocation.
 *
 * - [GuideAsset.FOCUS_SHADOW]: a rounded rect (radius 12) blurred once in software (three box passes ≈ a Gaussian
 *   with σ = 15, CSS `0 12px 30px`), drawn as a nine-slice so long cells keep round corners; [shadowInset] is the
 *   slice inset (margin + radius).
 * - [GuideAsset.NOW_GLOW]: a horizontal Gaussian strip (CSS `box-shadow: 0 0 12px`), stretched vertically.
 * - [GuideAsset.ARCHIVE_GLYPH] / [GuideAsset.ARCHIVE_GLYPH_HOLLOW]: ↺ (stroke / outlined stroke), 2× oversampled at
 *   the label column's 20 design px (decision 2026-10-19); the in-cell 12 px ↺ is drawn from the same mask.
 * - [GuideAsset.EDGE_CHEVRON] / [GuideAsset.CLIP_CHEVRON] (decision 2026-10-19): a left-pointing chevron rasterised at
 *   its drawn size, 12 × 24 design px: polyline (9.5, 2.5) → (2.5, 12) → (9.5, 21.5) in a 12 × 24 viewport, stroke 3,
 *   round caps and joins. Both entries share one mask; the renderer tints them (accent / the focused title's colour).
 * - [GuideAsset.LOCK_GLYPH]: a padlock, 2× oversampled.
 *
 * Built lazily on first use at [scale]; a scale change needs a new instance. Total < 50 KB at 1080p.
 */
class GuideAssets(private val scale: Float) {
    private val bitmaps = arrayOfNulls<Bitmap>(GuideAsset.entries.size)

    private val shadowMargin = (SHADOW_MARGIN * scale).roundToInt()
    /** Core rounded rect of the shadow: radius plus a plateau of 2σ so its centre reaches ~0.9 coverage. */
    private val shadowCore = 2 * (CELL_R * scale).roundToInt() + 4 + 2 * (SHADOW_SIGMA * scale).roundToInt()

    /**
     * Nine-slice inset of [GuideAsset.FOCUS_SHADOW] in its bitmap's px: everything but a 2 px centre cross. The
     * canvas draws the corners 1:1 and shrinks them proportionally when the target is smaller than two insets.
     */
    val shadowInset: Int = shadowMargin + shadowCore / 2 - 1

    fun bitmap(asset: GuideAsset): Bitmap {
        bitmaps[asset.ordinal]?.let { return it }
        val b = when (asset) {
            GuideAsset.FOCUS_SHADOW -> shadow()
            GuideAsset.NOW_GLOW -> glow()
            GuideAsset.ARCHIVE_GLYPH -> archive(hollow = false)
            GuideAsset.ARCHIVE_GLYPH_HOLLOW -> archive(hollow = true)
            GuideAsset.LOCK_GLYPH -> lock()
            GuideAsset.EDGE_CHEVRON -> chevron()
            GuideAsset.CLIP_CHEVRON -> bitmap(GuideAsset.EDGE_CHEVRON)
        }
        bitmaps[asset.ordinal] = b
        return b
    }

    /** Bytes held (hand-off measurement). */
    fun byteCount(): Int = bitmaps.sumOf { it?.allocationByteCount ?: 0 }

    private fun shadow(): Bitmap {
        val margin = shadowMargin
        val r = (CELL_R * scale).roundToInt()
        val core = shadowCore
        val size = core + 2 * margin
        val a = FloatArray(size * size)
        // Rounded rect coverage (1 inside), then three box blurs (σ ≈ 15 design px).
        val rect = RectF(margin.toFloat(), margin.toFloat(), (margin + core).toFloat(), (margin + core).toFloat())
        for (y in 0 until size) for (x in 0 until size) a[y * size + x] = roundRectCoverage(x + 0.5f, y + 0.5f, rect, r.toFloat())
        val box = maxOf(1, (SHADOW_SIGMA * scale * 0.87f).roundToInt())
        repeat(3) { boxBlur(a, size, size, box) }
        return alphaBitmap(size, size) { x, y -> a[y * size + x] }
    }

    private fun glow(): Bitmap {
        val w = maxOf(4, (2 * GLOW_HALF * scale).roundToInt())
        val sigma = GLOW_SIGMA * scale
        val c = (w - 1) / 2f
        return alphaBitmap(w, 4) { x, _ -> val d = x - c; GLOW_PEAK * exp(-(d * d) / (2 * sigma * sigma)) }
    }

    private fun archive(hollow: Boolean): Bitmap {
        val n = maxOf(8, ceil(ARCHIVE_GLYPH * scale * 2).toInt())
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        val k = n / 24f // draw on a 24-unit grid
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = -0x1000000 }
        val path = Path().apply {
            // ↺: an arc open at the top-left with an arrowhead pointing anticlockwise.
            addArc(RectF(4f * k, 4f * k, 20f * k, 20f * k), 200f, 300f)
            moveTo(3f * k, 4f * k); lineTo(4.3f * k, 9.6f * k); lineTo(9.8f * k, 8.2f * k)
        }
        p.strokeWidth = 2.6f * k
        c.drawPath(path, p)
        if (hollow) {
            p.strokeWidth = 1.1f * k
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            c.drawPath(path, p)
        }
        return bmp
    }

    /** Left-pointing chevron mask at its drawn size (1:1, no resampling at the snapped 12 × 24 × scale target). */
    private fun chevron(): Bitmap {
        val w = maxOf(3, ceil(CHEVRON_W * scale).toInt())
        val h = maxOf(6, ceil(CHEVRON_H * scale).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        val kx = w / CHEVRON_W
        val ky = h / CHEVRON_H
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = -0x1000000
            strokeWidth = CHEVRON_STROKE * minOf(kx, ky)
        }
        val path = Path().apply { moveTo(9.5f * kx, 2.5f * ky); lineTo(2.5f * kx, 12f * ky); lineTo(9.5f * kx, 21.5f * ky) }
        c.drawPath(path, p)
        return bmp
    }

    private fun lock(): Bitmap {
        val n = maxOf(8, ceil(GLYPH * scale * 2).toInt())
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        val k = n / 24f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0x1000000 }
        c.drawRoundRect(RectF(5f * k, 10f * k, 19f * k, 21f * k), 2f * k, 2f * k, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.4f * k
        c.drawArc(RectF(8f * k, 3f * k, 16f * k, 13f * k), 180f, 180f, false, p)
        c.drawLine(8f * k, 8f * k, 8f * k, 11f * k, p); c.drawLine(16f * k, 8f * k, 16f * k, 11f * k, p)
        return bmp
    }

    private inline fun alphaBitmap(w: Int, h: Int, alpha: (Int, Int) -> Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val a = (alpha(x, y).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            px[y * w + x] = a shl 24
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    companion object {
        const val SHADOW_MARGIN = 30f
        const val SHADOW_SIGMA = 15f
        const val CELL_R = 12f
        const val GLOW_HALF = 12f
        const val GLOW_SIGMA = 5f
        const val GLOW_PEAK = 0.6f
        /** Lock glyph size (design px). */
        const val GLYPH = 12f
        /** ↺ mask size: the largest draw, the label column's glyph (decision 2026-10-19; 24 since 2026-11-09). */
        const val ARCHIVE_GLYPH = app.thebox.core.guide.GuideGeometry.LABEL_GLYPH
        const val CHEVRON_W = 12f
        const val CHEVRON_H = 24f
        const val CHEVRON_STROKE = 3f

        private fun roundRectCoverage(x: Float, y: Float, r: RectF, rad: Float): Float {
            if (x < r.left || x > r.right || y < r.top || y > r.bottom) return 0f
            val cx = when { x < r.left + rad -> r.left + rad; x > r.right - rad -> r.right - rad; else -> return 1f }
            val cy = when { y < r.top + rad -> r.top + rad; y > r.bottom - rad -> r.bottom - rad; else -> return 1f }
            val dx = x - cx; val dy = y - cy
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            return (rad - d + 0.5f).coerceIn(0f, 1f)
        }

        /** Separable box blur of radius [r], in place. */
        private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int) {
            val tmp = FloatArray(maxOf(w, h))
            val div = 2f * r + 1
            for (y in 0 until h) {
                var acc = 0f
                for (i in -r..r) acc += a[y * w + i.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    tmp[x] = acc / div
                    acc += a[y * w + (x + r + 1).coerceAtMost(w - 1)] - a[y * w + (x - r).coerceAtLeast(0)]
                }
                for (x in 0 until w) a[y * w + x] = tmp[x]
            }
            for (x in 0 until w) {
                var acc = 0f
                for (i in -r..r) acc += a[i.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    tmp[y] = acc / div
                    acc += a[(y + r + 1).coerceAtMost(h - 1) * w + x] - a[(y - r).coerceAtLeast(0) * w + x]
                }
                for (y in 0 until h) a[y * w + x] = tmp[y]
            }
        }
    }
}
