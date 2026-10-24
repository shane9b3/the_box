// WP-S1 replaces this stub (docs/design/m6-storage-locations.md §3.1, §8; storage locations commit 0, §17).
package app.thebox.data.storage

import app.thebox.core.storage.LocationStatus
import app.thebox.core.storage.LocationTestResult
import app.thebox.core.storage.SmbAddress
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StorageLocation
import app.thebox.core.storage.StorageLocations
import app.thebox.core.storage.StoragePurpose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The location list, the per-purpose choice, the passwords (`SecureBlobStore` "smb-<id>", never in the `storage`
 * domain) and the status per location (§2 "Ownership").
 *
 * Commit 0: no locations; everything is chosen on this device. Writes are WP-S1's.
 */
@Singleton
class StorageLocationsImpl @Inject constructor() : StorageLocations {
    private val _status = MutableStateFlow<List<LocationStatus>>(emptyList())
    override val status: StateFlow<List<LocationStatus>> get() = _status

    override fun chosen(purpose: StoragePurpose): String = StorageConstants.INTERNAL_ID

    override suspend fun choose(purpose: StoragePurpose, locationId: String): Unit = TODO("WP-S1")

    override suspend fun saveSmb(address: SmbAddress, name: String, password: CharArray?, test: LocationTestResult?): StorageLocation = TODO("WP-S1")

    override suspend fun updateSignIn(locationId: String, user: String?, domain: String?, password: CharArray?, guest: Boolean): Unit = TODO("WP-S1")

    override suspend fun rename(locationId: String, name: String): Unit = TODO("WP-S1")

    override suspend fun remove(locationId: String): Unit = TODO("WP-S1")
}
