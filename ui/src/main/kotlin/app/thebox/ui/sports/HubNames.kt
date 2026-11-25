package app.thebox.ui.sports

import androidx.compose.runtime.Immutable
import app.thebox.core.sports.League
import app.thebox.core.sports.LeagueGroup
import app.thebox.core.sports.NhlTeamPlaces
import app.thebox.core.sports.TeamLine
import app.thebox.core.sports.group
import app.thebox.core.sports.hub.ChlTeamPlaces

/**
 * (Sports Hub WP-4, docs/design/m6-sports-hub.md §4.6) One team's name split into its place and nickname: "Winnipeg" /
 * "Jets". [place] is null for an unknown team (one line). [cardPlace] is the place's form for the card's name column (a
 * short form only for a CHL place that does not fit, `ChlTeamPlaces`); [place] is the full place for the headline.
 */
@Immutable
internal data class HubName(val nickname: String, val place: String? = null, val cardPlace: String? = place) {
    /** "Winnipeg Jets"; the nickname alone for an unknown place. */
    val full: String get() = if (place == null) nickname else "$place $nickname"
    /** The card's lines: place over nickname, or the nickname alone. */
    val cardLines: List<String> get() = if (cardPlace == null) listOf(nickname) else listOf(cardPlace, nickname)
}

internal object HubNames {
    /**
     * NHL: the board's `name` is the nickname, the place from `NhlTeamPlaces` (a full "Edmonton Oilers" from a landing is
     * split the same way). NFL: `name` minus its `shortName` suffix. CHL: `ChlTeamPlaces` by `(league, code)`; the board's
     * nickname, or the table's when the source gave "City Nickname".
     */
    fun of(league: League, team: TeamLine): HubName = when (league.group) {
        LeagueGroup.NHL -> {
            val place = NhlTeamPlaces.of(team.abbrev)
            if (place == null) HubName(team.name) else HubName(team.name.removePrefix(place).trim().ifBlank { team.name }, place)
        }
        LeagueGroup.NFL -> {
            val short = team.shortName?.takeIf { it.isNotBlank() }
            if (short == null) HubName(team.name) else {
                val place = team.name.removeSuffix(short).trim().ifBlank { null }
                HubName(short, place)
            }
        }
        LeagueGroup.CHL -> {
            val e = ChlTeamPlaces.of(league, team.abbrev)
            if (e == null) HubName(team.name) else {
                val nick = if (team.name.endsWith(e.nickname)) e.nickname else team.name.removePrefix(e.place).trim().ifBlank { team.name }
                HubName(nick, e.place, e.cardPlace)
            }
        }
    }
}

/**
 * (§1.2, §1.3, U-N9, U-B4) The fit rules, pure: [Measure] answers a text's width in px at a design size (the caller applies
 * the Text size factor and the density, so the same code runs in composition and in the fit tests).
 */
internal typealias Measure = (text: String, designPx: Int, weight: Int) -> Float

internal object HubFit {
    /** The card's name sizes: 28 px in 2 px steps down to the floor 24 (× factor), then an ellipsis. */
    val NAME_SIZES = intArrayOf(28, 26, 24)
    /** The card's score sizes: 52 down to 44 (× factor) in 2 px steps (U-N9). */
    val SCORE_SIZES = intArrayOf(52, 50, 48, 46, 44)
    /** The headline's sizes: 32, then names shrink to 26 (× factor) (§1.3). */
    val HEADLINE_SIZES = intArrayOf(32, 30, 28, 26)

    /** The largest of [sizes] at which every text of [texts] fits [maxPx]; the smallest when none does (the caller ellipsizes). */
    fun size(sizes: IntArray, texts: List<String>, maxPx: Float, weight: Int, measure: Measure): Int =
        sizes.firstOrNull { s -> texts.all { measure(it, s, weight) <= maxPx } } ?: sizes.last()

    /** True when every text fits [maxPx] at the smallest size (no ellipsis needed). */
    fun fits(sizes: IntArray, texts: List<String>, maxPx: Float, weight: Int, measure: Measure): Boolean =
        texts.all { measure(it, sizes.last(), weight) <= maxPx }

    /** [size] of a name's lines in the card's name column. */
    fun nameSize(name: HubName, colPx: Float, measure: Measure): Int = size(NAME_SIZES, name.cardLines, colPx, 600, measure)

    /** [size] of a score in the card's score column. */
    fun scoreSize(score: String, colPx: Float, measure: Measure): Int = size(SCORE_SIZES, listOf(score), colPx, 700, measure)

    /**
     * (U-B4) The headline `[away][ (n)] vs. [home][ (n)]`: full names at 32, shrinking to 26; then nicknames (largest size
     * that fits); then the nicknames ellipsized at 26. Only names give way: [HeadlinePlan.scoresPx] is the width the scores
     * and the "vs." need, which the layout measures first, so a score is never cut.
     */
    fun headline(away: HubName, home: HubName, awayScore: Int?, homeScore: Int?, maxPx: Float, measure: Measure): HeadlinePlan {
        fun line(a: String, h: String) = if (awayScore != null && homeScore != null) "$a ($awayScore) vs. $h ($homeScore)" else "$a vs. $h"
        for (s in HEADLINE_SIZES) if (measure(line(away.full, home.full), s, 600) <= maxPx) return HeadlinePlan(s, nicknames = false, ellipsis = false)
        for (s in HEADLINE_SIZES) if (measure(line(away.nickname, home.nickname), s, 600) <= maxPx) return HeadlinePlan(s, nicknames = true, ellipsis = false)
        return HeadlinePlan(HEADLINE_SIZES.last(), nicknames = true, ellipsis = true)
    }

    /** The width the headline's fixed parts need at [designPx]: the scores and " vs. ". */
    fun headlineFixedPx(awayScore: Int?, homeScore: Int?, designPx: Int, measure: Measure): Float {
        val fixed = if (awayScore != null && homeScore != null) " ($awayScore) vs. ($homeScore)" else " vs. "
        return measure(fixed, designPx, 600)
    }
}

/** What [HubFit.headline] decided: the design size, nicknames only, and whether the names still need an ellipsis. */
internal data class HeadlinePlan(val designPx: Int, val nicknames: Boolean, val ellipsis: Boolean)
