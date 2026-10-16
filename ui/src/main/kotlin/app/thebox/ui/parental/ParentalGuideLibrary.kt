package app.thebox.ui.parental

import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideLibrary
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.ParentalRules
import app.thebox.core.parental.ParentalSession
import app.thebox.core.parental.ParentalState
import app.thebox.core.parental.RelockReason
import app.thebox.core.playback.TuneGuard
import app.thebox.core.playback.TuneVerdict
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.player.api.ArchiveTune
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.PreviousResult
import app.thebox.player.api.TuneTarget
import app.thebox.player.api.ZapList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * The library as the view models see it (docs/design/m5-setup-settings.md §2.12 item 9, S10): every query gets the
 * access of [session]'s current state, and the flows restart when it changes (`ParentalState.queryKey`, plus whether
 * the source was opened anyway). A caller passing [ParentalAccess.OPEN] keeps it: that is the debug unlock switch
 * (`GuideSettings.debugUnlocked` through `legacyParentalAccess`); any other value is replaced by the session's.
 *
 * Wrapping the library here feeds the two commit-0 call sites inside `GuideEffectsImpl` (a skeleton nobody edits) from
 * the session without touching them. Also: Hide in the guide (`ParentalRules.visibleEntries`), and the D1 number-entry
 * rule (a number that only exists as locked content is returned, so the tune reaches the guard and the PIN prompt).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ParentalGuideLibrary(
    private val inner: GuideLibrary,
    private val session: ParentalSession,
) : GuideLibrary by inner {

    private fun effective(requested: ParentalAccess, state: ParentalState, source: GuideSource?): ParentalAccess =
        if (requested == ParentalAccess.OPEN) ParentalAccess.OPEN else ParentalRules.accessFor(state, source)

    override fun sources(access: ParentalAccess): Flow<List<GuideSourceEntry>> =
        session.state
            .distinctUntilChanged { a, b -> a.queryKey == b.queryKey && a.openedAnyway == b.openedAnyway }
            .flatMapLatest { st -> inner.sources(effective(access, st, null)).map { ParentalRules.visibleEntries(it, st) } }

    override fun lineup(source: GuideSource, access: ParentalAccess): Flow<GuideLineup> =
        session.state
            .map { st -> effective(access, st, source) }
            .distinctUntilChanged()
            .flatMapLatest { a -> inner.lineup(source, a) }

    override suspend fun byNumber(playlistId: Long?, number: Int, access: ParentalAccess): GuideChannel? {
        val a = effective(access, session.state.value, null)
        val ch = inner.byNumber(playlistId, number, a)
        if (ch != null || a == ParentalAccess.OPEN) return ch
        // D1 (§1.1): locked content keeps its number; the guard refuses the tune and the PIN prompt opens.
        return inner.byNumber(playlistId, number, ParentalAccess.OPEN)
    }
}

/**
 * The controller as the view models' effects see it: `tune`, `previousChannel` and `tuneArchive` ask the [guard]
 * first and report a refusal to [refused] (the host opens the PIN prompt or the not-protected card) instead of
 * starting anything (B1). The controller's own guard (WP-A, `PlaybackControllerImpl`) still covers the paths that do
 * not pass here (zap neighbours, resume after a lifecycle stop, archive starts from `ArchivePlayback`).
 */
class GuardedPlaybackController(
    private val inner: PlaybackController,
    private val guard: TuneGuard,
    private val refused: (ChannelIdentity, TuneVerdict) -> Unit,
) : PlaybackController by inner {

    private fun blocked(identity: ChannelIdentity): Boolean {
        val v = guard.check(identity)
        if (v == TuneVerdict.ALLOWED) return false
        refused(identity, v)
        return true
    }

    override fun tune(target: TuneTarget, zapList: ZapList?) {
        if (target is TuneTarget.Channel && blocked(target.identity)) return
        inner.tune(target, zapList)
    }

    /** True when a refusal opened the prompt (the caller must not toast "No previous channel"). */
    override fun previousChannel(): Boolean {
        val p = inner.previous.value
        if (p != null && blocked(p)) return true
        return inner.previousChannel()
    }

    override fun previousChannelResult(): PreviousResult {
        val p = inner.previous.value
        if (p != null && guard.check(p) != TuneVerdict.ALLOWED) return PreviousResult.Locked(p)
        return inner.previousChannelResult()
    }

    override fun tuneArchive(tune: ArchiveTune) {
        if (blocked(tune.identity)) return
        inner.tuneArchive(tune)
    }
}

/** A zap list with nothing in it: the zap list is cleared on every relock and rebuilt at the next lineup (§3.9.2). */
object EmptyZapList : ZapList {
    override val id: String = "parental-relock"
    override suspend fun neighbour(from: ChannelIdentity, delta: Int): ChannelIdentity? = null
}

/** Default for view models built without Hilt (tests): no overrides, nothing ever changes. */
object NoKeyBindings : app.thebox.core.input.KeyBindingStore {
    override val overrides: StateFlow<app.thebox.core.input.KeyBindingOverrides> = MutableStateFlow(app.thebox.core.input.KeyBindingOverrides())
    override fun bind(action: app.thebox.core.input.BoxAction, key: app.thebox.core.input.CapturedKey, press: app.thebox.core.input.Press, nowMs: Long) = Unit
    override fun unbind(context: app.thebox.core.input.KeyContext, keyCode: Int, press: app.thebox.core.input.Press, nowMs: Long) = Unit
    override fun resetKey(keyCode: Int) = Unit
    override fun resetAction(action: app.thebox.core.input.BoxAction) = Unit
    override fun resetAll() = Unit
}

/** Defaults for view models built without Hilt (tests): no PIN, auto-lock on (FILTERED_NO_PIN filters as M3 did). */
object NoPinSession : ParentalSession {
    override val state: StateFlow<ParentalState> = MutableStateFlow(ParentalState(pinSet = false, unlocked = false, policy = ParentalPolicy()))
    override fun unlock(nowMs: Long) = Unit
    override fun relock(reason: RelockReason) = Unit
    override fun onTick(nowMs: Long) = Unit
}
