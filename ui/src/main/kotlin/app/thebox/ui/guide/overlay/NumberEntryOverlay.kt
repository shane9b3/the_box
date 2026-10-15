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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import app.thebox.ui.R
import app.thebox.ui.guide.screen.LocalShellImageLoader
import app.thebox.ui.theme.BoxFonts
import app.thebox.ui.theme.DesignTokens
import coil3.compose.AsyncImage

const val NUMBER_ENTRY_TAG = "number_entry"

/**
 * Number entry digits (docs/design/m3-guide.md §3.9.4, §3.15: top right at (1520, 72), 320 × 160 design px, over the
 * header region and never over the hole). The caller places it (`Modifier.offset`/`align`) inside [BoxDesignScale];
 * sizes here are design px (`.dp`/`.sp` under the design density). Not focusable: keys reach the state machine
 * through the root key sink. D-pad entry underlines the cursor digit; "No channel N" replaces the preview when the
 * lookup failed.
 *
 * (M4, docs/design/m4-player-ux.md §3.10.4, §3.9) The preview shows the channel's logo (40) left of its name, and in
 * FULL the programme on now ([nowTitle], 15, .6) under it. Every string comes from resources (M3 H-L7).
 */
@Composable
fun NumberEntryOverlay(state: Overlay.Number, modifier: Modifier = Modifier, nowTitle: String? = null) {
    val fg = DesignTokens.Colors.Fg
    val shape = RoundedCornerShape(20.dp)
    val digitsText = state.digits.ifEmpty { "–" }
    val preview = state.preview
    val line = when {
        state.notFound -> stringResource(R.string.number_entry_not_found, state.digits)
        preview != null -> preview.displayName
        else -> ""
    }
    val description = if (line.isEmpty()) stringResource(R.string.number_entry_cd, state.digits) else stringResource(R.string.number_entry_cd_named, state.digits, line)
    Column(
        modifier
            .testTag(NUMBER_ENTRY_TAG)
            .size(320.dp, 160.dp)
            .background(DesignTokens.Colors.Canvas.copy(alpha = 0.92f), shape)
            .border(1.dp, DesignTokens.Colors.GlassBorder, shape)
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .semantics {
                contentDescription = description
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!state.notFound && preview != null) PreviewLogo(preview.logoUrl, preview.displayName)
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
        if (!state.notFound && preview != null && !nowTitle.isNullOrBlank()) {
            Text(
                nowTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = BoxFonts.Sans, fontSize = 15.sp, color = fg.copy(alpha = 0.6f)),
            )
        }
    }
}

/** The preview channel's logo (40 design px), when the shell has a loader and the channel has a logo. */
@Composable
private fun PreviewLogo(url: String?, name: String) {
    val loader = LocalShellImageLoader.current
    if (loader == null || url.isNullOrBlank()) return
    AsyncImage(model = url, contentDescription = name, imageLoader = loader, contentScale = ContentScale.Fit, modifier = Modifier.size(40.dp))
}
