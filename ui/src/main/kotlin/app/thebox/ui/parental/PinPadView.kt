package app.thebox.ui.parental

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.thebox.core.parental.PinPromptState

const val PIN_PAD_TAG = "pin_pad"

/**
 * The PIN prompt (docs/design/m5-setup-settings.md §3.9.3; card 560 wide, centred; four 22 dots, gap 24). Sizes are
 * design px (1.dp = 1 design px under BoxDesignScale). **Commit-0 stub (WP-C replaces the body):** the card and the
 * four dots, no keys, no text. Never shown before WP-C (nothing sets `GuideUiState.pin`).
 */
@Composable
fun PinPadView(state: PinPromptState, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().testTag(PIN_PAD_TAG), contentAlignment = Alignment.Center) {
        Box(
            Modifier.width(560.dp).background(Color(0xF5141924), RoundedCornerShape(24.dp)).padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                repeat(4) { i ->
                    Box(Modifier.size(22.dp).background(if (i < state.filled) Color(0xFFEEF1F6) else Color(0x29FFFFFF), CircleShape))
                }
            }
        }
    }
}
