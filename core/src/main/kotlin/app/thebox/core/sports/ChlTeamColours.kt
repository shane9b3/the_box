// Contract (CHL commit 0, docs/design/m6-chl-leagues.md §1.5, §6.2): the signature of [ChlTeamColours.of] is fixed for
// WP-4 (the tables) and every caller; changes go through the lead.
package app.thebox.core.sports

/**
 * (CHL §6.2) The CHL teams' goal / power-play colours, keyed by `(League, code)`: CGY, EDM, OTT, SEA, VAN, CHI and VIC
 * exist in the NHL too, VIC in two CHL leagues, and the QMJHL's "NFL" is a team (L2), so a code alone never picks a row.
 *
 * **Commit-0 stub (fallback only):** every team resolves to [FALLBACK]. WP-4 adds one table per league (base, accent),
 * resolved with `NhlTeamColours.derive` (ruling 12) plus per-team C1 exceptions, without changing [of]'s signature.
 */
object ChlTeamColours {
    /** The colours of an unknown team, or of a null code: the NHL table's neutral pair. */
    val FALLBACK: TeamColours = NhlTeamColours.FALLBACK

    /**
     * [league] must be OHL, WHL or QMJHL (any other league gives [FALLBACK]); [abbrev] is trimmed and upper-cased; an
     * unknown or null code gives [FALLBACK].
     */
    fun of(league: League, abbrev: String?): TeamColours = when (league) {
        League.OHL, League.WHL, League.QMJHL -> FALLBACK // TODO(WP-4): the per-league tables
        League.NHL, League.NFL -> FALLBACK
    }
}
