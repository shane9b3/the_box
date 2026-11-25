package app.thebox.core.sports.hub

import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.SportsTiming

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §4.3; WP-1) When the shown game's detail is fetched again. Pure.
 *
 * | Phase | Next request |
 * |---|---|
 * | SCHEDULED, PRE | every `HUB_DETAIL_IDLE_MS` |
 * | LIVE, INTERMISSION | every 30 s, bucket-aligned (`SportsTiming.nearestAligned`, the install's poll phase) |
 * | FINAL | none; while the recap is missing and the game ended < `HUB_RECAP_WAIT_MS` ago, every `HUB_DETAIL_IDLE_MS` |
 * | POSTPONED | none |
 *
 * Failures back off as the day list does (`SportsTiming.backoffAt`, `Retry-After` a floor); a [refused] failure (429 or
 * REFUSED) waits at least `BACKOFF_MAX_MS`. Nothing at all while the [DetailGate] is closed.
 */
object DetailPollPolicy {
    /** The "no request" conditions (§4.3): every one must hold for any request. */
    data class DetailGate(
        val sportsOn: Boolean,
        val awake: Boolean,
        val foreground: Boolean,
        val online: Boolean,
        val paused: Boolean,
    ) {
        val open: Boolean get() = sportsOn && awake && foreground && online && !paused
    }

    /**
     * The phase the policy times by: the further along of the board's [board] phase and the detail's [fetched] one (a
     * board that went LIVE re-times a detail fetched at PRE at once; a detail that already saw FINAL is not polled as
     * live because the board lags). POSTPONED on either side wins.
     */
    fun effectivePhase(board: GamePhase, fetched: GamePhase?): GamePhase {
        if (board == GamePhase.POSTPONED || fetched == GamePhase.POSTPONED) return GamePhase.POSTPONED
        fetched ?: return board
        return if (rank(fetched) > rank(board)) fetched else board
    }

    /** The phase's re-read interval, or null for none (the staleness yardstick too). */
    fun intervalMs(phase: GamePhase, recapPosted: Boolean, endedAgoMs: Long?): Long? = when (phase) {
        GamePhase.SCHEDULED, GamePhase.PRE -> SportsConstants.HUB_DETAIL_IDLE_MS
        GamePhase.LIVE, GamePhase.INTERMISSION -> LIVE_MS
        GamePhase.FINAL -> if (!recapPosted && endedAgoMs != null && endedAgoMs < SportsConstants.HUB_RECAP_WAIT_MS) {
            SportsConstants.HUB_DETAIL_IDLE_MS
        } else {
            null
        }
        GamePhase.POSTPONED -> null
    }

    /**
     * The `MonoClock` instant of the next detail request, or null for none. [lastOk] is the newest good answer for this
     * game (null = none yet: now). After [failures] consecutive failures since it, the back-off from [lastFailMono]
     * ([jitter] ∈ [-1, 1]).
     */
    fun nextAt(
        gate: DetailGate,
        lastOk: Fetch.Ok<*>?,
        phase: GamePhase,
        recapPosted: Boolean,
        endedAgoMs: Long?,
        failures: Int,
        lastFailMono: Long?,
        retryAfterS: Int?,
        refused: Boolean,
        pollPhaseMs: Long,
        jitter: Double,
        nowMono: Long,
    ): Long? {
        if (!gate.open) return null
        if (failures > 0 && lastFailMono != null) {
            val at = SportsTiming.backoffAt(lastFailMono, failures, retryAfterS, jitter)
            return if (refused) maxOf(at, lastFailMono + SportsConstants.BACKOFF_MAX_MS) else at
        }
        lastOk ?: return nowMono
        val interval = intervalMs(phase, recapPosted, endedAgoMs) ?: return null
        return when (phase) {
            GamePhase.LIVE, GamePhase.INTERMISSION -> SportsTiming.nearestAligned(lastOk, pollPhaseMs, lastOk.receivedMono + interval)
            else -> lastOk.receivedMono + interval
        }
    }

    /** (A-S7) A detail fetched at [fetchedAtMono] is stale once older than 2 × its phase's interval; never without one. */
    fun isStale(fetchedAtMono: Long, phase: GamePhase, recapPosted: Boolean, endedAgoMs: Long?, nowMono: Long): Boolean {
        val interval = intervalMs(phase, recapPosted, endedAgoMs) ?: return false
        return nowMono - fetchedAtMono > STALE_FACTOR * interval
    }

    /** The instant [isStale] turns true, or null for never (so the repository can re-publish then). */
    fun staleAt(fetchedAtMono: Long, phase: GamePhase, recapPosted: Boolean, endedAgoMs: Long?): Long? =
        intervalMs(phase, recapPosted, endedAgoMs)?.let { fetchedAtMono + STALE_FACTOR * it + 1 }

    /** The live re-read: 30 s, the day list's live cadence (`DAY_POLL_LIVE_MS`). */
    private const val LIVE_MS = SportsConstants.DAY_POLL_LIVE_MS
    private const val STALE_FACTOR = 2

    private fun rank(p: GamePhase): Int = when (p) {
        GamePhase.SCHEDULED -> 0
        GamePhase.PRE -> 1
        GamePhase.LIVE, GamePhase.INTERMISSION -> 2
        GamePhase.FINAL -> 3
        GamePhase.POSTPONED -> 4
    }
}
