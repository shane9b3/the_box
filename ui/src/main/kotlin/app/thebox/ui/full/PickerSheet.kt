package app.thebox.ui.full

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.full.PickerChoice
import app.thebox.core.full.PickerState
import app.thebox.core.full.state.selectable
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import app.thebox.ui.theme.DesignTokens

const val PICKER_SHEET_TAG = "player_picker"
const val PICKER_ROW_TAG = "player_picker_row"
const val PICKER_DIVIDER_TAG = "player_picker_divider"
/** (CHL emulator pass, defect 1) The score sheet's failure text (a day failure, or the followed game's refused feed). */
const val PICKER_FAILED_TAG = "player_picker_failed"

/**
 * Audio / subtitles / aspect / decoder picker (docs/design/m4-player-ux.md §3.4.2, §3.9): (1376, 48) 496 × 880 design
 * px, ending above the quick bar; fill `#0A0D13` .86, border .09, radius 24; title 24/600, rows 64 high 21/500, a check
 * mark on [PickerState.current], unsupported audio tracks greyed with "not supported on this device", speculative CEA
 * tracks as "Captions (if broadcast)", and the "Remember for this channel" row (a box glyph) when
 * [PickerState.remember] is non-null (index `choices.size`). Rows scroll as a window around the focus.
 */
@Composable
fun PickerSheet(state: PickerState, modifier: Modifier = Modifier, clock24: Boolean = true) {
    val res = androidx.compose.ui.platform.LocalResources.current
    // (Score bug WP-4b; fix pass U-S5) Upcoming games in the score sheet read "7:00 PM" or "19:00" per the app's clock
    // setting ([clock24], from FullScreenHost like the quick bar), not the device's.
    val shape = RoundedCornerShape(DesignTokens.Radii.Panel)
    Column(
        modifier
            .testTag(PICKER_SHEET_TAG)
            .size(PlayerUi.PICKER_W.dp, PlayerUi.PICKER_H.dp)
            .background(PlayerUi.PanelFill, shape)
            .border(1.dp, PlayerUi.Border09, shape)
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Line(stringResource(PlayerText.pickerTitle(state.kind)), GuideUi.sans(30, FontWeight.SemiBold), Modifier.padding(start = 12.dp, bottom = 12.dp))
        val rows = state.choices.size + if (state.remember != null) 1 else 0
        // (CHL emulator pass, defect 1) A failure row is two rows tall so its reason and next step wrap instead of
        // ellipsising; the window counts it twice so the sheet never grows past its 880 px.
        val window = pickerWindow(rows, state.focused, VISIBLE) { i -> if (i < state.choices.size && state.choices[i] is PickerChoice.ScoreFailed) FAILED_SLOTS else 1 }
        for (i in window) {
            if (i == state.choices.size) {
                RememberRow(checked = state.remember == true, focused = state.focused == i)
                continue
            }
            val c = state.choices[i]
            if (c is PickerChoice.ScoreTodayDivider || c is PickerChoice.ScoreDayDivider) { // (U-N9; U-B1) the non-selectable day divider
                DividerRow(PlayerText.choice(res, c, i, clock24))
                continue
            }
            if (c is PickerChoice.ScoreFailed) {
                FailedRow(PlayerText.choice(res, c, i, clock24), focused = i == state.focused)
                continue
            }
            val disabled = (c is PickerChoice.Audio && !c.track.supported) || !c.selectable // "Loading games…" reads greyed
            PickerRow(
                text = PlayerText.choice(res, c, i, clock24),
                note = if (disabled) stringResource(R.string.player_picker_unsupported) else null,
                checked = i == state.current,
                focused = i == state.focused,
                disabled = disabled,
            )
        }
    }
}

/** The score sheet's "Today" divider: a small label over a hairline, never focused (skipped by [selectable]). */
@Composable
private fun DividerRow(text: String) {
    Column(
        Modifier.testTag(PICKER_DIVIDER_TAG).fillMaxWidth().height(DIVIDER_H.dp).padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Line(text, GuideUi.sans(20, FontWeight.SemiBold, GuideUi.Fg50))
        Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(1.dp).background(PlayerUi.Border09))
    }
}

