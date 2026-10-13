package app.thebox.data.net

import app.thebox.data.di.ApiClient
import app.thebox.data.di.DownloadClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * `@ApiClient` / `@DownloadClient` from one shared base client (shared pool and dispatcher), §3.7.6/§3.10.
 * API: connect 10 s, read 60 s. Download: connect 10 s, read 120 s. Redirects are followed. A default
 * User-Agent is added when the request has none (playlist UAs are set per request).
 * DoH hook point: M5 sets `.dns(DnsOverHttps…)` on the base client from a user setting.
 */
@Module
@InstallIn(SingletonComponent::class)
object HttpModule {
    const val DEFAULT_USER_AGENT = "TheBox/1.0 (Android TV)"

    val defaultUserAgent = Interceptor { chain ->
        val req = chain.request()
        chain.proceed(if (req.header("User-Agent") == null) req.newBuilder().header("User-Agent", DEFAULT_USER_AGENT).build() else req)
    }

    @Provides
    @Singleton
    fun baseClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(defaultUserAgent)
        .build()

    @Provides
    @Singleton
    @ApiClient
    fun apiClient(base: OkHttpClient): OkHttpClient = base.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

    @Provides
    @Singleton
    @DownloadClient
    fun downloadClient(base: OkHttpClient): OkHttpClient = base.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
}
