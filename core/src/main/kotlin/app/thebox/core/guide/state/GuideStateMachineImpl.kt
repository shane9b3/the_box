package app.thebox.core.guide.state

import app.thebox.core.epg.EpgGenerationInfo
import app.thebox.core.guide.CellKind
import app.thebox.core.guide.DayItem
import app.thebox.core.guide.DeviceClass
import app.thebox.core.guide.EmptyKind
import app.thebox.core.guide.ExitConfirmation
import app.thebox.core.guide.FooterHint
import app.thebox.core.guide.GridContext
import app.thebox.core.guide.GridFocus
import app.thebox.core.guide.GridMove
import app.thebox.core.guide.GridMoveResult
import app.thebox.core.guide.GroupMenuItem
import app.thebox.core.guide.GroupsShortcut
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideDataWindow
import app.thebox.core.guide.GuideEffects
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideFrame
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideNavigator
import app.thebox.core.guide.GuideRenderer
import app.thebox.core.guide.GuideSettings
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.GuideStateMachine
import app.thebox.core.guide.GuideGeometry
import app.thebox.core.guide.epgBasisOf
import app.thebox.core.guide.GuideStateMachineFactory
import app.thebox.core.guide.GuideToast
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.GuideWindowData
import app.thebox.core.guide.HeaderChip
import app.thebox.core.guide.MenuItem
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.OpenGuideOn
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.PanelButton
import app.thebox.core.guide.PanelState
import app.thebox.core.guide.PerformanceMode
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.ReminderAlert
import app.thebox.core.guide.ReminderCardState
import app.thebox.core.guide.RowWatchable
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.SourceSection
import app.thebox.core.guide.TimeLabels
import app.thebox.core.guide.TunedState
import app.thebox.core.guide.Zone
import app.thebox.core.catchup.archive.ArchiveFailure
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.full.EnterReason
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.PlayerStateMachine
import app.thebox.core.input.BoxAction
import app.thebox.core.input.Keys
import app.thebox.core.playback.NextStep
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.SettingsMachine
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.full.PlayerEvent
import app.thebox.core.input.FooterKeyNames
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.parental.ParentalActions
import app.thebox.core.parental.ParentalAnswer
import app.thebox.core.parental.ParentalHost
import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.ParentalRules
import app.thebox.core.parental.ParentalState
import app.thebox.core.parental.PinCheck
import app.thebox.core.parental.PinMessage
import app.thebox.core.parental.PinOwner
import app.thebox.core.parental.PinPadMode
import app.thebox.core.parental.PinPadModel
import app.thebox.core.parental.PinPromptState
import app.thebox.core.parental.PinPurpose
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The guide's state machine (docs/design/m3-guide.md §3.4, §3.9, §3.11; reviewed 2026-10-14, B1, B2, S2, S10a, S11,
 * S16). Pure and main-thread only: every input is a [GuideEvent], every output is [ui], [frame] (+ [frameVersion]) or
 * a [GuideEffects] call. Nothing here blocks or allocates per frame; allocation happens per event.
 *
 * Dispatch order for an action: reminder card (modal) → overlay → global actions (DIGIT, NUMBER_ENTRY,
 * PREVIOUS_CHANNEL, EXIT, OPEN_GUIDE) → mode (FULL, SEARCH, SETTINGS) → zone (§3.4.1).
 *
 * Timers (panel description debounce, number entry, exit hint, group card, reminder card guard/countdown and
 * auto-switch) run on [GuideEvent.Tick]s. [nextDeadlineMs] says when the next one is due so the host posts a Tick
 * then (§2.13 item 4).
 *
 * The host assigns [GuideFrame.rows] (the rows cache), [GuideFrame.labels] and [GuideFrame.images] once after
 * creation; the machine reads rows but never replaces them (§2.13 item 20).
 */
