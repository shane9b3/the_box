// Contract (CHL fix pass, docs/design/m6-chl-leagues.md §6.4, lead ruling on O3): the step-down rule for team codes in the
// logo fallbacks, shared by the painters (WP-5: the `TeamLogo` capsule, the `GoalPainter` and `StackedGoalPainter` badge
// text) and the fit tests. Changes go through the lead.
package app.thebox.core.sports

/**
 * (§6.4, Q4) The 2 px step-down extended to team codes. A code never gets a display alias: it is drawn smaller, down to a
 * floor, or the fit is a lead escalation.
 */
object CodeFit {
    /**
     * The floor of a logo-fallback code's text: [SportsConstants.LOGO_FALLBACK_CODE4_FLOOR_PX] (16) for a code of 4 or
     * more characters, else the general [SportsConstants.SCORE_TEXT_FLOOR_PX] (18).
     */
    fun fallbackFloorPx(code: String): Int =
        if (code.length >= 4) SportsConstants.LOGO_FALLBACK_CODE4_FLOOR_PX else SportsConstants.SCORE_TEXT_FLOOR_PX

    /**
     * The largest size from [startPx] down in [SportsConstants.SCORE_FIT_STEP_PX] steps to [floorPx] at which [fits]
     * holds; [startPx] itself when it fits (so a code that fits today is drawn exactly as today). Null = it does not fit
     * even at [floorPx]: a lead escalation, never a silent clip.
     */
    fun stepDown(startPx: Int, floorPx: Int, fits: (Int) -> Boolean): Int? {
        var px = startPx
        while (true) {
            if (fits(px)) return px
            if (px <= floorPx) return null
            px = maxOf(floorPx, px - SportsConstants.SCORE_FIT_STEP_PX)
        }
    }
}
