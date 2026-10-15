// Contract (M4 commit 0): docs/design/m4-player-ux.md §2.1. Verbatim; changes go through the lead.
package app.thebox.core.full

import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideRows
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.TunedState
import app.thebox.core.input.BoxAction
import app.thebox.core.playback.AfrMode
import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.BrokerSnapshot
import app.thebox.core.playback.BufferProfile
import app.thebox.core.playback.HdrKind
import app.thebox.core.playback.NextStep
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.VideoDecoderMode
import kotlinx.coroutines.flow.StateFlow

/** Which layer owns the keys in FULL (§3.1). NONE = bare video (a passive info bar may still be drawn). */
enum class PlayerLayer { NONE, INFO, SCRUB, CHANNELS, QUICK_BAR, PICKER, ERROR, SCREEN_OFF }

/** State pill (product §3.8): LIVE red, CATCH_UP accent, PAUSED white 12 %, BEHIND "−MM:SS". */
enum class PillKind { LIVE, CATCH_UP, PAUSED, BEHIND, CONNECTING, RECONNECTING }

/**
 * [behindMs] is valid at [atMs]; it grows 1:1 with wall time while [behindGrows] (paused), else it is constant (playing
 * archive at 1×: the playhead and the live edge advance together). The UI ticks its label at 1 Hz from these.
 */
data class PillState(
    val kind: PillKind = PillKind.LIVE,
    val behindMs: Long = 0,
    val atMs: Long = 0,
    val behindGrows: Boolean = false,
    val reconnectAttempt: Int = 0,
    val reconnectMax: Int = 0,
)

/** Mirror of :player's TrackRef (:core cannot see :player). */
data class TrackKey(val type: Int, val groupId: String, val index: Int)

data class AudioChoice(
    val key: TrackKey,
    val language: String?,
    val label: String?,
    /** "E-AC-3", "AC-3", "AAC", "MP2", … (TrackController.codecName). */
    val codec: String,
    val channels: Int,
    val describesVideo: Boolean,
    val selected: Boolean,
    val supported: Boolean,
)

data class TextChoice(
    val key: TrackKey,
    val language: String?,
    val label: String?,
    /** "DVB", "CEA-608", "WebVTT", … (TrackController.textKind). */
    val kind: String,
    val selected: Boolean,
    /** CEA-608/708 exposed without a caption descriptor (API notes §1.3): may never carry cues; labelled "if broadcast". */
    val speculative: Boolean = false,
)

data class TrackMenu(
    val audio: List<AudioChoice> = emptyList(),
    val text: List<TextChoice> = emptyList(),
    val textEnabled: Boolean = false,
)

/** What the player machine needs from :player's VideoInfo. */
data class VideoFacts(
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float? = null,
    val interlaced: Boolean = false,
    val hdr: HdrKind = HdrKind.SDR,
    val aspect: AspectMode = AspectMode.FIT,
    /** The channel's decoder override (null = follow the global setting). */
    val decoderOverride: VideoDecoderMode? = null,
)

/** Mirror of :player's StreamStats (field meanings identical, PlaybackApi.kt:168-196). */
data class StatsFacts(
    val videoMime: String? = null,
    val videoCodecs: String? = null,
    val videoDecoder: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float? = null,
    val interlaced: Boolean = false,
    val hdr: HdrKind = HdrKind.SDR,
    val audioMime: String? = null,
    val audioChannels: Int = 0,
    val audioDecoder: String? = null,
    val audioPassthrough: Boolean = false,
    val streamBitrateBps: Long = 0,
    val bufferMs: Long = 0,
    val droppedFrames: Long = 0,
    val httpStatus: Int? = null,
    val host: String? = null,
    val redirects: Int = 0,
    val format: StreamFormat = StreamFormat.UNKNOWN,
    val reconnects: Int = 0,
    val broker: BrokerSnapshot? = null,
    val bufferProfile: BufferProfile? = null,
    val afr: AfrMode = AfrMode.SEAMLESS,
    val displayRefreshHz: Float? = null,
    val tunneling: Boolean = false,
    /** (reviewed 2026-10-15, E23) 0-based base URL in use and the number of bases (backup servers); "Server: n of m". */
    val serverIndex: Int = 0,
    val serverCount: Int = 1,
)

/** Stat rows in display order (§3.4.4); the UI localises [label]. */
enum class StatLabel { VIDEO, RESOLUTION, FRAME_RATE, VIDEO_DECODER, AUDIO, AUDIO_DECODER, BITRATE, BUFFER, DROPPED, FORMAT, HTTP, SERVER, CONNECTIONS, BUFFER_PROFILE, DISPLAY, TUNNELING }

