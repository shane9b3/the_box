package app.thebox.ui.root

import app.thebox.core.guide.GuideGeometry
import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.RectPx
import app.thebox.core.playback.VideoRectCalculator
import app.thebox.core.playback.surface.VideoRectCalculatorImpl
import app.thebox.ui.theme.designPxToPx
import kotlin.math.roundToInt

/** Where the video goes (docs/design/m3-guide.md §3.13.2). */
enum class HoleMode {
    /** GUIDE with live video in the mini player: viewport MINI. */
    MINI,
    /** GUIDE showing a still (Still setting, low memory, HDR poster, nothing tuned): viewport HIDDEN, video track off. */
    STILL,
    /** FULL: the hole is the whole window. */
    FULL,
    /** SEARCH / SETTINGS cover the hole: viewport HIDDEN. */
    COVERED,
}

/**
 * [hole] is the mini-player (or full-window) rect the chrome draws around, null when covered. [video] is the
 * [VideoLayer] rect inside it (letterboxed), null when the surface parks at 1 × 1 (viewport HIDDEN).
 */
data class HoleRects(val hole: RectPx?, val video: RectPx?, val cropToFill: Boolean = false)

/**
 * Hole and video rects in window px (docs/design/m3-guide.md §3.13.2). Pure; the root composable owns one.
 *
 * The MINI hole comes from the layout: `GuideScreen` places a transparent 720 × 405 design-px slot at (64, 56) and
 * publishes its `boundsInWindow(clipBounds = false)` here through [publishMeasured], which dedupes so a layout →
 * state → layout loop cannot start. Until the first measurement (and in FULL) the rect is computed from the window
 * size with the same rounding as `GuideMetrics.holeRect` (§3.1): `round(64 s), round(56 s), round(784 s), round(461 s)`.
 */
class HoleGeometry(private val calculator: VideoRectCalculator = VideoRectCalculatorImpl()) {
    var windowWidthPx: Int = 0
        private set
    var windowHeightPx: Int = 0
        private set

    /** Last published layout rect of the mini hole, or null before the first layout pass. */
    var measuredHole: RectPx? = null
        private set

    fun update(windowWidthPx: Int, windowHeightPx: Int) {
        if (windowWidthPx == this.windowWidthPx && windowHeightPx == this.windowHeightPx) return
        this.windowWidthPx = windowWidthPx
        this.windowHeightPx = windowHeightPx
        measuredHole = null // a new window size invalidates the old layout rect
    }

    /** Publishes the layout rect of the hole slot; true when it changed (callers update state only then). */
    fun publishMeasured(rect: RectPx): Boolean {
        if (rect.width <= 0 || rect.height <= 0 || rect == measuredHole) return false
        measuredHole = rect
        return true
    }

    /** The mini hole: the measured rect when there is one, else the design rect for this window. */
    val miniHole: RectPx get() = measuredHole ?: designHole(windowWidthPx, windowHeightPx)

    val fullWindow: RectPx get() = RectPx(0, 0, windowWidthPx, windowHeightPx)

    fun rects(mode: HoleMode, videoWidth: Int, videoHeight: Int, pixelRatio: Float, aspect: AspectMode): HoleRects = when (mode) {
        HoleMode.MINI -> place(miniHole, videoWidth, videoHeight, pixelRatio, aspect)
        HoleMode.FULL -> place(fullWindow, videoWidth, videoHeight, pixelRatio, aspect)
        HoleMode.STILL -> HoleRects(miniHole, null)
        HoleMode.COVERED -> HoleRects(null, null)
    }

    private fun place(hole: RectPx, w: Int, h: Int, par: Float, aspect: AspectMode): HoleRects {
        if (hole.width <= 0 || hole.height <= 0) return HoleRects(hole, null)
        val p = calculator.place(hole, w, h, par, aspect)
        return HoleRects(hole, p.videoRect, p.cropToFill)
    }

    companion object {
        /** §3.1: the hole rect for a window, rounded to whole device px. */
        fun designHole(windowWidthPx: Int, windowHeightPx: Int): RectPx {
            val s = designPxToPx(windowWidthPx.toFloat(), windowHeightPx.toFloat())
            return RectPx(
                (GuideGeometry.HOLE_L * s).roundToInt(),
                (GuideGeometry.HOLE_T * s).roundToInt(),
                ((GuideGeometry.HOLE_L + GuideGeometry.HOLE_W) * s).roundToInt(),
                ((GuideGeometry.HOLE_T + GuideGeometry.HOLE_H) * s).roundToInt(),
            )
        }

        /** Rounds a layout rect (window px, floats) to whole pixels. */
        fun round(left: Float, top: Float, right: Float, bottom: Float): RectPx =
            RectPx(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())
    }
}
