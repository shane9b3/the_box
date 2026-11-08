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
enum class PlayerLayer {
    NONE, INFO, SCRUB, CHANNELS, QUICK_BAR, PICKER, ERROR, SCREEN_OFF,
    /**
     * (Score bug commit 0, docs/design/m6-score-bug.md §6, U-B1; append-only) Place mode: the arrows move the score bug
     * between corners, OK or Back saves ([PlayerEffects.setScoreCorner]) and returns to the quick bar. WP-4b enters it.
     */
    PLACE_SCORE,
}

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
    /** (M5 integration 2026-10-16, E23) The tune's servers (`host[:port]` labels; ≥ 2 offers Sources) and the one in use. */
    val servers: List<String> = emptyList(),
    val server: Int = 0,
    /**
     * (U-S12, code review 2026-10-16; additive) `playback.audioOutput` is "Always passthrough": the AUDIO picker offers
     * [PickerChoice.AudioOutputAuto] ("Back to Auto", B2) after the tracks. The host fills it from `PlaybackSettings`.
     */
    val audioOutputForced: Boolean = false,
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
    // (M6 time-shift commit 0, docs/design/m6-timeshift.md §11.4, §16.3; additive, WP-C fills them)
    /** PROGRAMME: M4's bar; REWIND_WINDOW: a channel without catch-up, the bar spans the rewind window. */
    val scope: ScrubScope = ScrubScope.PROGRAMME,
    /** REWIND_WINDOW: `edge − rewindLength` (P frame). */
    val windowFromMs: Long? = null,
    /** REWIND_WINDOW: the oldest saved instant (P frame); the saved part grows from the right. */
    val savedFromMs: Long? = null,
    /** "Not saved" ranges (P frame, ≤ MAX_SEGMENTS; content equality, A-C1). */
    val notSaved: app.thebox.core.playback.timeshift.TimeRanges = app.thebox.core.playback.timeshift.TimeRanges.EMPTY,
    /** The header line under the pill while paused (§11.5); null = none. */
    val header: PausedHeader? = null,
    /** Show the hint row (focused bar, first HINT_SHOW_COUNT times, §11.6). */
    val hint: Boolean = false,
)

/** (M6 commit 0, §11.4) What the scrub bar spans. */
enum class ScrubScope { PROGRAMME, REWIND_WINDOW }

/** (M6 commit 0, §11.5) The paused header line: saving, full, hold limit, slot lost, catch-up covers the rest. */
enum class PausedHeader { SAVING, FULL, HOLD_LIMIT, SLOT_LOST, CATCHUP_COVERS }

/**
 * (M6 time-shift commit 0, docs/design/m6-timeshift.md §11.1, §16.3) The local buffer of the tuned live channel, in the
 * **programme frame** (P), from `LocalTimelineBackend` (A-C1). [fromMs] is the oldest saved instant, [atMs] the
 * snapshot's live edge; [notSaved] the parts that were not saved. [off] says why it is not [available].
 */
data class LocalBufferInfo(
    val available: Boolean, val fromMs: Long, val atMs: Long, val saving: Boolean, val notSaved: app.thebox.core.playback.timeshift.TimeRanges,   // P frame, from LocalTimelineBackend (A-C1)
    val pauseRemainingMs: Long?, val stop: app.thebox.core.playback.timeshift.RecordStop?,
    val off: app.thebox.core.playback.timeshift.BufferOff?, val hls: Boolean, val limitedToday: Boolean,
    // (Fix pass, product, 2026-10-19: UX review S1, S2, S8, S9; additive, defaults keep callers compiling)
    /** The rewind window's width (a length, ms), stable for the cycle: the REWIND_WINDOW bar never rescales. 0 = unknown. */
    val windowMs: Long = 0,
    /** The "Keep recording while paused" limit in force (a length, ms); null = until full. "Saving stopped after N min". */
    val holdMs: Long? = null,
    /** When saving stopped (P frame); null while saving. */
    val stoppedAtMs: Long? = null,
    /** The controller's buffered cycle this snapshot belongs to; a new value is a new cycle (REWIND_NOTHING_YET resets). */
    val cycleId: Long = 0,
    // (Storage locations commit 0, docs/design/m6-storage-locations.md §10, §12.4; additive, defaults = this device)
    /** Where this cycle saves (after a §5.2 switch: INTERNAL). */
    val storage: app.thebox.core.storage.StorageKind = app.thebox.core.storage.StorageKind.INTERNAL,
    /** Why a remote/USB ring stopped saving (the §10 wording of REWIND_STOPPED_*); null = none or internal. */
    val storageFailure: app.thebox.core.storage.StorageFailure? = null,
    /** Why the cycle saves on this device instead of the chosen place; the machine toasts SAVE_LOCATION_FALLBACK once. */
    val fallback: app.thebox.core.storage.LocationFallback? = null,
    /** The chosen location's name ("NAS"), for the toasts and the stats line; null = this device. */
    val storageName: String? = null,
    /** The chosen location's id ("internal", "vol-…", "smb-…"): the key of `PlayerEffects.fallbackToastDue`. */
    val locationId: String = app.thebox.core.storage.StorageConstants.INTERNAL_ID,
)

