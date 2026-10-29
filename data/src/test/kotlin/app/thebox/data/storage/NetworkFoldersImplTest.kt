// Fix pass, core half (2026-10-24): C-B2 and C-S6 (docs/review/2026-10-24-storage-locations-review-core.md).
package app.thebox.data.storage

import androidx.test.core.app.ApplicationProvider
import app.thebox.core.setup.lan.LanAddressFinder
import app.thebox.core.storage.BrowseError
import app.thebox.core.storage.BrowseErrorKind
import app.thebox.core.storage.BrowseResult
import app.thebox.core.storage.FindState
import app.thebox.core.storage.ShareListing
import app.thebox.core.storage.SignInMode
import app.thebox.core.storage.SmbAddress
import app.thebox.core.storage.StorageFailure
import app.thebox.data.storage.discovery.ComputerFinder
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/** One fake browse connection: its own transport, closed only by its own [closeForce] (as each smbj client's is). */
internal class FakeBrowseConnection(val host: String) : BrowseConnection {
    @Volatile var closed = false
    val modes = CopyOnWriteArrayList<String>()
    @Volatile var onAuthenticate: (AuthenticationContext) -> Unit = {}
    override val isConnected: Boolean get() = !closed
    override fun authenticate(ctx: AuthenticationContext): BrowseSession {
        modes += if (ctx.isAnonymous) "anonymous" else if (ctx.isGuest) "guest" else "user"
        onAuthenticate(ctx)
        return object : BrowseSession {
            override fun shares(signedInAsUser: Boolean): ShareListing =
                if (closed) ShareListing(emptyList(), BrowseError(BrowseErrorKind.STORAGE, StorageFailure.UNREACHABLE)) // a dead transport
                else ShareListing(listOf("Private", "TV"))
            override fun disk(share: String): DiskShare = throw IOException("not in this fake")
        }
    }
    override fun closeForce() { closed = true }
}

internal class FakeBrowseConnections : BrowseConnections {
    val made = CopyOnWriteArrayList<FakeBrowseConnection>()
    @Volatile var onAuthenticate: (AuthenticationContext) -> Unit = {}
    override fun connect(host: String, port: Int): BrowseConnection = FakeBrowseConnection(host).also { it.onAuthenticate = { c -> onAuthenticate(c) }; made += it }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NetworkFoldersImplTest {
    private val conns = FakeBrowseConnections()
    private val folders = NetworkFoldersImpl(
        finder = object : ComputerFinder {
            override fun search(): Flow<FindState> = emptyFlow()
        },
        lan = object : LanAddressFinder { override fun find(): String? = "192.168.1.37"; override fun vpnActive() = false },
        context = ApplicationProvider.getApplicationContext(),
        deviceId = DeviceIdentity(Files.createTempDirectory("nf").toFile()),
        stored = { error("no stored sign-in in these tests") },
        connections = conns,
    )
    private val nas = SmbAddress("192.168.1.20", share = "")

    private fun eventually(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!cond()) { check(System.nanoTime() < end) { "timed out waiting for $what" }; Thread.sleep(5) }
    }

    /**
     * C-B2: a Samba NAS lists its shares anonymously; the private share asks for a sign-in; the page closes the old
     * session once the new one opened. The new session must still work (it used to share the old one's connection).
     */
    @Test fun reSignInInsideABrowseKeepsTheNewSessionsConnection() = runBlocking {
        val first = folders.open(nas, null) as BrowseResult.Opened
        assertEquals(SignInMode.ANONYMOUS, first.handle.mode)
        assertEquals(listOf("Private", "TV"), folders.shares(first.handle).shares)
        val second = folders.open(nas.copy(user = "shawn"), "s3cret!".toCharArray()) as BrowseResult.Opened
        assertEquals(2, conns.made.size)
        assertTrue("each session has its own connection", conns.made[0] !== conns.made[1])
        folders.close(first.handle) // StoragePages.onOpened closes the previous handle
        val listing = folders.shares(second.handle) // runs after the posted close on box-smb-browse
        assertNull("the new session's connection is alive", listing.error)
        assertEquals(listOf("Private", "TV"), listing.shares)
        assertTrue(conns.made[0].closed)
        assertFalse(conns.made[1].closed)
        assertEquals(1, folders.openSessions)
    }

    /** C-B2: a refused or failed sign-in closes only the connection it made, never another session's. */
    @Test fun aRefusedSignInClosesOnlyItsOwnConnection() = runBlocking {
        val first = folders.open(nas, null) as BrowseResult.Opened
        conns.onAuthenticate = { throw com.hierynomus.mssmb2.SMBApiException(com.hierynomus.mserref.NtStatus.STATUS_LOGON_FAILURE.value, com.hierynomus.mssmb2.SMB2MessageCommandCode.SMB2_SESSION_SETUP, null) }
        val r = folders.open(nas.copy(user = "shawn"), "wrong".toCharArray())
        assertTrue(r is BrowseResult.NeedsSignIn)
        assertTrue(conns.made[1].closed)
        assertFalse(conns.made[0].closed)
        conns.onAuthenticate = {}
        assertNull(folders.shares(first.handle).error)
    }

    /** C-S6: BACK just as the server accepts the sign-in: the session it made is closed, nothing outlives the caller. */
    @Test fun cancelAfterSignInClosesTheSession() = runBlocking {
        val job = CompletableDeferred<Job>()
        conns.onAuthenticate = { runBlocking { job.await() }.cancel() } // the cancel lands while the server answers
        val j = launch(Dispatchers.Default) { folders.open(nas.copy(user = "shawn"), "s3cret!".toCharArray()) }
        job.complete(j)
        j.join()
        assertTrue(j.isCancelled)
        eventually("the session closed") { conns.made.single().closed }
        assertEquals(0, folders.openSessions)
    }
}
