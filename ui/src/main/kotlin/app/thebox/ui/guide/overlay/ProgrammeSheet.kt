package app.thebox.ui.guide.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.Overlay
import app.thebox.ui.R
import app.thebox.ui.guide.screen.Chip
import app.thebox.ui.guide.screen.Dot
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.theme.DesignTokens

const val PROGRAMME_SHEET_TAG = "programme_sheet"

private const val SHEET_W = 560f
private const val SHEET_H = 176f

/**
 * Sheet for a future programme (product S7b, docs/design/m3-guide.md §3.4.4): title and time from the panel, then
 * [Remind me / Reminder set] and [Watch channel now]; anchored like the menu.
 */
@Composable
fun ProgrammeSheet(state: GuideUiState, anchor: Rect?, modifier: Modifier = Modifier) {
    val o = state.overlay as? Overlay.Sheet ?: return
    val at = anchoredOrigin(anchor, SHEET_W, SHEET_H)
    val shape = RoundedCornerShape(DesignTokens.Radii.Panel)
    Column(
        modifier
            .offset(at.x.dp, at.y.dp)
            .width(SHEET_W.dp)
            .testTag(PROGRAMME_SHEET_TAG)
            .background(GuideUi.OverlayFill, shape)
            .border(1.dp, GuideUi.OverlayBorder, shape)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.panel?.let { p ->
            Line(p.title, GuideUi.sans(30, FontWeight.SemiBold))
            Line(p.timeLine, GuideUi.mono(22, color = GuideUi.Fg70))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Chip(o.focused == 0, fill = GuideUi.White10, border = GuideUi.White16, shape = RoundedCornerShape(DesignTokens.Radii.Button), padding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)) { color ->
                Dot(if (o.reminderSet) GuideUi.Live else if (o.focused == 0) color.copy(alpha = 0.5f) else GuideUi.Fg50, 10.dp)
                Line(stringResource(if (o.reminderSet) R.string.guide_btn_reminder_set else R.string.guide_btn_remind), GuideUi.sans(24, FontWeight.Medium, color))
            }
            Chip(o.focused == 1, fill = GuideUi.White10, border = GuideUi.White16, shape = RoundedCornerShape(DesignTokens.Radii.Button), padding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)) { color ->
                Line(stringResource(R.string.guide_btn_watch_channel_now), GuideUi.sans(24, FontWeight.Medium, color))
            }
        }
    }
}
