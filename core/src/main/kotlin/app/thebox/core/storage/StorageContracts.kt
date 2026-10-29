// Contract (M6 storage locations commit 0): docs/design/m6-storage-locations.md §12.1. Verbatim; changes go through the
// lead. The browse data classes the design leaves to commit 0 ("listed in full by commit 0 from §7") are at the end.
package app.thebox.core.storage

import app.thebox.core.playback.timeshift.TimeshiftConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Where a location lives (§3.1). Append-only; [SAF_TREE] is reserved for v2 and never produced. */
@Serializable enum class StorageKind { INTERNAL, VOLUME, SMB, SAF_TREE }

/** What a location is chosen for (§0 item 1). Append-only; [RECORDING] is v2 (§11). */
@Serializable enum class StoragePurpose { TIMESHIFT, RECORDING }

/**
 * A folder in a computer's shared folder (§3.3). [path] is `/`-separated (smbj gets `\`); [lastAddress] is the IPv4 the
 * name last resolved to. **Never** a password (§8): that is `SecureBlobStore` "smb-<locationId>". [toString] prints
 * whether a user is set, never the name.
 */
@Serializable
data class SmbAddress(
    val host: String, val port: Int = StorageConstants.SMB_DEFAULT_PORT, val share: String, val path: String = "",
    val user: String? = null, val domain: String? = null, val guest: Boolean = false, val lastAddress: String? = null,
) { override fun toString() = "SmbAddress(host=$host, port=$port, share=$share, path=$path, user=${if (user == null) "null" else "<set>"}, guest=$guest)" }

/** The Test's grade (§7.8). */
@Serializable enum class TestVerdict { GOOD_4K, GOOD_HD, SLOW, TOO_SLOW, FAILED }

/**
 * One Test run (§7.8), stored as `StorageLocation.lastTest`. Rates are bytes/s, [wakeMs] the first 4 KiB write (excluded
 * from every rate), [atWallMs] device wall time.
 *
 * (Commit 0 addition, see the implementation record) [error] is the cause of a [TestVerdict.FAILED] run (§7.7 / CRC /
 * space), so the page can show "the cause | its next step"; null otherwise.
 *
 * (C-N7, closure fix pass; additive) [stop] is set when the Test ended itself before a verdict: [TestStopReason.TOOK_TOO_LONG]
 * when the whole run passed its deadline (`StorageConstants.TEST_RUN_MAX_MS`). Such a run is FAILED with no [error]; it
 * is never stored as `lastTest`.
 */
@Serializable
data class LocationTestResult(val verdict: TestVerdict, val writeBps: Long, val readBps: Long, val rttMs: Int,
                              val wakeMs: Int, val freeBytes: Long, val wifi: Boolean, val atWallMs: Long,
                              val error: BrowseError? = null, val stop: TestStopReason? = null)

/** (C-N7, closure fix pass) Why a Test stopped itself ("Stopped: NAS took too long."). Append-only. */
@Serializable enum class TestStopReason { TOOK_TOO_LONG }

/** A stored location (§3.1). `INTERNAL` is implicit ([StorageConstants.INTERNAL_ID]) and never in the list. */
@Serializable
data class StorageLocation(
    val id: String,                    // "vol-<uuid>" | "smb-<uuid>"; INTERNAL is implicit ("internal")
    val kind: StorageKind,
    val name: String,                  // user-editable (U-N1), ≤ LABEL_MAX_CHARS
    val volumeUuid: String? = null,
    val smb: SmbAddress? = null,
    val lastTest: LocationTestResult? = null,
)

/**
 * The backed-up `storage` settings domain (§8 "Backup", [StorageDomain][app.thebox.core.settings.state.StorageDomain]).
 * Defaults are today's behaviour: no locations, rewind on this device, so a store without the document (every install
 * before this feature) reads as it always did.
 */
@Serializable
data class StoragePrefs(
    val locations: List<StorageLocation> = emptyList(),
    val timeshiftLocationId: String = StorageConstants.INTERNAL_ID,
    val recordingLocationId: String? = null,     // v2
)

/** Why a cycle saves on this device instead of the chosen place (§5.1, §10 SAVE_LOCATION_FALLBACK). Append-only. */
enum class LocationFallback { NOT_READY_YET, UNREACHABLE, UNSTABLE, SIGN_IN_NEEDED, NOT_CONNECTED, NO_SPACE_THERE }   // append-only

