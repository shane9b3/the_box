package app.thebox.ui.guide.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.HeaderChip
import app.thebox.core.guide.Zone
import app.thebox.ui.R

const val GROUP_CHIP_TAG = "group_chip"
const val DAY_CHIP_TAG = "day_chip"

private val ChipFill = GuideUi.White08
/**
 * The Now and Day chips end at x 1827 and overlap the ruler's last tick label (WP-A hand-off), so they are opaque:
 * the 8 % white fill composited over the canvas colour `#0A0D13` (§2.13 item 20).
 */
private val ChipFillOpaque = androidx.compose.ui.graphics.Color(0xFF1E2026)
private val ChipBorder = GuideUi.White12

/**
 * The grid header row's chips (docs/design/m3-guide.md §3.15, §3.4.3): the Group chip in the label column at
 * (93, 507), max 184 × 36, with ▾; the Now chip (only while now is outside the window; arrow = `nowDirection`) and the
 * Day chip right-aligned ending at x 1827. Positions are absolute design px on the 1920 × 1080 canvas.
 */
@Composable
fun GridHeaderChips(state: GuideUiState, modifier: Modifier = Modifier) {
    Box(modifier) {
        val chips = state.headerChips
        val focusedChip = if (state.zone == Zone.HEADER) chips.getOrNull(state.headerFocused) else null
        Chip(
            focused = focusedChip == HeaderChip.GROUP,
            modifier = Modifier.offset(93.dp, 507.dp).widthIn(max = 184.dp).height(36.dp).testTag(GROUP_CHIP_TAG),
            fill = ChipFill,
            border = ChipBorder,
            padding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        ) { color ->
            Line(state.sourceLabel, GuideUi.sans(16, FontWeight.SemiBold, color), Modifier.weight(1f, fill = false))
            Line("▾", GuideUi.sans(14, color = color))
        }
        Box(Modifier.offset(0.dp, 507.dp).width(1827.dp).height(36.dp), contentAlignment = Alignment.CenterEnd) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (HeaderChip.NOW in chips && state.nowDirection != 0) {
                    Chip(focusedChip == HeaderChip.NOW, Modifier.height(36.dp), ChipFillOpaque, ChipBorder, padding = PaddingValues(horizontal = 14.dp)) { color ->
                        val label = if (state.nowDirection < 0) R.string.guide_chip_now_left else R.string.guide_chip_now_right
                        Line(stringResource(label), GuideUi.sans(16, FontWeight.SemiBold, color))
                    }
                }
                if (HeaderChip.DAY in chips) {
                    Chip(focusedChip == HeaderChip.DAY, Modifier.height(36.dp).testTag(DAY_CHIP_TAG), ChipFillOpaque, ChipBorder, padding = PaddingValues(horizontal = 14.dp)) { color ->
                        Line(state.dayLabel, GuideUi.sans(16, FontWeight.SemiBold, color))
                        Line("▾", GuideUi.sans(14, color = color))
                    }
                }
            }
        }
    }
}
