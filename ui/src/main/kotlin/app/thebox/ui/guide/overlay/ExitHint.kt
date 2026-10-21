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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.Overlay
import app.thebox.ui.R
import app.thebox.ui.guide.screen.Chip
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.theme.DesignTokens

const val EXIT_HINT_TAG = "exit_hint"
const val EXIT_DIALOG_TAG = "exit_dialog"

/**
 * The exit step of the Back ladder (docs/design/m3-guide.md §3.4.6, B2): `BACK_TWICE` shows a non-modal hint
 * "Press Back again to exit" (toast position, left 64 / bottom 96); `ASK` shows the dialog [Exit] [Cancel], placed
 * over the grid panel so it never intersects the hole.
 */
@Composable
fun ExitHint(state: GuideUiState, modifier: Modifier = Modifier) {
    val o = state.overlay as? Overlay.Exit ?: return
    if (!o.dialog) {
        Line(
            stringResource(R.string.guide_exit_hint),
            GuideUi.sans(24, FontWeight.Medium),
            modifier
                .testTag(EXIT_HINT_TAG)
                .background(GuideUi.OverlayFill, RoundedCornerShape(DesignTokens.Radii.Button))
                .border(1.dp, GuideUi.OverlayBorder, RoundedCornerShape(DesignTokens.Radii.Button))
                .padding(horizontal = 22.dp, vertical = 14.dp),
        )
        return
    }
    val shape = RoundedCornerShape(DesignTokens.Radii.Panel)
    Column(
        modifier
            .offset(860.dp, 600.dp)
            .width(560.dp)
            .testTag(EXIT_DIALOG_TAG)
            .background(GuideUi.OverlayFill, shape)
            .border(1.dp, GuideUi.OverlayBorder, shape)
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Line(stringResource(R.string.guide_exit_title), GuideUi.sans(34, FontWeight.SemiBold))
        Line(stringResource(R.string.guide_exit_body), GuideUi.sans(24, color = GuideUi.Fg70), maxLines = 2)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Chip(o.focused == 0, fill = GuideUi.White10, border = GuideUi.White16, shape = RoundedCornerShape(DesignTokens.Radii.Button), padding = PaddingValues(horizontal = 26.dp, vertical = 12.dp)) { c ->
                Line(stringResource(R.string.guide_exit_confirm), GuideUi.sans(24, FontWeight.SemiBold, c))
            }
            Chip(o.focused == 1, fill = GuideUi.White10, border = GuideUi.White16, shape = RoundedCornerShape(DesignTokens.Radii.Button), padding = PaddingValues(horizontal = 26.dp, vertical = 12.dp)) { c ->
                Line(stringResource(R.string.guide_exit_cancel), GuideUi.sans(24, FontWeight.SemiBold, c))
            }
        }
    }
}
