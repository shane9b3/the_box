package app.thebox.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable programme reference: (playlist_id, stable_key, raw start_min, Hash64(title)), never an epg.db id. */
@Entity(
    tableName = "reminder",
    foreignKeys = [ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlist_id"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["playlist_id", "stable_key", "start_min"], unique = true, name = "index_reminder_identity"),
        Index(value = ["state", "start_min"], name = "index_reminder_due"),
    ],
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "stable_key") val stableKey: String,
    /** RAW (unshifted) programme start, epoch minutes. */
    @ColumnInfo(name = "start_min") val startMin: Int,
    @ColumnInfo(name = "end_min") val endMin: Int,
    @ColumnInfo(name = "title_hash") val titleHash: Long,
    val title: String,
    @ColumnInfo(name = "channel_name") val channelName: String,
    @ColumnInfo(name = "lead_sec", defaultValue = "60") val leadSec: Int = 60,
    /** 0 PENDING, 1 FIRED, 2 DISMISSED, 3 ORPHANED */
    @ColumnInfo(defaultValue = "0") val state: Int = STATE_PENDING,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    companion object {
        const val STATE_PENDING = 0
        const val STATE_FIRED = 1
        const val STATE_DISMISSED = 2
        const val STATE_ORPHANED = 3
    }
}