/** Why a remote/USB ring stopped saving (§4.3 classes, §10 wording). Append-only. */
enum class StorageFailure { UNREACHABLE, FULL, ACCESS, REMOVED, SLOW }                                                 // append-only

/** Per-kind sizing (§3.4). Values are constants, defined here in commit 0 (A-S13). */
data class StorageLimits(
    val maxRingBytes: Long, val reserveBytes: Long, val countsWear: Boolean, val preallocate: Boolean,
    val autoLengthLarge: Boolean, val ramBlocks: Int, val ramBlocksSmall: Int,
) {
    /**
     * Remote kinds: [ramBlocks] when memoryClass ≥ REMOTE_POOL_MEMORY_CLASS_MB, else [ramBlocksSmall] (lead ruling 5).
     * The internal kind (the only one that [countsWear]) keeps today's rule: [ramBlocksSmall] on an `isLowRamDevice`.
     */
    fun ramBlocks(memoryClassMb: Int, lowRamDevice: Boolean): Int =
        if (countsWear) { if (lowRamDevice) ramBlocksSmall else ramBlocks }
        else if (memoryClassMb >= TimeshiftConstants.REMOTE_POOL_MEMORY_CLASS_MB) ramBlocks else ramBlocksSmall

    /**
     * (Commit 0 helper, §3.4 "Reserve enforcement", A-S4) What a **non-internal** storage reports from `availableBytes()`
     * so the flusher's fixed halt at `FREE_SPACE_RESERVE_MIN / 2` (`TsRingImpl`) keeps this kind's [reserveBytes]:
     * `free − (reserve − FREE_SPACE_RESERVE_MIN / 2)`, never below 0. The internal storage reports its free space as is.
     */
    fun reportedAvailableBytes(freeBytes: Long): Long =
        (freeBytes - (reserveBytes - TimeshiftConstants.FREE_SPACE_RESERVE_MIN / 2).coerceAtLeast(0L)).coerceAtLeast(0L)

    companion object {
        /** This device (today's rules, TS §8.2): reserve `max(1 GiB, total × 0.10)`, Auto 1 h above 16 GB, 64/32 blocks. */
        fun internal(totalBytes: Long): StorageLimits = StorageLimits(
            maxRingBytes = TimeshiftConstants.MAX_RING_BYTES,
            reserveBytes = maxOf(TimeshiftConstants.FREE_SPACE_RESERVE_MIN, (totalBytes * TimeshiftConstants.FREE_SPACE_RESERVE_FRACTION).toLong()),
            countsWear = true, preallocate = true,
            autoLengthLarge = totalBytes > TimeshiftConstants.SMALL_STORAGE_BYTES,
            ramBlocks = TimeshiftConstants.RING_RAM_BLOCKS, ramBlocksSmall = TimeshiftConstants.RING_RAM_BLOCKS_LOW_RAM,
        )

        /** A USB drive or SD card (§3.2): under the FAT32 file limit, no preallocation, no wear cap, Auto 1 h. */
        val VOLUME: StorageLimits = StorageLimits(
            maxRingBytes = StorageConstants.VOLUME_MAX_RING_BYTES, reserveBytes = StorageConstants.VOLUME_FREE_SPACE_RESERVE,
            countsWear = false, preallocate = false, autoLengthLarge = true,
            ramBlocks = TimeshiftConstants.RING_RAM_BLOCKS_EXTERNAL, ramBlocksSmall = TimeshiftConstants.RING_RAM_BLOCKS_EXTERNAL_SMALL,
        )

        /** A network folder (§3.3): 32 GiB cap, flat 2 GiB reserve, no preallocation (no SET_INFO, A-S6), Auto 1 h. */
        val SMB: StorageLimits = StorageLimits(
            maxRingBytes = StorageConstants.NETWORK_MAX_RING_BYTES, reserveBytes = StorageConstants.NETWORK_FREE_SPACE_RESERVE,
            countsWear = false, preallocate = false, autoLengthLarge = true,
            ramBlocks = TimeshiftConstants.RING_RAM_BLOCKS_EXTERNAL, ramBlocksSmall = TimeshiftConstants.RING_RAM_BLOCKS_EXTERNAL_SMALL,
        )

        /** The limits of [kind]; [totalBytes] is the internal filesystem's (ignored for the other kinds). */
        fun of(kind: StorageKind, totalBytes: Long): StorageLimits = when (kind) {
            StorageKind.INTERNAL -> internal(totalBytes)
            StorageKind.VOLUME, StorageKind.SAF_TREE -> VOLUME
            StorageKind.SMB -> SMB
        }
    }
}

