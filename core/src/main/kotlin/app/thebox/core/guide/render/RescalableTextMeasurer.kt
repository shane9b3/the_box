package app.thebox.core.guide.render

import app.thebox.core.guide.GuideTextMeasurer

/**
 * A [GuideTextMeasurer] whose scale can follow the window (WP-A local). The contract's `scale` is a `val`, and the
 * renderer is built once (Hilt) before any window size is known, so [GuideRendererImpl] rescales a measurer that
 * implements this when `frame.metrics.scale` changes. A measurer that does not is still correct: the renderer
 * multiplies its widths by `metrics.scale / measurer.scale`.
 */
interface RescalableTextMeasurer : GuideTextMeasurer {
    fun rescale(scale: Float)
}
