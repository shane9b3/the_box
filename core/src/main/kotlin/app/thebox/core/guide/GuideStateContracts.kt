// Contract (M3 commit 0): docs/design/m3-guide.md §2.5. Verbatim; changes go through the lead.
package app.thebox.core.guide

import app.thebox.core.input.BoxAction
import kotlinx.coroutines.flow.StateFlow

enum class ScreenMode { GUIDE, FULL, SEARCH, SETTINGS }
enum class Zone { TABS, PANEL, HEADER, GRID }
/** (reviewed 2026-10-14, S11/E21) MORE opens the context menu, for remotes without long-press or Menu; always last. */
enum class PanelButton { WATCHING, WATCH_NOW, WATCH, FROM_START, REMIND, REMINDER_SET, WATCH_CHANNEL_NOW, MORE }
/** (reviewed 2026-10-14, E22) Rows at the top of the Groups panel. */
enum class GroupsShortcut { SEARCH, SETTINGS }
enum class HeaderChip { GROUP, NOW, DAY }
enum class MenuItem { WATCH, WATCH_FROM_START, REMIND, CANCEL_REMINDER, ADD_FAVOURITE, REMOVE_FAVOURITE, HIDE_CHANNEL }
enum class GroupMenuItem { HIDE, MOVE_UP, MOVE_DOWN }

data class PanelState(
    val channelNumber: Int?,
    val channelName: String,
    val genre: String?,
    val onNow: Boolean,
    /** "Catch-up · 7 days" when > 0 (product §3.5). */
    val catchupDays: Int,
    val title: String,
    /** "19:30 – 20:00 · 30 min" (prototype timeLabel · durLabel); "Not in catch-up" appended for a past cell without archive. */
    val timeLine: String,
    /** null until 150 ms without a focus change (B2.8). */
    val description: String?,
    val buttons: List<PanelButton>,
    val noInfo: Boolean,
)

data class DayItem(val day: Int, val label: String, val enabled: Boolean, val primeTime: Boolean = false)

sealed interface Overlay {
    /** [focused] indexes [shortcuts] first, then [entries]. */
    data class Groups(val shortcuts: List<GroupsShortcut>, val entries: List<GuideSourceEntry>, val focused: Int, val current: Int, val menu: List<GroupMenuItem>?, val menuFocused: Int) : Overlay
    data class Days(val items: List<DayItem>, val focused: Int) : Overlay
    data class Menu(val items: List<MenuItem>, val focused: Int) : Overlay
    /** Programme sheet for a future cell (product S7b). */
    data class Sheet(val reminderSet: Boolean, val focused: Int) : Overlay
    data class Number(val digits: String, val preview: GuideChannel?, val notFound: Boolean, val cursorDigit: Int) : Overlay
    /** "Press Back again to exit" (BACK_TWICE) or the dialog (ASK). */
    data class Exit(val dialog: Boolean, val focused: Int) : Overlay
}

/** Modal (reviewed 2026-10-14, S2): keys are ignored until [inputFromMs] (shown + REMINDER_CARD_GUARD_MS). */
data class ReminderCardState(val alert: ReminderAlert, val focused: Int, val secondsLeft: Int, val inputFromMs: Long)

data class FooterHint(val keys: String, val label: String)

data class SyncChip(val text: String, val failed: Boolean)

enum class EmptyKind { NO_PLAYLIST, EMPTY_SOURCE, NO_GUIDE_DATA }

data class GuideUiState(
    val mode: ScreenMode = ScreenMode.GUIDE,
    val zone: Zone = Zone.GRID,
    val tabFocused: Int = 0,
    val headerChips: List<HeaderChip> = listOf(HeaderChip.GROUP, HeaderChip.DAY),
    val headerFocused: Int = 0,
    val panelFocused: Int = 0,
    val sourceLabel: String = "",
    val dayLabel: String = "",
    /** −1: now is left of the window, +1: right, 0: visible (NOW chip hidden). */
    val nowDirection: Int = 0,
    val panel: PanelState? = null,
    val overlay: Overlay? = null,
    val reminder: ReminderCardState? = null,
    val tuned: TunedState = TunedState(),
    val tunedNow: ProgrammeInfo? = null,
    val tunedChannel: GuideChannel? = null,
    val footer: List<FooterHint> = emptyList(),
    val sync: SyncChip? = null,
    val empty: EmptyKind? = null,
    /** Group-name card in FULL after a group cycle (decision note accelerator 3). */
    val groupCard: String? = null,
    /** (M4) The archive session, for the mini player's PAUSED / −MM:SS pills (PillRules, §3.2.3). */
    val archive: app.thebox.core.catchup.archive.ArchiveStatus? = null,
)

