package app.thebox.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.thebox.data.db.entity.ChannelPrefsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelPrefsDao {
    @Query("SELECT * FROM channel_prefs WHERE playlist_id = :playlistId AND stable_key = :stableKey")
    suspend fun get(playlistId: Long, stableKey: String): ChannelPrefsEntity?

    @Query("SELECT * FROM channel_prefs WHERE playlist_id = :playlistId AND stable_key = :stableKey")
    fun observe(playlistId: Long, stableKey: String): Flow<ChannelPrefsEntity?>

    @Upsert
    suspend fun upsert(prefs: ChannelPrefsEntity)

    @Query("DELETE FROM channel_prefs WHERE playlist_id = :playlistId AND stable_key = :stableKey")
    suspend fun delete(playlistId: Long, stableKey: String)

    /** Inserts the row if needed, then bumps the watch counters (recents, S13 ordering). */
    @Query(
        """
        INSERT INTO channel_prefs(playlist_id, stable_key, last_watched_at, watch_count, updated_at, hidden)
        VALUES (:playlistId, :stableKey, :at, 1, :at, 0)
        ON CONFLICT(playlist_id, stable_key) DO UPDATE SET last_watched_at = :at, watch_count = watch_count + 1, updated_at = :at""",
    )
    suspend fun recordWatched(playlistId: Long, stableKey: String, at: Long)

    @Query(
        """
        SELECT cp.* FROM channel_prefs cp
        JOIN channel c ON c.playlist_id = cp.playlist_id AND c.stable_key = cp.stable_key AND c.missing_since IS NULL
        WHERE cp.last_watched_at IS NOT NULL ORDER BY cp.last_watched_at DESC LIMIT :limit""",
    )
    fun recent(limit: Int): Flow<List<ChannelPrefsEntity>>
}
