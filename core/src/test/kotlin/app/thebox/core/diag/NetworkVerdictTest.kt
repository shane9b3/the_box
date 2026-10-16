package app.thebox.core.diag

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The verdict's rule order (docs/design/m5-setup-settings.md §3.11.2) and the step runner over fake probes. */
class NetworkVerdictTest {
    private val good = NetworkFindings(DnsOutcome.OK, null, ipv4Connects = true, ipv6Connects = null, port = 8080, server = HttpOutcome.OK)

    private fun key(f: NetworkFindings) = NetworkVerdict.decide(f).key

    @Test
    fun everythingFine() {
        assertEquals(Verdict(VerdictKey.ALL_GOOD, emptyList()), NetworkVerdict.decide(good))
    }

    @Test
    fun blockedSystemDnsWithWorkingSecureDnsWinsOverEverything() {
        val f = good.copy(systemDns = DnsOutcome.BLOCKED, secureDns = DnsOutcome.OK, server = HttpOutcome.AUTH_REFUSED, ipv6Connects = false)
        assertEquals(VerdictKey.DNS_BLOCKED_TRY_SECURE, key(f))
        assertEquals(VerdictKey.DNS_BLOCKED_TRY_SECURE, key(good.copy(systemDns = DnsOutcome.FAILED, secureDns = DnsOutcome.OK)))
    }

    @Test
    fun bothDnsFailIsTheNameDoesNotExist() {
        assertEquals(Verdict(VerdictKey.NAME_NOT_FOUND, emptyList()), NetworkVerdict.decide(good.copy(systemDns = DnsOutcome.FAILED, secureDns = DnsOutcome.FAILED)))
        // Secure lookup skipped (DNS already secure) and the system lookup failed: same.
        assertEquals(Verdict(VerdictKey.NAME_NOT_FOUND, emptyList()), NetworkVerdict.decide(good.copy(systemDns = DnsOutcome.FAILED, secureDns = null)))
    }

    @Test
    fun noAddressConnectsNamesThePort() {
        val f = good.copy(ipv4Connects = false, ipv6Connects = false, server = null)
        assertEquals(Verdict(VerdictKey.SERVER_UNREACHABLE, listOf("8080")), NetworkVerdict.decide(f))
        assertEquals(VerdictKey.SERVER_UNREACHABLE, key(good.copy(ipv4Connects = null, ipv6Connects = null)))
    }

    @Test
    fun tlsBeforeAuth() {
        assertEquals(VerdictKey.TLS_PROBLEM, key(good.copy(server = HttpOutcome.TLS_FAILED, ipv6Connects = false)))
        assertEquals(VerdictKey.AUTH_PROBLEM, key(good.copy(server = HttpOutcome.AUTH_REFUSED, ipv6Connects = false)))
    }

    @Test
    fun ipv6BrokenOnlyWhenIpv4Works() {
        assertEquals(VerdictKey.IPV6_BROKEN, key(good.copy(ipv6Connects = false)))
        assertEquals(VerdictKey.ALL_GOOD, key(good.copy(ipv6Connects = true)))
    }

    @Test
    fun authBeforeIpv6() {
        assertEquals(VerdictKey.AUTH_PROBLEM, key(good.copy(server = HttpOutcome.AUTH_REFUSED, ipv6Connects = false)))
    }

    @Test
    fun slowProviderNeedsBufferDrops() {
        val slow = StreamSample(deliveredBps = 6_100_000, neededBps = 8_000_000, bufferDrops = 2)
        assertEquals(Verdict(VerdictKey.SLOW_PROVIDER, listOf("6.1", "8.0")), NetworkVerdict.decide(good.copy(stream = slow)))
        assertEquals(VerdictKey.ALL_GOOD, key(good.copy(stream = slow.copy(bufferDrops = 0))))
        assertEquals(VerdictKey.ALL_GOOD, key(good.copy(stream = slow.copy(deliveredBps = 9_600_000))))
    }

    @Test
    fun slowConnectionWhenTheWifiLinkIsTheBottleneck() {
        val s = StreamSample(deliveredBps = 6_100_000, neededBps = 8_000_000, bufferDrops = 1, wifiLinkMbps = 6)
        assertEquals(Verdict(VerdictKey.SLOW_CONNECTION, listOf("6", "8.0")), NetworkVerdict.decide(good.copy(stream = s)))
        // The platform link figure is an estimate (F27): a link that can carry the channel blames the provider.
        assertEquals(VerdictKey.SLOW_PROVIDER, key(good.copy(stream = s.copy(wifiLinkMbps = 12))))
        assertEquals(VerdictKey.SLOW_PROVIDER, key(good.copy(stream = s.copy(wifiLinkMbps = 433))))
    }

