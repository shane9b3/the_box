package app.thebox.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "channel_prefs",
    primaryKeys = ["playlist_id", "stable_key"],
    foreignKeys = [ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlist_id"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["last_watched_at"], name = "index_channel_prefs_recent"),
        // v5 (docs/design/m6-channel-editor.md §3.6): the group lineup's second half drives from it. Name and columns match
        // Migrations.MIGRATION_4_5 exactly (Room validation).
        Index(value = ["playlist_id", "custom_group_key"], name = "index_channel_prefs_group"),
    ],
)
data class ChannelPrefsEntity(
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "stable_key") val stableKey: String,
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
    /** null = auto (channel.is_adult / group lock). */
    val locked: Boolean? = null,
    @ColumnInfo(name = "custom_name") val customName: String? = null,
    @ColumnInfo(name = "custom_number") val customNumber: Int? = null,
    @ColumnInfo(name = "custom_logo") val customLogo: String? = null,
    /** User order inside its group (dense per group). */
    @ColumnInfo(name = "custom_sort_index") val customSortIndex: Int? = null,
    /** Per-channel guide shift (overrides tvg-shift). */
    @ColumnInfo(name = "epg_shift_min") val epgShiftMin: Int? = null,
    @ColumnInfo(name = "aspect_mode") val aspectMode: String? = null,
    @ColumnInfo(name = "audio_lang") val audioLang: String? = null,
    @ColumnInfo(name = "subtitle_lang") val subtitleLang: String? = null,
    @ColumnInfo(name = "video_decoder") val videoDecoder: String? = null,
    @ColumnInfo(name = "buffer_profile") val bufferProfile: String? = null,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long? = null,
    @ColumnInfo(name = "watch_count", defaultValue = "0") val watchCount: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /**
     * v2 (docs/design/m2-playback-core.md §2.10). Learned, not a user choice (M5 export leaves it out): bits 0–1 the
     * learned TS compatibility level, bit 2 "last tune was HDR". Written only by [ChannelPrefsDao.upsertPlaybackFlags].
     */
    @ColumnInfo(name = "playback_flags", defaultValue = "0") val playbackFlags: Int = 0,
    /** v2. Learned: last snapped fps × 1000. Written only by [ChannelPrefsDao.upsertFpsMilli]. */
    @ColumnInfo(name = "fps_milli") val fpsMilli: Int? = null,
    /**
     * v5 (docs/design/m6-channel-editor.md §3.6): the channel editor's Move to group. A LIVE `group_key` of the same
     * playlist (`xcat:<id>` / `g:<name>`, refresh-stable); null = the provider's group. Readers fall back to the provider
     * group only when no `channel_group` row has the key ([app.thebox.data.db.dao.ChannelDao.GROUP_TAIL]).
     */
    @ColumnInfo(name = "custom_group_key") val customGroupKey: String? = null,
)
