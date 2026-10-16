package app.thebox.core.full.state

import app.thebox.core.catchup.archive.ArchiveFailure
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.full.BackInPlayer
import app.thebox.core.full.ChannelListState
import app.thebox.core.full.ChannelMenuItem
import app.thebox.core.full.DPAD_UP_ZAP_DELTA
import app.thebox.core.full.EnterReason
import app.thebox.core.full.ErrorButton
import app.thebox.core.full.ErrorCardState
import app.thebox.core.full.InfoBarState
import app.thebox.core.full.InfoGlyphs
import app.thebox.core.full.ListColumn
import app.thebox.core.full.PickerChoice
import app.thebox.core.full.PickerKind
import app.thebox.core.full.PickerState
import app.thebox.core.full.PlayerEffects
import app.thebox.core.full.PlayerEvent
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.PlayerSettings
import app.thebox.core.full.PlayerStateMachine
import app.thebox.core.full.PlayerToast
import app.thebox.core.full.PlayerUiState
import app.thebox.core.full.QuickBarState
import app.thebox.core.full.QuickItem
import app.thebox.core.full.ScrubFocus
import app.thebox.core.full.ScrubState
import app.thebox.core.full.StatsFacts
import app.thebox.core.full.TrackKey
import app.thebox.core.full.TrackMenu
import app.thebox.core.full.UpDownInPlayer
import app.thebox.core.full.VideoFacts
import app.thebox.core.full.feed.StatsFormatter
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideRows
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.SourceSection
import app.thebox.core.guide.TunedState
import app.thebox.core.input.BoxAction
import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.VideoDecoderMode
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.ParentalRules
import app.thebox.core.parental.ParentalState
import app.thebox.core.parental.PinGate
import app.thebox.core.parental.PinOwner
import app.thebox.core.parental.PinPurpose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The full-screen player's brain (docs/design/m4-player-ux.md §3.1–§3.5, §3.7; owner ruling 2026-10-15): layers, every
 * key table of §3.1, info-bar timing, channel-list cursor and paging requests, quick bar and pickers, error-card focus,
 * scrub interaction (steps, hold acceleration, 600 ms commit, `settledSeq`), screen-off and the back ladder (§3.1.5).
 *
 * Pure and main-thread only, like GuideStateMachineImpl: every input is a key ([act]/[release]) or a [PlayerEvent],
 * every output is [ui] or a [PlayerEffects] call. Timers are deadlines in [nextDeadlineMs]; the host posts a
 * [PlayerEvent.Tick] then. The guide machine calls [act] in FULL after its reminder card and overlays (§3.1.1).
 *
 * The unfocused scrub bar (auto-shown after an archive start, pause or start over) and the passive info bar take no
 * keys: the layer stays NONE (or ERROR) while they are drawn.
 */
