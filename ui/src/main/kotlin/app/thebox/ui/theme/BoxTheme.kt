package app.thebox.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/** Converts a [DesignTokens.TypeToken] into a Compose [TextStyle] using the bundled fonts. */
fun DesignTokens.TypeToken.toTextStyle(): TextStyle = TextStyle(
    fontFamily = if (mono) BoxFonts.Mono else BoxFonts.Sans,
    fontSize = size,
    fontWeight = weight,
    lineHeight = lineHeight,
    letterSpacing = letterSpacing,
    fontFeatureSettings = if (tabularNums) "tnum" else null,
)

/** Named text styles for the guide; prefer these over the Material slots in app code. */
object BoxTextStyles {
    val Title = DesignTokens.Type.Title.toTextStyle()
    val MiniTitle = DesignTokens.Type.MiniTitle.toTextStyle()
    val Clock = DesignTokens.Type.Clock.toTextStyle()
    val Body = DesignTokens.Type.Body.toTextStyle()
    val CellTitle = DesignTokens.Type.CellTitle.toTextStyle()
    val CellTime = DesignTokens.Type.CellTime.toTextStyle()
}

private val BoxColorScheme: ColorScheme = with(DesignTokens.Colors) {
    darkColorScheme(
        primary = Accent,
        onPrimary = Canvas,
        primaryContainer = OnNowFill,
        onPrimaryContainer = AccentText,
        secondary = AccentText,
        onSecondary = Canvas,
        background = Canvas,
        onBackground = Fg,
        surface = Canvas,
        onSurface = Fg,
        surfaceVariant = GlassFill,
        onSurfaceVariant = Fg.copy(alpha = 0.75f),
        border = GlassBorder,
        error = Live,
        onError = Fg,
    )
}

private val BoxTypography = Typography(
    displayLarge = BoxTextStyles.Title,
    headlineMedium = BoxTextStyles.Clock,
    titleLarge = BoxTextStyles.MiniTitle,
    titleSmall = BoxTextStyles.CellTitle,
    bodyLarge = BoxTextStyles.Body,
    labelSmall = BoxTextStyles.CellTime,
)

/**
 * App theme: TV Material 3 with the dark token scheme, wrapped in [BoxDesignScale] so all
 * sizes are design px.
 */
@Composable
fun BoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BoxColorScheme, typography = BoxTypography) {
        BoxDesignScale(content)
    }
}
