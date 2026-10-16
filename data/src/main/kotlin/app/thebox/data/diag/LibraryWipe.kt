package app.thebox.data.diag

import app.thebox.data.db.LibraryDatabase
import app.thebox.data.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Deletes every `library.db` row (playlists, credentials, user data, `app_setting`). Used only by the debug harness's
 * `wipe` (docs/design/m5-setup-settings.md §6.5, the in-process equivalent of Clear data for the instrumented suite);
 * `:app`'s debug source set cannot see Room, so the call lives here. Nothing in the release app calls it.
 */
class LibraryWipe @Inject constructor(private val db: LibraryDatabase, @IoDispatcher private val io: CoroutineDispatcher) {
    suspend fun clearAll() = withContext(io) { db.clearAllTables() }
}
