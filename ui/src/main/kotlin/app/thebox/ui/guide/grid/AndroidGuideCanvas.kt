package app.thebox.ui.guide.grid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import app.thebox.core.guide.GuideAsset
import app.thebox.core.guide.GuideCanvas
import app.thebox.core.guide.GuideImage
import app.thebox.core.guide.GuideTextStyle

/** A Coil/Android bitmap as a [GuideImage]. */
class BitmapGuideImage(val bitmap: Bitmap) : GuideImage {
    override val widthPx: Int get() = bitmap.width
    override val heightPx: Int get() = bitmap.height
}

/**
 * `GuideCanvas` over `android.graphics.Canvas` (docs/design/m3-guide.md §1.2, §4.2). Preallocated paints and rects;
 * no call allocates. The host [bind]s the frame's canvas and scale before each `GuideRenderer.draw`.
 *
 * Text paints come from [GuidePaints] (the same typefaces, sizes, tracking and `tnum` as [AndroidTextMeasurer]).
 * Assets are `ALPHA_8` masks tinted by the paint colour ([GuideAssets]); the focus shadow is drawn as a nine-slice.
 */
class AndroidGuideCanvas(context: Context) : GuideCanvas {
    /** The canvas of the current draw pass. */
    var canvas: Canvas? = null

    var scale: Float = 1f
        private set

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text: Array<Paint> = GuidePaints.textPaints(context, 1f)
    private val rect = RectF()
    private val src = Rect()
    private val dst = RectF()
    private var assets: GuideAssets = GuideAssets(1f)

    /** Binds [target] for one draw pass at [scale] device px per design px. */
    fun bind(target: Canvas, scale: Float) {
        canvas = target
        if (scale != this.scale) {
            this.scale = scale
            GuidePaints.applyScale(text, scale)
            assets = GuideAssets(scale)
        }
    }

    /** Asset bytes (hand-off measurement). */
    fun assetBytes(): Int = assets.byteCount()

    override fun save(): Int = canvas?.save() ?: 0

    override fun restoreToCount(count: Int) { canvas?.restoreToCount(count) }

    override fun clipRect(l: Float, t: Float, r: Float, b: Float) { canvas?.clipRect(l, t, r, b) }

    override fun fillRect(l: Float, t: Float, r: Float, b: Float, argb: Int) {
        val c = canvas ?: return
        fill.color = argb
        c.drawRect(l, t, r, b, fill)
    }

    override fun fillRoundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, argb: Int) {
        val c = canvas ?: return
        fill.color = argb
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, radius, radius, fill)
    }

    override fun strokeRoundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, width: Float, argb: Int) {
        val c = canvas ?: return
        stroke.color = argb
        stroke.strokeWidth = width
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, radius, radius, stroke)
    }

    override fun fillCircle(cx: Float, cy: Float, radius: Float, argb: Int) {
        val c = canvas ?: return
        fill.color = argb
        c.drawCircle(cx, cy, radius, fill)
    }

    override fun drawText(text: String, start: Int, end: Int, x: Float, baseline: Float, style: GuideTextStyle, argb: Int) {
        val c = canvas ?: return
        if (end <= start) return
        val p = this.text[style.ordinal]
        p.color = argb
        c.drawText(text, start, minOf(end, text.length), x, baseline, p)
    }

    override fun drawImage(image: GuideImage, l: Float, t: Float, r: Float, b: Float, alpha: Float) {
        val c = canvas ?: return
        val bmp = (image as? BitmapGuideImage)?.bitmap ?: return
        if (bmp.isRecycled) return
        bitmapPaint.color = -0x1000000
        bitmapPaint.alpha = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
        dst.set(l, t, r, b)
        c.drawBitmap(bmp, null, dst, bitmapPaint)
    }

    override fun drawAsset(asset: GuideAsset, l: Float, t: Float, r: Float, b: Float, argb: Int) {
        val c = canvas ?: return
        val bmp = assets.bitmap(asset)
        bitmapPaint.color = argb
        if (asset == GuideAsset.FOCUS_SHADOW) {
            nineSlice(c, bmp, assets.shadowInset, l, t, r, b)
        } else {
            dst.set(l, t, r, b)
            c.drawBitmap(bmp, null, dst, bitmapPaint)
        }
    }

    /** Draws [bmp] stretched to (l, t, r, b) keeping [inset]-px corners (shrunk proportionally when they do not fit). */
    private fun nineSlice(c: Canvas, bmp: Bitmap, inset: Int, l: Float, t: Float, r: Float, b: Float) {
        val w = bmp.width
        val h = bmp.height
        val ix = minOf(inset.toFloat(), (r - l) / 2)
        val iy = minOf(inset.toFloat(), (b - t) / 2)
        for (row in 0 until 3) {
            val sy0 = if (row == 0) 0 else if (row == 1) inset else h - inset
            val sy1 = if (row == 0) inset else if (row == 1) h - inset else h
            val dy0 = if (row == 0) t else if (row == 1) t + iy else b - iy
            val dy1 = if (row == 0) t + iy else if (row == 1) b - iy else b
            if (sy1 <= sy0 || dy1 <= dy0) continue
            for (col in 0 until 3) {
                val sx0 = if (col == 0) 0 else if (col == 1) inset else w - inset
                val sx1 = if (col == 0) inset else if (col == 1) w - inset else w
                val dx0 = if (col == 0) l else if (col == 1) l + ix else r - ix
                val dx1 = if (col == 0) l + ix else if (col == 1) r - ix else r
                if (sx1 <= sx0 || dx1 <= dx0) continue
                src.set(sx0, sy0, sx1, sy1)
                dst.set(dx0, dy0, dx1, dy1)
                c.drawBitmap(bmp, src, dst, bitmapPaint)
            }
        }
    }
}
