package app.thebox.ui.ambient

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import app.thebox.core.guide.GuideChannel
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tuned channel → ambient hue (docs/design/m3-guide.md §3.14): the logo loaded once at 16 × 16 with
 * `allowHardware(false)`, [HueExtractor] on `Dispatchers.Default`, else the stable-key hash. Cached in an LRU of
 * [HUE_CACHE] by identity hash; not persisted (§7 Q6). The logo request bypasses Coil's memory cache (S8).
 */
class AmbientHueSource(private val context: Context, private val loader: ImageLoader?) {
    private val cache = LruCache<Long, Float>(HUE_CACHE)

    suspend fun hue(channel: GuideChannel): Float {
        cache[channel.identityHash]?.let { return it }
        val fromLogo = channel.logoUrl?.takeIf { it.isNotBlank() }?.let { url -> logoHue(url) }
        val h = fromLogo ?: HueExtractor.fallbackHue(channel.identityHash)
        cache.put(channel.identityHash, h)
        return h
    }

    private suspend fun logoHue(url: String): Float? {
        val l = loader ?: return null
        return try {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(HueExtractor.SAMPLE, HueExtractor.SAMPLE)
                .allowHardware(false)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .build()
            val result = l.execute(request) as? SuccessResult ?: return null
            withContext(Dispatchers.Default) {
                val bmp: Bitmap = result.image.toBitmap(HueExtractor.SAMPLE, HueExtractor.SAMPLE)
                HueExtractor.hue(bmp)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** onTrimMemory (§4.7). */
    fun trim() = cache.evictAll()

    companion object {
        const val HUE_CACHE = 64
    }
}
