// Contract (CHL commit 0, docs/design/m6-chl-leagues.md §1.5, §6.2): the signature of [StackedColourTable.pair] is fixed
// for WP-4 (the CHL tables) and `StackedHooks.colours`; changes go through the lead.
package app.thebox.core.sports

/**
 * (CHL §1.5) The Stacked pair of a game, league-keyed: one resolved table and pair cache per hockey league, with the
 * look-alike rule (ΔE76 < `STACKED_STRIPE_DE_MIN` → home swaps, then away) applied within a league. The NHL's is
 * [NhlStackedColours], untouched (its arrays and cache are not shared with the CHL's).
 *
 * **Commit-0 stub:** OHL, WHL and QMJHL give the fallback pair (both teams at `NhlTeamColours.FALLBACK`'s derivation).
 * WP-4 adds the per-league tables (from `ChlTeamColours`) without changing [pair]'s signature.
 */
object StackedColourTable {
    fun pair(league: League, away: String?, home: String?): StackedPair = when (league) {
        League.NHL -> NhlStackedColours.pair(away, home)
        League.OHL, League.WHL, League.QMJHL -> NhlStackedColours.pair(null, null) // TODO(WP-4): the CHL tables
        // The Stacked bug is hockey only (`ScoreBug(...)` forces the Pill / NFL bug for the NFL): never drawn.
        League.NFL -> NhlStackedColours.pair(null, null)
    }
}
