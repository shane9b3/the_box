package app.thebox.ui.guide.vm

import app.thebox.core.settings.FirstRunDecider
import app.thebox.core.settings.SettingsMachine
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsStore
import app.thebox.core.settings.SettingsTimings
import app.thebox.ui.root.EffectDelegates
import kotlinx.coroutines.CompletableDeferred
import app.thebox.core.parental.legacyParentalAccess
import app.thebox.core.input.BoxAction
import app.thebox.core.input.KeyBindingStore
import app.thebox.core.input.KeyContext
import app.thebox.core.input.Press
import app.thebox.core.input.RemappedKeyMap
import app.thebox.core.input.DefaultKeyMap
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.parental.ParentalControls
import app.thebox.core.parental.ParentalHost
import app.thebox.core.parental.ParentalSession
import app.thebox.core.parental.PinOwner
import app.thebox.core.parental.PinPurpose
import app.thebox.core.playback.TuneGuard
import app.thebox.core.playback.TuneVerdict
import app.thebox.player.api.StopReason
import app.thebox.ui.parental.EmptyZapList
import app.thebox.ui.parental.GuardedPlaybackController
import app.thebox.ui.parental.NoKeyBindings
import app.thebox.ui.parental.NoPinSession
import app.thebox.ui.parental.ParentalEffectsImpl
import app.thebox.ui.parental.ParentalGuideLibrary
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import app.thebox.core.common.BoxLog
import app.thebox.core.guide.DeviceClass
import app.thebox.core.guide.DeviceClassSource
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideDataWindowSource
import app.thebox.core.guide.GuideDebugProbe
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideImages
import app.thebox.core.guide.GuideLibrary
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideMetricsFactory
import app.thebox.core.guide.GuideNavigator
import app.thebox.core.guide.GuideRenderer
import app.thebox.core.guide.GuideRowsCache
import app.thebox.core.guide.GuideRowsCacheFactory
import app.thebox.core.guide.GuideSessionStore
import app.thebox.core.guide.GuideSettings
import app.thebox.core.guide.GuideSettingsSource
import app.thebox.core.catchup.archive.ArchivePlayback
import app.thebox.core.full.OnLaunch
import app.thebox.core.full.PlayerSettingsSource
import app.thebox.core.full.PlayerStateMachine
import app.thebox.core.guide.GuideStateMachine
import app.thebox.core.guide.GuideStateMachineFactory
import app.thebox.core.guide.GuideToast
import app.thebox.core.guide.GuideWindowPager
import app.thebox.core.guide.GuideWindowPagerFactory
import app.thebox.core.guide.PerformanceMode
import app.thebox.core.guide.ReminderAlerts
import app.thebox.core.guide.ReminderBook
import app.thebox.core.guide.RowCellsBuilder
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.SyncChip
import app.thebox.core.guide.SyncFeedState
import app.thebox.core.guide.SyncFeed
import app.thebox.core.guide.TimeLabels
import app.thebox.core.input.KeyCapabilities
import app.thebox.core.playback.Viewport
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.TuneTarget
import app.thebox.player.api.VideoInfo
import app.thebox.ui.R
import app.thebox.ui.ambient.AmbientHueSource
import app.thebox.ui.ambient.AmbientState
import app.thebox.ui.guide.grid.AndroidTextMeasurer
import app.thebox.ui.guide.grid.GuideTrim
import app.thebox.ui.full.PlayerHost
import app.thebox.ui.full.vm.PlayerGuideBridge
import app.thebox.ui.search.SearchEffect
import app.thebox.ui.input.KeyRouter
import app.thebox.ui.playback.CatchupLauncher
import app.thebox.ui.root.ShellEvent
import app.thebox.ui.root.ShellHost
import coil3.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import javax.inject.Inject

/**
 * Glue between the `:core` state machine and Android (docs/design/m3-guide.md §3.16.1). Owns the coroutine scopes, the
 * rows cache and the window pager; forwards every feed to the machine as [GuideEvent]s; maps `PlaybackState` to
 * `TunedState` (§3.16.2); implements the machine's effects through [GuideEffectsImpl] (§3.16.3); and is the
 * [ShellHost] of the root composable. Nothing here blocks main: session-store reads run on IO (N7).
 */
