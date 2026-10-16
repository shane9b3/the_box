package app.thebox.ui.parental

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.SourceSection
import app.thebox.core.parental.PinMessage
import app.thebox.core.parental.PinPadMode
import app.thebox.core.parental.PinPadModel
import app.thebox.core.parental.PinPromptState
import app.thebox.core.parental.PinPurpose
import app.thebox.core.parental.isEasyConfirm
import app.thebox.core.parental.isNotProtectedCard
import app.thebox.ui.R
import app.thebox.ui.guide.screen.Chip
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import kotlinx.coroutines.delay

const val PIN_PAD_TAG = "pin_pad"
const val PIN_DOTS_TAG = "pin_dots"
const val PIN_CARD_TAG = "pin_not_protected"

/** Card fill of every M5 overlay card (§3.1: `rgba(20,25,36,.96)`, no blur). */
internal val CardFill = Color(0xF5141924)
internal val CardBorder = Color.White.copy(alpha = 0.12f)
/** The lockout line's amber (§3.1). */
internal val Amber = Color(0xFFF2B25C)

/**
 * The PIN prompt (docs/design/m5-setup-settings.md §3.9.3; §3.1 "PIN pad": card 560 wide centred, four 22 dots with a
 * 24 gap, a 3 × 4 grid of 112 × 80 keys with a 12 gap, radius 14, mono 30, focused key FG fill; the lockout line 19
 * amber with a mono countdown; "Forgot the PIN?" 17 at .55). Sizes are design px (1.dp = 1 design px under
 * BoxDesignScale). In-tree, no Popup (§3.1). Drawn from [PinPromptState] only, which carries the **count** of digits,
 * never a digit; the key grid's labels are cleared from the semantics tree and the dots announce "n of 4" (§5.10).
 * The not-protected card ([NotProtectedCard]) and the "Easy to guess" confirm share the slot.
 */
@Composable
fun PinPadView(state: PinPromptState, modifier: Modifier = Modifier, nowMs: () -> Long = System::currentTimeMillis) {
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).testTag(PIN_PAD_TAG), contentAlignment = Alignment.Center) {
        when {
            state.isNotProtectedCard -> NotProtectedCard(state)
            state.isEasyConfirm -> EasyConfirmCard(state)
            else -> Pad(state, nowMs)
        }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier.width(560.dp).background(CardFill, shape).border(1.dp, CardBorder, shape).padding(32.dp),
        contentAlignment = Alignment.TopCenter,
    ) { content() }
}

@Composable
private fun Pad(state: PinPromptState, nowMs: () -> Long) {
    Card {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            val title = when (state.mode) {
                PinPadMode.VERIFY -> R.string.pin_title_verify
                PinPadMode.CREATE_FIRST -> R.string.pin_title_create
                PinPadMode.CREATE_CONFIRM -> R.string.pin_title_confirm
            }
            Line(stringResource(title), GuideUi.sans(28, FontWeight.SemiBold))
            val dotsDescription = stringResource(R.string.pin_digits_entered, state.filled)
            Row(
                Modifier.testTag(PIN_DOTS_TAG).clearAndSetSemantics { contentDescription = dotsDescription; liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                repeat(PinPadModel.PIN_LENGTH) { i ->
                    Box(Modifier.size(22.dp).background(if (i < state.filled) GuideUi.Fg else Color.White.copy(alpha = 0.16f), CircleShape))
                }
            }
            MessageLine(state, nowMs)
            KeyGrid(state)
            Line(stringResource(R.string.pin_forgot), GuideUi.sans(17, color = GuideUi.Fg55))
        }
    }
}

@Composable
private fun MessageLine(state: PinPromptState, nowMs: () -> Long) {
    val until = state.lockedUntilMs
    if (until != null) {
        var now by remember(until) { mutableLongStateOf(nowMs()) }
        LaunchedEffect(until) {
            while (now < until) {
                delay(1_000L - (now % 1_000L))
                now = nowMs()
            }
        }
        val left = ((until - now).coerceAtLeast(0L) + 999L) / 1000L
        val clock = String.format(java.util.Locale.ROOT, "%d:%02d", left / 60, left % 60)
        Line(stringResource(R.string.pin_locked_out, clock), GuideUi.mono(19, color = Amber), maxLines = 2)
        return
    }
    val text = when {
        state.checking -> stringResource(R.string.pin_checking)
        state.message == PinMessage.UNAVAILABLE -> stringResource(R.string.pin_unavailable)
        state.message == PinMessage.MISMATCH -> stringResource(R.string.pin_mismatch)
        state.message == PinMessage.STORE_FAILED -> stringResource(R.string.pin_store_failed)
        state.message?.startsWith(PinMessage.WRONG) == true -> when (PinMessage.triesLeft(state.message)) {
            1 -> stringResource(R.string.pin_wrong_last_try)
            null -> stringResource(R.string.pin_wrong)
            else -> stringResource(R.string.pin_wrong_tries, PinMessage.triesLeft(state.message) ?: 0)
        }
        else -> null
    }
    Box(Modifier.height(28.dp)) {
        if (text != null) Line(text, GuideUi.sans(19, color = if (state.wrong || state.message != null) Amber else GuideUi.Fg70), maxLines = 2)
    }
}

