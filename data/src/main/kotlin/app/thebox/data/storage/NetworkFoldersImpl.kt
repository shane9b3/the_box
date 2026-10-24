// WP-S1 replaces this stub (docs/design/m6-storage-locations.md §7; storage locations commit 0, §17). find() delegates to
// WP-S3's ComputerFinder.
package app.thebox.data.storage

import app.thebox.core.storage.BrowseHandle
import app.thebox.core.storage.BrowseResult
import app.thebox.core.storage.FindState
import app.thebox.core.storage.FolderListing
import app.thebox.core.storage.LocationTestResult
import app.thebox.core.storage.NetworkFolders
import app.thebox.core.storage.ShareListing
import app.thebox.core.storage.SmbAddress
import app.thebox.core.storage.TestProgress
import app.thebox.data.storage.discovery.ComputerFinder
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Browse, sign-in, shares, folders and the Test on `box-smb-browse`, over the browse `SMBClient` (never the ring's,
 * lead ruling 6). Finding computers is [finder]'s (WP-S3).
 */
@Singleton
class NetworkFoldersImpl @Inject constructor(private val finder: ComputerFinder) : NetworkFolders {
    override fun find(): Flow<FindState> = finder.search()

    override suspend fun open(address: SmbAddress, password: CharArray?): BrowseResult = TODO("WP-S1")

    override suspend fun shares(session: BrowseHandle): ShareListing = TODO("WP-S1")

    override suspend fun folders(session: BrowseHandle, share: String, path: String): FolderListing = TODO("WP-S1")

    override suspend fun test(session: BrowseHandle, share: String, path: String, progress: (TestProgress) -> Unit): LocationTestResult = TODO("WP-S1")

    override fun close(session: BrowseHandle): Unit = TODO("WP-S1")
}
