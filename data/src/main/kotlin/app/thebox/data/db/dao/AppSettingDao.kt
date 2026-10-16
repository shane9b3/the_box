package app.thebox.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.thebox.data.db.entity.AppSettingEntity
import kotlinx.coroutines.flow.Flow

/**
 * `app_setting` (docs/design/m5-setup-settings.md §0.4, §2.2, §4.2): WP-A's queries for the store's load, coalesced
 * writes and the restore's in-transaction replace, plus the one read the `data` domain's Auto past days needs (S12).
 */
@Dao
interface AppSettingDao {
    /** The store's one startup read (§0.5): ≤ 10 rows. */
    @Query("SELECT * FROM app_setting")
    suspend fun all(): List<AppSettingEntity>

    @Query("SELECT * FROM app_setting WHERE domain = :domain")
    suspend fun get(domain: String): AppSettingEntity?

    @Upsert
    suspend fun upsert(row: AppSettingEntity)

    @Query("DELETE FROM app_setting WHERE domain = :domain")
    suspend fun delete(domain: String)

    /**
     * (S12) The most catch-up days any channel of an enabled playlist offers (the playlist's override first, then the
     * channel's `catchup-days` / Xtream `tv_archive_duration`), for "Past days: Auto"; null when none has catch-up.
     */
    @Query(
        """
        SELECT MAX(COALESCE(p.catchup_days_override, c.catchup_days, c.tv_archive_days))
        FROM channel c JOIN playlist p ON p.id = c.playlist_id
        WHERE p.enabled = 1 AND (c.tv_archive = 1 OR c.catchup_days IS NOT NULL OR c.catchup_type IS NOT NULL)
        """,
    )
    fun observeMaxCatchupDays(): Flow<Int?>
}
