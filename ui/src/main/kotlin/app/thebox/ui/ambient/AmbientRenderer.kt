package app.thebox.ui.ambient

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import app.thebox.core.guide.GuideColors
import app.thebox.core.guide.GuideGeometry
import kotlin.math.abs
import kotlin.math.exp

/**
 * One 480×270 ambient bitmap per hue (docs/design/m3-guide.md §3.14, architecture review B1.4): base #0A0D13, the two
 * CSS-blurred blobs approximated by radial gradients (no runtime blur anywhere), then the vertical overlay. Rendered on
 * a software canvas off the main thread; deterministic for a given hue (AmbientRendererTest).
 */
class AmbientRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    fun render(hue: Float): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(GuideColors.CANVAS)
        c.save()
        c.scale(WIDTH / GuideGeometry.CANVAS_W, HEIGHT / GuideGeometry.CANVAS_H)
        // 01-design-reference "Ambient background": blob A 1300×1000 at (−200, −260), blur 140, opacity .55.
        blob(c, cx = 450f, cy = 240f, rx = 650f, ry = 500f, blur = 140f, rgb = Oklch.toArgb(0.45f, 0.12f, hue), opacity = 0.55f)
        // Blob B 1100×900 at (right −300, top 200), blur 160, opacity .35.
        blob(c, cx = 1670f, cy = 650f, rx = 550f, ry = 450f, blur = 160f, rgb = Oklch.toArgb(0.40f, 0.10f, (hue + 40f) % 360f), opacity = 0.35f)
        paint.shader = LinearGradient(
            0f, 0f, 0f, GuideGeometry.CANVAS_H,
            intArrayOf(0x000A0D13, 0x990A0D13.toInt(), 0xEB0A0D13.toInt()),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, GuideGeometry.CANVAS_W, GuideGeometry.CANVAS_H, paint)
        paint.shader = null
        c.restore()
        return bmp
    }

    /**
     * A uniform ellipse blurred by CSS `filter: blur(b)` (Gaussian σ = b / 2, B1.4): the radial profile is
     * ½·erfc((u − 1) / (σᵤ√2)) in units of the x radius, sampled at 8 stops (2 inside, 6 across the edge).
     */
    private fun blob(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, blur: Float, rgb: Int, opacity: Float) {
        val sigmaU = (blur / 2f) / rx
        val outer = 1f + 3f * sigmaU
        val inner = (1f - 2f * sigmaU).coerceAtLeast(0.05f)
        val us = floatArrayOf(0f, inner, 1f - sigmaU, 1f - sigmaU / 2f, 1f, 1f + sigmaU, 1f + 2f * sigmaU, outer)
        val colors = IntArray(us.size)
        val stops = FloatArray(us.size)
        for (i in us.indices) {
            val a = opacity * 0.5f * erfc((us[i] - 1f) / (sigmaU * SQRT2))
            colors[i] = (((a * 255f).toInt().coerceIn(0, 255)) shl 24) or (rgb and 0xFFFFFF)
            stops[i] = us[i] / outer
        }
        paint.shader = RadialGradient(0f, 0f, rx * outer, colors, stops, Shader.TileMode.CLAMP)
        c.save()
        c.translate(cx, cy)
        c.scale(1f, ry / rx)
        c.drawCircle(0f, 0f, rx * outer, paint)
        c.restore()
        paint.shader = null
    }

    /**
     * The hole's drop shadow `0 30px 80px rgba(0,0,0,.55)` (§3.13.3), rendered once with a software [BlurMaskFilter]
     * at ¼ scale. Covers the hole plus [SHADOW_MARGIN] design px on each side; drawn by [AmbientBackground] scaled ×4
     * with its top-left at (hole.left − margin, hole.top − margin + 30).
     */
    fun renderShadow(): Bitmap {
        val q = SHADOW_SCALE
        val w = ((GuideGeometry.HOLE_W + 2 * SHADOW_MARGIN) * q).toInt()
        val h = ((GuideGeometry.HOLE_H + 2 * SHADOW_MARGIN) * q).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x8C000000.toInt() // .55
        p.maskFilter = BlurMaskFilter(SHADOW_BLUR / 2f * q, BlurMaskFilter.Blur.NORMAL)
        val m = SHADOW_MARGIN * q
        c.drawRoundRect(RectF(m, m, m + GuideGeometry.HOLE_W * q, m + GuideGeometry.HOLE_H * q), GuideGeometry.HOLE_R * q, GuideGeometry.HOLE_R * q, p)
        return bmp
    }

    companion object {
        const val WIDTH = 480
        const val HEIGHT = 270
        const val SHADOW_SCALE = 0.25f
        const val SHADOW_MARGIN = 120f
        const val SHADOW_BLUR = 80f
        const val SHADOW_DY = 30f
        private const val SQRT2 = 1.4142135f

        /** Abramowitz–Stegun 7.1.26 (|error| < 1.5e−7). */
        fun erfc(x: Float): Float {
            val z = abs(x)
            val t = 1f / (1f + 0.3275911f * z)
            val y = t * (0.254829592f + t * (-0.284496736f + t * (1.421413741f + t * (-1.453152027f + t * 1.061405429f)))) * exp(-z * z)
            return if (x >= 0f) y else 2f - y
        }
    }
}
