package app.thebox.ui.sports

import android.graphics.Canvas
import android.graphics.Paint

/**
 * (Stacked §6, B4; Stacked WP-2) The Stacked box's drop shadow: one `ALPHA_8` mask per size, baked on a software canvas
 * with `BlurMaskFilter` (σ `STACKED_SHADOW_SIGMA`, offset `STACKED_SHADOW_DY`, radius `StackedMock.BOX_RADIUS` × scale)
 * from a source square of `STACKED_SHADOW_SRC` (Large `STACKED_SHADOW_SRC_LARGE`) design px, ≥ 6σ + 1, so every slice line
 * lies ≥ 3σ inside the shape and the stretched edge profile is exact. Drawn as 8 `drawBitmap(src, dst)` calls stretched
 * to the current box rect, the centre skipped (the box fill covers it, N7); it follows the box through every layout
 * transition. No real-time blur.
 *
 * Built by [StackedPainter.bakeShadow] off the main thread; only the painters read its insides, so its fields are WP-2's.
 * The runner (Stacked WP-3) only holds it and hands it back in [StackedDrawArgs.shadow].
 *
 * **Commit-0 stub:** no mask; [draw] draws nothing.
 */
internal class NineSlice {
    /**
     * The shadow for the box rect ([l], [t], [r], [b], device px, the box's own shape, the shadow's offset applied here),
     * tinted black at [paint]'s alpha. Allocation-free.
     */
    @Suppress("UNUSED_PARAMETER")
    fun draw(c: Canvas, l: Float, t: Float, r: Float, b: Float, paint: Paint) = Unit
}
