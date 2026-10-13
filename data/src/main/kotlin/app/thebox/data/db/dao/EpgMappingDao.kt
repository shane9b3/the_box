package app.thebox.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.thebox.data.db.entity.EpgMappingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EpgMappingDao {
    @Upsert
    suspend fun upsert(mapping: EpgMappingEntity)

    @Query("DELETE FROM epg_mapping WHERE playlist_id = :playlistId AND stable_key = :stableKey")
    suspend fun delete(playlistId: Long, stableKey: String)

    @Query("SELECT * FROM epg_mapping WHERE playlist_id = :playlistId AND stable_key = :stableKey")
    suspend fun get(playlistId: Long, stableKey: String): EpgMappingEntity?

    @Query("SELECT * FROM epg_mapping WHERE playlist_id = :playlistId AND stable_key = :stableKey")
    fun observe(playlistId: Long, stableKey: String): Flow<EpgMappingEntity?>

    @Query("SELECT * FROM epg_mapping")
    suspend fun all(): List<EpgMappingEntity>

    @Query("SELECT * FROM epg_mapping WHERE source_id = :sourceId")
    suspend fun forSource(sourceId: Long): List<EpgMappingEntity>
}
