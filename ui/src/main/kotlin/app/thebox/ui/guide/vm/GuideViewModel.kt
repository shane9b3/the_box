package app.thebox.ui.guide.vm

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
import app.thebox.core.guide.GuideStateMachine
import app.thebox.core.guide.GuideStateMachineFactory
import app.thebox.core.guide.GuideToast
import app.thebox.core.guide.GuideWindowPager
import app.thebox.core.guide.GuideWindowPagerFactory
import app.thebox.core.guide.PerformanceMode
import app.thebox.core.guide.ReminderAlerts
import app.thebox.core.guide.ReminderBook
import app.thebox.core.guide.RowCellsBuilder
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
) : ViewModel(), ShellHost, ShellEffects {

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
        controller = controller,
        library = library,
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
        registerTimeReceiver()
        forwardFeeds(reminderBook)
        startTicker()
        startup()
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
            is SearchEffect.Tune -> controller.tune(TuneTarget.Channel(effect.channel.identity), effects.currentLineup?.let { LineupZapList(it, library) })
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
            settingsSource.settings.map { it.debugUnlocked }.distinctUntilChanged().collect { unlocked ->
                library.sources(unlocked).collect { machine.dispatch(GuideEvent.Sources(it)) }
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
        scope.launch {
            combine(controller.state, controller.video, settingsSource.settings) { st, v, s -> Triple(st, v, s) }.collect { (st, v, s) ->
                val identity = st.target?.identity
                val channel = identity?.let { channelOf(it) }
                // H-B1: the HDR poster is not a still. It is the controller's MINI state (video track off, viewport
                // MINI); mapping it to a still hid the viewport, which ended the poster, which showed MINI again: a loop.
                val still = s.miniStill || device.stillByDefault
                machine.dispatch(GuideEvent.Tuned(TunedStateMapper.map(st, channel?.rowId ?: -1L, still), channel))
            }
        }
        scope.launch {
            machine.ui.map { it.tunedChannel }.distinctUntilChanged { a, b -> a?.identityHash == b?.identityHash }.collect { ch ->
                ambient.targetHue = ch?.let { hueSource.hue(it) }
            }
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
                val target = nextTickAt(now, machine.nextDeadlineMs.value)
                withTimeoutOrNull(target - System.currentTimeMillis()) { machine.nextDeadlineMs.first { it < target } }
            }
        }
    }

    /** Startup (§3.16.1): "guide with last channel" tunes the last watched channel; the machine picks the source. */
    private fun startup() {
        viewModelScope.launch {
            try {
                if (controller.state.value is PlaybackState.Idle) {
                    library.lastWatched()?.let { controller.tune(TuneTarget.Channel(it.identity)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.w(TAG, "startup tune failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** A new lineup for the current source: it becomes the zap list while something plays (§3.16.3, N13). */
    private fun onLineup(lineup: GuideLineup) {
        if (controller.state.value !is PlaybackState.Idle) controller.setZapList(LineupZapList(lineup, library))
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
