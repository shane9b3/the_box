package app.thebox.data.repo

import androidx.room.PooledConnection
import app.thebox.core.refresh.KeyRemap

/**
 * Moves user data from old to new stable keys (§3.3.6): `UPDATE OR IGNORE … SET stable_key = new WHERE
 * stable_key = old`, then deletes leftover old rows (the new key already had data: the current row wins).
 * Covers favourite, channel_prefs, epg_mapping, reminder and stream_source (both sides). The derived
 * epg_match rows of the old key are dropped (re-matched later). Runs inside the caller's transaction.
 */
internal object UserDataRemapper {
    private val TABLES = listOf("favourite", "channel_prefs", "epg_mapping", "reminder")

    suspend fun apply(conn: PooledConnection, playlistId: Long, remaps: List<KeyRemap>): Int {
        if (remaps.isEmpty()) return 0
        for (table in TABLES) {
            conn.forEachBound("UPDATE OR IGNORE $table SET stable_key = ? WHERE playlist_id = ? AND stable_key = ?", remaps) {
                bindText(1, it.newKey); bindLong(2, playlistId); bindText(3, it.oldKey)
            }
            conn.forEachBound("DELETE FROM $table WHERE playlist_id = ? AND stable_key = ?", remaps) {
                bindLong(1, playlistId); bindText(2, it.oldKey)
            }
        }
        conn.forEachBound("UPDATE OR IGNORE stream_source SET stable_key = ? WHERE playlist_id = ? AND stable_key = ?", remaps) {
            bindText(1, it.newKey); bindLong(2, playlistId); bindText(3, it.oldKey)
        }
        conn.forEachBound("DELETE FROM stream_source WHERE playlist_id = ? AND stable_key = ?", remaps) {
            bindLong(1, playlistId); bindText(2, it.oldKey)
        }
        conn.forEachBound("UPDATE stream_source SET source_stable_key = ? WHERE source_playlist_id = ? AND source_stable_key = ?", remaps) {
            bindText(1, it.newKey); bindLong(2, playlistId); bindText(3, it.oldKey)
        }
        conn.forEachBound("DELETE FROM epg_match WHERE playlist_id = ? AND stable_key = ?", remaps) {
            bindLong(1, playlistId); bindText(2, it.oldKey)
        }
        return remaps.size
    }
}
