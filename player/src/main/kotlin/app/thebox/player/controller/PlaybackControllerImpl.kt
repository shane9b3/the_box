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
import app.thebox.core.playback.StallKind
import app.thebox.core.playback.StallVerdict
import app.thebox.core.playback.StreamFormat
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
import app.thebox.player.api.NowPlaying
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
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
    // M4 commit 0 (docs/design/m4-player-ux.md §1.7 item 4): trivial bodies; WP-C wires onCues and the previous channel.
    private val _cues = MutableStateFlow<List<androidx.media3.common.text.Cue>>(emptyList())
    override val cues: StateFlow<List<androidx.media3.common.text.Cue>> = _cues.asStateFlow()
    private val _previous = MutableStateFlow<ChannelIdentity?>(null)
    override val previous: StateFlow<ChannelIdentity?> = _previous.asStateFlow()

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
    internal val lifecycle = LifecycleCoordinator(context, this, deviceState, { settings.current.lifecycleTimeScale })

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
    private var lifecycleStopAtMs = 0L

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

    /** What a cycle plays. */
    private sealed interface Spec {
        data class Live(val target: TuneTarget) : Spec
        data class Archive(val tune: ArchiveTune) : Spec
    }

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
        var currentTuneId = 0L
        var title: String? = null
        var counters = LadderCounters()
        var attempt = 1
        var firstMediaReached = false
        var firstMediaAtMs = 0L
        var lastSignal: FailureSignal? = null
        var lastErrorAtMs = 0L
        var cardShown = false
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
        var audioDropped = false
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
    )

    // ------------------------------------------------------------------ tune API

    override fun tune(target: TuneTarget, zapList: ZapList?) {
        if (zapList != null) this.zapList = zapList
        cancelPendingZap()
        startCycle(Spec.Live(target))
    }

    override fun tuneArchive(tune: ArchiveTune) {
        cancelPendingZap()
        startCycle(Spec.Archive(tune))
    }

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
                    list.neighbour(from, delta)
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

    override fun previousChannel(): Boolean {
        val prev = previousIdentity ?: return false
        tune(TuneTarget.Channel(prev))
        return true
    }

    override fun retry() {
        val c = cycle ?: return
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
            finishZapRecord(old, "superseded")
            if (old.identity != null && old.identity != identity && old.firstMediaReached &&
                now - old.firstMediaAtMs >= PREVIOUS_CHANNEL_MIN_PLAY_MS
            ) {
                previousIdentity = old.identity
            }
        }
        cancelCycleJobs()
        val c = Cycle(spec, identity, now)
        cycle = c
        // (follow-up 2026-10-14, half of Later N-L2) Per (playlist, stable key): the same stable key in another playlist
        // is another channel and must not share the 5-min escalation windows.
        ladder.beginChannel(identity?.let { "${it.playlistId}/${it.stableKey.value}" })
        importGate.closeForZap()
        // The first-frame counter survives the tune: the shell waits for "the next one" (H-S10).
        _video.value = VideoInfo(aspectMode = settings.current.defaultAspect, renderedFirstFrames = _video.value.renderedFirstFrames)
        _tracks.value = TrackInfo()
        launchSession(c, Plan(refreshAccount = refreshAccount), waiting = null)
        c.firstTuneId = c.currentTuneId
        eventLog.add(PlaybackEventType.TUNE, c.currentTuneId, if (c.isArchive) "archive" else "live", identity?.stableKey?.value ?: "direct")
        traceBegin(c.firstTuneId)
        switchTimeoutJob = scope.launch {
            delay(settings.current.streamSwitchTimeoutMs)
            onSwitchTimeout(c)
        }
    }

    /**
     * Ends the current session (transport first: the old call is cancelled before anything new opens, §3.5.2), shows
     * [waiting] (default Connecting(RESOLVING)) and runs steps 3–7 of §3.1.1 for a new session.
     */
    private fun launchSession(c: Cycle, plan: Plan, waiting: ((NowPlaying) -> PlaybackState)?) {
        sessionJob?.cancel()
        val reuse = if (plan.reuseResolved) c.resolved else null
        endSession()
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

        // Step 6: session and MediaSource.
        val ctx = StreamSessionContext(
            tuneId = id,
            resolved = resolved,
            broker = brokers.broker(resolved.brokerKey),
            leaseKind = LeaseKind.PLAYBACK,
            sinkFactory = null,
            onEvent = { type, code, detail -> mainHandler.post { onSessionEvent(id, type, code, detail) } },
        )
        val s = TuneSession(id, resolved, ctx, transport, format, clock.nowMs())
        val built = try {
            mediaSources.build(ctx, format, if (c.tsCompat > 0) TsCompat.MULTI_PMT else TsCompat.DEFAULT, transport, c.archive?.request)
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
        p.setMediaSource(built.source, /* resetPosition = */ true)
        p.prepare()
        val archive = c.archive
        if (archive != null && c.firstSession && archive.startOffsetMs > 0) p.seekTo(archive.startOffsetMs)
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
            if (session === s) p.playWhenReady = !c.paused
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
        _video.update { it.copy(posterMode = posterMode(c)) }
    }

    private fun effectiveVideoMode(c: Cycle): VideoDecoderMode =
        if (c.softwareVideo) VideoDecoderMode.SOFTWARE_PREFERRED else c.decoderOverride ?: c.memory.videoDecoder ?: settings.current.videoDecoderMode

    /** Ends the transport of the current session, then stops the player (keeps the last frame; never clears the surface). */
    private fun endSession() {
        val s = session ?: return
        session = null
        eng?.parts?.watchdog?.stop()
        s.end()
        player?.stop()
    }

    private fun clipDelay(c: Cycle, delayMs: Long): Long {
        if (c.firstMediaReached) return delayMs
        val left = c.startedAtMs + settings.current.streamSwitchTimeoutMs - clock.nowMs()
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

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width == 0 && videoSize.height == 0) return
            _video.update { it.copy(width = videoSize.width, height = videoSize.height, pixelRatio = videoSize.pixelWidthHeightRatio) }
        }

        override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
            val s = session ?: return
            s.audioAdvancingSeen = true
            if (s.milestoneVideo == false) onFirstMedia(s)
        }

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
                if (st is PlaybackState.Error && !c.cardShown) return
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
                    setState(PlaybackState.Playing(nowPlaying(c), paused = true, warning = c.warning))
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
        val stats = safeStats()
        val facts = FailureClassifier.Facts(
            currentTuneId = s.tuneId,
            sessionStartMs = s.startedAtMs,
            isArchive = c.isArchive,
            isHls = s.format == StreamFormat.HLS,
            lastLoadDataType = s.lastLoadDataType,
            bytesReceived = s.telemetry?.bytesTotal ?: 0,
            passthroughActive = stats.audioPassthrough,
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
        onFailure(c, FailureSignal.Stall(verdict.kind), null)
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
            // WP-B emits no FIRST_BYTE event; its telemetry stamps the first byte on the same monotonic clock.
            val firstByteAt = s.telemetry?.firstByteAtMs ?: 0L
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
        if (c.cardShown) {
            c.cardShown = false
            if (player?.playbackState == Player.STATE_READY) setState(PlaybackState.Playing(nowPlaying(c), c.paused, c.warning))
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
            setState(PlaybackState.Error(nowPlaying(c), enrich(c, error, signal)))
        } else {
            fail(c, error, signal)
        }
    }

    // ------------------------------------------------------------------ ladder (§4.3)

    private fun onFailure(c: Cycle, signal: FailureSignal, error: PlaybackException?) {
        if (cycle !== c) return
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
            now - c.startedAtMs >= settings.current.streamSwitchTimeoutMs &&
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
            firstFrameRendered = c.firstMediaReached,
            isLive = !c.isArchive,
            isArchive = c.isArchive,
            format = s?.format ?: c.format ?: r?.formatHint ?: StreamFormat.UNKNOWN,
            // WP-A: resolved.xtream != null; an XTREAM playlist always resolves with it (the type covers a failed resolve).
            isXtream = r?.xtream != null || c.playlistInfo?.type == PlaylistType.XTREAM,
            baseCount = r?.baseCount ?: 1,
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
        when (d) {
            is LadderDecision.Fail -> fail(c, d.error, signal)
            is LadderDecision.RetrySame -> restart(c, d, signal, Plan(delayMs = d.delayMs, reuseResolved = c.resolved != null))
            is LadderDecision.ReResolve -> {
                if (d.rotateBase) {
                    val r = c.resolved
                    if (r != null && r.baseCount > 1) c.baseIndex = (r.baseIndex + 1) % r.baseCount
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
                }
                restart(c, d, signal, Plan(reuseResolved = c.resolved != null))
            }
            LadderDecision.DisableTunnelingAndReprepare -> {
                c.tunnelingOff = true
                _notices.tryEmit(PlaybackNotice.TunnelingDisabled)
                restart(c, d, signal, Plan(reuseResolved = c.resolved != null))
            }
            LadderDecision.SoftwareVideoAndReprepare -> {
                c.softwareVideo = true
                c.warning = PlaybackWarning.SOFTWARE_VIDEO
                _notices.tryEmit(PlaybackNotice.VideoDecoderSwitchedToSoftware)
                restart(c, d, signal, Plan(reuseResolved = c.resolved != null))
            }
            is LadderDecision.DisablePassthroughAndReprepare -> {
                routeDeniedPassthrough += d.mimeType ?: "*"
                c.warning = PlaybackWarning.PASSTHROUGH_DISABLED
                _notices.tryEmit(PlaybackNotice.PassthroughDisabled(d.mimeType))
                restart(c, d, signal, Plan(reuseResolved = c.resolved != null))
            }
            is LadderDecision.SoftwareAudioAndReprepare -> {
                c.softwareAudioMimes += d.mimeType
                _notices.tryEmit(PlaybackNotice.AudioDecoderSwitched(toSoftware = true, mimeType = d.mimeType))
                restart(c, d, signal, Plan(reuseResolved = c.resolved != null))
            }
            LadderDecision.StereoAndReprepare -> {
                c.forceStereo = true
                restart(c, d, signal, Plan(reuseResolved = c.resolved != null))
            }
            is LadderDecision.DropAudioAndReprepare -> {
                c.audioDropped = true
                c.warning = d.warning
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
    }

    private fun fail(c: Cycle, error: PlaybackError, signal: FailureSignal?) {
        if (cycle !== c) return
        val enriched = enrich(c, error, signal)
        sessionJob?.cancel()
        switchTimeoutJob?.cancel()
        livePauseJob?.cancel()
        endSession()
        stopPolling()
        c.cardShown = false
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
        return error.copy(detail = detail, actions = actions)
    }

    // ------------------------------------------------------------------ pause, resume, stop

    override fun pause() {
        val c = cycle ?: return
        val st = _state.value
        if (st !is PlaybackState.Playing && st !is PlaybackState.Buffering && st !is PlaybackState.Connecting && st !is PlaybackState.Stalled) return
        if (c.paused) return
        c.paused = true
        player?.playWhenReady = false
        setState(PlaybackState.Playing(nowPlaying(c), paused = true, warning = c.warning))
        if (!c.isArchive) {
            livePauseJob?.cancel()
            livePauseJob = scope.launch {
                delay(LIVE_PAUSE_RELEASE_MS)
                if (cycle === c && c.paused) {
                    BoxLog.i(TAG) { "live pause: releasing the connection" }
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
            else -> Unit
        }
    }

    override fun stop(reason: StopReason) {
        val c = cycle
        cancelPendingZap()
        cancelCycleJobs()
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
    }

    private fun cancelCycleJobs() {
        sessionJob?.cancel()
        switchTimeoutJob?.cancel()
        livePauseJob?.cancel()
        healthyJob?.cancel()
        compatPersistJob?.cancel()
        recordWatchedJob?.cancel()
    }

    // ------------------------------------------------------------------ lifecycle target (§3.10)

    override fun isPlaybackActive(): Boolean = when (val st = _state.value) {
        is PlaybackState.Connecting, is PlaybackState.Buffering, is PlaybackState.Playing, is PlaybackState.Stalled -> true
        is PlaybackState.Error -> cycle?.cardShown == true
        else -> false
    }

    override fun stopForLifecycle(reason: StopReason) {
        lifecycleStopAtMs = clock.nowMs()
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
        if (isPlaybackActive() && session != null) {
            eventLog.add(PlaybackEventType.DECISION, c.currentTuneId, "Reprepare", "decoder override")
            launchSession(c, Plan(reuseResolved = c.resolved != null), waiting = { np -> _state.value.withTarget(np) })
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
        )
    }

    private fun safeStats(): StreamStats = try {
        eng?.parts?.stats() ?: StreamStats()
    } catch (e: Exception) {
        StreamStats()
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
            is PlaybackState.Playing -> !s.paused
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
        )
        eventLog.addZap(record)
        BoxLog.i(TAG) {
            "zap ${record.tuneId} ${record.format} resolve=${record.resolveMs} lease=${record.leaseMs} firstByte=${record.firstByteMs} " +
                "ready=${record.readyMs} firstMedia=${record.firstFrameMs} $outcome"
        }
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

        // Owners' constants (Appendix B), pointed at at integration.
        const val SEEK_GRACE_MS = StallConstants.SEEK_GRACE_MS
        const val LADDER_HEALTHY_RESET_MS = ErrorConstants.LADDER_HEALTHY_RESET_MS
        const val POLL_INTERVAL_MS = StatsCollector.STATS_INTERVAL_MS
        /** Buffer-decay tick (§3.6.2); the FSM owns the 10-min decay rule, this is only how often it is asked. */
        const val STABLE_TICK_MS = 60_000L
        const val MAX_CONNECTIONS_CAP = BrokerConstants.MAX_CONNECTIONS_CAP

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
            is FailureSignal.AudioTrackInit, FailureSignal.AudioWrite -> PlaybackErrorCode.AUDIO_OUTPUT_FAILED
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
