package app.thebox.player.api

import app.thebox.core.common.Outcome
import app.thebox.core.playback.*
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.xtream.XtreamAccountInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Data the player reads. Bound in :app over the M1 repositories (§2.4.2). All calls are main-safe (impl switches to IO). */
interface PlaybackSourceProvider {
    /** null when the channel no longer exists (purged) → CHANNEL_NOT_FOUND. Parental filtering is the UI's job, not this one's. */
    suspend fun channel(identity: ChannelIdentity): ChannelStreamInfo?
    suspend fun playlist(playlistId: Long): PlaylistPlaybackInfo?
    /** Ok(null): the playlist has no credentials (plain M3U). Err(SourceError.Credentials(…)) as in M1 §3.9. */
    suspend fun credentials(playlistId: Long): Outcome<StreamCredentials?>
    /** Cached account card (xtream_account.info_json); null for plain M3U. */
    suspend fun account(playlistId: Long): XtreamAccountInfo?
    /**
     * Logs in now (player_api.php, Xtream and Xtream-shaped M3U), stores the card and returns it. The implementation
     * coalesces calls: at most one login per playlist per 10 s (ACCOUNT_REFRESH_MIN_INTERVAL_MS), later callers get the
     * cached result.
     */
    suspend fun refreshAccount(playlistId: Long): Outcome<XtreamAccountInfo>
    suspend fun saveActiveBaseIndex(playlistId: Long, index: Int)
    suspend fun saveLearnedOutput(playlistId: Long, output: XtreamOutput?)
    /** v1.1 failover (stream_source). v1 implementations return an empty list. */
    suspend fun backupSources(identity: ChannelIdentity): List<ChannelIdentity>
}

/** Per-channel playback memory (channel_prefs, keyed by (playlist_id, stable_key)). */
data class ChannelPlaybackMemory(
    val audioLanguage: String? = null,
    /** null = follow settings; "" = the user turned subtitles off on this channel. */
    val subtitleLanguage: String? = null,
    val aspect: AspectMode? = null,
    val videoDecoder: VideoDecoderMode? = null,
    val bufferMode: BufferMode? = null,
    /** Learned TS compatibility level 0..1 (§3.8.5). */
    val tsCompatLevel: Int = 0,
    /** Last tune saw HDR video: pre-disable video in the MINI viewport (§3.9.5). */
    val knownHdr: Boolean = false,
    /** Last snapped fps × 1000 (AFR pre-switch, §3.9.3). */
    val fpsMilli: Int? = null,
    /**
     * Device finding 2026-10-17 (Sony Bravia, MediaTek HEVC): the ladder turned tunneling off for a channel whose tunneled
     * decoder produced nothing, and the user's next retry tunneled again. Learned bit: this channel plays untunneled.
     */
    val tunnelingOff: Boolean = false,
)

interface ChannelMemory {
    suspend fun read(identity: ChannelIdentity): ChannelPlaybackMemory
    /** Serialised read-modify-write (fixes M1 code review Later 6 for these columns). */
    suspend fun update(identity: ChannelIdentity, change: (ChannelPlaybackMemory) -> ChannelPlaybackMemory)
    /** channel_prefs.last_watched_at / watch_count (startup "last channel"). */
    suspend fun recordWatched(identity: ChannelIdentity)
}

/** Global playback settings (product §4.4, §4.9). Per-playlist values come from PlaylistPlaybackInfo. */
data class PlaybackSettings(
    val bufferMode: BufferMode = BufferMode.AUTO,
    val videoDecoderMode: VideoDecoderMode = VideoDecoderMode.AUTO,
    /** Media3 FFmpeg audio extension in the renderer list (EXTENSION_RENDERER_MODE_ON vs OFF). Changing it rebuilds the player. */
    val softwareAudioFallback: Boolean = true,
    /** Prefer FFmpeg for every MIME type it supports (EXTENSION_RENDERER_MODE_PREFER semantics, applied per MIME at runtime). */
    val preferSoftwareAudio: Boolean = false,
    val audioOutput: AudioOutputMode = AudioOutputMode.AUTO,
    val tunneling: Boolean = false,
    val afrMode: AfrMode = AfrMode.SEAMLESS,
    val hdrInPreview: HdrPreviewMode = HdrPreviewMode.POSTER,
    /** ISO 639 codes, most preferred first (max 3). Empty = device locale. */
    val preferredAudioLanguages: List<String> = emptyList(),
    val subtitlesEnabled: Boolean = false,
    val preferredTextLanguages: List<String> = emptyList(),
    val defaultAspect: AspectMode = AspectMode.FIT,
    /** 3, 5, 10, or -1 = unlimited (product §4.4). */
    val reconnectAttempts: Int = 5,
    /** No first media (§0.16: first frame, or first audio position for audio-only/poster/hidden) within this after tune() → error card (product/protocol "stream switch timeout"). */
    val streamSwitchTimeoutMs: Long = 15_000,
    /** One-shot retry with the VLC preset UA on a 403 that is not a connection limit (§4.3 L5). */
    val autoUserAgentFallback: Boolean = true,
    val userAgentFallbackPreset: String = "VLC/3.0.20 LibVLC/3.0.20",
    /** Sent when neither the channel nor the playlist sets a UA. :app passes HttpModule.DEFAULT_USER_AGENT. */
    val defaultUserAgent: String = "TheBox/1.0 (Android TV)",
    /** v1.1 failover toggle; read by the ladder (TryBackupSource). */
    val failoverToBackup: Boolean = true,
    /** Product §4.4 "After standby over 15 min": true = resume playback, false = guide with nothing playing. */
    val resumeAfterStandby: Boolean = true,
    /** Test hook: compresses lifecycle timers in instrumented tests. 1.0 in production. */
    val lifecycleTimeScale: Float = 1.0f,
    /**
     * (M6 time-shift, docs/design/m6-timeshift.md §12, lead reconciliation R10) "Pause and rewind", "Keep recording"
     * and "Protect storage". Read once per live cycle (NEXT_TUNE / LIVE). Defaults mirror `PlaybackPrefs`.
     */
    val timeshift: app.thebox.core.settings.TimeshiftLength = app.thebox.core.settings.TimeshiftLength.AUTO,
    val timeshiftHold: app.thebox.core.settings.TimeshiftHold = app.thebox.core.settings.TimeshiftHold.AUTO,
    val storageProtection: app.thebox.core.settings.StorageProtection = app.thebox.core.settings.StorageProtection.AUTO,
)

interface PlaybackSettingsSource {
    val settings: StateFlow<PlaybackSettings>
    val current: PlaybackSettings get() = settings.value
    fun update(transform: (PlaybackSettings) -> PlaybackSettings)
}

/** In-memory default (M2), like M1's InMemoryDataSettings. M5 binds a DataStore-backed implementation. */
@Singleton
class InMemoryPlaybackSettings @Inject constructor() : PlaybackSettingsSource {
    private val state = MutableStateFlow(PlaybackSettings())
    override val settings: StateFlow<PlaybackSettings> = state
    override fun update(transform: (PlaybackSettings) -> PlaybackSettings) = state.update(transform)
}
