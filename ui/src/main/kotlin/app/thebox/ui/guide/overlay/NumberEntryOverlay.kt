package app.thebox.ui.guide.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.thebox.core.guide.Overlay
import app.thebox.ui.theme.BoxFonts
import app.thebox.ui.theme.DesignTokens

/**
 * Number entry digits (docs/design/m3-guide.md §3.9.4, §3.15: top right at (1520, 72), 320 × 160 design px, over the
 * header region and never over the hole). The caller places it (`Modifier.offset`/`align`) inside [BoxDesignScale];
 * sizes here are design px (`.dp`/`.sp` under the design density). Not focusable: keys reach the state machine
 * through the root key sink. D-pad entry underlines the cursor digit; "No channel N" replaces the preview when the
 * lookup failed.
 */
@Composable
fun NumberEntryOverlay(state: Overlay.Number, modifier: Modifier = Modifier) {
    val fg = DesignTokens.Colors.Fg
    val shape = RoundedCornerShape(20.dp)
    val digitsText = state.digits.ifEmpty { "–" }
    val preview = state.preview
    val line = when {
        state.notFound -> "No channel ${state.digits}"
        preview != null -> preview.displayName
        else -> ""
    }
    Column(
        modifier
            .size(320.dp, 160.dp)
            .background(DesignTokens.Colors.Canvas.copy(alpha = 0.92f), shape)
            .border(1.dp, DesignTokens.Colors.GlassBorder, shape)
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .semantics {
                contentDescription = if (line.isEmpty()) "Channel ${state.digits}" else "Channel ${state.digits}, $line"
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.End,
    ) {
        Row(horizontalArrangement = Arrangement.End) {
            val cursor = if (state.cursorDigit >= 0) state.digits.length - 1 else -1
            digitsText.forEachIndexed { i, c ->
                Text(
                    c.toString(),
                    style = TextStyle(
                        fontFamily = BoxFonts.Mono,
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (state.notFound) fg.copy(alpha = 0.5f) else fg,
                        textDecoration = if (i == cursor) TextDecoration.Underline else null,
                    ),
                )
            }
        }
        Text(
            line,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontFamily = BoxFonts.Sans,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (state.notFound) DesignTokens.Colors.Live else Color.White.copy(alpha = 0.75f),
            ),
        )
    }
}