/** [value] is language-neutral (numbers, codec names, hosts). */
data class StatLine(val label: StatLabel, val value: String)

data class InfoGlyphs(
    val catchupDays: Int = 0,
    /** "1080p50", "720p", "576i25" (StatsFormatter.resolutionLabel); null while unknown. */
    val resolution: String? = null,
    /** "5.1", "2.0"; null while unknown. */
    val audioLayout: String? = null,
    val subtitlesAvailable: Boolean = false,
    val hdr: HdrKind = HdrKind.SDR,
)

data class InfoBarState(
    /** Keys go to the bar (layer INFO). False: passive, drawn after a zap, no keys. */
    val active: Boolean,
    /** The channel shown: the tuned one, the pending zap target, or the one browsed with ←/→. */
    val channel: GuideChannel?,
    /** True while ←/→ show another channel than the tuned one (OK tunes it). */
    val browsing: Boolean,
    /** Loaded programmes of [channel] in start order (§3.2.2); empty while loading or without EPG. */
    val programmes: List<ProgrammeInfo>,
    /** Index into [programmes] of the programme in focus; −1 when [programmes] is empty. */
    val focused: Int,
    /** Index of the programme on now; −1 when none. */
    val nowIndex: Int,
    val pill: PillState,
    val glyphs: InfoGlyphs,
    val loading: Boolean,
)

enum class ScrubFocus { BAR, CHIPS }

/**
 * The archive/time-shift bar (§3.5). All times are programme-frame epoch ms (§3.8.1); labels add [displayShiftMs].
 * [playheadMs] is valid at [atMs] and advances 1:1 while [advancing]; [previewMs] is the uncommitted scrub target.
 */
data class ScrubState(
    val focused: Boolean,
    val focus: ScrubFocus,
    val programme: ProgrammeInfo?,
    val barFromMs: Long,
    val barToMs: Long,
    val archiveFromMs: Long,
    val liveEdgeMs: Long,
    val playheadMs: Long,
    val atMs: Long,
    val advancing: Boolean,
    val previewMs: Long?,
    val prev: ProgrammeInfo?,
    val next: ProgrammeInfo?,
    /** 0 = previous chip, 1 = next chip; −1 when [focus] is BAR. (Owner ruling 2026-10-15: no Options chip; the quick bar is Back → Up.) */
    val chipFocused: Int,
    val displayShiftMs: Long,
    val pill: PillState,
    /** A re-request or native seek is in flight. */
    val seeking: Boolean,
    /** Live channel with archive, time-shift not started yet: the playhead sits on the live edge. */
    val atLive: Boolean,
)

enum class ListColumn { GROUPS, CHANNELS }
enum class ChannelMenuItem { WATCH_FROM_START, ADD_FAVOURITE, REMOVE_FAVOURITE, HIDE_CHANNEL }

/**
 * Channel list panel (§3.3). Rows are read from the `GuideRows` the factory received (main-only); [rowsVersion] bumps
 * when blocks land, [nowVersion] when "now" titles arrive in the host's cache.
 */
data class ChannelListState(
    val column: ListColumn,
    val groups: List<GuideSourceEntry>,
    val groupFocused: Int,
    val groupCurrent: Int,
    val source: GuideSource?,
    val lineupVersion: Long,
    val size: Int,
    /** −1 = the pinned "Previous" row (only when [previous] != null), else 0 until [size]. */
    val focused: Int,
    /** First visible lineup index. */
    val top: Int,
    val previous: GuideChannel?,
    val tunedRowId: Long,
    val rowsVersion: Long,
    val nowVersion: Long,
    /** Opened by BACK ("Back in player: Open channel list"): BACK from the panel then leaves FULL (§3.1.5). */
    val openedByBack: Boolean,
    val menu: List<ChannelMenuItem>?,
    val menuFocused: Int,
)

/** Quick bar items in display order (owner ruling 2026-10-15; §3.4.1). Hidden items are left out of [QuickBarState.items]. */
enum class QuickItem {
    AUDIO, ASPECT, SUBTITLES, FAVOURITE_ADD, FAVOURITE_REMOVE, START_OVER, JUMP_TO_LIVE, TIME_SHIFT,
    PREVIOUS_CHANNEL, STREAM_INFO, VIDEO_DECODER, SCREEN_OFF, SETTINGS,
}

/** The quick bar (D-pad Up on bare video, CONTEXT_MENU, QUICK_MENU). Current values are shown on the items. */
data class QuickBarState(
    val items: List<QuickItem>,
    val focused: Int,
    val statsOn: Boolean,
    val audio: AudioChoice?,
    /** null = subtitles off. */
    val subtitle: TextChoice?,
    val aspect: AspectMode,
    val decoder: VideoDecoderMode?,
    val previous: GuideChannel?,
)

