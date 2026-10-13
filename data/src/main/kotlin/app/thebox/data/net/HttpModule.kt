package app.thebox.data.net

import app.thebox.data.di.ApiClient
import app.thebox.data.di.DownloadClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * `@ApiClient` / `@DownloadClient` from one shared base client (shared pool and dispatcher), §3.10.
 * Commit-0 wiring only (no timeout profiles yet); WP-D owns this file.
 */
@Module
@InstallIn(SingletonComponent::class)
object HttpModule {
    @Provides
    @Singleton
    fun baseClient(): OkHttpClient = OkHttpClient()

    @Provides
    @Singleton
    @ApiClient
    fun apiClient(base: OkHttpClient): OkHttpClient = base.newBuilder().build()

    @Provides
    @Singleton
    @DownloadClient
    fun downloadClient(base: OkHttpClient): OkHttpClient = base.newBuilder().build()
}
