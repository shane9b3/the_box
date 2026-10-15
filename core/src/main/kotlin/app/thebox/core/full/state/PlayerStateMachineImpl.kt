package app.thebox.core.full.state

import app.thebox.core.full.EnterReason
import app.thebox.core.full.PlayerEffects
import app.thebox.core.full.PlayerEvent
import app.thebox.core.full.PlayerStateMachine
import app.thebox.core.full.PlayerUiState
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideRows
import app.thebox.core.input.BoxAction
import kotlinx.coroutines.flow.StateFlow

/**
 * WP-A stub (docs/design/m4-player-ux.md §1.2, §3.1–§3.5): layers, every key table of §3.1, info-bar timing,
 * channel-list cursor and paging requests, quick bar and pickers, error-card focus, scrub interaction, screen-off and
 * the back ladder (§3.1.5). Pure and main-thread only, like GuideStateMachineImpl.
 */
class PlayerStateMachineImpl(
    private val effects: PlayerEffects,
    private val listRows: GuideRows,
    private val nowMs: () -> Long,
) : PlayerStateMachine {
    override val ui: StateFlow<PlayerUiState> get() = TODO("WP-A")
    override val nextDeadlineMs: StateFlow<Long> get() = TODO("WP-A")
    override val horizontalRepeat: Boolean get() = TODO("WP-A")
    override fun act(a: GuideEvent.Act): Boolean = TODO("WP-A")
    override fun release(action: BoxAction): Unit = TODO("WP-A")
    override fun handles(action: BoxAction): Boolean = TODO("WP-A")
    override fun dispatch(event: PlayerEvent): Unit = TODO("WP-A")
    override fun enterFull(nowMs: Long, reason: EnterReason): Unit = TODO("WP-A")
    override fun leaveFull(): Unit = TODO("WP-A")

    companion object {
        /** Passive info bar after first media (§3.2.1). */
        const val INFO_AFTER_ZAP_MS = 4_000L
        /** The quick bar closes after this long without a key (§3.4.1, owner ruling 2026-10-15). */
        const val QUICK_BAR_IDLE_MS = 8_000L
        /** Unfocused scrub bar after an archive start (§3.2.1). */
        const val SCRUB_AUTO_SHOW_MS = 4_000L
        /** RW on live enters time-shift this far behind the edge (§3.1.2). */
        const val RW_ON_LIVE_BACK_MS = 30_000L
        /** Info bar programmes (§3.2.2): minutes back (archive channels only), minutes ahead, and the paging edge. */
        const val INFO_PAST_MIN = 360
        const val INFO_FUTURE_MIN = 720
        const val INFO_EDGE = 2
    }
}
