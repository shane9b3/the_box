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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.thebox.core.editor.EditorChannelRow
import app.thebox.core.editor.EditorGroupRow
import app.thebox.core.editor.EditorText
import app.thebox.core.editor.EditorTexts
import app.thebox.ui.R
import app.thebox.ui.full.ChannelLogo
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.parental.LockGlyph
import app.thebox.ui.parental.NotProtectedChip
import app.thebox.ui.settings.BadgeChip
import app.thebox.ui.settings.CheckBox
import app.thebox.ui.settings.SettingsUi

/** Test tags of the editor's rows (`editor_group:{key}`, `editor_channel:{key}`, `editor_head:{index}`, …). */
const val EDITOR_GROUP_ROW_TAG = "editor_group:"
const val EDITOR_CHANNEL_ROW_TAG = "editor_channel:"
const val EDITOR_HEAD_ROW_TAG = "editor_head:"
const val EDITOR_PLACEHOLDER_TAG = "editor_placeholder:"
const val EDITOR_LOCKED_NOTE_TAG = "editor_locked_note"
const val EDITOR_TICK_TAG = "editor_tick"
const val EDITOR_LIFT_TAG = "editor_lifted"
const val EDITOR_CURRENT_BAR_TAG = "editor_current_bar"
const val EDITOR_BADGE_TAG = "editor_badge:"
const val EDITOR_LOGO_TAG = "editor_logo"

/**
 * The channel editor's geometry (docs/design/m6-channel-editor.md §2.2), in design px (1.dp = 1 design px inside
 * `BoxDesignScale`). Positions inside a pane are from the pane's top-left corner; positions inside a row from the
 * row's left edge.
 */
object EditorUi {
    const val PANE_Y = 144
    const val PANE_H = 800
    const val GROUPS_X = 64
    const val GROUPS_W = 560
    const val CHANNELS_X = 648
    const val CHANNELS_W = 1208
    const val PANE_PAD = 16
    const val TITLE_H = 40
    const val TOOLBAR_Y = 64
    const val TOOLBAR_H = 48
    const val CHIP_GAP = 12
    const val ROWS_Y = 128
    const val ROW_H = 72
    const val ROW_GAP = 8
    const val ROW_STEP = ROW_H + ROW_GAP
    const val ROW_RADIUS = 14
    const val ROW_PAD = 20
    const val TICK = 24
    /** Name (group rows) and number (channel rows) start at x + 60. */
    const val TEXT_X = 60
    const val NUMBER_W = 80
    const val LOGO_X = 152
    const val LOGO_W = 96
    const val LOGO_H = 48
    const val LOGO_RADIUS = 8
    const val CHANNEL_NAME_X = 268
    const val CURRENT_BAR_X = 8
    const val MENU_W = 480
    const val MENU_PAD = 16
    const val MENU_TITLE_H = 40
    const val MENU_LINE_H = 60
    /** A disabled line with its reason underneath. */
    const val MENU_LINE_REASON_H = 84
    const val CHOICE_W = 420
    const val PICKER_X = 600
    const val PICKER_Y = 160
    const val PICKER_W = 720
    const val PICKER_ROW_H = 64
    const val PICKER_PAD = 24
    const val NOTICE_X = 64
    const val NOTICE_Y = 956
    const val NOTICE_H = 40
    const val FOOTER_X = 64
    const val FOOTER_BOTTOM = 40

    /**
     * Rows area height: 8 whole rows (8 × 80 − 8 = 632 of the 656 under the toolbar). The area clips there, so a ninth,
     * partial row never peeks under the eighth (emulator finding 2026-10-19).
     */
    const val ROWS_H = 8 * ROW_STEP - ROW_GAP

    val Fg50 = SettingsUi.Fg.copy(alpha = 0.50f)
    const val HIDDEN_ALPHA = 0.45f

    val RowName = GuideUi.sans(26, FontWeight.SemiBold)
    val RowValue = GuideUi.mono(22)
    val Detail = GuideUi.sans(22)
    val PaneTitle = GuideUi.sans(30, FontWeight.SemiBold)
    val PaneCounts = GuideUi.mono(22, color = SettingsUi.Fg55)
    val ChipText = GuideUi.sans(22)
}

