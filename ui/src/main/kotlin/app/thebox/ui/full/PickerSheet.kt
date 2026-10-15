package app.thebox.ui.full

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.full.PickerChoice
import app.thebox.core.full.PickerState
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.theme.DesignTokens

const val PICKER_SHEET_TAG = "player_picker"
const val PICKER_ROW_TAG = "player_picker_row"

/**
 * Audio / subtitles / aspect / decoder picker (docs/design/m4-player-ux.md §3.4.2, §3.9): (1376, 48) 496 × 880 design
 * px, ending above the quick bar; fill `#0A0D13` .86, border .09, radius 24; title 24/600, rows 64 high 21/500, a check
 * mark on [PickerState.current], unsupported audio tracks greyed with "not supported on this device", speculative CEA
 * tracks as "Captions (if broadcast)", and the "Remember for this channel" row (a box glyph) when
 * [PickerState.remember] is non-null (index `choices.size`). Rows scroll as a window around the focus.
 */
@Composable
fun PickerSheet(state: PickerState, modifier: Modifier = Modifier) {
    val res = androidx.compose.ui.platform.LocalResources.current
    val shape = RoundedCornerShape(DesignTokens.Radii.Panel)
    Column(
        modifier
            .testTag(PICKER_SHEET_TAG)
            .size(PlayerUi.PICKER_W.dp, PlayerUi.PICKER_H.dp)
            .background(PlayerUi.PanelFill, shape)
            .border(1.dp, PlayerUi.Border09, shape)
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Line(stringResource(PlayerText.pickerTitle(state.kind)), GuideUi.sans(24, FontWeight.SemiBold), Modifier.padding(start = 12.dp, bottom = 12.dp))
        val rows = state.choices.size + if (state.remember != null) 1 else 0
        val top = (state.focused - VISIBLE / 2).coerceIn(0, (rows - VISIBLE).coerceAtLeast(0))
        for (i in top until minOf(rows, top + VISIBLE)) {
            if (i == state.choices.size) {
                RememberRow(checked = state.remember == true, focused = state.focused == i)
                continue
            }
            val c = state.choices[i]
            val disabled = c is PickerChoice.Audio && !c.track.supported
            PickerRow(
                text = PlayerText.choice(res, c, i),
                note = if (disabled) stringResource(R.string.player_picker_unsupported) else null,
                checked = i == state.current,
                focused = i == state.focused,
                disabled = disabled,
            )
        }
    }
}

@Composable
private fun PickerRow(text: String, note: String?, checked: Boolean, focused: Boolean, disabled: Boolean) {
    val fg = when {
        focused -> GuideUi.Canvas
        disabled -> GuideUi.Fg50
        else -> GuideUi.Fg
    }
    Row(
        Modifier
            .testTag(PICKER_ROW_TAG)
            .height(PlayerUi.PICKER_ROW_H.dp)
            .fillMaxWidth()
            .then(if (focused) Modifier.border(GuideUi.FocusRing, GuideUi.Accent, RoundedCornerShape(DesignTokens.Radii.Row)) else Modifier)
            .background(if (focused) GuideUi.Fg else Color.Transparent, RoundedCornerShape(DesignTokens.Radii.Row))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(24.dp)) { if (checked) Line("✓", GuideUi.sans(21, FontWeight.SemiBold, if (focused) fg else GuideUi.Accent)) }
        Column(Modifier.weight(1f)) {
            Line(text, GuideUi.sans(21, FontWeight.Medium, fg))
            note?.let { Line(it, GuideUi.sans(15, color = fg.copy(alpha = 0.7f))) }
        }
    }
}

@Composable
private fun RememberRow(checked: Boolean, focused: Boolean) {
    val fg = if (focused) GuideUi.Canvas else GuideUi.Fg
    Row(
        Modifier
            .testTag(PICKER_ROW_TAG)
            .padding(top = 8.dp)
            .height(PlayerUi.PICKER_ROW_H.dp)
            .fillMaxWidth()
            .background(if (focused) GuideUi.Fg else Color.Transparent, RoundedCornerShape(DesignTokens.Radii.Row))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(22.dp).border(2.dp, fg, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
            if (checked) Line("✓", GuideUi.sans(17, FontWeight.Bold, fg))
        }
        Line(stringResource(R.string.player_picker_remember), GuideUi.sans(19, FontWeight.Medium, fg))
    }
}

/** Rows that fit the 880 px sheet under its title. */
private const val VISIBLE = 11
