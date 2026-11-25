package app.thebox.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import app.thebox.data.db.entity.PlaylistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlist WHERE id = :id")
    fun observe(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlist ORDER BY sort_index, id")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist ORDER BY sort_index, id")
    suspend fun all(): List<PlaylistEntity>

    @Query("SELECT COALESCE(MAX(sort_index), -1) FROM playlist")
    suspend fun maxSortIndex(): Int

    @Query("UPDATE playlist SET needs_reauth = :value WHERE id = :id")
    suspend fun setNeedsReauth(id: Long, value: Boolean)

    @Query("UPDATE playlist SET needs_reauth = 1")
    suspend fun setNeedsReauthAll()

    @Query("UPDATE playlist SET last_refresh_at = :at, last_status = :status WHERE id = :id")
    suspend fun setRefreshStatus(id: Long, at: Long, status: String)

    @Query("UPDATE playlist SET header_json = :headerJson WHERE id = :id")
    suspend fun setHeader(id: Long, headerJson: String?)

    @Query("UPDATE playlist SET server_zone = :zone, server_offset_sec = :offsetSec, clock_skew_sec = :skewSec WHERE id = :id")
    suspend fun setServerTime(id: Long, zone: String?, offsetSec: Int?, skewSec: Long)

    @Query("UPDATE playlist SET active_base_index = :index WHERE id = :id")
    suspend fun setActiveBaseIndex(id: Long, index: Int)

    /** `TS`/`HLS` learned by the player after a 405/406 flip, or null (docs/design/m2-playback-core.md §2.10). */
    @Query("UPDATE playlist SET xtream_output_learned = :value WHERE id = :id")
    suspend fun setXtreamOutputLearned(id: Long, value: String?)

    /** The user's `AUTO`/`TS`/`HLS` choice. Clears the learned value (§2.10: "cleared when the user changes it"). */
    @Query("UPDATE playlist SET xtream_output = :value, xtream_output_learned = NULL WHERE id = :id")
    suspend fun setXtreamOutput(id: Long, value: String)

    @Query("UPDATE playlist SET credential_ref = :ref WHERE id = :id")
    suspend fun setCredentialRef(id: Long, ref: String)

    // ---- M3 (docs/design/m3-guide.md §2.9.3, §3.12)

    /** The user's prefix rule (`AUTO`/`OFF`/`CUSTOM` + JSON). Resets `name_prefix_ver` so the recompute pass runs. */
    @Query("UPDATE playlist SET name_prefix_mode = :mode, name_prefix_custom = :customJson, name_prefix_ver = 0 WHERE id = :id")
    suspend fun setNamePrefix(id: Long, mode: String, customJson: String?)

    /** Stamped after a recompute pass over the playlist's `channel.display_name`s. */
    @Query("UPDATE playlist SET name_prefix_ver = :ver WHERE id = :id")
    suspend fun setNamePrefixVer(id: Long, ver: Int)

    // ---- M4 (docs/design/m4-player-ux.md §1.4, §3.8.8)

    /**
     * The catch-up variant that reached first media (`CatchupUrlBuilder.variants` key), or null to forget it. One-column
     * `UPDATE`: a whole-row [update] from a stale entity would overwrite concurrent writes (M2 N-L13).
     */
    @Query("UPDATE playlist SET catchup_probe_winner = :value WHERE id = :id")
    suspend fun setCatchupProbeWinner(id: Long, value: String?)

    @Query("SELECT catchup_probe_winner FROM playlist WHERE id = :id")
    suspend fun catchupProbeWinner(id: Long): String?

    /**
     * True when any channel of playlist [id] is keyed by the Xtream rule (`xc:`, `xcm:`, `xce:`): for an M3U this
     * happens only when the refresh detected Xtream credentials. A range on the identity index; stops at the first row.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM channel WHERE playlist_id = :id AND stable_key >= 'xc' AND stable_key < 'xd')")
    suspend fun hasXtreamKeyedChannels(id: Long): Boolean

    // ---- M5 WP-B (docs/design/m5-setup-settings.md §3.3.1, §3.4.5): one column per playlist row, never the whole-row
    // [update] from a possibly stale entity (M2 N-L13): a refresh writes other columns of the same row concurrently.

    @Query("UPDATE playlist SET name = :name WHERE id = :id")
    suspend fun setName(id: Long, name: String)

    /** Redacted host or file name only (never a URL with credentials). */
    @Query("UPDATE playlist SET display_source = :display WHERE id = :id")
    suspend fun setDisplaySource(id: Long, display: String)

    @Query("UPDATE playlist SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE playlist SET update_on_start = :value WHERE id = :id")
    suspend fun setUpdateOnStart(id: Long, value: Boolean)

    /** `PROVIDER` | `PLAYLIST_ORDER` | `START_AT:<n>`. */
    @Query("UPDATE playlist SET numbering_mode = :mode WHERE id = :id")
    suspend fun setNumberingMode(id: Long, mode: String)

    /** `PROVIDER` | `NUMBER` | `NAME`. */
    @Query("UPDATE playlist SET order_mode = :mode WHERE id = :id")
    suspend fun setOrderMode(id: Long, mode: String)

    /**
     * 0 = manual. (Event channels commit 0, docs/design/m6-event-channels.md §10) The user picked it, so it also clears
     * `refresh_interval_auto`: the choice stands over the event default. Its only caller is the settings row.
     */
    @Query("UPDATE playlist SET refresh_interval_h = :hours, refresh_interval_auto = 0 WHERE id = :id")
    suspend fun setRefreshInterval(id: Long, hours: Int)

    @Query("UPDATE playlist SET use_playlist_epg = :value WHERE id = :id")
    suspend fun setUsePlaylistEpg(id: Long, value: Boolean)

    /** null = the default user agent. Written by the UA row and after a working VLC probe (§3.4.5). */
    @Query("UPDATE playlist SET user_agent = :userAgent WHERE id = :id")
    suspend fun setUserAgent(id: Long, userAgent: String?)

    /** Non-secret extra headers (secret ones live in the credential blob). */
    @Query("UPDATE playlist SET extra_headers_json = :headers WHERE id = :id")
    suspend fun setExtraHeaders(id: Long, headers: Map<String, String>?)

    /** `AUTO` | `OFF` | a `CatchupMode` name. Clears the probe winner: it was learned for the old mode. */
    @Query("UPDATE playlist SET catchup_mode_setting = :mode, catchup_probe_winner = NULL WHERE id = :id")
    suspend fun setCatchupModeSetting(id: Long, mode: String)

    @Query("UPDATE playlist SET catchup_days_override = :days WHERE id = :id")
    suspend fun setCatchupDaysOverride(id: Long, days: Int?)

    /** `AUTO` | `UTC` | `DEVICE` | `SERVER` | an IANA id. */
    @Query("UPDATE playlist SET token_zone_setting = :value WHERE id = :id")
    suspend fun setTokenZoneSetting(id: Long, value: String)

    @Query("UPDATE playlist SET padding_before_sec = :sec WHERE id = :id")
    suspend fun setPaddingBefore(id: Long, sec: Int)

    @Query("UPDATE playlist SET padding_after_sec = :sec WHERE id = :id")
    suspend fun setPaddingAfter(id: Long, sec: Int)

    @Query("UPDATE playlist SET max_connections_override = :max WHERE id = :id")
    suspend fun setMaxConnectionsOverride(id: Long, max: Int?)

    @Query("UPDATE playlist SET ignore_tls_errors = :value WHERE id = :id")
    suspend fun setIgnoreTlsErrors(id: Long, value: Boolean)

    // ---- Event channels commit 0 (docs/design/m6-event-channels.md §4.5, §5.2, §5.5, §10): v6 columns, one per write.

    /** The "Hide channels with no event" switch (§5.5). The caller re-runs the hide pass (WP-2). */
    @Query("UPDATE playlist SET hide_empty_events = :on WHERE id = :id")
    suspend fun setHideEmptyEvents(id: Long, on: Boolean)

    /** Restores the event default of the update interval (backup merge, tests). The settings row uses [setRefreshInterval]. */
    @Query("UPDATE playlist SET refresh_interval_auto = :auto WHERE id = :id")
    suspend fun setRefreshIntervalAuto(id: Long, auto: Boolean)

    /** The learned zone and its sample count (§3.3). `EventIndexImpl` calls it only when they change (re-review E9). */
    @Query("UPDATE playlist SET event_zone = :zone, event_zone_n = :n WHERE id = :id")
    suspend fun setEventZone(id: Long, zone: String?, n: Int)

    /** Live events after the last index build (§5.2). Only when it changes (E9): `playlist` reloads the guide's rows. */
    @Query("UPDATE playlist SET event_count = :count WHERE id = :id")
    suspend fun setEventCount(id: Long, count: Int)

    /** The event index's key inputs of every **enabled** playlist (§5.2), by id. */
    @Query("SELECT $EVENT_KEY_COLUMNS FROM playlist WHERE enabled = 1 ORDER BY id")
    suspend fun eventKeys(): List<PlaylistEventKey>

    /** [eventKeys] as a flow: it re-emits on every `playlist` write; the index compares keys before rebuilding. */
    @Query("SELECT $EVENT_KEY_COLUMNS FROM playlist WHERE enabled = 1 ORDER BY id")
    fun observeEventKeys(): Flow<List<PlaylistEventKey>>
}

