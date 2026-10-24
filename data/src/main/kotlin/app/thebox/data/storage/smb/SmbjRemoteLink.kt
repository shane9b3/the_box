// WP-S1 (docs/design/m6-storage-locations.md §2 "Ownership", §4.1, §4.3, §8; A-S1, A-B1, A-B3, A-B4; lead ruling 6).
package app.thebox.data.storage.smb

import app.thebox.core.common.BoxLog
import app.thebox.core.playback.MonoClock
import app.thebox.core.storage.SmbAddress
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.smb.FaultClass
import app.thebox.core.storage.smb.OpenMode
import app.thebox.core.storage.smb.RemoteEntry
import app.thebox.core.storage.smb.RemoteFault
import app.thebox.core.storage.smb.RemoteFile
import app.thebox.core.storage.smb.RemoteLink
import app.thebox.core.storage.smb.RemoteShare
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.socket.ProxySocketFactory
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.IOException
import java.io.InterruptedIOException
import java.util.EnumSet
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val TAG = "SmbLink"

// ==================================================================== threads

/**
 * One named single thread (`box-smb`, `box-smb-browse`, §2 "Threads"), daemon, never main. Every task is wrapped so a
 * throw is logged by fault name and never kills the thread. [busySinceMono] feeds the keeper's watchdog (§13).
 */
class SmbExecutor(val name: String, private val clock: MonoClock = MonoClock { System.nanoTime() / 1_000_000L }) {
    private val current = AtomicReference<Thread?>()
    private val pool = ScheduledThreadPoolExecutor(1) { r -> Thread(r, name).also { it.isDaemon = true; current.set(it) } }
        .apply { removeOnCancelPolicy = true }
    @Volatile var busySinceMono: Long = Long.MIN_VALUE; private set

    fun isCurrent(): Boolean = Thread.currentThread() === current.get()
    fun post(work: Runnable) { pool.execute(wrap(work)) }
    fun <T> submit(work: () -> T): Future<T> = pool.submit<T> { busy { work() } }
    fun schedule(delayMs: Long, work: Runnable): ScheduledFuture<*> = pool.schedule(wrap(work), delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)

    private fun wrap(work: Runnable) = Runnable {
        try { busy { work.run() } } catch (t: Throwable) { BoxLog.w(TAG, "$name task failed: ${SmbFaults.faultName(t)}") }
    }

    private inline fun <T> busy(f: () -> T): T {
        busySinceMono = clock.nowMs()
        try { return f() } finally { busySinceMono = Long.MIN_VALUE }
    }
}

// ==================================================================== smbj configuration (§4.3, Must)