/** How a row is drawn: plain, focused (FG fill, dark text, accent ring), current (the channels pane's group) or lifted. */
enum class RowLook { PLAIN, FOCUSED, CURRENT, LIFTED }

@Composable
private fun rowModifier(look: RowLook, modifier: Modifier): Modifier {
    val shape = RoundedCornerShape(EditorUi.ROW_RADIUS.dp)
    val m = modifier.fillMaxWidth().height(EditorUi.ROW_H.dp).semantics { selected = look == RowLook.FOCUSED }
    return when (look) {
        RowLook.FOCUSED -> m.border(SettingsUi.FOCUS_RING.dp, SettingsUi.Accent, shape).background(SettingsUi.Fg, shape)
        RowLook.CURRENT -> m.background(SettingsUi.White08, shape)
        // MoveModeRow's lift look (1 px accent border, `.12` fill), without Modifier.shadow (a graphicsLayer).
        RowLook.LIFTED -> m.border(1.dp, SettingsUi.Accent, shape).background(SettingsUi.White12, shape)
        RowLook.PLAIN -> m
    }
}

/** The row's base text colour: dark on the focused fill, else FG (`.45` when [dim]). */
private fun textColor(look: RowLook, dim: Boolean): Color {
    val base = if (look == RowLook.FOCUSED) SettingsUi.Canvas else SettingsUi.Fg
    return if (dim) base.copy(alpha = EditorUi.HIDDEN_ALPHA) else base
}

/** The tick slot: the 24 px check box, or the `↕` glyph on a lifted row. */
@Composable
private fun TickSlot(look: RowLook, ticked: Boolean, text: Color) {
    Box(Modifier.width(EditorUi.TICK.dp), contentAlignment = Alignment.Center) {
        if (look == RowLook.LIFTED) {
            Line(stringResource(R.string.settings_ui_move_glyph), GuideUi.sans(22, FontWeight.SemiBold, SettingsUi.Accent), Modifier.testTag(EDITOR_LIFT_TAG))
        } else {
            CheckBox(
                ticked, text,
                Modifier.testTag(EDITOR_TICK_TAG).semantics { stateDescription = if (ticked) "checked" else "unchecked" },
                side = EditorUi.TICK,
            )
        }
    }
}

@Composable
private fun CurrentBar() {
    Box(
        Modifier.offset(x = EditorUi.CURRENT_BAR_X.dp).size(4.dp, 32.dp).testTag(EDITOR_CURRENT_BAR_TAG)
            .background(SettingsUi.Accent, RoundedCornerShape(2.dp)),
    )
}

@Composable
private fun Badge(label: String, text: Color, tag: String) = BadgeChip(label, text, Modifier.testTag(EDITOR_BADGE_TAG + tag))

/** `🔒 LOCKED`: the drawn padlock and the LOCKED badge. */
@Composable
private fun LockedBadge(text: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LockGlyph(text.copy(alpha = text.alpha * 0.8f), size = 18.dp)
        Badge(stringResource(R.string.settings_ui_badge_locked), text, "locked")
    }
}

/**
 * A fixed Groups-pane row (§2.2): "All channels" or "Results for '…'". No tick box, name 26/600, count mono 22 (known
 * only while the channels pane shows that list; null otherwise).
 */
@Composable
fun HeadRow(index: Int, label: String, count: Int?, look: RowLook, modifier: Modifier = Modifier) {
    val text = textColor(look, dim = false)
    Box(rowModifier(look, modifier).testTag(EDITOR_HEAD_ROW_TAG + index).semantics { contentDescription = label }, contentAlignment = Alignment.CenterStart) {
        if (look == RowLook.CURRENT) CurrentBar()
        Row(Modifier.fillMaxWidth().padding(start = EditorUi.TEXT_X.dp, end = EditorUi.ROW_PAD.dp), verticalAlignment = Alignment.CenterVertically) {
            Line(label, EditorUi.RowName.copy(color = text), Modifier.weight(1f))
            if (count != null) Line(formatCount(count), EditorUi.RowValue.copy(color = text.copy(alpha = text.alpha * 0.65f)), Modifier.padding(start = 12.dp))
        }
    }
}