/** What Settings shows per location (§9.2 Status). */
enum class LocationState { READY, CONNECTING, UNREACHABLE, SIGN_IN_NEEDED, NOT_CONNECTED, DEMOTED, UNTESTED }
data class LocationStatus(val location: StorageLocation, val state: LocationState, val freeBytes: Long? = null, val failure: StorageFailure? = null)

/** :data (StorageLocationsImpl). Reads main-safe; writes suspend on IO. */
interface StorageLocations {
    val status: StateFlow<List<LocationStatus>>
    fun chosen(purpose: StoragePurpose): String
    suspend fun choose(purpose: StoragePurpose, locationId: String)
    /** Stores [password] in SecureBlobStore ("smb-<id>"), zeroes it and the UTF-8 copy (A-S11). */
    suspend fun saveSmb(address: SmbAddress, name: String, password: CharArray?, test: LocationTestResult?): StorageLocation
    suspend fun updateSignIn(locationId: String, user: String?, domain: String?, password: CharArray?, guest: Boolean)
    suspend fun rename(locationId: String, name: String)
    suspend fun remove(locationId: String)
    /**
     * (U-S5, save-location review 2026-10-24; additive) Stores [result] as [locationId]'s `lastTest` (§7.8: the verdict,
     * rates, RTT, `wakeMs` and the time), so Status and "Use for rewind" read the latest Test. Default: a no-op (fakes).
     */
    suspend fun recordTest(locationId: String, result: LocationTestResult) = Unit
}

/**
 * (Commit 0 addition, see the implementation record) `:app`'s answer to `PlayerEffects.fallbackToastDue` (§5.1, §10):
 * `:ui`'s PlayerEffectsImpl delegates to it through `EffectDelegates`; WP-S4's FallbackToastPolicy binds it. The
 * commit-0 binding answers false (no fallback ever happens before the WPs land).
 */
fun interface FallbackToastGate {
    fun due(locationId: String, reason: LocationFallback): Boolean

    /**
     * (U-S9, save-location review 2026-10-24; additive) [locationId] signed in again, or was removed: its "sign in again"
     * toast starts a fresh episode (§5.1 "the first 3 processes after it starts"). Default: a no-op.
     */
    fun signedIn(locationId: String) = Unit
}

/** USB volumes (§6). :player (VolumeWatcher) implements; :app binds it for :data. */
interface VolumeCatalog {
    data class Volume(val uuid: String, val label: String, val dir: java.io.File, val totalBytes: Long, val freeBytes: Long)
    val volumes: StateFlow<List<Volume>>
}

/** Browse, sign-in and Test for the pages (§7). :data implements; every call runs on box-smb-browse. */
interface NetworkFolders {
    fun find(): Flow<FindState>       // cold; collection = the page's lifetime (lock, sockets)
    suspend fun open(address: SmbAddress, password: CharArray?): BrowseResult
    suspend fun shares(session: BrowseHandle): ShareListing
    suspend fun folders(session: BrowseHandle, share: String, path: String): FolderListing
    suspend fun test(session: BrowseHandle, share: String, path: String, progress: (TestProgress) -> Unit): LocationTestResult
    fun close(session: BrowseHandle)                           // zeroes the password, closes the browse connection
}

// ==================================================================== browse data (§7, listed in full by commit 0)

/** Where a found computer's name came from (§7.1 "Merge": NSD instance, then NetBIOS, then reverse DNS). */
enum class NameSource { NSD, NETBIOS, REVERSE_DNS, NONE }

/**
 * One computer on `FindComputers` (§7.1). [name] null = "No name" (the address is the label). [address] is IPv4.
 * [saved] = a stored location's host (`Badge.SAVED`, U-S9). [apple] = NSD reported an Apple host (the §7.7 Mac hint).
 */
