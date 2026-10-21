package app.thebox.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.editor.ChannelEditorMachine
import app.thebox.core.editor.ChoiceKind
import app.thebox.core.editor.ConfirmKind
import app.thebox.core.editor.EditorChoice
import app.thebox.core.editor.EditorConfirm
import app.thebox.core.editor.EditorFilter
import app.thebox.core.editor.EditorGroupRow
import app.thebox.core.editor.EditorMenu
import app.thebox.core.editor.EditorMenus
import app.thebox.core.editor.EditorText
import app.thebox.core.editor.EditorTexts
import app.thebox.core.editor.EditorWindow
import app.thebox.core.editor.GroupPicker
import app.thebox.core.editor.MenuLine
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.settings.ConfirmCardView
import app.thebox.ui.settings.SettingsUi

const val EDITOR_MENU_TAG = "editor_menu"
const val EDITOR_MENU_LINE_TAG = "editor_menu_line:"
const val EDITOR_MENU_TITLE_TAG = "editor_menu_title"
const val EDITOR_PICKER_TAG = "editor_picker"
const val EDITOR_PICKER_ROW_TAG = "editor_picker_row:"
const val EDITOR_CHOICE_TAG = "editor_choice"
const val EDITOR_CHOICE_OPTION_TAG = "editor_choice_option:"

private val cardShape = RoundedCornerShape(SettingsUi.CARD_RADIUS.dp)
private fun Modifier.card(): Modifier = background(SettingsUi.CardFill, cardShape).border(1.dp, SettingsUi.CardBorder, cardShape)

/** A line's height in the action menu: 60, or 84 with a disabled reason under the label. */
fun menuLineHeight(line: MenuLine): Int = if (!line.enabled && line.reason != null) EditorUi.MENU_LINE_REASON_H else EditorUi.MENU_LINE_H

/** The action menu card's height for [menu] (padding, title, lines). */
fun menuHeight(menu: EditorMenu): Int = 2 * EditorUi.MENU_PAD + EditorUi.MENU_TITLE_H + menu.lines.sumOf { menuLineHeight(it) }

/**
 * The menu's top (design px) for a focused row whose top is at [rowY] (absolute): at the row, clamped so the card stays
 * inside the pane, as the settings `menuTop` rule; a card taller than the pane starts near the top of the canvas.
 */
fun editorMenuTop(rowY: Int, height: Int): Int {
    val paneBottom = EditorUi.PANE_Y + EditorUi.PANE_H
    return minOf(rowY, paneBottom - height).coerceAtLeast(MENU_MIN_TOP)

}

private const val MENU_MIN_TOP = 16

/**
 * The action menu (§2.2 "Action menu"): 480 wide, the confirm card's fill, 1 px `.12` border, radius 24, padding 16;
 * title 22/600 `.7`; lines 60, label 26, count mono 22 `.55` right; disabled lines at `.4` with their reason under the
 * label (20 `.55`). While the summary loads the title is "…" and there are no lines. [reasonArg] fills a reason's
 * `%1$s` (the group of "Locked by its group, {group}").
 */
@Composable
fun EditorMenuCard(menu: EditorMenu, texts: EditorTexts, reasonArg: String, modifier: Modifier = Modifier) {
    Column(modifier.width(EditorUi.MENU_W.dp).testTag(EDITOR_MENU_TAG).card().padding(EditorUi.MENU_PAD.dp)) {
        val title = if (menu.loading) stringResource(R.string.editor_ui_loading) else menu.title
        Box(Modifier.fillMaxWidth().height(EditorUi.MENU_TITLE_H.dp).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
            Line(title, GuideUi.sans(22, FontWeight.SemiBold, SettingsUi.Fg70), Modifier.testTag(EDITOR_MENU_TITLE_TAG))
        }
        menu.lines.forEachIndexed { i, line ->
            MenuLineView(line, label = texts.get(EditorMenus.labelOf(line, menu.pane)), reason = line.reason?.let { reasonText(it, texts, reasonArg) }, focused = i == menu.focused, index = i)
        }
    }
}

/** A disabled reason's text; the only one with an argument is D_LOCKED_BY_GROUP. */
fun reasonText(key: EditorText, texts: EditorTexts, arg: String): String =
    if (key == EditorText.D_LOCKED_BY_GROUP) texts.get(key, arg) else texts.get(key)

