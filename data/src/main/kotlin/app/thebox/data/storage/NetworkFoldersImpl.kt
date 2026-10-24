// WP-S1 (docs/design/m6-storage-locations.md §7.3, §7.5–§7.8; lead ruling 6). find() is WP-S3's ComputerFinder.
// Fix pass, core half (2026-10-24): C-B2 (each browse session owns its connection), C-S6 (cancel and password copy).
package app.thebox.data.storage

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.thebox.core.common.BoxLog
import app.thebox.core.playback.MonoClock
import app.thebox.core.setup.lan.LanAddressFinder
import app.thebox.core.storage.BrowseError
import app.thebox.core.storage.BrowseErrorKind
import app.thebox.core.storage.BrowseHandle
import app.thebox.core.storage.BrowseResult
import app.thebox.core.storage.FindState
import app.thebox.core.storage.FolderListing
import app.thebox.core.storage.LocationTestResult
import app.thebox.core.storage.NetworkFolders
import app.thebox.core.storage.ShareListing
import app.thebox.core.storage.SignInMode
import app.thebox.core.storage.SmbAddress
import app.thebox.core.storage.StorageConstants
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.TestProgress
import app.thebox.core.storage.TestVerdict
import app.thebox.core.storage.smb.FaultClass
import app.thebox.core.storage.smb.RemoteEntry
import app.thebox.data.storage.discovery.ComputerFinder
import app.thebox.data.storage.smb.LocationTester
import app.thebox.data.storage.smb.SmbFaults
import app.thebox.data.storage.smb.SmbShareLister
import app.thebox.data.storage.smb.SmbjClients
import app.thebox.data.storage.smb.SmbjShare
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

// ==================================================================== the browse connection seam (C-B2)

/**
 * (C-B2) One browse session's **own** connection. smbj caches connections per `host:port` inside one `SMBClient` and
 * `close(true)` disconnects the transport for every lease of it, so two sessions to one computer (a re-sign-in after an
 * anonymous share listing, a stored location's Test while a browse is open) must never share one: closing the old
 * session would kill the new one ("Can't reach NAS"). [closeForce] releases only this session's transport.
 */
interface BrowseConnection {
    val isConnected: Boolean
    @Throws(Exception::class) fun authenticate(ctx: AuthenticationContext): BrowseSession
    /** `close(true)` of this session's transport (and its client); never throws. */
    fun closeForce()
}

/** One authenticated session on a [BrowseConnection]. */
interface BrowseSession {
    /** §7.5: the disk shares (srvsvc); an interrupt is rethrown as a cancel (C-S6). */
    fun shares(signedInAsUser: Boolean): ShareListing
    @Throws(Exception::class) fun disk(share: String): DiskShare
}

/** (C-B2) Opens a [BrowseConnection]: a fresh one per call, never a lease of another session's. */
fun interface BrowseConnections {
    @Throws(Exception::class) fun connect(host: String, port: Int): BrowseConnection
}

/**
 * (C-B2) The production [BrowseConnections]: a new `SMBClient` per connect (cheap: the config, with its static
 * BC provider, is shared), so each browse session has its own connection table and transport.
 */
class SmbjBrowseConnections(private val config: () -> SmbConfig = { SHARED_CONFIG }) : BrowseConnections {
    override fun connect(host: String, port: Int): BrowseConnection {
        val client = SMBClient(config())
        val conn = try { client.connect(host, port) } catch (e: Exception) {
            try { client.close() } catch (_: Exception) { }
            throw e
        }
        return SmbjBrowseConnection(client, conn)
    }

    private class SmbjBrowseConnection(private val client: SMBClient, private val conn: Connection) : BrowseConnection {
        override val isConnected: Boolean get() = try { conn.isConnected } catch (_: Exception) { false }
        override fun authenticate(ctx: AuthenticationContext): BrowseSession = SmbjBrowseSession(conn.authenticate(ctx))
        override fun closeForce() {
            SmbjClients.forceClose(conn)
            try { client.close() } catch (_: Exception) { /* already gone */ }
        }
    }

    private class SmbjBrowseSession(private val session: Session) : BrowseSession {
        override fun shares(signedInAsUser: Boolean): ShareListing = SmbShareLister().list(session, signedInAsUser)
        override fun disk(share: String): DiskShare = session.connectShare(share) as? DiskShare ?: throw IOException("not a disk share")
    }

    private companion object {
        val SHARED_CONFIG: SmbConfig by lazy { SmbjClients.config(StorageConstants.SMB_IO_TIMEOUT_MS) }
    }
}

