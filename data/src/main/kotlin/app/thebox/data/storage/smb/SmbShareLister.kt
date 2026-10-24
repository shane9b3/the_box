// WP-S1 (docs/design/m6-storage-locations.md §7.5; lead ruling 4). Fix pass, core half (2026-10-24): C-S6.
package app.thebox.data.storage.smb

import app.thebox.core.common.BoxLog
import app.thebox.core.storage.BrowseError
import app.thebox.core.storage.BrowseErrorKind
import app.thebox.core.storage.ShareListing
import app.thebox.core.storage.StorageFailure
import app.thebox.core.storage.smb.FaultClass
import com.hierynomus.smbj.session.Session
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories

/**
 * Disk shares through DCERPC `NetrShareEnum` level 1 on `\PIPE\srvsvc` (§7.5): keep `STYPE_DISKTREE`, drop `$` names and
 * `IPC$`, sort A–Z. A refused listing still offers the typed share name (the page always shows that row):
 * - ACCESS_DENIED → [BrowseErrorKind.AUTH] when not signed in as a user (ask), else STORAGE/ACCESS;
 * - anything else → [BrowseErrorKind.LIST_FAILED] ("This computer didn't list its shared folders.").
 *
 * (C-S6) An interrupt (BACK while the srvsvc call waits) is rethrown, so the caller sees a cancel, never LIST_FAILED.
 * dcerpc 0.12.13 gives the pipe no public close; its IPC$ tree ends with the browse session's own connection
 * (`NetworkFolders.close`, C-B2), and a transport that does offer `close` is closed on the way out.
 */
class SmbShareLister {
    fun list(session: Session, signedInAsUser: Boolean): ShareListing {
        var transport: Any? = null
        try {
            val t = SMBTransportFactories.SRVSVC.getTransport(session)
            transport = t
            return ShareListing(filter(ServerService(t).getShares1().map { it.netName to it.type }))
        } catch (e: Exception) {
            if (isCancel(e)) throw e // C-S6
            BoxLog.i(TAG) { "share listing failed: ${SmbFaults.faultName(e)}" }
            return ShareListing(emptyList(), errorFor(e, signedInAsUser))
        } catch (e: LinkageError) {
            // (Integration 2026-10-24) dcerpc's malformed-response path names java.rmi.UnmarshalException, which Android
            // lacks: that is a NoClassDefFoundError here. The typed share name is still offered.
            BoxLog.i(TAG) { "share listing failed: ${e.javaClass.simpleName}" }
            return ShareListing(emptyList(), BrowseError(BrowseErrorKind.LIST_FAILED))
        } finally {
            (transport as? AutoCloseable)?.let { runCatching { it.close() } }
        }
    }

    companion object {
        private const val TAG = "SmbShares"
        /** `STYPE_DISKTREE` is 0 in the low bits; `STYPE_SPECIAL`/`STYPE_TEMPORARY` are flags above them. */
        private const val STYPE_MASK = 0x0FFFFFFF
        private const val STYPE_DISKTREE = 0

        /** C-S6: the thread was interrupted, or an interrupt is in the cause chain (smbj's `Futures.get`). */
        fun isCancel(e: Throwable): Boolean =
            Thread.currentThread().isInterrupted || SmbFaults.classify(e, interrupted = false, transportAlive = null) == FaultClass.CANCEL

        /** Pure: (name, type) pairs → the shares the page offers. */
        fun filter(shares: List<Pair<String?, Int>>): List<String> = shares.asSequence()
            .filter { (name, type) -> name != null && (type and STYPE_MASK) == STYPE_DISKTREE && type >= 0 }
            .map { it.first!!.trim() }
            .filter { it.isNotEmpty() && !it.endsWith('$') && !it.equals("IPC$", ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toList()

        fun errorFor(e: Throwable, signedInAsUser: Boolean): BrowseError =
            if (SmbFaults.classify(e, interrupted = false, transportAlive = null) == FaultClass.ACCESS && !SmbFaults.isLogonFailure(e)) {
                if (signedInAsUser) BrowseError(BrowseErrorKind.STORAGE, StorageFailure.ACCESS) else BrowseError(BrowseErrorKind.AUTH)
            } else BrowseError(BrowseErrorKind.LIST_FAILED)
    }
}