@Composable
private fun MenuLineView(line: MenuLine, label: String, reason: String?, focused: Boolean, index: Int) {
    val rs = RoundedCornerShape(SettingsUi.ROW_RADIUS.dp)
    val alpha = if (line.enabled) 1f else SettingsUi.DISABLED_ALPHA
    val text = (if (focused) SettingsUi.Canvas else SettingsUi.Fg).copy(alpha = alpha)
    var m = Modifier.fillMaxWidth().height(menuLineHeight(line).dp).testTag(EDITOR_MENU_LINE_TAG + index)
        .semantics { selected = focused; contentDescription = label }
    if (focused) m = m.border(SettingsUi.FOCUS_RING.dp, SettingsUi.Accent, rs).background(SettingsUi.Fg, rs)
    Row(m.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Line(label, SettingsUi.OptionLabel.copy(color = text))
            if (!line.enabled && reason != null) {
                // The reason stays readable on a disabled line: `.55` of the row colour, not of the dimmed label.
                val base = if (focused) SettingsUi.Canvas else SettingsUi.Fg
                Line(reason, GuideUi.sans(20, color = base.copy(alpha = 0.55f)), Modifier.padding(top = 2.dp))
            }
        }
        line.count?.let { Line(formatCount(it), GuideUi.mono(22, color = text.copy(alpha = text.alpha * 0.55f)), Modifier.padding(start = 16.dp)) }
    }
}

/**
 * The Move-to-group picker (E4, §2.2): a 720-wide card at (600, 160), title 30/600 "Move 3 channels to", rows 64 from
 * the shared group window, [ChannelEditorMachine.VISIBLE_ROWS] from the picker's top. The current group says "Already
 * here" (dim); a hidden target says what that means.
 */
@Composable
fun GroupPickerCard(picker: GroupPicker, groups: EditorWindow<EditorGroupRow>?, texts: EditorTexts, modifier: Modifier = Modifier) {
    Column(modifier.width(EditorUi.PICKER_W.dp).testTag(EDITOR_PICKER_TAG).card().padding(EditorUi.PICKER_PAD.dp)) {
        Line(texts.count(EditorText.PICKER_TITLE, picker.moving), EditorUi.PaneTitle, Modifier.padding(start = 20.dp, bottom = 16.dp))
        val count = groups?.count ?: 0
        val end = minOf(count, picker.top + ChannelEditorMachine.VISIBLE_ROWS)
        for (i in picker.top until end) {
            val row = groups?.let { rowOf(it, i) }
            val focused = i == picker.cursor
            if (row == null) {
                Box(Modifier.fillMaxWidth().height(EditorUi.PICKER_ROW_H.dp).testTag(EDITOR_PICKER_ROW_TAG + i).semantics { selected = focused }
                    .background(SettingsUi.White03, RoundedCornerShape(SettingsUi.ROW_RADIUS.dp)))
            } else {
                PickerRow(row, here = row.groupKey == picker.fromGroupKey, focused = focused, index = i, texts = texts)
            }
        }
    }
}

@Composable
private fun PickerRow(row: EditorGroupRow, here: Boolean, focused: Boolean, index: Int, texts: EditorTexts) {
    val rs = RoundedCornerShape(SettingsUi.ROW_RADIUS.dp)
    val alpha = if (here) SettingsUi.DISABLED_ALPHA else 1f
    val text = (if (focused) SettingsUi.Canvas else SettingsUi.Fg).copy(alpha = alpha)
    var m = Modifier.fillMaxWidth().height(EditorUi.PICKER_ROW_H.dp).testTag(EDITOR_PICKER_ROW_TAG + index)
        .semantics { selected = focused; contentDescription = row.name }
    if (focused) m = m.border(SettingsUi.FOCUS_RING.dp, SettingsUi.Accent, rs).background(SettingsUi.Fg, rs)
    Row(m.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Line(row.name, SettingsUi.OptionLabel.copy(color = text))
            if (row.hidden && !here) Line(texts.get(EditorText.PICKER_HIDDEN_DETAIL), GuideUi.sans(20, color = text.copy(alpha = text.alpha * 0.55f)))
        }
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (here) {
                Line(texts.get(EditorText.PICKER_HERE), EditorUi.Detail.copy(color = text.copy(alpha = text.alpha * 0.65f)))
            } else {
                GroupBadges(row, text, onLight = focused)
                Line(formatCount(row.channels), GuideUi.mono(22, color = text.copy(alpha = text.alpha * 0.65f)))
            }
        }
    }
}

/** The labels of a choice list's options (E6). */
fun choiceOptions(kind: ChoiceKind, texts: EditorTexts): List<String> = when (kind) {
    ChoiceKind.FILTER -> listOf(EditorText.FILTER_ALL, EditorText.FILTER_SHOWN, EditorText.FILTER_HIDDEN)
    ChoiceKind.CHANNEL_SORT -> listOf(EditorText.SORT_NAME_AZ, EditorText.SORT_NAME_ZA, EditorText.SORT_NUMBER, EditorText.SORT_PROVIDER)
    ChoiceKind.GROUP_SORT -> listOf(EditorText.SORT_NAME_AZ, EditorText.SORT_PROVIDER)
}.map { texts.get(it) }

