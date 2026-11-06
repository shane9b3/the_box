package app.thebox.data.diag

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.thebox.core.diag.ConnectResult
import app.thebox.core.diag.HttpOutcome
import app.thebox.core.diag.HttpProbe
import app.thebox.core.diag.LookupResult
import app.thebox.core.diag.NetworkProbes
import app.thebox.core.diag.StreamSample
import app.thebox.core.diag.StreamSampler
import app.thebox.core.diag.TestTarget
import app.thebox.core.net.DnsChoice
import app.thebox.core.net.NetworkSettings
import app.thebox.core.settings.Domains
import app.thebox.core.settings.SettingsDomain
import app.thebox.core.settings.SettingsStore
import app.thebox.core.xtream.XtreamUrls
import app.thebox.data.di.ApiClient
import app.thebox.data.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.net.ssl.SSLException

/**
 * The platform side of the connection test (docs/design/m5-setup-settings.md §3.11.2). Every call runs on IO and
 * catches its own failures (the runner treats an exception as a failed step anyway). Nothing here logs a URL.
 *
 * - DNS (system): `Dns.SYSTEM`. DNS (secure): Cloudflare DoH with bootstrap IPs (§3.13), skipped when the user's DNS is
 *   already secure.
 * - Connect: a plain TCP socket per address family, [connect]'s timeout.
 * - Server: Xtream `player_api.php` login (status + `auth`); M3U: GET of the playlist, first 64 KB, then cancel.
 * - Response time: one `player_api.php` call per sample (the runner takes the median of three).
 */
class NetworkProbesImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApiClient private val api: OkHttpClient,
    private val store: SettingsStore,
    /** Bound in `:ui` (`DiagUiModule`); absent in `:data`-only graphs (tests), where the stream step is skipped. */
    private val sampler: java.util.Optional<StreamSampler>,
    @IoDispatcher private val io: CoroutineDispatcher,
) : NetworkProbes {

    private val probeClient: OkHttpClient by lazy {
        api.newBuilder().connectTimeout(CONNECT_S, TimeUnit.SECONDS).readTimeout(READ_S, TimeUnit.SECONDS).callTimeout(CALL_S, TimeUnit.SECONDS).build()
    }

    private val cloudflare: Dns by lazy {
        DnsOverHttps.Builder()
            .client(api.newBuilder().dns(Dns.SYSTEM).build())
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
            .includeIPv6(true)
            .build()
    }

    override suspend fun systemLookup(host: String): LookupResult = withContext(io) { lookup { Dns.SYSTEM.lookup(host) } }

    override suspend fun secureLookup(host: String): LookupResult? = withContext(io) {
        if (store.flow(NetworkDomain).value.dns != DnsChoice.SYSTEM) return@withContext null
        lookup { cloudflare.lookup(host) }
    }

    private inline fun lookup(block: () -> List<InetAddress>): LookupResult {
        val t0 = System.nanoTime()
        val addrs = try {
            block().mapNotNull { it.hostAddress }
        } catch (e: UnknownHostException) {
            emptyList()
        } catch (e: IOException) {
            emptyList()
        }
        return LookupResult(addrs.distinct(), (System.nanoTime() - t0) / 1_000_000)
    }

    override suspend fun connect(address: String, port: Int, timeoutMs: Long): ConnectResult = withContext(io) {
        val t0 = System.nanoTime()
        try {
            Socket().use { s -> s.connect(InetSocketAddress(InetAddress.getByName(address), port), timeoutMs.toInt()) }
            ConnectResult(true, (System.nanoTime() - t0) / 1_000_000)
        } catch (e: IOException) {
            ConnectResult(false, (System.nanoTime() - t0) / 1_000_000)
        }
    }

    override suspend fun server(target: TestTarget): HttpProbe = withContext(io) {
        if (target.xtream) xtreamLogin(target) else m3uHead(target)
    }

    override suspend fun apiCall(target: TestTarget): HttpProbe = withContext(io) { xtreamLogin(target) }

    override suspend fun streamSample(playlistId: Long): StreamSample? {
        val s = sampler.orElse(StreamSampler.NONE).sample(playlistId, StreamSampler.SAMPLE_MS) ?: return null
        return if (s.wifiLinkMbps == null) s.copy(wifiLinkMbps = wifiLinkMbps()) else s
    }

    private fun xtreamLogin(t: TestTarget): HttpProbe {
        val url = XtreamUrls(t.url, t.username.orEmpty(), t.password.orEmpty()).playerApi(null)
        return fetch(url, t.userAgent) { status, body ->
            when {
                status == 401 || status == 403 || status == 513 -> HttpOutcome.AUTH_REFUSED to null
                status !in 200..299 -> HttpOutcome.HTTP_ERROR to null
                else -> {
                    val text = body.trim()
                    val auth = AUTH.find(text)?.groupValues?.get(1)
                    val active = STATUS.find(text)?.groupValues?.get(1)?.equals("Active", ignoreCase = true)
                    when {
                        text.startsWith("<") -> HttpOutcome.NOT_A_SOURCE to null
                        text.isEmpty() || text == "[]" || auth == "0" -> HttpOutcome.AUTH_REFUSED to null
                        auth == "1" -> HttpOutcome.OK to active
                        else -> HttpOutcome.NOT_A_SOURCE to null
                    }
                }
            }
        }
    }

    private fun m3uHead(t: TestTarget): HttpProbe = fetch(t.url, t.userAgent) { status, body ->
        when {
            status == 401 || status == 403 -> HttpOutcome.AUTH_REFUSED to null
            status !in 200..299 -> HttpOutcome.HTTP_ERROR to null
            body.trimStart('\uFEFF', ' ', '\n', '\r').startsWith("#EXTM3U", ignoreCase = true) -> HttpOutcome.OK to null
            else -> HttpOutcome.NOT_A_SOURCE to null
        }
    }

    private inline fun fetch(url: String, userAgent: String?, classify: (Int, String) -> Pair<HttpOutcome, Boolean?>): HttpProbe {
        val t0 = System.nanoTime()
        return try {
            val request = Request.Builder().url(url).apply { if (userAgent != null) header("User-Agent", userAgent) }.build() // D-L9
            probeClient.newCall(request).execute().use { r ->
                val src = r.body.source()
                src.request(MAX_BODY)
                val body = src.buffer.snapshot(minOf(src.buffer.size, MAX_BODY).toInt()).utf8()
                val (outcome, active) = classify(r.code, body)
                HttpProbe(outcome, r.code, (System.nanoTime() - t0) / 1_000_000, active)
            }
        } catch (e: SSLException) {
            HttpProbe(HttpOutcome.TLS_FAILED, null, (System.nanoTime() - t0) / 1_000_000)
        } catch (e: IOException) {
            HttpProbe(HttpOutcome.NETWORK_ERROR, null, (System.nanoTime() - t0) / 1_000_000)
        } catch (e: IllegalArgumentException) {
            HttpProbe(HttpOutcome.NETWORK_ERROR, null, 0)
        }
    }

    /** §9 F27: the platform's link estimate on Wi-Fi only (WifiManager.linkSpeed needs ACCESS_WIFI_STATE, which we do not ask for). */
    private fun wifiLinkMbps(): Int? = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) (caps.linkDownstreamBandwidthKbps / 1000).takeIf { it > 0 } else null
    } catch (e: SecurityException) {
        null
    }

    companion object {
        private const val CONNECT_S = 5L
        private const val READ_S = 10L
        private const val CALL_S = 20L
        private const val MAX_BODY = 65_536L
        private val AUTH = Regex(""""auth"\s*:\s*"?(\d)""")
        private val STATUS = Regex(""""status"\s*:\s*"([A-Za-z]+)"""")

        /** The `network` domain: WP-A's object (M5 integration). */
        val NetworkDomain: SettingsDomain<NetworkSettings> get() = app.thebox.core.settings.state.NetworkDomain
    }
}
