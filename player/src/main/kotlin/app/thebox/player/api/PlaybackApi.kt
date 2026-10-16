package app.thebox.player.api

import androidx.media3.common.Player
import app.thebox.core.catchup.ArchiveRequest
import app.thebox.core.playback.*
import app.thebox.core.refresh.ChannelIdentity
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** The one playback engine of the app (plan §3 "Process"). Implemented by WP-D's PlaybackControllerImpl. */
interface PlaybackController {
    val state: StateFlow<PlaybackState>
    /** The channel a debounced zap will tune, for the banner; null when no zap is pending. */
    val pendingZap: StateFlow<ChannelIdentity?>
    val video: StateFlow<VideoInfo>
    val tracks: StateFlow<TrackInfo>
    /** Updated at ≤ 2 Hz while [setStatsEnabled] is true; otherwise frozen at the last value. */
    val stats: StateFlow<StreamStats>
    /** Toast-level events (replay 0, buffer 8, DROP_OLDEST). */
    val notices: SharedFlow<PlaybackNotice>
    /** The UI layer sets FLAG_KEEP_SCREEN_ON from this (§3.10.6). */
    val keepScreenOn: StateFlow<Boolean>
    /** API < 31 AFR: the window's preferredDisplayModeId (0 = system default). Applied by WindowDisplayModeBinder. */
    val displayModeRequest: StateFlow<Int>
    /** Give this to media3-ui-compose PlayerSurface and nothing else: it exposes only the video-surface commands. */
    val surfacePlayer: Player
    val diagnostics: PlaybackDiagnostics

    /** Tune now (no debounce). [zapList] becomes the CH±/Up/Down list when non-null. */
    fun tune(target: TuneTarget, zapList: ZapList? = null)
    /** ±n within the current zap list. Repeats and presses within 350 ms are coalesced (§3.5.4). */
    fun zap(delta: Int, isRepeat: Boolean = false)
    fun setZapList(list: ZapList)
    /** Tunes the previously played channel (KEYCODE_LAST_CHANNEL). False when there is none. */
    fun previousChannel(): Boolean
    /** Archive seam (§3.14): plays one ArchiveRequest built by CatchupUrlBuilder. ArchivePlayback proper is M4. */
    fun tuneArchive(tune: ArchiveTune)
    /** Native seek inside the current archive request only. No-op on live. */
    fun seekTo(positionMs: Long)
    fun position(): PositionSnapshot
    /** Live: freeze now, release the connection after 10 s; resume() re-tunes at the live edge. Archive: real pause. */
    fun pause()
    fun resume()
    fun stop(reason: StopReason = StopReason.USER)
    /** Error card "Retry": restart the current target with a fresh ladder cycle. */
    fun retry()
    fun setViewport(viewport: Viewport)
    fun setStatsEnabled(enabled: Boolean)
    fun selectAudioTrack(track: TrackRef?, remember: Boolean = true)
    /** null = subtitles off. */
    fun selectTextTrack(track: TrackRef?, remember: Boolean = true)
    fun setAspectMode(mode: AspectMode, remember: Boolean = true)
    /** null = follow the global setting. Takes effect with a re-prepare of the current channel. */
    fun setVideoDecoderOverride(mode: VideoDecoderMode?, remember: Boolean = true)
    /** Creates the player and warms the HTTP pool (startup path, architecture review C9). */
    fun prewarm()

    // M4 (docs/design/m4-player-ux.md §2.9): two members with defaults (implementations override)
    /** (M4) Current subtitle cues from Player.Listener.onCues(CueGroup); emptied on every tune and stop. */
    val cues: StateFlow<List<androidx.media3.common.text.Cue>> get() = NO_CUES
    /** (M4) The channel previousChannel() would tune (M2 §3.1.1 rule), for the channel list's pinned row. */
    val previous: StateFlow<ChannelIdentity?> get() = NO_PREVIOUS