@Composable
private fun PickerRow(text: String, note: String?, checked: Boolean, focused: Boolean, disabled: Boolean) {
    val fg = when {
        focused -> GuideUi.Canvas
        disabled -> GuideUi.Fg50
        else -> GuideUi.Fg
    }
    Row(
        Modifier
            .testTag(PICKER_ROW_TAG)
            .height(PlayerUi.PICKER_ROW_H.dp)
            .fillMaxWidth()
            .then(if (focused) Modifier.border(GuideUi.FocusRing, GuideUi.Accent, RoundedCornerShape(DesignTokens.Radii.Row)) else Modifier)
            .background(if (focused) GuideUi.Fg else Color.Transparent, RoundedCornerShape(DesignTokens.Radii.Row))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(24.dp)) { if (checked) Line("✓", GuideUi.sans(26, FontWeight.SemiBold, if (focused) fg else GuideUi.Accent)) }
        Column(Modifier.weight(1f)) {
            Line(text, GuideUi.sans(26, FontWeight.Medium, fg))
            note?.let { Line(it, GuideUi.sans(20, color = fg.copy(alpha = 0.7f))) }
        }
    }
}

/**
 * (CHL emulator pass, defect 1) A score failure (the §5.4 day text, or the followed game's refused feed): the whole
 * reason and next step, wrapped at 22/500 in a row [FAILED_SLOTS] rows tall (up to [FAILED_LINES] lines), never
 * ellipsised to "OHL scores stopped letting thi…". Focus and OK work as on any row (OK retries).
 */
@Composable
private fun FailedRow(text: String, focused: Boolean) {
    val fg = if (focused) GuideUi.Canvas else GuideUi.Fg
    Row(
        Modifier
            .testTag(PICKER_ROW_TAG)
            .height(FAILED_ROW_H.dp)
            .fillMaxWidth()
            .then(if (focused) Modifier.border(GuideUi.FocusRing, GuideUi.Accent, RoundedCornerShape(DesignTokens.Radii.Row)) else Modifier)
            .background(if (focused) GuideUi.Fg else Color.Transparent, RoundedCornerShape(DesignTokens.Radii.Row))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(24.dp))
        Line(text, GuideUi.sans(FAILED_PX, FontWeight.Medium, fg), Modifier.weight(1f).testTag(PICKER_FAILED_TAG), maxLines = FAILED_LINES)
    }
}

/**
 * The rows the sheet shows: a window of at most [budget] slots (row i costs [cost] slots) that holds [focused], centred
 * on it where it can be, and filled up to the budget at the end of the list. With every cost 1 this is the old
 * `focused - budget / 2` window, clamped to the list.
 */
internal fun pickerWindow(rows: Int, focused: Int, budget: Int, cost: (Int) -> Int): IntRange {
    if (rows <= 0) return IntRange.EMPTY
    val f = focused.coerceIn(0, rows - 1)
    var top = (f - budget / 2).coerceIn(0, (rows - budget).coerceAtLeast(0))
    fun endOf(from: Int): Int {
        var used = 0
        var i = from
        while (i < rows && used + cost(i) <= budget) { used += cost(i); i++ }
        return if (i == from) from + 1 else i // a lone row larger than the budget still shows
    }
    var end = endOf(top)
    while (f >= end && top < f) { top++; end = endOf(top) }
    if (end == rows) {
        var used = (top until end).sumOf(cost)
        while (top > 0 && used + cost(top - 1) <= budget) { top--; used += cost(top) }
    }
    return top until end
}

@Composable
private fun RememberRow(checked: Boolean, focused: Boolean) {
    val fg = if (focused) GuideUi.Canvas else GuideUi.Fg
    Row(
        Modifier
            .testTag(PICKER_ROW_TAG)
            .padding(top = 8.dp)
            .height(PlayerUi.PICKER_ROW_H.dp)
            .fillMaxWidth()
            .background(if (focused) GuideUi.Fg else Color.Transparent, RoundedCornerShape(DesignTokens.Radii.Row))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(22.dp).border(2.dp, fg, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
            if (checked) Line("✓", GuideUi.sans(22, FontWeight.Bold, fg))
        }
        Line(stringResource(R.string.player_picker_remember), GuideUi.sans(24, FontWeight.Medium, fg))
    }
}

/** The score sheet's divider row height (design px); shorter than a choice row. */
private const val DIVIDER_H = 40f

/** Rows that fit the 880 px sheet under its title. */
private const val VISIBLE = 11

/** (CHL emulator pass, defect 1) A failure row takes two row slots: 2 × 64 + the 4 px gap = 132 design px. */
internal const val FAILED_SLOTS = 2
internal const val FAILED_ROW_H = FAILED_SLOTS * PlayerUi.PICKER_ROW_H + (FAILED_SLOTS - 1) * 4f
private const val FAILED_PX = 22
private const val FAILED_LINES = 4
