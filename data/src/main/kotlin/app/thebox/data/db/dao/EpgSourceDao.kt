package app.thebox.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import app.thebox.data.db.entity.EpgSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EpgSourceDao {
    @Insert
    suspend fun insert(source: EpgSourceEntity): Long

    @Update
    suspend fun update(source: EpgSourceEntity)

    @Query("DELETE FROM epg_source WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM epg_source WHERE id = :id")
    suspend fun get(id: Long): EpgSourceEntity?

    /**
     * Every row, including restore placeholders ([EpgSourceEntity.isPlaceholder], [EpgSourceEntity.PLACEHOLDER_SQL]).
     * A reader that acts on guides (downloads, matches, schedules) must use [active] or filter placeholders itself.
     */
    @Query("SELECT * FROM epg_source ORDER BY priority, id")
    fun observeAll(): Flow<List<EpgSourceEntity>>

    /**
     * Every row, including restore placeholders ([EpgSourceEntity.isPlaceholder], [EpgSourceEntity.PLACEHOLDER_SQL]).
     * A reader that acts on guides (downloads, matches, schedules) must use [active] or filter placeholders itself.
     */
    @Query("SELECT * FROM epg_source ORDER BY priority, id")
    suspend fun all(): List<EpgSourceEntity>

    @Query("SELECT * FROM epg_source WHERE playlist_id = :playlistId ORDER BY origin, ordinal")
    suspend fun forPlaylist(playlistId: Long): List<EpgSourceEntity>

    @Query("SELECT * FROM epg_source WHERE playlist_id = :playlistId AND origin = :origin AND ordinal = :ordinal")
    suspend fun byOrigin(playlistId: Long, origin: String, ordinal: Int): EpgSourceEntity?

    /**
     * Sources that take part in the guide, in priority order: enabled USER sources, and enabled sources of
     * enabled playlists that use their playlist EPG. Restore placeholders are left out: they have no address
     * until the playlist's first refresh fills them in (docs/design/m5-restore-placeholder-guides.md §3).
     */
    @Query(
        """
        SELECT s.* FROM epg_source s
        LEFT JOIN playlist p ON p.id = s.playlist_id
        WHERE s.enabled = 1 AND (s.playlist_id IS NULL OR (p.enabled = 1 AND p.use_playlist_epg = 1))
          AND NOT """ + EpgSourceEntity.PLACEHOLDER_SQL + """
        ORDER BY s.priority, s.id""",
    )
    suspend fun active(): List<EpgSourceEntity>

    @Query("SELECT COALESCE(MAX(priority), -1) FROM epg_source")
    suspend fun maxPriority(): Int

    @Query(
        """
        UPDATE epg_source SET etag = :etag, last_modified = :lastModified, cached_file = :cachedFile, cached_bytes = :cachedBytes,
               last_import_at = :at, last_status = :status, channel_count = :channels, programme_count = :programmes
        WHERE id = :id""",
    )
    suspend fun setImported(
        id: Long, etag: String?, lastModified: String?, cachedFile: String?, cachedBytes: Long?,
        at: Long, status: String, channels: Int, programmes: Int,
    )

    @Query("UPDATE epg_source SET last_status = :status, last_import_at = :at WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, at: Long)
}
