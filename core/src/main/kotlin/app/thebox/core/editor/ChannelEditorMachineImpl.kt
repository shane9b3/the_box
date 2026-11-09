package app.thebox.core.editor

import app.thebox.core.guide.FooterHint
import app.thebox.core.guide.GuideEvent
import app.thebox.core.input.BoxAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The channel editor machine (docs/design/m6-channel-editor.md §2.3–§2.6, §3.3). Toolkit-agnostic, main thread, pure
 * state, like `SettingsMachineImpl`: keys come in through [act], data through [dispatch], and every read or write goes
 * out through [ChannelEditorEffects]. Time only enters through `nowMs` and [ChannelEditorEvent.Tick] at
 * [nextDeadlineMs].
 *
 * Conventions the view relies on:
 * - Groups pane: [GroupsPane.cursor] / [GroupsPane.top] are pane indices (the fixed head rows first). The group
 *   window's own `cursor` / `top` mirror them as group indices (clamped at 0).
 * - Channels pane: `channels.cursor` / `channels.top` are list indices; `-1` focuses (or tops at) the locked-out note
 *   row, which exists only while [ChannelEditorUiState.lockedOut] > 0.
 * - [Selection.Keys] for groups maps group key → **group** index (not pane index).
 * - [EditorMove.index] / `fromIndex`: the block's first row in the resulting order (see [EditorMoves]); a list index for
 *   channels, a pane index for groups. While moving, `channels.cursor` (or `groups.cursor`) is the block's start; use
 *   [EditorMoves.sourceIndex] with [liftedSourceIndices] to draw the other rows.
 * - [GroupPicker.cursor] / `top` are group indices over the shared group window.
 * - [ChannelEditorUiState.empty]: the channels pane's empty or error text; on a load failure it is `E_LOAD` and the
 *   failed pane's window is null. Its `%1$s` argument (the group name or the query) is the view's to fill.
 * - The machine asks for every page it needs ([ChannelEditorEffects.loadGroupRows] / `loadChannelRows`), page 0
 *   included; the view model need not send any page unasked (a duplicate is harmless).
 */
