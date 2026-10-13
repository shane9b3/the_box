package app.thebox.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.thebox.data.db.entity.XtreamAccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface XtreamAccountDao {
    @Upsert
    suspend fun upsert(account: XtreamAccountEntity)

    @Query("SELECT * FROM xtream_account WHERE playlist_id = :playlistId")
    suspend fun get(playlistId: Long): XtreamAccountEntity?

    @Query("SELECT * FROM xtream_account WHERE playlist_id = :playlistId")
    fun observe(playlistId: Long): Flow<XtreamAccountEntity?>
}