/** The two `SMBClient` instances (lead ruling 6, A-S1): the ring's and the browser/Test's, never shared. */
object SmbjClients {
    /** §4.3: connect timeout via the socket factory, SO timeout > keepalive, I/O timeouts per pool, BC provider. */
    fun config(ioTimeoutMs: Long): SmbConfig = SmbConfig.builder()
        .withSocketFactory(ProxySocketFactory(StorageConstants.SMB_CONNECT_TIMEOUT_MS.toInt()))
        .withSoTimeout(StorageConstants.SMB_SO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .withTimeout(ioTimeoutMs, TimeUnit.MILLISECONDS)
        .withBufferSize(StorageConstants.SMB_MAX_IO_BYTES)
        .withSecurityProvider(BCSecurityProvider())
        .withSigningEnabled(true)
        .withSigningRequired(false)
        .withDfsEnabled(false)
        .build()

    /** A new client for one ring link (its own connection table: a Test never shares or closes the ring's TCP). */
    fun ringClient(ioTimeoutMs: Long): SMBClient = SMBClient(config(ioTimeoutMs))

    /** The browse/Test client (`box-smb-browse`). */
    fun browseClient(): SMBClient = SMBClient(config(StorageConstants.SMB_IO_TIMEOUT_MS))

    /** Teardown is always `close(true)`: `close(false)` only drops a lease (`Connection.java:177-180`). */
    fun forceClose(connection: Connection?) {
        if (connection == null) return
        try { connection.close(true) } catch (_: Exception) { /* already gone */ }
    }

    /** NTLM context from a char array; [password] is copied by smbj, whose copy the caller zeroes after `authenticate`. */
    fun auth(address: SmbAddress, password: CharArray?): AuthenticationContext = when {
        address.guest -> AuthenticationContext.guest()
        address.user.isNullOrEmpty() -> AuthenticationContext.anonymous()
        else -> AuthenticationContext(address.user, password ?: CharArray(0), address.domain?.takeIf { it.isNotBlank() })
    }

    /** Zeroes smbj's copy of the password (the context keeps a private copy, `AuthenticationContext.java:25-29`). */
    fun zero(ctx: AuthenticationContext) { ctx.password?.fill('\u0000') }

    fun win(path: String): String = path.trim('/').replace('/', '\\')
}

// ==================================================================== the opener seam (fakes in tests)

/** What a connect produced: one tree-connected share, alive until its transport dies. */
interface OpenedShare {
    fun share(epoch: Long, link: SmbjRemoteLink): RemoteShare
    /** False once the transport is known dead (smbj `Connection.isConnected`). */
    val alive: Boolean
    /** A live free-space query (the keepalive, §5.3). */
    @Throws(IOException::class) fun probeFreeBytes(): Long
    /** `Connection.close(true)`; never throws. */
    fun closeForce()
}

/** Connect → authenticate → connectShare (box-smb). [password] is borrowed: the caller zeroes it. */
fun interface ShareOpener {
    @Throws(IOException::class) fun open(password: CharArray?): OpenedShare
}

/** What the keeper's password read gave (§8 `getBlob` outcomes). */
sealed interface PasswordRead {
    /** [chars] is the caller's to zero; null = no password (guest/anonymous). */
    class Ok(val chars: CharArray?) : PasswordRead { override fun toString() = "Ok(****)" }
    /** MISSING (restore) or KEY_INVALIDATED: AUTH_FAILED (SIGN_IN_NEEDED). */
    data object NeedsSignIn : PasswordRead
    /** UNAVAILABLE (transient Keystore failure): BACKOFF. */
    data object Unavailable : PasswordRead
}

/** A connect refused for the sign-in (logon status, or no stored password). */
class SignInNeededException : IOException("smb sign-in needed")

/** The real opener over the ring's `SMBClient` (created lazily on box-smb: BC class loading stays off the cold start, A-N6). */
class SmbjShareOpener(private val address: SmbAddress, private val ioTimeoutMs: Long) : ShareOpener {
    @Volatile private var client: SMBClient? = null
    private val inFlight = AtomicReference<Connection?>()

    override fun open(password: CharArray?): OpenedShare {
        val c = client ?: SmbjClients.ringClient(ioTimeoutMs).also { client = it }
        val conn = try { c.connect(address.host, address.port) } catch (e: Exception) { throw SmbIoException(e) }
        inFlight.set(conn)
        try {
            val ctx = SmbjClients.auth(address, password)
            val session = try { conn.authenticate(ctx) } finally { SmbjClients.zero(ctx) }
            val share = session.connectShare(address.share) as? DiskShare
                ?: throw SmbIoException(IllegalStateException("not a disk share"))
            return SmbjOpenedShare(conn, session, share)
        } catch (e: Exception) {
            SmbjClients.forceClose(conn)
            throw if (e is SmbIoException) e else SmbIoException(e)
        } finally { inFlight.set(null) }
    }

