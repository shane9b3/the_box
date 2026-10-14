package app.thebox.ui.guide.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.GuideUiState
import app.thebox.ui.guide.screen.Dot
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.theme.DesignTokens

const val SYNC_CHIP_TAG = "sync_chip"

/**
 * Sync chip after the tabs (docs/design/m3-guide.md §3.15, B7, N11): "Updating guide…" or the progress line while busy;
 * "Guide update failed. Details arrive with Settings." (amber dot) for unacknowledged failures. Not focusable: there is
 * no details screen in M3.
 */
@Composable
fun SyncChipView(state: GuideUiState, modifier: Modifier = Modifier) {
    val chip = state.sync ?: return
    Row(
        modifier
            .testTag(SYNC_CHIP_TAG)
            .widthIn(max = 420.dp)
            .background(GuideUi.White08, RoundedCornerShape(DesignTokens.Radii.Pill))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(if (chip.failed) GuideUi.Warn else GuideUi.Accent, 8.dp)
        Line(chip.text, GuideUi.sans(15, color = GuideUi.Fg70))
    }
}