    // M5 (docs/design/m5-setup-settings.md §2.12 item 6): members with defaults (WP-A overrides in PlaybackControllerImpl)
    /**
     * Re-prepares the current **live** tune with the current settings at the live edge; counts as one re-prepare in the
     * event log, never a new engine. Called once by `LiveSettingsApplier` when Settings closes (S9). Default: no-op.
     */
    fun reapplySettings() = Unit
    /**
     * Stops the stream (frees the connection) and returns the resolved URL and headers for the external-player intent
     * (product §4.4); null when nothing can be handed off. Default: null.
     */
    fun handOffExternal(): ExternalHandOff? = null
    /**
     * (M5 commit 0, B1) [previousChannel] with the [TuneGuard][app.thebox.core.playback.TuneGuard] refusal visible:
     * [PreviousResult.Locked] when the target needs the PIN. The default maps the M2 Boolean (never Locked); WP-A
     * overrides it when the guard lands in the controller.
     */
    fun previousChannelResult(): PreviousResult = if (previousChannel()) PreviousResult.Started else PreviousResult.NoPrevious
    /**
     * (M5 integration 2026-10-16, E23 / WP-C amendment) The current tune's servers (base URLs as `host[:port]`) and the one
     * in use; empty unless the playlist has backup servers. Feeds the player's Sources picker (§3.6.2). Default: none.
     */
    val servers: StateFlow<ServerList> get() = NO_SERVERS
    /** (M5 integration) Re-resolves the current tune on base URL [index] (the Sources picker); kept as the active one once it plays. */
    fun selectServer(index: Int) = Unit
}

/** (M5 integration, E23) [labels] are `host[:port]` (no credentials); [current] indexes them. */
data class ServerList(val labels: List<String> = emptyList(), val current: Int = 0)

/** (M5, §2.12 item 6) The external-player hand-off (product §4.4). Never logged with its URL. */
data class ExternalHandOff(val url: String, val headers: Map<String, String>, val title: String?) {
    override fun toString(): String = "ExternalHandOff(${app.thebox.core.common.Redact.host(url)})"
}

/** (M5, §2.12 item 6, B1) Result of a previous-channel request. */
sealed interface PreviousResult {
    data object Started : PreviousResult
    data object NoPrevious : PreviousResult
    /** The guard refused the target: the caller opens the PIN prompt (or the "not protected" card) for it. */
    data class Locked(val identity: ChannelIdentity) : PreviousResult
}

sealed interface TuneTarget {
    data class Channel(val identity: ChannelIdentity) : TuneTarget
    /** Debug harness and external "play this URL" intents. Never persisted. [brokerKey] groups connection limits. */
    data class DirectUrl(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val maxConnections: Int = 1,
        val formatHint: StreamFormat = StreamFormat.UNKNOWN,
    ) : TuneTarget {
        override fun toString(): String = "DirectUrl(${app.thebox.core.common.Redact.text(url)})"
    }
}

/** Ordered channel list for zapping, supplied by the UI (group, favourites, search results). */
interface ZapList {
    val id: String
    /** The channel [delta] steps from [from], wrapping; null when the list is empty. Skips hidden/locked channels (the list does). */
    suspend fun neighbour(from: ChannelIdentity, delta: Int): ChannelIdentity?
}

/** [request] comes straight from CatchupUrlBuilder.build (credentials still templated; M2 expands them last). */
data class ArchiveTune(val identity: ChannelIdentity, val request: ArchiveRequest, val startOffsetMs: Long = 0)

// ---- M3 commit 0 (docs/design/m3-guide.md §2.8): the catch-up seam, additive.

