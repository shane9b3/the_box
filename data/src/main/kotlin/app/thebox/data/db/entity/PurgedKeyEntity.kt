package app.thebox.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Tombstone for a purged channel key (S8): the clock of the 180-day orphan rule. */
@Entity(
    tableName = "purged_key",
    primaryKeys = ["playlist_id", "stable_key"],
    foreignKeys = [ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlist_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["purged_at"], name = "index_purged_key_age")],
)
data class PurgedKeyEntity(
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "stable_key") val stableKey: String,
    @ColumnInfo(name = "purged_at") val purgedAt: Long,
)
