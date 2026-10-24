// WP-S1 (docs/design/m6-storage-locations.md §5.1, §5.4, §3.5; lead ruling 2; A-B1, A-N4, A-N5).
package app.thebox.data.storage

import android.os.StatFs
import app.thebox.core.common.BoxLog
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.RingTarget
import app.thebox.core.playback.timeshift.RingTargetChoice
import app.thebox.core.playback.timeshift.RingTargetSource
import app.thebox.core.playback.timeshift.TimeshiftConstants
import app.thebox.core.storage.LocationFallback
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.StorageKind
import app.thebox.core.storage.StorageLimits
import app.thebox.core.storage.StorageLocation
import app.thebox.core.storage.StoragePurpose
import app.thebox.core.storage.VolumeCatalog
import app.thebox.core.storage.smb.SmbRingStorage
import app.thebox.data.storage.smb.KeeperSnapshot
import app.thebox.data.storage.smb.KeeperState
import app.thebox.data.storage.smb.SmbSessionKeeper
import app.thebox.data.storage.smb.SmbSweeper
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The filesystem figures of a local directory (`StatFs`; a seam for the JVM tests). */
fun interface LocalSpace {
    /** (total, available) bytes of the filesystem holding [dir] (or its nearest existing parent). */
    fun of(dir: File): Pair<Long, Long>

    companion object {
        val STATFS = LocalSpace { dir ->
            val probe = generateSequence(dir) { it.parentFile }.firstOrNull { it.exists() } ?: dir
            try { val s = StatFs(probe.path); s.totalBytes to s.availableBytes } catch (_: Exception) { 0L to 0L }
        }
    }
}

/**
 * The per-cycle target choice (§5.1). [current] **never blocks and does no network I/O** (Must, A-B1, A-N4): it reads the
 * chosen id and the keeper's [KeeperSnapshot] from memory, makes no smbj call, and the SMB storage it hands out opens
 * its file lazily on the ring's flusher. The internal and USB figures are the same local `StatFs` the factory's sizing
 * made before this feature.
 *
 * | Chosen | State | Target | fallback |
 * |---|---|---|---|
 * | internal | — | INTERNAL | — |
 * | vol-* | mounted | VOLUME (dir = the volume's files dir; the factory appends `timeshift/`) | — |
 * | vol-* | not listed | INTERNAL | NOT_CONNECTED |
 * | smb-* | sign-in needed (keeper AUTH_FAILED, password missing) | INTERNAL | SIGN_IN_NEEDED |
 * | smb-* | demoted (§5.4) | INTERNAL | UNSTABLE |
 * | smb-* | READY, last good round trip ≤ SMB_FRESH_MS | SMB | — |
 * | smb-* | not READY, last-known-good ≤ SMB_LAST_GOOD_MS, no failure in this process, network up | SMB (lastKnownGood) | — |
 * | smb-* | BACKOFF / failure in this process / no network | INTERNAL | UNREACHABLE |
 * | smb-* | otherwise (still connecting, no last-known-good) | INTERNAL | NOT_READY_YET |
 * | any remote | cached free < MIN_RING_BYTES + reserve | INTERNAL | NO_SPACE_THERE |
 *
 * [onStorageFailure] is synchronous (A-N5): outside the start-of-cycle grace it demotes the folder before the stop is
 * published, so the next [current] already sees it.
 */