/** Inputs of the state machine. All delivered on main. */
sealed interface GuideEvent {
    /** [heldMs] = eventTime − downTime of the key (0 for a single press); drives the hold rules of §3.4.7. */
    data class Act(val action: BoxAction, val repeat: Int = 0, val digit: Int = -1, val heldMs: Long = 0) : GuideEvent
    /** Key released after a hold (ends time-step mode and the bubble). */
    data class Release(val action: BoxAction) : GuideEvent
    data class Tick(val nowMs: Long) : GuideEvent
    data class Sources(val entries: List<GuideSourceEntry>) : GuideEvent
    data class Lineup(val lineup: GuideLineup) : GuideEvent
    data class RowsChanged(val version: Long) : GuideEvent
    data class Window(val data: GuideWindowData) : GuideEvent
    data class Generation(val info: app.thebox.core.epg.EpgGenerationInfo?) : GuideEvent
    data class Info(val token: Long, val info: ProgrammeInfo?) : GuideEvent
    data class TunedNow(val info: ProgrammeInfo?) : GuideEvent
    data class Tuned(val state: TunedState, val channel: GuideChannel?) : GuideEvent
    data class Reminders(val index: ReminderIndex) : GuideEvent
    data class Alert(val alert: ReminderAlert?) : GuideEvent
    data class Favourite(val token: Long, val isFavourite: Boolean) : GuideEvent
    data class Number(val token: Long, val channel: GuideChannel?, val lineupIndex: Int) : GuideEvent
    data class Settings(val settings: GuideSettings, val device: DeviceClass) : GuideEvent
    data class Sync(val chip: SyncChip?) : GuideEvent
    data class KeysSeen(val keyCodes: Set<Int>) : GuideEvent
    data class Metrics(val metrics: GuideMetrics) : GuideEvent
    /**
     * (reviewed 2026-10-14, B7) SEARCH closed. [channel] non-null: focus it in the grid; [tuned]: it was tuned from
     * search, go FULL once it plays. (amended 2026-10-14, §2.13 item 11) [archive]: it was a catch-up launch, FULL
     * follows the first archive Tuned only (a failed launch stays in the guide).
     */
    data class SearchClosed(val channel: GuideChannel?, val tuned: Boolean, val archive: Boolean = false) : GuideEvent
    data class DataWindow(val window: GuideDataWindow) : GuideEvent
    /** Missed reminders found on start (N5): a toast with the count. */
    data class MissedReminders(val count: Int) : GuideEvent
    /** (M4) ArchivePlayback.status: suspect ↺ and the pending catch-up FULL follow phase FAILED, not raw errors (§0.6). */
    data class Archive(val status: app.thebox.core.catchup.archive.ArchiveStatus) : GuideEvent
    /** (M4) Player settings the guide machine reads (number-entry wait, Back in player). */
    data class Player(val settings: app.thebox.core.full.PlayerSettings) : GuideEvent
    /** (M4) "On launch: Full screen last channel": go FULL on the first active Tuned (the existing pendingFull path). */
    data object StartFull : GuideEvent
    /** (M4, owner ruling 2026-10-15) Quick bar → Settings: mode SETTINGS; BACK from SETTINGS returns to FULL when opened from it. */
    data object OpenSettings : GuideEvent
}

/** Side effects requested by the state machine; implemented by :ui's GuideEffectsImpl (WP-C). Main-safe, non-blocking. */
interface GuideEffects {
    fun loadLineup(source: GuideSource)
    fun ensureRows(from: Int, to: Int)
    fun ensureWindow(topRow: Int, windowStartMin: Int, holdDirection: Int = 0)
    fun loadInfo(token: Long, channel: GuideChannel, rawStartMin: Int)
    fun loadTunedNow(channel: GuideChannel, atMin: Int)
    fun tune(channel: GuideChannel, lineup: GuideLineup)
    /** Catch-up from the programme's start (§3.13.6): "Watch" on a finished programme and "From start" on the current one. */
    fun playArchive(channel: GuideChannel, info: ProgrammeInfo)
    fun setFullScreen(full: Boolean)
    fun zap(delta: Int, isRepeat: Boolean)
    fun previousChannel()
    fun retry()
    fun setReminder(channel: GuideChannel, info: ProgrammeInfo)
    fun cancelReminder(channel: GuideChannel, rawStartMin: Int)
    /** (code review 2026-10-14, H-S7) The alert the card showed; the effect must not re-read the current alert. */
    fun answerReminder(alert: ReminderAlert, accept: Boolean)
    fun queryFavourite(token: Long, channel: GuideChannel)
    fun setFavourite(channel: GuideChannel, on: Boolean)
    fun hideChannel(channel: GuideChannel)
    fun groupAction(entry: GuideSourceEntry, action: GroupAction)
    fun lookupNumber(token: Long, number: Int)
    fun persistSource(source: GuideSource)
    fun openSearch()
    fun openSettings()
    fun exitApp()
    fun toast(kind: GuideToast, arg: String? = null)
    /** TalkBack/VoiceView announcement (debounced by the caller). */
    fun announce(text: String)
}

