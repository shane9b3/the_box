// Storage locations commit 0 (docs/design/m6-storage-locations.md §16, §17 "Integration order": WP-S2 against
// FakeRemoteLink, WP-S4 against fake StorageLocations / NetworkFolders / PhoneLink).
package app.thebox.core.storage.fakes

import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.RingTarget
import app.thebox.core.playback.timeshift.RingTargetChoice
import app.thebox.core.playback.timeshift.RingTargetSource
import app.thebox.core.storage.BrowseHandle
import app.thebox.core.storage.BrowseResult
import app.thebox.core.storage.FindState
import app.thebox.core.storage.FolderListing
import app.thebox.core.storage.LocationState
import app.thebox.core.storage.LocationStatus
import app.thebox.core.storage.LocationTestResult
import app.thebox.core.storage.NetworkFolders
import app.thebox.core.storage.ShareListing
import app.thebox.core.storage.SignInMode
import app.thebox.core.storage.SmbAddress
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.StorageKind
import app.thebox.core.storage.StorageLimits
import app.thebox.core.storage.StorageLocation
import app.thebox.core.storage.StorageLocations
import app.thebox.core.storage.StoragePurpose
import app.thebox.core.storage.TestPhase
import app.thebox.core.storage.TestProgress
import app.thebox.core.storage.TestVerdict
import app.thebox.core.storage.VolumeCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory [StorageLocations]. Passwords are recorded as copies in [passwords] (a test may assert what was stored) and
 * the caller's arrays are zeroed, as the real one must (A-S11).
 */
class FakeStorageLocations(initial: List<StorageLocation> = emptyList()) : StorageLocations {
    private val _status = MutableStateFlow(initial.map { LocationStatus(it, LocationState.UNTESTED) })
    override val status: StateFlow<List<LocationStatus>> get() = _status
    private val chosenIds = HashMap<StoragePurpose, String>()
    private val nextId = AtomicLong(1)
    val passwords = HashMap<String, String?>()

    override fun chosen(purpose: StoragePurpose): String = chosenIds[purpose] ?: StorageConstants.INTERNAL_ID
    override suspend fun choose(purpose: StoragePurpose, locationId: String) { chosenIds[purpose] = locationId }

    override suspend fun saveSmb(address: SmbAddress, name: String, password: CharArray?, test: LocationTestResult?): StorageLocation {
        val loc = StorageLocation("${StorageConstants.SMB_ID_PREFIX}${nextId.getAndIncrement()}", StorageKind.SMB, name, smb = address, lastTest = test)
        passwords[loc.id] = password?.let { String(it) }
        password?.fill('\u0000')
        _status.value = _status.value + LocationStatus(loc, if (test != null) LocationState.READY else LocationState.UNTESTED)
        return loc
    }

    override suspend fun updateSignIn(locationId: String, user: String?, domain: String?, password: CharArray?, guest: Boolean) {
        passwords[locationId] = password?.let { String(it) }
        password?.fill('\u0000')
        edit(locationId) { l -> l.copy(smb = l.smb?.copy(user = user, domain = domain, guest = guest)) }
    }

    override suspend fun rename(locationId: String, name: String) = edit(locationId) { it.copy(name = name.take(StorageConstants.LABEL_MAX_CHARS)) }

    /** U-S5: the Test's result becomes `lastTest`, as `StorageLocationsImpl` stores it. */
    override suspend fun recordTest(locationId: String, result: LocationTestResult) = edit(locationId) { it.copy(lastTest = result) }

    override suspend fun remove(locationId: String) {
        passwords.remove(locationId)
        _status.value = _status.value.filter { it.location.id != locationId }
        chosenIds.entries.removeAll { it.value == locationId }
    }

    /** Sets a location's state (and optionally its failure and free space) as the keeper would. */
    fun setState(locationId: String, state: LocationState, failure: StorageFailure? = null, freeBytes: Long? = null) {
        _status.value = _status.value.map { if (it.location.id == locationId) it.copy(state = state, failure = failure, freeBytes = freeBytes) else it }
    }

    private fun edit(id: String, f: (StorageLocation) -> StorageLocation) {
        _status.value = _status.value.map { if (it.location.id == id) it.copy(location = f(it.location)) else it }
    }
}

/**
 * Scripted [NetworkFolders]: [findStates] is what `find()` emits; [openAnswer], [sharesAnswer], [foldersAnswer] and
 * [testAnswer] answer the calls; [calls] records them (passwords are never recorded, only whether one was given).
 */
