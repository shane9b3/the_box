package app.thebox.ui.guide.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.PanelButton
import app.thebox.core.guide.PanelState
import app.thebox.core.guide.Zone
import app.thebox.ui.R
import app.thebox.ui.theme.BoxTextStyles
import app.thebox.ui.theme.DesignTokens

const val FOCUSED_PANEL_TAG = "focused_panel"

/**
 * The focused-programme panel, bottom-aligned in the header region (docs/design/m3-guide.md §3.9.1, §3.15; prototype
 * header block). Drawn from [GuideUiState.panel]; text layouts are cached by Compose per string (`remember` keys), the
 * description is at most 3 lines and only arrives after the 150 ms debounce (B2.8). The secondary button is a flat
 * 10 % fill, no blur (B1.3).
 */
@Composable
fun FocusedPanel(state: GuideUiState, modifier: Modifier = Modifier) {
    val panel = state.panel ?: return
    Column(modifier.testTag(FOCUSED_PANEL_TAG), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MetaRow(panel)
        val titleStyle = remember { BoxTextStyles.Title.copy(color = GuideUi.Fg, lineBreak = LineBreak.Heading) }
        Line(panel.title, titleStyle, maxLines = 2)
        Line(panel.timeLine, GuideUi.mono(18, color = GuideUi.Fg.copy(alpha = 0.75f)))
        panel.description?.let { desc ->
            val bodyStyle = remember { BoxTextStyles.Body.copy(color = GuideUi.Fg.copy(alpha = 0.78f)) }
            Line(desc, bodyStyle, Modifier.widthIn(max = 860.dp), maxLines = 3)
        }
        if (panel.buttons.isNotEmpty()) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                panel.buttons.forEachIndexed { i, b ->
                    PanelButtonView(b, primary = i == 0, focused = state.zone == Zone.PANEL && state.panelFocused == i)
                }
            }
        }
    }
}

@Composable
private fun MetaRow(panel: PanelState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        panel.channelNumber?.let { n ->
            Box(
                Modifier
                    .border(1.dp, GuideUi.White12, RoundedCornerShape(DesignTokens.Radii.Chip))
                    .background(GuideUi.White08, RoundedCornerShape(DesignTokens.Radii.Chip))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) { Line(n.toString(), GuideUi.mono(16, color = GuideUi.Fg70)) }
        }
        Line(panel.channelName, GuideUi.sans(18, color = GuideUi.Fg70))
        panel.genre?.takeIf { it.isNotBlank() }?.let { g ->
            Dot(GuideUi.Fg.copy(alpha = 0.4f), 4.dp)
            Line(g, GuideUi.sans(18, color = GuideUi.Fg70))
        }
        if (panel.onNow) {
            Box(
                Modifier
                    .background(DesignTokens.Colors.OnNowFill, RoundedCornerShape(DesignTokens.Radii.Pill))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) { Line(stringResource(R.string.guide_on_now), GuideUi.sans(15, FontWeight.SemiBold, DesignTokens.Colors.AccentText, 0.06f)) }
        }
        if (panel.catchupDays > 0) {
            Dot(GuideUi.Fg.copy(alpha = 0.4f), 4.dp)
            Line(pluralStringResource(R.plurals.guide_catchup_days, panel.catchupDays, panel.catchupDays), GuideUi.sans(18, color = GuideUi.Fg70))
        }
    }
}

/** Play triangle of the primary button (prototype: border-left 11, top/bottom 7). */
private val PlayTriangle = GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width, size.height / 2f)
    lineTo(0f, size.height)
    close()
}

@Composable
private fun PanelButtonView(button: PanelButton, primary: Boolean, focused: Boolean) {
    val shape = RoundedCornerShape(DesignTokens.Radii.Button)
    Chip(
        focused = focused,
        fill = if (primary) GuideUi.Fg else GuideUi.White10,
        border = if (primary) null else GuideUi.White16,
        shape = shape,
        padding = PaddingValues(horizontal = if (primary) 26.dp else 24.dp, vertical = 14.dp),
    ) { contentColor ->
        val textColor = if (primary || focused) GuideUi.Canvas else contentColor
        when (button) {
            PanelButton.WATCHING, PanelButton.WATCH_NOW, PanelButton.WATCH, PanelButton.FROM_START, PanelButton.WATCH_CHANNEL_NOW ->
                Box(Modifier.size(11.dp, 14.dp).background(textColor, PlayTriangle))
            PanelButton.REMIND -> Dot(if (focused || primary) GuideUi.Canvas.copy(alpha = 0.5f) else GuideUi.Fg50, 10.dp)
            PanelButton.REMINDER_SET -> Dot(GuideUi.Live, 10.dp)
            PanelButton.MORE -> Unit
        }
        Text(
            stringResource(panelButtonLabel(button)),
            style = GuideUi.sans(19, if (primary) FontWeight.SemiBold else FontWeight.Medium, textColor),
            maxLines = 1,
        )
    }
}

fun panelButtonLabel(button: PanelButton): Int = when (button) {
    PanelButton.WATCHING -> R.string.guide_btn_watching
    PanelButton.WATCH_NOW -> R.string.guide_btn_watch_now
    PanelButton.WATCH -> R.string.guide_btn_watch
    PanelButton.FROM_START -> R.string.guide_btn_from_start
    PanelButton.REMIND -> R.string.guide_btn_remind
    PanelButton.REMINDER_SET -> R.string.guide_btn_reminder_set
    PanelButton.WATCH_CHANNEL_NOW -> R.string.guide_btn_watch_channel_now
    PanelButton.MORE -> R.string.guide_btn_more
}
