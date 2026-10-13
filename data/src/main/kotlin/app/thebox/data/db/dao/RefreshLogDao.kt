package app.thebox.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import app.thebox.data.db.entity.RefreshLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RefreshLogDao {
    @Insert
    suspend fun insertRow(row: RefreshLogEntity): Long

    /** Keeps the newest [keep] rows of one (target_type, target_id). */
    @Query(
        """
        DELETE FROM refresh_log WHERE target_type = :targetType AND target_id IS :targetId AND id NOT IN (
            SELECT id FROM refresh_log WHERE target_type = :targetType AND target_id IS :targetId
            ORDER BY started_at DESC, id DESC LIMIT :keep)""",
    )
    suspend fun trim(targetType: String, targetId: Long?, keep: Int)

    /** Insert + retention trim in one transaction (§2.1.2). */
    @Transaction
    suspend fun insert(row: RefreshLogEntity): Long {
        val id = insertRow(row)
        trim(row.targetType, row.targetId, RefreshLogEntity.KEEP_PER_TARGET)
        return id
    }

    @Query("SELECT * FROM refresh_log WHERE target_type = :targetType AND target_id IS :targetId ORDER BY started_at DESC, id DESC LIMIT :limit")
    suspend fun latest(targetType: String, targetId: Long?, limit: Int = 1): List<RefreshLogEntity>

    @Query("SELECT * FROM refresh_log WHERE target_type = :targetType AND target_id IS :targetId ORDER BY started_at DESC, id DESC")
    fun observe(targetType: String, targetId: Long?): Flow<List<RefreshLogEntity>>

    /** S15: failures stay visible until acknowledged. */
    @Query("SELECT * FROM refresh_log WHERE outcome = 'FAILED' AND acknowledged = 0 ORDER BY started_at DESC")
    fun observeUnacknowledgedFailures(): Flow<List<RefreshLogEntity>>

    @Query("UPDATE refresh_log SET acknowledged = 1 WHERE id = :id")
    suspend fun acknowledge(id: Long)

    @Query("UPDATE refresh_log SET acknowledged = 1 WHERE outcome = 'FAILED'")
    suspend fun acknowledgeAll()

    @Query("SELECT COUNT(*) FROM refresh_log WHERE target_type = :targetType AND target_id IS :targetId")
    suspend fun count(targetType: String, targetId: Long?): Int
}