/**
 * A group row (§2.2): tick box at x + 20, name 26/600 at x + 60, badges, value mono 22 `.65` right-aligned ("120" or
 * "120 · 8 hidden"). Hidden groups draw at `.45`.
 */
@Composable
fun GroupRowView(row: EditorGroupRow, ticked: Boolean, look: RowLook, texts: EditorTexts, modifier: Modifier = Modifier) {
    val dim = row.hidden || row.ruleHidden
    val text = textColor(look, dim)
    Box(rowModifier(look, modifier).testTag(EDITOR_GROUP_ROW_TAG + row.groupKey).semantics { contentDescription = row.name }, contentAlignment = Alignment.CenterStart) {
        if (look == RowLook.CURRENT) CurrentBar()
        Row(Modifier.fillMaxWidth().padding(horizontal = EditorUi.ROW_PAD.dp), verticalAlignment = Alignment.CenterVertically) {
            TickSlot(look, ticked, text)
            Spacer(Modifier.width((EditorUi.TEXT_X - EditorUi.ROW_PAD - EditorUi.TICK).dp))
            Line(row.name, EditorUi.RowName.copy(color = text), Modifier.weight(1f))
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GroupBadges(row, text, onLight = look == RowLook.FOCUSED)
                val value = if (row.hiddenChannels > 0) texts.count(EditorText.GROUP_VALUE_HIDDEN, row.channels, row.hiddenChannels) else formatCount(row.channels)
                Line(value, EditorUi.RowValue.copy(color = text.copy(alpha = text.alpha * 0.65f)))
            }
        }
    }
}

/** HIDDEN, RULE, the lock (or the amber `18+` while no PIN protects it, U-S11), `18+`. */
@Composable
fun GroupBadges(row: EditorGroupRow, text: Color, onLight: Boolean = false) {
    if (row.hidden) Badge(stringResource(R.string.settings_ui_badge_hidden), text, "hidden")
    if (row.ruleHidden) Badge(stringResource(R.string.editor_ui_badge_rule), text, "rule")
    if (row.notProtected) {
        NotProtectedChip(color = if (onLight) text else null)
    } else {
        if (row.locked) LockedBadge(text)
        if (row.adult) Badge(stringResource(R.string.settings_ui_badge_adult), text, "adult")
    }
}

/**
 * A channel row (§2.2): tick box, number mono 22 `.5` (x + 60, w 80, right-aligned), logo slot 96 × 48 at x + 152
 * ([showLogo]), name 26/600 at x + 268 (x + 152 without logos), detail sans 22 `.55`, badges at the right edge.
 */
@Composable
fun ChannelRowView(row: EditorChannelRow, ticked: Boolean, look: RowLook, showLogo: Boolean, texts: EditorTexts, modifier: Modifier = Modifier) {
    val dim = row.hidden || row.ruleHidden
    val text = textColor(look, dim)
    Box(rowModifier(look, modifier).testTag(EDITOR_CHANNEL_ROW_TAG + row.stableKey).semantics { contentDescription = row.name }, contentAlignment = Alignment.CenterStart) {
        Row(Modifier.fillMaxWidth().padding(horizontal = EditorUi.ROW_PAD.dp), verticalAlignment = Alignment.CenterVertically) {
            TickSlot(look, ticked, text)
            Spacer(Modifier.width((EditorUi.TEXT_X - EditorUi.ROW_PAD - EditorUi.TICK).dp))
            Text(
                row.number?.toString() ?: "",
                style = EditorUi.RowValue.copy(color = text.copy(alpha = text.alpha * 0.5f)),
                maxLines = 1, overflow = TextOverflow.Clip, textAlign = TextAlign.End,
                modifier = Modifier.width(EditorUi.NUMBER_W.dp),
            )
            Spacer(Modifier.width((EditorUi.LOGO_X - EditorUi.TEXT_X - EditorUi.NUMBER_W).dp))
            if (showLogo) {
                ChannelLogo(
                    row.logoUrl, row.name, EditorUi.LOGO_W.dp, EditorUi.LOGO_H.dp, EditorUi.LOGO_RADIUS.dp,
                    Modifier.testTag(EDITOR_LOGO_TAG),
                )
                Spacer(Modifier.width((EditorUi.CHANNEL_NAME_X - EditorUi.LOGO_X - EditorUi.LOGO_W).dp))
            }
            Line(row.name, EditorUi.RowName.copy(color = text), Modifier.weight(1f))
            val detail = channelDetail(row, texts)
            if (detail != null) {
                Line(detail, EditorUi.Detail.copy(color = text.copy(alpha = text.alpha * 0.55f)), Modifier.padding(start = 16.dp).widthIn(max = DETAIL_MAX_W.dp))
            }
            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChannelBadges(row, text, onLight = look == RowLook.FOCUSED)
            }
        }
    }
}

