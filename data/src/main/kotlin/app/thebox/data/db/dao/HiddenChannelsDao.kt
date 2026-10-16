package app.thebox.data.db.dao

import androidx.room.Dao
import androidx.room.Query

/**
 * The "Hidden channels" page (docs/design/m5-setup-settings.md §3.7.2): channels hidden by the user or by a rule, paged
 * (`PagedList.PAGE_SIZE`). Commit-0 signatures with placeholder queries; WP-D owns the queries from here on.
 */
@Dao
interface HiddenChannelsDao {
    @Query(
        "SELECT COUNT(*) FROM channel c LEFT JOIN channel_prefs cp ON cp.playlist_id = c.playlist_id AND cp.stable_key = c.stable_key " +
            "WHERE c.playlist_id = :playlistId AND (COALESCE(cp.hidden, 0) = 1 OR c.rule_hidden = 1)",
    )
    suspend fun count(playlistId: Long): Int

    /** Stable keys of one page, in name order. */
    @Query(
        "SELECT c.stable_key FROM channel c LEFT JOIN channel_prefs cp ON cp.playlist_id = c.playlist_id AND cp.stable_key = c.stable_key " +
            "WHERE c.playlist_id = :playlistId AND (COALESCE(cp.hidden, 0) = 1 OR c.rule_hidden = 1) " +
            "ORDER BY c.norm_name LIMIT :limit OFFSET :offset",
    )
    suspend fun page(playlistId: Long, offset: Int, limit: Int): List<String>
}
