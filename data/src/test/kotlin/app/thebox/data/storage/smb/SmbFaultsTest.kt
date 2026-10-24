package app.thebox.data.storage.smb

import app.thebox.core.storage.BrowseErrorKind
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.smb.FaultClass
import app.thebox.core.storage.smb.RemoteEntry
import app.thebox.data.storage.NetworkFoldersImpl
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb.SMB1NotSupportedException
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/** §16.1 `SmbFaultTest` (WP-S1), the smbj side: §4.3's order over real smbj exception shapes; log-safe names (§8). */
class SmbFaultsTest {
    private fun status(s: NtStatus) = SMBApiException(s.value, SMB2MessageCommandCode.SMB2_WRITE, "\\\\nas\\homes\\shawn\\TheBox-rewind\\ring.bin", null)
    private fun code(c: Long) = SMBApiException(c, SMB2MessageCommandCode.SMB2_SESSION_SETUP, null)
    private fun cls(t: Throwable, alive: Boolean? = true) = SmbFaults.classify(t, interrupted = false, transportAlive = alive)

    @Test fun anInterruptIsACancelBeforeAnythingElse() {
        // smbj's Futures.get wraps an interrupt into a TransportException (Futures.java:29-49).
        assertEquals(FaultClass.CANCEL, cls(SMBRuntimeException(TransportException(InterruptedException()))))
        assertEquals(FaultClass.CANCEL, cls(TransportException(InterruptedIOException())))
        assertEquals(FaultClass.CANCEL, SmbFaults.classify(status(NtStatus.STATUS_DISK_FULL), interrupted = true, transportAlive = true))
        assertEquals(FaultClass.CANCEL, SmbFaults.classify(TransportException(EOFException()), interrupted = true, transportAlive = false))
        // A socket timeout is an InterruptedIOException subclass, but not a cancel.
        assertEquals(FaultClass.SLOW, cls(SocketTimeoutException()))
    }

    @Test fun deadTransportsAreLinkLost() {
        assertEquals(FaultClass.LINK_LOST, cls(SMBRuntimeException(TransportException(ExecutionException(EOFException("EOF while reading packet"))))))
        assertEquals(FaultClass.LINK_LOST, cls(TransportException("Cannot write SMB2_WRITE as transport is disconnected")))
        assertEquals(FaultClass.LINK_LOST, cls(SocketException("Connection reset")))
        assertEquals(FaultClass.LINK_LOST, cls(ConnectException("refused")))
        assertEquals(FaultClass.LINK_LOST, cls(LinkUnavailableException()))
        for (s in listOf(NtStatus.STATUS_NETWORK_NAME_DELETED, NtStatus.STATUS_USER_SESSION_DELETED, NtStatus.STATUS_NETWORK_SESSION_EXPIRED, NtStatus.STATUS_FILE_CLOSED)) {
            assertEquals(s.name, FaultClass.LINK_LOST, cls(status(s)))
        }
    }

    @Test fun aTimeoutOnALiveTransportIsSlowAndOnADeadOneLinkLost() {
        val timeout = SMBRuntimeException(TransportException(TimeoutException("Timeout expired")))
        assertEquals(FaultClass.SLOW, cls(timeout, alive = true))
        assertEquals(FaultClass.SLOW, cls(timeout, alive = null))
        assertEquals(FaultClass.LINK_LOST, cls(timeout, alive = false))
    }

    @Test fun statuses() {
        assertEquals(FaultClass.FULL, cls(status(NtStatus.STATUS_DISK_FULL)))
        assertEquals(FaultClass.FULL, cls(code(0xC0000044L))) // QUOTA_EXCEEDED
        assertEquals(FaultClass.ACCESS, cls(status(NtStatus.STATUS_ACCESS_DENIED)))
        assertEquals(FaultClass.ACCESS, cls(status(NtStatus.STATUS_LOGON_FAILURE)))
        assertEquals(FaultClass.ACCESS, cls(status(NtStatus.STATUS_PASSWORD_EXPIRED)))
        assertEquals(FaultClass.ACCESS, cls(code(0xC0000234L))) // ACCOUNT_LOCKED_OUT
        assertEquals(FaultClass.ACCESS, cls(SMB2GuestSigningRequiredException()))
        for (s in listOf(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_DELETE_PENDING, NtStatus.STATUS_FILE_DELETED, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND)) {
            assertEquals(s.name, FaultClass.REMOVED, cls(status(s)))
        }
        assertTrue(SmbFaults.isLogonFailure(status(NtStatus.STATUS_LOGON_FAILURE)))
        assertFalse(SmbFaults.isLogonFailure(status(NtStatus.STATUS_ACCESS_DENIED)))
        // Unknown: one bounded reconnect, never a silent success.
        assertEquals(FaultClass.LINK_LOST, cls(IllegalStateException()))
    }

