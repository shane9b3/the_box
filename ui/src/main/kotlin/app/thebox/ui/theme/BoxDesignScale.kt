package app.thebox.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import kotlin.math.min

/** Width of the design canvas in design px (docs/01-design-reference.md). */
const val DESIGN_CANVAS_WIDTH_PX = 1920f

/** Height of the design canvas in design px. */
const val DESIGN_CANVAS_HEIGHT_PX = 1080f

/**
 * The root design density, carried separately from [LocalDensity] because Popup and Dialog
 * windows re-provide [LocalDensity] from their own window (and their own, smaller container).
 * Content inside a popup/dialog must be wrapped in [BoxDesignScale] again; it then reuses this
 * value instead of re-measuring against the popup window.
 */
private val LocalBoxDesignDensity = staticCompositionLocalOf<Density?> { null }

/**
 * The design density rule: inside this composable **1.dp == 1.sp == 1 design px**, where one
 * design px is `windowWidthPx / 1920` on a 16:9 window. Android TV runs a 1080p UI at xhdpi
 * (960×540 dp), so design px are not dp; every measurement from the design doc is written
 * directly as `.dp` (and text sizes as `.sp`) under this scale. `fontScale` is pinned to 1 so the
 * system font-size setting cannot break the fixed-canvas layout.
 *
 * On a non-16:9 window (phones, ultrawide) the 1920×1080 canvas is fitted inside the window
 * (`min(w / 1920, h / 1080)`), so the canvas never overflows vertically. On every 16:9 TV this
 * is identical to `width / 1920`.
 *
 * Wrap the whole app content in this once, at the root, and again inside each Popup/Dialog.
 */
@Composable
fun BoxDesignScale(content: @Composable () -> Unit) {
    val designDensity = LocalBoxDesignDensity.current ?: rememberRootDesignDensity()
    CompositionLocalProvider(
        LocalDensity provides designDensity,
        LocalBoxDesignDensity provides designDensity,
        content = content,
    )
}

@Composable
private fun rememberRootDesignDensity(): Density {
    val container = LocalWindowInfo.current.containerSize
    val configuration = LocalConfiguration.current
    val systemDensity = LocalDensity.current.density
    // Before the window is attached the container size can be 0; fall back to configuration.
    val widthPx = if (container.width > 0) container.width.toFloat() else configuration.screenWidthDp * systemDensity
    val heightPx = if (container.height > 0) container.height.toFloat() else configuration.screenHeightDp * systemDensity
    val scale = designPxToPx(widthPx, heightPx)
    return remember(scale) { Density(density = scale, fontScale = 1f) }
}

/** Screen pixels per design px for a window [windowWidthPx] × [windowHeightPx] (fit inside). */
fun designPxToPx(windowWidthPx: Float, windowHeightPx: Float = 0f): Float {
    val byWidth = windowWidthPx / DESIGN_CANVAS_WIDTH_PX
    return if (windowHeightPx > 0f) min(byWidth, windowHeightPx / DESIGN_CANVAS_HEIGHT_PX) else byWidth
}
