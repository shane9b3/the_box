package app.thebox.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.update.UpdateCardModel
import app.thebox.core.update.UpdateCardState
import app.thebox.ui.R
import app.thebox.ui.guide.screen.Chip
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.theme.DesignTokens

const val UPDATE_CARD_TAG = "update_card"
const val UPDATE_BUTTON_TAG = "update_button_"

/**
 * The update prompt (S17, docs/design/m5-setup-settings.md §3.12.3), in the guide only, bottom right where the reminder
 * card goes (right 64, bottom 96, 600 wide; design px): "Version 1.0.4 · notes" and Update / Later / Skip this version
 * (focus from [UpdateCardState.focused]; keys are the guide machine's, `UpdateCardModel.onAction`). A download shows a
 * 4 px progress bar; a failure after Update replaces the notes with its reason (amber). Same glass as the reminder card,
 * no blur, no graphics layer.
 */
@Composable
fun UpdateCard(state: UpdateCardState, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        val shape = RoundedCornerShape(DesignTokens.Radii.Panel)
        Column(
            Modifier
                .padding(end = 64.dp, bottom = 96.dp)
                .width(600.dp)
                .testTag(UPDATE_CARD_TAG)
                .background(GuideUi.OverlayFill, shape)
                .border(1.dp, GuideUi.OverlayBorder, shape)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Line(stringResource(R.string.update_card_title, state.versionName), GuideUi.sans(22, FontWeight.SemiBold))
            val failed = state.failed
            if (failed != null) {
                Line(failed, GuideUi.sans(18, color = GuideUi.Warn), maxLines = 3)
            } else if (state.notes.isNotEmpty()) {
                Line(state.notes, GuideUi.sans(18, color = GuideUi.Fg50), maxLines = 2)
            }
            state.progress?.let { p ->
                Box(Modifier.fillMaxWidth().height(4.dp).background(GuideUi.White10, RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(4.dp).background(GuideUi.Accent, RoundedCornerShape(2.dp)))
                }
                Line(
                    if (p >= 1f) stringResource(R.string.update_card_ready) else stringResource(R.string.update_card_downloading, (p * 100).toInt()),
                    GuideUi.mono(15, color = GuideUi.Fg50),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0 until UpdateCardModel.BUTTONS) {
                    val label = when (i) {
                        0 -> R.string.update_card_update
                        1 -> R.string.update_card_later
                        else -> R.string.update_card_skip
                    }
                    Chip(
                        focused = state.focused == i,
                        modifier = Modifier.testTag(UPDATE_BUTTON_TAG + i),
                        fill = GuideUi.White10,
                        border = GuideUi.White16,
                        shape = RoundedCornerShape(DesignTokens.Radii.Button),
                        padding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                    ) { c -> Line(stringResource(label), GuideUi.sans(18, FontWeight.SemiBold, c)) }
                }
            }
        }
    }
}
