package app.thebox.image

import android.content.Context
import app.thebox.core.guide.DeviceClassSource
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * The app's Coil `ImageLoader` (docs/design/m3-guide.md §1.4, §3.7, §4.6, review S8; owner WP-C):
 * - memory cache capped at [MEMORY_CACHE_BYTES] (half in low-end mode). Guide logos bypass it (`LogoCache` requests
 *   with `memoryCachePolicy(DISABLED)` into its own byte-capped LRU), so this only holds the panel/mini-player images;
 * - disk cache [DISK_CACHE_BYTES] in `cacheDir/coil_images` (one instance per directory);
 * - the app's shared OkHttp client (connection pool, default user agent) through
 *   `OkHttpNetworkFetcherFactory(callFactory)` — signature verified in the coil-network-okhttp 3.6.3 sources;
 * - no cross-fade (TV, and the grid draws logos itself).
 */
@Module
@InstallIn(SingletonComponent::class)
object ImageModule {
    const val MEMORY_CACHE_BYTES = 2L * 1024 * 1024
    const val DISK_CACHE_BYTES = 64L * 1024 * 1024

    @Provides
    @Singleton
    fun imageLoader(@ApplicationContext context: Context, client: OkHttpClient, device: DeviceClassSource): ImageLoader {
        val memory = if (device.get().lowEnd) MEMORY_CACHE_BYTES / 2 else MEMORY_CACHE_BYTES
        return ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizeBytes(memory).build() }
            .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("coil_images")).maxSizeBytes(DISK_CACHE_BYTES).build() }
            // (Score bug, docs/design/m6-score-bug.md §3, A-N8) SVG team logos. ServiceLoader registers one too; the
            // duplicate is harmless and the explicit entry keeps the wiring visible.
            .components {
                add(OkHttpNetworkFetcherFactory(client))
                add(SvgDecoder.Factory())
            }
            .crossfade(false)
            .build()
    }
}
