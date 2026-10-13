package app.thebox.data.repo

import app.thebox.core.common.Hash64
import app.thebox.core.refresh.ExistingChannel
import app.thebox.core.refresh.ExistingChannels
import app.thebox.core.source.EntryKind
import app.thebox.data.db.LibraryDatabase
import kotlinx.coroutines.runBlocking

/**
 * Streams the existing rows of one playlist for the differ (§3.3.2) over a reader connection. `urlHash`
 * and `nameHash` are computed while streaming (they are not stored); the strings are dropped immediately.
 * `kind` and `group_key` (joined from `channel_group`) feed S18, `KeyRemap.sameGroup` and the group counts
 * (amended 2026-10-13). Blocking; call on the importer thread.
 */
class RoomExistingChannels(private val db: LibraryDatabase, private val playlistId: Long) : ExistingChannels {
    override fun forEach(block: (ExistingChannel) -> Unit): Unit = runBlocking {
        db.read {
            usePrepared(
                "SELECT c.id, c.stable_key, c.content_hash, c.url, c.name, c.sort_index, c.missing_since, c.missing_count, c.kind, g.group_key " +
                    "FROM channel c LEFT JOIN channel_group g ON g.id = c.group_id WHERE c.playlist_id = ?",
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
                            kind = EntryKind.entries.getOrElse(st.getLong(8).toInt()) { EntryKind.LIVE },
                            // A row whose group was deleted (FK SET NULL) is ungrouped: the empty key never matches a group.
                            groupKey = if (st.isNull(9)) "" else st.getText(9),
                        ),
                    )
                }
            }
        }
    }
}
