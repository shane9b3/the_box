package app.thebox.ui.parental

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

const val LOCK_GLYPH_TAG = "lock_glyph"
/** U-S11 (code review 2026-10-16): the amber `18+` chip of a locked entry while no PIN protects it (FILTERED_NO_PIN). */
const val NOT_PROTECTED_CHIP_TAG = "not_protected_chip"

/**
 * The padlock of locked entries in the Groups panel and the player's group column (docs/design/m5-setup-settings.md
 * §3.9.1 "Show with a lock"; the same shape as the grid's `GuideAsset.LOCK_GLYPH`). Drawn, not a font glyph (the
 * bundled fonts have none). [size] in design px.
 */
@Composable
fun LockGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier.size(size).testTag(LOCK_GLYPH_TAG)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.14f
        val bodyTop = h * 0.45f
        drawRoundRect(color, topLeft = Offset(w * 0.1f, bodyTop), size = Size(w * 0.8f, h - bodyTop), cornerRadius = CornerRadius(w * 0.12f))
        drawArc(
            color, startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(w * 0.26f, h * 0.08f), size = Size(w * 0.48f, h * 0.62f), style = Stroke(stroke),
        )
    }
}

/**
 * U-S11 (code review 2026-10-16): §3.9.1, in FILTERED_NO_PIN locked groups show "the `18+` badge in amber" instead of
 * the padlock (their door is the not-protected card, not a PIN). 14 mono, radius 7, amber text and a `.22` amber
 * border; on a focused (light) row it takes the row's text colour. [color] null = amber.
 */
@Composable
fun NotProtectedChip(modifier: Modifier = Modifier, color: Color? = null) {
    val c = color ?: Amber
    androidx.compose.foundation.layout.Box(
        modifier
            .testTag(NOT_PROTECTED_CHIP_TAG)
            .border(1.dp, c.copy(alpha = 0.22f), androidx.compose.foundation.shape.RoundedCornerShape(7.dp))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    ) {
        app.thebox.ui.guide.screen.Line(
            androidx.compose.ui.res.stringResource(app.thebox.ui.R.string.settings_ui_badge_adult),
            app.thebox.ui.guide.screen.GuideUi.mono(14, color = c),
        )
    }
}
