package app.thebox.core.sports

/**
 * (NFL commit 0, docs/design/m6-nfl.md §2 S7, §6; CHL commit 0, docs/design/m6-chl-leagues.md §1.5) The league-keyed
 * colour lookup. Team identity is `(League, code)`: BUF, CAR, CHI, DAL, DET, MIN, PHI, PIT and SEA exist in the NHL and
 * the NFL; CGY, EDM, OTT, SEA, VAN, CHI and VIC in the NHL and the CHL; and the QMJHL's Newfoundland Regiment is "NFL"
 * (L2). An abbreviation alone never picks a table. `GoalHooks.colours` is this function (CHL §1.5).
 */
object TeamColourTable {
    fun of(league: League, abbrev: String?): TeamColours = when (league) {
        League.NHL -> NhlTeamColours.of(abbrev)
        League.NFL -> NflTeamColours.of(abbrev)
        League.OHL, League.WHL, League.QMJHL -> ChlTeamColours.of(league, abbrev)
    }
}