data class FoundComputer(
    val name: String?, val address: String, val source: NameSource, val saved: Boolean = false,
    val apple: Boolean = false, val port: Int = StorageConstants.SMB_DEFAULT_PORT,
)

/**
 * The Find page's state (§7.1, §9.2). [searching] ends as soon as the sweep finishes; [scannedNetwork] is "192.168.1.x"
 * (U-N2), null when there is no usable LAN address; [vpnUp] shows the amber VPN row; [error] is NO_NETWORK when
 * there is no network at all.
 */
data class FindState(
    val computers: List<FoundComputer>, val searching: Boolean, val scannedNetwork: String?, val vpnUp: Boolean,
    val error: BrowseError? = null,
)

/**
 * The §7.7 causes. [STORAGE] carries a [StorageFailure] (UNREACHABLE: connect timeout/refused; ACCESS: a share or folder
 * refused; FULL: no space in the Test). The others: [AUTH] `STATUS_LOGON_FAILURE`; [GUEST_REFUSED] guests refused or
 * guest signing required; [SMB1] the server speaks SMB1 only; [NO_NETWORK]; [NOT_FOUND] a typed share or path that does
 * not exist ([BrowseError.deepestPath]); [LIST_FAILED] the share listing RPC failed (typed name offered); [WRITE_REFUSED]
 * the Test could read but not write; [DATA_MISMATCH] the Test read back different bytes.
 */
@Serializable
enum class BrowseErrorKind { STORAGE, AUTH, GUEST_REFUSED, SMB1, NO_NETWORK, NOT_FOUND, LIST_FAILED, WRITE_REFUSED, DATA_MISMATCH }

/**
 * A browse or Test error: reason + next step on screen (§7.7, Must). [deepestPath] is the deepest existing folder for
 * [BrowseErrorKind.NOT_FOUND] (the page lands there, §7.2). [vpnUp] appends the VPN hint to UNREACHABLE.
 * [needBytes]/[freeBytes] fill the FULL text ("NAS has only 1.1 GB free; rewind needs 2.1 GB").
 */
@Serializable
data class BrowseError(
    val kind: BrowseErrorKind, val failure: StorageFailure? = null, val deepestPath: String? = null,
    val vpnUp: Boolean = false, val freeBytes: Long? = null, val needBytes: Long? = null,
)

/** How a browse session signed in (§7.3: anonymous, then guest, then asks). */
enum class SignInMode { ANONYMOUS, GUEST, USER }

/**
 * A browse session in [NetworkFolders] (on the browse `SMBClient`, never the ring's). [id] is opaque. It holds **no**
 * credential: the password stays in `:data`, zeroed by [NetworkFolders.close]. [address] has the user name, never printed.
 */
data class BrowseHandle(val id: Long, val address: SmbAddress, val mode: SignInMode)

/** Answer of [NetworkFolders.open]. */
sealed interface BrowseResult {
    data class Opened(val handle: BrowseHandle) : BrowseResult
    /** Anonymous and guest both failed (or the server wants signing for guests): ask (§7.3). [error] for a refused try. */
    data class NeedsSignIn(val error: BrowseError? = null) : BrowseResult
    data class Failed(val error: BrowseError) : BrowseResult
}

/** Disk shares A–Z, `$` names and IPC$ dropped (§7.5). [error] (LIST_FAILED, AUTH, …) still offers a typed name. */
data class ShareListing(val shares: List<String>, val error: BrowseError? = null)

/**
 * Sub-folders of [path] (§7.6): directories only, hidden/system and SMB_SKIP_FOLDERS skipped, case-insensitive order,
 * at most SMB_LIST_MAX_ENTRIES ([truncated]). [error] NOT_FOUND carries the deepest existing path.
 */
data class FolderListing(val share: String, val path: String, val folders: List<String>, val truncated: Boolean = false, val error: BrowseError? = null)

/** The Test's phases (§7.8). */
enum class TestPhase { CONNECTING, WAKING, WRITING, READING, RTT, DONE }

/** Progress of a running Test: "9 of 16 MB" ([doneBytes] of [totalBytes]) in the WRITING and READING phases. */
data class TestProgress(val phase: TestPhase, val doneBytes: Long = 0L, val totalBytes: Long = 0L)
