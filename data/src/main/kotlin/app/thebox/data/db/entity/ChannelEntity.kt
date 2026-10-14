package app.thebox.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Refresh-owned. Identity = (playlist_id, stable_key); [id] is internal and never referenced by user data.
 * Written by RoomMergeSink through prepared statements; the column list there must follow this entity.
 */
@Entity(
    tableName = "channel",
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlist_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ChannelGroupEntity::class, parentColumns = ["id"], childColumns = ["group_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index(value = ["playlist_id", "stable_key"], unique = true, name = "index_channel_identity"),
        Index(value = ["playlist_id", "kind", "group_id", "sort_index"], name = "index_channel_browse"),
        Index(value = ["playlist_id", "kind", "number"], name = "index_channel_number"),
        Index(value = ["playlist_id", "tvg_id"], name = "index_channel_tvg"),
        Index(value = ["playlist_id", "missing_since"], name = "index_channel_missing"),
        Index(value = ["group_id"], name = "index_channel_group"),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "stable_key") val stableKey: String,
    @ColumnInfo(name = "key_rule") val keyRule: String,
    val kind: Int,
    val name: String,
    @ColumnInfo(name = "norm_name") val normName: String,
    val number: Int? = null,
    @ColumnInfo(name = "sort_index") val sortIndex: Int,
    @ColumnInfo(name = "group_id") val groupId: Long? = null,
    @ColumnInfo(name = "logo_url") val logoUrl: String? = null,
    @ColumnInfo(name = "tvg_id") val tvgId: String? = null,
    @ColumnInfo(name = "tvg_name") val tvgName: String? = null,
    /** Template (§3.2.4): credentials as {{u}}/{{p}}. */
    val url: String,
    @ColumnInfo(name = "headers_json") val headers: Map<String, String>? = null,
    @ColumnInfo(name = "xtream_stream_id") val xtreamStreamId: Long? = null,
    @ColumnInfo(name = "container_ext") val containerExt: String? = null,
    @ColumnInfo(name = "epg_shift_min") val epgShiftMin: Int? = null,
    @ColumnInfo(name = "catchup_type") val catchupType: String? = null,
    /** Templated like [url] (B3). */
    @ColumnInfo(name = "catchup_source") val catchupSource: String? = null,
    @ColumnInfo(name = "catchup_days") val catchupDays: Int? = null,
    @ColumnInfo(name = "catchup_correction_h") val catchupCorrectionH: Double? = null,
    @ColumnInfo(name = "timeshift_days") val timeshiftDays: Int? = null,
    @ColumnInfo(name = "tvg_rec_days") val tvgRecDays: Int? = null,
    @ColumnInfo(name = "catchup_time_sec") val catchupTimeSec: Long? = null,
    @ColumnInfo(name = "tv_archive") val tvArchive: Boolean? = null,
    @ColumnInfo(name = "tv_archive_days") val tvArchiveDays: Int? = null,
    @ColumnInfo(name = "is_adult", defaultValue = "0") val isAdult: Boolean = false,
    /** {"attrs":{},"kodi":{},"vlc":{},"groups":[],"duration":n} */
    @ColumnInfo(name = "extras_json") val extrasJson: String? = null,
    @ColumnInfo(name = "content_hash") val contentHash: Long,
    @ColumnInfo(name = "missing_since") val missingSince: Long? = null,
    @ColumnInfo(name = "missing_count", defaultValue = "0") val missingCount: Int = 0,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /**
     * v3 (docs/design/m3-guide.md §2.9): [name] after the playlist's prefix rule; NULL = same as [name]. Readers use
     * `COALESCE(channel_prefs.custom_name, channel.display_name, channel.name)`. Stable keys, the matcher and
     * `channel_fts` keep the raw [name].
     */
    @ColumnInfo(name = "display_name") val displayName: String? = null,
)