/**
 * Browse, sign-in, shares, folders and the Test on `box-smb-browse`, over browse connections (never the ring's `SMBClient`,
 * lead ruling 6). Finding computers is [finder]'s (WP-S3).
 *
 * - **Sign-in order** (§7.3): a user name or password given → that user only; a guest address → guest; otherwise
 *   anonymous, then guest. A logon refusal, guests refused or signing required for guests → [BrowseResult.NeedsSignIn].
 * - **Connections** (C-B2): each [open] gets its own connection ([connections]); [close] and every failure path close
 *   only that one, so a re-sign-in never kills the session that replaced it.
 * - **Password** (§7.3, §8; C-S6): no copy is kept. smbj's copy is zeroed after `authenticate`; the caller's array stays
 *   the caller's.
 * - **Cancel**: every call is `runInterruptible`, so leaving the page (BACK, Stop test) interrupts the smbj wait. (C-S6) A
 *   cancel that lands after a successful sign-in closes the session it made, so nothing outlives the caller.
 * - Errors are §7.7's [BrowseError]s; logs carry fault names only (§8).
 */
@Singleton
class NetworkFoldersImpl internal constructor(
    private val finder: ComputerFinder,
    private val lan: LanAddressFinder,
    private val context: Context,
    private val deviceId: DeviceIdentity,
    /** (Save-location integration, S4-Q3) The stored sign-in of a saved location; a Provider, so no construction cycle. */
    private val stored: javax.inject.Provider<StorageLocationsImpl>,
    private val connections: BrowseConnections,
) : NetworkFolders {
    @Inject constructor(
        finder: ComputerFinder,
        lan: LanAddressFinder,
        @ApplicationContext context: Context,
        deviceId: DeviceIdentity,
        stored: javax.inject.Provider<StorageLocationsImpl>,
    ) : this(finder, lan, context, deviceId, stored, SmbjBrowseConnections())

    private val browse = Executors.newSingleThreadExecutor { r -> Thread(r, "box-smb-browse").also { it.isDaemon = true } }
    private val dispatcher = browse.asCoroutineDispatcher()
    private val ids = AtomicLong()
    private val sessions = ConcurrentHashMap<Long, Browse>()
    private val clock = MonoClock { System.nanoTime() / 1_000_000L }

    /** C-S6: no password copy; C-B2: [connection] is this session's alone. */
    private class Browse(val handle: BrowseHandle, val connection: BrowseConnection, val session: BrowseSession) {
        val shares = HashMap<String, DiskShare>()
    }

    /** Browse sessions open now (tests). */
    internal val openSessions: Int get() = sessions.size

    override fun find(): Flow<FindState> = finder.search()

    /**
     * (Save-location integration, S4-Q3) A stored location's Test and sign-in check call `open(location.smb, null)`: with
     * no password given and a user name in [address], the saved password of the matching stored location
     * (`SecureBlobStore`) is used. None stored (restored backup, Keystore reset) → [BrowseResult.NeedsSignIn]. The pages
     * never see it; this copy is zeroed here.
     */
    override suspend fun open(address: SmbAddress, password: CharArray?): BrowseResult {
        if (password != null || address.guest || address.user.isNullOrEmpty()) return openWith(address, password)
        val loc = stored.get().status.value.map { it.location }.firstOrNull { l ->
            val a = l.smb
            a != null && a.host.equals(address.host, ignoreCase = true) && a.port == address.port &&
                a.user == address.user && (a.domain.orEmpty()).equals(address.domain.orEmpty(), ignoreCase = true)
        } ?: return openWith(address, null)
        return when (val r = stored.get().readPassword(loc.id)) {
            is app.thebox.data.storage.smb.PasswordRead.Ok -> {
                val chars = r.chars
                try { openWith(address, chars) } finally { chars?.fill('\u0000') }
            }
            // Missing (restore) or unreadable (Keystore): ask for the sign-in, which stores it again.
            app.thebox.data.storage.smb.PasswordRead.NeedsSignIn, app.thebox.data.storage.smb.PasswordRead.Unavailable -> BrowseResult.NeedsSignIn()
        }
    }

    private suspend fun openWith(address: SmbAddress, password: CharArray?): BrowseResult {
        // C-S6: the session this call registered, seen by the caller's side; a cancel after the block returned
        // (BACK just as the server answers) would otherwise discard the handle and leak the session.
        val made = AtomicReference<BrowseHandle?>()
        try {
            return runInterruptible(dispatcher) { openBlocking(address, password, made) }
        } catch (c: CancellationException) {
            made.get()?.let { close(it) }
            throw c
        }
    }

    private fun openBlocking(address: SmbAddress, password: CharArray?, made: AtomicReference<BrowseHandle?>): BrowseResult {
        val vpn = runCatching { lan.vpnActive() }.getOrDefault(false)
        val conn = try { connections.connect(address.host, address.port) } catch (e: Exception) {
            if (cancelled(e)) throw e
            BoxLog.i(TAG) { "connect failed: ${SmbFaults.faultName(e)}" }
            return BrowseResult.Failed(SmbFaults.browseError(e, vpn))
        }
        val tries = when {
            password != null || !address.user.isNullOrEmpty() -> listOf(SignInMode.USER)
            address.guest -> listOf(SignInMode.GUEST)
            else -> listOf(SignInMode.ANONYMOUS, SignInMode.GUEST)
        }
        var refused: BrowseError? = null
        for (mode in tries) {
            val ctx = when (mode) {
                SignInMode.USER -> AuthenticationContext(address.user.orEmpty(), password ?: CharArray(0), address.domain?.takeIf { it.isNotBlank() })
                SignInMode.GUEST -> AuthenticationContext.guest()
                SignInMode.ANONYMOUS -> AuthenticationContext.anonymous()
            }
            try {
                val session = conn.authenticate(ctx)
                val id = ids.incrementAndGet()
                val handle = BrowseHandle(id, address.copy(guest = mode == SignInMode.GUEST, user = if (mode == SignInMode.USER) address.user else null), mode)
                sessions[id] = Browse(handle, conn, session)
                made.set(handle)
                BoxLog.i(TAG) { "browse session $id open ($mode)" }
                return BrowseResult.Opened(handle)
            } catch (e: Exception) {
                // C-B2: every close below is this call's own connection; no other session leases it.
                if (cancelled(e)) { conn.closeForce(); throw e }
                BoxLog.i(TAG) { "sign-in $mode refused: ${SmbFaults.faultName(e)}" }
                refused = when {
                    SmbFaults.isSmb1Only(e) -> { conn.closeForce(); return BrowseResult.Failed(BrowseError(BrowseErrorKind.SMB1)) }
                    SmbFaults.isGuestRefused(e) -> BrowseError(BrowseErrorKind.GUEST_REFUSED)
                    SmbFaults.isLogonFailure(e) -> if (mode == SignInMode.USER) BrowseError(BrowseErrorKind.AUTH) else BrowseError(BrowseErrorKind.GUEST_REFUSED)
                    SmbFaults.classify(e, false, conn.isConnected) == FaultClass.ACCESS -> BrowseError(BrowseErrorKind.GUEST_REFUSED)
                    else -> { conn.closeForce(); return BrowseResult.Failed(SmbFaults.browseError(e, vpn)) }
                }
            } finally { SmbjClients.zero(ctx) }
        }
        conn.closeForce()
        return BrowseResult.NeedsSignIn(refused)
    }

    override suspend fun shares(session: BrowseHandle): ShareListing = runInterruptible(dispatcher) {
        val b = sessions[session.id] ?: return@runInterruptible ShareListing(emptyList(), BrowseError(BrowseErrorKind.STORAGE, StorageFailure.UNREACHABLE))
        b.session.shares(signedInAsUser = b.handle.mode == SignInMode.USER)
    }

    override suspend fun folders(session: BrowseHandle, share: String, path: String): FolderListing = runInterruptible(dispatcher) {
        val p = path.trim('/')
        val b = sessions[session.id] ?: return@runInterruptible FolderListing(share, p, emptyList(), error = BrowseError(BrowseErrorKind.STORAGE, StorageFailure.UNREACHABLE))
        val vpn = runCatching { lan.vpnActive() }.getOrDefault(false)
        val disk = try { disk(b, share) } catch (e: Exception) {
            if (cancelled(e)) throw e
            return@runInterruptible FolderListing(share, p, emptyList(), error = shareError(e, vpn))
        }
        val view = SmbjShare(0L, disk, null)
        try {
            val (names, truncated) = folderNames(view.list(p, Int.MAX_VALUE))
            FolderListing(share, p, names, truncated)
        } catch (e: IOException) {
            if (cancelled(e)) throw e
            if (SmbFaults.classify(e, false, b.connection.isConnected) != FaultClass.REMOVED) {
                return@runInterruptible FolderListing(share, p, emptyList(), error = SmbFaults.browseError(e, vpn))
            }
            // §7.2: land on the deepest folder that exists.
            val parts = p.split('/').filter { it.isNotEmpty() }
            var deepest = ""
            for (n in parts.size - 1 downTo 1) {
                val cand = parts.take(n).joinToString("/")
                if (runCatching { disk.folderExists(SmbjClients.win(cand)) }.getOrDefault(false)) { deepest = cand; break }
            }
            val (names, truncated) = runCatching { folderNames(view.list(deepest, Int.MAX_VALUE)) }.getOrDefault(emptyList<String>() to false)
            FolderListing(share, deepest, names, truncated, BrowseError(BrowseErrorKind.NOT_FOUND, deepestPath = deepest))
        }
    }

    override suspend fun test(session: BrowseHandle, share: String, path: String, progress: (TestProgress) -> Unit): LocationTestResult = runInterruptible(dispatcher) {
        val b = sessions[session.id]
            ?: return@runInterruptible failed(BrowseError(BrowseErrorKind.STORAGE, StorageFailure.UNREACHABLE))
        val vpn = runCatching { lan.vpnActive() }.getOrDefault(false)
        val disk = try { disk(b, share) } catch (e: Exception) {
            if (cancelled(e)) throw e
            return@runInterruptible failed(shareError(e, vpn))
        }
        val tester = LocationTester(
            clock = clock, deviceId = deviceId.id, pid = android.os.Process.myPid(),
            classify = { SmbFaults.classify(it, Thread.currentThread().isInterrupted, b.connection.isConnected) },
            wifi = onWifi(),
        )
        val r = tester.run(SmbjShare(0L, disk, null), path.trim('/'), progress)
        BoxLog.i(TAG) { "test: ${r.verdict}, ${r.writeBps} / ${r.readBps} B/s, ${r.rttMs} ms, wake ${r.wakeMs} ms" }
        r
    }

    /** Closes [session]'s own connection only (C-B2). Never waits. */
    override fun close(session: BrowseHandle) {
        val b = sessions.remove(session.id) ?: return
        browse.execute { b.connection.closeForce() }
        BoxLog.i(TAG) { "browse session ${session.id} closed" }
    }

    // ================================================================ internals

    private fun disk(b: Browse, share: String): DiskShare = b.shares.getOrPut(share) { b.session.disk(share) }

    private fun shareError(e: Throwable, vpn: Boolean): BrowseError = when (SmbFaults.classify(e, false, null)) {
        FaultClass.ACCESS -> BrowseError(BrowseErrorKind.STORAGE, StorageFailure.ACCESS)
        FaultClass.REMOVED -> BrowseError(BrowseErrorKind.NOT_FOUND)
        else -> if (isBadShareName(e)) BrowseError(BrowseErrorKind.NOT_FOUND) else SmbFaults.browseError(e, vpn)
    }

    private fun isBadShareName(e: Throwable): Boolean = SmbFaults.chain(e).any {
        (it as? com.hierynomus.mssmb2.SMBApiException)?.status == com.hierynomus.mserref.NtStatus.STATUS_BAD_NETWORK_NAME
    }

    private fun cancelled(e: Throwable): Boolean =
        Thread.currentThread().isInterrupted || SmbFaults.classify(e, false, null) == FaultClass.CANCEL

    private fun failed(error: BrowseError) = LocationTestResult(TestVerdict.FAILED, 0, 0, 0, 0, -1, onWifi(), System.currentTimeMillis(), error)

    private fun onWifi(): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (_: Exception) { false }

    companion object {
        private const val TAG = "SmbBrowse"

        /**
         * §7.6: directories only; `.`, `..`, hidden/system entries and `SMB_SKIP_FOLDERS` skipped (case-insensitive);
         * case-insensitive order; at most `SMB_LIST_MAX_ENTRIES` (second = truncated).
         */
        fun folderNames(entries: List<RemoteEntry>): Pair<List<String>, Boolean> {
            val skip = StorageConstants.SMB_SKIP_FOLDERS.map { it.lowercase() }.toSet()
            val all = entries.asSequence()
                .filter { it.directory && !it.hidden && it.name != "." && it.name != ".." && it.name.lowercase() !in skip }
                .map { it.name }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .toList()
            return if (all.size > StorageConstants.SMB_LIST_MAX_ENTRIES) all.take(StorageConstants.SMB_LIST_MAX_ENTRIES) to true else all to false
        }
    }
}
