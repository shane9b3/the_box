package app.thebox.ui.full

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.Overlay
import app.thebox.ui.guide.overlay.NumberEntryOverlay
import app.thebox.ui.guide.screen.ErrorCard
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line

const val FULL_SCREEN_TAG = "full_screen"
const val GROUP_CARD_TAG = "group_card"

/**
 * Minimal M3 full screen (docs/design/m3-guide.md §3.13.5): only the video (the root puts `VideoLayer` at the full
 * window), the group card after a group cycle (accelerator 3), the compact error card centred in ERROR, and number
 * entry. The reminder card and toasts are drawn by the root in every mode. Info bar, channel list and pickers are M4.
 * Keys reach the state machine through the root key sink, which this screen never replaces (B1).
 */
@Composable
fun FullScreenHost(state: GuideUiState, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().testTag(FULL_SCREEN_TAG)) {
        state.groupCard?.let { label ->
            Line(
                label,
                GuideUi.sans(24, FontWeight.SemiBold),
                Modifier
                    .offset(64.dp, 56.dp)
                    .testTag(GROUP_CARD_TAG)
                    .background(GuideUi.OverlayFill, RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            )
        }
        val tuned = state.tuned
        if (tuned.phase == MiniPhase.ERROR && tuned.error != null) {
            ErrorCard(tuned.error!!, state.tunedChannel, Modifier.align(Alignment.Center))
        }
        (state.overlay as? Overlay.Number)?.let { NumberEntryOverlay(it, Modifier.offset(1520.dp, 72.dp).size(320.dp, 160.dp)) }
    }
}
