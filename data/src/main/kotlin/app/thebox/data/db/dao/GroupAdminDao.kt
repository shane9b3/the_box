package app.thebox.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import app.thebox.data.db.entity.ChannelGroupEntity

/**
 * The group manager (docs/design/m5-setup-settings.md §3.7.1): every group of a playlist with its user decisions
 * (hidden, locked, custom name and order), including hidden and locked ones. Commit-0 signatures with placeholder
 * queries; WP-D owns the queries from here on.
 */
@Dao
interface GroupAdminDao {
    @Query("SELECT * FROM channel_group WHERE playlist_id = :playlistId AND kind = :kind ORDER BY sort_index")
    suspend fun groups(playlistId: Long, kind: Int): List<ChannelGroupEntity>

    @Query("SELECT COUNT(*) FROM channel_group WHERE playlist_id = :playlistId AND kind = :kind")
    suspend fun count(playlistId: Long, kind: Int): Int
}
