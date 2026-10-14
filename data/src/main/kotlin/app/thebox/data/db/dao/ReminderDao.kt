package app.thebox.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.thebox.data.db.entity.ReminderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    /** Returns -1 when a reminder for (playlist_id, stable_key, start_min) already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(reminder: ReminderEntity): Long

    @Query("DELETE FROM reminder WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM reminder WHERE playlist_id = :playlistId AND stable_key = :stableKey AND start_min = :startMin")
    suspend fun delete(playlistId: Long, stableKey: String, startMin: Int)

    @Query("UPDATE reminder SET state = :state WHERE id = :id")
    suspend fun setState(id: Long, state: Int)

    @Query("SELECT * FROM reminder WHERE state = 0 AND start_min >= :fromMin ORDER BY start_min")
    fun observePending(fromMin: Int): Flow<List<ReminderEntity>>

    /** Pending reminders whose start (minus lead) is at or before [nowMin]. */
    @Query("SELECT * FROM reminder WHERE state = 0 AND start_min <= :nowMin + (lead_sec / 60) ORDER BY start_min")
    suspend fun due(nowMin: Int): List<ReminderEntity>

    @Query("SELECT * FROM reminder WHERE playlist_id = :playlistId AND stable_key = :stableKey AND start_min = :startMin")
    suspend fun get(playlistId: Long, stableKey: String, startMin: Int): ReminderEntity?

    @Query("SELECT * FROM reminder ORDER BY start_min")
    suspend fun all(): List<ReminderEntity>

    // ---- M3 (docs/design/m3-guide.md §2.9.3; commit 0 declares, WP-B uses)

    /** Every pending reminder, no lower bound: the planner expires old ones. */
    @Query("SELECT * FROM reminder WHERE state = 0 ORDER BY start_min")
    fun observeAllPending(): Flow<List<ReminderEntity>>

    /** Two writers (the card and the planner): only a still-pending row changes. Returns the rows updated (0 or 1). */
    @Query("UPDATE reminder SET state = :state WHERE id = :id AND state = 0")
    suspend fun setStateIfPending(id: Long, state: Int): Int

    /** Before re-setting a reminder (N5): the identity index ignores state, so a fired/dismissed row is removed first. */
    @Query("DELETE FROM reminder WHERE playlist_id = :playlistId AND stable_key = :stableKey AND start_min = :startMin AND state <> 0")
    suspend fun deleteIfNotPending(playlistId: Long, stableKey: String, startMin: Int): Int
}
