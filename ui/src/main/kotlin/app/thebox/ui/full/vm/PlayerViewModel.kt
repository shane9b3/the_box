package app.thebox.ui.full.vm

import app.thebox.core.parental.legacyParentalAccess
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.text.Cue
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchiveVisibility
import app.thebox.core.catchup.archive.ArchivePlayback
import app.thebox.core.common.BoxLog
import app.thebox.core.full.PlayerEvent
import app.thebox.core.full.PlayerSettingsSource
import app.thebox.core.full.PlayerStateMachine
import app.thebox.core.full.PlayerStateMachineFactory
import app.thebox.core.full.PlayerToast
import app.thebox.core.full.ProgrammeFeed
import app.thebox.core.guide.DeviceClass
import app.thebox.core.guide.DeviceClassSource
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideLibrary
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideRowsCache
import app.thebox.core.guide.GuideRowsCacheFactory
import app.thebox.core.guide.GuideSettingsSource
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideStateMachine
import app.thebox.core.guide.GuideToast
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.ReminderBook
import app.thebox.core.guide.ScreenMode
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.PlaybackNotice
import app.thebox.ui.full.PlayerDebugProbe
import app.thebox.ui.full.PlayerHost
import app.thebox.ui.full.PlayerText
import app.thebox.ui.guide.vm.GuideViewModel
import app.thebox.ui.guide.vm.TunedStateMapper
import app.thebox.ui.root.ShellEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * What the guide view model hands the player view model (docs/design/m4-player-ux.md §3.10.1, §3.10.3): the guide
 * machine (Settings from the quick bar, the mode for cues and notices, the number-entry preview) and the guide's
 * current source and lineup (`PlayerEvent.GuideSourceNow`; it is also the zap list the controller uses).
 */
interface PlayerGuideBridge {
    fun attachGuide(guide: GuideStateMachine)
    fun onGuideLineup(source: GuideSource?, lineup: GuideLineup?)
}

