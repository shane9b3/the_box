// WP-S1 (docs/design/m6-storage-locations.md §3.1, §6 (A-N7), §8, §9.2 Status; A-S11).
package app.thebox.data.storage

import app.thebox.core.common.BoxLog
import app.thebox.core.common.Redact
import app.thebox.core.common.CredentialFailure
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.TimeshiftLength
import app.thebox.core.settings.state.PlaybackDomain
import app.thebox.core.settings.state.StorageDomain
import app.thebox.core.storage.LocationState
import app.thebox.core.storage.LocationStatus
import app.thebox.core.storage.LocationTestResult
import app.thebox.core.storage.SmbAddress
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.StorageKind
import app.thebox.core.storage.StorageLocation
import app.thebox.core.storage.StorageLocations
import app.thebox.core.storage.StoragePrefs
import app.thebox.core.storage.StoragePurpose
import app.thebox.core.storage.VolumeCatalog
import app.thebox.data.contracts.SecureBlobStore
import app.thebox.data.di.ApplicationScope
import app.thebox.data.di.IoDispatcher
import app.thebox.data.storage.smb.PasswordRead
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/** Device-local directories of the storage feature (`noBackupFilesDir`: device id, last-known-good, seen volumes). */
class StorageDirs(val noBackup: File)

/**
 * The location list, the per-purpose choice, the passwords (`SecureBlobStore` "smb-<id>", never in the `storage`
 * domain) and the status per location (§2 "Ownership").
 *
 * - **Reads are main-safe**: [chosen] and [status] come from memory. Writes suspend on IO.
 * - **Passwords** (§8, A-S11): the caller's `CharArray` is encoded to UTF-8, stored with `putBlob`, and **both arrays are
 *   zeroed**. [readPassword] decodes into a `CharArray` and zeroes the UTF-8 copy. Remove deletes the blob first.
 * - **Restore** (§8): SMB locations whose password is missing read as SIGN_IN_NEEDED; a chosen USB drive this box never
 *   saw resets the choice to this device, the location staying in the list (A-N7).
 * - The keeper publishes its live state per location through [publishKeeper]; [addChangeListener] tells it when the kept
 *   location, its sign-in or the rewind setting changed.
 */
