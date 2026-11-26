package app.thebox.ui.full

import android.content.res.Resources
import app.thebox.core.full.PickerChoice
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.LeagueGroup
import app.thebox.core.sports.Sport
import app.thebox.core.sports.group
import app.thebox.core.sports.sport
import app.thebox.ui.R
import app.thebox.ui.sports.HubNames
import app.thebox.ui.sports.Measure
import app.thebox.ui.sports.SportsText
import java.util.Locale
import kotlin.math.ceil

/**
 * (Score sheet restyle, owner mock-up 2026-11-26) The quick-bar score sheet's geometry in design px (1 design px = screen
 * width ÷ 1920, the player's `.dp`). The sheet keeps the picker's box ([PlayerUi.PICKER_W] × [PlayerUi.PICKER_H]); rows
 * have their own heights, grown with the Text size factor above Default so nothing clips at Largest.
 */
internal object ScoreSheetMetrics {
    const val PAD_H = 16
    const val PAD_V = 20
    /** Space between two rows. */
    const val GAP = 4
    const val TITLE_H = 56
    /** Space between the title row and the first row. */
    const val TITLE_GAP = 8
    const val ROW_PAD_H = 16
    const val SETTING_H = 64
    const val GAME_H = 84
    const val HEADER_H = 48
    /** The failure row: two settings rows and the gap between them (132), as before the restyle. */
    const val FAILED_H = 2 * SETTING_H + GAP
    const val TEAM_LOGO = 44
    const val LOGO_GAP = 12
    const val LEAGUE_LOGO = 32
    const val MARKER_W = 20
    const val MARKER_H = 14
    const val MARKER_GAP = 12
    /** The focused row's border. */
    const val FOCUS_BORDER = 2
    /** The score at the right of a live / final game: 30/700 tabular digits, after this gap. */
    const val SCORE_PX = 30
    const val SCORE_GAP = 12
    /** The widest score the right-hand block is sized for (two two-digit numbers), so the blocks line up down the list. */
    const val SCORE_SAMPLE = "88 – 88"

    /** [base] at Default and below; grown by [factor] (rounded up) above it. */
    fun grow(base: Int, factor: Float): Int = if (factor <= 1f) base else ceil(base * factor).toInt()

    fun titleH(factor: Float): Int = grow(TITLE_H, factor)

    /** One row's height at [factor]. */
    fun height(c: PickerChoice, factor: Float): Int = grow(
        when (c) {
            is PickerChoice.ScoreGame -> GAME_H
            PickerChoice.ScoreTodayDivider, is PickerChoice.ScoreDayDivider -> HEADER_H
            is PickerChoice.ScoreFailed -> FAILED_H
            else -> SETTING_H
        },
        factor,
    )

    /** The height the rows (and the gaps between them) may take under the title. */
    fun budget(factor: Float): Int = PlayerUi.PICKER_H.toInt() - 2 * PAD_V - titleH(factor) - TITLE_GAP

    /**
     * The width of a game row's text column: after the away logo, before the followed marker's slot (reserved on every game
     * row so the scores line up whether or not the row is followed). A score block, when shown, comes out of this.
     */
    fun gameTextW(): Int = PlayerUi.PICKER_W.toInt() - 2 * PAD_H - 2 * ROW_PAD_H - TEAM_LOGO - LOGO_GAP - MARKER_GAP - MARKER_W
}

/**
 * The rows the score sheet shows: a contiguous window that always holds [focused] and whose rows (heights [heights], [gap]
 * between two) fit [budget]. It grows from the focus one row at a time on the side that has less so far, so the focus
 * sits in the middle where the list allows and the window fills the sheet at either end of the list. A lone row taller
 * than the budget still shows.
 */
internal fun scoreWindow(heights: List<Int>, focused: Int, budget: Int, gap: Int = ScoreSheetMetrics.GAP): IntRange {
    val n = heights.size
    if (n == 0) return IntRange.EMPTY
    val f = focused.coerceIn(0, n - 1)
    var top = f
    var end = f + 1
    var used = heights[f]
    var above = 0
    var below = 0
    while (true) {
        val up = if (top > 0) gap + heights[top - 1] else Int.MAX_VALUE
        val down = if (end < n) gap + heights[end] else Int.MAX_VALUE
        val canUp = up != Int.MAX_VALUE && used + up <= budget
        val canDown = down != Int.MAX_VALUE && used + down <= budget
        if (!canUp && !canDown) break
        if (canUp && (!canDown || above <= below)) { top--; used += up; above += up } else { end++; used += down; below += down }
    }
    return top until end
}

