// WP-S1 (docs/design/m6-storage-locations.md §4.3, §7.7, §8 "Logs"; A-B3, A-B4).
package app.thebox.data.storage.smb

import app.thebox.core.storage.BrowseError
import app.thebox.core.storage.BrowseErrorKind
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.smb.FaultClass
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb.SMB1NotSupportedException
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/**
 * The one IOException `:data`'s SMB code throws to `:core` (§12.2: `RemoteFile`/`RemoteShare` throw IOException). The
 * message is only the fault name, **never** smbj's `e.message`, which carries `\\host\share\path` (§8 "Logs"). The smbj
 * exception stays the [cause] for [SmbFaults.classify].
 */
class SmbIoException(cause: Throwable) : IOException("smb: " + SmbFaults.faultName(cause), cause)

/** `share(deadline)` / `reconnect` gave up: no READY link before the deadline. Classified LINK_LOST (UNREACHABLE). */
class LinkUnavailableException(what: String = "unavailable") : IOException("smb link $what")

/**
 * §4.3's classification of smbj failures, in order (Must):
 * 1. CANCEL: the thread is interrupted, or an `InterruptedException`/`InterruptedIOException` is anywhere in the chain
 *    (smbj's `Futures.get` turns an interrupt into a `TransportException`). Never a reconnect (A-B3).
 * 2. LINK_LOST: a dead transport ([transportAlive] false; `PacketReader` exit, reset, EOF, connect refused, or
 *    [LinkUnavailableException]), or the statuses NETWORK_NAME_DELETED, USER_SESSION_DELETED, NETWORK_SESSION_EXPIRED,
 *    FILE_CLOSED (and CONNECTION_DISCONNECTED/RESET).
 * 3. SLOW: an I/O wait timeout on a live transport (`TimeoutException` in the chain, `SocketTimeoutException`,
 *    STATUS_IO_TIMEOUT, STATUS_INSUFFICIENT_RESOURCES). Never a reconnect (A-B4).
 * 4. FULL: DISK_FULL, QUOTA_EXCEEDED.
 * 5. ACCESS: ACCESS_DENIED, LOGON_FAILURE, ACCOUNT_*, PASSWORD_EXPIRED (and guest signing required).
 * 6. REMOVED: OBJECT_NAME_NOT_FOUND, OBJECT_PATH_NOT_FOUND, DELETE_PENDING, FILE_DELETED.
 * 7. (C-S2) Any other NT status on a transport that is not known dead is a **server refusal**: SLOW (fail the operation,
 *    no reconnect; a write also needs a fresh file). INVALID_PARAMETER, SHARING_VIOLATION, FILE_LOCK_CONFLICT,
 *    NOT_SUPPORTED and the like come from a live server: reconnecting would `close(true)` a healthy connection for the
 *    readers and the keeper, again every `SMB_RECONNECT_MIN_GAP_MS` (lead ruling 1, A-B4: reconnect only on real
 *    transport errors). On a dead transport the status is LINK_LOST.
 * Anything else (no status at all) is LINK_LOST: the safe default is one bounded reconnect, never a silent success.
 */
object SmbFaults {
    // NTSTATUS codes the 0.15.0 enum does not name.
    private const val STATUS_QUOTA_EXCEEDED = 0xC0000044L
    private const val STATUS_ACCOUNT_RESTRICTION = 0xC000006EL
    private const val STATUS_INVALID_LOGON_HOURS = 0xC000006FL
    private const val STATUS_INVALID_WORKSTATION = 0xC0000070L
    private const val STATUS_ACCOUNT_EXPIRED = 0xC0000193L
    private const val STATUS_ACCOUNT_LOCKED_OUT = 0xC0000234L
    private const val STATUS_PASSWORD_MUST_CHANGE = 0xC0000224L

    fun chain(t: Throwable): Sequence<Throwable> = generateSequence(t) { it.cause.takeIf { c -> c !== it } }.take(16)

    /** [interrupted] is `Thread.currentThread().isInterrupted` of the thread that failed; [transportAlive] null = unknown. */
    fun classify(t: Throwable, interrupted: Boolean, transportAlive: Boolean?): FaultClass {
        val all = chain(t).toList()
        if (interrupted || all.any { it is InterruptedException || it is InterruptedIOException && it !is SocketTimeoutException }) return FaultClass.CANCEL
        if (all.any { it is LinkUnavailableException }) return FaultClass.LINK_LOST
        val status = statusOf(all)
        if (status != null && status in LINK_STATUSES) return FaultClass.LINK_LOST
        if (all.any { isDeadTransport(it) }) return FaultClass.LINK_LOST
        val timeout = all.any { it is TimeoutException || it is SocketTimeoutException } ||
            status == NtStatus.STATUS_IO_TIMEOUT.value || status == NtStatus.STATUS_INSUFFICIENT_RESOURCES.value // C-S2
        // A timeout on a transport that is already gone is a loss, not a slow server.
        if (timeout) return if (transportAlive == false) FaultClass.LINK_LOST else FaultClass.SLOW
        if (status != null) {
            when (status) {
                NtStatus.STATUS_DISK_FULL.value, STATUS_QUOTA_EXCEEDED -> return FaultClass.FULL
                in ACCESS_STATUSES -> return FaultClass.ACCESS
                in REMOVED_STATUSES -> return FaultClass.REMOVED
            }
        }
        if (all.any { it is SMB2GuestSigningRequiredException }) return FaultClass.ACCESS
        // C-S2: a status from a server whose transport is not known dead is a refusal, not a link loss.
        if (status != null) return if (transportAlive == false) FaultClass.LINK_LOST else FaultClass.SLOW
        return FaultClass.LINK_LOST
    }

