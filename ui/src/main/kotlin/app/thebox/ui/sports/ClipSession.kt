package app.thebox.ui.sports

import app.thebox.core.catchup.archive.ArchivePlayback
import app.thebox.core.sports.hub.ClipInfo
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.ClipResolver
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.PositionSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §6.5, §6.6, A-B2; WP-5) One clip session at a time on the one player, owned
 * by `SportsViewModel` (WP-4), which builds it with its `viewModelScope` and talks to it only through this public API:
 *
 * - [play] snapshots the [ResumePoint][app.thebox.core.sports.hub.ResumePoint] (before anything changes), resolves the
 *   clip in [scope] and calls `controller.playClip`; the returned tune id is the owned one. A later queue entry keeps the
 *   snapshot.
 * - Ownership: any non-clip tune past the owned tune id, or a `Stopped` on the clip target, ends the session **without**
 *   resuming ([state] → [State.Idle]).
 * - [back], the end of the queue or of a recap: emits [SportsEffect.Resume] on [effects] (the view model forwards it to
 *   its own `effects`), then [State.Idle].
 * - [close] (the view model's `onCleared`): cancels, ends without resuming (R-N1).
 * - While [State.Playing] the view model turns `DemandOwner.SPORTS_TAB` off and calls `GameDetailRepository.pause(true)`
 *   (§4.4, §6.6); [ui] feeds `SportsViewModel.clipUi`.
 *
 * Commit 0 stub: never plays; [play] does nothing and every flow stays idle. The members and their meanings are frozen
 * (WP-4 calls them); WP-5 may add fields to the [State] variants (WP-4 only reads them).
 */
@Suppress("unused") // (commit 0) the stub keeps its dependencies for WP-5
class ClipSession(
    private val controller: PlaybackController,
    private val archive: ArchivePlayback,
    private val resolver: ClipResolver?,
    private val scope: CoroutineScope,
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

    /** The clip that played last in this page visit (the Goals sheet's focus on return, U-S14); null before any. */
    val lastPlayed: StateFlow<ClipRef?> = MutableStateFlow<ClipRef?>(null).asStateFlow()

    /** Plays [queue]`[`[index]`]`, then the later entries (goals auto-advance; a recap or condensed game is a queue of one). */
    fun play(queue: List<ClipRef>, index: Int) = Unit

    fun next() = Unit
    fun previous() = Unit
    fun togglePause() = Unit
    /** ± ms from the playhead (the layer's Left / Right and Rewind / Fast-forward). */
    fun seekBy(deltaMs: Long) = Unit
    /** Menu: captions on / off for this clip, when it has them. */
    fun toggleCaptions() = Unit
    /** The error card's Try again: re-resolve and `playClip` at the playhead. */
    fun retry() = Unit
    /** Back: end the session with resume. */
    fun back() = Unit
    /** `onCleared`: cancel and end **without** resuming (R-N1). */
    fun close() = Unit

    /** The transport bar's progress; null while nothing plays. */
    fun position(): PositionSnapshot? = null
}
