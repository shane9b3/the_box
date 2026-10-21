package app.thebox.core.editor

import app.thebox.core.guide.FooterHint
import app.thebox.core.guide.GuideEvent
import kotlinx.coroutines.flow.StateFlow

/*
 * The channel editor (docs/design/m6-channel-editor.md §3.3; owner request 2026-10-18 "a dedicated way to edit and hide
 * groups and channels … like a full IPTV editor"). Commit 0 lands these contracts; `ChannelEditorMachineImpl` (WP-A),
 * `ChannelEditorRepositoryImpl` (WP-B) and the screen (WP-C) follow. Everything here is toolkit-agnostic and main-thread.
 */

enum class EditorPane { GROUPS, CHANNELS }
enum class EditorFilter { ALL, SHOWN, HIDDEN }
enum class ChannelSort { NAME_AZ, NAME_ZA, NUMBER, PROVIDER }
enum class GroupSort { NAME_AZ, PROVIDER }

/** What the channels pane lists, and the fixed rows at the top of the Groups pane. */
sealed interface EditorList {
    data object All : EditorList
    data class Find(val query: String) : EditorList
    data class Group(val groupKey: String) : EditorList
}

/** Ticks: transient UI state, scoped to the pane's current (list, filter); changing either clears them. */
sealed interface Selection {
    data object None : Selection
    /** Explicit ticks: stable key (channels) or group key (groups) → its list index when ticked (block order, §2.3 E5). */
    data class Keys(val keys: Map<String, Int>) : Selection
    /** "Select all in this list" minus [except]. Not liftable (Reorder disabled, §2.6). */
    data class AllExcept(val except: Set<String>) : Selection
}

data class EditorGroupRow(
    val groupKey: String, val name: String, val providerName: String,
    val channels: Int, val hiddenChannels: Int,
    val hidden: Boolean, val ruleHidden: Boolean, val locked: Boolean, val adult: Boolean, val notProtected: Boolean,
    val renamed: Boolean, val customOrder: Boolean,
)

data class EditorChannelRow(
    val stableKey: String, val name: String, val providerName: String,
    /** The number the guide shows (custom, else the playlist's numbering mode); [customNumber] non-null when set here. */
    val number: Int?, val customNumber: Int?, val logoUrl: String?,
    val hidden: Boolean, val ruleHidden: Boolean, val rulePattern: String?,
    val locked: Boolean, val lockedByGroup: String?,
    /** In the default Favorites list (FavouritesRepository.defaultList()). */
    val favorite: Boolean, val movedFrom: String?, val renamed: Boolean,
)

/** A paged window over an ordered list (§4): [count] rows, pages of [PAGE] loaded around the cursor. */
data class EditorWindow<T>(val version: Long, val count: Int, val hidden: Int, val pages: Map<Int, List<T>>, val cursor: Int, val top: Int) {
    companion object {
        const val PAGE = 32
        /** Pages kept per pane; the rest are evicted (§4 item 2). */
        const val PAGES_KEPT = 6
    }
}

/**
 * The Groups pane: [head] fixed rows (All, then Find when present) followed by the paged real groups.
 * Pane index i < head.size is head[i]; otherwise group index i − head.size in [groups]. Cursor, ticks and move-mode
 * clamps use the pane index; only group rows are tickable and movable.
 */
data class GroupsPane(val head: List<EditorList>, val groups: EditorWindow<EditorGroupRow>?, val cursor: Int, val top: Int)

enum class EditorAction {
    HIDE, SHOW, MOVE_TO_GROUP, REORDER, ADD_FAVORITE, REMOVE_FAVORITE, LOCK, UNLOCK, RENAME, SET_NUMBER, CLEAR_NUMBER,
    SELECT_ALL, FIND, FILTER, SORT, RESET, EDIT_CHANNELS, DROP_RESULTS,
}
data class MenuLine(val action: EditorAction, val count: Int?, val enabled: Boolean, val reason: EditorText?)

/** [lines] empty while [loading] (the summary is on its way); the card shows a "…" title meanwhile. */
data class EditorMenu(val pane: EditorPane, val title: String, val lines: List<MenuLine>, val focused: Int, val loading: Boolean)
/** Shares the Groups pane's group window (same version, same cache); only its own cursor. */
data class GroupPicker(val cursor: Int, val top: Int, val moving: Int, val fromGroupKey: String?)
sealed interface Lifted {
    data class Channels(val rows: List<EditorChannelRow>) : Lifted
    data class Groups(val rows: List<EditorGroupRow>) : Lifted
}
data class EditorMove(val pane: EditorPane, val lifted: Lifted, val fromIndex: Int, val index: Int)
data class EditorConfirm(val kind: ConfirmKind, val args: List<String>, val pending: EditorWrite, val focused: Int = 1)
enum class ConfirmKind { SORT_REPLACES_ORDER, RESET_CHANNELS, RESET_GROUPS, PROVIDER_ORDER }
data class EditorChoice(val kind: ChoiceKind, val focused: Int)
enum class ChoiceKind { FILTER, CHANNEL_SORT, GROUP_SORT }

