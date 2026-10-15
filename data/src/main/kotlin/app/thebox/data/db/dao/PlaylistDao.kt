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
}
