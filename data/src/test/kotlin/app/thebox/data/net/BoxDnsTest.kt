package app.thebox.data.net

import app.thebox.core.net.DnsChoice
import app.thebox.core.net.DohEndpoint
import app.thebox.core.net.IPV6_FAILS_FOR_AUTO
import app.thebox.core.net.NetworkSettings
import app.thebox.core.net.PreferIpv4
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.NetworkDomain
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/design/m5-setup-settings.md §3.13, §6.1 WP-A `BoxDnsTest`. */
@OptIn(ExperimentalCoroutinesApi::class)
class BoxDnsTest {
    private val v4 = InetAddress.getByName("192.0.2.1")
    private val v6 = InetAddress.getByName("2001:db8::1")
    private val v4b = InetAddress.getByName("192.0.2.2")

    private val store = FakeSettingsStore()
    private val systemCalls = ArrayList<String>()
    private val dohCalls = ArrayList<String>()
    private var dohFails = false
    private val built = ArrayList<DohEndpoint>()

    private val dns = BoxDns(store).apply {
        system = Dns { h -> systemCalls += h; listOf(v6, v4) }
        dohFactory = { ep ->
            built += ep
            Dns { h ->
                dohCalls += "${ep.url}|$h"
                if (dohFails) throw UnknownHostException("unreachable")
                listOf(v6, v4b)
            }
        }
    }

    private fun set(s: NetworkSettings) = store.update(NetworkDomain) { s }

    @Test
    fun choiceIsReadAtEveryLookup() {
        set(NetworkSettings(preferIpv4 = PreferIpv4.OFF))
        assertEquals(listOf(v6, v4), dns.lookup("panel.test"))
        assertEquals(listOf("panel.test"), systemCalls)

        set(NetworkSettings(dns = DnsChoice.CLOUDFLARE, preferIpv4 = PreferIpv4.OFF))
        assertEquals(listOf(v6, v4b), dns.lookup("panel.test"))
        assertEquals(listOf("https://cloudflare-dns.com/dns-query|panel.test"), dohCalls)

        set(NetworkSettings(dns = DnsChoice.QUAD9, preferIpv4 = PreferIpv4.OFF))
        dns.lookup("a.test"); dns.lookup("b.test")
        set(NetworkSettings(dns = DnsChoice.CLOUDFLARE, preferIpv4 = PreferIpv4.OFF))
        dns.lookup("c.test")
        // One resolver per endpoint, cached across switches.
        assertEquals(2, built.size)

        set(NetworkSettings(dns = DnsChoice.SYSTEM, preferIpv4 = PreferIpv4.OFF))
        dns.lookup("d.test")
        assertEquals(listOf("panel.test", "d.test"), systemCalls)
    }

    @Test
    fun preferIpv4OnSortsAndOffKeepsOrder() {
        set(NetworkSettings(preferIpv4 = PreferIpv4.ON))
        assertEquals(listOf(v4, v6), dns.lookup("h"))
        set(NetworkSettings(preferIpv4 = PreferIpv4.OFF))
        assertEquals(listOf(v6, v4), dns.lookup("h"))
    }

    @Test
    fun autoSortsAfterTwoIpv6ConnectFailures() {
        set(NetworkSettings(preferIpv4 = PreferIpv4.AUTO))
        assertEquals(listOf(v6, v4), dns.lookup("h"))
        val listener = dns.eventListenerFactory.create(OkHttpClient().newCall(Request.Builder().url("http://h/").build()))
        val call = OkHttpClient().newCall(Request.Builder().url("http://h/").build())
        listener.connectFailed(call, InetSocketAddress(v4, 80), Proxy.NO_PROXY, null, java.io.IOException("v4"))
        repeat(IPV6_FAILS_FOR_AUTO - 1) { listener.connectFailed(call, InetSocketAddress(v6, 80), Proxy.NO_PROXY, null, java.io.IOException("v6")) }
        assertEquals(listOf(v6, v4), dns.lookup("h"))
        listener.connectFailed(call, InetSocketAddress(v6, 80), Proxy.NO_PROXY, null, java.io.IOException("v6"))
        assertEquals(listOf(v4, v6), dns.lookup("h"))
    }

    @Test
    fun dohFailureFallsBackToSystemAndNotifiesOnce() = runTest(UnconfinedTestDispatcher()) {
        set(NetworkSettings(dns = DnsChoice.GOOGLE, preferIpv4 = PreferIpv4.OFF))
        dohFails = true
        val seen = ArrayList<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { dns.fallbackUsed.collect { seen += it } }
        assertEquals(listOf(v6, v4), dns.lookup("panel.test"))
        assertEquals(listOf(v6, v4), dns.lookup("panel.test"))
        assertEquals(listOf("panel.test", "panel.test"), systemCalls)
        assertEquals(1, seen.size)
        job.cancel()
    }

    @Test
    fun noticeIsKeptUntilSomeoneListens() = runTest(UnconfinedTestDispatcher()) {
        set(NetworkSettings(dns = DnsChoice.GOOGLE))
        dohFails = true
        dns.lookup("x") // nobody collects yet: the once-per-process notice is not spent
        val seen = ArrayList<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { dns.fallbackUsed.collect { seen += it } }
        dns.lookup("x")
        assertEquals(1, seen.size)
        job.cancel()
    }

    @Test(expected = UnknownHostException::class)
    fun bothResolversFailingFailsTheLookup() {
        set(NetworkSettings(dns = DnsChoice.CLOUDFLARE))
        dohFails = true
        dns.system = Dns { throw UnknownHostException("nope") }
        dns.lookup("missing.test")
    }

    @Test
    fun timeoutsInterceptorAppliesTheSettings() {
        set(NetworkSettings(connectTimeoutSec = 5, readTimeoutSec = 40))
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().body("ok").build())
            server.enqueue(MockResponse.Builder().body("ok").build())
            var seen = IntArray(0)
            val probe = okhttp3.Interceptor { chain -> seen = intArrayOf(chain.connectTimeoutMillis(), chain.readTimeoutMillis()); chain.proceed(chain.request()) }
            val api = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).addInterceptor(dns.timeoutsInterceptor()).addInterceptor(probe).build()
            api.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
            assertEquals(listOf(5_000, 40_000), seen.toList())
            val download = OkHttpClient.Builder().addInterceptor(dns.timeoutsInterceptor(HttpModule.DOWNLOAD_READ_FLOOR_S)).addInterceptor(probe).build()
            download.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
            assertEquals(listOf(5_000, 120_000), seen.toList())
        } finally {
            server.close()
        }
    }

    @Test
    fun realDohBuilderAcceptsTheEndpoints() {
        // Builds the real DnsOverHttps for every built-in endpoint (bootstrap IP literals, no network use).
        val real = BoxDns(store)
        for (c in listOf(DnsChoice.CLOUDFLARE, DnsChoice.GOOGLE, DnsChoice.QUAD9)) {
            val ep = app.thebox.core.net.BoxDnsPolicy.endpoint(NetworkSettings(dns = c))!!
            assertTrue(real.dohFactory(ep) is okhttp3.dnsoverhttps.DnsOverHttps)
        }
        assertTrue(real.dohFactory(DohEndpoint("https://doh.example.net/dns-query", emptyList())) is okhttp3.dnsoverhttps.DnsOverHttps)
    }
}
