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
        slots.nextFrame()
        if (scale != this.scale) {
            clearRows()
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

    /** O-2: `guide-draw-chrome` / `-cells` / `-text` sub-sections inside the host's `guide-draw` (constant names). */
    override fun beginSection(name: String) = android.os.Trace.beginSection(name)

    override fun endSection() = android.os.Trace.endSection()

    // ---- O-2 per-row recordings (API 29+ RenderNode, hardware canvases only; ROW_LAYERS is the flag)
    private val rowNodes = arrayOfNulls<android.graphics.RenderNode>(ROW_SLOTS)
    /** Key → slot with frame-aware eviction: a node recorded this frame is never re-recorded in the same frame. */
    private val slots = RowSlots(ROW_SLOTS)
    private val rowTops = FloatArray(ROW_SLOTS)
    private var recordingNode: android.graphics.RenderNode? = null
    private var outerCanvas: Canvas? = null

    /** Rows replayed / recorded (debug measurement). */
    var rowReplays: Long = 0
        private set
    var rowRecords: Long = 0
        private set

    override fun beginRow(key: Long, top: Float): Boolean {
        val c = canvas ?: return false
        if (!ROW_LAYERS || android.os.Build.VERSION.SDK_INT < 29 || recordingNode != null || !c.isHardwareAccelerated) return false
        return beginRow29(c, key, top)
    }

    @androidx.annotation.RequiresApi(29)
    private fun beginRow29(c: Canvas, key: Long, top: Float): Boolean {
        var slot = slots.find(key)
        if (slot >= 0) {
            val existing = rowNodes[slot]
            if (existing != null && existing.hasDisplayList()) {
                val sv = c.save()
                c.translate(0f, top - rowTops[slot])
                c.drawRenderNode(existing)
                c.restoreToCount(sv)
                rowReplays++
                return true
            }
            // A trimmed display list: record again into the same slot.
        } else {
            slot = slots.allocate(key)
            if (slot < 0) return false // more rows than slots in one frame: drawn directly
        }
        val node = rowNodes[slot] ?: android.graphics.RenderNode("guide-row").also {
            it.setPosition(0, 0, ROW_NODE_EXTENT, ROW_NODE_EXTENT)
            it.setClipToBounds(false)
            rowNodes[slot] = it
        }
        rowTops[slot] = top
        outerCanvas = c
        recordingNode = node
        canvas = node.beginRecording(ROW_NODE_EXTENT, ROW_NODE_EXTENT)
        rowRecords++
        return false
    }

    override fun endRow() {
        val node = recordingNode ?: return
        if (android.os.Build.VERSION.SDK_INT >= 29) endRow29(node)
    }

    @androidx.annotation.RequiresApi(29)
    private fun endRow29(node: android.graphics.RenderNode) {
        node.endRecording()
        val outer = outerCanvas
        recordingNode = null
        outerCanvas = null
        canvas = outer
        outer?.drawRenderNode(node)
    }

    /** Drops every recording (trim, scale change). */
    fun clearRows() {
        slots.clear()
        if (android.os.Build.VERSION.SDK_INT >= 29) for (n in rowNodes) n?.discardDisplayList()
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

    companion object {
        /**
         * O-2 flag: per-row recordings on API 29+ hardware canvases. Off = the direct-draw path (always used on API ≤ 28
         * and on software canvases such as the golden-frame test).
         */
        @Volatile var ROW_LAYERS: Boolean = true
        /** Two passes × the visible rows (≤ 8) plus the rows a vertical scroll animation leaves behind; power of two not required. */
        private const val ROW_SLOTS = 48
        /** Recordings are in window px; their bounds only need to cover the window (no clip to bounds). */
        private const val ROW_NODE_EXTENT = 8192
    }
}
