package app.thebox.core.full.state

import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.full.PillKind
import app.thebox.core.full.PillState
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.TunedState

/**
 * The state pill for both machines and the mini player (docs/design/m4-player-ux.md §3.2.3; review B1/F3). [tuned] is
 * always the visible state (ArchiveVisibility), so an owned hand-off never reads CONNECTING. First match wins:
 *
 * 1. `tuned.handoff` → the pill of the session (BEHIND when time-shift, else CATCH-UP);
 * 2. CONNECTING/BUFFERING → CONNECTING; 3. RECONNECTING → RECONNECTING (attempt/max);
 * 4. status PAUSED_LIVE/PAUSED or tuned PAUSED → PAUSED (behind grows with wall time when the session is paused);
 * 5. an active session in time-shift → BEHIND (constant); 6. an active session or an archive tune → CATCH-UP;
 * 7. LIVE.
 *
 * A status of another channel (or IDLE/FAILED) is ignored.
 */
object PillRules {
    fun pill(tuned: TunedState, status: ArchiveStatus?, nowMs: Long): PillState {
        val s = status?.takeIf { it.phase != ArchivePhase.IDLE && it.phase != ArchivePhase.FAILED && (tuned.identityHash == 0L || it.identityHash == tuned.identityHash) }
        if (tuned.handoff && s != null) return sessionPill(s)
        when (tuned.phase) {
            MiniPhase.CONNECTING, MiniPhase.BUFFERING -> return PillState(PillKind.CONNECTING, atMs = nowMs)
            MiniPhase.RECONNECTING -> return PillState(PillKind.RECONNECTING, atMs = nowMs, reconnectAttempt = tuned.reconnectAttempt, reconnectMax = tuned.reconnectMax)
            else -> Unit
        }
        if (s != null && (s.phase == ArchivePhase.PAUSED_LIVE || s.phase == ArchivePhase.PAUSED)) {
            val live = s.liveEdgeMs + (nowMs - s.atMs)
            val wall = s.wallMs + if (s.advancing) nowMs - s.atMs else 0L
            return PillState(PillKind.PAUSED, behindMs = maxOf(0L, live - wall), atMs = nowMs, behindGrows = true)
        }
        if (tuned.phase == MiniPhase.PAUSED) return PillState(PillKind.PAUSED, atMs = nowMs)
        if (s != null) return sessionPill(s)
        if (tuned.isArchive) return PillState(PillKind.CATCH_UP, atMs = nowMs)
        return PillState(PillKind.LIVE, atMs = nowMs)
    }

    private fun sessionPill(s: ArchiveStatus): PillState =
        if (s.timeShift) PillState(PillKind.BEHIND, behindMs = maxOf(0L, s.liveEdgeMs - s.wallMs), atMs = s.atMs)
        else PillState(PillKind.CATCH_UP, atMs = s.atMs)

    /**
     * The UI changes the displayed pill only after a new kind has been stable for [debounceMs] (§3.2.3,
     * `PILL_DEBOUNCE_MS`; hides M2's Playing ↔ Buffering(RECONNECTING) flicker, M2 E-L8). Pure and main-only; WP-D
     * drives it from its pill flow and a timer at [nextChangeAtMs]. The first pill shows at once; a change of numbers
     * within the same kind (behind, attempt) shows at once.
     */
    class Debouncer(private val debounceMs: Long = PILL_DEBOUNCE_MS) {
        var shown: PillState? = null
            private set
        private var candidate: PillState? = null
        private var candidateSince = 0L

        /** Feeds the latest pill at [nowMs]; returns the pill to display. */
        fun offer(p: PillState, nowMs: Long): PillState {
            val cur = shown
            if (cur == null || cur.kind == p.kind) { shown = p; candidate = null; return p }
            if (candidate?.kind != p.kind) { candidate = p; candidateSince = nowMs } else candidate = p
            if (nowMs - candidateSince >= debounceMs) { shown = p; candidate = null; return p }
            return cur
        }

        /** Wall ms at which a pending candidate becomes shown (call [offer] again then); MAX_VALUE = none. */
        val nextChangeAtMs: Long get() = if (candidate == null) Long.MAX_VALUE else candidateSince + debounceMs
    }

    const val PILL_DEBOUNCE_MS = 400L
}
