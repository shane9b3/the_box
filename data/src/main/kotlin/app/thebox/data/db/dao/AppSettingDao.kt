package app.thebox.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.thebox.data.db.entity.AppSettingEntity

/**
 * `app_setting` (docs/design/m5-setup-settings.md §0.4, §2.2, §4.2). Commit-0 signatures with minimal queries; WP-A owns
 * the queries from here on (the store's load, coalesced writes and the restore's in-transaction replace).
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
}
