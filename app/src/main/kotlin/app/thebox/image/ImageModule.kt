package app.thebox.image

import android.content.Context
import coil3.ImageLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The app's Coil `ImageLoader` (docs/design/m3-guide.md §1.4, §3.7; owner WP-C). Commit-0 stub: a default loader.
 * WP-C sets the 2 MB / 1 MB memory cache, the 64 MB disk cache and the shared OkHttp client (S8).
 */
@Module
@InstallIn(SingletonComponent::class)
object ImageModule {
    @Provides @Singleton fun imageLoader(@ApplicationContext context: Context): ImageLoader = ImageLoader(context)
}
