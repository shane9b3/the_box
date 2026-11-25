package app.thebox.ui.sports

import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchivePlayback
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.catchup.archive.TimelineSource
import app.thebox.core.common.BoxLog
import app.thebox.core.guide.rawEndMin
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.ClipInfo
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.ClipResolver
import app.thebox.core.sports.hub.ClipResult
import app.thebox.core.sports.hub.ResumePoint
import app.thebox.player.api.ClipTune
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.PositionSnapshot
import app.thebox.player.api.StopReason
import app.thebox.player.api.TrackInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §6.5, §6.6, A-B2; WP-5) One clip session at a time on the one player, owned
 * by `SportsViewModel` (WP-4), which builds it with its `viewModelScope` and talks to it only through this public API:
 *
 * - [play] snapshots the [ResumePoint] (before anything changes), resolves the clip in [scope] and calls
 *   `controller.playClip`; the returned tune id is the owned one. A later queue entry keeps the snapshot.
 * - Ownership: any non-clip tune past the owned tune id, or a `Stopped` on the clip target, ends the session **without**
 *   resuming ([state] → [State.Idle]).
 * - [back], the end of the queue or of a recap: emits [SportsEffect.Resume] on [effects] (the view model forwards it to
 *   its own `effects`), then [State.Idle].
 * - [close] (the view model's `onCleared`): cancels, ends without resuming (R-N1).
 * - While [State.Playing] the view model turns `DemandOwner.SPORTS_TAB` off and calls `GameDetailRepository.pause(true)`
 *   (§4.4, §6.6); [ui] feeds `SportsViewModel.clipUi`.
 *
 * Rules of this implementation (WP-5):
 * - The snapshot is taken on the first [play] of a session and **taken again just before the first `playClip`** (R-N3):
 *   a catch-up keeps playing during a resolve of up to 10 s, and a tune from elsewhere during the resolve (an intent, a
 *   PIN success) is then what the clip interrupts, so that is what comes back. The catch-up playhead is the programme-
 *   frame `wallMs` advanced by `now − atMs` while `advancing` (`ArchiveStatus` KDoc: `atMs` is the device time at which
 *   `wallMs` is valid; the player view model reads it the same way).
 * - Nothing has changed until the first `playClip`: Back, a failed lookup or [close] before it emits no resume.
 * - [ui] turns false **before** [SportsEffect.Resume] is emitted, so the hole is covered again before the resumed channel
 *   can draw a frame (A-S2).
 * - `CLIP_EXPIRED` → exactly one fresh lookup and `playClip` at the playhead per clip; any other failure, or a second
 *   expiry, → [State.Failed] (the layer's Try again / Back card). `controller.retry()` is never called on a clip.
 * - Must be called on the main thread (the view model's scope).
 *
 * [nowMs] is the device clock `ArchiveStatus.atMs` is read on (tests pass a fake).
 */
class ClipSession(
    private val controller: PlaybackController,
    private val archive: ArchivePlayback,
    private val resolver: ClipResolver?,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    sealed interface State {
        data object Idle : State
        /** Resolving [ref] (cancelled on Back, leaving the page, a new [play], an ownership loss). */
        data class Resolving(val ref: ClipRef, val queue: List<ClipRef>, val index: Int) : State
        /** The clip [queue]`[`[index]`]` plays as tune [ownedTuneId]; [info] once the lookup answered; [upNext] the next title while "Up next" shows. */
        data class Playing(
            val ownedTuneId: Long,
            val queue: List<ClipRef>,
            val index: Int,
            val info: ClipInfo?,
            val paused: Boolean = false,
            val upNext: String? = null,
            val captionsOn: Boolean = false,
            /** (WP-5) The clip has a text track the Menu key can switch. */
            val hasCaptions: Boolean = false,
            /** (WP-5) The controller is connecting or buffering the clip (the transport bar stays up). */
            val buffering: Boolean = true,
        ) : State
        /** Playback failed mid-clip after the expired-URL re-resolve: the layer's Try again / Back card (§6.8). */
        data class Failed(val queue: List<ClipRef>, val index: Int) : State
    }

    /** Why a clip did not start; the page shows the §6.8 toast. */
    enum class Failure { UNAVAILABLE, OFFLINE, REFUSED, GEO, NOT_FOUND, NOT_PLAYABLE, NO_CLIP }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _ui = MutableStateFlow(ClipUi())
    /** For `AppRoot.sportsClip` (through `SportsViewModel.clipUi`). */
    val ui: StateFlow<ClipUi> = _ui.asStateFlow()

    private val _effects = MutableSharedFlow<SportsEffect>(extraBufferCapacity = 4)
    /** [SportsEffect.Resume] only. */
    val effects: SharedFlow<SportsEffect> = _effects.asSharedFlow()

    private val _failures = MutableSharedFlow<Failure>(extraBufferCapacity = 4)
    val failures: SharedFlow<Failure> = _failures.asSharedFlow()

    private val _lastPlayed = MutableStateFlow<ClipRef?>(null)
    /** The clip that played last in this page visit (the Goals sheet's focus on return, U-S14); null before any. */
    val lastPlayed: StateFlow<ClipRef?> = _lastPlayed.asStateFlow()

    // ---- session (main thread only)
    /** Null while no session runs. */
    private var resume: ResumePoint? = null
    /** A `playClip` has run in this session: from then on the player is ours and the end resumes. */
    private var playedAny = false
    private var ownedTuneId = -1L
    /** The clip (by queue index) whose `CLIP_EXPIRED` was already re-resolved once. */
    private var expiredRetriedIndex = -1
    private var endedHandledTuneId = -1L
    private var lastPositionMs = 0L
    private var job: Job? = null
    private var watchJob: Job? = null
    /** Bumped on every start and end; a lookup that comes back under an older one is dropped. */
    private var generation = 0L
    /**
     * (Sports Hub code review 2026-11-25, H1) The player state when the session began, until a different one is seen:
     * a `Stopped(BACKGROUND)` that was already there is not "Home during the lookup".
     */
    private var stateAtStart: PlaybackState? = null

    /** Plays [queue]`[`[index]`]`, then the later entries (goals auto-advance; a recap or condensed game is a queue of one). */
    fun play(queue: List<ClipRef>, index: Int) {
        if (index !in queue.indices) return
        if (resolver == null) {
            _failures.tryEmit(Failure.UNAVAILABLE)
            return
        }
        if (resume == null) {
            resume = snapshot()
            playedAny = false
            ownedTuneId = -1L
            watch()
        }
        start(queue, index, startMs = 0L)
    }

    fun next() {
        val (queue, index) = current() ?: return
        if (index + 1 < queue.size) start(queue, index + 1, 0L)
    }

    fun previous() {
        val (queue, index) = current() ?: return
        if (index > 0) start(queue, index - 1, 0L) else if (_state.value is State.Playing) controller.seekTo(0L)
    }

    fun togglePause() {
        val s = _state.value as? State.Playing ?: return
        if (s.paused) controller.resume() else controller.pause()
        _state.value = s.copy(paused = !s.paused)
    }

    /** ± ms from the playhead (the layer's Left / Right and Rewind / Fast-forward). */
    fun seekBy(deltaMs: Long) {
        if (_state.value !is State.Playing) return
        val p = controller.position()
        var target = (p.positionMs + deltaMs).coerceAtLeast(0L)
        p.durationMs?.let { d -> target = target.coerceAtMost((d - SEEK_END_GUARD_MS).coerceAtLeast(0L)) }
        lastPositionMs = target
        controller.seekTo(target)
    }

    /** Menu: captions on / off for this clip, when it has them. */
    fun toggleCaptions() {
        val s = _state.value as? State.Playing ?: return
        val tracks = controller.tracks.value
        if (tracks.text.isEmpty()) return
        if (captionsOn(tracks)) {
            controller.selectTextTrack(null, remember = false)
            _state.value = s.copy(captionsOn = false)
        } else {
            val pick = tracks.text.firstOrNull { it.language?.startsWith("en", ignoreCase = true) == true } ?: tracks.text.first()
            controller.selectTextTrack(pick.ref, remember = false)
            _state.value = s.copy(captionsOn = true)
        }
    }

    /** The error card's Try again: re-resolve and `playClip` at the playhead. */
    fun retry() {
        val s = _state.value as? State.Failed ?: return
        expiredRetriedIndex = -1
        start(s.queue, s.index, lastPositionMs)
    }

    /** Back: end the session with resume. */
    fun back() {
        if (resume == null) return
        if (playedAny) endWithResume() else end()
    }

    /** `onCleared`: cancel and end **without** resuming (R-N1). */
    fun close() {
        if (resume == null && job == null) return
        end()
    }

    /** The transport bar's progress; null while nothing plays. */
    fun position(): PositionSnapshot? {
        if (!playedAny || (_state.value !is State.Playing && _state.value !is State.Failed)) return null
        val p = controller.position()
        if (_state.value is State.Playing && p.positionMs > 0) lastPositionMs = p.positionMs
        return p
    }

    // ------------------------------------------------------------------ internals

    private fun current(): Pair<List<ClipRef>, Int>? = when (val s = _state.value) {
        is State.Playing -> s.queue to s.index
        is State.Resolving -> s.queue to s.index
        is State.Failed -> s.queue to s.index
        State.Idle -> null
    }

    private fun start(queue: List<ClipRef>, index: Int, startMs: Long) {
        val r = resolver ?: return
        job?.cancel()
        val gen = ++generation
        if (index != expiredRetriedIndex) expiredRetriedIndex = -1
        val ref = queue[index]
        _state.value = State.Resolving(ref, queue, index)
        job = scope.launch {
            val result = r.stream(ref)
            currentCoroutineContext().ensureActive()
            if (gen != generation) return@launch
            when (result) {
                is ClipResult.Ok -> {
                    val (info, stream) = result.value
                    // (H1) A lifecycle stop the collector has not delivered yet still means the viewer left.
                    val now = controller.state.value
                    if (!playedAny && now != stateAtStart && now is PlaybackState.Stopped && now.reason in LIFECYCLE_REASONS) {
                        lose("stopped (${now.reason}) during the lookup")
                        return@launch
                    }
                    // R-N3: what the first clip interrupts is read now, not before the lookup.
                    if (!playedAny) resume = snapshot()
                    val id = controller.playClip(ClipTune(stream.url, stream.format, stream.captionsUrl, info.title, startMs))
                    if (id < 0) {
                        BoxLog.i(TAG) { "clip ${ref.videoId}: the player did not start it" }
                        fail(Failure.UNAVAILABLE)
                        return@launch
                    }
                    playedAny = true
                    ownedTuneId = id
                    endedHandledTuneId = -1L
                    lastPositionMs = startMs
                    _lastPlayed.value = ref
                    val tracks = controller.tracks.value
                    _state.value = State.Playing(
                        ownedTuneId = id, queue = queue, index = index, info = info,
                        captionsOn = captionsOn(tracks), hasCaptions = tracks.text.isNotEmpty(),
                    )
                    _ui.value = ClipUi(showing = true, tuneId = id)
                    BoxLog.i(TAG) { "clip ${ref.videoId} (${ref.kind}) plays as tune $id" }
                }
                ClipResult.NotFound -> fail(Failure.NOT_FOUND)
                ClipResult.Geo -> fail(Failure.GEO)
                ClipResult.Refused -> fail(Failure.REFUSED)
                is ClipResult.Failed -> fail(
                    when (result.kind) {
                        FailKind.OFFLINE -> Failure.OFFLINE
                        FailKind.BAD_BODY -> Failure.NOT_PLAYABLE
                        FailKind.REFUSED -> Failure.REFUSED
                        else -> Failure.UNAVAILABLE
                    },
                )
            }
        }
    }

    /** A lookup failed: the toast, then back to the page (resuming only when a clip already took the player). */
    private fun fail(f: Failure) {
        BoxLog.i(TAG) { "clip lookup: $f" }
        _failures.tryEmit(f)
        if (playedAny) endWithResume() else end()
    }

    private fun watch() {
        watchJob?.cancel()
        stateAtStart = controller.state.value
        watchJob = scope.launch {
            launch { controller.state.collect { onPlayback(it) } }
            controller.tracks.collect { t ->
                val s = _state.value as? State.Playing ?: return@collect
                _state.value = s.copy(captionsOn = captionsOn(t), hasCaptions = t.text.isNotEmpty())
            }
        }
    }

    private fun onPlayback(st: PlaybackState) {
        if (resume == null) return
        if (!playedAny) {
            // (H1) Home, standby or HDMI while the first lookup runs: the viewer left, so nothing may start afterwards.
            if (st == stateAtStart) return
            stateAtStart = null
            if (st is PlaybackState.Stopped && st.reason in LIFECYCLE_REASONS) lose("stopped (${st.reason}) during the lookup")
            return
        }
        val t = st.target
        // Ownership (A-B2): any other tune past ours, a stop of the clip (Home, standby, HDMI) or nothing tuned at all.
        if (t != null && !t.isClip && t.tuneId > ownedTuneId) return lose("tune ${t.tuneId} took the player")
        if (st is PlaybackState.Idle) return lose("the player went idle")
        if (st is PlaybackState.Stopped && (t == null || (t.isClip && t.tuneId >= ownedTuneId))) return lose("stopped (${st.reason})")
        if (t == null || !t.isClip || t.tuneId < ownedTuneId) return
        when (st) {
            is PlaybackState.Playing -> {
                val s = _state.value as? State.Playing ?: return
                if (st.ended) {
                    if (endedHandledTuneId != ownedTuneId) {
                        endedHandledTuneId = ownedTuneId
                        onEnded(s)
                    }
                } else if (s.paused != st.paused || s.buffering) {
                    _state.value = s.copy(paused = st.paused, buffering = false)
                }
            }
            is PlaybackState.Connecting, is PlaybackState.Buffering, is PlaybackState.Stalled -> {
                val s = _state.value as? State.Playing ?: return
                if (!s.buffering) _state.value = s.copy(buffering = true)
            }
            is PlaybackState.Error -> onClipError(st)
            else -> Unit
        }
    }

    private fun onClipError(st: PlaybackState.Error) {
        val (queue, index) = current() ?: return
        if (_state.value !is State.Playing) return
        val at = controller.position().positionMs.takeIf { it > 0 } ?: lastPositionMs
        lastPositionMs = at
        if (st.error.code == PlaybackErrorCode.CLIP_EXPIRED && expiredRetriedIndex != index) {
            BoxLog.i(TAG) { "clip link expired: one fresh lookup" }
            expiredRetriedIndex = index
            start(queue, index, at)
            return
        }
        BoxLog.i(TAG) { "clip failed: ${st.error.code}" }
        _state.value = State.Failed(queue, index)
    }

    private fun onEnded(s: State.Playing) {
        val nextIndex = s.index + 1
        if (s.queue[s.index].kind != ClipKind.GOAL || nextIndex >= s.queue.size) {
            endWithResume()
            return
        }
        val gen = ++generation
        job?.cancel()
        job = scope.launch {
            val r = resolver ?: return@launch
            val title = withTimeoutOrNull(UP_NEXT_LOOKUP_MS) { (r.info(s.queue[nextIndex]) as? ClipResult.Ok)?.value?.title }
            if (gen != generation) return@launch
            if (title != null) {
                (_state.value as? State.Playing)?.let { _state.value = it.copy(upNext = title) }
                delay(SportsConstants.HUB_UP_NEXT_MS)
            }
            if (gen != generation) return@launch
            start(s.queue, nextIndex, 0L)
        }
    }

    private fun lose(why: String) {
        BoxLog.i(TAG) { "clip session ends without resuming: $why" }
        end()
    }

    /** Hides the layer first (A-S2), then asks for the resume. */
    private fun endWithResume() {
        val point = resume ?: ResumePoint.Nothing
        end()
        _effects.tryEmit(SportsEffect.Resume(point))
    }

    private fun end() {
        generation++
        job?.cancel()
        job = null
        watchJob?.cancel()
        watchJob = null
        _ui.value = ClipUi()
        _state.value = State.Idle
        resume = null
        playedAny = false
        ownedTuneId = -1L
        expiredRetriedIndex = -1
        endedHandledTuneId = -1L
        lastPositionMs = 0L
        stateAtStart = null
    }

    /** §6.6: what the clip interrupts. */
    private fun snapshot(): ResumePoint {
        val st = controller.state.value
        if (st is PlaybackState.Idle || st is PlaybackState.Stopped || st is PlaybackState.Error) return ResumePoint.Nothing
        val t = st.target ?: return ResumePoint.Nothing
        if (t.isClip) return ResumePoint.Nothing
        val a = archive.status.value
        val ch = a.channel
        val prog = a.programme
        if (t.isArchive && a.source == TimelineSource.PROVIDER && a.phase in ACTIVE_ARCHIVE && ch != null && prog != null &&
            ch.identity == t.identity && t.tuneId >= a.ownedTuneId
        ) {
            return ResumePoint.Archive(ch, prog, rawEndMin = prog.rawEndMin, timeShift = a.timeShift, playheadMs = playhead(a))
        }
        return t.identity?.let { ResumePoint.Live(it) } ?: ResumePoint.Nothing
    }

    private fun playhead(a: ArchiveStatus): Long = a.wallMs + if (a.advancing) (nowMs() - a.atMs).coerceAtLeast(0L) else 0L

    private fun captionsOn(t: TrackInfo): Boolean = t.textEnabled && t.text.any { it.selected }

    private companion object {
        const val TAG = "ClipSession"
        /** A seek never lands in the last second (it would end the clip at once). */
        const val SEEK_END_GUARD_MS = 1_000L
        /** The "Up next" title is the cached `info()`; it never holds the next clip up for long. */
        const val UP_NEXT_LOOKUP_MS = 1_000L
        val LIFECYCLE_REASONS = setOf(StopReason.BACKGROUND, StopReason.STANDBY, StopReason.HDMI_UNPLUGGED, StopReason.DISPLAY_OFF)
        val ACTIVE_ARCHIVE = setOf(
            ArchivePhase.STARTING, ArchivePhase.PROBING, ArchivePhase.PLAYING, ArchivePhase.PAUSED,
            ArchivePhase.SEEKING, ArchivePhase.CONTINUING,
        )
    }
}
