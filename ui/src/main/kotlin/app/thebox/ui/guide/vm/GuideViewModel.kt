package app.thebox.ui.guide.vm

import android.content.Context
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
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.GuideStateMachine
import app.thebox.core.guide.GuideStateMachineFactory
import app.thebox.core.guide.GuideToast
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.GuideWindowPager
import app.thebox.core.guide.GuideWindowPagerFactory
import app.thebox.core.guide.OpenGuideOn
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.PerformanceMode
import app.thebox.core.guide.ReminderAlerts
import app.thebox.core.guide.ReminderBook
import app.thebox.core.guide.RowCellsBuilder
import app.thebox.core.guide.SourceSection
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
import app.thebox.ui.input.KeyRouter
import app.thebox.ui.playback.CatchupLauncher
import app.thebox.ui.root.ShellEvent
import app.thebox.ui.root.ShellHost
import coil3.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
) : ViewModel(), ShellHost, ShellEffects {

    private val io = Dispatchers.IO
    private val device: DeviceClass = deviceClassSource.get()
    private val rowsCache: GuideRowsCache = rowsCacheFactory.create(library, viewModelScope)
    private val shellEvents = MutableSharedFlow<ShellEvent>(extraBufferCapacity = 8)
    private val hueSource = AmbientHueSource(context, imageLoader)
    private var pager: GuideWindowPager? = null
    private var windowW = 0
    private var windowH = 0

    /** Set by the Search screen (WP-B): true while its field is empty and the IME is hidden (KeyRouter, B1). */
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

    init {
        machine.frame.rows = rowsCache
        machine.frame.labels = labels
        machine.frame.images = guideImages
        pager = pagerFactory.create(
            rowsCache,
            { machine.frame.metrics },
            { AndroidTextMeasurer(context, machine.frame.metrics?.scale ?: 1f) },
            rowCellsBuilder,
            labels,
        )
        debugProbe.register(machine)
        keyRouter.attach(machine) { searchFieldIdle }
        forwardFeeds(reminderBook)
        startTicker()
        startup()
    }

    // ---- ShellHost

    override val events: SharedFlow<ShellEvent> = shellEvents.asSharedFlow()

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
    override fun toast(kind: GuideToast, arg: String?) { shellEvents.tryEmit(ShellEvent.Toast(kind, arg)) }
    override fun announce(text: String) { shellEvents.tryEmit(ShellEvent.Announce(text)) }
    override fun exitApp() { shellEvents.tryEmit(ShellEvent.Exit) }
    override fun openSearch() = Unit // mode SEARCH is the machine's; the root swaps the screen and hides the viewport
    override fun openSettings() = Unit

    /** onTrimMemory fan-out for the shell's own caches (§4.7); grid/pager caches are trimmed by their owners. */
    @Suppress("DEPRECATION") // TRIM_MEMORY_RUNNING_LOW is still delivered on the API levels we ship to (25–35)
    fun onTrimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            ambient.trim()
            hueSource.trim()
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
        scope.launch {
            settingsSource.settings.map { it.debugUnlocked }.distinctUntilChanged().collect { unlocked ->
                library.sources(unlocked).collect { machine.dispatch(GuideEvent.Sources(it)) }
            }
        }
        scope.launch { library.rowContent.collect { rowsCache.reloadLoaded() } }
        scope.launch { rowsCache.changes.collect { machine.dispatch(GuideEvent.RowsChanged(it)) } }
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
                val still = v.posterMode || s.miniStill || device.stillByDefault
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

    /** Minute-aligned ticks, plus 250 ms ticks while a countdown or the exit hint shows (§3.16.1). */
    private fun startTicker() {
        viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                machine.dispatch(GuideEvent.Tick(now))
                val fast = needsFastTicks(machine.ui.value)
                val wait = if (fast) FAST_TICK_MS else MINUTE_MS - now % MINUTE_MS
                // Wake early when a countdown appears so it starts ticking at once.
                withTimeoutOrNull(wait) { machine.ui.first { needsFastTicks(it) != fast } }
            }
        }
    }

    /** Startup (§3.8.4, §3.16.1): the initial source from `openGuideOn` and the "guide with last channel" tune. */
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
        viewModelScope.launch {
            val last = withContext(io) { session.lastSource() }
            val entries = library.sources(settingsSource.settings.value.debugUnlocked).first()
            if (effects.currentSource != null) return@launch // the machine already chose a source
            initialSource(settingsSource.settings.value.openGuideOn, last, entries)?.let { effects.loadLineup(it) }
        }
    }

    /** A new lineup for the current source: it becomes the zap list while something plays (§3.16.3, N13). */
    private fun onLineup(lineup: GuideLineup) {
        if (controller.state.value !is PlaybackState.Idle) controller.setZapList(LineupZapList(lineup, library))
    }

    override fun onCleared() {
        effects.close()
        pager?.close()
        pager = null
        debugProbe.register(null)
        keyRouter.attach(null) { true }
        super.onCleared()
    }

    companion object {
        private const val TAG = "GuideViewModel"
        const val FAST_TICK_MS = 250L
        const val MINUTE_MS = 60_000L

        fun needsFastTicks(ui: GuideUiState): Boolean =
            ui.reminder != null || ui.overlay is Overlay.Number || ui.overlay is Overlay.Exit || ui.groupCard != null

        fun lowEndFor(s: GuideSettings, device: DeviceClass): Boolean = when (s.performance) {
            PerformanceMode.LOW -> true
            PerformanceMode.HIGH -> false
            PerformanceMode.AUTO -> device.lowEnd
        }

        /** §3.8.4: LAST → the stored source if it still exists, FAVOURITES → the default list if present, else All. */
        fun initialSource(open: OpenGuideOn, last: GuideSource?, entries: List<GuideSourceEntry>): GuideSource? {
            val firstAll = entries.firstOrNull { it.section == SourceSection.ALL }?.source
            return when (open) {
                OpenGuideOn.LAST -> last?.takeIf { l -> entries.any { it.source == l } } ?: firstAll
                OpenGuideOn.FAVOURITES -> entries.firstOrNull { it.section == SourceSection.FAVOURITES && it.count > 0 }?.source ?: firstAll
                OpenGuideOn.ALL -> firstAll
            } ?: entries.firstOrNull()?.source
        }

        private fun versionNameOf(context: Context): String = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
