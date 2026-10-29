package app.thebox.data.storage

import app.thebox.core.common.CredentialFailure
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.playback.MonoClock
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.StorageDomain
import app.thebox.core.storage.SmbAddress
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StoragePurpose
import app.thebox.core.storage.fakes.FakeVolumeCatalog
import app.thebox.core.storage.smb.FakeRemoteLink
import app.thebox.core.storage.smb.FakeStatus
import app.thebox.core.storage.smb.FakeStatusException
import app.thebox.core.storage.smb.OpenMode
import app.thebox.core.storage.smb.RemoteEntry
import app.thebox.core.storage.smb.RemoteFile
import app.thebox.core.storage.smb.RemoteShare
import app.thebox.data.contracts.SecureBlobStore
import app.thebox.data.storage.smb.LastGoodStore
import app.thebox.data.storage.smb.OpenedShare
import app.thebox.data.storage.smb.ShareOpener
import app.thebox.data.storage.smb.SignInNeededException
import app.thebox.data.storage.smb.SmbExecutor
import app.thebox.data.storage.smb.SmbSessionKeeper
import app.thebox.data.storage.smb.SmbjRemoteLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** An in-memory [SecureBlobStore] that records every array it was given (to check they are zeroed). */
class RecordingBlobs : SecureBlobStore {
    val map = ConcurrentHashMap<String, ByteArray>()
    val given = CopyOnWriteArrayList<ByteArray>()
    val handedOut = CopyOnWriteArrayList<ByteArray>()
    @Volatile var failure: CredentialFailure? = null
    /** Q-E2: decrypts (getBlob) so far. */
    val gets = AtomicInteger()
    /** Q-E2: stored rows that do not decrypt (CORRUPT) while [hasBlob] still sees them. */
    @Volatile var undecryptable = false
    override suspend fun putBlob(ref: String, bytes: ByteArray): Outcome<Unit> {
        failure?.let { return Outcome.Err(SourceError.Credentials(it)) }
        given += bytes; map[ref] = bytes.copyOf(); return Outcome.Ok(Unit)
    }
    override suspend fun getBlob(ref: String): Outcome<ByteArray> {
        gets.incrementAndGet()
        failure?.let { return Outcome.Err(SourceError.Credentials(it)) }
        if (undecryptable && map.containsKey(ref)) return Outcome.Err(SourceError.Credentials(CredentialFailure.CORRUPT))
        val b = map[ref]?.copyOf() ?: return Outcome.Err(SourceError.Credentials(CredentialFailure.MISSING))
        handedOut += b
        return Outcome.Ok(b)
    }
    override suspend fun deleteBlob(ref: String) { map.remove(ref) }
    /** Like `TinkCredentialStore`: the row only, no decrypt. */
    override suspend fun hasBlob(ref: String): Boolean {
        failure?.let { return it == CredentialFailure.UNAVAILABLE }
        return map.containsKey(ref)
    }
}

/** A share whose [epoch] is the link's (the fake link counts its own epochs). */
class EpochShare(override val epoch: Long, private val d: RemoteShare) : RemoteShare {
    override fun open(path: String, mode: OpenMode, deleteOnClose: Boolean, exclusive: Boolean): RemoteFile = d.open(path, mode, deleteOnClose, exclusive)
    override fun mkdirs(path: String) = d.mkdirs(path)
    override fun list(path: String, max: Int): List<RemoteEntry> = d.list(path, max)
    override fun delete(path: String) = d.delete(path)
    override fun freeBytes(): Long = d.freeBytes()
    override fun lastWriteMs(path: String): Long = d.lastWriteMs(path)
}

/** A [ShareOpener] over a [FakeRemoteLink] "server". */
class FakeOpener(val server: FakeRemoteLink) : ShareOpener {
    private val far = Long.MAX_VALUE / 2
    @Volatile var fail: (() -> IOException)? = null
    val opens = AtomicInteger()
    val closes = AtomicInteger()
    @Volatile var lastPassword: String? = null