enum class PickerKind { AUDIO, SUBTITLES, ASPECT, VIDEO_DECODER }

sealed interface PickerChoice {
    data class Audio(val track: AudioChoice) : PickerChoice
    data class Text(val track: TextChoice) : PickerChoice
    data object TextOff : PickerChoice
    data class Aspect(val mode: AspectMode) : PickerChoice
    /** null = Auto (follow the global setting). */
    data class Decoder(val mode: VideoDecoderMode?) : PickerChoice
}

/**
 * [focused] indexes [choices]; `choices.size` is the "Remember for this channel" row when [remember] != null.
 * [current] is the index of the active choice (check mark).
 */
data class PickerState(
    val kind: PickerKind,
    val choices: List<PickerChoice>,
    val current: Int,
    val focused: Int,
    val remember: Boolean?,
    /** Opened from the quick bar: BACK/LEFT return to it. */
    val fromQuickBar: Boolean,
)

/** Buttons M4 can execute (§3.7). Everything else is a text step until M5 routes it. */
enum class ErrorButton { RETRY, PREVIOUS_CHANNEL, WATCH_LIVE, TRY_OTHER_CATCHUP_FORMAT, TRY_SOFTWARE_DECODER, AUDIO_OPTIONS, STOP, GUIDE }

data class ErrorCardState(
    val error: PlaybackError,
    val channel: GuideChannel?,
    val buttons: List<ErrorButton>,
    val focused: Int,
    /** The first step that has no button in M4 (shown as the next-step line); null when every step is a button. */
    val textStep: NextStep?,
    /** Catch-up probing ended without a working format (ArchiveStatus.FAILED). */
    val archiveFailed: Boolean,
)

data class PlayerUiState(
    val layer: PlayerLayer = PlayerLayer.NONE,
    val info: InfoBarState? = null,
    val scrub: ScrubState? = null,
    val channels: ChannelListState? = null,
    val quick: QuickBarState? = null,
    val picker: PickerState? = null,
    val error: ErrorCardState? = null,
    /** Non-null while the stats overlay is on (it never takes keys). */
    val stats: List<StatLine>? = null,
    val pill: PillState = PillState(),
    /** A transient "Trying another catch-up format (2/3)" line while probing; null otherwise. */
    val probing: Pair<Int, Int>? = null,
)

/** Why FULL was entered (decides the auto-shown bar, §3.2.1). */
enum class EnterReason { USER, ARCHIVE, REMINDER, SEARCH, LAUNCH }

/** Toasts the machine raises synchronously; effects toast what they learn asynchronously (M3 §2.13 item 9). */
enum class PlayerToast {
    NO_CATCHUP, NO_PREVIOUS, NO_FAVOURITES, NO_AUDIO_CHOICE, NO_SUBTITLES, ARCHIVE_NOT_READY, BACK_TO_LIVE,
    ADDED_FAVOURITE, REMOVED_FAVOURITE, CHANNEL_HIDDEN,
    /** arg = ArchiveFailure.name. */
    CATCHUP_FAILED,
}

/** Inputs other than keys. All on main. */
sealed interface PlayerEvent {
    data class Tick(val nowMs: Long) : PlayerEvent
    /** Mirrors GuideEvent.Tuned; [channel] resolved by the host. */
    data class Tuned(val state: TunedState, val channel: GuideChannel?) : PlayerEvent
    /** controller.pendingZap resolved to a row; null when no zap is pending. */
    data class PendingZap(val channel: GuideChannel?) : PlayerEvent
    /** Answer to PlayerEffects.loadProgrammes. */
    data class Programmes(val token: Long, val channelHash: Long, val programmes: List<ProgrammeInfo>) : PlayerEvent
    /** Answer to PlayerEffects.loadChannel (info-bar ←/→ browse). */
    data class BrowseChannel(val token: Long, val channel: GuideChannel?) : PlayerEvent
    data class Tracks(val menu: TrackMenu) : PlayerEvent
    data class Video(val facts: VideoFacts) : PlayerEvent
    data class Stats(val facts: StatsFacts) : PlayerEvent
    data class Archive(val status: ArchiveStatus) : PlayerEvent
    data class Sources(val entries: List<GuideSourceEntry>) : PlayerEvent
    /** The channel list's lineup (the host already called rowsCache.setLineup). */
    data class ListLineup(val lineup: GuideLineup) : PlayerEvent
    data class ListRows(val version: Long) : PlayerEvent
    data class ListNow(val version: Long) : PlayerEvent
    data class Previous(val channel: GuideChannel?) : PlayerEvent
    data class Favourite(val token: Long, val isFavourite: Boolean) : PlayerEvent
    /** The guide's current source and lineup (the channel list opens on it). */
    data class GuideSourceNow(val source: GuideSource?, val lineup: GuideLineup?) : PlayerEvent
    data class Settings(val settings: PlayerSettings, val lowEnd: Boolean) : PlayerEvent
}

