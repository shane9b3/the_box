package app.thebox.core.sports

import kotlinx.serialization.Serializable

/**
 * The `sports` domain (docs/design/m6-score-bug.md §7): app-wide, never per playlist; backed up. [enabled] is "Show
 * Sports" (Off hides the tab and the quick-bar item, stops every request and ends a follow); [delaySec] is one of
 * [SportsConstants.DELAY_STEPS_S] (0 = "Off").
 */
@Serializable
data class SportsPrefs(
    val enabled: Boolean = true,
    val corner: ScoreCorner = ScoreCorner.TOP_RIGHT,
    val delaySec: Int = 0,
    val size: ScoreBugSize = ScoreBugSize.STANDARD,
    /** (NFL §7.1, ruling 24) The league the Sports tab and the picker list; remembered and backed up. */
    val league: League = League.NHL,
    /**
     * (Update interval §5.1, rulings 29–32) The followed game's poll interval per league, in seconds: 0 = Auto (the
     * default), else one of [SportsConstants.INTERVAL_CHOICES_NHL_S] / [SportsConstants.INTERVAL_CHOICES_NFL_S]. Backed up;
     * the measurement behind Auto is not.
     */
    val nhlIntervalS: Int = 0,
    val nflIntervalS: Int = 0,
    /**
     * (Stacked §1.3) "Score bug style" for NHL games; Pill (the default) is never written to the document
     * (`encodeDefaults = false`). Backed up with the rest of the domain. An unknown name decodes to [NhlBugStyle.PILL]
     * (the store's `coerceInputValues`, S7).
     */
    val nhlStyle: NhlBugStyle = NhlBugStyle.PILL,
) {
    /** [league]'s stored interval choice (0 = Auto), as stored. */
    fun intervalS(league: League): Int = if (league == League.NHL) nhlIntervalS else nflIntervalS
}
