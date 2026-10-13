package app.thebox.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "favourite",
    primaryKeys = ["list_id", "playlist_id", "stable_key"],
    foreignKeys = [
        ForeignKey(entity = FavouriteListEntity::class, parentColumns = ["id"], childColumns = ["list_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlist_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["playlist_id", "stable_key"], name = "index_favourite_channel")],
)
data class FavouriteEntity(
    @ColumnInfo(name = "list_id") val listId: Long,
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "stable_key") val stableKey: String,
    @ColumnInfo(name = "sort_index") val sortIndex: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)
