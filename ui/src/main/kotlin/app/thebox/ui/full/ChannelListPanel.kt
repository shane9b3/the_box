package app.thebox.ui.full

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.full.ChannelListState
import app.thebox.core.full.ListColumn
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.ui.R
import app.thebox.ui.guide.screen.Chip
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.theme.DesignTokens

const val CHANNEL_LIST_TAG = "player_channel_list"
const val CHANNEL_GROUPS_TAG = "player_channel_groups"
const val CHANNEL_MENU_TAG = "player_channel_menu"
const val CHANNEL_ROW_TAG = "player_channel_row"

/**
 * The channel list panel (docs/design/m4-player-ux.md §3.3, §3.9): (48, 48) 620 × 984 design px, fill `#0A0D13` .86,
 * border .09, radius 24; a 64 px header (group name 22/600 + count .5), the pinned Previous row (accent label), then
 * [PlayerUi.LIST_VISIBLE] rows of 72 px on an 80 px pitch starting at [ChannelListState.top]. Rows are read from the
 * host's list rows (main-only `GuideRows`) and "now" from its cache; [ChannelListState.rowsVersion] and
 * [ChannelListState.nowVersion] are the recomposition keys. The GROUPS column (260 wide) slides over the rows and
 * dims them to .4. Focus is virtual: the machine owns the cursor and `top`, so the panel draws exactly the window
 * `[top, top + 10)` (no scroll state to keep in step with the machine).
 */
/** U-S5: debug composition counter of one channel-list row. */
const val COMPOSITIONS_LIST_ROW = "ChannelListRow"

