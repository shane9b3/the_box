package app.thebox.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.thebox.ui.R

/**
 * Bundled fonts (SIL OFL 1.1, licences in assets/licenses/). Static instances are used instead
 * of Google Fonts' variable TTFs because font variations need API 26 and minSdk is 25.
 */
object BoxFonts {
    /** font.sans: Instrument Sans 400/500/600/700. */
    val Sans = FontFamily(
        Font(R.font.instrument_sans_regular, FontWeight.Normal),
        Font(R.font.instrument_sans_medium, FontWeight.Medium),
        Font(R.font.instrument_sans_semibold, FontWeight.SemiBold),
        Font(R.font.instrument_sans_bold, FontWeight.Bold),
    )

    /** font.mono: JetBrains Mono 400/500. */
    val Mono = FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    )
}