    override fun open(password: CharArray?): OpenedShare {
        opens.incrementAndGet()
        lastPassword = password?.concatToString()
        fail?.let { throw it() }
        if (!server.transportUp) server.reconnect(server.epoch, far)
        val s = server.share(far)
        return object : OpenedShare {
            override fun share(epoch: Long, link: SmbjRemoteLink): RemoteShare = EpochShare(epoch, s)
            override val alive: Boolean get() = server.transportUp && server.epoch == s.epoch
            override fun probeFreeBytes(): Long = s.freeBytes()
            override fun closeForce() { closes.incrementAndGet() }
        }
    }
}

fun eventually(what: String, ms: Long = 5_000, cond: () -> Boolean) {
    val end = System.nanoTime() + ms * 1_000_000
    while (!cond()) { check(System.nanoTime() < end) { "timed out waiting for $what" }; Thread.sleep(5) }
}

/** The storage graph of `:data` over fakes: settings, blobs, volumes, a fake SMB server, the real keeper and targets. */
class StorageWorld(
    val server: FakeRemoteLink = FakeRemoteLink(),
    val pid: Int = 4242,
    lastGoodWall: Pair<String, Long>? = null,
) {
    val clock: MonoClock = server.clock
    val dir: File = Files.createTempDirectory("storage-test").toFile()
    val store = FakeSettingsStore()
    val blobs = RecordingBlobs()
    val volumes = FakeVolumeCatalog()
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "test-storage-scope").also { it.isDaemon = true } }
    val scope = CoroutineScope(SupervisorJob() + pool.asCoroutineDispatcher())
    val locations = StorageLocationsImpl(store, blobs, volumes, StorageDirs(dir), Dispatchers.Unconfined, scope)
    val deviceId = DeviceIdentity(dir)
    val lastGood = LastGoodStore(File(dir, LastGoodStore.FILE_NAME)).also { s -> lastGoodWall?.let { s.put(it.first, it.second) } }
    var wall = 1_800_000_000_000L
    val opener = FakeOpener(server)
    val exec = SmbExecutor("test-box-smb", clock)
    /** Every `onSignedInAgain` (the FallbackToastGate.signedIn hook). */
    val signedIn: MutableList<String> = CopyOnWriteArrayList()
    val keeper = SmbSessionKeeper(
        locations = locations, deviceId = deviceId, lastGood = lastGood, exec = exec, clock = clock, wallMs = { wall },
        pid = pid, ioTimeoutMs = server.ioTimeoutMs, openerFor = { opener },
        classifier = { t, _, _ -> server.classify(t).cls },
        isAuthFailure = { it is SignInNeededException || (it as? FakeStatusException)?.status == FakeStatus.LOGON_FAILURE },
        onSignedInAgain = { signedIn += it },
    )
    val targets = StorageTargetsImpl(
        locations, { keeper }, volumes, deviceId, File(dir, "timeshift"), clock, { wall }, pid,
        space = LocalSpace { 32L * 1024 * 1024 * 1024 to 20L * 1024 * 1024 * 1024 },
    )

    init { server.mkdirsDirect("TV") }

    /** Saves an SMB location `\\nas\TV\Rewind` (user "shawn", password "s3cret!") and chooses it for rewind. */
    fun addSmb(user: String? = "shawn", password: String? = "s3cret!", choose: Boolean = true, path: String = "Rewind"): String = runBlocking {
        server.mkdirsDirect("Rewind")
        val loc = locations.saveSmb(SmbAddress("nas", share = "TV", path = path, user = user), "NAS", password?.toCharArray(), null)
        if (choose) locations.choose(StoragePurpose.TIMESHIFT, loc.id)
        eventually("prefs") { locations.status.value.any { it.location.id == loc.id } }
        loc.id
    }

    fun chosen() = store.flow(StorageDomain).value.timeshiftLocationId

    companion object { val INTERNAL = StorageConstants.INTERNAL_ID }
}

/** Seeds a directory on the fake server (its `mkdirs` goes through the I/O counter; tests seed before counting). */
fun FakeRemoteLink.mkdirsDirect(path: String) { share(Long.MAX_VALUE / 2).mkdirs(path) }
