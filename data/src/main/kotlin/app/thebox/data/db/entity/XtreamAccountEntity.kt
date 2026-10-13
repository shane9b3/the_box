package app.thebox.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** `xtream_account`: the account card (S12). Also stored for Xtream-shaped M3U playlists after the B1 login. */
@Entity(
    tableName = "xtream_account",
    foreignKeys = [ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlist_id"], onDelete = ForeignKey.CASCADE)],
)
data class XtreamAccountEntity(
    @PrimaryKey @ColumnInfo(name = "playlist_id") val playlistId: Long,
    /** XtreamAccountInfo JSON (no credentials). */
    @ColumnInfo(name = "info_json") val infoJson: String,
    val status: String,
    /** Epoch ms; null = unlimited. */
    @ColumnInfo(name = "expires_at") val expiresAt: Long?,
    @ColumnInfo(name = "max_connections") val maxConnections: Int?,
    @ColumnInfo(name = "active_connections") val activeConnections: Int?,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)
