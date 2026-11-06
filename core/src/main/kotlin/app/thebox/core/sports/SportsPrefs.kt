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
)
