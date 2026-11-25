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
import androidx.compose.ui.focus.FocusRequester
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
import app.thebox.core.sports.hub.BoxScore
import app.thebox.core.sports.hub.GoalieLine
import app.thebox.core.sports.hub.SkaterLine
import app.thebox.core.sports.hub.TeamBox
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
    val team: TeamBox? = (box as? Fetch.Ok)?.value?.let { if (side == Side.AWAY) it.away else it.home }
    val failed = box is Fetch.Failed || box is Fetch.NotFound
    val rows = remember(team, failed) { boxRows(team, failed) }
    val focusable = rows.indices.filter { rows[it].focusable }
    val requesters = remember(side, rows.size) { List(rows.size) { FocusRequester() } }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { tabs.getValue(Side.AWAY).requestFocus() }
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
        header = {
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
                    BoxRow.Error -> Line(res.getString(R.string.sports_hub_error_box_score), GuideUi.sans(24, color = GuideUi.Fg70), Modifier.padding(horizontal = 16.dp), maxLines = 3)
                    BoxRow.Retry -> HubSheetRow(reqs[i], first, last, key = "retry", height = 56, upTarget = tabs.getValue(side), onClick = onRetry) { c ->
                        Line(res.getString(R.string.sports_hub_slot_retry), GuideUi.sans(26, FontWeight.SemiBold, c), Modifier.testTag(BOX_SCORE_RETRY_TAG))
                    }
                    is BoxRow.Section -> Line(res.getString(row.title), GuideUi.sans(22, FontWeight.SemiBold, GuideUi.Fg70), Modifier.padding(start = 16.dp, top = 8.dp))
                    BoxRow.SkaterHeader -> TableRow(GuideUi.Fg70, header = true) { c ->
                        Cell(res.getString(R.string.sports_hub_box_player), c, null)
                        for ((s, w) in SKATER_COLS) Cell(res.getString(s), c, w)
                    }
                    BoxRow.GoalieHeader -> TableRow(GuideUi.Fg70, header = true) { c ->
                        Cell(res.getString(R.string.sports_hub_box_goalies), c, null)
                        for ((s, w) in GOALIE_COLS) Cell(res.getString(s), c, w)
                    }
                    is BoxRow.Skater -> HubSheetRow(reqs[i], first, last, key = "sk:$i", height = ROW_H, upTarget = tabs.getValue(side), onClick = {}) { c ->
                        Line(playerCell(res, row.line.name, row.line.pos), GuideUi.sans(26, FontWeight.Medium, c), Modifier.weight(1f))
                        skaterValues(row.line).zip(SKATER_COLS).forEach { (v, col) -> Cell(v, c, col.second) }
                    }
                    is BoxRow.Goalie -> HubSheetRow(reqs[i], first, last, key = "gk:$i", height = ROW_H, upTarget = tabs.getValue(side), onClick = {}) { c ->
                        Line(row.line.name, GuideUi.sans(26, FontWeight.Medium, c), Modifier.weight(1f))
                        goalieValues(row.line).zip(GOALIE_COLS).forEach { (v, col) -> Cell(v, c, col.second) }
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
}

/** Skaters (forwards, then defence), then goalies; loading / failure rows. */
internal fun boxRows(team: TeamBox?, failed: Boolean): List<BoxRow> = buildList {
    if (failed) {
        add(BoxRow.Error)
        add(BoxRow.Retry)
        return@buildList
    }
    if (team == null) {
        add(BoxRow.Loading)
        return@buildList
    }
    val (defence, forwards) = team.skaters.partition { it.pos.equals("D", ignoreCase = true) }
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

/** "C. McDavid · C" (the contract carries no sweater number, so `sports_hub_box_player_cell` is not used). */
internal fun playerCell(res: Resources, name: String, pos: String): String =
    if (pos.isBlank()) name else name + res.getString(R.string.sports_hub_summary_sep) + pos

private const val NONE = "–"

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
    var m = Modifier
        .testTag(tag)
        .focusRequester(requester)
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
