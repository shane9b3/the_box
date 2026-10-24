package app.thebox.data.storage.discovery

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import app.thebox.core.common.BoxLog
import app.thebox.core.setup.lan.LanAddressFinder
import app.thebox.core.storage.BrowseError
import app.thebox.core.storage.BrowseErrorKind
import app.thebox.core.storage.FindState
import app.thebox.core.storage.NameSource
import app.thebox.core.storage.SmbAddress
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StorageLocations
import app.thebox.core.storage.discovery.DiscoveryMerge
import app.thebox.core.storage.discovery.LanSubnet
import app.thebox.core.storage.discovery.SavedHosts
import app.thebox.core.storage.discovery.Sighting
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Finding computers (§7.1, lead ruling 3): NsdManager `_smb._tcp`, the 445 sweep of the Wi-Fi/Ethernet /24 (never the
 * VPN), NetBIOS node-status then reverse-DNS names. WS-Discovery is cut.
 *
 * [search] is cold and is collected for the `FindComputers` page's lifetime:
 * - collecting it takes the `WifiManager.MulticastLock`, which is held until the collection is cancelled (the page
 *   closes), not only while the finders run (A-S10);
 * - the finders run for at most DISCOVERY_TOTAL_MS; `searching` turns false as soon as the sweep finishes (with no
 *   sweep, when the finders stop); after the finders stop the flow emits a final state and then **stays open, holding
 *   the lock, until cancelled** (it never completes on its own);
 * - cancelling stops everything at once (Must): pending sweep and NBNS sockets are closed from the cancelling thread,
 *   NSD discovery is stopped, reverse-DNS answers still in flight are dropped.
 *
 * Owner ruling 2: the sweep runs only inside a collection of [search], never in the background.
 */
interface ComputerFinder {
    fun search(): Flow<FindState>

    /** The IPv4 an NSD instance named [name] had in this process (§7.2 `.local` fallback, U-8); null if never seen. */
    fun recentAddress(name: String): String?
}

/** What the finder needs from the platform; a seam for `ComputerFinderTest`. */
internal interface FinderEnvironment {
    /** `LanAddressFinder.find()`: a private IPv4 of Wi-Fi/Ethernet, never a VPN (§7.1). */
    fun lanAddress(): String?
    fun vpnUp(): Boolean
    /** Any connected network at all (for NO_NETWORK). */
    fun networkUp(): Boolean
    /** Takes the multicast lock; closing the handle releases it. */
    fun acquireMulticastLock(): AutoCloseable
    /** Sockets bound to the network that owns [lanAddress]. */
    fun sockets(lanAddress: String): LanSockets
    fun nsd(): Flow<Sighting>
}

