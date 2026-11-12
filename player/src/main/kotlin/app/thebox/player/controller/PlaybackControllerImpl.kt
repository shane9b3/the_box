package app.thebox.player.controller

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Trace
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import app.thebox.core.common.BoxLog
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.common.AuthReason
import app.thebox.core.playback.AccountGate
import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.AudioOutputMode
import app.thebox.core.playback.BufferMode
import app.thebox.core.playback.BufferProfile
import app.thebox.core.playback.BufferProfileFsm
import app.thebox.core.playback.ChannelStreamInfo
import app.thebox.core.playback.ErrorDetail
import app.thebox.core.playback.ErrorLadder
import app.thebox.core.playback.FailureSignal
import app.thebox.core.playback.HdrKind
import app.thebox.core.playback.HdrPreviewMode
import app.thebox.core.playback.LadderContext
import app.thebox.core.playback.LadderCounters
import app.thebox.core.playback.LadderDecision
import app.thebox.core.playback.LeaseKind
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.NetworkFailureKind
import app.thebox.core.playback.NextStep
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.PlaybackEventLog
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.PlaybackWarning
import app.thebox.core.playback.PlaylistPlaybackInfo
import app.thebox.core.playback.ReResolveLimiter
import app.thebox.core.playback.errors.ReResolveKeys
import app.thebox.core.playback.resolve.FormatHints
import app.thebox.core.playback.resolve.ResolveConstants
import app.thebox.core.playback.ResolveOutcome
import app.thebox.core.playback.ResolvedStream
import app.thebox.core.playback.RetryInfo
import app.thebox.core.playback.StallKind
import app.thebox.core.playback.StallVerdict
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.TuneGuard
import app.thebox.core.playback.TuneVerdict
import app.thebox.core.playback.VideoDecoderMode
import app.thebox.core.playback.Viewport
import app.thebox.core.playback.XtreamOutput
import app.thebox.core.playback.ZapDebouncer
import app.thebox.core.playback.ZapRecord
import app.thebox.core.playback.broker.BrokerConstants
import app.thebox.core.playback.errors.ErrorConstants
import app.thebox.core.playback.errors.ErrorLadderImpl
import app.thebox.core.playback.stall.StallConstants
import app.thebox.player.engine.PlayerFactory
import app.thebox.player.engine.StatsCollector
import app.thebox.player.engine.UnsupportedStreamFormatException
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.source.PlaylistType
import app.thebox.core.xtream.AccountStatus
import app.thebox.core.xtream.XtreamAccountInfo
import app.thebox.player.api.ArchiveTune
import app.thebox.player.api.BufferingCause
import app.thebox.player.api.ChannelMemory
import app.thebox.player.api.ChannelPlaybackMemory
import app.thebox.player.api.ConnectPhase
import app.thebox.player.api.ExternalHandOff
import app.thebox.player.api.NowPlaying
import app.thebox.player.api.PreviousResult
import app.thebox.player.api.ServerList
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.PlaybackDiagnostics
import app.thebox.player.api.PlaybackNotice
import app.thebox.player.api.PlaybackSettingsSource
import app.thebox.player.api.PlaybackSourceProvider
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.PositionSnapshot
import app.thebox.player.api.ReconnectProgress
import app.thebox.player.api.StopReason
import app.thebox.player.api.StreamStats
import app.thebox.player.api.TrackInfo
import app.thebox.player.api.TrackRef
import app.thebox.player.api.TuneTarget
import app.thebox.player.api.VideoInfo
import app.thebox.player.api.ZapList
import app.thebox.player.internal.MediaSourceBuilderApi
import app.thebox.player.internal.PlayerBundle
import app.thebox.player.internal.PlayerFactoryApi
import app.thebox.player.internal.StreamBrokerRegistry
import app.thebox.player.internal.StreamSessionContext
import app.thebox.player.internal.StreamUrlResolver
import app.thebox.player.internal.TransportFactory
import app.thebox.player.internal.TsCompat
import app.thebox.player.internal.TuneTargetKey
import app.thebox.core.playback.timeshift.BufferOff
import app.thebox.core.playback.timeshift.PinOwner
import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.RingBoundaryException
import app.thebox.core.playback.timeshift.RingEndException
import app.thebox.core.playback.timeshift.RingException
import app.thebox.core.playback.timeshift.RingPositionLostException
import app.thebox.core.playback.timeshift.RingWriterListener
import app.thebox.core.playback.timeshift.SegmentReason
import app.thebox.core.playback.timeshift.TimeshiftConstants
import app.thebox.player.api.LocalBufferState
import app.thebox.player.api.LocalSeekResult
import app.thebox.player.datasource.LiveFailureKind
import app.thebox.player.datasource.LiveStreamException
import app.thebox.player.internal.RingOpen
import app.thebox.player.internal.TimeshiftGrant
import app.thebox.player.internal.TimeshiftPrepared
import app.thebox.player.internal.TimeshiftSession
import app.thebox.player.internal.TimeshiftSessionFactory
import app.thebox.player.timeshift.PendingTimeshiftPreparation
import app.thebox.player.timeshift.NoTimeshiftFactory
import app.thebox.player.timeshift.RingReaderTelemetry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * The state machine of §3.1, ladder execution (§4.3), zap list and debounce (§3.5.4), previous channel, pause/resume,
 * lifecycle (§3.10, through [LifecycleCoordinator]), per-channel memory (§3.12), diagnostics wiring (§3.13) and the
 * archive seam (§3.14).
 *
 * Vocabulary: a **cycle** is what the user asked for (one `tune()`, `retry()`, resume or zap); it owns the ladder
 * counters, the first-media milestone and the runtime fallbacks. A **session** ([TuneSession]) is one open of the stream
 * inside a cycle: every ladder re-open ends the old session (the transport cancels its calls and releases the lease)
 * before the next one starts, and gets a new tune id.
 *
 * Threading (§2.7): every method and every listener runs on the main looper; provider calls are suspend functions whose
 * implementations switch to IO; data-source events are posted to the main looper. No `runBlocking`, no sleeps.
 */
