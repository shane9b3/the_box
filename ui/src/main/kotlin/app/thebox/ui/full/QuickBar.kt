package app.thebox.ui.full

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.full.QuickBarState
import app.thebox.core.full.QuickItem
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.theme.DesignTokens

const val QUICK_BAR_TAG = "player_quick_bar"
const val QUICK_ITEM_TAG = "player_quick_item"

/**
 * The quick bar (owner ruling 2026-10-15, docs/design/m4-player-ux.md §3.4.1, §3.9): (64, 952) 1792 × 88 design px,
 * black .55 (translucent, **no blur**), radius 24, padding 12; one 64 px pill per item, gap 12, label 19/600 + the
 * current value 17/400 .7 ("Audio · English 5.1"); the focused pill has the FG fill, dark text and the accent ring.
 * Overflow scrolls horizontally with `requestScrollToItem`, keeping the focused pill inside 64 px of either end (API
 * notes §4). The pills are not focusable: focus is the machine's [QuickBarState.focused].
 */
@Composable
fun QuickBar(state: QuickBarState, modifier: Modifier = Modifier) {
    val res = androidx.compose.ui.platform.LocalResources.current
    val shape = RoundedCornerShape(DesignTokens.Radii.Panel)
    val list = rememberLazyListState()
    val edgePx = with(LocalDensity.current) { PlayerUi.QUICK_EDGE.dp.roundToPx() }
    LaunchedEffect(state.focused, state.items.size) {
        val f = state.focused
        if (f !in state.items.indices) return@LaunchedEffect
        val info = list.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == f }
        when {
            item == null -> list.requestScrollToItem(f, -edgePx)
            item.offset < info.viewportStartOffset + edgePx -> list.requestScrollToItem(f, -edgePx)
            item.offset + item.size > info.viewportEndOffset - edgePx -> {
                val target = item.offset + item.size - (info.viewportEndOffset - edgePx)
                list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset + target)
            }
        }
    }
    LazyRow(
        state = list,
        modifier = modifier
            .testTag(QUICK_BAR_TAG)
            .size(PlayerUi.QUICK_W.dp, PlayerUi.QUICK_H.dp)
            .background(PlayerUi.QuickFill, shape),
        contentPadding = PaddingValues(PlayerUi.QUICK_PAD.dp),
        horizontalArrangement = Arrangement.spacedBy(PlayerUi.QUICK_GAP.dp),
        verticalAlignment = Alignment.CenterVertically,
        userScrollEnabled = false,
    ) {
        itemsIndexed(state.items, key = { _, item -> item.name }) { i, item ->
            QuickPill(stringResource(PlayerText.quickLabel(item)), PlayerText.quickValue(res, item, state), focused = i == state.focused, checked = item == QuickItem.STREAM_INFO && state.statsOn)
        }
    }
}

@Composable
private fun QuickPill(label: String, value: String?, focused: Boolean, checked: Boolean) {
    val shape = RoundedCornerShape(DesignTokens.Radii.Pill)
    val text = if (focused) GuideUi.Canvas else GuideUi.Fg
    var m = Modifier.testTag(QUICK_ITEM_TAG).height(PlayerUi.QUICK_PILL_H.dp)
    m = if (focused) m.border(GuideUi.FocusRing, GuideUi.Accent, shape).background(GuideUi.Fg, shape) else m.background(PlayerUi.ChipFill, shape)
    Row(
        m.padding(horizontal = PlayerUi.QUICK_PILL_PAD_H.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (checked) Line("✓", GuideUi.sans(19, FontWeight.SemiBold, text))
        Line(label, GuideUi.sans(19, FontWeight.SemiBold, text))
        if (value != null && !checked) {
            Line("·", GuideUi.sans(17, color = text.copy(alpha = 0.7f)))
            Line(value, GuideUi.sans(17, color = text.copy(alpha = 0.7f)), Modifier.padding(end = 2.dp))
        }
    }
}
