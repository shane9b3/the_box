package app.thebox.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.thebox.data.db.entity.EpgMatchEntity

data class SubjectHash(
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "stable_key") val stableKey: String,
    @ColumnInfo(name = "inputs_hash") val inputsHash: Long,
)

/** Matcher cache. Bulk writes (per-source replace, has_data) go through EpgMatchRepository's prepared statements. */
@Dao
interface EpgMatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<EpgMatchEntity>)

    @Query("SELECT * FROM epg_match WHERE playlist_id = :playlistId AND stable_key = :stableKey ORDER BY status, score DESC")
    suspend fun forSubject(playlistId: Long, stableKey: String): List<EpgMatchEntity>

    @Query("SELECT * FROM epg_match WHERE status = 0 ORDER BY playlist_id, stable_key")
    suspend fun autos(): List<EpgMatchEntity>

    @Query("SELECT * FROM epg_match WHERE source_id = :sourceId AND status = 0")
    suspend fun autosForSource(sourceId: Long): List<EpgMatchEntity>

    /** One inputs_hash per matched subject (all rows of a subject carry the same hash). */
    @Query("SELECT playlist_id, stable_key, MAX(inputs_hash) AS inputs_hash FROM epg_match WHERE playlist_id = :playlistId GROUP BY playlist_id, stable_key")
    suspend fun subjectHashes(playlistId: Long): List<SubjectHash>

    /** EPG channels of [sourceId] last known to have programmes (the published generation's `has_data`). */
    @Query("SELECT DISTINCT xmltv_id FROM epg_match WHERE source_id = :sourceId AND has_data = 1")
    suspend fun xmltvIdsWithData(sourceId: Long): List<String>

    @Query("SELECT COUNT(*) FROM epg_match")
    suspend fun count(): Int

    @Query("DELETE FROM epg_match WHERE source_id = :sourceId")
    suspend fun deleteForSource(sourceId: Long)
}
