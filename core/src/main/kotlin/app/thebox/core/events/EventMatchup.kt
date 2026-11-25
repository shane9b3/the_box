// Stub (Event channels commit 0): docs/design/m6-event-channels.md §3.7, §4.1. WP-1 owns the body.
package app.thebox.core.events

/** The two sides of an event title (§3.7), for `EventGameRule` and the duration estimate. */
object EventMatchup {
    /** The cleaned, folded sides of [title], or null when it names no single matchup. */
    @Suppress("UNUSED_PARAMETER")
    fun of(title: String): Matchup? = null
}
