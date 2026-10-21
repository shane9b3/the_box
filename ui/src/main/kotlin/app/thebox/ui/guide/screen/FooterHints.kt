package app.thebox.ui.guide.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.FooterHint

const val FOOTER_TAG = "footer_hints"

/** Test tag of the footer hint at list index [i]. */
fun footerHintTag(i: Int): String = "${FOOTER_TAG}_$i"

/** The footer's width: x 64 → 1856 (decision 2026-10-19, ruling 8). */
const val FOOTER_MAX_W = 1792f
/** Gap between hints (prototype footer). */
const val FOOTER_GAP = 36f

/**
 * Footer key hints at (64, bottom 40) (docs/design/m3-guide.md §3.18.3; prototype footer): 17 px at .55, key caps mono
 * 14 with a .22 border, gap 36. The list is built by the state machine in priority order from the keys the device has
 * reported.
 *
 * (Decision 2026-10-19, ruling 8.) A measuring layout: hints are placed in list order, [FOOTER_GAP] apart, and the
 * first hint whose right edge would pass the available width (the smaller of the incoming constraint and [maxWidth])
 * ends the row; it and every later hint are left out. Never wraps; dropping from the end drops the least important.
 */
@Composable
fun FooterHints(footer: List<FooterHint>, modifier: Modifier = Modifier, maxWidth: Dp = FOOTER_MAX_W.dp) {
    if (footer.isEmpty()) return
    Layout(
        modifier = modifier.testTag(FOOTER_TAG),
        content = {
            footer.forEachIndexed { i, hint ->
                Row(Modifier.testTag(footerHintTag(i)), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    KeyCap(hint.keys)
                    Line(hint.label, GuideUi.sans(22, color = GuideUi.Fg55))
                }
            }
        },
    ) { measurables, constraints ->
        val avail = if (constraints.hasBoundedWidth) minOf(constraints.maxWidth, maxWidth.roundToPx()) else maxWidth.roundToPx()
        val gap = FOOTER_GAP.dp.roundToPx()
        val loose = Constraints(maxHeight = constraints.maxHeight)
        val placed = ArrayList<Placeable>(measurables.size)
        var x = 0
        var h = 0
        for (m in measurables) {
            val p = m.measure(loose)
            val left = if (placed.isEmpty()) 0 else x + gap
            if (left + p.width > avail) break
            placed += p
            x = left + p.width
            if (p.height > h) h = p.height
        }
        layout(x.coerceIn(constraints.minWidth, maxOf(constraints.minWidth, avail)), h.coerceAtLeast(constraints.minHeight)) {
            var px = 0
            for (p in placed) {
                p.placeRelative(px, (h - p.height) / 2)
                px += p.width + gap
            }
        }
    }
}