@Singleton
class StorageLocationsImpl @Inject constructor(
    private val store: SettingsStore,
    private val blobs: SecureBlobStore,
    private val volumes: VolumeCatalog,
    private val dirs: StorageDirs,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) : StorageLocations {
    private val prefs: StateFlow<StoragePrefs> = store.flow(StorageDomain) // registers the domain (first use, §12.4)
    private val playback = store.flow(PlaybackDomain)
    private val _status = MutableStateFlow<List<LocationStatus>>(emptyList())
    override val status: StateFlow<List<LocationStatus>> get() = _status

    /** Per location: what the keeper last published. */
    private val keeper = ConcurrentHashMap<String, LocationStatus>()
    /** SMB locations whose stored password is missing or unreadable for good (§8 `getBlob` outcomes). */
    @Volatile private var needsSignIn: Set<String> = emptySet()
    /**
     * Q-E2: locations whose stored password failed to decrypt at a [readPassword] (CORRUPT, KEY_INVALIDATED). [onPrefs]
     * only checks that a password exists (no decrypt), so this keeps them SIGN_IN_NEEDED until the sign-in changes.
     */
    @Volatile private var unreadable: Set<String> = emptySet()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val _signInVersion = MutableStateFlow(0L)
    /** Bumped on every sign-in change (the keeper reconnects with the new one). */
    val signInVersion: StateFlow<Long> = _signInVersion.asStateFlow()
    private val seenFile = File(dirs.noBackup, SEEN_VOLUMES_FILE)
    @Volatile private var seen: Set<String>? = null

    init {
        scope.launch { prefs.collect { onPrefs(it) } }
        scope.launch { playback.collect { notifyListeners() } }
        scope.launch { volumes.volumes.collect { recompute() } }
    }

    // ================================================================ StorageLocations

    override fun chosen(purpose: StoragePurpose): String {
        val p = prefs.value
        val id = when (purpose) {
            StoragePurpose.TIMESHIFT -> p.timeshiftLocationId
            StoragePurpose.RECORDING -> p.recordingLocationId ?: StorageConstants.INTERNAL_ID
        }
        return if (id == StorageConstants.INTERNAL_ID || p.locations.any { it.id == id }) id else StorageConstants.INTERNAL_ID
    }

    override suspend fun choose(purpose: StoragePurpose, locationId: String) = withContext(io) {
        if (locationId.startsWith(StorageConstants.VOLUME_ID_PREFIX)) {
            val uuid = locationId.removePrefix(StorageConstants.VOLUME_ID_PREFIX)
            markSeen(uuid)
            if (prefs.value.locations.none { it.id == locationId }) {
                val v = volumes.volumes.value.firstOrNull { it.uuid == uuid }
                val loc = StorageLocation(locationId, StorageKind.VOLUME, name = clip(v?.label?.takeIf { it.isNotBlank() } ?: "USB drive"), volumeUuid = uuid)
                store.update(StorageDomain) { it.copy(locations = it.locations + loc) }
            }
        }
        require(locationId == StorageConstants.INTERNAL_ID || prefs.value.locations.any { it.id == locationId }) { "unknown location" }
        store.update(StorageDomain) {
            when (purpose) {
                StoragePurpose.TIMESHIFT -> it.copy(timeshiftLocationId = locationId)
                StoragePurpose.RECORDING -> it.copy(recordingLocationId = locationId)
            }
        }
        BoxLog.i(TAG) { "chosen for $purpose: $locationId" }
        Unit
    }

    override suspend fun saveSmb(address: SmbAddress, name: String, password: CharArray?, test: LocationTestResult?): StorageLocation = withContext(io) {
        val id = StorageConstants.SMB_ID_PREFIX + UUID.randomUUID().toString()
        try {
            if (password != null && !address.guest) putPassword(id, password)
        } finally { password?.fill('\u0000') }
        val loc = StorageLocation(id, StorageKind.SMB, clip(name.ifBlank { address.host }), smb = address, lastTest = test)
        store.update(StorageDomain) { it.copy(locations = it.locations + loc) }
        _signInVersion.value++
        BoxLog.i(TAG) { "saved network folder $id" }
        loc
    }

    override suspend fun updateSignIn(locationId: String, user: String?, domain: String?, password: CharArray?, guest: Boolean) = withContext(io) {
        try {
            val loc = prefs.value.locations.firstOrNull { it.id == locationId && it.kind == StorageKind.SMB } ?: return@withContext
            if (guest) blobs.deleteBlob(ref(locationId)) else if (password != null) putPassword(locationId, password)
            val addr = loc.smb!!.copy(user = if (guest) null else user?.takeIf { it.isNotBlank() }, domain = if (guest) null else domain?.takeIf { it.isNotBlank() }, guest = guest)
            store.update(StorageDomain) { p -> p.copy(locations = p.locations.map { if (it.id == locationId) it.copy(smb = addr) else it }) }
            needsSignIn = needsSignIn - locationId
            unreadable = unreadable - locationId
            _signInVersion.value++
            recompute(); notifyListeners()
        } finally { password?.fill('\u0000') }
    }

    override suspend fun rename(locationId: String, name: String) = withContext(io) {
        val n = clip(name)
        if (n.isEmpty()) return@withContext
        store.update(StorageDomain) { p -> p.copy(locations = p.locations.map { if (it.id == locationId) it.copy(name = n) else it }) }
    }

    // U-S5 (save-location review 2026-10-24): a stored folder's Test is its new lastTest (§7.8); Status reads it through onPrefs.
    override suspend fun recordTest(locationId: String, result: LocationTestResult) = withContext(io) {
        store.update(StorageDomain) { p -> p.copy(locations = p.locations.map { if (it.id == locationId) it.copy(lastTest = result) else it }) }
        Unit
    }

    override suspend fun remove(locationId: String) = withContext(io) {
        blobs.deleteBlob(ref(locationId)) // the password goes first (§8)
        store.update(StorageDomain) { p ->
            p.copy(
                locations = p.locations.filterNot { it.id == locationId },
                timeshiftLocationId = if (p.timeshiftLocationId == locationId) StorageConstants.INTERNAL_ID else p.timeshiftLocationId,
                recordingLocationId = if (p.recordingLocationId == locationId) null else p.recordingLocationId,
            )
        }
        keeper.remove(locationId)
        BoxLog.i(TAG) { "removed $locationId" }
    }

    // ================================================================ keeper side

    /** The SMB location the keeper keeps connected: chosen for TIMESHIFT, while rewind is not Off (§9.1). Main-safe. */
    fun keptLocation(): StorageLocation? {
        if (playback.value.timeshift == TimeshiftLength.OFF) return null
        val id = chosen(StoragePurpose.TIMESHIFT)
        return prefs.value.locations.firstOrNull { it.id == id && it.kind == StorageKind.SMB && it.smb != null }
    }

    fun location(id: String): StorageLocation? = prefs.value.locations.firstOrNull { it.id == id }

    /** Whether [id] is known to need a sign-in (password missing after a restore, or the keeper's AUTH_FAILED). */
    fun signInNeeded(id: String): Boolean = id in needsSignIn || keeper[id]?.state == LocationState.SIGN_IN_NEEDED

    /** The password for [locationId] (§8): the caller zeroes [PasswordRead.Ok.chars]. Guest/anonymous: Ok(null). */
    suspend fun readPassword(locationId: String): PasswordRead {
        val loc = location(locationId) ?: return PasswordRead.NeedsSignIn
        val smb = loc.smb ?: return PasswordRead.NeedsSignIn
        if (smb.guest || smb.user.isNullOrEmpty()) return PasswordRead.Ok(null)
        return when (val r = withContext(io) { blobs.getBlob(ref(locationId)) }) {
            is Outcome.Ok -> {
                val bytes = r.value
                try { PasswordRead.Ok(decode(bytes)) } finally { bytes.fill(0) }
            }
            is Outcome.Err -> when ((r.error as? SourceError.Credentials)?.reason) {
                CredentialFailure.UNAVAILABLE -> PasswordRead.Unavailable
                else -> {
                    unreadable = unreadable + locationId
                    needsSignIn = needsSignIn + locationId; recompute(); PasswordRead.NeedsSignIn
                }
            }
        }
    }

    /** The keeper's live state for [locationId] (§9.2 Status). */
    fun publishKeeper(locationId: String, state: LocationState, freeBytes: Long?, failure: StorageFailure?) {
        val loc = location(locationId) ?: return
        keeper[locationId] = LocationStatus(loc, state, freeBytes, failure)
        recompute()
    }

    fun clearKeeper(locationId: String) { if (keeper.remove(locationId) != null) recompute() }

    fun addChangeListener(l: () -> Unit) { listeners += l }

    /** The export audit's secrets (§8, A-S11): each location's user name and password, read once. */
    suspend fun auditSecrets(): List<String> = withContext(io) {
        val out = ArrayList<String>()
        for (loc in prefs.value.locations) {
            val smb = loc.smb ?: continue
            smb.user?.takeIf { it.isNotEmpty() }?.let(out::add)
            smb.domain?.takeIf { it.isNotEmpty() }?.let(out::add)
            val r = blobs.getBlob(ref(loc.id))
            if (r is Outcome.Ok) {
                val bytes = r.value
                try { if (bytes.isNotEmpty()) out += String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
            }
        }
        out
    }

    // ================================================================ internals

    private suspend fun onPrefs(p: StoragePrefs) {
        withContext(io) {
            val s = seen ?: loadSeen().also { seen = it }
            // A-N7: a chosen USB drive this box never saw (a restore from another box) resets to this device.
            val chosenId = p.timeshiftLocationId
            if (chosenId.startsWith(StorageConstants.VOLUME_ID_PREFIX) && chosenId.removePrefix(StorageConstants.VOLUME_ID_PREFIX) !in s) {
                store.update(StorageDomain) { it.copy(timeshiftLocationId = StorageConstants.INTERNAL_ID) }
                BoxLog.i(TAG) { "unknown USB drive chosen after a restore: rewind back on this device" }
            }
            // §8 restore: SMB locations with a user and no stored password need a sign-in. Q-E2: existence only, no
            // decrypt per settings write; a password that does not decrypt is caught by the keeper's readPassword.
            val missing = HashSet<String>()
            val ids = HashSet<String>()
            for (loc in p.locations) {
                val smb = loc.smb ?: continue
                ids += loc.id
                // SEC-N1: an SMB user name or domain (smbj puts the user in its auth failure message) is never logged.
                Redact.registerSecret(smb.user)
                Redact.registerSecret(smb.domain)
                if (smb.guest || smb.user.isNullOrEmpty()) continue
                if (!blobs.hasBlob(ref(loc.id))) missing += loc.id
            }
            unreadable = unreadable.filterTo(HashSet()) { it in ids }
            needsSignIn = missing + unreadable
        }
        recompute()
        notifyListeners()
    }

    private fun recompute() {
        val p = prefs.value
        val mounted = volumes.volumes.value.associateBy { it.uuid }
        _status.value = p.locations.map { loc ->
            when (loc.kind) {
                StorageKind.SMB -> {
                    val k = keeper[loc.id]
                    when {
                        loc.id in needsSignIn -> LocationStatus(loc, LocationState.SIGN_IN_NEEDED, k?.freeBytes)
                        k != null -> k.copy(location = loc)
                        else -> loc.lastTest?.let { t -> LocationStatus(loc, LocationState.READY, t.freeBytes.takeIf { it >= 0 }) }
                            ?: LocationStatus(loc, LocationState.UNTESTED)
                    }
                }
                StorageKind.VOLUME, StorageKind.SAF_TREE -> {
                    val v = loc.volumeUuid?.let { mounted[it] }
                    if (v != null) LocationStatus(loc, LocationState.READY, v.freeBytes) else LocationStatus(loc, LocationState.NOT_CONNECTED)
                }
                StorageKind.INTERNAL -> LocationStatus(loc, LocationState.READY)
            }
        }
    }

    // Q-H5: a listener is the keeper's evaluate trigger; a bug there must show in the log, and must not stop the others.
    private fun notifyListeners() {
        for (l in listeners) try { l() } catch (e: RuntimeException) { BoxLog.w(TAG, "change listener failed: ${e.javaClass.simpleName}") }
    }

    private suspend fun putPassword(id: String, password: CharArray) {
        val bytes = encode(password)
        try {
            val r = blobs.putBlob(ref(id), bytes)
            if (r is Outcome.Err) throw IllegalStateException("password not stored: ${r.error.code}")
        } finally { bytes.fill(0) }
    }

    private fun markSeen(uuid: String) {
        val s = (seen ?: loadSeen()) + uuid
        seen = s
        // Q-U7: atomic like the other device-state files. Q-H5: A-N7 depends on this file, so a failure is logged.
        try { writeTextAtomically(seenFile, s.joinToString("\n")) } catch (e: Exception) {
            BoxLog.w(TAG, "seen USB drives not saved: ${e.javaClass.simpleName}")
        }
    }

    private fun loadSeen(): Set<String> = try {
        if (seenFile.isFile) seenFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() else emptySet()
    } catch (_: Exception) { emptySet() }

    private fun clip(name: String): String {
        val n = name.trim()
        return if (n.length <= StorageConstants.LABEL_MAX_CHARS) n else n.take(StorageConstants.LABEL_MAX_CHARS)
    }

    companion object {
        private const val TAG = "StorageLoc"
        const val SEEN_VOLUMES_FILE = "storage-seen-volumes"

        fun ref(locationId: String): String = SecureBlobStore.REF_SMB_PREFIX + locationId

        /** UTF-8 of [chars] without an intermediate String; the caller zeroes the result. */
        fun encode(chars: CharArray): ByteArray {
            val bb = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars))
            val out = ByteArray(bb.remaining()).also { bb.get(it) }
            if (bb.hasArray()) bb.array().fill(0)
            return out
        }

        /** [bytes] (UTF-8) into a new CharArray without an intermediate String; the caller zeroes both. */
        fun decode(bytes: ByteArray): CharArray {
            val cb = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
            val out = CharArray(cb.remaining()).also { cb.get(it) }
            if (cb.hasArray()) cb.array().fill('\u0000')
            return out
        }
    }
}
