package app.thebox.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
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
          AND (:unlocked = 1 OR COALESCE(gp.locked, g.is_adult, 0) = 0)
        ORDER BY gp.custom_sort_index IS NULL, gp.custom_sort_index, g.sort_index""",
    )
    fun groups(playlistId: Long, kind: Int, unlocked: Boolean): Flow<List<GroupRow>>

    @Query("SELECT * FROM channel_group WHERE playlist_id = :playlistId ORDER BY kind, sort_index")
    suspend fun all(playlistId: Long): List<ChannelGroupEntity>

    @Query("SELECT * FROM group_prefs WHERE playlist_id = :playlistId AND kind = :kind AND group_key = :groupKey")
    suspend fun prefs(playlistId: Long, kind: Int, groupKey: String): GroupPrefsEntity?

    @Upsert
    suspend fun upsertPrefs(prefs: GroupPrefsEntity)

    @Query("DELETE FROM group_prefs WHERE playlist_id = :playlistId AND kind = :kind AND group_key = :groupKey")
    suspend fun deletePrefs(playlistId: Long, kind: Int, groupKey: String)
}
