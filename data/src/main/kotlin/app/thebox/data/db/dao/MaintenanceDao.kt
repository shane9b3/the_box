package app.thebox.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.thebox.data.db.entity.PurgedKeyEntity
import app.thebox.data.db.entity.StreamSourceEntity

/**
 * `purged_key` tombstones, the 180-day orphan trim (S8) and `stream_source` (ships empty in v1).
 * The orphan age is `purged_key.purged_at`, never `favourite.added_at` / `channel_prefs.updated_at`.
 */
@Dao
interface MaintenanceDao {
    @Upsert
    suspend fun upsertTombstone(row: PurgedKeyEntity)

    @Query("SELECT * FROM purged_key WHERE playlist_id = :playlistId ORDER BY purged_at")
    suspend fun tombstones(playlistId: Long): List<PurgedKeyEntity>

    @Query("DELETE FROM favourite WHERE EXISTS (SELECT 1 FROM purged_key t WHERE t.purged_at < :cutoff AND t.playlist_id = favourite.playlist_id AND t.stable_key = favourite.stable_key) AND NOT EXISTS (SELECT 1 FROM channel c WHERE c.playlist_id = favourite.playlist_id AND c.stable_key = favourite.stable_key)")
    suspend fun trimFavourites(cutoff: Long): Int

    @Query("DELETE FROM channel_prefs WHERE EXISTS (SELECT 1 FROM purged_key t WHERE t.purged_at < :cutoff AND t.playlist_id = channel_prefs.playlist_id AND t.stable_key = channel_prefs.stable_key) AND NOT EXISTS (SELECT 1 FROM channel c WHERE c.playlist_id = channel_prefs.playlist_id AND c.stable_key = channel_prefs.stable_key)")
    suspend fun trimPrefs(cutoff: Long): Int

    @Query("DELETE FROM epg_mapping WHERE EXISTS (SELECT 1 FROM purged_key t WHERE t.purged_at < :cutoff AND t.playlist_id = epg_mapping.playlist_id AND t.stable_key = epg_mapping.stable_key) AND NOT EXISTS (SELECT 1 FROM channel c WHERE c.playlist_id = epg_mapping.playlist_id AND c.stable_key = epg_mapping.stable_key)")
    suspend fun trimMappings(cutoff: Long): Int

    @Query("DELETE FROM reminder WHERE EXISTS (SELECT 1 FROM purged_key t WHERE t.purged_at < :cutoff AND t.playlist_id = reminder.playlist_id AND t.stable_key = reminder.stable_key) AND NOT EXISTS (SELECT 1 FROM channel c WHERE c.playlist_id = reminder.playlist_id AND c.stable_key = reminder.stable_key)")
    suspend fun trimReminders(cutoff: Long): Int

    @Query("DELETE FROM stream_source WHERE EXISTS (SELECT 1 FROM purged_key t WHERE t.purged_at < :cutoff AND t.playlist_id = stream_source.playlist_id AND t.stable_key = stream_source.stable_key) AND NOT EXISTS (SELECT 1 FROM channel c WHERE c.playlist_id = stream_source.playlist_id AND c.stable_key = stream_source.stable_key)")
    suspend fun trimStreamSources(cutoff: Long): Int

    @Query("DELETE FROM purged_key WHERE purged_at < :cutoff AND NOT EXISTS (SELECT 1 FROM channel c WHERE c.playlist_id = purged_key.playlist_id AND c.stable_key = purged_key.stable_key)")
    suspend fun trimTombstones(cutoff: Long): Int

    /** Removes user data of keys purged before [cutoff] that never came back. Returns rows deleted. */
    @Transaction
    suspend fun trimOrphans(cutoff: Long): Int =
        trimFavourites(cutoff) + trimPrefs(cutoff) + trimMappings(cutoff) + trimReminders(cutoff) + trimStreamSources(cutoff) + trimTombstones(cutoff)

    @Upsert
    suspend fun upsertStreamSource(row: StreamSourceEntity)

    @Query("SELECT * FROM stream_source WHERE playlist_id = :playlistId AND stable_key = :stableKey ORDER BY priority")
    suspend fun streamSources(playlistId: Long, stableKey: String): List<StreamSourceEntity>
}