class FakeNetworkFolders : NetworkFolders {
    var findStates: List<FindState> = listOf(FindState(emptyList(), searching = false, scannedNetwork = "192.168.1.x", vpnUp = false))
    var openAnswer: (SmbAddress, Boolean) -> BrowseResult = { a, _ -> BrowseResult.Opened(BrowseHandle(nextHandle.getAndIncrement(), a, SignInMode.ANONYMOUS)) }
    var sharesAnswer: (BrowseHandle) -> ShareListing = { ShareListing(listOf("TV")) }
    var foldersAnswer: (BrowseHandle, String, String) -> FolderListing = { _, share, path -> FolderListing(share, path, emptyList()) }
    var testAnswer: (BrowseHandle, String, String) -> LocationTestResult = { _, _, _ ->
        LocationTestResult(TestVerdict.GOOD_4K, writeBps = 10_500_000, readBps = 11_000_000, rttMs = 4, wakeMs = 0, freeBytes = 1L shl 40, wifi = true, atWallMs = 0L)
    }
    val calls: MutableList<String> = CopyOnWriteArrayList()
    val closed: MutableList<Long> = CopyOnWriteArrayList()
    private val nextHandle = AtomicLong(1)

    override fun find(): Flow<FindState> { calls += "find"; return flowOf(*findStates.toTypedArray()) }

    override suspend fun open(address: SmbAddress, password: CharArray?): BrowseResult {
        calls += "open:${address.host}:${address.share}:${password != null}"
        val r = openAnswer(address, password != null)
        password?.fill('\u0000')
        return r
    }

    override suspend fun shares(session: BrowseHandle): ShareListing { calls += "shares:${session.id}"; return sharesAnswer(session) }

    override suspend fun folders(session: BrowseHandle, share: String, path: String): FolderListing {
        calls += "folders:${session.id}:$share:$path"; return foldersAnswer(session, share, path)
    }

    override suspend fun test(session: BrowseHandle, share: String, path: String, progress: (TestProgress) -> Unit): LocationTestResult {
        calls += "test:${session.id}:$share:$path"
        progress(TestProgress(TestPhase.WRITING, 0L, StorageConstants.SMB_TEST_MAX_BYTES))
        progress(TestProgress(TestPhase.DONE, StorageConstants.SMB_TEST_MAX_BYTES, StorageConstants.SMB_TEST_MAX_BYTES))
        return testAnswer(session, share, path)
    }

    override fun close(session: BrowseHandle) { calls += "close:${session.id}"; closed += session.id }
}

/** A settable [VolumeCatalog]. */
class FakeVolumeCatalog(initial: List<VolumeCatalog.Volume> = emptyList()) : VolumeCatalog {
    override val volumes = MutableStateFlow(initial)
}

/**
 * A scripted [RingTargetSource]: [choice] is what `current()` answers (this device by default); [failures] records
 * `onStorageFailure` calls in order; `nextRemoteFileName` counts per location like §3.5 ("ring-<device>-<pid>-<n>.bin").
 */
class FakeRingTargetSource(
    var choice: RingTargetChoice = internalChoice(),
    private val deviceId: String = "0123456789ab",
    private val pid: Int = 4242,
) : RingTargetSource {
    data class Failure(val locationId: String, val stop: RecordStop, val failure: StorageFailure?, val sinceFirstWriteMs: Long)

    val failures: MutableList<Failure> = CopyOnWriteArrayList()
    val currentCalls = AtomicLong()
    val warms = AtomicLong()
    private val counters = HashMap<String, Int>()

    override fun current(purpose: StoragePurpose): RingTargetChoice { currentCalls.incrementAndGet(); return choice }
    override fun warm() { warms.incrementAndGet() }
    override fun onStorageFailure(locationId: String, stop: RecordStop, failure: StorageFailure?, sinceFirstWriteMs: Long) {
        failures += Failure(locationId, stop, failure, sinceFirstWriteMs)
    }

    /** (C-S3) Every `onCreateFailed`, in order. */
    val createFailures: MutableList<Pair<String, StorageFailure?>> = CopyOnWriteArrayList()
    override fun onCreateFailed(locationId: String, failure: StorageFailure?) { createFailures += locationId to failure }

    @Synchronized override fun nextRemoteFileName(locationId: String): String {
        val n = (counters[locationId] ?: 0) + 1
        counters[locationId] = n
        return "ring-$deviceId-$pid-$n.bin"
    }

    companion object {
        /** This device with [dir] and the given sizes (StatFs-like). */
        fun internalChoice(dir: File = File("build/tmp/timeshift"), total: Long = 32L shl 30, available: Long = 16L shl 30): RingTargetChoice =
            RingTargetChoice(
                RingTarget.Local(StorageConstants.INTERNAL_ID, StorageKind.INTERNAL, StorageLimits.internal(total), "", dir, total, available),
                chosenId = StorageConstants.INTERNAL_ID, chosenKind = StorageKind.INTERNAL, fallback = null, lastKnownGood = false,
            )
    }
}