@OptIn(UnstableApi::class)
@Singleton
internal class PlaybackControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val provider: PlaybackSourceProvider,
    private val memory: ChannelMemory,
    private val settings: PlaybackSettingsSource,
    private val resolver: StreamUrlResolver,
    private val brokers: StreamBrokerRegistry,
    private val transport: TransportFactory,
    private val playerFactory: PlayerFactoryApi,
    private val mediaSources: MediaSourceBuilderApi,
    private val eventLog: PlaybackEventLog,
    private val clock: MonoClock,
    private val ladder: ErrorLadder,
    private val limiter: ReResolveLimiter,
    private val debouncer: ZapDebouncer,
    private val bufferFsm: BufferProfileFsm,
    private val engine: EngineEnvironment,
    private val importGate: ZapImportGate,
    private val sessionFactory: MediaSessionFactory,
    deviceState: LifecycleCoordinator.DeviceState,
    /**
     * (M5, docs/design/m5-setup-settings.md §3.9.2, B1) Checked before every channel cycle starts (tune, catch-up, zap,
     * previous channel, retry, resume after a lifecycle stop). Called on main; WP-C's implementation must be main-safe.
     */
    private val tuneGuard: TuneGuard,
    /**
     * (M6 time-shift, docs/design/m6-timeshift.md §7) The split session factory (A-C1); its three settings come in
     * [PlaybackSettings] (lead reconciliation R10). The default (tests, an app without the time-shift module) keeps every
     * cycle unbuffered whatever the setting says: today's pipeline exactly.
     */
    private val timeshift: TimeshiftSessionFactory = NoTimeshiftFactory,
    /**
     * (M6, §9.1, A-C5) The gap rule's owner. Not consulted yet: the A-C5 rule is coded in [onRingBoundary] until WP-C's
     * router lands; the switch is one line (design "Lead reconciliation — WP-A/WP-B", R14).
     */
    private val timelineRouter: app.thebox.core.catchup.archive.TimelineRouter = app.thebox.core.catchup.archive.TimelineRouterImpl(),
    /** (Score bug commit 0, docs/design/m6-score-bug.md §2.3, A-S5) Fed by [lifecycle]; read by the score repository. */
    deviceAwake: DeviceAwakeState = DeviceAwakeState(),
) : PlaybackController, LifecycleCoordinator.Target {

    // ------------------------------------------------------------------ public flows

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val _pendingZap = MutableStateFlow<ChannelIdentity?>(null)
    override val pendingZap: StateFlow<ChannelIdentity?> = _pendingZap.asStateFlow()
    private val _video = MutableStateFlow(VideoInfo())
    override val video: StateFlow<VideoInfo> = _video.asStateFlow()
    private val _tracks = MutableStateFlow(TrackInfo())
    override val tracks: StateFlow<TrackInfo> = _tracks.asStateFlow()
    private val _stats = MutableStateFlow(StreamStats())
    override val stats: StateFlow<StreamStats> = _stats.asStateFlow()
    private val _notices = MutableSharedFlow<PlaybackNotice>(replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val notices: SharedFlow<PlaybackNotice> = _notices.asSharedFlow()
    private val _keepScreenOn = MutableStateFlow(false)
    override val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()
    private val _displayModeRequest = MutableStateFlow(0)
    override val displayModeRequest: StateFlow<Int> = _displayModeRequest.asStateFlow()

    override val surfacePlayer: Player get() = ensureEngine().surface
    /**
     * (M4, docs/design/m4-player-ux.md §3.6.3) `Player.Listener.onCues(CueGroup)` on the application (main) looper;
     * emptied whenever a session ends ([endSession]: every tune, ladder re-open, error and stop), because
     * `ExoPlayerImpl.stop()` resets its cue group without a callback (API notes §1.2).
     */
    private val _cues = MutableStateFlow<List<androidx.media3.common.text.Cue>>(emptyList())
    override val cues: StateFlow<List<androidx.media3.common.text.Cue>> = _cues.asStateFlow()
    /** (M4, §2.9) Mirrors [previousIdentity]: what [previousChannel] would tune (M2 §3.1.1 rule). */
    private val _previous = MutableStateFlow<ChannelIdentity?>(null)
    override val previous: StateFlow<ChannelIdentity?> = _previous.asStateFlow()
    /** (M6 time-shift, §16.2) The current live cycle's local buffer, ≤ 1 Hz; null = no live cycle. */
    private val _localBuffer = MutableStateFlow<LocalBufferState?>(null)
    override val localBuffer: StateFlow<LocalBufferState?> = _localBuffer.asStateFlow()

    override val diagnostics: PlaybackDiagnostics = object : PlaybackDiagnostics {
        override fun events() = eventLog.events()
        override fun zaps() = eventLog.zaps()
        override suspend fun capabilities(): Map<String, String> = withContext(Dispatchers.IO) { engine.capabilities() }
    }

    // ------------------------------------------------------------------ state

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, t -> BoxLog.e(TAG, "controller job failed", t) },
    )

    /**
     * (M3 code review 2026-10-14, O-3) The tune's facts and resolve run in one hop to this dispatcher instead of about
     * 18 IO ↔ main hops, each of which waited for a busy main thread. Tests set it to the main dispatcher.
     */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val mainHandler = Handler(Looper.getMainLooper())
    private val classifier = FailureClassifier()

    /** Lifecycle wiring (§3.10). Started with the player. Internal for tests. */
    internal val lifecycle = LifecycleCoordinator(context, this, deviceState, { settings.current.lifecycleTimeScale }, awakeSink = deviceAwake::set)

    private class Engine(val bundle: PlayerBundle, val parts: EngineParts, val surface: SurfacePlayer, val session: SessionHandle?)

    private var eng: Engine? = null
    private val player: Player? get() = eng?.bundle?.player

    private var cycle: Cycle? = null
    private var session: TuneSession? = null
    private var lastTuneId = 0L
    private var viewport = Viewport.FULL
    /** (E-S4) The root `SurfaceView` has a valid surface (reported by [SurfacePlayer]). */
    private var surfaceValid = false
    private var statsEnabled = false
    private var zapList: ZapList? = null
    private var previousIdentity: ChannelIdentity? = null
        set(value) {
            field = value
            _previous.value = value
        }
    private var lifecycleStopAtMs = 0L
    /**
     * (M4, M2 E-L2) The session whose display-mode gate opened (`awaitSettled`): only then may a resume set
     * `playWhenReady`; before it, the gate itself applies `!c.paused`.
     */
    private var settledSession: TuneSession? = null

    /** Passthrough denials learned on the current audio route (L33); survive tunes, cleared on a route change (§3.8.3). */
    private val routeDeniedPassthrough = LinkedHashSet<String>()

    private var sessionJob: Job? = null
    private var switchTimeoutJob: Job? = null
    private var livePauseJob: Job? = null
    private var healthyJob: Job? = null
    private var compatPersistJob: Job? = null
    private var recordWatchedJob: Job? = null
    private var zapFireJob: Job? = null
    /** (E-S1) `neighbour()` lookups in flight, and the generation that [cancelPendingZap] bumps. Main thread only. */
    private val zapLookups = LinkedHashSet<Job>()
    private var zapGeneration = 0
    private var pollJob: Job? = null
    private val zapMutex = Mutex()
    /** (M6 time-shift, §7.4) Writer-scoped ladder work (delay, resolve, restart); never cancelled by a reader re-prepare. */
    private var writerJob: Job? = null
    /** (Fix pass UX-B2) Skips a pending LocalGap locally when no archive tune arrived within GAP_HANDOFF_TIMEOUT_MS. */
    private var gapFallbackJob: Job? = null
    private var cycleSeq = 0L
    /** (M6, §7.5) A lifecycle stop ended a paused or behind buffered cycle: the next tune says so once. */
    private var leftAppPending = false
    private var lastLocalPublishAtMs = 0L

    /** What a cycle plays. */
    private sealed interface Spec {
        data class Live(val target: TuneTarget) : Spec
        data class Archive(val tune: ArchiveTune) : Spec
    }

    /** (Keep-decoders WP-0) Decoder cost of the zap in progress; written into its [ZapRecord] and one log line. */
    private val zapDecoders = app.thebox.core.playback.diag.ZapDecoderMeter()

    /** Per-cycle timings for the [ZapRecord] (ms since the cycle started). */
    private class ZapTimings {
        var resolveMs: Long? = null
        var leaseMs: Long? = null
        var firstByteMs: Long? = null
        var readyMs: Long? = null
        var firstMediaMs: Long? = null
        var recorded = false
    }

    /** One user request: ladder counters, milestone, runtime fallbacks, cached provider facts (§3.1, §4.3, §4.5). */
    private class Cycle(val spec: Spec, val identity: ChannelIdentity?, val startedAtMs: Long) {
        val zap = ZapTimings()
        var firstTuneId = 0L
        /** (Fix pass UX-S1) `LocalBufferState.cycleId`: unique per cycle, set when the cycle is created. */
        var cycleId = 0L
        var currentTuneId = 0L
        var title: String? = null
        var counters = LadderCounters()
        var attempt = 1
        var firstMediaReached = false
        var firstMediaAtMs = 0L
        var lastSignal: FailureSignal? = null
        var lastErrorAtMs = 0L
        var cardShown = false
        /**
         * (Fix pass P1) First media cleared [cardShown] before READY (Media3 renders the first frame while BUFFERING):
         * READY still lifts the card.
         */
        var cardLiftPending = false
        var paused = false
        var resolved: ResolvedStream? = null
        var firstSession = true
        /** Archive position at stop, so a resume continues there. */
        var archiveResumeAtMs = 0L

        // Provider facts, read once per cycle.
        var factsLoaded = false
        var channelInfo: ChannelStreamInfo? = null
        var playlistInfo: PlaylistPlaybackInfo? = null
        var account: XtreamAccountInfo? = null
        var accountGate = AccountGate.UNKNOWN
        var memory = ChannelPlaybackMemory()
        var backups: List<ChannelIdentity> = emptyList()

        // Ladder overrides (session-only unless noted).
        var baseIndex: Int? = null
        var output: XtreamOutput? = null
        var flipPending: XtreamOutput? = null
        var userAgentOverride: String? = null
        var uaFallbackPending = false
        var format: StreamFormat? = null
        var softwareVideo = false
        var decoderOverride: VideoDecoderMode? = null
        val softwareAudioMimes = LinkedHashSet<String>()
        var forceStereo = false
        var tunnelingOff = false
        /** The stream-switch deadline: tune start + timeout, moved once by [extendSwitchWindow]. */
        var switchDeadlineAtMs = 0L
        var switchExtended = false
        var audioDropped = false
        /** (L33r) The TS guard regenerates the audio timestamps for the sessions of this cycle. */
        var audioTsRepair = false
        var warning: PlaybackWarning? = null
        var tsCompat = 0
        /**
         * (follow-up 2026-10-14) A compat raise (L27b) from a decoder-type signal, applied for this cycle but saved to
         * ChannelMemory only after [COMPAT_PERSIST_HEALTHY_MS] of healthy playback at the raised level.
         */
        var compatPending = false
        /** Failures and rebuffers in this cycle (the compat-persist window checks that neither changed). */
        var failureCount = 0
        var rebufferCount = 0
        var reconnectNoticeShown = false

        // (M6 time-shift, §7) The cycle's local buffer.
        /** The cycle's time-shift session (null = unbuffered, or not started yet). */
        var ts: TimeshiftSession? = null
        /** Storage granted by `prepare()`; retries restart the existing session instead of reusing a consumed grant. */
        var tsGrant: TimeshiftGrant? = null
        var tsPrepare: PendingTimeshiftPreparation? = null
        /** The buffered/unbuffered decision was taken (once per cycle). */
        var tsDecided = false
        var tsOff: BufferOff? = null
        /** The writer committed its first bytes (A-C2: before it, writer failures take the one first-open route). */
        var firstByteSeen = false
        /** PTS mono of the current reader session's position 0 (§6 step 4); < 0 = not known yet. */
        var readerOrigin = -1L
        var readerStartOffset = 0L
        /** An active LOCAL time-shift: paused, or seeked behind live and not yet back at the head (§9.1, A-C5). */
        var localShift = false
        /** (Save-location integration, S4-Q5) MonoClock ms of the last buffered resume; MIN_VALUE = none. */
        var resumedAtMonoMs = Long.MIN_VALUE
        /** (S4-Q5) The read-failure notice (back at live / resume lost) was sent for this cycle. */
        var readLostNotified = false
        var pausedAtMs = 0L
        /** A cycle-scoped decision deferred until the reader reaches the head (§7.4). */
        var deferred: Pair<LadderDecision, FailureSignal>? = null
        /** The backlog of the cycle's first connection (A-C3): newest PTS mono − first-media PTS mono. */
        var latencyAtTuneMs = 0L
        /** After a non-failure ring end the writer restarted: jump to its first RAP once the index has one (§7.4). */
        var liveAfterRestartFrom = -1L
        /** Set while the current failure came from the recorder (writer path, or detector (a) at the head). */
        var failureFromWriter = false
        /** The last SavingStopped notice (one toast per stop). */
        var stopNotice: RecordStop? = null
        /** The last reason saving stopped this cycle (writer self-stop or [stopSaving]); the A-C5 `stop` argument (R14). */
        var lastStop: RecordStop? = null
        /** (Fix pass S2) The last writer segment storm (mono ms); a repeat within WRITER_STORM_REPEAT_MS is cycle-scoped. */
        var lastStormAtMs = Long.MIN_VALUE
        /** (Fix pass UX-B2) A gap handed to the provider (`LocalGap`) that has not been acted on yet. */
        var pendingGap: RingBoundaryException? = null

        // Buffer and video facts.
        var profile: BufferProfile? = null
        var lastRebufferAtMs = 0L
        var lastStableTickAtMs = 0L
        var hdr: HdrKind? = null
        var fpsMilli: Int? = null

        val isArchive: Boolean get() = spec is Spec.Archive
        val archive: ArchiveTune? get() = (spec as? Spec.Archive)?.tune
        val directTarget: TuneTarget.DirectUrl? get() = (spec as? Spec.Live)?.target as? TuneTarget.DirectUrl
    }

    private data class Plan(
        val delayMs: Long = 0,
        val reuseResolved: Boolean = false,
        val refreshAccount: Boolean = false,
        /** (M6, §6, §7.6 `Plan.readerOnly`) Re-prepare the ring reader only: no facts, resolve, broker or writer. */
        val reader: ReaderStart? = null,
        /**
         * (Ladder fix 2026-11-11, docs/design/m6-timeshift.md §6 step 3) The next prepare needs new decoders. Only a
         * buffered reader re-prepare reads it: it then stops the player before the re-prepare, because a `setMediaSource`
         * without `stop()` only disables the renderers and Media3 reuses the same codecs. The stop resets every renderer
         * (video and audio). Unbuffered sessions always stop anyway.
         */
        val freshDecoders: Boolean = false,
    )

    /** (M6, §6) Where a reader session opens: a RAP's packet and that RAP's PTS mono (< 0 = resolved at first media). */
    private class ReaderStart(val offset: Long, val originMono: Long)

    // ------------------------------------------------------------------ tune API

    override fun tune(target: TuneTarget, zapList: ZapList?) {
        if (zapList != null) this.zapList = zapList
        cancelPendingZap()
        val identity = (target as? TuneTarget.Channel)?.identity
        if (identity != null && refused(identity, isArchive = false)) return
        if (viewport == Viewport.MINI) previewStarted()
        startCycle(Spec.Live(target))
    }

    override fun tuneArchive(tune: ArchiveTune) {
        cancelPendingZap()
        if (refused(tune.identity, isArchive = true)) return
        startCycle(Spec.Archive(tune))
    }

    /**
     * (M5, docs/design/m5-setup-settings.md §3.9.2, B1) The [TuneGuard] backstop for a direct request (tune, catch-up,
     * retry). A refused target never starts: the current stream stops (its connection is released) and the state
     * becomes `Stopped(target = the refused channel, reason = LOCKED, resumable = false)`, from which the guide/player
     * machines open the PIN prompt (`Tune(channel, owner)`) or, for NOT_PROTECTED, the "not protected" card. True when
     * refused.
     */
    private fun refused(identity: ChannelIdentity, isArchive: Boolean): Boolean {
        val verdict = guard(identity)
        if (verdict == TuneVerdict.ALLOWED) return false
        BoxLog.i(TAG) { "tune refused by the guard: ${verdict.name}" }
        stop(StopReason.LOCKED)
        setState(PlaybackState.Stopped(lockedTarget(identity, isArchive), StopReason.LOCKED, resumable = false))
        return true
    }

    /** One guard call; a failing guard refuses (fail closed, §3.9.6). */
    private fun guard(identity: ChannelIdentity): TuneVerdict = try {
        tuneGuard.check(identity)
    } catch (e: Exception) {
        BoxLog.w(TAG, "tune guard failed: ${e.javaClass.simpleName}")
        TuneVerdict.NEEDS_PIN
    }

    private fun lockedTarget(identity: ChannelIdentity, isArchive: Boolean) =
        NowPlaying(tuneId = cycle?.currentTuneId ?: 0, identity = identity, title = null, isArchive = isArchive, format = StreamFormat.UNKNOWN)

    override fun setZapList(list: ZapList) {
        zapList = list
    }

    override fun zap(delta: Int, isRepeat: Boolean) {
        val list = zapList ?: return
        val plan = debouncer.onZap(clock.nowMs(), isRepeat)
        val generation = zapGeneration
        val job = scope.launch(start = CoroutineStart.LAZY) {
            zapMutex.withLock {
                if (generation != zapGeneration) return@withLock
                val from = _pendingZap.value ?: cycle?.identity ?: return@withLock
                val next = try {
                    allowedNeighbour(list, from, delta)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    BoxLog.w(TAG, "zap list failed: ${e.javaClass.simpleName}")
                    null
                } ?: return@withLock
                // (code review 2026-10-14, E-S1) tune(), stop() or a lifecycle stop happened during the lookup.
                if (generation != zapGeneration) return@withLock
                if (plan.immediate) {
                    zapFireJob?.cancel()
                    _pendingZap.value = null
                    startCycle(Spec.Live(TuneTarget.Channel(next)))
                } else {
                    _pendingZap.value = next
                    zapFireJob?.cancel()
                    zapFireJob = scope.launch {
                        delay((plan.fireAtMs - clock.nowMs()).coerceAtLeast(0))
                        debouncer.onFired()
                        val target = _pendingZap.value ?: return@launch
                        _pendingZap.value = null
                        startCycle(Spec.Live(TuneTarget.Channel(target)))
                    }
                }
            }
        }
        zapLookups += job
        job.invokeOnCompletion { zapLookups -= job }
        job.start()
    }

    /**
     * (M5, §3.9.2, B1) The zap neighbour the [TuneGuard] allows: a refused channel is skipped like the lineups skip
     * hidden ones, at most [MAX_ZAP_GUARD_SKIPS] steps, and never past [from] again (a list of locked channels only
     * yields null: no zap).
     */
    private suspend fun allowedNeighbour(list: ZapList, from: ChannelIdentity, delta: Int): ChannelIdentity? {
        var at = from
        repeat(MAX_ZAP_GUARD_SKIPS) {
            val next = list.neighbour(at, delta) ?: return null
            if (guard(next) == TuneVerdict.ALLOWED) return next
            if (next == from) return null
            at = next
        }
        return null
    }

    /** The M2 Boolean form; a guard refusal counts as "nothing tuned" (the PIN-aware form is [previousChannelResult]). */
    override fun previousChannel(): Boolean = previousChannelResult() == PreviousResult.Started

    /**
     * (M5, §2.12 item 6, §3.9.2, B1) [PreviousResult.Locked] when the guard refuses the previous channel: nothing is
     * tuned and the state is unchanged; the caller opens the PIN prompt (or the "not protected" card) and tunes it after
     * the PIN.
     */
    override fun previousChannelResult(): PreviousResult {
        val prev = previousIdentity ?: return PreviousResult.NoPrevious
        if (guard(prev) != TuneVerdict.ALLOWED) return PreviousResult.Locked(prev)
        tune(TuneTarget.Channel(prev))
        return PreviousResult.Started
    }

    /**
     * (M5, docs/design/m5-setup-settings.md §0.6, §2.12 item 6, S9) Settings closed with a REPREPARE-class change: one new
     * session of the current **live** cycle at the live edge (fresh open; the resolved URL is reused), so
     * `applySwitches` reads the new buffer, decoder, tunneling and audio-output values. Same engine, same cycle (ladder
     * counters and per-channel memory kept), logged as one `DECISION reapply_settings`. No-op when nothing live is
     * active: idle, stopped (the next start reads the settings anyway), archive (the `:app` applier resumes the archive
     * at its playhead), or the error card (Retry applies the settings).
     */
    override fun reapplySettings() {
        val c = cycle ?: return
        if (c.isArchive || c.cardShown) return
        val st = _state.value
        if (st !is PlaybackState.Playing && st !is PlaybackState.Buffering && st !is PlaybackState.Connecting && st !is PlaybackState.Stalled) return
        // A paused unbuffered live tune resumes with a fresh tune anyway; (M6, §7.6) a buffered one re-prepares its reader
        // at the pin and stays paused.
        if (st is PlaybackState.Playing && st.paused && c.ts == null) return
        eventLog.add(PlaybackEventType.DECISION, c.currentTuneId, "reapply_settings", "")
        BoxLog.i(TAG) { "settings changed: re-preparing the live tune" }
        val waiting: ((NowPlaying) -> PlaybackState)? =
            if (c.firstMediaReached) ({ np -> PlaybackState.Buffering(np, BufferingCause.RECOVERING, null) }) else null
        // (Ladder fix 2026-11-11) A changed video decoder mode needs a new codec: a buffered re-prepare stops the player
        // first.
        launchSession(c, Plan(reuseResolved = c.resolved != null, freshDecoders = videoModeChanged(c)), waiting)
    }

    /** The effective video decoder mode differs from the one the current codec was created under (applySwitches). */
    private fun videoModeChanged(c: Cycle): Boolean = eng?.bundle?.codecPolicy?.let { it.videoMode != effectiveVideoMode(c) } ?: false

    /**
     * (M5, §2.12 item 6, §3.3.4) The external-player hand-off: the resolved address and headers of the current tune, then
     * our stream stops first (the provider's connection limit). Null when nothing is resolved (nothing tuned, a direct
     * tune still resolving). Never logged with its URL ([ExternalHandOff.toString] keeps the host only).
     */
    override fun handOffExternal(): ExternalHandOff? {
        val handOff = peekExternalHandOff() ?: return null
        stop(StopReason.USER)
        BoxLog.i(TAG) { "handed off to an external player: $handOff" }
        return handOff
    }

    override fun peekExternalHandOff(): ExternalHandOff? {
        val c = cycle ?: return null
        val resolved = c.resolved ?: return null
        return ExternalHandOff(url = resolved.url, headers = resolved.headers, title = c.title)
    }

    private val _servers = MutableStateFlow(ServerList())
    override val servers: StateFlow<ServerList> = _servers.asStateFlow()

    /** (M5 integration, §3.6.2) The Sources picker: the same cycle, re-resolved on base [index]; the guard still applies. */
    override fun selectServer(index: Int) {
        val c = cycle ?: return
        // (Fix pass P2) An archive request is a fixed URL on one base: there is nothing to switch to.
        if (c.isArchive) return
        val count = c.resolved?.baseCount ?: return
        if (index !in 0 until count || index == c.resolved?.baseIndex) return
        if (c.identity != null && refused(c.identity, c.isArchive)) return
        c.baseIndex = index
        // (M6, §7.6) Buffered: the recorder restarts on the chosen base; the reader (and a time-shifted viewer) is untouched.
        // (Fix pass B2) Before the first byte too: the writer restarts in its own session (the reader still waits at the
        // tail), never a second session on the same grant, which left the generation ended.
        if (c.ts != null) {
            restartWriterScoped(c, delayMs = 0, refreshAccount = false, reuseResolved = false, waiting = false)
            return
        }
        launchSession(c, Plan(), waiting = null)
    }

    override fun retry() {
        val c = cycle ?: return
        if (c.identity != null && refused(c.identity, c.isArchive)) return
        // §4.3 "Retry from the card": fresh counters (new cycle) and a fresh re-resolve window for the channel.
        limiter.reset(limiterKey(c))
        startCycle(c.specForRestart())
    }

    private fun cancelPendingZap() {
        debouncer.reset()
        // (code review 2026-10-14, E-S1) A neighbour() lookup still in flight belongs to the zap being cancelled.
        zapGeneration++
        if (zapLookups.isNotEmpty()) {
            zapLookups.toList().forEach { it.cancel() }
            zapLookups.clear()
        }
        zapFireJob?.cancel()
        zapFireJob = null
        _pendingZap.value = null
    }

    /**
     * (code review 2026-10-14, N-S6) The re-resolve limiter key (§4.5): the channel, or for a direct URL its broker key
     * (`d:<host>`), so direct tunes on different hosts never share one window.
     */
    private fun limiterKey(c: Cycle): ChannelIdentity? = ReResolveKeys.of(
        c.identity,
        c.resolved?.brokerKey ?: c.directTarget?.url?.let { url ->
            FormatHints.host(url).takeIf { it.isNotEmpty() }?.let { ResolveConstants.DIRECT_BROKER_PREFIX + it }
        },
    )

    // ------------------------------------------------------------------ pipeline (§3.1.1)

    private fun startCycle(spec: Spec, refreshAccount: Boolean = false) {
        val now = clock.nowMs()
        val identity = when (spec) {
            is Spec.Live -> (spec.target as? TuneTarget.Channel)?.identity
            is Spec.Archive -> spec.tune.identity
        }
        cycle?.let { old ->
            // (M6, §7.2, A-S1) The old cycle's recorder: call cancelled and lease released before anything new opens. A
            // zap keeps the file for a logical reset; a catch-up request ends the ring for good (§8.3).
            closeTimeshift(old, discardFile = spec is Spec.Archive)
            finishZapRecord(old, "superseded")
            if (old.identity != null && old.identity != identity && old.firstMediaReached &&
                now - old.firstMediaAtMs >= PREVIOUS_CHANNEL_MIN_PLAY_MS
            ) {
                previousIdentity = old.identity
            }
        }
        cancelCycleJobs()
        val c = Cycle(spec, identity, now)
        c.cycleId = ++cycleSeq
        cycle = c
        // (follow-up 2026-10-14, half of Later N-L2) Per (playlist, stable key): the same stable key in another playlist
        // is another channel and must not share the 5-min escalation windows.
        ladder.beginChannel(identity?.let { "${it.playlistId}/${it.stableKey.value}" })
        importGate.closeForZap()
        // The first-frame counter survives the tune: the shell waits for "the next one" (H-S10).
        _video.value = VideoInfo(aspectMode = settings.current.defaultAspect, renderedFirstFrames = _video.value.renderedFirstFrames)
        _tracks.value = TrackInfo()
        if (leftAppPending) {
            leftAppPending = false
            _notices.tryEmit(PlaybackNotice.LeftAppCleared)
        }
        publishLocalBuffer(force = true)
        zapDecoders.begin(c.cycleId)
        launchSession(c, Plan(refreshAccount = refreshAccount), waiting = null)
        c.firstTuneId = c.currentTuneId
        eventLog.add(PlaybackEventType.TUNE, c.currentTuneId, if (c.isArchive) "archive" else "live", identity?.stableKey?.value ?: "direct")
        traceBegin(c.firstTuneId)
        c.switchDeadlineAtMs = c.startedAtMs + settings.current.streamSwitchTimeoutMs
        armSwitchTimeout(c)
    }

    private fun armSwitchTimeout(c: Cycle) {
        switchTimeoutJob?.cancel()
        switchTimeoutJob = scope.launch {
            delay((c.switchDeadlineAtMs - clock.nowMs()).coerceAtLeast(0L))
            onSwitchTimeout(c)
        }
    }

    /**
     * Device finding 2026-10-17 (Sony Bravia 4K, MediaTek HEVC, tunneling on): the decoder-stuck verdict came at 14.1 s,
     * the ladder's DisableTunnelingAndReprepare restarted the session, and the 15 s switch deadline failed the cycle
     * 0.6 s later, so the repair never ran and the user retried into the same tunneled failure. A repair that changes
     * the decoder pipeline is a new attempt: once per cycle it gets its own switch window. Plain re-prepares, retries
     * and re-resolves do not (they would loop), so a cycle still ends within two windows.
     */
    private fun extendSwitchWindow(c: Cycle) {
        if (c.firstMediaReached || c.switchExtended) return
        c.switchExtended = true
        c.switchDeadlineAtMs = clock.nowMs() + settings.current.streamSwitchTimeoutMs
        armSwitchTimeout(c)
        BoxLog.i(TAG) { "switch window extended once for a pipeline repair" }
    }

    /**
     * Ends the current session (transport first: the old call is cancelled before anything new opens, §3.5.2), shows
     * [waiting] (default Connecting(RESOLVING)) and runs steps 3–7 of §3.1.1 for a new session.
     */
    private fun launchSession(c: Cycle, planIn: Plan, waiting: ((NowPlaying) -> PlaybackState)?) {
        sessionJob?.cancel()
        // (M6, §7.6) In a buffered cycle every other re-open is reader-only, at the pin (a time-shifted viewer keeps
        // their place); writer restarts never come through here.
        val plan = if (c.ts != null && planIn.reader == null) planIn.copy(reader = pinStart(c)) else planIn
        val reuse = if (plan.reuseResolved) c.resolved else null
        // (M6, §6 step 3) A reader-only re-prepare holds the last frame: no player.stop() between the two prepares.
        val readerOnly = plan.reader != null && c.ts != null
        endSession(stopPlayer = !readerOnly)
        if (readerOnly && plan.freshDecoders) {
            // (Ladder fix 2026-11-11) ...unless the decision needs new decoders. Without stop() Media3 disables
            // the renderers without a reset and reuses the codecs (ExoPlayerImplInternal.prepareInternal and
            // seekToPeriodPosition: resetRenderers = false), so the selector is never asked again. stop() →
            // stopInternal(forceResetRenderers = false) → resetInternal(resetRenderers = !foregroundMode); foreground
            // mode is never on, so every renderer is reset and MediaCodecRenderer.onReset releases the codec. Only the
            // player stops: the ring, the recorder and its connection, the cycle and the pin (in `plan.reader`, taken
            // above) are untouched, and the reader re-opens at the same RAP with playWhenReady = !paused.
            // Through stopExo() like every other stop; its WP-0 note ignores a stop after the zap's first prepare.
            stopExo()
            BoxLog.i(TAG) { "buffered re-prepare: player stopped first for new decoders" }
        }
        val id = ++lastTuneId
        c.currentTuneId = id
        val np = nowPlaying(c)
        setActiveState(c, waiting?.invoke(np) ?: PlaybackState.Connecting(np, ConnectPhase.RESOLVING, c.attempt, clock.nowMs()))
        sessionJob = scope.launch {
            try {
                runSession(c, id, plan, reuse)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.e(TAG, "session $id failed to start", e)
                if (cycle === c && c.currentTuneId == id) fail(c, PlaybackError(PlaybackErrorCode.INTERNAL, ErrorDetail(signal = e.javaClass.simpleName)), null)
            }
        }
    }

    private suspend fun runSession(c: Cycle, id: Long, plan: Plan, reuse: ResolvedStream?) {
        if (plan.delayMs > 0) delay(clipDelay(c, plan.delayMs))
        val e = ensureEngine()
        val readerStart = plan.reader
        if (readerStart != null && c.ts != null) {
            runReaderSession(e, c, id, readerStart, plan.freshDecoders)
            return
        }
        // (M6, §7.1, A-C1, A-N5) Storage is prepared on IO in parallel with the facts and the resolve. Setting Off: no
        // extra work at all (the zap gate).
        if (!c.tsDecided && c.tsPrepare == null && c.spec is Spec.Live) {
            val prefs = settings.current
            if (timeshift === NoTimeshiftFactory || prefs.timeshift == app.thebox.core.settings.TimeshiftLength.OFF) {
                c.tsDecided = true
                c.tsOff = BufferOff.SETTING_OFF
                // (Integration 2026-10-19, I-TS-20a/30) The tune's first publish (in tune()) had no reason yet, and the
                // post-resolve publish is skipped for a decided cycle: publish the Off reason now, so the machine shows
                // PAUSE_NO_REWIND_OFF instead of the unknown-state plain pause.
                publishLocalBuffer(force = true)
                launchPrepareForOff(prefs)
            } else {
                val max = maxConnections(c)
                c.tsPrepare = PendingTimeshiftPreparation(timeshift, scope, ioDispatcher, prefs, max)
            }
        }
        // O-3: a plain first session loads its facts and resolves in one IO hop; the main thread only applies results.
        var early: ResolveOutcome? = null
        if (!c.factsLoaded && reuse == null && !plan.refreshAccount) {
            val identity = c.identity
            val request = resolveRequest(c)
            val (facts, outcome) = withContext(ioDispatcher) { (identity?.let { readFacts(it) }) to resolveOutcome(request) }
            facts?.let { applyFacts(c, it) }
            c.factsLoaded = true
            early = outcome
        }
        if (!c.factsLoaded) loadFacts(c)
        if (!current(c, id)) return
        if (plan.refreshAccount) {
            refreshAccount(c)
            if (!current(c, id)) return
            // (integration 2026-10-13, device I-17) The §4.3 G1 gate with the fresh account, before another open the
            // panel is known to refuse: an EXPIRED/DISABLED/AUTH_FAILED account fails now instead of after one more 403.
            val last = c.lastSignal
            if (last != null && c.accountGate != AccountGate.ACTIVE && c.accountGate != AccountGate.UNKNOWN) {
                val decision = ladder.decide(last, ladderContext(c, reResolveAllowed = false)).first
                if (decision is LadderDecision.Fail) {
                    eventLog.add(PlaybackEventType.DECISION, id, decisionName(decision), "account ${c.accountGate.name}")
                    fail(c, decision.error, last)
                    return
                }
            }
        }
        if (!current(c, id)) return

        // Step 3: resolve (never reuse a resolved URL across a user request; only RetrySame/Reprepare reuse it).
        val resolved = reuse ?: (if (early != null) handleResolveOutcome(c, early) else resolve(c)) ?: return
        if (!current(c, id)) return
        c.resolved = resolved
        // (M5 integration, E23) The Sources picker's servers for this tune. (Fix pass P2) None for archive: the request's
        // URL is expanded on one fixed base, so a picked server would restart on the same host.
        _servers.value = if (!c.isArchive && resolved.baseHosts.size > 1) ServerList(resolved.baseHosts, resolved.baseIndex) else ServerList()
        if (resolved.droppedHeaders.isNotEmpty()) {
            // (code review 2026-10-14, N-S1) Names only; the event log redacts anyway.
            eventLog.add(PlaybackEventType.RESOLVED, id, "headers_dropped", resolved.droppedHeaders.joinToString(","))
            BoxLog.w(TAG, "invalid request headers dropped: ${resolved.droppedHeaders.joinToString(",")}")
        }
        val now = clock.nowMs()
        if (c.zap.resolveMs == null) c.zap.resolveMs = now - c.startedAtMs
        eventLog.add(PlaybackEventType.RESOLVED, id, resolved.formatHint.name, resolved.host)

        // Step 4: broker.
        brokers.configure(resolved.brokerKey, maxConnections(c))

        val format = c.format ?: resolved.formatHint.takeUnless { it == StreamFormat.UNKNOWN } ?: StreamFormat.TS
        if (format == StreamFormat.DASH) {
            fail(c, PlaybackError(PlaybackErrorCode.UNSUPPORTED_SOURCE, ErrorDetail(host = resolved.host, signal = "DASH")), null)
            return
        }

        // Step 5: memory and runtime switches.
        applySwitches(e, c, resolved)

        // (M6, §7.1) A live TS cycle with a storage grant gets its time-shift session now (main, right after the resolve).
        if (!c.tsDecided || (c.tsGrant != null && c.ts == null)) {
            decideTimeshift(c, format)
            if (!current(c, id)) return
            if (c.tsGrant != null && c.ts == null) startTimeshift(c, resolved) else publishLocalBuffer(force = true)
        }
        val ts = c.ts
        val ring = if (ts != null) {
            c.readerOrigin = -1L
            c.readerStartOffset = ts.ring.tail
            // S7: the cycle's first reader opens at the generation's first RAP (position 0 = the origin's PTS).
            RingOpen(ts.ring, ts.ring.generation, ts.ring.tail, ts.readerTelemetry { null }, firstRap = true)
        } else null

        // Step 6: session and MediaSource.
        val ctx = StreamSessionContext(
            tuneId = id,
            resolved = resolved,
            broker = brokers.broker(resolved.brokerKey),
            leaseKind = LeaseKind.PLAYBACK,
            // (diagnosis 2026-10-18) Debug capture of the raw TS; null in release (TsDebugOverrides is debug-set only).
            sinkFactory = app.thebox.player.engine.TsDebugOverrides.captureDir?.let { dir ->
                androidx.media3.datasource.DataSink.Factory { app.thebox.player.datasource.FileCaptureSink(dir, id) }
            },
            onEvent = { type, code, detail -> mainHandler.post { onSessionEvent(id, type, code, detail) } },
            repairAudioTimestamps = c.audioTsRepair,
        )
        val s = TuneSession(id, resolved, ctx, transport, format, clock.nowMs())
        s.ringGeneration = ring?.generation ?: -1L
        val built = try {
            mediaSources.build(ctx, format, if (c.tsCompat > 0) TsCompat.MULTI_PMT else TsCompat.DEFAULT, transport, c.archive?.request, ring)
        } catch (ex: UnsupportedStreamFormatException) {
            val what = ex.scheme?.let { "scheme $it" } ?: ex.format.name
            BoxLog.w(TAG, "media source $id not built: unsupported $what")
            eventLog.add(PlaybackEventType.ERROR, id, "unsupported_source", what)
            s.end()
            fail(c, PlaybackError(PlaybackErrorCode.UNSUPPORTED_SOURCE, ErrorDetail(host = resolved.host, signal = what)), null)
            return
        } catch (ex: Exception) {
            BoxLog.w(TAG, "media source $id not built: ${ex.javaClass.simpleName}")
            s.end()
            // (code review 2026-10-14, Later E-4) Anything but UnsupportedStreamFormatException is a bug, not the source.
            fail(c, PlaybackError(PlaybackErrorCode.INTERNAL, ErrorDetail(host = resolved.host, signal = ex.javaClass.simpleName)), null)
            return
        }
        s.telemetry = built.telemetry
        session = s

        // Step 7: prepare and play.
        val p = e.bundle.player
        val archive = c.archive
        // (M4 integration 2026-10-15) A later session of an archive cycle (a ladder re-prepare: L32 software audio, a
        // stall, a retry) continues at the playhead; `stop()` keeps the position, `setMediaSource(resetPosition)` drops it.
        // Without this the file restarted at 0 and a 20 s time-shift fell minutes behind (I-M4-13 on the device).
        val startAtMs = if (archive == null) 0L else if (c.firstSession) archive.startOffsetMs else p.currentPosition.coerceAtLeast(0)
        p.setMediaSource(built.source, /* resetPosition = */ true)
        p.prepare()
        zapDecoders.onPrepare(clock.nowMs())
        if (archive != null && startAtMs > 0) p.seekTo(startAtMs)
        c.firstSession = false
        e.parts.watchdog.bind(built.telemetry)
        e.parts.watchdog.reset()
        // WP-C: per session beginSession + setSessionKind + bind (watchdog), bind (stats), fps estimator reset.
        e.parts.beginSession(built.telemetry, format, isLive = !c.isArchive, isHls = format == StreamFormat.HLS)
        // Stopped in endSession() while a ladder decision runs (§3.7 global guard); started again for the new session.
        e.parts.watchdog.start { verdict -> onStall(verdict) }
        updateExpectations()
        if (!c.firstMediaReached && _state.value is PlaybackState.Connecting) {
            setActiveState(c, PlaybackState.Connecting(nowPlaying(c), ConnectPhase.CONNECTING, c.attempt, clock.nowMs()))
        }
        eventLog.add(PlaybackEventType.OPEN, id, format.name, resolved.host)
        e.parts.displayMode.awaitSettled {
            if (session === s) {
                settledSession = s
                p.playWhenReady = !c.paused
            }
        }
        startPolling()
    }

    private fun current(c: Cycle, id: Long) = cycle === c && c.currentTuneId == id

    private suspend fun loadFacts(c: Cycle) {
        c.identity?.let { id -> readFacts(id)?.let { applyFacts(c, it) } }
        c.factsLoaded = true
    }

    /** The cycle's facts, read on any thread (no cycle state is touched until [applyFacts] on main). */
    private class Facts(
        val channelInfo: ChannelStreamInfo?,
        val playlistInfo: PlaylistPlaybackInfo?,
        val account: XtreamAccountInfo?,
        val memory: ChannelPlaybackMemory,
        val backups: List<ChannelIdentity>,
    )

    private suspend fun readFacts(id: ChannelIdentity): Facts? = try {
        val failover = settings.current.failoverToBackup
        Facts(
            channelInfo = provider.channel(id),
            playlistInfo = provider.playlist(id.playlistId),
            account = provider.account(id.playlistId),
            memory = memory.read(id),
            backups = if (failover) provider.backupSources(id) else emptyList(),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        BoxLog.w(TAG, "playback facts unavailable: ${e.javaClass.simpleName}")
        null
    }

    private fun applyFacts(c: Cycle, f: Facts) {
        c.channelInfo = f.channelInfo
        c.title = f.channelInfo?.name
        c.playlistInfo = f.playlistInfo
        c.account = f.account
        c.accountGate = gateOf(f.account)
        c.memory = f.memory
        c.tsCompat = f.memory.tsCompatLevel
        if (f.memory.tunnelingOff) c.tunnelingOff = true
        c.backups = f.backups
    }

    private suspend fun refreshAccount(c: Cycle) {
        val id = c.identity ?: return
        try {
            when (val r = provider.refreshAccount(id.playlistId)) {
                is Outcome.Ok -> {
                    c.account = r.value
                    c.accountGate = gateOf(r.value)
                }
                is Outcome.Err -> {
                    val err = r.error
                    if (err is SourceError.Auth) c.accountGate = when (err.reason) {
                        AuthReason.BAD_CREDENTIALS -> AccountGate.AUTH_FAILED
                        AuthReason.EXPIRED -> AccountGate.EXPIRED
                        AuthReason.BANNED, AuthReason.DISABLED -> AccountGate.DISABLED
                        AuthReason.UNKNOWN -> AccountGate.UNKNOWN
                    }
                    BoxLog.i(TAG) { "account refresh failed: ${err.code}" }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BoxLog.w(TAG, "account refresh unavailable: ${e.javaClass.simpleName}")
        }
    }

    /** What [resolveOutcome] needs from the cycle, captured on main. */
    private class ResolveRequest(val spec: Spec, val baseIndex: Int?, val output: XtreamOutput?, val userAgent: String?)

    private fun resolveRequest(c: Cycle) = ResolveRequest(c.spec, c.baseIndex, c.output, c.userAgentOverride)

    private suspend fun resolveOutcome(r: ResolveRequest): ResolveOutcome = try {
        when (val spec = r.spec) {
            is Spec.Archive -> resolver.expandArchive(spec.tune.identity, spec.tune.request)
            is Spec.Live -> when (val t = spec.target) {
                is TuneTarget.Channel -> resolver.resolve(TuneTargetKey.Channel(t.identity), r.baseIndex, r.output, r.userAgent)
                is TuneTarget.DirectUrl -> resolver.resolve(
                    TuneTargetKey.Direct(t.url, t.headers, t.maxConnections, t.formatHint), null, null, r.userAgent,
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ResolveOutcome.Failed(PlaybackErrorCode.INTERNAL, e.javaClass.simpleName)
    }

    private suspend fun resolve(c: Cycle): ResolvedStream? = handleResolveOutcome(c, resolveOutcome(resolveRequest(c)))

    private fun handleResolveOutcome(c: Cycle, outcome: ResolveOutcome): ResolvedStream? {
        return when (outcome) {
            is ResolveOutcome.Ok -> outcome.stream
            is ResolveOutcome.Failed -> {
                BoxLog.i(TAG) { "resolve failed: ${outcome.code.key}" }
                val signal = when (outcome.code) {
                    PlaybackErrorCode.CHANNEL_NOT_FOUND -> FailureSignal.ChannelMissing
                    PlaybackErrorCode.NEEDS_REAUTH -> FailureSignal.Credentials(permanent = true)
                    PlaybackErrorCode.CREDENTIALS_UNAVAILABLE -> FailureSignal.Credentials(permanent = false)
                    else -> null
                }
                if (signal != null) onFailure(c, signal, null)
                else fail(c, PlaybackError(outcome.code, ErrorDetail(signal = "Resolve")), null)
                null
            }
        }
    }

    /** §3.5.1 via WP-A's rule: direct URLs use their own value (broker `d:<host>`), playlists override → account → 1. */
    private fun maxConnections(c: Cycle): Int {
        c.directTarget?.let { return BrokerConstants.clamp(it.maxConnections) }
        return BrokerConstants.effectiveMax(c.playlistInfo?.maxConnectionsOverride, c.account?.maxConnections)
    }

    /** §3.1.1 step 5: runtime switches read by the long-lived engine components (no player rebuild). */
    private fun applySwitches(e: Engine, c: Cycle, resolved: ResolvedStream) {
        val b = e.bundle
        val s = settings.current
        // WP-C: settings-derived switches first, then the union of what the ladder learned (applies at the next prepare).
        engine.applySettings(b, s)
        b.codecPolicy.videoMode = effectiveVideoMode(c)
        b.codecPolicy.softwareAudioMimes = b.codecPolicy.softwareAudioMimes + c.softwareAudioMimes
        val denied = b.audioPolicy.deniedPassthrough + routeDeniedPassthrough
        b.audioPolicy.deniedPassthrough = if (s.audioOutput == AudioOutputMode.ALWAYS_DECODE) denied + "*" else denied
        b.audioPolicy.forceStereo = b.audioPolicy.forceStereo || c.forceStereo || s.audioOutput == AudioOutputMode.STEREO
        val mode = if (c.isArchive) BufferMode.MEDIUM else c.memory.bufferMode ?: s.bufferMode
        val profile = bufferFsm.profileFor(resolved.brokerKey, mode, engine.isLowRam)
        b.loadControl.profile = profile
        c.profile = profile
        b.trackSelector.setParameters(b.trackSelector.buildUponParameters().setTunnelingEnabled(s.tunneling && !c.tunnelingOff))

        val p = b.player
        if (c.firstSession) {
            // §3.8.6 / §3.12 defaults and per-channel memory, once per cycle (WP-C TrackController.applyTuneDefaults).
            val audioLangs = (listOfNotNull(c.memory.audioLanguage) + PlayerFactory.preferredAudioLanguages(s)).distinct().take(3)
            e.parts.applyTuneDefaults(audioLangs, s.preferredTextLanguages.take(3), c.memory.subtitleLanguage, s.subtitlesEnabled)
        }
        val params = p.trackSelectionParameters.buildUpon()
        if (c.firstSession) {
            applyAspect(c.memory.aspect ?: s.defaultAspect)
            c.fpsMilli = c.memory.fpsMilli
            e.parts.displayMode.onTune(c.memory.fpsMilli)
        }
        params.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, c.audioDropped)
        params.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, videoShouldBeDisabled(c))
        p.trackSelectionParameters = params.build()
        _video.update { it.copy(posterMode = posterMode(c), decoderOverride = channelDecoderOverride(c)) }
    }

    private fun effectiveVideoMode(c: Cycle): VideoDecoderMode =
        if (c.softwareVideo) VideoDecoderMode.SOFTWARE_PREFERRED else channelDecoderOverride(c) ?: settings.current.videoDecoderMode

    /** (M4, §2.9 `VideoInfo.decoderOverride`) The channel's own choice (picker or memory); null = the global setting. */
    private fun channelDecoderOverride(c: Cycle): VideoDecoderMode? = c.decoderOverride ?: c.memory.videoDecoder

    /**
     * Ends the transport of the current session, then stops the player (keeps the last frame; never clears the surface).
     * (M6, §6 step 3) [stopPlayer] = false for a ring reader re-prepare: the next `setMediaSource` + `prepare` replaces the
     * source with no `stop()` in between, so the last frame stays.
     */
    private fun endSession(stopPlayer: Boolean = true) {
        if (_cues.value.isNotEmpty()) _cues.value = emptyList()
        val s = session ?: return
        session = null
        settledSession = null
        eng?.parts?.watchdog?.stop()
        s.end()
        if (stopPlayer) stopExo()
    }

    /** `player.stop()`, noted for the zap's decoder figures (WP-0; no behaviour of its own). */
    private fun stopExo() {
        val p = player ?: return
        zapDecoders.onStop(clock.nowMs())
        p.stop()
    }

    private fun clipDelay(c: Cycle, delayMs: Long): Long {
        // (M4) Behind the unlimited-retry card the switch timeout has already passed: clipping to it would turn L19's
        // UNLIMITED_RETRY_INTERVAL_MS into back-to-back reconnects.
        if (c.firstMediaReached || c.cardShown) return delayMs
        val left = c.switchDeadlineAtMs - clock.nowMs()
        return delayMs.coerceAtMost(left.coerceAtLeast(0))
    }

    // ------------------------------------------------------------------ engine

    private fun ensureEngine(): Engine {
        eng?.let { return it }
        check(Looper.myLooper() == Looper.getMainLooper()) { "PlaybackController is main-thread only" }
        val bundle = playerFactory.create(Looper.getMainLooper(), settings.current.softwareAudioFallback)
        val parts = engine.createParts(bundle)
        val surface = SurfacePlayer(
            bundle.player,
            onSurfaceView = { view -> parts.displayMode.attachSurfaceView(view) },
            onSurfaceValid = { valid -> onSurfaceValidChanged(valid) },
        )
        bundle.player.addListener(listener)
        bundle.player.addAnalyticsListener(listener)
        val handle = try {
            sessionFactory.create(context, bundle.player, sessionActions)
        } catch (e: Exception) {
            BoxLog.w(TAG, "media session unavailable: ${e.javaClass.simpleName}")
            null
        }
        val created = Engine(bundle, parts, surface, handle)
        eng = created
        scope.launch { parts.displayMode.request.collect { _displayModeRequest.value = it } }
        scope.launch { parts.fps.collect { onFps(it) } }
        parts.setFirstMediaListener { video ->
            session?.let { s ->
                if (s.milestoneVideo == null) s.milestoneVideo = video
                onFirstMedia(s)
            }
        }
        parts.setDisplaySwitchListener(onStarted = ::onDisplaySwitchStarted, onSettled = ::onDisplaySwitchSettled)
        parts.setStatsEnabled(statsEnabled)
        // (code review 2026-10-14, E-S2) The display-mode controller starts at Viewport.NONE and setViewport() ignores an
        // unchanged value, so the current viewport (FULL by default) must be pushed once, or AFR ALWAYS never evaluates.
        parts.displayMode.onViewport(viewport)
        scope.launch {
            settings.settings.map { it.afrMode }.distinctUntilChanged().drop(1).collect { parts.onAfrModeChanged(it) }
        }
        try {
            engine.observeAudioRoute {
                if (routeDeniedPassthrough.isNotEmpty()) BoxLog.i(TAG) { "audio route changed: passthrough denials cleared" }
                routeDeniedPassthrough.clear()
            }
        } catch (e: Exception) {
            BoxLog.w(TAG, "audio route observer unavailable: ${e.javaClass.simpleName}")
        }
        try {
            lifecycle.start()
        } catch (e: Exception) {
            BoxLog.w(TAG, "lifecycle observers unavailable: ${e.javaClass.simpleName}")
        }
        BoxLog.i(TAG) { "player created" }
        return created
    }

    override fun prewarm() {
        ensureEngine()
    }

    private val sessionActions = object : SessionPlayer.Actions {
        override fun isForeground() = lifecycle.isForeground
        override fun isArchive() = cycle?.isArchive == true
        override fun title() = cycle?.title
        override fun play() = resume()
        override fun pause() = this@PlaybackControllerImpl.pause()
        override fun stop() = this@PlaybackControllerImpl.stop(StopReason.USER)
        override fun next() = zap(+1)
        override fun previous() = zap(-1)
        override fun seekTo(positionMs: Long) = this@PlaybackControllerImpl.seekTo(positionMs)
    }

    // ------------------------------------------------------------------ player events

    private val listener = object : Player.Listener, AnalyticsListener {
        override fun onPlaybackStateChanged(playbackState: Int) = onPlayerState(playbackState)

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady && player?.playbackState == Player.STATE_READY) onPlayerState(Player.STATE_READY)
        }

        override fun onPlayerError(error: PlaybackException) = onPlayerFailure(error)

        override fun onRenderedFirstFrame() {
            _video.update { it.copy(renderedFirstFrames = it.renderedFirstFrames + 1) }
            val s = session ?: return
            if (s.milestoneVideo != false) onFirstMedia(s)
        }

        override fun onTracksChanged(tracks: Tracks) = onTracks(tracks)

        /** (M4, §3.6.3) The non-deprecated form only (API notes §1.2). Nothing is published between sessions. */
        override fun onCues(cueGroup: CueGroup) {
            if (session == null) return
            _cues.value = cueGroup.cues
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width == 0 && videoSize.height == 0) return
            _video.update { it.copy(width = videoSize.width, height = videoSize.height, pixelRatio = videoSize.pixelWidthHeightRatio) }
        }

        override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
            val s = session ?: return
            s.audioAdvancingSeen = true
            if (s.milestoneVideo == false) onFirstMedia(s)
        }

        // (Keep-decoders WP-0) Decoder cost per zap; one event per codec init or release, never per frame.
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) =
            zapDecoders.onVideoDecoderInitialized(decoderName, initializationDurationMs)

        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) =
            zapDecoders.onAudioDecoderInitialized(decoderName, initializationDurationMs)

        override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) =
            zapDecoders.onVideoDecoderReleased(clock.nowMs())

        // (WP-0 review S1) The channel kind of the zap: MIME, height, frame rate, channel count; no name, no URL.
        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) =
            zapDecoders.onVideoFormat(format.sampleMimeType, format.height, format.frameRate)

        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) =
            zapDecoders.onAudioFormat(format.sampleMimeType, format.channelCount)

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: java.io.IOException,
            wasCanceled: Boolean,
        ) {
            session?.lastLoadDataType = mediaLoadData.dataType
        }
    }

    private fun onPlayerState(playbackState: Int) {
        val c = cycle ?: return
        val s = session ?: return
        val now = clock.nowMs()
        val st = _state.value
        when (playbackState) {
            Player.STATE_READY -> {
                if (s.readyAtMs == 0L) {
                    s.readyAtMs = now
                    if (c.zap.readyMs == null) c.zap.readyMs = now - c.startedAtMs
                    eventLog.add(PlaybackEventType.READY, s.tuneId, "", "")
                }
                if (st is PlaybackState.Error && !c.cardShown && !(c.cardLiftPending && s.firstMedia)) return
                c.cardLiftPending = false
                if (player?.playWhenReady == true || c.paused) {
                    setState(PlaybackState.Playing(nowPlaying(c), paused = c.paused, warning = c.warning))
                } else {
                    setActiveState(c, PlaybackState.Connecting(nowPlaying(c), ConnectPhase.SWITCHING_DISPLAY_MODE, c.attempt, now))
                }
            }
            Player.STATE_BUFFERING -> {
                if (st !is PlaybackState.Playing || st.paused) return
                val np = nowPlaying(c)
                if (s.telemetry?.inReconnect == true) {
                    setState(PlaybackState.Buffering(np, BufferingCause.RECONNECTING, null))
                    return
                }
                if (s.firstMedia && now - s.lastDisruptionAtMs >= SEEK_GRACE_MS) onRebuffer(c, s)
                setState(PlaybackState.Buffering(np, BufferingCause.REBUFFER, null))
            }
            Player.STATE_ENDED -> {
                if (c.isArchive) {
                    // (M4, §2.9) `ended` tells the archive session the request ran out (continuation), not a user pause.
                    setState(PlaybackState.Playing(nowPlaying(c), paused = true, warning = c.warning, ended = true))
                } else {
                    onFailure(c, FailureSignal.LiveEnded, null)
                }
            }
            else -> Unit
        }
    }

    private fun onRebuffer(c: Cycle, s: TuneSession) {
        val now = clock.nowMs()
        c.lastRebufferAtMs = now
        c.rebufferCount++
        eventLog.add(PlaybackEventType.REBUFFER, s.tuneId, "", "")
        if (c.isArchive) return
        val mode = c.memory.bufferMode ?: settings.current.bufferMode
        val next = bufferFsm.onRebuffer(s.resolved.brokerKey, now, mode, engine.isLowRam) ?: return
        eng?.bundle?.loadControl?.profile = next
        c.profile = next
        eventLog.add(PlaybackEventType.BUFFER_LEVEL, s.tuneId, "L${next.level}", "start=${next.startMs} rebuffer=${next.afterRebufferMs}")
        _notices.tryEmit(PlaybackNotice.BufferIncreased(next.level))
    }

    private fun onPlayerFailure(error: PlaybackException) {
        val c = cycle ?: return
        val s = session ?: return
        // (M6, §5.3, §7.4) A buffered reader's ring exceptions are routed before the classifier's network rows and the
        // ladder: boundaries, the end of saving, a lost position and disk errors.
        if (c.ts != null) {
            val ringEx = FailureClassifier.ringException(error)
            if (ringEx != null) {
                if (error.timestampMs < s.startedAtMs) {
                    eventLog.add(PlaybackEventType.DECISION, s.tuneId, "ring_stale", ringEx.javaClass.simpleName)
                    return
                }
                if (ringEx is RingEndException && ringEx.stop == RecordStop.SESSION_END) {
                    // (Fix pass S3) Stale only when this reader's generation is not the current session's ring's. On
                    // the current generation the ring was closed under a live cycle (a discard racing prepare/start):
                    // a fresh cycle, never an indefinitely frozen picture.
                    val ts = c.ts
                    if (ts == null || s.ringGeneration != ts.ring.generation) {
                        eventLog.add(PlaybackEventType.DECISION, s.tuneId, "ring_stale", ringEx.javaClass.simpleName)
                        return
                    }
                    eventLog.add(PlaybackEventType.DECISION, s.tuneId, "ring_closed", "relaunch")
                    BoxLog.w(TAG, "time-shift ring closed under the current cycle; relaunching")
                    startCycle(c.specForRestart())
                    return
                }
                onReaderRingException(c, s, ringEx)
                return
            }
        }
        val stats = safeStats()
        val facts = FailureClassifier.Facts(
            currentTuneId = s.tuneId,
            sessionStartMs = s.startedAtMs,
            isArchive = c.isArchive,
            isHls = s.format == StreamFormat.HLS,
            lastLoadDataType = s.lastLoadDataType,
            bytesReceived = s.telemetry?.bytesTotal ?: 0,
            passthroughActive = stats.audioPassthrough,
            writerTuneId = c.ts?.writerTuneId,
        )
        val signal = classifier.classify(error, facts)
        if (signal == null) {
            eventLog.add(PlaybackEventType.ERROR, s.tuneId, "stale", error.errorCodeName)
            BoxLog.d(TAG) { "stale error ignored: ${error.errorCodeName}" }
            return
        }
        onFailure(c, signal, error)
    }

    private fun onStall(verdict: StallVerdict) {
        val c = cycle ?: return
        val s = session ?: return
        eventLog.add(PlaybackEventType.STALL, s.tuneId, verdict.kind.name, verdict.evidence)
        // (M6, §5.2, §7.4) Detector (a) at the head of a buffered cycle is the recorder's silence: writer-scoped.
        val fromWriter = c.ts != null && verdict.kind == StallKind.NO_BYTES
        onFailure(c, FailureSignal.Stall(verdict.kind), null, fromWriter)
    }

    private fun onTracks(tracks: Tracks) {
        val c = cycle ?: return
        val s = session ?: return
        if (tracks.isEmpty) return
        _tracks.value = try {
            eng?.parts?.tracks(tracks) ?: TrackInfo()
        } catch (e: Exception) {
            TrackInfo()
        }
        val videoFormat = selectedFormat(tracks, C.TRACK_TYPE_VIDEO)
        if (videoFormat != null) {
            val hdr = hdrOf(videoFormat)
            if (c.hdr != hdr) {
                c.hdr = hdr
                _video.update { it.copy(hdr = hdr) }
                val known = hdr != HdrKind.SDR
                if (c.identity != null && c.memory.knownHdr != known) {
                    c.memory = c.memory.copy(knownHdr = known)
                    remember(c.identity) { it.copy(knownHdr = known) }
                }
                applyVideoTrackState()
            }
        }
        if (s.milestoneVideo == null) {
            val video = tracks.isTypeSelected(C.TRACK_TYPE_VIDEO) && !videoShouldBeDisabled(c)
            s.milestoneVideo = video
            if (!video && s.audioAdvancingSeen) onFirstMedia(s)
        }
        updateExpectations()
    }

    /** §0.16 first media: first video frame when video is selected and enabled, else first audio position advance. */
    private fun onFirstMedia(s: TuneSession) {
        val c = cycle ?: return
        if (s !== session || s.firstMedia) return
        s.firstMedia = true
        val now = clock.nowMs()
        eventLog.add(PlaybackEventType.FIRST_FRAME, s.tuneId, if (s.milestoneVideo == false) "audio" else "video", "")
        if (!c.firstMediaReached) {
            c.firstMediaReached = true
            c.firstMediaAtMs = now
            c.zap.firstMediaMs = now - c.startedAtMs
            // WP-B emits no FIRST_BYTE event; its telemetry stamps the first byte on the same monotonic clock. (M6) A
            // buffered cycle's first byte is the recorder's (the network), not the reader's.
            val firstByteAt = c.ts?.writerTelemetry?.firstByteAtMs?.takeIf { it > 0 } ?: s.telemetry?.firstByteAtMs ?: 0L
            c.ts?.let { ts -> measureLatencyAtTune(c, ts) }
            if (firstByteAt > 0L && firstByteAt >= c.startedAtMs) {
                val fromTelemetry = firstByteAt - c.startedAtMs
                c.zap.firstByteMs = c.zap.firstByteMs?.let { minOf(it, fromTelemetry) } ?: fromTelemetry
            }
            finishZapRecord(c, "ok")
            traceEnd(c.firstTuneId)
            importGate.releaseAfterFirstMedia()
            switchTimeoutJob?.cancel()
            c.identity?.let { id ->
                recordWatchedJob?.cancel()
                recordWatchedJob = scope.launch {
                    delay(RECORD_WATCHED_AFTER_MS)
                    if (cycle === c) try {
                        memory.recordWatched(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        BoxLog.w(TAG, "recordWatched failed: ${e.javaClass.simpleName}")
                    }
                }
            }
        }
        if (c.cardShown || c.cardLiftPending) {
            c.cardShown = false
            if (player?.playbackState == Player.STATE_READY) {
                c.cardLiftPending = false
                setState(PlaybackState.Playing(nowPlaying(c), c.paused, c.warning))
            } else {
                c.cardLiftPending = true
            }
        }
        scheduleHealthyReset(c)
        if (c.compatPending) scheduleCompatPersist(c, s)
        // Learned values are persisted and announced only once the new variant actually played (§4.3 L5/L9/L15).
        val id = c.identity
        c.flipPending?.let { to ->
            c.flipPending = null
            if (id != null && c.playlistInfo?.xtreamOutput == XtreamOutput.AUTO) {
                scope.launch { safeProvider { provider.saveLearnedOutput(id.playlistId, to) } }
            }
            _notices.tryEmit(PlaybackNotice.SwitchedFormat(if (to == XtreamOutput.HLS) StreamFormat.HLS else StreamFormat.TS))
        }
        val playlist = c.playlistInfo
        if (id != null && playlist != null && s.resolved.baseIndex != playlist.activeBaseIndex) {
            val index = s.resolved.baseIndex
            c.playlistInfo = playlist.copy(activeBaseIndex = index)
            scope.launch { safeProvider { provider.saveActiveBaseIndex(id.playlistId, index) } }
            _notices.tryEmit(PlaybackNotice.SwitchedToBackupServer(index))
        }
        if (c.uaFallbackPending) {
            c.uaFallbackPending = false
            _notices.tryEmit(PlaybackNotice.UserAgentSwitched(settings.current.userAgentFallbackPreset))
        }
        if (c.reconnectNoticeShown) {
            c.reconnectNoticeShown = false
            _notices.tryEmit(PlaybackNotice.Reconnected)
        }
    }

    /**
     * (follow-up 2026-10-14) Saves a pending compat raise after [COMPAT_PERSIST_HEALTHY_MS] at the raised level: same
     * session, Playing, no failure (stall verdicts included) and no rebuffer since the raised session's first media.
     */
    private fun scheduleCompatPersist(c: Cycle, s: TuneSession) {
        compatPersistJob?.cancel()
        val failures = c.failureCount
        val rebuffers = c.rebufferCount
        compatPersistJob = scope.launch {
            delay(COMPAT_PERSIST_HEALTHY_MS)
            val healthy = cycle === c && session === s && c.compatPending && c.tsCompat > 0 &&
                c.failureCount == failures && c.rebufferCount == rebuffers && _state.value is PlaybackState.Playing
            if (!healthy) return@launch
            c.compatPending = false
            eventLog.add(PlaybackEventType.DECISION, s.tuneId, "CompatPersisted", "level ${c.tsCompat}")
            c.identity?.let { idn -> remember(idn) { it.copy(tsCompatLevel = c.tsCompat) } }
        }
    }

    private fun scheduleHealthyReset(c: Cycle) {
        healthyJob?.cancel()
        healthyJob = scope.launch {
            delay(LADDER_HEALTHY_RESET_MS)
            if (cycle === c && clock.nowMs() - c.lastErrorAtMs >= LADDER_HEALTHY_RESET_MS) {
                c.counters = ErrorLadderImpl.countersAfterHealthy(c.counters, clock.nowMs() - maxOf(c.lastErrorAtMs, c.firstMediaAtMs))
                c.attempt = 1
                BoxLog.d(TAG) { "ladder counters reset after healthy playback" }
            }
        }
    }

    private fun onSessionEvent(id: Long, type: PlaybackEventType, code: String, detail: String) {
        eventLog.add(type, id, code, detail)
        val c = cycle ?: return
        if (session?.tuneId != id) return
        val since = clock.nowMs() - c.startedAtMs
        when (type) {
            PlaybackEventType.LEASE -> {
                if (c.zap.leaseMs == null) c.zap.leaseMs = since
                val st = _state.value
                if (st is PlaybackState.Connecting && st.phase == ConnectPhase.WAITING_FOR_SLOT) {
                    setActiveState(c, st.copy(phase = ConnectPhase.CONNECTING))
                }
            }
            PlaybackEventType.FIRST_BYTE -> {
                if (c.zap.firstByteMs == null) c.zap.firstByteMs = since
                val st = _state.value
                if (st is PlaybackState.Connecting && st.phase == ConnectPhase.CONNECTING) {
                    setActiveState(c, st.copy(phase = ConnectPhase.BUFFERING_INITIAL))
                }
            }
            // (device finding 2026-10-18) The TS guard's stuck-audio verdict is a failure signal for the ladder (L33t).
            PlaybackEventType.STALL -> if (code == app.thebox.player.engine.MediaSourceBuilder.STUCK_AUDIO_CODE) {
                BoxLog.i(TAG) { "audio timestamps stuck ($detail): asking the ladder" }
                onFailure(c, FailureSignal.AudioTimestampsStuck, null)
            }
            PlaybackEventType.RECONNECT -> {
                val st = _state.value
                if ((st is PlaybackState.Playing && !st.paused) || (st is PlaybackState.Buffering && st.cause == BufferingCause.REBUFFER)) {
                    setState(PlaybackState.Buffering(nowPlaying(c), BufferingCause.RECONNECTING, null))
                }
            }
            PlaybackEventType.RECONNECT_OK -> {
                // (integration 2026-10-13) The player usually never left READY during an in-source reconnect, so no
                // READY callback ends Buffering(RECONNECTING): end it here (and in pollTick, from the telemetry).
                val st = _state.value
                if (st is PlaybackState.Buffering && st.cause == BufferingCause.RECONNECTING && player?.playbackState == Player.STATE_READY && !c.paused) {
                    setState(PlaybackState.Playing(nowPlaying(c), paused = false, warning = c.warning))
                }
            }
            else -> Unit
        }
    }

    private fun onFps(fps: Float?) {
        if (fps == null) return
        _video.update { it.copy(fps = fps) }
        val c = cycle ?: return
        val milli = (fps * 1000f).roundToInt()
        if (c.fpsMilli != milli) {
            c.fpsMilli = milli
            c.memory = c.memory.copy(fpsMilli = milli)
            eng?.parts?.displayMode?.onFps(fps)
            c.identity?.let { id -> remember(id) { it.copy(fpsMilli = milli) } }
        }
    }

    /** API < 31 AFR switch while a session plays (WP-C switchListener): pause and show SWITCHING_DISPLAY_MODE. */
    private fun onDisplaySwitchStarted() {
        val c = cycle ?: return
        if (session == null) return
        player?.playWhenReady = false
        if (!c.paused) setActiveState(c, PlaybackState.Connecting(nowPlaying(c), ConnectPhase.SWITCHING_DISPLAY_MODE, c.attempt, clock.nowMs()))
    }

    private fun onDisplaySwitchSettled() {
        val c = cycle ?: return
        if (session == null || c.paused) return
        player?.playWhenReady = true
    }

    private fun onSwitchTimeout(c: Cycle) {
        if (cycle !== c || c.firstMediaReached) return
        val st = _state.value
        if (st is PlaybackState.Error || st is PlaybackState.Stopped || st is PlaybackState.Idle) return
        val signal = c.lastSignal
        val error = PlaybackError(eventualCode(signal), ErrorDetail(signal = signal?.name ?: "SwitchTimeout"))
        BoxLog.i(TAG) { "stream switch timeout: ${error.code.key}" }
        if (settings.current.reconnectAttempts < 0 && signal != null && isTransient(signal)) {
            // L19 unlimited: the card shows, retries continue behind it.
            c.cardShown = true
            setState(PlaybackState.Error(nowPlaying(c), enrich(c, error, signal).copy(retry = retryInfo(c, nextAttemptInMs = null))))
        } else {
            fail(c, error, signal)
        }
    }

    // ------------------------------------------------------------------ ladder (§4.3)

    private fun onFailure(c: Cycle, signal: FailureSignal, error: PlaybackException?, fromWriter: Boolean = false) {
        if (cycle !== c) return
        c.failureFromWriter = fromWriter
        val now = clock.nowMs()
        val id = c.currentTuneId
        c.lastSignal = signal
        c.lastErrorAtMs = now
        c.failureCount++
        healthyJob?.cancel()
        // (follow-up 2026-10-14) Any failure before the raised level proved itself: the raise is never saved.
        compatPersistJob?.cancel()
        c.compatPending = false
        eventLog.add(PlaybackEventType.ERROR, id, signal.name, error?.errorCodeName ?: "")
        BoxLog.i(TAG) { "failure ${signal.name} (tune $id, ${now - c.startedAtMs} ms)" }

        // WP-A: peek the limiter for the context; consume a slot only when the ladder actually re-resolves (format flip
        // and UA fallback are not rate-limited). The second decision only runs if the slot vanished in between.
        val limiterKey = limiterKey(c)
        var result = ladder.decide(signal, ladderContext(c, reResolveAllowed = limiter.wouldAllow(limiterKey, now)))
        if (result.first is LadderDecision.ReResolve && !limiter.tryAcquire(limiterKey, now)) {
            result = ladder.decide(signal, ladderContext(c, reResolveAllowed = false))
        }
        c.counters = result.second
        var decision = result.first
        if (decision !is LadderDecision.Fail && !c.firstMediaReached &&
            now >= c.switchDeadlineAtMs &&
            !(settings.current.reconnectAttempts < 0 && isTransient(signal))
        ) {
            decision = LadderDecision.Fail(PlaybackError(eventualCode(signal), ErrorDetail(signal = signal.name)))
        }
        execute(c, decision, signal, error)
    }

    private fun ladderContext(c: Cycle, reResolveAllowed: Boolean): LadderContext {
        val now = clock.nowMs()
        val s = session
        val r = c.resolved
        val stats = safeStats()
        return LadderContext(
            nowMs = now,
            msSinceTuneStart = now - c.startedAtMs,
            msSinceSwitchWindowStart = now - (c.switchDeadlineAtMs - settings.current.streamSwitchTimeoutMs),
            firstFrameRendered = c.firstMediaReached,
            isLive = !c.isArchive,
            isArchive = c.isArchive,
            format = s?.format ?: c.format ?: r?.formatHint ?: StreamFormat.UNKNOWN,
            // WP-A: resolved.xtream != null; an XTREAM playlist always resolves with it (the type covers a failed resolve).
            isXtream = r?.xtream != null || c.playlistInfo?.type == PlaylistType.XTREAM,
            // (Fix pass P2) Archive re-resolves keep the request's fixed base: no rotation for the ladder to plan.
            baseCount = if (c.isArchive) 1 else r?.baseCount ?: 1,
            output = r?.output,
            counters = c.counters,
            reResolveAllowed = reResolveAllowed,
            reconnectAttemptsSetting = settings.current.reconnectAttempts,
            tunnelingActive = settings.current.tunneling && !c.tunnelingOff,
            passthroughActive = stats.audioPassthrough,
            // WP-A reads it as the selected audio MIME (also for PCM stalls, L33/L34).
            passthroughMime = stats.audioMime,
            audioChannelCount = stats.audioChannels,
            videoDecoderMode = effectiveVideoMode(c),
            softwareVideoAvailable = engine.softwareVideoDecoderAvailable(stats.videoMime),
            ffmpegSupportsMime = engine::ffmpegSupports,
            uaFallbackAvailable = uaFallbackAvailable(c),
            backupAvailable = settings.current.failoverToBackup && c.backups.isNotEmpty(),
            tsCompatLevel = c.tsCompat,
            accountActive = c.account?.activeConnections,
            accountMax = c.account?.maxConnections,
            accountStatus = c.accountGate,
            lastHost = s?.telemetry?.finalHost ?: r?.host,
        )
    }

    private fun uaFallbackAvailable(c: Cycle): Boolean {
        val s = settings.current
        if (!s.autoUserAgentFallback || c.counters.uaFallbackTried) return false
        val channelHeaders = c.channelInfo?.headers ?: c.directTarget?.headers ?: emptyMap()
        if (channelHeaders.keys.any { it.equals("User-Agent", ignoreCase = true) }) return false
        return c.playlistInfo?.userAgent == null
    }

    private fun execute(c: Cycle, d: LadderDecision, signal: FailureSignal, error: PlaybackException?) {
        val id = c.currentTuneId
        eventLog.add(PlaybackEventType.DECISION, id, decisionName(d), signal.name)
        BoxLog.i(TAG) { "decision ${decisionName(d)} for ${signal.name}" }
        // (M6, §7.4) A buffered cycle routes writer-scoped, cycle-scoped and behind-live decisions first.
        if (c.ts != null && executeBuffered(c, d, signal)) return
        when (d) {
            is LadderDecision.Fail -> fail(c, d.error, signal)
            is LadderDecision.RetrySame -> restart(c, d, signal, Plan(delayMs = d.delayMs, reuseResolved = c.resolved != null))
            is LadderDecision.ReResolve -> {
                if (d.rotateBase) {
                    val r = c.resolved
                    if (r != null && r.baseCount > 1 && !c.isArchive) c.baseIndex = (r.baseIndex + 1) % r.baseCount
                }
                restart(c, d, signal, Plan(delayMs = d.delayMs, refreshAccount = d.refreshAccount))
            }
            is LadderDecision.FlipXtreamOutput -> {
                c.output = d.to
                c.flipPending = d.to
                restart(c, d, signal, Plan())
            }
            is LadderDecision.SwitchSourceType -> {
                c.format = d.to
                restart(c, d, signal, Plan(reuseResolved = c.resolved != null))
            }
            LadderDecision.SeekToLiveAndPrepare -> restart(c, d, signal, Plan(reuseResolved = c.resolved != null))
            is LadderDecision.Reprepare -> {
                if (d.raiseTsCompat && c.tsCompat == 0) {
                    // (follow-up 2026-10-14) The raise applies to this cycle at once; it is saved only after 30 s of
                    // healthy playback at level 1, and only when a decoder-type signal caused it (never NO_BYTES, PCR,
                    // network), so a misfire or a provider problem does not leave a permanent flag.
                    c.tsCompat = 1
                    c.memory = c.memory.copy(tsCompatLevel = 1)
                    c.compatPending = isDecoderTypeSignal(signal)
                    if (!c.compatPending) BoxLog.i(TAG) { "compat raised for this cycle only (${signal.name})" }
                    extendSwitchWindow(c)
                }
                restart(c, d, signal, Plan(reuseResolved = c.resolved != null, freshDecoders = isStuckPipelineStall(signal, error)))
            }
            LadderDecision.DisableTunnelingAndReprepare -> {
                c.tunnelingOff = true
                // Learned for the channel (ChannelPlaybackMemory.tunnelingOff): the next tune starts untunneled.
                c.memory = c.memory.copy(tunnelingOff = true)
                c.identity?.let { id -> remember(id) { it.copy(tunnelingOff = true) } }
                _notices.tryEmit(PlaybackNotice.TunnelingDisabled)
                extendSwitchWindow(c)
                restart(c, d, signal, Plan(reuseResolved = c.resolved != null))
            }
            LadderDecision.SoftwareVideoAndReprepare -> {
                c.softwareVideo = true
                c.warning = PlaybackWarning.SOFTWARE_VIDEO
                // (M5 integration) The event types M2 declared for the fallbacks (diagnostics, I-M5-27).
                eventLog.add(PlaybackEventType.VIDEO_FALLBACK, c.currentTuneId, "software_video", "")
                _notices.tryEmit(PlaybackNotice.VideoDecoderSwitchedToSoftware)
                extendSwitchWindow(c)
                // After a player error Media3 has already released the codec (see isStuckPipelineStall); otherwise the
                // software decoder needs a new codec, which a buffered re-prepare gets only with a stop() first.
                restart(c, d, signal, Plan(reuseResolved = c.resolved != null, freshDecoders = error == null))
            }
            is LadderDecision.DisablePassthroughAndReprepare -> {
                routeDeniedPassthrough += d.mimeType ?: "*"
                c.warning = PlaybackWarning.PASSTHROUGH_DISABLED
                eventLog.add(PlaybackEventType.AUDIO_FALLBACK, c.currentTuneId, "passthrough_off", d.mimeType ?: "*")
                _notices.tryEmit(PlaybackNotice.PassthroughDisabled(d.mimeType, forced = settings.current.audioOutput == AudioOutputMode.FORCE_PASSTHROUGH))
                extendSwitchWindow(c)
                restart(c, d, signal, Plan(reuseResolved = c.resolved != null))
            }
            is LadderDecision.SoftwareAudioAndReprepare -> {
                c.softwareAudioMimes += d.mimeType
                eventLog.add(PlaybackEventType.AUDIO_FALLBACK, c.currentTuneId, "software_audio", d.mimeType)
                _notices.tryEmit(PlaybackNotice.AudioDecoderSwitched(toSoftware = true, mimeType = d.mimeType))
                extendSwitchWindow(c)
                restart(c, d, signal, Plan(reuseResolved = c.resolved != null))
            }
            LadderDecision.RepairAudioTimestampsAndReprepare -> {
                c.audioTsRepair = true
                eventLog.add(PlaybackEventType.AUDIO_FALLBACK, c.currentTuneId, "audio_ts_repair", "")
                restart(c, d, signal, Plan(reuseResolved = c.resolved != null))
            }
            LadderDecision.StereoAndReprepare -> {
                c.forceStereo = true
                restart(c, d, signal, Plan(reuseResolved = c.resolved != null))
            }
            is LadderDecision.DropAudioAndReprepare -> {
                c.audioDropped = true
                c.warning = d.warning
                eventLog.add(PlaybackEventType.AUDIO_FALLBACK, c.currentTuneId, "audio_dropped", "")
                _notices.tryEmit(PlaybackNotice.Warning(d.warning))
                val p = player
                if (error != null || p == null || p.playbackState == Player.STATE_IDLE) {
                    // A player error stopped the player: a new session is needed (audio stays disabled).
                    restart(c, d, signal, Plan(reuseResolved = c.resolved != null))
                } else {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                    session?.lastDisruptionAtMs = clock.nowMs()
                    updateExpectations()
                    val st = _state.value
                    if (st is PlaybackState.Playing) setState(st.copy(warning = d.warning))
                }
            }
            LadderDecision.UserAgentFallback -> {
                c.userAgentOverride = settings.current.userAgentFallbackPreset
                c.uaFallbackPending = true
                restart(c, d, signal, Plan())
            }
            LadderDecision.TryBackupSource -> {
                val backup = c.backups.firstOrNull()
                if (backup == null) {
                    fail(c, PlaybackError(PlaybackErrorCode.CHANNEL_OFFLINE, ErrorDetail(signal = signal.name)), signal)
                } else {
                    startCycle(Spec.Live(TuneTarget.Channel(backup)))
                }
            }
        }
    }

    /**
     * (Ladder fix 2026-11-11) An L27 stall verdict (a stuck decoder, a frozen picture, or a position that stopped
     * advancing: review S3), with no player error behind it. Every player error has already reset the renderers in Media3
     * (`ExoPlayerImplInternal`: renderer and runtime errors call `stopInternal(forceResetRenderers = true)`; IO errors
     * `stopInternal(false)`, which resets too because we never turn foreground mode on), so only the watchdog's verdicts
     * leave the stuck codecs in place for a buffered re-prepare. With the buffer off the same repair always stops.
     */
    private fun isStuckPipelineStall(signal: FailureSignal, error: PlaybackException?): Boolean =
        error == null && signal is FailureSignal.Stall &&
            (signal.kind == StallKind.DECODER_STUCK || signal.kind == StallKind.FROZEN_VIDEO || signal.kind == StallKind.POSITION_STUCK)

    /** Ends the session and opens a new one after the decision's delay, showing the §4.3 waiting state. */
    private fun restart(c: Cycle, d: LadderDecision, signal: FailureSignal, plan: Plan) {
        val max = settings.current.reconnectAttempts
        val transient = (d is LadderDecision.ReResolve || d is LadderDecision.RetrySame) && isTransient(signal)
        val waiting: (NowPlaying) -> PlaybackState = when {
            !c.firstMediaReached -> {
                c.attempt++
                val attempt = c.attempt
                val since = clock.nowMs()
                ({ np -> PlaybackState.Connecting(np, ConnectPhase.RESOLVING, attempt, since) })
            }
            transient -> {
                val n = c.counters.transientRetries.coerceAtLeast(1)
                _notices.tryEmit(PlaybackNotice.Reconnecting(n, max))
                c.reconnectNoticeShown = true
                ({ np -> PlaybackState.Buffering(np, BufferingCause.RECONNECTING, ReconnectProgress(n, max)) })
            }
            else -> {
                c.attempt++
                val attempt = c.attempt
                ({ np -> PlaybackState.Stalled(np, (signal as? FailureSignal.Stall)?.kind, decisionName(d), attempt, max) })
            }
        }
        launchSession(c, plan, waiting)
        // (M4, lead ruling) Behind the unlimited-retry card the waiting state is not shown (setActiveState), so the card
        // itself carries the progress: attempts so far and the wait before the next one.
        val st = _state.value
        if (c.cardShown && st is PlaybackState.Error) {
            setState(PlaybackState.Error(nowPlaying(c), st.error.copy(retry = retryInfo(c, nextAttemptInMs = clipDelay(c, plan.delayMs)))))
        }
    }

    /**
     * (M4, lead ruling) [PlaybackError.retry] for a card behind which the ladder keeps retrying: [RetryInfo.attemptsMade]
     * counts the connection attempts this cycle has started (the next one excluded), [RetryInfo.maxAttempts] is 0 for
     * the unlimited setting (the only case that shows a card while retrying, L19), else the setting.
     */
    private fun retryInfo(c: Cycle, nextAttemptInMs: Long?): RetryInfo {
        val max = settings.current.reconnectAttempts
        val made = if (nextAttemptInMs != null) c.attempt - 1 else c.attempt
        return RetryInfo(attemptsMade = made.coerceAtLeast(1), maxAttempts = if (max < 0) 0 else max, nextAttemptInMs = nextAttemptInMs)
    }

    private fun fail(c: Cycle, error: PlaybackError, signal: FailureSignal?) {
        if (cycle !== c) return
        // The ladder gave up: no retry runs behind this card.
        val enriched = enrich(c, error, signal).copy(retry = null)
        sessionJob?.cancel()
        switchTimeoutJob?.cancel()
        livePauseJob?.cancel()
        closeTimeshift(c, discardFile = false)
        endSession()
        stopPolling()
        c.cardShown = false
        c.cardLiftPending = false
        setState(PlaybackState.Error(nowPlaying(c), enriched))
        finishZapRecord(c, "error:${enriched.code.key}")
        importGate.open()
        BoxLog.i(TAG) { "error ${enriched.code.key} (${enriched.detail.signal ?: "-"})" }
    }

    /** Fills the detail line (hosts only) and filters the actions by availability (§2.1 PlaybackError KDoc). */
    private fun enrich(c: Cycle, error: PlaybackError, signal: FailureSignal?): PlaybackError {
        val s = session
        var detail = error.detail
        if (detail.host == null) detail = detail.copy(host = s?.telemetry?.finalHost ?: c.resolved?.host)
        if (detail.signal == null && signal != null) detail = detail.copy(signal = signal.name)
        if (detail.httpStatus == null && signal is FailureSignal.Http) detail = detail.copy(httpStatus = signal.status)
        if (error.code == PlaybackErrorCode.CONNECTION_LIMIT || error.code == PlaybackErrorCode.CONNECTION_LIMIT_LOCAL) {
            val limit = signal as? FailureSignal.LocalConnectionLimit
            if (detail.maxConnections == null) detail = detail.copy(maxConnections = limit?.max?.takeIf { it > 0 } ?: c.account?.maxConnections ?: maxConnections(c))
            if (detail.activeConnections == null) detail = detail.copy(activeConnections = limit?.active ?: c.account?.activeConnections)
        }
        val actions = error.actions.filter { step ->
            when (step) {
                NextStep.TRY_BACKUP -> settings.current.failoverToBackup && c.backups.isNotEmpty()
                NextStep.PREVIOUS_CHANNEL -> previousIdentity != null && previousIdentity != c.identity
                else -> true
            }
        }
        // (M5 integration, B2, §3.14) While "Always passthrough" is on, an audio-output failure offers Back to Auto first.
        val withAuto = if (error.code == PlaybackErrorCode.AUDIO_OUTPUT_FAILED && settings.current.audioOutput == AudioOutputMode.FORCE_PASSTHROUGH && NextStep.BACK_TO_AUTO !in actions) {
            listOf(NextStep.BACK_TO_AUTO) + actions
        } else actions
        return error.copy(detail = detail, actions = withAuto)
    }

    // ------------------------------------------------------------------ pause, resume, stop

    override fun pause() {
        val c = cycle ?: return
        val st = _state.value
        if (st !is PlaybackState.Playing && st !is PlaybackState.Buffering && st !is PlaybackState.Connecting && st !is PlaybackState.Stalled) return
        if (c.paused) return
        c.paused = true
        player?.playWhenReady = false
        // (M4, M2 E-L2) Before first media or while the ladder acts, the state stays honest (Connecting / Stalled); the
        // pause is applied when the player reaches READY (onPlayerState reports Playing(paused) then), and the
        // display-mode gate of every later session opens with playWhenReady = false.
        if (st !is PlaybackState.Connecting && st !is PlaybackState.Stalled) {
            setState(PlaybackState.Playing(nowPlaying(c), paused = true, warning = c.warning))
        }
        if (!c.isArchive && c.ts != null) {
            // (M6, §7.2) Buffered: the recorder keeps saving; no livePauseJob, no connection release, no re-tune.
            pauseBuffered(c)
        } else if (!c.isArchive) {
            livePauseJob?.cancel()
            livePauseJob = scope.launch {
                delay(LIVE_PAUSE_RELEASE_MS)
                if (cycle === c && c.paused) {
                    BoxLog.i(TAG) { "live pause: releasing the connection" }
                    // (Fix pass S5) A recorder that started meanwhile must not outlive the released tune.
                    closeTimeshift(c, discardFile = false)
                    sessionJob?.cancel()
                    switchTimeoutJob?.cancel()
                    endSession()
                    stopPolling()
                    importGate.open()
                    setState(PlaybackState.Stopped(nowPlaying(c), StopReason.LIVE_PAUSE_TIMEOUT, resumable = true))
                }
            }
        }
    }

    override fun resume() {
        val c = cycle ?: return
        when (val st = _state.value) {
            is PlaybackState.Playing -> {
                if (!st.paused) return
                if (c.isArchive) {
                    c.paused = false
                    player?.playWhenReady = true
                    setState(PlaybackState.Playing(nowPlaying(c), paused = false, warning = c.warning))
                } else if (c.ts != null) {
                    // (M6, §7.2) Continue exactly where it stopped: same session, no new connection, no LiveResumed.
                    resumeBuffered(c)
                } else {
                    // Live has no time-shift in v1: resume re-tunes at the live edge with a fresh resolve.
                    startCycle(c.specForRestart())
                    _notices.tryEmit(PlaybackNotice.LiveResumed)
                }
            }
            is PlaybackState.Stopped -> {
                if (!st.resumable) return
                startCycle(c.specForRestart())
                if (!c.isArchive) _notices.tryEmit(PlaybackNotice.LiveResumed)
            }
            // (M4, M2 E-L2) Paused before READY: nothing was frozen yet, so un-pause in place (no re-tune). The gate
            // opens the player later when it has not opened yet.
            is PlaybackState.Connecting, is PlaybackState.Stalled -> {
                if (!c.paused) return
                c.paused = false
                livePauseJob?.cancel()
                c.ts?.onResume()
                val s = session
                if (s != null && settledSession === s) player?.playWhenReady = true
            }
            else -> Unit
        }
    }

    override fun stop(reason: StopReason) {
        val c = cycle
        cancelPendingZap()
        cancelCycleJobs()
        // (M6, §7.5, §8.3) The saved part and the file are discarded: writer call cancelled, lease released, fd closed,
        // then unlinked.
        if (c != null) closeTimeshift(c, discardFile = true)
        // A terminal error may already have closed the session while retaining its reusable ring. Also remember the
        // stop if a superseded preparation is still returning its grant on IO.
        timeshift.discardUnused()
        if (c != null && c.isArchive) c.archiveResumeAtMs = player?.currentPosition ?: 0
        endSession()
        stopPolling()
        if (reason != StopReason.LIVE_PAUSE_TIMEOUT) try {
            eng?.parts?.displayMode?.clear()
        } catch (e: Exception) {
            BoxLog.w(TAG, "display mode clear failed: ${e.javaClass.simpleName}")
        }
        importGate.open()
        if (c != null) {
            finishZapRecord(c, "stopped:${reason.name.lowercase()}")
            eventLog.add(PlaybackEventType.STOP, c.currentTuneId, reason.name, "")
        }
        BoxLog.i(TAG) { "stop ${reason.name}" }
        setState(PlaybackState.Stopped(c?.let { nowPlaying(it) }, reason, resumable = reason != StopReason.USER))
        publishLocalBuffer(force = true)
    }

    private fun cancelCycleJobs() {
        sessionJob?.cancel()
        switchTimeoutJob?.cancel()
        livePauseJob?.cancel()
        healthyJob?.cancel()
        compatPersistJob?.cancel()
        recordWatchedJob?.cancel()
        writerJob?.cancel()
        gapFallbackJob?.cancel()
    }

    // ------------------------------------------------------------------ lifecycle target (§3.10)

    override fun isPlaybackActive(): Boolean = when (val st = _state.value) {
        is PlaybackState.Connecting, is PlaybackState.Buffering, is PlaybackState.Playing, is PlaybackState.Stalled -> true
        is PlaybackState.Error -> cycle?.let { it.cardShown || it.cardLiftPending } == true
        else -> false
    }

    override fun stopForLifecycle(reason: StopReason) {
        lifecycleStopAtMs = clock.nowMs()
        // (M6, §7.5) Home or standby ended a paused or behind buffered cycle: the first tune after says so once.
        cycle?.let { c -> if (c.ts != null && (c.paused || c.localShift)) leftAppPending = true }
        eventLog.add(PlaybackEventType.LIFECYCLE, cycle?.currentTuneId ?: 0, "stop", reason.name)
        stop(reason)
    }

    override fun resumeAfterLifecycle() {
        val st = _state.value as? PlaybackState.Stopped ?: return
        if (!st.resumable || st.reason !in LIFECYCLE_REASONS) return
        val c = cycle ?: return
        val away = clock.nowMs() - lifecycleStopAtMs
        val reauth = away >= LifecycleCoordinator.RESUME_REAUTH_AFTER_MS
        if (reauth && !settings.current.resumeAfterStandby) {
            BoxLog.i(TAG) { "back after ${away / 1000} s: not resuming (setting)" }
            return
        }
        // (M5, docs/design/m5-setup-settings.md §3.9.2, §3.9.5, B1) A relock on ON_STOP is never "mid-programme": a locked
        // channel does not resume; the guide machine opens the PIN prompt from Stopped(LOCKED).
        val identity = c.identity
        if (identity != null && guard(identity) != TuneVerdict.ALLOWED) {
            eventLog.add(PlaybackEventType.LIFECYCLE, c.currentTuneId, "resume", "locked")
            BoxLog.i(TAG) { "back after ${away / 1000} s: locked channel, not resuming" }
            setState(PlaybackState.Stopped(nowPlaying(c), StopReason.LOCKED, resumable = false))
            return
        }
        eventLog.add(PlaybackEventType.LIFECYCLE, c.currentTuneId, "resume", if (reauth) "reauth" else "")
        // Always a fresh resolve and session at the live edge (never a stale TS connection).
        startCycle(c.specForRestart(), refreshAccount = reauth)
    }

    override fun onForegroundChanged(foreground: Boolean) {
        eng?.session?.player?.refresh()
    }

    /** (code review 2026-10-14, E-S4) No rendered video is expected during a standby/HDMI/display-off grace. */
    override fun onGraceChanged(active: Boolean) {
        if (!active) eng?.parts?.watchdog?.reset()
        updateExpectations()
    }

    /** (E-S4) The root `SurfaceView`'s surface was created (true) or destroyed/detached (false). */
    internal fun onSurfaceValidChanged(valid: Boolean) {
        if (surfaceValid == valid) return
        surfaceValid = valid
        // A new surface: the frozen-picture baseline starts again (frames were skipped while there was none).
        if (valid) eng?.parts?.watchdog?.reset()
        updateExpectations()
    }

    // ------------------------------------------------------------------ viewport, tracks, aspect, decoder

    override fun setViewport(viewport: Viewport) {
        if (this.viewport == viewport) return
        this.viewport = viewport
        if (viewport == Viewport.MINI && cycle != null) previewStarted()
        eng?.parts?.displayMode?.onViewport(viewport)
        applyVideoTrackState()
    }

    private fun videoShouldBeDisabled(c: Cycle?): Boolean =
        viewport == Viewport.HIDDEN || viewport == Viewport.NONE || (c != null && posterMode(c))

    /** §3.9.5: HDR (known from the format, or from the channel's `knownHdr` bit before it arrives) in MINI → poster. */
    private fun posterMode(c: Cycle): Boolean =
        settings.current.hdrInPreview == HdrPreviewMode.POSTER && viewport == Viewport.MINI &&
            (c.hdr?.let { it != HdrKind.SDR } ?: c.memory.knownHdr)

    /** HIDDEN/NONE/poster disable the video track (no decoding); audio and the connection are kept (§3.11, Q8). */
    private fun applyVideoTrackState() {
        val p = player ?: return
        val c = cycle
        val disable = videoShouldBeDisabled(c)
        val params = p.trackSelectionParameters
        if (params.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) != disable) {
            p.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disable).build()
            session?.lastDisruptionAtMs = clock.nowMs()
            eng?.parts?.watchdog?.reset()
        }
        _video.update { it.copy(posterMode = c != null && posterMode(c)) }
        updateExpectations()
    }

    private fun updateExpectations() {
        val e = eng ?: return
        val c = cycle
        // (code review 2026-10-14, E-S4) Frames are only rendered onto a valid surface, and the standby/display-off grace
        // runs without one: detector c (FROZEN_VIDEO) must not count those windows toward compat escalation.
        val videoExpected = !videoShouldBeDisabled(c) && session?.milestoneVideo != false && surfaceValid && lifecycle.graceReason == null
        val audioExpected = c?.audioDropped != true
        e.parts.watchdog.setExpectations(videoExpected, audioExpected)
    }

    override fun setStatsEnabled(enabled: Boolean) {
        statsEnabled = enabled
        eng?.parts?.setStatsEnabled(enabled)
        if (enabled) publishStats()
    }

    override fun selectAudioTrack(track: TrackRef?, remember: Boolean) {
        val p = player ?: return
        val c = cycle
        val builder = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        var language: String? = null
        if (track != null) {
            val group = findGroup(p.currentTracks, track) ?: return
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.index))
            language = group.getTrackFormat(track.index).language
        }
        p.trackSelectionParameters = builder.build()
        session?.lastDisruptionAtMs = clock.nowMs()
        val id = c?.identity
        if (remember && id != null && language != null) {
            c.memory = c.memory.copy(audioLanguage = language)
            remember(id) { it.copy(audioLanguage = language) }
        }
    }

    override fun selectTextTrack(track: TrackRef?, remember: Boolean) {
        val p = player ?: return
        val c = cycle
        val builder = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        var language: String? = ""
        if (track == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val group = findGroup(p.currentTracks, track) ?: return
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.index))
            language = group.getTrackFormat(track.index).language
        }
        p.trackSelectionParameters = builder.build()
        val id = c?.identity
        if (remember && id != null && language != null) {
            c.memory = c.memory.copy(subtitleLanguage = language)
            remember(id) { it.copy(subtitleLanguage = language) }
        }
    }

    private fun findGroup(tracks: Tracks, ref: TrackRef): Tracks.Group? =
        tracks.groups.firstOrNull { it.type == ref.type && it.mediaTrackGroup.id == ref.groupId && ref.index in 0 until it.length }

    override fun setAspectMode(mode: AspectMode, remember: Boolean) {
        applyAspect(mode)
        val c = cycle ?: return
        val id = c.identity
        if (remember && id != null) {
            c.memory = c.memory.copy(aspect = mode)
            remember(id) { it.copy(aspect = mode) }
        }
    }

    private fun applyAspect(mode: AspectMode) {
        _video.update { it.copy(aspectMode = mode) }
        val crop = mode == AspectMode.FILL || mode == AspectMode.ZOOM
        (player as? androidx.media3.exoplayer.ExoPlayer)?.videoScalingMode =
            if (crop) C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING else C.VIDEO_SCALING_MODE_SCALE_TO_FIT
    }

    override fun setVideoDecoderOverride(mode: VideoDecoderMode?, remember: Boolean) {
        val c = cycle ?: return
        c.decoderOverride = mode
        val id = c.identity
        if (remember && id != null) {
            c.memory = c.memory.copy(videoDecoder = mode)
            remember(id) { it.copy(videoDecoder = mode) }
        }
        _video.update { it.copy(decoderOverride = channelDecoderOverride(c)) }
        if (isPlaybackActive() && session != null) {
            eventLog.add(PlaybackEventType.DECISION, c.currentTuneId, "Reprepare", "decoder override")
            // (M6, §7.6) Buffered: reader-only at the pin (launchSession converts the plan). (Ladder fix 2026-11-11) When the
            // effective mode changes, the player is stopped first so that the chosen decoder is really created; picking
            // the mode already in effect costs no gap (review L6).
            launchSession(c, Plan(reuseResolved = c.resolved != null, freshDecoders = videoModeChanged(c)), waiting = { np -> _state.value.withTarget(np) })
        }
    }

    // ------------------------------------------------------------------ archive

    override fun seekTo(positionMs: Long) {
        val c = cycle ?: return
        if (!c.isArchive) return
        player?.seekTo(positionMs.coerceAtLeast(0))
        session?.lastDisruptionAtMs = clock.nowMs()
        eng?.parts?.watchdog?.reset()
    }

    override fun position(): PositionSnapshot {
        val p = player
        val c = cycle
        val archive = c?.archive
        return PositionSnapshot(
            positionMs = p?.currentPosition ?: 0,
            bufferedMs = p?.totalBufferedDuration ?: 0,
            durationMs = p?.duration?.takeIf { it != C.TIME_UNSET && it >= 0 },
            isSeekable = archive != null && p?.isCurrentMediaItemSeekable == true,
            archiveStartUtcMs = archive?.request?.requestStart?.toEpochMilli(),
            localMonoMs = c?.takeIf { it.ts != null && !it.isArchive }?.let { playheadMono(it) },
        )
    }

    // ------------------------------------------------------------------ polling, stats

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                pollTick()
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun pollTick() {
        val c = cycle ?: return
        val s = session ?: return
        val now = clock.nowMs()
        val t = s.telemetry
        if (t != null) {
            val st = _state.value
            if (t.waitingForLease && st is PlaybackState.Connecting && st.phase == ConnectPhase.CONNECTING) {
                setActiveState(c, st.copy(phase = ConnectPhase.WAITING_FOR_SLOT))
            }
            if (t.bytesTotal > 0) {
                if (c.zap.firstByteMs == null) c.zap.firstByteMs = now - c.startedAtMs
                if (st is PlaybackState.Connecting && st.phase == ConnectPhase.CONNECTING) setActiveState(c, st.copy(phase = ConnectPhase.BUFFERING_INITIAL))
            }
            if (t.inReconnect && ((st is PlaybackState.Playing && !st.paused) || (st is PlaybackState.Buffering && st.cause == BufferingCause.REBUFFER))) {
                setState(PlaybackState.Buffering(nowPlaying(c), BufferingCause.RECONNECTING, null))
            } else if (!t.inReconnect && st is PlaybackState.Buffering && st.cause == BufferingCause.RECONNECTING &&
                player?.playbackState == Player.STATE_READY && player?.playWhenReady == true
            ) {
                // The player never left READY (buffer covered the gap), so no READY callback will end the state.
                setState(PlaybackState.Playing(nowPlaying(c), paused = false, warning = c.warning))
            }
        }
        if (statsEnabled) publishStats()
        if (c.ts != null) pollTimeshift(c)
        val st = _state.value
        if (st is PlaybackState.Playing && !st.paused && !c.isArchive) {
            if (c.lastStableTickAtMs == 0L) c.lastStableTickAtMs = now
            if (now - c.lastStableTickAtMs >= STABLE_TICK_MS && now - c.lastRebufferAtMs >= STABLE_TICK_MS) {
                c.lastStableTickAtMs = now
                val mode = c.memory.bufferMode ?: settings.current.bufferMode
                bufferFsm.onStableTick(s.resolved.brokerKey, now, mode, engine.isLowRam)?.let { p ->
                    eng?.bundle?.loadControl?.profile = p
                    c.profile = p
                    eventLog.add(PlaybackEventType.BUFFER_LEVEL, s.tuneId, "L${p.level}", "decay")
                }
            }
        }
    }

    private fun publishStats() {
        val c = cycle
        val s = session
        val t = s?.telemetry
        val base = safeStats()
        _stats.value = base.copy(
            streamBitrateBps = t?.bitrateBps ?: base.streamBitrateBps,
            bufferMs = player?.totalBufferedDuration ?: base.bufferMs,
            httpStatus = t?.httpStatus?.takeIf { it > 0 } ?: base.httpStatus,
            host = t?.finalHost ?: s?.resolved?.host ?: base.host,
            redirects = t?.redirects ?: base.redirects,
            format = s?.format ?: base.format,
            reconnects = t?.reconnects ?: base.reconnects,
            broker = try {
                s?.ctx?.broker?.snapshot()
            } catch (e: Exception) {
                null
            } ?: base.broker,
            bufferProfile = c?.profile ?: base.bufferProfile,
            fps = _video.value.fps ?: base.fps,
            updatedAtElapsedMs = clock.nowMs(),
            serverIndex = s?.resolved?.baseIndex ?: base.serverIndex,
            serverCount = s?.resolved?.baseCount ?: base.serverCount,
        ).let { st -> withRewindStats(c, st) }
    }

    private fun safeStats(): StreamStats = try {
        eng?.parts?.stats() ?: StreamStats()
    } catch (e: Exception) {
        StreamStats()
    }

    // ------------------------------------------------------------------ time-shift (M6, docs/design/m6-timeshift.md §6, §7)

    /**
     * (Storage locations §5.3; Q-D1 / lead ruling R2, closure fix pass 2026-10-28) The guide's preview started (a live tune
     * in MINI, or MINI over a running cycle): the save location's keeper trigger and `.wake` write, so a NAS disk spins up
     * before the viewer pauses. On IO, never the zap path: the factory may resolve its target source there.
     */
    private fun previewStarted() {
        if (timeshift === NoTimeshiftFactory) return
        scope.launch(ioDispatcher) {
            try {
                timeshift.warmSaveLocation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.w(TAG, "save-location warm failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun readerTelemetry(): RingReaderTelemetry? = session?.telemetry as? RingReaderTelemetry

    /**
     * The viewer is at live: playing, and either never time-shifted or the **playhead** is back within the live landing
     * (§7.4; fix pass S1). The loader cursor is not the test: after a short rewind it reaches the head within seconds
     * while the viewer is still up to a player buffer behind. The cursor stays the §5.2 telemetry gate only.
     */
    private fun readerAtHead(c: Cycle): Boolean = !c.paused && (!c.localShift || !playheadBehind(c))

    /** `newestPtsMono − playheadMono > liveLandingMs() + GAP_MIN_MS` (S1); an unknown playhead counts as behind. */
    private fun playheadBehind(c: Cycle): Boolean {
        val ts = c.ts ?: return false
        val at = playheadMono(c) ?: return true
        return ts.ring.index.newestPtsMono() - at > liveLandingMs() + TimeshiftConstants.GAP_MIN_MS
    }

    /** Setting Off: the next `prepare` deletes the ring file (§8.3); asynchronous, never on the zap path. */
    private fun launchPrepareForOff(prefs: app.thebox.player.api.PlaybackSettings) {
        if (timeshift === NoTimeshiftFactory) return
        scope.launch(ioDispatcher) {
            try {
                timeshift.prepare(prefs, 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.w(TAG, "time-shift release failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** §7.1: once per cycle, after the resolve: buffered when live TS (or UNKNOWN → TS) and storage was granted. */
    private suspend fun decideTimeshift(c: Cycle, format: StreamFormat) {
        if (c.tsDecided) return
        val pending = c.tsPrepare
        val bufferable = !c.isArchive && (format == StreamFormat.TS || format == StreamFormat.UNKNOWN)
        if (!bufferable) {
            pending?.release(discardFile = true)
            c.tsDecided = true
            c.tsPrepare = null
            c.tsOff = if (c.isArchive) BufferOff.ARCHIVE else BufferOff.NOT_TS
            return
        }
        if (pending == null) {
            c.tsDecided = true
            if (c.tsOff == null) c.tsOff = BufferOff.SETTING_OFF
            return
        }
        val prepared = try {
            pending.take()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BoxLog.w(TAG, "time-shift prepare failed: ${e.javaClass.simpleName}")
            TimeshiftPrepared.Off(BufferOff.STORAGE_ERROR)
        }
        c.tsPrepare = null
        c.tsDecided = true
        when (prepared) {
            is TimeshiftPrepared.Granted -> c.tsGrant = prepared.grant
            is TimeshiftPrepared.Off -> c.tsOff = prepared.reason
        }
    }

    /** Main, right after the resolve (A-C1): the writer under a tune id reserved from the controller's sequence. */
    private fun startTimeshift(c: Cycle, resolved: ResolvedStream) {
        val grant = c.tsGrant ?: return
        val wid = ++lastTuneId
        val writerCtx = StreamSessionContext(
            tuneId = wid,
            resolved = resolved,
            broker = brokers.broker(resolved.brokerKey),
            leaseKind = LeaseKind.PLAYBACK,
            sinkFactory = app.thebox.player.engine.TsDebugOverrides.captureDir?.let { dir ->
                androidx.media3.datasource.DataSink.Factory { app.thebox.player.datasource.FileCaptureSink(dir, wid) }
            },
            onEvent = { type, code, detail -> mainHandler.post { onWriterEvent(c, wid, type, code, detail) } },
        )
        val ts = try {
            timeshift.start(grant, writerCtx, WriterListener(c, wid))
        } catch (e: Exception) {
            BoxLog.w(TAG, "time-shift start failed: ${e.javaClass.simpleName}")
            scope.launch(ioDispatcher + NonCancellable) { timeshift.abandon(grant, discardFile = true) }
            c.tsGrant = null
            c.tsOff = BufferOff.STORAGE_ERROR
            return
        }
        c.ts = ts
        c.firstByteSeen = false
        eventLog.add(PlaybackEventType.DECISION, wid, "timeshift_start", "gen=${ts.ring.generation} mode=${grant.mode.name}")
        if (c.paused) {
            // (Fix pass S5) Paused while resolving: M2's release job was armed because no session existed yet. The cycle
            // is buffered now, so the pause is a buffered one: no release job, the writer paused (hold limit, FULL stop).
            livePauseJob?.cancel()
            livePauseJob = null
            pauseBuffered(c)
        }
        publishLocalBuffer(force = true)
    }

    /**
     * Ends the cycle's time-shift (§7.2 "ts.close() before the next launchSession"): the writer's call is cancelled and
     * its lease released first (A-S1). [discardFile]: stop and lifecycle also delete the ring file (§8.3). A grant that
     * no session used yet is handed back (fix pass S3), so the factory's discard can run.
     */
    private fun closeTimeshift(c: Cycle, discardFile: Boolean) {
        c.tsPrepare?.release(discardFile)
        c.tsPrepare = null
        gapFallbackJob?.cancel()
        gapFallbackJob = null
        c.pendingGap = null
        val ts = c.ts
        if (ts == null) {
            c.tsGrant?.let { g ->
                c.tsGrant = null
                try {
                    timeshift.abandon(g, discardFile)
                } catch (e: Exception) {
                    BoxLog.w(TAG, "time-shift grant release failed: ${e.javaClass.simpleName}")
                }
            }
            return
        }
        c.ts = null
        c.firstByteSeen = false
        c.deferred = null
        c.liveAfterRestartFrom = -1L
        c.readerOrigin = -1L
        c.tsGrant = null
        writerJob?.cancel()
        writerJob = null
        try {
            ts.close(discardFile)
        } catch (e: Exception) {
            BoxLog.w(TAG, "time-shift close failed: ${e.javaClass.simpleName}")
        }
        eventLog.add(PlaybackEventType.DECISION, ts.writerTuneId, "timeshift_end", if (discardFile) "discard" else "")
        publishLocalBuffer(force = true)
    }

    /**
     * (Fix pass UX-S7) After a cycle-scoped decision closed the ring, the relaunch decides buffering again (a fresh
     * prepare, a new generation), so "Rewind saves again from now" holds when the new source is TS; an HLS source then
     * reports NOT_TS with `hls`, which has its own toast.
     */
    private fun redecideTimeshift(c: Cycle) {
        c.tsDecided = false
        c.tsOff = null
    }

    /**
     * §7.6 `Plan.readerOnly`: a reader session at [start]; no facts, resolve, broker or writer; codec policies applied.
     * [freshDecoders]: `launchSession` stopped the player first (ladder fix 2026-11-11), noted on the OPEN event.
     */
    private fun runReaderSession(e: Engine, c: Cycle, id: Long, start: ReaderStart, freshDecoders: Boolean = false) {
        val ts = c.ts ?: return
        val resolved = c.resolved ?: return
        applySwitches(e, c, resolved)
        c.readerOrigin = start.originMono
        c.readerStartOffset = start.offset
        val format = StreamFormat.TS
        val ctx = StreamSessionContext(
            tuneId = id,
            resolved = resolved,
            broker = brokers.broker(resolved.brokerKey),
            leaseKind = LeaseKind.PLAYBACK,
            sinkFactory = null,
            onEvent = { type, code, detail -> mainHandler.post { onSessionEvent(id, type, code, detail) } },
            repairAudioTimestamps = c.audioTsRepair,
        )
        val s = TuneSession(id, resolved, ctx, transport, format, clock.nowMs())
        val ring = RingOpen(ts.ring, ts.ring.generation, start.offset, ts.readerTelemetry { null })
        s.ringGeneration = ring.generation
        val built = try {
            mediaSources.build(ctx, format, if (c.tsCompat > 0) TsCompat.MULTI_PMT else TsCompat.DEFAULT, transport, null, ring)
        } catch (ex: Exception) {
            BoxLog.w(TAG, "ring source $id not built: ${ex.javaClass.simpleName}")
            s.end()
            fail(c, PlaybackError(PlaybackErrorCode.INTERNAL, ErrorDetail(host = resolved.host, signal = ex.javaClass.simpleName)), null)
            return
        }
        s.telemetry = built.telemetry
        session = s
        val p = e.bundle.player
        // No stop() since the previous prepare (endSession(stopPlayer = false)): the last frame stays (§6 step 3). The
        // exception is [freshDecoders], where launchSession stopped the player so that Media3 releases the codec.
        p.setMediaSource(built.source, /* resetPosition = */ true)
        p.prepare()
        zapDecoders.onPrepare(clock.nowMs())
        e.parts.watchdog.bind(built.telemetry)
        e.parts.watchdog.reset()
        e.parts.beginSession(built.telemetry, format, isLive = true, isHls = false)
        e.parts.watchdog.start { verdict -> onStall(verdict) }
        updateExpectations()
        eventLog.add(PlaybackEventType.OPEN, id, "RING", if (freshDecoders) "offset=${start.offset} fresh_decoders" else "offset=${start.offset}")
        e.parts.displayMode.awaitSettled {
            if (session === s) {
                settledSession = s
                p.playWhenReady = !c.paused
            }
        }
        startPolling()
    }

    /** Resolves the current reader's origin (position 0's PTS mono) from the index once it has data (§6 step 4). */
    private fun resolveOrigin(c: Cycle) {
        if (c.readerOrigin >= 0) return
        val index = c.ts?.ring?.index ?: return
        // R1: every RAP lookup below takes slot + entry in one consistent read (no slot reuse in between).
        val e = LongArray(2)
        if (index.oldestRapEntry(e) < 0) return
        val oldestMono = e[1]
        c.readerOrigin = if (index.ceilRapEntry(index.monoOfOffset(c.readerStartOffset), e) >= 0) e[1] else oldestMono
    }

    /** The playhead in mono ms: `originMono + player.currentPosition` (§6 step 4); null while unknown. */
    private fun playheadMono(c: Cycle): Long? {
        if (c.ts == null) return null
        resolveOrigin(c)
        if (c.readerOrigin < 0) return null
        return c.readerOrigin + (player?.currentPosition ?: 0L).coerceAtLeast(0L)
    }

    /** A-C3: the backlog of the cycle's first connection (newest PTS mono − first-media PTS mono). */
    private fun measureLatencyAtTune(c: Cycle, ts: TimeshiftSession) {
        if (c.latencyAtTuneMs > 0) return
        val at = playheadMono(c) ?: return
        c.latencyAtTuneMs = (ts.ring.index.newestPtsMono() - at).coerceAtLeast(0L)
    }

    /** `liveLandingMs = max(profile.startMs + LIVE_LANDING_EXTRA_MS, latencyAtTune)` (§6, A-C3). */
    internal fun liveLandingMs(): Long {
        val c = cycle ?: return TimeshiftConstants.LIVE_LANDING_EXTRA_MS
        return maxOf((c.profile?.startMs ?: 0).toLong() + TimeshiftConstants.LIVE_LANDING_EXTRA_MS, c.latencyAtTuneMs)
    }

    /** The RAP at or before the playhead, never before the oldest RAP (`max(pin, oldestRap)`, §7.4); the tail when empty. */
    private fun pinStart(c: Cycle): ReaderStart {
        val ring = c.ts?.ring ?: return ReaderStart(0L, -1L)
        val index = ring.index
        val at = playheadMono(c)
        val e = LongArray(2)
        if (index.oldestRapEntry(e) < 0) return ReaderStart(ring.tail, -1L)
        val oldest = ReaderStart(e[0], e[1])
        return if (at != null && index.floorRapEntry(at, e) >= 0) ReaderStart(e[0], e[1]) else oldest
    }

    /** §6 step 1 `LIVE`: the RAP at or before `newestPtsMono − liveLandingMs`. */
    private fun liveStart(c: Cycle): ReaderStart? {
        val ring = c.ts?.ring ?: return null
        val index = ring.index
        val e = LongArray(2)
        if (index.oldestRapEntry(e) < 0) return ReaderStart(ring.head, -1L)
        val oldest = ReaderStart(e[0], e[1])
        return if (index.floorRapEntry(index.newestPtsMono() - liveLandingMs(), e) >= 0) ReaderStart(e[0], e[1]) else oldest
    }

    /** The PLAYHEAD pin follows the RAP before the playhead (§7.2, the 1 Hz poll). */
    private fun setPlayheadPin(c: Cycle) {
        val ring = c.ts?.ring ?: return
        val index = ring.index
        val at = playheadMono(c) ?: return
        val e = LongArray(2)
        if (index.floorRapEntry(at, e) >= 0 || index.oldestRapEntry(e) >= 0) ring.setPin(PinOwner.PLAYHEAD, e[0])
    }

    /** §6 steps 2–3: the pin first, then a reader-only re-prepare (last frame held). */
    private fun launchReader(c: Cycle, start: ReaderStart, waiting: ((NowPlaying) -> PlaybackState)? = null) {
        val ts = c.ts ?: return
        ts.ring.setPin(PinOwner.PLAYHEAD, start.offset)
        val w = waiting ?: { np -> PlaybackState.Connecting(np, ConnectPhase.CONNECTING, c.attempt, clock.nowMs()) }
        launchSession(c, Plan(reuseResolved = true, reader = start), w)
    }

    private fun pauseBuffered(c: Cycle) {
        c.localShift = true
        c.pausedAtMs = clock.nowMs()
        setPlayheadPin(c)
        c.ts?.onPause(c.pausedAtMs)
        refreshKeepScreenOn()
        publishLocalBuffer(force = true)
    }

    private fun resumeBuffered(c: Cycle) {
        c.paused = false
        c.resumedAtMonoMs = clock.nowMs()
        player?.playWhenReady = true
        setState(PlaybackState.Playing(nowPlaying(c), paused = false, warning = c.warning))
        c.ts?.onResume()
        // §7.2: saving stopped while paused (full, hold limit, slot lost) → a fresh resolve and a GAP segment.
        val stop = c.ts?.stopReason
        if (stop != null && stop in RESTARTABLE_STOPS && c.ts?.stashed == null && c.deferred == null) {
            restartWriterScoped(c, delayMs = 0, refreshAccount = false, reuseResolved = false, waiting = false, reason = SegmentReason.GAP)
        }
        publishLocalBuffer(force = true)
    }

    override fun seekLocal(targetMonoMs: Long, direction: Int): LocalSeekResult {
        val c = cycle ?: return LocalSeekResult.Unavailable
        val ts = c.ts ?: return LocalSeekResult.Unavailable
        if (c.isArchive) return LocalSeekResult.Unavailable
        val index = ts.ring.index
        val landed = playheadMono(c)
        // R1: slot and entry from one consistent read each, so a slot reused by a newer entry cannot mix two RAPs.
        val e = LongArray(2)
        if (index.oldestRapEntry(e) < 0) return LocalSeekResult.Unavailable
        val oldestOffset = e[0]
        val oldestMono = e[1]
        var clamped = false
        var slot = if (direction > 0) index.ceilRapEntry(targetMonoMs, e) else index.floorRapEntry(targetMonoMs, e)
        // A-S4: → is never a no-op on long GOPs or anchor-only streams.
        if (direction > 0 && slot >= 0 && landed != null && e[1] <= landed) slot = index.ceilRapEntry(landed + 1, e)
        var offset = e[0]
        var origin = e[1]
        if (slot < 0) {
            if (targetMonoMs < oldestMono) {
                offset = oldestOffset
                origin = oldestMono
                clamped = true
            } else {
                jumpToLiveLocal()
                return LocalSeekResult.Live
            }
        }
        c.localShift = true
        eventLog.add(PlaybackEventType.DECISION, c.currentTuneId, "seek_local", "dir=$direction${if (clamped) " clamped" else ""}")
        launchReader(c, ReaderStart(offset, origin))
        return if (clamped) LocalSeekResult.Clamped(origin) else LocalSeekResult.Started(origin)
    }

    override fun jumpToLiveLocal(): Boolean {
        val c = cycle ?: return false
        val ts = c.ts ?: return false
        if (c.isArchive) return false
        c.localShift = false
        if (c.paused) {
            c.paused = false
            ts.onResume()
        }
        val stop = c.ts?.stopReason
        eventLog.add(PlaybackEventType.DECISION, c.currentTuneId, "jump_to_live_local", stop?.name ?: "")
        if (stop != null && stop in RESTARTABLE_STOPS && ts.stashed == null && c.deferred == null) {
            // Saving stopped: restart the writer first, then open at the new segment's first RAP (§7.2).
            restartWriterThenLive(c)
            return true
        }
        val start = liveStart(c) ?: return false
        launchReader(c, start)
        return true
    }

    /** RingWriterListener calls arrive on the pump thread and are only posted to main (A-C1). */
    private inner class WriterListener(private val c: Cycle, private val wid: Long) : RingWriterListener {
        private fun post(block: () -> Unit) {
            mainHandler.post { if (cycle === c && c.ts?.writerTuneId == wid) block() }
        }

        override fun onFirstBytes() = post { onWriterFirstBytes(c) }
        override fun onFailure(e: java.io.IOException) = post { onWriterFailure(c, wid, e) }
        override fun onStopped(reason: RecordStop) = post { onWriterStopped(c, wid, reason) }
        override fun onSegment(reason: SegmentReason, gapMs: Long) = post {
            eventLog.add(PlaybackEventType.DECISION, wid, "segment", "${reason.name} gap=$gapMs")
            publishLocalBuffer(force = true)
        }
    }

    /** §7.4 "Writer events": the first connection's zap milestones; reconnect state rules only at the head (B1, B3). */
    private fun onWriterEvent(c: Cycle, wid: Long, type: PlaybackEventType, code: String, detail: String) {
        eventLog.add(type, wid, code, detail)
        if (cycle !== c || c.ts?.writerTuneId != wid) return
        when (type) {
            PlaybackEventType.LEASE -> {
                if (c.zap.leaseMs == null) c.zap.leaseMs = clock.nowMs() - c.startedAtMs
                val st = _state.value
                if (st is PlaybackState.Connecting && st.phase == ConnectPhase.WAITING_FOR_SLOT) setActiveState(c, st.copy(phase = ConnectPhase.CONNECTING))
            }
            PlaybackEventType.RECONNECT -> if (readerAtHead(c)) {
                val st = _state.value
                if ((st is PlaybackState.Playing && !st.paused) || (st is PlaybackState.Buffering && st.cause == BufferingCause.REBUFFER)) {
                    setState(PlaybackState.Buffering(nowPlaying(c), BufferingCause.RECONNECTING, null))
                }
            }
            PlaybackEventType.RECONNECT_OK -> if (readerAtHead(c)) {
                val st = _state.value
                if (st is PlaybackState.Buffering && st.cause == BufferingCause.RECONNECTING && player?.playbackState == Player.STATE_READY && !c.paused) {
                    setState(PlaybackState.Playing(nowPlaying(c), paused = false, warning = c.warning))
                }
            }
            else -> Unit
        }
    }

    private fun onWriterFirstBytes(c: Cycle) {
        val first = !c.firstByteSeen
        c.firstByteSeen = true
        c.stopNotice = null
        val now = clock.nowMs()
        if (c.zap.firstByteMs == null) c.zap.firstByteMs = now - c.startedAtMs
        val st = _state.value
        if (st is PlaybackState.Connecting && st.phase == ConnectPhase.CONNECTING) setActiveState(c, st.copy(phase = ConnectPhase.BUFFERING_INITIAL))
        // A writer restart (no new reader session, so onFirstMedia does not run): announce the reconnect's end here.
        if (!first && c.reconnectNoticeShown && c.firstMediaReached) {
            c.reconnectNoticeShown = false
            _notices.tryEmit(PlaybackNotice.Reconnected)
        }
        publishLocalBuffer(force = true)
    }

    /** §7.4: the writer's failure, classified with its own tune id (never "stale"), decided once. */
    private fun onWriterFailure(c: Cycle, wid: Long, e: java.io.IOException) {
        val ts = c.ts ?: return
        val live = FailureClassifier.causeChain(e).firstOrNull { it is LiveStreamException } as LiveStreamException?
        eventLog.add(PlaybackEventType.ERROR, wid, "writer_failure", live?.kind?.name ?: e.javaClass.simpleName)
        val head = readerAtHead(c)
        // A-C4 (R3): a reconnect the cap refused never gets here; the writer stops itself with SLOT_LOST (onStopped).
        // §3.5, §7.4 (R2): a segment storm is a writer-scoped PcrDiscontinuity. Fix pass S2: handled here, never by the
        // ladder (whose L25 is a reader re-prepare and whose L27 would downgrade the decoder for a provider problem).
        classifier.classifyWriter(e)?.let { signal ->
            if (signal is FailureSignal.PcrDiscontinuity) onWriterStorm(c, wid, signal) else onFailure(c, signal, null, fromWriter = true)
            return
        }
        if (live == null) {
            onFailure(c, FailureSignal.Network(NetworkFailureKind.RESET, beforeFirstByte = !c.firstByteSeen), null, fromWriter = true)
            return
        }
        if (live.kind == LiveFailureKind.PREEMPTED) {
            // Another in-app acquire took the lease (A-N6): behind live the saved part stays playable; at the head it is
            // the stale pre-emption M2 already ignores.
            if (c.firstByteSeen && !head) stopSaving(c, RecordStop.PREEMPTED)
            return
        }
        val facts = FailureClassifier.Facts(
            currentTuneId = c.currentTuneId,
            sessionStartMs = 0L,
            isArchive = false,
            bytesReceived = ts.writerTelemetry.bytesTotal,
            writerTuneId = wid,
        )
        val signal = classifier.classifyLive(live, facts)
        if (signal == null) {
            eventLog.add(PlaybackEventType.ERROR, wid, "stale", live.kind.name)
            return
        }
        // A-S2, lead ruling 3: a kicked or refused recorder while not at the head stops saving; no reconnect, no ladder.
        if (c.firstByteSeen && !head && isLimitLike(signal)) {
            stopSaving(c, RecordStop.SLOT_LOST)
            return
        }
        onFailure(c, signal, null, fromWriter = true)
    }

    /**
     * (Fix pass S2; §7.4, §14 "Segment storm") The first storm restarts the recorder with a fresh resolve (writer-scoped,
     * the reader untouched). A repeat within [TimeshiftConstants.WRITER_STORM_REPEAT_MS] is cycle-scoped: at live the
     * ring ends and the cycle relaunches (re-resolved); behind live that is deferred until the saved part has played
     * out. Neither reaches the ladder's counters, L25 or L27.
     */
    private fun onWriterStorm(c: Cycle, wid: Long, signal: FailureSignal) {
        val now = clock.nowMs()
        val repeat = c.lastStormAtMs != Long.MIN_VALUE && now - c.lastStormAtMs < TimeshiftConstants.WRITER_STORM_REPEAT_MS
        c.lastStormAtMs = now
        eventLog.add(PlaybackEventType.DECISION, wid, "segment_storm", if (repeat) "cycle" else "writer")
        BoxLog.i(TAG) { "time-shift: segment storm (${if (repeat) "repeat: cycle-scoped" else "writer restart"})" }
        if (!repeat) {
            restartWriterScoped(c, delayMs = 0, refreshAccount = false, reuseResolved = false, waiting = false)
            return
        }
        val d = LadderDecision.ReResolve(0)
        if (c.firstByteSeen && !readerAtHead(c)) {
            c.deferred = d to signal
            eventLog.add(PlaybackEventType.DECISION, c.currentTuneId, "deferred", decisionName(d))
            stopSaving(c, RecordStop.UPSTREAM_FAILED)
            return
        }
        closeTimeshift(c, discardFile = false)
        redecideTimeshift(c)
        execute(c, d, signal, null)
    }

    private fun onWriterStopped(c: Cycle, wid: Long, reason: RecordStop) {
        if (reason != RecordStop.SESSION_END) c.lastStop = reason
        eventLog.add(PlaybackEventType.DECISION, wid, "saving_stopped", reason.name)
        notifySavingStopped(c, reason)
        refreshKeepScreenOn()
        publishLocalBuffer(force = true)
    }

    private fun notifySavingStopped(c: Cycle, reason: RecordStop) {
        if (reason !in TOASTED_STOPS || c.stopNotice == reason) return
        c.stopNotice = reason
        _notices.tryEmit(PlaybackNotice.SavingStopped(reason))
    }

    /** Stops saving (no ladder): SLOT_LOST, PREEMPTED, UPSTREAM_FAILED (§4.4, §7.4). */
    private fun stopSaving(c: Cycle, reason: RecordStop) {
        val control = c.ts ?: return
        eventLog.add(PlaybackEventType.DECISION, control.writerTuneId, "stop_saving", reason.name)
        BoxLog.i(TAG) { "time-shift: saving stopped (${reason.name})" }
        c.lastStop = reason
        control.stopSaving(reason)
        notifySavingStopped(c, reason)
        refreshKeepScreenOn()
        publishLocalBuffer(force = true)
    }

    /**
     * §7.4 for a buffered cycle; true when handled. Writer-scoped decisions restart the recorder only (before the first
     * byte: a recreate, A-C2); cycle-scoped ones end the ring at the head and are deferred behind live; a `Fail` behind
     * live is stashed until the saved part has played out (decided once, A-B1). Reader-scoped decisions fall through to
     * the normal path, which re-prepares the reader at the pin (`launchSession` converts the plan).
     */
    private fun executeBuffered(c: Cycle, d: LadderDecision, signal: FailureSignal): Boolean {
        val fromWriter = c.failureFromWriter
        c.failureFromWriter = false
        val head = readerAtHead(c)
        when (d) {
            is LadderDecision.Fail -> {
                // (Fix pass S1) Once bytes were saved, always stash: the reader surfaces RingEnd(UPSTREAM_FAILED) only
                // after the buffered media has played out, so the card comes at the right time at live too.
                if (!fromWriter || !c.firstByteSeen) return false
                val ts = c.ts ?: return false
                if (ts.stashed == null) ts.stashed = d.error to signal
                eventLog.add(PlaybackEventType.DECISION, ts.writerTuneId, "stash", d.error.code.key)
                stopSaving(c, RecordStop.UPSTREAM_FAILED)
                return true
            }
            is LadderDecision.RetrySame, is LadderDecision.ReResolve, LadderDecision.UserAgentFallback -> {
                if (!fromWriter) return false
                var delayMs = 0L
                var refresh = false
                when (d) {
                    is LadderDecision.RetrySame -> delayMs = d.delayMs
                    is LadderDecision.ReResolve -> {
                        delayMs = d.delayMs
                        refresh = d.refreshAccount
                        if (d.rotateBase) {
                            val r = c.resolved
                            if (r != null && r.baseCount > 1) c.baseIndex = (r.baseIndex + 1) % r.baseCount
                        }
                    }
                    else -> {
                        c.userAgentOverride = settings.current.userAgentFallbackPreset
                        c.uaFallbackPending = true
                    }
                }
                val reuse = d is LadderDecision.RetrySame && c.resolved != null
                // (Fix pass B2) Before the first byte too, the writer restarts in place: its pump terminates, closes and
                // reopens in order, the empty first segment is reused, and the reader waiting at the tail reads the
                // new connection's bytes. A second session on the grant would find the generation ended.
                restartWriterScoped(c, delayMs, refresh, reuse, waiting = head || !c.firstByteSeen, reason = SegmentReason.WRITER_RESTART, d = d, signal = signal)
                return true
            }
            is LadderDecision.SwitchSourceType, is LadderDecision.FlipXtreamOutput, LadderDecision.TryBackupSource -> {
                if (fromWriter && c.firstByteSeen && !head) {
                    c.deferred = d to signal
                    eventLog.add(PlaybackEventType.DECISION, c.currentTuneId, "deferred", decisionName(d))
                    stopSaving(c, RecordStop.UPSTREAM_FAILED)
                    return true
                }
                // At the head (or before the first byte): end the ring and relaunch; buffering is decided again for the
                // new source (UX-S7).
                closeTimeshift(c, discardFile = false)
                redecideTimeshift(c)
                return false
            }
            LadderDecision.SeekToLiveAndPrepare -> {
                jumpToLiveLocal()
                return true
            }
            else -> return false
        }
    }

    /** A writer-scoped restart on [writerJob] (§7.4): the reader is untouched; waiting state and toast only at the head. */
    private fun restartWriterScoped(
        c: Cycle,
        delayMs: Long,
        refreshAccount: Boolean,
        reuseResolved: Boolean,
        waiting: Boolean,
        reason: SegmentReason = SegmentReason.WRITER_RESTART,
        d: LadderDecision? = null,
        signal: FailureSignal? = null,
    ) {
        val control = c.ts ?: return
        control.markRestartPending()
        if (waiting && d != null && signal != null && !c.firstMediaReached) {
            // Before first media the cycle is still connecting (as M2's restart shows, B2); the reader is already open, so
            // the phase is CONNECTING and the writer's first bytes move it on to BUFFERING_INITIAL.
            c.attempt++
            setActiveState(c, PlaybackState.Connecting(nowPlaying(c), ConnectPhase.CONNECTING, c.attempt, clock.nowMs()))
        } else if (waiting && d != null && signal != null) {
            val max = settings.current.reconnectAttempts
            if ((d is LadderDecision.ReResolve || d is LadderDecision.RetrySame) && isTransient(signal)) {
                val n = c.counters.transientRetries.coerceAtLeast(1)
                _notices.tryEmit(PlaybackNotice.Reconnecting(n, max))
                c.reconnectNoticeShown = true
                setActiveState(c, PlaybackState.Buffering(nowPlaying(c), BufferingCause.RECONNECTING, ReconnectProgress(n, max)))
            } else {
                c.attempt++
                setActiveState(c, PlaybackState.Stalled(nowPlaying(c), (signal as? FailureSignal.Stall)?.kind, decisionName(d), c.attempt, max))
            }
        }
        writerJob?.cancel()
        writerJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            if (refreshAccount) refreshAccount(c)
            if (cycle !== c || c.ts !== control) return@launch
            val resolved = (if (reuseResolved) c.resolved else null) ?: resolveForWriter(c) ?: return@launch
            if (cycle !== c || c.ts !== control) return@launch
            c.resolved = resolved
            _servers.value = if (resolved.baseHosts.size > 1) ServerList(resolved.baseHosts, resolved.baseIndex) else ServerList()
            eventLog.add(PlaybackEventType.DECISION, control.writerTuneId, "writer_restart", reason.name)
            control.restartWriter(resolved, reason)
        }
    }

    /** A fresh resolve for the recorder; a failure is the writer's (stashed behind live, the card at the head). */
    private suspend fun resolveForWriter(c: Cycle): ResolvedStream? {
        return when (val o = withContext(ioDispatcher) { resolveOutcome(resolveRequest(c)) }) {
            is ResolveOutcome.Ok -> o.stream
            is ResolveOutcome.Failed -> {
                if (cycle !== c) return null
                val signal = when (o.code) {
                    PlaybackErrorCode.CHANNEL_NOT_FOUND -> FailureSignal.ChannelMissing
                    PlaybackErrorCode.NEEDS_REAUTH -> FailureSignal.Credentials(permanent = true)
                    PlaybackErrorCode.CREDENTIALS_UNAVAILABLE -> FailureSignal.Credentials(permanent = false)
                    else -> null
                }
                if (signal != null) {
                    onFailure(c, signal, null, fromWriter = true)
                } else if (readerAtHead(c) || !c.firstByteSeen) {
                    fail(c, PlaybackError(o.code, ErrorDetail(signal = "Resolve")), null)
                } else {
                    c.ts?.let { if (it.stashed == null) it.stashed = PlaybackError(o.code, ErrorDetail(signal = "Resolve")) to FailureSignal.Unknown(0) }
                    stopSaving(c, RecordStop.UPSTREAM_FAILED)
                }
                null
            }
        }
    }

    /** §7.4 "Reader RingEndException (non-failure stop)": restart the writer, then live at its first RAP. */
    private fun restartWriterThenLive(c: Cycle) {
        val ring = c.ts?.ring ?: return
        c.localShift = false
        c.liveAfterRestartFrom = ring.head
        setActiveState(c, PlaybackState.Buffering(nowPlaying(c), BufferingCause.RECONNECTING, null))
        restartWriterScoped(c, delayMs = 0, refreshAccount = false, reuseResolved = false, waiting = false, reason = SegmentReason.GAP)
    }

    /** A deferred cycle-scoped decision runs once the reader reaches the head (§7.4). */
    private fun runDeferred(c: Cycle) {
        val (d, signal) = c.deferred ?: return
        c.deferred = null
        closeTimeshift(c, discardFile = false)
        // UX-S7: "Rewind saves again from now" must hold, so the relaunch buffers again when its source is TS.
        redecideTimeshift(c)
        _notices.tryEmit(PlaybackNotice.JumpedToLiveRestart)
        execute(c, d, signal, null)
    }

    /** §5.3, §7.4: the reader's ring exceptions, before the ladder. */
    private fun onReaderRingException(c: Cycle, s: TuneSession, e: RingException) {
        val ts = c.ts ?: return
        when (e) {
            is RingBoundaryException -> onRingBoundary(c, s, e)
            is RingEndException -> {
                eventLog.add(PlaybackEventType.DECISION, s.tuneId, "ring_end", e.stop.name)
                val stashed = ts.stashed
                if (stashed != null) {
                    // The saved part has played out: the error the ladder decided while behind (decided once, A-B1).
                    ts.stashed = null
                    fail(c, stashed.first, stashed.second)
                    return
                }
                if (c.deferred != null) {
                    runDeferred(c)
                    return
                }
                restartWriterThenLive(c)
            }
            is RingPositionLostException -> {
                eventLog.add(PlaybackEventType.DECISION, s.tuneId, "ring_position_lost", "")
                val o = LongArray(2)
                if (ts.ring.index.oldestRapEntry(o) >= 0) launchReader(c, ReaderStart(o[0], o[1])) else jumpToLiveLocal()
            }
            else -> {
                // RingIoException (§4.6): live, with the storage toast.
                eventLog.add(PlaybackEventType.DECISION, s.tuneId, "ring_io", "")
                // (Save-location integration, S4-Q5; storage §4.6, §10) The read side names what happened: a resume after
                // a pause that found the file unreadable (PAUSE_RESUME_LOST_STORAGE), or a viewer behind live who is
                // back at live. Once per cycle each; the host words it for the place the cycle saved on.
                val resumeLost = c.paused || (c.resumedAtMonoMs != Long.MIN_VALUE && clock.nowMs() - c.resumedAtMonoMs <= RESUME_READ_WINDOW_MS)
                val jumped = c.localShift || c.paused
                if ((resumeLost || jumped) && !c.readLostNotified) {
                    c.readLostNotified = true
                    c.stopNotice = RecordStop.DISK_ERROR
                    _notices.tryEmit(PlaybackNotice.SavingStopped(RecordStop.DISK_ERROR, jumpedToLive = jumped, resumeLost = resumeLost))
                } else notifySavingStopped(c, RecordStop.DISK_ERROR)
                c.localShift = false
                liveStart(c)?.let { launchReader(c, it) }
            }
        }
    }

    /**
     * §5.3, §9.1 (A-C5): provider at the gap only on a catch-up channel during an active time-shift, and only when the gap
     * came from saving stopping or is ≥ `GAP_PROVIDER_MIN_MS`; otherwise the next segment's first RAP (GAP_SKIPPED_* for
     * gaps ≥ 1 s). No counters, no stats error, no event-log ERROR row (A-N2).
     */
    private fun onRingBoundary(c: Cycle, s: TuneSession, e: RingBoundaryException) {
        val ts = c.ts ?: return
        eventLog.add(PlaybackEventType.DECISION, s.tuneId, "ring_boundary", "${e.reason.name} gap=${e.gapMs}")
        val hasArchive = c.channelInfo?.catchup?.isAvailable == true
        val active = c.localShift || c.paused
        val stopGap = e.reason == SegmentReason.GAP
        // R14: WP-C's switch replaces this condition with
        // `if (timelineRouter.onGap(e.gapStartMonoMs, e.gapMs, if (stopGap) c.lastStop ?: RecordStop.SLOT_LOST else null, active, hasArchive, index.newestPtsMono()) is TimelineTarget.Provider) {`
        val index = ts.ring.index
        if (timelineRouter.onGap(e.gapStartMonoMs, e.gapMs, if (stopGap) c.lastStop ?: RecordStop.SLOT_LOST else null, active, hasArchive, index.newestPtsMono()) is app.thebox.core.catchup.archive.TimelineTarget.Provider) {
            // UX-B2: the archive side takes it from here; if no archive tune arrives in time, skip locally (a provider
            // that refuses catch-up must never leave the picture frozen at the gap).
            c.pendingGap = e
            gapFallbackJob?.cancel()
            gapFallbackJob = scope.launch {
                delay(TimeshiftConstants.GAP_HANDOFF_TIMEOUT_MS)
                if (cycle === c && c.pendingGap === e) {
                    eventLog.add(PlaybackEventType.DECISION, c.currentTuneId, "gap_handoff_timeout", "")
                    skipGapLocal()
                }
            }
            _notices.tryEmit(PlaybackNotice.LocalGap(e.gapStartMonoMs, e.gapMs, e.reason))
            return
        }
        skipGap(c, e)
    }

    /** The local side of a boundary (§5.3): the next segment's first RAP, GAP_SKIPPED_* for gaps of 1 s or more. */
    private fun skipGap(c: Cycle, e: RingBoundaryException) {
        val ts = c.ts ?: return
        val index = ts.ring.index
        val r = LongArray(2)
        val origin = if (index.ceilRapEntry(e.gapStartMonoMs, r) >= 0 && r[0] == e.nextRapOffset) r[1] else index.monoOfOffset(e.nextRapOffset)
        launchReader(c, ReaderStart(e.nextRapOffset, origin)) { np -> _state.value.withTarget(np) }
        if (e.gapMs >= TimeshiftConstants.GAP_MIN_MS) _notices.tryEmit(PlaybackNotice.GapSkipped(e.gapMs, stream = e.reason != SegmentReason.GAP))
    }

    /** UX-B2: the archive side could not act on the pending [PlaybackNotice.LocalGap]; skip the gap locally. */
    override fun skipGapLocal(): Boolean {
        val c = cycle ?: return false
        val e = c.pendingGap ?: return false
        c.pendingGap = null
        gapFallbackJob?.cancel()
        gapFallbackJob = null
        if (c.ts == null || c.isArchive) return false
        eventLog.add(PlaybackEventType.DECISION, c.currentTuneId, "gap_skip_local", "${e.reason.name} gap=${e.gapMs}")
        skipGap(c, e)
        return true
    }

    /** The 1 Hz part of the poll for a buffered cycle (§7.2). */
    private fun pollTimeshift(c: Cycle) {
        val ts = c.ts ?: return
        val st = _state.value
        val playing = st is PlaybackState.Playing && !st.paused
        // S1: the time-shift ends when the playhead (not the loader cursor) is back within the live landing.
        if (playing && c.localShift && !playheadBehind(c)) c.localShift = false
        // B3 (§4.5, §4.6): the PLAYHEAD pin exists only while paused or time-shifted. At live it would hold the tail a
        // GOP plus the latency behind the head: RAM-only (after a disk stop) live would stall on FULL, and PAUSE_ONLY
        // would persist continuously. Cleared once a reader is open (its own pin then holds its place).
        if (c.paused || c.localShift) {
            if (playing) setPlayheadPin(c)
        } else if (readerTelemetry()?.reader != null) {
            ts.ring.clearPin(PinOwner.PLAYHEAD)
        }
        if (c.deferred != null && readerAtHead(c)) {
            runDeferred(c)
            return
        }
        if (c.liveAfterRestartFrom >= 0) {
            val n = LongArray(2)
            if (ts.ring.index.newestRapEntry(n) >= 0 && n[0] >= c.liveAfterRestartFrom) {
                c.liveAfterRestartFrom = -1L
                launchReader(c, ReaderStart(n[0], n[1]))
            }
        }
        if (c.paused) refreshKeepScreenOn()
        publishLocalBuffer(force = false)
    }

    /** `localBuffer` (≤ 1 Hz unless [force]): mono times, converted to wall once per snapshot by the consumer (A-S9). */
    private fun publishLocalBuffer(force: Boolean) {
        val now = clock.nowMs()
        if (!force && now - lastLocalPublishAtMs < LOCAL_BUFFER_PUBLISH_MS) return
        lastLocalPublishAtMs = now
        _localBuffer.value = localBufferState(cycle, now)
    }

    private fun localBufferState(c: Cycle?, now: Long): LocalBufferState? {
        if (c == null || c.isArchive) return null
        val st = _state.value
        if (st is PlaybackState.Stopped || st is PlaybackState.Idle) return null
        val ts = c.ts
        if (ts == null) {
            val format = session?.format ?: c.format ?: c.resolved?.formatHint
            return LocalBufferState(identity = c.identity, available = false, off = c.tsOff, hls = format == StreamFormat.HLS, cycleId = c.cycleId)
        }
        val snap = try {
            ts.snapshot(now, System.currentTimeMillis() - now)
        } catch (e: Exception) {
            return LocalBufferState(identity = c.identity, available = false, off = BufferOff.STORAGE_ERROR, cycleId = c.cycleId)
        }
        // UX-S1: the cycle id lets the consumer tell a new cycle on the same identity from the same one.
        val out = snap.copy(identity = c.identity, atMonoMs = playheadMono(c) ?: snap.liveMonoMs, cycleId = c.cycleId)
        // (Storage locations WP-S2, §5.5) where this cycle saves, its fallback and failure, the folder's rates.
        return timeshift.storageFields(ts.grant, out) // Q-H3 / R10: the seam's default keeps `out` as is
    }

    /** "Rewind: 18:20 saved of 30:00 · 1.1 GB" (A-S9, U-N3). */
    private fun withRewindStats(c: Cycle?, st: StreamStats): StreamStats {
        if (c?.ts == null) return st
        val b = _localBuffer.value ?: return st
        return st.copy(
            rewindSavedMs = (b.liveMonoMs - b.fromMonoMs).coerceAtLeast(0L),
            rewindWindowMs = b.windowMs,
            rewindBytes = b.usedBytes,
            rewindSaving = b.saving,
        )
    }

    /** §11.5: a buffered pause keeps the screen on while saving, and on catch-up channels up to 3 h. */
    private fun keepAwakePaused(): Boolean {
        val c = cycle ?: return false
        val ts = c.ts ?: return false
        if (!c.paused) return false
        val control = c.ts
        val saving = control?.stopReason == null && (ts.ring.saving || !c.firstByteSeen)
        if (saving) return true
        val catchUp = c.channelInfo?.catchup?.isAvailable == true
        return catchUp && clock.nowMs() - c.pausedAtMs < TimeshiftConstants.PAUSE_KEEP_AWAKE_MAX_MS
    }

    private fun refreshKeepScreenOn() {
        val st = _state.value
        if (st is PlaybackState.Playing) _keepScreenOn.value = !st.paused || keepAwakePaused()
    }

    // ------------------------------------------------------------------ helpers

    private fun nowPlaying(c: Cycle) = NowPlaying(
        tuneId = c.currentTuneId,
        identity = c.identity,
        title = c.title,
        isArchive = c.isArchive,
        format = session?.format ?: c.format ?: c.resolved?.formatHint ?: StreamFormat.UNKNOWN,
    )

    private fun setState(s: PlaybackState) {
        _state.value = s
        _keepScreenOn.value = when (s) {
            is PlaybackState.Connecting, is PlaybackState.Buffering, is PlaybackState.Stalled -> true
            // (M6, §11.5) A buffered pause keeps the screen on while it is still being saved.
            is PlaybackState.Playing -> !s.paused || keepAwakePaused()
            // (M4, M2 E-L7) The unlimited-retry card (L19) keeps retrying behind it: the screen must stay on.
            is PlaybackState.Error -> cycle?.let { it.cardShown || it.cardLiftPending } == true
            else -> false
        }
        BoxLog.d(TAG) { "state ${s.javaClass.simpleName}" }
    }

    /** Waiting states never replace the error card shown for unlimited retries (L19). */
    private fun setActiveState(c: Cycle, s: PlaybackState) {
        if (c.cardShown && _state.value is PlaybackState.Error) return
        setState(s)
    }

    private fun PlaybackState.withTarget(np: NowPlaying): PlaybackState = when (this) {
        is PlaybackState.Playing -> copy(target = np)
        is PlaybackState.Buffering -> copy(target = np)
        is PlaybackState.Stalled -> copy(target = np)
        else -> PlaybackState.Connecting(np, ConnectPhase.RESOLVING, 1, clock.nowMs())
    }

    private fun Cycle.specForRestart(): Spec = when (val sp = spec) {
        is Spec.Archive -> if (archiveResumeAtMs > 0) Spec.Archive(sp.tune.copy(startOffsetMs = archiveResumeAtMs)) else sp
        is Spec.Live -> sp
    }

    private fun finishZapRecord(c: Cycle, outcome: String) {
        if (c.zap.recorded) return
        c.zap.recorded = true
        val decoders = zapDecoders.finish(c.cycleId, if (c.firstMediaReached) c.firstMediaAtMs else null)
        val record = ZapRecord(
            tuneId = c.firstTuneId,
            stableKey = c.identity?.stableKey?.value,
            format = session?.format ?: c.format ?: c.resolved?.formatHint ?: StreamFormat.UNKNOWN,
            resolveMs = c.zap.resolveMs ?: -1,
            leaseMs = c.zap.leaseMs ?: -1,
            firstByteMs = c.zap.firstByteMs,
            readyMs = c.zap.readyMs,
            firstFrameMs = c.zap.firstMediaMs,
            outcome = outcome,
            decoders = decoders,
        )
        eventLog.addZap(record)
        BoxLog.i(TAG) {
            "zap ${record.tuneId} ${record.format} resolve=${record.resolveMs} lease=${record.leaseMs} firstByte=${record.firstByteMs} " +
                "ready=${record.readyMs} firstMedia=${record.firstFrameMs} $outcome"
        }
        if (decoders != null) BoxLog.i(TAG) { decoders.logLine(record.tuneId) }
    }

    private fun remember(id: ChannelIdentity, change: (ChannelPlaybackMemory) -> ChannelPlaybackMemory) {
        scope.launch {
            try {
                memory.update(id, change)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.w(TAG, "channel memory write failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun safeProvider(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BoxLog.w(TAG, "provider write failed: ${e.javaClass.simpleName}")
        }
    }

    private fun traceBegin(id: Long) {
        if (Build.VERSION.SDK_INT >= 29) Trace.beginAsyncSection("zap", id.toInt())
    }

    private fun traceEnd(id: Long) {
        if (Build.VERSION.SDK_INT >= 29) Trace.endAsyncSection("zap", id.toInt())
    }

    companion object {
        private const val TAG = "PlaybackController"

        const val LIVE_PAUSE_RELEASE_MS = 10_000L
        const val RECORD_WATCHED_AFTER_MS = 5_000L

        /** (follow-up 2026-10-14) Healthy playback at a raised TS compat level before it is saved (L27b). */
        const val COMPAT_PERSIST_HEALTHY_MS = 30_000L

        /**
         * Signals that may persist a compat raise: decoder-side stalls (b1 and the readiness stall are DECODER_STUCK;
         * FROZEN_VIDEO is decoder output) and container/decoding failures. Not POSITION_STUCK (usually provider PTS),
         * PCR exhaustion, NO_BYTES or network.
         */
        fun isDecoderTypeSignal(s: FailureSignal): Boolean = when (s) {
            is FailureSignal.Stall -> s.kind == StallKind.DECODER_STUCK || s.kind == StallKind.FROZEN_VIDEO
            is FailureSignal.Parsing -> !s.manifest
            is FailureSignal.DecodingFailed -> true
            else -> false
        }
        const val PREVIOUS_CHANNEL_MIN_PLAY_MS = 1_000L

        /** (M5, B1) At most this many guard-refused neighbours are skipped by one zap step. */
        const val MAX_ZAP_GUARD_SKIPS = 64

        // Owners' constants (Appendix B), pointed at at integration.
        const val SEEK_GRACE_MS = StallConstants.SEEK_GRACE_MS
        const val LADDER_HEALTHY_RESET_MS = ErrorConstants.LADDER_HEALTHY_RESET_MS
        const val POLL_INTERVAL_MS = StatsCollector.STATS_INTERVAL_MS
        /** Buffer-decay tick (§3.6.2); the FSM owns the 10-min decay rule, this is only how often it is asked. */
        const val STABLE_TICK_MS = 60_000L
        const val MAX_CONNECTIONS_CAP = BrokerConstants.MAX_CONNECTIONS_CAP

        /** (M6) `localBuffer` is published at most this often (§7.2 "≤ 1 Hz"). */
        const val LOCAL_BUFFER_PUBLISH_MS = 1_000L

        /** (M6, §4.4) Stops a resume or Back to live restarts the writer after (not storage, not a decided failure). */
        private val RESTARTABLE_STOPS = setOf(RecordStop.FULL_WHILE_PAUSED, RecordStop.HOLD_LIMIT, RecordStop.SLOT_LOST, RecordStop.PREEMPTED)

        /** (M6, §13) Stops with a toast (the others show in the paused header line). */
        /** (Save-location integration, S4-Q5) A read failure this soon after a resume counts as "lost on resume". */
        private const val RESUME_READ_WINDOW_MS = 10_000L

        private val TOASTED_STOPS = setOf(RecordStop.SLOT_LOST, RecordStop.PREEMPTED, RecordStop.DISK_ERROR, RecordStop.STORAGE_EVICTED, RecordStop.DISK_TOO_SLOW)

        /** (M6, A-S2) Answers that mean another device holds the slot. */
        fun isLimitLike(s: FailureSignal): Boolean = when (s) {
            is FailureSignal.Http -> s.status in LIMIT_STATUSES
            is FailureSignal.TinyBody -> s.bytes == 0
            is FailureSignal.ReconnectExhausted -> s.lastStatus != null && s.lastStatus in LIMIT_STATUSES
            else -> false
        }
        private val LIMIT_STATUSES = setOf(403, 458, 503, 509)

        private val LIFECYCLE_REASONS = setOf(StopReason.BACKGROUND, StopReason.STANDBY, StopReason.HDMI_UNPLUGGED, StopReason.DISPLAY_OFF)

        fun decisionName(d: LadderDecision): String = d::class.simpleName ?: "Decision"

        /** Transient signals (L15/L17/L19/L22): reconnect, not a codec or account problem. */
        fun isTransient(s: FailureSignal): Boolean = when (s) {
            is FailureSignal.ReconnectExhausted, is FailureSignal.Network, is FailureSignal.TinyBody -> true
            is FailureSignal.Stall -> s.kind == StallKind.NO_BYTES || s.kind == StallKind.HLS_NO_PROGRESS
            is FailureSignal.Http -> s.status >= 500 || s.status == 408 || s.status == 429
            else -> false
        }

        /** The code a decision would eventually produce, for the stream-switch-timeout rule (§4.3 global rules). */
        fun eventualCode(s: FailureSignal?): PlaybackErrorCode = when (s) {
            null -> PlaybackErrorCode.NO_DATA_TIMEOUT
            is FailureSignal.Http -> when (s.status) {
                401 -> PlaybackErrorCode.AUTH_REJECTED
                403 -> PlaybackErrorCode.FORBIDDEN
                458, 509 -> PlaybackErrorCode.CONNECTION_LIMIT
                404, 410 -> if (s.origin == app.thebox.core.playback.LoadOrigin.ARCHIVE) PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE else PlaybackErrorCode.CHANNEL_OFFLINE
                405, 406 -> PlaybackErrorCode.FORMAT_REFUSED
                else -> if (s.status >= 500) PlaybackErrorCode.SERVER_ERROR else PlaybackErrorCode.NO_DATA_TIMEOUT
            }
            is FailureSignal.Network -> when (s.kind) {
                NetworkFailureKind.DNS -> PlaybackErrorCode.DNS_FAILURE
                NetworkFailureKind.TLS -> PlaybackErrorCode.TLS_FAILURE
                NetworkFailureKind.CLEARTEXT -> PlaybackErrorCode.INTERNAL
                else -> PlaybackErrorCode.NO_DATA_TIMEOUT
            }
            is FailureSignal.HtmlInsteadOfStream -> PlaybackErrorCode.WEB_PAGE_INSTEAD
            is FailureSignal.TinyBody -> PlaybackErrorCode.EMPTY_RESPONSE
            is FailureSignal.FormatIsHls, is FailureSignal.FormatUnrecognized -> PlaybackErrorCode.UNSUPPORTED_SOURCE
            is FailureSignal.DecoderInit -> if (s.video) PlaybackErrorCode.CODEC_UNSUPPORTED else PlaybackErrorCode.AUDIO_OUTPUT_FAILED
            is FailureSignal.FormatUnsupported -> if (s.video) PlaybackErrorCode.CODEC_UNSUPPORTED else PlaybackErrorCode.AUDIO_OUTPUT_FAILED
            is FailureSignal.DecodingFailed -> if (s.video) PlaybackErrorCode.STREAM_UNDECODABLE else PlaybackErrorCode.AUDIO_OUTPUT_FAILED
            is FailureSignal.AudioTrackInit, FailureSignal.AudioWrite, FailureSignal.AudioTimestampsStuck -> PlaybackErrorCode.AUDIO_OUTPUT_FAILED
            is FailureSignal.Parsing, is FailureSignal.PcrDiscontinuity, is FailureSignal.Stuck, FailureSignal.ResourcesReclaimed -> PlaybackErrorCode.STREAM_UNDECODABLE
            is FailureSignal.Credentials -> if (s.permanent) PlaybackErrorCode.NEEDS_REAUTH else PlaybackErrorCode.CREDENTIALS_UNAVAILABLE
            is FailureSignal.LocalConnectionLimit -> PlaybackErrorCode.CONNECTION_LIMIT_LOCAL
            FailureSignal.ChannelMissing -> PlaybackErrorCode.CHANNEL_NOT_FOUND
            is FailureSignal.Stall -> if (isTransient(s)) PlaybackErrorCode.NO_DATA_TIMEOUT else PlaybackErrorCode.STREAM_UNDECODABLE
            else -> PlaybackErrorCode.NO_DATA_TIMEOUT
        }

        fun gateOf(a: XtreamAccountInfo?): AccountGate = when {
            a == null -> AccountGate.UNKNOWN
            !a.auth -> AccountGate.AUTH_FAILED
            else -> when (a.status) {
                AccountStatus.ACTIVE -> AccountGate.ACTIVE
                AccountStatus.EXPIRED -> AccountGate.EXPIRED
                AccountStatus.BANNED, AccountStatus.DISABLED -> AccountGate.DISABLED
                AccountStatus.UNKNOWN -> AccountGate.UNKNOWN
            }
        }

        fun selectedFormat(tracks: Tracks, type: Int): Format? {
            for (g in tracks.groups) {
                if (g.type != type) continue
                for (i in 0 until g.length) if (g.isTrackSelected(i)) return g.getTrackFormat(i)
            }
            return null
        }

        fun hdrOf(f: Format): HdrKind {
            if (f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) return HdrKind.DOLBY_VISION
            val color = f.colorInfo ?: return HdrKind.SDR
            if (!ColorInfo.isTransferHdr(color)) return HdrKind.SDR
            return when (color.colorTransfer) {
                C.COLOR_TRANSFER_ST2084 -> HdrKind.HDR10
                C.COLOR_TRANSFER_HLG -> HdrKind.HLG
                else -> HdrKind.UNKNOWN_HDR
            }
        }
    }
}
