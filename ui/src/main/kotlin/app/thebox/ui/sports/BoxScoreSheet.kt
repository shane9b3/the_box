package app.thebox.ui.sports

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.BoxCategory
import app.thebox.core.sports.hub.BoxLine
import app.thebox.core.sports.hub.BoxScore
import app.thebox.core.sports.hub.GoalieLine
import app.thebox.core.sports.hub.SkaterLine
import app.thebox.core.sports.hub.TeamBox
import app.thebox.core.sports.group
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.theme.DesignTokens
import java.util.Locale

const val BOX_SCORE_SHEET_TAG = "hub_box_score_sheet"
fun boxScoreTabTag(side: Side) = "hub_box_tab:$side"
const val BOX_SCORE_RETRY_TAG = "hub_box_retry"

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §6.4, U-N7; WP-5) The Box score sheet (final NHL): Away / Home tab chips,
 * skaters (forwards then defence) and goalies. [box] is null while loading; a `Fetch.Failed` / `NotFound` shows the §4.5
 * text with a Retry row ([onRetry]). Focus on the Away tab; Back calls [onClose].
 *
 * Left / Right on the tabs switch the team; Down enters the rows (44 tall, focusable for scrolling); Up from the first row
 * goes back to the shown team's tab.
 *
 * (NFL hub §3.5) An NFL game: the line score by quarter from the board above the tabs (not focusable), then per team the
 * Passing, Rushing and Receiving tables ([TeamBox.tables], at most `HUB_NFL_BOX_LINES` athletes each, ESPN's order),
 * and ESPN's failure words.
 */
@Composable
fun BoxScoreSheet(
    game: GameSnapshot,
    box: Fetch<BoxScore>?,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val res = LocalResources.current
    var side by rememberSaveable { mutableStateOf(Side.AWAY) }
    val tabs = remember { Side.entries.associateWith { FocusRequester() } }
    val whole: BoxScore? = (box as? Fetch.Ok)?.value
    val team: TeamBox? = whole?.let { if (side == Side.AWAY) it.away else it.home }
    val failed = box is Fetch.Failed || box is Fetch.NotFound
    val nfl = game.id.league.group == app.thebox.core.sports.LeagueGroup.NFL
    // (CHL hub §3.3, S6) Data rules, never a league check: no TOI column when no skater of either team has one.
    val toi = whole == null || skaterToiShown(whole)
    val rows = remember(team, failed, nfl) { boxRows(team, failed, nfl) }
    val skaterCols = if (toi) SKATER_COLS else SKATER_COLS.dropLast(1)
    val errorText = remember(game, nfl) {
        HubLeagues.of(game.id.league.group).boxScoreError(res, game)
            ?: res.getString(if (nfl) R.string.sports_hub_error_box_score_espn else R.string.sports_hub_error_box_score)
    }
    val focusable = rows.indices.filter { rows[it].focusable }
    val requesters = remember(side, rows.size) { List(rows.size) { FocusRequester() } }
    val listState = rememberLazyListState()
    val hubFocusable = LocalHubFocusable.current
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (hubFocusable) runCatching { tabs.getValue(Side.AWAY).requestFocus() }
    }
    LaunchedEffect(side) { listState.scrollToItem(0) }
    val firstRow = focusable.firstOrNull()?.let { requesters[it] }
    HubSheetFrame(
        title = res.getString(R.string.sports_hub_sheet_box_score, game.away.abbrev, game.home.abbrev),
        rowCount = rows.size,
        initialRow = 0,
        focusKey = Unit,
        onClose = onClose,
        modifier = modifier.testTag(BOX_SCORE_SHEET_TAG),
        listState = listState,
        requesters = requesters,
        takeInitialFocus = false,
        // (Review H1) Retry (or a failure replacing the rows) removes the focused row: focus goes to Retry when the sheet
        // shows one, else to the shown team's tab, so Back keeps working.
        rowsKey = rows,
        refocus = {
            val retry = rows.indexOf(BoxRow.Retry)
            if (retry >= 0) requesters[retry] to retry else tabs.getValue(side) to null
        },
        header = {
            if (nfl) LineScore(res, game)
            // (CHL hub §3.3) The hockey line score when the box carries goals and shots by period (the NHL and NFL: none).
            whole?.takeIf { hockeyLineScoreShown(it) }?.let { HockeyLineScore(res, game, it) }
            Row(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (s in Side.entries) {
                    BoxTab(
                        label = (if (s == Side.AWAY) game.away else game.home).let { it.shortName ?: it.name },
                        selected = s == side,
                        requester = tabs.getValue(s),
                        tag = boxScoreTabTag(s),
                        onLeft = { if (s == Side.HOME) { side = Side.AWAY; runCatching { tabs.getValue(Side.AWAY).requestFocus() } } },
                        onRight = { if (s == Side.AWAY) { side = Side.HOME; runCatching { tabs.getValue(Side.HOME).requestFocus() } } },
                        onDown = { firstRow?.let { r -> runCatching { r.requestFocus() } } },
                        onFocused = { side = s },
                    )
                }
            }
        },
    ) { reqs ->
        rows.forEachIndexed { i, row ->
            item(key = "${side}:$i") {
                val first = focusable.firstOrNull() == i
                val last = focusable.lastOrNull() == i
                when (row) {
                    BoxRow.Loading -> Line(res.getString(R.string.sports_hub_stat_none), GuideUi.sans(26, color = GuideUi.Fg70), Modifier.padding(horizontal = 16.dp))
                    BoxRow.Error -> Line(errorText, GuideUi.sans(24, color = GuideUi.Fg70), Modifier.padding(horizontal = 16.dp), maxLines = 3)
                    BoxRow.Retry -> HubSheetRow(reqs[i], first, last, key = "retry", height = 56, upTarget = tabs.getValue(side), onClick = onRetry) { c ->
                        Line(res.getString(R.string.sports_hub_slot_retry), GuideUi.sans(26, FontWeight.SemiBold, c), Modifier.testTag(BOX_SCORE_RETRY_TAG))
                    }
                    is BoxRow.Section -> Line(res.getString(row.title), GuideUi.sans(22, FontWeight.SemiBold, GuideUi.Fg70), Modifier.padding(start = 16.dp, top = 8.dp))
                    BoxRow.SkaterHeader -> TableRow(GuideUi.Fg70, header = true) { c ->
                        Cell(res.getString(R.string.sports_hub_box_player), c, null)
                        for ((s, w) in skaterCols) Cell(res.getString(s), c, w)
                    }
                    BoxRow.GoalieHeader -> TableRow(GuideUi.Fg70, header = true) { c ->
                        Cell(res.getString(R.string.sports_hub_box_goalies), c, null)
                        for ((s, w) in GOALIE_COLS) Cell(res.getString(s), c, w)
                    }
                    is BoxRow.Skater -> HubSheetRow(reqs[i], first, last, key = "sk:$i", height = ROW_H, upTarget = tabs.getValue(side), onClick = {}) { c ->
                        Line(playerCell(res, row.line.name, row.line.pos), GuideUi.sans(26, FontWeight.Medium, c), Modifier.weight(1f))
                        skaterValues(row.line).zip(skaterCols).forEach { (v, col) -> Cell(v, c, col.second) }
                    }
                    is BoxRow.Goalie -> HubSheetRow(reqs[i], first, last, key = "gk:$i", height = ROW_H, upTarget = tabs.getValue(side), onClick = {}) { c ->
                        Line(row.line.name, GuideUi.sans(26, FontWeight.Medium, c), Modifier.weight(1f))
                        goalieValues(row.line).zip(GOALIE_COLS).forEach { (v, col) -> Cell(v, c, col.second) }
                    }
                    is BoxRow.Table -> Line(res.getString(boxCategoryTitle(row.category)), GuideUi.sans(22, FontWeight.SemiBold, GuideUi.Fg70), Modifier.padding(start = 16.dp, top = 8.dp))
                    is BoxRow.TableHeader -> TableRow(GuideUi.Fg70, header = true) { c ->
                        Cell(res.getString(R.string.sports_hub_box_player), c, null)
                        row.columns.forEach { col -> Cell(boxColumnLabel(res, col), c, nflColWidth(col)) }
                    }
                    is BoxRow.Athlete -> HubSheetRow(reqs[i], first, last, key = "nfl:$i", height = ROW_H, upTarget = tabs.getValue(side), onClick = {}) { c ->
                        Line(row.line.name, GuideUi.sans(26, FontWeight.Medium, c), Modifier.weight(1f))
                        row.columns.forEachIndexed { k, col -> Cell(row.line.values.getOrNull(k) ?: NONE, c, nflColWidth(col)) }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ model

internal sealed interface BoxRow {
    val focusable: Boolean get() = false
    data object Loading : BoxRow
    data object Error : BoxRow
    data object Retry : BoxRow { override val focusable get() = true }
    data class Section(val title: Int) : BoxRow
    data object SkaterHeader : BoxRow
    data class Skater(val line: SkaterLine) : BoxRow { override val focusable get() = true }
    data object GoalieHeader : BoxRow
    data class Goalie(val line: GoalieLine) : BoxRow { override val focusable get() = true }
    /** (NFL hub §3.5) A table's title ("Passing"), its column header, and one athlete's line. */
    data class Table(val category: BoxCategory) : BoxRow
    data class TableHeader(val columns: List<String>) : BoxRow
    data class Athlete(val line: BoxLine, val columns: List<String>) : BoxRow { override val focusable get() = true }
}

/**
 * Skaters (forwards, then defence), then goalies; loading / failure rows. (NFL hub §3.5) [nfl]: the Passing, Rushing and
 * Receiving tables in that order, each with at most `HUB_NFL_BOX_LINES` athletes.
 */
internal fun boxRows(team: TeamBox?, failed: Boolean, nfl: Boolean = false): List<BoxRow> = buildList {
    if (failed) {
        add(BoxRow.Error)
        add(BoxRow.Retry)
        return@buildList
    }
    if (team == null) {
        add(BoxRow.Loading)
        return@buildList
    }
    if (nfl) {
        for (cat in BoxCategory.entries) {
            val t = team.tables.firstOrNull { it.category == cat } ?: continue
            if (t.lines.isEmpty()) continue
            add(BoxRow.Table(cat))
            add(BoxRow.TableHeader(t.columns))
            t.lines.take(SportsConstants.HUB_NFL_BOX_LINES).forEach { add(BoxRow.Athlete(it, t.columns)) }
        }
        return@buildList
    }
    val (defence, forwards) = team.skaters.partition { isDefence(it.pos) }
    add(BoxRow.SkaterHeader)
    if (forwards.isNotEmpty()) {
        add(BoxRow.Section(R.string.sports_hub_box_forwards))
        forwards.forEach { add(BoxRow.Skater(it)) }
    }
    if (defence.isNotEmpty()) {
        add(BoxRow.Section(R.string.sports_hub_box_defense))
        defence.forEach { add(BoxRow.Skater(it)) }
    }
    if (team.goalies.isNotEmpty()) {
        add(BoxRow.GoalieHeader)
        team.goalies.forEach { add(BoxRow.Goalie(it)) }
    }
}

/** (CHL hub §3.3) "D", and HockeyTech's "LD" / "RD", are the defence group; NHL.com only ever sends "D". */
internal fun isDefence(pos: String): Boolean = pos.trim().uppercase(Locale.ROOT).let { it == "D" || it == "LD" || it == "RD" }

/** (CHL hub §3.3) The skaters' TOI column is drawn unless no skater of either team has a time (HockeyTech tracks none). */
internal fun skaterToiShown(box: BoxScore): Boolean = (box.away.skaters + box.home.skaters).let { all -> all.isEmpty() || all.any { it.toi != null } }

/** (CHL hub §3.3) The hockey line score is drawn when the box carries goals and shots by period. */
internal fun hockeyLineScoreShown(box: BoxScore): Boolean = box.periods.isNotEmpty() && box.periodShots.isNotEmpty()

/**
 * (CHL hub §3.3) The hockey line score's cells: the period words ("1st", "2nd", "3rd", "OT", never "Q%d") and "T"; per
 * team a Goals row and a Shots row, the total added. The shots follow the goals' labels (the same periods in order).
 */
internal data class HockeyLine(val team: String, val label: String, val cells: List<String>)

internal fun hockeyLineScore(res: Resources, game: GameSnapshot, box: BoxScore): Pair<List<String>, List<HockeyLine>> {
    val heads = box.periods.map { SportsText.periodWord(res, it.label) } + res.getString(R.string.sports_hub_row_total)
    // The goals' total is the board's final score when it has one (a shootout winner's extra goal is in no period).
    fun cells(list: List<app.thebox.core.sports.hub.PeriodScore>, away: Boolean, total: Int?): List<String> {
        val byLabel = list.associateBy { it.label }
        val values = box.periods.map { p -> byLabel[p.label]?.let { if (away) it.away else it.home } }
        return values.map { it?.toString() ?: NONE } + (total ?: values.filterNotNull().sum()).toString()
    }
    val goals = res.getString(R.string.sports_hub_box_row_goals)
    val shots = res.getString(R.string.sports_hub_box_row_shots)
    return heads to listOf(
        HockeyLine(game.away.abbrev, goals, cells(box.periods, true, game.away.score)),
        HockeyLine("", shots, cells(box.periodShots, true, null)),
        HockeyLine(game.home.abbrev, goals, cells(box.periods, false, game.home.score)),
        HockeyLine("", shots, cells(box.periodShots, false, null)),
    )
}

/** "C. McDavid · C" (the contract carries no sweater number, so `sports_hub_box_player_cell` is not used). */
internal fun playerCell(res: Resources, name: String, pos: String): String =
    if (pos.isBlank()) name else name + res.getString(R.string.sports_hub_summary_sep) + pos

private const val NONE = "–"

internal fun boxCategoryTitle(c: BoxCategory): Int = when (c) {
    BoxCategory.PASSING -> R.string.sports_hub_box_passing
    BoxCategory.RUSHING -> R.string.sports_hub_box_rushing
    BoxCategory.RECEIVING -> R.string.sports_hub_box_receiving
}

/**
 * (NFL hub §3.5; integration §18) A column's header: `BoxTable.columns` are ESPN's stat keys ("completions/passingAttempts",
 * "passingYards", "QBRating"), the one representation from `:core`; the sheet's words (C/ATT, YDS, TD, INT, RTG, CAR, AVG,
 * LONG, REC) are these resources. A key the mapper never emits is shown as given.
 */
internal fun boxColumnLabel(res: Resources, key: String): String = when (key.trim().lowercase(Locale.ROOT)) {
    "completions/passingattempts" -> res.getString(R.string.sports_hub_box_c_att)
    "passingyards", "rushingyards", "receivingyards" -> res.getString(R.string.sports_hub_box_yds)
    "passingtouchdowns", "rushingtouchdowns", "receivingtouchdowns" -> res.getString(R.string.sports_hub_box_td)
    "interceptions" -> res.getString(R.string.sports_hub_box_int)
    "qbrating" -> res.getString(R.string.sports_hub_box_rtg)
    "rushingattempts" -> res.getString(R.string.sports_hub_box_car)
    "yardsperrushattempt", "yardsperreception" -> res.getString(R.string.sports_hub_box_avg)
    "longrushing", "longreception" -> res.getString(R.string.sports_hub_box_long)
    "receptions" -> res.getString(R.string.sports_hub_box_rec)
    else -> key
}

/** The C/ATT column holds "21/32"; every other NFL column a number of up to 5 characters at 22 px (× Largest). */
private fun nflColWidth(key: String): Int = if (key.contains('/')) 104 else 88

internal fun skaterValues(s: SkaterLine): List<String> = listOf(
    s.g.toString(), s.a.toString(), s.p.toString(),
    s.plusMinus?.let { if (it > 0) "+$it" else it.toString() } ?: NONE,
    s.sog?.toString() ?: NONE,
    s.toi ?: NONE,
)

internal fun goalieValues(g: GoalieLine): List<String> = listOf(
    g.sa?.toString() ?: NONE,
    g.saves?.toString() ?: NONE,
    g.svPct?.let { svPct(it) } ?: NONE,
    g.toi ?: NONE,
)

/** ".915" (hockey's form); "1.000" for a perfect game. */
internal fun svPct(v: Double): String {
    val s = String.format(Locale.US, "%.3f", v.coerceIn(0.0, 1.0))
    return if (s.startsWith("0")) s.substring(1) else s
}

private const val ROW_H = 44
private val SKATER_COLS = listOf(
    R.string.sports_hub_box_g to 56, R.string.sports_hub_box_a to 56, R.string.sports_hub_box_p to 56,
    R.string.sports_hub_box_plus_minus to 72, R.string.sports_hub_box_sog to 72, R.string.sports_hub_box_toi to 96,
)
private val GOALIE_COLS = listOf(
    R.string.sports_hub_box_sa to 72, R.string.sports_hub_box_sv to 72, R.string.sports_hub_box_sv_pct to 96,
    R.string.sports_hub_box_toi to 96,
)

// ------------------------------------------------------------------ parts

internal const val BOX_LINE_SCORE_TAG = "hub_box_line_score"

/**
 * (NFL hub §3.5) The line score (not focusable), 22/500 tabular: the teams' abbreviations by Q1 Q2 Q3 Q4 (OT) T, from the
 * board's quarters and score. Nothing without quarters.
 */
@Composable
private fun LineScore(res: Resources, game: GameSnapshot) {
    val periods = game.extras?.periods.orEmpty()
    if (periods.isEmpty()) return
    val heads = periods.map { p ->
        if (p.label.kind == app.thebox.core.sports.PeriodKind.REG) res.getString(R.string.sports_hub_row_quarter_n, p.label.number) else SportsText.periodWord(res, p.label)
    } + res.getString(R.string.sports_hub_row_total)
    val away = periods.map { it.away.toString() } + (game.away.score ?: periods.sumOf { it.away }).toString()
    val home = periods.map { it.home.toString() } + (game.home.score ?: periods.sumOf { it.home }).toString()
    androidx.compose.foundation.layout.Column(Modifier.padding(bottom = 12.dp).testTag(BOX_LINE_SCORE_TAG)) {
        for ((name, cells, ink) in listOf(Triple("", heads, GuideUi.Fg70), Triple(game.away.abbrev, away, GuideUi.Fg), Triple(game.home.abbrev, home, GuideUi.Fg))) {
            Row(Modifier.height(36.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.width(96.dp), style = GuideUi.sans(22, FontWeight.SemiBold, ink), maxLines = 1)
                cells.forEach { Text(it, Modifier.width(64.dp), style = GuideUi.mono(22, FontWeight.Medium, ink), maxLines = 1, textAlign = TextAlign.End) }
            }
        }
    }
}

internal const val BOX_HOCKEY_LINE_SCORE_TAG = "hub_box_hockey_line_score"

/**
 * (CHL hub §3.3) The hockey line score (not focusable): the period words and T across, then per team a Goals and a Shots
 * row (the abbreviation on the Goals row), 22 px tabular, rows 34 tall (36 from Larger up, as the NFL's line score).
 */
@Composable
private fun HockeyLineScore(res: Resources, game: GameSnapshot, box: BoxScore) {
    val (heads, lines) = remember(box, game) { hockeyLineScore(res, game, box) }
    val h = if (app.thebox.core.settings.UiTextScale.factor > 1.05f) 36 else 34
    androidx.compose.foundation.layout.Column(Modifier.padding(bottom = 12.dp).testTag(BOX_HOCKEY_LINE_SCORE_TAG)) {
        val rows = listOf(Triple("", "", heads) to GuideUi.Fg70) + lines.map { Triple(it.team, it.label, it.cells) to GuideUi.Fg }
        rows.forEachIndexed { i, (cells, ink) ->
            val (team, label, values) = cells
            // A hairline between the two teams.
            val m = if (i == 3) Modifier.drawBehindTop() else Modifier
            Row(m.height(h.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(team, Modifier.width(96.dp), style = GuideUi.sans(22, FontWeight.SemiBold, ink), maxLines = 1)
                Text(label, Modifier.width(112.dp), style = GuideUi.sans(22, FontWeight.Medium, if (i == 0) ink else GuideUi.Fg70), maxLines = 1)
                values.forEachIndexed { k, v ->
                    val total = k == values.lastIndex
                    Text(v, Modifier.width(72.dp), style = GuideUi.mono(22, if (total && i > 0) FontWeight.SemiBold else FontWeight.Medium, ink), maxLines = 1, textAlign = TextAlign.End)
                }
            }
        }
    }
}

/** A 1 px White08 line along the top edge (a flat draw: no layer, no clip). */
private fun Modifier.drawBehindTop(): Modifier = this.then(
    Modifier.drawBehind { drawLine(GuideUi.White08, androidx.compose.ui.geometry.Offset(0f, 0.5f), androidx.compose.ui.geometry.Offset(size.width, 0.5f), 1f) },
)

/** A table cell: 22/500 tabular, right-aligned in [width] design px; null width = the flexible name column. */
@Composable
private fun RowScope.Cell(text: String, color: Color, width: Int?) {
    val style = GuideUi.mono(22, FontWeight.Medium, color)
    if (width == null) Line(text, GuideUi.sans(22, FontWeight.SemiBold, color), Modifier.weight(1f))
    else Text(text, Modifier.width(width.dp), style = style, maxLines = 1, textAlign = TextAlign.End)
}

@Composable
private fun TableRow(color: Color, header: Boolean, content: @Composable RowScope.(Color) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(if (header) 36.dp else ROW_H.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) { content(color) }
}

@Composable
private fun BoxTab(
    label: String,
    selected: Boolean,
    requester: FocusRequester,
    tag: String,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onDown: () -> Unit,
    onFocused: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DesignTokens.Radii.Pill)
    val focusable = LocalHubFocusable.current
    var m = Modifier
        .testTag(tag)
        .focusRequester(requester)
        .focusProperties { canFocus = focusable }
        .onPreviewKeyEvent { e ->
            val down = e.type == KeyEventType.KeyDown
            when (e.key) {
                Key.DirectionLeft -> { if (down) onLeft(); true }
                Key.DirectionRight -> { if (down) onRight(); true }
                Key.DirectionDown -> { if (down) onDown(); true }
                Key.DirectionUp -> true
                else -> false
            }
        }
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onFocused() }
    if (focused) m = m.border(GuideUi.FocusRing, GuideUi.Accent, shape)
    val fill = when {
        focused -> GuideUi.Fg
        selected -> GuideUi.White16
        else -> Color.Transparent
    }
    if (!focused && !selected) m = m.border(1.dp, GuideUi.White16, shape)
    Box(m.background(fill, shape).padding(horizontal = 24.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Line(label, GuideUi.sans(24, if (selected) FontWeight.SemiBold else FontWeight.Medium, if (focused) GuideUi.Canvas else GuideUi.Fg))
    }
}
