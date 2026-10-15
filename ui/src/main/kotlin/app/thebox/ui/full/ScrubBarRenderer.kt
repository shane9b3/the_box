package app.thebox.ui.full

import android.graphics.Canvas
import app.thebox.core.full.ScrubState

/**
 * WP-D stub (docs/design/m4-player-ux.md §1.5, §3.5, §4.1): the scrub track as one draw node with a preallocated
 * `Paint`; no allocation per frame. [scale] is device px per design px.
 */
class ScrubBarRenderer(private val scale: Float) {
    fun draw(canvas: Canvas, state: ScrubState, nowMs: Long, widthPx: Float, heightPx: Float): Unit = TODO("WP-D")
}
