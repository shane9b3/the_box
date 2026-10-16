package app.thebox.ui.settings

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.thebox.core.settings.ChoiceOption
import app.thebox.core.settings.ChoiceState
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsRow
import app.thebox.ui.R
import app.thebox.ui.guide.screen.Line

/** Test tags (`settings_row:{id}`, `settings_option:{id}:{index}`, `settings_section:{index}`). */
const val SETTINGS_ROW_TAG = "settings_row:"
const val SETTINGS_OPTION_TAG = "settings_option:"
const val SETTINGS_SECTION_TAG = "settings_section:"
const val SETTINGS_ADVANCED_TAG = "settings_advanced"
const val SETTINGS_PLACEHOLDER_ROW_TAG = "settings_row_placeholder:"

/** Height of [row] (§3.1): 76 one line, 104 with a description (or a disabled reason), 72 for list items. */
fun rowHeight(row: SettingsRow): Int = when {
    secondLine(row) != null -> SettingsUi.ROW_H_DESC
    row.kind is RowKind.Item -> SettingsUi.ITEM_ROW_H
    else -> SettingsUi.ROW_H
}

/** The line under the label: the disabled reason replaces the description while the row is disabled. */
fun secondLine(row: SettingsRow): String? =
    if (!row.enabled && row.disabledReason != null) row.disabledReason else row.description

/** The value text on the right (§3.1: `.65`; the Choice/MultiChoice selection; masked text as dots). */
@Composable
fun valueText(row: SettingsRow): String? {
    row.value?.let { return it }
    return when (val k = row.kind) {
        is RowKind.Choice -> k.options.getOrNull(k.selected)?.label
        is RowKind.MultiChoice -> k.selected.sorted().mapNotNull { k.options.getOrNull(it)?.label }.joinToString(", ").ifEmpty { null }
        is RowKind.Text -> if (k.masked && k.value.isNotEmpty()) stringResource(R.string.settings_ui_masked) else k.value.ifEmpty { null }
        is RowKind.Number -> if (k.unit != null) "${k.value} ${k.unit}" else k.value.toString()
        // (M5 integration) An Item's `primary` is the id of the action OK runs (contract §2.1), not text: the device pass
        // showed "restore" as the value of WP-D's Downloads entries.
        is RowKind.Item -> null
        else -> null
    }
}

/**
 * One settings row and, when [choice] is open on it, its inline option list (the list pushes the rows below down; product
 * S11 "not a modal"). Pure renderer: [focused] and [choice] come from `SettingsUiState`.
 */
@Composable
fun SettingsRowView(row: SettingsRow, focused: Boolean, choice: ChoiceState?, moving: Boolean, modifier: Modifier = Modifier) {
    // (M5 integration) The lifted row of move mode is WP-D's MoveModeRow (§3.2.2).
    if (moving) {
        MoveModeRow(row.label, valueText(row), modifier.testTag(SETTINGS_ROW_TAG + row.id.key))
        return
    }
    Column(modifier.fillMaxWidth()) {
        RowBody(row, focused && choice == null, moving)
        if (choice != null && choice.row == row.id) {
            when (val k = row.kind) {
                is RowKind.Choice -> OptionList(row, k.options, selected = setOf(k.selected), focused = choice.focused, multi = false)
                is RowKind.MultiChoice -> OptionList(row, k.options, selected = k.selected, focused = choice.focused, multi = true)
                else -> Unit
            }
        }
    }
}

