package app.thebox.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.thebox.core.settings.ConfirmState
import app.thebox.core.settings.MenuState
import app.thebox.ui.guide.screen.Chip
import app.thebox.ui.guide.screen.Line

const val SETTINGS_CONFIRM_TAG = "settings_confirm"
const val SETTINGS_CONFIRM_BUTTON_TAG = "settings_confirm_button:"
const val SETTINGS_MENU_TAG = "settings_menu"
const val SETTINGS_MENU_OPTION_TAG = "settings_menu_option:"

/**
 * The confirm card (§3.1): centred, 720 wide, `rgba(20,25,36,.96)` (no blur), 1 px `.12` border, radius 24, padding 32.
 * Buttons [confirmLabel] (index 0) and [cancelLabel] (index 1); the machine starts focus on the safe one (1). In-tree,
 * never a Dialog.
 */
@Composable
fun ConfirmCard(state: ConfirmState, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(SettingsUi.CARD_RADIUS.dp)
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(SettingsUi.CONFIRM_W.dp)
                .testTag(SETTINGS_CONFIRM_TAG)
                .background(SettingsUi.CardFill, shape)
                .border(1.dp, SettingsUi.CardBorder, shape)
                .padding(SettingsUi.CARD_PAD.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Line(state.spec.title, SettingsUi.CardTitle, maxLines = 2)
            Text(state.spec.body, style = SettingsUi.CardBody)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CardButton(state.spec.confirmLabel, state.focused == 0, 0)
                CardButton(state.spec.cancelLabel, state.focused == 1, 1)
            }
        }
    }
}

@Composable
private fun CardButton(label: String, focused: Boolean, index: Int) {
    Chip(
        focused = focused,
        modifier = Modifier.testTag(SETTINGS_CONFIRM_BUTTON_TAG + index).semantics { selected = focused },
        fill = SettingsUi.White12,
        border = SettingsUi.White16,
        shape = RoundedCornerShape(14.dp),
        padding = PaddingValues(horizontal = 26.dp, vertical = 14.dp),
    ) { c -> Line(label, SettingsUi.Button.copy(color = c)) }
}

/** The item context menu (§3.1): 420 wide, the confirm card's fill, rows 60, anchored right of the item. */
@Composable
fun SettingsMenu(state: MenuState, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(SettingsUi.CARD_RADIUS.dp)
    Column(
        modifier
            .width(SettingsUi.MENU_W.dp)
            .testTag(SETTINGS_MENU_TAG)
            .background(SettingsUi.CardFill, shape)
            .border(1.dp, SettingsUi.CardBorder, shape)
            .padding(12.dp),
    ) {
        state.options.forEachIndexed { i, o ->
            val f = i == state.focused
            val rs = RoundedCornerShape(SettingsUi.ROW_RADIUS.dp)
            val text = (if (f) SettingsUi.Canvas else SettingsUi.Fg).copy(alpha = if (o.enabled) 1f else SettingsUi.DISABLED_ALPHA)
            var m = Modifier.fillMaxWidth().height(SettingsUi.MENU_ROW_H.dp).testTag(SETTINGS_MENU_OPTION_TAG + i).semantics { selected = f }
            if (f) m = m.border(SettingsUi.FOCUS_RING.dp, SettingsUi.Accent, rs).background(SettingsUi.Fg, rs)
            Box(m.padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) { Line(o.label, SettingsUi.OptionLabel.copy(color = text)) }
        }
    }
}
