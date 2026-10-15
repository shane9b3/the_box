package app.thebox.ui.full

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.thebox.core.full.StatLine
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line

const val STATS_OVERLAY_TAG = "player_stats"

/**
 * Stream info (docs/design/m4-player-ux.md §3.4.4, §3.9): (64, 56) 600 wide, auto height, black .62, radius 16, mono 16
 * lines of 24 px, label .55 / value FG. Lines arrive formatted (`StatsFormatter`, `Locale.ROOT`); only the labels are
 * localised here. It never takes keys and redraws at the M2 stats cadence (≤ 2 Hz) only while on.
 */
@Composable
fun StatsOverlay(lines: List<StatLine>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .testTag(STATS_OVERLAY_TAG)
            .width(PlayerUi.STATS_W.dp)
            .background(PlayerUi.StatsFill, RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        for (l in lines) {
            Row(Modifier.height(PlayerUi.STATS_LINE_H.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Line(stringResource(PlayerText.statLabelRes(l.label)), GuideUi.mono(16, color = GuideUi.Fg55), Modifier.width(LABEL_W.dp))
                Line(l.value, GuideUi.mono(16), Modifier.weight(1f))
            }
        }
    }
}

private const val LABEL_W = 170f
