package app.thebox.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line

const val MOVE_ROW_TAG = "move_row"

/** The lifted row's width in full-screen Settings (design px). */
const val MOVE_ROW_W = 1276

/**
 * The lifted row of move mode (docs/design/m5-setup-settings.md §3.1, §3.2.2): 1 px accent border, `.12` fill, a `↕`
 * glyph 22 at the left; 1276 × 72 (an item row), design px. WP-A's row list draws it in place of the row whose id is
 * `MoveState.row`; ↑/↓ move it, OK drops (`RowIntent.Move`), BACK cancels. Groups, favourite lists and list entries use it.
 *
 * (Settings panel S9, docs/design/m6-player-settings-panel.md §2.7, §5.6) In the panel it takes the row's [width] (464)
 * with [compact] on: compact-row type (26 / 22), 16 px padding, at least 64 high, the label on up to 2 lines, and no
 * inline hint (the panel's band shows "↑↓ · OK · Back" while `ui.move != null`). TalkBack still hears the hint.
 */
@Composable
fun MoveModeRow(label: String, value: String?, modifier: Modifier = Modifier, width: Int = MOVE_ROW_W, compact: Boolean = false) {
    val hint = stringResource(R.string.backup_move_hint)
    val shape = RoundedCornerShape(if (compact) 12.dp else 14.dp)
    val sized = if (compact) Modifier.width(width.dp).heightIn(min = 64.dp) else Modifier.width(width.dp).height(72.dp)
    Row(
        modifier.testTag(MOVE_ROW_TAG).semantics { contentDescription = "$label, $hint" }
            .then(sized)
            .background(Color.White.copy(alpha = 0.12f), shape)
            .border(1.dp, GuideUi.Accent, shape)
            .padding(horizontal = if (compact) 16.dp else 24.dp, vertical = if (compact) 10.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (compact) {
            Line("↕", GuideUi.sans(26, FontWeight.SemiBold, GuideUi.Accent))
            Spacer(Modifier.width(12.dp))
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Line(label, GuideUi.sans(26, FontWeight.Medium), maxLines = 2)
                if (value != null) Line(value, GuideUi.sans(22, color = GuideUi.Fg65), Modifier.padding(top = 2.dp))
            }
            return@Row
        }
        Line("↕", GuideUi.sans(28, FontWeight.SemiBold, GuideUi.Accent))
        Spacer(Modifier.width(16.dp))
        Line(label, GuideUi.sans(28, FontWeight.Medium), Modifier.weight(1f))
        if (value != null) Line(value, GuideUi.sans(26, color = GuideUi.Fg65))
        Spacer(Modifier.width(24.dp))
        Line(hint, GuideUi.mono(22, color = GuideUi.Fg55))
    }
}
