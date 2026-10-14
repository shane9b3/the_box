package app.thebox.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import app.thebox.core.source.PlaylistType

/** `playlist` (docs/design/m1-data-layer.md §2.1.2). Secrets live in `credential(ref = credentialRef)`. */
@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: PlaylistType,
    @ColumnInfo(name = "credential_ref") val credentialRef: String,
    /** Redacted: "panel.example.com:8080", "my_list.m3u". */
    @ColumnInfo(name = "display_source") val displaySource: String,
    @ColumnInfo(name = "sort_index") val sortIndex: Int,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(name = "needs_reauth", defaultValue = "0") val needsReauth: Boolean = false,
    @ColumnInfo(name = "user_agent") val userAgent: String? = null,
    /** Non-secret headers (JSON map); secret ones go in PlaylistSecret.headers. */
    @ColumnInfo(name = "extra_headers_json") val extraHeaders: Map<String, String>? = null,
    @ColumnInfo(name = "use_playlist_epg", defaultValue = "1") val usePlaylistEpg: Boolean = true,
    @ColumnInfo(name = "update_on_start", defaultValue = "1") val updateOnStart: Boolean = true,
    /** 0 = manual. */
    @ColumnInfo(name = "refresh_interval_h", defaultValue = "24") val refreshIntervalH: Int = 24,
    /** PROVIDER | PLAYLIST_ORDER | START_AT:<n> */
    @ColumnInfo(name = "numbering_mode", defaultValue = "'PROVIDER'") val numberingMode: String = "PROVIDER",
    /** PROVIDER | NUMBER | NAME */
    @ColumnInfo(name = "order_mode", defaultValue = "'PROVIDER'") val orderMode: String = "PROVIDER",
    /** AUTO | TS | HLS */
    @ColumnInfo(name = "xtream_output", defaultValue = "'AUTO'") val xtreamOutput: String = "AUTO",
    /** bit0 live+radio, bit1 movies, bit2 series. */
    @ColumnInfo(name = "content_types", defaultValue = "7") val contentTypes: Int = 7,
    @ColumnInfo(name = "extgrp_sticky", defaultValue = "0") val extgrpSticky: Boolean = false,
    @ColumnInfo(name = "charset_override") val charsetOverride: String? = null,
    /** AUTO | OFF | <CatchupMode> */
    @ColumnInfo(name = "catchup_mode_setting", defaultValue = "'AUTO'") val catchupModeSetting: String = "AUTO",
    @ColumnInfo(name = "catchup_days_override") val catchupDaysOverride: Int? = null,
    @ColumnInfo(name = "catchup_probe_winner") val catchupProbeWinner: String? = null,
    /** AUTO | UTC | DEVICE | SERVER | <IANA id> */
    @ColumnInfo(name = "token_zone_setting", defaultValue = "'AUTO'") val tokenZoneSetting: String = "AUTO",
    @ColumnInfo(name = "server_zone") val serverZone: String? = null,
    @ColumnInfo(name = "server_offset_sec") val serverOffsetSec: Int? = null,
    @ColumnInfo(name = "clock_skew_sec", defaultValue = "0") val clockSkewSec: Long = 0,
    @ColumnInfo(name = "catchup_correction_h") val catchupCorrectionH: Double? = null,
    @ColumnInfo(name = "padding_before_sec", defaultValue = "60") val paddingBeforeSec: Int = 60,
    @ColumnInfo(name = "padding_after_sec", defaultValue = "300") val paddingAfterSec: Int = 300,
    @ColumnInfo(name = "max_connections_override") val maxConnectionsOverride: Int? = null,
    @ColumnInfo(name = "ignore_tls_errors", defaultValue = "0") val ignoreTlsErrors: Boolean = false,
    @ColumnInfo(name = "active_base_index", defaultValue = "0") val activeBaseIndex: Int = 0,
    /** M3uHeader JSON with EPG URLs redacted (B3). */
    @ColumnInfo(name = "header_json") val headerJson: String? = null,
    /** Written only by MergeSink.finish (S10). */
    val etag: String? = null,
    @ColumnInfo(name = "last_modified") val lastModified: String? = null,
    @ColumnInfo(name = "refresh_seq", defaultValue = "0") val refreshSeq: Long = 0,
    @ColumnInfo(name = "live_count", defaultValue = "0") val liveCount: Int = 0,
    @ColumnInfo(name = "last_refresh_at") val lastRefreshAt: Long? = null,
    @ColumnInfo(name = "last_success_at") val lastSuccessAt: Long? = null,
    @ColumnInfo(name = "last_status") val lastStatus: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /**
     * v2 (docs/design/m2-playback-core.md §2.10): `TS`/`HLS` after a successful 405/406 flip while [xtreamOutput] is
     * `AUTO`. Written only by [PlaylistDao.setXtreamOutputLearned]; cleared by [PlaylistDao.setXtreamOutput].
     */
    @ColumnInfo(name = "xtream_output_learned") val xtreamOutputLearned: String? = null,
)