/** The total height of [window]'s rows and the gaps between them. */
internal fun scoreWindowHeight(heights: List<Int>, window: IntRange, gap: Int = ScoreSheetMetrics.GAP): Int =
    if (window.isEmpty()) 0 else window.sumOf { heights[it] } + gap * (window.last - window.first)

/** What the matchup line of a game that has not started shows, and at what design size. */
internal data class MatchupPlan(val away: String, val home: String, val designPx: Int)

/**
 * The sheet's fit rules, pure: [Measure] answers a text's width in px at a design size and weight (the caller applies the
 * Text size factor and the density). A game title never wraps and never cuts a team: it steps down a ladder of forms.
 */
internal object ScoreSheetFit {
    val SIZES = intArrayOf(26, 24, 22)
    const val ABBREV_PX = 26
    /** Weight the matchup is measured at (the teams are drawn at 600, "vs" lighter, so this is the safe side). */
    const val MATCHUP_WEIGHT = 600
    /** "CHI Bears"; the abbreviation alone when the name is blank or says the same; the name alone without an abbreviation. */
    fun full(abbrev: String, name: String): String {
        val a = abbrev.trim()
        val n = name.trim()
        return when {
            n.isEmpty() || n.equals(a, ignoreCase = true) -> a
            a.isEmpty() -> n
            else -> "$a $n"
        }
    }

    /** "Bears"; the abbreviation when there is no name. */
    fun short(abbrev: String, name: String): String = name.trim().ifEmpty { abbrev.trim() }

    /** The one-line text of a matchup as the sheet draws it. */
    fun line(away: String, home: String, vs: String): String = "$away $vs $home"

    /**
     * The ladder: full forms ("CHI Bears vs DET Lions") at 26, 24, 22; then names only ("Bears vs Lions") at 26, 24, 22; then
     * abbreviations ("CHI vs DET") at 26 (and, should even that not fit, at 24 and 22). The first that fits [maxPx] wins.
     */
    fun matchup(
        awayAbbrev: String, awayName: String, homeAbbrev: String, homeName: String, vs: String, maxPx: Float, measure: Measure,
    ): MatchupPlan {
        fun fits(a: String, h: String, px: Int) = ceil(measure(line(a, h, vs), px, MATCHUP_WEIGHT)) <= maxPx
        val fa = full(awayAbbrev, awayName)
        val fh = full(homeAbbrev, homeName)
        for (px in SIZES) if (fits(fa, fh, px)) return MatchupPlan(fa, fh, px)
        val sa = short(awayAbbrev, awayName)
        val sh = short(homeAbbrev, homeName)
        for (px in SIZES) if (fits(sa, sh, px)) return MatchupPlan(sa, sh, px)
        val aa = awayAbbrev.trim().ifEmpty { sa }
        val ah = homeAbbrev.trim().ifEmpty { sh }
        for (px in intArrayOf(ABBREV_PX) + SIZES.filter { it < ABBREV_PX }) if (fits(aa, ah, px)) return MatchupPlan(aa, ah, px)
        return MatchupPlan(aa, ah, SIZES.last())
    }

    /** One game's title inputs for [commonStep]: the four name parts and the width its row leaves the title. */
    class TitleAsk(val awayAbbrev: String, val awayName: String, val homeAbbrev: String, val homeName: String, val maxPx: Float)

    /** The ladder as (form, design px) steps: form 0 = full, 1 = names only, 2 = abbreviations. */
    val STEPS: List<Pair<Int, Int>> =
        SIZES.map { 0 to it } + SIZES.map { 1 to it } + (intArrayOf(ABBREV_PX) + SIZES.filter { it < ABBREV_PX }).map { 2 to it }

    private fun side(form: Int, abbrev: String, name: String): String = when (form) {
        0 -> full(abbrev, name)
        1 -> short(abbrev, name)
        else -> abbrev.trim().ifEmpty { short(abbrev, name) }
    }

