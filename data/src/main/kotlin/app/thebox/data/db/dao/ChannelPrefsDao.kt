package app.thebox.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.thebox.data.db.entity.ChannelPrefsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelPrefsDao {
    @Query("SELECT * FROM channel_prefs WHERE playlist_id = :playlistId AND stable_key = :stableKey")
    suspend fun get(playlistId: Long, stableKey: String): ChannelPrefsEntity?

    @Query("SELECT * FROM channel_prefs WHERE playlist_id = :playlistId AND stable_key = :stableKey")
    fun observe(playlistId: Long, stableKey: String): Flow<ChannelPrefsEntity?>

    @Upsert
    suspend fun upsert(prefs: ChannelPrefsEntity)

    @Query("DELETE FROM channel_prefs WHERE playlist_id = :playlistId AND stable_key = :stableKey")
    suspend fun delete(playlistId: Long, stableKey: String)

    /** Inserts the row if needed, then bumps the watch counters (recents, S13 ordering). */
    @Query(
        """
        INSERT INTO channel_prefs(playlist_id, stable_key, last_watched_at, watch_count, updated_at, hidden)
        VALUES (:playlistId, :stableKey, :at, 1, :at, 0)
        ON CONFLICT(playlist_id, stable_key) DO UPDATE SET last_watched_at = :at, watch_count = watch_count + 1, updated_at = :at""",
    )
    suspend fun recordWatched(playlistId: Long, stableKey: String, at: Long)

    // Column-targeted upserts (docs/design/m2-playback-core.md §2.10, review S10). Each creates the row if needed and
    // writes one column only, so a learned value never reverts a concurrent setHidden/setLocked (unlike a whole-row
    // [upsert]).

    @Query(
        """
        INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, playback_flags)
        VALUES (:playlistId, :stableKey, 0, 0, :now, :flags)
        ON CONFLICT(playlist_id, stable_key) DO UPDATE SET playback_flags = :flags, updated_at = :now""",
    )
    suspend fun upsertPlaybackFlags(playlistId: Long, stableKey: String, flags: Int, now: Long)

    @Query(
        """
        INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, fps_milli)
        VALUES (:playlistId, :stableKey, 0, 0, :now, :fpsMilli)
        ON CONFLICT(playlist_id, stable_key) DO UPDATE SET fps_milli = :fpsMilli, updated_at = :now""",
    )
    suspend fun upsertFpsMilli(playlistId: Long, stableKey: String, fpsMilli: Int?, now: Long)

    @Query(
        """
        INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, audio_lang)
        VALUES (:playlistId, :stableKey, 0, 0, :now, :audioLang)
        ON CONFLICT(playlist_id, stable_key) DO UPDATE SET audio_lang = :audioLang, updated_at = :now""",
    )
    suspend fun upsertAudioLang(playlistId: Long, stableKey: String, audioLang: String?, now: Long)

    @Query(
        """
        INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, subtitle_lang)
        VALUES (:playlistId, :stableKey, 0, 0, :now, :subtitleLang)
        ON CONFLICT(playlist_id, stable_key) DO UPDATE SET subtitle_lang = :subtitleLang, updated_at = :now""",
    )
    suspend fun upsertSubtitleLang(playlistId: Long, stableKey: String, subtitleLang: String?, now: Long)

    @Query(
        """
        INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, aspect_mode)
        VALUES (:playlistId, :stableKey, 0, 0, :now, :aspectMode)
        ON CONFLICT(playlist_id, stable_key) DO UPDATE SET aspect_mode = :aspectMode, updated_at = :now""",
    )
    suspend fun upsertAspectMode(playlistId: Long, stableKey: String, aspectMode: String?, now: Long)

    @Query(
        """
        INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, video_decoder)
        VALUES (:playlistId, :stableKey, 0, 0, :now, :videoDecoder)
        ON CONFLICT(playlist_id, stable_key) DO UPDATE SET video_decoder = :videoDecoder, updated_at = :now""",
    )
    suspend fun upsertVideoDecoder(playlistId: Long, stableKey: String, videoDecoder: String?, now: Long)

    @Query(
        """
        INSERT INTO channel_prefs(playlist_id, stable_key, hidden, watch_count, updated_at, buffer_profile)
        VALUES (:playlistId, :stableKey, 0, 0, :now, :bufferProfile)
        ON CONFLICT(playlist_id, stable_key) DO UPDATE SET buffer_profile = :bufferProfile, updated_at = :now""",
    )
    suspend fun upsertBufferProfile(playlistId: Long, stableKey: String, bufferProfile: String?, now: Long)

    @Query(
        """
        SELECT cp.* FROM channel_prefs cp
        JOIN channel c ON c.playlist_id = cp.playlist_id AND c.stable_key = cp.stable_key AND c.missing_since IS NULL
        WHERE cp.last_watched_at IS NOT NULL ORDER BY cp.last_watched_at DESC LIMIT :limit""",
    )
    fun recent(limit: Int): Flow<List<ChannelPrefsEntity>>
}
