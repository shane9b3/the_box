package app.thebox.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Matcher cache (derived, may be wiped and recomputed). At most one AUTO row per (channel, source), up to
 * MatchSettings.maxSuggestions SUGGESTED rows.
 */
@Entity(
    tableName = "epg_match",
    primaryKeys = ["playlist_id", "stable_key", "source_id", "xmltv_id"],
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlist_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = EpgSourceEntity::class, parentColumns = ["id"], childColumns = ["source_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["playlist_id", "status"], name = "index_epg_match_status"),
        Index(value = ["source_id"], name = "index_epg_match_source"),
    ],
)
data class EpgMatchEntity(
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "stable_key") val stableKey: String,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    @ColumnInfo(name = "xmltv_id") val xmltvId: String,
    /** 0 AUTO, 1 SUGGESTED */
    val status: Int,
    /** MatchMethod name. */
    val method: String,
    val score: Float,
    /** programme_count > 0 in the current generation. */
    @ColumnInfo(name = "has_data", defaultValue = "0") val hasData: Boolean = false,
    /** Hash64(tvg_id, tvg_name, name, settings.level, MATCHER_VERSION). */
    @ColumnInfo(name = "inputs_hash") val inputsHash: Long,
    @ColumnInfo(name = "matched_at") val matchedAt: Long,
) {
    companion object {
        const val STATUS_AUTO = 0
        const val STATUS_SUGGESTED = 1
    }
}
