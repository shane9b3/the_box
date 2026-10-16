package app.thebox.data.net

import app.thebox.core.common.BoxLog
import app.thebox.core.net.BoxDnsPolicy
import app.thebox.core.net.DohEndpoint
import app.thebox.core.net.Ipv6FailureCounter
import app.thebox.core.net.NetworkSettings
import app.thebox.core.net.OncePerProcess
import app.thebox.core.net.SecureDnsNotices
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.state.NetworkDomain
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.dnsoverhttps.DnsOverHttps

/**
 * The app's one `okhttp3.Dns` (docs/design/m5-setup-settings.md §3.13, N8), set on `HttpModule`'s base client, so the
 * API, download and (through `PlaybackHttpClients`' `base.newBuilder()`) the live/segment clients all use it.
 *
 * Each [lookup] reads [NetworkSettings] at call time ([BoxDnsPolicy]): SYSTEM → the system resolver; a secure-DNS
 * choice → a cached [DnsOverHttps] (own small client, never the base client this is installed on). A secure-DNS failure
 * falls back to the system resolver for that lookup and emits [fallbackUsed] once per process. Prefer IPv4 sorts the
 * answer ([eventListenerFactory] counts IPv6 connect failures for AUTO). Pooled connections keep their address.
 */
@Singleton
class BoxDns @Inject constructor(private val store: SettingsStore) : Dns, SecureDnsNotices {
    /** Test seams. */
    internal var system: Dns = Dns.SYSTEM
    internal var dohFactory: (DohEndpoint) -> Dns = ::buildDoh

    private val resolvers = ConcurrentHashMap<DohEndpoint, Dns>()
    val ipv6Failures = Ipv6FailureCounter()
    private val notice = OncePerProcess()
    private val _fallback = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val fallbackUsed: SharedFlow<Unit> = _fallback.asSharedFlow()

    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(DOH_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(DOH_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    private fun settings(): NetworkSettings = store.flow(NetworkDomain).value

    override fun lookup(hostname: String): List<InetAddress> {
        val s = settings()
        val endpoint = BoxDnsPolicy.endpoint(s)
        val answer = if (endpoint == null) {
            system.lookup(hostname)
        } else {
            val doh = resolvers.getOrPut(endpoint) { dohFactory(endpoint) }
            try {
                doh.lookup(hostname).ifEmpty { throw java.net.UnknownHostException("secure DNS: empty answer") }
            } catch (e: IOException) {
                val fallback = system.lookup(hostname) // throws on its own failure: nothing resolves the name
                BoxLog.w(TAG, "secure DNS failed (${e.javaClass.simpleName}); system resolver answered")
                notifyFallback()
                fallback
            }
        }
        return if (BoxDnsPolicy.preferIpv4(s, ipv6Failures.count)) BoxDnsPolicy.ipv4First(answer) { it is Inet4Address } else answer
    }

    /** Consumes the once-per-process notice only when somebody listens (else a later collector would never see it). */
    private fun notifyFallback() {
        if (_fallback.subscriptionCount.value == 0 || notice.consumed) return
        if (notice.take()) _fallback.tryEmit(Unit)
    }

    private val listener = object : EventListener() {
        override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
            ipv6Failures.onConnectFailed(inetSocketAddress.address is Inet6Address)
        }
    }

    /** Shared by the base client: counts IPv6 connect failures (Prefer IPv4 = AUTO). */
    val eventListenerFactory: EventListener.Factory = EventListener.Factory { listener }

    /** Connect/read timeouts of `NetworkSettings` on the API and download clients (§3.3.11); [readFloorS] keeps a client's minimum. */
    fun timeoutsInterceptor(readFloorS: Int = 0): Interceptor = Interceptor { chain ->
        val s = settings()
        chain.withConnectTimeout(s.connectTimeoutSec.coerceIn(1, MAX_TIMEOUT_S), TimeUnit.SECONDS)
            .withReadTimeout(maxOf(s.readTimeoutSec.coerceIn(1, MAX_TIMEOUT_S), readFloorS), TimeUnit.SECONDS)
            .proceed(chain.request())
    }

    private fun buildDoh(endpoint: DohEndpoint): Dns {
        val b = DnsOverHttps.Builder()
            .client(dohClient)
            .url(endpoint.url.toHttpUrl())
            .systemDns(system)
            // Provider hosts may be names only a secure resolver knows (I-M5-25: panel.test); answer them all.
            .resolvePrivateAddresses(true)
        if (endpoint.bootstrapIps.isNotEmpty()) b.bootstrapDnsHosts(endpoint.bootstrapIps.map { InetAddress.getByName(it) })
        return b.build()
    }

    companion object {
        private const val TAG = "BoxDns"
        const val DOH_TIMEOUT_S = 5L
        const val MAX_TIMEOUT_S = 120
    }
}