    @Test
    fun ipv6BeforeSlow() {
        val slow = StreamSample(1_000_000, 8_000_000, 3)
        assertEquals(VerdictKey.IPV6_BROKEN, key(good.copy(ipv6Connects = false, stream = slow)))
    }

    @Test
    fun serverErrorIsUnreachable() {
        assertEquals(Verdict(VerdictKey.SERVER_UNREACHABLE, listOf("8080")), NetworkVerdict.decide(good.copy(server = HttpOutcome.HTTP_ERROR)))
        assertEquals(VerdictKey.SERVER_UNREACHABLE, key(good.copy(server = HttpOutcome.NETWORK_ERROR)))
    }

    @Test
    fun blockedAnswers() {
        assertTrue(NetworkVerdict.looksBlocked("panel.example.com", listOf("0.0.0.0")))
        assertTrue(NetworkVerdict.looksBlocked("panel.example.com", listOf("127.0.0.1")))
        assertTrue(NetworkVerdict.looksBlocked("panel.example.com", listOf("192.168.1.1")))
        assertFalse(NetworkVerdict.looksBlocked("panel.example.com", listOf("93.184.216.34")))
        assertFalse(NetworkVerdict.looksBlocked("panel.example.com", listOf("192.168.1.1", "93.184.216.34")))
        assertFalse(NetworkVerdict.looksBlocked("nas.local", listOf("192.168.1.10")))
        assertFalse(NetworkVerdict.looksBlocked("panel.test", listOf("10.0.2.2")))
        assertFalse(NetworkVerdict.looksBlocked("10.0.2.2", listOf("10.0.2.2")))
        assertFalse(NetworkVerdict.looksBlocked("nas", listOf("10.0.0.5")))
    }

    // ---------------------------------------------------------------- the runner

    private class Probes(
        var sys: LookupResult = LookupResult(listOf("203.0.113.5", "2001:db8::5"), 18),
        var sec: LookupResult? = LookupResult(listOf("203.0.113.5"), 40),
        var v4: Boolean = true, var v6: Boolean = true,
        var server: HttpProbe = HttpProbe(HttpOutcome.OK, 200, 120, accountActive = true),
        var api: List<Long> = listOf(300, 310, 900),
        var sample: StreamSample? = null,
        var throwOnServer: Boolean = false,
    ) : NetworkProbes {
        val calls = ArrayList<String>()
        private var apiIdx = 0
        override suspend fun systemLookup(host: String) = sys.also { calls += "sys" }
        override suspend fun secureLookup(host: String) = sec.also { calls += "sec" }
        override suspend fun connect(address: String, port: Int, timeoutMs: Long): ConnectResult {
            calls += "connect $address"
            return ConnectResult(if (address.contains(':')) v6 else v4, 42)
        }
        override suspend fun server(target: TestTarget): HttpProbe {
            calls += "server"
            if (throwOnServer) throw java.io.IOException("http://h/player_api.php?username=u&password=p")
            return server
        }
        override suspend fun apiCall(target: TestTarget) = HttpProbe(HttpOutcome.OK, 200, api[apiIdx++ % api.size]).also { calls += "api" }
        override suspend fun streamSample(playlistId: Long) = sample.also { calls += "sample" }
    }

    private val xtream = TestTarget(7, xtream = true, host = "panel.example.com", port = 8080, https = false, url = "http://panel.example.com:8080", username = "u", password = "p")

    private suspend fun run(p: Probes, t: TestTarget = xtream) = NetworkTestRunner(p).run(t).toList()

    @Test
    fun runnerEmitsSixStepsThenTheVerdict() = runTest {
        val events = run(Probes())
        assertEquals(7, events.size)
        val steps = events.filterIsInstance<NetworkTestEvent.Step>().map { it.result }
        assertEquals(TestStep.entries.toList(), steps.map { it.step })
        assertEquals("n=2;v4=1;v6=1", steps[0].detail)
        assertEquals("n=1;v4=1;v6=0;agree=1", steps[1].detail)
        assertEquals("v4=42;v6=42;port=8080", steps[2].detail)
        assertEquals("status=200;active=1", steps[3].detail)
        assertEquals(310L, steps[4].ms)
        assertEquals("skipped=1", steps[5].detail)
        val done = assertIs<NetworkTestEvent.Done>(events.last()).result
        assertEquals(VerdictKey.ALL_GOOD, done.verdict.key)
        assertEquals(6, done.steps.size)
    }

