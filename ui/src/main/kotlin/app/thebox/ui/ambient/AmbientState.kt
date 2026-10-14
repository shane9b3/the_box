package app.thebox.ui.ambient

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The ambient field shared by [AmbientBackground] and the hole corners in `HoleChrome` (docs/design/m3-guide.md §3.14,
 * N9: both apply the same cross-fade so the corners never pop). [targetHue] is set by the view model (tuned channel's
 * logo hue, or the stable-key hash fallback); [AmbientBackground] renders and cross-fades it in its own effect so the
 * animation runs on the composition's frame clock. Holds at most two bitmaps (current and fading-out previous).
 */
@Stable
class AmbientState(
    private val renderer: AmbientRenderer = AmbientRenderer(),
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Hue to show (degrees); null = nothing tuned yet ([DEFAULT_HUE]). */
    var targetHue: Float? by mutableStateOf(null)

    /** Low-end mode or "Ambient" off: a flat #0A0D13 fill, no bitmaps (§0.11). */
    var flat: Boolean by mutableStateOf(false)

    var current: ImageBitmap? by mutableStateOf(null)
        private set
    var previous: ImageBitmap? by mutableStateOf(null)
        private set
    var shadow: ImageBitmap? by mutableStateOf(null)
        private set

    private val fadeAnim = Animatable(1f)

    /** Cross-fade progress of [current] over [previous], 0..1. Read it in the draw phase only. */
    val fade: Float get() = fadeAnim.value

    private var shownKey = Int.MIN_VALUE

    /** Renders [hue] off-main (if it changed) and cross-fades to it over [AMBIENT_FADE_MS]. Call from a composition effect. */
    suspend fun show(hue: Float) {
        if (shadow == null) shadow = withContext(renderDispatcher) { renderer.renderShadow().asImageBitmap() }
        val key = Math.floorMod(hue.roundToInt(), 360)
        if (key == shownKey && current != null) return
        val bmp = withContext(renderDispatcher) { renderer.render(key.toFloat()).asImageBitmap() }
        shownKey = key
        val first = current == null
        previous = current
        current = bmp
        if (first) {
            fadeAnim.snapTo(1f)
        } else {
            fadeAnim.snapTo(0f)
            fadeAnim.animateTo(1f, tween(AMBIENT_FADE_MS, easing = LinearEasing))
        }
        previous = null
    }

    /** onTrimMemory (§4.7): drop the non-current bitmap. The hue cache lives in [AmbientHueSource]. */
    fun trim() {
        previous = null
    }

    companion object {
        const val AMBIENT_FADE_MS = 1_200
        /** Accent hue (oklch … 240) while nothing is tuned. */
        const val DEFAULT_HUE = 240f
    }
}
