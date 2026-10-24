// Storage locations commit 0 only (docs/design/m6-storage-locations.md §2 "Module placement", §12.4, A-S5).
package app.thebox.player.timeshift

import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.RingTarget
import app.thebox.core.playback.timeshift.RingTargetChoice
import app.thebox.core.playback.timeshift.RingTargetSource
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.StorageKind
import app.thebox.core.storage.StorageLimits
import app.thebox.core.storage.StoragePurpose
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [RingTargetSource] `:player` uses while `:data` binds none (`@BindsOptionalOf` in [TimeshiftModule]): always this
 * device, today's directory and sizing, never a fallback. With it the app behaves exactly as before the storage-locations
 * feature. [current] does no network I/O and never waits (§5.1 Must); the StatFs figures are the same local syscall the
 * factory's sizing makes today.
 */
@Singleton
internal class InternalRingTargets @Inject constructor(private val storage: TimeshiftStorageImpl) : RingTargetSource {
    override fun current(purpose: StoragePurpose): RingTargetChoice {
        val total = storage.totalBytes()
        return RingTargetChoice(
            target = RingTarget.Local(
                locationId = StorageConstants.INTERNAL_ID, kind = StorageKind.INTERNAL, limits = StorageLimits.internal(total),
                name = "", dir = storage.dir, totalBytes = total, availableBytes = storage.availableBytes(),
            ),
            chosenId = StorageConstants.INTERNAL_ID, chosenKind = StorageKind.INTERNAL, fallback = null, lastKnownGood = false,
        )
    }

    override fun warm() = Unit

    override fun onStorageFailure(locationId: String, stop: RecordStop, failure: StorageFailure?, sinceFirstWriteMs: Long) = Unit

    /** Never asked: this source has no remote target. */
    override fun nextRemoteFileName(locationId: String): String = throw UnsupportedOperationException("no remote targets")

    companion object {
        /**
         * The default of `TimeshiftGrant.choice` (a grant built before WP-S2 copies the real choice in): this device, sizes
         * unknown (0). Only the location id and kind are meaningful.
         */
        val UNSIZED: RingTargetChoice = RingTargetChoice(
            target = RingTarget.Local(
                locationId = StorageConstants.INTERNAL_ID, kind = StorageKind.INTERNAL, limits = StorageLimits.internal(0L),
                name = "", dir = File(""), totalBytes = 0L, availableBytes = 0L,
            ),
            chosenId = StorageConstants.INTERNAL_ID, chosenKind = StorageKind.INTERNAL, fallback = null, lastKnownGood = false,
        )
    }
}
