// Storage locations commit 0 (docs/design/m6-storage-locations.md §2, §17): the stub bindings. WP-S1 owns this file and
// adds the RingTargetSource binding (StorageTargetsImpl), the keeper and the `storage` domain registration.
package app.thebox.data.storage

import app.thebox.core.storage.NetworkFolders
import app.thebox.core.storage.StorageLocations
import app.thebox.data.storage.discovery.ComputerFinder
import app.thebox.data.storage.discovery.ComputerFinderImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Commit 0 binds no `RingTargetSource`, so `:player` uses `InternalRingTargets` and the app saves rewind on this device
 * exactly as before (A-S5).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {
    @Binds abstract fun locations(impl: StorageLocationsImpl): StorageLocations
    @Binds abstract fun folders(impl: NetworkFoldersImpl): NetworkFolders
    @Binds abstract fun finder(impl: ComputerFinderImpl): ComputerFinder
}
