package app.thebox.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * EPG sources (ordered). [id] is the stable sourceId used in epg.db and EpgChannelRef. Playlist-derived
 * sources are identified by (playlist_id, origin, ordinal) and their URL is updated in place (S14).
 */
@Entity(
    tableName = "epg_source",
    foreignKeys = [ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlist_id"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["playlist_id"], name = "index_epg_source_playlist"),
        Index(value = ["url_hash"], name = "index_epg_source_hash"),
        // USER rows have playlist_id NULL and never conflict.
        Index(value = ["playlist_id", "origin", "ordinal"], unique = true, name = "index_epg_source_origin"),
    ],
)
data class EpgSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** USER | PLAYLIST_HEADER | XTREAM */
    val origin: String,
    /** null = USER source, applies to all playlists. */
    @ColumnInfo(name = "playlist_id") val playlistId: Long? = null,
    /** Position in the playlist's x-tvg-url list (0 for XTREAM/USER). */
    @ColumnInfo(defaultValue = "0") val ordinal: Int = 0,
    @ColumnInfo(name = "credential_ref") val credentialRef: String,
    /** Hash64 of the TEMPLATED/redacted URL (B3). */
    @ColumnInfo(name = "url_hash") val urlHash: Long,
    @ColumnInfo(name = "display_source") val displaySource: String,
    val priority: Int,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(name = "shift_min", defaultValue = "0") val shiftMin: Int = 0,
    /** Zone for offset-less times; null = UTC. */
    @ColumnInfo(name = "assume_zone") val assumeZone: String? = null,
    val etag: String? = null,
    @ColumnInfo(name = "last_modified") val lastModified: String? = null,
    /** cacheDir/epg/<id>.src (last good). */
    @ColumnInfo(name = "cached_file") val cachedFile: String? = null,
    @ColumnInfo(name = "cached_bytes") val cachedBytes: Long? = null,
    @ColumnInfo(name = "last_import_at") val lastImportAt: Long? = null,
    @ColumnInfo(name = "last_status") val lastStatus: String? = null,
    @ColumnInfo(name = "channel_count", defaultValue = "0") val channelCount: Int = 0,
    @ColumnInfo(name = "programme_count", defaultValue = "0") val programmeCount: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    /**
     * A playlist guide a restore created before the playlist's first refresh (docs/design/m5-restore-placeholder-guides.md):
     * it carries the user's overrides and mappings but no address yet. Only [EpgSourceDao.active] filters it out.
     */
    val isPlaceholder: Boolean get() = origin != ORIGIN_USER && urlHash == PLACEHOLDER_HASH

    companion object {
        const val ORIGIN_USER = "USER"
        const val ORIGIN_PLAYLIST_HEADER = "PLAYLIST_HEADER"
        const val ORIGIN_XTREAM = "XTREAM"

        /** The `url_hash` of a placeholder. `syncSources` never stores it for a real playlist guide. */
        const val PLACEHOLDER_HASH = 0L

        /** SQL predicate for a placeholder row on `epg_source` aliased `s` (see [isPlaceholder]). */
        const val PLACEHOLDER_SQL = "(s.origin <> 'USER' AND s.url_hash = 0)"

        /** Most guide URLs a playlist header may list for a restore to create placeholders for (ordinals 0..31). */
        const val MAX_HEADER_GUIDES = 32
    }
}
