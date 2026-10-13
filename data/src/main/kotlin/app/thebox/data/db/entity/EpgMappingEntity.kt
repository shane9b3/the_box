package app.thebox.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** User override (TiviMate "Channel → EPG"). Both [sourceId] and [xmltvId] null = explicit "no guide data". */
@Entity(
    tableName = "epg_mapping",
    primaryKeys = ["playlist_id", "stable_key"],
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlist_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = EpgSourceEntity::class, parentColumns = ["id"], childColumns = ["source_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["source_id"], name = "index_epg_mapping_source")],
)
data class EpgMappingEntity(
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "stable_key") val stableKey: String,
    @ColumnInfo(name = "source_id") val sourceId: Long?,
    @ColumnInfo(name = "xmltv_id") val xmltvId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