class StorageTargetsImpl(
    private val locations: StorageLocationsImpl,
    private val keeper: SmbSessionKeeper,
    private val volumes: VolumeCatalog,
    private val deviceId: DeviceIdentity,
    private val internalDir: File,
    private val clock: MonoClock,
    private val wallMs: () -> Long,
    private val pid: Int,
    private val space: LocalSpace = LocalSpace.STATFS,
) : RingTargetSource {
    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    override fun current(purpose: StoragePurpose): RingTargetChoice {
        val chosenId = locations.chosen(purpose)
        if (chosenId == StorageConstants.INTERNAL_ID) return internal(chosenId, StorageKind.INTERNAL, null)
        val loc = locations.location(chosenId) ?: return internal(StorageConstants.INTERNAL_ID, StorageKind.INTERNAL, null)
        return when (loc.kind) {
            StorageKind.VOLUME, StorageKind.SAF_TREE -> volume(loc)
            StorageKind.SMB -> smb(loc)
            StorageKind.INTERNAL -> internal(chosenId, StorageKind.INTERNAL, null)
        }
    }

    override fun warm() = keeper.warm()

    override fun onStorageFailure(locationId: String, stop: RecordStop, failure: StorageFailure?, sinceFirstWriteMs: Long) {
        if (!locationId.startsWith(StorageConstants.SMB_ID_PREFIX)) return
        if (stop != RecordStop.DISK_ERROR && stop != RecordStop.DISK_TOO_SLOW && stop != RecordStop.STORAGE_EVICTED) return
        keeper.onRingFailure()
        // §5.4 grace: a stop within SMB_FIRST_WRITE_GRACE_MS of the cycle's first successful write (or before any) does not demote.
        if (sinceFirstWriteMs < StorageConstants.SMB_FIRST_WRITE_GRACE_MS) {
            BoxLog.i(TAG) { "$stop on $locationId within the start-of-cycle grace: no demotion" }
            return
        }
        keeper.demote(locationId)
    }

    /** C-S3: the flusher switched a cycle on [locationId] to this device; the keeper demotes or re-checks (never blocks). */
    override fun onCreateFailed(locationId: String, failure: StorageFailure?) {
        if (!locationId.startsWith(StorageConstants.SMB_ID_PREFIX)) return
        keeper.onCreateFailed(locationId, failure)
    }

    override fun nextRemoteFileName(locationId: String): String {
        val n = counters.getOrPut(locationId) { AtomicInteger() }.incrementAndGet()
        return SmbSweeper.ringName(deviceId.id, pid, n)
    }

    // ================================================================ per kind

    /** This device. On a fallback, [name] is the chosen place's name, so the toast can say it ("NAS", lead ruling). */
    private fun internal(chosenId: String, chosenKind: StorageKind, fallback: LocationFallback?, name: String = ""): RingTargetChoice {
        val (total, avail) = space.of(internalDir)
        return RingTargetChoice(
            target = RingTarget.Local(StorageConstants.INTERNAL_ID, StorageKind.INTERNAL, StorageLimits.internal(total), name, internalDir, total, avail),
            chosenId = chosenId, chosenKind = chosenKind, fallback = fallback, lastKnownGood = false,
        )
    }

    private fun volume(loc: StorageLocation): RingTargetChoice {
        val v = volumes.volumes.value.firstOrNull { it.uuid == loc.volumeUuid }
            ?: return internal(loc.id, StorageKind.VOLUME, LocationFallback.NOT_CONNECTED, loc.name)
        val dir = v.dir // the factory appends timeshift/ (lead ruling, WP-S2)
        val limits = StorageLimits.VOLUME
        if (v.freeBytes < TimeshiftConstants.MIN_RING_BYTES + limits.reserveBytes) return internal(loc.id, StorageKind.VOLUME, LocationFallback.NO_SPACE_THERE, loc.name)
        return RingTargetChoice(
            target = RingTarget.Local(loc.id, StorageKind.VOLUME, limits, loc.name, dir, v.totalBytes, v.freeBytes),
            chosenId = loc.id, chosenKind = StorageKind.VOLUME, fallback = null, lastKnownGood = false,
        )
    }

    private fun smb(loc: StorageLocation): RingTargetChoice {
        val s = keeper.snapshot.takeIf { it.locationId == loc.id }
        val link = keeper.linkFor(loc) // no I/O: makes the link object on first use
        val now = clock.nowMs()
        val state = s?.state ?: KeeperState.IDLE
        if (state != KeeperState.READY || !link.isReady) keeper.warm()

        if (state == KeeperState.AUTH_FAILED || locations.signInNeeded(loc.id)) return fallback(loc, LocationFallback.SIGN_IN_NEEDED)
        // C-S3: a folder that refused the ring file falls back with that reason (the switch's toast already said it).
        if (s != null && demoted(s, now)) return fallback(loc, when (s.demotedFailure) {
            StorageFailure.ACCESS -> LocationFallback.SIGN_IN_NEEDED
            StorageFailure.FULL -> LocationFallback.NO_SPACE_THERE
            StorageFailure.REMOVED -> LocationFallback.UNREACHABLE
            else -> LocationFallback.UNSTABLE
        })

        val ready = state == KeeperState.READY && link.isReady && s != null &&
            s.lastGoodMonoMs != KeeperSnapshot.NEVER && now - s.lastGoodMonoMs <= StorageConstants.SMB_FRESH_MS
        val lkg = !ready && (s == null || !s.failureSeen) && (s?.networkUp ?: true) && keeper.lastKnownGoodWall(loc.id)?.let { wallMs() - it in 0..StorageConstants.SMB_LAST_GOOD_MS } == true
        if (!ready && !lkg) {
            val unreachable = state == KeeperState.BACKOFF || s?.failureSeen == true || s?.networkUp == false
            return fallback(loc, if (unreachable) LocationFallback.UNREACHABLE else LocationFallback.NOT_READY_YET)
        }

        val limits = StorageLimits.SMB
        val free = link.cachedFree(StorageConstants.SMB_SPACE_MAX_AGE_MS)
        if (free != null && free < TimeshiftConstants.MIN_RING_BYTES + limits.reserveBytes) return fallback(loc, LocationFallback.NO_SPACE_THERE)
        val dir = SmbSessionKeeper.ringDir(loc)
        return RingTargetChoice(
            target = RingTarget.Remote(
                locationId = loc.id, kind = StorageKind.SMB, limits = limits, name = loc.name,
                availableBytes = free ?: Long.MAX_VALUE, ready = ready,
                open = { fileName, capacity -> SmbRingStorage(link, "$dir/$fileName", capacity, limits, clock) }, // no I/O (A-B1)
            ),
            chosenId = loc.id, chosenKind = StorageKind.SMB, fallback = null, lastKnownGood = !ready,
        )
    }

    private fun fallback(loc: StorageLocation, why: LocationFallback) = internal(loc.id, loc.kind, why, loc.name)

    private fun demoted(s: KeeperSnapshot, now: Long): Boolean {
        val until = s.demotedUntilMono
        if (until == KeeperSnapshot.NEVER) return false
        // Preferred again after the window, a READY and one more good round trip (the keepalive) after it (§5.4).
        return now < until || s.lastGoodMonoMs < until
    }

    companion object {
        private const val TAG = "StorageTargets"
    }
}
