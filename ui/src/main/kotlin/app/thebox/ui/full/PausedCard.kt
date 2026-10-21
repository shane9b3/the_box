// M6 time-shift, WP-C (docs/design/m6-timeshift.md §11.5, U-B1, U-S4).
package app.thebox.ui.full

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.full.PausedHeader
import app.thebox.core.full.PillState
import app.thebox.core.playback.timeshift.TimeshiftConstants
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line

const val PAUSED_DIM_TAG = "player_paused_dim"
const val PAUSED_CARD_TAG = "player_paused_card"
const val PAUSED_CARD_BODY_TAG = "player_paused_card_body"

/**
 * The paused screen (§11.5): after `PAUSED_DIM_AFTER_MS` of a LOCAL pause without a key, the frozen frame is dimmed to
 * `PAUSED_DIM_ALPHA` brightness by a plain black layer drawn **over** the video hole (a sibling in the FULL box, never an
 * ancestor of the `SurfaceView`: CLAUDE.md), with a card that moves every `PAUSED_CARD_DRIFT_MS` among
 * [CARD_POSITIONS] (inside the 5 % title-safe area):
 *
 * > **Paused** · 12:40 behind live
 * > Can stay paused 17 more min · OK or ▶ to continue
 *
 * When saving stopped, the body is the whole header line ([PausedHeader]) with its next step ("Press OK to continue
 * from where you paused"), up to three lines (fix pass, UX review S4: the 110–115 character lines no longer lose their
 * end). [header] comes from `PlayerUiState.paused`, so it is there with the scrub bar closed. Draws nothing unless
 * [PausedFacts.dimmed]. The machine consumes the key that wakes it (`PlayerStateMachineImpl`), so the first OK only
 * restores the picture.
 */
@Composable
internal fun PausedCard(pill: PillState, header: PausedHeader?, facts: PausedFacts, modifier: Modifier = Modifier) {
    if (!facts.dimmed) return
    val res = LocalResources.current
    val now = rememberTicker(1_000L).value
    val behind = pill.behindMs + if (pill.behindGrows) (now - pill.atMs).coerceAtLeast(0L) else 0L
    val since = facts.dimSinceMs ?: now
    val (x, y) = cardPosition(now - since)
    Box(modifier.fillMaxSize().testTag(PAUSED_DIM_TAG).background(Color.Black.copy(alpha = 1f - TimeshiftConstants.PAUSED_DIM_ALPHA))) {
        Column(
            Modifier
                .offset(x.dp, y.dp)
                .width(CARD_W.dp)
                .testTag(PAUSED_CARD_TAG)
                .background(PlayerUi.CardFill, RoundedCornerShape(20.dp))
                .padding(horizontal = 32.dp, vertical = 24.dp),
        ) {
            Line(stringResource(R.string.player_paused_card_title, PlayerText.behindText(behind)), GuideUi.sans(32, FontWeight.SemiBold))
            val ok = stringResource(if (facts.mediaKeys) R.string.player_paused_ok_play else R.string.player_paused_ok)
            val body = when {
                header == PausedHeader.SAVING || (header == null && facts.pauseRemainingMs != null) ->
                    stringResource(R.string.player_paused_card_body, PlayerText.minutesDown(facts.pauseRemainingMs ?: 0L), ok)
                header != null -> PlayerText.pausedHeader(res, header, facts)
                else -> null
            }
            body?.let { Line(it, GuideUi.sans(24, color = GuideUi.Fg.copy(alpha = 0.8f)), Modifier.padding(top = 8.dp).testTag(PAUSED_CARD_BODY_TAG), maxLines = CARD_BODY_LINES) }
        }
    }
}

/** The card's top-left (design px) [elapsedMs] after the dim began: one of [CARD_POSITIONS] per `PAUSED_CARD_DRIFT_MS`. */
internal fun cardPosition(elapsedMs: Long): Pair<Float, Float> {
    val i = Math.floorMod(elapsedMs.coerceAtLeast(0L) / TimeshiftConstants.PAUSED_CARD_DRIFT_MS, CARD_POSITIONS.size.toLong()).toInt()
    return CARD_POSITIONS[i]
}

/** Card width (design px). */
internal const val CARD_W = 720f
/**
 * Card height budget (design px) used to keep every position inside the safe area: 24 + title (32 px type) + 8 + three
 * body lines (24 px type) + 24 (fix pass S4: was 180 for two lines).
 */
internal const val CARD_H = 216f
/** Body lines: the longest header line (HOLD_LIMIT, SLOT_LOST with "Press OK or ▶") needs three at 656 px. */
internal const val CARD_BODY_LINES = 3
/** 5 % title-safe area of the 1920 × 1080 canvas (96 × 54 design px). */
internal const val SAFE_X = 96f
internal const val SAFE_Y = 54f

/** Drift positions: the four safe corners and the centre band, visited in an order that never repeats a neighbour. */
internal val CARD_POSITIONS: List<Pair<Float, Float>> = listOf(
    SAFE_X to SAFE_Y,
    (1920f - SAFE_X - CARD_W) to (1080f - SAFE_Y - CARD_H),
    (1920f - SAFE_X - CARD_W) to SAFE_Y,
    SAFE_X to (1080f - SAFE_Y - CARD_H),
    ((1920f - CARD_W) / 2f) to ((1080f - CARD_H) / 2f),
)
