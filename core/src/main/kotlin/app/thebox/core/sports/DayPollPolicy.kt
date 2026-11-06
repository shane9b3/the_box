package app.thebox.core.sports

/**
 * When the day list polls (docs/design/m6-score-bug.md §2.3, A-S7; WP-1): 30 s, bucket-aligned from [lastOk]'s
 * `Date − Age`, while any game that day is live or pre-game; 5 min otherwise; once for an all-final day; nothing while
 * [demand] is not SEEN (the tab and the picker closed). After [failures] consecutive `Failed`s since [lastOk] the §2.4
 * back-off runs from [lastFailMono] ([retryAfterS] wins); [jitter] ∈ [-1, 1] scales its ±20 %.
 *
 * Returns the `MonoClock` instant of the next request, or null for none. [lastOk] null = never loaded: [nowMono].
 *
 * Commit 0: contract only; WP-1 fills the body.
 */
object DayPollPolicy {
    @Suppress("UNUSED_PARAMETER")
    fun nextAt(
        lastOk: Fetch.Ok<DayBoard>?,
        demand: Demand,
        failures: Int,
        lastFailMono: Long?,
        retryAfterS: Int?,
        pollPhaseMs: Long,
        jitter: Double,
        nowMono: Long,
    ): Long? = TODO("WP-1")
}
