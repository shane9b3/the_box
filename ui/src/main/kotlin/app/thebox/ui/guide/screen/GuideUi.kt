package app.thebox.ui.guide.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.thebox.ui.theme.BoxFonts
import app.thebox.ui.theme.DesignTokens

/**
 * Shared visuals of the guide shell, in design px (docs/01-design-reference.md, `design/TV Guide.dc.html`). Plain
 * foundation `Box`/`Row` + tv-material `Text` only: no tv-material Surface/Button (their focus scale, borders and
 * `graphicsLayer { clip; alpha }` do not match the design, API notes §2.5). Focus is virtual: [focused] comes from
 * `GuideUiState`, never from Compose focus.
 */
object GuideUi {
    val Fg = DesignTokens.Colors.Fg
    val Canvas = DesignTokens.Colors.Canvas
    val Accent = DesignTokens.Colors.Accent
    val Live = DesignTokens.Colors.Live
    val Warn = Color(0xFFFFB547)
    val Fg70 = Fg.copy(alpha = 0.70f)
    val Fg65 = Fg.copy(alpha = 0.65f)
    val Fg60 = Fg.copy(alpha = 0.60f)
    val Fg55 = Fg.copy(alpha = 0.55f)
    val Fg50 = Fg.copy(alpha = 0.50f)
    val White10 = Color.White.copy(alpha = 0.10f)
    val White12 = Color.White.copy(alpha = 0.12f)
    val White16 = Color.White.copy(alpha = 0.16f)
    val White22 = Color.White.copy(alpha = 0.22f)
    val White08 = Color.White.copy(alpha = 0.08f)
    val PillDark = Color.Black.copy(alpha = 0.45f)
    /** Opaque fill for overlays that sit over the grid (no blur: B1.3), so grid text and chips never show through. */
    val OverlayFill = Color(0xFF0E121A)
    val OverlayBorder = Color.White.copy(alpha = 0.12f)
    val FocusRing = 3.dp

    fun sans(size: Int, weight: FontWeight = FontWeight.Normal, color: Color = Fg, letterSpacingEm: Float = 0f) = TextStyle(
        fontFamily = BoxFonts.Sans, fontSize = size.sp, fontWeight = weight, color = color,
        letterSpacing = if (letterSpacingEm == 0f) TextStyle.Default.letterSpacing else letterSpacingEm.em,
    )

    fun mono(size: Int, weight: FontWeight = FontWeight.Normal, color: Color = Fg) = TextStyle(
        fontFamily = BoxFonts.Mono, fontSize = size.sp, fontWeight = weight, color = color, fontFeatureSettings = "tnum",
    )
}

/** Single-line text with a real ellipsis. */
@Composable
fun Line(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(text, modifier = modifier, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/**
 * A pill/chip/button drawn from state. [focused] = solid `FG` fill, dark text and a 3 px accent ring (the design's
 * focused button and cell); otherwise [fill] and an optional 1 px [border].
 */
@Composable
fun Chip(
    focused: Boolean,
    modifier: Modifier = Modifier,
    fill: Color = Color.Transparent,
    border: Color? = null,
    shape: Shape = RoundedCornerShape(DesignTokens.Radii.Pill),
    padding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
    content: @Composable RowScope.(contentColor: Color) -> Unit,
) {
    val bg = if (focused) GuideUi.Fg else fill
    var m = modifier
    if (focused) m = m.border(GuideUi.FocusRing, GuideUi.Accent, shape)
    else if (border != null) m = m.border(1.dp, border, shape)
    Row(
        m.background(bg, shape).padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) { content(if (focused) GuideUi.Canvas else GuideUi.Fg) }
}

@Composable
fun Dot(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** Key cap of the footer hints: mono 14, 1 px .22 border, radius 7 (prototype). */
@Composable
fun KeyCap(keys: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .border(1.dp, GuideUi.White22, RoundedCornerShape(DesignTokens.Radii.KeyCap))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) { Line(keys, GuideUi.mono(18, color = GuideUi.Fg55)) }
}

/**
 * (code review 2026-10-14, H-S9) `SimpleDateFormat`s for the header clock and the mini player, cached per (pattern,
 * locale, default zone) instead of built on every recomposition. A zone change (ACTION_TIMEZONE_CHANGED updates the
 * default `TimeZone`) gives a new key. Main thread only.
 */
object TimeFormats {
    private data class Key(val pattern: String, val locale: java.util.Locale, val zoneId: String)
    private val cache = HashMap<Key, java.text.SimpleDateFormat>()

    fun format(pattern: String, locale: java.util.Locale, date: java.util.Date): String {
        val key = Key(pattern, locale, java.util.TimeZone.getDefault().id)
        val f = cache.getOrPut(key) {
            if (cache.size >= MAX) cache.clear()
            java.text.SimpleDateFormat(pattern, locale)
        }
        return f.format(date)
    }

    private const val MAX = 16
}
