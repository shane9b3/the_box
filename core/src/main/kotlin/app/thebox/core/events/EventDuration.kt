// Stub (Event channels commit 0): docs/design/m6-event-channels.md §3.5, §4.1. WP-1 owns the body.
package app.thebox.core.events

/** The estimated length of an event from its title's tokens (§3.5; the guide shows the end as "about"). */
object EventDuration {
    /**
     * Minutes: [EventConstants.EVENT_MIN_FOOTBALL], [EventConstants.EVENT_MIN_SOCCER], [EventConstants.EVENT_MIN_MATCHUP]
     * or [EventConstants.EVENT_MIN_SHOW]. The stub answers a show's length.
     */
    @Suppress("UNUSED_PARAMETER")
    fun minutes(title: String): Int = EventConstants.EVENT_MIN_SHOW
}
