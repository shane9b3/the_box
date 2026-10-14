package app.thebox.ui.guide.grid

import android.content.ComponentCallbacks2
import app.thebox.core.guide.GuideImages
import app.thebox.core.guide.GuideRenderer
import app.thebox.core.guide.render.GuideRendererImpl

/**
 * `onTrimMemory` for the grid caches (docs/design/m3-guide.md §4.7; review S8). `MainActivity` (WP-C) fans
 * `ComponentCallbacks2.onTrimMemory` out to this and the other owners.
 *
 * At `TRIM_MEMORY_RUNNING_LOW` (10) and above, which includes `TRIM_MEMORY_UI_HIDDEN` (20): clear the logo LRU
 * and the text-fit caches. The snapshot, the pager width cache, row blocks and ambient bitmaps belong to WP-B/WP-C.
 */
class GuideTrim(private val images: GuideImages?, private val renderer: GuideRenderer?) {
    var trims = 0
        private set

    fun onTrimMemory(level: Int) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        (images as? LogoCache)?.clear() ?: images?.trim()
        (renderer as? GuideRendererImpl)?.trimCaches()
        trims++
    }
}