@Singleton
class ComputerFinderImpl internal constructor(
    private val env: FinderEnvironment,
    private val savedHosts: () -> List<SmbAddress>,
    private val sweeps: (LanSockets) -> Port445Sweep = { Port445Sweep(it) },
    private val names: (LanSockets) -> NameLookup = { NetworkNameLookup(it) },
    private val totalMs: Long = StorageConstants.DISCOVERY_TOTAL_MS,
) : ComputerFinder {

    @Inject constructor(
        @ApplicationContext context: Context,
        lan: LanAddressFinder,
        locations: Provider<StorageLocations>,   // Provider: no construction cycle with :data's storage graph
    ) : this(
        AndroidFinderEnvironment(context, lan),
        { locations.get().status.value.mapNotNull { it.location.smb } },
    )

    private val nsdNames = ConcurrentHashMap<String, String>()

    override fun recentAddress(name: String): String? = nsdNames[norm(name)]

    override fun search(): Flow<FindState> = channelFlow {
        val lock = env.acquireMulticastLock()
        try {
            runSearch(this)
            awaitCancellation()                     // the page is still open: keep the lock until it closes
        } finally {
            lock.close()
        }
    }.conflate().flowOn(Dispatchers.IO)             // binder calls (lock, LinkProperties) stay off the page's thread

    private suspend fun runSearch(out: ProducerScope<FindState>) {
        val startedAt = System.nanoTime()
        val own = env.lanAddress()
        val subnet = LanSubnet.of(own)
        val vpn = env.vpnUp()
        if (subnet == null && !env.networkUp()) {
            BoxLog.i(TAG) { "find: no network" }
            out.send(FindState(emptyList(), searching = false, scannedNetwork = null, vpnUp = vpn, error = BrowseError(BrowseErrorKind.NO_NETWORK, vpnUp = vpn)))
            return
        }
        val saved = try { SavedHosts(savedHosts()) } catch (e: RuntimeException) { SavedHosts.NONE }
        val merge = DiscoveryMerge(saved)
        val mutex = Mutex()
        var searching = true
        var nsdCount = 0
        var stats: Port445Sweep.Stats? = null

        suspend fun publishLocked() {
            out.send(FindState(merge.computers(), searching, subnet?.label, vpn))
        }
        suspend fun add(s: Sighting) = mutex.withLock { if (merge.add(s)) publishLocked() }

        BoxLog.i(TAG) { "find: start (lan=${subnet != null}, vpn=$vpn)" }
        mutex.withLock { publishLocked() }

        withTimeoutOrNull(totalMs) {
            coroutineScope {
                launch {
                    env.nsd().collect { s ->
                        if (s.source == NameSource.NSD && s.name != null) { nsdNames[norm(s.name!!)] = s.address; nsdCount++ }
                        add(s)
                    }
                }
                if (subnet != null && own != null) {
                    val sockets = env.sockets(own)
                    val lookup = names(sockets)
                    val permits = Semaphore(NAME_LOOKUPS_PARALLEL)
                    stats = sweeps(sockets).sweep(subnet) { address ->
                        add(Sighting(address))
                        launch {
                            permits.withPermit {
                                if (mutex.withLock { merge.wantsName(address) }) {
                                    lookup.lookup(address)?.let { (name, source) ->
                                        add(Sighting(address, name, source, port = null, hintOnly = true))
                                    }
                                }
                            }
                        }
                    }
                    mutex.withLock { searching = false; publishLocked() }
                }
                awaitCancellation()                 // NSD and the name lookups run until DISCOVERY_TOTAL_MS
            }
        }
        mutex.withLock { searching = false; publishLocked() }
        val ms = (System.nanoTime() - startedAt) / 1_000_000
        BoxLog.i(TAG) { "find: done in $ms ms, ${merge.size} computers (nsd $nsdCount, sweep ${stats?.open ?: 0} open of ${stats?.probed ?: 0})" }
    }

    private fun norm(n: String) = n.trim().trimEnd('.').lowercase().removeSuffix(".local")

    private companion object {
        const val TAG = "ComputerFinder"
        const val NAME_LOOKUPS_PARALLEL = 8
    }
}

/** The platform side: `LanAddressFinder`, `ConnectivityManager`, `WifiManager`, `NsdManager`. */
internal class AndroidFinderEnvironment(private val context: Context, private val lan: LanAddressFinder) : FinderEnvironment {
    private val cm get() = context.getSystemService(ConnectivityManager::class.java)

    override fun lanAddress(): String? = lan.find()

    override fun vpnUp(): Boolean = lan.vpnActive()

    override fun networkUp(): Boolean = try {
        val c = cm
        c?.getNetworkCapabilities(c.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true ||
            lan.find() != null
    } catch (e: SecurityException) {
        true                                      // unknown: do not claim "no network"
    }

    @SuppressLint("WifiManagerLeak") // application context
    override fun acquireMulticastLock(): AutoCloseable {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return AutoCloseable { }
        return try {
            val lock = wifi.createMulticastLock("box-find").apply { setReferenceCounted(false); acquire() }
            AutoCloseable { try { if (lock.isHeld) lock.release() } catch (e: RuntimeException) { /* already released */ } }
        } catch (e: RuntimeException) {           // SecurityException without CHANGE_WIFI_MULTICAST_STATE
            BoxLog.w(TAG, "find: multicast lock unavailable (${e.javaClass.simpleName})")
            AutoCloseable { }
        }
    }

    override fun sockets(lanAddress: String): LanSockets = NetworkLanSockets(networkOf(lanAddress))

    override fun nsd(): Flow<Sighting> {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return emptyFlow()
        return NsdFinder(AndroidNsdApi(nsd)).sightings()
    }

    /** The non-VPN Wi-Fi/Ethernet network whose link addresses include [address]. */
    @Suppress("DEPRECATION") // allNetworks: as LanAddressFinderImpl, the pre-31 way to see every network
    private fun networkOf(address: String): Network? = try {
        val c = cm ?: return null
        c.allNetworks.firstOrNull { n ->
            val caps = c.getNetworkCapabilities(n) ?: return@firstOrNull false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                c.getLinkProperties(n)?.linkAddresses?.any { (it.address as? Inet4Address)?.hostAddress == address } == true
        }
    } catch (e: SecurityException) {
        null
    }

    private companion object { const val TAG = "ComputerFinder" }
}