/** Keys 0–8 = 1–9, 9 = ⌫, 10 = 0, 11 = OK (`PinPromptState.focusedKey`). Labels are not in the semantics tree. */
@Composable
private fun KeyGrid(state: PinPromptState) {
    val padDescription = stringResource(R.string.pin_pad_description)
    val ok = stringResource(R.string.pin_key_ok)
    val disabled = state.lockedUntilMs != null || state.checking
    Column(Modifier.clearAndSetSemantics { contentDescription = padDescription }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in 0 until PinPadModel.ROWS) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (col in 0 until PinPadModel.COLS) {
                    val key = row * PinPadModel.COLS + col
                    val label = when (key) {
                        PinPadModel.KEY_DELETE -> "⌫"
                        PinPadModel.KEY_ZERO -> "0"
                        PinPadModel.KEY_OK -> ok
                        else -> (key + 1).toString()
                    }
                    PadKey(label, focused = key == state.focusedKey, dim = disabled && key != PinPadModel.KEY_DELETE)
                }
            }
        }
    }
}

@Composable
private fun PadKey(label: String, focused: Boolean, dim: Boolean) {
    val shape = RoundedCornerShape(14.dp)
    val fill = if (focused) GuideUi.Fg else Color.White.copy(alpha = 0.08f)
    var m = Modifier.size(112.dp, 80.dp).background(fill, shape)
    if (focused) m = m.border(GuideUi.FocusRing, GuideUi.Accent, shape)
    Box(m, contentAlignment = Alignment.Center) {
        val color = when {
            focused -> GuideUi.Canvas
            dim -> GuideUi.Fg.copy(alpha = 0.35f)
            else -> GuideUi.Fg
        }
        Line(label, GuideUi.mono(30, FontWeight.Medium, color))
    }
}

/** FILTERED_NO_PIN (B3): "This group is not protected. [Set a PIN] [Open anyway]"; `focusedKey` is the button. */
@Composable
fun NotProtectedCard(state: PinPromptState) {
    Card(Modifier.testTag(PIN_CARD_TAG)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val channel = state.purpose is PinPurpose.Tune ||
                (state.purpose as? PinPurpose.OpenSource)?.entry?.section?.let { it != SourceSection.GROUP } == true
            Line(stringResource(if (channel) R.string.pin_np_title_channel else R.string.pin_np_title), GuideUi.sans(30, FontWeight.SemiBold), maxLines = 2)
            Line(stringResource(R.string.pin_np_body), GuideUi.sans(21, color = GuideUi.Fg.copy(alpha = 0.8f)), maxLines = 4)
            Buttons(listOf(stringResource(R.string.pin_np_set), stringResource(R.string.pin_np_open)), state.focusedKey)
        }
    }
}

@Composable
private fun EasyConfirmCard(state: PinPromptState) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Line(stringResource(R.string.pin_easy_title), GuideUi.sans(30, FontWeight.SemiBold), maxLines = 2)
            Buttons(listOf(stringResource(R.string.pin_easy_use), stringResource(R.string.pin_easy_another)), state.focusedKey)
        }
    }
}

/** Buttons as the error card's (19/600, padding 14 × 26, radius 14). */
@Composable
internal fun Buttons(labels: List<String>, focused: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        labels.forEachIndexed { i, label ->
            Chip(
                focused = i == focused,
                fill = Color.White.copy(alpha = 0.10f),
                border = Color.White.copy(alpha = 0.16f),
                shape = RoundedCornerShape(14.dp),
                padding = PaddingValues(horizontal = 26.dp, vertical = 14.dp),
            ) { color -> Line(label, GuideUi.sans(19, FontWeight.SemiBold, color)) }
        }
    }
}