/**
 * The player's glue (docs/design/m4-player-ux.md §3.10): creates the channel list's rows cache and the machine (with
 * [PlayerEffectsImpl]), registers it with the probe and forwards every feed as a [PlayerEvent]:
 *
 * | Source | Event |
 * |---|---|
 * | `combine(controller.state, archive.status)` + row lookup (hits cached, N10) | `Tuned(ArchiveVisibility.visible(…))` (B1) |
 * | `controller.pendingZap` | `PendingZap` |
 * | `controller.tracks` / `video` | `Tracks` (`PlayerMappers.trackMenu`) / `Video` |
 * | `controller.stats` (only while stats are on) | `Stats` |
 * | `controller.previous` | `Previous` |
 * | `archive.status` | `Archive` |
 * | `library.sources(unlocked)` | `Sources` |
 * | `listRows.changes` / now-cache bumps | `ListRows` / `ListNow` |
 * | player + guide settings | `Settings(settings, lowEnd)` |
 * | `controller.notices` | `ShellEvent.Message` toasts (§3.10.2) |
 *
 * The ticker ([runTicker]) runs under `repeatOnLifecycle(STARTED)` (M3 H-L11) with monotonic `delay`s (M3 H-L2): a
 * `Tick` at the machine's deadline and at every minute boundary. Injects only `:core`/`:player`/`:ui` types (M3 B7).
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    internal val controller: PlaybackController,
    internal val archive: ArchivePlayback,
    internal val feed: ProgrammeFeed,
    internal val library: GuideLibrary,
    rowsFactory: GuideRowsCacheFactory,
    private val playerSettings: PlayerSettingsSource,
    internal val guideSettings: GuideSettingsSource,
    deviceClass: DeviceClassSource,
    machineFactory: PlayerStateMachineFactory,
    private val probe: PlayerDebugProbe,
    internal val reminders: ReminderBook,
    /** (M5 commit 0, docs/design/m5-setup-settings.md §1.3a) What PlayerEffectsImpl's M5 effects delegate to. */
    delegates: app.thebox.ui.root.EffectDelegates = app.thebox.ui.root.EffectDelegates.NONE,
) : ViewModel(), PlayerHost, PlayerGuideBridge, app.thebox.ui.full.PlayerExtras {

    /** Wall clock (tests substitute a virtual one). */
    internal var clock: () -> Long = System::currentTimeMillis

    private val device: DeviceClass = deviceClass.get()
    private val shellEvents = Channel<ShellEvent>(Channel.BUFFERED)
    private val rowsCache: GuideRowsCache = rowsFactory.create(library, viewModelScope)
    internal val nowCache = ChannelNowCache()
    internal val effects = PlayerEffectsImpl(this, delegates)

    override val listRows: GuideRowsCache get() = rowsCache
    override val machine: PlayerStateMachine = machineFactory.create(effects, rowsCache) { clock() }
    override val events: Flow<ShellEvent> = shellEvents.receiveAsFlow()

    internal var guide: GuideStateMachine? = null
        private set
    private val guideFull = MutableStateFlow(false)

    /** The zap list's lineup: the guide's (its lineup is the controller's zap list) or the last one a list tune set. */
    internal var zapLineup: GuideLineup? = null
    internal var guideSource: GuideSource? = null
        private set
    internal var guideLineup: GuideLineup? = null
        private set
    /** The source the channel list's rows hold (null until the first lineup). */
    internal var listSource: GuideSource? = null
    internal var listJob: Job? = null

    internal val statsOn = MutableStateFlow(false)
    /** Last time an archive session was active or an archive effect ran (suppresses the controller's LiveResumed). */
    internal var archiveActiveAtMs: Long = Long.MIN_VALUE / 2

    /** One-entry cache of the number-entry preview's programme on now (§3.10.4); snapshot state, read by the overlay. */
    private val previewNow = mutableStateOf<Pair<Long, ProgrammeInfo?>?>(null)
    private var nowRange: IntRange? = null
    private var nowJob: Job? = null

    override val cues: StateFlow<List<Cue>> = combine(controller.cues, guideFull) { c, full -> if (full) c else emptyList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    override val archiveFailure: StateFlow<app.thebox.core.catchup.archive.ArchiveFailure?> =
        archive.status.map { if (it.phase == ArchivePhase.FAILED) it.failure else null }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val detail = MutableStateFlow<ProgrammeInfo?>(null)
    override val focusedDetail: StateFlow<ProgrammeInfo?> get() = detail

    /** Row lookups (N10 / M3 H-L12: only hits are cached); cleared when row content changes. */
    private val channelCache = object : LinkedHashMap<ChannelIdentity, GuideChannel>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ChannelIdentity, GuideChannel>?): Boolean = size > CHANNEL_CACHE
    }

    init {
        probe.register(machine)
        forwardFeeds()
        loadFocusedDetails()
    }

    // ---- PlayerHost

    override fun nowOf(row: GuideChannel): ProgrammeInfo? {
        val nowMin = (clock() / MINUTE_MS).toInt()
        nowCache.get(row.identityHash, nowMin)?.let { return it }
        val p = previewNow.value ?: return null
        return if (p.first == row.identityHash) p.second?.takeIf { nowMin < it.endMin } else null
    }

    // ---- PlayerGuideBridge

    override fun attachGuide(guide: GuideStateMachine) {
        if (this.guide === guide) return
        this.guide = guide
        viewModelScope.launch { guide.ui.map { it.mode == ScreenMode.FULL }.distinctUntilChanged().collect { guideFull.value = it } }
        viewModelScope.launch {
            guide.ui.map { s -> if (s.mode == ScreenMode.FULL) (s.overlay as? Overlay.Number)?.preview else null }
                .distinctUntilChanged { a, b -> a?.identityHash == b?.identityHash }
                .collectLatest { ch -> if (ch != null) loadPreviewNow(ch) }
        }
    }

    override fun onGuideLineup(source: GuideSource?, lineup: GuideLineup?) {
        guideSource = source
        guideLineup = lineup
        if (lineup != null) {
            zapLineup = lineup
            if (listSource == null || listSource == source) {
                listJob?.cancel()
                listSource = source
                rowsCache.setLineup(lineup)
            }
        }
        machine.dispatch(PlayerEvent.GuideSourceNow(source, lineup))
    }

    // ---- ticker (§3.10.1)

    /** Ticks while [lifecycle] is at least STARTED (M3 H-L11); MainActivity calls it once per activity. */
    fun runTicker(lifecycle: Lifecycle): Job = viewModelScope.launch {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { tickLoop() }
    }

    internal suspend fun tickLoop() {
        var minute = clock() / MINUTE_MS
        while (true) {
            val now = clock()
            machine.dispatch(PlayerEvent.Tick(now))
            val m = now / MINUTE_MS
            if (m != minute) {
                minute = m
                onMinute()
            }
            val deadline = machine.nextDeadlineMs.value
            val target = GuideViewModel.nextTickAt(now, deadline)
            // Wake early only for a *new* deadline before the target: a deadline the machine left in the past would
            // otherwise satisfy `it < target` at once and spin the loop without ever suspending.
            withTimeoutOrNull((target - clock()).coerceAtLeast(1)) { machine.nextDeadlineMs.first { it != deadline && it < target } }
        }
    }

    private fun onMinute() {
        nowRange?.let { scheduleNow(it, debounce = false) }
    }

    // ---- feeds (§3.10.1)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun forwardFeeds() {
        val scope = viewModelScope
        scope.launch {
            combine(controller.state, archive.status) { st, status -> st to status }.collect { (st, status) ->
                val ch = st.target?.identity?.let { channelOf(it) }
                val mapped = TunedStateMapper.map(st, ch?.rowId ?: -1L, still = false)
                machine.dispatch(PlayerEvent.Tuned(ArchiveVisibility.visible(mapped, status), ch))
            }
        }
        scope.launch {
            archive.status.collect { s ->
                if (s.phase != ArchivePhase.IDLE) archiveActiveAtMs = clock()
                machine.dispatch(PlayerEvent.Archive(s))
            }
        }
        scope.launch { controller.pendingZap.collect { id -> machine.dispatch(PlayerEvent.PendingZap(id?.let { channelOf(it) })) } }
        scope.launch { controller.tracks.collect { machine.dispatch(PlayerEvent.Tracks(PlayerMappers.trackMenu(it))) } }
        scope.launch { controller.video.collect { machine.dispatch(PlayerEvent.Video(PlayerMappers.videoFacts(it))) } }
        scope.launch {
            statsOn.flatMapLatest { on -> if (on) controller.stats else emptyFlow() }.collect {
                machine.dispatch(PlayerEvent.Stats(PlayerMappers.statsFacts(it, controller.video.value)))
            }
        }
        scope.launch { controller.previous.collect { id -> machine.dispatch(PlayerEvent.Previous(id?.let { channelOf(it) })) } }
        scope.launch {
            // (M5 commit 0, S10) explicit access; WP-C feeds it from ParentalSession.state.
            guideSettings.settings.map { it.debugUnlocked }.distinctUntilChanged().collectLatest { unlocked ->
                library.sources(access = legacyParentalAccess(unlocked)).collect { machine.dispatch(PlayerEvent.Sources(it)) }
            }
        }
        scope.launch { library.rowContent.collect { channelCache.clear() } }
        scope.launch {
            rowsCache.changes.collect { v ->
                machine.dispatch(PlayerEvent.ListRows(v))
                // Rows that were PENDING when "now" was asked get their titles once their block lands (§3.3.2).
                nowRange?.let { scheduleNow(it, debounce = true) }
            }
        }
        scope.launch {
            combine(playerSettings.settings, guideSettings.settings) { p, g -> p to GuideViewModel.lowEndFor(g, device) }.collect { (p, low) ->
                machine.dispatch(PlayerEvent.Settings(p, low))
            }
        }
        scope.launch { controller.notices.collect(::onNotice) }
        scope.launch {
            archive.notices.collect { n ->
                when (n) {
                    is app.thebox.core.catchup.archive.ArchiveNotice.LiveNotReady -> onArchiveLiveNotReady()
                }
            }
        }
    }

    /**
     * (lead ruling 2026-10-15) The info bar's synopsis: `ProgrammeFeed.at` for `info.programmes[focused]` when the
     * focused programme changes, debounced [DETAIL_DEBOUNCE_MS] and cancelled by the next change; the result is
     * [focusedDetail], matched to the drawn programme by `rawStartMin` (the machine does not ask for it).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun loadFocusedDetails() {
        viewModelScope.launch {
            machine.ui.map { u -> u.info?.let { i -> i.channel?.let { ch -> i.programmes.getOrNull(i.focused)?.let { ch to it } } } }
                .distinctUntilChanged { a, b -> a?.first?.identityHash == b?.first?.identityHash && a?.second?.rawStartMin == b?.second?.rawStartMin }
                .collectLatest { pair ->
                    if (pair == null) return@collectLatest
                    if (detail.value?.rawStartMin == pair.second.rawStartMin && detail.value?.title == pair.second.title) return@collectLatest
                    delay(DETAIL_DEBOUNCE_MS)
                    val full = try {
                        feed.at(pair.first, pair.second.startMin)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        BoxLog.w(TAG, "programme detail failed: ${e.javaClass.simpleName}")
                        null
                    }
                    if (full != null && full.rawStartMin == pair.second.rawStartMin) detail.value = full
                }
        }
    }

    /** §3.10.2: notices become toasts; Reconnecting only in FULL; LiveResumed not when the archive session asked for it. */
    internal fun onNotice(n: PlaybackNotice) {
        when (n) {
            is PlaybackNotice.Reconnecting -> if (!guideFull.value) return
            PlaybackNotice.LiveResumed -> {
                val active = archive.status.value.phase != ArchivePhase.IDLE
                if (active || clock() - archiveActiveAtMs < LIVE_RESUMED_GRACE_MS) return
            }
            else -> Unit
        }
        val text = PlayerText.notice(context.resources, n) ?: return
        shellEvents.trySend(ShellEvent.Message(text))
    }

    /** WP-B's `ArchiveNotice.LiveNotReady` (archive lag used up near the edge, §3.8.7) → the ARCHIVE_NOT_READY toast. */
    internal fun onArchiveLiveNotReady() = toast(PlayerToast.ARCHIVE_NOT_READY, null)

    // ---- effect helpers (PlayerEffectsImpl)

    internal fun dispatch(event: PlayerEvent) = machine.dispatch(event)

    internal fun toast(kind: PlayerToast, arg: String?) {
        shellEvents.trySend(ShellEvent.Message(PlayerText.toast(context.resources, kind, arg)))
    }

    internal fun guideToast(kind: GuideToast, arg: String?) {
        shellEvents.trySend(ShellEvent.Toast(kind, arg))
    }

    internal fun announce(text: String) {
        shellEvents.trySend(ShellEvent.Announce(text))
    }

    internal fun nowMs(): Long = clock()

    internal fun launchSafely(what: String, block: suspend () -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BoxLog.w(TAG, "$what failed: ${e.javaClass.simpleName}")
        }
    }

    // ---- row lookups (N10 / M3 H-L12: only hits are cached)

    internal suspend fun channelOf(identity: ChannelIdentity): GuideChannel? {
        channelCache[identity]?.let { return it }
        val ch = try {
            library.rowIdOf(identity)?.let { id -> library.channels(longArrayOf(id), 0, 1).firstOrNull() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BoxLog.w(TAG, "channel lookup failed: ${e.javaClass.simpleName}")
            null
        }
        if (ch != null) channelCache[identity] = ch
        return ch
    }

    // ---- channel list (§3.3)

    internal fun loadListLineup(source: GuideSource) {
        listJob?.cancel()
        val g = guideLineup
        if (source == guideSource && g != null) {
            listSource = source
            rowsCache.setLineup(g)
            machine.dispatch(PlayerEvent.ListLineup(g))
            return
        }
        val access = legacyParentalAccess(guideSettings.settings.value.debugUnlocked) // (M5 commit 0, S10)
        listJob = launchSafely("listLineup") {
            library.lineup(source, access = access).collect { lineup ->
                listSource = source
                rowsCache.setLineup(lineup)
                machine.dispatch(PlayerEvent.ListLineup(lineup))
            }
        }
    }

    internal fun ensureListRows(from: Int, to: Int) {
        val lo = from.coerceAtLeast(0)
        val hi = to.coerceAtMost(rowsCache.size)
        if (hi > lo) rowsCache.ensure(lo, hi)
    }

    /** Debounced by `LIST_NOW_DEBOUNCE_MS` during a hold (§3.1.4); the latest range wins. */
    internal fun ensureListNow(from: Int, to: Int) {
        val r = from.coerceAtLeast(0) until to.coerceAtMost(rowsCache.size)
        nowRange = r
        scheduleNow(r, debounce = true)
    }

    private fun scheduleNow(range: IntRange, debounce: Boolean) {
        nowJob?.cancel()
        nowJob = launchSafely("listNow") {
            if (debounce) delay(app.thebox.ui.full.PlayerUi.LIST_NOW_DEBOUNCE_MS)
            loadNow(range)
        }
    }

    private suspend fun loadNow(range: IntRange) {
        val nowMin = (clock() / MINUTE_MS).toInt()
        val wanted = ArrayList<GuideChannel>(range.count().coerceAtMost(32))
        for (i in range) {
            if (i >= rowsCache.size) break
            val ch = rowsCache.channel(i) ?: continue // PENDING: asked again when its block lands
            if (!nowCache.isFresh(ch.identityHash, nowMin)) wanted += ch
        }
        if (wanted.isEmpty()) return
        val answers = feed.nowFor(wanted, nowMin)
        for (k in wanted.indices) nowCache.put(wanted[k].identityHash, answers.getOrNull(k), nowMin)
        machine.dispatch(PlayerEvent.ListNow(nowCache.version))
    }

    private suspend fun loadPreviewNow(ch: GuideChannel) {
        val nowMin = (clock() / MINUTE_MS).toInt()
        nowCache.get(ch.identityHash, nowMin)?.let { previewNow.value = ch.identityHash to it; return }
        val info = try {
            feed.at(ch, nowMin)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BoxLog.w(TAG, "preview now failed: ${e.javaClass.simpleName}")
            null
        }
        previewNow.value = ch.identityHash to info
    }

    override fun onCleared() {
        listJob?.cancel()
        nowJob?.cancel()
        if (probe.current === machine) probe.register(null)
        shellEvents.close()
        super.onCleared()
    }

    companion object {
        private const val TAG = "PlayerViewModel"
        const val MINUTE_MS = 60_000L
        /** Row lookups kept (hits only). */
        const val CHANNEL_CACHE = 64
        /** A LiveResumed this soon after the archive session was active belongs to it (§3.10.2). */
        const val LIVE_RESUMED_GRACE_MS = 3_000L
        /** Focus changes in the info bar settle this long before the synopsis is read. */
        const val DETAIL_DEBOUNCE_MS = 150L
    }
}
