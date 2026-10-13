package app.thebox.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Refresh-owned. Identity = (playlist_id, kind, group_key). RADIO shares LIVE groups (kind stored as 0). */
@Entity(
    tableName = "channel_group",
    foreignKeys = [ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlist_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["playlist_id", "kind", "group_key"], unique = true, name = "index_channel_group_identity")],
)
data class ChannelGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    val kind: Int,
    /** "xcat:<id>" | "g:<forGroup(name)>" | "g:" */
    @ColumnInfo(name = "group_key") val groupKey: String,
    val name: String,
    @ColumnInfo(name = "sort_index") val sortIndex: Int,
    @ColumnInfo(name = "is_adult", defaultValue = "0") val isAdult: Boolean = false,
    @ColumnInfo(name = "missing_since") val missingSince: Long? = null,
)
