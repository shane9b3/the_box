package app.thebox.ui.guide.grid

import android.graphics.Canvas
import app.thebox.core.guide.GuideAsset
import app.thebox.core.guide.GuideCanvas
import app.thebox.core.guide.GuideImage
import app.thebox.core.guide.GuideTextStyle

/** WP-A stub (docs/design/m3-guide.md §1.2): `GuideCanvas` over `android.graphics.Canvas`, preallocated Paints and RectF. */
class AndroidGuideCanvas : GuideCanvas {
    /** The canvas of the current draw pass (set by the host before `GuideRenderer.draw`). */
    var canvas: Canvas? = null

    override fun save(): Int = TODO("WP-A")
    override fun restoreToCount(count: Int): Unit = TODO("WP-A")
    override fun clipRect(l: Float, t: Float, r: Float, b: Float): Unit = TODO("WP-A")
    override fun fillRect(l: Float, t: Float, r: Float, b: Float, argb: Int): Unit = TODO("WP-A")
    override fun fillRoundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, argb: Int): Unit = TODO("WP-A")
    override fun strokeRoundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, width: Float, argb: Int): Unit = TODO("WP-A")
    override fun fillCircle(cx: Float, cy: Float, radius: Float, argb: Int): Unit = TODO("WP-A")
    override fun drawText(text: String, start: Int, end: Int, x: Float, baseline: Float, style: GuideTextStyle, argb: Int): Unit = TODO("WP-A")
    override fun drawImage(image: GuideImage, l: Float, t: Float, r: Float, b: Float, alpha: Float): Unit = TODO("WP-A")
    override fun drawAsset(asset: GuideAsset, l: Float, t: Float, r: Float, b: Float, argb: Int): Unit = TODO("WP-A")
}
