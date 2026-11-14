// Contract (CHL commit 0, docs/design/m6-chl-leagues.md §1.5, §6.2): the signature of [StackedColourTable.pair] is fixed
// for WP-4 (the CHL tables) and `StackedHooks.colours`; changes go through the lead.
package app.thebox.core.sports

import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * (CHL §1.5) The Stacked pair of a game, league-keyed: one resolved table and pair cache per hockey league, with the
 * look-alike rule (ΔE76 < `STACKED_STRIPE_DE_MIN` → home swaps, then away) applied within a league. The NHL's is
 * [NhlStackedColours], untouched (its arrays and cache are not shared with the CHL's).
 *
 * (WP-4) A CHL league's table is its `ChlTeamColours` rows, each resolved once with `NhlStackedColours.derive` (the same
 * derivation as the NHL's) plus the fallback; the pair rule is `NhlStackedColours`' (§5.2). The pair cache is one
 * `(n + 1)²` array per league, filled on first use, so a lookup allocates nothing after the first.
 */
object StackedColourTable {
    fun pair(league: League, away: String?, home: String?): StackedPair = when (league) {
        League.NHL -> NhlStackedColours.pair(away, home)
        League.OHL -> OHL.pair(away, home)
        League.WHL -> WHL.pair(away, home)
        League.QMJHL -> QMJHL.pair(away, home)
        // The Stacked bug is hockey only (`ScoreBug(...)` forces the Pill / NFL bug for the NFL): never drawn.
        League.NFL -> NhlStackedColours.pair(null, null)
    }

    private val OHL = LeagueTable(League.OHL)
    private val WHL = LeagueTable(League.WHL)
    private val QMJHL = LeagueTable(League.QMJHL)

    /** (Test) A team's resolution in a CHL [league]: primary and swapped. */
    internal fun resolved(league: League, abbrev: String?): NhlStackedColours.Resolved = table(league).resolve(abbrev)

    private fun table(league: League): LeagueTable = when (league) {
        League.OHL -> OHL
        League.WHL -> WHL
        League.QMJHL -> QMJHL
        League.NHL, League.NFL -> throw IllegalArgumentException("not a CHL league: $league")
    }

    /** One CHL league: its teams resolved once (sorted by code), then the fallback, and the pair cache. */
    private class LeagueTable(league: League) {
        private val keys: List<String> = ChlTeamColours.table(league).keys.sorted()
        private val index: Map<String, Int> = keys.withIndex().associate { (i, k) -> k to i }
        private val fallbackIndex = keys.size
        private val slots = keys.size + 1
        private val teams: Array<NhlStackedColours.Resolved> = Array(slots) { i ->
            if (i < keys.size) NhlStackedColours.derive(keys[i], ChlTeamColours.of(league, keys[i]))
            else NhlStackedColours.derive(null, ChlTeamColours.FALLBACK)
        }
        private val pairs = AtomicReferenceArray<StackedPair?>(slots * slots)

        fun pair(away: String?, home: String?): StackedPair {
            val a = indexOf(away)
            val h = indexOf(home)
            val slot = a * slots + h
            pairs.get(slot)?.let { return it }
            val made = derivePair(teams[a], teams[h])
            return if (pairs.compareAndSet(slot, null, made)) made else pairs.get(slot) ?: made
        }

        fun resolve(abbrev: String?): NhlStackedColours.Resolved = teams[indexOf(abbrev)]

        private fun indexOf(abbrev: String?): Int {
            val key = abbrev?.trim()?.uppercase() ?: return fallbackIndex
            return index[key] ?: fallbackIndex
        }
    }

    /** (§5.2, as `NhlStackedColours`) Home swaps to its other colour; failing that, home keeps its primary and away swaps. */
    private fun derivePair(away: NhlStackedColours.Resolved, home: NhlStackedColours.Resolved): StackedPair {
        if (!NhlStackedColours.collides(away.primary.stripe, home.primary.stripe)) return StackedPair(away.primary, home.primary)
        if (!NhlStackedColours.collides(away.primary.stripe, home.swapped.stripe)) return StackedPair(away.primary, home.swapped)
        return StackedPair(away.swapped, home.primary)
    }
}
