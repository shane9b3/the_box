// Stub (Event channels commit 0): docs/design/m6-event-channels.md §4.2, §8.2. WP-1 owns the bodies.
package app.thebox.core.sports

/** §8.2: a channel is a team channel when its name equals a team's name **and** its group or tvg id says so. */
object TeamChannelRule {
    /**
     * True when [group] holds the adjacent words "<league> team(s)" (any prefix: "CA | NHL TEAMS", "NHL Teams (2)") and
     * none of radio, replay(s), highlights or classic(s).
     */
    @Suppress("UNUSED_PARAMETER")
    fun groupIsTeams(league: League, group: String?): Boolean = false

    /** The team whose channel this is, or null (both the name rule and the context rule must hold). */
    @Suppress("UNUSED_PARAMETER")
    fun teamOf(league: League, name: String, group: String?, tvgId: String?): TeamName? = null
}