/** Side effects; implemented by :ui's PlayerEffectsImpl (WP-D). Main-safe, non-blocking. */
interface PlayerEffects {
    fun zap(delta: Int, isRepeat: Boolean)
    /** [lineup] non-null makes it the zap list (channel list tunes); null keeps the current zap list. */
    fun tune(channel: GuideChannel, lineup: GuideLineup?)
    fun previousChannel()
    fun retry()
    /** controller.stop(USER) (error card "Stop and wait"). */
    fun stopPlayback()
    /** Live without archive: controller.pause()/resume() (M2 freeze, 10 s release, re-tune on resume). */
    fun pausePlain()
    fun resumePlain()
    // Archive (all delegate to ArchivePlayback, §2.3)
    fun pauseLive(channel: GuideChannel)
    fun archivePause()
    fun archiveResume()
    /** (reviewed 2026-10-15, B3) [seq] increases per committed seek; ArchiveStatus.settledSeq echoes it when settled. */
    fun archiveSeek(programmeMs: Long, seq: Long)
    fun jumpToLive()
    fun startOver(channel: GuideChannel)
    fun rewindLive(channel: GuideChannel, backMs: Long)
    fun playProgramme(channel: GuideChannel, info: ProgrammeInfo)
    fun tryNextCatchupFormat()
    /** (reviewed 2026-10-15, S7) Info bar OK on a future programme: M3 reminder store, toasts REMINDER_SET / REMINDER_CANCELLED. */
    fun toggleReminder(channel: GuideChannel, info: ProgrammeInfo)
    /** (owner ruling 2026-10-15) Quick bar Settings: the host dispatches GuideEvent.OpenSettings to the guide machine. */
    fun openSettings()
    // Data
    fun loadProgrammes(token: Long, channel: GuideChannel, aroundMin: Int, before: Int, after: Int)
    fun loadChannel(token: Long, from: GuideChannel, delta: Int)
    fun loadListLineup(source: GuideSource)
    fun ensureListRows(from: Int, to: Int)
    fun ensureListNow(from: Int, to: Int)
    fun queryFavourite(token: Long, channel: GuideChannel)
    fun setFavourite(channel: GuideChannel, on: Boolean)
    fun hideChannel(channel: GuideChannel)
    // Engine
    fun selectAudio(key: TrackKey?)
    fun selectText(key: TrackKey?)
    fun setAspect(mode: AspectMode, remember: Boolean)
    fun setDecoder(mode: VideoDecoderMode?)
    fun setStatsEnabled(on: Boolean)
    /** Viewport HIDDEN (video track off, audio continues) while on; the root draws black. */
    fun setScreenOff(on: Boolean)
    fun toast(kind: PlayerToast, arg: String? = null)
    fun announce(text: String)
}

interface PlayerStateMachine {
    val ui: StateFlow<PlayerUiState>
    /** Wall-clock ms of the next timer (info bar, scrub commit, passive bar, auto-shown scrub); Long.MAX_VALUE = none. */
    val nextDeadlineMs: StateFlow<Long>
    /**
     * (reviewed 2026-10-15, S5) True while a player layer is open or archive video is on screen: the router ignores the
     * long bindings of D-pad LEFT/RIGHT so they act on DOWN with auto-repeat. The guide machine reports it as
     * GuideStateMachine.horizontalRepeat in FULL.
     */
    val horizontalRepeat: Boolean
    /**
     * FULL only, called by the guide machine after its reminder card and overlays (§3.1.1). Returns false when the
     * guide machine must handle [a] (BACK with nothing open, OPEN_GUIDE, EXIT, GROUP_PREV/NEXT, DIGIT, NUMBER_ENTRY).
     */
    fun act(a: GuideEvent.Act): Boolean
    fun release(action: BoxAction)
    /** False when [action] should go on to the framework (none in M4; kept for symmetry with the guide machine). */
    fun handles(action: BoxAction): Boolean
    fun dispatch(event: PlayerEvent)
    fun enterFull(nowMs: Long, reason: EnterReason)
    /** Closes every layer, stops stats, cancels a pending scrub commit (no seek). */
    fun leaveFull()
}

fun interface PlayerStateMachineFactory {
    /** [listRows]: the channel list's own GuideRowsCache (main-only reads). */
    fun create(effects: PlayerEffects, listRows: GuideRows, nowMs: () -> Long): PlayerStateMachine
}
