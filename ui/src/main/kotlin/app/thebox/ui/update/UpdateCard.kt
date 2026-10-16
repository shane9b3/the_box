package app.thebox.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.thebox.core.update.UpdateCardState
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line

const val UPDATE_CARD_TAG = "update_card"

/**
 * The update prompt (S17, docs/design/m5-setup-settings.md §3.12.3), in the guide only. Sizes in design px.
 * **Commit-0 stub (WP-E replaces the body):** a card with the version name; no buttons. Never shown before WP-E
 * (nothing sets `GuideUiState.update`).
 */
@Composable
fun UpdateCard(state: UpdateCardState, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().testTag(UPDATE_CARD_TAG), contentAlignment = Alignment.Center) {
        Box(Modifier.width(720.dp).background(Color(0xF5141924), RoundedCornerShape(24.dp)).padding(32.dp)) {
            Line(state.versionName, GuideUi.mono(30))
        }
    }
}
