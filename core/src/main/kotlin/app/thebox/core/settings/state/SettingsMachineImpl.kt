package app.thebox.core.settings.state

import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideStateMachine
import app.thebox.core.input.BoxAction
import app.thebox.core.input.CaptureState
import app.thebox.core.input.Keys
import app.thebox.core.input.Press
import app.thebox.core.parental.PinGate
import app.thebox.core.parental.PinPurpose
import app.thebox.core.settings.ChoiceOption
import app.thebox.core.settings.ConfirmState
import app.thebox.core.settings.EditorState
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.MenuState
import app.thebox.core.settings.MoveState
import app.thebox.core.settings.NumberPadState
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.PagedList
import app.thebox.core.settings.Pane
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsEffects
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.SettingsMachine
import app.thebox.core.settings.SettingsMachineFactory
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.SettingsUiState
import app.thebox.core.settings.ChoiceState
import app.thebox.core.settings.tree.SettingsTree
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The settings machine (docs/design/m5-setup-settings.md §2.3, §3.2): routes, the two panes, rows, the inline choice
 * list, the context menu, the confirm card, move mode, the editor and number-pad hand-offs, capture, detached routes
 * (S2) and [onLeave] (S9). Pure Kotlin, main thread, never blocks; pages arrive as [SettingsEvent.Page] after
 * [SettingsEffects.loadPage].
 *
 * Conventions the views rely on (WP-A §1.4a):
 * - [SettingsUiState.route] is the open page; [SettingsUiState.stack] its parents (bottom first). In the SECTIONS pane at
 *   the root, `route` is [SettingsRoute.Root] and [SettingsUiState.page] previews the focused section's page.
 * - [SettingsUiState.sections] is empty for the first-run flow and for detached routes (no sections pane).
 *   `sectionCursor == sections.size` focuses the pinned "Show advanced settings" toggle ([ADVANCED_ROW]).
 * - Row indices run over `page.rows` then `page.list` (list index `j` = index − rows.size; `PagedList.loaded` is keyed
 *   by page number `j / pageSize`).
 * - A MultiChoice list edits a working copy: the open page's row shows the toggled selection until the list closes.
 */
