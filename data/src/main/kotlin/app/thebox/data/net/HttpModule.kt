package app.thebox.data.net

import app.thebox.data.di.ApiClient
import app.thebox.data.di.DownloadClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import app.thebox.core.net.SecureDnsNotices
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * `@ApiClient` / `@DownloadClient` from one shared base client (shared pool and dispatcher), §3.7.6/§3.10.
 * API: connect 10 s, read 60 s. Download: connect 10 s, read 120 s. Redirects are followed. A default
 * User-Agent is added when the request has none (playlist UAs are set per request).
 * (M5, docs/design/m5-setup-settings.md §3.13, N8) The base client carries [BoxDns] (system or secure DNS, read at
 * lookup time) and its IPv6 connect-failure counter; every client derived from it (API, download, and
 * `PlaybackHttpClients`' live/segment clients through `base.newBuilder()`) inherits both. The Network page's connect and
 * read timeouts apply through an interceptor on the API and download clients only (the live clients keep theirs, M2
 * B4.3); the download client never reads faster than [DOWNLOAD_READ_FLOOR_S] (large XMLTV/catalog bodies).
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
    fun baseClient(dns: BoxDns): OkHttpClient = OkHttpClient.Builder()
        .dns(dns)
        .eventListenerFactory(dns.eventListenerFactory)
        .connectTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(defaultUserAgent)
        .build()

    @Provides
    @Singleton
    @ApiClient
    fun apiClient(base: OkHttpClient, dns: BoxDns): OkHttpClient =
        base.newBuilder().readTimeout(60, TimeUnit.SECONDS).addInterceptor(dns.timeoutsInterceptor()).build()

    @Provides
    @Singleton
    @DownloadClient
    fun downloadClient(base: OkHttpClient, dns: BoxDns): OkHttpClient =
        base.newBuilder().readTimeout(120, TimeUnit.SECONDS).addInterceptor(dns.timeoutsInterceptor(DOWNLOAD_READ_FLOOR_S)).build()

    /** Plain variants (no DNS policy, no settings timeouts) for tests and `XtreamClient`'s secondary constructor. */
    fun baseClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(defaultUserAgent)
        .build()

    fun apiClient(base: OkHttpClient): OkHttpClient = base.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

    fun downloadClient(base: OkHttpClient): OkHttpClient = base.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()

    /** (M5, §3.13) The UI collects it for the one-time "Secure DNS is not reachable" toast. */
    @Provides
    fun secureDnsNotices(dns: BoxDns): SecureDnsNotices = dns

    /** The download client's read-timeout floor (it had a fixed 120 s before M5). */
    const val DOWNLOAD_READ_FLOOR_S = 120
}
