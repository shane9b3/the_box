package app.thebox.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import app.thebox.data.db.entity.ChannelGroupEntity

/** One group of the group manager with the user's decisions and its channel count. */
data class GroupAdminEntry(
    val kind: Int,
    @ColumnInfo(name = "group_key") val groupKey: String,
    val name: String,
    @ColumnInfo(name = "is_adult") val isAdult: Boolean,
    @ColumnInfo(name = "rule_hidden") val ruleHidden: Boolean,
    val hidden: Boolean,
    /** null = automatic. */
    val locked: Boolean?,
    val channels: Int,
)

/**
 * The group manager (docs/design/m5-setup-settings.md §3.7.1): every group of a playlist with its user decisions
 * (hidden, locked, custom name and order), including hidden and locked ones, in the user's order (custom sort first,
 * then the provider's). WP-D's queries.
 */
@Dao
interface GroupAdminDao {
    @Query("SELECT * FROM channel_group WHERE playlist_id = :playlistId AND kind = :kind ORDER BY sort_index")
    suspend fun groups(playlistId: Long, kind: Int): List<ChannelGroupEntity>

    @Query("SELECT COUNT(*) FROM channel_group WHERE playlist_id = :playlistId AND kind = :kind AND missing_since IS NULL")
    suspend fun count(playlistId: Long, kind: Int): Int

    @Query(
        "SELECT g.kind, g.group_key, COALESCE(gp.custom_name, g.name) AS name, g.is_adult, g.rule_hidden, " +
            "COALESCE(gp.hidden, 0) AS hidden, gp.locked AS locked, " +
            "(SELECT COUNT(*) FROM channel c WHERE c.group_id = g.id AND c.missing_since IS NULL) AS channels " +
            "FROM channel_group g LEFT JOIN group_prefs gp ON gp.playlist_id = g.playlist_id AND gp.kind = g.kind AND gp.group_key = g.group_key " +
            "WHERE g.playlist_id = :playlistId AND g.kind = :kind AND g.missing_since IS NULL " +
            "ORDER BY COALESCE(gp.custom_sort_index, 2147483647), g.sort_index, g.id LIMIT :limit OFFSET :offset",
    )
    suspend fun entries(playlistId: Long, kind: Int, offset: Int, limit: Int): List<GroupAdminEntry>

    /** Group keys in the user's order (move mode rewrites `custom_sort_index` densely from this list). */
    @Query(
        "SELECT g.group_key FROM channel_group g LEFT JOIN group_prefs gp ON gp.playlist_id = g.playlist_id AND gp.kind = g.kind AND gp.group_key = g.group_key " +
            "WHERE g.playlist_id = :playlistId AND g.kind = :kind AND g.missing_since IS NULL " +
            "ORDER BY COALESCE(gp.custom_sort_index, 2147483647), g.sort_index, g.id",
    )
    suspend fun orderedKeys(playlistId: Long, kind: Int): List<String>

    @Query("SELECT COUNT(*) FROM group_prefs WHERE playlist_id = :playlistId AND hidden = 1")
    suspend fun hiddenCount(playlistId: Long): Int

    /** Groups that end up locked: explicit locks, plus locked groups (adult-flagged) left automatic. */
    @Query(
        "SELECT COUNT(*) FROM channel_group g LEFT JOIN group_prefs gp ON gp.playlist_id = g.playlist_id AND gp.kind = g.kind AND gp.group_key = g.group_key " +
            "WHERE g.playlist_id = :playlistId AND g.missing_since IS NULL AND COALESCE(gp.locked, g.is_adult) = 1",
    )
    suspend fun lockedCount(playlistId: Long): Int

    @Query("UPDATE group_prefs SET hidden = 0, updated_at = :now WHERE playlist_id = :playlistId AND hidden = 1")
    suspend fun showAll(playlistId: Long, now: Long): Int

    @Query(
        "INSERT INTO group_prefs(playlist_id, kind, group_key, hidden, locked, updated_at) VALUES (:playlistId, :kind, :groupKey, 0, :locked, :now) " +
            "ON CONFLICT(playlist_id, kind, group_key) DO UPDATE SET locked = excluded.locked, updated_at = excluded.updated_at",
    )
    suspend fun upsertLocked(playlistId: Long, kind: Int, groupKey: String, locked: Boolean?, now: Long)
}