private const val EVENT_KEY_COLUMNS =
    "id, refresh_seq, last_refresh_at, server_zone, event_zone, event_zone_n, event_count, refresh_interval_h, " +
        "refresh_interval_auto, hide_empty_events"

/**
 * (Event channels commit 0, docs/design/m6-event-channels.md §5.2, §10) One enabled playlist's event inputs: the index key
 * (`id`, [refreshSeq], [lastRefreshAt], [eventZone]), the learned-zone state and fallback ([eventZoneN], [serverZone]),
 * and what the scheduler and the hide pass read ([eventCount], [refreshIntervalH], [refreshIntervalAuto],
 * [hideEmptyEvents]).
 */
data class PlaylistEventKey(
    val id: Long,
    @androidx.room.ColumnInfo(name = "refresh_seq") val refreshSeq: Long,
    @androidx.room.ColumnInfo(name = "last_refresh_at") val lastRefreshAt: Long?,
    @androidx.room.ColumnInfo(name = "server_zone") val serverZone: String?,
    @androidx.room.ColumnInfo(name = "event_zone") val eventZone: String?,
    @androidx.room.ColumnInfo(name = "event_zone_n") val eventZoneN: Int,
    @androidx.room.ColumnInfo(name = "event_count") val eventCount: Int,
    @androidx.room.ColumnInfo(name = "refresh_interval_h") val refreshIntervalH: Int,
    @androidx.room.ColumnInfo(name = "refresh_interval_auto") val refreshIntervalAuto: Boolean,
    @androidx.room.ColumnInfo(name = "hide_empty_events") val hideEmptyEvents: Boolean,
)
