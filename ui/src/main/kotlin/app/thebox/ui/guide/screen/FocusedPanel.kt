package app.thebox.ui.guide.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.widthIn
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
import app.thebox.core.guide.PanelState
import app.thebox.ui.R
import app.thebox.ui.theme.BoxTextStyles
import app.thebox.ui.theme.DesignTokens

const val FOCUSED_PANEL_TAG = "focused_panel"

/**
 * The focused-programme panel, bottom-aligned in the header region (docs/design/m3-guide.md §3.9.1, §3.15; prototype
 * header block). Drawn from [GuideUiState.panel]; text layouts are cached by Compose per string (`remember` keys), the
 * description is at most 3 lines and only arrives after the 150 ms debounce (B2.8). (owner ruling 2026-11-01) No
 * buttons: meta row, title, time line, description and artwork only; OK and the context menu carry the actions
 * (docs/decisions/2026-11-01-guide-panel-and-mini-player.md).
 */
/** Debug composition counter key (I-12, §2.13 item 20). */
const val COMPOSITIONS_PANEL = "FocusedPanel"

@Composable
fun FocusedPanel(panel: PanelState?, modifier: Modifier = Modifier) {
    if (app.thebox.ui.guide.GuideDebugCounters.ENABLED) androidx.compose.runtime.SideEffect { app.thebox.ui.guide.GuideDebugCounters.bump(COMPOSITIONS_PANEL) } // I-12
    if (panel == null) return
    // Owner request 2026-10-18: poster art (the programme's EPG icon) on the right, the text column beside it.
    Row(modifier.testTag(FOCUSED_PANEL_TAG), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MetaRow(panel)
            val titleStyle = remember { BoxTextStyles.Title.copy(color = GuideUi.Fg, lineBreak = LineBreak.Heading) }
            Line(panel.title, titleStyle, maxLines = 2)
            Line(panel.timeLine, GuideUi.mono(24, color = GuideUi.Fg.copy(alpha = 0.75f)))
            panel.description?.let { desc ->
                val bodyStyle = remember { BoxTextStyles.Body.copy(color = GuideUi.Fg.copy(alpha = 0.78f)) }
                Line(desc, bodyStyle, Modifier.widthIn(max = 980.dp), maxLines = 2)
            }
        }
        panel.imageUrl?.let { Poster(it, panel.title) }
    }
}

/** The programme's artwork: a 2:3 card, 200 × 300 design px, fitted (posters and 16:9 stills both read). */
@Composable
private fun Poster(url: String, title: String) {
    val loader = LocalShellImageLoader.current ?: return
    Box(
        Modifier
            .size(PANEL_POSTER_W.dp, PANEL_POSTER_H.dp)
            .background(GuideUi.White08, RoundedCornerShape(DesignTokens.Radii.Chip))
            .testTag(PANEL_POSTER_TAG),
        contentAlignment = Alignment.Center,
    ) {
        coil3.compose.AsyncImage(
            model = url,
            contentDescription = title,
            imageLoader = loader,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(DesignTokens.Radii.Chip)),
        )
    }
}

const val PANEL_POSTER_TAG = "focused_panel_poster"
private const val PANEL_POSTER_W = 200
private const val PANEL_POSTER_H = 300

@Composable
private fun MetaRow(panel: PanelState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        panel.channelNumber?.let { n ->
            Box(
                Modifier
                    .border(1.dp, GuideUi.White12, RoundedCornerShape(DesignTokens.Radii.Chip))
                    .background(GuideUi.White08, RoundedCornerShape(DesignTokens.Radii.Chip))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) { Line(n.toString(), GuideUi.mono(22, color = GuideUi.Fg70)) }
        }
        Line(panel.channelName, GuideUi.sans(24, color = GuideUi.Fg70))
        panel.genre?.takeIf { it.isNotBlank() }?.let { g ->
            Dot(GuideUi.Fg.copy(alpha = 0.4f), 4.dp)
            Line(g, GuideUi.sans(24, color = GuideUi.Fg70))
        }
        if (panel.onNow) {
            Box(
                Modifier
                    .background(DesignTokens.Colors.OnNowFill, RoundedCornerShape(DesignTokens.Radii.Pill))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) { Line(stringResource(R.string.guide_on_now), GuideUi.sans(20, FontWeight.SemiBold, DesignTokens.Colors.AccentText, 0.06f)) }
        }
        if (panel.catchupDays > 0) {
            Dot(GuideUi.Fg.copy(alpha = 0.4f), 4.dp)
            Line(pluralStringResource(R.plurals.guide_catchup_days, panel.catchupDays, panel.catchupDays), GuideUi.sans(24, color = GuideUi.Fg70))
        }
    }
}
