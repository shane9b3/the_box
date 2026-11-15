// Contract (NFL Stacked commit 0): docs/design/m6-nfl-stacked.md §6, §8.1. The signature of [NflStackedColours.pair] is
// fixed for WP-2 (the table), WP-3 (the painters read `StackedPair`) and WP-4 (`StackedNflHooks.colours`, through
// `StackedColourTable.pair(League.NFL, …)`); changes go through the lead.
package app.thebox.core.sports

/**
 * (NFL Stacked §6.1, Rec 4) The NFL Stacked colours: its own object, so no NHL or CHL code path is touched. The 32
 * `NflTeamColours` rows plus `NflTeamColours.FALLBACK`, each resolved once with `NhlStackedColours.derive` (the one
 * derivation: stripe, `barBg = mixBlack(tab, .172)`, `barA`, `barB`, the true `goal` colours, the C7 `rowFlashAlpha`),
 * and its own `(n + 1)²` pair cache with `NhlStackedColours`' look-alike rule (ΔE76 < `STACKED_STRIPE_DE_MIN` → home
 * swaps, then away). The §6.2 table and every ST §5 rule are asserted by `NflStackedColoursTest` (WP-2).
 *
 * `StackedColourTable.pair(League.NFL, …)` delegates here.
 */
object NflStackedColours {
    /**
     * The game's pair (abbreviations trimmed and upper-cased; unknown or null → the fallback), with the §6.2 collision
     * rule applied. Cached per pair: the same instance for the same two teams, allocation-free after the first lookup.
     *
     * **Commit-0 stub:** the NHL fallback pair (`NhlStackedColours.pair(null, null)`), the value the `StackedColourTable`
     * NFL arm returned before. Commit 0 never draws it (`StackedNflBug` draws the NFL Pill until WP-4).
     */
    fun pair(away: String?, home: String?): StackedPair {
        // WP-2: the 32 NFL resolutions, the fallback and the pair cache.
        return NhlStackedColours.pair(null, null)
    }
}
