package app.thebox.ui.sports

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import app.thebox.core.sports.SportsConstants
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap

/** Fetches one team logo already trimmed and fitted to a [w] × [h] device-px slot; null = failed. */
typealias LogoFetch = suspend (url: String, w: Int, h: Int) -> Bitmap?

/**
 * (docs/design/m6-score-bug.md §3) The Coil path: the SVG is rasterised at 4x the slot with the memory cache off (the
 * [FollowLogos] holder is the cache), then [LogoTrim] crops and fits it. Coil's disk cache keeps the SVG source.
 */
fun coilLogoFetch(loader: ImageLoader, context: Context): LogoFetch = { url, w, h ->
    val request = ImageRequest.Builder(context.applicationContext ?: context)
        .data(url)
        .size(w * SportsConstants.LOGO_RASTER_SCALE, h * SportsConstants.LOGO_RASTER_SCALE)
        .scale(Scale.FIT)
        .precision(Precision.INEXACT)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .allowHardware(false)
        .transformations(LogoTrim(w, h))
        .build()
    (loader.execute(request) as? SuccessResult)?.image?.toBitmap()
}

/**
 * The fallback (§3): a neutral disc with the team abbreviation (White08 fill, 1 px Border12, Instrument Sans 18/700 Fg),
 * drawn when the logo failed or has not arrived within `LOGO_WAIT_MS`. Paints are built once; [draw] allocates nothing.
 */
internal class LogoDisc(private val scale: Float, typeface: Typeface?) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = argb(0.08f, 0xFFFFFF) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = scale.coerceAtLeast(1f); color = argb(0.12f, 0xFFFFFF)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = SportsConstants.LOGO_DISC_TEXT_PX * scale; color = 0xFFEEF1F6.toInt(); textAlign = Paint.Align.CENTER
        this.typeface = typeface
    }
    private val centreOffset = -(text.fontMetrics.ascent + text.fontMetrics.descent) / 2f
    private val rect = RectF()

    fun draw(c: Canvas, cx: Float, cy: Float, abbrev: String, alpha: Float = 1f) {
        val r = SportsConstants.LOGO_DISC_D * scale / 2f
        fill.alpha = (0x14 * alpha).toInt()
        c.drawCircle(cx, cy, r, fill)
        stroke.alpha = (0x1F * alpha).toInt()
        val h = stroke.strokeWidth / 2f
        rect.set(cx - r + h, cy - r + h, cx + r - h, cy + r - h)
        c.drawOval(rect, stroke)
        text.alpha = (255 * alpha).toInt()
        c.drawText(abbrev, cx, cy + centreOffset, text)
    }

    private fun argb(a: Float, rgb: Int): Int = ((a * 255f + 0.5f).toInt() shl 24) or rgb
}
