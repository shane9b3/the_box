package app.thebox.playback

import app.thebox.core.catchup.ArchiveRequest
import app.thebox.core.catchup.ArchiveWindow
import app.thebox.core.catchup.CatchupBuild
import app.thebox.core.catchup.CatchupTarget
import app.thebox.core.catchup.CatchupUrlBuilder
import app.thebox.core.catchup.archive.ActiveRequest
import app.thebox.core.catchup.archive.ArchiveFailure
import app.thebox.core.catchup.archive.ArchiveFrames
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchivePhase.CONTINUING
import app.thebox.core.catchup.archive.ArchivePhase.FAILED
import app.thebox.core.catchup.archive.ArchivePhase.IDLE
import app.thebox.core.catchup.archive.ArchivePhase.PAUSED
import app.thebox.core.catchup.archive.ArchivePhase.PAUSED_LIVE
import app.thebox.core.catchup.archive.ArchivePhase.PLAYING
import app.thebox.core.catchup.archive.ArchivePhase.PROBING
import app.thebox.core.catchup.archive.ArchivePhase.SEEKING
import app.thebox.core.catchup.archive.ArchivePhase.STARTING
import app.thebox.core.catchup.archive.ArchivePlanner
import app.thebox.core.catchup.archive.ArchivePlannerImpl
import app.thebox.core.catchup.archive.ArchiveNotice
import app.thebox.core.catchup.archive.ArchivePlayback
import app.thebox.core.catchup.archive.ArchiveStart
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.catchup.archive.CatchupProbeStore
import app.thebox.core.catchup.archive.ContinuePlan
import app.thebox.core.catchup.archive.LiveWindow
import app.thebox.core.catchup.archive.PositionView
import app.thebox.core.catchup.archive.ProbePlan
import app.thebox.core.catchup.archive.RequestPlan
import app.thebox.core.catchup.archive.SeekPlan
import app.thebox.core.common.BoxLog
import app.thebox.core.full.ProgrammeFeed
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.rawEndMin
import app.thebox.core.model.TimeBase
import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.StreamFormat
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.player.api.ArchiveTargets
import app.thebox.player.api.ArchiveTune
import app.thebox.player.api.CatchupTargetResult
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.PositionSnapshot
import app.thebox.player.api.StopReason
import app.thebox.player.api.TuneTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.ContinuationInterceptor

/**
 * The catch-up orchestrator (docs/design/m4-player-ux.md §3.8.2). Owns one session at a time, executes the planner's
 * plans against the controller, observes `controller.state`, polls `position()` while active and publishes [status]
 * (incl. `ownedTuneId` and `settledSeq`, B1/B3). Carries the per-session aspect and pause across hand-offs (S2).
 *
 * Threading (§2.6): every field below is main-confined. [scope] runs on the main dispatcher (`Dispatchers.Main.immediate`
 * in production); the suspend entry points hop onto it after their lookups. Target, time-base and probe-store lookups
 * are main-safe suspend calls; the first request of a session is built on [io], the hand-off requests (seek,
 * continuation, probe, resume) are built on main in the same turn as their `tuneArchive` (the builder is pure string
 * formatting), which is what keeps B1's "phase → tuneArchive → tuneId → status" sequence in one main-thread turn.
 *
 * Never logs a URL: only variant names, phase names and error codes.
 */
