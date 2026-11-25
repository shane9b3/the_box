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
     * The headline's fixed words (review L2: from the strings file): [vs] "vs." and [score] "(%d)". [middle] is what sits
     * between the names (" (2) vs. " or " vs. "), [tail] what follows the home name (" (3)").
     */
    class HeadlineWords(private val vs: String, private val score: (Int) -> String) {
        fun middle(awayScore: Int?, homeScore: Int?): String =
            if (awayScore != null && homeScore != null) " " + score(awayScore) + " " + vs + " " else " $vs "
        fun tail(homeScore: Int?): String = if (homeScore != null) " " + score(homeScore) else ""

        companion object {
            /** The English words, for the pure fit tests. */
            val DEFAULT = HeadlineWords("vs.") { "($it)" }
            fun of(res: android.content.res.Resources) = HeadlineWords(res.getString(app.thebox.ui.R.string.sports_hub_headline_vs)) {
                res.getString(app.thebox.ui.R.string.sports_hub_headline_score, it)
            }
        }
    }

    /**
     * (U-B4) The headline `[away][ (n)] vs. [home][ (n)]`: full names at 32, shrinking to 26; then nicknames (largest size
     * that fits); then the nicknames ellipsized at 26. Only names give way: the layout measures the scores and the "vs."
     * first, so a score is never cut.
     *
     * (Review M5) The line is measured as the layout draws it: four texts, each rounded up to whole px, so a line that fits
     * by a pixel or two in one piece is not cut once split. In the ellipsis case the names' weights are their widths.
     */
    fun headline(
        away: HubName, home: HubName, awayScore: Int?, homeScore: Int?, maxPx: Float, measure: Measure,
        words: HeadlineWords = HeadlineWords.DEFAULT,
    ): HeadlinePlan {
        val scored = awayScore != null && homeScore != null
        val middle = words.middle(awayScore, homeScore)
        val tail = if (scored) words.tail(homeScore) else ""
        fun w(text: String, s: Int): Float = if (text.isEmpty()) 0f else kotlin.math.ceil(measure(text, s, 600))
        fun width(a: String, h: String, s: Int) = w(a, s) + w(middle, s) + w(h, s) + w(tail, s)
        for (s in HEADLINE_SIZES) if (width(away.full, home.full, s) <= maxPx) return HeadlinePlan(s, nicknames = false, ellipsis = false)
        for (s in HEADLINE_SIZES) if (width(away.nickname, home.nickname, s) <= maxPx) return HeadlinePlan(s, nicknames = true, ellipsis = false)
        val s = HEADLINE_SIZES.last()
        val a = w(away.nickname, s).coerceAtLeast(1f)
        val h = w(home.nickname, s).coerceAtLeast(1f)
        return HeadlinePlan(s, nicknames = true, ellipsis = true, awayWeight = a / (a + h), homeWeight = h / (a + h))
    }

    /** The width the headline's fixed parts need at [designPx]: the scores and " vs. ". */
    fun headlineFixedPx(awayScore: Int?, homeScore: Int?, designPx: Int, measure: Measure, words: HeadlineWords = HeadlineWords.DEFAULT): Float {
        fun w(text: String): Float = if (text.isEmpty()) 0f else kotlin.math.ceil(measure(text, designPx, 600))
        return w(words.middle(awayScore, homeScore)) + if (awayScore != null && homeScore != null) w(words.tail(homeScore)) else 0f
    }
}

/** What [HubFit.headline] decided: the design size, nicknames only, and whether the names still need an ellipsis. */
internal data class HeadlinePlan(
    val designPx: Int, val nicknames: Boolean, val ellipsis: Boolean,
    /** The names' shares of the free width when they ellipsize (their measured widths); unused otherwise. */
    val awayWeight: Float = 0.5f, val homeWeight: Float = 0.5f,
)
