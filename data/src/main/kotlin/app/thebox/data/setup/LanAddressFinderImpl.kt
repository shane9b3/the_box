package app.thebox.data.setup

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.thebox.core.common.BoxLog
import app.thebox.core.setup.lan.LanAddressFinder
import app.thebox.core.setup.lan.LanAddressPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import javax.inject.Inject

/**
 * The LAN IPv4 the phone page binds (docs/design/m5-setup-settings.md §3.5.2): the active network's `LinkProperties`
 * if it is Wi-Fi or Ethernet (and not a VPN), else the first Wi-Fi/Ethernet network with a private IPv4
 * ([LanAddressPolicy]: 10/8, 172.16/12, 192.168/16; CGNAT 100.64/10 refused); never 0.0.0.0, loopback, a VPN or IPv6.
 */
class LanAddressFinderImpl @Inject constructor(@ApplicationContext private val context: Context) : LanAddressFinder {
    private val cm get() = context.getSystemService(ConnectivityManager::class.java)

    override fun find(): String? = try {
        val c = cm ?: return null
        val active = c.activeNetwork
        addressOf(c, active) ?: allNetworks(c).firstNotNullOfOrNull { if (it == active) null else addressOf(c, it) }
    } catch (e: SecurityException) {
        BoxLog.w(TAG, "network state not readable")
        null
    }

    override fun vpnActive(): Boolean = try {
        val c = cm
        c != null && allNetworks(c).any { c.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    } catch (e: SecurityException) {
        false
    }

    @Suppress("DEPRECATION") // allNetworks: the only pre-31 way to see a non-default Wi-Fi/Ethernet network
    private fun allNetworks(c: ConnectivityManager): List<Network> = c.allNetworks.toList()

    private fun addressOf(c: ConnectivityManager, n: Network?): String? {
        if (n == null) return null
        val caps = c.getNetworkCapabilities(n) ?: return null
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        val lp = c.getLinkProperties(n) ?: return null
        return lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.firstOrNull(LanAddressPolicy::isUsable)
    }

    private companion object { const val TAG = "LanAddressFinder" }
}