@HiltViewModel
class GuideViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: PlaybackController,
    catchupLauncher: CatchupLauncher,
    private val library: GuideLibrary,
    rowsCacheFactory: GuideRowsCacheFactory,
    pagerFactory: GuideWindowPagerFactory,
    private val settingsSource: GuideSettingsSource,
    private val session: GuideSessionStore,
    reminderBook: ReminderBook,
    private val alerts: ReminderAlerts,
    private val keyCapabilities: KeyCapabilities,
    stateMachineFactory: GuideStateMachineFactory,
    navigator: GuideNavigator,
    private val labels: TimeLabels,
    private val renderer: GuideRenderer,
    deviceClassSource: DeviceClassSource,
    private val syncFeed: SyncFeed,
    private val dataWindow: GuideDataWindowSource,
    private val debugProbe: GuideDebugProbe,
    override val keyRouter: KeyRouter,
    private val metricsFactory: GuideMetricsFactory,
    rowCellsBuilder: RowCellsBuilder,
    private val guideImages: GuideImages,
    override val imageLoader: ImageLoader,
    guideTrim: GuideTrim,
    // M4 (docs/design/m4-player-ux.md §3.10.1, §3.10.3; review B1)
    private val archive: ArchivePlayback,
    private val playerSettings: PlayerSettingsSource,
    // M5 commit 0 (docs/design/m5-setup-settings.md §0.5, §1.3a, S1/S11)
    private val settingsStore: SettingsStore,
    private val firstRun: FirstRunDecider = FirstRunDecider.NONE,
    delegates: EffectDelegates = EffectDelegates.NONE,
    // M5 WP-C (docs/design/m5-setup-settings.md §3.8.4, §3.9; S10, B1)
    private val parental: ParentalSession = NoPinSession,
    private val parentalControls: ParentalControls = ParentalControls.NONE,
    tuneGuard: TuneGuard = TuneGuard.ALLOW_ALL,
    private val keyBindings: KeyBindingStore = NoKeyBindings,
) : ViewModel(), ShellHost, ShellEffects {

    /**
     * (M5, B1) Every tune the guide's effects, Search and the startup make asks the guard first; a refusal opens the
     * PIN prompt (or the not-protected card) instead of a cycle ([onRefused]).
     */
    private val guarded: PlaybackController = GuardedPlaybackController(controller, tuneGuard) { id, v -> onRefused(id, v) }

    /** (M5, S10) The library with the session's access in every query; flows restart when it changes. */
    private val parentalLibrary: GuideLibrary = ParentalGuideLibrary(library, parental)

    private val io = Dispatchers.IO
    private val device: DeviceClass = deviceClassSource.get()
    private val rowsCache: GuideRowsCache = rowsCacheFactory.create(library, viewModelScope)
    /**
     * (code review 2026-10-14, H-B3) A buffered queue, consumed once: events sent before the root subscribes (the
     * missed-reminders toast at a cold start) wait for it instead of being dropped like a SharedFlow without replay.
     */
    private val shellEvents = Channel<ShellEvent>(Channel.BUFFERED)

    /**
     * (code review 2026-10-14, G-S3/H-S3) The device zone, refreshed by ACTION_TIMEZONE_CHANGED; [clockJumps] counts
     * ACTION_TIME_CHANGED (the zone tables are built around "now", so a large clock change rebuilds them too).
     */
    private val zone = MutableStateFlow<ZoneId>(ZoneId.systemDefault())
    private val clockJumps = MutableStateFlow(0)
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_TIMEZONE_CHANGED -> zone.value = ZoneId.systemDefault()
                Intent.ACTION_TIME_CHANGED -> { zone.value = ZoneId.systemDefault(); clockJumps.value++ }
            }
        }
    }
    private var receiverRegistered = false
    private val hueSource = AmbientHueSource(context, imageLoader)
    private var pager: GuideWindowPager? = null
    private var windowW = 0
    private var windowH = 0

    /**
     * Set by the Search screen (§2.13 item 7): true while its field is empty and no menu is open. The IME consumes
     * BACK while it shows, so BACK reaching the key sink with an empty field means "leave Search" (KeyRouter, B1).
     */
    @Volatile var searchFieldIdle: Boolean = true

    override val ambient = AmbientState()
    override val surfacePlayer: Player get() = controller.surfacePlayer
    override val video: StateFlow<VideoInfo> get() = controller.video
    override val playback: StateFlow<PlaybackState> get() = controller.state
    override val settings: StateFlow<GuideSettings> get() = settingsSource.settings
    override val versionName: String = versionNameOf(context)

    override val lowEnd: StateFlow<Boolean> = settingsSource.settings
        .map { lowEndFor(it, device) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, lowEndFor(settingsSource.settings.value, device))

    private val effects: GuideEffectsImpl = GuideEffectsImpl(
        scope = viewModelScope,
        io = io,
        controller = guarded,
        library = parentalLibrary,
        rows = { rowsCache },
        pager = { pager },
        reminders = reminderBook,
        alerts = alerts,
        catchup = catchupLauncher,
        session = session,
        settings = settingsSource,
        dispatch = { machine.dispatch(it) },
        shell = this,
        onLineup = ::onLineup,
        delegates = delegates,
    )

    override val machine: GuideStateMachine = stateMachineFactory.create(effects, navigator, labels, renderer, System::currentTimeMillis)

    /** §2.13 item 6: `MainActivity.onTrimMemory` → [GuideTrim] → [trim]. */
    private val trimHandle: AutoCloseable = guideTrim.register { _, uiHidden -> trim(uiHidden) }

    init {
        machine.frame.rows = rowsCache
        machine.frame.labels = labels
        machine.frame.images = guideImages
        // One measurer per pager thread, built there at the metrics scale (§2.13 item 3).
        pager = pagerFactory.create(rowsCache, { machine.frame.metrics }, { scale -> AndroidTextMeasurer(context, scale) }, rowCellsBuilder, labels)
        debugProbe.register(machine)
        keyRouter.attach(machine) { searchFieldIdle }
        // (M5, §0.9) The one PIN prompt: PIN creation and Open anyway go through the parental effects.
        (machine as? ParentalHost)?.let { host -> (delegates.parental as? ParentalEffectsImpl)?.let { host.parentalActions = it.actionsFor(host) } }
        registerTimeReceiver()
        forwardFeeds(reminderBook)
        startTicker()
        // (M4 commit 0, docs/design/m4-player-ux.md §1.7 item 10, S9) startup() is MainActivity's call, after attachPlayer.
    }

    /**
     * (M4, S9) The player machine FULL delegates to. MainActivity calls this **before** [startup], so no FULL state
     * runs without it (the machine's `full` setter enters FULL at once when the mode already is FULL).
     */
    fun attachPlayer(machine: PlayerStateMachine) {
        this.machine.full = machine
        sendGuideSource()
    }

    /**
     * (M4, §3.10.1) Attaches the player view model: its machine becomes the guide machine's `full` ([attachPlayer]),
     * it becomes [ShellHost.player] for the root, and (when it is a [PlayerGuideBridge]) it gets the guide machine
     * (quick bar Settings, the displayed mode, the number-entry preview) and the guide's source and lineup.
     */
    fun attachPlayer(player: PlayerHost) {
        attachedPlayer = player
        (player as? PlayerGuideBridge)?.attachGuide(machine)
        attachPlayer(player.machine)
    }

    private var attachedPlayer: PlayerHost? = null

    /** (M5 commit 0, S11) Completed by [attachSettings]; [startup] waits for it (bounded, see there). */
    private val settingsAttached = CompletableDeferred<Unit>()

    /**
     * (M5 commit 0, docs/design/m5-setup-settings.md §2.13, S11) The settings machine SETTINGS delegates to (the settings
     * view model's). MainActivity calls this before [startup]; the two activity view models have no creation order, so
     * the guide machine also copes with a late attach (it waits in SETTINGS while the machine is missing).
     */
    fun attachSettings(settings: SettingsMachine) {
        machine.settings = settings
        settingsAttached.complete(Unit)
    }
    override val player: PlayerHost? get() = attachedPlayer

    /** `PlayerEvent.GuideSourceNow` (§3.3.1): the guide's current source and lineup, through the bridge when attached. */
    private fun sendGuideSource() {
        val source = effects.currentSource
        val lineup = effects.currentLineup
        val bridge = attachedPlayer as? PlayerGuideBridge
        if (bridge != null) bridge.onGuideLineup(source, lineup)
        else machine.full?.dispatch(app.thebox.core.full.PlayerEvent.GuideSourceNow(source, lineup))
    }

    // ---- ShellHost

    override val events: Flow<ShellEvent> = shellEvents.receiveAsFlow()

    override fun setViewport(viewport: Viewport) = controller.setViewport(viewport)

    override fun onWindowSize(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0 || (widthPx == windowW && heightPx == windowH)) return
        windowW = widthPx
        windowH = heightPx
        machine.dispatch(GuideEvent.Metrics(metricsFactory.create(widthPx, heightPx)))
    }

    override fun focusedCellBounds(out: FloatArray): Boolean {
        val f = machine.frame
        if (f.metrics == null || f.focus == null) return false
        return renderer.focusedCellBounds(f, f.windowStartMin, 0f, f.topRow.toFloat(), out)
    }

    // ---- ShellEffects

    override fun setFullScreen(full: Boolean) = Unit // the root follows ui.mode and runs the scrim sequence (§3.13.5)
    override fun toast(kind: GuideToast, arg: String?) { shellEvents.trySend(ShellEvent.Toast(kind, arg)) }
    override fun announce(text: String) { shellEvents.trySend(ShellEvent.Announce(text)) }
    override fun exitApp() { shellEvents.trySend(ShellEvent.Exit) }
    override fun openSearch() = Unit // mode SEARCH is the machine's; the root swaps the screen and hides the viewport
    override fun openSettings() = Unit

    /**
     * The view model's share of `onTrimMemory` ≥ RUNNING_LOW (§4.7, §2.13 item 6), called by [GuideTrim] (which
     * clears the logo cache and the renderer's text caches itself): row blocks outside `[top − 6, top + 12)`, the
     * pager's width/detail caches (and its snapshot when the UI is hidden), the non-current ambient bitmap and the hue
     * cache.
     */
    private fun trim(uiHidden: Boolean) {
        val top = machine.frame.topRow
        rowsCache.trim(top - ROWS_BEHIND, top + ROWS_AHEAD)
        pager?.trimMemory(dropSnapshot = uiHidden)
        ambient.trim()
        hueSource.trim()
    }

    // ---- Search host (§2.13 item 7; WP-B hand-off "Search host", B7)

    /** Every [SearchEffect] of the Search screen; `Close` is the only way out of SEARCH. Main thread. */
    fun onSearchEffect(effect: SearchEffect) {
        when (effect) {
            is SearchEffect.Tune -> guarded.tune(TuneTarget.Channel(effect.channel.identity), effects.currentLineup?.let { LineupZapList(it, library) })
            is SearchEffect.PlayArchive -> effects.playArchive(effect.channel, effect.info)
            is SearchEffect.Toast -> toast(effect.kind, effect.arg)
            is SearchEffect.Close -> {
                searchFieldIdle = true
                machine.dispatch(effect.event)
            }
        }
    }

    // ---- feeds (§3.16.1)

    private fun forwardFeeds(reminderBook: ReminderBook) {
        val scope = viewModelScope
        scope.launch {
            settingsSource.settings.collect { s ->
                machine.dispatch(GuideEvent.Settings(s, device))
                ambient.flat = !s.ambient || lowEndFor(s, device)
            }
        }
        // G-S3/H-S3: the grid's time tables follow the 12/24 h setting and the device zone. A change resets them, drops
        // the pager's snapshot texts and re-sends Settings so the machine re-runs its labels and panel.
        scope.launch {
            var applied: Triple<ZoneId, Boolean, Int>? = null
            combine(settingsSource.settings.map { it.clock24 }.distinctUntilChanged(), zone, clockJumps) { c24, z, j -> Triple(z, c24, j) }
                .collect { t ->
                    if (t == applied) return@collect
                    val first = applied == null
                    applied = t
                    labels.reset(t.first, t.second)
                    if (!first) {
                        pager?.invalidate()
                        machine.dispatch(GuideEvent.Settings(settingsSource.settings.value, device))
                    }
                }
        }
        scope.launch {
            // §2.13 item 10: the machine picks the startup source from this hint on the first Sources, so the hint is
            // read (on IO) before any Sources is forwarded; the view model never loads a lineup itself.
            machine.lastSourceHint = try {
                withContext(io) { session.lastSource() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.w(TAG, "last source read failed: ${e.javaClass.simpleName}")
                null
            }
            // (M5, S10) ParentalGuideLibrary feeds the session's access (the debug switch still forces OPEN).
            settingsSource.settings.map { it.debugUnlocked }.distinctUntilChanged().collectLatest { unlocked ->
                parentalLibrary.sources(access = legacyParentalAccess(unlocked)).collect { machine.dispatch(GuideEvent.Sources(it)) }
            }
        }
        scope.launch { library.rowContent.collect { rowsCache.reloadLoaded() } }
        scope.launch {
            rowsCache.changes.collect {
                machine.dispatch(GuideEvent.RowsChanged(it))
                // A block arrival (or a B5 in-place reload) can change a row's (epg ref, shift) basis: the pager
                // re-queries only the rows whose basis changed, so rows that were PENDING get their programmes
                // without waiting for the next move (§2.13 item 20; WP-B hand-off "VM glue").
                val f = machine.frame
                pager?.ensure(f.topRow, f.windowStartMin)
                prefetchLogos()
            }
        }
        scope.launch { alerts.missed.collect { machine.dispatch(GuideEvent.MissedReminders(it)) } }
        scope.launch { syncFeed.state.collect { machine.dispatch(GuideEvent.Sync(syncChip(it))) } }
        scope.launch { dataWindow.window.collect { machine.dispatch(GuideEvent.DataWindow(it)) } }
        pager?.let { p ->
            scope.launch { p.snapshots.filterNotNull().collect { machine.dispatch(GuideEvent.Window(it)) } }
            scope.launch { p.generation.collect { machine.dispatch(GuideEvent.Generation(it)) } }
        }
        scope.launch { reminderBook.index.collect { machine.dispatch(GuideEvent.Reminders(it)) } }
        scope.launch { alerts.current.collect { machine.dispatch(GuideEvent.Alert(it)) } }
        scope.launch { keyCapabilities.seen.collect { machine.dispatch(GuideEvent.KeysSeen(it)) } }
        forwardParental()
        scope.launch {
            combine(controller.state, archive.status, settingsSource.settings) { st, a, s -> Triple(st, a, s) }.collect { (st, a, s) ->
                val identity = st.target?.identity
                val channel = identity?.let { channelOf(it) }
                // H-B1: the HDR poster is not a still. It is the controller's MINI state (video track off, viewport
                // MINI); mapping it to a still hid the viewport, which ended the poster, which showed MINI again: a loop.
                val still = s.miniStill || device.stillByDefault
                // (M4, B1) An archive session's own hand-offs and probe errors are hidden by the one shared rule.
                // (M5, B1) A tune the guard refused never played: the mini player shows nothing for it.
                val shown = if (st is PlaybackState.Stopped && st.reason == StopReason.LOCKED) PlaybackState.Idle else st
                val visible = app.thebox.core.catchup.archive.ArchiveVisibility.visible(TunedStateMapper.map(shown, channel?.rowId ?: -1L, still), a)
                machine.dispatch(GuideEvent.Tuned(visible, channel))
            }
        }
        // (M4, §0.6, §3.10.3) The archive session (suspect ↺ and the pending catch-up FULL follow FAILED, the mini
        // player's PAUSED / −MM:SS pills) and the player settings the guide machine reads.
        scope.launch { archive.status.collect { machine.dispatch(GuideEvent.Archive(it)) } }
        scope.launch { playerSettings.settings.collect { machine.dispatch(GuideEvent.Player(it)) } }
        scope.launch {
            // (M4, M3 H-L5) No hue work while FULL: the background is black there. The hue of the channel tuned in FULL
            // is computed when the guide shows again.
            machine.ui.map { if (it.mode == ScreenMode.FULL) null else it.tunedChannel }
                .distinctUntilChanged { a, b -> a?.identityHash == b?.identityHash }
                .collect { ch -> if (ch != null || machine.ui.value.mode != ScreenMode.FULL) ambient.targetHue = ch?.let { hueSource.hue(it) } }
        }
    }

    private var cachedIdentity: ChannelIdentity? = null
    private var cachedChannel: GuideChannel? = null

    /** The tuned channel's row (cached per identity, §3.16.2). */
    private suspend fun channelOf(identity: ChannelIdentity): GuideChannel? {
        if (identity == cachedIdentity) return cachedChannel
        val ch = try {
            library.rowIdOf(identity)?.let { id -> library.channels(longArrayOf(id), 0, 1).firstOrNull() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BoxLog.w(TAG, "tuned channel lookup failed: ${e.javaClass.simpleName}")
            null
        }
        cachedIdentity = identity
        cachedChannel = ch
        return ch
    }

    private fun syncChip(s: SyncFeedState): SyncChip? = when {
        s.busy -> SyncChip(s.progress ?: context.getString(R.string.guide_sync_updating), failed = false)
        s.unacknowledgedFailures > 0 -> SyncChip(context.getString(R.string.guide_sync_failed), failed = true)
        else -> null
    }

    /** §3.7: logos for rows `[top − 6, top + 12)` are requested when a block arrives, before they scroll in. */
    private fun prefetchLogos() {
        val f = machine.frame
        if (!f.showLogos) return
        val rows = f.rows
        val from = maxOf(0, f.topRow - ROWS_BEHIND)
        val to = minOf(rows.size, f.topRow + ROWS_AHEAD)
        for (i in from until to) rows.channel(i)?.logoUrl?.let { url -> if (!guideImages.failed(url)) guideImages.get(url) }
    }

    /**
     * Ticks (§2.13 item 4): one at every minute boundary and one at the machine's [GuideStateMachine.nextDeadlineMs]
     * (150 ms description debounce, number entry, exit hint, group card, reminder card), whichever is first. A deadline
     * that moves earlier wakes the loop at once.
     */
    private fun startTicker() {
        viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                machine.dispatch(GuideEvent.Tick(now))
                parental.onTick(now) // (M5, §3.9.5) the 1-hour stay-unlocked timer
                val deadline = machine.nextDeadlineMs.value
                val target = nextTickAt(now, deadline)
                // U-B1: wake early only for a *new* deadline before the target. A deadline the machine left in the past
                // satisfies `it < target` at once, `first` returns without suspending and the loop spun on main (ANR).
                withTimeoutOrNull((target - System.currentTimeMillis()).coerceAtLeast(1)) {
                    machine.nextDeadlineMs.first { it != deadline && it < target }
                }
            }
        }
    }

    private var started = false

    /**
     * Startup (§3.16.1): "guide with last channel" tunes the last watched channel; the machine picks the source. (M4
     * commit 0, S9) Called by MainActivity after [attachPlayer]; runs once per view model.
     *
     * (M4, docs/design/m4-player-ux.md §3.10.3) `PlayerSettings.onLaunch`: GUIDE_WITH_LAST is the M3 behaviour;
     * FULL_LAST tunes the same channel and dispatches `GuideEvent.StartFull` (the machine's pendingFull path: FULL on the
     * first active Tuned); GUIDE_NOTHING tunes nothing. StartFull is sent only when there is a channel to tune, so a
     * later tune from the guide never jumps to FULL by surprise.
     */
    fun startup() {
        if (started) return
        started = true
        viewModelScope.launch {
            try {
                // (M5 commit 0, §0.5, S1/S11) Persisted settings decide the first frame ("On launch", "Open guide on", …):
                // wait for the store and the settings machine, bounded by SETTINGS_LOAD_TIMEOUT_MS (then defaults, logged).
                val waited = withTimeoutOrNull(SettingsTimings.SETTINGS_LOAD_TIMEOUT_MS) {
                    settingsStore.awaitReady()
                    settingsAttached.await()
                }
                if (waited == null) BoxLog.w(TAG, "startup: settings not ready after ${SettingsTimings.SETTINGS_LOAD_TIMEOUT_MS} ms; defaults")
                // §3.4.1: the first-run decision replaces the startup tune.
                firstRunRoute()?.let { route ->
                    machine.dispatch(GuideEvent.OpenSetup(route))
                    return@launch
                }
                val onLaunch = playerSettings.settings.value.onLaunch
                if (onLaunch != OnLaunch.GUIDE_NOTHING && controller.state.value is PlaybackState.Idle) {
                    library.lastWatched()?.let {
                        if (onLaunch == OnLaunch.FULL_LAST) machine.dispatch(GuideEvent.StartFull)
                        guarded.tune(TuneTarget.Channel(it.identity)) // N13: never a locked channel (the guard too)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.w(TAG, "startup tune failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * (M5 commit 0, docs/design/m5-setup-settings.md §3.4.1) The first-run decision, made once after the waits above:
     * no row in `playlist` and `SetupState.completed == false` → [SettingsRoute.FirstRun]. Delegated to
     * [FirstRunDecider] (WP-A implements it; commit 0 binds the stub that always answers null, the guide).
     */
    private suspend fun firstRunRoute(): SettingsRoute? = firstRun.route()

    /**
     * A new lineup for the current source: it becomes the zap list while something plays (§3.16.3, N13). (M4, §3.3.1)
     * The player hears of it too (`GuideSourceNow`): the channel list opens on it and its ←/→ browse follows it.
     */
    private fun onLineup(lineup: GuideLineup) {
        if (controller.state.value !is PlaybackState.Idle) controller.setZapList(LineupZapList(lineup, library))
        sendGuideSource()
    }

    // ---- parental (M5, WP-C; docs/design/m5-setup-settings.md §3.9)

    /**
     * The session's state to the machine (the PIN layer and the Groups panel doors); on every relock (access leaves
     * OPEN) the zap list is cleared and rebuilt from the next, filtered lineup (§3.9.2). What plays goes to the session
     * (never relock mid-programme, ASK_EVERY_TIME); leaving Settings too. A resume the controller refused
     * (`Stopped(LOCKED)`, B1) opens the PIN prompt for that channel. Key hints follow the user's mapping (§3.8.4).
     */
    private fun forwardParental() {
        val scope = viewModelScope
        scope.launch {
            var before: ParentalAccess? = null
            parental.state.collect { st ->
                machine.dispatch(GuideEvent.Parental(st))
                val was = before
                before = st.access
                if (was == ParentalAccess.OPEN && st.access != ParentalAccess.OPEN && controller.state.value !is PlaybackState.Idle) {
                    controller.setZapList(EmptyZapList) // rebuilt by the re-queried, filtered lineup (onLineup)
                }
            }
        }
        scope.launch {
            var lastLockedTune = -1L
            controller.state.collect { st ->
                val playing = when (st) {
                    is PlaybackState.Playing, is PlaybackState.Buffering, is PlaybackState.Connecting, is PlaybackState.Stalled -> st.target?.identity
                    else -> null
                }
                parentalControls.watching(playing)
                if (st is PlaybackState.Stopped && st.reason == StopReason.LOCKED) {
                    val id = st.target?.identity
                    val tuneId = st.target?.tuneId ?: 0L
                    if (id != null && tuneId != lastLockedTune) {
                        lastLockedTune = tuneId
                        onRefused(id, TuneVerdict.NEEDS_PIN)
                    }
                }
            }
        }
        scope.launch {
            var was = machine.ui.value.mode
            machine.ui.map { it.mode }.distinctUntilChanged().collect { m ->
                if (was == ScreenMode.SETTINGS && m != ScreenMode.SETTINGS) parentalControls.leftSettings()
                was = m
            }
        }
        scope.launch {
            combine(keyCapabilities.seen, keyBindings.overrides) { seen, o -> seen to o }.collect { (seen, o) ->
                machine.dispatch(GuideEvent.KeyHints(keyHints(seen, o, KeyContext.GUIDE)))
            }
        }
    }

    /**
     * A tune the guard refused (B1): the PIN prompt with `Tune(channel, GUIDE)` (the not-protected card in
     * FILTERED_NO_PIN). On success the guide machine tunes it again; the guard then allows it.
     */
    private fun onRefused(identity: ChannelIdentity, @Suppress("UNUSED_PARAMETER") verdict: TuneVerdict) {
        viewModelScope.launch {
            val ch = try {
                library.rowIdOf(identity)?.let { id -> library.channels(longArrayOf(id), 0, 1).firstOrNull() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.w(TAG, "refused channel lookup failed: ${e.javaClass.simpleName}")
                null
            } ?: return@launch
            (machine as? ParentalHost)?.request(PinPurpose.Tune(ch, PinOwner.GUIDE))
        }
    }

    private fun registerTimeReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
            }
            ContextCompat.registerReceiver(context, timeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        } catch (e: RuntimeException) {
            BoxLog.w(TAG, "time receiver not registered: ${e.javaClass.simpleName}")
        }
    }

    override fun onCleared() {
        trimHandle.close()
        effects.close()
        pager?.close()
        pager = null
        if (receiverRegistered) {
            try { context.unregisterReceiver(timeReceiver) } catch (e: IllegalArgumentException) { /* already gone */ }
            receiverRegistered = false
        }
        // H-S5: the old view model's onCleared can run after the new activity's view model attached its machine.
        if (debugProbe.current === machine) debugProbe.register(null)
        keyRouter.detach(machine)
        shellEvents.close()
        super.onCleared()
    }

    companion object {
        private const val TAG = "GuideViewModel"
        const val MINUTE_MS = 60_000L
        /** Floor between two ticks, so a deadline that is already due cannot spin the loop. */
        const val MIN_TICK_GAP_MS = 16L
        const val ROWS_BEHIND = 6
        const val ROWS_AHEAD = 12

        /** The next tick: the machine's deadline or the next minute boundary, whichever is first, at least [MIN_TICK_GAP_MS] away. */
        fun nextTickAt(nowMs: Long, deadlineMs: Long): Long =
            maxOf(nowMs + MIN_TICK_GAP_MS, minOf(deadlineMs, nowMs - Math.floorMod(nowMs, MINUTE_MS) + MINUTE_MS))

        /** The actions the footer hints name (§3.8.4). */
        private val HINTED = listOf(BoxAction.START_OVER, BoxAction.REMIND, BoxAction.ZAP_UP, BoxAction.PAGE_UP)

        /** For each hinted action, the seen keys bound to it (short presses) in [context]: EffectiveKeyMap ∩ seen. */
        fun keyHints(seen: Set<Int>, overrides: app.thebox.core.input.KeyBindingOverrides, context: KeyContext): Map<BoxAction, List<Int>> {
            val map = RemappedKeyMap(DefaultKeyMap(), null, null).also { it.apply(overrides) }
            return HINTED.associateWith { a -> map.keysFor(context, a).filter { it.second == Press.SHORT && it.first in seen }.map { it.first } }
        }

        fun lowEndFor(s: GuideSettings, device: DeviceClass): Boolean = when (s.performance) {
            PerformanceMode.LOW -> true
            PerformanceMode.HIGH -> false
            PerformanceMode.AUTO -> device.lowEnd
        }

        private fun versionNameOf(context: Context): String = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
