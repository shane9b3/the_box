package app.thebox.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.thebox.data.db.entity.HideRuleEntity
import kotlinx.coroutines.flow.Flow

/**
 * `hide_rule` (docs/design/m5-setup-settings.md §3.7.3, §4.2). Commit-0 signatures with placeholder queries; WP-D owns
 * the queries from here on (and bumps `playlist.hide_rules_ver` with every change).
 */
@Dao
interface HideRuleDao {
    @Query("SELECT * FROM hide_rule WHERE playlist_id = :playlistId ORDER BY id")
    fun rules(playlistId: Long): Flow<List<HideRuleEntity>>

    @Query("SELECT * FROM hide_rule WHERE playlist_id = :playlistId AND enabled = 1 ORDER BY id")
    suspend fun enabledRules(playlistId: Long): List<HideRuleEntity>

    @Insert
    suspend fun insert(rule: HideRuleEntity): Long

    @Query("UPDATE hide_rule SET enabled = :enabled, disabled_reason = :reason WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, reason: String?)

    @Query("DELETE FROM hide_rule WHERE id = :id")
    suspend fun delete(id: Long)
}