class SettingsMachineImpl(
    private val effects: SettingsEffects,
    private val nowMs: () -> Long,
    private val texts: SettingsTexts = SettingsTexts.KEYS,
    private val validator: SettingsValidator = HintValidator(texts),
) : SettingsMachine, SettingsShellLink {
    private val _ui = MutableStateFlow(SettingsUiState())
    override val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()
    private val _deadline = MutableStateFlow(GuideStateMachine.NO_DEADLINE.value)
    override val nextDeadlineMs: StateFlow<Long> = _deadline.asStateFlow()
    override var pinGate: PinGate? = null
    override val textInputActive: Boolean get() = _ui.value.editor != null

    private val _shell = MutableSharedFlow<SettingsShellEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val shellEvents: SharedFlow<SettingsShellEvent> = _shell.asSharedFlow()
    private val _editorBacks = MutableSharedFlow<Unit>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val editorBacks: SharedFlow<Unit> = _editorBacks.asSharedFlow()
    private var guideDispatch: ((GuideEvent) -> Unit)? = null

    // ---------------------------------------------------------------- state

    /** The open path, bottom first; the last entry is the open page. Empty only before the first [open]. */
    private var path: List<SettingsRoute> = listOf(SettingsRoute.Root)
    private var pane = Pane.SECTIONS
    private var detached = false
    private var advanced = false
    private var sectionCursor = 0
    /** "The last section used this process" (§3.2.1). */
    private var lastSection: SectionId = SectionId.PLAYLISTS
    private var cursor = 0
    private var top = 0
    private var choice: ChoiceState? = null
    /** MultiChoice working set while its list is open, and the selection it opened with. */
    private var multiWorking: Set<Int>? = null
    private var multiInitial: Set<Int>? = null
    private var menu: MenuState? = null
    private var confirm: ConfirmState? = null
    private var editor: EditorState? = null
    private var numberPad: NumberPadState? = null
    private var move: MoveState? = null
    private var capture: CaptureState? = null
    private var captureRow: RowId? = null
    private var captureUntilMs = Long.MAX_VALUE
    private var notice: String? = null
    private var noticeUntilMs = Long.MAX_VALUE

    /** Pages by route (the open page plus [PAGE_CACHE] recent ones, §5 item 6). */
    private val pages = LinkedHashMap<SettingsRoute, PageModel>(8, 0.75f, true)
    /** Paged-list rows by route: list index → row (merged into [PageModel.list] when published). */
    private val listRows = HashMap<SettingsRoute, HashMap<Int, SettingsRow>>()
    private val requested = HashMap<SettingsRoute, MutableSet<Int>>()
    /** Cursor memory per route: RowId first, then the index (§3.2.1). */
    private val memory = HashMap<SettingsRoute, Pair<RowId?, Int>>()

    /** Timings of rows changed during this visit (S9: [onLeave] reports them once). */
    private val changed = LinkedHashSet<EffectTiming>()
    private var visitOpen = false

    /** A PIN request in flight: what runs when [SettingsEvent.PinResult] grants it. */
    private var pinToken = 0L
    private var pinPending: (() -> Unit)? = null
    private var captureToken = 0L

    // ---------------------------------------------------------------- SettingsMachine

    override fun open(route: SettingsRoute, fromFull: Boolean, detached: Boolean) {
        closeOverlays()
        if (!visitOpen) {
            visitOpen = true
            changed.clear()
        }
        this.detached = detached
        when {
            route == SettingsRoute.Root -> {
                path = listOf(SettingsRoute.Root)
                pane = Pane.SECTIONS
                sectionCursor = sections().indexOf(lastSection).coerceAtLeast(0)
                cursor = 0; top = 0
            }
            detached || route == SettingsRoute.FirstRun -> {
                path = listOf(route)
                pane = Pane.PAGE
                restoreCursor(route)
            }
            else -> {
                path = pathTo(route)
                pane = Pane.PAGE
                SettingsTree.sectionOf(route)?.let { s -> sections().indexOf(s).takeIf { it >= 0 }?.let { sectionCursor = it; lastSection = s } }
                restoreCursor(route)
            }
        }
        requestDisplayed()
        publish()
    }

    override fun act(action: GuideEvent.Act): Boolean {
        val a = action.action
        // N11: the Guide key leaves Settings to the guide and Exit opens the exit dialog, from any depth.
        if (a == BoxAction.OPEN_GUIDE || a == BoxAction.EXIT) {
            closeOverlays(); publish()
            return false
        }
        if (editor != null) return true // the router hands the editor's keys to Compose; BACK arrives as EditorBack
        val consumed = when {
            capture != null -> captureKey(a)
            numberPad != null -> padKey(action)
            confirm != null -> confirmKey(a)
            menu != null -> menuKey(a)
            move != null -> moveKey(a, action)
            choice != null -> choiceKey(a)
            pane == Pane.SECTIONS -> sectionsKey(a)
            else -> pageKey(action)
        }
        publish()
        return consumed
    }

    override fun onLeave() {
        closeOverlays()
        if (!visitOpen) return
        visitOpen = false
        val timings = changed.toSet()
        changed.clear()
        effects.onClosed(timings)
        // The next visit rebuilds its pages (sources stop observing when Settings closes).
        pages.clear(); listRows.clear(); requested.clear()
        publish()
    }

    override fun dispatch(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.Page -> onPage(event.page)
            is SettingsEvent.Items -> onItems(event)
            is SettingsEvent.EditorClosed -> onEditorClosed(event)
            SettingsEvent.EditorBack -> if (editor != null) _editorBacks.tryEmit(Unit)
            is SettingsEvent.Captured -> onCaptured(event)
            is SettingsEvent.CaptureTimedOut -> if (capture?.token == event.token) cancelCapture(texts[SettingsText.CAPTURE_TIMED_OUT])
            is SettingsEvent.PinResult -> onPinResult(event)
            is SettingsEvent.Notice -> showNotice(event.text)
            is SettingsEvent.Advanced -> setAdvanced(event.on, emit = false)
            is SettingsEvent.Tick -> onTick(event.nowMs)
        }
        publish()
    }

    // ---------------------------------------------------------------- SettingsShellLink

    override fun attachGuide(dispatch: ((GuideEvent) -> Unit)?) { guideDispatch = dispatch }

    override fun openFromFull(route: SettingsRoute, detached: Boolean) {
        // The guide's M4 quick-bar path enters SETTINGS and remembers FULL for BACK; it opens us on Root first.
        guideDispatch?.invoke(GuideEvent.OpenSettings)
        open(route, fromFull = true, detached = detached)
    }

    override fun push(route: SettingsRoute) {
        closeOverlays()
        pushRoute(route)
        publish()
    }

    override fun post(event: SettingsShellEvent) { _shell.tryEmit(event) }

    override var backHandler: ((SettingsRoute) -> Boolean)? = null

    override fun replace(route: SettingsRoute) {
        closeOverlays()
        path = if (path.size > 1) path.dropLast(1) + route else listOf(route)
        pane = Pane.PAGE
        restoreCursor(route)
        requestDisplayed()
        publish()
    }

    override fun pop() {
        closeOverlays()
        if (!popPage(fromBack = true)) guideDispatch?.invoke(GuideEvent.Act(BoxAction.BACK))
        publish()
    }

    override fun popTo(route: SettingsRoute) {
        closeOverlays()
        val i = path.lastIndexOf(route)
        if (i < 0) { pushRoute(route); publish(); return }
        if (i == path.lastIndex) { publish(); return }
        rememberCursor()
        path = path.subList(0, i + 1)
        if (route == SettingsRoute.Root) pane = Pane.SECTIONS else restoreCursor(route)
        requestDisplayed()
        publish()
    }

    /** "Start watching" / "Later": the guide's Guide key leaves SETTINGS to the guide (N11). */
    override fun leaveToGuide() {
        closeOverlays()
        publish()
        guideDispatch?.invoke(GuideEvent.Act(BoxAction.OPEN_GUIDE))
    }

    override fun confirm(spec: app.thebox.core.settings.ConfirmSpec, intent: RowIntent) {
        confirm = ConfirmState(spec, intent)
        publish()
    }

    // ---------------------------------------------------------------- key tables (§3.2.2)

    private fun sectionsKey(a: BoxAction): Boolean {
        val secs = sections()
        val last = secs.size // the advanced toggle
        when (a) {
            BoxAction.UP -> moveSection(sectionCursor - 1)
            BoxAction.DOWN -> moveSection(sectionCursor + 1)
            BoxAction.PAGE_UP, BoxAction.ZAP_UP -> moveSection(sectionCursor - SECTION_PAGE)
            BoxAction.PAGE_DOWN, BoxAction.ZAP_DOWN -> moveSection(sectionCursor + SECTION_PAGE)
            BoxAction.RIGHT, BoxAction.SELECT -> {
                if (sectionCursor == last) {
                    if (a == BoxAction.SELECT) setAdvanced(!advanced, emit = true)
                } else {
                    val s = secs[sectionCursor]
                    lastSection = s
                    path = listOf(SettingsRoute.Root, SettingsRoute.Section(s))
                    pane = Pane.PAGE
                    restoreCursor(currentRoute())
                    requestDisplayed()
                    announceRow()
                }
            }
            BoxAction.BACK -> return false // leave Settings (the guide applies the M4 rule)
            else -> Unit
        }
        return true
    }

    private fun moveSection(to: Int) {
        val clamped = to.coerceIn(0, sections().size)
        if (clamped == sectionCursor) return
        sectionCursor = clamped
        sections().getOrNull(sectionCursor)?.let { lastSection = it }
        requestDisplayed()
        val label = sections().getOrNull(sectionCursor)?.let { texts.section(it) } ?: texts[SettingsText.ADVANCED_TOGGLE]
        effects.announce(label)
    }

    private fun pageKey(act: GuideEvent.Act): Boolean {
        val page = displayedPage()
        val row = page?.let { rowAt(it, cursor) }
        when (act.action) {
            BoxAction.UP -> moveCursor(cursor - step(act))
            BoxAction.DOWN -> moveCursor(cursor + step(act))
            BoxAction.PAGE_UP, BoxAction.ZAP_UP -> moveCursor(cursor - ROW_PAGE)
            BoxAction.PAGE_DOWN, BoxAction.ZAP_DOWN -> moveCursor(cursor + ROW_PAGE)
            BoxAction.SELECT -> if (row != null) select(row)
            // Welcome (product S1): its cards sit side by side, so ←/→ move between them (WP-B's WelcomeScreen).
            BoxAction.LEFT, BoxAction.RIGHT -> if (currentRoute() == SettingsRoute.FirstRun) {
                moveCursor(cursor + if (act.action == BoxAction.RIGHT) 1 else -1)
            } else if (act.action == BoxAction.LEFT) popPage(fromBack = false)
            else if (row != null && row.enabled) when (val k = row.kind) {
                is RowKind.Link -> openLink(row, k)
                is RowKind.Item -> k.route?.let { r -> pushRoute(r) }
                else -> Unit
            }
            BoxAction.BACK -> return popPage(fromBack = true)
            BoxAction.CONTEXT_MENU -> {
                val k = row?.kind
                if (row != null && row.enabled && k is RowKind.Item && k.menu.isNotEmpty()) menu = MenuState(row.id, k.menu, firstEnabled(k.menu))
            }
            BoxAction.DIGIT -> {
                val k = row?.kind
                if (row != null && row.enabled && k is RowKind.Number && act.digit in 0..9) {
                    numberPad = NumberPadState(row.id, row.label, act.digit.toString(), k.min, k.max)
                }
            }
            else -> Unit
        }
        return true
    }

    /** §3.2.2: paged lists accelerate after HOLD_ACCEL_MS to three rows per repeat. */
    private fun step(act: GuideEvent.Act): Int =
        if (act.repeat > 0 && act.heldMs >= HOLD_ACCEL_MS && displayedPage()?.list != null) HOLD_ACCEL_ROWS else 1

    private fun select(row: SettingsRow) {
        if (!row.enabled) return
        when (val k = row.kind) {
            is RowKind.Toggle -> {
                emit(RowIntent.Toggle(row.id, !k.on), row)
                replaceRow(row.id) { it.copy(kind = RowKind.Toggle(!k.on)) }
            }
            is RowKind.Choice -> if (k.options.isNotEmpty()) {
                choice = ChoiceState(row.id, if (k.options.getOrNull(k.selected)?.enabled == true) k.selected else firstEnabled(k.options))
            }
            is RowKind.MultiChoice -> if (k.options.isNotEmpty()) {
                multiWorking = k.selected; multiInitial = k.selected
                choice = ChoiceState(row.id, firstEnabled(k.options))
            }
            is RowKind.Text -> editor = EditorState(row.id, row.label, k.value, k.hint, k.masked, k.chips)
            is RowKind.Number -> numberPad = NumberPadState(row.id, row.label, "", k.min, k.max)
            is RowKind.Action -> runAction(row, k)
            is RowKind.Link -> openLink(row, k)
            is RowKind.Info -> Unit
            is RowKind.Item -> when {
                k.route != null -> pushRoute(k.route)
                k.primary != null -> emit(RowIntent.Run(row.id), row)
                else -> Unit
            }
        }
    }

    private fun runAction(row: SettingsRow, k: RowKind.Action) {
        val run = {
            when {
                row.id.key.startsWith(CAPTURE_PREFIX) -> startCapture(row.id)
                k.confirm != null -> confirm = ConfirmState(k.confirm, RowIntent.Run(row.id))
                else -> emit(RowIntent.Run(row.id), row)
            }
        }
        if (k.needsPin) gated(run) else run()
    }

    private fun openLink(row: SettingsRow, k: RowKind.Link) {
        if (k.needsPin) gated { pushRoute(k.route) } else pushRoute(k.route)
    }

    /** PIN first when one is set (§3.2.2): the gate answers false when none is, and the step runs at once. */
    private fun gated(step: () -> Unit) {
        val gate = pinGate
        val token = ++pinToken
        if (gate != null && gate.request(PinPurpose.SettingsAction(token))) pinPending = step else step()
    }

    private fun onPinResult(e: SettingsEvent.PinResult) {
        val p = e.purpose as? PinPurpose.SettingsAction ?: return
        if (p.token != pinToken) return
        val step = pinPending ?: return
        pinPending = null
        if (e.granted) step()
    }

    // ---------------------------------------------------------------- overlays

    private fun choiceKey(a: BoxAction): Boolean {
        val c = choice ?: return true
        val row = findRow(c.row)
        val options = when (val k = row?.kind) {
            is RowKind.Choice -> k.options
            is RowKind.MultiChoice -> k.options
            else -> { closeChoice(commit = false); return true }
        }
        when (a) {
            BoxAction.UP -> choice = c.copy(focused = nextEnabled(options, c.focused, -1))
            BoxAction.DOWN -> choice = c.copy(focused = nextEnabled(options, c.focused, +1))
            BoxAction.SELECT -> {
                val k = row.kind
                if (k is RowKind.Choice) {
                    val opt = options.getOrNull(c.focused)
                    choice = null
                    if (opt != null && opt.enabled && c.focused != k.selected) {
                        emit(RowIntent.Choose(row.id, opt.id), row)
                        replaceRow(row.id) { it.copy(kind = k.copy(selected = c.focused), value = opt.label) }
                    }
                } else if (k is RowKind.MultiChoice && options.getOrNull(c.focused)?.enabled == true) {
                    val w = multiWorking ?: k.selected
                    val next = if (c.focused in w) w - c.focused else w + c.focused
                    multiWorking = next
                    replaceRow(row.id) { it.copy(kind = k.copy(selected = next)) }
                }
            }
            BoxAction.BACK, BoxAction.LEFT -> closeChoice(commit = true)
            else -> Unit
        }
        return true
    }

    private fun closeChoice(commit: Boolean) {
        val c = choice ?: return
        choice = null
        val working = multiWorking
        val initial = multiInitial
        multiWorking = null; multiInitial = null
        if (!commit || working == null || working == initial) return
        val row = findRow(c.row) ?: return
        val k = row.kind as? RowKind.MultiChoice ?: return
        emit(RowIntent.ChooseMany(row.id, working.mapNotNull { k.options.getOrNull(it)?.id }.toSet()), row)
    }

    private fun menuKey(a: BoxAction): Boolean {
        val m = menu ?: return true
        when (a) {
            BoxAction.UP -> menu = m.copy(focused = nextEnabled(m.options, m.focused, -1))
            BoxAction.DOWN -> menu = m.copy(focused = nextEnabled(m.options, m.focused, +1))
            BoxAction.SELECT -> {
                menu = null
                val opt = m.options.getOrNull(m.focused) ?: return true
                if (!opt.enabled) return true
                val row = findRow(m.row) ?: return true
                if (opt.id == MOVE_OPTION) startMove(row) else emit(RowIntent.Menu(row.id, opt.id), row)
            }
            BoxAction.BACK -> menu = null
            else -> Unit
        }
        return true
    }

    private fun confirmKey(a: BoxAction): Boolean {
        val c = confirm ?: return true
        when (a) {
            BoxAction.LEFT -> confirm = c.copy(focused = 0)
            BoxAction.RIGHT -> confirm = c.copy(focused = 1)
            BoxAction.SELECT -> {
                confirm = null
                if (c.focused == 0) findRow(c.intent.row).let { r -> emit(c.intent, r) }
            }
            BoxAction.BACK -> confirm = null
            else -> Unit
        }
        return true
    }

    private fun padKey(act: GuideEvent.Act): Boolean {
        val p = numberPad ?: return true
        val col = p.focusedKey % 3
        val rowIdx = p.focusedKey / 3
        when (act.action) {
            BoxAction.UP -> if (rowIdx > 0) numberPad = p.copy(focusedKey = p.focusedKey - 3)
            BoxAction.DOWN -> if (rowIdx < 3) numberPad = p.copy(focusedKey = p.focusedKey + 3)
            BoxAction.LEFT -> if (col > 0) numberPad = p.copy(focusedKey = p.focusedKey - 1)
            BoxAction.RIGHT -> if (col < 2) numberPad = p.copy(focusedKey = p.focusedKey + 1)
            BoxAction.DIGIT -> if (act.digit in 0..9) numberPad = appendDigit(p, act.digit)
            BoxAction.SELECT -> when (p.focusedKey) {
                in 0..8 -> numberPad = appendDigit(p, p.focusedKey + 1)
                PAD_DELETE -> numberPad = p.copy(digits = p.digits.dropLast(1), error = null)
                PAD_ZERO -> numberPad = appendDigit(p, 0)
                PAD_OK -> commitPad(p)
            }
            BoxAction.BACK -> numberPad = if (p.digits.isNotEmpty()) p.copy(digits = p.digits.dropLast(1), error = null) else null
            else -> Unit
        }
        return true
    }

    private fun appendDigit(p: NumberPadState, d: Int): NumberPadState {
        val maxLen = maxOf(p.max.toString().length, 1)
        if (p.digits.length >= maxLen) return p
        val digits = if (p.digits == "0") d.toString() else p.digits + d
        return p.copy(digits = digits, error = null)
    }

    private fun commitPad(p: NumberPadState) {
        val v = p.digits.toIntOrNull()
        if (v == null || v < p.min || v > p.max) {
            numberPad = p.copy(error = texts[SettingsText.ERR_NUMBER_RANGE, p.min, p.max])
            return
        }
        numberPad = null
        val row = findRow(p.row)
        val k = row?.kind as? RowKind.Number
        if (k == null || k.value != v) {
            emit(RowIntent.SetNumber(p.row, v), row)
            if (k != null) replaceRow(p.row) { it.copy(kind = k.copy(value = v)) }
        }
    }

    private fun startMove(row: SettingsRow) {
        val page = displayedPage() ?: return
        val idx = indexOf(page, row.id) ?: return
        move = MoveState(row.id, idx, idx)
    }

    private fun moveKey(a: BoxAction, act: GuideEvent.Act): Boolean {
        val m = move ?: return true
        val page = displayedPage() ?: run { move = null; return true }
        // A lifted row stays in its block (fixed rows or the paged list).
        val (lo, hi) = if (m.fromIndex < page.rows.size) 0 to page.rows.size - 1 else page.rows.size to total(page) - 1
        when (a) {
            BoxAction.UP -> move = m.copy(index = (m.index - 1).coerceIn(lo, hi))
            BoxAction.DOWN -> move = m.copy(index = (m.index + 1).coerceIn(lo, hi))
            BoxAction.SELECT -> {
                move = null
                if (m.index != m.fromIndex) {
                    val blockIndex = if (m.fromIndex < page.rows.size) m.index else m.index - page.rows.size
                    emit(RowIntent.Move(m.row, blockIndex), findRow(m.row))
                    cursor = m.index
                    fixTop(page)
                }
            }
            BoxAction.BACK -> move = null
            else -> Unit
        }
        if (act.action == BoxAction.UP || act.action == BoxAction.DOWN) move?.let { cursor = it.index; fixTop(page) }
        return true
    }

    // ---------------------------------------------------------------- editor and capture hand-offs

    private fun onEditorClosed(e: SettingsEvent.EditorClosed) {
        val ed = editor ?: return
        if (ed.row != e.row) return
        if (!e.committed || e.text == null) { editor = null; return }
        val error = validator.validate(ed.row, ed.hint, e.text)
        if (error != null) { editor = ed.copy(error = error); return }
        editor = null
        if (e.text != ed.initial) {
            val row = findRow(ed.row)
            emit(RowIntent.Edit(ed.row, e.text), row)
            val k = row?.kind as? RowKind.Text
            if (k != null) replaceRow(ed.row) { it.copy(kind = k.copy(value = e.text)) }
        }
    }

    /** `capture:<actionId>:<SHORT|LONG>` Action rows start a capture (§3.8.3, WP-C's pages). */
    private fun startCapture(row: RowId) {
        val parts = row.key.removePrefix(CAPTURE_PREFIX).split(':')
        val action = BoxAction.entries.firstOrNull { it.id == parts.getOrNull(0) } ?: return
        val press = Press.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: Press.entries.first()
        val now = nowMs()
        capture = CaptureState(++captureToken, action, press, now)
        captureRow = row
        captureUntilMs = now + CAPTURE_TIMEOUT_MS
        effects.armCapture(true)
    }

    private fun onCaptured(e: SettingsEvent.Captured) {
        val c = capture ?: return
        if (c.captured != null) return
        effects.armCapture(false)
        captureUntilMs = Long.MAX_VALUE
        if (e.key.keyCode == Keys.BACK || e.key.keyCode == Keys.ESCAPE || e.key.keyCode == Keys.BUTTON_B) {
            cancelCapture(null)
            return
        }
        capture = c.copy(captured = e.key)
    }

    private fun captureKey(a: BoxAction): Boolean {
        val c = capture ?: return true
        when (a) {
            BoxAction.BACK -> cancelCapture(null)
            BoxAction.SELECT -> {
                val key = c.captured ?: return true
                val row = captureRow
                cancelCapture(null)
                if (row != null) emit(RowIntent.Menu(row, BIND_PREFIX + key.keyCode), findRow(row))
            }
            else -> Unit
        }
        return true
    }

    private fun cancelCapture(message: String?) {
        if (capture?.captured == null && capture != null) effects.armCapture(false)
        capture = null
        captureRow = null
        captureUntilMs = Long.MAX_VALUE
        if (message != null) showNotice(message)
    }

    // ---------------------------------------------------------------- navigation

    private fun pushRoute(route: SettingsRoute) {
        rememberCursor()
        path = path + route
        pane = Pane.PAGE
        restoreCursor(route)
        requestDisplayed()
        announceRow()
    }

    /** ← / BACK on the page pane (§3.2.4). False: leave Settings (BACK at a detached root). */
    private fun popPage(fromBack: Boolean): Boolean {
        val route = currentRoute()
        if (fromBack && backHandler?.invoke(route) == true) return true
        if (path.size <= 1) {
            if (route == SettingsRoute.FirstRun) {
                if (fromBack) effects.exitApp() // product S1: no confirmation
                return true
            }
            if (detached || route != SettingsRoute.Root) return !fromBack
            pane = Pane.SECTIONS
            return true
        }
        rememberCursor()
        path = path.dropLast(1)
        val parent = currentRoute()
        if (parent == SettingsRoute.Root) {
            pane = Pane.SECTIONS
            (route as? SettingsRoute.Section)?.let { s -> sections().indexOf(s.id).takeIf { it >= 0 }?.let { sectionCursor = it } }
        } else {
            restoreCursor(parent)
        }
        requestDisplayed()
        return true
    }

    private fun pathTo(route: SettingsRoute): List<SettingsRoute> {
        val out = ArrayList<SettingsRoute>()
        var r: SettingsRoute? = route
        var guard = 0
        while (r != null && guard++ < 8) { out += r; r = parentOf(r) }
        if (out.last() != SettingsRoute.Root && out.last() != SettingsRoute.FirstRun) out += SettingsRoute.Root
        return out.reversed()
    }

    private fun moveCursor(to: Int) {
        val page = displayedPage() ?: return
        val n = total(page)
        if (n == 0) return
        val c = to.coerceIn(0, n - 1)
        if (c == cursor) return
        cursor = c
        fixTop(page)
        requestItemsAround(page)
        announceRow()
    }

    private fun fixTop(page: PageModel) {
        val n = total(page)
        if (n <= VISIBLE_ROWS) { top = 0; return }
        if (cursor >= top + VISIBLE_ROWS - 1) top = minOf(cursor - VISIBLE_ROWS + 2, n - VISIBLE_ROWS)
        if (cursor <= top) top = maxOf(0, cursor - 1)
        top = top.coerceIn(0, n - VISIBLE_ROWS)
    }

    private fun rememberCursor() {
        val page = displayedPage() ?: return
        memory[currentRoute()] = rowAt(page, cursor)?.id to cursor
    }

    private fun restoreCursor(route: SettingsRoute) {
        val m = memory[route]
        val page = pages[route]
        val focus = (route as? SettingsRoute.Playlist)?.focus
        cursor = when {
            page != null && focus != null && m == null -> indexOf(page, focus) ?: 0
            page != null && m != null -> m.first?.let { indexOf(page, it) } ?: m.second
            m != null -> m.second
            else -> 0
        }
        if (page != null) cursor = cursor.coerceIn(0, (total(page) - 1).coerceAtLeast(0))
        top = 0
        if (page != null) fixTop(page)
    }

    // ---------------------------------------------------------------- pages

    private fun onPage(page: PageModel) {
        val old = pages[page.route]
        if (old != null && old.version > page.version) return
        val keepRows = listRows[page.route]
        if (old == null || old.version != page.version || page.list == null) {
            listRows.remove(page.route); requested.remove(page.route)
        } else if (keepRows != null) {
            listRows[page.route] = keepRows
        }
        val displayed = displayedRoute()
        val prevFocus = if (displayed == page.route && old != null) rowAt(old, cursor)?.id else null
        pages[page.route] = page
        while (pages.size > PAGE_CACHE) {
            val eldest = pages.keys.first { it != displayed && it !in path }
            pages.remove(eldest); listRows.remove(eldest); requested.remove(eldest)
            if (pages.keys.all { it == displayed || it in path }) break
        }
        if (displayed != page.route) return
        // A MultiChoice list keeps its working selection on top of the rebuilt row.
        multiWorking?.let { w -> choice?.let { c -> replaceRow(c.row) { r -> (r.kind as? RowKind.MultiChoice)?.let { r.copy(kind = it.copy(selected = w)) } ?: r } } }
        val pub = pages[page.route]!!
        cursor = when {
            prevFocus != null -> indexOf(pub, prevFocus) ?: cursor
            old == null && pane == Pane.PAGE -> {
                val m = memory[page.route]
                val focus = (page.route as? SettingsRoute.Playlist)?.focus
                m?.first?.let { indexOf(pub, it) } ?: m?.second ?: focus?.let { indexOf(pub, it) } ?: cursor
            }
            else -> cursor
        }
        cursor = cursor.coerceIn(0, (total(pub) - 1).coerceAtLeast(0))
        fixTop(pub)
        // Overlays whose row vanished close.
        if (choice != null && findRow(choice!!.row) == null) { choice = null; multiWorking = null; multiInitial = null }
        if (menu != null && findRow(menu!!.row) == null) menu = null
        if (move != null && findRow(move!!.row) == null) move = null
        requestItemsAround(pub)
    }

    private fun onItems(e: SettingsEvent.Items) {
        val page = pages[e.route] ?: return
        if (page.version != e.version || page.list == null) return
        val rows = listRows.getOrPut(e.route) { HashMap() }
        e.rows.forEachIndexed { k, r -> rows[e.from + k] = r }
        pages[e.route] = page.copy(list = mergeList(page.list, rows))
    }

    private fun mergeList(list: PagedList, rows: Map<Int, SettingsRow>): PagedList {
        val byPage = HashMap<Int, MutableList<SettingsRow>>()
        val ps = list.pageSize
        val pagesPresent = rows.keys.map { it / ps }.toSortedSet()
        for (p in pagesPresent) {
            val start = p * ps
            val end = minOf(start + ps, list.count)
            val out = ArrayList<SettingsRow>(end - start)
            for (i in start until end) out += rows[i] ?: break
            if (out.size == end - start) byPage[p] = out
        }
        return list.copy(loaded = list.loaded + byPage)
    }

    private fun requestItemsAround(page: PageModel) {
        val list = page.list ?: return
        if (list.count == 0) return
        val j = (cursor - page.rows.size).coerceAtLeast(0)
        val ps = list.pageSize
        val from = (maxOf(0, j - ITEMS_BEHIND) / ps) * ps
        val to = minOf(list.count, ((j + ITEMS_AHEAD + ps - 1) / ps) * ps)
        val req = requested.getOrPut(page.route) { HashSet() }
        var p = from / ps
        while (p * ps < to) {
            if (!list.loaded.containsKey(p) && req.add(p)) effects.loadItems(page.route, p * ps, minOf(list.count, (p + 1) * ps))
            p++
        }
    }

    private fun requestDisplayed() {
        val r = displayedRoute() ?: return
        if (pages[r] == null) effects.loadPage(r) else pages[r]?.let { requestItemsAround(it) }
    }

    private fun setAdvanced(on: Boolean, emit: Boolean) {
        if (on == advanced) return
        advanced = on
        if (emit) {
            changed += EffectTiming.LIVE
            effects.onIntent(RowIntent.Toggle(ADVANCED_ROW, on))
        }
        val secs = sections()
        if (pane == Pane.SECTIONS && sectionCursor > secs.size) sectionCursor = secs.size
        if (pane == Pane.SECTIONS && emit) sectionCursor = secs.size // the cursor stays on the toggle
        // Advanced rows appear or disappear in every page: rebuild what is shown, drop the rest.
        val displayed = displayedRoute()
        pages.keys.retainAll { it == displayed }
        listRows.keys.retainAll { it == displayed }
        requested.clear()
        val cur = currentRoute()
        if (cur is SettingsRoute.Section && cur.id == SectionId.NETWORK && !on) {
            path = listOf(SettingsRoute.Root); pane = Pane.SECTIONS
            sectionCursor = secs.size
        }
        displayedRoute()?.let { effects.loadPage(it) }
    }

    private fun onTick(now: Long) {
        if (now >= noticeUntilMs) { notice = null; noticeUntilMs = Long.MAX_VALUE }
        if (now >= captureUntilMs) capture?.let { c -> if (c.captured == null) cancelCapture(texts[SettingsText.CAPTURE_TIMED_OUT]) }
    }

    private fun showNotice(text: String) {
        notice = text
        noticeUntilMs = nowMs() + NOTICE_MS
        effects.announce(text)
    }

    // ---------------------------------------------------------------- helpers

    private fun emit(intent: RowIntent, row: SettingsRow?) {
        row?.let { changed += it.effect } ?: SettingsTree.spec(intent.row)?.let { changed += it.effect }
        effects.onIntent(intent)
    }

    private fun closeOverlays() {
        if (choice != null) closeChoice(commit = true)
        menu = null; confirm = null; numberPad = null; move = null
        if (editor != null) editor = null
        if (capture != null) cancelCapture(null)
    }

    private fun sections(): List<SectionId> = SectionId.entries.filter { advanced || it != SectionId.NETWORK }

    private fun currentRoute(): SettingsRoute = path.last()

    /** The route whose page the page pane shows: the focused section's in SECTIONS at the root. */
    private fun displayedRoute(): SettingsRoute? {
        val cur = currentRoute()
        if (pane == Pane.SECTIONS && cur == SettingsRoute.Root) {
            return sections().getOrNull(sectionCursor)?.let { SettingsRoute.Section(it) }
                ?: pages.keys.lastOrNull { it is SettingsRoute.Section }
        }
        return cur
    }

    private fun displayedPage(): PageModel? = displayedRoute()?.let { pages[it] }

    private fun findRow(id: RowId): SettingsRow? {
        val page = displayedPage() ?: return null
        page.rows.firstOrNull { it.id == id }?.let { return it }
        return page.list?.loaded?.values?.firstNotNullOfOrNull { l -> l.firstOrNull { it.id == id } }
    }

    private fun replaceRow(id: RowId, change: (SettingsRow) -> SettingsRow) {
        val route = displayedRoute() ?: return
        val page = pages[route] ?: return
        val i = page.rows.indexOfFirst { it.id == id }
        if (i >= 0) {
            pages[route] = page.copy(rows = page.rows.toMutableList().also { it[i] = change(it[i]) })
            return
        }
        val list = page.list ?: return
        val loaded = list.loaded.mapValues { (_, l) -> l.map { if (it.id == id) change(it) else it } }
        pages[route] = page.copy(list = list.copy(loaded = loaded))
        listRows[route]?.let { m -> m.entries.forEach { e -> if (e.value.id == id) e.setValue(change(e.value)) } }
    }

    private fun announceRow() {
        val page = displayedPage() ?: return
        val row = rowAt(page, cursor) ?: return
        val n = total(page)
        effects.announce(
            if (row.value != null) texts[SettingsText.ANNOUNCE_ROW, row.label, row.value, cursor + 1, n]
            else texts[SettingsText.ANNOUNCE_ROW_NO_VALUE, row.label, cursor + 1, n],
        )
    }

    private fun publish() {
        val displayed = displayedPage()
        val secs = if (detached || path.first() == SettingsRoute.FirstRun) emptyList() else sections()
        val next = SettingsUiState(
            route = currentRoute(),
            stack = path.dropLast(1),
            pane = pane,
            sections = secs,
            sectionCursor = sectionCursor,
            page = displayed,
            cursor = cursor,
            top = top,
            advanced = advanced,
            choice = choice,
            menu = menu,
            confirm = confirm,
            editor = editor,
            numberPad = numberPad,
            move = move,
            capture = capture,
            notice = notice,
        )
        if (next != _ui.value) _ui.value = next
        val deadline = minOf(noticeUntilMs, captureUntilMs)
        _deadline.value = if (deadline == Long.MAX_VALUE) GuideStateMachine.NO_DEADLINE.value else deadline
    }

    companion object {
        val FACTORY = SettingsMachineFactory { effects, nowMs -> SettingsMachineImpl(effects, nowMs) }

        /** The pinned "Show advanced settings" toggle of the sections pane (§3.2.3); `GuidePrefs.advancedSettings`. */
        val ADVANCED_ROW = RowId("settings.advanced")
        /** A context-menu option with this id enters move mode instead of emitting `RowIntent.Menu` (§3.2.2). */
        const val MOVE_OPTION = "move"
        /** Action rows whose id is `capture:<BoxAction.id>:<Press.name>` start a key capture (§3.8.3). */
        const val CAPTURE_PREFIX = "capture:"
        /** A finished capture emits `RowIntent.Menu(row, "bind:<keyCode>")` on OK. */
        const val BIND_PREFIX = "bind:"

        /** `NOTICE_MS` (Appendix B). */
        const val NOTICE_MS = 3_000L
        /** `HOLD_ACCEL_MS` (Appendix B) and the accelerated step. */
        const val HOLD_ACCEL_MS = 600L
        const val HOLD_ACCEL_ROWS = 3
        /** `CAPTURE_TIMEOUT_MS` (Appendix B). */
        const val CAPTURE_TIMEOUT_MS = 10_000L
        /** About eight one-line rows fit the page pane (§3.1). */
        const val VISIBLE_ROWS = 8
        /** CH± / PAGE_UP/DOWN (§3.2.2). */
        const val ROW_PAGE = 8
        const val SECTION_PAGE = 6
        /** Paged lists load `[cursor − 16, cursor + 32)` (§3.2.1). */
        const val ITEMS_BEHIND = 16
        const val ITEMS_AHEAD = 32
        /** The open page plus two cached pages (§5 item 6), plus the open path. */
        const val PAGE_CACHE = 3
        /** Number pad keys (§3.1): 0–8 = 1–9, 9 = ⌫, 10 = 0, 11 = OK. */
        const val PAD_DELETE = 9
        const val PAD_ZERO = 10
        const val PAD_OK = 11

        /** The parent page of [route] when Settings opens directly on it (§3.2.1: `[Root, …parents, route]`). */
        fun parentOf(route: SettingsRoute): SettingsRoute? = when (route) {
            SettingsRoute.Root, SettingsRoute.FirstRun -> null
            is SettingsRoute.Section -> SettingsRoute.Root
            is SettingsRoute.AddSource -> if (route.firstRun) SettingsRoute.FirstRun else SettingsRoute.Section(SectionId.PLAYLISTS)
            is SettingsRoute.PhoneSetup -> if (route.firstRun) SettingsRoute.FirstRun else SettingsRoute.Section(SectionId.PLAYLISTS)
            is SettingsRoute.ImportProgress -> if (route.firstRun) SettingsRoute.FirstRun else SettingsRoute.Section(SectionId.PLAYLISTS)
            is SettingsRoute.GroupsReview -> if (route.firstRun) SettingsRoute.FirstRun else SettingsRoute.Playlist(route.playlistId)
            is SettingsRoute.PlaylistSource -> SettingsRoute.Playlist(route.playlistId)
            is SettingsRoute.NetworkTest -> SettingsRoute.Playlist(route.playlistId)
            is SettingsRoute.FavouriteList -> SettingsRoute.FavouriteLists
            is SettingsRoute.ActionBindings -> SettingsRoute.ButtonMapping
            is SettingsRoute.RestorePreview -> SettingsRoute.Backup
            is SettingsRoute.FixGuide -> if (route.channel != null) SettingsRoute.FixGuide(null) else SettingsRoute.Section(SectionId.GUIDE)
            SettingsRoute.EpgSources, SettingsRoute.SyncStatus -> SettingsRoute.Section(SectionId.GUIDE)
            else -> SettingsTree.sectionOf(route)?.let { SettingsRoute.Section(it) } ?: SettingsRoute.Root
        }

        fun total(page: PageModel): Int = page.rows.size + (page.list?.count ?: 0)

        fun rowAt(page: PageModel, index: Int): SettingsRow? {
            if (index < 0) return null
            if (index < page.rows.size) return page.rows[index]
            val list = page.list ?: return null
            val j = index - page.rows.size
            if (j >= list.count) return null
            return list.loaded[j / list.pageSize]?.getOrNull(j % list.pageSize)
        }

        fun indexOf(page: PageModel, id: RowId): Int? {
            page.rows.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { return it }
            val list = page.list ?: return null
            for ((p, rows) in list.loaded) {
                val k = rows.indexOfFirst { it.id == id }
                if (k >= 0) return page.rows.size + p * list.pageSize + k
            }
            return null
        }

        fun firstEnabled(options: List<ChoiceOption>): Int = options.indexOfFirst { it.enabled }.coerceAtLeast(0)

        /** Next enabled option from [from] in [dir] (no wrap); stays put when none. */
        fun nextEnabled(options: List<ChoiceOption>, from: Int, dir: Int): Int {
            var i = from + dir
            while (i in options.indices) {
                if (options[i].enabled) return i
                i += dir
            }
            return from
        }
    }
}
