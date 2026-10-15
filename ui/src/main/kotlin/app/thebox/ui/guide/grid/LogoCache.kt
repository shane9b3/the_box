package app.thebox.ui.guide.grid

import android.content.Context
import android.graphics.Bitmap
import app.thebox.core.guide.GuideImage
import app.thebox.core.guide.GuideImages
import app.thebox.core.guide.layout.GridConstants
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * Channel-logo cache for the grid (docs/design/m3-guide.md §3.7, §4.4; review S8, N3). Main thread only.
 *
 * - [get] is non-blocking: a hit returns the bitmap and marks it most recently drawn; a miss starts **one** load per
 *   URL (in-flight loads are deduplicated, so a missing logo never launches a coroutine per frame) and returns null.
 * - Loads go through Coil 3 `ImageLoader.execute` at the exact slot size (96 × 40 design px × [scale]), `Scale.FIT`,
 *   `Precision.INEXACT`, with Coil's **memory cache disabled** (S8: this LRU is the cache, so the byte cap is real
 *   on API 25 where bitmaps live on the Java heap); Coil's disk cache stays. Hardware bitmaps stay allowed (they
 *   draw on the hardware canvas of both hosts and keep pixels off the heap on API 26+).
 * - Our LRU holds at most [GridConstants.LOGO_CACHE] entries and [GridConstants.LOGO_CACHE_BYTES] (48 /
 *   2 MB in low-end mode, [lowEnd]); failures are remembered for the session ([failed]).
 * - [onLoaded] fires on main after each successful load (the host bumps its image tick).
 */
class LogoCache internal constructor(
    private val fetch: suspend (url: String, widthPx: Int, heightPx: Int) -> Bitmap?,
    private val scope: CoroutineScope,
) : GuideImages {

    constructor(imageLoader: ImageLoader, context: Context) : this(coilFetch(imageLoader, context.applicationContext ?: context), mainScope())

    private class Entry(val image: BitmapGuideImage, val bytes: Int)

    private val lru = LinkedHashMap<String, Entry>(64, 0.75f, true)
    private val failedUrls = HashSet<String>()
    private val inFlight = HashSet<String>()
    private var bytes = 0L
    private var generation = 0

    /** Device px per design px; a change drops the cache (the slot size changed). */
    var scale: Float = 1f
        set(value) { if (value != field) { field = value; clear() } }

    /** Low-end caps (48 entries, 2 MB). */
    var lowEnd: Boolean = false
        set(value) { if (value != field) { field = value; trim() } }

    override var onLoaded: (() -> Unit)? = null

    val size: Int get() = lru.size
    /** Entry cap in force (§3.7: 160, 48 in low-end mode); read by I-16. */
    val capacity: Int get() = if (lowEnd) GridConstants.LOGO_CACHE_LOW_END else GridConstants.LOGO_CACHE
    val byteCount: Long get() = bytes

    override fun get(url: String): GuideImage? {
        val e = lru[url]
        if (e != null) return e.image
        if (url in failedUrls || url in inFlight) return null
        load(url)
        return null
    }

    override fun failed(url: String): Boolean = url in failedUrls

    override var version: Long = 0L
        private set

    override fun trim() {
        version++
        val maxEntries = if (lowEnd) GridConstants.LOGO_CACHE_LOW_END else GridConstants.LOGO_CACHE
        val maxBytes = if (lowEnd) GridConstants.LOGO_CACHE_BYTES_LOW_END else GridConstants.LOGO_CACHE_BYTES
        val it = lru.entries.iterator()
        while ((lru.size > maxEntries || bytes > maxBytes) && it.hasNext()) {
            val e = it.next()
            bytes -= e.value.bytes
            it.remove()
        }
    }

    /** Drops every bitmap and pending result (`onTrimMemory`, a scale change). Failures stay remembered. */
    override fun clear() {
        version++
        lru.clear()
        inFlight.clear()
        bytes = 0
        generation++
    }

    private fun load(url: String) {
        inFlight += url
        val gen = generation
        val w = (GridConstants.LOGO_W * scale).roundToInt().coerceAtLeast(1)
        val h = (GridConstants.LOGO_H * scale).roundToInt().coerceAtLeast(1)
        scope.launch {
            val bmp = try {
                fetch(url, w, h)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            if (gen != generation) return@launch
            inFlight -= url
            if (bmp == null) {
                failedUrls += url
                return@launch
            }
            val b = bmp.allocationByteCount
            lru[url] = Entry(BitmapGuideImage(bmp), b)
            bytes += b
            trim()
            version++
            onLoaded?.invoke()
        }
    }

    companion object {
        private fun mainScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        private fun coilFetch(loader: ImageLoader, context: Context): suspend (String, Int, Int) -> Bitmap? = { url, w, h ->
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(w, h)
                .scale(Scale.FIT)
                .precision(Precision.INEXACT)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .build()
            (loader.execute(request) as? SuccessResult)?.image?.toBitmap()
        }
    }
}
