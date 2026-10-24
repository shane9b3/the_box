// WP-S3 replaces this stub (docs/design/m6-storage-locations.md §7.1; storage locations commit 0, §17). The interface is
// here so WP-S1's NetworkFoldersImpl compiles against it; WP-S3 owns both.
package app.thebox.data.storage.discovery

import app.thebox.core.storage.FindState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finding computers (§7.1, lead ruling 3): NsdManager `_smb._tcp`, the 445 sweep of the Wi-Fi/Ethernet /24 (never the
 * VPN), NetBIOS node-status / reverse-DNS names. [search] is cold: collecting it holds the `MulticastLock` and the
 * sockets; cancelling it stops everything within 500 ms (Must); it ends by itself after DISCOVERY_TOTAL_MS.
 */
interface ComputerFinder {
    fun search(): Flow<FindState>
}

/** Commit 0: finds nothing (manual entry still works once WP-S1 lands). */
@Singleton
class ComputerFinderImpl @Inject constructor() : ComputerFinder {
    override fun search(): Flow<FindState> =
        flowOf(FindState(computers = emptyList(), searching = false, scannedNetwork = null, vpnUp = false))
}
