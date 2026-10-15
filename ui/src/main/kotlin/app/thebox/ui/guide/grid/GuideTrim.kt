package app.thebox.ui.guide.grid

import android.content.ComponentCallbacks2
import app.thebox.core.guide.GuideImages
import app.thebox.core.guide.GuideRenderer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `onTrimMemory` for the guide (docs/design/m3-guide.md §4.7; review S8; §2.13 item 6). `MainActivity` calls
 * [onTrimMemory]; this singleton clears what it owns and fans out to the registered per-screen owners.
 *
 * At `TRIM_MEMORY_RUNNING_LOW` (10) and above, which includes `TRIM_MEMORY_UI_HIDDEN` (20):
 * - the logo cache ([GuideImages.clear]) and the renderer's text-fit caches ([GuideRenderer.trimCaches]);
 * - every registered [Target] (the guide view model: row blocks outside `[top − 6, top + 12)`, the pager's width and
 *   detail caches, the non-current ambient bitmap and the hue cache; at UI_HIDDEN also the pager's snapshot).
 */
class GuideTrim(private val images: GuideImages?, private val renderer: GuideRenderer?) {
    /** A per-screen owner of guide caches. [uiHidden]: `level ≥ TRIM_MEMORY_UI_HIDDEN`. Main thread. */
    fun interface Target {
        fun trim(level: Int, uiHidden: Boolean)
    }

    private val targets = CopyOnWriteArrayList<Target>()

    var trims = 0
        private set

    /** Registers [target] until the returned handle is closed (the view model's `onCleared`). */
    fun register(target: Target): AutoCloseable {
        targets += target
        return AutoCloseable { targets -= target }
    }

    @Suppress("DEPRECATION") // the TRIM_MEMORY_* levels are still delivered on the API levels we ship to (25–35)
    fun onTrimMemory(level: Int) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        images?.clear()
        renderer?.trimCaches()
        val uiHidden = level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        for (t in targets) t.trim(level, uiHidden)
        trims++
    }
}
