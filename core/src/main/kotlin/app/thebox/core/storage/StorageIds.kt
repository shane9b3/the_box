// Q-U3 (save-location closure fix pass, 2026-10-28): the one location id → kind derivation.
package app.thebox.core.storage

/**
 * The kind a location id names, by its prefix (§3.1): `vol-…` a USB drive, `smb-…` a network folder, anything else this
 * device. For callers that hold only the id (a toast's `LocalBufferState.locationId`, the Pause and rewind page's
 * choice before the location list is read).
 */
fun storageKindOf(locationId: String): StorageKind = when {
    locationId.startsWith(StorageConstants.VOLUME_ID_PREFIX) -> StorageKind.VOLUME
    locationId.startsWith(StorageConstants.SMB_ID_PREFIX) -> StorageKind.SMB
    else -> StorageKind.INTERNAL
}
