package app.thebox.ui.reminder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.ReminderCardState
import app.thebox.ui.R
import app.thebox.ui.guide.screen.Chip
import app.thebox.ui.guide.screen.Dot
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.theme.DesignTokens
import kotlinx.coroutines.delay
import kotlin.math.ceil

const val REMINDER_CARD_TAG = "reminder_card"
const val REMINDER_SWITCH_TAG = "reminder_switch"
const val REMINDER_DISMISS_TAG = "reminder_dismiss"

/**
 * The modal reminder card, bottom right (right 64, bottom 96, 520 × 152), in GUIDE and FULL (docs/design/m3-guide.md
 * §3.11.3, S2). The logic (modality, the 600 ms input guard, the countdown) is the state machine's (WP-D); the card
 * shows the guard: until [ReminderCardState.inputFromMs] the buttons are dimmed, carry no focus ring and are marked
 * disabled for accessibility, so an in-flight OK visibly cannot accept.
 */
@Composable
fun ReminderCard(state: ReminderCardState, modifier: Modifier = Modifier, clock: () -> Long = System::currentTimeMillis) {
    // Recomputed on every recomposition; the effect forces one when the guard ends (the machine's 250 ms ticks also do).
    var guardTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.inputFromMs) {
        val wait = state.inputFromMs - clock()
        if (wait > 0) {
            delay(wait)
            guardTick++
        }
    }
    val guarded = guardTick >= 0 && clock() < state.inputFromMs
    val a = state.alert
    val now = clock()
    val text = if (a.late || now >= a.startAtMs) {
        val ago = ((now - a.startAtMs) / 60_000L).toInt().coerceAtLeast(0)
        stringResource(R.string.guide_reminder_started, a.title, ago, a.channelName)
    } else {
        val inMin = ceil((a.startAtMs - now) / 60_000.0).toInt().coerceAtLeast(1)
        stringResource(R.string.guide_reminder_starts, a.title, inMin, a.channelName)
    }
    val shape = RoundedCornerShape(DesignTokens.Radii.Panel)
    Column(
        modifier
            .width(520.dp)
            .testTag(REMINDER_CARD_TAG)
            .background(GuideUi.OverlayFill, shape)
            .border(1.dp, GuideUi.OverlayBorder, shape)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Dot(GuideUi.Live, 10.dp)
            Line(text, GuideUi.sans(26, FontWeight.SemiBold), Modifier.weight(1f), maxLines = 2)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CardButton(R.string.guide_reminder_switch, focused = !guarded && state.focused == 0, guarded = guarded, tag = REMINDER_SWITCH_TAG)
            CardButton(R.string.guide_reminder_dismiss, focused = !guarded && state.focused == 1, guarded = guarded, tag = REMINDER_DISMISS_TAG)
            Spacer(Modifier.weight(1f))
            Line(stringResource(R.string.guide_reminder_countdown, state.secondsLeft.coerceAtLeast(0)), GuideUi.mono(20, color = GuideUi.Fg50))
        }
    }
}

@Composable
private fun CardButton(label: Int, focused: Boolean, guarded: Boolean, tag: String) {
    Chip(
        focused = focused,
        modifier = Modifier
            .testTag(tag)
            .alpha(if (guarded) 0.5f else 1f)
            .semantics { if (guarded) disabled() },
        fill = GuideUi.White10,
        border = GuideUi.White16,
        shape = RoundedCornerShape(DesignTokens.Radii.Button),
        padding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
    ) { c -> Line(stringResource(label), GuideUi.sans(24, FontWeight.SemiBold, c), Modifier.padding(0.dp)) }
}
