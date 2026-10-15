package app.thebox.player

import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thebox.core.full.PickerChoice
import app.thebox.core.full.PickerKind
import app.thebox.core.full.QuickItem
import app.thebox.player.api.PlaybackState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-M4-19: in FULL with subtitles on: exactly one `SurfaceView`; no ancestor `View` with alpha < 1, a layer type or a
 * clip; the `SubtitleView` is drawn after the surface (a later sibling branch, never its ancestor) and never focused
 * (docs/design/m4-player-ux.md §3.6.3, N11, N15, §5.5; CLAUDE.md surface rule). Panel: `player_fixtures` (multi HLS).
 * Depends on WP-D (SubtitleLayer placement, focus flags) and WP-C (cues).
 */
@RunWith(AndroidJUnit4::class)
class PlayerSurfaceTest : PlayerTestBase() {

    private fun fullRectOutline(v: View): Boolean {
        val provider = v.outlineProvider ?: return true
        val outline = android.graphics.Outline()
        provider.getOutline(v, outline)
        if (outline.isEmpty) return true
        val r = android.graphics.Rect()
        if (!outline.getRect(r)) return false
        return outline.radius <= 0f && r.left <= 0 && r.top <= 0 && r.right >= v.width && r.bottom >= v.height
    }

    private fun ancestors(v: View): List<View> {
        val out = ArrayList<View>()
        var p = v.parent
        while (p is View) { out += p; p = p.parent }
        return out
    }

    @Test
    fun surfaceRuleWithSubtitles() {
        fullOn(fx.multiHls)
        awaitController("playing") { it is PlaybackState.Playing }
        runBlocking { withTimeoutOrNull(15_000) { controller.tracks.first { it.text.isNotEmpty() } } }
        openQuickBar()
        quickSelect(QuickItem.SUBTITLES)
        pickerSelect(PickerKind.SUBTITLES) { it is PickerChoice.Text && !it.track.speculative }
        runBlocking { withTimeoutOrNull(5_000) { controller.cues.first { it.isNotEmpty() } } }
        val surfaces = surfaceViews()
        val subs = subtitleViews()
        val problems = main {
            val out = mutableListOf<String>()
            if (surfaces.size != 1) out += "SurfaceViews in the window: ${surfaces.size}"
            val s = surfaces.firstOrNull() ?: return@main out
            val chain = ancestors(s)
            for (p in chain) {
                if (p.alpha < 1f) out += "${p.javaClass.simpleName} alpha=${p.alpha}"
                if (p.layerType != View.LAYER_TYPE_NONE) out += "${p.javaClass.simpleName} layerType=${p.layerType}"
                if (p.clipToOutline && !fullRectOutline(p)) out += "${p.javaClass.simpleName} clipToOutline"
                if (p.clipBounds != null) out += "${p.javaClass.simpleName} clipBounds=${p.clipBounds}"
            }
            if (subs.size != 1) out += "SubtitleViews: ${subs.size}" // WP-D behaviour
            subs.firstOrNull()?.let { sub ->
                if (sub in chain) out += "SubtitleView is an ancestor of the SurfaceView"
                if (sub.isFocused || sub.hasFocus()) out += "SubtitleView has focus" // WP-D behaviour (N15)
                if (sub.isFocusable) out += "SubtitleView is focusable" // WP-D behaviour (N15)
                // Drawing order: in the lowest common ancestor, the subtitle branch comes after the surface branch.
                val subChain = listOf(sub) + ancestors(sub)
                val surfChain = listOf<View>(s) + chain
                val common = subChain.firstOrNull { it in surfChain } as? ViewGroup
                if (common == null) {
                    out += "no common ancestor"
                } else {
                    val si = common.indexOfChild(subChain[subChain.indexOf(common) - 1])
                    val vi = common.indexOfChild(surfChain[surfChain.indexOf(common) - 1])
                    if (si <= vi) out += "SubtitleView branch ($si) is not after the SurfaceView branch ($vi) in ${common.javaClass.simpleName}" // WP-D behaviour
                }
            }
            out
        }
        log("I-M4-19 problems: $problems")
        assertTrue("surface rule with subtitles: $problems", problems.isEmpty())
        assertEquals(1, surfaceViews().size)
    }
}
