package app.thebox.ui.guide.grid

import app.thebox.core.guide.GuideImage
import app.thebox.core.guide.GuideImages
import coil3.ImageLoader

/** WP-A stub (docs/design/m3-guide.md §1.2, §3.7): `GuideImages` over Coil 3 at the exact slot size, own LRU. */
class LogoCache(private val imageLoader: ImageLoader) : GuideImages {
    override fun get(url: String): GuideImage? = TODO("WP-A")
    override fun failed(url: String): Boolean = TODO("WP-A")
    override var onLoaded: (() -> Unit)?
        get() = TODO("WP-A")
        set(value) = TODO("WP-A")
    override fun trim(): Unit = TODO("WP-A")
}