class PlayerStateMachineImpl(
    private val effects: PlayerEffects,
    private val listRows: GuideRows,
    private val nowMs: () -> Long,
) : PlayerStateMachine {
    private val _ui = MutableStateFlow(PlayerUiState())
    override val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()
    private val _deadline = MutableStateFlow(Long.MAX_VALUE)
    override val nextDeadlineMs: StateFlow<Long> = _deadline.asStateFlow()

    override val horizontalRepeat: Boolean get() = inFull && (layer != PlayerLayer.NONE || inArchive())

    private var curMs = nowMs()
    private var inFull = false

    // ---------------------------------------------------------------- inputs
    private var settings = PlayerSettings()
    private var tuned = TunedState()
    private var tunedChannel: GuideChannel? = null
    private var pendingZap: GuideChannel? = null
    private var status = ArchiveStatus()
    private var menu = TrackMenu()
    private var video = VideoFacts()
    private var statsFacts: StatsFacts? = null
    private var sources: List<GuideSourceEntry> = emptyList()
    private var guideSource: GuideSource? = null
    private var guideLineup: GuideLineup? = null
    private var listLineup: GuideLineup? = null
    private var previous: GuideChannel? = null
    private var rowsVersion = 0L
    private var nowVersion = 0L

    // ---------------------------------------------------------------- layers
    private var layer = PlayerLayer.NONE
    private var statsOn = false
    private var errorWasActive = false
    private var errorFocused = 0
    private var errorCode: PlaybackErrorCode? = null
    private var listJustClosedByBack = false

    // Programmes of one channel (info bar, scrub chips, quick bar's "now"); one cache, merged per answer (§3.2.2).
    private var progHash = 0L
    private var progs: List<ProgrammeInfo> = emptyList()
    private var progLoaded = false
    private var progBaseToken = 0L
    private var progToken = 0L
    private val progRequested = HashSet<Int>()

    // Info bar (§3.2)
    private var passive = false
    private var passiveHideAt = Long.MAX_VALUE
    private var passiveAwait = false
    private var passiveRequireChangeFrom = 0L
    private var passiveSawConnecting = false
    private var infoChannel: GuideChannel? = null
    private var infoFocused = -1
    /** U-S7: the last archive start came from the info bar and no key followed it yet. */
    private var barLaunchPending = false
    private var infoHideAt = Long.MAX_VALUE
    private var browseToken = 0L
    private var browseHolding = false

    // Quick bar (§3.4.1)
    private var quickFocus: QuickItem? = null
    private var quickHideAt = Long.MAX_VALUE
    private var favourite: Boolean? = null
    private var favToken = 0L
    private var favPurpose = FavPurpose.NONE
    private var favChannel: GuideChannel? = null

    // Picker (§3.4.2)
    private var picker: PickerState? = null

    // Scrub bar (§3.5)
    private var scrubShown = false
    private var scrubFocused = false
    private var scrubFocus = ScrubFocus.BAR
    private var chipFocused = -1
    private var scrubAtLive = false
    private var preview: Long? = null
    /** The last step clamped the preview to the live edge: the commit goes live even though the edge moved on since. */
    private var previewAtEdge = false
    private var commitAt = Long.MAX_VALUE
    private var seekSeq = 0L
    private var awaitSeq = 0L
    private var atLiveCommitPending = false
    private var scrubHideAt = Long.MAX_VALUE
    private var lastScope: ScopeBounds? = null
    private var scrubLoadAroundMin = Int.MIN_VALUE

    // Channel list (§3.3)
    private val list = ChannelListModel()
    private var listColumn = ListColumn.CHANNELS
    private var listSource: GuideSource? = null
    private var listOpenedByBack = false
    private var listGroupFocused = 0
    private var listPendingFocus = false
    private var listMenu: List<ChannelMenuItem>? = null
    private var listMenuFocused = 0
    private var listMenuChannel: GuideChannel? = null

    // Parental (M5, WP-C; docs/design/m5-setup-settings.md §3.9.1, §3.9.2)
    /** Injected by the guide machine (§0.9): the one PIN prompt. */
    override var pinGate: PinGate? = null
    private var parental = ParentalState(pinSet = false, unlocked = false, policy = ParentalPolicy())
    /** Lock actions waiting for the PIN, by SettingsAction token (the guide routes tokens ≥ PLAYER_ACTION_TOKEN_BASE here). */
    private val pendingLocks = HashMap<Long, () -> Unit>()
    private var lockToken = 0L
    @Suppress("unused") private var keyHints: Map<BoxAction, List<Int>> = emptyMap()

    private enum class FavPurpose { NONE, QUICK, TOGGLE, LIST_MENU }
    private class ScopeBounds(val programme: ProgrammeInfo?, val from: Long, val to: Long)

    // ================================================================= PlayerStateMachine

    override fun handles(action: BoxAction): Boolean = true

    override fun act(a: GuideEvent.Act): Boolean {
        curMs = nowMs()
        val justClosed = listJustClosedByBack
        listJustClosedByBack = false
        barLaunchPending = false // U-S7: a key after the launch means the user moved on
        val consumed = when (layer) {
            PlayerLayer.SCREEN_OFF -> { wake(); true }
            PlayerLayer.QUICK_BAR -> quickKey(a)
            PlayerLayer.PICKER -> pickerKey(a)
            PlayerLayer.CHANNELS -> listKey(a)
            PlayerLayer.SCRUB -> scrubKey(a)
            PlayerLayer.INFO -> infoKey(a)
            PlayerLayer.ERROR -> errorKey(a, justClosed)
            PlayerLayer.NONE -> bareKey(a, justClosed)
        }
        if (layer == PlayerLayer.INFO) infoHideAt = curMs + settings.infoBarMs
        if (layer == PlayerLayer.QUICK_BAR) quickHideAt = curMs + QUICK_BAR_IDLE_MS
        publish()
        return consumed
    }

    override fun release(action: BoxAction) {
        curMs = nowMs()
        when (action) {
            BoxAction.LEFT, BoxAction.RIGHT, BoxAction.SEEK_BACK, BoxAction.SEEK_FORWARD -> {
                // §3.1.4: Release re-arms the 600 ms commit from the release time.
                if (scrubShown && preview != null && commitAt != Long.MAX_VALUE) commitAt = curMs + ScrubModel.SCRUB_COMMIT_MS
                if (browseHolding && (action == BoxAction.LEFT || action == BoxAction.RIGHT)) {
                    browseHolding = false
                    if (layer == PlayerLayer.INFO) infoChannel?.let { loadProgrammesFor(it, force = !progLoaded) }
                }
            }
            BoxAction.UP, BoxAction.DOWN, BoxAction.ZAP_UP, BoxAction.ZAP_DOWN ->
                if (layer == PlayerLayer.CHANNELS) ensureList()
            else -> Unit
        }
        publish()
    }

    override fun dispatch(event: PlayerEvent) {
        curMs = if (event is PlayerEvent.Tick) event.nowMs else nowMs()
        when (event) {
            is PlayerEvent.Tick -> tick()
            is PlayerEvent.Tuned -> onTuned(event.state, event.channel)
            is PlayerEvent.PendingZap -> onPendingZap(event.channel)
            is PlayerEvent.Programmes -> onProgrammes(event.token, event.channelHash, event.programmes)
            is PlayerEvent.BrowseChannel -> onBrowse(event.token, event.channel)
            is PlayerEvent.Tracks -> onTracks(event.menu)
            is PlayerEvent.Video -> onVideo(event.facts)
            is PlayerEvent.Stats -> statsFacts = event.facts
            is PlayerEvent.Archive -> onArchive(event.status)
            is PlayerEvent.Sources -> sources = event.entries
            is PlayerEvent.ListLineup -> onListLineup(event.lineup)
            is PlayerEvent.ListRows -> rowsVersion = event.version
            is PlayerEvent.ListNow -> nowVersion = event.version
            is PlayerEvent.Previous -> { previous = event.channel; if (layer == PlayerLayer.CHANNELS) list.resize(list.size, previous != null) }
            is PlayerEvent.Favourite -> onFavourite(event.token, event.isFavourite)
            is PlayerEvent.GuideSourceNow -> { guideSource = event.source; guideLineup = event.lineup }
            is PlayerEvent.Settings -> settings = event.settings
            is PlayerEvent.ArchiveStartFailed -> onArchiveStartFailed()
            // M5 (docs/design/m5-setup-settings.md §3.9)
            is PlayerEvent.Parental -> parental = event.state
            is PlayerEvent.PinResult -> onPinResult(event.purpose, event.granted)
            is PlayerEvent.KeyHints -> keyHints = event.hints
        }
        publish()
    }

    override fun enterFull(nowMs: Long, reason: EnterReason) {
        curMs = nowMs
        inFull = true
        closeAll()
        if (reason == EnterReason.ARCHIVE || inArchive()) showScrubAuto()
        else showPassive(requireChangeFrom = 0L)
        publish()
    }

    override fun leaveFull() {
        curMs = nowMs()
        if (layer == PlayerLayer.SCREEN_OFF) effects.setScreenOff(false)
        closeAll()
        if (statsOn) { statsOn = false; effects.setStatsEnabled(false) }
        inFull = false
        errorWasActive = false
        publish()
    }

    // ================================================================= bare video (§3.1.2)

    private fun bareKey(a: GuideEvent.Act, justClosedByBack: Boolean): Boolean {
        val archive = inArchive()
        when (a.action) {
            BoxAction.UP, BoxAction.DOWN -> upDown(a, fromError = false)
            BoxAction.ZAP_UP -> zap(+1, a.repeat)
            BoxAction.ZAP_DOWN -> zap(-1, a.repeat)
            BoxAction.LEFT -> if (archive) scrubStep(-ScrubModel.stepMs(a.heldMs), openFocused = true) else openList(ListColumn.GROUPS, byBack = false, offset = 0)
            BoxAction.RIGHT -> if (archive) scrubStep(ScrubModel.stepMs(a.heldMs), openFocused = true) else openInfo()
            BoxAction.GROUP_PREV, BoxAction.GROUP_NEXT -> return archive // live: the guide's cycleGroup; archive: never fires (horizontalRepeat)
            // N12: SELECT on stopped video plays again (after the card's Stop, or any stop the controller reported).
            BoxAction.SELECT -> if (!archive && tuned.phase == MiniPhase.STOPPED) effects.retry() else openList(ListColumn.CHANNELS, byBack = false, offset = 0)
            BoxAction.CONTEXT_MENU, BoxAction.QUICK_MENU -> openQuick()
            BoxAction.INFO_BAR -> if (archive) openScrubFocused() else openInfo()
            BoxAction.CHANNEL_LIST -> openList(ListColumn.CHANNELS, byBack = false, offset = 0)
            BoxAction.GROUPS_PANEL -> openList(ListColumn.GROUPS, byBack = false, offset = 0)
            BoxAction.FAVOURITES -> openFavouritesList()
            BoxAction.PLAY_PAUSE -> playPause()
            BoxAction.PLAY -> play()
            BoxAction.PAUSE -> pause()
            BoxAction.SEEK_BACK -> seekKey(-1, a)
            BoxAction.SEEK_FORWARD -> seekKey(+1, a)
            BoxAction.JUMP_TO_LIVE -> if (archive) jumpToLive()
            BoxAction.START_OVER -> startOverKey()
            BoxAction.TIME_SHIFT -> when {
                archive -> openScrubFocused()
                archiveCapable() -> openScrubAtLive()
                else -> effects.toast(PlayerToast.NO_CATCHUP)
            }
            BoxAction.PREV_PROGRAMME -> if (archive) seekProgramme(-1) else { openInfo(); moveInfo(-1) }
            BoxAction.NEXT_PROGRAMME -> if (archive) seekProgramme(+1) else { openInfo(); moveInfo(+1) }
            BoxAction.AUDIO -> openPicker(PickerKind.AUDIO, fromQuickBar = false)
            BoxAction.SUBTITLES -> openPicker(PickerKind.SUBTITLES, fromQuickBar = false)
            BoxAction.ASPECT -> openPicker(PickerKind.ASPECT, fromQuickBar = false)
            BoxAction.VIDEO_DECODER -> openPicker(PickerKind.VIDEO_DECODER, fromQuickBar = false)
            BoxAction.STATS_OVERLAY -> toggleStats()
            BoxAction.TOGGLE_FAVOURITE -> tunedChannel?.let { queryFavourite(it, FavPurpose.TOGGLE) }
            BoxAction.PREVIOUS_CHANNEL -> previousChannel()
            BoxAction.SCREEN_OFF -> screenOff()
            BoxAction.BACK -> return backAtBase(justClosedByBack)
            BoxAction.DIGIT, BoxAction.NUMBER_ENTRY, BoxAction.OPEN_GUIDE, BoxAction.EXIT -> return false
            // M5 commit 0: the guide machine owns OPEN_SEARCH / OPEN_SETTINGS (WP-C binds and handles them).
            BoxAction.OPEN_SEARCH, BoxAction.OPEN_SETTINGS -> return false
            BoxAction.REMIND, BoxAction.PAGE_UP, BoxAction.PAGE_DOWN, BoxAction.JUMP_TO_NOW, BoxAction.DAY_STRIP -> Unit
        }
        return true
    }

    /** UP/DOWN on bare video or the error card, per "Up/Down in player" (§3.1.2; owner ruling 2026-10-15). */
    private fun upDown(a: GuideEvent.Act, fromError: Boolean) {
        val up = a.action == BoxAction.UP
        when (settings.upDown) {
            UpDownInPlayer.QUICK_BAR_AND_INFO -> when {
                up -> openQuick()
                fromError -> Unit
                inArchive() -> openScrubFocused()
                else -> openInfo()
            }
            UpDownInPlayer.ZAP -> {
                val d = (if (up) DPAD_UP_ZAP_DELTA else -DPAD_UP_ZAP_DELTA) * (if (settings.invertChannelDirection) -1 else 1)
                zap(d, a.repeat)
            }
            UpDownInPlayer.CHANNEL_LIST -> openList(ListColumn.CHANNELS, byBack = false, offset = if (up) -1 else 1)
        }
    }

    /** §3.1.5 step 8–9: stats off, else the "Back in player" setting; a passive bar never consumes BACK. */
    private fun backAtBase(justClosedByBack: Boolean): Boolean {
        if (layer == PlayerLayer.NONE && statsOn) { toggleStats(); return true }
        if (settings.back == BackInPlayer.CHANNEL_LIST && !justClosedByBack) {
            openList(ListColumn.CHANNELS, byBack = true, offset = 0)
            return true
        }
        return false
    }

    private fun zap(delta: Int, repeat: Int) {
        effects.zap(delta, repeat > 0)
        when (layer) {
            PlayerLayer.NONE, PlayerLayer.ERROR -> showPassive(requireChangeFrom = tuned.identityHash.takeIf { it != 0L } ?: ANY_CHANGE)
            else -> Unit // INFO follows the tuned channel; QUICK_BAR stays (values refresh); CHANNELS stays
        }
    }

    private fun previousChannel() {
        if (previous == null) { effects.toast(PlayerToast.NO_PREVIOUS); return }
        effects.previousChannel()
        if (layer == PlayerLayer.NONE || layer == PlayerLayer.ERROR) showPassive(requireChangeFrom = tuned.identityHash.takeIf { it != 0L } ?: ANY_CHANGE)
    }

    private fun playPause() {
        val ch = tunedChannel
        when {
            inArchive() -> {
                if (archivePaused()) effects.archiveResume() else effects.archivePause()
                showScrubAuto()
                scrubHideAt = curMs + SCRUB_AUTO_SHOW_MS
            }
            tuned.phase == MiniPhase.PAUSED -> effects.resumePlain()
            archiveCapable() && ch != null -> { effects.pauseLive(ch); showScrubAuto(atLive = true) }
            else -> effects.pausePlain()
        }
    }

    private fun play() {
        when {
            inArchive() -> if (archivePaused()) effects.archiveResume()
            tuned.phase == MiniPhase.PAUSED -> effects.resumePlain()
        }
    }

    private fun pause() {
        when {
            inArchive() -> if (!archivePaused()) effects.archivePause()
            tuned.phase != MiniPhase.PAUSED -> playPause()
        }
    }

    private fun seekKey(dir: Int, a: GuideEvent.Act) {
        when {
            inArchive() -> scrubStep(dir * ScrubModel.ffRwStepMs(a.repeat, a.heldMs), openFocused = true)
            !archiveCapable() -> effects.toast(PlayerToast.NO_CATCHUP)
            dir < 0 -> {
                val ch = tunedChannel ?: return
                openScrubAtLive()
                effects.rewindLive(ch, RW_ON_LIVE_BACK_MS)
                preview = liveEdgeNow() - RW_ON_LIVE_BACK_MS
                atLiveCommitPending = true
            }
            else -> Unit // FF on live: already live
        }
    }

    private fun startOverKey() {
        if (inArchive()) {
            val p = scopeAt(playheadNow()).programme ?: status.programme
            if (p != null) seekTo(ScrubModel.startMs(p))
            return
        }
        val ch = tunedChannel
        val knownNoProgramme = progLoaded && progHash == tuned.identityHash && nowIndex(progs) < 0
        if (!archiveCapable() || ch == null || knownNoProgramme) { effects.toast(PlayerToast.NO_CATCHUP); return }
        effects.startOver(ch)
        showScrubAuto(atLive = true)
    }

    private fun screenOff() {
        closeAll()
        layer = PlayerLayer.SCREEN_OFF
        effects.setScreenOff(true)
    }

    private fun wake() {
        effects.setScreenOff(false)
        layer = base()
    }

    private fun toggleStats() {
        statsOn = !statsOn
        effects.setStatsEnabled(statsOn)
    }

    // ================================================================= layers: common

    /** The layer a closing layer returns to: the error card while the error lasts, else bare video. */
    private fun base(): PlayerLayer = if (errorActive()) PlayerLayer.ERROR else PlayerLayer.NONE

    private fun closeLayer() {
        if (layer == PlayerLayer.SCRUB) { scrubFocused = false; scrubShown = false; cancelPreview() }
        if (layer == PlayerLayer.PICKER) picker = null
        if (layer == PlayerLayer.CHANNELS) listMenu = null
        layer = base()
    }

    /** Opening a layer closes the others, except stats (orthogonal) and the passive bar (replaced) (§3.1.3). */
    private fun openLayer(l: PlayerLayer) {
        if (layer == PlayerLayer.PICKER && l != PlayerLayer.PICKER) picker = null
        if (layer == PlayerLayer.CHANNELS && l != PlayerLayer.CHANNELS) listMenu = null
        if (l != PlayerLayer.SCRUB && scrubShown) { scrubShown = false; scrubFocused = false; cancelPreview() }
        passive = false
        layer = l
    }

    private fun closeAll() {
        picker = null
        listMenu = null
        scrubShown = false
        scrubFocused = false
        cancelPreview()
        awaitSeq = 0
        atLiveCommitPending = false
        passive = false
        passiveAwait = false
        layer = PlayerLayer.NONE
    }

    // ================================================================= info bar (§3.2)

    private fun showPassive(requireChangeFrom: Long) {
        if (!inFull) return
        if (scrubShown && !scrubFocused) { scrubShown = false; cancelPreview() }
        passive = true
        infoFocused = -1
        val ch = pendingZap ?: tunedChannel
        infoChannel = ch
        passiveHideAt = Long.MAX_VALUE
        passiveAwait = true
        passiveRequireChangeFrom = requireChangeFrom
        passiveSawConnecting = false
        armPassiveIfSettled()
        ch?.let { loadProgrammesFor(it, force = false) }
    }

    /** §3.2.1: the passive bar hides INFO_AFTER_ZAP_MS after the tune reaches PLAYING or ERROR. */
    private fun armPassiveIfSettled() {
        if (!passiveAwait) return
        val settled = tuned.phase == MiniPhase.PLAYING || tuned.phase == MiniPhase.ERROR || tuned.phase == MiniPhase.PAUSED
        val changed = passiveRequireChangeFrom == 0L ||
            (passiveRequireChangeFrom != ANY_CHANGE && tuned.identityHash != passiveRequireChangeFrom) || passiveSawConnecting
        if (settled && changed && pendingZap == null) {
            passiveAwait = false
            passiveHideAt = curMs + INFO_AFTER_ZAP_MS
        }
    }

    private fun openInfo() {
        if (layer == PlayerLayer.INFO) return
        val ch = tunedChannel ?: return
        openLayer(PlayerLayer.INFO)
        infoChannel = ch
        infoFocused = -1
        browseToken++
        loadProgrammesFor(ch, force = false)
        if (progLoaded && progHash == ch.identityHash) infoFocused = nowOrNext(progs)
    }

    private fun infoKey(a: GuideEvent.Act): Boolean {
        val ch = infoChannel ?: tunedChannel
        when (a.action) {
            BoxAction.UP -> moveInfo(-1)
            BoxAction.DOWN -> moveInfo(+1)
            BoxAction.LEFT, BoxAction.RIGHT -> if (ch != null) {
                browseHolding = a.repeat > 0
                browseToken++
                effects.loadChannel(browseToken, ch, if (a.action == BoxAction.LEFT) -1 else 1)
            }
            BoxAction.SELECT -> infoSelect()
            BoxAction.BACK, BoxAction.INFO_BAR -> closeLayer()
            BoxAction.ZAP_UP -> zap(+1, a.repeat)
            BoxAction.ZAP_DOWN -> zap(-1, a.repeat)
            else -> return bareKey(a, justClosedByBack = false)
        }
        return true
    }

    private fun moveInfo(delta: Int) {
        val ch = infoChannel ?: return
        if (progHash != ch.identityHash || progs.isEmpty()) return
        val nowIdx = nowOrNext(progs)
        val min = earliestInfoIndex(ch, nowIdx)
        val from = if (infoFocused < 0) nowIdx.coerceAtLeast(0) else infoFocused
        infoFocused = (from + delta).coerceIn(min, progs.size - 1)
        // Load more within INFO_EDGE of either end (§3.2.2).
        if (infoFocused <= INFO_EDGE) requestAround(ch, progs.first().startMin)
        if (infoFocused >= progs.size - 1 - INFO_EDGE) requestAround(ch, progs.last().startMin)
    }

    /** Earlier stops at the first programme inside the archive when the channel has archive, else at now (§3.1.3). */
    private fun earliestInfoIndex(ch: GuideChannel, nowIdx: Int): Int {
        val nowIdx0 = nowIdx.coerceAtLeast(0)
        if (ch.archiveDays <= 0) return nowIdx0
        val from = nowMin() - ch.archiveDays * MIN_PER_DAY
        for (i in progs.indices) if (progs[i].rawStartMin >= from) return minOf(i, nowIdx0)
        return nowIdx0
    }

    private fun infoSelect() {
        val ch = infoChannel ?: return
        if (ch.identityHash != tuned.identityHash) {
            effects.tune(ch, null)
            layer = base()
            showPassive(requireChangeFrom = tuned.identityHash.takeIf { it != 0L } ?: ANY_CHANGE)
            infoChannel = ch
            return
        }
        val p = progs.getOrNull(infoFocused)?.takeIf { progHash == ch.identityHash }
        val now = nowMin()
        when {
            p == null || (p.startMin <= now && now < p.endMin) -> openQuick()
            p.endMin <= now -> if (inArchiveWindow(ch, p)) {
                effects.playProgramme(ch, p)
                closeLayer()
                barLaunchPending = true // U-S7: a synchronous failure brings the bar back at now
            } else effects.toast(PlayerToast.NO_CATCHUP)
            else -> effects.toggleReminder(ch, p)
        }
    }

    /**
     * U-S7 = N17 (§3.2.2): `playProgramme` from the info bar failed synchronously (the host toasted CATCHUP_FAILED, and the
     * toast stays). The bar closed at the launch; it comes back on the tuned channel with the focus on now, so the user is
     * not left on the past programme that could not play. A failure of any other start (chips, RW, start over) or after a
     * further key changes nothing here.
     */
    private fun onArchiveStartFailed() {
        val fromBar = barLaunchPending
        barLaunchPending = false
        if (!fromBar || !inFull) return
        when (layer) {
            PlayerLayer.INFO -> {
                infoChannel = tunedChannel
                tunedChannel?.let { loadProgrammesFor(it, force = false) }
                infoFocused = if (progHash == tuned.identityHash) nowOrNext(progs) else -1
            }
            PlayerLayer.NONE -> {
                openInfo()
                infoHideAt = curMs + settings.infoBarMs
            }
            else -> Unit
        }
    }

    private fun inArchiveWindow(ch: GuideChannel, p: ProgrammeInfo): Boolean =
        ch.archiveDays > 0 && p.rawStartMin >= nowMin() - ch.archiveDays * MIN_PER_DAY

    private fun onBrowse(token: Long, ch: GuideChannel?) {
        if (token != browseToken || layer != PlayerLayer.INFO || ch == null) return
        infoChannel = ch
        infoFocused = -1
        if (browseHolding) { progHash = ch.identityHash; progs = emptyList(); progLoaded = false; progRequested.clear() }
        else loadProgrammesFor(ch, force = false)
    }

    // ---------------------------------------------------------------- programmes cache

    private fun loadProgrammesFor(ch: GuideChannel, force: Boolean) {
        if (!force && progHash == ch.identityHash && (progLoaded || progRequested.isNotEmpty())) return
        progHash = ch.identityHash
        progs = emptyList()
        progLoaded = false
        progRequested.clear()
        progBaseToken = progToken
        requestAround(ch, nowMin())
    }

    private fun requestAround(ch: GuideChannel, aroundMin: Int) {
        if (!progRequested.add(aroundMin)) return
        progToken++
        effects.loadProgrammes(progToken, ch, aroundMin, if (ch.archiveDays > 0) INFO_PAST_MIN else 0, INFO_FUTURE_MIN)
    }

    private fun onProgrammes(token: Long, hash: Long, list: List<ProgrammeInfo>) {
        if (hash != progHash || token <= progBaseToken) return
        val wasEmpty = !progLoaded
        val focusedStart = progs.getOrNull(infoFocused)?.rawStartMin
        progs = ScrubModel.merge(progs, list)
        progLoaded = true
        if (focusedStart != null) infoFocused = progs.indexOfFirst { it.rawStartMin == focusedStart }
        if (infoFocused < 0 && (wasEmpty || focusedStart == null)) infoFocused = nowOrNext(progs)
    }

    private fun nowMin(): Int = (curMs / 60_000L).toInt()

    private fun nowIndex(list: List<ProgrammeInfo>): Int {
        val now = nowMin()
        for (i in list.indices) if (list[i].startMin <= now && now < list[i].endMin) return i
        return -1
    }

    /** The programme on now, or (a gap) the first one after now; −1 when the list is empty. */
    private fun nowOrNext(list: List<ProgrammeInfo>): Int {
        if (list.isEmpty()) return -1
        val i = nowIndex(list)
        if (i >= 0) return i
        val now = nowMin()
        val next = list.indexOfFirst { it.startMin > now }
        return if (next >= 0) next else list.size - 1
    }

    // ================================================================= quick bar (§3.4.1)

    private fun openQuick() {
        openLayer(PlayerLayer.QUICK_BAR)
        favourite = null
        quickFocus = null
        tunedChannel?.let { ch ->
            queryFavourite(ch, FavPurpose.QUICK)
            loadProgrammesFor(ch, force = false) // START_OVER needs to know a programme airs now
        }
    }

    private fun quickItems(): List<QuickItem> {
        val nowProg = progLoaded && progHash == tuned.identityHash && nowIndex(progs) >= 0
        return QuickBarModel.items(menu, archiveCapable(), inArchive(), nowProg, favourite, previous)
    }

    private fun quickIndex(items: List<QuickItem>): Int {
        val f = quickFocus ?: return 0
        val i = items.indexOf(f)
        if (i >= 0) return i
        val alt = when (f) {
            QuickItem.FAVOURITE_ADD -> QuickItem.FAVOURITE_REMOVE
            QuickItem.FAVOURITE_REMOVE -> QuickItem.FAVOURITE_ADD
            QuickItem.START_OVER, QuickItem.TIME_SHIFT -> QuickItem.JUMP_TO_LIVE
            QuickItem.JUMP_TO_LIVE -> QuickItem.TIME_SHIFT
            else -> null
        }
        return items.indexOf(alt).coerceAtLeast(0)
    }

    private fun quickKey(a: GuideEvent.Act): Boolean {
        val items = quickItems()
        val idx = quickIndex(items)
        when (a.action) {
            BoxAction.UP -> Unit
            BoxAction.DOWN, BoxAction.BACK -> closeLayer()
            BoxAction.LEFT -> quickFocus = items.getOrNull((idx - 1).coerceAtLeast(0))
            BoxAction.RIGHT -> quickFocus = items.getOrNull((idx + 1).coerceAtMost(items.size - 1))
            BoxAction.SELECT -> items.getOrNull(idx)?.let { quickSelect(it) }
            BoxAction.ZAP_UP -> zap(+1, a.repeat)
            BoxAction.ZAP_DOWN -> zap(-1, a.repeat)
            BoxAction.CONTEXT_MENU, BoxAction.QUICK_MENU -> closeLayer()
            else -> return bareKey(a, justClosedByBack = false)
        }
        return true
    }

    private fun quickSelect(item: QuickItem) {
        quickFocus = item
        val ch = tunedChannel
        when (item) {
            QuickItem.AUDIO -> openPicker(PickerKind.AUDIO, fromQuickBar = true)
            QuickItem.ASPECT -> openPicker(PickerKind.ASPECT, fromQuickBar = true)
            QuickItem.SUBTITLES -> openPicker(PickerKind.SUBTITLES, fromQuickBar = true)
            QuickItem.VIDEO_DECODER -> openPicker(PickerKind.VIDEO_DECODER, fromQuickBar = true)
            QuickItem.FAVOURITE_ADD, QuickItem.FAVOURITE_REMOVE -> if (ch != null) {
                val on = item == QuickItem.FAVOURITE_ADD
                effects.setFavourite(ch, on)
                favourite = on
                quickFocus = if (on) QuickItem.FAVOURITE_REMOVE else QuickItem.FAVOURITE_ADD
            }
            QuickItem.START_OVER -> { closeLayer(); startOverKey() }
            QuickItem.JUMP_TO_LIVE -> { closeLayer(); jumpToLive() }
            QuickItem.TIME_SHIFT -> openScrubAtLive()
            QuickItem.PREVIOUS_CHANNEL -> { closeLayer(); previousChannel() }
            QuickItem.STREAM_INFO -> toggleStats()
            QuickItem.SCREEN_OFF -> screenOff()
            QuickItem.SETTINGS -> { closeLayer(); effects.openSettings() }
            QuickItem.SOURCES -> openPicker(PickerKind.SERVER, fromQuickBar = true) // M5 integration (§3.6.2, E23)
        }
    }

    private fun queryFavourite(ch: GuideChannel, purpose: FavPurpose) {
        favToken++
        favPurpose = purpose
        favChannel = ch
        effects.queryFavourite(favToken, ch)
    }

    private fun onFavourite(token: Long, isFavourite: Boolean) {
        if (token != favToken) return
        val ch = favChannel ?: return
        when (favPurpose) {
            FavPurpose.QUICK -> if (layer == PlayerLayer.QUICK_BAR) favourite = isFavourite
            FavPurpose.TOGGLE -> effects.setFavourite(ch, !isFavourite)
            FavPurpose.LIST_MENU -> if (layer == PlayerLayer.CHANNELS && listMenuChannel === ch) {
                val items = ArrayList<ChannelMenuItem>(3)
                if (ch.archiveDays > 0) items += ChannelMenuItem.WATCH_FROM_START
                items += if (isFavourite) ChannelMenuItem.REMOVE_FAVOURITE else ChannelMenuItem.ADD_FAVOURITE
                items += ChannelMenuItem.HIDE_CHANNEL
                items += ChannelMenuItem.LOCK_CHANNEL // M5 (§3.9.1)
                listMenu = items
                listMenuFocused = 0
            }
            FavPurpose.NONE -> Unit
        }
        favPurpose = FavPurpose.NONE
    }

    // ================================================================= pickers (§3.4.2, §3.4.3)

    private fun openPicker(kind: PickerKind, fromQuickBar: Boolean) {
        val choices = choicesFor(kind, menu)
        when (kind) {
            PickerKind.AUDIO -> if (menu.audio.count { it.supported } < 2 && menu.audio.none { it.selected && !it.supported }) {
                effects.toast(PlayerToast.NO_AUDIO_CHOICE); return
            }
            PickerKind.SUBTITLES -> if (menu.text.isEmpty()) { effects.toast(PlayerToast.NO_SUBTITLES); return }
            else -> Unit
        }
        val current = currentChoice(kind, choices)
        openLayer(PlayerLayer.PICKER)
        picker = PickerState(kind, choices, current, current.coerceAtLeast(0), if (kind == PickerKind.ASPECT) true else null, fromQuickBar)
    }

    private fun choicesFor(kind: PickerKind, m: TrackMenu): List<PickerChoice> = when (kind) {
        PickerKind.AUDIO -> m.audio.map { PickerChoice.Audio(it) }
        PickerKind.SUBTITLES -> buildList {
            add(PickerChoice.TextOff)
            m.text.filter { !it.speculative }.forEach { add(PickerChoice.Text(it)) }
            m.text.filter { it.speculative }.forEach { add(PickerChoice.Text(it)) } // E14: speculative captions last
        }
        PickerKind.ASPECT -> AspectMode.entries.map { PickerChoice.Aspect(it) }
        PickerKind.VIDEO_DECODER -> DECODER_CHOICES
        PickerKind.SERVER -> m.servers.mapIndexed { i, label -> PickerChoice.Server(i, label) } // M5 integration (§3.6.2)
    }

    private fun currentChoice(kind: PickerKind, choices: List<PickerChoice>): Int = when (kind) {
        PickerKind.AUDIO -> choices.indexOfFirst { it is PickerChoice.Audio && it.track.selected }.coerceAtLeast(0)
        PickerKind.SUBTITLES -> if (!menu.textEnabled) 0 else choices.indexOfFirst { it is PickerChoice.Text && it.track.selected }.coerceAtLeast(0)
        PickerKind.ASPECT -> choices.indexOfFirst { it is PickerChoice.Aspect && it.mode == video.aspect }.coerceAtLeast(0)
        PickerKind.VIDEO_DECODER -> {
            val d = video.decoderOverride?.takeIf { it != VideoDecoderMode.AUTO }
            choices.indexOfFirst { it is PickerChoice.Decoder && it.mode == d }.coerceAtLeast(0)
        }
        PickerKind.SERVER -> menu.server.coerceIn(0, (choices.size - 1).coerceAtLeast(0))
    }

    private fun pickerKey(a: GuideEvent.Act): Boolean {
        val p = picker ?: run { closeLayer(); return true }
        val rows = p.choices.size + if (p.remember != null) 1 else 0
        when (a.action) {
            BoxAction.UP -> picker = p.copy(focused = (p.focused - 1).coerceAtLeast(0))
            BoxAction.DOWN -> picker = p.copy(focused = (p.focused + 1).coerceAtMost(rows - 1))
            BoxAction.LEFT, BoxAction.BACK -> if (p.fromQuickBar) { picker = null; layer = PlayerLayer.QUICK_BAR } else closeLayer()
            BoxAction.RIGHT -> Unit
            BoxAction.SELECT -> {
                if (p.focused >= p.choices.size) { picker = p.copy(remember = !(p.remember ?: true)); return true }
                val c = p.choices[p.focused]
                if (c is PickerChoice.Audio && !c.track.supported) return true
                apply(c, p.remember ?: true)
                closeLayer()
            }
            BoxAction.ZAP_UP -> zap(+1, a.repeat)
            BoxAction.ZAP_DOWN -> zap(-1, a.repeat)
            else -> return bareKey(a, justClosedByBack = false)
        }
        return true
    }

    private fun apply(c: PickerChoice, remember: Boolean) {
        when (c) {
            is PickerChoice.Audio -> effects.selectAudio(c.track.key)
            is PickerChoice.Text -> effects.selectText(c.track.key)
            PickerChoice.TextOff -> effects.selectText(null)
            is PickerChoice.Aspect -> effects.setAspect(c.mode, remember)
            is PickerChoice.Decoder -> effects.setDecoder(c.mode)
            is PickerChoice.Server -> effects.selectServer(c.index)
        }
    }

    private fun onTracks(m: TrackMenu) {
        val p = picker
        // S2: during an owned hand-off an empty menu keeps the open picker's choices.
        if (tuned.handoff && m.audio.isEmpty() && m.text.isEmpty()) return
        menu = m
        if (p != null && (p.kind == PickerKind.AUDIO || p.kind == PickerKind.SUBTITLES)) {
            val focusKey = keyOf(p.choices.getOrNull(p.focused))
            val choices = choicesFor(p.kind, m)
            val focused = if (p.focused >= p.choices.size) choices.size
            else choices.indexOfFirst { keyOf(it) == focusKey && (focusKey != null || it == PickerChoice.TextOff) }.let { if (it < 0) 0 else it }
            picker = p.copy(choices = choices, current = currentChoice(p.kind, choices), focused = focused)
        }
    }

    private fun keyOf(c: PickerChoice?): TrackKey? = when (c) {
        is PickerChoice.Audio -> c.track.key
        is PickerChoice.Text -> c.track.key
        else -> null
    }

    private fun onVideo(f: VideoFacts) {
        video = f
        val p = picker ?: return
        if (p.kind == PickerKind.ASPECT || p.kind == PickerKind.VIDEO_DECODER) picker = p.copy(current = currentChoice(p.kind, p.choices))
    }

    // ================================================================= error card (§3.7)

    /** (B1/F3) The visible tuned phase is ERROR, or the archive session FAILED for this channel (not a toast-only failure). */
    private fun errorActive(): Boolean {
        if (tuned.phase == MiniPhase.ERROR) return true
        if (status.phase != ArchivePhase.FAILED || status.identityHash != tuned.identityHash || tuned.identityHash == 0L) return false
        if (status.failure == ArchiveFailure.NO_PROGRAMME) return false
        // A build failure while live keeps playing is a toast (the launcher's), not a card over working video.
        return tuned.isArchive || tuned.phase == MiniPhase.STOPPED || tuned.phase == MiniPhase.IDLE
    }

    private fun currentError(): PlaybackError =
        if (tuned.phase == MiniPhase.ERROR) tuned.errorDetail ?: PlaybackError(tuned.error ?: PlaybackErrorCode.INTERNAL)
        else status.error ?: ErrorCardModel.archiveFailure(status.failure)

    private fun archiveError(): Boolean =
        tuned.isArchive || (status.phase == ArchivePhase.FAILED && status.identityHash == tuned.identityHash)

    private fun errorCard(): ErrorCardState {
        val archiveFailed = status.phase == ArchivePhase.FAILED && status.identityHash == tuned.identityHash
        val variantLeft = archiveError() && status.attempts > 0 && status.attempt < status.attempts
        val c = ErrorCardModel.card(currentError(), tunedChannel, video.decoderOverride, variantLeft, archiveFailed)
        return c.copy(focused = errorFocused.coerceIn(0, c.buttons.size - 1))
    }

    private fun syncError() {
        val active = inFull && errorActive()
        if (active) {
            val code = currentError().code
            if (!errorWasActive || code != errorCode) {
                errorFocused = 0
                errorCode = code
                // The error closes every other layer except stats (§3.1.3).
                if (layer == PlayerLayer.SCREEN_OFF) effects.setScreenOff(false)
                closeAllKeepPassive()
                layer = PlayerLayer.ERROR
            }
        } else {
            errorCode = null
            if (layer == PlayerLayer.ERROR) layer = PlayerLayer.NONE
        }
        errorWasActive = active
    }

    private fun closeAllKeepPassive() {
        val p = passive
        closeAll()
        passive = p
    }

    private fun errorKey(a: GuideEvent.Act, justClosed: Boolean): Boolean {
        val card = errorCard()
        when (a.action) {
            BoxAction.UP, BoxAction.DOWN -> upDown(a, fromError = true)
            BoxAction.LEFT -> errorFocused = (card.focused - 1).coerceAtLeast(0)
            BoxAction.RIGHT -> errorFocused = (card.focused + 1).coerceAtMost(card.buttons.size - 1)
            BoxAction.SELECT -> return errorButton(card.buttons[card.focused], card)
            BoxAction.BACK -> return backAtBase(justClosed)
            BoxAction.ZAP_UP -> zap(+1, a.repeat)
            BoxAction.ZAP_DOWN -> zap(-1, a.repeat)
            BoxAction.CONTEXT_MENU, BoxAction.QUICK_MENU -> openQuick()
            BoxAction.PLAY_PAUSE, BoxAction.PLAY, BoxAction.PAUSE, BoxAction.SEEK_BACK, BoxAction.SEEK_FORWARD -> Unit
            else -> return bareKey(a, justClosed)
        }
        return true
    }

    /** False for GUIDE: the guide machine's FULL code returns to the guide on an unconsumed SELECT over the card. */
    private fun errorButton(b: ErrorButton, card: ErrorCardState): Boolean {
        val ch = tunedChannel
        when (b) {
            // F5: an archive Retry goes through ArchivePlayback, never controller.retry(): archiveResume() is
            // ArchivePlayback.resume(), which re-requests at the playhead (or repeats the last plan before first media).
            ErrorButton.RETRY -> if (archiveError() && status.identityHash == tuned.identityHash && status.phase != ArchivePhase.IDLE) effects.archiveResume() else effects.retry()
            ErrorButton.PREVIOUS_CHANNEL -> effects.previousChannel()
            ErrorButton.WATCH_LIVE -> effects.jumpToLive()
            ErrorButton.TRY_OTHER_CATCHUP_FORMAT -> effects.tryNextCatchupFormat()
            ErrorButton.TRY_SOFTWARE_DECODER -> effects.setDecoder(VideoDecoderMode.SOFTWARE_PREFERRED)
            ErrorButton.AUDIO_OPTIONS -> openPicker(PickerKind.AUDIO, fromQuickBar = false)
            ErrorButton.STOP -> effects.stopPlayback()
            ErrorButton.GUIDE -> return false
            // M5 commit 0 (§3.14): one-line hand-offs; WP-C's ErrorCardModel is what offers these buttons.
            ErrorButton.OPEN_SETTINGS -> card.route?.let { effects.openSettings(it) }
            ErrorButton.USE_SECURE_DNS, ErrorButton.REFRESH_PLAYLIST, ErrorButton.OPEN_EXTERNAL, ErrorButton.BACK_TO_AUTO -> effects.errorAction(b, card)
        }
        return true
    }

    // ================================================================= scrub bar (§3.5)

    private fun openScrubFocused() {
        if (layer != PlayerLayer.SCRUB) openLayer(PlayerLayer.SCRUB)
        scrubShown = true
        scrubFocused = true
        scrubFocus = ScrubFocus.BAR
        chipFocused = -1
        scrubAtLive = !inArchive()
        scrubHideAt = Long.MAX_VALUE
    }

    private fun openScrubAtLive() {
        openScrubFocused()
        scrubAtLive = true
    }

    /** The unfocused bar (§3.2.1): after an archive start, START_OVER, rewind or pause; takes no keys. */
    private fun showScrubAuto(atLive: Boolean = false) {
        if (!inFull) return
        if (layer != PlayerLayer.NONE && layer != PlayerLayer.SCRUB) return
        if (scrubShown) return
        passive = false
        scrubShown = true
        scrubFocused = false
        scrubFocus = ScrubFocus.BAR
        chipFocused = -1
        scrubAtLive = atLive && !inArchive()
        scrubHideAt = Long.MAX_VALUE
    }

    private fun scrubKey(a: GuideEvent.Act): Boolean {
        if (scrubFocus == ScrubFocus.CHIPS) return chipsKey(a)
        when (a.action) {
            BoxAction.UP -> if (!scrubAtLive) jumpToLive()
            BoxAction.DOWN -> {
                val (prev, next) = chips()
                when {
                    next != null -> { scrubFocus = ScrubFocus.CHIPS; chipFocused = 1 }
                    prev != null -> { scrubFocus = ScrubFocus.CHIPS; chipFocused = 0 }
                    else -> openQuick() // no chips: the five-key route to the quick bar (WP-A addition, see the hand-off notes)
                }
            }
            BoxAction.LEFT -> scrubStep(-ScrubModel.stepMs(a.heldMs), openFocused = false)
            BoxAction.RIGHT -> scrubStep(ScrubModel.stepMs(a.heldMs), openFocused = false)
            BoxAction.SELECT -> when {
                commitAt != Long.MAX_VALUE -> commit()
                scrubAtLive -> tunedChannel?.let { effects.pauseLive(it) }
                archivePaused() -> effects.archiveResume()
                else -> effects.archivePause()
            }
            BoxAction.BACK -> if (commitAt != Long.MAX_VALUE) cancelPreview() else closeLayer()
            BoxAction.SEEK_BACK -> scrubStep(-ScrubModel.ffRwStepMs(a.repeat, a.heldMs), openFocused = false)
            BoxAction.SEEK_FORWARD -> scrubStep(ScrubModel.ffRwStepMs(a.repeat, a.heldMs), openFocused = false)
            BoxAction.PREV_PROGRAMME -> seekProgramme(-1)
            BoxAction.NEXT_PROGRAMME -> seekProgramme(+1)
            BoxAction.INFO_BAR -> closeLayer()
            else -> return bareKey(a, justClosedByBack = false)
        }
        return true
    }

    private fun chipsKey(a: GuideEvent.Act): Boolean {
        val (prev, next) = chips()
        when (a.action) {
            BoxAction.UP, BoxAction.BACK -> { scrubFocus = ScrubFocus.BAR; chipFocused = -1 }
            BoxAction.DOWN -> openQuick() // WP-A addition: the quick bar under every Up/Down setting in archive (hand-off notes)
            BoxAction.LEFT -> if (prev != null) chipFocused = 0
            BoxAction.RIGHT -> if (next != null) chipFocused = 1
            BoxAction.SELECT -> {
                val p = if (chipFocused == 0) prev else next
                scrubFocus = ScrubFocus.BAR
                chipFocused = -1
                if (p != null) seekToProgramme(p)
            }
            else -> { scrubFocus = ScrubFocus.BAR; chipFocused = -1; return scrubKey(a) }
        }
        return true
    }

    /** Applies one step to the preview and re-arms the 600 ms commit (§3.5.2). */
    private fun scrubStep(deltaMs: Long, openFocused: Boolean) {
        if (openFocused || !scrubShown || !scrubFocused) openScrubFocused()
        val from = archiveFromNow()
        val edge = liveEdgeNow()
        val p = ScrubModel.preview(preview, playheadNow(), deltaMs, from, edge)
        preview = p
        previewAtEdge = p >= edge
        commitAt = curMs + ScrubModel.SCRUB_COMMIT_MS
    }

    private fun cancelPreview() {
        previewAtEdge = false
        if (commitAt != Long.MAX_VALUE) preview = null
        commitAt = Long.MAX_VALUE
        if (awaitSeq == 0L && !atLiveCommitPending) preview = null
    }

    /** At the deadline or on SELECT: one seek (or a rewind from live, or live) for the whole gesture (§3.5.2, B3). */
    private fun commit() {
        val t = preview ?: return
        commitAt = Long.MAX_VALUE
        val ch = tunedChannel ?: return
        val edge = liveEdgeNow()
        val live = previewAtEdge || t >= edge
        previewAtEdge = false
        if (scrubAtLive) {
            if (live) { preview = null; return }
            effects.rewindLive(ch, edge - t)
            atLiveCommitPending = true
            return
        }
        if (live) { preview = null; jumpToLive(); return }
        seekSeq++
        awaitSeq = seekSeq
        effects.archiveSeek(t, seekSeq)
    }

    private fun seekTo(t: Long) {
        val edge = liveEdgeNow()
        if (t >= edge) { jumpToLive(); return }
        preview = t
        commitAt = Long.MAX_VALUE
        seekSeq++
        awaitSeq = seekSeq
        effects.archiveSeek(t, seekSeq)
    }

    private fun seekToProgramme(p: ProgrammeInfo) {
        val edge = liveEdgeNow()
        val target = ScrubModel.chipTarget(p, edge)
        when {
            // P-B1: a programme target is EPG-anchored. Before a session exists the machine's edge is the device clock, so
            // `edge − target` as a rewindLive distance landed `correction + skew` away from the programme on the panel.
            scrubAtLive -> if (target != null) { tunedChannel?.let { effects.playProgramme(it, p, timeShift = true) }; preview = target; atLiveCommitPending = true }
            target == null -> jumpToLive()
            else -> seekTo(target)
        }
    }

    /** PREV/NEXT_PROGRAMME in archive: the previous programme's start / the next one's (next beyond live → live). */
    private fun seekProgramme(dir: Int) {
        val scope = scopeAt(playheadNow())
        val p = scope.programme
        if (p == null) return
        if (dir < 0) {
            val i = progs.indexOfFirst { it.rawStartMin == p.rawStartMin }
            val prev = if (i > 0) progs[i - 1] else null
            if (prev != null) seekToProgramme(prev) else seekTo(ScrubModel.startMs(p))
        } else {
            val i = progs.indexOfFirst { it.rawStartMin == p.rawStartMin }
            val next = if (i >= 0) progs.getOrNull(i + 1) else null
            if (next != null) seekToProgramme(next) else jumpToLive()
        }
    }

    private fun jumpToLive() {
        effects.jumpToLive()
        if (layer == PlayerLayer.SCRUB) closeLayer()
        scrubShown = false
        scrubFocused = false
        preview = null
        commitAt = Long.MAX_VALUE
    }

    private fun chips(): Pair<ProgrammeInfo?, ProgrammeInfo?> {
        val p = scopeAt(shownMs()).programme ?: return null to null
        val i = progs.indexOfFirst { it.rawStartMin == p.rawStartMin }
        if (i < 0) return null to null
        val prev = progs.getOrNull(i - 1)
        val next = progs.getOrNull(i + 1)?.takeIf { !scrubAtLive }
        return prev to next
    }

    private fun shownMs(): Long = preview ?: playheadNow()

    /** The programme containing [t] (§3.5.1), else the last bounds while a load is pending, else ±30 min. */
    private fun scopeAt(t: Long): ScopeBounds {
        val ch = tunedChannel
        if (ch != null && progHash == ch.identityHash) {
            val i = ScrubModel.indexAt(progs, t)
            if (i >= 0) return ScopeBounds(progs[i], ScrubModel.startMs(progs[i]), ScrubModel.endMs(progs[i])).also { lastScope = it }
        }
        status.programme?.takeIf { status.identityHash == tuned.identityHash && ScrubModel.contains(it, t) }?.let {
            return ScopeBounds(it, ScrubModel.startMs(it), ScrubModel.endMs(it)).also { s -> lastScope = s }
        }
        val pendingLoad = ch != null && progHash == ch.identityHash && !progLoaded
        lastScope?.takeIf { pendingLoad || (preview != null && scrubLoadAroundMin != Int.MIN_VALUE) }?.let { return it }
        val b = ScrubModel.noEpgBounds(t, archiveFromNow(), liveEdgeNow())
        return ScopeBounds(null, b[0], b[1])
    }

    /** Crossing a boundary with the preview re-scopes the bar: ask for programmes around it (§3.5.2). */
    private fun ensureScrubProgrammes() {
        val ch = tunedChannel ?: return
        if (progHash != ch.identityHash) { loadProgrammesFor(ch, force = false); return }
        if (!progLoaded) return
        val t = shownMs()
        if (ScrubModel.indexAt(progs, t) >= 0) return
        val minute = (t / 60_000L).toInt() + (status.displayShiftMs / 60_000L).toInt()
        if (scrubLoadAroundMin == minute) return
        scrubLoadAroundMin = minute
        requestAround(ch, minute)
    }

    private fun scrubState(): ScrubState? {
        if (!scrubShown) return null
        ensureScrubProgrammes()
        val atLive = scrubAtLive
        val edge = liveEdgeNow()
        val scope = scopeAt(shownMs())
        val (prev, next) = chips()
        val st = statusForTuned()
        return ScrubState(
            focused = scrubFocused && layer == PlayerLayer.SCRUB,
            focus = scrubFocus,
            programme = scope.programme,
            barFromMs = scope.from,
            barToMs = scope.to,
            archiveFromMs = if (st != null && !atLive) st.archiveFromMs else archiveFromNow(),
            liveEdgeMs = if (st != null && !atLive) st.liveEdgeMs else edge,
            playheadMs = if (st != null && !atLive) st.wallMs else edge,
            atMs = if (st != null && !atLive) st.atMs else curMs,
            advancing = if (st != null && !atLive) st.advancing else true,
            previewMs = preview,
            prev = prev,
            next = next,
            chipFocused = if (scrubFocus == ScrubFocus.CHIPS) chipFocused else -1,
            displayShiftMs = st?.displayShiftMs ?: ((tunedChannel?.epgShiftMin ?: 0) * 60_000L),
            pill = PillRules.pill(tuned, st, curMs),
            seeking = awaitSeq != 0L || atLiveCommitPending || tuned.handoff,
            atLive = atLive,
        )
    }

    // ---------------------------------------------------------------- archive status

    private fun onArchive(s: ArchiveStatus) {
        val was = statusForTuned()
        status = s
        val now = statusForTuned()
        // (M4 integration 2026-10-15) The archive session is app-wide and outlives this machine (a new PlayerViewModel
        // after an activity recreation): continue from its high-water mark, or our first seq would equal an already
        // settled one and the preview would clear before the seek settled (B3).
        if (s.settledSeq > seekSeq) seekSeq = s.settledSeq
        // B3: the preview clears when the session echoes our seek, wherever the playhead landed.
        if (awaitSeq != 0L && s.settledSeq >= awaitSeq) { awaitSeq = 0; if (commitAt == Long.MAX_VALUE) preview = null }
        if (atLiveCommitPending && now != null && (now.phase == ArchivePhase.PLAYING || now.phase == ArchivePhase.PAUSED || now.phase == ArchivePhase.PAUSED_LIVE)) {
            atLiveCommitPending = false
            if (commitAt == Long.MAX_VALUE) preview = null
        }
        if (s.phase == ArchivePhase.FAILED || s.phase == ArchivePhase.IDLE) { atLiveCommitPending = false; awaitSeq = 0; if (commitAt == Long.MAX_VALUE) preview = null }
        // A session started (or was taken over) for the tuned channel: the bar shows unfocused (§3.2.1).
        if (was == null && now != null) {
            scrubAtLive = false
            if (inFull && (layer == PlayerLayer.NONE) && !scrubShown) showScrubAuto()
        }
        if (now != null && scrubShown) scrubAtLive = false
    }

    private fun statusForTuned(): ArchiveStatus? =
        status.takeIf { it.phase != ArchivePhase.IDLE && it.phase != ArchivePhase.FAILED && (tuned.identityHash == 0L || it.identityHash == tuned.identityHash) }

    private fun inArchive(): Boolean = statusForTuned() != null || tuned.isArchive

    private fun archivePaused(): Boolean {
        val s = statusForTuned()
        return s != null && (s.phase == ArchivePhase.PAUSED || s.phase == ArchivePhase.PAUSED_LIVE)
    }

    private fun archiveCapable(): Boolean = (tunedChannel?.archiveDays ?: 0) > 0 && !tuned.isArchive

    private fun liveEdgeNow(): Long {
        val s = statusForTuned()
        return if (s != null && s.liveEdgeMs > 0) s.liveEdgeMs + (curMs - s.atMs) else curMs
    }

    private fun archiveFromNow(): Long {
        val s = statusForTuned()
        if (s != null && s.archiveFromMs > 0) return s.archiveFromMs + (curMs - s.atMs)
        val days = tunedChannel?.archiveDays ?: 0
        return curMs - days * 86_400_000L + EDGE_MARGIN_MS
    }

    private fun playheadNow(): Long {
        val s = statusForTuned() ?: return liveEdgeNow()
        if (scrubAtLive) return liveEdgeNow()
        return s.wallMs + if (s.advancing) curMs - s.atMs else 0L
    }

    // ================================================================= channel list (§3.3)

    private fun listLineupNow(): GuideLineup? = listLineup?.takeIf { it.source == listSource } ?: guideLineup?.takeIf { it.source == listSource }

    private fun listSize(): Int = listLineupNow()?.rowIds?.size ?: 0

    private fun tunedIndexIn(l: GuideLineup?): Int {
        if (l == null || tuned.rowId < 0) return -1
        val ids = l.rowIds
        for (i in ids.indices) if (ids[i] == tuned.rowId) return i
        return -1
    }

    private fun openList(column: ListColumn, byBack: Boolean, offset: Int) {
        openLayer(PlayerLayer.CHANNELS)
        listSource = guideSource
        listOpenedByBack = byBack
        listColumn = column
        listMenu = null
        listPendingFocus = false
        val src = guideSource
        if (src != null && listLineup?.source != src) effects.loadListLineup(src)
        val lineup = listLineupNow()
        val tunedIdx = tunedIndexIn(lineup)
        list.open(listSize(), if (tunedIdx >= 0) tunedIdx + offset else 0, previous != null)
        listGroupFocused = currentGroupIndex().coerceAtLeast(0)
        ensureList()
    }

    private fun openFavouritesList() {
        val fav = sources.firstOrNull { it.section == SourceSection.FAVOURITES }
        if (fav == null) { effects.toast(PlayerToast.NO_FAVOURITES); return }
        openLayer(PlayerLayer.CHANNELS)
        listOpenedByBack = false
        listColumn = ListColumn.CHANNELS
        listMenu = null
        applyListSource(fav.source)
    }

    private fun applyListSource(src: GuideSource) {
        listSource = src
        listPendingFocus = true
        val l = listLineupNow()
        if (l != null && listLineup?.source == src) focusAfterLineup(l) else {
            list.open(0, 0, previous != null)
            effects.loadListLineup(src)
        }
        listGroupFocused = currentGroupIndex().coerceAtLeast(0)
    }

    private fun focusAfterLineup(l: GuideLineup) {
        listPendingFocus = false
        val t = tunedIndexIn(l)
        list.open(l.rowIds.size, if (t >= 0) t else 0, previous != null)
        ensureList()
    }

    private fun onListLineup(l: GuideLineup) {
        val prevSize = listLineup?.takeIf { it.source == l.source }?.rowIds?.size
        listLineup = l
        if (layer != PlayerLayer.CHANNELS || l.source != listSource) return
        if (listPendingFocus || prevSize == null && list.size == 0) focusAfterLineup(l)
        else { list.resize(l.rowIds.size, previous != null); ensureList() }
    }

    private fun currentGroupIndex(): Int = sources.indexOfFirst { it.source == listSource }

    private fun ensureList() {
        val size = list.size
        if (size == 0) return
        val top = list.top
        effects.ensureListRows(maxOf(0, top - ChannelListModel.LIST_PREFETCH_BEFORE), minOf(size, top + ChannelListModel.LIST_VISIBLE + ChannelListModel.LIST_PREFETCH_AFTER))
        effects.ensureListNow(top, minOf(size, top + ChannelListModel.LIST_VISIBLE))
    }

    private fun listKey(a: GuideEvent.Act): Boolean {
        val m = listMenu
        if (m != null) return listMenuKey(a, m)
        if (listColumn == ListColumn.GROUPS) return groupsKey(a)
        when (a.action) {
            BoxAction.UP -> { list.move(-1, a.repeat); ensureList() }
            BoxAction.DOWN -> { list.move(+1, a.repeat); ensureList() }
            BoxAction.LEFT -> { listColumn = ListColumn.GROUPS; listGroupFocused = currentGroupIndex().coerceAtLeast(0) }
            BoxAction.RIGHT -> listRowChannel()?.let { if (it.identityHash != tuned.identityHash) effects.tune(it, listLineupNow()) }
            BoxAction.SELECT -> {
                if (list.focused == -1) { previous?.let { effects.previousChannel() }; return true }
                val ch = listRowChannel() ?: return true
                if (ch.identityHash == tuned.identityHash) closeLayer() else effects.tune(ch, listLineupNow())
            }
            BoxAction.BACK -> {
                if (listOpenedByBack) return false
                closeLayer()
                listJustClosedByBack = true
            }
            BoxAction.ZAP_UP -> { list.page(-1); ensureList() }
            BoxAction.ZAP_DOWN -> { list.page(+1); ensureList() }
            BoxAction.CONTEXT_MENU -> {
                val ch = (if (list.focused == -1) previous else listRowChannel()) ?: return true
                listMenuChannel = ch
                queryFavourite(ch, FavPurpose.LIST_MENU)
            }
            BoxAction.CHANNEL_LIST -> closeLayer()
            BoxAction.DIGIT -> return false
            else -> return bareKey(a, justClosedByBack = false)
        }
        return true
    }

    private fun listRowChannel(): GuideChannel? = if (list.focused < 0) null else listRows.channel(list.focused)

    private fun groupsKey(a: GuideEvent.Act): Boolean {
        when (a.action) {
            BoxAction.UP -> listGroupFocused = (listGroupFocused - 1).coerceAtLeast(0)
            BoxAction.DOWN -> listGroupFocused = (listGroupFocused + 1).coerceAtMost(maxOf(0, sources.size - 1))
            BoxAction.RIGHT, BoxAction.BACK -> listColumn = ListColumn.CHANNELS
            BoxAction.LEFT -> Unit
            BoxAction.SELECT -> sources.getOrNull(listGroupFocused)?.let { e ->
                // M5 (§3.9.2): a locked group asks for the PIN (or the not-protected card) through the guide's prompt.
                if (ParentalRules.needsDoor(e, parental) && pinGate?.request(PinPurpose.OpenSource(e, PinOwner.PLAYER)) == true) return true
                listColumn = ListColumn.CHANNELS
                applyListSource(e.source)
            }
            BoxAction.ZAP_UP, BoxAction.ZAP_DOWN -> {
                if (sources.isEmpty()) return true
                val cur = currentGroupIndex().let { if (it < 0) listGroupFocused else it }
                val next = (cur + if (a.action == BoxAction.ZAP_UP) -1 else 1).coerceIn(0, sources.size - 1)
                if (next != cur || currentGroupIndex() < 0) { applyListSource(sources[next].source); listGroupFocused = next }
            }
            BoxAction.DIGIT -> return false
            else -> return bareKey(a, justClosedByBack = false)
        }
        return true
    }

    private fun listMenuKey(a: GuideEvent.Act, m: List<ChannelMenuItem>): Boolean {
        when (a.action) {
            BoxAction.UP -> listMenuFocused = (listMenuFocused - 1).coerceAtLeast(0)
            BoxAction.DOWN -> listMenuFocused = (listMenuFocused + 1).coerceAtMost(m.size - 1)
            BoxAction.BACK -> listMenu = null
            BoxAction.SELECT -> {
                val ch = listMenuChannel
                val item = m.getOrNull(listMenuFocused)
                listMenu = null
                if (ch != null && item != null) when (item) {
                    ChannelMenuItem.WATCH_FROM_START -> effects.startOver(ch)
                    ChannelMenuItem.ADD_FAVOURITE -> effects.setFavourite(ch, true)
                    ChannelMenuItem.REMOVE_FAVOURITE -> effects.setFavourite(ch, false)
                    ChannelMenuItem.HIDE_CHANNEL -> effects.hideChannel(ch)
                    // M5 (§3.9.1, §3.9.2): with a PIN set (session locked) the PIN comes first.
                    ChannelMenuItem.LOCK_CHANNEL -> lockAction { effects.setChannelLocked(ch, true) }
                    ChannelMenuItem.CHANNEL_OPTIONS -> Unit // WP-A's Channel options page (§3.3.12) offers it
                }
            }
            else -> Unit
        }
        return true
    }

    /**
     * (M5, §3.9.2) Lock from the channel list's menu: with a PIN and a locked session the PIN first (a `SettingsAction`
     * whose token the guide routes back here); otherwise at once (without a PIN the lock filters at once and the effect
     * toasts "Set a PIN to protect it").
     */
    private fun lockAction(run: () -> Unit) {
        val gate = pinGate
        if (parental.pinSet && !parental.unlocked && gate != null) {
            val t = PLAYER_ACTION_TOKEN_BASE + lockToken++
            pendingLocks[t] = run
            if (gate.request(PinPurpose.SettingsAction(t))) return
            pendingLocks.remove(t)
        }
        run()
    }

    /** The guide's prompt answered (§0.9). */
    private fun onPinResult(purpose: PinPurpose, granted: Boolean) {
        when (purpose) {
            is PinPurpose.OpenSource -> if (granted && layer == PlayerLayer.CHANNELS) {
                listColumn = ListColumn.CHANNELS
                applyListSource(purpose.entry.source)
                listGroupFocused = sources.indexOfFirst { it.source == purpose.entry.source }.coerceAtLeast(0)
            }
            // A refused tune the player asked about (list, zap target, previous): tune it now (the guard allows it).
            is PinPurpose.Tune -> if (granted) effects.tune(purpose.channel, null)
            is PinPurpose.SettingsAction -> pendingLocks.remove(purpose.token)?.let { if (granted) it() }
            is PinPurpose.EnterSettings -> Unit
            is PinPurpose.CreatePin -> Unit
        }
    }

    private fun listState(): ChannelListState? {
        if (layer != PlayerLayer.CHANNELS) return null
        val l = listLineupNow()
        return ChannelListState(
            column = listColumn,
            groups = sources,
            groupFocused = listGroupFocused,
            groupCurrent = currentGroupIndex(),
            source = listSource,
            lineupVersion = l?.version ?: 0L,
            size = list.size,
            focused = list.focused,
            top = list.top,
            previous = previous,
            tunedRowId = tuned.rowId,
            rowsVersion = rowsVersion,
            nowVersion = nowVersion,
            openedByBack = listOpenedByBack,
            menu = listMenu,
            menuFocused = listMenuFocused,
        )
    }

    // ================================================================= tuned state, timers

    private fun onTuned(state: TunedState, channel: GuideChannel?) {
        val prevHash = tuned.identityHash
        val identityChanged = state.identityHash != prevHash
        tuned = state
        tunedChannel = channel ?: if (identityChanged) null else tunedChannel
        if (state.phase == MiniPhase.CONNECTING || state.phase == MiniPhase.BUFFERING) passiveSawConnecting = true
        if (identityChanged) {
            syncScrubValidity() // leaving archive by a zap closes the scrub bar before the passive bar decision
            if (layer == PlayerLayer.PICKER) closeLayer() // §3.4.3: a picker closes when the tuned identity changes
            favourite = null
            if (layer == PlayerLayer.QUICK_BAR) tunedChannel?.let { queryFavourite(it, FavPurpose.QUICK) }
            if (layer == PlayerLayer.INFO) {
                infoChannel = tunedChannel
                infoFocused = -1
                tunedChannel?.let { loadProgrammesFor(it, force = false) }
            }
            // S7: a tune the player did not issue (number entry, reminder switch) shows the passive bar too.
            val owned = status.phase != ArchivePhase.IDLE && status.phase != ArchivePhase.FAILED && status.identityHash == state.identityHash
            if (inFull && prevHash != 0L && state.identityHash != 0L && !owned) {
                if (layer == PlayerLayer.NONE || layer == PlayerLayer.ERROR || layer == PlayerLayer.SCRUB && !scrubFocused) {
                    pendingZap = null
                    showPassive(requireChangeFrom = 0L)
                } else if (passive) infoChannel = tunedChannel
            }
        }
        if (passive && !passiveAwait && pendingZap == null && infoChannel?.identityHash != tuned.identityHash && tunedChannel != null) infoChannel = tunedChannel
        armPassiveIfSettled()
    }

    private fun onPendingZap(ch: GuideChannel?) {
        pendingZap = ch
        if (ch != null && passive) {
            infoChannel = ch
            infoFocused = -1
            loadProgrammesFor(ch, force = false)
        }
        armPassiveIfSettled()
    }

    private fun tick() {
        if (passive && curMs >= passiveHideAt) { passive = false; passiveHideAt = Long.MAX_VALUE }
        if (layer == PlayerLayer.INFO && curMs >= infoHideAt) closeLayer()
        if (layer == PlayerLayer.QUICK_BAR && curMs >= quickHideAt) closeLayer()
        if (commitAt != Long.MAX_VALUE && curMs >= commitAt) commit()
        if (scrubShown && !scrubFocused && curMs >= scrubHideAt) { scrubShown = false; scrubHideAt = Long.MAX_VALUE; cancelPreview() }
    }

    /** The unfocused bar stays while paused, and hides SCRUB_AUTO_SHOW_MS after first media (§3.2.1). */
    private fun syncScrubAutoHide() {
        if (!scrubShown) { scrubHideAt = Long.MAX_VALUE; return }
        if (scrubFocused) { scrubHideAt = Long.MAX_VALUE; return }
        val s = statusForTuned()
        val playing = if (s != null) s.phase == ArchivePhase.PLAYING else tuned.phase == MiniPhase.PLAYING && !scrubAtLive
        when {
            archivePaused() || tuned.phase == MiniPhase.PAUSED -> scrubHideAt = Long.MAX_VALUE
            playing -> if (scrubHideAt == Long.MAX_VALUE) scrubHideAt = curMs + SCRUB_AUTO_SHOW_MS
            scrubAtLive && s == null -> if (scrubHideAt == Long.MAX_VALUE) scrubHideAt = curMs + SCRUB_AUTO_SHOW_MS
            else -> scrubHideAt = Long.MAX_VALUE
        }
    }

    /** The scrub bar exists only on archive/time-shift video, or at the live edge of an archive-capable channel. */
    private fun syncScrubValidity() {
        if (!scrubShown) return
        val ok = if (scrubAtLive) archiveCapable() || inArchive() else inArchive()
        if (!ok) {
            scrubShown = false
            scrubFocused = false
            cancelPreview()
            if (layer == PlayerLayer.SCRUB) layer = base()
        }
        if (scrubShown && scrubAtLive && inArchive() && !atLiveCommitPending) scrubAtLive = false
    }

    private fun nextDeadline(): Long {
        var d = Long.MAX_VALUE
        if (passive) d = minOf(d, passiveHideAt)
        if (layer == PlayerLayer.INFO) d = minOf(d, infoHideAt)
        if (layer == PlayerLayer.QUICK_BAR) d = minOf(d, quickHideAt)
        d = minOf(d, commitAt)
        if (scrubShown && !scrubFocused) d = minOf(d, scrubHideAt)
        return d
    }

    // ================================================================= UI

    private fun publish() {
        syncError()
        syncScrubValidity()
        syncScrubAutoHide()
        if (layer == PlayerLayer.PICKER && picker == null) layer = base()
        val st = statusForTuned()
        val pill = PillRules.pill(tuned, st, curMs)
        val info = infoState(pill)
        val quick = if (layer == PlayerLayer.QUICK_BAR) quickState() else null
        val s = PlayerUiState(
            layer = layer,
            info = info,
            scrub = scrubState(),
            channels = listState(),
            quick = quick,
            picker = if (layer == PlayerLayer.PICKER) picker else null,
            error = if (errorActive() && inFull) errorCard() else null,
            stats = if (statsOn) statsFacts?.let { StatsFormatter.lines(it) } ?: emptyList() else null,
            pill = pill,
            probing = status.takeIf { it.phase == ArchivePhase.PROBING && it.identityHash == tuned.identityHash }?.let { it.attempt to it.attempts },
        )
        if (s != _ui.value) _ui.value = s
        _deadline.value = nextDeadline()
    }

    private fun infoState(pill: app.thebox.core.full.PillState): InfoBarState? {
        val active = layer == PlayerLayer.INFO
        if (!active && !(passive && (layer == PlayerLayer.NONE || layer == PlayerLayer.ERROR))) return null
        val ch = infoChannel ?: (pendingZap ?: tunedChannel)
        val mine = ch != null && progHash == ch.identityHash
        val list = if (mine) progs else emptyList()
        val nowIdx = nowIndex(list)
        val focused = when {
            list.isEmpty() -> -1
            infoFocused in list.indices -> infoFocused
            else -> nowOrNext(list)
        }
        return InfoBarState(
            active = active,
            channel = ch,
            browsing = ch != null && ch.identityHash != tuned.identityHash && active,
            programmes = list,
            focused = focused,
            nowIndex = nowIdx,
            pill = pill,
            glyphs = glyphs(ch),
            loading = ch != null && !(mine && progLoaded),
        )
    }

    private fun glyphs(ch: GuideChannel?): InfoGlyphs {
        val forTuned = ch != null && ch.identityHash == tuned.identityHash
        val audio = if (forTuned) menu.audio.firstOrNull { it.selected } else null
        return InfoGlyphs(
            catchupDays = ch?.archiveDays ?: 0,
            resolution = if (forTuned && video.height > 0) StatsFormatter.resolutionLabel(video.height, video.fps, video.interlaced) else null,
            audioLayout = audio?.let { layoutOf(it.channels) },
            subtitlesAvailable = forTuned && menu.text.any { !it.speculative },
            hdr = if (forTuned) video.hdr else app.thebox.core.playback.HdrKind.SDR,
        )
    }

    private fun quickState(): QuickBarState {
        val items = quickItems()
        return QuickBarState(
            items = items,
            focused = quickIndex(items),
            statsOn = statsOn,
            audio = menu.audio.firstOrNull { it.selected },
            subtitle = if (menu.textEnabled) menu.text.firstOrNull { it.selected } else null,
            aspect = video.aspect,
            decoder = video.decoderOverride,
            previous = previous,
        )
    }

    companion object {
        /** (M5) The player's SettingsAction tokens (lock actions); equals GuideStateMachineImpl.PLAYER_ACTION_TOKEN_BASE. */
        const val PLAYER_ACTION_TOKEN_BASE = 1L shl 40
        /** Passive info bar after first media (§3.2.1). */
        const val INFO_AFTER_ZAP_MS = 4_000L
        /** The quick bar closes after this long without a key (§3.4.1, owner ruling 2026-10-15). */
        const val QUICK_BAR_IDLE_MS = 8_000L
        /** Unfocused scrub bar after an archive start (§3.2.1). */
        const val SCRUB_AUTO_SHOW_MS = 4_000L
        /** RW on live enters time-shift this far behind the edge (§3.1.2). */
        const val RW_ON_LIVE_BACK_MS = 30_000L
        /** Info bar programmes (§3.2.2): minutes back (archive channels only), minutes ahead, and the paging edge. */
        const val INFO_PAST_MIN = 360
        const val INFO_FUTURE_MIN = 720
        const val INFO_EDGE = 2
        /** The builder's edge margin (`EDGE_MARGIN_SEC`), for the live-edge bar before a session exists (§3.8.1). */
        const val EDGE_MARGIN_MS = 60_000L
        private const val MIN_PER_DAY = 1_440
        /** showPassive: arm on the first settled state after a CONNECTING (the zap may land on the same identity). */
        private const val ANY_CHANGE = Long.MIN_VALUE

        private val DECODER_CHOICES: List<PickerChoice> = listOf(
            PickerChoice.Decoder(null), PickerChoice.Decoder(VideoDecoderMode.HARDWARE_ONLY), PickerChoice.Decoder(VideoDecoderMode.SOFTWARE_PREFERRED),
        )

        fun layoutOf(channels: Int): String? = when (channels) {
            0 -> null
            1 -> "1.0"
            2 -> "2.0"
            6 -> "5.1"
            8 -> "7.1"
            else -> "$channels.0"
        }
    }
}
