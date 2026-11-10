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
import app.thebox.core.update.UpdateCardPhase
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
 * (focus from [UpdateCardState.focused]; keys are the guide machine's, `UpdateCardModel.onAction`). Every
 * [UpdateCardPhase] has a status line under the title (docs/design/m5-update-visible-install.md §1): "Downloading N %"
 * with a 4 px bar, "Checking the download…", "Opening the installer…" (no buttons in these three), the permission hint,
 * "Not installed. Press Update to try again."; a failure after Update replaces the notes with its reason (amber). Same
 * glass as the reminder card, no blur, no graphics layer.
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
            Line(stringResource(R.string.update_card_title, state.versionName), GuideUi.sans(28, FontWeight.SemiBold))
            val failed = state.failed
            if (failed != null) {
                Line(failed, GuideUi.sans(24, color = GuideUi.Warn), maxLines = 3)
            } else if (state.notes.isNotEmpty()) {
                Line(state.notes, GuideUi.sans(24, color = GuideUi.Fg50), maxLines = 2)
            }
            when (state.phase) {
                UpdateCardPhase.DOWNLOADING -> {
                    val p = (state.progress ?: 0f).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(4.dp).background(GuideUi.White10, RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxWidth(p).height(4.dp).background(GuideUi.Accent, RoundedCornerShape(2.dp)))
                    }
                    Status(stringResource(R.string.update_card_downloading, (p * 100).toInt()))
                }
                UpdateCardPhase.VERIFYING -> Status(stringResource(R.string.update_card_verifying))
                UpdateCardPhase.OPENING_INSTALLER -> Status(stringResource(R.string.update_card_opening))
                // The pre-check sends the user to "Install unknown apps" (API 26+); this says what to do on return.
                UpdateCardPhase.NEEDS_PERMISSION -> Line(stringResource(R.string.update_needs_permission), GuideUi.sans(24, color = GuideUi.Warn), maxLines = 2)
                // Code review 2026-11-10: no Fire OS hint here (a plain Cancel looks the same). A repeated miss switches to
                // the session route (InstallRoute.recordMiss), whose INSTALL_BLOCKED carries the Fire OS text below API 26.
                UpdateCardPhase.NOT_INSTALLED -> Line(stringResource(R.string.update_card_not_installed), GuideUi.sans(24, color = GuideUi.Warn), maxLines = 2)
                UpdateCardPhase.OFFER, UpdateCardPhase.FAILED -> Unit
            }
            // Busy phases show no buttons: BACK hides the card and the work goes on (UpdateCardModel.onAction).
            if (!state.phase.busy) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    ) { c -> Line(stringResource(label), GuideUi.sans(24, FontWeight.SemiBold, c)) }
                }
            }
        }
    }
}

@Composable
private fun Status(text: String) = Line(text, GuideUi.mono(20, color = GuideUi.Fg50))
