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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
const val SETTINGS_PLACEHOLDER_ROW_TAG = "settings_row_placeholder:"

/** Height of [row] (§3.1): 76 one line, 104 with a description (or a disabled reason), 72 for list items. */
fun rowHeight(row: SettingsRow): Int = when {
    // (WP-S4, storage locations §7.7, §9.4) The Test's progress row has its track; an Info row of the storage pages shows each
    // next-step line whole, one per line.
    row.id == PROGRESS_ROW -> SettingsUi.PROGRESS_ROW_H
    isStorageInfo(row) -> maxOf(SettingsUi.ROW_H_DESC, 56 + SettingsUi.STORAGE_INFO_LINE_H * (row.kind as RowKind.Info).lines.size)
    secondLine(row) != null -> SettingsUi.ROW_H_DESC
    row.kind is RowKind.Item -> SettingsUi.ITEM_ROW_H
    else -> SettingsUi.ROW_H
}

/** (WP-S4) The Test's progress Info row and the Find page's searching row: `:core`'s ids (Q-U9, closure fix pass). */
internal val PROGRESS_ROW = app.thebox.core.settings.state.StoragePages.ROW_T_PROGRESS
internal val SEARCHING_ROW = app.thebox.core.settings.state.StoragePages.ROW_SEARCHING
internal val INTERVAL_ROW = app.thebox.core.settings.tree.SportsRows.INTERVAL
/**
 * (Fix pass 4) The focused interval row's detail: the longest (a fixed 5 s on the fallback while slowed: recommendation,
 * faster, slowed, fallback) needs about 5–6 lines at the row's width; the league line is dropped when those show.
 */
internal const val INTERVAL_DESC_LINES = 6
/** The focused interval row's top and bottom padding once it grows past one line of detail. */
private const val INTERVAL_PAD_V = 14

/** The description's line cap: the focused interval row wraps to [INTERVAL_DESC_LINES]; every other row is one line. */
internal fun descLines(row: SettingsRow, focused: Boolean): Int = if (focused && row.id == INTERVAL_ROW) INTERVAL_DESC_LINES else 1

/** An Info row of the storage pages with lines under its label. */
internal fun isStorageInfo(row: SettingsRow): Boolean =
    row.id.key.startsWith("storage.") && (row.kind as? RowKind.Info)?.lines?.isNotEmpty() == true

/** The line under the label: the disabled reason replaces the description while the row is disabled. */
fun secondLine(row: SettingsRow): String? =
    if (!row.enabled && row.disabledReason != null) row.disabledReason else row.description

