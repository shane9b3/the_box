// Contract (Stacked commit 0): docs/design/m6-score-bug-stacked-theme.md §5. The types and the `of` / `pair` signatures are
// fixed for WP-1 (the derivation), WP-2 (the painters) and WP-3 (`StackedHooks.colours`); changes go through the lead.
package app.thebox.core.sports

/**
 * (Stacked §5.1) One team's Stacked colours, ARGB ints, derived (never tabled) from `NhlTeamColours`:
 * - [stripe]: the row stripe (and the neutral bar's stripe for this side), ≥ `STACKED_STRIPE_MIN` against the row navy;
 * - [barBg]: the team's power-play bar background, `mixBlack(tab, STACKED_BAR_BG_BLACK)` (white text ≥ 4.5 : 1);
 * - [barA] / [barB]: the team bar's two skewed stripes (S2 candidate rules);
 * - [goal]: the takeover's panel, lead, glow and pop (`NhlTeamColours.of`, ruling 12; the true colours, never swapped);
 * - [rowFlashAlpha]: the row flash's peak α for [stripe] (C7, S11), between .30 and .55.
 *
 * The rows, columns, border and neutral bar are fixed (`STACKED_ROW_BG`, `STACKED_COL_BG`, `STACKED_BORDER`,
 * `STACKED_BAR_NEUTRAL`), not per team.
 */
data class StackedColours(
    val stripe: Int,
    val barBg: Int,
    val barA: Int,
    val barB: Int,
    val goal: TeamColours,
    val rowFlashAlpha: Float,
)

/** (Stacked §5.2) A game's two teams after the stripe-collision rule (S3): use these, never two separate [NhlStackedColours.of]. */
data class StackedPair(val away: StackedColours, val home: StackedColours)

/** (Stacked §5; WP-1) The Stacked colour derivation, pure; asserted for all 32 teams and the fallback (`NhlStackedColoursTest`). */
object NhlStackedColours {
    /**
     * The team's own resolution (trimmed, upper-cased; unknown or null → the fallback), before any collision swap.
     * Resolved once per team.
     *
     * **Commit-0 stub:** [StackedColours.goal] is the real `NhlTeamColours.of(abbrev)`; every other slot is the fallback
     * stub (stripe = `goal.glow`, bar = `STACKED_BAR_NEUTRAL`, stripes white, flash α `STACKED_ROW_FLASH_ALPHA`).
     */
    fun of(abbrev: String?): StackedColours {
        val goal = NhlTeamColours.of(abbrev)
        return StackedColours(
            stripe = goal.glow,
            barBg = SportsConstants.STACKED_BAR_NEUTRAL,
            barA = WHITE,
            barB = WHITE,
            goal = goal,
            rowFlashAlpha = SportsConstants.STACKED_ROW_FLASH_ALPHA,
        )
    }

    /**
     * The game's pair with the §5.2 rule applied: if the two stripes collide (CIELAB ΔE76 < `STACKED_STRIPE_DE_MIN`), the
     * **home** team's stripe uses its other colour (lightened by the stripe rule); if that still collides, home keeps its
     * primary and the **away** team uses its other colour. The swapped stripe is used everywhere that team's stripe is
     * (row stripe, neutral bar stripes, row flash with its own C7 α); [StackedColours.goal] keeps the true colours. Cached
     * per pair.
     *
     * **Commit-0 stub:** `StackedPair(of(away), of(home))`, no collision rule.
     */
    fun pair(away: String?, home: String?): StackedPair = StackedPair(of(away), of(home))

    private const val WHITE = 0xFFFFFFFF.toInt()
}
