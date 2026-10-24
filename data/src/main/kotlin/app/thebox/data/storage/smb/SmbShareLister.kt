// WP-S1 (docs/design/m6-storage-locations.md §7.5; lead ruling 4).
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
 */
class SmbShareLister {
    fun list(session: Session, signedInAsUser: Boolean): ShareListing = try {
        val transport = SMBTransportFactories.SRVSVC.getTransport(session)
        ShareListing(filter(ServerService(transport).getShares1().map { it.netName to it.type }))
    } catch (e: Exception) {
        BoxLog.i(TAG) { "share listing failed: ${SmbFaults.faultName(e)}" }
        ShareListing(emptyList(), errorFor(e, signedInAsUser))
    }

    companion object {
        private const val TAG = "SmbShares"
        /** `STYPE_DISKTREE` is 0 in the low bits; `STYPE_SPECIAL`/`STYPE_TEMPORARY` are flags above them. */
        private const val STYPE_MASK = 0x0FFFFFFF
        private const val STYPE_DISKTREE = 0

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
