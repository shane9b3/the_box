package app.thebox.core.net

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.SharedFlow

/** `IPV6_FAILS_FOR_AUTO` (Appendix B, §3.13): IPv6 connect failures this process before AUTO sorts IPv4 first. */
const val IPV6_FAILS_FOR_AUTO = 2

/** A DNS-over-HTTPS resolver: its endpoint and the bootstrap IPs of its host (empty = the system resolver bootstraps). */
data class DohEndpoint(val url: String, val bootstrapIps: List<String>)

/**
 * The pure rules of `BoxDns` (docs/design/m5-setup-settings.md §3.13, §3.3.11): which resolver a lookup uses, read from
 * [NetworkSettings] at call time, and in which order the addresses come back. Wording in the UI is "secure DNS".
 */
object BoxDnsPolicy {
    val CLOUDFLARE = DohEndpoint("https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1"))
    val GOOGLE = DohEndpoint("https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4"))
    val QUAD9 = DohEndpoint("https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112"))

    /** The secure-DNS resolver for [settings]; null = the system resolver (SYSTEM, or CUSTOM without a valid URL). */
    fun endpoint(settings: NetworkSettings): DohEndpoint? = when (settings.dns) {
        DnsChoice.SYSTEM -> null
        DnsChoice.CLOUDFLARE -> CLOUDFLARE
        DnsChoice.GOOGLE -> GOOGLE
        DnsChoice.QUAD9 -> QUAD9
        DnsChoice.CUSTOM -> settings.customDohUrl?.trim()?.takeIf(::isValidCustomUrl)?.let { DohEndpoint(it, emptyList()) }
    }

    /** §3.3.11: a custom endpoint must be `https://host[:port]/path…` (no credentials, no spaces). */
    fun isValidCustomUrl(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return false
        val rest = url.substring("https://".length)
        if (rest.isEmpty() || rest.any { it.isWhitespace() } || '@' in rest.substringBefore('/')) return false
        val host = rest.substringBefore('/').substringBefore(':')
        return host.isNotEmpty() && host.all { it.isLetterOrDigit() || it == '.' || it == '-' }
    }

    /** ON: always; OFF: never; AUTO: once [ipv6Failures] reached [IPV6_FAILS_FOR_AUTO] this process. */
    fun preferIpv4(settings: NetworkSettings, ipv6Failures: Int): Boolean = when (settings.preferIpv4) {
        PreferIpv4.ON -> true
        PreferIpv4.OFF -> false
        PreferIpv4.AUTO -> ipv6Failures >= IPV6_FAILS_FOR_AUTO
    }

    /** Stable partition: IPv4 addresses first, each group in the resolver's order. */
    fun <T> ipv4First(addresses: List<T>, isIpv4: (T) -> Boolean): List<T> {
        if (addresses.size < 2) return addresses
        val (v4, rest) = addresses.partition(isIpv4)
        return if (v4.isEmpty() || rest.isEmpty()) addresses else v4 + rest
    }
}

/** Counts IPv6 connect failures this process (fed by the base client's `EventListener`). Thread-safe. */
class Ipv6FailureCounter {
    private val n = AtomicInteger()
    val count: Int get() = n.get()
    fun onConnectFailed(ipv6: Boolean) { if (ipv6) n.incrementAndGet() }
}

/** The "Secure DNS is not reachable" notice is shown once per process (§3.13). */
class OncePerProcess {
    private val done = AtomicBoolean(false)
    val consumed: Boolean get() = done.get()
    /** True exactly once. */
    fun take(): Boolean = done.compareAndSet(false, true)
}

/**
 * (§3.13) A secure-DNS lookup failed and the system resolver answered instead. Emitted at most once per process, and only
 * while someone collects. The UI shows "Secure DNS is not reachable; using your normal DNS" (its own string).
 */
interface SecureDnsNotices {
    val fallbackUsed: SharedFlow<Unit>
}
