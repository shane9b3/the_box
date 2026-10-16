package app.thebox.core.setup.lan

/** The address the phone page binds (docs/design/m5-setup-settings.md §3.5.2). `:data` implements it over `ConnectivityManager`. */
interface LanAddressFinder {
    /** A private IPv4 of the active Wi-Fi/Ethernet network (else the first such network); null = not on a home network. */
    fun find(): String?
    /** True when any network has `TRANSPORT_VPN` (the VPN hint, S4). */
    fun vpnActive(): Boolean
}

/** Which IPv4 the server may bind: 10/8, 172.16/12, 192.168/16 only; CGNAT 100.64/10, loopback, link-local and public refused. */
object LanAddressPolicy {
    fun isUsable(ipv4: String): Boolean {
        val parts = ipv4.split('.')
        if (parts.size != 4) return false
        val o = parts.map { p -> p.toIntOrNull()?.takeIf { it in 0..255 && p.length in 1..3 } ?: return false }
        return when {
            o[0] == 10 -> true
            o[0] == 172 && o[1] in 16..31 -> true
            o[0] == 192 && o[1] == 168 -> true
            else -> false
        }
    }
}
