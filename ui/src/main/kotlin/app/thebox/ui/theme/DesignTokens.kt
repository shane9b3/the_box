package app.thebox.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Design tokens from docs/01-design-reference.md ("Design tokens").
 *
 * All sizes are design px and are only correct inside [BoxDesignScale], where 1.dp/1.sp equals
 * one design px. oklch values use their sRGB fallbacks.
 */
object DesignTokens {

    object Colors {
        /** color.bg outer. */
        val BgOuter = Color(0xFF07090D)

        /** color.bg canvas (ambient base). */
        val Canvas = Color(0xFF0A0D13)

        /** color.fg; also the focused-cell and primary-button fill. */
        val Fg = Color(0xFFEEF1F6)

        /** color.accent: oklch(0.72 0.14 240) ≈ #5CA8F2. */
        val Accent = Color(0xFF5CA8F2)

        /** color.accentText: oklch(0.86 0.09 240) ≈ #B6D4F7. */
        val AccentText = Color(0xFFB6D4F7)

        /** color.live: LIVE dot, tuned-channel dot, reminder/record dot. */
        val Live = Color(0xFFFF4D5E)

        /** glass.panel fill: rgba(255,255,255,.045). No real-time blur (master plan §3). */
        val GlassFill = Color.White.copy(alpha = 0.045f)

        /** glass.panel border: rgba(255,255,255,.09). */
        val GlassBorder = Color.White.copy(alpha = 0.09f)

        /** ON NOW pill background: accent at .22. */
        val OnNowFill = Accent.copy(alpha = 0.22f)
    }

    object Radii {
        val Pill: Dp = 999.dp
        val Panel: Dp = 24.dp
        val MiniPlayer: Dp = 20.dp
        val Button: Dp = 14.dp
        val Cell: Dp = 12.dp
        val Row: Dp = 12.dp
        val Chip: Dp = 8.dp
        val KeyCap: Dp = 7.dp
    }

    /** A type token: size in design px, weight, optional line height / letter spacing (em). */
    data class TypeToken(
        val size: TextUnit,
        val weight: FontWeight = FontWeight.Normal,
        val lineHeight: TextUnit = TextUnit.Unspecified,
        val letterSpacing: TextUnit = TextUnit.Unspecified,
        val mono: Boolean = false,
        val tabularNums: Boolean = false,
    )

    object Type {
        /** type.title 64/600 lh 1.02 ls -.025em. */
        val Title = TypeToken(64.sp, FontWeight.SemiBold, lineHeight = 1.02.em, letterSpacing = (-0.025).em)

        /** type.miniTitle 30/600. */
        val MiniTitle = TypeToken(30.sp, FontWeight.SemiBold)

        /** type.clock 34/500 tabular. */
        val Clock = TypeToken(34.sp, FontWeight.Medium, tabularNums = true)

        /** type.body 21 lh 1.45. */
        val Body = TypeToken(21.sp, lineHeight = 1.45.em)

        /** type.cellTitle 18/600. */
        val CellTitle = TypeToken(18.sp, FontWeight.SemiBold)

        /** type.cellTime 13 mono. */
        val CellTime = TypeToken(13.sp, mono = true)
    }
}
