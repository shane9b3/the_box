package app.thebox.ui.guide.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.Overlay
import app.thebox.ui.guide.screen.Chip
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.core.guide.GuideGeometry as G
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.theme.DesignTokens

const val DAY_STRIP_TAG = "day_strip"

/**
 * The day strip over the ruler, (361, 501) – (1839, 558) (docs/design/m3-guide.md §3.15, §3.5.1): every browsable day
 * plus "Tonight 20:00"; days without guide coverage are dimmed (the machine skips them). Opaque so the ruler does not
 * show through. Anchored to [G.TIMELINE_L] / [G.HEADER_H] (decision 2026-11-09; it used the prototype's 281 before).
 */
@Composable
fun DayStrip(state: GuideUiState, modifier: Modifier = Modifier) {
    val o = state.overlay as? Overlay.Days ?: return
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (o.focused - 2).coerceAtLeast(0))
    LaunchedEffect(o.focused) { keepVisible(list, o.focused) }
    val shape = RoundedCornerShape(DesignTokens.Radii.Row)
    Box(
        modifier
            .offset(G.TIMELINE_L.dp, (G.PANEL_T + G.PANEL_BORDER).dp)
            .width(G.TIMELINE_W.dp)
            .height(G.HEADER_H.dp)
            .testTag(DAY_STRIP_TAG)
            .background(GuideUi.OverlayFill, shape)
            .border(1.dp, GuideUi.OverlayBorder, shape),
        contentAlignment = Alignment.CenterStart,
    ) {
        LazyRow(state = list, contentPadding = PaddingValues(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            items(o.items.size) { i ->
                val item = o.items[i]
                val focused = i == o.focused
                Chip(focused, Modifier.height(G.CHIP_H.dp), padding = PaddingValues(horizontal = G.CHIP_PAD_X.dp)) { color ->
                    val c = when {
                        focused -> color
                        !item.enabled -> GuideUi.Fg.copy(alpha = 0.3f)
                        item.primeTime -> DesignTokens.Colors.AccentText
                        else -> GuideUi.Fg70
                    }
                    Line(item.label, GuideUi.sans(G.CHIP_TEXT.toInt(), if (focused) FontWeight.SemiBold else FontWeight.Medium, c))
                }
            }
        }
    }
}
