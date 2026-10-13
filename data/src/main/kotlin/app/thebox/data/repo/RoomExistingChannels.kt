package app.thebox.data.repo

import app.thebox.core.common.Hash64
import app.thebox.core.refresh.ExistingChannel
import app.thebox.core.refresh.ExistingChannels
import app.thebox.data.db.LibraryDatabase
import kotlinx.coroutines.runBlocking

/**
 * Streams the existing rows of one playlist for the differ (§3.3.2) over a reader connection. `urlHash`
 * and `nameHash` are computed while streaming (they are not stored); the strings are dropped immediately.
 * Blocking; call on the importer thread.
 */
class RoomExistingChannels(private val db: LibraryDatabase, private val playlistId: Long) : ExistingChannels {
    override fun forEach(block: (ExistingChannel) -> Unit): Unit = runBlocking {
        db.read {
            usePrepared(
                "SELECT id, stable_key, content_hash, url, name, sort_index, missing_since, missing_count FROM channel WHERE playlist_id = ?",
            ) { st ->
                st.bindLong(1, playlistId)
                while (st.step()) {
                    block(
                        ExistingChannel(
                            id = st.getLong(0),
                            stableKey = st.getText(1),
                            contentHash = st.getLong(2),
                            urlHash = Hash64.of(st.getText(3)),
                            nameHash = Hash64.of(st.getText(4)),
                            sortIndex = st.getLong(5).toInt(),
                            missingSince = st.longOrNull(6),
                            missingCount = st.getLong(7).toInt(),
                        ),
                    )
                }
            }
        }
    }
}