class GuideStateMachineImpl(
    private val effects: GuideEffects,
    private val navigator: GuideNavigator,
    private val labels: TimeLabels,
    @Suppress("unused") private val renderer: GuideRenderer,
    private val nowMs: () -> Long,
) : GuideStateMachine, ParentalHost {

    private val _ui = MutableStateFlow(GuideUiState())
    override val ui: StateFlow<GuideUiState> = _ui.asStateFlow()
    override val frame: GuideFrame = GuideFrame().also { it.labels = labels }
    private val _frameVersion = MutableStateFlow(0L)
    override val frameVersion: StateFlow<Long> = _frameVersion.asStateFlow()
    private val _nextDeadline = MutableStateFlow(Long.MAX_VALUE)
    /** Wall ms of the next timer the machine waits for (post a [GuideEvent.Tick] then); MAX_VALUE = none. */
    override val nextDeadlineMs: StateFlow<Long> = _nextDeadline.asStateFlow()

    /**
     * Last persisted source (`GuideSessionStore.lastSource()`, read on IO by the host) for `OpenGuideOn.LAST`; used on
     * the first [GuideEvent.Sources] when it is still listed (§3.8.4, §2.13 item 10).
     */
    override var lastSourceHint: GuideSource? = null

    /**
     * (M4 commit 0, docs/design/m4-player-ux.md §1.7 item 10, S9) The player machine FULL delegates to; the host sets it
     * before its first act. Set while the mode is already FULL, it is entered at once, so no FULL state runs without it.
     */
    override var full: PlayerStateMachine? = null
        set(value) {
            if (field === value) return // U-L7: re-attaching the same machine (activity recreation) keeps its open layers
            field = value
            // (M5, §0.9) The player asks for the PIN through the guide's one prompt.
            value?.pinGate = this
            // The last dispatch's curMs may be stale (the host attaches between events): read the clock now.
            if (value != null && _ui.value.mode == ScreenMode.FULL) value.enterFull(nowMs(), EnterReason.LAUNCH)
        }

    /**
     * (M4, docs/design/m4-player-ux.md §3.1.1, S5) In FULL the player machine decides whether D-pad LEFT/RIGHT act on
     * DOWN with auto-repeat (a layer is open or archive video is on screen); never while a guide overlay or the
     * reminder card owns the keys.
     */
    override val horizontalRepeat: Boolean
        get() {
            val s = _ui.value
            return s.mode == ScreenMode.FULL && s.overlay == null && s.reminder == null && full?.horizontalRepeat == true
        }

    /**
     * (M5 commit 0, docs/design/m5-setup-settings.md §1.9 item 5, §2.12 item 2) The settings machine SETTINGS delegates
     * to; the host sets it once after create. Set while the mode already is SETTINGS (S11: the activity's view models have
     * no creation order), it is opened on the pending route at once.
     */
    override var settings: SettingsMachine? = null
        set(value) {
            if (field === value) return
            field = value
            value?.pinGate = this
            if (value != null && _ui.value.mode == ScreenMode.SETTINGS) value.open(settingsRoute, settingsFromFull)
        }

    /** (M5, WP-C) What the PIN layer asks the host for beyond `GuideEffects.verifyPin` (PIN creation, Open anyway). */
    override var parentalActions: ParentalActions? = null

    /** (M5, §2.12 item 5, S3) The settings editor is open: the router hands every key but BACK to Compose. */
    override val textInputActive: Boolean
        get() = _ui.value.mode == ScreenMode.SETTINGS && settings?.textInputActive == true

    private val limits = GuideLimits(labels)
    private val number = NumberEntryImpl()

    // ---------------------------------------------------------------- state
    private var u = GuideUiState()
    private var frameDirty = false
    private var curMs = nowMs()
    private var nowMin = (curMs / 60_000L).toInt()

    private var guideSettings = GuideSettings()
    private var device: DeviceClass? = null
    private var lowEnd = false
    private var dataWindow = GuideDataWindow()
    private var generation: EpgGenerationInfo? = null
    private var generationKnown = false
    private var earliest = 0
    private var latest = 0

    private var sources: List<GuideSourceEntry> = emptyList()
    private var sourcesKnown = false
    private var lineup: GuideLineup? = null
    private var requestedSource: GuideSource? = null
    private var lastNonFavourite: GuideSource? = null
    private var pendingFocusRowId = -1L
    private var pendingFull = false
    /** Why [pendingFull] is armed: StartFull (LAUNCH) or Search (SEARCH) (§3.1.1 enterFull reasons). */
    private var pendingFullReason = EnterReason.USER
    /** (M4, owner ruling 2026-10-15) SETTINGS was opened from the quick bar: BACK returns to FULL. */
    private var settingsFromFull = false
    /** (M5 commit 0) The route SETTINGS was opened on (FirstRun: BACK exits while the settings machine is missing, S11). */
    private var settingsRoute: SettingsRoute = SettingsRoute.Root
    private var archive: ArchiveStatus? = null
    /** Identity hash of a live channel tuned from Search: FULL on its first active Tuned (§2.13 item 7). */
    private var pendingFullHash = 0L
    /**
     * (code review 2026-10-14, H-S7) Identity hash of a reminder's channel after "Switch now": FULL on its first active
     * Tuned, so an accept whose channel is gone (toast, no tune) stays in the guide. Any user action clears it.
     */
    private var pendingReminderHash = 0L
    /**
     * (code review 2026-10-14, H-S2) True until the first Tuned with a channel, or the first user action: a startup
     * Tuned that arrives after the first Lineup still moves focus to the tuned row at now (§3.8.4, I-1).
     */
    private var startupFocusPending = true
    /** (code review 2026-10-14, H-B2) Whether the reminder card was visible (GUIDE/FULL) after the last dispatch. */
    private var cardWasVisible = false
    private var lastRowsVersion = Long.MIN_VALUE

    private var tuned = TunedState()
    private var tunedChannel: GuideChannel? = null
    private var tunedIndex = -1
    private var tunedNowRequestedFor = Int.MIN_VALUE
    private var pendingArchiveHash = 0L
    private var returnedFromFull = false
    private val suspect = HashSet<Long>()

    private var holdingVertical = false
    private var holdDirection = 0
    private var holdingHorizontal = false

    private var panelToken = 0L
    private var descDueAtMs = Long.MAX_VALUE
    private var info: ProgrammeInfo? = null
    private var infoHash = 0L

    private var menuToken = 0L
    private var menuTarget: Target? = null
    private var menuFavourite = false
    /** (M4) The channel a TOGGLE_FAVOURITE query is for; the answer toggles instead of opening the menu. */
    private var favouriteToggle: GuideChannel? = null
    private var sheetTarget: Target? = null

    private var numberToken = 0L
    private var numberCommitting = false
    private var numberPreviewFor: String? = null
    private var numberNotFoundUntil = Long.MAX_VALUE
    private var numberMode = ScreenMode.GUIDE

    private var exitHintUntil = Long.MAX_VALUE
    private var groupCardUntil = Long.MAX_VALUE
    private var keysSeen: Set<Int> = emptySet()
    /** (M5, §3.8.4) Keys bound to each hinted action (EffectiveKeyMap ∩ seen); null until the first KeyHints (M3 rule). */
    private var keyHints: Map<BoxAction, List<Int>>? = null

    // ---------------------------------------------------------------- parental (M5, WP-C; §0.9, §3.9)
    private var parental = ParentalState(pinSet = false, unlocked = false, policy = ParentalPolicy())
    private val pad = PinPadModel()
    private var pinPurpose: PinPurpose? = null
    private var pinToken = 0L
    private var pinChecking = false
    private var pinWrong = false
    private var pinMessage: String? = null
    private var pinLockedUntil: Long? = null
    /** The not-protected card is up instead of the pad (FILTERED_NO_PIN, B3); [cardFocused] 0 = Set a PIN, 1 = Open anyway. */
    private var pinCard = false
    private var cardFocused = 0
    /** Guide-owned `SettingsAction` tokens (Lock / Unlock in the menus), negative so they never meet the settings machine's. */
    private val guideActions = HashMap<Long, () -> Unit>()
    private var guideActionToken = 0L
    /** Settings was asked for from FULL while its PIN prompt is open (EnterSettings keeps the M4 return-to-FULL rule). */
    private var pendingSettingsFromFull = false
    /** A relock (access left OPEN) waits for the next Sources to move a locked current group to All channels (§3.9.5). */
    private var relockMovePending = false
    /** True while [dispatch] runs: [request] and [parentalAnswer] from outside publish themselves. */
    private var dispatching = false

    private val watchable = RowWatchable { row ->
        val ch = frame.rows.channel(row)
        val d = if (ch == null) 0 else archiveDays(ch)
        if (d > 0) nowMin - d * GuideLimits.MIN_PER_DAY else nowMin
    }

    init {
        frame.nowMin = nowMin
        recomputeLimits(reclamp = false)
        u = u.copy(footer = footer(null))
        _ui.value = u
    }

    /**
     * (code review 2026-10-14, H-S4; M4 §3.1.1, §7 item 15) In FULL the player machine answers: it handles Play/Pause
     * and FF/RW itself (the media session still receives Assistant/phone transport commands, §8 Q7). True everywhere
     * else.
     */
    override fun handles(action: BoxAction): Boolean {
        val s = _ui.value
        if (s.mode != ScreenMode.FULL || s.overlay != null || s.reminder != null) return true
        return full?.handles(action) ?: true
    }

    // ---------------------------------------------------------------- dispatch

    override fun dispatch(event: GuideEvent) {
        if (dispatching) { dispatchInner(event); return }
        u = _ui.value
        curMs = if (event is GuideEvent.Tick) event.nowMs else nowMs()
        dispatching = true
        try {
            dispatchInner(event)
        } finally {
            dispatching = false
        }
        publish()
    }

    /** Publishes [u], the frame version and the deadline (the end of every dispatch, and of an outside [request]). */
    private fun publish() {
        frame.gridFocused = u.zone == Zone.GRID
        rearmDeferredCard()
        if (u !== _ui.value) _ui.value = u
        if (frameDirty) {
            frameDirty = false
            _frameVersion.value = _frameVersion.value + 1
        }
        _nextDeadline.value = nextDeadline()
    }

    /** Runs [block] on the machine's state: inside a dispatch as is; from outside (the host) with its own load and publish. */
    private inline fun <T> external(block: () -> T): T {
        if (dispatching) return block()
        u = _ui.value
        curMs = nowMs()
        dispatching = true
        val r = try { block() } finally { dispatching = false }
        publish()
        return r
    }

    private fun dispatchInner(event: GuideEvent) {
        when (event) {
            is GuideEvent.Act -> act(event)
            is GuideEvent.Release -> release(event.action)
            is GuideEvent.Tick -> tick()
            is GuideEvent.Sources -> onSources(event.entries)
            is GuideEvent.Lineup -> onLineup(event.lineup)
            is GuideEvent.RowsChanged -> onRowsChanged(event.version)
            is GuideEvent.Window -> onWindow(event.data)
            is GuideEvent.Generation -> onGeneration(event.info)
            is GuideEvent.Info -> onInfo(event.token, event.info)
            is GuideEvent.TunedNow -> if (event.info != u.tunedNow) u = u.copy(tunedNow = event.info)
            is GuideEvent.Tuned -> onTuned(event.state, event.channel)
            is GuideEvent.Reminders -> onReminders(event)
            is GuideEvent.Alert -> onAlert(event.alert)
            is GuideEvent.Favourite -> onFavourite(event.token, event.isFavourite)
            is GuideEvent.Number -> onNumber(event.token, event.channel, event.lineupIndex)
            is GuideEvent.Settings -> onSettings(event.settings, event.device)
            is GuideEvent.Sync -> u = u.copy(sync = event.chip)
            is GuideEvent.KeysSeen -> { keysSeen = event.keyCodes; refreshFooter() }
            is GuideEvent.Metrics -> { frame.metrics = event.metrics; frameDirty = true }
            is GuideEvent.SearchClosed -> onSearchClosed(event.channel, event.tuned, event.archive)
            is GuideEvent.DataWindow -> { dataWindow = event.window; recomputeLimits(reclamp = true) }
            is GuideEvent.MissedReminders -> if (event.count > 0) effects.toast(GuideToast.REMINDERS_MISSED, event.count.toString())
            // M4 (docs/design/m4-player-ux.md §3.1.1, §0.6, owner ruling 2026-10-15)
            is GuideEvent.Archive -> onArchive(event.status)
            is GuideEvent.Player -> number.setCommitAfterMs(event.settings.numberEntryWaitMs)
            GuideEvent.StartFull -> if (somethingTuned()) goFull(EnterReason.LAUNCH) else { pendingFull = true; pendingFullReason = EnterReason.LAUNCH }
            // (M5, §3.2.5) Quick bar → Settings asks for the PIN first when "Lock settings" is on.
            GuideEvent.OpenSettings -> openSettingsGated(SettingsRoute.Root)
            // M5 commit 0 (docs/design/m5-setup-settings.md §1.9 item 5): OpenSetup opens SETTINGS on its route (the
            // first-run decision sends FirstRun, §3.4.1); setup routes never ask for the PIN.
            is GuideEvent.OpenSetup -> openSettings(event.route)
            // M5 WP-C (§3.9)
            is GuideEvent.Parental -> onParental(event.state)
            is GuideEvent.PinChecked -> onPinChecked(event.token, event.check)
            is GuideEvent.KeyHints -> { keyHints = event.hints; refreshFooter() }
            is GuideEvent.Update -> Unit
        }
    }

    // ---------------------------------------------------------------- actions

    private fun act(a: GuideEvent.Act) {
        val action = a.action
        // M5 commit 0 (§0.9, §1.9 item 5): the PIN prompt is modal above every mode and gets the first refusal.
        if (u.pin != null && pinKey(a)) return
        // H-B2: the card is modal only where it is drawn (GUIDE, FULL); in SEARCH/SETTINGS it waits for the mode to return.
        if (u.reminder != null && cardShown()) { cardKey(a); return }
        if (action != BoxAction.BACK) returnedFromFull = false
        startupFocusPending = false
        pendingReminderHash = 0L
        // H-S11: a fresh press (repeat 0) of any key ends every hold, so a lost key-up cannot leave a hold stuck.
        if (a.repeat == 0) clearHolds(action)
        if (u.overlay != null && overlayKey(a)) return
        // M4 §3.1.1 step 3: in FULL the player machine takes the action first; false falls through to the guide.
        if (u.mode == ScreenMode.FULL) { val f = full; if (f != null && f.act(a)) return }
        // M5 commit 0 (§1.9 item 5): in SETTINGS the settings machine acts first; the guide acts on what it declines.
        if (u.mode == ScreenMode.SETTINGS && settingsKey(a)) return
        if (globalKey(a)) return
        when (u.mode) {
            ScreenMode.FULL -> fullKey(a)
            ScreenMode.SEARCH -> if (action == BoxAction.BACK) onSearchClosed(null, tunedFromSearch = false, archive = false)
            ScreenMode.SETTINGS -> when (action) {
                // Owner ruling 2026-10-15: Settings opened from the quick bar returns to FULL.
                BoxAction.BACK -> if (settingsFromFull) { settingsFromFull = false; goFull(EnterReason.USER) } else setMode(ScreenMode.GUIDE)
                // (reviewed 2026-10-16, N11) the Guide key leaves Settings to the guide from any depth; EXIT opens the exit
                // dialog (in the guide, where it is drawn).
                BoxAction.OPEN_GUIDE -> leaveSettingsToGuide()
                BoxAction.EXIT -> { leaveSettingsToGuide(); exitStep() }
                else -> Unit
            }
            ScreenMode.GUIDE -> when (u.zone) {
                Zone.GRID -> gridKey(a)
                Zone.TABS -> tabsKey(a)
                Zone.PANEL -> panelKey(a)
                Zone.HEADER -> headerKey(a)
            }
        }
    }

    /**
     * (M5 commit 0, §1.9 item 5, S11) SETTINGS with the settings machine attached: [SettingsMachine.act] first; true when
     * it consumed the action. Without it (its view model not attached yet) the machine waits: every key is consumed except
     * BACK, which on FirstRun exits the app (product S1) and elsewhere falls through to M4's "leave Settings" (so a missing
     * machine can never trap the user).
     */
    private fun settingsKey(a: GuideEvent.Act): Boolean {
        val s = settings
        if (s != null) return s.act(a)
        // (N11) The Guide key and Exit still leave a waiting Settings (never the first-run Welcome).
        if (a.action == BoxAction.OPEN_GUIDE || a.action == BoxAction.EXIT) return settingsRoute == SettingsRoute.FirstRun
        if (a.action != BoxAction.BACK) return true
        if (settingsRoute == SettingsRoute.FirstRun) { exitApp(); return true }
        return false
    }

    /** (N11) Settings → the guide grid at now, whatever opened it (FULL included). */
    private fun leaveSettingsToGuide() {
        settingsFromFull = false
        setMode(ScreenMode.GUIDE)
        u = u.copy(zone = Zone.GRID, overlay = null)
        jumpToNow()
        refreshFooter()
    }

    private fun release(action: BoxAction) {
        if (u.mode == ScreenMode.FULL) full?.release(action)
        if (u.mode == ScreenMode.SETTINGS) settings?.release(action)
        when (action) {
            BoxAction.UP, BoxAction.DOWN, BoxAction.ZAP_UP, BoxAction.ZAP_DOWN, BoxAction.PAGE_UP, BoxAction.PAGE_DOWN -> {
                if (holdingVertical) {
                    endVerticalHold()
                    frame.focus?.let { effects.ensureWindow(it.topRow, it.windowStartMin, 0) }
                    rebuildPanel(newCell = true)
                }
            }
            BoxAction.LEFT, BoxAction.RIGHT -> {
                if (holdingHorizontal) {
                    holdingHorizontal = false
                    armDescription()
                }
            }
            else -> Unit
        }
    }

    /**
     * Ends both holds on a fresh press (H-S11). A D-pad press of the same axis only drops the flag (its own move rebuilds
     * the window and panel); any other key ends the hold as its release would.
     */
    private fun clearHolds(action: BoxAction) {
        val vertical = action == BoxAction.UP || action == BoxAction.DOWN
        val horizontal = action == BoxAction.LEFT || action == BoxAction.RIGHT
        if (vertical || !holdingVertical) endVerticalHold() else release(BoxAction.DOWN)
        if (holdingHorizontal) { if (horizontal) holdingHorizontal = false else release(BoxAction.RIGHT) }
    }

    private fun endVerticalHold() {
        if (!holdingVertical && frame.bubbleNumber < 0) return
        holdingVertical = false
        holdDirection = 0
        if (frame.bubbleNumber >= 0) { frame.bubbleNumber = -1; frameDirty = true }
    }

    /** DIGIT, NUMBER_ENTRY, PREVIOUS_CHANNEL, EXIT, OPEN_GUIDE (§3.4.1). True when handled. */
    private fun globalKey(a: GuideEvent.Act): Boolean {
        if (u.mode == ScreenMode.SEARCH || u.mode == ScreenMode.SETTINGS) return false
        when (a.action) {
            BoxAction.DIGIT -> if (a.digit in 0..9) openNumber(a.digit) else return false
            BoxAction.NUMBER_ENTRY -> openNumber(-1)
            BoxAction.PREVIOUS_CHANNEL -> effects.previousChannel()
            BoxAction.EXIT -> exitStep()
            BoxAction.OPEN_GUIDE -> if (u.mode == ScreenMode.FULL) returnToGuide() else { u = u.copy(zone = Zone.GRID); jumpToNow() }
            // M5 (§2.12 item 1, §3.2.5): the bindable Search and Settings keys, from the guide and from FULL.
            BoxAction.OPEN_SEARCH -> openSearch()
            BoxAction.OPEN_SETTINGS -> openSettingsGated(SettingsRoute.Root)
            else -> return false
        }
        return true
    }

    // ---------------------------------------------------------------- GRID (§3.4.2)

    private fun gridKey(a: GuideEvent.Act) {
        val f = frame.focus
        when (a.action) {
            BoxAction.LEFT, BoxAction.RIGHT -> {
                // H-S6: an empty source's next step reads "Press Left to choose another group".
                if (f == null) { if (a.action == BoxAction.LEFT && a.repeat == 0 && lineupSize() == 0) openGroups(); return }
                val left = a.action == BoxAction.LEFT
                if (a.repeat > 0) holdingHorizontal = true
                val step = a.repeat > 0 && a.heldMs >= HOLD_TIME_STEP_AFTER_MS
                val move = when {
                    step && left -> GridMove.STEP_BACK
                    step -> GridMove.STEP_FORWARD
                    left -> GridMove.LEFT
                    else -> GridMove.RIGHT
                }
                when (val r = navigator.move(f, move, 0, ctx(a.repeat))) {
                    is GridMoveResult.Moved -> setFocus(r.focus, r.animate)
                    GridMoveResult.LeftEdge -> if (a.repeat == 0) openGroups()
                    GridMoveResult.TopEdge, GridMoveResult.Blocked -> Unit
                }
            }
            BoxAction.UP, BoxAction.DOWN -> {
                val up = a.action == BoxAction.UP
                if (f == null) { if (up && a.repeat == 0) enterZone(Zone.HEADER); return }
                if (a.repeat > 0) { holdingVertical = true; holdDirection = if (up) -1 else 1 }
                when (val r = navigator.move(f, if (up) GridMove.UP else GridMove.DOWN, 0, ctx(a.repeat))) {
                    is GridMoveResult.Moved -> setFocus(r.focus, r.animate)
                    GridMoveResult.TopEdge -> if (a.repeat == 0) enterZone(Zone.HEADER)
                    else -> Unit
                }
            }
            BoxAction.ZAP_UP, BoxAction.PAGE_UP -> page(-1, a.repeat)
            BoxAction.ZAP_DOWN, BoxAction.PAGE_DOWN -> page(1, a.repeat)
            BoxAction.SEEK_BACK -> timeMove(GridMove.TIME_BACK, a.repeat)
            BoxAction.SEEK_FORWARD -> timeMove(GridMove.TIME_FORWARD, a.repeat)
            BoxAction.SELECT -> gridOk()
            BoxAction.CONTEXT_MENU -> openMenu()
            BoxAction.BACK -> backLadder()
            BoxAction.PLAY_PAUSE, BoxAction.PLAY -> if (somethingTuned()) goFull()
            BoxAction.JUMP_TO_NOW -> jumpToNow()
            // M4 actions in the grid (§2.9): programme steps move along the row (never opening Groups at the edge).
            BoxAction.PREV_PROGRAMME, BoxAction.NEXT_PROGRAMME -> if (f != null) {
                val r = navigator.move(f, if (a.action == BoxAction.PREV_PROGRAMME) GridMove.LEFT else GridMove.RIGHT, 0, ctx(a.repeat))
                if (r is GridMoveResult.Moved) setFocus(r.focus, r.animate)
            }
            else -> commonGuideKey(a.action)
        }
    }

    /** Accelerators that act the same from every GUIDE zone (Green, Red, Remind, Groups, Days). */
    private fun commonGuideKey(action: BoxAction) {
        when (action) {
            BoxAction.FAVOURITES -> toggleFavourites()
            BoxAction.START_OVER -> startOver()
            BoxAction.REMIND -> { val t = target(); if (t != null && t.cls == CellClass.FUTURE) toggleReminder(t) }
            BoxAction.GROUPS_PANEL -> openGroups()
            BoxAction.DAY_STRIP -> openDays()
            BoxAction.JUMP_TO_NOW -> jumpToNow()
            BoxAction.TOGGLE_FAVOURITE -> toggleFavourite()
            // AUDIO, SUBTITLES, ASPECT, QUICK_MENU, INFO_BAR, CHANNEL_LIST, PAUSE, VIDEO_DECODER, TIME_SHIFT, … are
            // player actions (M4): no-ops in the guide.
            else -> Unit
        }
    }

    private fun page(dir: Int, repeat: Int) {
        val f = frame.focus ?: return
        if (repeat > 0) { holdingVertical = true; holdDirection = dir }
        val r = navigator.move(f, if (dir < 0) GridMove.PAGE_UP else GridMove.PAGE_DOWN, 0, ctx(repeat))
        if (r is GridMoveResult.Moved) setFocus(r.focus, r.animate)
    }

    private fun timeMove(move: GridMove, repeat: Int) {
        val f = frame.focus ?: return
        val r = navigator.move(f, move, 0, ctx(repeat))
        if (r is GridMoveResult.Moved) setFocus(r.focus, r.animate)
    }

    /** OK in GRID (§3.9.2, last column). */
    private fun gridOk() {
        val t = target() ?: return
        val ch = t.channel ?: return
        when (t.cls) {
            CellClass.ON_NOW, CellClass.NOW_GAP -> okOnNow(ch)
            CellClass.PAST_ARCHIVE -> playArchive(ch, t.info())
            CellClass.PAST -> if (tunedLive(ch)) goFull() else tune(ch)
            CellClass.FUTURE -> openSheet(t)
            CellClass.FUTURE_GAP, CellClass.LOADING -> tune(ch)
        }
    }

    private fun okOnNow(ch: GuideChannel) {
        if (!tunedLive(ch)) { tune(ch); return }
        if (tuned.phase == MiniPhase.ERROR && retryOffered()) effects.retry() else goFull()
    }

    private fun startOver() {
        val t = target() ?: run { effects.toast(GuideToast.NO_CATCHUP); return }
        val ch = t.channel
        when {
            ch != null && t.cls == CellClass.ON_NOW && t.covered -> playArchive(ch, t.info())
            t.cls == CellClass.FUTURE -> toggleReminder(t)
            else -> effects.toast(GuideToast.NO_CATCHUP)
        }
    }

    // ---------------------------------------------------------------- TABS, PANEL, HEADER (§3.4.3)

    private fun tabsKey(a: GuideEvent.Act) {
        when (a.action) {
            BoxAction.LEFT -> if (u.tabFocused > 0) { u = u.copy(tabFocused = u.tabFocused - 1); announceZone() }
            BoxAction.RIGHT -> if (u.tabFocused < TAB_COUNT - 1) { u = u.copy(tabFocused = u.tabFocused + 1); announceZone() }
            BoxAction.SELECT -> when (u.tabFocused) {
                TAB_GUIDE -> enterZone(Zone.GRID)
                TAB_SEARCH -> openSearch()
                else -> openSettingsGated(SettingsRoute.Root)
            }
            BoxAction.DOWN -> enterZone(if (panelButtons().isNotEmpty()) Zone.PANEL else Zone.HEADER)
            BoxAction.BACK -> enterZone(Zone.GRID)
            BoxAction.UP -> Unit
            else -> commonGuideKey(a.action)
        }
    }

    private fun panelKey(a: GuideEvent.Act) {
        val buttons = panelButtons()
        when (a.action) {
            BoxAction.LEFT -> if (u.panelFocused > 0) { u = u.copy(panelFocused = u.panelFocused - 1); announceZone() }
            BoxAction.RIGHT -> if (u.panelFocused < buttons.size - 1) { u = u.copy(panelFocused = u.panelFocused + 1); announceZone() }
            BoxAction.SELECT -> buttons.getOrNull(u.panelFocused)?.let { panelButton(it) }
            BoxAction.UP -> enterZone(Zone.TABS)
            BoxAction.DOWN -> enterZone(Zone.HEADER)
            BoxAction.BACK -> enterZone(Zone.GRID)
            else -> commonGuideKey(a.action)
        }
    }

    private fun headerKey(a: GuideEvent.Act) {
        val chips = u.headerChips
        when (a.action) {
            BoxAction.LEFT -> if (u.headerFocused > 0) { u = u.copy(headerFocused = u.headerFocused - 1); announceZone() }
            BoxAction.RIGHT -> if (u.headerFocused < chips.size - 1) { u = u.copy(headerFocused = u.headerFocused + 1); announceZone() }
            BoxAction.SELECT -> when (chips.getOrNull(u.headerFocused)) {
                HeaderChip.GROUP -> openGroups()
                HeaderChip.NOW -> { enterZone(Zone.GRID); jumpToNow() }
                HeaderChip.DAY -> openDays()
                null -> Unit
            }
            BoxAction.UP -> enterZone(if (panelButtons().isNotEmpty()) Zone.PANEL else Zone.TABS)
            BoxAction.DOWN, BoxAction.BACK -> enterZone(Zone.GRID)
            BoxAction.PLAY_PAUSE -> jumpToNow()
            BoxAction.ZAP_UP, BoxAction.PAGE_UP -> page(-1, a.repeat)
            BoxAction.ZAP_DOWN, BoxAction.PAGE_DOWN -> page(1, a.repeat)
            else -> commonGuideKey(a.action)
        }
    }

    private fun panelButton(b: PanelButton) {
        val t = target() ?: return
        val ch = t.channel ?: return
        when (b) {
            PanelButton.WATCHING -> goFull()
            PanelButton.WATCH_NOW -> okOnNow(ch)
            PanelButton.WATCH, PanelButton.FROM_START -> playArchive(ch, t.info())
            PanelButton.REMIND, PanelButton.REMINDER_SET -> toggleReminder(t)
            PanelButton.WATCH_CHANNEL_NOW -> tune(ch)
            PanelButton.MORE -> openMenu()
        }
    }

    private fun enterZone(z: Zone) {
        u = when (z) {
            Zone.PANEL -> u.copy(zone = z, panelFocused = 0)
            Zone.HEADER -> u.copy(zone = z, headerFocused = 0)
            else -> u.copy(zone = z)
        }
        frame.gridFocused = z == Zone.GRID
        frameDirty = true
        refreshFooter()
        if (z != Zone.GRID) announceZone()
    }

    // ---------------------------------------------------------------- FULL (§3.4.5)

    /**
     * M4 §3.1.1 step 5: what the player machine left to the guide in FULL. M3's zap and SELECT-retry branches moved into
     * the player machine (`PlayerStateMachineImpl`); without one attached (never in production, S9) the rest is a no-op.
     * An unconsumed SELECT over the error card is its Guide button.
     */
    private fun fullKey(a: GuideEvent.Act) {
        when (a.action) {
            BoxAction.BACK -> returnToGuide()
            BoxAction.GROUP_PREV -> cycleGroup(-1)
            BoxAction.GROUP_NEXT -> cycleGroup(1)
            BoxAction.SELECT -> if (full?.ui?.value?.layer == PlayerLayer.ERROR) returnToGuide()
            else -> Unit
        }
    }

    private fun cycleGroup(delta: Int) {
        if (sources.isEmpty()) return
        val cur = currentEntryIndex()
        val next = Math.floorMod((if (cur < 0) 0 else cur) + delta, sources.size)
        val e = sources[next]
        applySource(e.source)
        u = u.copy(groupCard = e.label)
        groupCardUntil = curMs + GROUP_CARD_MS
    }

    /** [reason] decides the player's auto-shown bar (§3.1.1): ARCHIVE, REMINDER, SEARCH, LAUNCH, else USER. */
    private fun goFull(reason: EnterReason = EnterReason.USER) {
        if (u.mode == ScreenMode.FULL) return
        leavingSettings(ScreenMode.FULL)
        u = u.copy(mode = ScreenMode.FULL, overlay = null, groupCard = null)
        groupCardUntil = Long.MAX_VALUE
        settingsFromFull = false
        effects.setFullScreen(true)
        refreshFooter()
        full?.enterFull(curMs, reason)
    }

    /** BACK/OPEN_GUIDE in FULL (B2/F10): GUIDE, viewport MINI, focus home, `returnedFromFull`. */
    private fun returnToGuide() {
        val wasFull = u.mode == ScreenMode.FULL
        u = u.copy(mode = ScreenMode.GUIDE, zone = Zone.GRID, overlay = null, groupCard = null)
        groupCardUntil = Long.MAX_VALUE
        effects.setFullScreen(false)
        if (wasFull) full?.leaveFull()
        goHome()
        returnedFromFull = true
        frameDirty = true
        refreshFooter()
    }

    /** (M5 commit 0, S9) Every exit from SETTINGS, whatever the route, tells the settings machine once. */
    private fun leavingSettings(to: ScreenMode) {
        if (u.mode == ScreenMode.SETTINGS && to != ScreenMode.SETTINGS) settings?.onLeave()
    }

    private fun setMode(mode: ScreenMode) {
        if (u.mode == mode) return
        leavingSettings(mode)
        val wasFull = u.mode == ScreenMode.FULL
        u = u.copy(mode = mode, overlay = if (mode == ScreenMode.FULL) null else u.overlay)
        if (wasFull && mode != ScreenMode.FULL) { effects.setFullScreen(false); full?.leaveFull() }
        if (mode == ScreenMode.GUIDE) settingsFromFull = false
        refreshFooter()
    }

    private fun openSearch() {
        u = u.copy(overlay = null)
        setMode(ScreenMode.SEARCH)
        effects.openSearch()
    }

    /**
     * (M5 commit 0) [route] Root keeps M4's `effects.openSettings()`; other routes use the overload (§2.12 item 2). The
     * settings machine, when attached, opens on the route (else the `settings` setter opens it on attach, S11).
     */
    private fun openSettings(route: SettingsRoute = SettingsRoute.Root) {
        val fromFull = u.mode == ScreenMode.FULL
        u = u.copy(overlay = null)
        settingsRoute = route
        setMode(ScreenMode.SETTINGS)
        settingsFromFull = fromFull
        if (route == SettingsRoute.Root) effects.openSettings() else effects.openSettings(route)
        settings?.open(route, fromFull)
    }

    /**
     * (M5, §3.2.5, §3.9.2) Every entry into Settings except FirstRun and the detached routes: with "Lock settings" on
     * and a PIN set (session locked) the PIN prompt comes first (`EnterSettings(route)`), and Settings opens on success.
     */
    private fun openSettingsGated(route: SettingsRoute) {
        val p = parental
        if (route != SettingsRoute.FirstRun && p.policy.lockSettings && p.pinSet && !p.unlocked) {
            pendingSettingsFromFull = u.mode == ScreenMode.FULL
            openPin(PinPurpose.EnterSettings(route), PinPadMode.VERIFY)
            return
        }
        openSettings(route)
    }

    // ---------------------------------------------------------------- PIN layer (M5, WP-C; §0.9, §3.9.2, §3.9.3)

    /**
     * [app.thebox.core.parental.PinGate]: the player and settings machines (and the host, for controller refusals) ask
     * here. False when nothing opened (no PIN, or the session is already unlocked): the caller proceeds at once. A
     * `SettingsAction` always asks while a PIN is set. In FILTERED_NO_PIN an `OpenSource`/`Tune` opens the not-protected
     * card instead of the pad (B3).
     */
    override fun request(purpose: PinPurpose): Boolean = external {
        val p = parental
        when {
            purpose is PinPurpose.SettingsAction && p.pinSet -> { openPin(purpose, PinPadMode.VERIFY); true }
            p.pinSet && p.unlocked -> false
            p.pinSet -> { openPin(purpose, PinPadMode.VERIFY); true }
            p.access == ParentalAccess.FILTERED_NO_PIN && (purpose is PinPurpose.OpenSource || purpose is PinPurpose.Tune) -> {
                openCard(purpose)
                true
            }
            else -> false
        }
    }

    override fun parentalAnswer(answer: ParentalAnswer) = external {
        when (answer) {
            is ParentalAnswer.PinStored -> {
                val purpose = pinPurpose
                if (u.pin == null || !pinChecking || purpose == null) return@external
                pinChecking = false
                if (answer.ok) grant(purpose)
                else { pad.reset(PinPadMode.CREATE_FIRST); pinMessage = PinMessage.STORE_FAILED; publishPin() }
            }
            is ParentalAnswer.LockedUntil -> {
                if (u.pin == null || pad.mode != PinPadMode.VERIFY) return@external
                val until = answer.untilMs?.takeIf { it > curMs } ?: return@external
                pinLockedUntil = until
                pad.inputDisabled = true
                pinMessage = PinMessage.LOCKED_OUT
                publishPin()
            }
        }
    }

    override fun startPinSetup(change: Boolean) = external {
        val create = GUIDE_ACTION_TOKEN_BASE - guideActionToken++
        guideActions[create] = {}
        if (change && parental.pinSet) {
            val verify = GUIDE_ACTION_TOKEN_BASE - guideActionToken++
            guideActions[verify] = { openPin(PinPurpose.SettingsAction(create), PinPadMode.CREATE_FIRST) }
            openPin(PinPurpose.SettingsAction(verify), PinPadMode.VERIFY)
        } else {
            openPin(PinPurpose.SettingsAction(create), PinPadMode.CREATE_FIRST)
        }
    }

    private fun openPin(purpose: PinPurpose, mode: PinPadMode) {
        cancelOpenPrompt(except = purpose)
        pinPurpose = purpose
        pinCard = false
        pinChecking = false
        pinWrong = false
        pinMessage = null
        pinLockedUntil = null
        pad.reset(mode)
        publishPin()
        // A prompt opened during a lockout shows the countdown at once (§2.6 lockedUntil).
        if (mode == PinPadMode.VERIFY) parentalActions?.queryLockout()
    }

    private fun openCard(purpose: PinPurpose) {
        cancelOpenPrompt(except = purpose)
        pinPurpose = purpose
        pinCard = true
        cardFocused = 0
        pinChecking = false
        pinWrong = false
        pinMessage = PinMessage.NOT_PROTECTED
        pinLockedUntil = null
        pad.reset(PinPadMode.CREATE_FIRST)
        publishPin()
    }

    /** A second request while a prompt is open replaces it; the first owner hears "not granted". */
    private fun cancelOpenPrompt(except: PinPurpose) {
        val old = pinPurpose ?: return
        if (old == except) return
        closePin()
        notifyOwner(old, granted = false)
    }

    private fun publishPin() {
        val purpose = pinPurpose
        if (purpose == null) { if (u.pin != null) u = u.copy(pin = null); return }
        val message = when {
            pinCard -> PinMessage.NOT_PROTECTED
            pad.easyConfirm -> PinMessage.EASY
            else -> pinMessage
        }
        val focused = when {
            pinCard -> cardFocused
            pad.easyConfirm -> pad.easyFocused
            else -> pad.focusedKey
        }
        val s = PinPromptState(
            purpose = purpose, mode = pad.mode, filled = pad.filled, focusedKey = focused, checking = pinChecking,
            wrong = pinWrong, lockedUntilMs = pinLockedUntil, message = message,
        )
        if (s != u.pin) u = u.copy(pin = s)
    }

    private fun closePin() {
        pad.clear()
        pinPurpose = null
        pinCard = false
        pinChecking = false
        pinWrong = false
        pinMessage = null
        pinLockedUntil = null
        if (u.pin != null) u = u.copy(pin = null)
    }

    /** The prompt is modal above every mode (§3.9.3): every key is consumed while it is open. */
    private fun pinKey(a: GuideEvent.Act): Boolean {
        val purpose = pinPurpose ?: run { if (u.pin != null) u = u.copy(pin = null); return false }
        if (pinCard) {
            when (a.action) {
                BoxAction.LEFT -> cardFocused = 0
                BoxAction.RIGHT -> cardFocused = 1
                BoxAction.SELECT -> if (cardFocused == 0) {
                    // Set a PIN: the pad in CREATE_FIRST for the same purpose.
                    pinCard = false
                    pinMessage = null
                    pad.reset(PinPadMode.CREATE_FIRST)
                } else {
                    openAnyway(purpose)
                    return true
                }
                BoxAction.BACK -> { cancelPin(); return true }
                else -> Unit
            }
            publishPin()
            return true
        }
        if (pinChecking) {
            // A check or a store is in flight: BACK still cancels (the answer is then dropped by its token).
            if (a.action == BoxAction.BACK) cancelPin()
            return true
        }
        val r = when (a.action) {
            BoxAction.DIGIT -> if (a.digit in 0..9) pad.digit(a.digit) else PinPadModel.Result.None
            BoxAction.UP -> { pad.move(0, -1); PinPadModel.Result.None }
            BoxAction.DOWN -> { pad.move(0, 1); PinPadModel.Result.None }
            BoxAction.LEFT -> { pad.move(-1, 0); PinPadModel.Result.None }
            BoxAction.RIGHT -> { pad.move(1, 0); PinPadModel.Result.None }
            BoxAction.SELECT -> pad.press()
            BoxAction.BACK -> pad.back()
            else -> PinPadModel.Result.None
        }
        if (pad.filled > 0 && pinMessage != PinMessage.LOCKED_OUT) { pinWrong = false; pinMessage = null }
        when (r) {
            is PinPadModel.Result.Submit -> {
                pinChecking = true
                pinToken++
                effects.verifyPin(pinToken, r.digits)
            }
            is PinPadModel.Result.Created -> {
                pinChecking = true
                val actions = parentalActions
                if (actions != null) actions.storePin(r.digits)
                else { r.digits.fill('\u0000'); pinChecking = false; pinMessage = PinMessage.STORE_FAILED; pad.reset(PinPadMode.CREATE_FIRST) }
            }
            PinPadModel.Result.Mismatch -> pinMessage = PinMessage.MISMATCH
            PinPadModel.Result.Cancel -> { cancelPin(); return true }
            PinPadModel.Result.None -> Unit
        }
        publishPin()
        return true
    }

    private fun onPinChecked(token: Long, check: PinCheck) {
        val purpose = pinPurpose ?: return
        if (token != pinToken || !pinChecking) return
        pinChecking = false
        when (check) {
            PinCheck.Ok -> { grant(purpose); return }
            is PinCheck.Wrong -> { pinWrong = true; pinMessage = PinMessage.wrong(check.attemptsBeforeLockout) }
            is PinCheck.LockedOut -> {
                pinWrong = true
                pinLockedUntil = check.untilMs
                pad.inputDisabled = true
                pinMessage = PinMessage.LOCKED_OUT
            }
            PinCheck.Unavailable -> pinMessage = PinMessage.UNAVAILABLE // fail closed: nothing unlocks (§3.9.6)
        }
        publishPin()
    }

    /** The lockout countdown ended (tick): digits work again. */
    private fun tickPin() {
        val until = pinLockedUntil ?: return
        if (curMs < until) return
        pinLockedUntil = null
        pad.inputDisabled = false
        if (pinMessage == PinMessage.LOCKED_OUT) pinMessage = null
        pinWrong = false
        publishPin()
    }

    /** "Open anyway" (FILTERED_NO_PIN): the group joins openedAnyway until the app closes, then the purpose runs. */
    private fun openAnyway(purpose: PinPurpose) {
        val actions = parentalActions
        when (purpose) {
            is PinPurpose.OpenSource -> actions?.openAnyway(purpose.entry.source)
            is PinPurpose.Tune -> actions?.openAnyway(purpose.channel.identity)
            else -> Unit
        }
        grant(purpose)
    }

    /** The purpose succeeded (§3.9.2 "After success"). */
    private fun grant(purpose: PinPurpose) {
        closePin()
        when (purpose) {
            is PinPurpose.OpenSource -> if (purpose.owner == PinOwner.GUIDE) {
                closeOverlay()
                enterZone(Zone.GRID)
                applySource(purpose.entry.source)
            } else notifyOwner(purpose, granted = true)
            is PinPurpose.Tune -> if (purpose.owner == PinOwner.PLAYER && full != null) notifyOwner(purpose, granted = true)
            else effects.tune(purpose.channel, lineup ?: emptyLineupFor(purpose.channel))
            is PinPurpose.EnterSettings -> {
                pendingSettingsFromFull = false
                openSettings(purpose.route)
            }
            is PinPurpose.SettingsAction -> notifyOwner(purpose, granted = true)
        }
    }

    /** BACK on the prompt: `granted = false`, the owner stays where it was (§3.9.3). */
    private fun cancelPin() {
        val purpose = pinPurpose
        closePin()
        if (purpose != null) notifyOwner(purpose, granted = false)
        pendingSettingsFromFull = false
        // A refused resume / tune in FULL with nothing playing: BACK leaves the guide on All channels (§3.9.2).
        if (purpose is PinPurpose.Tune && purpose.owner == PinOwner.GUIDE && u.mode == ScreenMode.FULL && !somethingTuned()) {
            returnToGuide()
            moveOffLockedSource()
        }
    }

    private fun notifyOwner(purpose: PinPurpose, granted: Boolean) {
        when (purpose) {
            is PinPurpose.SettingsAction -> {
                val t = purpose.token
                val mine = guideActions.remove(t)
                when {
                    mine != null -> if (granted) mine()
                    t >= PLAYER_ACTION_TOKEN_BASE -> full?.dispatch(PlayerEvent.PinResult(purpose, granted))
                    else -> settings?.dispatch(SettingsEvent.PinResult(purpose, granted))
                }
            }
            is PinPurpose.EnterSettings -> Unit
            else -> when (purpose.owner) {
                PinOwner.PLAYER -> full?.dispatch(PlayerEvent.PinResult(purpose, granted))
                PinOwner.SETTINGS -> settings?.dispatch(SettingsEvent.PinResult(purpose, granted))
                PinOwner.GUIDE -> Unit
            }
        }
    }

    /**
     * Lock / Unlock from a menu (§3.9.1, §3.9.2 `SettingsAction`): with a PIN, the PIN first (unless the session is
     * already unlocked); without one, an unlock runs at once, a lock in FILTERED_NO_PIN applies as a filter at once (the
     * effect toasts "Set a PIN to protect it"), and with auto-lock off a lock first creates a PIN.
     */
    private fun lockAction(locking: Boolean, run: () -> Unit) {
        val p = parental
        when {
            p.pinSet && p.unlocked -> run()
            p.pinSet -> {
                val t = GUIDE_ACTION_TOKEN_BASE - guideActionToken++
                guideActions[t] = run
                openPin(PinPurpose.SettingsAction(t), PinPadMode.VERIFY)
            }
            !locking || p.access == ParentalAccess.FILTERED_NO_PIN -> run()
            else -> {
                val t = GUIDE_ACTION_TOKEN_BASE - guideActionToken++
                guideActions[t] = run
                openPin(PinPurpose.SettingsAction(t), PinPadMode.CREATE_FIRST)
            }
        }
    }

    private fun groupMenu(entry: GuideSourceEntry): List<GroupMenuItem> {
        val lock = if (entry.locked) GroupMenuItem.UNLOCK else GroupMenuItem.LOCK
        return listOf(GroupMenuItem.HIDE, lock, GroupMenuItem.MOVE_UP, GroupMenuItem.MOVE_DOWN)
    }

    /**
     * `ParentalSession.state` (§3.9.5). Leaving OPEN arms [relockMovePending]: the next Sources moves a locked current
     * group to All channels of its playlist at now. The host restarts its library flows by `queryKey` and clears the
     * zap list.
     */
    private fun onParental(state: ParentalState) {
        val before = parental
        parental = state
        if (before.access == ParentalAccess.OPEN && state.access != ParentalAccess.OPEN) relockMovePending = true
        // A PIN appeared (set elsewhere) while the not-protected card is up: the card's question is moot.
        if (pinCard && state.pinSet) {
            val p = pinPurpose
            closePin()
            if (p != null) notifyOwner(p, granted = false)
        }
        refreshFooter()
    }

    /** The current source is a locked group the session can no longer open: All channels of its playlist at now. */
    private fun moveOffLockedSource(): Boolean {
        val src = lineup?.source ?: requestedSource
        if (src !is GuideSource.Group) return false
        val entry = sources.firstOrNull { it.source == src }
        val blocked = if (entry == null) parental.access != ParentalAccess.OPEN && src !in parental.openedAnyway
        else ParentalRules.needsDoor(entry, parental)
        if (!blocked) return false
        u = u.copy(zone = Zone.GRID)
        applySource(GuideSource.All(src.playlistId))
        return true
    }

    private fun emptyLineupFor(ch: GuideChannel) =
        GuideLineup(GuideSource.All(ch.identity.playlistId), 0L, LongArray(0), archiveDaysUpperBound = 0)

    // ---------------------------------------------------------------- Back ladder and exit (§3.4.6)

    private fun backLadder() {
        when {
            u.zone != Zone.GRID -> enterZone(Zone.GRID)
            frame.focus != null && !isHome() -> goHome()
            somethingTuned() && !returnedFromFull -> goFull()
            else -> exitStep()
        }
    }

    private fun exitStep() {
        when (guideSettings.exitConfirmation) {
            ExitConfirmation.BACK_TWICE -> {
                u = u.copy(overlay = Overlay.Exit(dialog = false, focused = 0))
                exitHintUntil = curMs + EXIT_CONFIRM_MS
            }
            ExitConfirmation.ASK -> u = u.copy(overlay = Overlay.Exit(dialog = true, focused = 0))
            ExitConfirmation.NONE -> exitApp()
        }
    }

    private fun exitApp() {
        u = u.copy(overlay = null)
        exitHintUntil = Long.MAX_VALUE
        effects.exitApp()
    }

    private fun homeRow(): Int {
        val f = frame.focus ?: return 0
        return if (tunedIndex >= 0) tunedIndex else f.row
    }

    /** Home = the tuned row (else the current row), the cell containing now, the window placed for now (§3.4.6). */
    private fun isHome(): Boolean {
        val f = frame.focus ?: return true
        val row = homeRow()
        if (f.row != row) return false
        if (nowMin < f.cellStart || nowMin >= f.cellEnd) return false
        return f.windowStartMin == navigator.focusAt(row, nowMin, ctx(0)).windowStartMin
    }

    private fun goHome() {
        if (frame.focus == null || lineupSize() == 0) return
        setFocus(navigator.focusAt(homeRow().coerceIn(0, lineupSize() - 1), nowMin, ctx(0)), animate = !lowEnd)
    }

    private fun jumpToNow() {
        val f = frame.focus ?: return
        setFocus(navigator.focusAt(f.row, nowMin, ctx(0)), animate = !lowEnd)
    }

    // ---------------------------------------------------------------- overlays (§3.4.4)

    /** True when the overlay consumed the action; false lets it run normally (exit hint, number entry commit-then-run). */
    private fun overlayKey(a: GuideEvent.Act): Boolean {
        val o = u.overlay ?: return false
        val action = a.action
        when (o) {
            is Overlay.Groups -> groupsKey(o, a)
            is Overlay.Days -> when (action) {
                BoxAction.LEFT -> u = u.copy(overlay = o.copy(focused = nextEnabled(o.items, o.focused, -1)))
                BoxAction.RIGHT -> u = u.copy(overlay = o.copy(focused = nextEnabled(o.items, o.focused, 1)))
                BoxAction.SELECT -> { closeOverlay(); o.items.getOrNull(o.focused)?.let { jumpToDay(it) } }
                BoxAction.BACK -> closeOverlay()
                else -> Unit
            }
            is Overlay.Menu -> when (action) {
                BoxAction.UP -> if (o.focused > 0) u = u.copy(overlay = o.copy(focused = o.focused - 1))
                BoxAction.DOWN -> if (o.focused < o.items.size - 1) u = u.copy(overlay = o.copy(focused = o.focused + 1))
                BoxAction.SELECT -> { closeOverlay(); o.items.getOrNull(o.focused)?.let { menuItem(it) } }
                BoxAction.BACK -> closeOverlay()
                else -> Unit
            }
            is Overlay.Sheet -> when (action) {
                BoxAction.LEFT -> if (o.focused > 0) u = u.copy(overlay = o.copy(focused = 0))
                BoxAction.RIGHT -> if (o.focused < 1) u = u.copy(overlay = o.copy(focused = 1))
                BoxAction.SELECT -> {
                    val t = sheetTarget
                    closeOverlay()
                    if (t?.channel != null) { if (o.focused == 0) toggleReminder(t) else tune(t.channel) }
                }
                BoxAction.BACK -> closeOverlay()
                else -> Unit
            }
            is Overlay.Number -> return numberKey(o, a)
            is Overlay.Exit -> {
                if (!o.dialog) {
                    // Not modal: a second BACK exits, any other action hides the hint and runs.
                    closeOverlay()
                    if (action == BoxAction.BACK) { exitApp(); return true }
                    return false
                }
                when (action) {
                    BoxAction.LEFT -> u = u.copy(overlay = o.copy(focused = 0))
                    BoxAction.RIGHT -> u = u.copy(overlay = o.copy(focused = 1))
                    BoxAction.SELECT -> if (o.focused == 0) exitApp() else closeOverlay()
                    BoxAction.BACK -> closeOverlay()
                    else -> Unit
                }
            }
        }
        return true
    }

    private fun closeOverlay() {
        if (u.overlay is Overlay.Exit) exitHintUntil = Long.MAX_VALUE
        if (u.overlay is Overlay.Number) resetNumber()
        u = u.copy(overlay = null)
        sheetTarget = null
    }

    private fun groupsKey(o: Overlay.Groups, a: GuideEvent.Act) {
        val total = o.shortcuts.size + o.entries.size
        val menu = o.menu
        if (menu != null) {
            when (a.action) {
                BoxAction.UP -> if (o.menuFocused > 0) u = u.copy(overlay = o.copy(menuFocused = o.menuFocused - 1))
                BoxAction.DOWN -> if (o.menuFocused < menu.size - 1) u = u.copy(overlay = o.copy(menuFocused = o.menuFocused + 1))
                BoxAction.SELECT -> {
                    val entry = o.entries.getOrNull(o.focused - o.shortcuts.size)
                    val item = menu.getOrNull(o.menuFocused)
                    u = u.copy(overlay = o.copy(menu = null, menuFocused = 0))
                    if (entry != null && item != null) {
                        when (item) {
                            // M5 (§3.9.1, §3.9.2): Lock / Unlock ask for the PIN when one is set (SettingsAction).
                            GroupMenuItem.LOCK -> lockAction(locking = true) { effects.setGroupLocked(entry, true) }
                            GroupMenuItem.UNLOCK -> lockAction(locking = false) { effects.setGroupLocked(entry, false) }
                            else -> item.toAction()?.let { effects.groupAction(entry, it) }
                        }
                    }
                }
                BoxAction.BACK -> u = u.copy(overlay = o.copy(menu = null, menuFocused = 0))
                else -> Unit
            }
            return
        }
        when (a.action) {
            BoxAction.UP -> if (o.focused > 0) { u = u.copy(overlay = o.copy(focused = o.focused - 1)); announceOverlay() }
            BoxAction.DOWN -> if (o.focused < total - 1) { u = u.copy(overlay = o.copy(focused = o.focused + 1)); announceOverlay() }
            BoxAction.RIGHT, BoxAction.BACK -> closeOverlay()
            BoxAction.SELECT -> {
                if (o.focused < o.shortcuts.size) {
                    when (o.shortcuts[o.focused]) {
                        GroupsShortcut.SEARCH -> openSearch()
                        GroupsShortcut.SETTINGS -> openSettingsGated(SettingsRoute.Root)
                    }
                } else {
                    val entry = o.entries.getOrNull(o.focused - o.shortcuts.size) ?: return
                    // M5 (§3.9.2): a locked group asks for the PIN (or shows the not-protected card) first.
                    if (ParentalRules.needsDoor(entry, parental) && request(PinPurpose.OpenSource(entry, PinOwner.GUIDE))) return
                    closeOverlay()
                    enterZone(Zone.GRID)
                    applySource(entry.source)
                }
            }
            BoxAction.CONTEXT_MENU -> {
                val entry = o.entries.getOrNull(o.focused - o.shortcuts.size) ?: return
                if (entry.section == SourceSection.GROUP) u = u.copy(overlay = o.copy(menu = groupMenu(entry), menuFocused = 0))
            }
            BoxAction.ZAP_UP, BoxAction.PAGE_UP, BoxAction.ZAP_DOWN, BoxAction.PAGE_DOWN -> {
                if (o.entries.isEmpty()) return
                val dir = if (a.action == BoxAction.ZAP_UP || a.action == BoxAction.PAGE_UP) -1 else 1
                val from = if (o.focused >= o.shortcuts.size) o.focused - o.shortcuts.size else o.current
                val next = (from + dir).coerceIn(0, o.entries.size - 1)
                if (next == from && o.focused >= o.shortcuts.size) return
                applySource(o.entries[next].source)
                u = u.copy(overlay = o.copy(focused = o.shortcuts.size + next, current = next))
                announceOverlay()
            }
            else -> Unit
        }
    }

    private fun openGroups() {
        val cur = currentEntryIndex()
        u = u.copy(
            overlay = Overlay.Groups(
                shortcuts = SHORTCUTS, entries = sources,
                focused = SHORTCUTS.size + maxOf(cur, 0).coerceAtMost(maxOf(sources.size - 1, 0)).let { if (sources.isEmpty()) -SHORTCUTS.size else it },
                current = cur, menu = null, menuFocused = 0,
            ),
        )
        announceOverlay()
    }

    private fun openDays() {
        val items = limits.days(nowMin, earliest, latest, generation?.windowFromMin, generation?.windowToMin)
        val anchorDay = labels.dayOf(frame.focus?.anchorMin ?: nowMin)
        val idx = items.indexOfFirst { it.day == anchorDay && !it.primeTime }.coerceAtLeast(0)
        u = u.copy(overlay = Overlay.Days(items, idx))
    }

    private fun nextEnabled(items: List<DayItem>, from: Int, dir: Int): Int {
        var i = from + dir
        while (i in items.indices) { if (items[i].enabled) return i; i += dir }
        return from
    }

    private fun jumpToDay(item: DayItem) {
        val f = frame.focus ?: return
        val target = limits.dayTarget(item, f.anchorMin, nowMin)
        val r = navigator.move(f, GridMove.TO_TIME, target, ctx(0))
        if (r is GridMoveResult.Moved) setFocus(r.focus, r.animate)
    }

    // ---------------------------------------------------------------- menu, sheet, reminders, favourites (§3.9)

    private fun openMenu() {
        val t = target() ?: return
        val ch = t.channel ?: return
        favouriteToggle = null
        menuToken++
        menuTarget = t
        effects.queryFavourite(menuToken, ch)
    }

    private fun onFavourite(token: Long, isFavourite: Boolean) {
        if (token != menuToken) return
        favouriteToggle?.let { ch -> favouriteToggle = null; effects.setFavourite(ch, !isFavourite); return }
        val t = menuTarget ?: return
        menuFavourite = isFavourite
        val items = ArrayList<MenuItem>(4)
        if (t.cls == CellClass.PAST_ARCHIVE) items += MenuItem.WATCH
        if (t.cls == CellClass.ON_NOW && t.covered) items += MenuItem.WATCH_FROM_START
        if (t.cls == CellClass.FUTURE) items += if (reminderSet(t)) MenuItem.CANCEL_REMINDER else MenuItem.REMIND
        items += if (isFavourite) MenuItem.REMOVE_FAVOURITE else MenuItem.ADD_FAVOURITE
        items += MenuItem.HIDE_CHANNEL
        // M5 (§3.9.1): a channel listed while locked content is filtered is not locked; while unlocked (OPEN) the
        // machine cannot tell, so both are offered. Then the Settings routes of §3.2.5.
        items += MenuItem.LOCK_CHANNEL
        if (parental.access == ParentalAccess.OPEN) items += MenuItem.UNLOCK_CHANNEL
        items += MenuItem.FIX_GUIDE_DATA
        items += MenuItem.CHANNEL_OPTIONS
        if (u.mode != ScreenMode.GUIDE || u.reminder != null) return
        u = u.copy(overlay = Overlay.Menu(items, 0))
    }

    private fun menuItem(item: MenuItem) {
        val t = menuTarget ?: return
        val ch = t.channel ?: return
        when (item) {
            MenuItem.WATCH, MenuItem.WATCH_FROM_START -> playArchive(ch, t.info())
            MenuItem.REMIND -> effects.setReminder(ch, t.info())
            MenuItem.CANCEL_REMINDER -> effects.cancelReminder(ch, t.rawStart)
            // Toast ownership (§2.13 item 9): the effects toast these once the library write has succeeded.
            MenuItem.ADD_FAVOURITE -> effects.setFavourite(ch, true)
            MenuItem.REMOVE_FAVOURITE -> effects.setFavourite(ch, false)
            MenuItem.HIDE_CHANNEL -> effects.hideChannel(ch)
            // M5 (§3.9.1, §3.9.2, §3.2.5)
            MenuItem.LOCK_CHANNEL -> lockAction(locking = true) { effects.setChannelLocked(ch, true) }
            MenuItem.UNLOCK_CHANNEL -> lockAction(locking = false) { effects.setChannelLocked(ch, false) }
            MenuItem.FIX_GUIDE_DATA -> openSettingsGated(SettingsRoute.FixGuide(ch.identity))
            MenuItem.CHANNEL_OPTIONS -> openSettingsGated(SettingsRoute.Channel(ch.identity))
        }
    }

    private fun openSheet(t: Target) {
        sheetTarget = t
        u = u.copy(overlay = Overlay.Sheet(reminderSet(t), 0))
    }

    private fun reminderSet(t: Target): Boolean = t.channel != null && frame.reminders.contains(t.channel.identityHash, t.rawStart)

    /** Toggle on a future PROGRAMME; the effects implementation shows the REMINDER_SET(_FIRST_TIME)/CANCELLED toasts. */
    private fun toggleReminder(t: Target) {
        val ch = t.channel ?: return
        if (t.cls != CellClass.FUTURE) return
        if (reminderSet(t)) effects.cancelReminder(ch, t.rawStart) else effects.setReminder(ch, t.info())
    }

    private fun toggleFavourites() {
        val cur = lineup?.source ?: requestedSource
        if (cur is GuideSource.Favourites) {
            val back = lastNonFavourite ?: firstAll()?.source ?: return
            applySource(back)
            return
        }
        val fav = sources.firstOrNull { it.section == SourceSection.FAVOURITES }
        if (fav == null) { effects.toast(GuideToast.NO_FAVOURITES); return }
        applySource(fav.source)
    }

    private fun onReminders(e: GuideEvent.Reminders) {
        frame.reminders = e.index
        frameDirty = true
        val o = u.overlay
        val t = sheetTarget
        if (o is Overlay.Sheet && t != null) u = u.copy(overlay = o.copy(reminderSet = reminderSet(t)))
        rebuildPanel(newCell = false)
    }

    // ---------------------------------------------------------------- reminder card (modal, S2)

    private fun onAlert(alert: ReminderAlert?) {
        if (alert == null) { u = u.copy(reminder = null); return }
        val cur = u.reminder
        if (cur != null && cur.alert.reminderId == alert.reminderId) { u = u.copy(reminder = cur.copy(alert = alert)); return }
        u = u.copy(reminder = ReminderCardState(alert, focused = 0, secondsLeft = secondsLeft(alert), inputFromMs = curMs + REMINDER_CARD_GUARD_MS))
    }

    private fun secondsLeft(alert: ReminderAlert): Int {
        val shownAt = u.reminder?.let { it.inputFromMs - REMINDER_CARD_GUARD_MS } ?: curMs
        val end = maxOf(alert.startAtMs, shownAt) + REMINDER_CARD_MS
        return ((end - curMs + 999) / 1000).toInt().coerceAtLeast(0)
    }

    private fun secondsLeftFrom(c: ReminderCardState): Int {
        val end = maxOf(c.alert.startAtMs, c.inputFromMs - REMINDER_CARD_GUARD_MS) + REMINDER_CARD_MS
        return ((end - curMs + 999) / 1000).toInt().coerceAtLeast(0)
    }

    private fun cardKey(a: GuideEvent.Act) {
        val c = u.reminder ?: return
        if (curMs < c.inputFromMs) return
        when (a.action) {
            BoxAction.LEFT -> u = u.copy(reminder = c.copy(focused = 0))
            BoxAction.RIGHT -> u = u.copy(reminder = c.copy(focused = 1))
            BoxAction.SELECT -> answerCard(c, accept = c.focused == 0)
            BoxAction.BACK -> answerCard(c, accept = false)
            else -> Unit
        }
    }

    /**
     * "Switch now": the host tunes the alert's channel on accept (it owns the identity → channel lookup). (code review
     * 2026-10-14, H-S7) FULL follows the first active Tuned of that channel, like Search's (§2.13 item 7): an accept
     * whose channel is gone toasts and stays in the guide. Already playing it live: FULL at once.
     */
    private fun answerCard(c: ReminderCardState, accept: Boolean) {
        u = u.copy(reminder = null)
        effects.answerReminder(c.alert, accept)
        if (!accept) return
        closeOverlay()
        val hash = GuideChannel.hashOf(c.alert.identity)
        if (tuned.identityHash == hash && !tuned.isArchive && somethingTuned() && tuned.phase != MiniPhase.ERROR) goFull(EnterReason.REMINDER)
        else pendingReminderHash = hash
    }

    /** H-B2: the card is drawn (and modal) only in GUIDE and FULL. */
    private fun cardShown(): Boolean = u.mode == ScreenMode.GUIDE || u.mode == ScreenMode.FULL

    /** H-B2: a card that waited in SEARCH/SETTINGS gets a fresh guard and countdown when it becomes visible. */
    private fun rearmDeferredCard() {
        val c = u.reminder
        val visible = c != null && cardShown()
        if (visible && !cardWasVisible && c != null && c.inputFromMs - REMINDER_CARD_GUARD_MS < curMs) {
            val shown = c.copy(inputFromMs = curMs + REMINDER_CARD_GUARD_MS)
            u = u.copy(reminder = shown.copy(secondsLeft = secondsLeftFrom(shown)))
        }
        cardWasVisible = visible
    }

    // ---------------------------------------------------------------- number entry (§3.9.4)

    private fun openNumber(digit: Int) {
        val o = u.overlay
        if (o is Overlay.Number && !o.notFound) {
            if (digit >= 0) number.digit(digit, curMs)
            numberCommitting = false
            u = u.copy(overlay = numberOverlay(o.preview))
            return
        }
        resetNumber()
        numberMode = u.mode
        if (digit >= 0) number.digit(digit, curMs) else number.startCursor()
        u = u.copy(overlay = numberOverlay(null))
    }

    private fun numberOverlay(preview: GuideChannel?, notFound: Boolean = false) =
        Overlay.Number(number.digits, preview, notFound, number.cursorDigit)

    private fun numberKey(o: Overlay.Number, a: GuideEvent.Act): Boolean {
        if (o.notFound) {
            closeOverlay()
            return a.action == BoxAction.BACK
        }
        when (a.action) {
            BoxAction.DIGIT -> if (a.digit in 0..9) {
                if (number.digit(a.digit, curMs)) { numberCommitting = false; u = u.copy(overlay = numberOverlay(o.preview)) }
            }
            BoxAction.UP -> { number.adjust(1); u = u.copy(overlay = numberOverlay(o.preview)) }
            BoxAction.DOWN -> { number.adjust(-1); u = u.copy(overlay = numberOverlay(o.preview)) }
            BoxAction.RIGHT -> { number.advance(); u = u.copy(overlay = numberOverlay(o.preview)) }
            BoxAction.SELECT -> commitNumber()
            BoxAction.BACK -> closeOverlay()
            else -> { commitNumber(); return false }
        }
        return true
    }

    private fun commitNumber() {
        val v = number.commitValue()
        if (v == null) { closeOverlay(); return }
        numberCommitting = true
        numberToken++
        effects.lookupNumber(numberToken, v)
    }

    private fun resetNumber() {
        number.clear()
        numberCommitting = false
        numberPreviewFor = null
        numberNotFoundUntil = Long.MAX_VALUE
        numberToken++
    }

    private fun onNumber(token: Long, channel: GuideChannel?, lineupIndex: Int) {
        if (token != numberToken) return
        val o = u.overlay as? Overlay.Number
        if (!numberCommitting) {
            if (o != null) u = u.copy(overlay = o.copy(preview = channel))
            return
        }
        numberCommitting = false
        if (channel == null) {
            u = u.copy(overlay = Overlay.Number(number.digits, null, notFound = true, cursorDigit = -1))
            numberNotFoundUntil = curMs + NUMBER_NOT_FOUND_MS
            return
        }
        if (o != null) closeOverlay() else resetNumber()
        if (numberMode == ScreenMode.FULL || u.mode == ScreenMode.FULL) { tune(channel); return }
        if (lineupIndex >= 0 && lineupIndex < lineupSize()) {
            u = u.copy(zone = Zone.GRID)
            setFocus(navigator.focusAt(lineupIndex, nowMin, ctx(0)))
        } else {
            u = u.copy(zone = Zone.GRID)
            applySource(GuideSource.All(channel.identity.playlistId), focusRowId = channel.rowId)
        }
    }

    // ---------------------------------------------------------------- tune, archive

    private fun tune(ch: GuideChannel) {
        val l = lineup ?: return
        effects.tune(ch, l)
    }

    /** Catch-up (§3.13.6): the launcher may fail (toast by the host); FULL follows the first archive [GuideEvent.Tuned]. */
    private fun playArchive(ch: GuideChannel, info: ProgrammeInfo) {
        pendingArchiveHash = ch.identityHash
        effects.playArchive(ch, info)
    }

    private fun somethingTuned(): Boolean = tuned.phase != MiniPhase.IDLE && tuned.phase != MiniPhase.STOPPED

    private fun tunedLive(ch: GuideChannel): Boolean =
        tuned.identity != null && !tuned.isArchive && tuned.identityHash == ch.identityHash && somethingTuned()

    private fun retryOffered(): Boolean = tuned.error?.defaultSteps?.contains(NextStep.RETRY) == true

    private fun archiveDays(ch: GuideChannel): Int = if (ch.archiveDays > 0 && ch.identityHash !in suspect) ch.archiveDays else 0

    // ---------------------------------------------------------------- data events (§3.4.9)

    private fun onSources(entries: List<GuideSourceEntry>) {
        sources = entries
        val first = !sourcesKnown
        sourcesKnown = true
        val o = u.overlay
        if (o is Overlay.Groups) {
            val focusedSource = o.entries.getOrNull(o.focused - o.shortcuts.size)?.source
            val cur = currentEntryIndex()
            val newFocused = if (focusedSource == null) o.focused.coerceAtMost(SHORTCUTS.size + entries.size - 1)
            else SHORTCUTS.size + entries.indexOfFirst { it.source == focusedSource }.let { if (it < 0) maxOf(cur, 0) else it }
            u = u.copy(overlay = o.copy(entries = entries, focused = newFocused.coerceAtLeast(0), current = cur))
        }
        if (first && lineup == null && requestedSource == null) {
            val initial = initialSource(entries)
            if (initial != null) applySource(initial, persist = false)
        }
        // (M5, §3.9.5) After a relock, a locked current group moves to All channels of its playlist at now. The flag stays
        // until the re-queried entries show the group locked (an answer still in flight from OPEN lists it unlocked).
        if (relockMovePending && parental.access != ParentalAccess.OPEN) {
            val src = lineup?.source ?: requestedSource
            val entry = sources.firstOrNull { it.source == src }
            if (src !is GuideSource.Group || (entry != null && !entry.locked && src !in parental.openedAnyway)) Unit
            else if (moveOffLockedSource()) relockMovePending = false
        } else if (parental.access == ParentalAccess.OPEN) relockMovePending = false
        refreshSourceLabel()
        refreshEmpty()
    }

    /** §3.8.4: LAST → the last source if still listed; FAVOURITES → the default list if any; else All(first playlist). */
    private fun initialSource(entries: List<GuideSourceEntry>): GuideSource? {
        val all = entries.firstOrNull { it.section == SourceSection.ALL }?.source
        return when (guideSettings.openGuideOn) {
            OpenGuideOn.LAST -> lastSourceHint?.takeIf { h -> entries.any { it.source == h } } ?: all
            OpenGuideOn.FAVOURITES -> entries.firstOrNull { it.section == SourceSection.FAVOURITES && it.count > 0 }?.source ?: all
            OpenGuideOn.ALL -> all
        }
    }

    private fun applySource(source: GuideSource, focusRowId: Long = -1, persist: Boolean = true) {
        requestedSource = source
        pendingFocusRowId = focusRowId
        if (source !is GuideSource.Favourites) lastNonFavourite = source
        effects.loadLineup(source)
        if (persist) effects.persistSource(source)
        refreshSourceLabel()
    }

    private fun onLineup(l: GuideLineup) {
        val prev = lineup
        val prevRowId = prev?.let { p -> frame.focus?.row?.takeIf { it in p.rowIds.indices }?.let { p.rowIds[it] } } ?: -1L
        lineup = l
        if (l.source !is GuideSource.Favourites) lastNonFavourite = l.source
        if (requestedSource == null) requestedSource = l.source
        tunedIndex = indexOf(l, tuned.rowId)
        recomputeLimits(reclamp = false)
        refreshSourceLabel()
        if (l.rowIds.isEmpty()) {
            frame.focus = null
            frameDirty = true
            u = u.copy(panel = null)
            refreshEmpty()
            return
        }
        val pending = if (pendingFocusRowId >= 0) indexOf(l, pendingFocusRowId) else -1
        val sameSource = prev != null && prev.source == l.source && prevRowId >= 0
        val kept = if (sameSource) indexOf(l, prevRowId) else -1
        val f = frame.focus
        val newFocus = when {
            pending >= 0 -> navigator.focusAt(pending, nowMin, ctx(0))
            kept >= 0 && f != null -> {
                val moved = f.copy(row = kept, topRow = clampTop(f.topRow + (kept - f.row), kept, l.rowIds.size))
                (navigator.move(moved, GridMove.RESOLVE, 0, ctx(0)) as? GridMoveResult.Moved)?.focus ?: moved
            }
            // H-S1 (§3.9.3): the focused row left the same source (hidden, unfavourited, purged): focus the same index
            // at the current anchor instead of jumping to row 0.
            sameSource && f != null -> {
                val row = minOf(f.row, l.rowIds.size - 1)
                val moved = f.copy(row = row, topRow = clampTop(f.topRow, row, l.rowIds.size))
                (navigator.move(moved, GridMove.RESOLVE, 0, ctx(0)) as? GridMoveResult.Moved)?.focus ?: moved
            }
            prev == null && f == null && tunedIndex >= 0 -> navigator.focusAt(tunedIndex, nowMin, ctx(0))
            else -> navigator.focusAt(0, nowMin, ctx(0))
        }
        if (prev == null && f == null && tunedIndex >= 0 && pending < 0) startupFocusPending = false
        if (pending >= 0 || l.source == requestedSource) pendingFocusRowId = -1
        setFocus(newFocus)
        refreshEmpty()
    }

    private fun clampTop(top: Int, row: Int, size: Int): Int =
        top.coerceIn(row - (VISIBLE_ROWS - 1), row).coerceIn(0, maxOf(0, size - VISIBLE_ROWS))

    private fun onRowsChanged(version: Long) {
        lastRowsVersion = version
        frameDirty = true
        val f = frame.focus ?: return
        val p = u.panel
        if ((p == null || p.channelName.isEmpty()) && frame.rows.channel(f.row) != null && !holdingVertical) rebuildPanel(newCell = true)
        else if (frame.bubbleNumber >= 0) updateBubble()
    }

    private fun onWindow(data: GuideWindowData) {
        val l = lineup ?: return
        if (data.lineupVersion != l.version) return
        // S10a, amended 2026-10-14 (§2.13 item 20, device bug G1): an older rows version is stale only when a row's
        // (epg ref, shift) basis changed since. Two blocks landing back to back made the pager (correctly) skip a
        // re-query while the machine dropped its only snapshot, leaving the grid LOADING until the next key.
        if (lastRowsVersion != Long.MIN_VALUE && data.rowsVersion < lastRowsVersion && !basisUnchanged(data)) return
        frame.data = data
        frameDirty = true
        val f = frame.focus ?: return
        val r = navigator.move(f, GridMove.RESOLVE, 0, ctx(0))
        if (r is GridMoveResult.Moved) {
            val nf = r.focus
            frame.focus = nf
            frame.windowStartMin = nf.windowStartMin
            frame.topRow = nf.topRow
            val changed = nf.cellStart != f.cellStart || nf.cellEnd != f.cellEnd || nf.cellKind != f.cellKind
            if ((changed || u.panel?.noInfo == true || u.panel?.title.isNullOrEmpty()) && !holdingVertical) rebuildPanel(newCell = changed || u.panel?.description == null)
        }
    }

    private fun basisUnchanged(data: GuideWindowData): Boolean {
        val rows = frame.rows
        for (i in 0 until data.rowCount) {
            if (epgBasisOf(rows.channel(data.firstRow + i)) != data.rowEpgBasis[i]) return false
        }
        return true
    }

    private fun onGeneration(gen: EpgGenerationInfo?) {
        generation = gen
        generationKnown = true
        recomputeLimits(reclamp = true)
        panelToken++
        info = null
        armDescription()
        refreshEmpty()
    }

    private fun onInfo(token: Long, loaded: ProgrammeInfo?) {
        if (token != panelToken) return
        val p = u.panel ?: return
        info = loaded
        infoHash = target()?.channel?.identityHash ?: 0L
        u = u.copy(panel = p.copy(description = loaded?.description ?: "", genre = loaded?.genre))
    }

    private fun onTuned(state: TunedState, channel: GuideChannel?) {
        val identityChanged = state.identity != tuned.identity
        tuned = state
        tunedChannel = channel
        if (frame.tunedHash != state.identityHash) { frame.tunedHash = state.identityHash; frameDirty = true }
        tunedIndex = lineup?.let { indexOf(it, state.rowId) } ?: -1
        // H-S9: keep the slices identical when nothing changed (the host re-sends Tuned for every video/settings
        // emission), so the mini overlay is skipped by Compose's strong skipping.
        val tunedSlice = if (state == u.tuned) u.tuned else state
        val channelSlice = if (channel == u.tunedChannel) u.tunedChannel else channel
        val nowSlice = if (identityChanged) null else u.tunedNow
        if (tunedSlice !== u.tuned || channelSlice !== u.tunedChannel || nowSlice !== u.tunedNow) {
            u = u.copy(tuned = tunedSlice, tunedChannel = channelSlice, tunedNow = nowSlice)
        }
        if (identityChanged && channel != null) { tunedNowRequestedFor = nowMin; effects.loadTunedNow(channel, nowMin) }
        // §0.6 (B1): the hollow ↺ follows ArchiveStatus FAILED(NOT_IN_ARCHIVE) only (onArchive), never a raw probe error.
        if (pendingArchiveHash != 0L && state.identityHash == pendingArchiveHash && state.isArchive) {
            when (state.phase) {
                MiniPhase.CONNECTING, MiniPhase.BUFFERING, MiniPhase.RECONNECTING, MiniPhase.PLAYING, MiniPhase.PAUSED -> { pendingArchiveHash = 0; if (u.mode == ScreenMode.GUIDE) goFull(EnterReason.ARCHIVE) }
                // §0.6 / §5.1: a request still starting or probing does not end the wait; ArchiveStatus FAILED does.
                MiniPhase.ERROR, MiniPhase.STOPPED -> if (!archiveInFlight(state.identityHash)) pendingArchiveHash = 0
                MiniPhase.IDLE -> Unit
            }
        }
        if (pendingFull && state.phase != MiniPhase.IDLE && state.phase != MiniPhase.STOPPED) { pendingFull = false; goFull(pendingFullReason) }
        if (pendingReminderHash != 0L && state.identityHash == pendingReminderHash && !state.isArchive) {
            when (state.phase) {
                MiniPhase.CONNECTING, MiniPhase.BUFFERING, MiniPhase.RECONNECTING, MiniPhase.PLAYING, MiniPhase.PAUSED -> { pendingReminderHash = 0; if (u.mode == ScreenMode.GUIDE) goFull(EnterReason.REMINDER) }
                MiniPhase.ERROR, MiniPhase.STOPPED -> pendingReminderHash = 0
                MiniPhase.IDLE -> Unit
            }
        }
        // H-S2: the startup Tuned lost the race with the first Lineup: focus the tuned row at now (§3.8.4, I-1).
        if (startupFocusPending && state.identity != null) {
            startupFocusPending = false
            val f = frame.focus
            if (identityChanged && tunedIndex >= 0 && f != null && f.row != tunedIndex && u.mode == ScreenMode.GUIDE) {
                setFocus(navigator.focusAt(tunedIndex, nowMin, ctx(0)))
            }
        }
        if (pendingFullHash != 0L && state.identityHash == pendingFullHash && !state.isArchive) {
            when (state.phase) {
                MiniPhase.CONNECTING, MiniPhase.BUFFERING, MiniPhase.RECONNECTING, MiniPhase.PLAYING, MiniPhase.PAUSED -> { pendingFullHash = 0; if (u.mode == ScreenMode.GUIDE) goFull(EnterReason.SEARCH) }
                MiniPhase.ERROR, MiniPhase.STOPPED -> pendingFullHash = 0
                MiniPhase.IDLE -> Unit
            }
        }
        if (!holdingVertical) rebuildPanel(newCell = false)
    }

    /**
     * (M4 §0.6, §3.1.1) The archive session's status: kept for the mini player's pills ([GuideUiState.archive]); a
     * FAILED(NOT_IN_ARCHIVE) marks the channel suspect (hollow ↺); FAILED ends a pending catch-up FULL.
     */
    private fun onArchive(s: ArchiveStatus) {
        archive = s
        // U-S5 (core half) = P-L1: the session publishes once a second while playing; the guide's UI state changes only
        // when the mini player's inputs do, so GUIDE with catch-up in the mini does not recompose every second.
        if (archiveUiChanged(u.archive, s)) u = u.copy(archive = s)
        if (s.phase != ArchivePhase.FAILED) return
        if (s.failure == ArchiveFailure.NOT_IN_ARCHIVE && s.identityHash != 0L && suspect.add(s.identityHash)) {
            frame.suspectArchive = suspect.toLongArray().also { it.sort() }
            frameDirty = true
            if (!holdingVertical) rebuildPanel(newCell = false)
        }
        if (pendingArchiveHash == s.identityHash) pendingArchiveHash = 0
    }

    /**
     * U-S5 / P-L1: [new] differs from the published [old] in what the mini player and the FULL card read (phase,
     * channel, programme, failure, time-shift, advancing), or its wall clock or live edge left [old]'s extrapolation by
     * more than [ARCHIVE_UI_SLACK_MS].
     */
    private fun archiveUiChanged(old: ArchiveStatus?, new: ArchiveStatus): Boolean {
        if (old == null) return true
        if (old.phase != new.phase || old.identityHash != new.identityHash || old.channel != new.channel ||
            old.programme != new.programme || old.failure != new.failure || old.error != new.error ||
            old.timeShift != new.timeShift || old.advancing != new.advancing || old.displayShiftMs != new.displayShiftMs
        ) return true
        val dt = new.atMs - old.atMs
        val wall = old.wallMs + if (old.advancing) dt else 0L
        val edge = old.liveEdgeMs + dt
        return kotlin.math.abs(new.wallMs - wall) > ARCHIVE_UI_SLACK_MS || kotlin.math.abs(new.liveEdgeMs - edge) > ARCHIVE_UI_SLACK_MS
    }

    /** A request for [hash] is still being started or probed (STARTING, PROBING): its errors are not final. */
    private fun archiveInFlight(hash: Long): Boolean {
        val s = archive ?: return false
        return s.identityHash == hash && (s.phase == ArchivePhase.STARTING || s.phase == ArchivePhase.PROBING)
    }

    /** (M4 §2.9) TOGGLE_FAVOURITE in the guide: the focused row's channel, through the menu's favourite query. */
    private fun toggleFavourite() {
        val ch = target()?.channel ?: return
        menuToken++
        menuTarget = null
        favouriteToggle = ch
        effects.queryFavourite(menuToken, ch)
    }

    private fun onSettings(s: GuideSettings, d: DeviceClass) {
        guideSettings = s
        device = d
        lowEnd = s.performance == PerformanceMode.LOW || (s.performance == PerformanceMode.AUTO && d.lowEnd)
        frame.showLogos = s.showLogos
        frame.showNumbers = s.showNumbers
        frame.lowEnd = lowEnd
        frameDirty = true
        // G-S3/H-S3: the host re-sends Settings after a clock-form or zone change (TimeLabels reset): re-run every text.
        refreshWindowLabels()
        rebuildPanel(newCell = false)
    }

    /**
     * (amended 2026-10-14, §2.13 items 7 and 11) A channel tuned from Search goes FULL on its own first active
     * [GuideEvent.Tuned] (immediately when it is already the live channel); a catch-up from Search goes FULL on the
     * first archive Tuned, like the grid's, so a failed launch stays in the guide with the S13 toast.
     */
    private fun onSearchClosed(channel: GuideChannel?, tunedFromSearch: Boolean, archive: Boolean) {
        // I-19 (§5.5, device bug G4): every exit from Search lands in the grid, with the Guide tab current.
        startupFocusPending = false
        u = u.copy(mode = ScreenMode.GUIDE, overlay = null, zone = Zone.GRID, tabFocused = TAB_GUIDE)
        if (channel != null) {
            val l = lineup
            val idx = if (l != null) indexOf(l, channel.rowId) else -1
            if (idx >= 0) { u = u.copy(zone = Zone.GRID); setFocus(navigator.focusAt(idx, nowMin, ctx(0))) }
            else { u = u.copy(zone = Zone.GRID); applySource(GuideSource.All(channel.identity.playlistId), focusRowId = channel.rowId) }
        }
        if (tunedFromSearch) {
            when {
                channel == null -> if (somethingTuned()) goFull(EnterReason.SEARCH) else { pendingFull = true; pendingFullReason = EnterReason.SEARCH }
                archive -> pendingArchiveHash = channel.identityHash
                tunedLive(channel) -> goFull(EnterReason.SEARCH)
                else -> pendingFullHash = channel.identityHash
            }
        }
        refreshFooter()
    }

    // ---------------------------------------------------------------- ticks and timers

    private fun tick() {
        val m = (curMs / 60_000L).toInt()
        if (m != nowMin) {
            nowMin = m
            frame.nowMin = m
            frameDirty = true
            recomputeLimits(reclamp = true)
            refreshWindowLabels()
            if (!holdingVertical) rebuildPanel(newCell = false)
            val tn = u.tunedNow
            val tc = tunedChannel
            if (tc != null && tn != null && nowMin >= tn.endMin && tunedNowRequestedFor < tn.endMin) {
                tunedNowRequestedFor = nowMin
                effects.loadTunedNow(tc, nowMin)
            }
        }
        if (descDueAtMs <= curMs && !holdingVertical && !holdingHorizontal) loadDescription()
        tickPin()
        // Number entry
        val o = u.overlay
        if (o is Overlay.Number) {
            if (o.notFound) {
                if (curMs >= numberNotFoundUntil) closeOverlay()
            } else if (!numberCommitting) {
                if (number.due(curMs)) commitNumber()
                else if (number.previewDue(curMs) && numberPreviewFor != number.digits) {
                    numberPreviewFor = number.digits
                    numberToken++
                    number.commitValue()?.let { effects.lookupNumber(numberToken, it) }
                }
            }
        }
        if (o is Overlay.Exit && !o.dialog && curMs >= exitHintUntil) closeOverlay()
        if (u.groupCard != null && curMs >= groupCardUntil) { u = u.copy(groupCard = null); groupCardUntil = Long.MAX_VALUE }
        val c = u.reminder
        if (c != null && cardShown()) {
            if (c.alert.autoSwitch && curMs >= c.alert.startAtMs && curMs >= c.inputFromMs) { answerCard(c, accept = true) }
            else {
                val left = secondsLeft(c.alert)
                if (left != c.secondsLeft) u = u.copy(reminder = c.copy(secondsLeft = left))
            }
        }
    }

    private fun nextDeadline(): Long {
        // U-B1: a hold blocks the description load (tick()), so its due time is not a deadline while the hold lasts;
        // publishing it would leave a past deadline the host ticker spins on. The release re-arms it (armDescription /
        // rebuildPanel), which republishes the deadline.
        var d = if (holdingVertical || holdingHorizontal) Long.MAX_VALUE else descDueAtMs
        val o = u.overlay
        if (o is Overlay.Number) {
            d = if (o.notFound) minOf(d, numberNotFoundUntil)
            else if (!numberCommitting) minOf(d, number.commitAtMs(), if (numberPreviewFor != number.digits) number.previewAtMs() else Long.MAX_VALUE) else d
        }
        if (o is Overlay.Exit && !o.dialog) d = minOf(d, exitHintUntil)
        if (u.groupCard != null) d = minOf(d, groupCardUntil)
        // (M5) The lockout countdown ticks once a second while the prompt shows it.
        pinLockedUntil?.let { until -> if (u.pin != null) d = minOf(d, minOf(until, (curMs / 1000 + 1) * 1000)) }
        val c = u.reminder
        if (c != null && cardShown()) {
            d = minOf(d, if (curMs < c.inputFromMs) c.inputFromMs else (curMs / 1000 + 1) * 1000)
            if (c.alert.autoSwitch) d = minOf(d, maxOf(c.alert.startAtMs, c.inputFromMs))
        }
        return d
    }

    // ---------------------------------------------------------------- focus, panel, footer

    /**
     * (amended 2026-10-14, §2.13 item 2) [animate] is the navigator's `Moved.animate` (`!ctx.snap`); placements that
     * are not navigator moves (lineup change, number entry, search) pass false. A target more than two windows or two
     * pages away always snaps (the host no longer guesses).
     */
    private fun setFocus(f: GridFocus, animate: Boolean = false) {
        frame.animate = animate && !lowEnd &&
            abs(f.windowStartMin - frame.windowStartMin) <= 2 * GuideGeometry.WINDOW_MIN && abs(f.topRow - frame.topRow) <= 2 * VISIBLE_ROWS
        frame.focus = f
        frame.windowStartMin = f.windowStartMin
        frame.topRow = f.topRow
        frameDirty = true
        val size = lineupSize()
        effects.ensureRows(maxOf(0, f.topRow - ROWS_BEHIND), minOf(size, f.topRow + ROWS_AHEAD))
        effects.ensureWindow(f.topRow, f.windowStartMin, if (holdingVertical) holdDirection else 0)
        refreshWindowLabels()
        if (holdingVertical) updateBubble() else rebuildPanel(newCell = true)
    }

    private fun updateBubble() {
        val f = frame.focus ?: return
        val n = frame.rows.channel(f.row)?.number ?: (f.row + 1)
        if (frame.bubbleNumber != n) { frame.bubbleNumber = n; frameDirty = true }
    }

    private fun refreshWindowLabels() {
        val f = frame.focus
        val ws = f?.windowStartMin ?: limits.clampWs(limits.nowWindow(nowMin), earliest, latest)
        val ctxLabel = labels.contextLabel(ws, nowMin)
        if (frame.contextLabel != ctxLabel) { frame.contextLabel = ctxLabel; frameDirty = true }
        val dir = when {
            nowMin < ws -> -1
            nowMin >= ws + GuideLimits.WINDOW_MIN -> 1
            else -> 0
        }
        val chips = if (dir == 0) CHIPS_NO_NOW else CHIPS_WITH_NOW
        val dayLabel = limits.dayLabel(labels.dayOf(f?.anchorMin ?: nowMin), labels.dayOf(nowMin))
        var hf = u.headerFocused
        if (chips !== u.headerChips) {
            val focusedChip = u.headerChips.getOrNull(u.headerFocused)
            hf = chips.indexOf(focusedChip).let { if (it < 0) 0 else it }
        }
        if (dir != u.nowDirection || chips !== u.headerChips || dayLabel != u.dayLabel || hf != u.headerFocused) {
            u = u.copy(nowDirection = dir, headerChips = chips, dayLabel = dayLabel, headerFocused = hf)
        }
    }

    /** §3.9.1: built at once from the snapshot; the description follows [PANEL_DESC_DELAY_MS] later. */
    private fun rebuildPanel(newCell: Boolean) {
        val t = target()
        val ch = t?.channel
        if (t == null || ch == null) {
            if (u.panel != null) u = u.copy(panel = null)
            if (newCell) { panelToken++; descDueAtMs = Long.MAX_VALUE; info = null }
            refreshFooter()
            return
        }
        val old = u.panel
        if (newCell) { panelToken++; info = null; armDescription() }
        val noInfo = t.kind == CellKind.GAP || t.kind == CellKind.NO_EPG
        val title = when {
            t.kind == CellKind.PROGRAMME -> t.title ?: GuideTexts.NO_INFORMATION
            noInfo -> GuideTexts.NO_INFORMATION
            else -> GuideTexts.LOADING
        }
        val timeLine = when {
            t.kind == CellKind.LOADING -> ""
            noInfo -> labels.hm(t.start) + GuideTexts.DASH + labels.hm(t.end)
            else -> buildString {
                append(labels.hm(t.start)).append(GuideTexts.DASH).append(labels.hm(t.end))
                append(GuideTexts.SEP).append(labels.duration(t.end - t.start))
                if (t.cls == CellClass.PAST) append(GuideTexts.SEP).append(GuideTexts.NOT_IN_CATCHUP)
            }
        }
        val description = when {
            t.kind == CellKind.NO_EPG -> GuideTexts.NO_GUIDE_DATA_CHANNEL
            t.kind == CellKind.GAP -> GuideTexts.NO_GUIDE_DATA_TIME
            newCell -> null
            else -> old?.description
        }
        val genre = if (newCell || noInfo) null else old?.genre
        val buttons = buttonsFor(t)
        val p = PanelState(
            channelNumber = ch.number, channelName = ch.displayName, genre = genre, onNow = t.cls == CellClass.ON_NOW,
            catchupDays = archiveDays(ch), title = title, timeLine = timeLine, description = description,
            buttons = buttons, noInfo = noInfo,
        )
        var pf = u.panelFocused
        if (old != null && old.buttons != buttons) {
            val prevButton = old.buttons.getOrNull(pf)
            pf = buttons.indexOf(prevButton).let { if (it < 0) 0 else it }
        }
        pf = pf.coerceIn(0, maxOf(0, buttons.size - 1))
        var zone = u.zone
        if (zone == Zone.PANEL && buttons.isEmpty()) zone = Zone.HEADER
        if (p != old || pf != u.panelFocused || zone != u.zone) u = u.copy(panel = p, panelFocused = pf, zone = zone)
        refreshFooter()
    }

    private fun armDescription() {
        val t = target()
        descDueAtMs = if (t?.channel != null && t.kind == CellKind.PROGRAMME && u.panel?.description == null) {
            curMs + if (lowEnd) PANEL_DESC_DELAY_LOW_END_MS else PANEL_DESC_DELAY_MS
        } else Long.MAX_VALUE
    }

    private fun loadDescription() {
        descDueAtMs = Long.MAX_VALUE
        val t = target() ?: return
        val ch = t.channel ?: return
        if (t.kind != CellKind.PROGRAMME) return
        effects.loadInfo(panelToken, ch, t.rawStart)
    }

    /** §3.9.2 primary/secondary buttons, MORE always last (S11/E21). LOADING rows have none. */
    private fun buttonsFor(t: Target): List<PanelButton> {
        val ch = t.channel ?: return emptyList()
        val live = tunedLive(ch)
        val out = ArrayList<PanelButton>(3)
        when (t.cls) {
            CellClass.LOADING -> return emptyList()
            CellClass.ON_NOW -> {
                when {
                    live && tuned.phase == MiniPhase.ERROR -> out += PanelButton.WATCH_NOW
                    live -> { out += PanelButton.WATCHING; if (t.covered) out += PanelButton.FROM_START }
                    else -> { out += PanelButton.WATCH_NOW; if (t.covered) out += PanelButton.FROM_START }
                }
            }
            CellClass.PAST_ARCHIVE -> out += PanelButton.WATCH
            CellClass.PAST -> out += PanelButton.WATCH_NOW
            CellClass.FUTURE -> { out += if (reminderSet(t)) PanelButton.REMINDER_SET else PanelButton.REMIND; out += PanelButton.WATCH_CHANNEL_NOW }
            CellClass.NOW_GAP -> out += if (live && tuned.phase != MiniPhase.ERROR) PanelButton.WATCHING else PanelButton.WATCH_NOW
            CellClass.FUTURE_GAP -> out += PanelButton.WATCH_CHANNEL_NOW
        }
        out += PanelButton.MORE
        return out
    }

    private fun panelButtons(): List<PanelButton> = u.panel?.buttons ?: emptyList()

    private fun refreshFooter() {
        val f = footer(target())
        if (f != u.footer) u = u.copy(footer = f)
    }

    /**
     * §3.18.3: from KeysSeen (keys the device has actually reported), never from deviceHasKey. (M5, §3.8.4) Once the
     * host sends [GuideEvent.KeyHints] (EffectiveKeyMap.keysFor ∩ seen), the hints follow the user's mapping: a
     * remapped Red stops advertising Remind and the key that now carries Start over / Remind / Page is named instead.
     */
    private fun footer(t: Target?): List<FooterHint> {
        val out = ArrayList<FooterHint>(6)
        out += FooterHint(GuideTexts.KEYS_ARROWS, GuideTexts.HINT_BROWSE)
        out += FooterHint(GuideTexts.KEYS_OK, GuideTexts.HINT_WATCH)
        out += FooterHint(GuideTexts.KEYS_HOLD_OK, GuideTexts.HINT_OPTIONS)
        val hints = keyHints
        if (hints != null) {
            val future = t?.cls == CellClass.FUTURE
            val startOver = hints[BoxAction.START_OVER].orEmpty()
            val remind = hints[BoxAction.REMIND].orEmpty()
            when {
                startOver.isNotEmpty() -> out += FooterHint(FooterKeyNames.of(startOver.first()), if (future) GuideTexts.HINT_REMIND else GuideTexts.HINT_FROM_START)
                future && remind.isNotEmpty() -> out += FooterHint(FooterKeyNames.of(remind.first()), GuideTexts.HINT_REMIND)
            }
            val page = hints[BoxAction.ZAP_UP].orEmpty() + hints[BoxAction.PAGE_UP].orEmpty()
            if (Keys.CHANNEL_UP in page) out += FooterHint(GuideTexts.KEYS_CH, GuideTexts.HINT_PAGE)
            else page.firstOrNull()?.let { out += FooterHint(FooterKeyNames.of(it), GuideTexts.HINT_PAGE) }
            if (keysSeen.any { it in Keys.DIGIT_0..Keys.DIGIT_0 + 9 || it in Keys.NUMPAD_0..Keys.NUMPAD_0 + 9 }) {
                out += FooterHint(GuideTexts.KEYS_DIGITS, GuideTexts.HINT_CHANNEL)
            }
            return out
        }
        if (Keys.PROG_RED in keysSeen) {
            out += FooterHint(GuideTexts.KEYS_RED, if (t?.cls == CellClass.FUTURE) GuideTexts.HINT_REMIND else GuideTexts.HINT_FROM_START)
        }
        if (Keys.CHANNEL_UP in keysSeen) out += FooterHint(GuideTexts.KEYS_CH, GuideTexts.HINT_PAGE)
        if (keysSeen.any { it in Keys.DIGIT_0..Keys.DIGIT_0 + 9 || it in Keys.NUMPAD_0..Keys.NUMPAD_0 + 9 }) {
            out += FooterHint(GuideTexts.KEYS_DIGITS, GuideTexts.HINT_CHANNEL)
        }
        return out
    }

    private fun refreshSourceLabel() {
        val src = lineup?.source ?: requestedSource
        val label = sources.firstOrNull { it.source == src }?.label ?: u.sourceLabel
        if (label != u.sourceLabel) u = u.copy(sourceLabel = label)
    }

    private fun refreshEmpty() {
        val kind = when {
            sourcesKnown && sources.none { it.section == SourceSection.ALL } && (lineup?.rowIds?.isEmpty() != false) -> EmptyKind.NO_PLAYLIST
            lineup?.rowIds?.isEmpty() == true -> EmptyKind.EMPTY_SOURCE
            lineup != null && generationKnown && generation == null -> EmptyKind.NO_GUIDE_DATA
            else -> null
        }
        if (kind != u.empty) u = u.copy(empty = kind)
    }

    private fun announceZone() {
        val text = when (u.zone) {
            Zone.TABS -> GuideTexts.tab(u.tabFocused)
            Zone.PANEL -> panelButtons().getOrNull(u.panelFocused)?.let { GuideTexts.button(it) }
            Zone.HEADER -> u.headerChips.getOrNull(u.headerFocused)?.let { GuideTexts.chip(it, u.sourceLabel, u.dayLabel) }
            Zone.GRID -> null // the grid node's own semantics (§3.6.6)
        }
        if (text != null) effects.announce(text)
    }

    private fun announceOverlay() {
        val o = u.overlay as? Overlay.Groups ?: return
        val text = if (o.focused < o.shortcuts.size) GuideTexts.shortcut(o.shortcuts[o.focused])
        else o.entries.getOrNull(o.focused - o.shortcuts.size)?.label
        if (text != null) effects.announce(text)
    }

    // ---------------------------------------------------------------- limits and helpers

    private fun recomputeLimits(reclamp: Boolean) {
        val days = lineup?.archiveDaysUpperBound ?: 0
        earliest = limits.earliestMin(nowMin, days, dataWindow, generation?.windowFromMin)
        latest = limits.latestMin(nowMin, dataWindow, generation?.windowToMin)
        if (frame.earliestMin != earliest) { frame.earliestMin = earliest; frameDirty = true }
        if (!reclamp) return
        val f = frame.focus ?: return
        val ws = limits.clampWs(f.windowStartMin, earliest, latest)
        val anchor = f.anchorMin.coerceIn(earliest, maxOf(earliest, latest - 1))
        if (ws != f.windowStartMin || anchor != f.anchorMin) {
            val moved = f.copy(windowStartMin = ws, anchorMin = anchor)
            val r = navigator.move(moved, GridMove.RESOLVE, 0, ctx(SNAP_AFTER_REPEATS))
            val nf = (r as? GridMoveResult.Moved)?.focus ?: moved
            frame.focus = nf
            frame.windowStartMin = nf.windowStartMin
            frame.topRow = nf.topRow
            frameDirty = true
            effects.ensureWindow(nf.topRow, nf.windowStartMin, 0)
        }
    }

    private fun ctx(repeat: Int): GridContext =
        GridContext(lineupSize(), frame.data, nowMin, earliest, latest, snap = lowEnd || repeat >= SNAP_AFTER_REPEATS, watchableFrom = watchable)

    private fun lineupSize(): Int = lineup?.rowIds?.size ?: 0

    private fun indexOf(l: GuideLineup, rowId: Long): Int {
        if (rowId < 0) return -1
        val ids = l.rowIds
        for (i in ids.indices) if (ids[i] == rowId) return i
        return -1
    }

    private fun currentEntryIndex(): Int {
        val src = lineup?.source ?: requestedSource ?: return -1
        return sources.indexOfFirst { it.source == src }
    }

    private fun firstAll(): GuideSourceEntry? = sources.firstOrNull { it.section == SourceSection.ALL }

    private enum class CellClass { LOADING, ON_NOW, PAST_ARCHIVE, PAST, FUTURE, NOW_GAP, FUTURE_GAP }

    /** The focused cell as the panel/OK/menu see it, copied out of the snapshot (event-time allocation only). */
    private inner class Target(
        val channel: GuideChannel?, val start: Int, val end: Int, val rawStart: Int, val kind: Byte, val flags: Int,
        val title: String?, val titleKey: Long, val cls: CellClass, val covered: Boolean,
    ) {
        fun info(): ProgrammeInfo {
            val loaded = info
            if (loaded != null && channel != null && infoHash == channel.identityHash && loaded.rawStartMin == rawStart) return loaded
            return ProgrammeInfo(rawStart, start, end, title ?: "", null, null, null, null, flags, titleKey)
        }
    }

    private fun target(): Target? {
        val f = frame.focus ?: return null
        val ch = frame.rows.channel(f.row)
        val d = frame.data
        var kind = CellKind.LOADING
        var start = f.cellStart; var end = f.cellEnd; var raw = f.cellStart; var flags = 0
        var title: String? = null; var key = 0L
        if (d != null && d.covers(f.row, f.anchorMin.coerceIn(d.fromMin, d.toMin - 1))) {
            val r = f.row - d.firstRow
            val first = d.rowFirstCell[r]
            for (i in first until first + d.rowCellCount[r]) {
                if (d.cellStart[i] == f.cellStart || (f.anchorMin >= d.cellStart[i] && f.anchorMin < d.cellEnd[i] && d.cellStart[i] <= f.cellStart && f.cellStart < d.cellEnd[i])) {
                    kind = d.cellKind[i]; start = d.cellStart[i]; end = d.cellEnd[i]; raw = d.cellRawStart[i]; flags = d.cellFlags[i]
                    title = d.cellTitle[i]; key = d.cellTitleKey[i]
                    break
                }
            }
        }
        val days = if (ch == null) 0 else archiveDays(ch)
        val covered = days > 0 && kind == CellKind.PROGRAMME && raw >= nowMin - days * GuideLimits.MIN_PER_DAY && start <= nowMin
        val cls = when (kind) {
            CellKind.PROGRAMME -> when {
                end <= nowMin -> if (covered) CellClass.PAST_ARCHIVE else CellClass.PAST
                start <= nowMin -> CellClass.ON_NOW
                else -> CellClass.FUTURE
            }
            CellKind.GAP, CellKind.NO_EPG -> if (start > nowMin) CellClass.FUTURE_GAP else CellClass.NOW_GAP
            else -> CellClass.LOADING
        }
        return Target(ch, start, end, raw, kind, flags, title, key, cls, covered)
    }

    private fun GroupMenuItem.toAction() = when (this) {
        GroupMenuItem.HIDE -> app.thebox.core.guide.GroupAction.HIDE
        GroupMenuItem.MOVE_UP -> app.thebox.core.guide.GroupAction.MOVE_UP
        GroupMenuItem.MOVE_DOWN -> app.thebox.core.guide.GroupAction.MOVE_DOWN
        GroupMenuItem.LOCK, GroupMenuItem.UNLOCK -> null // handled by lockAction (§3.9.1), not a library GroupAction
    }

    companion object {
        val FACTORY = GuideStateMachineFactory { effects, navigator, labels, renderer, nowMs ->
            GuideStateMachineImpl(effects, navigator, labels, renderer, nowMs)
        }

        /** U-S5 / P-L1: how far a status's clocks may leave the published one's extrapolation before it is republished. */
        const val ARCHIVE_UI_SLACK_MS = 1_000L

        // Appendix B (WP-D)
        const val HOLD_TIME_STEP_AFTER_MS = 1_000L
        const val PANEL_DESC_DELAY_MS = 150L
        const val PANEL_DESC_DELAY_LOW_END_MS = 600L
        const val EXIT_CONFIRM_MS = 2_000L
        const val GROUP_CARD_MS = 2_000L
        const val REMINDER_CARD_MS = 60_000L
        const val REMINDER_CARD_GUARD_MS = 600L
        const val NUMBER_NOT_FOUND_MS = 1_500L
        const val SNAP_AFTER_REPEATS = 3
        const val VISIBLE_ROWS = 6
        /** Rows around the top the rows cache should hold (§0.5: `[top − 6, top + 12)`). */
        const val ROWS_BEHIND = 6
        const val ROWS_AHEAD = 12
        const val TAB_COUNT = 3
        const val TAB_GUIDE = 0
        const val TAB_SEARCH = 1

        val SHORTCUTS = listOf(GroupsShortcut.SEARCH, GroupsShortcut.SETTINGS)
        val GROUP_MENU = listOf(GroupMenuItem.HIDE, GroupMenuItem.MOVE_UP, GroupMenuItem.MOVE_DOWN)
        /** (M5) Base for the guide's own SettingsAction tokens (negative: the settings machine counts up from 0). */
        const val GUIDE_ACTION_TOKEN_BASE = -1L
        /** (M5) The player machine's SettingsAction tokens start here; the guide routes their answers to the player. */
        const val PLAYER_ACTION_TOKEN_BASE = app.thebox.core.full.state.PlayerStateMachineImpl.PLAYER_ACTION_TOKEN_BASE
        private val CHIPS_NO_NOW = listOf(HeaderChip.GROUP, HeaderChip.DAY)
        private val CHIPS_WITH_NOW = listOf(HeaderChip.GROUP, HeaderChip.NOW, HeaderChip.DAY)
    }
}