/** (reviewed 2026-10-14, S13) Distinguishes "channel gone" from "credentials missing" so the toast names the right next step. */
sealed interface CatchupTargetResult {
    data class Found(val target: app.thebox.core.catchup.CatchupTarget) : CatchupTargetResult
    data object ChannelGone : CatchupTargetResult
    data object CredentialsUnavailable : CatchupTargetResult
}
/** M3 catch-up seam (M2 §3.14). Bound in PlaybackModule over the internal StreamUrlResolver.catchupTarget. */
interface ArchiveTargets {
    /** Main-safe. */
    suspend fun catchupTarget(identity: app.thebox.core.refresh.ChannelIdentity): CatchupTargetResult
}

data class PositionSnapshot(val positionMs: Long, val bufferedMs: Long, val durationMs: Long?, val isSeekable: Boolean, val archiveStartUtcMs: Long?)

enum class StopReason {
    USER, BACKGROUND, STANDBY, HDMI_UNPLUGGED, DISPLAY_OFF, LIVE_PAUSE_TIMEOUT, ERROR, RELEASE,
    /** (M5, §2.12 item 6, B1) resumeAfterLifecycle refused by the TuneGuard: the guide machine opens the PIN prompt. */
    LOCKED,
}

/** What is on screen. [tuneId] increases with every tune/retry. */
data class NowPlaying(
    val tuneId: Long,
    val identity: ChannelIdentity?,
    val title: String?,
    val isArchive: Boolean,
    val format: StreamFormat,
)

enum class ConnectPhase { RESOLVING, WAITING_FOR_SLOT, CONNECTING, BUFFERING_INITIAL, SWITCHING_DISPLAY_MODE }
enum class BufferingCause { REBUFFER, RECONNECTING, RECOVERING }

data class ReconnectProgress(val attempt: Int, val maxAttempts: Int)

sealed interface PlaybackState {
    val target: NowPlaying?

    data object Idle : PlaybackState { override val target: NowPlaying? get() = null }
    data class Connecting(override val target: NowPlaying, val phase: ConnectPhase, val attempt: Int, val sinceElapsedMs: Long) : PlaybackState
    data class Buffering(override val target: NowPlaying, val cause: BufferingCause, val reconnect: ReconnectProgress?) : PlaybackState
    data class Playing(
        override val target: NowPlaying,
        val paused: Boolean = false,
        val warning: PlaybackWarning? = null,
        /** (M4) Archive only: STATE_ENDED reached (the controller reports it as paused, PlaybackControllerImpl.kt:910-915). */
        val ended: Boolean = false,
    ) : PlaybackState
    /** A detector or recoverable error fired and the ladder is acting ([step] is the LadderDecision name). */
    data class Stalled(override val target: NowPlaying, val kind: StallKind?, val step: String, val attempt: Int, val maxAttempts: Int) : PlaybackState
    data class Stopped(override val target: NowPlaying?, val reason: StopReason, val resumable: Boolean) : PlaybackState
    data class Error(override val target: NowPlaying, val error: PlaybackError) : PlaybackState
}

data class VideoInfo(
    val width: Int = 0,
    val height: Int = 0,
    val pixelRatio: Float = 1f,
    /** Snapped fps, or null while unknown. */
    val fps: Float? = null,
    val hdr: HdrKind = HdrKind.SDR,
    /** True while the HDR poster rule keeps the video track disabled (M3 shows the poster). */
    val posterMode: Boolean = false,
    val aspectMode: AspectMode = AspectMode.FIT,
    val interlacedHint: Boolean = false,
    /**
     * (M3 code review 2026-10-14, H-S10) Count of rendered first frames (`onRenderedFirstFrame`: a new stream, surface
     * or re-enabled video track), kept across tunes. The shell lifts its still → FULL scrim on the next one.
     */
    val renderedFirstFrames: Long = 0,
    /** (M4) The channel's decoder override (null = global setting). */
    val decoderOverride: VideoDecoderMode? = null,
)

/** Stable within one tune: [groupId] is TrackGroup.id, [index] the track index in the group. */
data class TrackRef(val type: Int, val groupId: String, val index: Int)