    /** The watchdog (§13, facts §1.3 #875): unblocks a hung sign-in by closing its connection from another thread. */
    fun abortInFlight() { SmbjClients.forceClose(inFlight.get()) }
}

/** smbj's objects for one epoch. */
class SmbjOpenedShare(val connection: Connection, val session: Session?, val disk: DiskShare) : OpenedShare {
    override fun share(epoch: Long, link: SmbjRemoteLink): RemoteShare = SmbjShare(epoch, disk, link)
    override val alive: Boolean get() = try { connection.isConnected } catch (_: Exception) { false }
    override fun probeFreeBytes(): Long = smb { disk.shareInformation.callerFreeSpace }
    override fun closeForce() = SmbjClients.forceClose(connection)
}

/** Runs an smbj call; every failure becomes an [SmbIoException] (path-free message), the cause kept for classification. */
internal inline fun <T> smb(f: () -> T): T = try { f() } catch (e: SmbIoException) { throw e } catch (e: Exception) { throw SmbIoException(e) }

/** [RemoteShare] over smbj's [DiskShare] (§4.1 mapping). Paths are `/`-separated and relative to the share. */
class SmbjShare(override val epoch: Long, private val disk: DiskShare, private val link: SmbjRemoteLink?) : RemoteShare {
    override fun open(path: String, mode: OpenMode, deleteOnClose: Boolean, exclusive: Boolean): RemoteFile = smb {
        val options = EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE, SMB2CreateOptions.FILE_RANDOM_ACCESS)
        if (deleteOnClose) options += SMB2CreateOptions.FILE_DELETE_ON_CLOSE
        val f = disk.openFile(
            SmbjClients.win(path),
            // C-S7: an exclusive (sweep) open asks only what a delete needs.
            if (exclusive) EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES)
            else EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE, AccessMask.DELETE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            // Full sharing (facts §1.8): a lingering old handle never blocks the reopen. C-S7: none for an exclusive open,
            // so a file another box holds open answers STATUS_SHARING_VIOLATION and is kept.
            if (exclusive) EnumSet.noneOf(SMB2ShareAccess::class.java) else SMB2ShareAccess.ALL,
            if (mode == OpenMode.CREATE_OVERWRITE) SMB2CreateDisposition.FILE_OVERWRITE_IF else SMB2CreateDisposition.FILE_OPEN,
            options,
        )
        SmbjFile(f, link)
    }

    override fun mkdirs(path: String) = smb {
        var p = ""
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            p = if (p.isEmpty()) part else "$p\\$part"
            if (!disk.folderExists(p)) {
                try { disk.mkdir(p) } catch (e: SMBApiException) { if (e.status != NtStatus.STATUS_OBJECT_NAME_COLLISION) throw e }
            }
        }
    }

    override fun list(path: String, max: Int): List<RemoteEntry> = smb {
        disk.list(SmbjClients.win(path)).asSequence()
            .filter { it.fileName != "." && it.fileName != ".." }
            .map {
                val a = it.fileAttributes
                RemoteEntry(
                    name = it.fileName,
                    directory = a and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
                    hidden = a and (FileAttributes.FILE_ATTRIBUTE_HIDDEN.value or FileAttributes.FILE_ATTRIBUTE_SYSTEM.value) != 0L,
                    sizeBytes = it.endOfFile,
                )
            }
            .take(max).toList()
    }

    override fun delete(path: String) = smb { disk.rm(SmbjClients.win(path)) }

    override fun freeBytes(): Long {
        link?.cachedFree(StorageConstants.SMB_SPACE_MAX_AGE_MS)?.let { return it }
        return smb { disk.shareInformation.callerFreeSpace }.also { link?.putFree(it) }
    }

    override fun lastWriteMs(path: String): Long =
        smb { disk.getFileInformation(SmbjClients.win(path)).basicInformation.lastWriteTime.toEpochMillis() }
}

/** [RemoteFile] over smbj's `File`: positional, one SMB2 WRITE/READ per call up to the buffer size (facts §1.6). */
class SmbjFile(private val file: com.hierynomus.smbj.share.File, private val link: SmbjRemoteLink?) : RemoteFile {
    @Volatile private var closed = false

    override fun write(src: ByteArray, off: Int, len: Int, fileOffset: Long) = smb {
        val n = file.write(src, fileOffset, off, len)
        if (n < len) throw IOException("short write")
    }

    override fun read(dst: ByteArray, off: Int, len: Int, fileOffset: Long): Int = smb { file.read(dst, fileOffset, off, len) }

    override fun closeAsync() {
        if (closed) return
        closed = true
        val l = link
        if (l == null || l.onLinkThread()) closeNow() else l.post { closeNow() }
    }

    override fun close() = closeAsync()

    private fun closeNow() { try { file.closeSilently() } catch (_: Exception) { } }
}

// ==================================================================== the link

