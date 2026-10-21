package app.thebox.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query

/** One row of the Hidden channels page. */
data class HiddenChannelEntry(
    @ColumnInfo(name = "stable_key") val stableKey: String,
    val name: String,
    @ColumnInfo(name = "group_name") val groupName: String?,
    /** Hidden by the user (`channel_prefs.hidden`), as opposed to only by a rule. */
    @ColumnInfo(name = "by_user") val byUser: Boolean,
    @ColumnInfo(name = "rule_hidden") val ruleHidden: Boolean,
)

/**
 * The "Hidden channels" page (docs/design/m5-setup-settings.md §3.7.2): channels hidden by the user or by a channel-name
 * rule, paged (`PagedList.PAGE_SIZE`), in name order. Missing channels (awaiting purge) are left out. WP-D's queries.
 */
@Dao
interface HiddenChannelsDao {
    @Query(
        "SELECT COUNT(*) FROM channel c LEFT JOIN channel_prefs cp ON cp.playlist_id = c.playlist_id AND cp.stable_key = c.stable_key " +
            "WHERE c.playlist_id = :playlistId AND c.missing_since IS NULL AND (COALESCE(cp.hidden, 0) = 1 OR c.rule_hidden = 1)",
    )
    suspend fun count(playlistId: Long): Int

    /** Stable keys of one page, in name order. */
    @Query(
        "SELECT c.stable_key FROM channel c LEFT JOIN channel_prefs cp ON cp.playlist_id = c.playlist_id AND cp.stable_key = c.stable_key " +
            "WHERE c.playlist_id = :playlistId AND c.missing_since IS NULL AND (COALESCE(cp.hidden, 0) = 1 OR c.rule_hidden = 1) " +
            "ORDER BY c.norm_name, c.id LIMIT :limit OFFSET :offset",
    )
    suspend fun page(playlistId: Long, offset: Int, limit: Int): List<String>

    /** v5 (docs/design/m6-channel-editor.md §3.6 row 10): the group a channel is shown in (its effective group). */
    @Query(
        "SELECT c.stable_key, COALESCE(cp.custom_name, c.display_name, c.name) AS name, g.name AS group_name, " +
            "COALESCE(cp.hidden, 0) AS by_user, c.rule_hidden FROM channel c " + ChannelDao.EFFECTIVE_GROUP_JOINS +
            "WHERE c.playlist_id = :playlistId AND c.missing_since IS NULL AND (COALESCE(cp.hidden, 0) = 1 OR c.rule_hidden = 1) " +
            "ORDER BY c.norm_name, c.id LIMIT :limit OFFSET :offset",
    )
    suspend fun entries(playlistId: Long, offset: Int, limit: Int): List<HiddenChannelEntry>

    @Query("UPDATE channel_prefs SET hidden = 0, updated_at = :now WHERE playlist_id = :playlistId AND hidden = 1")
    suspend fun unhideAll(playlistId: Long, now: Long): Int
}