@Composable
fun ChannelListPanel(state: ChannelListState, host: PlayerHost, modifier: Modifier = Modifier, nowMs: Long = System.currentTimeMillis()) {
    val shape = RoundedCornerShape(DesignTokens.Radii.Panel)
    val groups = state.column == ListColumn.GROUPS
    Box(
        modifier
            .testTag(CHANNEL_LIST_TAG)
            .size(PlayerUi.LIST_W.dp, PlayerUi.LIST_H.dp)
            .background(PlayerUi.PanelFill, shape)
            .border(1.dp, PlayerUi.Border09, shape),
    ) {
        val rowsAlpha = if (groups) 0.4f else 1f
        Column(Modifier.padding(horizontal = 16.dp)) {
            Header(state)
            state.previous?.let { prev -> PreviousRow(prev, focused = !groups && state.focused == -1, alpha = rowsAlpha) }
            val rows = host.listRows
            // state.rowsVersion / state.nowVersion change whenever blocks or "now" titles land, so the panel recomposes
            // and re-reads the main-only rows below (no snapshot state is needed for them).
            val end = minOf(state.size, state.top + PlayerUi.LIST_VISIBLE)
            for (i in state.top.coerceAtLeast(0) until end) {
                val ch = rows.channel(i)
                // U-S5: rows keyed by channel, so a list step moves the rows' compositions with their channels and only
                // the rows whose focus changed recompose (not all ten, nor their logo requests).
                key(ch?.identityHash ?: -1L - i) {
                    ChannelRow(
                        channel = ch,
                        now = ch?.let { host.nowOf(it) },
                        focused = !groups && state.focused == i,
                        tuned = ch != null && ch.rowId == state.tunedRowId,
                        nowMs = nowMs,
                        alpha = rowsAlpha,
                    )
                }
            }
        }
        if (groups) GroupsColumn(state)
        state.menu?.let { items ->
            val row = (state.focused - state.top).coerceIn(0, PlayerUi.LIST_VISIBLE - 1)
            val y = PlayerUi.LIST_HEADER_H + (if (state.previous != null) PlayerUi.LIST_PITCH else 0f) + row * PlayerUi.LIST_PITCH
            Column(
                Modifier
                    .offset((PlayerUi.LIST_W - 300f).dp, y.dp)
                    .testTag(CHANNEL_MENU_TAG)
                    .width(280.dp)
                    .background(GuideUi.OverlayFill, RoundedCornerShape(16.dp))
                    .border(1.dp, GuideUi.OverlayBorder, RoundedCornerShape(16.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items.forEachIndexed { i, item ->
                    Chip(i == state.menuFocused, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { c ->
                        Line(stringResource(PlayerText.menuItem(item)), GuideUi.sans(19, FontWeight.Medium, c))
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(state: ChannelListState) {
    val entry = state.groups.getOrNull(state.groupCurrent) ?: state.groups.firstOrNull { it.source == state.source }
    Row(Modifier.height(PlayerUi.LIST_HEADER_H.dp).fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Line(entry?.label ?: "", GuideUi.sans(22, FontWeight.SemiBold), Modifier.weight(1f, fill = false))
        Line(pluralStringResource(R.plurals.player_list_count, state.size, state.size), GuideUi.sans(17, color = GuideUi.Fg50))
    }
}

@Composable
private fun PreviousRow(prev: GuideChannel, focused: Boolean, alpha: Float) {
    RowFrame(focused, alpha) { text ->
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Line(stringResource(R.string.player_list_previous), GuideUi.sans(15, FontWeight.SemiBold, if (focused) text else GuideUi.Accent))
            Line(prev.displayName, GuideUi.sans(20, FontWeight.SemiBold, text))
        }
    }
}

@Composable
private fun ChannelRow(channel: GuideChannel?, now: ProgrammeInfo?, focused: Boolean, tuned: Boolean, nowMs: Long, alpha: Float) {
    if (app.thebox.ui.guide.GuideDebugCounters.ENABLED) androidx.compose.runtime.SideEffect { app.thebox.ui.guide.GuideDebugCounters.bump(COMPOSITIONS_LIST_ROW) } // U-S5
    RowFrame(focused, alpha, Modifier.testTag(CHANNEL_ROW_TAG)) { text ->
        Line(channel?.number?.toString() ?: "", GuideUi.mono(18, color = text.copy(alpha = 0.5f)), Modifier.width(56.dp))
        ChannelLogo(channel, PlayerUi.LIST_LOGO.dp, 8.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Line(channel?.displayName ?: "", GuideUi.sans(20, FontWeight.SemiBold, text))
            if (now != null) {
                Line(now.title, GuideUi.sans(17, color = text.copy(alpha = 0.7f)))
                if (now.endMin > now.startMin) {
                    val f = ((nowMs / 60_000.0 - now.startMin) / (now.endMin - now.startMin)).coerceIn(0.0, 1.0).toFloat()
                    Box(
                        Modifier.fillMaxWidth().height(3.dp).drawBehind {
                            drawRect(text.copy(alpha = 0.2f))
                            drawRect(text, size = Size(size.width * f, size.height))
                        },
                    )
                }
            }
        }
        if (tuned) Box(Modifier.size(PlayerUi.TUNED_DOT.dp).background(GuideUi.Live, CircleShape))
    }
}

@Composable
private fun RowFrame(focused: Boolean, alpha: Float, modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.(Color) -> Unit) {
    val text = if (focused) GuideUi.Canvas else GuideUi.Fg.copy(alpha = alpha)
    Box(Modifier.height(PlayerUi.LIST_PITCH.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            modifier
                .height(PlayerUi.LIST_ROW_H.dp)
                .fillMaxWidth()
                .background(if (focused) GuideUi.Fg else Color.Transparent, RoundedCornerShape(DesignTokens.Radii.Row))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) { content(text) }
    }
}

@Composable
private fun GroupsColumn(state: ChannelListState) {
    val shape = RoundedCornerShape(DesignTokens.Radii.Panel)
    Column(
        Modifier
            .testTag(CHANNEL_GROUPS_TAG)
            .size(PlayerUi.GROUPS_W.dp, PlayerUi.LIST_H.dp)
            .background(PlayerUi.Panel.copy(alpha = 0.96f), shape)
            .border(1.dp, PlayerUi.Border09, shape)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Line(stringResource(R.string.player_list_groups), GuideUi.sans(22, FontWeight.SemiBold), Modifier.padding(start = 8.dp, top = 8.dp, bottom = 12.dp))
        val visible = GROUPS_VISIBLE
        val top = (state.groupFocused - visible / 2).coerceIn(0, (state.groups.size - visible).coerceAtLeast(0))
        for (i in top until minOf(state.groups.size, top + visible)) {
            val e = state.groups[i]
            val focused = i == state.groupFocused
            Chip(focused, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { c ->
                Line(e.label, GuideUi.sans(19, if (i == state.groupCurrent) FontWeight.SemiBold else FontWeight.Normal, if (i == state.groupCurrent && !focused) DesignTokens.Colors.AccentText else c), Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(1.dp))
    }
}

private const val GROUPS_VISIBLE = 16
