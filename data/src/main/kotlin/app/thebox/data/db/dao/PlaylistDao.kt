package app.thebox.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import app.thebox.data.db.entity.PlaylistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlist WHERE id = :id")
    fun observe(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlist ORDER BY sort_index, id")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist ORDER BY sort_index, id")
    suspend fun all(): List<PlaylistEntity>

    @Query("SELECT COALESCE(MAX(sort_index), -1) FROM playlist")
    suspend fun maxSortIndex(): Int

    @Query("UPDATE playlist SET needs_reauth = :value WHERE id = :id")
    suspend fun setNeedsReauth(id: Long, value: Boolean)

    @Query("UPDATE playlist SET needs_reauth = 1")
    suspend fun setNeedsReauthAll()

    @Query("UPDATE playlist SET last_refresh_at = :at, last_status = :status WHERE id = :id")
    suspend fun setRefreshStatus(id: Long, at: Long, status: String)

    @Query("UPDATE playlist SET header_json = :headerJson WHERE id = :id")
    suspend fun setHeader(id: Long, headerJson: String?)

    @Query("UPDATE playlist SET server_zone = :zone, server_offset_sec = :offsetSec, clock_skew_sec = :skewSec WHERE id = :id")
    suspend fun setServerTime(id: Long, zone: String?, offsetSec: Int?, skewSec: Long)

    @Query("UPDATE playlist SET active_base_index = :index WHERE id = :id")
    suspend fun setActiveBaseIndex(id: Long, index: Int)

    @Query("UPDATE playlist SET credential_ref = :ref WHERE id = :id")
    suspend fun setCredentialRef(id: Long, ref: String)
}
