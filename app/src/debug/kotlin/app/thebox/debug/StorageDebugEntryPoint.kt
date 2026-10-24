package app.thebox.debug

import app.thebox.core.playback.timeshift.RingTargetSource
import app.thebox.core.storage.StorageLocations
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * (Save-location integration, 2026-10-24) The storage graph for the device suite `SaveLocationTest`
 * (docs/design/m6-storage-locations.md §16.4): store and choose a location in-process, and read the ring target the next
 * tune will get. Debug builds only.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface StorageDebugEntryPoint {
    fun storageLocations(): StorageLocations
    fun ringTargets(): RingTargetSource
    fun keeper(): app.thebox.data.storage.smb.SmbSessionKeeper
}
