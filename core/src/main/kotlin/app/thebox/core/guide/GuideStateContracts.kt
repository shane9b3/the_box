// Contract (M3 commit 0): docs/design/m3-guide.md §2.5. Verbatim; changes go through the lead.
package app.thebox.core.guide

import app.thebox.core.input.BoxAction
import kotlinx.coroutines.flow.StateFlow

enum class ScreenMode { GUIDE, FULL, SEARCH, SETTINGS }
/** (owner ruling 2026-11-01) PANEL is kept for the append-only contract but never entered: the panel has no buttons. */
enum class Zone { TABS, PANEL, HEADER, GRID }
/**
 * (reviewed 2026-10-14, S11/E21) MORE opens the context menu, for remotes without long-press or Menu; always last.
 * (owner ruling 2026-11-01) Retired: the panel draws no buttons and [PanelState.buttons] is always empty; kept for the
 * append-only contract (docs/decisions/2026-11-01-guide-panel-and-mini-player.md).
 */
enum class PanelButton { WATCHING, WATCH_NOW, WATCH, FROM_START, REMIND, REMINDER_SET, WATCH_CHANNEL_NOW, MORE }
/** (reviewed 2026-10-14, E22) Rows at the top of the Groups panel. */
enum class GroupsShortcut { SEARCH, SETTINGS }
enum class HeaderChip { GROUP, NOW, DAY }
enum class MenuItem {
    WATCH, WATCH_FROM_START, REMIND, CANCEL_REMINDER, ADD_FAVOURITE, REMOVE_FAVOURITE, HIDE_CHANNEL,
    // M5 (docs/design/m5-setup-settings.md §2.12 item 2; append-only)
    LOCK_CHANNEL, UNLOCK_CHANNEL, FIX_GUIDE_DATA, CHANNEL_OPTIONS,
}
enum class GroupMenuItem {
    HIDE, MOVE_UP, MOVE_DOWN,
    // M5 (§2.12 item 2; append-only)
    LOCK, UNLOCK,
    /** Opens the channel editor on this group (docs/design/m6-channel-editor.md §2.1; append-only, last in the menu). */
    EDIT,
}

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
    /** (owner ruling 2026-11-01) Always empty: the panel has no buttons; OK and the context menu carry the actions. */
    val buttons: List<PanelButton>,
    val noInfo: Boolean,
    /** Poster art beside the text (owner request 2026-10-18): the programme's XMLTV icon, with the description. */
    val imageUrl: String? = null,
)

data class DayItem(val day: Int, val label: String, val enabled: Boolean, val primeTime: Boolean = false)

sealed interface Overlay {
    /** [focused] indexes [shortcuts] first, then [entries]. */
    /**
     * [viaBack] (decision 2026-10-19): opened by the Back ladder from home, so the next Back continues outward to the top
     * bar (Search focused); otherwise Back closes the panel to where it was opened from.
     */
    data class Groups(val shortcuts: List<GroupsShortcut>, val entries: List<GuideSourceEntry>, val focused: Int, val current: Int, val menu: List<GroupMenuItem>?, val menuFocused: Int, val viaBack: Boolean = false) : Overlay
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
    /** True from creation until the startup decision (StartGuide, StartFull's first Tuned, setup, or any key): the shell draws a blank canvas instead of the guide. */
    val booting: Boolean = false,
    val zone: Zone = Zone.GRID,
    val tabFocused: Int = 0,
    val headerChips: List<HeaderChip> = listOf(HeaderChip.GROUP, HeaderChip.DAY),
    val headerFocused: Int = 0,
    /** (owner ruling 2026-11-01) Always 0: PANEL is never entered. */
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
    /** (M4) The archive session (PillRules, §3.2.3). (owner ruling 2026-11-01) The mini player no longer draws its pills. */
    val archive: app.thebox.core.catchup.archive.ArchiveStatus? = null,
    /** (M5, §2.12 item 2, decision §0.9) The PIN prompt: one modal layer drawn above every mode. */
    val pin: app.thebox.core.parental.PinPromptState? = null,
    /** (M5, §2.12 item 2) The update card (S17), in the guide only (never in FULL). */
    val update: app.thebox.core.update.UpdateCardState? = null,
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
    /** (owner ruling 2026-10-18) A looked-up poster for the panel of [token]; ignored once the panel moved on. */
    data class Artwork(val token: Long, val url: String?) : GuideEvent
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
    /**
     * Owner ruling 2026-10-17: the startup decided on the guide (On launch = guide, or nothing to tune). Ends the boot
     * blank ([GuideUiState.booting]); StartFull ends it on the first active Tuned instead, so the guide never flashes
     * before the full-screen player.
     */
    data object StartGuide : GuideEvent
    /** (M4, owner ruling 2026-10-15) Quick bar → Settings: mode SETTINGS; BACK from SETTINGS returns to FULL when opened from it. */
    data object OpenSettings : GuideEvent

