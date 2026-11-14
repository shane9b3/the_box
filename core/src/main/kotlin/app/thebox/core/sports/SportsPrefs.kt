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
    /**
     * (NFL §7.1, ruling 24) The league the Sports tab and the picker list; remembered and backed up. (CHL §1.3) Now any of
     * the five: choosing a CHL sub-league writes this and [chlLeague]; the CHL chip writes [chlShown]. An older build
     * reading "OHL" coerces it to NHL (the store's `coerceInputValues`, S7).
     */
    val league: League = League.NHL,
    /**
     * (Update interval §5.1, rulings 29–32) The followed game's poll interval per league, in seconds: 0 = Auto (the
     * default), else one of [SportsConstants.INTERVAL_CHOICES_NHL_S] / [SportsConstants.INTERVAL_CHOICES_NFL_S]. Backed up;
     * the measurement behind Auto is not.
     */
    val nhlIntervalS: Int = 0,
    val nflIntervalS: Int = 0,
    /**
     * (Stacked §1.3) "Score bug style" for hockey games (CHL P12: every `League.isHockey` league; the stored name is
     * unchanged); Pill (the default) is never written to the document (`encodeDefaults = false`). Backed up with the rest of the domain. An unknown name decodes to [NhlBugStyle.PILL]
     * (the store's `coerceInputValues`, S7).
     */
    val nhlStyle: NhlBugStyle = NhlBugStyle.PILL,
    /**
     * (CHL §1.3) The remembered CHL sub-league; only OHL / WHL / QMJHL are meaningful (anything else reads as OHL, see
     * [chlShown]). NHL or NFL choices leave it alone. Appended after [nhlStyle], so older documents decode unchanged.
     */
    val chlLeague: League = League.OHL,
    /**
     * (CHL P1) The CHL's poll interval in s, shared by OHL, WHL and QMJHL: 0 = Auto, else one of
     * [SportsConstants.INTERVAL_CHOICES_CHL_S].
     */
    val chlIntervalS: Int = 0,
) {
    /** [league]'s stored interval choice (0 = Auto), as stored: one setting per [LeagueGroup]. */
    fun intervalS(league: League): Int = when (league.group) {
        LeagueGroup.NHL -> nhlIntervalS
        LeagueGroup.NFL -> nflIntervalS
        LeagueGroup.CHL -> chlIntervalS
    }

    /** (CHL §1.3) The CHL sub-league to show: [chlLeague] when it is a CHL league, else OHL. */
    val chlShown: League get() = chlLeague.takeIf { it.isChl } ?: League.OHL
}
