package app.thebox.player.datasource

import android.annotation.SuppressLint
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Live/segment `OkHttpClient` variants derived from the injected base client (`:data`'s unqualified
 * `HttpModule.baseClient()`, bound in `:app`'s graph), and the per-playlist "ignore TLS errors" variant.
 *
 * All variants share the base client's connection pool, DNS and interceptors (the default User-Agent interceptor only
 * fills a missing UA; resolved streams always carry one). They differ from the base in:
 * - timeouts: connect [LIVE_CONNECT_TIMEOUT_MS], read [LIVE_READ_TIMEOUT_MS] (architecture review B4.3), no call
 *   timeout (a live body never ends);
 * - redirects: followed by OkHttp, cross-protocol included (`followSslRedirects`); `OkHttpDataSource` has no redirect
 *   logic of its own;
 * - no HTTP cache;
 * - their own [Dispatcher]: `OkHttpDataSource` uses `enqueue`, and the base dispatcher's 5-per-host async limit is
 *   shared with imports from the same panel host.
 *
 * The relaxed (trust-all, any hostname) clients are separate `newBuilder()` clients, used only for sessions whose
 * `ResolvedStream.ignoreTlsErrors` is set (the playlist's own setting). OkHttp's `Address` includes the socket factory
 * and hostname verifier, so pooled connections are never shared between the two trust settings (review N7).
 */
@Singleton
internal class PlaybackHttpClients @Inject constructor(private val base: OkHttpClient) {
    private val dispatcher by lazy {
        Dispatcher().apply {
            maxRequests = MAX_REQUESTS
            maxRequestsPerHost = MAX_REQUESTS_PER_HOST
        }
    }
    private val liveStrict by lazy { build(relaxed = false) }
    private val liveRelaxed by lazy { build(relaxed = true) }
    private val segmentsStrict by lazy { build(relaxed = false) }
    private val segmentsRelaxed by lazy { build(relaxed = true) }

    fun live(ignoreTlsErrors: Boolean): OkHttpClient = if (ignoreTlsErrors) liveRelaxed else liveStrict
    fun segments(ignoreTlsErrors: Boolean): OkHttpClient = if (ignoreTlsErrors) segmentsRelaxed else segmentsStrict

    private fun build(relaxed: Boolean): OkHttpClient {
        val b = base.newBuilder()
            .dispatcher(dispatcher)
            .connectTimeout(LIVE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(LIVE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(LIVE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cache(null)
        if (relaxed) {
            val tm = TrustAll()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
            b.sslSocketFactory(ssl.socketFactory, tm).hostnameVerifier(AnyHostname)
        }
        return b.build()
    }

    /** The user's per-playlist "ignore TLS errors" opt-in (product §4.12): accepts any certificate. */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private class TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Pairs with [TrustAll]: only ever installed on the relaxed clients. */
    @SuppressLint("BadHostnameVerifier")
    private object AnyHostname : HostnameVerifier {
        override fun verify(hostname: String?, session: javax.net.ssl.SSLSession?): Boolean = true
    }

    companion object {
        const val LIVE_CONNECT_TIMEOUT_MS = 5_000L
        const val LIVE_READ_TIMEOUT_MS = 8_000L
        private const val MAX_REQUESTS = 64
        private const val MAX_REQUESTS_PER_HOST = 16
    }
}