/**
 * (Fix pass, product, 2026-10-19: UX review §7, S4) A LOCAL time-shift of the tuned channel is paused: what the paused
 * header line and the dim card need, independent of whether the scrub bar is shown. [pausedSinceMs] is when the pause
 * began (device wall ms; a seek while paused keeps it, S2); [dimmed] since [dimSinceMs] draws the dim layer and its
 * card (the next key only restores the picture, §11.5); [mediaKeys]: the remote has sent a media key this process
 * ("Press OK or ▶", product line 94); [header] is the §11.5 line (null for a storage stop, which toasts instead).
 */
data class PausedUi(
    val pausedSinceMs: Long,
    val dimmed: Boolean = false,
    val dimSinceMs: Long? = null,
    val mediaKeys: Boolean = false,
    val header: PausedHeader? = null,
)

enum class ListColumn { GROUPS, CHANNELS }
enum class ChannelMenuItem {
    WATCH_FROM_START, ADD_FAVOURITE, REMOVE_FAVOURITE, HIDE_CHANNEL,
    // M5 (docs/design/m5-setup-settings.md §2.12 item 4; append-only)
    LOCK_CHANNEL, CHANNEL_OPTIONS,
}

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
    PREVIOUS_CHANNEL, STREAM_INFO, VIDEO_DECODER, SCREEN_OFF,
    /**
     * (M5, docs/design/m5-setup-settings.md §2.12 item 4; M4 E23) The server picker. Placed before SETTINGS, not after
     * it: QuickItem is never persisted, and M4's rule "display order is the enum order, Settings always last" holds.
     */
    SOURCES,
    SETTINGS,
    /**
     * (Score bug commit 0, docs/design/m6-score-bug.md §6, U-S5; append-only) "Score bug": right after Subtitles (or
     * Picture shape) while a game is followed, just before Settings otherwise; only while Show Sports is on. Placed by
     * `QuickBarModel` (the explicit order list), not by this enum's order. Offered from WP-4b on.
     */
    SCORE_BUG,
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
    /**
     * (Score bug WP-4b, docs/design/m6-score-bug.md §6; additive) The facts behind the "Score bug" item's value line
     * (`SportsText.quickValue`); set only while the quick bar shows, so a score update never recomposes anything else.
     */
    val score: app.thebox.core.sports.ScoreFacts? = null,
)

enum class PickerKind {
    AUDIO, SUBTITLES, ASPECT, VIDEO_DECODER,
    // M5 (§2.12 item 4; append-only)
    SERVER,
    /** (Score bug commit 0, docs/design/m6-score-bug.md §6; append-only) The "Score bug" sheet. */
    SCORE_BUG,
    /**
     * (Score bug commit 0, §6 row 3; append-only) The sheet's nested delay list (`PickerChoice.ScoreDelayChoice` rows, a
     * check on the current one); Back returns to the SCORE_BUG sheet.
     */
    SCORE_DELAY,
}

