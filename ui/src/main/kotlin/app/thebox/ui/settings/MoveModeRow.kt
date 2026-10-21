package app.thebox.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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

/**
 * The lifted row of move mode (docs/design/m5-setup-settings.md §3.1, §3.2.2): 1 px accent border, `.12` fill, a `↕`
 * glyph 22 at the left; 1276 × 72 (an item row), design px. WP-A's row list draws it in place of the row whose id is
 * `MoveState.row`; ↑/↓ move it, OK drops (`RowIntent.Move`), BACK cancels. Groups, favourite lists and list entries use it.
 */
@Composable
fun MoveModeRow(label: String, value: String?, modifier: Modifier = Modifier) {
    val hint = stringResource(R.string.backup_move_hint)
    Row(
        modifier.testTag(MOVE_ROW_TAG).semantics { contentDescription = "$label, $hint" }
            .width(1276.dp).height(72.dp)
            .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .border(1.dp, GuideUi.Accent, RoundedCornerShape(14.dp))
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Line("↕", GuideUi.sans(28, FontWeight.SemiBold, GuideUi.Accent))
        Spacer(Modifier.width(16.dp))
        Line(label, GuideUi.sans(28, FontWeight.Medium), Modifier.weight(1f))
        if (value != null) Line(value, GuideUi.sans(26, color = GuideUi.Fg65))
        Spacer(Modifier.width(24.dp))
        Line(hint, GuideUi.mono(22, color = GuideUi.Fg55))
    }
}
