package app.thebox.core.guide.layout

import app.thebox.core.guide.GuideGeometry as G
import app.thebox.core.guide.GuideMetrics
import app.thebox.core.guide.GuideMetricsFactory
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * `GuideMetrics` factory (docs/design/m3-guide.md §3.1): design px → device px with
 * `s = min(w / 1920, h / 1080)` (the `BoxDesignScale` rule), every edge rounded to a whole device pixel.
 */
class GuideMetricsFactoryImpl : GuideMetricsFactory {
    override fun create(windowWidthPx: Int, windowHeightPx: Int): GuideMetrics {
        val byW = windowWidthPx / G.CANVAS_W
        val s = if (windowHeightPx > 0) min(byW, windowHeightPx / G.CANVAS_H) else byW
        return GuideMetricsImpl(s)
    }
}

/**
 * Pixel-snapped geometry at [scale] device px per design px (§3.1 table). Rows are snapped one by one relative to
 * [rowsTop] (`rowTop(i) = rowsTop + round(i × rowPitch)`, see [rowTopPx]), so the visible rows never drift; the timeline is
 * snapped at both ends and [pxPerMin] is derived from the snapped width so 180 minutes span it exactly.
 */
class GuideMetricsImpl(override val scale: Float) : GuideMetrics {
    private fun snap(designPx: Float): Float = (designPx * scale).roundToInt().toFloat()

    override val panelLeft = snap(G.PANEL_L)
    override val panelTop = snap(G.PANEL_T)
    override val panelRight = snap(G.PANEL_L + G.PANEL_W)
    override val panelBottom = snap(G.PANEL_T + G.PANEL_H)

    override val labelLeft = snap(G.PANEL_L + G.PANEL_BORDER + G.PANEL_PAD_X)
    override val timelineLeft = snap(G.PANEL_L + G.PANEL_BORDER + G.PANEL_PAD_X + G.LABEL_W)
    override val labelWidth = timelineLeft - labelLeft
    override val timelineWidth = snap(G.PANEL_L + G.PANEL_W - G.PANEL_BORDER - G.PANEL_PAD_X) - timelineLeft

    override val headerTop = snap(G.PANEL_T + G.PANEL_BORDER)
    override val headerBottom = snap(G.PANEL_T + G.PANEL_BORDER + G.HEADER_H)
    override val rowsTop = headerBottom
    override val rowPitch = G.ROW_PITCH * scale
    override val rowHeight = snap(G.ROW_H)
    /** Inner panel bottom (1023 at 1080p, decision 2026-11-09): §3.1 clips there, not at the padding, so row 5 (ending at 1015) is whole. */
    override val rowsBottom = snap(G.PANEL_T + G.PANEL_H - G.PANEL_BORDER)
    override val pxPerMin = timelineWidth / G.WINDOW_MIN

    override fun px(designPx: Float): Float = designPx * scale

    override fun xOf(min: Int, windowStartMin: Int, scrollOffsetMin: Float): Float =
        timelineLeft + ((min - windowStartMin) - scrollOffsetMin) * pxPerMin

    override fun holeRect(out: IntArray) {
        out[0] = (G.HOLE_L * scale).roundToInt()
        out[1] = (G.HOLE_T * scale).roundToInt()
        out[2] = ((G.HOLE_L + G.HOLE_W) * scale).roundToInt()
        out[3] = ((G.HOLE_T + G.HOLE_H) * scale).roundToInt()
    }
}

/**
 * Top of the row at visible position [rel] (lineup row − animated top row), pixel-snapped per row relative to
 * `rowsTop` so rounding never accumulates (§3.1). Works for any [GuideMetrics] (the fakes do not snap).
 */
fun GuideMetrics.rowTopPx(rel: Float): Float = rowsTop + (rel * rowPitch).roundToInt()