sealed interface PickerChoice {
    data class Audio(val track: AudioChoice) : PickerChoice
    data class Text(val track: TextChoice) : PickerChoice
    data object TextOff : PickerChoice
    data class Aspect(val mode: AspectMode) : PickerChoice
    /** null = Auto (follow the global setting). */
    data class Decoder(val mode: VideoDecoderMode?) : PickerChoice
    /** (M5 integration 2026-10-16, E23 / WP-C amendment) Base URL [index] of the tune, labelled `host[:port]`. */
    data class Server(val index: Int, val label: String) : PickerChoice
    /**
     * (U-S12, code review 2026-10-16; additive) "Back to Auto": the last AUDIO row while [TrackMenu.audioOutputForced];
     * OK runs [PlayerEffects.audioOutputAuto].
     */
    data object AudioOutputAuto : PickerChoice

    // (Score bug commit 0, docs/design/m6-score-bug.md §6, U-S4, U-N9; append-only) The SCORE_BUG sheet's rows, in this
    // order: the followed game, Corner, Delay, Stop following, the Today divider, the other games, then Loading or the
    // failure row. [selectable] false rows are skipped by focus (WP-4b's PickerSheet).
    /** A game: "CGY 0–2 EDM · 2nd 10:22" / "CGY – EDM · 7:00 PM"; [followed] draws the ✓ and is the first row. */
    data class ScoreGame(val game: app.thebox.core.sports.GameSnapshot, val followed: Boolean) : PickerChoice
    /** "Corner · Top right": OK enters place mode. */
    data class ScoreCornerRow(val corner: app.thebox.core.sports.ScoreCorner) : PickerChoice
    /** "Delay · Off": OK opens the SCORE_DELAY list. */
    data class ScoreDelayRow(val delaySec: Int) : PickerChoice
    /** "Stop following", while a game is followed. */
    data object ScoreStop : PickerChoice
    /** Non-selectable "Today" divider. */
    data object ScoreTodayDivider : PickerChoice
    /**
     * (Fix pass, U-B1) Non-selectable divider for a board that is not today's ([dayKey] "2026-11-04"): its date, never
     * "Today". Shown only until the picker's own `/score/now` answer replaces that board.
     */
    data class ScoreDayDivider(val dayKey: String) : PickerChoice
    /** Non-selectable "Loading games…". */
    data object ScoreLoading : PickerChoice
    /** The §5.4 failure text; OK retries ([PlayerEffects.retryScores]). */
    data class ScoreFailed(val kind: app.thebox.core.sports.FailKind) : PickerChoice
    /** A SCORE_DELAY row (one of `SportsConstants.DELAY_STEPS_S`; 0 = "Off"). */
    data class ScoreDelayChoice(val delaySec: Int) : PickerChoice
    /**
     * (NFL commit 0, docs/design/m6-nfl.md §7.3; append-only) "League · NFL", the second row (after the followed game):
     * OK toggles the shown league ([league] is the current one); the board reloads under a Loading row.
     */
    data class ScoreLeagueRow(val league: app.thebox.core.sports.League) : PickerChoice
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
enum class ErrorButton {
    RETRY, PREVIOUS_CHANNEL, WATCH_LIVE, TRY_OTHER_CATCHUP_FORMAT, TRY_SOFTWARE_DECODER, AUDIO_OPTIONS, STOP, GUIDE,
    // M5 (docs/design/m5-setup-settings.md §2.12 item 4, §3.14; append-only)
    OPEN_SETTINGS, USE_SECURE_DNS, REFRESH_PLAYLIST, OPEN_EXTERNAL,
    /** (M5 integration 2026-10-16, B2 amendment; WP-C's one-tap) `playback.audioOutput = AUTO`, applied by a re-prepare. */
    BACK_TO_AUTO,
}

data class ErrorCardState(
    val error: PlaybackError,
    val channel: GuideChannel?,
    val buttons: List<ErrorButton>,
    val focused: Int,
    /** The first step that has no button in M4 (shown as the next-step line); null when every step is a button. */
    val textStep: NextStep?,
    /** Catch-up probing ended without a working format (ArchiveStatus.FAILED). */
    val archiveFailed: Boolean,
    /** (M5, §2.12 item 4, §3.14) The OPEN_SETTINGS target; opened detached (S2). */
    val route: app.thebox.core.settings.SettingsRoute? = null,
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
    /** (Fix pass, product, 2026-10-19: UX review §7, S4; additive) Non-null while a LOCAL time-shift is paused. */
    val paused: PausedUi? = null,
    /** (Score bug commit 0, docs/design/m6-score-bug.md §6, U-B1; additive) Non-null while [PlayerLayer.PLACE_SCORE]. */
    val placeScore: app.thebox.core.sports.ScorePlaceState? = null,
)

/** Why FULL was entered (decides the auto-shown bar, §3.2.1). */
enum class EnterReason { USER, ARCHIVE, REMINDER, SEARCH, LAUNCH }

/**
 * Toasts the machine raises synchronously; effects toast what they learn asynchronously (M3 §2.13 item 9).
 *
 * **Argument convention** (`PlayerEffects.toast(kind, arg)`; fix pass, product, 2026-10-19, UX review §7): the machine
 * passes raw values and the host turns them into words. Times (a clock time) and lengths ("N min") are decimal **ms**
 * (a time in the display frame, i.e. with the EPG shift added); a playlist name is passed as is and "" means unnamed
 * ("your playlist"); several values are joined by `PlayerText.ARG_SEP` (U+001F) in the order the kind's KDoc gives.
 * A kind without values gets null. A typed `ToastArg` may replace this later.
 */
enum class PlayerToast {
    NO_CATCHUP, NO_PREVIOUS, NO_FAVOURITES, NO_AUDIO_CHOICE, NO_SUBTITLES, ARCHIVE_NOT_READY, BACK_TO_LIVE,
    ADDED_FAVOURITE, REMOVED_FAVOURITE, CHANNEL_HIDDEN,
    /** arg = ArchiveFailure.name. */
    CATCHUP_FAILED,
    /**
     * (U-S10, code review 2026-10-16; append-only) Lock from the channel list: "Channel locked" with a PIN, the B3 "not
     * protected" wording without one (the texts of `GuideToast.CHANNEL_LOCKED` / `LOCKED_NOT_PROTECTED`).
     */
    CHANNEL_LOCKED, LOCKED_NOT_PROTECTED,
    // (M6 time-shift commit 0, docs/design/m6-timeshift.md §13, §16.3; append-only). Texts are §13's; where a text has
    // values (a time, a size, "N min") the effects pass them in `arg` (several joined by PlayerText.ARG_SEP).
    PAUSE_NO_REWIND_HLS, PAUSE_NO_REWIND_OFF, PAUSE_NO_REWIND_SPACE, BACK_TO_LIVE_UNBUFFERED,
    REWIND_NOTHING_YET, REWIND_START, GAP_SKIPPED_PAUSED, GAP_SKIPPED_STREAM, REWIND_STOPPED_STORAGE, REWIND_STOPPED_SLOW,
    REWIND_LIMITED_TODAY, LEFT_APP_CLEARED, ZAP_CONFIRM, ZAP_CONFIRM_NUMBER, SAVING_STOPPED_CONNECTION, JUMPED_TO_LIVE_RESTART, PAUSE_TIP,
    // Values per kind (ms unless said): PAUSE_NO_REWIND_HLS the playlist name (null = M3U); PAUSE_NO_REWIND_SPACE the free
    // bytes (null = the host measures); REWIND_NOTHING_YET the window length; REWIND_START the oldest saved time, then
    // (optional) the window length when that time is not when the viewer tuned in (S8); GAP_SKIPPED_* the gap length,
    // then the landing time; ZAP_CONFIRM / ZAP_CONFIRM_NUMBER the saved length.
    /**
     * (Fix pass, product, 2026-10-19, UX review S7; append-only) Pause or RW on a live stream that is not TS (and not
     * HLS): it cannot be saved, so Play returns to live.
     */
    PAUSE_NO_REWIND_FORMAT,
    /** (Fix pass, product, UX review S7; append-only) Pause while the buffer is off after a storage failure. */
    PAUSE_NO_REWIND_STORAGE,
    /**
     * (Storage locations commit 0, docs/design/m6-storage-locations.md §10; append-only) The cycle saves on this device
     * instead of the chosen place. arg = `"<StorageKind>␟<LocationFallback>␟<name>"` (U+001F).
     */
    SAVE_LOCATION_FALLBACK,
    /** (Storage locations commit 0, §4.6, §10; append-only) Resume after a pause: the saved file could not be read. */
    PAUSE_RESUME_LOST_STORAGE,
    // (Storage locations §10) REWIND_STOPPED_STORAGE, REWIND_STOPPED_SLOW, PAUSE_NO_REWIND_STORAGE and the two kinds above
    // take arg = "<StorageKind>␟<StorageFailure|>␟<name>␟<jumped 1|>"; a null arg means today's internal text.
}

/** (Fix pass, product, 2026-10-19) Joins the values of one toast's `arg` (see [PlayerToast]); `PlayerText.ARG_SEP`. */
const val PLAYER_TOAST_ARG_SEP: Char = '\u001F'

/**
 * (Q-U3, save-location closure fix pass 2026-10-28) The `arg` of a storage toast (§10), encoded in one place:
 * `"<StorageKind>␟<second>␟<name>␟<jumped 1|>"` (U+001F, [PLAYER_TOAST_ARG_SEP]). [second] is the LocationFallback name
 * for SAVE_LOCATION_FALLBACK and the StorageFailure name (or "") for the REWIND_STOPPED_*, PAUSE_NO_REWIND_STORAGE and
 * PAUSE_RESUME_LOST_STORAGE kinds; [name] is the chosen place's name ("" = unnamed).
 */
data class StorageToastArg(
    val kind: app.thebox.core.storage.StorageKind,
    val second: String = "",
    val name: String = "",
    val jumped: Boolean = false,
) {
    fun encode(): String = listOf(kind.name, second, name, if (jumped) "1" else "").joinToString(PLAYER_TOAST_ARG_SEP.toString())

    companion object {
        /** The arg of [encode]; null for a null or blank arg or an unknown kind. INTERNAL decodes (callers decide). */
        fun decode(arg: String?): StorageToastArg? {
            if (arg.isNullOrEmpty()) return null
            val parts = arg.split(PLAYER_TOAST_ARG_SEP)
            val kind = app.thebox.core.storage.StorageKind.entries.firstOrNull { it.name == parts[0] } ?: return null
            return StorageToastArg(kind, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" }, parts.getOrElse(3) { "" } == "1")
        }
    }
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
    /**
     * (code review 2026-10-16, U-S7 = N17; contract amendment) An archive start the effects launched returned
     * `ArchiveStart.Failed` synchronously. The host has already toasted CATCHUP_FAILED; the machine returns the info bar's
     * focus to now when the start came from the bar (§3.2.2).
     */
    data class ArchiveStartFailed(val reason: app.thebox.core.catchup.archive.ArchiveFailure) : PlayerEvent

    // M5 commit 0 (docs/design/m5-setup-settings.md §1.3a, §3.9.2; additive, WP-C fills the machine's branches)
    /** ParentalSession.state, for locked entries in the channel list and the lock menu items. */
    data class Parental(val state: app.thebox.core.parental.ParentalState) : PlayerEvent
    /** The answer to a PIN the player machine asked for through its [PlayerStateMachine.pinGate]. */
    data class PinResult(val purpose: app.thebox.core.parental.PinPurpose, val granted: Boolean) : PlayerEvent
    // (U-L18, code review 2026-10-16) `KeyHints` removed: the player machine stored it and never read it.

    // M6 time-shift commit 0 (docs/design/m6-timeshift.md §11.1, §16.3; additive, WP-C fills the machine's branches)
    /** `ArchivePlayback.localBuffer` (P frame, ≤ 1 Hz); null = no buffer for the tuned channel. */
    data class Buffer(val info: LocalBufferInfo?) : PlayerEvent
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
    /**
     * EPG-anchored start of [info] (raw programme times; the builder subtracts the correction). (code review 2026-10-16,
     * P-B1) [timeShift] true from the at-live scrub bar (a chip or PREV_PROGRAMME): the session is a time-shift that
     * continues past the programme's end, like start over. Never converted into a `rewindLive` distance.
     */
    fun playProgramme(channel: GuideChannel, info: ProgrammeInfo, timeShift: Boolean = false)
    fun tryNextCatchupFormat()
    /** (reviewed 2026-10-15, S7) Info bar OK on a future programme: M3 reminder store, toasts REMINDER_SET / REMINDER_CANCELLED. */
    fun toggleReminder(channel: GuideChannel, info: ProgrammeInfo)
    /** (owner ruling 2026-10-15) Quick bar Settings: the host dispatches GuideEvent.OpenSettings to the guide machine. */
    fun openSettings()
    // M5 commit 0 (docs/design/m5-setup-settings.md §1.3a, §3.14, §3.9). Defaults keep test doubles compiling;
    // PlayerEffectsImpl delegates each to the interface the owning WP implements.
    /** Error-card OPEN_SETTINGS (detached, S2) and other routes into Settings from FULL. */
    fun openSettings(route: app.thebox.core.settings.SettingsRoute) = Unit
    /** Error-card buttons that act outside the player (USE_SECURE_DNS, REFRESH_PLAYLIST, OPEN_EXTERNAL; §3.14). */
    fun errorAction(button: ErrorButton, card: ErrorCardState) = Unit
    /** Channel-list menu LOCK_CHANNEL (§3.9.1). */
    fun setChannelLocked(channel: GuideChannel, locked: Boolean) = Unit
    /** (M5 integration 2026-10-16, E23 / WP-C amendment) The Sources picker: play the current tune from base [index]. */
    fun selectServer(index: Int) = Unit
    /**
     * (U-S12, code review 2026-10-16; additive) The AUDIO picker's "Back to Auto": `playback.audioOutput = AUTO` and a
     * re-prepare, the same path as the AUDIO_OUTPUT_FAILED card's [ErrorButton.BACK_TO_AUTO].
     */
    fun audioOutputAuto() = Unit
    /**
     * (Storage locations commit 0, docs/design/m6-storage-locations.md §5.1, §10; additive) Whether SAVE_LOCATION_FALLBACK
     * for ([locationId], [reason]) is due now: once per process per (location, reason); SIGN_IN_NEEDED in the first
     * SIGN_IN_TOAST_PROCESSES processes only; never NOT_READY_YET. `:app`'s FallbackToastPolicy answers (WP-S4).
     */
    fun fallbackToastDue(locationId: String, reason: app.thebox.core.storage.LocationFallback): Boolean = false
    // (Score bug commit 0, docs/design/m6-score-bug.md §6, A-B2; additive) PlayerViewModel maps these to the
    // ScoreRepository and the `sports` settings domain.
    /** Follow [id] (replacing any current follow; resolved against the day board), or stop following (null). */
    fun followGame(id: app.thebox.core.sports.GameId?) = Unit
    /** Place mode's OK / Back: persist the corner. */
    fun setScoreCorner(c: app.thebox.core.sports.ScoreCorner) = Unit
    fun setScoreDelay(sec: Int) = Unit
    /** The SCORE_BUG sheet opened / closed: demand `SCORE_PICKER` (§2.3). */
    fun scorePicker(open: Boolean) = Unit
    /** OK on the picker's failure row. */
    fun retryScores() = Unit
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

    /**
     * (M5 commit 0, docs/design/m5-setup-settings.md §0.9, §2.6 `PinGate`) Injected by the guide machine: the player
     * asks for a PIN through it and gets the answer as [PlayerEvent.PinResult].
     */
    var pinGate: app.thebox.core.parental.PinGate?
        get() = null
        set(@Suppress("UNUSED_PARAMETER") value) = Unit

    /**
     * (M6 time-shift commit 0, docs/design/m6-timeshift.md §11.1, lead ruling 6) True when a single-key zap now (CH±,
     * Last, a digit commit) needs a second press because the viewer has been paused ≥ ZAP_CONFIRM_AFTER_PAUSE_MS. The
     * guide machine's number entry asks it before committing a digit zap in FULL. Default: never.
     */
    fun zapNeedsConfirm(nowMs: Long): Boolean = false

    /**
     * (Score bug commit 0, docs/design/m6-score-bug.md §6, A-B2; append-only) The followed game, the day list and the
     * `sports` prefs, from PlayerViewModel whenever one changes (main). Default: ignored (WP-4b's machine reads it).
     */
    fun scores(facts: app.thebox.core.sports.ScoreFacts) = Unit
}

fun interface PlayerStateMachineFactory {
    /** [listRows]: the channel list's own GuideRowsCache (main-only reads). */
    fun create(effects: PlayerEffects, listRows: GuideRows, nowMs: () -> Long): PlayerStateMachine
}