    /** [ask]'s title at [step] of [STEPS]. */
    fun atStep(ask: TitleAsk, step: Int): MatchupPlan {
        val (form, px) = STEPS[step.coerceIn(0, STEPS.lastIndex)]
        return MatchupPlan(side(form, ask.awayAbbrev, ask.awayName), side(form, ask.homeAbbrev, ask.homeName), px)
    }

    /**
     * (Lead review 2026-11-26) One form and one size for every game title of the sheet, so the list reads evenly: the
     * first step of [STEPS] at which **every** title fits the width its own row leaves it (a row with a score has less).
     * It never goes past the smallest names-only step ([LAST_NAMES_STEP]): one long pair beside a score does not turn the
     * whole list into abbreviations; that row alone steps further down ([shared]).
     */
    fun commonStep(asks: List<TitleAsk>, vs: String, measure: Measure): Int {
        for (i in 0..LAST_NAMES_STEP) if (asks.all { fitsAt(it, i, vs, measure) }) return i
        return LAST_NAMES_STEP
    }

    /** The last step of [STEPS] that still shows names ("Bears vs Lions" at the smallest size). */
    val LAST_NAMES_STEP: Int = 2 * SIZES.size - 1

    private fun fitsAt(a: TitleAsk, step: Int, vs: String, measure: Measure): Boolean =
        atStep(a, step).let { ceil(measure(line(it.away, it.home, vs), it.designPx, MATCHUP_WEIGHT)) <= a.maxPx }

    /** [ask]'s title at the sheet's [step]; when it does not fit there, the first later step that does (the last if none). */
    fun shared(ask: TitleAsk, step: Int, vs: String, measure: Measure): MatchupPlan {
        for (i in step.coerceIn(0, STEPS.lastIndex)..STEPS.lastIndex) if (fitsAt(ask, i, vs, measure)) return atStep(ask, i)
        return atStep(ask, STEPS.lastIndex)
    }

    /**
     * The matchup title's width in px: the text column ([columnPx]) less the score block ([scoreBlockPx]) and its gap
     * ([gapPx]) when the game shows a score ([scored]); the whole column otherwise.
     */
    fun titleWidth(columnPx: Float, scored: Boolean, scoreBlockPx: Float, gapPx: Float): Float =
        if (scored) columnPx - scoreBlockPx - gapPx else columnPx

    /** The score block's width in px: the widest score ([ScoreSheetMetrics.SCORE_SAMPLE]) at 30/700, rounded up. */
    fun scoreBlockPx(measure: Measure): Float = ceil(measure(ScoreSheetMetrics.SCORE_SAMPLE, ScoreSheetMetrics.SCORE_PX, 700)) + 2f

    /** A final's winner: 0 away, 1 home; −1 for a tie, a game that is not final or a missing score. */
    fun winner(g: GameSnapshot): Int {
        if (g.phase != GamePhase.FINAL) return -1
        val a = g.away.score ?: return -1
        val h = g.home.score ?: return -1
        return if (a > h) 0 else if (h > a) 1 else -1
    }

    /** True when [side] (0 away, 1 home) is drawn in the dim ink: the loser of a final. */
    fun dimmed(g: GameSnapshot, side: Int): Boolean = winner(g).let { it >= 0 && it != side }

    /** True for a game in progress (the red LIVE). */
    fun inProgress(g: GameSnapshot): Boolean = g.phase == GamePhase.LIVE || g.phase == GamePhase.INTERMISSION

    /** True for a game that shows its score at the right (in progress or final). */
    fun scored(g: GameSnapshot): Boolean = inProgress(g) || g.phase == GamePhase.FINAL
}

/** A settings row's text: [label] on the left, [value] on the right ([chip]: drawn in a small pill; [logo]: a league logo URL). */
internal data class SettingText(val label: String, val value: String?, val chip: Boolean = false, val logo: String? = null)

/** A game row's second line: [text], after a red dot and "LIVE ·" when [live]. */
internal data class GameStatus(val live: Boolean, val text: String)

/** The words of the restyled score sheet (the plain one-line labels stay [PlayerText.choice]). */
internal object ScoreSheetText {
    /** The NHL's league mark (the `_dark` art the team logos use on dark backgrounds; same host as `SportsEndpoints.LOGO_BASE`). */
    const val NHL_LOGO = "https://assets.nhle.com/logos/nhl/svg/NHL_dark.svg"
    /** The NFL's league mark, on the ESPN logo host the NFL team logos use (`EspnScoreSource.LOGO_BASE`, not reachable from :ui). */
    const val NFL_LOGO = "https://a.espncdn.com/i/teamlogos/leagues/500/nfl.png"

