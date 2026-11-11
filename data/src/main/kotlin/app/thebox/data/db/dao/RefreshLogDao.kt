package app.thebox.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import app.thebox.core.refresh.RefreshOutcome
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

    /**
     * (owner finding 2026-11-02) Acknowledges the failures of one (target_type, target_id) logged before row
     * [beforeId]. Ordered by id, not started_at: ids follow insertion order, while started_at follows the device
     * clock, which can jump back (NTP correction after boot) and make a resolved failure look newer than its success.
     */
    @Query(
        """
        UPDATE refresh_log SET acknowledged = 1
        WHERE target_type = :targetType AND target_id IS :targetId AND outcome = 'FAILED' AND id < :beforeId""",
    )
    suspend fun acknowledgeFailuresBefore(targetType: String, targetId: Long?, beforeId: Long)

    /**
     * Insert + retention trim in one transaction (§2.1.2). A SUCCESS resolves the earlier failures of the same target
     * (owner finding 2026-11-02), and so does NOT_MODIFIED: a playlist answering 304 was reached and is current
     * (RefreshRepository records it as last_success_at). A guide build's success also resolves earlier patch
     * failures: a full rebuild supersedes any patch, and `afterBuild` re-requests a patch that is still needed (the
     * Settings guide row shows only build failures, so a stuck patch failure had no Dismiss). A patch success does not
     * resolve a build failure. A failure with no later success still waits for [acknowledge] / [acknowledgeAll].
     */
    @Transaction
    suspend fun insert(row: RefreshLogEntity): Long {
        val id = insertRow(row)
        if (row.outcome == RefreshOutcome.SUCCESS.name || row.outcome == RefreshOutcome.NOT_MODIFIED.name) {
            acknowledgeFailuresBefore(row.targetType, row.targetId, id)
            if (row.targetType == RefreshLogEntity.TARGET_EPG_BUILD) acknowledgeFailuresBefore(RefreshLogEntity.TARGET_EPG_PATCH, null, id)
        }
        trim(row.targetType, row.targetId, RefreshLogEntity.KEEP_PER_TARGET)
        return id
    }

    @Query("SELECT * FROM refresh_log WHERE target_type = :targetType AND target_id IS :targetId ORDER BY started_at DESC, id DESC LIMIT :limit")
    suspend fun latest(targetType: String, targetId: Long?, limit: Int = 1): List<RefreshLogEntity>

    /**
     * (review 2026-11-10, S2) `finished_at` of the newest row (by id, so a clock jump cannot reorder them) of one target
     * that reached its source and found it current: SUCCESS or NOT_MODIFIED. A FAILED row never counts. Null when none.
     */
    @Query(
        """
        SELECT finished_at FROM refresh_log WHERE target_type = :targetType AND target_id IS :targetId
        AND outcome IN ('SUCCESS', 'NOT_MODIFIED') ORDER BY id DESC LIMIT 1""",
    )
    suspend fun lastCurrentAt(targetType: String, targetId: Long?): Long?

    @Query("SELECT * FROM refresh_log WHERE target_type = :targetType AND target_id IS :targetId ORDER BY started_at DESC, id DESC")
    fun observe(targetType: String, targetId: Long?): Flow<List<RefreshLogEntity>>

    /** S15: failures stay visible until acknowledged or until a later run of the same target succeeds. */
    @Query("SELECT * FROM refresh_log WHERE outcome = 'FAILED' AND acknowledged = 0 ORDER BY started_at DESC")
    fun observeUnacknowledgedFailures(): Flow<List<RefreshLogEntity>>

    @Query("UPDATE refresh_log SET acknowledged = 1 WHERE id = :id")
    suspend fun acknowledge(id: Long)

    @Query("UPDATE refresh_log SET acknowledged = 1 WHERE outcome = 'FAILED'")
    suspend fun acknowledgeAll()

    @Query("SELECT COUNT(*) FROM refresh_log WHERE target_type = :targetType AND target_id IS :targetId")
    suspend fun count(targetType: String, targetId: Long?): Int
}