/** `★`, HIDDEN, RULE, LOCKED (its own lock or its group's). */
@Composable
fun ChannelBadges(row: EditorChannelRow, text: Color, onLight: Boolean = false) {
    if (row.favorite) Line(STAR, GuideUi.sans(24, color = if (onLight) text else GuideUi.Warn), Modifier.testTag(EDITOR_BADGE_TAG + "favorite"))
    if (row.hidden) Badge(stringResource(R.string.settings_ui_badge_hidden), text, "hidden")
    if (row.ruleHidden && !row.hidden) Badge(stringResource(R.string.editor_ui_badge_rule), text, "rule")
    if (row.locked || row.lockedByGroup != null) LockedBadge(text)
}

/** "Hidden by the rule …", "from {group}", "was {name}" (§2.6), joined. */
fun channelDetail(row: EditorChannelRow, texts: EditorTexts): String? {
    val parts = buildList {
        if (row.ruleHidden && !row.hidden && row.rulePattern != null) add(texts.get(EditorText.DETAIL_RULE, row.rulePattern!!))
        row.movedFrom?.let { add(texts.get(EditorText.DETAIL_MOVED, it)) }
        if (row.renamed && row.providerName != row.name) add(texts.get(EditorText.DETAIL_RENAMED, row.providerName))
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(SEP)
}

/** The channels list's row −1 while some channels are left out by the PIN (§2.6 b): lock glyph and `.45` text 22. */
@Composable
fun LockedNoteRow(texts: EditorTexts, focused: Boolean, modifier: Modifier = Modifier) {
    val look = if (focused) RowLook.FOCUSED else RowLook.PLAIN
    val text = if (focused) SettingsUi.Canvas else SettingsUi.Fg45
    val label = texts.get(EditorText.LOCKED_NOTE)
    Row(
        rowModifier(look, modifier).testTag(EDITOR_LOCKED_NOTE_TAG).semantics { contentDescription = label }.padding(horizontal = EditorUi.ROW_PAD.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(EditorUi.TICK.dp), contentAlignment = Alignment.Center) { LockGlyph(text, size = 20.dp) }
        Spacer(Modifier.width((EditorUi.TEXT_X - EditorUi.ROW_PAD - EditorUi.TICK).dp))
        Line(label, GuideUi.sans(22, color = text))
    }
}

/** An unloaded row (§4 item 2): row height, `.03` fill. */
@Composable
fun EditorPlaceholderRow(tag: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(EditorUi.ROW_H.dp).testTag(EDITOR_PLACEHOLDER_TAG + tag)
            .background(SettingsUi.White03, RoundedCornerShape(EditorUi.ROW_RADIUS.dp)),
    )
}

/** A pane's message in place of rows (E7, empty and error texts): `.6` text 26, up to 3 lines, with a lock for E7. */
@Composable
fun PaneMessage(text: String, locked: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = EditorUi.ROW_PAD.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (locked) LockGlyph(SettingsUi.Fg60, size = 36.dp)
        Line(text, SettingsUi.RowValue.copy(color = SettingsUi.Fg60), maxLines = 3)
    }
}

/** Counts in the locale's grouping ("20,140"). */
fun formatCount(n: Int): String = String.format(java.util.Locale.getDefault(), "%,d", n)

internal const val SEP = " · "
internal const val STAR = "★"
private const val DETAIL_MAX_W = 360
