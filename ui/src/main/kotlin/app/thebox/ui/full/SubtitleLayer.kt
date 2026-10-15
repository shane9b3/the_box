package app.thebox.ui.full

import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import app.thebox.core.playback.RectPx

const val SUBTITLE_LAYER_TAG = "subtitle_layer"

/**
 * Subtitles (docs/design/m4-player-ux.md §0.9, §3.6.3): Media3's `SubtitleView` (it lays out DVB bitmap cues and
 * CEA-608/708 windows) in an `AndroidView`, sized and placed to [videoRect] in **window px** exactly as `VideoLayer`
 * places the surface (a `layout` modifier, no dp conversion), which is why it must be a **direct child of the root
 * `Box`** (N11, M2 E-L18). It sits after `HoleChrome` and before the key sink, never an ancestor of `VideoLayer`, and
 * carries no `graphicsLayer`, alpha or clip of its own. FULL only (the root composes it only while the displayed mode
 * is FULL and the screen is not off).
 *
 * N15: a classic `View` must never take focus from the key sink, so the factory sets `isFocusable = false`,
 * `FOCUS_BLOCK_DESCENDANTS` and `IMPORTANT_FOR_ACCESSIBILITY_NO`; the view is not clickable either. Style: the system
 * captioning style (`setUserDefaultStyle`/`setUserDefaultTextSize`), embedded styles applied, canvas rendering.
 * [bottomPaddingFraction] lifts captions over a visible bar (N5); `setCues` runs in `update`, on main.
 */
@OptIn(UnstableApi::class)
@Composable
fun SubtitleLayer(cues: List<Cue>, videoRect: RectPx, bottomPaddingFraction: Float, modifier: Modifier = Modifier) {
    val rect = videoRect
    AndroidView(
        factory = { context ->
            SubtitleView(context).apply {
                setUserDefaultStyle()
                setUserDefaultTextSize()
                setApplyEmbeddedStyles(true)
                setViewType(SubtitleView.VIEW_TYPE_CANVAS)
                configureNoFocus(this)
            }
        },
        modifier = modifier
            .testTag(SUBTITLE_LAYER_TAG)
            .layout { measurable, constraints ->
                val w = rect.width.coerceAtLeast(1)
                val h = rect.height.coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                val outerW = if (constraints.hasBoundedWidth) constraints.maxWidth else rect.right
                val outerH = if (constraints.hasBoundedHeight) constraints.maxHeight else rect.bottom
                layout(outerW, outerH) { placeable.place(rect.left, rect.top) }
            },
        update = { view ->
            view.setBottomPaddingFraction(bottomPaddingFraction)
            view.setCues(cues)
        },
    )
}

/** N15: never focusable, blocks its descendants, hidden from accessibility, not clickable. */
fun configureNoFocus(view: View) {
    view.isFocusable = false
    view.isFocusableInTouchMode = false
    view.isClickable = false
    view.isLongClickable = false
    view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    if (view is ViewGroup) view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
}

/**
 * N5: the bottom padding that keeps captions above a bar whose top is [barTopPx] (device px), as a fraction of the
 * video height: `((videoRect.bottom − barTop + 16·scale) / videoRect.height).coerceIn(0.08, 0.5)`; 0.08 without a bar.
 */
fun subtitleBottomPadding(videoRect: RectPx, barTopPx: Float?, scale: Float): Float {
    if (barTopPx == null || videoRect.height <= 0) return PlayerUi.SUB_PAD_DEFAULT
    val f = (videoRect.bottom - barTopPx + PlayerUi.SUB_PAD_GAP * scale) / videoRect.height
    return f.coerceIn(PlayerUi.SUB_PAD_DEFAULT, PlayerUi.SUB_PAD_MAX)
}
