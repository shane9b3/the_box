package app.thebox.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.thebox.core.guide.GuideColors
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * docs/design/m3-guide.md §1.6 item 6: the renderer's ARGB constants ([GuideColors], `:core`) and the Compose design
 * tokens ([DesignTokens.Colors], `:ui`) must agree wherever both name the same colour.
 */
class GuideColorsTokensTest {
    private fun hex(argb: Int) = "0x%08X".format(argb)

    private fun same(guide: Int, token: Color, name: String) = assertEquals(name, hex(guide), hex(token.toArgb()))

    @Test
    fun solidColoursMatch() {
        same(GuideColors.CANVAS, DesignTokens.Colors.Canvas, "canvas")
        same(GuideColors.FG, DesignTokens.Colors.Fg, "fg")
        same(GuideColors.ACCENT, DesignTokens.Colors.Accent, "accent")
        same(GuideColors.ACCENT_TEXT, DesignTokens.Colors.AccentText, "accentText")
        same(GuideColors.LIVE, DesignTokens.Colors.Live, "live")
    }

    @Test
    fun glassPanelMatches() {
        same(GuideColors.PANEL_FILL, DesignTokens.Colors.GlassFill, "glass fill (.045)")
        same(GuideColors.PANEL_BORDER, DesignTokens.Colors.GlassBorder, "glass border (.09)")
    }

    @Test
    fun fgDerivedAlphasUseTheFgRgb() {
        val fgRgb = GuideColors.FG and 0xFFFFFF
        for ((name, c) in listOf(
            "CELL_PROGRESS" to GuideColors.CELL_PROGRESS, "TEXT_TICK" to GuideColors.TEXT_TICK,
            "TEXT_LABEL" to GuideColors.TEXT_LABEL, "TEXT_PAST" to GuideColors.TEXT_PAST, "TEXT_ARCHIVE" to GuideColors.TEXT_ARCHIVE,
        )) {
            assertEquals(name, hex(fgRgb), hex(c and 0xFFFFFF))
        }
    }
}
