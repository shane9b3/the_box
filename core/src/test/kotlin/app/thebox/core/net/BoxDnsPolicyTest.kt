package app.thebox.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoxDnsPolicyTest {
    @Test
    fun endpointFollowsTheChoice() {
        assertNull(BoxDnsPolicy.endpoint(NetworkSettings()))
        assertEquals(BoxDnsPolicy.CLOUDFLARE, BoxDnsPolicy.endpoint(NetworkSettings(dns = DnsChoice.CLOUDFLARE)))
        assertEquals(listOf("8.8.8.8", "8.8.4.4"), BoxDnsPolicy.endpoint(NetworkSettings(dns = DnsChoice.GOOGLE))!!.bootstrapIps)
        assertEquals(listOf("9.9.9.9", "149.112.112.112"), BoxDnsPolicy.endpoint(NetworkSettings(dns = DnsChoice.QUAD9))!!.bootstrapIps)
        val custom = BoxDnsPolicy.endpoint(NetworkSettings(dns = DnsChoice.CUSTOM, customDohUrl = " https://doh.example.net/dns-query "))
        assertEquals(DohEndpoint("https://doh.example.net/dns-query", emptyList()), custom)
    }

    @Test
    fun customWithoutAValidHttpsUrlUsesTheSystemResolver() {
        assertNull(BoxDnsPolicy.endpoint(NetworkSettings(dns = DnsChoice.CUSTOM)))
        assertNull(BoxDnsPolicy.endpoint(NetworkSettings(dns = DnsChoice.CUSTOM, customDohUrl = "http://doh.example.net/dns-query")))
    }

    @Test
    fun customUrlValidation() {
        assertTrue(BoxDnsPolicy.isValidCustomUrl("https://10.0.2.2:8080/dns-query"))
        assertTrue(BoxDnsPolicy.isValidCustomUrl("HTTPS://dns.example.com/dns-query"))
        assertFalse(BoxDnsPolicy.isValidCustomUrl("https://"))
        assertFalse(BoxDnsPolicy.isValidCustomUrl("https://user:pw@dns.example.com/q"))
        assertFalse(BoxDnsPolicy.isValidCustomUrl("https://dns example.com/q"))
        assertFalse(BoxDnsPolicy.isValidCustomUrl("dns.example.com"))
    }

    @Test
    fun preferIpv4Rule() {
        assertTrue(BoxDnsPolicy.preferIpv4(NetworkSettings(preferIpv4 = PreferIpv4.ON), 0))
        assertFalse(BoxDnsPolicy.preferIpv4(NetworkSettings(preferIpv4 = PreferIpv4.OFF), 10))
        assertFalse(BoxDnsPolicy.preferIpv4(NetworkSettings(preferIpv4 = PreferIpv4.AUTO), IPV6_FAILS_FOR_AUTO - 1))
        assertTrue(BoxDnsPolicy.preferIpv4(NetworkSettings(preferIpv4 = PreferIpv4.AUTO), IPV6_FAILS_FOR_AUTO))
    }

    @Test
    fun ipv4FirstIsAStablePartition() {
        val input = listOf("6a", "4a", "6b", "4b")
        assertEquals(listOf("4a", "4b", "6a", "6b"), BoxDnsPolicy.ipv4First(input) { it.startsWith("4") })
        assertEquals(listOf("6a", "6b"), BoxDnsPolicy.ipv4First(listOf("6a", "6b")) { it.startsWith("4") })
    }

    @Test
    fun counterAndOnce() {
        val c = Ipv6FailureCounter()
        c.onConnectFailed(false); c.onConnectFailed(true); c.onConnectFailed(true)
        assertEquals(2, c.count)
        val once = OncePerProcess()
        assertTrue(once.take()); assertFalse(once.take()); assertTrue(once.consumed)
    }
}