class ArchivePlaybackImpl(
    private val controller: PlaybackController,
    private val targets: ArchiveTargets,
    private val builder: CatchupUrlBuilder,
    private val timeBases: TimeBaseLookup,
    private val probeStore: CatchupProbeStore,
    private val feed: ProgrammeFeed,
    private val planner: ArchivePlanner,
    /** Main-confined (production: application scope + `Dispatchers.Main.immediate`). */
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
) : ArchivePlayback {

    private val _status = MutableStateFlow(ArchiveStatus())
    override val status: StateFlow<ArchiveStatus> = _status.asStateFlow()

    private val _liveNotReady = MutableSharedFlow<ArchiveNotice>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * The lag rule gave up near the live edge and tuned live (§3.8.7): the UI toasts ARCHIVE_NOT_READY. Not on the
     * [ArchivePlayback] contract (contract gap reported to the lead); the status alone shows only `IDLE`.
     */
    override val notices: SharedFlow<ArchiveNotice> = _liveNotReady.asSharedFlow()

    private val mainContext = scope.coroutineContext[ContinuationInterceptor] ?: io

    // ------------------------------------------------------------------ session state (main only)

    /** Everything a session needs about its channel, loaded once (suspend lookups). */
    private class Ctx(
        val channel: GuideChannel,
        /** With `winner` filled from the probe store (the resolver passes null). */
        val target: CatchupTarget,
        val timeBase: TimeBase,
        val frames: ArchiveFrames,
        val variants: List<String>,
        val days: Int,
        val storedWinner: String?,
    )

    private sealed interface CtxResult {
        class Ok(val ctx: Ctx) : CtxResult
        class Failed(val reason: ArchiveFailure) : CtxResult
    }

    private class Built(val index: Int, val ok: CatchupBuild.Ok)

    private inner class Session(val channel: GuideChannel, val timeShift: Boolean) {
        var ctx: Ctx? = null
        /** [Ctx.frames] once loaded; before that (a live pause still loading) only the display shift. */
        var frames = ArchiveFrames(0, 0, channel.epgShiftMin * MINUTE_MS)
        var ctxJob: Deferred<CtxResult>? = null

        var phase: ArchivePhase = IDLE
        var variantIndex = 0
        var request: ActiveRequest? = null
        var format: StreamFormat = StreamFormat.UNKNOWN
        /** The plan of the current request with the target it actually used (padded first requests target the origin). */
        var lastPlan: RequestPlan? = null
        var ownedTuneId = 0L

        /** First media of the request currently issued (B3 re-request settle, S3 "before first media"). */
        var requestFirstMedia = false
        /** First media of any request of this session (the variant worked once: no more probing, §3.8.8 rule 2). */
        var firstMediaSeen = false

        /** Programme-frame playhead, valid at [wallAtMs] (device clock). */
        var wallMs = 0L
        var wallAtMs = 0L
        var programme: ProgrammeInfo? = null
        var rescopeMin = Int.MIN_VALUE
        var lastPos: PositionSnapshot? = null

        var lagRetries = 0
        var midPlayRetries = 0
        var playingSinceMs = 0L

        // Live pause (§3.8.7): the device instant on screen (now − live buffer) and when the pause began.
        var livePauseDeviceMs = 0L
        var pausedSinceMs = 0L
        // Archive pause.
        var archivePausedSinceMs = 0L
        /** Pause again at first media of the next request (seek or hand-off committed while paused, S2/E26). */
        var pauseAtFirstMedia = false
        /** We asked the controller to pause (tells a user pause from STATE_ENDED reported as paused). */
        var pauseRequested = false

        /** The aspect on screen when the last hand-off was issued; re-applied unremembered (S2). */
        var aspect: AspectMode? = null
        var winnerStored = false
        var diagnosticsLogged = false

        var failure: ArchiveFailure? = null
        var error: PlaybackError? = null

        // Native seek settle (B3): device time of controller.seekTo, or −1.
        var nativeSeekAtMs = -1L
        var nativeSeekLastPos = 0L
        var nativeSeekWasPaused = false
        /** A re-request seek is in flight: settles at its first media. */
        var rerequestSeekPending = false

        val pausedAtMs: Long get() = frames.liveEdge(livePauseDeviceMs).coerceAtLeast(0)
    }

    private var session: Session? = null
    private var stateJob: Job? = null
    private var pollJob: Job? = null
    private var pendingSeq = 0L
    private var settledSeq = 0L
    /** Increments on every suspend start; a start that loses the race to a later one does not tune. */
    private var startGen = 0L

    // ------------------------------------------------------------------ entry points

    override suspend fun playProgramme(channel: GuideChannel, info: ProgrammeInfo, rawEndMin: Int, timeShift: Boolean): ArchiveStart =
        withContext(mainContext) {
            val gen = ++startGen
            when (val r = loadContext(channel)) {
                is CtxResult.Failed -> ArchiveStart.Failed(r.reason)
                is CtxResult.Ok -> startProgramme(r.ctx, info, rawEndMin, timeShift, gen)
            }
        }

    override suspend fun startOver(channel: GuideChannel): ArchiveStart = withContext(mainContext) {
        val gen = ++startGen
        val ctx = when (val r = loadContext(channel)) {
            is CtxResult.Failed -> return@withContext ArchiveStart.Failed(r.reason)
            is CtxResult.Ok -> r.ctx
        }
        // The programme on now as the guide shows it (EPG UTC on the panel-corrected device clock, display frame).
        val nowDisplayMin = Math.floorDiv(clock() + ctx.frames.skewMs + ctx.frames.displayShiftMs, MINUTE_MS).toInt()
        val info = try {
            feed.at(channel, nowDisplayMin)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BoxLog.w(TAG, "start over: programme lookup failed: ${e.javaClass.simpleName}")
            null
        } ?: return@withContext ArchiveStart.Failed(ArchiveFailure.NO_PROGRAMME)
        if (gen != startGen) return@withContext ArchiveStart.Started
        startProgramme(ctx, info, info.rawEndMin, timeShift = true, gen)
    }

    override suspend fun rewindLive(channel: GuideChannel, backMs: Long): ArchiveStart = withContext(mainContext) {
        val gen = ++startGen
        val ctx = when (val r = loadContext(channel)) {
            is CtxResult.Failed -> return@withContext ArchiveStart.Failed(r.reason)
            is CtxResult.Ok -> r.ctx
        }
        val now = clock()
        val live = liveWindow(ctx, now)
        // Live-anchored: taken from the panel's now (B2); the builder subtracts the correction again.
        val t = (live.liveEdgeMs - backMs.coerceAtLeast(0)).coerceAtLeast(live.archiveFromMs)
        val plan = RequestPlan(startMs = t, stopMs = null, padBefore = false, targetMs = t)
        val built = withContext(io) { buildFrom(ctx, plan, 0, walk = true, now = now) }
            ?: return@withContext ArchiveStart.Failed(ArchiveFailure.UNSUPPORTED_MODE)
        if (gen != startGen) return@withContext ArchiveStart.Started
        val s = newSession(ctx, timeShift = true)
        issue(s, plan, STARTING, built)
        ArchiveStart.Started
    }

    override fun pauseLive(channel: GuideChannel) {
        val now = clock()
        val buffered = controller.position().bufferedMs.coerceAtLeast(0)
        startGen++
        // Before the phase changes: a collector started later would read the not-yet-frozen live state as foreign.
        ensureCollecting()
        val s = Session(channel, timeShift = true)
        replaceSession(s)
        s.phase = PAUSED_LIVE
        // N3: what is on screen is behind the panel's now by the live buffer.
        s.livePauseDeviceMs = now - buffered
        s.pausedSinceMs = now
        s.wallAtMs = now
        val job = scope.async {
            try {
                loadContext(channel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // resume() then falls back to M2's freeze-and-rejoin.
                BoxLog.w(TAG, "live pause: catch-up lookup failed: ${e.javaClass.simpleName}")
                CtxResult.Failed(ArchiveFailure.NO_ARCHIVE)
            }
        }
        s.ctxJob = job
        scope.launch {
            val r = try { job.await() } catch (e: CancellationException) { return@launch }
            if (r is CtxResult.Ok && session === s && s.ctx == null) {
                adopt(s, r.ctx)
                if (s.phase == PAUSED_LIVE) {
                    publish(s)
                    rescope(s, s.pausedAtMs)
                }
            }
        }
        controller.pause()
        publish(s)
    }

    override fun pause() {
        val s = session ?: return
        when (s.phase) {
            PLAYING -> {
                snapshotWall(s)
                s.pauseRequested = true
                s.phase = PAUSED
                s.archivePausedSinceMs = clock()
                stopPolling()
                controller.pause()
                publish(s)
            }
            SEEKING -> if (s.nativeSeekAtMs >= 0) {
                s.pauseRequested = true
                s.nativeSeekWasPaused = true
                controller.pause()
            } else {
                s.pauseAtFirstMedia = true
            }
            STARTING, CONTINUING, PROBING -> s.pauseAtFirstMedia = true
            else -> Unit
        }
    }

    override fun resume() {
        val s = session ?: return
        when (s.phase) {
            PAUSED_LIVE -> resumeLivePause(s)
            PAUSED -> {
                val now = clock()
                if (s.requestFirstMedia && now - s.archivePausedSinceMs >= ARCHIVE_PAUSE_REBUILD_MS) {
                    // Tokens and panel sessions expire: rebuild from the playhead instead of controller.resume() (§3.8.7).
                    val wall = s.wallMs
                    s.pauseAtFirstMedia = false
                    issueOrFail(s, RequestPlan(wall, null, padBefore = false, targetMs = wall), CONTINUING)
                    return
                }
                s.pauseRequested = false
                val st = controller.state.value
                controller.resume()
                if (st is PlaybackState.Playing && st.paused) {
                    s.phase = PLAYING
                    s.playingSinceMs = now
                    s.wallAtMs = now
                    startPolling(s)
                    publish(s)
                }
                // Stopped (lifecycle): the controller restarts the request; the collector moves to PLAYING at Playing.
            }
            SEEKING -> if (s.nativeSeekAtMs >= 0 && s.nativeSeekWasPaused) {
                s.pauseRequested = false
                s.nativeSeekWasPaused = false
                controller.resume()
            } else {
                s.pauseAtFirstMedia = false
            }
            STARTING, CONTINUING, PROBING -> s.pauseAtFirstMedia = false
            // Error card Retry on an archive error (§3.7, F5): re-request open-ended from the last playhead.
            FAILED -> retryFailed(s)
            else -> Unit
        }
    }

    override fun seekTo(programmeMs: Long, seq: Long) {
        if (seq > pendingSeq) pendingSeq = seq
        val s = session
        if (s == null || s.phase == IDLE) {
            settle(null)
            return
        }
        val ctx = s.ctx
        if (s.phase == PAUSED_LIVE) {
            if (ctx == null) { settle(s); publish(s); return }
            seekFromLivePause(s, ctx, programmeMs)
            return
        }
        val req = s.request
        if (ctx == null || req == null) { settle(s); publish(s); return }
        val now = clock()
        val live = liveWindow(ctx, now)
        val wall = currentWall(s, now)
        val pos = if (s.requestFirstMedia && s.phase != FAILED) positionView(s, controller.position()) else PositionView(0, null, false, false)
        val wasPaused = s.phase == PAUSED || s.pauseAtFirstMedia || (s.phase == SEEKING && s.nativeSeekWasPaused)
        when (val plan = planner.seek(programmeMs, wall, req, pos, live)) {
            SeekPlan.None -> { settle(s); publish(s) }
            SeekPlan.Live -> { settle(s); goLive(s, notReady = false) }
            is SeekPlan.Native -> {
                if (!s.requestFirstMedia || s.phase == FAILED) {
                    val t = req.originMs + plan.positionMs
                    seekRerequest(s, RequestPlan(t, null, padBefore = false, targetMs = t), wasPaused)
                    return
                }
                s.phase = SEEKING
                s.rerequestSeekPending = false
                s.nativeSeekAtMs = now
                s.nativeSeekLastPos = plan.positionMs
                s.nativeSeekWasPaused = wasPaused
                s.wallMs = req.originMs + plan.positionMs
                s.wallAtMs = now
                controller.seekTo(plan.positionMs)
                restartPolling(s)
                publish(s)
            }
            is SeekPlan.Rerequest -> seekRerequest(s, plan.plan, wasPaused)
        }
    }

    override fun jumpToLive() {
        val s = session ?: return
        if (s.phase == IDLE) return
        goLive(s, notReady = false)
    }

    override fun tryNextVariant(): Boolean {
        val s = session ?: return false
        val ctx = s.ctx ?: return false
        if (s.phase == IDLE || s.phase == PAUSED_LIVE) return false
        if (s.variantIndex + 1 >= ctx.variants.size) return false
        val plan = if (s.firstMediaSeen) {
            val wall = currentWall(s, clock())
            RequestPlan(wall, null, padBefore = false, targetMs = wall)
        } else {
            s.lastPlan ?: return false
        }
        val built = buildFrom(ctx, plan, s.variantIndex + 1, walk = true, now = clock())
        if (built == null) {
            s.variantIndex = ctx.variants.size - 1
            fail(s, ArchiveFailure.NOT_IN_ARCHIVE, s.error)
            return false
        }
        issue(s, plan, PROBING, built)
        return true
    }

    // ------------------------------------------------------------------ starting a session

    private suspend fun loadContext(channel: GuideChannel): CtxResult {
        val identity = channel.identity
        val target = when (val r = targets.catchupTarget(identity)) {
            CatchupTargetResult.ChannelGone -> return CtxResult.Failed(ArchiveFailure.CHANNEL_GONE)
            CatchupTargetResult.CredentialsUnavailable -> return CtxResult.Failed(ArchiveFailure.CREDENTIALS)
            is CatchupTargetResult.Found -> r.target
        }
        if (!target.catchup.isAvailable) return CtxResult.Failed(ArchiveFailure.NO_ARCHIVE)
        // The playlist vanished between the target lookup and here: the channel went with it (M3 semantics).
        val timeBase = timeBases.timeBase(identity.playlistId) ?: return CtxResult.Failed(ArchiveFailure.CHANNEL_GONE)
        val winner = try {
            probeStore.winner(identity.playlistId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BoxLog.w(TAG, "probe winner lookup failed: ${e.javaClass.simpleName}")
            null
        }
        val withWinner = target.copy(winner = winner)
        val variants = builder.variants(withWinner)
        if (variants.isEmpty()) return CtxResult.Failed(ArchiveFailure.UNSUPPORTED_MODE)
        val frames = planner.frames(timeBase, target.catchup.correctionHours, channel.epgShiftMin)
        return CtxResult.Ok(Ctx(channel, withWinner, timeBase, frames, variants, target.catchup.days, winner))
    }

    private suspend fun startProgramme(ctx: Ctx, info: ProgrammeInfo, rawEndMin: Int, timeShift: Boolean, gen: Long): ArchiveStart {
        val now = clock()
        val live = liveWindow(ctx, now)
        val rawStartMs = info.rawStartMin.toLong() * MINUTE_MS
        val rawEndMs = rawEndMin.toLong() * MINUTE_MS
        // §3.8.9 step 2; the builder repeats the depth check without padding.
        if (rawStartMs < live.archiveFromMs - ArchivePlannerImpl.EDGE_MARGIN_MS) return ArchiveStart.Failed(ArchiveFailure.OUTSIDE_ARCHIVE)
        // Not started on the panel's clock (a negative correction moves the archive behind the EPG, §3.8.1).
        if (rawStartMs > live.liveEdgeMs) return ArchiveStart.Failed(ArchiveFailure.NO_PROGRAMME)
        val plan = planner.programmeRequest(rawStartMs, rawEndMs.takeIf { it <= live.liveEdgeMs }, live)
        val built = withContext(io) { buildFrom(ctx, plan, 0, walk = true, now = now) }
            ?: return ArchiveStart.Failed(ArchiveFailure.UNSUPPORTED_MODE)
        if (gen != startGen) return ArchiveStart.Started
        val s = newSession(ctx, timeShift)
        s.programme = info
        s.rescopeMin = info.startMin
        issue(s, plan, STARTING, built)
        return ArchiveStart.Started
    }

    private fun newSession(ctx: Ctx, timeShift: Boolean): Session {
        val s = Session(ctx.channel, timeShift)
        adopt(s, ctx)
        replaceSession(s)
        ensureCollecting()
        return s
    }

    private fun adopt(s: Session, ctx: Ctx) {
        s.ctx = ctx
        s.frames = ctx.frames
    }

    private fun replaceSession(s: Session) {
        stopPolling()
        session?.ctxJob?.cancel()
        session = s
    }

    private fun ensureCollecting() {
        if (stateJob?.isActive == true) return
        stateJob = scope.launch { controller.state.collect { onState(it) } }
    }

    // ------------------------------------------------------------------ building and issuing (B1)

    /**
     * Builds [plan] from variant [from] on (walking over `Unsupported` when [walk]). Non-padded plans start at
     * `alignStart` for the variant actually built (§3.8.3).
     */
    private fun buildFrom(ctx: Ctx, plan: RequestPlan, from: Int, walk: Boolean, now: Long): Built? {
        val live = liveWindow(ctx, now)
        var i = from
        while (i < ctx.variants.size) {
            val v = ctx.variants[i]
            val start = if (plan.padBefore) plan.startMs else alignedStart(ctx, v, plan.startMs, live)
            val tb = if (plan.padBefore) ctx.timeBase else ctx.timeBase.copy(paddingBefore = Duration.ZERO)
            val window = ArchiveWindow(
                programmeStart = Instant.ofEpochMilli(start),
                programmeStop = plan.stopMs?.let(Instant::ofEpochMilli),
                deviceNow = Instant.ofEpochMilli(now),
            )
            when (val b = builder.build(ctx.target, tb, window, v)) {
                is CatchupBuild.Ok -> return Built(i, b)
                is CatchupBuild.Unsupported -> {
                    BoxLog.i(TAG) { "variant $v unsupported: ${b.reason}" }
                    if (!walk) return null
                }
            }
            i++
        }
        return null
    }

    private fun alignedStart(ctx: Ctx, variant: String, startMs: Long, live: LiveWindow): Long {
        val aligned = planner.alignStart(variant, startMs, ctx.frames)
        // A floor below the archive edge would be refused by the builder: one minute later is still aligned.
        return if (aligned < live.archiveFromMs - ArchivePlannerImpl.EDGE_MARGIN_MS) aligned + MINUTE_MS else aligned
    }

    /** Builds with the session's current variant (no walk) and issues, or fails the session as UNSUPPORTED_MODE. */
    private fun issueOrFail(s: Session, plan: RequestPlan, phase: ArchivePhase) {
        val ctx = s.ctx ?: return
        val built = buildFrom(ctx, plan, s.variantIndex, walk = false, now = clock())
        if (built == null) {
            fail(s, ArchiveFailure.UNSUPPORTED_MODE, s.error)
            return
        }
        issue(s, plan, phase, built)
    }

    /**
     * (reviewed 2026-10-15, B1) Sets the next phase, calls `tuneArchive`, reads the new cycle's tuneId from
     * `controller.state.value` in the same main-thread turn (startCycle emits Connecting synchronously), then publishes.
     */
    private fun issue(s: Session, plan: RequestPlan, nextPhase: ArchivePhase, built: Built) {
        val ctx = s.ctx ?: return
        val req: ArchiveRequest = built.ok.request
        val f = ctx.frames
        if (!s.diagnosticsLogged && built.ok.diagnostics.isNotEmpty()) {
            s.diagnosticsLogged = true
            BoxLog.i(TAG) { "catch-up diagnostics: ${built.ok.diagnostics.joinToString { it.code }}" }
        }
        // xc-path/xc-php format the start at minute resolution: the media origin is the floored minute (§3.8.3).
        val originA = originArchiveMs(req)
        val active = ActiveRequest(
            variant = req.variant,
            originMs = f.programmeOf(originA),
            endMs = req.requestEnd?.let { f.programmeOf(it.toEpochMilli()) },
            nativeFromMs = req.nativeSeekRange?.let { f.programmeOf(it.first * 1000) },
            nativeToMs = req.nativeSeekRange?.let { f.programmeOf(it.last * 1000) },
        )
        // The first request of a programme plays from its padded start (§3.8.9 step 3): the playhead is the origin.
        val targetMs = if (plan.padBefore) active.originMs else plan.targetMs
        val offset = planner.startOffset(targetMs, originA, f)
        val now = clock()

        // S2: the aspect on screen before a hand-off on this channel is carried, unremembered.
        val current = controller.state.value.target
        if (current != null && current.identity == ctx.channel.identity) s.aspect = controller.video.value.aspectMode

        s.phase = nextPhase
        s.variantIndex = built.index
        s.request = active
        s.format = formatOf(req)
        s.lastPlan = plan.copy(targetMs = targetMs)
        s.requestFirstMedia = false
        s.wallMs = targetMs
        s.wallAtMs = now
        s.lastPos = null
        s.nativeSeekAtMs = -1
        s.pauseRequested = false
        stopPolling()
        BoxLog.i(TAG) { "request ${nextPhase.name} variant ${req.variant} (${built.index + 1}/${ctx.variants.size}) offset ${offset}ms" }

        controller.tuneArchive(ArchiveTune(ctx.channel.identity, req, offset))
        val np = controller.state.value.target
        if (np != null && np.isArchive && np.identity == ctx.channel.identity && np.tuneId > s.ownedTuneId) s.ownedTuneId = np.tuneId
        reapplyAspect(s)
        publish(s)
        rescope(s, targetMs)
    }

    private fun originArchiveMs(req: ArchiveRequest): Long {
        val start = req.requestStart.toEpochMilli()
        return if (ArchivePlannerImpl.isMinuteVariant(req.variant)) Math.floorDiv(start, MINUTE_MS) * MINUTE_MS else start
    }

    /** `.m3u8` (or an HLS template) archives: a moving EVENT window seeks by re-request only (§3.8.4). */
    private fun formatOf(req: ArchiveRequest): StreamFormat {
        val path = req.url.substringBefore('?').substringBefore('#')
        return when {
            path.endsWith(".m3u8", ignoreCase = true) -> StreamFormat.HLS
            path.endsWith(".mpd", ignoreCase = true) -> StreamFormat.DASH
            path.endsWith(".ts", ignoreCase = true) -> StreamFormat.TS
            else -> StreamFormat.UNKNOWN
        }
    }

    private fun reapplyAspect(s: Session) {
        val a = s.aspect ?: return
        if (controller.video.value.aspectMode != a) controller.setAspectMode(a, remember = false)
    }

    // ------------------------------------------------------------------ controller states

    private fun onState(st: PlaybackState) {
        val s = session ?: return
        if (s.phase == IDLE) return
        if (!owns(s, st)) {
            BoxLog.i(TAG) { "session ended: ${st.javaClass.simpleName} is not ours" }
            end(s)
            return
        }
        if (s.phase == PAUSED_LIVE) return
        when (st) {
            is PlaybackState.Playing -> onPlaying(s, st)
            is PlaybackState.Error -> onError(s, st.error)
            is PlaybackState.Stopped -> onLifecycleStop(s)
            else -> Unit // Connecting / Buffering / Stalled: the phase stays (§3.8.2)
        }
    }

    /** §3.8.2 ownership rule. */
    private fun owns(s: Session, st: PlaybackState): Boolean {
        val t = st.target ?: return false
        if (t.identity != s.channel.identity) return false
        if (s.phase == PAUSED_LIVE) {
            if (t.isArchive) return false
            return when (st) {
                is PlaybackState.Playing -> st.paused
                is PlaybackState.Stopped -> st.reason == StopReason.LIVE_PAUSE_TIMEOUT
                // The live freeze before first media (pause kept as Connecting, M2 E-L2).
                is PlaybackState.Connecting, is PlaybackState.Buffering, is PlaybackState.Stalled -> true
                else -> false
            }
        }
        if (!t.isArchive || t.tuneId < s.ownedTuneId) return false
        if (st is PlaybackState.Stopped && st.reason !in LIFECYCLE_REASONS) return false
        return true
    }

    private fun onPlaying(s: Session, st: PlaybackState.Playing) {
        if (st.ended || (st.paused && !s.pauseRequested && looksEnded(s))) {
            onEnded(s)
            return
        }
        if (!s.requestFirstMedia) {
            onFirstMedia(s, st)
            return
        }
        val now = clock()
        when (s.phase) {
            PLAYING -> if (st.paused) {
                // Paused from elsewhere (media session, §8 item 7): follow it.
                snapshotWall(s)
                s.phase = PAUSED
                s.archivePausedSinceMs = now
                stopPolling()
                publish(s)
            }
            PAUSED -> if (!st.paused) {
                // controller.resume() or a lifecycle restart reached media again.
                s.pauseRequested = false
                s.phase = PLAYING
                s.playingSinceMs = now
                s.wallAtMs = now
                startPolling(s)
                publish(s)
            }
            FAILED -> {
                // The ladder recovered behind the card (unlimited retries, RetryInfo.maxAttempts == 0).
                s.failure = null
                s.error = null
                s.phase = if (st.paused) PAUSED else PLAYING
                s.playingSinceMs = now
                s.wallAtMs = now
                if (!st.paused) startPolling(s)
                publish(s)
            }
            else -> Unit // SEEKING (native): settles by polling (B3)
        }
    }

    private fun onFirstMedia(s: Session, st: PlaybackState.Playing) {
        val ctx = s.ctx ?: return
        val now = clock()
        s.requestFirstMedia = true
        s.firstMediaSeen = true
        s.lagRetries = 0
        s.failure = null
        s.error = null
        val variant = ctx.variants[s.variantIndex]
        // §3.8.8: persist only a non-first variant that reached first media, once per session (S3, E13).
        if (s.variantIndex > 0 && !s.winnerStored && variant != ctx.storedWinner) {
            s.winnerStored = true
            val playlistId = ctx.channel.identity.playlistId
            scope.launch {
                try {
                    probeStore.setWinner(playlistId, variant)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    BoxLog.w(TAG, "storing the catch-up variant failed: ${e.javaClass.simpleName}")
                }
            }
        }
        reapplyAspect(s)
        if (s.rerequestSeekPending) {
            s.rerequestSeekPending = false
            settle(s)
        }
        // wall = origin + position (an unseekable, minute-aligned file starts before the target, B3).
        s.request?.let { req ->
            val snap = controller.position()
            s.lastPos = snap
            s.wallMs = req.originMs + snap.positionMs
        }
        s.wallAtMs = now
        if (s.pauseAtFirstMedia || st.paused) {
            // S2/E26: a request issued while paused stays paused.
            s.pauseAtFirstMedia = false
            s.pauseRequested = true
            s.phase = PAUSED
            s.archivePausedSinceMs = now
            if (!st.paused) controller.pause()
        } else {
            s.phase = PLAYING
            s.playingSinceMs = now
            startPolling(s)
        }
        publish(s)
    }

    private fun onEnded(s: Session) {
        val ctx = s.ctx ?: return
        val req = s.request ?: return
        s.requestFirstMedia = true
        s.firstMediaSeen = true
        val snap = controller.position()
        s.lastPos = snap
        val now = clock()
        val wall = req.originMs + snap.positionMs
        s.wallMs = wall
        s.wallAtMs = now
        val pos = positionView(s, snap).copy(ended = true)
        executeContinue(s, planner.onProgress(wall, req, pos, liveWindow(ctx, now)))
    }

    private fun looksEnded(s: Session): Boolean {
        val snap = controller.position()
        val d = snap.durationMs ?: return false
        return snap.positionMs >= d - ENDED_SLACK_MS
    }

    private fun onError(s: Session, e: PlaybackError) {
        if (s.phase == FAILED) return
        val code = e.code
        when {
            !s.requestFirstMedia && isVariantFailure(e) -> probe(s, e)
            s.firstMediaSeen && code in MIDPLAY_CODES && s.midPlayRetries < ARCHIVE_MIDPLAY_RETRIES -> midPlayRetry(s, e)
            // CONNECTION_LIMIT: the ladder already retried within the cycle (L2–L4, lead sign-off S3). Auth, account,
            // codec and the rest: another open cannot help (plan §3 "401 → no loop").
            else -> fail(s, ArchiveFailure.PLAYBACK, e)
        }
    }

    /** S3 trigger set (§3.8.2 first Error row). */
    private fun isVariantFailure(e: PlaybackError): Boolean =
        e.code == PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE || e.detail.httpStatus == 400 || e.code in VARIANT_FAILURE_CODES

    private fun probe(s: Session, e: PlaybackError) {
        val ctx = s.ctx ?: return
        val req = s.request ?: return
        val now = clock()
        val live = liveWindow(ctx, now)
        // The lag rule is for an archive 404/410 near the edge only; a 400 or another code is a format problem.
        val lagEligible = e.code == PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE && e.detail.httpStatus != 400
        val plan = planner.onNotInArchive(
            req = req,
            live = if (lagEligible) live else live.copy(liveEdgeMs = Long.MAX_VALUE),
            firstMediaSeen = s.firstMediaSeen,
            variantIndex = s.variantIndex,
            variantCount = ctx.variants.size,
            lagRetriesUsed = if (lagEligible) s.lagRetries else 0,
        )
        BoxLog.i(TAG) { "${e.code.name} before first media (${req.variant}): ${plan.javaClass.simpleName}" }
        s.error = e
        when (plan) {
            is ProbePlan.NextVariant -> {
                val last = s.lastPlan ?: return fail(s, ArchiveFailure.NOT_IN_ARCHIVE, e)
                val built = buildFrom(ctx, last, plan.index, walk = true, now = now)
                if (built == null) {
                    s.variantIndex = ctx.variants.size - 1
                    fail(s, ArchiveFailure.NOT_IN_ARCHIVE, e)
                } else {
                    issue(s, last, PROBING, built)
                }
            }
            is ProbePlan.StepBack -> {
                val last = s.lastPlan ?: return fail(s, ArchiveFailure.NOT_IN_ARCHIVE, e)
                s.lagRetries++
                // Same variant, start earlier, target unchanged (startOffset restores the position when seekable).
                val stepped = last.copy(startMs = last.startMs - plan.stepBackMs)
                val built = buildFrom(ctx, stepped, s.variantIndex, walk = false, now = now)
                if (built == null) goLive(s, notReady = true) else issue(s, stepped, s.phase, built)
            }
            ProbePlan.LiveNotReady -> goLive(s, notReady = true)
            ProbePlan.Fail ->
                if (s.firstMediaSeen && e.code in MIDPLAY_CODES && s.midPlayRetries < ARCHIVE_MIDPLAY_RETRIES) {
                    midPlayRetry(s, e)
                } else {
                    fail(s, if (s.firstMediaSeen) ArchiveFailure.PLAYBACK else ArchiveFailure.NOT_IN_ARCHIVE, e)
                }
        }
    }

    /** §3.8.2: re-request open-ended from the last known playhead (expired token, dropped archive connection). */
    private fun midPlayRetry(s: Session, e: PlaybackError) {
        s.midPlayRetries++
        s.error = e
        val wall = s.wallMs
        BoxLog.i(TAG) { "mid-play ${e.code.name}: re-request (${s.midPlayRetries}/$ARCHIVE_MIDPLAY_RETRIES)" }
        if (s.phase == PAUSED) s.pauseAtFirstMedia = true
        issueOrFail(s, RequestPlan(wall, null, padBefore = false, targetMs = wall), CONTINUING)
    }

    private fun retryFailed(s: Session) {
        val plan = if (s.firstMediaSeen) {
            RequestPlan(s.wallMs, null, padBefore = false, targetMs = s.wallMs)
        } else {
            s.lastPlan ?: return
        }
        s.midPlayRetries = 0
        s.lagRetries = 0
        issueOrFail(s, plan, if (s.firstMediaSeen) CONTINUING else STARTING)
    }

    /** N13: lifecycle stop. The controller restarts the same request later under a newer tuneId, which we own. */
    private fun onLifecycleStop(s: Session) {
        if (s.phase == PAUSED) return
        snapshotWall(s)
        s.nativeSeekAtMs = -1
        if (s.rerequestSeekPending || s.phase == SEEKING) settle(s)
        s.phase = PAUSED
        s.archivePausedSinceMs = clock()
        stopPolling()
        publish(s)
    }

    // ------------------------------------------------------------------ polling (§3.8.2)

    private fun startPolling(s: Session) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                delay(if (s.nativeSeekAtMs >= 0) SEEK_SETTLE_POLL_MS else ARCHIVE_POLL_MS)
                if (session !== s) break
                poll(s)
            }
        }
    }

    private fun restartPolling(s: Session) {
        stopPolling()
        startPolling(s)
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun poll(s: Session) {
        if (s.phase != PLAYING && s.phase != SEEKING && s.phase != CONTINUING) return
        if (!s.requestFirstMedia) return
        val ctx = s.ctx ?: return
        val req = s.request ?: return
        val st = controller.state.value
        if (!owns(s, st)) return // the collector ends the session
        val snap = controller.position()
        s.lastPos = snap
        val now = clock()
        val wall = req.originMs + snap.positionMs

        if (s.nativeSeekAtMs >= 0) {
            // B3: an in-buffer seek emits no state; settle on progress or after SEEK_SETTLE_MAX_MS.
            val since = now - s.nativeSeekAtMs
            val playing = st is PlaybackState.Playing && !st.paused
            val advanced = snap.positionMs - s.nativeSeekLastPos >= SEEK_SETTLE_PROGRESS_MS
            s.nativeSeekLastPos = snap.positionMs
            val settled = since >= SEEK_SETTLE_MAX_MS ||
                (since >= SEEK_SETTLE_MIN_MS && ((playing && advanced) || s.nativeSeekWasPaused))
            if (!settled) return
            s.nativeSeekAtMs = -1
            settle(s)
            s.wallMs = wall
            s.wallAtMs = now
            if (s.nativeSeekWasPaused) {
                s.nativeSeekWasPaused = false
                s.phase = PAUSED
                s.archivePausedSinceMs = now
                stopPolling()
                publish(s)
                return
            }
            s.phase = PLAYING
            s.playingSinceMs = now
            restartPolling(s)
        }

        s.wallMs = wall
        s.wallAtMs = now
        if (s.phase == PLAYING && s.midPlayRetries > 0 && now - s.playingSinceMs >= MIDPLAY_RESET_MS) s.midPlayRetries = 0
        rescope(s, wall)
        executeContinue(s, planner.onProgress(wall, req, positionView(s, snap), liveWindow(ctx, now)))
    }

    private fun executeContinue(s: Session, plan: ContinuePlan) {
        when (plan) {
            ContinuePlan.Keep -> publish(s)
            ContinuePlan.Live -> goLive(s, notReady = false)
            is ContinuePlan.Rerequest -> {
                if (s.phase == PAUSED) s.pauseAtFirstMedia = true
                issueOrFail(s, plan.plan, CONTINUING)
            }
        }
    }

    /** Re-scopes [ArchiveStatus.programme] when the playhead leaves it; never a request (§3.8.5). */
    private fun rescope(s: Session, wallMs: Long) {
        val p = s.programme
        // The padded lead-in of a programme's first request belongs to that programme (§3.8.3).
        val lead = s.ctx?.timeBase?.paddingBefore?.toMillis() ?: 0L
        if (p != null && wallMs >= p.rawStartMin * MINUTE_MS - lead && wallMs < p.rawEndMin * MINUTE_MS) return
        val displayMin = Math.floorDiv(wallMs + s.frames.displayShiftMs, MINUTE_MS).toInt()
        if (displayMin == s.rescopeMin) return
        s.rescopeMin = displayMin
        scope.launch {
            val info = try {
                feed.at(s.channel, displayMin)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.w(TAG, "programme lookup failed: ${e.javaClass.simpleName}")
                return@launch
            }
            if (session !== s || s.phase == IDLE) return@launch
            s.programme = info
            publish(s)
        }
    }

    // ------------------------------------------------------------------ seeks, live pause, ends

    private fun seekRerequest(s: Session, plan: RequestPlan, wasPaused: Boolean) {
        s.rerequestSeekPending = true
        s.pauseAtFirstMedia = wasPaused
        val ctx = s.ctx ?: return
        val built = buildFrom(ctx, plan, s.variantIndex, walk = false, now = clock())
        if (built == null) {
            fail(s, ArchiveFailure.UNSUPPORTED_MODE, s.error)
            return
        }
        issue(s, plan, SEEKING, built)
    }

    /** A scrub commit while live is paused: archive from the target, still paused (S2), or live at the edge. */
    private fun seekFromLivePause(s: Session, ctx: Ctx, programmeMs: Long) {
        val live = liveWindow(ctx, clock())
        val t = programmeMs.coerceIn(live.archiveFromMs, live.liveEdgeMs)
        // P-S1 = U-L3: direction-aware, as the planner. Only a target later than the pause point snaps to live; the first
        // ← after a fresh live pause requests the archive.
        if (t > s.pausedAtMs && t >= live.liveEdgeMs - ArchivePlannerImpl.LIVE_SNAP_MS) {
            settle(s)
            goLive(s, notReady = false)
            return
        }
        seekRerequest(s, RequestPlan(t, null, padBefore = false, targetMs = t), wasPaused = true)
    }

    private fun resumeLivePause(s: Session) {
        val ctx = s.ctx
        if (ctx == null) {
            val job = s.ctxJob
            if (job == null) { controller.resume(); end(s); return }
            scope.launch {
                val r = try { job.await() } catch (e: CancellationException) { return@launch }
                if (session !== s || s.phase != PAUSED_LIVE) return@launch
                if (r is CtxResult.Ok) {
                    if (s.ctx == null) adopt(s, r.ctx)
                    resumeLivePause(s)
                } else {
                    // No archive after all: M2's freeze-and-rejoin.
                    controller.resume()
                    end(s)
                }
            }
            return
        }
        val now = clock()
        when (val plan = planner.resumeFromPause(s.pausedAtMs, now - s.pausedSinceMs, liveWindow(ctx, now))) {
            ContinuePlan.Keep, ContinuePlan.Live -> {
                // Short pause: M2 re-tunes at the live edge (the VM suppresses the expected LiveResumed toast).
                controller.resume()
                end(s)
            }
            is ContinuePlan.Rerequest -> {
                val built = buildFrom(ctx, plan.plan, 0, walk = true, now = now)
                if (built == null) fail(s, ArchiveFailure.UNSUPPORTED_MODE, null) else issue(s, plan.plan, CONTINUING, built)
            }
        }
    }

    /** Tunes the channel live and ends the session; the status follows the tune (no frame with a stale Error). */
    private fun goLive(s: Session, notReady: Boolean) {
        controller.tune(TuneTarget.Channel(s.channel.identity))
        end(s)
        if (notReady) _liveNotReady.tryEmit(ArchiveNotice.LiveNotReady(s.channel.identity))
    }

    private fun end(s: Session) {
        s.phase = IDLE
        s.ctxJob?.cancel()
        settle(s)
        if (session !== s) return
        stopPolling()
        _status.value = ArchiveStatus(settledSeq = settledSeq)
    }

    /** Invariant (B1): an owned Error the session does not act on turns it FAILED in the same main-thread turn. */
    private fun fail(s: Session, failure: ArchiveFailure, e: PlaybackError?) {
        BoxLog.i(TAG) { "failed: ${failure.name} ${e?.code?.name ?: ""}" }
        s.phase = FAILED
        s.failure = failure
        s.error = e
        s.nativeSeekAtMs = -1
        s.rerequestSeekPending = false
        stopPolling()
        settle(s)
        publish(s)
    }

    private fun settle(s: Session?) {
        if (pendingSeq > settledSeq) settledSeq = pendingSeq
        if (s == null) _status.value = _status.value.copy(settledSeq = settledSeq)
    }

    // ------------------------------------------------------------------ helpers

    private fun liveWindow(ctx: Ctx, now: Long): LiveWindow = planner.liveWindow(ctx.frames, ctx.days, now)

    private fun currentWall(s: Session, now: Long): Long =
        if (s.phase == PLAYING) s.wallMs + (now - s.wallAtMs) else s.wallMs

    private fun snapshotWall(s: Session) {
        val req = s.request ?: return
        if (!s.requestFirstMedia) return
        val snap = controller.position()
        s.lastPos = snap
        s.wallMs = req.originMs + snap.positionMs
        s.wallAtMs = clock()
    }

    /** HLS without a native range is re-request only (a moving EVENT window, §3.8.4): it reports neither seekability nor a duration. */
    private fun positionView(s: Session, snap: PositionSnapshot): PositionView {
        val req = s.request
        val hlsOpen = s.format == StreamFormat.HLS && req?.nativeFromMs == null
        return PositionView(
            positionMs = snap.positionMs,
            durationMs = if (hlsOpen) null else snap.durationMs,
            isSeekable = !hlsOpen && snap.isSeekable,
            ended = false,
        )
    }

    private fun publish(s: Session) {
        if (session !== s) return
        val now = clock()
        val ctx = s.ctx
        val frames = s.frames
        val edge = frames.liveEdge(now)
        val archiveFrom = ctx?.let { liveWindow(it, now).archiveFromMs } ?: edge
        val wall = when (s.phase) {
            PAUSED_LIVE -> s.pausedAtMs
            PLAYING -> s.wallMs + (now - s.wallAtMs)
            else -> s.wallMs
        }
        val req = s.request
        val pos = s.lastPos
        val seekable = req != null && (req.nativeFromMs != null ||
            (pos != null && positionView(s, pos).let { it.isSeekable && it.durationMs != null }))
        _status.value = ArchiveStatus(
            phase = s.phase,
            channel = s.channel,
            identityHash = s.channel.identityHash,
            programme = s.programme,
            wallMs = wall,
            atMs = now,
            advancing = s.phase == PLAYING,
            liveEdgeMs = edge,
            archiveFromMs = archiveFrom,
            displayShiftMs = frames.displayShiftMs,
            seekable = seekable,
            timeShift = s.timeShift,
            variant = req?.variant,
            attempt = if (ctx == null) 0 else s.variantIndex + 1,
            attempts = ctx?.variants?.size ?: 0,
            failure = s.failure,
            error = s.error,
            ownedTuneId = s.ownedTuneId,
            settledSeq = settledSeq,
        )
    }

    companion object {
        private const val TAG = "ArchivePlayback"
        private const val MINUTE_MS = 60_000L
        /** A pause reported within this of the duration is STATE_ENDED (before the controller sets `ended`). */
        private const val ENDED_SLACK_MS = 1_000L

        const val ARCHIVE_POLL_MS = 1_000L
        const val ARCHIVE_MIDPLAY_RETRIES = 1
        /** Seek settle bounds (B3): native seeks settle no sooner than MIN and no later than MAX. */
        const val SEEK_SETTLE_MIN_MS = 250L
        const val SEEK_SETTLE_MAX_MS = 5_000L
        /** A native seek has settled once the position advanced this far; polled at [SEEK_SETTLE_POLL_MS] while SEEKING. */
        const val SEEK_SETTLE_PROGRESS_MS = 200L
        const val SEEK_SETTLE_POLL_MS = 250L
        /** Mid-play retry budget resets after this long of clean playback (S3). */
        const val MIDPLAY_RESET_MS = 300_000L
        /** An archive pause longer than this rebuilds the request on resume (§3.8.7). */
        const val ARCHIVE_PAUSE_REBUILD_MS = 600_000L

        private val LIFECYCLE_REASONS = setOf(StopReason.BACKGROUND, StopReason.STANDBY, StopReason.HDMI_UNPLUGGED, StopReason.DISPLAY_OFF)

        /** S3: before first media, besides CATCHUP_NOT_IN_ARCHIVE and any HTTP 400 (ladder row L13a). */
        private val VARIANT_FAILURE_CODES = setOf(
            PlaybackErrorCode.FORBIDDEN, PlaybackErrorCode.FORMAT_REFUSED, PlaybackErrorCode.WEB_PAGE_INSTEAD,
            PlaybackErrorCode.EMPTY_RESPONSE, PlaybackErrorCode.UNSUPPORTED_SOURCE, PlaybackErrorCode.SERVER_ERROR,
        )

        /** S3: mid-play retry list (§3.8.2). */
        private val MIDPLAY_CODES = setOf(
            PlaybackErrorCode.STREAM_INTERRUPTED, PlaybackErrorCode.NO_DATA_TIMEOUT, PlaybackErrorCode.CONNECT_FAILED,
            PlaybackErrorCode.EMPTY_RESPONSE, PlaybackErrorCode.SERVER_ERROR, PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE,
        )
    }
}