data class AudioTrackOption(
    val ref: TrackRef,
    val language: String?,
    val label: String?,
    /** "E-AC-3", "AC-3", "AAC", "MP2", … */
    val codec: String,
    val channels: Int,
    val describesVideo: Boolean,
    val selected: Boolean,
    val supported: Boolean,
)

data class TextTrackOption(val ref: TrackRef, val language: String?, val label: String?, val kind: String, val selected: Boolean)

data class TrackInfo(
    val audio: List<AudioTrackOption> = emptyList(),
    val text: List<TextTrackOption> = emptyList(),
    val textEnabled: Boolean = false,
)

data class StreamStats(
    val videoMime: String? = null,
    val videoCodecs: String? = null,
    val videoDecoder: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float? = null,
    val hdr: HdrKind = HdrKind.SDR,
    val audioMime: String? = null,
    val audioChannels: Int = 0,
    val audioDecoder: String? = null,
    val audioPassthrough: Boolean = false,
    /** Measured over the last 2 s at the data source (bits/s). Media3's bandwidth meter does not report on endless loads. */
    val streamBitrateBps: Long = 0,
    val bufferMs: Long = 0,
    val droppedFrames: Long = 0,
    val httpStatus: Int? = null,
    /** Final host after redirects (host[:port] only). */
    val host: String? = null,
    val redirects: Int = 0,
    val format: StreamFormat = StreamFormat.UNKNOWN,
    val reconnects: Int = 0,
    val broker: BrokerSnapshot? = null,
    val bufferProfile: BufferProfile? = null,
    val afr: AfrMode = AfrMode.SEAMLESS,
    val displayRefreshHz: Float? = null,
    val tunneling: Boolean = false,
    val updatedAtElapsedMs: Long = 0,
    // (M4, reviewed 2026-10-15, E23)
    /** 0-based base URL in use (ResolvedStream.baseIndex) and the number of bases. */
    val serverIndex: Int = 0,
    val serverCount: Int = 1,
)

sealed interface PlaybackNotice {
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : PlaybackNotice
    data object Reconnected : PlaybackNotice
    data class SwitchedToBackupServer(val baseIndex: Int) : PlaybackNotice
    data class SwitchedFormat(val to: StreamFormat) : PlaybackNotice
    data class AudioDecoderSwitched(val toSoftware: Boolean, val mimeType: String) : PlaybackNotice
    /** (M5 integration, B2) [forced]: "Always passthrough" is on (the toast names the way back to Auto). */
    data class PassthroughDisabled(val mimeType: String?, val forced: Boolean = false) : PlaybackNotice
    data object VideoDecoderSwitchedToSoftware : PlaybackNotice
    data object TunnelingDisabled : PlaybackNotice
    data class UserAgentSwitched(val preset: String) : PlaybackNotice
    data class BufferIncreased(val level: Int) : PlaybackNotice
    /** Live resumed after pause (no time-shift on this channel). */
    data object LiveResumed : PlaybackNotice
    data class Warning(val warning: PlaybackWarning) : PlaybackNotice
}

interface PlaybackDiagnostics {
    fun events(): List<PlaybackEvent>
    fun zaps(): List<ZapRecord>
    /** Device capability dump for S16 (decoders, HDR, display modes, audio encodings, FFmpeg). */
    suspend fun capabilities(): Map<String, String>
}

// M4 (docs/design/m4-player-ux.md §2.9): defaults of PlaybackController.cues/previous; fakes in other modules override them.
internal val NO_CUES: StateFlow<List<androidx.media3.common.text.Cue>> = kotlinx.coroutines.flow.MutableStateFlow(emptyList())
internal val NO_PREVIOUS: StateFlow<ChannelIdentity?> = kotlinx.coroutines.flow.MutableStateFlow(null)
internal val NO_SERVERS: StateFlow<ServerList> = kotlinx.coroutines.flow.MutableStateFlow(ServerList())
