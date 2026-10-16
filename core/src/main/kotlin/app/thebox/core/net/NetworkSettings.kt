// Contract (M5 commit 0): docs/design/m5-setup-settings.md §2.11. Verbatim; changes go through the lead.
package app.thebox.core.net

@kotlinx.serialization.Serializable
data class NetworkSettings(
    val dns: DnsChoice = DnsChoice.SYSTEM,
    /** Custom DoH endpoint (https://…/dns-query) when dns == CUSTOM. */
    val customDohUrl: String? = null,
    val preferIpv4: PreferIpv4 = PreferIpv4.AUTO,
    val connectTimeoutSec: Int = 10,
    val readTimeoutSec: Int = 20,
)
enum class DnsChoice { SYSTEM, CLOUDFLARE, GOOGLE, QUAD9, CUSTOM }
enum class PreferIpv4 { AUTO, ON, OFF }