    // M5 (docs/design/m5-setup-settings.md §2.12 item 2)
    /** Startup's first-run decision (§3.4.1) and other setup entries: mode SETTINGS on [route] (FirstRun: Back exits). */
    data class OpenSetup(val route: app.thebox.core.settings.SettingsRoute) : GuideEvent
    /**
     * (C-S1 / U-S8, code review 2026-10-16; additive) An error card's Settings route from FULL (§3.2.5, S2): mode
     * SETTINGS with no PIN for the route itself, the settings machine opened on [route] detached, BACK returns to FULL.
     * `SettingsShellLink.openFromFull` sends it instead of the PIN-gated [OpenSettings].
     */
    data class OpenSettingsDetached(val route: app.thebox.core.settings.SettingsRoute) : GuideEvent
    data class Parental(val state: app.thebox.core.parental.ParentalState) : GuideEvent
    /** Answer to [GuideEffects.verifyPin]. */
    data class PinChecked(val token: Long, val check: app.thebox.core.parental.PinCheck) : GuideEvent
    /** Keys bound to each action (EffectiveKeyMap), for the footer hints. */
    data class KeyHints(val hints: Map<BoxAction, List<Int>>) : GuideEvent
    data class Update(val card: app.thebox.core.update.UpdateCardState?) : GuideEvent
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
    // M5 (docs/design/m5-setup-settings.md §2.12 item 2). Defaults keep test doubles compiling; GuideEffectsImpl
    // delegates each to the interface the owning WP implements (§1.3a).
    /** PBKDF2 check on IO; the answer is [GuideEvent.PinChecked]. The effect zeroes [digits] after the check. */
    fun verifyPin(token: Long, digits: CharArray) { digits.fill('\u0000') }
    /** Overload of [openSettings]; the old `openSettings()` = [app.thebox.core.settings.SettingsRoute.Root]. */
    fun openSettings(route: app.thebox.core.settings.SettingsRoute) = Unit
    /** EXIT stops playback (M3 H-L3, M4 §7.1): `controller.stop(USER)`. */
    fun stopPlayback() = Unit
    /** A button of the update card (S17). */
    fun updateAction(action: app.thebox.core.update.UpdateAction) = Unit
    /** (lead amendment, §1.9a) Channel menu Lock / Unlock (`MenuItem.LOCK_CHANNEL` / `UNLOCK_CHANNEL`); WP-C dispatches it. */
    fun setChannelLocked(channel: GuideChannel, locked: Boolean) = Unit
    /** (lead amendment, §1.9a) Group menu Lock / Unlock (`GroupMenuItem.LOCK` / `UNLOCK`); WP-C dispatches it. */
    fun setGroupLocked(entry: GuideSourceEntry, locked: Boolean) = Unit
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
    /** (M5 integration 2026-10-16, WP-C amendment) Lock feedback from the channel and group menus (§3.9.1). */
    CHANNEL_LOCKED, CHANNEL_UNLOCKED, GROUP_LOCKED, GROUP_UNLOCKED,
    /** A lock applied in FILTERED_NO_PIN: it filters now; a PIN protects it (B3). */
    LOCKED_NOT_PROTECTED,
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

    /**
     * (M5, docs/design/m5-setup-settings.md §2.12 item 2, §1.9 item 5) The settings machine SETTINGS delegates to; the
     * host sets it once after create (S11: while it is null in SETTINGS the machine waits; only BACK on FirstRun acts).
     */
    var settings: app.thebox.core.settings.SettingsMachine?
        get() = null
        set(@Suppress("UNUSED_PARAMETER") value) = Unit
    /** (M5, §2.12 item 5, S3) The settings editor is open: the router passes every key except BACK to Compose. */
    val textInputActive: Boolean get() = false
    /**
     * (M5 integration 2026-10-16, WP-C amendment) The PIN layer (decision §0.9) the host routes controller refusals and
     * PIN-store answers to; null when the machine has none (fakes). Replaces the hosts' `machine as? ParentalHost` cast.
     */
    val parentalHost: app.thebox.core.parental.ParentalHost? get() = null

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
