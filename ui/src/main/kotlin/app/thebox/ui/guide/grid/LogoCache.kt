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
import kotlinx.coroutines.Job
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
 * - Stale loads are cancelled (review M9): the host calls [tick] once per draw; a load whose URL no [get] has asked for
 *   in [STALE_FRAMES] draws is cancelled, so after a long D-pad hold the visible rows' logos do not queue behind rows
 *   long off screen. A cancelled URL is neither cached nor [failed]; the next [get] or [touch] starts it again. Rows that
 *   replay a recording (O-2) call [touch] instead of [get] (review 2026-11-10, S3), so a visible row's load is never
 *   cancelled and a cancel does not bump [version].
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
    private class Load(var lastFrame: Int) { var job: Job? = null }

    private val inFlight = HashMap<String, Load>()
    private var frame = 0
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
        if (url in failedUrls) return null
        val pending = inFlight[url]
        if (pending != null) { pending.lastFrame = frame; return null }
        load(url)
        return null
    }

    /**
     * Review 2026-11-10 S3: [get] without the result, for rows replayed from a recording. A hit marks the entry recently
     * drawn, a pending load stays alive (map lookup, no allocation), and a URL that is neither cached, failed nor in
     * flight starts its load, as [get] would: the row's recording can outlive a load cancelled while it was off screen.
     */
    override fun touch(url: String) {
        get(url)
    }

    override fun failed(url: String): Boolean = url in failedUrls

    override var version: Long = 0L
        private set

    override fun trim() {
        version++
        evict()
    }

    /**
     * Called once per draw by the host, before the renderer runs. No allocation unless a sweep cancels something:
     * the sweep itself runs every [SWEEP_EVERY] draws and only while loads are in flight.
     */
    fun tick() {
        frame++
        if (inFlight.isEmpty() || frame % SWEEP_EVERY != 0) return
        val it = inFlight.values.iterator()
        while (it.hasNext()) {
            val l = it.next()
            if (frame - l.lastFrame >= STALE_FRAMES) {
                it.remove()
                l.job?.cancel()
            }
        }
        // No version bump (review 2026-11-10, S3): visible rows call get or touch every draw, so only off-screen loads
        // are cancelled, and what get returns for a visible row did not change. Bumping it re-recorded every row.
    }

    private fun evict() {
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
        for (l in inFlight.values) l.job?.cancel()
        inFlight.clear()
        bytes = 0
        generation++
    }

    private fun load(url: String) {
        val rec = Load(frame)
        inFlight[url] = rec
        val gen = generation
        val w = (GridConstants.LOGO_W * scale).roundToInt().coerceAtLeast(1)
        val h = (GridConstants.LOGO_H * scale).roundToInt().coerceAtLeast(1)
        rec.job = scope.launch {
            val bmp = try {
                fetch(url, w, h)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            if (gen != generation || inFlight[url] !== rec) return@launch
            inFlight.remove(url)
            if (bmp == null) {
                failedUrls += url
                return@launch
            }
            val b = bmp.allocationByteCount
            lru[url] = Entry(BitmapGuideImage(bmp), b)
            bytes += b
            evict()
            version++   // once per arrival (review M9): the frame key hashes it, so each bump is a full invalidation
            onLoaded?.invoke()
        }
    }

    companion object {
        /** Draws without a [get] before an in-flight load is cancelled: about 1 s at 60 fps, so a scroll-past-and-back is not thrashed. */
        internal const val STALE_FRAMES = 60
        private const val SWEEP_EVERY = 8

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
