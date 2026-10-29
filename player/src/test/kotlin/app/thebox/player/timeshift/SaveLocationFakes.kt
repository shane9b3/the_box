// WP-S2 test support (docs/design/m6-storage-locations.md §16.2): remote targets over FakeRemoteLink.
package app.thebox.player.timeshift

import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.timeshift.RemoteRingStorage
import app.thebox.core.playback.timeshift.RingTarget
import app.thebox.core.playback.timeshift.RingTargetChoice
import app.thebox.core.storage.LocationFallback
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StorageKind
import app.thebox.core.storage.StorageLimits
import app.thebox.core.storage.smb.FakeRemoteLink
import app.thebox.core.storage.smb.SmbRingStorage
import java.io.File

/**
 * An SMB choice whose `open` builds WP-S1's real [SmbRingStorage] over [link] and records it in [opened]. (Q-D9, closure
 * fix pass 2026-10-28: the commit-0 `NoIoRemoteStorage` stand-in and its guards are gone; every test drives the real one.)
 */
fun smbChoice(
    link: FakeRemoteLink,
    clock: MonoClock,
    opened: MutableList<RemoteRingStorage>,
    id: String = "smb-1",
    ready: Boolean = true,
    available: Long = 1L shl 40,
): RingTargetChoice = RingTargetChoice(
    target = RingTarget.Remote(id, StorageKind.SMB, StorageLimits.SMB, "NAS", availableBytes = available, ready = ready,
        open = { name, cap -> SmbRingStorage(link, "${StorageConstants.TIMESHIFT_DIR_SMB}/$name", cap, StorageLimits.SMB, clock).also { opened += it } }),
    chosenId = id, chosenKind = StorageKind.SMB, fallback = null, lastKnownGood = !ready,
)

/** SMB chosen, this device used at tune time (§5.1), e.g. UNREACHABLE. */
fun smbFellBackChoice(dir: File, reason: LocationFallback, total: Long = 32_000_000_000L): RingTargetChoice = RingTargetChoice(
    target = RingTarget.Local(StorageConstants.INTERNAL_ID, StorageKind.INTERNAL, StorageLimits.internal(total), "NAS", dir, total, 10_000_000_000L),
    chosenId = "smb-1", chosenKind = StorageKind.SMB, fallback = reason, lastKnownGood = false,
)

fun internalChoice(dir: File, total: Long = 32_000_000_000L): RingTargetChoice = RingTargetChoice(
    target = RingTarget.Local(StorageConstants.INTERNAL_ID, StorageKind.INTERNAL, StorageLimits.internal(total), "", dir, total, 10_000_000_000L),
    chosenId = StorageConstants.INTERNAL_ID, chosenKind = StorageKind.INTERNAL, fallback = null, lastKnownGood = false,
)

fun volumeChoice(filesDir: File, uuid: String = "ABCD-1234", total: Long = 64L shl 30): RingTargetChoice = RingTargetChoice(
    target = RingTarget.Local("${StorageConstants.VOLUME_ID_PREFIX}$uuid", StorageKind.VOLUME, StorageLimits.VOLUME, "USB drive", filesDir, total, 32L shl 30),
    chosenId = "${StorageConstants.VOLUME_ID_PREFIX}$uuid", chosenKind = StorageKind.VOLUME, fallback = null, lastKnownGood = false,
)