    fun leagueLogo(group: LeagueGroup): String? = when (group) {
        LeagueGroup.NHL -> NHL_LOGO
        LeagueGroup.NFL -> NFL_LOGO
        LeagueGroup.CHL -> null
    }

    fun title(res: Resources, delay: Boolean, locale: Locale = Locale.getDefault()): String =
        res.getString(if (delay) R.string.sports_picker_delay_title else R.string.sports_picker_settings_title).uppercase(locale)

    /** A settings row's label and value; null for any other row. */
    fun setting(res: Resources, c: PickerChoice): SettingText? = when (c) {
        is PickerChoice.ScoreLeagueRow -> SettingText(
            res.getString(R.string.sports_picker_league_label), PlayerText.groupName(res, c.league.group), logo = leagueLogo(c.league.group),
        )
        is PickerChoice.ScoreChlRow -> SettingText(res.getString(R.string.sports_picker_chl_label), PlayerText.leagueName(res, c.league))
        is PickerChoice.ScoreCornerRow -> SettingText(res.getString(R.string.sports_picker_corner_label), SportsText.corner(res, c.corner))
        is PickerChoice.ScoreDelayRow -> SettingText(res.getString(R.string.sports_picker_delay_label), SportsText.delay(res, c.delaySec), chip = true)
        is PickerChoice.ScoreStyleRow -> SettingText(res.getString(R.string.sports_picker_style_label), PlayerText.styleName(res, c.style))
        PickerChoice.ScoreStop -> SettingText(res.getString(R.string.sports_picker_stop), null)
        else -> null
    }

    /** The section header over the games, upper case: "THIS WEEK'S MATCHUPS", "TODAY'S GAMES", else the divider's label. */
    fun header(res: Resources, c: PickerChoice, clock24: Boolean, locale: Locale = Locale.getDefault()): String = when {
        c is PickerChoice.ScoreDayDivider && c.current -> res.getString(R.string.sports_picker_header_this_week)
        c == PickerChoice.ScoreTodayDivider -> res.getString(R.string.sports_picker_header_today)
        else -> PlayerText.choice(res, c, 0, clock24)
    }.uppercase(locale)

    /** A team's name for the matchup line: the nickname ("Bears", "Knights"); the feed's short name or name when unknown. */
    fun teamName(league: League, team: app.thebox.core.sports.TeamLine): String =
        HubNames.of(league, team).nickname.ifBlank { team.shortName ?: team.name }

    /**
     * A game's second line. Before the start: the NFL "Today 1:00 PM" or "Sun 1:00 PM", hockey "7:00 PM", "Time TBD";
     * "Postponed"; in progress the state after LIVE ("2nd 10:22", "Halftime", "End 3rd", "1st INT", "SO"); "Final/OT".
     */
    fun status(res: Resources, g: GameSnapshot, clock24: Boolean, nowMs: Long = System.currentTimeMillis()): GameStatus {
        val league = g.id.league
        return when (g.phase) {
            GamePhase.SCHEDULED, GamePhase.PRE -> when {
                g.startTbd -> GameStatus(false, res.getString(R.string.sports_time_tbd))
                league.sport == Sport.FOOTBALL && isToday(g.startUtcMs, nowMs) ->
                    GameStatus(false, res.getString(R.string.sports_picker_today_at, SportsText.startTime(g.startUtcMs, clock24)))
                else -> GameStatus(false, SportsText.state(res, g, clock24, league, nowMs))
            }
            GamePhase.POSTPONED -> GameStatus(false, res.getString(R.string.sports_postponed))
            GamePhase.LIVE, GamePhase.INTERMISSION -> GameStatus(true, SportsText.state(res, g, clock24, league, nowMs))
            GamePhase.FINAL -> GameStatus(false, SportsText.state(res, g, clock24, league, nowMs))
        }
    }

    private fun isToday(startUtcMs: Long, nowMs: Long): Boolean {
        val zone = java.time.ZoneId.systemDefault()
        return java.time.Instant.ofEpochMilli(startUtcMs).atZone(zone).toLocalDate() == java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    }
}
