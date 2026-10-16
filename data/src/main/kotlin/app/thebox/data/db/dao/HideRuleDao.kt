package app.thebox.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.thebox.data.db.entity.HideRuleEntity
import kotlinx.coroutines.flow.Flow

/**
 * `hide_rule` (docs/design/m5-setup-settings.md §3.7.3, §4.2). WP-D's queries. Every change also bumps
 * `playlist.hide_rules_ver` ([bumpVersion]); the `rule_hidden` pass (`data/.../rules/RuleHiddenPass`) recomputes the
 * derived flags.
 */
@Dao
interface HideRuleDao {
    @Query("SELECT * FROM hide_rule WHERE playlist_id = :playlistId ORDER BY id")
    fun rules(playlistId: Long): Flow<List<HideRuleEntity>>

    @Query("SELECT * FROM hide_rule WHERE playlist_id = :playlistId ORDER BY id")
    suspend fun all(playlistId: Long): List<HideRuleEntity>

    @Query("SELECT * FROM hide_rule WHERE playlist_id = :playlistId AND enabled = 1 ORDER BY id")
    suspend fun enabledRules(playlistId: Long): List<HideRuleEntity>

    @Query("SELECT * FROM hide_rule WHERE id = :id")
    suspend fun get(id: Long): HideRuleEntity?

    @Query("SELECT COUNT(*) FROM hide_rule WHERE playlist_id = :playlistId")
    suspend fun count(playlistId: Long): Int

    @Query("SELECT COUNT(*) FROM hide_rule WHERE playlist_id = :playlistId AND target = :target AND pattern = :pattern AND is_regex = :isRegex")
    suspend fun countIdentical(playlistId: Long, target: Int, pattern: String, isRegex: Boolean): Int

    @Insert
    suspend fun insert(rule: HideRuleEntity): Long

    @Query("UPDATE hide_rule SET enabled = :enabled, disabled_reason = :reason WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, reason: String?)

    @Query("DELETE FROM hide_rule WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE playlist SET hide_rules_ver = hide_rules_ver + 1 WHERE id = :playlistId")
    suspend fun bumpVersion(playlistId: Long)
}