@Composable
private fun RowBody(row: SettingsRow, focused: Boolean, moving: Boolean) {
    val shape = RoundedCornerShape(SettingsUi.ROW_RADIUS.dp)
    val alpha = if (row.enabled) 1f else SettingsUi.DISABLED_ALPHA
    val base = if (focused) SettingsUi.Canvas else SettingsUi.Fg
    val text = base.copy(alpha = alpha)
    var m = Modifier
        .fillMaxWidth()
        .height(rowHeight(row).dp)
        .testTag(SETTINGS_ROW_TAG + row.id.key)
        .semantics { selected = focused; contentDescription = row.label }
    m = when {
        focused -> m.border(SettingsUi.FOCUS_RING.dp, SettingsUi.Accent, shape).background(SettingsUi.Fg, shape)
        moving -> m.border(1.dp, SettingsUi.Accent, shape).background(SettingsUi.White12, shape)
        else -> m
    }
    Row(m.padding(horizontal = SettingsUi.ROW_PAD_H.dp), verticalAlignment = Alignment.CenterVertically) {
        // (M5 integration, WP-E §1.8a) The About header card carries the wordmark.
        if (row.id == app.thebox.core.settings.tree.AboutRows.HEADER) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(R.drawable.wordmark), contentDescription = null,
                modifier = Modifier.padding(end = 32.dp).width(WORDMARK_W.dp),
            )
        }
        if (moving) {
            Line(stringResource(R.string.settings_ui_move_glyph), SettingsUi.RowLabel.copy(color = text), Modifier.padding(end = 16.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Line(row.label, SettingsUi.RowLabel.copy(color = if (focused) text else SettingsUi.Fg92.copy(alpha = 0.92f * alpha)))
            secondLine(row)?.let { Line(it, SettingsUi.RowDesc.copy(color = text.copy(alpha = 0.55f * alpha)), Modifier.padding(top = 4.dp)) }
            (row.kind as? RowKind.Info)?.let { info ->
                if (info.lines.isNotEmpty()) {
                    val tone = if (focused) text else SettingsUi.toneColor(info.tone).copy(alpha = alpha)
                    Line(info.lines.joinToString("  ·  "), SettingsUi.RowDesc.copy(color = tone), Modifier.padding(top = 4.dp))
                }
            }
        }
        Trailing(row, focused, text)
    }
}

