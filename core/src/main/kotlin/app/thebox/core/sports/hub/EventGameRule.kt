// Stub (Event channels commit 0): docs/design/m6-event-channels.md §4.3, §8.4. WP-1 owns the body; until then no event
// names a game, so the hub's channel list is unchanged.
package app.thebox.core.sports.hub

import app.thebox.core.events.EventZoneSource
import app.thebox.core.sports.GameSnapshot

/**
 * What stage E knows of one indexed event. [name]: the whole provider name (the hints read it, with [group]); [title]:
 * the cleaned title the sides come from; [startUtcMs]: the event's start; [zone]: how the start's zone was found.
 */
data class EventFacts(val name: String, val title: String, val group: String?, val startUtcMs: Long, val zone: EventZoneSource)

object EventGameRule {
    /** [citiesOnly]: both sides matched by place only; [zoneAssumed]: [EventFacts.zone] is ASSUMED. */
    data class Grade(val confidence: MatchConfidence, val citiesOnly: Boolean, val zoneAssumed: Boolean)

    /** §8.4. [board]: the other games the hub has loaded for the day (the cities-only upgrade). Null: not this game. */
    @Suppress("UNUSED_PARAMETER")
    fun grade(event: EventFacts, game: GameSnapshot, board: List<GameSnapshot>): Grade? = null
}