/** The action menu's facts over a target, counted on IO with the write's own predicate. */
data class SelectionSummary(val total: Int, val hidden: Int, val ruleHidden: Int, val locked: Int, val lockedByGroup: Int, val favorite: Int, val fromLockedGroup: Int)

data class ChannelEditorUiState(
    val playlistId: Long, val playlistName: String,
    val pane: EditorPane = EditorPane.GROUPS,
    /** Focused toolbar chip index; null = rows. */
    val toolbar: Int? = null,
    val groups: GroupsPane,
    /** The Groups toolbar's "Hidden groups: Shown / Not shown" chip. */
    val showHiddenGroups: Boolean = true,
    val groupSelection: Selection = Selection.None,
    val list: EditorList, val filter: EditorFilter = EditorFilter.ALL,
    /** null = loading. */
    val channels: EditorWindow<EditorChannelRow>?,
    val channelSelection: Selection = Selection.None,
    /** E7: the list's group is locked and the session is locked; the pane shows E_LOCKED only. */
    val lockedList: Boolean = false,
    /** > 0: the "Locked channels are not shown" note row heads the list. */
    val lockedOut: Int = 0,
    val menu: EditorMenu? = null, val picker: GroupPicker? = null, val move: EditorMove? = null,
    val confirm: EditorConfirm? = null, val choice: EditorChoice? = null,
    /** Number jump in progress. */
    val digits: String? = null,
    /** FIFO length (0 = idle); the title shows "Saving…" while > 0. */
    val pendingWrites: Int = 0,
    val notice: String? = null,
    /** The channels pane's empty or error text (§5.2); E_LOAD only when the channels list itself failed. */
    val empty: EditorText? = null,
    /** E_PLAYLIST_GONE: only BACK works. */
    val gone: Boolean = false,
    val footer: List<FooterHint> = emptyList(),
    /** (review S1, additive) The Groups list failed to load: the Groups pane shows E_LOAD under its head rows; OK retries. */
    val groupsFailed: Boolean = false,
)

sealed interface ChannelEditorEvent {
    data class Act(val action: GuideEvent.Act) : ChannelEditorEvent
    data class Groups(val version: Long, val count: Int, val hidden: Int, val cursorIndex: Int?) : ChannelEditorEvent
    /** Rows of the group window (the pane and the picker share it). */
    data class GroupRows(val version: Long, val from: Int, val rows: List<EditorGroupRow>) : ChannelEditorEvent
    data class Channels(
        val list: EditorList, val filter: EditorFilter, val version: Long,
        val count: Int, val hidden: Int, val lockedOut: Int, val listLocked: Boolean,
        /** The index of the key the machine asked to keep (after a sort, move or reload), found on IO; null = keep the index. */
        val cursorIndex: Int?,
    ) : ChannelEditorEvent
    data class ChannelRows(val version: Long, val from: Int, val rows: List<EditorChannelRow?>) : ChannelEditorEvent
    data class Summary(val token: Long, val summary: SelectionSummary) : ChannelEditorEvent
    /** The summary for the open menu could not be counted (review S4): the machine closes that menu and says so; stale tokens are ignored. */
    data class SummaryFailed(val token: Long) : ChannelEditorEvent
    data class Located(val number: Int, val index: Int?) : ChannelEditorEvent
    data class Written(val write: EditorWrite, val result: WriteResult) : ChannelEditorEvent
    data class LoadFailed(val what: EditorPane) : ChannelEditorEvent
    data object PlaylistGone : ChannelEditorEvent
    /**
     * The library changed outside the machine (the repository's change feed, review S2): the machine reloads both panes
     * exactly as after [Written], keeping the focused keys, so the view model never issues a competing load of its own.
     */
    data object Changed : ChannelEditorEvent
    data class Access(val pinSet: Boolean, val unlocked: Boolean) : ChannelEditorEvent
    data class PinResult(val granted: Boolean) : ChannelEditorEvent
    data class TextResult(val text: String?) : ChannelEditorEvent
    data class NumberResult(val value: Int?) : ChannelEditorEvent
    data class Tick(val nowMs: Long) : ChannelEditorEvent
}

/** What a bulk write applies to. Keys are stable keys; AllOf re-runs the list query (same access) inside the write. */
sealed interface ChannelTarget {
    data class Keys(val keys: Set<String>) : ChannelTarget
    data class AllOf(val list: EditorList, val filter: EditorFilter, val except: Set<String>) : ChannelTarget
}
sealed interface GroupTarget {
    data class Keys(val keys: Set<String>) : GroupTarget
    data class AllOf(val showHidden: Boolean, val except: Set<String>) : GroupTarget
}