@Composable
private fun Trailing(row: SettingsRow, focused: Boolean, text: Color) {
    val value = valueText(row)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        (row.kind as? RowKind.Item)?.let { item -> item.badges.sortedBy { it.ordinal }.forEach { BadgeChip(stringResource(SettingsUi.badgeLabel(it)), text) } }
        if (value != null && row.kind !is RowKind.Toggle) {
            val style = if (SettingsUi.isMonoValue(value)) SettingsUi.RowValueMono else SettingsUi.RowValue
            Text(
                value,
                style = style.copy(color = text.copy(alpha = text.alpha * 0.65f)),
                maxLines = 1,
                overflow = if (value.contains("://") || value.contains('.')) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
                modifier = Modifier.width(VALUE_MAX_W.dp).padding(start = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        when (val k = row.kind) {
            is RowKind.Toggle -> TogglePill(k.on, Modifier.semantics { stateDescription = if (k.on) "on" else "off" })
            is RowKind.Link -> Line(stringResource(R.string.settings_ui_chevron), SettingsUi.RowLabel.copy(color = text.copy(alpha = text.alpha * 0.45f)))
            is RowKind.Item -> if (k.route != null) Line(stringResource(R.string.settings_ui_chevron), SettingsUi.RowLabel.copy(color = text.copy(alpha = text.alpha * 0.45f)))
            is RowKind.Action -> if (k.destructive) Dot(SettingsUi.Bad.copy(alpha = text.alpha), 10)
            else -> Unit
        }
    }
}

private const val VALUE_MAX_W = 560
/** The wordmark on the About header row (design px). */
private const val WORDMARK_W = 200

@Composable
private fun Dot(color: Color, size: Int) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

/** The kit's toggle (§3.1): a 56 × 30 pill; on = accent fill, knob right; off = `.16` fill, knob left. Colours stay on a focused row. */
@Composable
fun TogglePill(on: Boolean, modifier: Modifier = Modifier) {
    val pill = RoundedCornerShape(SettingsUi.TOGGLE_H.dp)
    Box(
        modifier
            .size(SettingsUi.TOGGLE_W.dp, SettingsUi.TOGGLE_H.dp)
            .background(if (on) SettingsUi.Accent else SettingsUi.White16, pill)
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(SettingsUi.TOGGLE_KNOB.dp).background(SettingsUi.Fg, CircleShape)) }
}

@Composable
private fun BadgeChip(label: String, text: Color) {
    Box(
        Modifier
            .border(1.dp, text.copy(alpha = 0.22f), RoundedCornerShape(SettingsUi.BADGE_RADIUS.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) { Line(label, SettingsUi.BadgeText.copy(color = text.copy(alpha = 0.8f))) }
}

/** The inline option list (§3.1): indent 48, rows 60, radio dot 18 (or a check box for MultiChoice). */
@Composable
private fun OptionList(row: SettingsRow, options: List<ChoiceOption>, selected: Set<Int>, focused: Int, multi: Boolean) {
    Column(Modifier.fillMaxWidth().padding(start = SettingsUi.CHOICE_INDENT.dp, top = SettingsUi.ROW_GAP.dp)) {
        options.forEachIndexed { i, o ->
            val isFocused = i == focused
            val isSelected = i in selected
            val shape = RoundedCornerShape(SettingsUi.ROW_RADIUS.dp)
            val alpha = if (o.enabled) 1f else SettingsUi.DISABLED_ALPHA
            val text = (if (isFocused) SettingsUi.Canvas else SettingsUi.Fg).copy(alpha = alpha)
            // The machine's `top` counts rows only, so the focused option of a long list (guide shift: 49) scrolls itself
            // into view inside the row item.
            val bring = remember { BringIntoViewRequester() }
            if (isFocused) LaunchedEffect(focused) { runCatching { bring.bringIntoView() } }
            var m = Modifier
                .fillMaxWidth()
                .height(SettingsUi.CHOICE_ROW_H.dp)
                .bringIntoViewRequester(bring)
                .testTag("$SETTINGS_OPTION_TAG${row.id.key}:$i")
                // `selected` marks focus (as on rows); the chosen state is the radio/check and the state description.
                .semantics { this.selected = isFocused; stateDescription = if (isSelected) "checked" else "unchecked"; contentDescription = o.label }
            if (isFocused) m = m.border(SettingsUi.FOCUS_RING.dp, SettingsUi.Accent, shape).background(SettingsUi.Fg, shape)
            Row(m.padding(horizontal = SettingsUi.ROW_PAD_H.dp), verticalAlignment = Alignment.CenterVertically) {
                if (multi) CheckBox(isSelected, text) else Radio(isSelected, text)
                Spacer(Modifier.width(18.dp))
                Line(o.label, SettingsUi.OptionLabel.copy(color = text))
                o.detail?.let { Line(it, SettingsUi.OptionDetail.copy(color = text.copy(alpha = 0.55f * alpha)), Modifier.padding(start = 16.dp)) }
            }
        }
    }
}

@Composable
private fun Radio(selected: Boolean, ring: Color) {
    val d = SettingsUi.RADIO.dp
    if (selected) Box(Modifier.size(d).background(SettingsUi.Accent, CircleShape))
    else Box(Modifier.size(d).border(2.dp, ring.copy(alpha = 0.45f), CircleShape))
}

@Composable
private fun CheckBox(checked: Boolean, ring: Color) {
    val d = SettingsUi.RADIO.dp
    val shape = RoundedCornerShape(4.dp)
    if (!checked) {
        Box(Modifier.size(d).border(2.dp, ring.copy(alpha = 0.45f), shape))
        return
    }
    Canvas(Modifier.size(d).background(SettingsUi.Accent, shape)) {
        val w = size.width
        drawLine(SettingsUi.Fg, Offset(w * 0.22f, w * 0.52f), Offset(w * 0.42f, w * 0.72f), strokeWidth = w * 0.14f, cap = StrokeCap.Round)
        drawLine(SettingsUi.Fg, Offset(w * 0.42f, w * 0.72f), Offset(w * 0.78f, w * 0.30f), strokeWidth = w * 0.14f, cap = StrokeCap.Round)
    }
}

/** An unloaded row of a paged list (§3.2.1): row height, `.03` fill. */
@Composable
fun PlaceholderRow(index: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(SettingsUi.ITEM_ROW_H.dp)
            .testTag(SETTINGS_PLACEHOLDER_ROW_TAG + index)
            .background(SettingsUi.White03, RoundedCornerShape(SettingsUi.ROW_RADIUS.dp)),
    )
}
