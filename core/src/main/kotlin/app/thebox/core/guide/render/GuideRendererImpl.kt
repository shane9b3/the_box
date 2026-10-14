package app.thebox.core.guide.render

import app.thebox.core.guide.GuideCanvas
import app.thebox.core.guide.GuideFrame
import app.thebox.core.guide.GuideRenderer
import app.thebox.core.guide.GuideTextMeasurer

/** WP-A stub (docs/design/m3-guide.md §1.2, §3.6, §4). */
class GuideRendererImpl(private val measurer: GuideTextMeasurer) : GuideRenderer {
    override fun draw(canvas: GuideCanvas, frame: GuideFrame, windowStartMin: Int, scrollOffsetMin: Float, scrollRow: Float): Unit = TODO("WP-A")
    override fun focusedCellBounds(frame: GuideFrame, windowStartMin: Int, scrollOffsetMin: Float, scrollRow: Float, out: FloatArray): Boolean = TODO("WP-A")
    override fun describeFocus(frame: GuideFrame): String = TODO("WP-A")
}
