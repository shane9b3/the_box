package app.thebox.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.thebox.data.db.entity.FavouriteEntity
import app.thebox.data.db.entity.FavouriteListEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavouriteDao {
    @Insert
    suspend fun insertList(list: FavouriteListEntity): Long

    @Query("SELECT * FROM favourite_list ORDER BY sort_index, id")
    fun observeLists(): Flow<List<FavouriteListEntity>>

    @Query("SELECT * FROM favourite_list ORDER BY sort_index, id")
    suspend fun lists(): List<FavouriteListEntity>

    @Query("DELETE FROM favourite_list WHERE id = :listId")
    suspend fun deleteList(listId: Long)

    @Query("UPDATE favourite_list SET name = :name WHERE id = :listId")
    suspend fun renameList(listId: Long, name: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(favourite: FavouriteEntity): Long

    @Query("DELETE FROM favourite WHERE list_id = :listId AND playlist_id = :playlistId AND stable_key = :stableKey")
    suspend fun delete(listId: Long, playlistId: Long, stableKey: String)

    @Query("SELECT COALESCE(MAX(sort_index), -1) FROM favourite WHERE list_id = :listId")
    suspend fun maxSortIndex(listId: Long): Int

    @Query("UPDATE favourite SET sort_index = :sortIndex WHERE list_id = :listId AND playlist_id = :playlistId AND stable_key = :stableKey")
    suspend fun setSortIndex(listId: Long, playlistId: Long, stableKey: String, sortIndex: Int)

    @Query("SELECT * FROM favourite WHERE list_id = :listId ORDER BY sort_index")
    fun observe(listId: Long): Flow<List<FavouriteEntity>>

    @Query("SELECT * FROM favourite WHERE playlist_id = :playlistId AND stable_key = :stableKey")
    suspend fun listsOf(playlistId: Long, stableKey: String): List<FavouriteEntity>

    /** Favourite channels of a list that are present (not missing) and pass the parental predicate (effective group, v5). */
    @Query(
        """
        SELECT c.*, cp.custom_name, cp.custom_number, cp.custom_logo, cp.epg_shift_min AS pref_shift,
               cp.custom_sort_index AS pref_sort, g.name AS group_name, g.group_key AS group_key
        FROM favourite f
        JOIN channel c             ON c.playlist_id = f.playlist_id AND c.stable_key = f.stable_key AND c.missing_since IS NULL
        JOIN playlist p            ON p.id = c.playlist_id AND p.enabled = 1
        """ + ChannelDao.EFFECTIVE_GROUP_JOINS + """
        WHERE f.list_id = :listId
        """ + ChannelDao.PARENTAL + """
        ORDER BY f.sort_index""",
    )
    fun channels(listId: Long, unlocked: Boolean): Flow<List<ChannelRow>>

    @Query("SELECT COUNT(*) FROM favourite WHERE playlist_id = :playlistId")
    suspend fun countForPlaylist(playlistId: Long): Int
}
