// Stub (Event channels commit 0): docs/design/m6-event-channels.md §4.2. The signatures are frozen; WP-1 owns the
// bodies and the NHL and NFL tables. Until then no team is known, so no channel is a team channel.
package app.thebox.core.sports

/** One club's names, for team channels (§8.2), event sides (§8.4) and search's nickname expansion (§7). */
data class TeamName(
    val league: League,
    /** The board's ("EDM"; NFL as ESPN gives it, "WSH"). */
    val abbrev: String,
    /** Current first, then former names of the same club: ["Utah Mammoth", "Utah Hockey Club"]. */
    val fullNames: List<String>,
    /** ["Oilers"], ["Golden Knights"]. */
    val nicknames: List<String>,
    /** ["Edmonton"], ["Vegas", "Las Vegas"], ["St. Louis"], ["Montréal"], ["Los Angeles", "LA"]. */
    val places: List<String>,
    /** tvg-id stems: ["EdmontonOilers"], ["UtahMammoth", "UtahHockeyClub"]. */
    val tvgStems: List<String>,
)

object TeamNames {
    /** Which of a team's names a side equals. */
    enum class Strength { FULL, NICKNAME, PLACE }

    /** The team [abbrev] of [league], or null. */
    @Suppress("UNUSED_PARAMETER")
    fun of(league: League, abbrev: String): TeamName? = null

    /** Every team of [league]; empty for a league without a table (CHL). */
    @Suppress("UNUSED_PARAMETER")
    fun all(league: League): List<TeamName> = emptyList()

    /** Folded tokens: "saint"/"st"/"st." → "st", "&" → "and", "l.a." → "la"; letters and digits only. */
    @Suppress("UNUSED_PARAMETER")
    fun tokens(s: String): List<String> = emptyList()

    /** How [side] (cleaned tokens, §3.7) **equals** one of [team]'s names, or null (arch S2: equality, never containment). */
    @Suppress("UNUSED_PARAMETER")
    fun sideMatch(side: List<String>, team: TeamName): Strength? = null

    /** True when [place] belongs to two clubs of [league] ("New York", "Los Angeles"). */
    @Suppress("UNUSED_PARAMETER")
    fun placeShared(league: League, place: String): Boolean = false
}
