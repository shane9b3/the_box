package app.thebox.ui.full

import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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
 *
 * U-B2 (V1, lead decision 7): captions are lifted by the view's **bottom padding** [liftPx] (device px), not by
 * `setBottomPaddingFraction`: Media3's `SubtitlePainter` applies the fraction only to cues whose line is unset, and
 * WebVTT "auto" lines and CEA-608 rows are `LINE_TYPE_NUMBER`, anchored to the box's bottom. `SubtitleView` is a
 * `FrameLayout`, so the padding shrinks the box every cue type is laid out in; the fraction is 0 so unset-line cues are
 * not lifted twice. DVB bitmap cues are scaled into the shorter box while a bar shows (accepted; M6 DVB check).
 *
 * U-S5: [cues] is read only in `update`, so a cue list (CEA-608 paint-on emits per character) re-runs `setCues` and
 * recomposes nothing.
 */
@OptIn(UnstableApi::class)
@Composable
fun SubtitleLayer(cues: State<List<Cue>>, videoRect: RectPx, liftPx: Int, modifier: Modifier = Modifier) {
    val rect = videoRect
    AndroidView(
        factory = { context -> newSubtitleView(context) },
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
        update = { view -> applySubtitleState(view, cues.value, liftPx) },
    )
}

/** The configured view (style, canvas output, no focus); shared by the layer and its placement test. */
@OptIn(UnstableApi::class)
fun newSubtitleView(context: android.content.Context): SubtitleView = SubtitleView(context).apply {
    setUserDefaultStyle()
    setUserDefaultTextSize()
    setApplyEmbeddedStyles(true)
    setViewType(SubtitleView.VIEW_TYPE_CANVAS)
    setBottomPaddingFraction(0f) // U-B2: the view padding lifts every cue type
    configureNoFocus(this)
}

/** U-B2: the lift as bottom padding (fraction 0), then the cues. Shared by the layer and its placement test. */
@OptIn(UnstableApi::class)
fun applySubtitleState(view: SubtitleView, cues: List<Cue>, liftPx: Int) {
    if (view.paddingBottom != liftPx) view.setPadding(0, 0, 0, liftPx)
    view.setCues(cues)
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
 * N5 as amended by U-B2 (lead decision 7): the bottom lift of the caption box in device px, `max(8 % of the video
 * height, the distance from the video's bottom to 16 design px above the highest open bar)`, capped at half the video
 * height. [barTopPx] is the bar's top in window px (null: no bar).
 */
fun subtitleLiftPx(videoRect: RectPx, barTopPx: Float?, scale: Float): Int {
    val h = videoRect.height
    if (h <= 0) return 0
    val base = PlayerUi.SUB_PAD_DEFAULT * h
    val overBar = if (barTopPx == null) 0f else videoRect.bottom - (barTopPx - PlayerUi.SUB_PAD_GAP * scale)
    return kotlin.math.ceil(maxOf(base, overBar).coerceAtMost(PlayerUi.SUB_PAD_MAX * h)).toInt()
}