    /** C-S2 (the review probe): A-B4 / lead ruling 1 — a live server's refusal is not a link loss; never a reconnect. */
    @Test fun aServerStatusOnALiveTransportIsNotALinkLoss() {
        for (s in listOf(NtStatus.STATUS_INVALID_PARAMETER, NtStatus.STATUS_SHARING_VIOLATION, NtStatus.STATUS_INSUFFICIENT_RESOURCES,
            NtStatus.STATUS_FILE_LOCK_CONFLICT, NtStatus.STATUS_NOT_SUPPORTED)) {
            val e = SMBApiException(s.value, SMB2MessageCommandCode.SMB2_WRITE, null)
            assertEquals(s.name, FaultClass.SLOW, SmbFaults.classify(e, interrupted = false, transportAlive = true))
            assertEquals(s.name, FaultClass.SLOW, SmbFaults.classify(e, interrupted = false, transportAlive = null))
            assertEquals("on a dead transport: ${s.name}", FaultClass.LINK_LOST, SmbFaults.classify(e, interrupted = false, transportAlive = false))
        }
        assertEquals(FaultClass.SLOW, cls(status(NtStatus.STATUS_IO_TIMEOUT)))
        assertTrue(SmbFaults.isSharingViolation(status(NtStatus.STATUS_SHARING_VIOLATION)))
        assertFalse(SmbFaults.isSharingViolation(status(NtStatus.STATUS_ACCESS_DENIED)))
    }

    @Test fun logNamesAndMessagesCarryNoPathOrUser() {
        val e = status(NtStatus.STATUS_ACCESS_DENIED)
        assertTrue(e.message!!.contains("shawn")) // smbj's own message does
        val name = SmbFaults.faultName(SMBRuntimeException(e))
        assertFalse(name, name.contains("shawn") || name.contains("nas") || name.contains("\\"))
        assertTrue(name, name.contains("STATUS_ACCESS_DENIED"))
        val io = SmbIoException(e)
        assertFalse(io.message!!, io.message!!.contains("shawn"))
        assertEquals("0xc0000234", SmbFaults.faultName(code(0xC0000234L)).substringAfterLast('/'))
    }

    @Test fun browseErrors() {
        assertEquals(BrowseErrorKind.SMB1, SmbFaults.browseError(SMB1NotSupportedException(), false).kind)
        assertEquals(BrowseErrorKind.GUEST_REFUSED, SmbFaults.browseError(SMB2GuestSigningRequiredException(), false).kind)
        assertEquals(BrowseErrorKind.AUTH, SmbFaults.browseError(status(NtStatus.STATUS_LOGON_FAILURE), false).kind)
        val unreachable = SmbFaults.browseError(ConnectException(), vpnUp = true)
        assertEquals(BrowseErrorKind.STORAGE, unreachable.kind)
        assertEquals(StorageFailure.UNREACHABLE, unreachable.failure)
        assertTrue(unreachable.vpnUp)
        assertEquals(StorageFailure.ACCESS, SmbFaults.browseError(status(NtStatus.STATUS_ACCESS_DENIED), false).failure)
    }

    @Test fun shareAndFolderFilters() {
        val shares = SmbShareLister.filter(listOf(
            "TV" to 0, "ADMIN$" to 0x80000000.toInt(), "IPC$" to 0x80000003.toInt(), "c$" to 0, "printer" to 1,
            "Backups" to 0, "apps" to 0x40000000, null to 0, "tv" to 0,
        ))
        assertEquals(listOf("apps", "Backups", "TV"), shares)
        val (folders, truncated) = NetworkFoldersImpl.folderNames(listOf(
            RemoteEntry("Rewind", true, false, 0), RemoteEntry("\$RECYCLE.BIN", true, true, 0), RemoteEntry("#recycle", true, false, 0),
            RemoteEntry("@eaDir", true, false, 0), RemoteEntry("movie.mkv", false, false, 1), RemoteEntry(".", true, false, 0),
            RemoteEntry("hidden", true, true, 0), RemoteEntry("archive", true, false, 0), RemoteEntry("system volume information", true, false, 0),
        ))
        assertEquals(listOf("archive", "Rewind"), folders)
        assertFalse(truncated)
        val many = (1..2_500).map { RemoteEntry("f%04d".format(it), true, false, 0) }
        val (first, cut) = NetworkFoldersImpl.folderNames(many)
        assertEquals(2_000, first.size)
        assertTrue(cut)
    }
}