    @Test
    fun blockedSystemDnsUsesTheSecureAnswersForConnect() = runTest {
        val p = Probes(sys = LookupResult(listOf("0.0.0.0"), 5), sec = LookupResult(listOf("203.0.113.5"), 30))
        val events = run(p)
        assertTrue("connect 203.0.113.5" in p.calls)
        val dns = (events[0] as NetworkTestEvent.Step).result
        assertFalse(dns.ok)
        assertEquals("error=blocked;n=1", dns.detail)
        assertEquals(VerdictKey.DNS_BLOCKED_TRY_SECURE, (events.last() as NetworkTestEvent.Done).result.verdict.key)
    }

    @Test
    fun nothingConnectsSkipsTheServerStep() = runTest {
        val p = Probes(v4 = false, v6 = false)
        val events = run(p)
        assertFalse("server" in p.calls)
        assertEquals("v4=fail;v6=fail;port=8080", (events[2] as NetworkTestEvent.Step).result.detail)
        assertEquals(Verdict(VerdictKey.SERVER_UNREACHABLE, listOf("8080")), (events.last() as NetworkTestEvent.Done).result.verdict)
    }

    @Test
    fun ipv6FailureIsReported() = runTest {
        val events = run(Probes(v6 = false))
        assertEquals("v4=42;v6=fail;port=8080", (events[2] as NetworkTestEvent.Step).result.detail)
        assertEquals(VerdictKey.IPV6_BROKEN, (events.last() as NetworkTestEvent.Done).result.verdict.key)
    }

    @Test
    fun authRefusedSkipsTheLatencyStep() = runTest {
        val p = Probes(server = HttpProbe(HttpOutcome.AUTH_REFUSED, 401, 80))
        val events = run(p)
        assertFalse("api" in p.calls)
        assertEquals("error=auth;status=401", (events[3] as NetworkTestEvent.Step).result.detail)
        assertEquals("skipped=1", (events[4] as NetworkTestEvent.Step).result.detail)
        assertEquals(VerdictKey.AUTH_PROBLEM, (events.last() as NetworkTestEvent.Done).result.verdict.key)
    }

    @Test
    fun aThrowingProbeIsAFailedStepAndNoDetailCarriesTheUrl() = runTest {
        val events = run(Probes(throwOnServer = true))
        val server = (events[3] as NetworkTestEvent.Step).result
        assertFalse(server.ok)
        assertEquals("error=net", server.detail)
        events.filterIsInstance<NetworkTestEvent.Step>().forEach { assertFalse(it.result.detail.contains("password"), it.result.detail) }
    }

    @Test
    fun m3uHasNoLatencyStepAndIpLiteralsSkipSecureDns() = runTest {
        val p = Probes(sys = LookupResult(listOf("10.0.2.2"), 0))
        val t = TestTarget(3, xtream = false, host = "10.0.2.2", port = 18080, https = false, url = "http://10.0.2.2:18080/get.php")
        val events = run(p, t)
        assertFalse("sec" in p.calls)
        assertEquals("skipped=1", (events[1] as NetworkTestEvent.Step).result.detail)
        assertEquals("skipped=1", (events[4] as NetworkTestEvent.Step).result.detail)
        assertEquals(VerdictKey.ALL_GOOD, (events.last() as NetworkTestEvent.Done).result.verdict.key)
    }

    @Test
    fun slowStreamSample() = runTest {
        val events = run(Probes(sample = StreamSample(6_100_000, 8_000_000, 2)))
        val s = (events[5] as NetworkTestEvent.Step).result
        assertFalse(s.ok)
        assertEquals(mapOf("delivered" to "6100000", "needed" to "8000000", "drops" to "2"), NetworkTestRunner.parseDetail(s.detail))
        assertEquals(Verdict(VerdictKey.SLOW_PROVIDER, listOf("6.1", "8.0")), (events.last() as NetworkTestEvent.Done).result.verdict)
    }
}