/** (reviewed 2026-10-14, S13) */
enum class GuideToast {
    REMINDER_SET_FIRST_TIME, REMINDER_SET, REMINDER_CANCELLED, REMINDER_CHANNEL_GONE,
    ADDED_FAVOURITE, REMOVED_FAVOURITE, NO_FAVOURITES, CHANNEL_HIDDEN, NO_CATCHUP,
    /** arg = CatchupFailure.name; ErrorText maps it to reason + next step. */
    CATCHUP_FAILED,
    /** arg = count (N5). */
    REMINDERS_MISSED,
    EXIT_HINT,
}

interface GuideStateMachine {
    val ui: StateFlow<GuideUiState>
    val frame: GuideFrame
    /** Bumped on every change of [frame]; the grid host reads it in its draw lambda. */
    val frameVersion: StateFlow<Long>
    fun dispatch(event: GuideEvent)

    /**
     * (code review 2026-10-14, H-S4) False when the machine would ignore [action] in its current state and the key
     * should go on to the framework. The key router asks it on a key's first DOWN. (M4, docs/design/m4-player-ux.md
     * §2.9) FULL handles Play/Pause and FF/RW itself; the media session still receives Assistant/phone transport
     * commands through `SessionPlayer` (§8 Q7).
     */
    fun handles(action: BoxAction): Boolean = true

    /**
     * (amended 2026-10-14, §2.13 item 4) Wall-clock ms of the next timer the machine waits for (panel description
     * debounce, number entry, exit hint, group card, reminder card); `Long.MAX_VALUE` = none. The host posts a
     * [GuideEvent.Tick] at that time (and at every minute boundary).
     */
    val nextDeadlineMs: StateFlow<Long> get() = NO_DEADLINE

    /**
     * (amended 2026-10-14, §2.13 item 10) The persisted last source (`GuideSessionStore.lastSource()`, read on IO by
     * the host before it forwards the first [GuideEvent.Sources]). The machine picks the startup source itself
     * (§3.8.4); the host never loads one.
     */
    var lastSourceHint: GuideSource?
        get() = null
        set(@Suppress("UNUSED_PARAMETER") value) = Unit

    /** (M4) The player machine FULL delegates to (§3.1.1); the host sets it once after create. */
    var full: app.thebox.core.full.PlayerStateMachine?
        get() = null
        set(@Suppress("UNUSED_PARAMETER") value) = Unit
    /** (M4, reviewed 2026-10-15, S5/F9) A player layer is open or archive video is on screen: D-pad LEFT/RIGHT act on DOWN with auto-repeat (their long bindings are ignored). */
    val horizontalRepeat: Boolean get() = false

    companion object {
        val NO_DEADLINE: StateFlow<Long> = kotlinx.coroutines.flow.MutableStateFlow(Long.MAX_VALUE)
    }
}

fun interface GuideStateMachineFactory {
    fun create(effects: GuideEffects, navigator: GuideNavigator, labels: TimeLabels, renderer: GuideRenderer, nowMs: () -> Long): GuideStateMachine
}

/** Number entry logic (§3.9.4), pure. */
interface NumberEntry {
    val digits: String
    fun digit(d: Int, nowMs: Long): Boolean
    /** D-pad entry (5-key path): Up/Down change the digit under the cursor, Right moves the cursor. */
    fun adjust(delta: Int)
    fun advance()
    /** The number to commit, or null when empty. */
    fun commitValue(): Int?
    /** True when the auto-commit deadline passed. */
    fun due(nowMs: Long): Boolean
    fun clear()
    /** (M4) The auto-commit wait (PlayerSettings.numberEntryWaitMs); default NUMBER_COMMIT_MS. */
    fun setCommitAfterMs(ms: Long) = Unit
}
