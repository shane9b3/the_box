package app.thebox.core.sports

import app.thebox.core.playback.MonoClock
import kotlin.random.Random

/**
 * The followed game's pure machine (docs/design/m6-score-bug.md §2.3–§2.7; WP-1). Single-threaded: the repository's
 * actor serialises every input (A-S8). [mono] is the one time base (`elapsedRealtime`, A-N7); [wall] is device wall
 * time, corrected by the server `Date` skew for expiry (A-N4). [pollPhaseMs] is `SportsFollowDoc.pollPhaseMs` (drawn
 * once per install); [random] draws the back-off jitter.
 *
 * Commit 0: contract only; WP-1 fills every body.
 */
@Suppress("unused")
class ScoreBugMachine(
    private val mono: MonoClock,
    private val wall: () -> Long,
    private val pollPhaseMs: Long = SportsConstants.POLL_PHASE_MIN_MS,
    private val random: Random = Random.Default,
) {
    fun follow(game: FollowedGame?): Unit = TODO("WP-1")
    fun prefs(delaySec: Int): Unit = TODO("WP-1")
    fun demand(d: Demand): Unit = TODO("WP-1")
    /** (Commit 0 addition) A default network exists (§2.4). Offline: nothing is due, but stale and hide still advance. */
    fun online(on: Boolean): Unit = TODO("WP-1")
    fun fetched(f: Fetch<GameSnapshot>): Unit = TODO("WP-1")
    /** Advance to `mono.nowMs()`: expiry, stale, hide, the delay queue, the clock hold (A-B1). */
    fun step(): Unit = TODO("WP-1")
    fun pollDue(): Boolean = TODO("WP-1")
    /** min(next poll, next snapshot due, stale, hide, cap instant, expiry, hold end, goal-text end); null = idle (A-B1). */
    fun nextWakeAtMono(): Long? = TODO("WP-1")
    /** Null before LIVE (owner ruling 2026-11-05) and while hidden. */
    fun view(): ScoreBugView? = TODO("WP-1")
    fun anchor(): ClockAnchor? = TODO("WP-1")
    /**
     * (Commit 0 addition) The followed game as the machine now knows it (start rewritten from a snapshot,
     * `finalSeenUtcMs` set), for the repository to persist; null once removed or expired.
     */
    fun followed(): FollowedGame? = TODO("WP-1")
    fun drainEvents(): List<ScoreEvent> = TODO("WP-1")
}