/**
 * The toolbar's choice list (E6): the settings inline option look (radio 18, option 26, rows 60) on a card drawn under
 * the chip, over the rows. Show… marks the current [filter]; the sorts are one-shot actions with no current choice.
 */
@Composable
fun EditorChoiceCard(choice: EditorChoice, filter: EditorFilter, texts: EditorTexts, modifier: Modifier = Modifier) {
    val selected = if (choice.kind == ChoiceKind.FILTER) filter.ordinal else -1
    Column(modifier.width(EditorUi.CHOICE_W.dp).testTag(EDITOR_CHOICE_TAG).card().padding(12.dp)) {
        choiceOptions(choice.kind, texts).forEachIndexed { i, label ->
            val focused = i == choice.focused
            val rs = RoundedCornerShape(SettingsUi.ROW_RADIUS.dp)
            val text = if (focused) SettingsUi.Canvas else SettingsUi.Fg
            var m = Modifier.fillMaxWidth().height(SettingsUi.CHOICE_ROW_H.dp).testTag(EDITOR_CHOICE_OPTION_TAG + i)
                .semantics { this.selected = focused; stateDescription = if (i == selected) "checked" else "unchecked"; contentDescription = label }
            if (focused) m = m.border(SettingsUi.FOCUS_RING.dp, SettingsUi.Accent, rs).background(SettingsUi.Fg, rs)
            Row(m.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                val d = SettingsUi.RADIO.dp
                if (i == selected) Box(Modifier.size(d).background(SettingsUi.Accent, CircleShape))
                else Box(Modifier.size(d).border(2.dp, text.copy(alpha = 0.45f), CircleShape))
                Spacer(Modifier.width(18.dp))
                Line(label, SettingsUi.OptionLabel.copy(color = text))
            }
        }
    }
}

/** Title, body and buttons of a confirm (§5.1 C_*); the safe button (Cancel) is index 1, where focus starts. */
fun confirmTexts(c: EditorConfirm, texts: EditorTexts): Triple<String, String, List<String>> {
    val arg = c.args.firstOrNull() ?: ""
    val n = arg.toIntOrNull() ?: 0
    val cancel = texts.get(EditorText.CANCEL)
    // A group sort (Sort groups…) has texts of its own; a channel sort names its group.
    val groups = c.pending is app.thebox.core.editor.EditorWrite.SortGroups
    return when (c.kind) {
        ConfirmKind.SORT_REPLACES_ORDER -> if (groups) {
            Triple(texts.get(EditorText.C_SORT_GROUPS_TITLE), texts.get(EditorText.C_SORT_GROUPS_BODY), listOf(texts.get(EditorText.C_CONFIRM_SORT), cancel))
        } else {
            Triple(texts.get(EditorText.C_SORT_TITLE, arg), texts.get(EditorText.C_SORT_BODY), listOf(texts.get(EditorText.C_CONFIRM_SORT), cancel))
        }
        ConfirmKind.PROVIDER_ORDER -> Triple(
            texts.get(EditorText.C_PROVIDER_TITLE),
            if (groups) texts.get(EditorText.C_PROVIDER_GROUPS_BODY) else texts.get(EditorText.C_PROVIDER_BODY, arg),
            listOf(texts.get(EditorText.C_CONFIRM_SORT), cancel),
        )
        ConfirmKind.RESET_CHANNELS -> Triple(texts.count(EditorText.C_RESET_TITLE, n), texts.get(EditorText.C_RESET_BODY), listOf(texts.get(EditorText.C_CONFIRM_RESET), cancel))
        ConfirmKind.RESET_GROUPS -> Triple(texts.count(EditorText.C_RESET_GROUPS_TITLE, n), texts.get(EditorText.C_RESET_GROUPS_BODY), listOf(texts.get(EditorText.C_CONFIRM_RESET), cancel))
    }
}

/** The confirm card (§2.2): the settings `ConfirmCard` look, 720 wide, centred. */
@Composable
fun EditorConfirmCard(confirm: EditorConfirm, texts: EditorTexts, modifier: Modifier = Modifier) {
    val (title, body, buttons) = confirmTexts(confirm, texts)
    ConfirmCardView(title, body, buttons, confirm.focused, modifier)
}

/** Row [i] of a paged window, or null when its page is not loaded. */
fun <T> rowOf(w: EditorWindow<T>, i: Int): T? =
    if (i < 0 || i >= w.count) null else w.pages[i / EditorWindow.PAGE]?.getOrNull(i % EditorWindow.PAGE)
