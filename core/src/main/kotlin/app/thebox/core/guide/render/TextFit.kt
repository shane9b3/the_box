package app.thebox.core.guide.render

import app.thebox.core.guide.GuideTextMeasurer
import app.thebox.core.guide.GuideTextStyle

/**
 * WP-A stub (docs/design/m3-guide.md §1.2, §3.6.4): ellipsis cache keyed by (content hash of the string, available px)
 * (review B4), backed off to a grapheme boundary, RTL-aware (S6).
 */
class TextFit(private val measurer: GuideTextMeasurer, private val slots: Int) {
    /** Chars of [text] to draw before the ellipsis in [availPx]; text.length when it fits. */
    fun fit(text: String, key: Long, style: GuideTextStyle, availPx: Float): Int = TODO("WP-A")
}