    /** C-S7: an open refused because another client holds the file (the sweep keeps it). */
    fun isSharingViolation(t: Throwable): Boolean = statusOf(chain(t).toList()) == NtStatus.STATUS_SHARING_VIOLATION.value

    /** (Q-U10) The share name does not exist on that computer. */
    fun isBadShareName(t: Throwable): Boolean = chain(t).any {
        (it as? com.hierynomus.mssmb2.SMBApiException)?.status == NtStatus.STATUS_BAD_NETWORK_NAME
    }

    /** A logon status (wrong password, account problems): the keeper goes to AUTH_FAILED (§4.3 row 5). */
    fun isLogonFailure(t: Throwable): Boolean {
        val s = statusOf(chain(t).toList()) ?: return false
        return s in LOGON_STATUSES
    }

    fun isGuestRefused(t: Throwable): Boolean = chain(t).any { it is SMB2GuestSigningRequiredException }

    fun isSmb1Only(t: Throwable): Boolean = chain(t).any { it is SMB1NotSupportedException }

    /** A log-safe name: exception class names and the NT status name, never a message (§8). */
    fun faultName(t: Throwable): String {
        val all = chain(t).toList()
        val status = all.firstNotNullOfOrNull { it as? SMBApiException }?.let { e ->
            e.status?.takeIf { it != NtStatus.STATUS_OTHER }?.name ?: "0x" + java.lang.Long.toHexString(e.statusCode)
        }
        val names = all.filter { it !is SmbIoException }.map { it.javaClass.simpleName }.distinct().take(3)
        return (names + listOfNotNull(status)).joinToString("/")
    }

    /** §7.7: the browse/Test error for [t]. */
    fun browseError(t: Throwable, vpnUp: Boolean): BrowseError = when {
        isSmb1Only(t) -> BrowseError(BrowseErrorKind.SMB1)
        isGuestRefused(t) -> BrowseError(BrowseErrorKind.GUEST_REFUSED)
        isLogonFailure(t) -> BrowseError(BrowseErrorKind.AUTH)
        chain(t).any { it is UnknownHostException } -> BrowseError(BrowseErrorKind.STORAGE, StorageFailure.UNREACHABLE, vpnUp = vpnUp)
        else -> when (classify(t, interrupted = false, transportAlive = null)) {
            FaultClass.ACCESS -> BrowseError(BrowseErrorKind.STORAGE, StorageFailure.ACCESS)
            FaultClass.FULL -> BrowseError(BrowseErrorKind.STORAGE, StorageFailure.FULL)
            FaultClass.REMOVED -> BrowseError(BrowseErrorKind.NOT_FOUND)
            else -> BrowseError(BrowseErrorKind.STORAGE, StorageFailure.UNREACHABLE, vpnUp = vpnUp)
        }
    }

    private fun statusOf(all: List<Throwable>): Long? = all.firstNotNullOfOrNull { it as? SMBApiException }?.statusCode

    private fun isDeadTransport(t: Throwable): Boolean = when (t) {
        is SocketTimeoutException -> false
        is EOFException, is ConnectException, is NoRouteToHostException, is UnknownHostException -> true
        is SocketException -> true // reset, broken pipe, closed
        is TransportException -> t.cause == null && t !is SMB1NotSupportedException // "transport is disconnected"
        else -> false
    }

    private val LINK_STATUSES = setOf(
        NtStatus.STATUS_NETWORK_NAME_DELETED.value, NtStatus.STATUS_USER_SESSION_DELETED.value,
        NtStatus.STATUS_NETWORK_SESSION_EXPIRED.value, NtStatus.STATUS_FILE_CLOSED.value,
        NtStatus.STATUS_CONNECTION_DISCONNECTED.value, NtStatus.STATUS_CONNECTION_RESET.value,
    )
    private val LOGON_STATUSES = setOf(
        NtStatus.STATUS_LOGON_FAILURE.value, NtStatus.STATUS_PASSWORD_EXPIRED.value, NtStatus.STATUS_ACCOUNT_DISABLED.value,
        NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED.value, STATUS_ACCOUNT_RESTRICTION, STATUS_INVALID_LOGON_HOURS,
        STATUS_INVALID_WORKSTATION, STATUS_ACCOUNT_EXPIRED, STATUS_ACCOUNT_LOCKED_OUT, STATUS_PASSWORD_MUST_CHANGE,
    )
    private val ACCESS_STATUSES = LOGON_STATUSES + NtStatus.STATUS_ACCESS_DENIED.value
    private val REMOVED_STATUSES = setOf(
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND.value, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND.value,
        NtStatus.STATUS_DELETE_PENDING.value, NtStatus.STATUS_FILE_DELETED.value,
    )
}
