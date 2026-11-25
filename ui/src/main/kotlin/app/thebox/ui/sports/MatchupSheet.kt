package app.thebox.ui.sports

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import app.thebox.core.sports.hub.Goalie
import app.thebox.core.sports.hub.Leader
import app.thebox.core.sports.hub.LeaderPair
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
 */
@Composable
fun MatchupSheet(
    game: GameSnapshot,
    detail: GameDetail,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val res = LocalResources.current
    val rows = remember(detail) { matchupRows(detail) }
    val focusable = rows.indices.filter { rows[it] !is MatchupRow.Header }
    HubSheetFrame(
        title = res.getString(R.string.sports_hub_sheet_matchup, game.away.abbrev, game.home.abbrev),
        rowCount = rows.size,
        initialRow = focusable.firstOrNull() ?: 0,
        focusKey = game.id,
        onClose = onClose,
        modifier = modifier.testTag(MATCHUP_SHEET_TAG),
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
                        LeaderCell(row.pair.away, c, Modifier.weight(1f))
                        LeaderCell(row.pair.home, c, Modifier.weight(1f))
                    }
                    is MatchupRow.Goalies -> HubSheetRow(reqs[i], first, last, key = "gk:$i", height = 96, onClick = {}) { c ->
                        GoalieCell(res, row.away, c, Modifier.weight(1f))
                        GoalieCell(res, row.home, c, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

internal sealed interface MatchupRow {
    data class Header(val title: Int) : MatchupRow
    data class Leaders(val pair: LeaderPair) : MatchupRow
    data class Goalies(val away: Goalie?, val home: Goalie?) : MatchupRow
}

/** §6.4: the three leader rows (Points, Goals, Assists, in that order when present), then up to two goalies per team. */
internal fun matchupRows(d: GameDetail): List<MatchupRow> = buildList {
    val order = listOf("points", "goals", "assists")
    val leaders = d.leaders.sortedBy { p -> order.indexOf(p.category.lowercase(Locale.ROOT)).let { if (it < 0) order.size else it } }
    if (leaders.isNotEmpty()) {
        add(MatchupRow.Header(R.string.sports_hub_matchup_leaders))
        leaders.forEach { add(MatchupRow.Leaders(it)) }
    }
    val g = d.goalies
    if (g != null && (g.away.isNotEmpty() || g.home.isNotEmpty())) {
        add(MatchupRow.Header(R.string.sports_hub_matchup_goalies))
        val n = maxOf(g.away.size, g.home.size).coerceAtMost(2)
        for (i in 0 until n) add(MatchupRow.Goalies(g.away.getOrNull(i), g.home.getOrNull(i)))
    }
}

internal fun leaderCategory(res: Resources, category: String): String = when (category.lowercase(Locale.ROOT)) {
    "points" -> res.getString(R.string.sports_hub_matchup_points)
    "goals" -> res.getString(R.string.sports_hub_matchup_goals)
    "assists" -> res.getString(R.string.sports_hub_matchup_assists)
    else -> category.replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

/** "2.41 GAA · .915 SV%" pieces after the record. */
internal fun goalieLine(res: Resources, g: Goalie): String {
    val sep = hubSep(res)
    return listOfNotNull(
        g.record,
        g.gaa?.let { String.format(Locale.US, "%.2f", it) + " " + res.getString(R.string.sports_hub_matchup_gaa) },
        g.svPct?.let { svPct(it) + " " + res.getString(R.string.sports_hub_matchup_sv_pct) },
    ).joinToString(sep)
}

@Composable
private fun LeaderCell(l: Leader?, color: Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (l == null) return@Row
        HubHeadshot(l.headshot, l.name, 72.dp)
        Line(l.name, GuideUi.sans(24, FontWeight.SemiBold, color), Modifier.weight(1f))
        Line(l.value.toString(), GuideUi.sans(26, FontWeight.Bold, color))
    }
}

@Composable
private fun GoalieCell(res: Resources, g: Goalie?, color: Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (g == null) return@Row
        HubHeadshot(g.headshot, g.name, 64.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Line(g.name, GuideUi.sans(24, FontWeight.SemiBold, color))
            Line(goalieLine(res, g), GuideUi.sans(22, FontWeight.Medium, GuideUi.secondary(color, focusedOf(color), 0.70f)))
        }
    }
}
