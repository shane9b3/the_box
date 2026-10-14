package app.thebox.ui.guide.grid

import android.content.Context
import app.thebox.core.guide.GuideTextMeasurer
import app.thebox.core.guide.GuideTextStyle

/** WP-A stub (docs/design/m3-guide.md §1.2, §4.3): Paint + bundled typefaces; one instance per thread. */
class AndroidTextMeasurer(private val context: Context, override val scale: Float) : GuideTextMeasurer {
    override fun width(text: String, start: Int, end: Int, style: GuideTextStyle): Float = TODO("WP-A")
    override fun fit(text: String, start: Int, end: Int, style: GuideTextStyle, maxWidth: Float): Int = TODO("WP-A")
    override fun ascent(style: GuideTextStyle): Float = TODO("WP-A")
    override fun descent(style: GuideTextStyle): Float = TODO("WP-A")
}
