package app.thebox.ui.parental

import androidx.compose.foundation.Canvas
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
