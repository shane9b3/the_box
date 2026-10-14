package app.thebox.ui.guide.grid

import android.content.Context
import android.graphics.Canvas
import android.view.View
import app.thebox.core.guide.GuideFrame
import app.thebox.core.guide.GuideRenderer

/**
 * The View-host fallback (architecture review B2.9 step 3; docs/design/m3-guide.md §1.2): `View.onDraw` calling the
 * same renderer through the same [AndroidGuideCanvas]. Compiled and unit-tested, not wired. The caller sets
 * [frame] (and the animated [scrollOffsetMin]/[scrollRow]) and calls `invalidate()`; the view translates by its
 * position in the window because the renderer works in window px.
 */
class GuideGridView(context: Context, private val renderer: GuideRenderer) : View(context) {
    private val guideCanvas = AndroidGuideCanvas(context)
    private val location = IntArray(2)

    var frame: GuideFrame? = null
        set(value) { field = value; invalidate() }

    /** Animated offset from `frame.windowStartMin`, minutes. */
    var scrollOffsetMin: Float = 0f

    /** Animated top row; NaN = `frame.topRow`. */
    var scrollRow: Float = Float.NaN

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val f = frame ?: return
        val m = f.metrics ?: return
        guideCanvas.bind(canvas, m.scale)
        getLocationInWindow(location)
        val sv = canvas.save()
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())
        val row = if (scrollRow.isNaN()) f.topRow.toFloat() else scrollRow
        renderer.draw(guideCanvas, f, f.windowStartMin, scrollOffsetMin, row)
        canvas.restoreToCount(sv)
    }
}