sealed interface EditorWrite {
    val playlistId: Long
    data class HideChannels(override val playlistId: Long, val target: ChannelTarget, val hidden: Boolean) : EditorWrite
    data class HideGroups(override val playlistId: Long, val target: GroupTarget, val hidden: Boolean) : EditorWrite
    data class LockChannels(override val playlistId: Long, val target: ChannelTarget, val locked: Boolean) : EditorWrite
    data class LockGroups(override val playlistId: Long, val target: GroupTarget, val locked: Boolean) : EditorWrite
    data class MoveChannels(override val playlistId: Long, val target: ChannelTarget, val toGroupKey: String) : EditorWrite
    data class ReorderChannels(override val playlistId: Long, val groupKey: String, val block: List<String>, val toIndex: Int) : EditorWrite
    /** [toIndex] is a group index in the pane's list: every non-missing group when [showHidden], else the shown ones only. */
    data class ReorderGroups(override val playlistId: Long, val block: List<String>, val toIndex: Int, val showHidden: Boolean = true) : EditorWrite
    data class SortChannels(override val playlistId: Long, val groupKey: String, val sort: ChannelSort) : EditorWrite
    data class SortGroups(override val playlistId: Long, val sort: GroupSort) : EditorWrite
    data class RenameChannel(override val playlistId: Long, val stableKey: String, val name: String?) : EditorWrite
    data class RenameGroup(override val playlistId: Long, val groupKey: String, val name: String?) : EditorWrite
    data class SetNumber(override val playlistId: Long, val stableKey: String, val number: Int?) : EditorWrite
    data class Favorite(override val playlistId: Long, val target: ChannelTarget, val on: Boolean) : EditorWrite
    data class ResetChannels(override val playlistId: Long, val target: ChannelTarget) : EditorWrite
    data class ResetGroups(override val playlistId: Long, val target: GroupTarget) : EditorWrite
}
sealed interface WriteResult {
    data class Done(val changed: Int, val skippedByRule: Int = 0, val numberClash: String? = null) : WriteResult
    data class Failed(val reason: WriteFailure) : WriteResult
}
/** STORAGE: SQLite error or full disk. GONE: every target left the library (refresh). REFUSED: refusesLowering() said no. BUSY: SQLITE_BUSY after one retry. */
enum class WriteFailure { STORAGE, GONE, REFUSED, BUSY }

interface ChannelEditorEffects {
    /** → Groups, then GroupRows for page 0. */
    fun loadGroups(playlistId: Long, showHidden: Boolean, keepKey: String?)
    fun loadGroupRows(version: Long, from: Int, to: Int)
    /** → Channels, then ChannelRows. */
    fun loadChannels(playlistId: Long, list: EditorList, filter: EditorFilter, keepKey: String?)
    fun loadChannelRows(version: Long, from: Int, to: Int)
    /** → Summary (IO, one COUNT/SUM). */
    fun summarize(token: Long, playlistId: Long, target: ChannelTarget)
    /** → Located. */
    fun locate(playlistId: Long, list: EditorList, filter: EditorFilter, number: Int)
    /** → Written (FIFO, app scope). */
    fun write(write: EditorWrite)
    /** → TextResult (SettingsShellLink.editText). */
    fun editText(label: EditorText, initial: String, allowEmpty: Boolean)
    /** → NumberResult. */
    fun editNumber(label: EditorText, value: Int?)
    /** → PinResult. */
    fun requestPin()
    fun announce(text: String)
}

interface ChannelEditorMachine {
    val ui: StateFlow<ChannelEditorUiState>
    /** The next time the machine needs a Tick (notice fade, group-follow debounce, digit timeout); Long.MAX_VALUE = none. */
    val nextDeadlineMs: StateFlow<Long>
    fun open(playlistId: Long, groupKey: String?)
    /** False = not consumed (BACK at the ladder's bottom, OPEN_GUIDE, EXIT): the settings shell handles it. */
    fun act(action: GuideEvent.Act): Boolean
    fun dispatch(event: ChannelEditorEvent)
    fun close()

    companion object {
        const val NOTICE_MS = 3_000L
        const val FOLLOW_DEBOUNCE_MS = 150L
        const val DIGIT_TIMEOUT_MS = 1_500L
        const val HOLD_ACCEL_MS = 600L
        const val WRITE_QUEUE_MAX = 8
        const val VISIBLE_ROWS = 8
    }
}
fun interface ChannelEditorMachineFactory { fun create(effects: ChannelEditorEffects, texts: EditorTexts, nowMs: () -> Long): ChannelEditorMachine }
