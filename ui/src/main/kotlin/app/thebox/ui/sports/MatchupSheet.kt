package app.thebox.ui.sports

import android.content.res.Resources
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import app.thebox.ui.theme.DesignTokens
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.GameResult
import app.thebox.core.sports.hub.InjuryLine
import app.thebox.core.sports.hub.InjuryStatus
import app.thebox.core.sports.hub.RecentGame
import app.thebox.core.sports.hub.Goalie
import app.thebox.core.sports.hub.Leader
import app.thebox.core.sports.hub.LeaderPair
import app.thebox.core.sports.group
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import java.util.Locale

const val MATCHUP_SHEET_TAG = "hub_matchup_sheet"

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §6.4; WP-5) The Matchup sheet (pre-game NHL): "Leaders · last 5 games"
 * (Points, Goals, Assists) and "Goalies · this season" from [detail]. Focus on the first row; Back calls [onClose].
 *
 * Each row shows the away entry on the left half and the home entry on the right half.
 *
 * (NFL hub §3.6) An NFL game: Season leaders (Passing, Rushing, Receiving, Sacks, Tackles: a label row, then one row with
 * the away and home leaders, headshot 72, the name by §2.4's rule and ESPN's line shortened), Last 5 games (one row per
 * team, oldest first, winner's score first) and the Injury report (Out, Doubtful, Questionable; at most
 * `HUB_NFL_INJURIES_PER_TEAM` per team, then "+n more"). Label rows never take focus; the list scrolls with the D-pad.
 */
