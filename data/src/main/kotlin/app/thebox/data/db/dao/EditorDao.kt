package app.thebox.data.db.dao

import androidx.room.Dao
import androidx.room.Query

/**
 * The channel editor's column-targeted writes (docs/design/m6-channel-editor.md §3.5). Room verifies each statement at
 * compile time; `ChannelEditorRepositoryImpl` runs the same SQL inside its one write transaction through `forEachBound`
 * / `exec`, binding the named parameters by **first appearance** (SQLite numbers `:name` parameters 1…n in the order
 * they first occur; each constant's doc gives that order).
 *
 * Hide/show of an `AllOf` target is one `INSERT … SELECT … ON CONFLICT DO UPDATE` built in `EditorQueries.hideAllSql`
 * (its SELECT always has a `WHERE`, which SQLite's upsert-after-join parsing needs).
 */
@Dao
interface EditorDao {
    @Query(UPSERT_SORT_INDEX)
    suspend fun upsertSortIndex(playlistId: Long, stableKey: String, now: Long, index: Int?)

    @Query(RESET_CHANNEL)
    suspend fun resetChannel(now: Long, playlistId: Long, stableKey: String)

    @Query(CLEAR_CHANNEL_ORDER)
    suspend fun clearChannelOrder(now: Long, playlistId: Long, groupKey: String): Int

    @Query(CLEAR_GROUP_ORDER)
    suspend fun clearGroupOrder(now: Long, playlistId: Long): Int

    /** The default Favorites list (`FavouritesRepository.defaultList()`: the first by sort index, then id), or null. */
    @Query(DEFAULT_LIST)
    suspend fun defaultListId(): Long?

    companion object {
        /** Binds: 1 playlistId, 2 stableKey, 3 now, 4 index. */
        const val UPSERT_SORT_INDEX = """
        INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, custom_sort_index)
        VALUES (:playlistId, :stableKey, 0, 0, :now, :index)
        ON CONFLICT(playlist_id, stable_key) DO UPDATE SET custom_sort_index = :index, updated_at = :now"""

        /**
         * Channel Reset (§3.5 "Reset scope"): name, number, hidden, lock and the group move. A moved channel's
         * `custom_sort_index` belonged to the target group, so it is cleared with the move (rule b); an unmoved channel
         * keeps its order (rule c). Favorites, logo, guide shift, playback prefs, history and `rule_hidden` stay.
         * Binds: 1 now, 2 playlistId, 3 stableKey.
         */
        const val RESET_CHANNEL = """
        UPDATE channel_prefs SET updated_at = :now, custom_name = NULL, custom_number = NULL, hidden = 0, locked = NULL,
          custom_sort_index = CASE WHEN custom_group_key IS NOT NULL THEN NULL ELSE custom_sort_index END,
          custom_group_key = NULL
        WHERE playlist_id = :playlistId AND stable_key = :stableKey"""

        /**
         * Sort › Provider order for one group: clears `custom_sort_index` of every channel whose **effective** group is
         * [groupKey] (hidden, rule-hidden, moved-in and missing ones included). Binds: 1 now, 2 playlistId, 3 groupKey.
         */
        const val CLEAR_CHANNEL_ORDER = """
        UPDATE channel_prefs SET updated_at = :now, custom_sort_index = NULL
        WHERE playlist_id = :playlistId AND custom_sort_index IS NOT NULL AND stable_key IN (
          SELECT c.stable_key FROM channel c """ + ChannelDao.EFFECTIVE_GROUP_JOINS + """
          WHERE c.playlist_id = :playlistId AND g.kind = 0 AND g.group_key = :groupKey)"""

        /** Sort › Provider order for the groups. Binds: 1 now, 2 playlistId. */
        const val CLEAR_GROUP_ORDER = """
        UPDATE group_prefs SET updated_at = :now, custom_sort_index = NULL
        WHERE playlist_id = :playlistId AND kind = 0 AND custom_sort_index IS NOT NULL"""

        const val DEFAULT_LIST = "SELECT id FROM favourite_list ORDER BY sort_index, id LIMIT 1"
    }
}
