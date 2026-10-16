package app.thebox.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * v4 (docs/design/m5-setup-settings.md §0.4, §4.2): one JSON document per settings domain (`SettingsStore`,
 * `Domains`). Written only by WP-A's store; restore replaces rows inside its library.db transaction (§3.10.4).
 */
@Entity(tableName = "app_setting")
data class AppSettingEntity(
    @PrimaryKey val domain: String,
    /** `Json { ignoreUnknownKeys = true; encodeDefaults = false }` of the domain's document. */
    val json: String,
    /** `SettingsDomain.schema` the document was written with (old documents go through `SettingsDomain.migrate`). */
    @ColumnInfo(name = "schema_ver") val schemaVer: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
