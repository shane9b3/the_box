package app.thebox.ui.full

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.playback.NextStep
import app.thebox.core.catchup.archive.ArchiveFailure
import app.thebox.core.full.ErrorCardState
import app.thebox.ui.guide.screen.Chip
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.playback.ErrorText
import app.thebox.ui.theme.DesignTokens
import kotlinx.coroutines.delay

const val PLAYER_ERROR_CARD_TAG = "player_error_card"
/** P-S2 = U-S4: the next-step line (I-M4-16 asserts it on every card). */
const val PLAYER_ERROR_STEP_TAG = "player_error_step"
const val PLAYER_ERROR_BUTTON_TAG = "player_error_button"

/**
 * The full-screen error card (docs/design/m4-player-ux.md §3.7, §3.9): centred, 720 wide, fill `#0A0D13` .94, border
 * .12, radius 24, padding 36. Logo 48 + channel name (19, .7); the reason (`ErrorText.headline`, 30/600, two lines);
 * the detail line (mono 15, .55: `HTTP 403 · host · … · play.forbidden`); the retry line while the ladder keeps trying
 * ("Still trying… next attempt in 12 s" counts down, unlimited when `maxAttempts == 0`); the next-step line (19, .75)
 * for [ErrorCardState.textStep] (M5 destinations are text, never buttons, F5); then the buttons (19/600, 14 × 26
 * padding, focused = FG fill + accent ring). A terminal archive session with a build failure ([archiveFailure] other
 * than NOT_IN_ARCHIVE / PLAYBACK) shows M3's catch-up reason and step instead of the playback error's.
 */
@Composable
fun PlayerErrorCard(state: ErrorCardState, modifier: Modifier = Modifier, archiveFailure: ArchiveFailure? = null) {
    val res = androidx.compose.ui.platform.LocalResources.current
    val shape = RoundedCornerShape(DesignTokens.Radii.Panel)
    val buildFailure = archiveFailure?.takeIf { state.archiveFailed && it != ArchiveFailure.NOT_IN_ARCHIVE && it != ArchiveFailure.PLAYBACK }
    Column(
        modifier
            .testTag(PLAYER_ERROR_CARD_TAG)
            .width(PlayerUi.CARD_W.dp)
            .background(PlayerUi.CardFill, shape)
            .border(1.dp, PlayerUi.Border12, shape)
            .padding(PlayerUi.CARD_PAD.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.channel?.let { ch ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ChannelLogo(ch, 48.dp, 8.dp)
                Line(ch.displayName, GuideUi.sans(24, color = GuideUi.Fg70))
            }
        }
        if (buildFailure != null) {
            Line(stringResource(ErrorText.archiveHeadline(buildFailure)), GuideUi.sans(36, FontWeight.SemiBold), maxLines = 2)
            Line(stringResource(ErrorText.archiveStep(buildFailure)), GuideUi.sans(24, color = GuideUi.Fg.copy(alpha = 0.75f)), maxLines = 2)
        } else {
            val e = state.error
            Line(stringResource(ErrorText.headline(e.code)), GuideUi.sans(36, FontWeight.SemiBold), maxLines = 2)
            Line(ErrorText.detailLine(res, e.code, e.detail), GuideUi.mono(20, color = GuideUi.Fg55), maxLines = 2)
            e.retry?.let { retry ->
                val shownAt = remember(retry) { System.currentTimeMillis() }
                var elapsed by remember(retry) { mutableLongStateOf(0L) }
                LaunchedEffect(retry) {
                    while (retry.maxAttempts == 0 && retry.nextAttemptInMs != null && elapsed < retry.nextAttemptInMs!!) {
                        delay(1_000L)
                        elapsed = System.currentTimeMillis() - shownAt
                    }
                }
                ErrorText.retryLine(res, retry, elapsed)?.let { Line(it, GuideUi.sans(24, color = GuideUi.Fg70)) }
            }
            // P-S2 = U-S4: the core model always fills the next step (from the buttons when every step became one), so
            // this line is always drawn; the fallback covers a hand-built state without one.
            Line(
                stringResource(ErrorText.step(state.textStep ?: NextStep.RETRY)),
                GuideUi.sans(24, color = GuideUi.Fg.copy(alpha = 0.75f)),
                Modifier.testTag(PLAYER_ERROR_STEP_TAG),
                maxLines = 2,
            )
        }
        if (state.buttons.isNotEmpty()) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                state.buttons.forEachIndexed { i, b ->
                    Chip(
                        focused = i == state.focused,
                        modifier = Modifier.testTag(PLAYER_ERROR_BUTTON_TAG),
                        fill = PlayerUi.ChipFill,
                        shape = RoundedCornerShape(DesignTokens.Radii.Button),
                        padding = PaddingValues(horizontal = 26.dp, vertical = 14.dp),
                    ) { c -> Line(stringResource(ErrorText.button(b, state.route)), GuideUi.sans(24, FontWeight.SemiBold, c)) }
                }
            }
        }
    }
}
