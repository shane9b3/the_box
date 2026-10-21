package app.thebox.ui.setup

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.RowKind
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.theme.DesignTokens

const val WELCOME_TAG = "setup_welcome"
const val WELCOME_CARD_TAG = "setup_welcome_card"

/**
 * Welcome, S1 (docs/design/m5-setup-settings.md §3.4.2), drawn from the FirstRun [PageModel] that `WelcomePage`
 * builds: the four Link rows are the cards in order, the Info row is the footer note; [cursor] is the machine's cursor
 * (← / → move it). Design px under `BoxDesignScale` (1.dp = 1 design px); in-tree, no Popup; no blur, no layer on any
 * ancestor of the video surface (the screen is opaque like the rest of SETTINGS).
 */
@Composable
fun WelcomeScreen(page: PageModel, cursor: Int, modifier: Modifier = Modifier) {
    val cards = page.rows.filter { it.kind is RowKind.Link }
    val note = page.rows.firstOrNull { it.kind is RowKind.Info }
    Box(modifier.fillMaxSize().background(DesignTokens.Colors.Canvas).testTag(WELCOME_TAG)) {
        // The wordmark on the ambient background in the mini-player slot (64, 56, 720 × 405).
        Box(
            Modifier.offset(64.dp, 56.dp).size(720.dp, 405.dp)
                .background(BRAND_AMBIENT, RoundedCornerShape(DesignTokens.Radii.MiniPlayer)),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.wordmark), contentDescription = null, modifier = Modifier.width(420.dp))
        }
        Column(Modifier.offset(832.dp, 160.dp).width(1024.dp)) {
            Line(page.title, GuideUi.sans(64, FontWeight.SemiBold))
            page.subtitle?.let {
                Line(it, GuideUi.sans(26, color = GuideUi.Fg70).merge(TextStyle(lineHeight = (21 * 1.45f).sp)), Modifier.padding(top = 24.dp), maxLines = 3)
            }
        }
        cards.forEachIndexed { i, row ->
            val focused = i == cursor
            val shape = RoundedCornerShape(DesignTokens.Radii.Panel)
            Column(
                Modifier.offset((64 + i * CARD_STEP).dp, 560.dp).size(400.dp, 260.dp)
                    .testTag("$WELCOME_CARD_TAG$i")
                    .background(if (focused) DesignTokens.Colors.Fg else DesignTokens.Colors.GlassFill, shape)
                    .border(if (focused) GuideUi.FocusRing else 1.dp, if (focused) DesignTokens.Colors.Accent else DesignTokens.Colors.GlassBorder, shape)
                    .padding(28.dp),
            ) {
                val text = if (focused) DesignTokens.Colors.Canvas else DesignTokens.Colors.Fg
                Line(row.label, GuideUi.sans(36, FontWeight.SemiBold, color = text))
                row.description?.let { Line(it, GuideUi.sans(24, color = text.copy(alpha = 0.7f)), Modifier.padding(top = 14.dp), maxLines = 2) }
            }
        }
        note?.let { Line(it.label, GuideUi.sans(22, color = GuideUi.Fg55), Modifier.offset(64.dp, 860.dp)) }
    }
}

/** 400 wide + 32 gap (4 × 400 + 3 × 32 = 1696 ≤ 1792). */
private const val CARD_STEP = 432

/** The brand blue `oklch(0.45 0.12 240)` in sRGB. */
private val BRAND_AMBIENT = Color(0xFF15588A)
