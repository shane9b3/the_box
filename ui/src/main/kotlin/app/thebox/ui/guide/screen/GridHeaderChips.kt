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
import app.thebox.core.guide.GuideGeometry as G
import app.thebox.core.guide.HeaderChip
import app.thebox.ui.R

const val GROUP_CHIP_TAG = "group_chip"
const val DAY_CHIP_TAG = "day_chip"

private val ChipFill = GuideUi.White08
/**
 * The Now and Day chips end at x [G.CHIPS_RIGHT] and overlap the ruler's last tick label (WP-A hand-off), so they are opaque:
 * the 8 % white fill composited over the canvas colour `#0A0D13` (§2.13 item 20).
 */
private val ChipFillOpaque = androidx.compose.ui.graphics.Color(0xFF1E2026)
private val ChipBorder = GuideUi.White12

/**
 * The grid header row's chips (docs/design/m3-guide.md §3.15, §3.4.3): the Group chip in the label column at
 * (95, 507), max 252 × 45, with ▾; the Now chip (only while now is outside the window; arrow = `nowDirection`) and the
 * Day chip right-aligned ending at x 1825. Positions are absolute design px on the 1920 × 1080 canvas, all from
 * [G] (decision 2026-11-09: chips 36 → 45 high, type 24/22 → 29/26).
 */
@Composable
fun GridHeaderChips(
    chips: List<HeaderChip>,
    focusedChip: HeaderChip?,
    sourceLabel: String,
    nowDirection: Int,
    dayLabel: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Chip(
            focused = focusedChip == HeaderChip.GROUP,
            modifier = Modifier.offset(G.GROUP_CHIP_L.dp, G.CHIP_T.dp).widthIn(max = G.GROUP_CHIP_MAX_W.dp).height(G.CHIP_H.dp).testTag(GROUP_CHIP_TAG),
            fill = ChipFill,
            border = ChipBorder,
            padding = PaddingValues(horizontal = G.CHIP_PAD_X.dp, vertical = 0.dp),
        ) { color ->
            Line(sourceLabel, GuideUi.sans(G.CHIP_GROUP_TEXT.toInt(), FontWeight.SemiBold, color), Modifier.weight(1f, fill = false))
            Line("▾", GuideUi.sans(G.CHIP_CARET_TEXT.toInt(), color = color))
        }
        Box(Modifier.offset(0.dp, G.CHIP_T.dp).width(G.CHIPS_RIGHT.dp).height(G.CHIP_H.dp), contentAlignment = Alignment.CenterEnd) {
            Row(horizontalArrangement = Arrangement.spacedBy(G.CHIP_GAP.dp)) {
                if (HeaderChip.NOW in chips && nowDirection != 0) {
                    Chip(focusedChip == HeaderChip.NOW, Modifier.height(G.CHIP_H.dp), ChipFillOpaque, ChipBorder, padding = PaddingValues(horizontal = G.CHIP_PAD_X.dp)) { color ->
                        val label = if (nowDirection < 0) R.string.guide_chip_now_left else R.string.guide_chip_now_right
                        Line(stringResource(label), GuideUi.sans(G.CHIP_TEXT.toInt(), FontWeight.SemiBold, color))
                    }
                }
                if (HeaderChip.DAY in chips) {
                    Chip(focusedChip == HeaderChip.DAY, Modifier.height(G.CHIP_H.dp).testTag(DAY_CHIP_TAG), ChipFillOpaque, ChipBorder, padding = PaddingValues(horizontal = G.CHIP_PAD_X.dp)) { color ->
                        Line(dayLabel, GuideUi.sans(G.CHIP_TEXT.toInt(), FontWeight.SemiBold, color))
                        Line("▾", GuideUi.sans(G.CHIP_CARET_TEXT.toInt(), color = color))
                    }
                }
            }
        }
    }
}
