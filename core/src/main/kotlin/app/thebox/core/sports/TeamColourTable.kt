package app.thebox.core.sports

/**
 * (NFL commit 0, docs/design/m6-nfl.md §2 S7, §6) The league-keyed colour lookup: BUF, CAR, CHI, DAL, DET, MIN, PHI, PIT
 * and SEA exist in both leagues, so an abbreviation alone never picks a table. The NHL path keeps calling
 * [NhlTeamColours.of] directly (`GoalHooks.colours` is unchanged).
 */
object TeamColourTable {
    fun of(league: League, abbrev: String?): TeamColours = when (league) {
        League.NHL -> NhlTeamColours.of(abbrev)
        League.NFL -> NflTeamColours.of(abbrev)
    }
}
