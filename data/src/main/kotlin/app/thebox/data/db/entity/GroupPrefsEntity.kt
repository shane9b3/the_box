package app.thebox.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "group_prefs",
    primaryKeys = ["playlist_id", "kind", "group_key"],
    foreignKeys = [ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlist_id"], onDelete = ForeignKey.CASCADE)],
)
data class GroupPrefsEntity(
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    val kind: Int,
    @ColumnInfo(name = "group_key") val groupKey: String,
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
    /** null = auto (channel_group.is_adult). */
    val locked: Boolean? = null,
    @ColumnInfo(name = "custom_sort_index") val customSortIndex: Int? = null,
    @ColumnInfo(name = "custom_name") val customName: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
