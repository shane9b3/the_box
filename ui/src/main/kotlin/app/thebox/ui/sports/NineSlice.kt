package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.StackedMock
import kotlin.math.ceil

/**
 * (Stacked §6, B4; Stacked WP-2) The Stacked box's drop shadow: one `ALPHA_8` mask per size, baked on a software canvas
 * with `BlurMaskFilter` (σ `STACKED_SHADOW_SIGMA`, offset `STACKED_SHADOW_DY`, radius `StackedMock.BOX_RADIUS` × scale)
 * from a source square of `STACKED_SHADOW_SRC` (Large `STACKED_SHADOW_SRC_LARGE`) design px, ≥ 6σ + 1, so every slice line
 * lies ≥ 3σ inside the shape and the stretched edge profile is exact. Drawn as 8 `drawBitmap(src, dst)` calls stretched
 * to the current box rect, the centre skipped (the box fill covers it, N7); it follows the box through every layout
 * transition. No real-time blur.
 *
 * Built by [StackedPainter.bakeShadow] off the main thread; only the painters read its insides, so its fields are WP-2's.
 * The runner (Stacked WP-3) only holds it and hands it back in [StackedDrawArgs.shadow]. A [NineSlice] made with the
 * no-argument constructor has no mask and draws nothing.
 *
 * **The mask.** The source box ([side] × [side] device px, the box's own corner radius) sits at ([padL], [padT]) in the
 * bitmap. Its shadow (the box moved down by dy, blurred to σ) is drawn there, and the bitmap reaches 3σ + 1 px past the
 * shadow on every side, so nothing of the blur is cut. The slice lines run through the shadow shape's middle: column
 * [cx] and row [cy] are one pixel each and lie ≥ 3σ from both opposite edges of the shadow shape, so the profile across
 * each is the straight-edge one and stretching them is exact. The corner parts keep their bitmap size, placed from the
 * box's own edges; the middle column and row stretch to whatever the box is.
 */
internal class NineSlice() {
    private var mask: Bitmap? = null
    private var side = 0
    private var padL = 0
    private var padT = 0
    private var cx = 0
    private var cy = 0

    private val src = Rect()
    private val dst = RectF()

    /**
     * (WP-2) Bakes the mask for one size: [s] device px per design px with the Large factor folded in; [large] picks
     * `STACKED_SHADOW_SRC_LARGE`, which is given for the Large size (so it is multiplied by the density, not by [s]).
     * Allocates; off the main thread.
     */
    internal constructor(s: Float, large: Boolean) : this() {
        val density = if (large) s / K.SCORE_LARGE_SCALE else s
        side = ceil((if (large) K.STACKED_SHADOW_SRC_LARGE else K.STACKED_SHADOW_SRC) * density).toInt()
        val sigma = K.STACKED_SHADOW_SIGMA * s
        val dy = K.STACKED_SHADOW_DY * s
        val radius = StackedMock.BOX_RADIUS * K.STACKED_SCALE * s
        val reach = ceil(3f * sigma).toInt() + 1
        padL = reach
        padT = (reach - dy.toInt()).coerceAtLeast(1)
        val w = padL + side + reach
        val h = padT + side + ceil(dy).toInt() + reach
        val bmp = createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            // Skia's sigma = 0.57735 × radius + 0.5 (as GoalPainter.bakeMask).
            maskFilter = BlurMaskFilter(((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL)
        }
        Canvas(bmp).drawRoundRect(padL.toFloat(), padT + dy, (padL + side).toFloat(), padT + side + dy, radius, radius, p)
        // The middle column / row through the centre of the shadow's shape (the row is moved down by dy with it), so
        // both lie ≥ 3σ from every edge of the shape.
        cx = padL + side / 2
        cy = padT + (side / 2f + dy).toInt()
        mask = bmp
    }

    /** Test seam: the baked mask (null for the no-argument instance). */
    internal val bitmap: Bitmap? get() = mask

    /** Test seam: the source square's side, device px. */
    internal val sourceSide: Int get() = side

    /**
     * The shadow for the box rect ([l], [t], [r], [b], device px, the box's own shape, the shadow's offset applied here),
     * tinted black at [paint]'s alpha. Allocation-free.
     */
    fun draw(c: Canvas, l: Float, t: Float, r: Float, b: Float, paint: Paint) {
        val m = mask ?: return
        if (r <= l || b <= t) return
        val bw = m.width
        val bh = m.height
        val x1 = cx
        val x2 = cx + 1
        val y1 = cy
        val y2 = cy + 1
        // Destination columns: [dl, dx1) left part, [dx1, dx2) the stretched middle, [dx2, dr) right part.
        val dl = l - padL
        val dx1 = l + (x1 - padL)
        var dx2 = r - (padL + side - x2)
        if (dx2 < dx1) dx2 = dx1 // a box narrower than the source: the middle collapses (never for the Stacked box)
        val dr = dx2 + (bw - x2)
        val dt = t - padT
        val dy1 = t + (y1 - padT)
        var dy2 = b - (padT + side - y2)
        if (dy2 < dy1) dy2 = dy1
        val db = dy2 + (bh - y2)

        slice(c, m, 0, 0, x1, y1, dl, dt, dx1, dy1, paint)       // top left
        slice(c, m, x1, 0, x2, y1, dx1, dt, dx2, dy1, paint)     // top
        slice(c, m, x2, 0, bw, y1, dx2, dt, dr, dy1, paint)      // top right
        slice(c, m, 0, y1, x1, y2, dl, dy1, dx1, dy2, paint)     // left
        slice(c, m, x2, y1, bw, y2, dx2, dy1, dr, dy2, paint)    // right (the centre is skipped, N7)
        slice(c, m, 0, y2, x1, bh, dl, dy2, dx1, db, paint)      // bottom left
        slice(c, m, x1, y2, x2, bh, dx1, dy2, dx2, db, paint)    // bottom
        slice(c, m, x2, y2, bw, bh, dx2, dy2, dr, db, paint)     // bottom right
    }

    private fun slice(
        c: Canvas, m: Bitmap, sl: Int, st: Int, sr: Int, sb: Int, l: Float, t: Float, r: Float, b: Float, paint: Paint,
    ) {
        if (sr <= sl || sb <= st || r <= l || b <= t) return
        src.set(sl, st, sr, sb)
        dst.set(l, t, r, b)
        c.drawBitmap(m, src, dst, paint)
    }
}
