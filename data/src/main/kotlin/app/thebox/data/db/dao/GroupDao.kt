package app.thebox.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.thebox.data.db.entity.ChannelGroupEntity
import app.thebox.data.db.entity.GroupPrefsEntity
import kotlinx.coroutines.flow.Flow

data class GroupRow(
    @Embedded val group: ChannelGroupEntity,
    @ColumnInfo(name = "custom_name") val customName: String?,
    @ColumnInfo(name = "custom_sort_index") val customSortIndex: Int?,
    @ColumnInfo(name = "channel_count") val channelCount: Int,
)

/** (M5, §4.3) A Groups-panel group with its lock state for the caller's access. */
data class PanelGroupRow(
    @Embedded val group: ChannelGroupEntity,
    @ColumnInfo(name = "custom_name") val customName: String?,
    @ColumnInfo(name = "custom_sort_index") val customSortIndex: Int?,
    @ColumnInfo(name = "channel_count") val channelCount: Int,
    @ColumnInfo(name = "locked") val locked: Boolean,
)

/** `channel_group` (refresh-owned, read here) and `group_prefs` (user data). */
@Dao
interface GroupDao {
    /** Visible groups of a playlist and kind, with the parental predicate on the group level. */
    @Query(
        """
        SELECT g.*, gp.custom_name, gp.custom_sort_index,
               (SELECT COUNT(*) FROM channel c WHERE c.group_id = g.id AND c.missing_since IS NULL) AS channel_count
        FROM channel_group g
        LEFT JOIN group_prefs gp ON gp.playlist_id = g.playlist_id AND gp.kind = g.kind AND gp.group_key = g.group_key
        WHERE g.playlist_id = :playlistId AND g.kind = :kind AND g.missing_since IS NULL
          AND COALESCE(gp.hidden, 0) = 0
          AND g.rule_hidden = 0
          AND (:unlocked = 1 OR COALESCE(gp.locked, g.is_adult, 0) = 0)
        ORDER BY gp.custom_sort_index IS NULL, gp.custom_sort_index, g.sort_index""",
    )
    fun groups(playlistId: Long, kind: Int, unlocked: Boolean): Flow<List<GroupRow>>

    /**
     * (M5, docs/design/m5-setup-settings.md §4.3, B3, S10) The Groups panel and the player's group column: every group
     * with `hidden = 0 AND rule_hidden = 0` (locked ones included, "Show with lock"), with `locked` =
     * `(:access != 0) AND COALESCE(gp.locked, CASE WHEN :access IN (1, 3) THEN g.is_adult ELSE 0 END, 0) = 1` and its
     * full channel count. `:access` = `ParentalAccess.ordinal`.
     */
    @Query(
        """
        SELECT g.*, gp.custom_name, gp.custom_sort_index,
               (SELECT COUNT(*) FROM channel c WHERE c.group_id = g.id AND c.missing_since IS NULL) AS channel_count,
               CASE WHEN :access != 0 AND COALESCE(gp.locked, CASE WHEN :access IN (1, 3) THEN g.is_adult ELSE 0 END, 0) = 1
                    THEN 1 ELSE 0 END AS locked
        FROM channel_group g
        LEFT JOIN group_prefs gp ON gp.playlist_id = g.playlist_id AND gp.kind = g.kind AND gp.group_key = g.group_key
        WHERE g.playlist_id = :playlistId AND g.kind = :kind AND g.missing_since IS NULL
          AND COALESCE(gp.hidden, 0) = 0
          AND g.rule_hidden = 0
        ORDER BY gp.custom_sort_index IS NULL, gp.custom_sort_index, g.sort_index""",
    )
    fun panelGroups(playlistId: Long, kind: Int, access: Int): Flow<List<PanelGroupRow>>

    /** (M5, §3.9.1) Column-targeted lock write for the group menu (Lock / Unlock); null clears the user's decision. */
    @Query(
        """
        INSERT INTO group_prefs(playlist_id, kind, group_key, hidden, locked, updated_at)
        VALUES (:playlistId, :kind, :groupKey, 0, :locked, :now)
        ON CONFLICT(playlist_id, kind, group_key) DO UPDATE SET locked = :locked, updated_at = :now""",
    )
    suspend fun upsertGroupLocked(playlistId: Long, kind: Int, groupKey: String, locked: Boolean, now: Long)

    @Query("SELECT * FROM channel_group WHERE playlist_id = :playlistId ORDER BY kind, sort_index")
    suspend fun all(playlistId: Long): List<ChannelGroupEntity>

    @Query("SELECT * FROM group_prefs WHERE playlist_id = :playlistId AND kind = :kind AND group_key = :groupKey")
    suspend fun prefs(playlistId: Long, kind: Int, groupKey: String): GroupPrefsEntity?

    @Upsert
    suspend fun upsertPrefs(prefs: GroupPrefsEntity)

    @Query("DELETE FROM group_prefs WHERE playlist_id = :playlistId AND kind = :kind AND group_key = :groupKey")
    suspend fun deletePrefs(playlistId: Long, kind: Int, groupKey: String)

    // ---- M3 (docs/design/m3-guide.md §2.9.3): column-targeted writes for the Groups panel menu.

    @Query(
        """
        INSERT INTO group_prefs(playlist_id, kind, group_key, hidden, updated_at)
        VALUES (:playlistId, :kind, :groupKey, :hidden, :now)
        ON CONFLICT(playlist_id, kind, group_key) DO UPDATE SET hidden = :hidden, updated_at = :now""",
    )
    suspend fun upsertGroupHidden(playlistId: Long, kind: Int, groupKey: String, hidden: Boolean, now: Long)

    @Query(
        """
        INSERT INTO group_prefs(playlist_id, kind, group_key, hidden, custom_sort_index, updated_at)
        VALUES (:playlistId, :kind, :groupKey, 0, :index, :now)
        ON CONFLICT(playlist_id, kind, group_key) DO UPDATE SET custom_sort_index = :index, updated_at = :now""",
    )
    suspend fun upsertGroupSortIndex(playlistId: Long, kind: Int, groupKey: String, index: Int, now: Long)

    /** Reorder: rewrites a dense `custom_sort_index` (0, 1, …) for [orderedKeys] of one playlist/kind in one transaction. */
    @Transaction
    suspend fun setGroupOrder(playlistId: Long, kind: Int, orderedKeys: List<String>, now: Long) {
        orderedKeys.forEachIndexed { i, key -> upsertGroupSortIndex(playlistId, kind, key, i, now) }
    }
}
