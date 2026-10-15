package app.thebox.ui.guide.grid

import android.content.Context
import android.os.Trace
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import app.thebox.core.guide.GuideFrame
import app.thebox.core.guide.GuideRenderer
import app.thebox.core.guide.GuideStateMachine
import app.thebox.core.guide.layout.GridConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The one draw node of the guide grid (docs/design/m3-guide.md §0.2, §3.6.5; review B1, B8, N4).
 *
 * - A `Spacer` the caller sizes and places at the grid panel rect (N4). The renderer works in window px, so the draw
 *   translates by −(position in window).
 * - `Modifier.graphicsLayer()` with **default parameters** (no alpha, clip, shape or effect): a plain RenderNode, so
 *   a grid redraw re-records only this node (B8). It is a sibling of `VideoLayer`, never an ancestor.
 * - `drawWithCache { onDrawBehind { … } }`: `frameVersion`, the scroll state and the logo tick are read **only**
 *   inside `onDrawBehind`, so focus moves and scroll frames invalidate the draw only, with no recomposition.
 * - Scroll: the window start is an Int base plus a small animated `Float` offset (§2.12 item 3). On a new target
 *   the offset is rebased (`offset += oldBase − newBase`) and animated to 0 over `SCROLL_ANIM_MS` (ease-out);
 *   rows likewise. It snaps when `frame.animate` is false (the state machine sets it from the navigator's
 *   `Moved.animate`, §2.13 item 2) and always in low-end mode.
 * - No focus or key modifiers (B1): keys arrive through the root key sink.
 * - Accessibility (§3.6.6): `contentDescription` = `renderer.describeFocus` 300 ms after the last change, polite
 *   live region, role Button; computed only while a touch-exploration service (TalkBack) is on.
 * - A `guide-draw` trace section wraps each renderer pass (§4.1 budget, I-18 layer check).
 */
@Composable
fun GuideGridHost(machine: GuideStateMachine, renderer: GuideRenderer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val frame = machine.frame
    val canvas = remember { AndroidGuideCanvas(context) }
    val scroll = remember { GridScrollState(frame.windowStartMin, frame.topRow.toFloat()) }
    val version = machine.frameVersion.collectAsState()
    val imageTick = remember { mutableIntStateOf(0) }
    val originX = remember { mutableFloatStateOf(0f) }
    val originY = remember { mutableFloatStateOf(0f) }

    // Logo arrivals bump the tick. `frame.images` is plain (non-state) data the state machine may set after the
    // first composition, so the callback is (re)attached from the draw lambda, by reference, without allocating.
    val onLogo: () -> Unit = remember { { imageTick.intValue++ } }
    DisposableEffect(frame) {
        onDispose { frame.images?.let { if (it.onLoaded === onLogo) it.onLoaded = null } }
    }
    LaunchedEffect(machine) {
        val scope: CoroutineScope = this
        machine.frameVersion.collect { scroll.retarget(frame, scope) }
    }

    if (app.thebox.ui.guide.GuideDebugCounters.ENABLED) androidx.compose.runtime.SideEffect { app.thebox.ui.guide.GuideDebugCounters.bump(COMPOSITIONS_GRID) } // I-12
    val a11y = remember { touchExplorationOn(context) }
    var description by remember { mutableStateOf("") }
    if (a11y) {
        LaunchedEffect(machine, renderer) {
            machine.frameVersion.collectLatest {
                delay(GridConstants.A11Y_DEBOUNCE_MS)
                description = renderer.describeFocus(frame)
            }
        }
    }

    Spacer(
        modifier
            .onGloballyPositioned { c ->
                val p = c.positionInWindow()
                if (p.x != originX.floatValue) originX.floatValue = p.x
                if (p.y != originY.floatValue) originY.floatValue = p.y
            }
            .graphicsLayer()
            .semantics {
                if (a11y) contentDescription = description
                liveRegion = LiveRegionMode.Polite
                role = Role.Button
            }
            .drawWithCache {
                onDrawBehind {
                    version.value              // state read in draw: invalidates the draw only
                    imageTick.intValue         // logo arrivals
                    val m = frame.metrics ?: return@onDrawBehind
                    val images = frame.images
                    if (images != null && images.onLoaded !== onLogo) images.onLoaded = onLogo
                    (images as? LogoCache)?.let { it.scale = m.scale; it.lowEnd = frame.lowEnd }
                    val ox = originX.floatValue
                    val oy = originY.floatValue
                    val base = scroll.baseWs
                    val off = scroll.offset
                    val row = scroll.row
                    drawIntoCanvas { c ->
                        val native = c.nativeCanvas
                        canvas.bind(native, m.scale)
                        val sv = native.save()
                        native.translate(-ox, -oy)
                        Trace.beginSection(TRACE_SECTION)
                        try {
                            renderer.draw(canvas, frame, base, off, row)
                        } finally {
                            Trace.endSection()
                            native.restoreToCount(sv)
                        }
                    }
                }
            },
    )
}

/** Debug composition counter key (I-12, §2.13 item 20). */
const val COMPOSITIONS_GRID = "GuideGridHost"

/** Trace section around the renderer pass (`guide-draw`, §4.1). */
const val TRACE_SECTION = "guide-draw"

private fun touchExplorationOn(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    return am.isEnabled && am.isTouchExplorationEnabled
}

/**
 * Animated scroll position of the grid: [baseWs] (Int epoch minutes) + [offset] (small Float minutes) and [row].
 * Snapshot state, read only in the draw lambda.
 */
@Stable
internal class GridScrollState(base: Int, row: Float) {
    var baseWs by mutableIntStateOf(base)
        private set
    var offset by mutableFloatStateOf(0f)
        private set
    var row by mutableFloatStateOf(row)
        private set

    private var targetRow = row
    private var offsetJob: Job? = null
    private var rowJob: Job? = null
    fun retarget(frame: GuideFrame, scope: CoroutineScope) {
        val newWs = frame.windowStartMin
        val newRow = frame.topRow.toFloat()
        val wsChanged = newWs != baseWs
        val rowChanged = newRow != targetRow
        if (!wsChanged && !rowChanged) return
        val snapAll = frame.lowEnd || !frame.animate
        if (wsChanged) {
            val delta = baseWs - newWs
            offsetJob?.cancel()
            baseWs = newWs
            if (snapAll) {
                offset = 0f
            } else {
                offset += delta
                val from = offset
                offsetJob = scope.launch { animate(from, 0f, animationSpec = tween(GridConstants.SCROLL_ANIM_MS, easing = EASE_OUT)) { v, _ -> offset = v } }
            }
        }
        if (rowChanged) {
            targetRow = newRow
            rowJob?.cancel()
            if (snapAll) {
                row = newRow
            } else {
                val from = row
                rowJob = scope.launch { animate(from, newRow, animationSpec = tween(GridConstants.SCROLL_ANIM_MS, easing = EASE_OUT)) { v, _ -> row = v } }
            }
        }
    }

    private companion object {
        /** CSS `ease-out`. */
        val EASE_OUT = CubicBezierEasing(0f, 0f, 0.58f, 1f)
    }
}
