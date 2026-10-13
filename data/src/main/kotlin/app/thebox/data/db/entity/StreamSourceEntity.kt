package app.thebox.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** v1.1 multi-source failover. Ships empty in v1. */
@Entity(
    tableName = "stream_source",
    primaryKeys = ["playlist_id", "stable_key", "priority"],
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlist_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["source_playlist_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["source_playlist_id", "source_stable_key"], name = "index_stream_source_source")],
)
data class StreamSourceEntity(
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "stable_key") val stableKey: String,
    /** 1.. (0 is the channel's own stream, implicit). */
    val priority: Int,
    @ColumnInfo(name = "source_playlist_id") val sourcePlaylistId: Long,
    @ColumnInfo(name = "source_stable_key") val sourceStableKey: String,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
