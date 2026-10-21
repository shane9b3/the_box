package app.thebox.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.settings.NumberPadState
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line

const val NUMBER_PAD_TAG = "settings_number_pad"
const val NUMBER_PAD_KEY_TAG = "settings_pad_key:"
const val NUMBER_PAD_VALUE_TAG = "settings_pad_value"

/** Key index → label: 0–8 = "1"…"9", 9 = ⌫, 10 = "0", 11 = OK (the machine's `NumberPadState.focusedKey` grid, 3 × 4). */
const val PAD_KEY_DELETE = 9
const val PAD_KEY_ZERO = 10
const val PAD_KEY_OK = 11

/**
 * The numeric pad (§3.1, decision §0.2): 560 wide card, title 28/600, value mono 44, a 3 × 4 grid of 112 × 80 keys (gap
 * 12, radius 14, mono 30); the focused key is FG-filled. A pure renderer: the machine moves [NumberPadState.focusedKey]
 * and edits [NumberPadState.digits]. Never the IME (§0.2).
 */
@Composable
fun NumberPad(state: NumberPadState, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(SettingsUi.CARD_RADIUS.dp)
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(SettingsUi.PAD_W.dp)
                .testTag(NUMBER_PAD_TAG)
                .background(SettingsUi.CardFill, shape)
                .border(1.dp, SettingsUi.CardBorder, shape)
                .padding(SettingsUi.CARD_PAD.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SettingsUi.PAD_GAP.dp),
        ) {
            Line(state.label, GuideUi.sans(34, FontWeight.SemiBold))
            Line(state.digits.ifEmpty { " " }, GuideUi.mono(44), Modifier.testTag(NUMBER_PAD_VALUE_TAG))
            state.error?.let { Line(it, GuideUi.sans(24, color = SettingsUi.Amber), maxLines = 2) }
            for (r in 0 until 4) {
                Row(horizontalArrangement = Arrangement.spacedBy(SettingsUi.PAD_GAP.dp)) {
                    for (c in 0 until 3) {
                        val k = r * 3 + c
                        PadKey(padKeyLabel(k), k == state.focusedKey, k)
                    }
                }
            }
        }
    }
}

@Composable
fun padKeyLabel(k: Int): String = when (k) {
    PAD_KEY_DELETE -> stringResource(R.string.settings_ui_pad_delete)
    PAD_KEY_ZERO -> "0"
    PAD_KEY_OK -> stringResource(R.string.settings_ui_pad_ok)
    else -> (k + 1).toString()
}

@Composable
private fun PadKey(label: String, focused: Boolean, index: Int) {
    val shape = RoundedCornerShape(14.dp)
    var m = Modifier.size(SettingsUi.PAD_KEY_W.dp, SettingsUi.PAD_KEY_H.dp).testTag(NUMBER_PAD_KEY_TAG + index).semantics { selected = focused }
    m = if (focused) m.border(SettingsUi.FOCUS_RING.dp, SettingsUi.Accent, shape).background(SettingsUi.Fg, shape)
    else m.background(SettingsUi.White08, shape)
    Box(m, contentAlignment = Alignment.Center) {
        Line(label, GuideUi.mono(36, color = if (focused) SettingsUi.Canvas else SettingsUi.Fg))
    }
}
