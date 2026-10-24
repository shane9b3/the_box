// WP-S2 replaces this stub (docs/design/m6-storage-locations.md §3.2, §6; storage locations commit 0, §17).
package app.thebox.player.timeshift

import app.thebox.core.storage.VolumeCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * USB drives and SD cards listed by `getExternalFilesDirs(null)` (index > 0, mounted, removable), refreshed on
 * `box-ts-sweep` at start and on every `MEDIA_*` broadcast; the volume sweep; removal (`stopRecording(STORAGE_EVICTED)`
 * on the receiver, `storage.close()` posted to `box-ts-discard`). Started by `TimeshiftStartup.onAppStart`.
 *
 * Commit 0: lists no volume, so no USB option is shown.
 */
@Singleton
internal class VolumeWatcher @Inject constructor() : VolumeCatalog {
    private val _volumes = MutableStateFlow<List<VolumeCatalog.Volume>>(emptyList())
    override val volumes: StateFlow<List<VolumeCatalog.Volume>> get() = _volumes
}