class ChannelEditorMachineImpl(
    private val effects: ChannelEditorEffects,
    private val texts: EditorTexts,
    private val nowMs: () -> Long,
) : ChannelEditorMachine {

    private val _ui = MutableStateFlow(blankState(0L))
    override val ui: StateFlow<ChannelEditorUiState> = _ui.asStateFlow()
    private val _deadline = MutableStateFlow(Long.MAX_VALUE)
    override val nextDeadlineMs: StateFlow<Long> = _deadline.asStateFlow()

    /** Test seam: every action the machine runs (a menu line, a toolbar chip, the green key). */
    internal var trace: ((EditorAction) -> Unit)? = null

    // ---------------------------------------------------------------- state

    private class Win<T>(val version: Long, val count: Int, val hidden: Int) {
        val pages = HashMap<Int, List<T>>()
        val requested = HashSet<Int>()
        fun row(i: Int): T? = if (i < 0 || i >= count) null else pages[i / PAGE]?.getOrNull(i % PAGE)
        fun window(cursor: Int, top: Int) = EditorWindow(version, count, hidden, HashMap(pages), cursor, top)
    }

    private sealed interface MenuCtx {
        data class Channels(val target: ChannelTarget?, val total: Int, val single: EditorChannelRow?, val summary: SelectionSummary?) : MenuCtx
        data class Groups(val target: GroupTarget, val total: Int, val rows: List<EditorGroupRow>?) : MenuCtx
        data class Head(val entry: EditorList) : MenuCtx
    }

    private sealed interface Prompt {
        data class RenameChannel(val key: String, val current: String, val provider: String) : Prompt
        data class RenameGroup(val key: String, val current: String, val provider: String) : Prompt
        data object Find : Prompt
        data class Number(val key: String) : Prompt
    }

    /** What a queued write acted on (ticks to clear when it lands) and how its result reads. */
    private class WriteMeta(
        val scope: Pair<EditorList, EditorFilter>?,
        val channelKeys: Set<String> = emptySet(),
        val channelAll: Selection.AllExcept? = null,
        val groupKeys: Set<String> = emptySet(),
        val groupAll: Selection.AllExcept? = null,
        val refused: EditorText = EditorText.E_WRITE,
        val notice: (WriteResult.Done) -> String?,
    )

    private var opened = false
    private var playlistId = 0L
    private var pinSet = false
    private var unlocked = true
    private var accessKnown = false
    private var gone = false

    private var pane = EditorPane.GROUPS
    private var toolbar: Int? = null

    // Groups pane
    private var head: List<EditorList> = listOf(EditorList.All)
    private var gWin: Win<EditorGroupRow>? = null
    private var gCursor = 0
    private var gTop = 0
    private var firstGroups = true
    private var showHiddenGroups = true
    private var groupSel: Selection = Selection.None
    private val groupTickRows = HashMap<String, EditorGroupRow>()

    // Channels pane
    private var list: EditorList = EditorList.All
    private var filter = EditorFilter.ALL
    private var cWin: Win<EditorChannelRow>? = null
    private var channelsIssued = false
    private var cCursor = 0
    private var cTop = 0
    private var pendingCursor: Int? = null
    private var lockedOut = 0
    private var listLocked = false
    private var chanSel: Selection = Selection.None
    private val chanTickRows = HashMap<String, EditorChannelRow>()
    private val memory = HashMap<EditorList, Pair<String?, Int>>()
    private val failed = HashSet<EditorPane>()
    /** The (list, filter) a null row already reloaded once (review S5). */
    private var nullReloadedFor: Pair<EditorList, EditorFilter>? = null

    // Follow (the channels pane follows the Groups cursor after FOLLOW_DEBOUNCE_MS)
    private var followPending = false
    private var followAt = Long.MAX_VALUE

    // Layers
    private var menu: EditorMenu? = null
    private var menuCtx: MenuCtx? = null
    private var summaryToken = 0L
    private var picker: GroupPicker? = null
    private var pickerTarget: ChannelTarget? = null
    private var pickerSel: Selection = Selection.None
    private var move: EditorMove? = null
    private var moveKeys: List<String> = emptyList()
    private var moveSources: List<Int> = emptyList()
    private var moveFocus = 0
    private var confirm: EditorConfirm? = null
    private var confirmMeta: WriteMeta? = null
    private var confirmNeedsPin: EditorText? = null
    private var choice: EditorChoice? = null

    // Number jump
    private var digits: String? = null
    private var digitAt = Long.MAX_VALUE
    private var locating: Int? = null

    // Hand-offs
    private var prompt: Prompt? = null
    private var pinStep: (() -> Unit)? = null
    private var pinRefused: EditorText? = null

    private val queue = EditorWriteQueue<WriteMeta>()
    private var notice: String? = null
    private var noticeUntil = Long.MAX_VALUE

    /** The original list indices of the lifted rows (ascending); [EditorMoves.liftedSources] derives the same from the UI state. */
    val liftedSourceIndices: List<Int> get() = moveSources

    // ---------------------------------------------------------------- ChannelEditorMachine

    override fun open(playlistId: Long, groupKey: String?) {
        resetAll()
        opened = true
        this.playlistId = playlistId
        if (groupKey != null) {
            // From the guide's group menu (§2.1): the channels pane, that group focused.
            pane = EditorPane.CHANNELS
            list = EditorList.Group(groupKey)
            channelsIssued = true
            publish()
            effects.loadGroups(playlistId, showHiddenGroups, groupKey)
            effects.loadChannels(playlistId, list, filter, null)
        } else {
            // From Settings: the Groups pane, first group (followed as soon as its row is known).
            pane = EditorPane.GROUPS
            followPending = true
            followAt = Long.MAX_VALUE
            publish()
            effects.loadGroups(playlistId, showHiddenGroups, null)
        }
    }

    override fun close() {
        resetAll()
        opened = false
        publish()
    }

    override fun act(action: GuideEvent.Act): Boolean {
        val a = action.action
        if (a == BoxAction.OPEN_GUIDE || a == BoxAction.EXIT) return false
        if (!opened) return a != BoxAction.BACK
        if (gone) return a != BoxAction.BACK
        val consumed = when {
            menu != null -> { menuKey(action); true }
            picker != null -> { pickerKey(a); true }
            confirm != null -> { confirmKey(a); true }
            choice != null -> { choiceKey(a); true }
            move != null -> { moveKey(a); true }
            toolbar != null -> { toolbarKey(a); true }
            a == BoxAction.BACK -> back()
            pane == EditorPane.GROUPS -> { groupsKey(action); true }
            else -> { channelsKey(action); true }
        }
        publish()
        return consumed
    }

    override fun dispatch(event: ChannelEditorEvent) {
        if (event is ChannelEditorEvent.Act) { act(event.action); return }
        // The parental session's state is kept while closed too (the view model may send it before open()).
        if (!opened) { if (event is ChannelEditorEvent.Access) { pinSet = event.pinSet; unlocked = event.unlocked; accessKnown = true }; return }
        when (event) {
            is ChannelEditorEvent.Act -> Unit
            is ChannelEditorEvent.Groups -> onGroups(event)
            is ChannelEditorEvent.GroupRows -> onGroupRows(event)
            is ChannelEditorEvent.Channels -> onChannels(event)
            is ChannelEditorEvent.ChannelRows -> onChannelRows(event)
            is ChannelEditorEvent.Summary -> onSummary(event)
            is ChannelEditorEvent.SummaryFailed -> onSummaryFailed(event.token)
            ChannelEditorEvent.Changed -> reloadAll() // as after a Written: the focused keys are kept
            is ChannelEditorEvent.Located -> onLocated(event)
            is ChannelEditorEvent.Written -> onWritten(event)
            is ChannelEditorEvent.LoadFailed -> onLoadFailed(event.what)
            ChannelEditorEvent.PlaylistGone -> onGone()
            is ChannelEditorEvent.Access -> onAccess(event)
            is ChannelEditorEvent.PinResult -> onPin(event.granted)
            is ChannelEditorEvent.TextResult -> onText(event.text)
            is ChannelEditorEvent.NumberResult -> onNumber(event.value)
            is ChannelEditorEvent.Tick -> onTick(event.nowMs)
        }
        publish()
    }

    // ---------------------------------------------------------------- panes

    private fun groupsKey(act: GuideEvent.Act) {
        when (act.action) {
            BoxAction.UP -> if (gCursor == 0) toolbar = 0 else moveGroupCursor(gCursor - step(act))
            BoxAction.DOWN -> moveGroupCursor(gCursor + step(act))
            BoxAction.PAGE_UP, BoxAction.ZAP_UP -> moveGroupCursor(gCursor - PAGE_ROWS)
            BoxAction.PAGE_DOWN, BoxAction.ZAP_DOWN -> moveGroupCursor(gCursor + PAGE_ROWS)
            BoxAction.SELECT -> when {
                failed.isNotEmpty() -> retry() // either pane's failure is retried from either pane (review S1)
                gCursor < head.size -> enterChannels()
                else -> tickGroup()
            }
            BoxAction.RIGHT -> enterChannels()
            BoxAction.CONTEXT_MENU -> openGroupMenu(fromToolbar = false)
            else -> Unit // ← is the left edge; digits and the green key are ignored here
        }
    }

    private fun channelsKey(act: GuideEvent.Act) {
        val a = act.action
        // OK commits a number entry at once (as the guide's number entry); any other key cancels it.
        if (digits != null && a == BoxAction.SELECT) { commitDigits(); return }
        if (digits != null && a != BoxAction.DIGIT) cancelDigits()
        when (a) {
            BoxAction.UP -> if (cCursor <= channelLo()) toolbar = 0 else moveChannelCursor(cCursor - step(act))
            BoxAction.DOWN -> moveChannelCursor(cCursor + step(act))
            BoxAction.PAGE_UP, BoxAction.ZAP_UP -> moveChannelCursor(cCursor - PAGE_ROWS)
            BoxAction.PAGE_DOWN, BoxAction.ZAP_DOWN -> moveChannelCursor(cCursor + PAGE_ROWS)
            BoxAction.SELECT -> when {
                failed.isNotEmpty() -> retry()
                listLocked || (cCursor < 0 && lockedOut > 0) -> withPin(true, null) { reloadChannels() }
                else -> tickChannel()
            }
            BoxAction.LEFT -> pane = EditorPane.GROUPS
            BoxAction.RIGHT, BoxAction.CONTEXT_MENU -> openChannelMenu()
            BoxAction.DIGIT -> if (act.digit in 0..9 && !listLocked && cWin != null) digit(act.digit)
            BoxAction.FAVOURITES, BoxAction.TOGGLE_FAVOURITE -> toggleFavorite()
            else -> Unit
        }
    }

    private fun step(act: GuideEvent.Act): Int =
        if (act.repeat > 0 && act.heldMs >= ChannelEditorMachine.HOLD_ACCEL_MS) HOLD_ACCEL_ROWS else 1

    private fun enterChannels() {
        toolbar = null
        pane = EditorPane.CHANNELS
        followNow()
    }

    private fun moveGroupCursor(to: Int) {
        val hi = head.size + (gWin?.count ?: 0) - 1
        val c = to.coerceIn(0, hi.coerceAtLeast(0))
        if (c == gCursor) return
        gCursor = c
        gTop = fixTop(gCursor, gTop, 0, hi)
        requestGroupPages()
        followPending = true
        followAt = nowMs() + ChannelEditorMachine.FOLLOW_DEBOUNCE_MS
        groupLabelAt(gCursor)?.let { effects.announce(it) }
    }

    private fun channelLo(): Int = if (lockedOut > 0) -1 else 0
    private fun channelHi(): Int = maxOf(channelLo(), (cWin?.count ?: 0) - 1)

    private fun moveChannelCursor(to: Int) {
        if (cWin == null) return
        val c = to.coerceIn(channelLo(), channelHi())
        if (c == cCursor) return
        cCursor = c
        cTop = fixTop(cCursor, cTop, channelLo(), channelHi())
        requestChannelPages()
        cWin?.row(cCursor)?.let { effects.announce(it.name) }
    }

    private fun focusedChannel(): EditorChannelRow? = cWin?.row(cCursor)
    private fun focusedGroup(): EditorGroupRow? = if (gCursor < head.size) null else gWin?.row(gCursor - head.size)

    private fun groupLabelAt(i: Int): String? = when {
        i < head.size -> headTitle(head[i])
        else -> gWin?.row(i - head.size)?.name
    }

    private fun headTitle(e: EditorList): String = when (e) {
        EditorList.All -> texts.get(EditorText.ALL_CHANNELS)
        is EditorList.Find -> texts.get(EditorText.FIND_RESULTS, e.query)
        is EditorList.Group -> groupRowByKey(e.groupKey)?.name ?: e.groupKey
    }

    // ---------------------------------------------------------------- ticks

    private fun tickChannel() {
        val row = focusedChannel() ?: return
        val k = row.stableKey
        chanSel = when (val s = chanSel) {
            Selection.None -> { chanTickRows[k] = row; Selection.Keys(mapOf(k to cCursor)) }
            is Selection.Keys -> if (k in s.keys) {
                chanTickRows.remove(k)
                (s.keys - k).let { if (it.isEmpty()) Selection.None else Selection.Keys(it) }
            } else { chanTickRows[k] = row; Selection.Keys(s.keys + (k to cCursor)) }
            is Selection.AllExcept -> Selection.AllExcept(if (k in s.except) s.except - k else s.except + k)
        }
        if (EditorMenus.selectedCount(chanSel, cWin?.count ?: 0) == 0) { chanSel = Selection.None; chanTickRows.clear() }
    }

    private fun tickGroup() {
        val gi = gCursor - head.size
        val row = gWin?.row(gi) ?: return
        val k = row.groupKey
        groupSel = when (val s = groupSel) {
            Selection.None -> { groupTickRows[k] = row; Selection.Keys(mapOf(k to gi)) }
            is Selection.Keys -> if (k in s.keys) {
                groupTickRows.remove(k)
                (s.keys - k).let { if (it.isEmpty()) Selection.None else Selection.Keys(it) }
            } else { groupTickRows[k] = row; Selection.Keys(s.keys + (k to gi)) }
            is Selection.AllExcept -> Selection.AllExcept(if (k in s.except) s.except - k else s.except + k)
        }
        if (EditorMenus.selectedCount(groupSel, gWin?.count ?: 0) == 0) { groupSel = Selection.None; groupTickRows.clear() }
    }

    private fun clearChannelSelection(withNotice: Boolean) {
        val n = EditorMenus.selectedCount(chanSel, cWin?.count ?: 0)
        chanSel = Selection.None
        chanTickRows.clear()
        if (withNotice && n > 0) showNotice(texts.count(EditorText.N_UNSELECTED, n))
    }

    private fun clearGroupSelection() {
        groupSel = Selection.None
        groupTickRows.clear()
    }

    /** The channel target of the current selection, else of the focused row; with its size. */
    private fun channelTarget(): Pair<ChannelTarget, Int>? = when (val s = chanSel) {
        is Selection.Keys -> ChannelTarget.Keys(s.keys.keys) to s.keys.size
        is Selection.AllExcept -> ChannelTarget.AllOf(list, filter, s.except) to EditorMenus.selectedCount(s, cWin?.count ?: 0)
        Selection.None -> focusedChannel()?.let { ChannelTarget.Keys(setOf(it.stableKey)) to 1 }
    }

    // ---------------------------------------------------------------- Back ladder (§2.5)

    private fun back(): Boolean {
        if (digits != null) { cancelDigits(); return true }
        // Rungs 1–3 (layers, move mode, toolbar) are handled by their own key tables before this.
        if (pane == EditorPane.CHANNELS) {
            if (chanSel != Selection.None) { clearChannelSelection(withNotice = true); return true }
            pane = EditorPane.GROUPS
            return true
        }
        if (groupSel != Selection.None) { clearGroupSelection(); return true }
        if (gCursor < head.size && head[gCursor] is EditorList.Find) { dropResults(); return true }
        return false
    }

    // ---------------------------------------------------------------- toolbar

    private fun toolbarKey(a: BoxAction) {
        val t = toolbar ?: return
        val chips = if (pane == EditorPane.GROUPS) GROUP_CHIPS else CHANNEL_CHIPS
        when (a) {
            BoxAction.DOWN, BoxAction.BACK -> toolbar = null
            BoxAction.LEFT -> toolbar = (t - 1).coerceAtLeast(0)
            BoxAction.RIGHT -> toolbar = (t + 1).coerceAtMost(chips - 1)
            BoxAction.SELECT -> runChip(t)
            else -> Unit
        }
    }

    private fun runChip(t: Int) {
        if (pane == EditorPane.GROUPS) when (t) {
            CHIP_ACTIONS -> if (groupSel == Selection.None) showNotice(texts.get(EditorText.D_GROUP_TICK_FIRST)) else openGroupMenu(fromToolbar = true)
            CHIP_HIDDEN_GROUPS -> toggleHiddenGroups()
            else -> openChoice(ChoiceKind.GROUP_SORT, fromToolbar = true)
        } else when (t) {
            CHIP_ACTIONS -> openChannelMenu()
            CHIP_SHOW -> { trace?.invoke(EditorAction.FILTER); openChoice(ChoiceKind.FILTER, fromToolbar = true) }
            CHIP_SORT -> if (list is EditorList.Group) { trace?.invoke(EditorAction.SORT); openChoice(ChoiceKind.CHANNEL_SORT, fromToolbar = true) }
            else showNotice(texts.get(EditorText.D_REORDER_LIST))
            else -> { trace?.invoke(EditorAction.FIND); startFind() }
        }
    }

    private fun toggleHiddenGroups() {
        showHiddenGroups = !showHiddenGroups
        clearGroupSelection()
        reloadGroups()
    }

    // ---------------------------------------------------------------- menus

    private fun openChannelMenu() {
        toolbar = null
        val win = cWin ?: return
        val sel = chanSel
        val ctx = EditorMenus.ChannelContext(list, filter, sel, null, win.count, gWin?.count ?: 0, lockedOut)
        if (sel == Selection.None) {
            val row = focusedChannel()
            if (row == null) {
                if (win.count > 0 && cCursor >= 0 && !listLocked) return // the row is still loading
                menuCtx = MenuCtx.Channels(null, 0, null, null)
                menu = EditorMenu(EditorPane.CHANNELS, listTitle(), EditorMenus.noTargetLines(list, if (listLocked) 0 else win.count), 0, loading = false)
                return
            }
            val s = EditorMenus.summaryOf(row)
            menuCtx = MenuCtx.Channels(ChannelTarget.Keys(setOf(row.stableKey)), 1, row, s)
            menu = EditorMenu(EditorPane.CHANNELS, row.name, EditorMenus.channelLines(ctx.copy(single = row), s), 0, loading = false)
            effects.announce(row.name)
            return
        }
        val (target, n) = channelTarget() ?: return
        // One ticked row: its own row is known, so Rename and Set number are offered once the summary says total = 1.
        val single = (sel as? Selection.Keys)?.keys?.keys?.singleOrNull()?.let { chanTickRows[it] }
        val token = ++summaryToken
        menuCtx = MenuCtx.Channels(target, n, single, null)
        val title = texts.count(EditorText.MENU_TITLE_CHANNELS, n)
        menu = EditorMenu(EditorPane.CHANNELS, title, emptyList(), 0, loading = true)
        effects.announce(title)
        effects.summarize(token, playlistId, target)
    }

    private fun onSummary(e: ChannelEditorEvent.Summary) {
        val m = menu ?: return
        val ctx = menuCtx as? MenuCtx.Channels ?: return
        if (!m.loading || e.token != summaryToken) return
        val win = cWin
        val c = EditorMenus.ChannelContext(list, filter, chanSel, ctx.single, win?.count ?: 0, gWin?.count ?: 0, lockedOut)
        menuCtx = ctx.copy(total = e.summary.total, summary = e.summary)
        menu = m.copy(lines = EditorMenus.channelLines(c, e.summary), focused = 0, loading = false)
    }

    /** Review S4: the count failed; the menu (still loading) closes and says why. A stale token is ignored. */
    private fun onSummaryFailed(token: Long) {
        val m = menu ?: return
        if (!m.loading || token != summaryToken || menuCtx !is MenuCtx.Channels) return
        closeMenu()
        showNotice(texts.get(EditorText.E_SUMMARY))
    }

    private fun openGroupMenu(fromToolbar: Boolean) {
        toolbar = null
        val win = gWin
        val count = win?.count ?: 0
        when (val s = groupSel) {
            Selection.None -> {
                if (gCursor < head.size) {
                    if (fromToolbar) return
                    val entry = head[gCursor]
                    menuCtx = MenuCtx.Head(entry)
                    menu = EditorMenu(EditorPane.GROUPS, headTitle(entry), EditorMenus.headLines(entry), 0, loading = false)
                    return
                }
                val row = focusedGroup() ?: return
                menuCtx = MenuCtx.Groups(GroupTarget.Keys(setOf(row.groupKey)), 1, listOf(row))
                menu = EditorMenu(EditorPane.GROUPS, row.name, EditorMenus.groupLines(listOf(row), 1, false, showHiddenGroups, count), 0, loading = false)
            }
            is Selection.Keys -> {
                val rows = s.keys.keys.mapNotNull { groupTickRows[it] }
                menuCtx = MenuCtx.Groups(GroupTarget.Keys(s.keys.keys), s.keys.size, rows)
                menu = EditorMenu(EditorPane.GROUPS, texts.count(EditorText.MENU_TITLE_GROUPS, s.keys.size),
                    EditorMenus.groupLines(rows, s.keys.size, false, showHiddenGroups, count), 0, loading = false)
            }
            is Selection.AllExcept -> {
                val n = EditorMenus.selectedCount(s, count)
                menuCtx = MenuCtx.Groups(GroupTarget.AllOf(showHiddenGroups, s.except), n, null)
                menu = EditorMenu(EditorPane.GROUPS, texts.count(EditorText.MENU_TITLE_GROUPS, n),
                    EditorMenus.groupLines(null, n, true, showHiddenGroups, count), 0, loading = false)
            }
        }
        menu?.let { effects.announce(it.title) }
    }

    private fun menuKey(act: GuideEvent.Act) {
        val m = menu ?: return
        val last = (m.lines.size - 1).coerceAtLeast(0)
        when (act.action) {
            // Clamped, no wrap (review E-26); disabled lines are focusable and show their reason.
            BoxAction.UP -> menu = m.copy(focused = (m.focused - 1).coerceIn(0, last))
            BoxAction.DOWN -> menu = m.copy(focused = (m.focused + 1).coerceIn(0, last))
            BoxAction.ZAP_UP, BoxAction.PAGE_UP -> menu = m.copy(focused = 0)
            BoxAction.ZAP_DOWN, BoxAction.PAGE_DOWN -> menu = m.copy(focused = last)
            BoxAction.LEFT, BoxAction.BACK -> closeMenu()
            BoxAction.SELECT -> {
                if (m.loading) return
                val line = m.lines.getOrNull(m.focused) ?: return
                if (!line.enabled) { line.reason?.let { showNotice(texts.get(it)) }; return }
                val ctx = menuCtx
                closeMenu()
                when (ctx) {
                    is MenuCtx.Channels -> runChannelLine(line, ctx)
                    is MenuCtx.Groups -> runGroupLine(line, ctx)
                    is MenuCtx.Head -> runHeadLine(line, ctx)
                    null -> Unit
                }
            }
            else -> Unit // →, Menu / long OK and digits are ignored in a layer
        }
    }

    private fun closeMenu() { menu = null; menuCtx = null }

    private fun runChannelLine(line: MenuLine, ctx: MenuCtx.Channels) {
        trace?.invoke(line.action)
        val target = ctx.target
        val total = ctx.total
        when (line.action) {
            EditorAction.HIDE -> target?.let { enqueue(EditorWrite.HideChannels(playlistId, it, true), channelMeta(EditorText.E_WRITE) { d -> texts.count(EditorText.N_HIDDEN, d.changed) }) }
            EditorAction.SHOW -> target?.let { enqueue(EditorWrite.HideChannels(playlistId, it, false), channelMeta(EditorText.E_WRITE) { d -> shownNotice(d) }) }
            EditorAction.MOVE_TO_GROUP -> if (target != null) {
                val fromLocked = (ctx.summary?.fromLockedGroup ?: 0) > 0
                withPin(sessionLocked() && fromLocked, EditorText.E_PIN_MOVE) { openPicker(target, total) }
            }
            EditorAction.REORDER -> liftChannels()
            EditorAction.ADD_FAVORITE, EditorAction.REMOVE_FAVORITE -> target?.let {
                val on = line.action == EditorAction.ADD_FAVORITE
                enqueue(EditorWrite.Favorite(playlistId, it, on), channelMeta(EditorText.E_WRITE) { d -> favoriteNotice(on, d) })
            }
            EditorAction.LOCK -> target?.let { enqueue(EditorWrite.LockChannels(playlistId, it, true), channelMeta(EditorText.E_WRITE) { d -> lockNotice(true, d) }) }
            EditorAction.UNLOCK -> target?.let { t ->
                withPin(sessionLocked(), EditorText.E_PIN_UNLOCK) {
                    enqueue(EditorWrite.LockChannels(playlistId, t, false), channelMeta(EditorText.E_PIN_UNLOCK) { d -> lockNotice(false, d) })
                }
            }
            EditorAction.RENAME -> ctx.single?.let { r ->
                prompt = Prompt.RenameChannel(r.stableKey, r.name, r.providerName)
                effects.editText(EditorText.EDIT_RENAME_CHANNEL, r.name, allowEmpty = true)
            }
            EditorAction.SET_NUMBER -> ctx.single?.let { r ->
                prompt = Prompt.Number(r.stableKey)
                effects.editNumber(EditorText.EDIT_NUMBER, r.customNumber ?: r.number)
            }
            EditorAction.CLEAR_NUMBER -> ctx.single?.let { r ->
                enqueue(EditorWrite.SetNumber(playlistId, r.stableKey, null), channelMeta(EditorText.E_WRITE) { texts.get(EditorText.N_NUMBER_CLEARED) })
            }
            EditorAction.SELECT_ALL -> { chanSel = Selection.AllExcept(emptySet()); chanTickRows.clear() }
            EditorAction.FIND -> startFind()
            EditorAction.FILTER -> openChoice(ChoiceKind.FILTER, fromToolbar = false)
            EditorAction.SORT -> openChoice(ChoiceKind.CHANNEL_SORT, fromToolbar = false)
            EditorAction.RESET -> target?.let {
                openConfirm(ConfirmKind.RESET_CHANNELS, listOf(total.toString()), EditorWrite.ResetChannels(playlistId, it),
                    channelMeta(EditorText.E_PIN_RESET) { d -> texts.count(EditorText.N_RESET, d.changed) }, pin = if (sessionLocked()) EditorText.E_PIN_RESET else null)
            }
            EditorAction.EDIT_CHANNELS, EditorAction.DROP_RESULTS -> Unit
        }
    }

    private fun runGroupLine(line: MenuLine, ctx: MenuCtx.Groups) {
        trace?.invoke(line.action)
        val t = ctx.target
        when (line.action) {
            EditorAction.HIDE, EditorAction.SHOW -> {
                val hide = line.action == EditorAction.HIDE
                enqueue(EditorWrite.HideGroups(playlistId, t, hide), groupMeta(EditorText.E_WRITE) { d ->
                    texts.count(if (hide) EditorText.N_GROUPS_HIDDEN else EditorText.N_GROUPS_SHOWN, d.changed)
                })
            }
            EditorAction.REORDER -> liftGroups()
            EditorAction.LOCK -> enqueue(EditorWrite.LockGroups(playlistId, t, true), groupMeta(EditorText.E_WRITE) { d -> lockNotice(true, d) })
            EditorAction.UNLOCK -> withPin(sessionLocked(), EditorText.E_PIN_UNLOCK) {
                enqueue(EditorWrite.LockGroups(playlistId, t, false), groupMeta(EditorText.E_PIN_UNLOCK) { d -> lockNotice(false, d) })
            }
            EditorAction.RENAME -> ctx.rows?.singleOrNull()?.let { r ->
                prompt = Prompt.RenameGroup(r.groupKey, r.name, r.providerName)
                effects.editText(EditorText.EDIT_RENAME_GROUP, r.name, allowEmpty = true)
            }
            EditorAction.EDIT_CHANNELS -> enterChannels()
            EditorAction.SORT -> openChoice(ChoiceKind.GROUP_SORT, fromToolbar = false)
            EditorAction.SELECT_ALL -> { groupSel = Selection.AllExcept(emptySet()); groupTickRows.clear() }
            EditorAction.RESET -> openConfirm(ConfirmKind.RESET_GROUPS, listOf(ctx.total.toString()), EditorWrite.ResetGroups(playlistId, t),
                groupMeta(EditorText.E_PIN_RESET) { d -> texts.count(EditorText.N_GROUPS_RESET, d.changed) }, pin = if (sessionLocked()) EditorText.E_PIN_RESET else null)
            else -> Unit
        }
    }

    private fun runHeadLine(line: MenuLine, ctx: MenuCtx.Head) {
        trace?.invoke(line.action)
        when (line.action) {
            EditorAction.EDIT_CHANNELS -> enterChannels()
            EditorAction.FIND -> startFind()
            EditorAction.DROP_RESULTS -> if (ctx.entry is EditorList.Find) dropResults()
            else -> Unit
        }
    }

    private fun channelMeta(refused: EditorText, notice: (WriteResult.Done) -> String?): WriteMeta {
        val s = chanSel
        return WriteMeta(
            scope = list to filter,
            channelKeys = (s as? Selection.Keys)?.keys?.keys ?: emptySet(),
            channelAll = s as? Selection.AllExcept,
            refused = refused, notice = notice,
        )
    }

    private fun groupMeta(refused: EditorText, notice: (WriteResult.Done) -> String?): WriteMeta {
        val s = groupSel
        return WriteMeta(
            scope = null,
            groupKeys = (s as? Selection.Keys)?.keys?.keys ?: emptySet(),
            groupAll = s as? Selection.AllExcept,
            refused = refused, notice = notice,
        )
    }

    private fun shownNotice(d: WriteResult.Done): String {
        val shown = texts.count(EditorText.N_SHOWN, d.changed)
        return if (d.skippedByRule > 0) shown + NOTICE_SEP + texts.count(EditorText.N_SKIPPED_RULE, d.skippedByRule) else shown
    }

    private fun favoriteNotice(on: Boolean, d: WriteResult.Done) =
        texts.count(if (on) EditorText.N_FAVORITE_ADDED else EditorText.N_FAVORITE_REMOVED, d.changed)

    private fun lockNotice(lock: Boolean, d: WriteResult.Done) = when {
        lock && !pinSet -> texts.get(EditorText.N_LOCKED_NO_PIN)
        lock -> texts.count(EditorText.N_LOCKED, d.changed)
        else -> texts.count(EditorText.N_UNLOCKED, d.changed)
    }

    private fun toggleFavorite() {
        val (target, _) = channelTarget() ?: return
        trace?.invoke(EditorAction.ADD_FAVORITE)
        val on = when (val s = chanSel) {
            Selection.None -> focusedChannel()?.favorite != true
            is Selection.Keys -> !s.keys.keys.all { chanTickRows[it]?.favorite == true }
            // Select all: the flags of unloaded rows are not known here; the accelerator adds (Remove is in the menu).
            is Selection.AllExcept -> true
        }
        enqueue(EditorWrite.Favorite(playlistId, target, on), channelMeta(EditorText.E_WRITE) { d -> favoriteNotice(on, d) })
    }

    private fun listTitle(): String = when (val l = list) {
        EditorList.All -> texts.get(EditorText.ALL_CHANNELS)
        is EditorList.Find -> texts.get(EditorText.FIND_RESULTS, l.query)
        is EditorList.Group -> groupRowByKey(l.groupKey)?.name ?: l.groupKey
    }

    // ---------------------------------------------------------------- picker (E4)

    private fun openPicker(target: ChannelTarget, moving: Int) {
        val win = gWin ?: return
        val from = (list as? EditorList.Group)?.groupKey
        val start = if (win.row(0)?.groupKey == from && win.count > 1) 1 else 0
        picker = GroupPicker(start, 0, moving, from)
        pickerTarget = target
        pickerSel = chanSel
        requestGroupPages()
    }

    private fun pickerKey(a: BoxAction) {
        val p = picker ?: return
        val n = gWin?.count ?: 0
        fun to(c: Int) {
            val cc = c.coerceIn(0, (n - 1).coerceAtLeast(0))
            picker = p.copy(cursor = cc, top = fixTop(cc, p.top, 0, n - 1))
            requestGroupPages()
        }
        when (a) {
            BoxAction.UP -> to(p.cursor - 1)
            BoxAction.DOWN -> to(p.cursor + 1)
            BoxAction.ZAP_UP, BoxAction.PAGE_UP -> to(p.cursor - PAGE_ROWS)
            BoxAction.ZAP_DOWN, BoxAction.PAGE_DOWN -> to(p.cursor + PAGE_ROWS)
            BoxAction.LEFT, BoxAction.BACK -> closePicker()
            BoxAction.SELECT -> {
                val row = gWin?.row(p.cursor) ?: return
                if (row.groupKey == p.fromGroupKey) return // "Already here"
                val target = pickerTarget ?: return
                val s = pickerSel
                val meta = WriteMeta(
                    scope = list to filter,
                    channelKeys = (s as? Selection.Keys)?.keys?.keys ?: emptySet(),
                    channelAll = s as? Selection.AllExcept,
                    refused = EditorText.E_PIN_MOVE,
                    notice = { d -> texts.count(EditorText.N_MOVED, d.changed, row.name) },
                )
                if (enqueue(EditorWrite.MoveChannels(playlistId, target, row.groupKey), meta)) closePicker()
            }
            else -> Unit
        }
    }

    private fun closePicker() { picker = null; pickerTarget = null; pickerSel = Selection.None }

    // ---------------------------------------------------------------- move mode (E5)

    private fun liftChannels() {
        val win = cWin ?: return
        if (list !is EditorList.Group) return
        val block = when (val s = chanSel) {
            is Selection.Keys -> EditorMoves.gather(s.keys, chanTickRows, cCursor.coerceAtLeast(0), null, null)
            Selection.None -> focusedChannel()?.let { EditorMoves.gather(emptyMap(), emptyMap(), cCursor, it.stableKey, it) }
            is Selection.AllExcept -> null
        } ?: return
        moveFocus = cCursor
        moveKeys = block.keys
        moveSources = block.sourceIndices
        val start = block.start.coerceIn(0, (win.count - block.keys.size).coerceAtLeast(0))
        move = EditorMove(EditorPane.CHANNELS, Lifted.Channels(block.rows), start, start)
        cCursor = start
        cTop = fixTop(cCursor, cTop, 0, win.count - 1)
    }

    private fun liftGroups() {
        val win = gWin ?: return
        val h = head.size
        val block = when (val s = groupSel) {
            is Selection.Keys -> EditorMoves.gather(s.keys, groupTickRows, (gCursor - h).coerceAtLeast(0), null, null)
            Selection.None -> focusedGroup()?.let { EditorMoves.gather(emptyMap(), emptyMap(), gCursor - h, it.groupKey, it) }
            is Selection.AllExcept -> null
        } ?: return
        moveFocus = gCursor
        moveKeys = block.keys
        moveSources = block.sourceIndices
        val start = h + block.start.coerceIn(0, (win.count - block.keys.size).coerceAtLeast(0))
        move = EditorMove(EditorPane.GROUPS, Lifted.Groups(block.rows), start, start)
        gCursor = start
        gTop = fixTop(gCursor, gTop, 0, h + win.count - 1)
        pane = EditorPane.GROUPS
    }

    private fun moveKey(a: BoxAction) {
        val m = move ?: return
        val size = moveKeys.size
        val groups = m.pane == EditorPane.GROUPS
        val offset = if (groups) head.size else 0
        val count = (if (groups) gWin?.count else cWin?.count) ?: 0
        fun shift(d: Int) {
            val i = offset + EditorMoves.step(m.index - offset, d, count, size)
            move = m.copy(index = i)
            if (groups) { gCursor = i; gTop = fixTop(gCursor, gTop, 0, offset + count - 1); requestGroupPages() }
            else { cCursor = i; cTop = fixTop(cCursor, cTop, 0, count - 1); requestChannelPages() }
        }
        when (a) {
            BoxAction.UP -> shift(-1)
            BoxAction.DOWN -> shift(+1)
            BoxAction.ZAP_UP, BoxAction.PAGE_UP -> shift(-PAGE_ROWS)
            BoxAction.ZAP_DOWN, BoxAction.PAGE_DOWN -> shift(+PAGE_ROWS)
            BoxAction.SELECT -> dropMove(m)
            BoxAction.BACK -> cancelMove(m)
            else -> Unit
        }
    }

    private fun dropMove(m: EditorMove) {
        val groups = m.pane == EditorPane.GROUPS
        // Dropped where it was lifted: nothing to write only if the block was already contiguous there (review S6);
        // a gathered block (ticks apart) is written so the rows come together.
        val offset = if (groups) head.size else 0
        val start = m.fromIndex - offset
        if (m.index == m.fromIndex && moveSources == (start until start + moveKeys.size).toList()) { endMove(); return }
        val ok = if (groups) {
            enqueue(EditorWrite.ReorderGroups(playlistId, moveKeys, m.index - head.size, showHidden = showHiddenGroups),
                groupMeta(EditorText.E_WRITE) { texts.get(EditorText.N_ORDER_SAVED) })
        } else {
            val gk = (list as? EditorList.Group)?.groupKey ?: return endMove()
            enqueue(EditorWrite.ReorderChannels(playlistId, gk, moveKeys, m.index),
                channelMeta(EditorText.E_WRITE) { texts.get(EditorText.N_ORDER_SAVED) })
        }
        if (ok) endMove() // a full queue keeps the block lifted, so the drop can be tried again
    }

    private fun cancelMove(m: EditorMove) {
        if (m.pane == EditorPane.GROUPS) {
            gCursor = moveFocus; gTop = fixTop(gCursor, gTop, 0, head.size + (gWin?.count ?: 0) - 1)
        } else {
            cCursor = moveFocus; cTop = fixTop(cCursor, cTop, channelLo(), channelHi())
        }
        endMove()
    }

    private fun endMove() { move = null; moveKeys = emptyList(); moveSources = emptyList() }

    // ---------------------------------------------------------------- confirm, choice

    private fun openConfirm(kind: ConfirmKind, args: List<String>, write: EditorWrite, meta: WriteMeta, pin: EditorText?) {
        confirm = EditorConfirm(kind, args, write, focused = 1)
        confirmMeta = meta
        confirmNeedsPin = pin
    }

    private fun confirmKey(a: BoxAction) {
        val c = confirm ?: return
        when (a) {
            BoxAction.LEFT -> confirm = c.copy(focused = 0)
            BoxAction.RIGHT -> confirm = c.copy(focused = 1)
            BoxAction.BACK -> closeConfirm()
            BoxAction.SELECT -> {
                val meta = confirmMeta
                val pin = confirmNeedsPin
                closeConfirm()
                if (c.focused != 0 || meta == null) return
                withPin(pin != null, pin) { enqueue(c.pending, meta) }
            }
            else -> Unit
        }
    }

    private fun closeConfirm() { confirm = null; confirmMeta = null; confirmNeedsPin = null }

    private fun openChoice(kind: ChoiceKind, fromToolbar: Boolean) {
        if (!fromToolbar) toolbar = null
        val focused = when (kind) {
            ChoiceKind.FILTER -> filter.ordinal
            else -> 0
        }
        choice = EditorChoice(kind, focused)
    }

    private fun choiceKey(a: BoxAction) {
        val c = choice ?: return
        val n = when (c.kind) {
            ChoiceKind.FILTER -> EditorFilter.entries.size
            ChoiceKind.CHANNEL_SORT -> ChannelSort.entries.size
            ChoiceKind.GROUP_SORT -> GroupSort.entries.size
        }
        when (a) {
            BoxAction.UP -> choice = c.copy(focused = (c.focused - 1).coerceAtLeast(0))
            BoxAction.DOWN -> choice = c.copy(focused = (c.focused + 1).coerceAtMost(n - 1))
            BoxAction.LEFT, BoxAction.BACK -> choice = null
            BoxAction.SELECT -> { choice = null; choose(c) }
            else -> Unit
        }
    }

    private fun choose(c: EditorChoice) {
        when (c.kind) {
            ChoiceKind.FILTER -> {
                val f = EditorFilter.entries[c.focused]
                if (f != filter) setFilter(f)
            }
            ChoiceKind.CHANNEL_SORT -> {
                val gk = (list as? EditorList.Group)?.groupKey ?: return
                val sort = ChannelSort.entries[c.focused]
                val write = EditorWrite.SortChannels(playlistId, gk, sort)
                val meta = channelMeta(EditorText.E_WRITE) { texts.get(EditorText.N_SORTED, texts.get(sortText(sort))) }
                val row = groupRowByKey(gk)
                // §2.3 E6: ask first when the group has an order of its own (unknown counts as "has one").
                if (row?.customOrder == false) enqueue(write, meta)
                else openConfirm(if (sort == ChannelSort.PROVIDER) ConfirmKind.PROVIDER_ORDER else ConfirmKind.SORT_REPLACES_ORDER,
                    listOf(row?.name ?: gk), write, meta, pin = null)
            }
            ChoiceKind.GROUP_SORT -> {
                val sort = GroupSort.entries[c.focused]
                val label = if (sort == GroupSort.NAME_AZ) EditorText.SORT_NAME_AZ else EditorText.SORT_PROVIDER
                // The view picks C_SORT_GROUPS_TITLE / _BODY (or C_PROVIDER_TITLE / C_PROVIDER_GROUPS_BODY) for a
                // SortGroups pending write (review S8); no group name to format, so no args.
                openConfirm(if (sort == GroupSort.PROVIDER) ConfirmKind.PROVIDER_ORDER else ConfirmKind.SORT_REPLACES_ORDER,
                    emptyList(), EditorWrite.SortGroups(playlistId, sort),
                    groupMeta(EditorText.E_WRITE) { texts.get(EditorText.N_SORTED, texts.get(label)) }, pin = null)
            }
        }
    }

    private fun sortText(s: ChannelSort) = when (s) {
        ChannelSort.NAME_AZ -> EditorText.SORT_NAME_AZ
        ChannelSort.NAME_ZA -> EditorText.SORT_NAME_ZA
        ChannelSort.NUMBER -> EditorText.SORT_NUMBER
        ChannelSort.PROVIDER -> EditorText.SORT_PROVIDER
    }

    private fun setFilter(f: EditorFilter) {
        rememberChannelCursor()
        clearChannelSelection(withNotice = true)
        filter = f
        cancelDigits()
        locating = null
        cWin = null
        lockedOut = 0; listLocked = false
        pendingCursor = 0; cCursor = 0; cTop = 0
        channelsIssued = true
        failed -= EditorPane.CHANNELS
        nullReloadedFor = null
        effects.loadChannels(playlistId, list, filter, null)
    }

    // ---------------------------------------------------------------- Find, results row

    private fun startFind() {
        toolbar = null
        prompt = Prompt.Find
        val q = (head.getOrNull(1) as? EditorList.Find)?.query ?: ""
        effects.editText(EditorText.EDIT_FIND, q, allowEmpty = false)
    }

    private fun showResults(query: String) {
        // Group ticks keep their group indices (head rows are not tickable), so adding the row moves nothing.
        head = listOf(EditorList.All, EditorList.Find(query))
        gCursor = 1
        gTop = fixTop(gCursor, gTop, 0, head.size + (gWin?.count ?: 0) - 1)
        pane = EditorPane.GROUPS
        toolbar = null
        followPending = false; followAt = Long.MAX_VALUE
        switchList(EditorList.Find(query))
    }

    private fun dropResults() {
        if (head.size < 2) return
        val wasOn = gCursor == 1
        head = listOf(EditorList.All)
        gCursor = if (wasOn) 0 else (gCursor - 1).coerceAtLeast(0)
        gTop = fixTop(gCursor, gTop.coerceAtMost(gCursor), 0, head.size + (gWin?.count ?: 0) - 1)
        memory.keys.removeAll { it is EditorList.Find }
        if (list is EditorList.Find) followNow()
    }

    // ---------------------------------------------------------------- text, number, PIN hand-offs

    private fun onText(text: String?) {
        val p = prompt ?: return
        if (p is Prompt.Number) return
        prompt = null
        if (text == null) return
        when (p) {
            Prompt.Find -> text.trim().takeIf { it.isNotEmpty() }?.let { showResults(it) }
            is Prompt.RenameChannel -> {
                val name = text.trim().ifEmpty { null }
                if (name == p.current) return // unchanged: nothing to write
                enqueue(EditorWrite.RenameChannel(playlistId, p.key, name), channelMeta(EditorText.E_WRITE) {
                    if (name == null) texts.get(EditorText.N_NAME_RESET, p.provider) else texts.get(EditorText.N_RENAMED)
                })
            }
            is Prompt.RenameGroup -> {
                val name = text.trim().ifEmpty { null }
                if (name == p.current) return // unchanged: nothing to write
                enqueue(EditorWrite.RenameGroup(playlistId, p.key, name), WriteMeta(scope = null, notice = {
                    if (name == null) texts.get(EditorText.N_NAME_RESET, p.provider) else texts.get(EditorText.N_RENAMED)
                }))
            }
            is Prompt.Number -> Unit
        }
    }

    private fun onNumber(value: Int?) {
        val p = prompt as? Prompt.Number ?: return
        prompt = null
        if (value == null) return
        enqueue(EditorWrite.SetNumber(playlistId, p.key, value), channelMeta(EditorText.E_WRITE) { d ->
            d.numberClash?.let { texts.get(EditorText.N_NUMBER_CLASH, value, it) } ?: texts.get(EditorText.N_NUMBER_SET, value)
        })
    }

    private fun sessionLocked() = pinSet && !unlocked

    /** Runs [step] at once, or after the guide's one PIN prompt grants it; a refusal shows [refused] (if any). */
    private fun withPin(needed: Boolean, refused: EditorText?, step: () -> Unit) {
        if (!needed) { step(); return }
        pinStep = step
        pinRefused = refused
        effects.requestPin()
    }

    private fun onPin(granted: Boolean) {
        val step = pinStep ?: return
        val refused = pinRefused
        pinStep = null; pinRefused = null
        if (granted) step() else refused?.let { showNotice(texts.get(it)) }
    }

    // ---------------------------------------------------------------- writes

    /** Appends [write] to the FIFO and hands it to the view model; false (and the notice) while the queue is full. */
    private fun enqueue(write: EditorWrite, meta: WriteMeta): Boolean {
        if (!queue.offer(write, meta)) {
            showNotice(texts.get(EditorText.N_QUEUE_FULL))
            return false
        }
        effects.write(write)
        return true
    }

    private fun onWritten(e: ChannelEditorEvent.Written) {
        val entry = queue.complete(e.write)
        val meta = entry?.meta
        when (val r = e.result) {
            is WriteResult.Done -> {
                if (meta != null) {
                    // Clear the ticks this write acted on; ticks made while it was saving survive.
                    if (meta.scope == null || meta.scope == (list to filter)) {
                        when (val s = chanSel) {
                            is Selection.Keys -> if (meta.channelKeys.isNotEmpty()) {
                                val left = s.keys - meta.channelKeys
                                meta.channelKeys.forEach { chanTickRows.remove(it) }
                                chanSel = if (left.isEmpty()) Selection.None else Selection.Keys(left)
                            }
                            is Selection.AllExcept -> if (meta.channelAll != null && s == meta.channelAll) chanSel = Selection.None
                            Selection.None -> Unit
                        }
                    }
                    when (val s = groupSel) {
                        is Selection.Keys -> if (meta.groupKeys.isNotEmpty()) {
                            val left = s.keys - meta.groupKeys
                            meta.groupKeys.forEach { groupTickRows.remove(it) }
                            groupSel = if (left.isEmpty()) Selection.None else Selection.Keys(left)
                        }
                        is Selection.AllExcept -> if (meta.groupAll != null && s == meta.groupAll) groupSel = Selection.None
                        Selection.None -> Unit
                    }
                    meta.notice(r)?.let { showNotice(it) }
                }
                reloadAll()
            }
            is WriteResult.Failed -> {
                val key = when (r.reason) {
                    WriteFailure.STORAGE -> EditorText.E_WRITE
                    WriteFailure.BUSY -> EditorText.E_WRITE_BUSY
                    WriteFailure.GONE -> EditorText.E_GONE
                    WriteFailure.REFUSED -> meta?.refused?.takeIf { it != EditorText.E_WRITE } ?: refusedText(e.write)
                }
                showNotice(texts.get(key))
                if (r.reason == WriteFailure.GONE) reloadAll()
            }
        }
    }

    /** §5.2: the action's own PIN text for a `REFUSED` write. */
    private fun refusedText(w: EditorWrite): EditorText = when (w) {
        is EditorWrite.MoveChannels -> EditorText.E_PIN_MOVE
        is EditorWrite.ResetChannels, is EditorWrite.ResetGroups -> EditorText.E_PIN_RESET
        else -> EditorText.E_PIN_UNLOCK
    }

    // ---------------------------------------------------------------- loads

    private fun reloadAll() { reloadGroups(); reloadChannels() }

    private fun reloadGroups() {
        failed -= EditorPane.GROUPS
        effects.loadGroups(playlistId, showHiddenGroups, focusedGroup()?.groupKey)
    }

    private fun reloadChannels() {
        if (!channelsIssued) return
        failed -= EditorPane.CHANNELS
        pendingCursor = cCursor
        effects.loadChannels(playlistId, list, filter, focusedChannel()?.stableKey)
    }

    private fun retry() {
        val f = failed.toSet()
        if (EditorPane.GROUPS in f) reloadGroups()
        if (EditorPane.CHANNELS in f) reloadChannels()
        updateEmpty()
    }

    private fun onGroups(e: ChannelEditorEvent.Groups) {
        val cur = gWin
        if (cur != null && e.version < cur.version) return
        failed -= EditorPane.GROUPS
        if (cur != null && e.count != cur.count) move?.takeIf { it.pane == EditorPane.GROUPS }?.let { cancelMove(it) }
        if (cur == null || cur.version != e.version || cur.count != e.count) gWin = Win(e.version, e.count, e.hidden)
        val h = head.size
        val hi = h + e.count - 1
        when {
            move?.pane == EditorPane.GROUPS -> Unit
            e.cursorIndex != null -> gCursor = h + e.cursorIndex
            firstGroups && pane == EditorPane.GROUPS -> gCursor = if (e.count > 0) h else 0
        }
        gCursor = gCursor.coerceIn(0, hi.coerceAtLeast(0))
        firstGroups = false
        gTop = fixTop(gCursor, gTop, 0, hi)
        picker?.let { p -> val c = p.cursor.coerceIn(0, (e.count - 1).coerceAtLeast(0)); picker = p.copy(cursor = c, top = fixTop(c, p.top, 0, e.count - 1)) }
        requestGroupPages()
        tryFollow()
    }

    private fun onGroupRows(e: ChannelEditorEvent.GroupRows) {
        val win = gWin ?: return
        if (e.version != win.version) return
        storePage(win, e.from, e.rows)
        for ((k, r) in e.rows.withIndex()) {
            if (groupTickRows.containsKey(r.groupKey)) {
                groupTickRows[r.groupKey] = r
                (groupSel as? Selection.Keys)?.let { s -> groupSel = Selection.Keys(s.keys + (r.groupKey to e.from + k)) }
            }
        }
        evict(win, groupAnchors())
        tryFollow()
    }

    private fun onChannels(e: ChannelEditorEvent.Channels) {
        if (e.list != list || e.filter != filter) return
        val cur = cWin
        if (cur != null && e.version < cur.version) return
        failed -= EditorPane.CHANNELS
        // A background reload that changes the list under a lifted block (review N12): the block returns.
        if (cur != null && e.count != cur.count) move?.takeIf { it.pane == EditorPane.CHANNELS }?.let { cancelMove(it) }
        if (cur == null || cur.version != e.version || cur.count != e.count) cWin = Win(e.version, e.count, e.hidden)
        lockedOut = e.lockedOut
        listLocked = e.listLocked
        if (move?.pane != EditorPane.CHANNELS) {
            cCursor = e.cursorIndex ?: pendingCursor ?: cCursor
            cCursor = cCursor.coerceIn(channelLo(), channelHi())
        }
        pendingCursor = null
        cTop = fixTop(cCursor, cTop, channelLo(), channelHi())
        updateEmpty()
        requestChannelPages()
    }

    private fun onChannelRows(e: ChannelEditorEvent.ChannelRows) {
        val win = cWin ?: return
        if (e.version != win.version) return
        if (e.rows.any { it == null }) {
            // A row left the library between the list and its rows: read the list again, keeping the focus, once per
            // (list, filter) (review S5). If the next read still has gaps, the page stays a placeholder until the change
            // feed (Changed) or a list change reloads it.
            if (nullReloadedFor != (list to filter)) {
                nullReloadedFor = list to filter
                reloadChannels()
            }
            return
        }
        @Suppress("UNCHECKED_CAST")
        val rows = e.rows as List<EditorChannelRow>
        storePage(win, e.from, rows)
        val s = chanSel
        if (s is Selection.Keys) {
            var keys = s.keys
            for ((k, r) in rows.withIndex()) if (keys.containsKey(r.stableKey)) {
                chanTickRows[r.stableKey] = r
                keys = keys + (r.stableKey to e.from + k)
            }
            if (keys != s.keys) chanSel = Selection.Keys(keys)
        }
        evict(win, listOf(cCursor.coerceAtLeast(0) to ROWS_BEHIND))
    }

    private fun onLoadFailed(what: EditorPane) {
        failed += what
        if (what == EditorPane.GROUPS) { gWin = null; firstGroups = false } else cWin = null
        updateEmpty()
        if (what == EditorPane.GROUPS) tryFollow() // All channels still works
    }

    private fun onGone() {
        gone = true
        closeLayers()
        updateEmpty()
    }

    private fun onAccess(e: ChannelEditorEvent.Access) {
        val changed = accessKnown && (e.pinSet != pinSet || e.unlocked != unlocked)
        pinSet = e.pinSet
        unlocked = e.unlocked
        accessKnown = true
        // E7 and the locked-out note depend on it (§3.3 "Access").
        if (changed) reloadAll()
    }

    private fun updateEmpty() {
        val win = cWin
        _empty = when {
            gone -> EditorText.E_PLAYLIST_GONE
            EditorPane.CHANNELS in failed -> EditorText.E_LOAD
            win == null -> null
            listLocked -> EditorText.E_LOCKED
            win.count > 0 -> null
            // Empty only because the PIN leaves channels out (review S9): the locked-out note row alone says so.
            lockedOut > 0 -> null
            else -> when (list) {
                EditorList.All -> EditorText.E_NO_CHANNELS
                is EditorList.Find -> EditorText.E_FIND_NONE
                is EditorList.Group -> when (filter) {
                    EditorFilter.HIDDEN -> EditorText.E_EMPTY_HIDDEN
                    EditorFilter.SHOWN -> EditorText.E_EMPTY_SHOWN
                    EditorFilter.ALL -> EditorText.E_EMPTY_GROUP
                }
            }
        }
    }

    private var _empty: EditorText? = null

    // ---------------------------------------------------------------- follow

    private fun listAtGroupsCursor(): EditorList? =
        if (gCursor < head.size) head[gCursor] else gWin?.row(gCursor - head.size)?.let { EditorList.Group(it.groupKey) }

    /** RIGHT / OK on a head row / Edit channels: follow at once (no debounce). */
    private fun followNow() {
        followPending = true
        followAt = Long.MAX_VALUE
        tryFollow()
    }

    /**
     * A follow is due when [followPending] and its debounce ([followAt]) has passed; [followAt] = MAX means "due, waiting
     * for the Groups list or the focused group's row" (no deadline then: the row's arrival calls this again).
     */
    private fun tryFollow() {
        if (!followPending) return
        if (followAt != Long.MAX_VALUE && nowMs() < followAt) return
        followAt = Long.MAX_VALUE
        if (firstGroups) return
        if (gWin == null && gCursor >= head.size) return
        val target = listAtGroupsCursor() ?: return // its row is still loading: follow when it lands
        followPending = false
        followAt = Long.MAX_VALUE
        if (target != list || !channelsIssued) switchList(target)
    }

    private fun switchList(target: EditorList) {
        rememberChannelCursor()
        clearChannelSelection(withNotice = true)
        if (move?.pane == EditorPane.CHANNELS) endMove()
        list = target
        cWin = null
        lockedOut = 0; listLocked = false
        cancelDigits()
        locating = null
        failed -= EditorPane.CHANNELS
        nullReloadedFor = null
        val m = memory[target]
        pendingCursor = m?.second ?: 0
        cCursor = pendingCursor ?: 0
        cTop = 0
        channelsIssued = true
        updateEmpty()
        effects.loadChannels(playlistId, target, filter, m?.first)
    }

    private fun rememberChannelCursor() {
        if (!channelsIssued || cWin == null) return
        memory[list] = focusedChannel()?.stableKey to cCursor
    }

    // ---------------------------------------------------------------- number jump

    private fun digit(d: Int) {
        val cur = digits ?: ""
        if (cur.length >= MAX_DIGITS) return
        digits = cur + d
        digitAt = nowMs() + ChannelEditorMachine.DIGIT_TIMEOUT_MS
    }

    private fun cancelDigits() { digits = null; digitAt = Long.MAX_VALUE }

    private fun commitDigits() {
        val n = digits?.toIntOrNull()
        cancelDigits()
        if (n == null) return
        locating = n
        effects.locate(playlistId, list, filter, n)
    }

    private fun onLocated(e: ChannelEditorEvent.Located) {
        if (locating != e.number) return
        locating = null
        val i = e.index
        if (i == null) showNotice(texts.get(EditorText.N_NO_NUMBER, e.number)) else moveChannelCursor(i)
    }

    // ---------------------------------------------------------------- time

    private fun onTick(now: Long) {
        if (now >= noticeUntil) { notice = null; noticeUntil = Long.MAX_VALUE }
        if (now >= digitAt) commitDigits()
        if (followPending && followAt != Long.MAX_VALUE && now >= followAt) tryFollow()
    }

    private fun showNotice(text: String) {
        notice = text
        noticeUntil = nowMs() + ChannelEditorMachine.NOTICE_MS
        effects.announce(text)
    }

    // ---------------------------------------------------------------- paging (§4 item 2)

    private fun groupAnchors(): List<Pair<Int, Int>> = buildList {
        add((gCursor - head.size).coerceAtLeast(0) to ROWS_BEHIND)
        picker?.let { add(it.cursor to PICKER_BEHIND) }
    }

    private fun requestGroupPages() {
        val win = gWin ?: return
        requestAround(win, (gCursor - head.size).coerceAtLeast(0), ROWS_BEHIND, ROWS_AHEAD) { f, t -> effects.loadGroupRows(win.version, f, t) }
        picker?.let { p -> requestAround(win, p.cursor, PICKER_BEHIND, PICKER_AHEAD) { f, t -> effects.loadGroupRows(win.version, f, t) } }
    }

    private fun requestChannelPages() {
        val win = cWin ?: return
        if (listLocked) return
        requestAround(win, cCursor.coerceAtLeast(0), ROWS_BEHIND, ROWS_AHEAD) { f, t -> effects.loadChannelRows(win.version, f, t) }
    }

    private fun <T> requestAround(win: Win<T>, center: Int, behind: Int, ahead: Int, load: (Int, Int) -> Unit) {
        if (win.count == 0) return
        val from = (center - behind).coerceAtLeast(0)
        val to = (center + ahead).coerceAtMost(win.count)
        if (from >= to) return
        for (p in from / PAGE..(to - 1) / PAGE) {
            if (!win.pages.containsKey(p) && win.requested.add(p)) load(p * PAGE, minOf(win.count, (p + 1) * PAGE))
        }
    }

    private fun <T> storePage(win: Win<T>, from: Int, rows: List<T>) {
        if (rows.isEmpty() || from < 0) return
        // Pages are requested aligned; a misaligned answer is split into the pages it covers that it completes.
        if (from % PAGE == 0 && (rows.size == PAGE || from + rows.size == win.count)) {
            win.pages[from / PAGE] = rows
            return
        }
        var i = 0
        while (i < rows.size) {
            val idx = from + i
            val p = idx / PAGE
            val start = p * PAGE
            val end = minOf(win.count, start + PAGE)
            if (idx == start && from + rows.size >= end) win.pages[p] = rows.subList(i, i + (end - start))
            i += (end - idx).coerceAtLeast(1)
        }
    }

    /** At most PAGES_KEPT pages: the farthest from every anchor (row index, its behind-range) go first. */
    private fun <T> evict(win: Win<T>, anchors: List<Pair<Int, Int>>) {
        val kept = EditorWindow.PAGES_KEPT
        if (win.pages.size <= kept) return
        fun distance(p: Int): Int = anchors.minOf { (c, _) ->
            val start = p * PAGE; val end = start + PAGE - 1
            when { c < start -> start - c; c > end -> c - end; else -> 0 }
        }
        val drop = win.pages.keys.sortedByDescending { distance(it) }.take(win.pages.size - kept)
        for (p in drop) { win.pages.remove(p); win.requested.remove(p) }
    }

    // ---------------------------------------------------------------- helpers

    private fun groupRowByKey(key: String): EditorGroupRow? =
        groupTickRows[key] ?: gWin?.pages?.values?.firstNotNullOfOrNull { l -> l.firstOrNull { it.groupKey == key } }

    private fun closeLayers() {
        closeMenu(); closePicker(); closeConfirm(); choice = null
        move?.let { cancelMove(it) }
        toolbar = null
        cancelDigits()
    }

    private fun resetAll() {
        // Access (pinSet, unlocked) is the parental session's, not the visit's: it survives open() and close().
        playlistId = 0L
        gone = false
        pane = EditorPane.GROUPS; toolbar = null
        head = listOf(EditorList.All); gWin = null; gCursor = 0; gTop = 0; firstGroups = true
        showHiddenGroups = true; groupSel = Selection.None; groupTickRows.clear()
        list = EditorList.All; filter = EditorFilter.ALL; cWin = null; channelsIssued = false
        cCursor = 0; cTop = 0; pendingCursor = null; lockedOut = 0; listLocked = false
        chanSel = Selection.None; chanTickRows.clear(); memory.clear(); failed.clear(); nullReloadedFor = null
        followPending = false; followAt = Long.MAX_VALUE
        menu = null; menuCtx = null; picker = null; pickerTarget = null; pickerSel = Selection.None
        move = null; moveKeys = emptyList(); moveSources = emptyList(); moveFocus = 0
        confirm = null; confirmMeta = null; confirmNeedsPin = null; choice = null
        digits = null; digitAt = Long.MAX_VALUE; locating = null
        prompt = null; pinStep = null; pinRefused = null
        queue.clear()
        notice = null; noticeUntil = Long.MAX_VALUE
        _empty = null
    }

    private fun footer(): List<FooterHint> {
        val f = { k: String, t: EditorText -> FooterHint(k, texts.get(t)) }
        return when {
            gone -> listOf(f(KEY_BACK, EditorText.FOOT_DONE))
            move != null -> listOf(f(KEY_UP_DOWN, EditorText.FOOT_MOVE), f(KEY_CH, EditorText.FOOT_MOVE), f(KEY_OK, EditorText.FOOT_DROP), f(KEY_BACK, EditorText.FOOT_CANCEL))
            menu != null || picker != null || choice != null ->
                listOf(f(KEY_UP_DOWN, EditorText.FOOT_BROWSE), f(KEY_OK, EditorText.FOOT_SELECT), f(KEY_BACK, EditorText.FOOT_CANCEL))
            confirm != null -> listOf(f(KEY_LEFT_RIGHT, EditorText.FOOT_BROWSE), f(KEY_OK, EditorText.FOOT_SELECT), f(KEY_BACK, EditorText.FOOT_CANCEL))
            toolbar != null -> listOf(f(KEY_LEFT_RIGHT, EditorText.FOOT_BROWSE), f(KEY_OK, EditorText.FOOT_SELECT), f(KEY_BACK, EditorText.FOOT_CANCEL))
            pane == EditorPane.GROUPS -> listOf(
                f(KEY_UP_DOWN, EditorText.FOOT_BROWSE), f(KEY_OK, EditorText.FOOT_SELECT), f(KEY_RIGHT, EditorText.FOOT_CHANNELS),
                f(KEY_MENU, EditorText.FOOT_ACTIONS),
                f(KEY_BACK, if (groupSel != Selection.None) EditorText.FOOT_CLEAR else EditorText.FOOT_DONE),
                f(KEY_GUIDE, EditorText.FOOT_GUIDE),
            )
            else -> listOf(
                f(KEY_UP_DOWN, EditorText.FOOT_BROWSE), f(KEY_OK, EditorText.FOOT_SELECT), f(KEY_LEFT, EditorText.FOOT_GROUPS),
                f(KEY_RIGHT_MENU, EditorText.FOOT_ACTIONS),
                f(KEY_BACK, if (chanSel != Selection.None) EditorText.FOOT_CLEAR else EditorText.FOOT_GROUPS),
                f(KEY_GUIDE, EditorText.FOOT_GUIDE),
            )
        }
    }

    private fun publish() {
        val h = head.size
        val next = ChannelEditorUiState(
            playlistId = playlistId,
            playlistName = "",
            pane = pane,
            toolbar = toolbar,
            groups = GroupsPane(head, gWin?.window((gCursor - h).coerceAtLeast(0), (gTop - h).coerceAtLeast(0)), gCursor, gTop),
            showHiddenGroups = showHiddenGroups,
            groupSelection = groupSel,
            list = list,
            filter = filter,
            channels = cWin?.window(cCursor, cTop),
            channelSelection = chanSel,
            lockedList = listLocked,
            lockedOut = lockedOut,
            menu = menu, picker = picker, move = move, confirm = confirm, choice = choice,
            digits = digits,
            pendingWrites = queue.size,
            notice = notice,
            empty = _empty,
            gone = gone,
            footer = if (opened) footer() else emptyList(),
            groupsFailed = EditorPane.GROUPS in failed,
        )
        if (next != _ui.value) _ui.value = next
        _deadline.value = minOf(noticeUntil, digitAt, if (followPending) followAt else Long.MAX_VALUE)
    }

    companion object {
        val FACTORY = ChannelEditorMachineFactory { effects, texts, nowMs -> ChannelEditorMachineImpl(effects, texts, nowMs) }

        private const val PAGE = EditorWindow.PAGE
        /** Joins two notice sentences into one line (review S8). */
        const val NOTICE_SEP = " · "
        /** CH± / PAGE± (§2.4). */
        const val PAGE_ROWS = 8
        const val HOLD_ACCEL_ROWS = 3
        /** §4 item 2: `[cursor − 32, cursor + 64)`. */
        const val ROWS_BEHIND = 32
        const val ROWS_AHEAD = 64
        /** The picker shows about ten rows; it loads `[cursor − 8, cursor + 16)` so the pane and the picker fit in 6 pages. */
        const val PICKER_BEHIND = 8
        const val PICKER_AHEAD = 16
        const val MAX_DIGITS = 5

        /** Toolbar chips (§2.3 E1/E2). Groups: Actions · Hidden groups · Sort…; Channels: Actions · Show · Sort… · Find…. */
        const val CHIP_ACTIONS = 0
        const val CHIP_HIDDEN_GROUPS = 1
        const val CHIP_SHOW = 1
        const val CHIP_SORT = 2
        const val CHIP_FIND = 3
        const val GROUP_CHIPS = 3
        const val CHANNEL_CHIPS = 4

        // Key caps of the footer.
        const val KEY_UP_DOWN = "↑ ↓"
        const val KEY_LEFT_RIGHT = "← →"
        const val KEY_LEFT = "←"
        const val KEY_RIGHT = "→"
        const val KEY_RIGHT_MENU = "→ / Menu"
        const val KEY_MENU = "Menu"
        const val KEY_OK = "OK"
        const val KEY_BACK = "Back"
        const val KEY_CH = "CH±"
        const val KEY_GUIDE = "Guide"

        private fun blankState(pid: Long) = ChannelEditorUiState(
            playlistId = pid, playlistName = "", groups = GroupsPane(listOf(EditorList.All), null, 0, 0),
            list = EditorList.All, channels = null,
        )

        /** Inclusive range [lo, hi]; one-row lookahead scrolling like the settings list (§3.2.4). */
        fun fixTop(cursor: Int, top: Int, lo: Int, hi: Int): Int {
            val visible = ChannelEditorMachine.VISIBLE_ROWS
            val n = hi - lo + 1
            if (n <= visible) return lo
            var t = top.coerceIn(lo, hi - visible + 1)
            if (cursor >= t + visible - 1) t = minOf(cursor - visible + 2, hi - visible + 1)
            if (cursor <= t) t = maxOf(lo, cursor - 1)
            return t.coerceIn(lo, hi - visible + 1)
        }
    }
}