@Composable
fun MatchupSheet(
    game: GameSnapshot,
    detail: GameDetail,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val res = LocalResources.current
    val nfl = game.id.league.group == app.thebox.core.sports.LeagueGroup.NFL
    // (CHL hub fix pass, A1) The CHL's focused row keeps its empty headshot disc visible; the NHL's and NFL's pixels as before.
    val chl = game.id.league.group == app.thebox.core.sports.LeagueGroup.CHL
    val rows = remember(detail, game, nfl) { if (nfl) nflMatchupRows(res, game, detail) else matchupRows(detail, res, game) }
    val focusable = rows.indices.filter { rows[it].focusable }
    HubSheetFrame(
        title = res.getString(R.string.sports_hub_sheet_matchup, game.away.abbrev, game.home.abbrev),
        rowCount = rows.size,
        initialRow = focusable.firstOrNull() ?: 0,
        focusKey = game.id,
        onClose = onClose,
        modifier = modifier.testTag(MATCHUP_SHEET_TAG),
        focusableRows = focusable.size,
        scrollTo = labelStart(focusable.firstOrNull() ?: 0) { !rows[it].focusable },
    ) { reqs ->
        rows.forEachIndexed { i, row ->
            item(key = "m:$i") {
                val first = focusable.firstOrNull() == i
                val last = focusable.lastOrNull() == i
                when (row) {
                    is MatchupRow.Header -> Line(res.getString(row.title), GuideUi.sans(22, FontWeight.SemiBold, GuideUi.Fg70), Modifier.padding(start = 16.dp, top = 8.dp))
                    is MatchupRow.Leaders -> HubSheetRow(reqs[i], first, last, key = "lead:$i", height = 104, onClick = {}) { c ->
                        val label = leaderCategory(res, row.pair.category)
                        Line(label, GuideUi.sans(22, FontWeight.SemiBold, GuideUi.secondary(c, focusedOf(c), 0.70f)), Modifier.weight(0.6f))
                        LeaderCell(row.pair.away, c, Modifier.weight(1f), chl && focusedOf(c))
                        LeaderCell(row.pair.home, c, Modifier.weight(1f), chl && focusedOf(c))
                    }
                    is MatchupRow.Goalies -> HubSheetRow(reqs[i], first, last, key = "gk:$i", height = 96, onClick = {}) { c ->
                        GoalieCell(res, row.away, c, Modifier.weight(1f), chl && focusedOf(c))
                        GoalieCell(res, row.home, c, Modifier.weight(1f), chl && focusedOf(c))
                    }
                    is MatchupRow.Label -> Line(row.text, GuideUi.sans(22, FontWeight.SemiBold, GuideUi.Fg55), Modifier.padding(start = 16.dp, top = 8.dp).testTag(matchupLabelTag(i)))
                    is MatchupRow.NflLeaders -> HubSheetRow(reqs[i], first, last, key = "nlead:$i", height = 104, onClick = {}) { c ->
                        NflLeaderCell(res, row.category, row.away, c, Modifier.weight(1f))
                        NflLeaderCell(res, row.category, row.home, c, Modifier.weight(1f))
                    }
                    // (Code review 2026-11-26, UI m9) The team line and two lines of results need more than 96 px from
                    // Larger up (Largest: 34 + 2 + 2 × 34): the row grows with the text size (96 at Default).
                    is MatchupRow.Recent -> HubSheetRow(reqs[i], first, last, key = "recent:$i", height = recentRowHeight(), onClick = {}) { c ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Line(row.team, GuideUi.sans(22, FontWeight.SemiBold, GuideUi.secondary(c, focusedOf(c), 0.70f)))
                            Line(row.text, GuideUi.sans(22, FontWeight.Medium, c), maxLines = 2)
                        }
                    }
                    is MatchupRow.Injury -> HubSheetRow(reqs[i], first, last, key = "inj:$i", height = 56, description = row.desc, onClick = {}) { c ->
                        Line(row.text, GuideUi.sans(22, FontWeight.Medium, c), Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** (UI m9) The Last 5 row's height in design px: 96 at Default, scaled with the text size above it (never below 96). */
internal fun recentRowHeight(): Int = kotlin.math.ceil(96f * app.thebox.core.settings.UiTextScale.factor.coerceAtLeast(1f)).toInt()

internal sealed interface MatchupRow {
    val focusable: Boolean get() = true
    data class Header(val title: Int) : MatchupRow { override val focusable get() = false }
    data class Leaders(val pair: LeaderPair) : MatchupRow
    data class Goalies(val away: Goalie?, val home: Goalie?) : MatchupRow
    /** (NFL hub §3.6) A label row (a leader category, a team, "+n more"): not focusable. */
    data class Label(val text: String) : MatchupRow { override val focusable get() = false }
    data class NflLeaders(val category: String, val away: Leader?, val home: Leader?) : MatchupRow
    /** One team's last five: [team] its nickname, [text] "W 23–13 DAL · L 20–17 @ DET · …". */
    data class Recent(val team: String, val text: String) : MatchupRow
    data class Injury(val text: String, val desc: String) : MatchupRow
}

internal fun matchupLabelTag(i: Int) = "hub_matchup_label:$i"

/** (NFL hub §3.6) The categories of the Season leaders, in order, with their labels. */
private val NFL_LEADER_CATEGORIES = listOf(
    "passingYards" to R.string.sports_hub_matchup_passing,
    "rushingYards" to R.string.sports_hub_matchup_rushing,
    "receivingYards" to R.string.sports_hub_matchup_receiving,
    "sacks" to R.string.sports_hub_matchup_sacks,
    "totalTackles" to R.string.sports_hub_matchup_tackles,
)

/** (§3.6) The Season leaders the sheet draws: the five categories in order, each with at least one side. */
private fun nflLeaders(d: GameDetail): List<Triple<String, Int, LeaderPair>> = NFL_LEADER_CATEGORIES.mapNotNull { (cat, label) ->
    d.leaders.firstOrNull { it.category.equals(cat, ignoreCase = true) }?.takeIf { it.away != null || it.home != null }?.let { Triple(cat, label, it) }
}

/**
 * (Code review 2026-11-26, UI m5) Whether the NFL Matchup sheet has a focusable row ([nflMatchupRows]`.any { it.focusable }`
 * without building the words): a drawn leader pair, a team's last five, or an injury line. The NFL MATCHUP slot is enabled
 * exactly then, so the sheet never opens with nothing to focus.
 */
internal fun nflMatchupHasRows(d: GameDetail): Boolean =
    nflLeaders(d).isNotEmpty() || d.recent.isNotEmpty() || d.injuries.isNotEmpty()

/** (NFL hub §3.6) The NFL sections: Season leaders, Last 5 games, Injury report; a section without rows is left out. */
internal fun nflMatchupRows(res: Resources, game: app.thebox.core.sports.GameSnapshot, d: GameDetail): List<MatchupRow> = buildList {
    val away = HubNames.of(game.id.league, game.away)
    val home = HubNames.of(game.id.league, game.home)
    val leaders = nflLeaders(d)
    if (leaders.isNotEmpty()) {
        add(MatchupRow.Header(R.string.sports_hub_matchup_season_leaders))
        for ((cat, label, p) in leaders) {
            add(MatchupRow.Label(res.getString(label)))
            add(MatchupRow.NflLeaders(cat, p.away, p.home))
        }
    }
    addAll(recentRows(res, game, d))
    val max = app.thebox.core.sports.SportsConstants.HUB_NFL_INJURIES_PER_TEAM
    val injuries = listOf(app.thebox.core.sports.Side.AWAY to away, app.thebox.core.sports.Side.HOME to home).mapNotNull { (side, name) ->
        d.injuries.filter { it.side == side }.takeIf { it.isNotEmpty() }?.let { Triple(name, it, d.injuryCounts[side] ?: it.size) }
    }
    if (injuries.isNotEmpty()) {
        add(MatchupRow.Header(R.string.sports_hub_matchup_injuries))
        for ((name, lines, total) in injuries) {
            add(MatchupRow.Label(name.nickname))
            val shown = lines.take(max)
            shown.forEach { add(MatchupRow.Injury(injuryText(res, it), injuryDesc(res, it))) }
            // (Integration §18) "+n more" from ESPN's full count (GameDetail.injuryCounts), not from the lines kept.
            if (total > shown.size) add(MatchupRow.Label(res.getString(R.string.sports_hub_more_n, total - shown.size)))
        }
    }
}

/** "W 23–13 DAL", "L 20–17 @ DET" (ESPN's score, winner first; "vs" games carry no prefix). */
internal fun recentText(res: Resources, r: RecentGame): String {
    val result = res.getString(
        when (r.result) {
            GameResult.W -> R.string.sports_hub_result_w
            GameResult.L -> R.string.sports_hub_result_l
            GameResult.T -> R.string.sports_hub_result_t
        },
    )
    val score = r.score.replace('-', '–')
    return res.getString(if (r.atOpponent) R.string.sports_hub_recent_at else R.string.sports_hub_recent_vs, result, score, r.opponent)
}

private fun statusWord(res: Resources, s: InjuryStatus, spoken: Boolean): String = res.getString(
    when (s) {
        InjuryStatus.OUT -> if (spoken) R.string.sports_hub_desc_injury_out else R.string.sports_hub_injury_out
        InjuryStatus.DOUBTFUL -> if (spoken) R.string.sports_hub_desc_injury_doubtful else R.string.sports_hub_injury_doubtful
        InjuryStatus.QUESTIONABLE -> if (spoken) R.string.sports_hub_desc_injury_questionable else R.string.sports_hub_injury_questionable
    },
)

/** "Kevin Zeitler" → "K. Zeitler" (the drawn line; the spoken form keeps the whole name). */
internal fun initialName(name: String): String {
    val n = name.trim()
    val i = n.indexOf(' ')
    return if (i <= 0) n else "${n[0]}. ${n.substring(i + 1)}"
}

/** "K. Zeitler · G · Questionable (triceps)". */
internal fun injuryText(res: Resources, l: InjuryLine): String {
    val pos = l.position?.takeIf { it.isNotBlank() } ?: res.getString(R.string.sports_hub_stat_none)
    val status = statusWord(res, l.status, spoken = false)
    val detail = l.detail?.takeIf { it.isNotBlank() }
    return if (detail != null) res.getString(R.string.sports_hub_injury_line, initialName(l.name), pos, status, detail.lowercase(Locale.getDefault()))
    else res.getString(R.string.sports_hub_injury_line_plain, initialName(l.name), pos, status)
}

/** §3.7: "Kevin Zeitler, guard, questionable, triceps"; an unknown position is read as written. */
internal fun injuryDesc(res: Resources, l: InjuryLine): String {
    val pos = l.position?.takeIf { it.isNotBlank() }?.let { positionName(res, it) } ?: ""
    val status = statusWord(res, l.status, spoken = true)
    val detail = l.detail?.takeIf { it.isNotBlank() }
    return if (detail != null) res.getString(R.string.sports_hub_desc_injury, l.name, pos, status, detail.lowercase(Locale.getDefault()))
    else res.getString(R.string.sports_hub_desc_injury_plain, l.name, pos, status)
}

/** (§3.7) The NFL position abbreviations spoken in full; anything else as written. */
internal fun positionName(res: Resources, abbr: String): String = when (abbr.trim().uppercase(Locale.ROOT)) {
    "QB" -> res.getString(R.string.sports_hub_pos_qb)
    "RB" -> res.getString(R.string.sports_hub_pos_rb)
    "FB" -> res.getString(R.string.sports_hub_pos_fb)
    "WR" -> res.getString(R.string.sports_hub_pos_wr)
    "TE" -> res.getString(R.string.sports_hub_pos_te)
    "OT" -> res.getString(R.string.sports_hub_pos_ot)
    "T" -> res.getString(R.string.sports_hub_pos_t)
    "G" -> res.getString(R.string.sports_hub_pos_g)
    "C" -> res.getString(R.string.sports_hub_pos_c)
    "DE" -> res.getString(R.string.sports_hub_pos_de)
    "DT" -> res.getString(R.string.sports_hub_pos_dt)
    "NT" -> res.getString(R.string.sports_hub_pos_nt)
    "LB" -> res.getString(R.string.sports_hub_pos_lb)
    "OLB" -> res.getString(R.string.sports_hub_pos_olb)
    "ILB" -> res.getString(R.string.sports_hub_pos_ilb)
    "CB" -> res.getString(R.string.sports_hub_pos_cb)
    "S" -> res.getString(R.string.sports_hub_pos_s)
    "DB" -> res.getString(R.string.sports_hub_pos_db)
    "K" -> res.getString(R.string.sports_hub_pos_k)
    "P" -> res.getString(R.string.sports_hub_pos_p)
    "LS" -> res.getString(R.string.sports_hub_pos_ls)
    else -> abbr
}

/**
 * (§3.6) A season leader's line from ESPN's `displayValue`: split on ", ", the attempts part dropped ("248/372", "139
 * CAR"), units lower-cased: "248/372, 2,623 YDS, 19 TD, 10 INT" → "2,623 yds · 19 TD · 10 INT"; sacks "5.5 sacks",
 * tackles "67 tackles". Fractional values come only from [Leader.line], never from the integer value.
 */
internal fun nflLeaderLine(res: Resources, category: String, l: Leader): String {
    val raw = l.line?.trim().orEmpty().ifEmpty { l.value.toString() }
    // (Code review 2026-11-26, UI M1) A part whose number has no digit (ESPN's live "3 CAR, - YDS" for −8 yards) is never
    // drawn; with nothing left the line is "–".
    val none = res.getString(R.string.sports_hub_stat_none)
    fun hasDigit(t: String) = t.any { it.isDigit() }
    when (category.lowercase(Locale.ROOT)) {
        "sacks" -> return raw.substringBefore(' ').takeIf(::hasDigit)?.let { res.getString(R.string.sports_hub_unit_sacks, it) } ?: none
        "totaltackles" -> return raw.substringBefore(' ').takeIf(::hasDigit)?.let { res.getString(R.string.sports_hub_unit_tackles, it) } ?: none
    }
    val sep = res.getString(R.string.sports_hub_summary_sep)
    val parts = raw.split(", ").map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { part ->
        val num = part.substringBefore(' ')
        if (!hasDigit(num)) return@mapNotNull null
        when (part.substringAfter(' ', "").uppercase(Locale.ROOT)) {
            "YDS" -> res.getString(R.string.sports_hub_unit_yds, num)
            "TD" -> res.getString(R.string.sports_hub_unit_td, num)
            "INT" -> res.getString(R.string.sports_hub_unit_int, num)
            "REC" -> res.getString(R.string.sports_hub_unit_rec, num)
            "CAR" -> null
            "" -> if (part.contains('/')) null else part
            else -> part
        }
    }
    return parts.joinToString(sep).ifEmpty { none }
}

/**
 * §6.4: the three leader rows (Points, Goals, Assists, in that order when present), then up to two goalies per team.
 *
 * (CHL hub §3.1, review S6) Driven by data the NHL never fills, never by the league: the Leaders header says "this season"
 * when [GameDetail.leadersSeason]; a goalie with `Goalie.starting` carries the Starting mark (drawn by the cell); and,
 * given [res] and [game], a Last 5 section (the NFL's rows) when [GameDetail.recent] is non-empty.
 */
internal fun matchupRows(d: GameDetail, res: Resources? = null, game: GameSnapshot? = null): List<MatchupRow> = buildList {
    if (!hasMatchupRows(d)) return@buildList
    val order = listOf("points", "goals", "assists")
    val leaders = d.leaders.sortedBy { p -> order.indexOf(p.category.lowercase(Locale.ROOT)).let { if (it < 0) order.size else it } }
    if (leaders.isNotEmpty()) {
        add(MatchupRow.Header(if (d.leadersSeason) R.string.sports_hub_matchup_leaders_season else R.string.sports_hub_matchup_leaders))
        leaders.forEach { add(MatchupRow.Leaders(it)) }
    }
    val g = d.goalies
    if (g != null && (g.away.isNotEmpty() || g.home.isNotEmpty())) {
        add(MatchupRow.Header(R.string.sports_hub_matchup_goalies))
        val n = maxOf(g.away.size, g.home.size).coerceAtMost(2)
        for (i in 0 until n) add(MatchupRow.Goalies(g.away.getOrNull(i), g.home.getOrNull(i)))
    }
    if (res != null && game != null && d.recent.isNotEmpty()) addAll(recentRows(res, game, d))
}

/** (NFL hub §3.6; CHL hub §3.1) "Last 5 games", then one row per team that has any, oldest first. */
private fun recentRows(res: Resources, game: GameSnapshot, d: GameDetail): List<MatchupRow> {
    val sep = res.getString(R.string.sports_hub_summary_sep)
    val sides = listOf(
        app.thebox.core.sports.Side.AWAY to HubNames.of(game.id.league, game.away),
        app.thebox.core.sports.Side.HOME to HubNames.of(game.id.league, game.home),
    )
    val rows = sides.mapNotNull { (side, name) ->
        val games = d.recent.filter { it.side == side }
        if (games.isEmpty()) null else MatchupRow.Recent(name.nickname, games.joinToString(sep) { recentText(res, it) })
    }
    return if (rows.isEmpty()) emptyList() else listOf(MatchupRow.Header(R.string.sports_hub_matchup_last5)) + rows
}

/** Whether [matchupRows] has anything to show (the MATCHUP slot's enabled rule, HubSlots.forAction). */
internal fun hasMatchupRows(d: GameDetail): Boolean = d.leaders.isNotEmpty() || d.goalies?.let { it.away.isNotEmpty() || it.home.isNotEmpty() } == true ||
    // (NFL hub §3.6) The NFL's last five and injury report.
    d.recent.isNotEmpty() || d.injuries.isNotEmpty()

internal fun leaderCategory(res: Resources, category: String): String = when (category.lowercase(Locale.ROOT)) {
    "points" -> res.getString(R.string.sports_hub_matchup_points)
    "goals" -> res.getString(R.string.sports_hub_matchup_goals)
    "assists" -> res.getString(R.string.sports_hub_matchup_assists)
    else -> category.replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

/** "2.41 GAA · .915 SV%" pieces after the record. */
internal fun goalieLine(res: Resources, g: Goalie): String {
    val sep = res.getString(R.string.sports_hub_summary_sep)
    return listOfNotNull(
        g.record,
        g.gaa?.let { String.format(Locale.US, "%.2f", it) + " " + res.getString(R.string.sports_hub_matchup_gaa) },
        g.svPct?.let { svPct(it) + " " + res.getString(R.string.sports_hub_matchup_sv_pct) },
    ).joinToString(sep)
}

@Composable
private fun LeaderCell(l: Leader?, color: Color, modifier: Modifier, onLight: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (l == null) return@Row
        HubHeadshot(l.headshot, l.name, 72.dp, onLight)
        Line(l.name, GuideUi.sans(24, FontWeight.SemiBold, color), Modifier.weight(1f))
        Line(l.value.toString(), GuideUi.sans(26, FontWeight.Bold, color))
    }
}

@Composable
private fun NflLeaderCell(res: Resources, category: String, l: Leader?, color: Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (l == null) return@Row
        HubHeadshot(l.headshot, l.name, 72.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Line(NflHubUi.shortName(l.name), GuideUi.sans(24, FontWeight.SemiBold, color))
            Line(nflLeaderLine(res, category, l), GuideUi.sans(22, FontWeight.Medium, GuideUi.secondary(color, focusedOf(color), 0.70f)))
        }
    }
}

@Composable
private fun GoalieCell(res: Resources, g: Goalie?, color: Color, modifier: Modifier, onLight: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (g == null) return@Row
        HubHeadshot(g.headshot, g.name, 64.dp, onLight)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // (CHL hub §3.1, B1) "Starting" only when tonight's lineup marks him; never inferred.
            if (g.starting) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Line(g.name, GuideUi.sans(24, FontWeight.SemiBold, color), Modifier.weight(1f, fill = false))
                    StartingMark(res, color)
                }
            } else {
                Line(g.name, GuideUi.sans(24, FontWeight.SemiBold, color))
            }
            // (CHL hub §3.1) A goalie the stats read did not list (a failed read, no table for his team) has his name only.
            val line = goalieLine(res, g)
            if (line.isNotEmpty()) Line(line, GuideUi.sans(22, FontWeight.Medium, GuideUi.secondary(color, focusedOf(color), 0.70f)))
        }
    }
}

internal const val MATCHUP_STARTING_TAG = "hub_matchup_starting"

/** (CHL hub §3.1) The Starting mark: 22/600 in a 1 px outlined pill, in the row's ink (dark on a focused row). */
@Composable
private fun StartingMark(res: Resources, color: Color) {
    val shape = RoundedCornerShape(DesignTokens.Radii.Pill)
    Box(
        Modifier.testTag(MATCHUP_STARTING_TAG).border(1.dp, color.copy(alpha = 0.55f), shape).padding(horizontal = 10.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Line(res.getString(R.string.sports_hub_matchup_starting), GuideUi.sans(22, FontWeight.SemiBold, color))
    }
}
