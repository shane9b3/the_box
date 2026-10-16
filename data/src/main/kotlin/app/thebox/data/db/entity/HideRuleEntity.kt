package app.thebox.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v4 (docs/design/m5-setup-settings.md §3.7.3, §4.2): a per-playlist hide rule (user data, backed up). Its results are
 * the derived `channel.rule_hidden` / `channel_group.rule_hidden` flags (WP-D's `rule_hidden` pass); a rule change
 * bumps `playlist.hide_rules_ver`.
 */
@Entity(
    tableName = "hide_rule",
    foreignKeys = [ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlist_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["playlist_id"], name = "index_hide_rule_playlist")],
)
data class HideRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    /** What the pattern matches (channel name, group name, …); WP-D defines the values. */
    val target: Int,
    val pattern: String,
    @ColumnInfo(name = "is_regex") val isRegex: Boolean,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    /** Why the rule was switched off automatically (regex over RULE_REGEX_BUDGET_MS); null when enabled by the user. */
    @ColumnInfo(name = "disabled_reason") val disabledReason: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
