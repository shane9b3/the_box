package app.thebox.ui.full.vm

import app.thebox.core.parental.legacyParentalAccess
import app.thebox.core.parental.ParentalSession
import app.thebox.core.parental.PinOwner
import app.thebox.core.parental.PinPurpose
import app.thebox.core.playback.TuneGuard
import app.thebox.ui.parental.GuardedPlaybackController
import app.thebox.ui.parental.NoPinSession
import app.thebox.ui.parental.ParentalGuideLibrary
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
    rawController: PlaybackController,
    internal val archive: ArchivePlayback,
    internal val feed: ProgrammeFeed,
    rawLibrary: GuideLibrary,
    rowsFactory: GuideRowsCacheFactory,
    private val playerSettings: PlayerSettingsSource,
    internal val guideSettings: GuideSettingsSource,
    deviceClass: DeviceClassSource,
    machineFactory: PlayerStateMachineFactory,
    private val probe: PlayerDebugProbe,
    internal val reminders: ReminderBook,
    /** (M5 commit 0, docs/design/m5-setup-settings.md §1.3a) What PlayerEffectsImpl's M5 effects delegate to. */
    delegates: app.thebox.ui.root.EffectDelegates = app.thebox.ui.root.EffectDelegates.NONE,
    // M5 WP-C (docs/design/m5-setup-settings.md §3.9; S10, B1)
    private val parental: ParentalSession = NoPinSession,
    tuneGuard: TuneGuard = TuneGuard.ALLOW_ALL,
    // U-S12 (code review 2026-10-16): "Always passthrough" (the AUDIO picker's Back to Auto, the stats line).
    audioForced: AudioOutputForced = AudioOutputForced.NONE,
    // (Score bug commit 0, docs/design/m6-score-bug.md §6, A-B2) Present once WP-2's SportsModule binds it (an optional
    // binding, SportsUiModule); absent = no score feature and no requests.
    scores: java.util.Optional<app.thebox.core.sports.ScoreRepository> = java.util.Optional.empty(),
    /** (Score bug commit 0) Where the corner and the delay are saved (`SportsDomain`); null in tests without a store. */
    private val sportsStore: app.thebox.core.settings.SettingsStore? = null,
) : ViewModel(), PlayerHost, PlayerGuideBridge, app.thebox.ui.full.PlayerExtras {

    /**
     * (M5, B1) The player's effects tune through the guard: a refused channel-list tune or previous channel opens the
     * guide's PIN prompt with `Tune(channel, PLAYER)`; on success the player machine tunes it ([PlayerEvent.PinResult]).
     */
    internal val controller: PlaybackController = GuardedPlaybackController(rawController, tuneGuard, viewModelScope) { id, _ -> onRefused(id) }

    /** (M5, S10) The library with the session's access in every query; flows restart when it changes. */
    internal val library: GuideLibrary = ParentalGuideLibrary(rawLibrary, parental)

    /** Wall clock (tests substitute a virtual one). */
    internal var clock: () -> Long = System::currentTimeMillis

    private val device: DeviceClass = deviceClass.get()
    private val shellEvents = Channel<ShellEvent>(Channel.BUFFERED)
    private val rowsCache: GuideRowsCache = rowsFactory.create(rawLibrary, viewModelScope)
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
    /**
     * (M6 time-shift, docs/design/m6-timeshift.md §11.5) The paused lines and the dim card: the machine's paused state
     * (`PlayerUiState.paused`: media keys, dim; fix pass, UX review §7 — no cast to the implementation), the LOCAL
     * session's estimate, and the window and the hold from `ArchivePlayback.localBuffer` (A-C1: never the controller's
     * raw `localBuffer`; fix pass S1, S2).
     */
    override val pausedFacts: StateFlow<app.thebox.ui.full.PausedFacts> =
        combine(
            machine.ui.map { it.paused }.distinctUntilChanged(),
            archive.status,
            archive.localBuffer,
        ) { p, st, lb ->
            val local = st.source == app.thebox.core.catchup.archive.TimelineSource.LOCAL && st.phase != ArchivePhase.IDLE
            app.thebox.ui.full.PausedFacts(
                mediaKeys = p?.mediaKeys ?: false,
                dimmed = p?.dimmed ?: false,
                dimSinceMs = p?.dimSinceMs,
                pauseRemainingMs = if (local) st.pauseRemainingMs else null,
                windowMs = lb?.windowMs?.takeIf { it > 0 },
                holdMs = lb?.holdMs,
            )
        }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, app.thebox.ui.full.PausedFacts())

    /** (M6 §13, U-N3) The stats overlay's rewind line, only while stats are on and the cycle is buffered. */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val rewindStat: StateFlow<String?> = statsOn.flatMapLatest { on ->
        if (!on) kotlinx.coroutines.flow.flowOf(null)
        else controller.stats.map { s ->
            if (s.rewindWindowMs <= 0) null
            else {
                val line = context.resources.getString(
                    app.thebox.ui.R.string.player_stat_rewind,
                    PlayerText.behindText(s.rewindSavedMs), PlayerText.behindText(s.rewindWindowMs),
                    PlayerText.gbArg(context.resources, s.rewindBytes),
                )
                // (Save-location integration, S4-Q4; §10, U-N4) Where this cycle saves: "· NAS 3.4 MB/s", "· this device
                // (NAS offline)". Read per stats tick: the write rate is excluded from LocalBufferState's equality.
                val lb = controller.localBuffer.value
                val place = lb?.let { PlayerText.rewindPlaceStat(context.resources, it.storage, it.storageName, it.storageRatesBps.writeBps, it.fallback) }
                if (place != null) "$line · $place" else line
            }
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** U-S12 (code review 2026-10-16): "Always passthrough" is the audio output. */
    override val audioOutputForced: StateFlow<Boolean> = audioForced.flow().distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** (Score bug commit 0, docs/design/m6-score-bug.md §4.4) A selected, enabled text track (bottom corners go to the top). */
    override val textTrackOn: StateFlow<Boolean> = controller.tracks
        .map { t -> PlayerMappers.trackMenu(t).let { m -> m.textEnabled && m.text.any { it.selected } } }
        .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** (Score bug commit 0, §6, A-B2) The score repository, when bound. */
    internal val scoreRepo: app.thebox.core.sports.ScoreRepository? = scores.orElse(null)
    override val scoreBug: app.thebox.core.sports.ScoreBugSource? get() = scoreRepo

    /** Row lookups (N10 / M3 H-L12: only hits are cached); cleared when row content changes. */
    private val channelCache = object : LinkedHashMap<ChannelIdentity, GuideChannel>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ChannelIdentity, GuideChannel>?): Boolean = size > CHANNEL_CACHE
    }

    init {
        probe.register(machine)
        forwardFeeds()
        loadFocusedDetails()
        forwardScores()
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

    /** The identity of the last Tuned the machine got (main; see the Buffer re-send in [forwardFeeds]). */
    private var tunedIdentity: ChannelIdentity? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun forwardFeeds() {
        val scope = viewModelScope
        scope.launch {
            combine(controller.state, archive.status) { st, status -> st to status }.collect { (st, status) ->
                val ch = st.target?.identity?.let { channelOf(it) }
                // (M5, B1) A tune the guard refused never played.
                val shown = if (st is app.thebox.player.api.PlaybackState.Stopped && st.reason == app.thebox.player.api.StopReason.LOCKED) app.thebox.player.api.PlaybackState.Idle else st
                val mapped = TunedStateMapper.map(shown, ch?.rowId ?: -1L, still = false)
                machine.dispatch(PlayerEvent.Tuned(ArchiveVisibility.visible(mapped, status), ch))
                // (Integration 2026-10-19, I-TS-20a/I-TS-30) The machine drops its buffer when the tuned identity changes.
                // The new cycle's snapshot can arrive before this Tuned (channelOf suspends), and an unbuffered cycle
                // publishes its reason once (a StateFlow never repeats it): re-send the snapshot when it is the new
                // channel's, or the machine pauses an Off channel with no reason toast.
                val identity = st.target?.identity
                if (identity != tunedIdentity) {
                    tunedIdentity = identity
                    val lb = archive.localBuffer.value
                    if (identity != null && lb != null && controller.localBuffer.value?.identity == identity) machine.dispatch(PlayerEvent.Buffer(lb))
                }
            }
        }
        scope.launch {
            archive.status.collect { s ->
                if (s.phase != ArchivePhase.IDLE) archiveActiveAtMs = clock()
                machine.dispatch(PlayerEvent.Archive(s))
            }
        }
        scope.launch { controller.pendingZap.collect { id -> machine.dispatch(PlayerEvent.PendingZap(id?.let { channelOf(it) })) } }
        // M6 time-shift (docs/design/m6-timeshift.md §11.2, A-C1): the P-frame buffer is the only source of Buffer.
        scope.launch { archive.localBuffer.collect { machine.dispatch(PlayerEvent.Buffer(it)) } }
        // (M5 integration, E23) The servers of the tune ride with the track menu (the Sources picker).
        scope.launch {
            // U-S12 (code review 2026-10-16): the track menu says whether "Always passthrough" is on (Back to Auto).
            kotlinx.coroutines.flow.combine(controller.tracks, controller.servers, audioOutputForced) { t, s, forced ->
                PlayerMappers.trackMenu(t).copy(servers = s.labels, server = s.current, audioOutputForced = forced)
            }.collect { machine.dispatch(PlayerEvent.Tracks(it)) }
        }
        scope.launch { controller.video.collect { machine.dispatch(PlayerEvent.Video(PlayerMappers.videoFacts(it))) } }
        scope.launch {
            statsOn.flatMapLatest { on -> if (on) controller.stats else emptyFlow() }.collect {
                machine.dispatch(PlayerEvent.Stats(PlayerMappers.statsFacts(it, controller.video.value)))
            }
        }
        scope.launch { controller.previous.collect { id -> machine.dispatch(PlayerEvent.Previous(id?.let { channelOf(it) })) } }
        scope.launch {
            // (M5, S10) ParentalGuideLibrary feeds the session's access (the debug switch still forces OPEN).
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
        // M5 (§3.9): the session for the channel list's doors and the lock menu. (U-L18) No key hints: the player's bars
        // never read them (the design's hint row follows key availability; nothing in the player draws one yet).
        scope.launch { parental.state.collect { machine.dispatch(PlayerEvent.Parental(it)) } }
        scope.launch {
            archive.notices.collect { n ->
                when (n) {
                    is app.thebox.core.catchup.archive.ArchiveNotice.LiveNotReady -> onArchiveLiveNotReady()
                }
            }
        }
    }

    /**
     * (Score bug commit 0, docs/design/m6-score-bug.md §2.3, §2.6, §6, A-B2) With a score repository bound:
     * - its bug, followed game, day list and prefs go to the machine as one [app.thebox.core.sports.ScoreFacts] (WP-4b
     *   reads it; the machine should publish only while the quick bar, the sheet or place mode shows);
     * - its events become toasts (a reason and a next step), in every mode;
     * - FULL without the screen-off layer is demand `FULL_WITH_FOLLOW` (the repository adds "a game is followed");
     * - FULL with the screen-off layer is reported as such: the repository's demand is NONE then (C-S5, zero requests).
     * Nothing here makes a request: with nothing followed and no Sports UI open, the repository stays idle.
     */
    private fun forwardScores() {
        val repo = scoreRepo ?: return
        viewModelScope.launch {
            combine(combine(repo.bug, repo.lastScore, ::Pair), repo.followed, repo.day, repo.prefs) { (bug, known), game, day, p ->
                app.thebox.core.sports.ScoreFacts(
                    followed = bug, lastScore = known, game = game, day = day,
                    corner = p.corner, delaySec = p.delaySec, enabled = p.enabled, league = p.league, style = p.nhlStyle, chlLeague = p.chlShown,
                )
            }.distinctUntilChanged().collect { machine.scores(it) }
        }
        viewModelScope.launch { repo.events.collect { e -> shellEvents.trySend(ShellEvent.Message(app.thebox.ui.sports.SportsText.toast(context.resources, e))) } }
        val screenOff = machine.ui.map { it.layer == app.thebox.core.full.PlayerLayer.SCREEN_OFF }.distinctUntilChanged()
        viewModelScope.launch {
            combine(guideFull, screenOff) { full, off -> full && !off }
                .distinctUntilChanged()
                .collect { repo.reportDemand(app.thebox.core.sports.DemandOwner.FULL_WITH_FOLLOW, it) }
        }
        viewModelScope.launch {
            combine(guideFull, screenOff) { full, off -> full && off }
                .distinctUntilChanged()
                .collect { repo.reportScreenOff(it) }
        }
        // C-N2: a cleared view model withdraws what it reported, so the repository's demand never stays SEEN for it.
        addCloseable {
            repo.reportDemand(app.thebox.core.sports.DemandOwner.FULL_WITH_FOLLOW, false)
            repo.reportScreenOff(false)
        }
    }

    /** (Score bug commit 0, §6) Follow [id] from the current day board (null = stop following); unknown ids are ignored. */
    internal fun followGame(id: app.thebox.core.sports.GameId?) {
        val repo = scoreRepo ?: return
        if (id == null) { repo.follow(null); return }
        val board = (repo.day.value as? app.thebox.core.sports.DayState.Board)?.board ?: return
        board.games.firstOrNull { it.id == id }?.let { repo.follow(it) }
    }

    /** (Score bug commit 0, §6) Corner and delay from the quick bar and place mode. */
    internal fun updateSports(transform: (app.thebox.core.sports.SportsPrefs) -> app.thebox.core.sports.SportsPrefs) {
        sportsStore?.update(app.thebox.core.settings.state.SportsDomain, transform)
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
                // M6 §10, §13 (U-S9): the reason-less "live resumed" becomes BACK_TO_LIVE_UNBUFFERED, with the next step
                // of the pause's reason once per tune (fix pass S6).
                toast(PlayerToast.BACK_TO_LIVE_UNBUFFERED, unbufferedStepOnce())
                return
            }
            // M6 §13: the toast needs the landing time, which only the timeline knows ("Now at 21:26").
            is PlaybackNotice.GapSkipped -> {
                val st = archive.status.value
                val at = if (st.phase != ArchivePhase.IDLE) st.wallMs + (if (st.advancing) clock() - st.atMs else 0L) + n.gapMs + st.displayShiftMs else clock()
                // Raw ms, like the machine's GAP_SKIPPED toasts (PlayerToast's argument convention).
                toast(if (n.stream) PlayerToast.GAP_SKIPPED_STREAM else PlayerToast.GAP_SKIPPED_PAUSED, n.gapMs.toString() + PlayerText.ARG_SEP + at)
                return
            }
            // §11.5: while paused the header line says it; the toast is for a viewer behind live.
            is PlaybackNotice.SavingStopped -> {
                if (archive.status.value.phase == ArchivePhase.PAUSED &&
                    (n.stop == app.thebox.core.playback.timeshift.RecordStop.SLOT_LOST || n.stop == app.thebox.core.playback.timeshift.RecordStop.PREEMPTED)) return
                // (Save-location integration, S4-Q5; storage §10) A cycle saving on a USB drive or a network folder names
                // the place, and a read failure says "back at live" / "while you were paused". This device: today's text.
                storageStopToast(n)?.let { (kind, arg) -> toast(kind, arg); return }
            }
            // §8.4 (U-S2): once a day.
            PlaybackNotice.LimitedToday -> if (!limitedTodayOnce()) return
            else -> Unit
        }
        val text = PlayerText.notice(context.resources, n) ?: return
        shellEvents.trySend(ShellEvent.Message(text))
    }

    /**
     * (S4-Q5, §10) The PlayerToast and its `"<StorageKind>␟<StorageFailure|>␟<name>␟<jumped 1|>"` arg for a storage stop
     * of a cycle that saves on a USB drive or a network folder; null = this device (today's notice text) or not a disk stop.
     */
    private fun storageStopToast(n: PlaybackNotice.SavingStopped): Pair<PlayerToast, String>? {
        val lb = controller.localBuffer.value ?: return null
        if (lb.storage == app.thebox.core.storage.StorageKind.INTERNAL) return null
        val kind = when {
            n.resumeLost -> PlayerToast.PAUSE_RESUME_LOST_STORAGE
            n.stop == app.thebox.core.playback.timeshift.RecordStop.DISK_TOO_SLOW -> PlayerToast.REWIND_STOPPED_SLOW
            n.stop == app.thebox.core.playback.timeshift.RecordStop.DISK_ERROR ||
                n.stop == app.thebox.core.playback.timeshift.RecordStop.STORAGE_EVICTED -> PlayerToast.REWIND_STOPPED_STORAGE
            else -> return null
        }
        val failure = if (n.stop == app.thebox.core.playback.timeshift.RecordStop.DISK_TOO_SLOW) app.thebox.core.storage.StorageFailure.SLOW else lb.storageFailure
        val arg = app.thebox.core.full.StorageToastArg(lb.storage, failure?.name.orEmpty(), lb.storageName.orEmpty(), n.jumpedToLive).encode()
        return kind to arg
    }

    /** The local day REWIND_LIMITED_TODAY was last shown (§8.4: once a day). */
    private var limitedShownDay = Long.MIN_VALUE

    private fun limitedTodayOnce(): Boolean {
        val day = java.time.Instant.ofEpochMilli(clock()).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
        if (day == limitedShownDay) return false
        limitedShownDay = day
        return true
    }

    /** WP-B's `ArchiveNotice.LiveNotReady` (archive lag used up near the edge, §3.8.7) → the ARCHIVE_NOT_READY toast. */
    internal fun onArchiveLiveNotReady() = toast(PlayerToast.ARCHIVE_NOT_READY, null)

    // ---- effect helpers (PlayerEffectsImpl)

    internal fun dispatch(event: PlayerEvent) = machine.dispatch(event)

    internal fun toast(kind: PlayerToast, arg: String?) {
        if (kind == PlayerToast.REWIND_LIMITED_TODAY && !limitedTodayOnce()) return
        val words = toastArg(kind, arg)
        PlayerText.noRewindStep(context.resources, kind, words)?.let { step ->
            val t = controller.state.value.target
            unbufferedStep = t?.identity?.let { UnbufferedStep(it, t.tuneId, step) }
        }
        shellEvents.trySend(ShellEvent.Message(PlayerText.toast(context.resources, kind, words)))
    }

    /** (Fix pass S6) The next step of the last PAUSE_NO_REWIND_* toast, for the channel and tune it was shown on. */
    private class UnbufferedStep(val identity: ChannelIdentity, val tuneId: Long, val step: String, var used: Boolean = false)
    private var unbufferedStep: UnbufferedStep? = null

    /**
     * §13 BACK_TO_LIVE_UNBUFFERED "with the next step of the reason above, once per tune": the step of the unbuffered
     * pause on the channel now playing, the first time only (a resume re-tunes, so the tune is the pause's).
     */
    private fun unbufferedStepOnce(): String? {
        val u = unbufferedStep ?: return null
        if (u.used || controller.state.value.target?.identity != u.identity) return null
        u.used = true
        return u.step
    }

    /**
     * (M6 time-shift, §13) The machine passes raw values (ms) where a toast shows a time or a length; this turns them into
     * the words on screen ("21:02", "30 min", "0.6 GB", the playlist).
     */
    private fun toastArg(kind: PlayerToast, arg: String?): String? {
        val res = context.resources
        return when (kind) {
            PlayerToast.REWIND_START -> {
                val at = PlayerText.args(arg, 0).toLongOrNull()?.let { PlayerText.clockArg(it, guideSettings.settings.value.clock24) } ?: PlayerText.args(arg, 0)
                val window = PlayerText.args(arg, 1).toLongOrNull()?.let { PlayerText.durationArg(res, it) }
                if (window == null) at else at + PlayerText.ARG_SEP + window
            }
            PlayerToast.REWIND_NOTHING_YET -> {
                val window = archive.localBuffer.value?.windowMs?.takeIf { it > 0 } ?: arg?.toLongOrNull()
                    ?: app.thebox.core.playback.timeshift.TimeshiftConstants.AUTO_LENGTH_SMALL_MS
                PlayerText.durationArg(res, window)
            }
            PlayerToast.GAP_SKIPPED_PAUSED, PlayerToast.GAP_SKIPPED_STREAM -> {
                val len = PlayerText.args(arg, 0).toLongOrNull()
                val at = PlayerText.args(arg, 1).toLongOrNull()
                if (len == null || at == null) arg
                else PlayerText.durationArg(res, len) + PlayerText.ARG_SEP + PlayerText.clockArg(at, guideSettings.settings.value.clock24)
            }
            PlayerToast.ZAP_CONFIRM, PlayerToast.ZAP_CONFIRM_NUMBER -> arg?.toLongOrNull()?.let { PlayerText.durationArg(res, it) } ?: arg
            PlayerToast.PAUSE_NO_REWIND_SPACE -> arg ?: try {
                PlayerText.gbArg(res, android.os.StatFs(context.cacheDir.path).availableBytes)
            } catch (e: Exception) {
                null
            }
            PlayerToast.PAUSE_NO_REWIND_HLS -> if (arg != null && arg.isEmpty()) res.getString(app.thebox.ui.R.string.player_arg_your_playlist) else arg
            else -> arg
        }
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

    /** (M5, B1) A refused tune from the player's effects: the guide's one PIN prompt, owner PLAYER. */
    private fun onRefused(identity: ChannelIdentity) {
        launchSafely("refused") {
            val ch = channelOf(identity) ?: return@launchSafely
            guide?.parentalHost?.request(PinPurpose.Tune(ch, PinOwner.PLAYER))
        }
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
        // (M5, S10) the decorated library replaces this with the session's access (a source opened anyway: OPEN).
        val access = legacyParentalAccess(guideSettings.settings.value.debugUnlocked)
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