/** The value text on the right (§3.1: `.65`; the Choice/MultiChoice selection; masked text as dots). */
@Composable
fun valueText(row: SettingsRow): String? {
    row.value?.let { return app.thebox.core.settings.state.StorageMarks.plain(it) }
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
fun SettingsRowView(row: SettingsRow, focused: Boolean, choice: ChoiceState?, moving: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
    // (M5 integration) The lifted row of move mode is WP-D's MoveModeRow (§3.2.2).
    if (moving) {
        // (Settings panel S9) In the panel it takes the row's width; its hint is in the band.
        if (compact) Box(modifier.testTag(SETTINGS_ROW_TAG + row.id.key)) { MoveModeRow(row.label, valueText(row), width = SettingsPanelUi.INNER_W, compact = true) }
        else MoveModeRow(row.label, valueText(row), modifier.testTag(SETTINGS_ROW_TAG + row.id.key))
        return
    }
    if (compact) {
        // (Settings panel §5.4, §5.5) The panel draws an open option list as its own window slots (CompactOptionRow); this
        // path draws the row and its list together, for callers that lay a row out as one item.
        Column(modifier.fillMaxWidth()) {
            CompactRowBody(row, focused && choice == null)
            if (choice != null && choice.row == row.id) {
                compactOptions(row)?.let { (options, selected, multi) ->
                    Spacer(Modifier.height(SettingsPanelUi.ROW_GAP.dp))
                    options.forEachIndexed { i, o -> CompactOptionRow(row, o, i, selected = i in selected, focused = i == choice.focused, multi = multi) }
                }
            }
        }
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
    // U-S6: a storage Info row grows with its lines (at least rowHeight), so no next-step line is cut by the row's height.
    // (Update interval §5.2) The focused interval row grows with its wrapped detail too.
    val grows = isStorageInfo(row) || (focused && row.id == INTERVAL_ROW)
    val sized = if (grows) Modifier.heightIn(min = rowHeight(row).dp) else Modifier.height(rowHeight(row).dp)
    var m = Modifier
        .fillMaxWidth()
        .then(sized)
        .testTag(SETTINGS_ROW_TAG + row.id.key)
        // U-N3 (save-location review 2026-10-24): TalkBack hears the next steps, the value and the details, not the label only.
        .semantics { selected = focused; contentDescription = rowDescription(row) }
    m = when {
        focused -> m.border(SettingsUi.FOCUS_RING.dp, SettingsUi.Accent, shape).background(SettingsUi.Fg, shape)
        moving -> m.border(1.dp, SettingsUi.Accent, shape).background(SettingsUi.White12, shape)
        else -> m
    }
    val padV = if (focused && row.id == INTERVAL_ROW) INTERVAL_PAD_V else 0
    Row(m.padding(horizontal = SettingsUi.ROW_PAD_H.dp, vertical = padV.dp), verticalAlignment = Alignment.CenterVertically) {
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
            secondLine(row)?.let { d ->
                // (WP-S4, §9.1) An amber description ("Can't reach NAS: saving on this device for now.").
                val amber = SettingsUi.isWarnText(d) && !focused
                val color = if (amber) SettingsUi.Amber.copy(alpha = alpha) else SettingsUi.secondary(text, focused, 0.55f)
                // (Update interval §5.2) The interval row's detail (recommendation, time per update, MB an hour, the
                // floor / slowed / fallback lines) wraps while the row is focused; every other row stays one line.
                val lines = descLines(row, focused)
                Line(app.thebox.core.settings.state.StorageMarks.plain(d), SettingsUi.RowDesc.copy(color = color), Modifier.padding(top = 4.dp), maxLines = lines)
            }
            (row.kind as? RowKind.Info)?.let { info ->
                if (info.lines.isNotEmpty()) {
                    val tone = if (focused) text else SettingsUi.toneColor(info.tone).copy(alpha = alpha)
                    // (WP-S4) The storage pages' next steps: one line each, never joined and clipped. U-S6: the Test's details
                    // ("10.5 MB/s · 4 ms · Wi-Fi") are a line too, in mono at the line size.
                    if (isStorageInfo(row)) info.lines.forEach {
                        val style = if (SettingsUi.isMonoValue(it)) SettingsUi.RowDescMono else SettingsUi.RowDesc
                        Line(it, style.copy(color = tone), Modifier.padding(top = 4.dp))
                    }
                    else Line(info.lines.joinToString("  ·  "), SettingsUi.RowDesc.copy(color = tone), Modifier.padding(top = 4.dp))
                }
            }
            // (WP-S4, §9.4) The Test's progress track under its label.
            if (row.id == PROGRESS_ROW) {
                val fraction = SettingsUi.progressFraction(row.value) ?: 0f
                // U-N3: the description is a string resource, not hard-coded English.
                val testing = row.value?.let { stringResource(R.string.storage_ui_testing_progress, it) } ?: stringResource(R.string.storage_ui_testing)
                ProgressTrack(fraction, Modifier.padding(top = 10.dp).semantics { contentDescription = testing })
            }
        }
        Trailing(row, focused, text)
    }
}

@Composable
private fun Trailing(row: SettingsRow, focused: Boolean, text: Color) {
    val value = valueText(row)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // (WP-S4, §9.4) The searching row's three dots.
        if (row.id == SEARCHING_ROW) SearchingDots(stringResource(R.string.settings_ui_searching), Modifier.padding(end = 8.dp))
        (row.kind as? RowKind.Item)?.let { item ->
            item.badges.sortedBy { it.ordinal }.forEach { b ->
                if (SettingsUi.isAccentBadge(b)) AccentBadgeChip(stringResource(SettingsUi.badgeLabel(b)), focused)
                else BadgeChip(stringResource(SettingsUi.badgeLabel(b)), text)
            }
        }
        if (value != null && row.kind !is RowKind.Toggle) {
            val style = if (SettingsUi.isMonoValue(value)) SettingsUi.RowValueMono else SettingsUi.RowValue
            // (WP-S4, §9.1) An amber value while the cycle falls back to this device ("1 hour · This device (NAS offline)").
            val amber = SettingsUi.isWarnText(row.value) && !focused
            Text(
                value,
                style = style.copy(color = if (amber) SettingsUi.Amber.copy(alpha = text.alpha) else SettingsUi.secondary(text, focused, 0.65f)),
                maxLines = 1,
                overflow = if (value.contains("://") || value.contains('.')) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
                // U-S6: at most 560 px, no longer always 560: a short value leaves the label column (and its lines) the room.
                modifier = Modifier.widthIn(max = VALUE_MAX_W.dp).padding(start = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        when (val k = row.kind) {
            is RowKind.Toggle -> TogglePill(k.on, Modifier.semantics { stateDescription = if (k.on) "on" else "off" }, onLight = focused) // U-S9
            is RowKind.Link -> Line(stringResource(R.string.settings_ui_chevron), SettingsUi.RowLabel.copy(color = SettingsUi.secondary(text, focused, 0.45f)))
            is RowKind.Item -> if (k.route != null) Line(stringResource(R.string.settings_ui_chevron), SettingsUi.RowLabel.copy(color = SettingsUi.secondary(text, focused, 0.45f)))
            is RowKind.Action -> if (k.destructive) Dot(SettingsUi.Bad.copy(alpha = text.alpha), 10)
            else -> Unit
        }
    }
}

private const val VALUE_MAX_W = 560

/** U-N3: the row as TalkBack reads it: label, the line under it, the Info lines (next steps, details) and the value. */
internal fun rowDescription(row: SettingsRow): String = buildList {
    add(row.label)
    secondLine(row)?.let { add(app.thebox.core.settings.state.StorageMarks.plain(it)) }
    (row.kind as? RowKind.Info)?.lines?.let { addAll(it) }
    row.value?.let { add(app.thebox.core.settings.state.StorageMarks.plain(it)) }
}.filter { it.isNotBlank() }.joinToString(", ")
/** The wordmark on the About header row (design px). */
private const val WORDMARK_W = 200

@Composable
private fun Dot(color: Color, size: Int) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

/**
 * The kit's toggle (§3.1): a 56 × 30 pill; on = accent fill, knob right; off = `.16` fill, knob left. On keeps its
 * colours on a focused row. U-S9 (code review 2026-10-16): Off on a focused (light, [onLight]) row draws a `.18` dark
 * fill with a dark knob; the `.16` white fill and the `#EEF1F6` knob vanished into the `#EEF1F6` row.
 */
@Composable
fun TogglePill(on: Boolean, modifier: Modifier = Modifier, onLight: Boolean = false) {
    val pill = RoundedCornerShape(SettingsUi.TOGGLE_H.dp)
    val (fill, knob) = togglePillColors(on, onLight)
    Box(
        modifier
            .size(SettingsUi.TOGGLE_W.dp, SettingsUi.TOGGLE_H.dp)
            .background(fill, pill)
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(SettingsUi.TOGGLE_KNOB.dp).background(knob, CircleShape)) }
}

/** U-S9: the pill's fill and knob colours; [onLight] = drawn on a focused (`Fg`) row. */
internal fun togglePillColors(on: Boolean, onLight: Boolean): Pair<Color, Color> = when {
    on -> SettingsUi.Accent to SettingsUi.Fg
    onLight -> SettingsUi.Canvas.copy(alpha = 0.18f) to SettingsUi.Canvas
    else -> SettingsUi.White16 to SettingsUi.Fg
}

/** A row badge (§3.1): mono 18, radius 7, a `.22` border in the row's text colour. Also the channel editor's badges. */
@Composable
internal fun BadgeChip(label: String, text: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .border(1.dp, text.copy(alpha = 0.22f), RoundedCornerShape(SettingsUi.BADGE_RADIUS.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) { Line(label, SettingsUi.BadgeText.copy(color = text.copy(alpha = 0.8f))) }
}

/**
 * (WP-S4, §9.4) `Badge.IN_USE` / `Badge.SAVED`: the M5 badge with a 1 px accent border; the text is FG .9 on a normal row and
 * the canvas colour on a focused (FG-filled) row.
 */
@Composable
internal fun AccentBadgeChip(label: String, focused: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .border(SettingsUi.ACCENT_BADGE_BORDER.dp, SettingsUi.Accent, RoundedCornerShape(SettingsUi.BADGE_RADIUS.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) { Line(label, SettingsUi.BadgeText.copy(color = SettingsUi.accentBadgeText(focused))) }
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
                o.detail?.let { Line(it, SettingsUi.OptionDetail.copy(color = SettingsUi.secondary(text, isFocused, 0.55f)), Modifier.padding(start = 16.dp)) }
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

/**
 * The kit's check box: accent fill and an FG tick when [checked]; a 2 px `.45` ring of [ring] when not. [side] in design
 * px (18 in option lists; the channel editor's tick box is 24).
 */
@Composable
internal fun CheckBox(checked: Boolean, ring: Color, modifier: Modifier = Modifier, side: Int = SettingsUi.RADIO) {
    val d = side.dp
    val shape = RoundedCornerShape(4.dp)
    if (!checked) {
        Box(modifier.size(d).border(2.dp, ring.copy(alpha = 0.45f), shape))
        return
    }
    Canvas(modifier.size(d).background(SettingsUi.Accent, shape)) {
        val w = size.width
        drawLine(SettingsUi.Fg, Offset(w * 0.22f, w * 0.52f), Offset(w * 0.42f, w * 0.72f), strokeWidth = w * 0.14f, cap = StrokeCap.Round)
        drawLine(SettingsUi.Fg, Offset(w * 0.42f, w * 0.72f), Offset(w * 0.78f, w * 0.30f), strokeWidth = w * 0.14f, cap = StrokeCap.Round)
    }
}


// ---------------------------------------------------------------- (Settings panel §5.4, §5.5) compact rows

/** The options of [row]'s inline list (Choice or MultiChoice): options, the selected indices, multi. Null for other kinds. */
internal fun compactOptions(row: SettingsRow): Triple<List<ChoiceOption>, Set<Int>, Boolean>? = when (val k = row.kind) {
    is RowKind.Choice -> Triple(k.options, setOf(k.selected), false)
    is RowKind.MultiChoice -> Triple(k.options, k.selected, true)
    else -> null
}

/** The account card of a playlist page (N8: its line wraps to 2 lines before it ellipsises). */
internal const val ACCOUNT_ROW = "playlist.account"

/** (§2.7) How many lines the Info text under a compact row's label may take: the account card 2 (N8), others 3. */
internal fun compactInfoLines(row: SettingsRow): Int = if (row.id.key == ACCOUNT_ROW) 2 else 3

/** (§5.4) True when a compact row has a second line (value, badges or Info lines): its minimum height is then 92, else 64. */
internal fun compactHasSecondLine(row: SettingsRow, value: String?): Boolean =
    (value != null && row.kind !is RowKind.Toggle) ||
        ((row.kind as? RowKind.Item)?.badges?.isNotEmpty() == true) ||
        ((row.kind as? RowKind.Info)?.lines?.isNotEmpty() == true)

/**
 * (Settings panel §5.4) One compact row: 464 wide, at least 64 (92 with a second line), padding 16 / 10, radius 12; the
 * label 26/500 on up to 2 lines; under it the value 22/400 (mono for mono values, a middle ellipsis for URLs and hosts)
 * led by the badges, or the Info lines (3; the account card 2, N8; storage rows: one line each); at the right the toggle,
 * a chevron or the destructive dot. The description is not drawn here: the panel's band shows it. Focus is the kit's (FG
 * fill, 3 px accent ring, dark text); disabled rows at .4. No wordmark, no progress track, no searching dots (those
 * pages expand, §2.6).
 */
@Composable
internal fun CompactRowBody(row: SettingsRow, focused: Boolean) {
    val shape = RoundedCornerShape(SettingsPanelUi.ROW_RADIUS.dp)
    val alpha = if (row.enabled) 1f else SettingsUi.DISABLED_ALPHA
    val base = if (focused) SettingsUi.Canvas else SettingsUi.Fg
    val text = base.copy(alpha = alpha)
    val value = valueText(row)
    val two = compactHasSecondLine(row, value)
    var m = Modifier
        .fillMaxWidth()
        .heightIn(min = (if (two) SettingsPanelUi.ROW_H_TWO else SettingsPanelUi.ROW_H).dp)
        .testTag(SETTINGS_ROW_TAG + row.id.key)
        .semantics { selected = focused; contentDescription = rowDescription(row) }
    if (focused) m = m.border(SettingsUi.FOCUS_RING.dp, SettingsUi.Accent, shape).background(SettingsUi.Fg, shape)
    Row(
        m.padding(horizontal = SettingsPanelUi.ROW_PAD_H.dp, vertical = SettingsPanelUi.ROW_PAD_V.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Line(
                row.label, SettingsPanelUi.RowLabel.copy(color = if (focused) text else SettingsUi.Fg92.copy(alpha = 0.92f * alpha)),
                Modifier.testTag(SETTINGS_PANEL_LABEL_TAG), maxLines = 2,
            )
            CompactSecondLine(row, value, focused, text)
        }
        CompactTrailing(row, focused, text)
    }
}

@Composable
private fun CompactSecondLine(row: SettingsRow, value: String?, focused: Boolean, text: Color) {
    val badges = (row.kind as? RowKind.Item)?.badges.orEmpty()
    val shownValue = value?.takeIf { row.kind !is RowKind.Toggle }
    if (badges.isNotEmpty() || shownValue != null) {
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            badges.sortedBy { it.ordinal }.forEach { b ->
                if (SettingsUi.isAccentBadge(b)) AccentBadgeChip(stringResource(SettingsUi.badgeLabel(b)), focused)
                else BadgeChip(stringResource(SettingsUi.badgeLabel(b)), text)
            }
            if (shownValue != null) {
                val style = if (SettingsUi.isMonoValue(shownValue)) SettingsPanelUi.RowValueMono else SettingsPanelUi.RowValue
                val amber = SettingsUi.isWarnText(row.value) && !focused
                Text(
                    shownValue,
                    style = style.copy(color = if (amber) SettingsUi.Amber.copy(alpha = text.alpha) else SettingsUi.secondary(text, focused, 0.65f)),
                    maxLines = 1,
                    overflow = if (shownValue.contains("://") || shownValue.contains('.')) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(SETTINGS_PANEL_VALUE_TAG),
                )
            }
        }
    }
    val info = row.kind as? RowKind.Info ?: return
    if (info.lines.isEmpty()) return
    val tone = if (focused) text else SettingsUi.toneColor(info.tone).copy(alpha = text.alpha)
    if (isStorageInfo(row)) {
        info.lines.forEach {
            val style = if (SettingsUi.isMonoValue(it)) SettingsPanelUi.RowValueMono else SettingsPanelUi.RowValue
            Line(it, style.copy(color = tone), Modifier.padding(top = 2.dp).testTag(SETTINGS_PANEL_INFO_TAG), maxLines = 2)
        }
    } else {
        Line(
            info.lines.joinToString("  ·  "), SettingsPanelUi.RowValue.copy(color = tone),
            Modifier.padding(top = 2.dp).testTag(SETTINGS_PANEL_INFO_TAG), maxLines = compactInfoLines(row),
        )
    }
}

@Composable
private fun CompactTrailing(row: SettingsRow, focused: Boolean, text: Color) {
    val chevronColor = SettingsUi.secondary(text, focused, 0.45f)
    when (val k = row.kind) {
        is RowKind.Toggle -> TogglePill(k.on, Modifier.padding(start = 12.dp).semantics { stateDescription = if (k.on) "on" else "off" }, onLight = focused)
        is RowKind.Link -> CompactChevron(chevronColor)
        is RowKind.Item -> if (k.route != null) CompactChevron(chevronColor)
        is RowKind.Action -> if (k.destructive) Box(Modifier.padding(start = 12.dp)) { Dot(SettingsUi.Bad.copy(alpha = text.alpha), 10) }
        else -> Unit
    }
}

@Composable
internal fun CompactChevron(color: Color) {
    Line(stringResource(R.string.settings_ui_chevron), SettingsPanelUi.Chevron.copy(color = color), Modifier.padding(start = 12.dp))
}

/**
 * (Settings panel §5.5) One option of a compact inline list: indent 24, at least 56 high (76 with a detail), the label
 * 24/400 on up to 2 lines (N7), the detail 22 .55 on one line under it, a radio or check box of 18 and a gap of 14.
 */
@Composable
internal fun CompactOptionRow(row: SettingsRow, o: ChoiceOption, i: Int, selected: Boolean, focused: Boolean, multi: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(SettingsPanelUi.ROW_RADIUS.dp)
    val alpha = if (o.enabled) 1f else SettingsUi.DISABLED_ALPHA
    val text = (if (focused) SettingsUi.Canvas else SettingsUi.Fg).copy(alpha = alpha)
    var m = modifier
        .fillMaxWidth()
        .padding(start = SettingsPanelUi.CHOICE_INDENT.dp)
        .heightIn(min = (if (o.detail != null) SettingsPanelUi.OPTION_H_DETAIL else SettingsPanelUi.OPTION_H).dp)
        .testTag("$SETTINGS_OPTION_TAG${row.id.key}:$i")
        .semantics { this.selected = focused; stateDescription = if (selected) "checked" else "unchecked"; contentDescription = o.label }
    if (focused) m = m.border(SettingsUi.FOCUS_RING.dp, SettingsUi.Accent, shape).background(SettingsUi.Fg, shape)
    Row(m.padding(horizontal = SettingsPanelUi.ROW_PAD_H.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (multi) CheckBox(selected, text) else Radio(selected, text)
        Spacer(Modifier.width(SettingsPanelUi.OPTION_GAP.dp))
        Column(Modifier.weight(1f)) {
            Line(o.label, SettingsPanelUi.OptionLabel.copy(color = text), Modifier.testTag(SETTINGS_PANEL_LABEL_TAG), maxLines = 2)
            o.detail?.let { Line(it, SettingsPanelUi.OptionDetail.copy(color = SettingsUi.secondary(text, focused, 0.55f)), Modifier.padding(top = 2.dp)) }
        }
    }
}

/** Test tags of the compact rows' texts (labels must be whole; values may ellipsise). */
const val SETTINGS_PANEL_LABEL_TAG = "settings_panel_label"
const val SETTINGS_PANEL_VALUE_TAG = "settings_panel_value"
const val SETTINGS_PANEL_INFO_TAG = "settings_panel_info"

/** An unloaded row of a paged list (§3.2.1): row height, `.03` fill. [height]: 72 (an item row), 64 in the panel. */
@Composable
fun PlaceholderRow(index: Int, modifier: Modifier = Modifier, height: Int = SettingsUi.ITEM_ROW_H) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .testTag(SETTINGS_PLACEHOLDER_ROW_TAG + index)
            .background(SettingsUi.White03, RoundedCornerShape(SettingsUi.ROW_RADIUS.dp)),
    )
}