/**
 * The keeper's one [RemoteLink] for the location chosen for TIMESHIFT (§2, A-S1, lead ruling 6): the state, the [epoch]
 * and the open-file reference count. All connects run on [exec] (`box-smb`); the ring's flusher and Media3's loaders only
 * wait, bounded by their deadlines and interruptible (A-B3).
 *
 * - [share] answers the READY share, or asks the keeper ([onDemand]) and waits up to the deadline.
 * - [reconnect] is single flight per failed epoch: the first caller submits one connect to box-smb (the dead connection
 *   is `close(true)`d first), the others wait on the same READY. Attempts are at least `SMB_RECONNECT_MIN_GAP_MS` apart.
 *   On box-smb itself it runs inline (the keeper's keepalive), never waiting on its own thread.
 * - The free-space cache is the keeper's (§4.1 `availableBytes`, §5.1 sizing).
 */
class SmbjRemoteLink(
    private val opener: ShareOpener,
    private val exec: SmbExecutor,
    private val clock: MonoClock,
    override val ioTimeoutMs: Long,
    /** The keeper's password read (box-smb). */
    private val password: () -> PasswordRead,
    /** Called (any thread, must not block) when a waiter needs the link and it is not READY: the keeper's warm(). */
    private val onDemand: () -> Unit = {},
    /** Called on box-smb after every successful (re)connect: the keeper's wake write and last-known-good. */
    private val onConnected: (RemoteShare) -> Unit = {},
    private val classifier: (Throwable, Boolean, Boolean?) -> FaultClass = SmbFaults::classify,
) : RemoteLink {
    private val epochV = AtomicLong(0L)
    override val epoch: Long get() = epochV.get()
    @Volatile private var opened: OpenedShare? = null
    @Volatile private var current: RemoteShare? = null
    @Volatile private var lastAttemptMono = Long.MIN_VALUE
    /** The last connect failure (keeper: AUTH_FAILED vs BACKOFF); null after a success. */
    @Volatile var lastConnectError: Throwable? = null; private set
    private val lock = ReentrantLock()
    private val readyCond = lock.newCondition()
    private var inflight: Future<*>? = null
    private val refs = AtomicInteger()
    @Volatile private var freeBytes = -1L
    /** C-S1: superseded by a new link; never connects again. */
    @Volatile private var retired = false
    @Volatile private var freeAtMono = Long.MIN_VALUE
    /** Connects done (tests, stats). */
    val connects = AtomicInteger()

    val isReady: Boolean get() = current != null
    val openFiles: Int get() = refs.get()
    fun currentShare(): RemoteShare? = current
    fun onLinkThread(): Boolean = exec.isCurrent()

    // ---------------------------------------------------------------- RemoteLink

    override fun share(deadlineMonoMs: Long): RemoteShare {
        if (retired) throw LinkUnavailableException("retired") // C-S1
        current?.let { return it }
        if (exec.isCurrent()) throw LinkUnavailableException("not ready") // never wait on our own thread
        onDemand()
        return awaitReady(epochAbove = -1L, deadlineMonoMs)
    }

    override fun reconnect(failedEpoch: Long, deadlineMonoMs: Long): RemoteShare {
        // C-S1: a retired link never connects again; its ring fails over (UNREACHABLE) and the next zap uses the new link.
        if (retired) throw LinkUnavailableException("retired")
        if (epoch > failedEpoch) current?.let { return it }
        if (exec.isCurrent()) {
            reconnectNow(failedEpoch)
            return current?.takeIf { epoch > failedEpoch } ?: throw LinkUnavailableException("reconnect failed")
        }
        lock.withLock {
            val f = inflight
            if (f == null || f.isDone) inflight = exec.submit { reconnectNow(failedEpoch) }
        }
        return awaitReady(failedEpoch, deadlineMonoMs)
    }

    override fun classify(t: Throwable): RemoteFault =
        RemoteFault(classifier(t, Thread.currentThread().isInterrupted, opened?.alive))

    override fun retain() { refs.incrementAndGet() }
    override fun release() {
        val left = refs.updateAndGet { maxOf(0, it - 1) }
        if (left == 0 && retired) exec.post { dropCurrent() } // C-S1: the last ring file of a retired link closed
    }

    /**
     * C-S1: the keeper made a new link for this location (a sign-in or address change); this one is retired. It never
     * connects again ([share], [reconnect] and [connectBlocking] refuse), and its connection is dropped now when no ring
     * file is open, else when the last one is released. Any thread; never waits.
     */
    fun retire() {
        retired = true
        if (refs.get() == 0) exec.post { dropCurrent() }
    }

    val isRetired: Boolean get() = retired

    override fun post(work: Runnable) = exec.post(work)

    // ---------------------------------------------------------------- keeper side (box-smb)

    /**
     * Connects now (box-smb): `close(true)` on whatever was open, read the password, connect → authenticate →
     * connectShare at epoch + 1. Returns null on success, else the failure ([SignInNeededException] when the password
     * store says so).
     */
    fun connectBlocking(): Throwable? {
        check(exec.isCurrent()) { "connect runs on ${exec.name}" }
        dropCurrent()
        if (retired) return LinkUnavailableException("retired").also { lastConnectError = it } // C-S1
        lastAttemptMono = clock.nowMs()
        val pw = password()
        val chars = when (pw) {
            is PasswordRead.Ok -> pw.chars
            PasswordRead.NeedsSignIn -> return SignInNeededException().also { lastConnectError = it }
            PasswordRead.Unavailable -> return LinkUnavailableException("password unavailable").also { lastConnectError = it }
        }
        val o = try { opener.open(chars) } catch (e: IOException) {
            lastConnectError = e
            return e
        } finally { chars?.fill('\u0000') }
        connects.incrementAndGet()
        lock.withLock {
            opened = o
            val e = epochV.incrementAndGet()
            current = o.share(e, this)
            lastConnectError = null
            readyCond.signalAll()
        }
        try { onConnected(current!!) } catch (t: Throwable) { BoxLog.w(TAG, "after-connect failed: ${SmbFaults.faultName(t)}") }
        return null
    }

    /** Box-smb: the keepalive's live query (refreshes the free-space cache). */
    fun probe(): Long {
        val o = opened ?: throw LinkUnavailableException("not connected")
        return o.probeFreeBytes().also { putFree(it) }
    }

    /** Box-smb (or the watchdog): `close(true)` and forget the share; waiters keep waiting for the next READY. */
    fun disconnect() { dropCurrent() }

    fun cachedFree(maxAgeMs: Long): Long? {
        val at = freeAtMono
        return if (freeBytes >= 0 && at != Long.MIN_VALUE && clock.nowMs() - at <= maxAgeMs) freeBytes else null
    }

    /** The last known free space and when (mono), for sizing (§5.1); null when never measured. */
    fun lastFree(): Pair<Long, Long>? = if (freeBytes >= 0) freeBytes to freeAtMono else null

    fun putFree(bytes: Long) { freeBytes = bytes; freeAtMono = clock.nowMs() }

    // ---------------------------------------------------------------- internals

    private fun reconnectNow(failedEpoch: Long) {
        if (epoch > failedEpoch && current != null) return
        val gapLeft = if (lastAttemptMono == Long.MIN_VALUE) 0L else lastAttemptMono + StorageConstants.SMB_RECONNECT_MIN_GAP_MS - clock.nowMs()
        if (gapLeft > 0) try { Thread.sleep(gapLeft) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
        if (epoch > failedEpoch && current != null) return
        val err = connectBlocking()
        if (err != null) BoxLog.i(TAG) { "reconnect failed: ${SmbFaults.faultName(err)}" }
    }

    private fun dropCurrent() {
        val o: OpenedShare?
        lock.withLock { o = opened; opened = null; current = null }
        o?.closeForce()
    }

    private fun awaitReady(epochAbove: Long, deadlineMonoMs: Long): RemoteShare {
        lock.lockInterruptiblyOrCancel()
        try {
            while (true) {
                val c = current
                if (c != null && epoch > epochAbove) return c
                val left = deadlineMonoMs - clock.nowMs()
                if (left <= 0) throw LinkUnavailableException("not ready before the deadline")
                try { readyCond.await(minOf(left, WAIT_SLICE_MS), TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt(); throw InterruptedIOException("smb wait cancelled")
                }
            }
        } finally { lock.unlock() }
    }

    private fun ReentrantLock.lockInterruptiblyOrCancel() {
        try { lockInterruptibly() } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); throw InterruptedIOException("smb wait cancelled")
        }
    }

    private companion object { const val WAIT_SLICE_MS = 50L }
}
