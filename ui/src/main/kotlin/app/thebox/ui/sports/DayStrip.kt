package app.thebox.ui.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.settings.UiTextScale
import app.thebox.ui.R
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import java.time.LocalDate

internal fun sportsDayTag(offset: Int) = "sports_day:$offset"
internal const val SPORTS_DAY_STRIP_TAG = "sports_day_strip"
internal const val SPORTS_DAY_LEFT_ARROW_TAG = "sports_day_left"
internal const val SPORTS_DAY_RIGHT_ARROW_TAG = "sports_day_right"

/**
 * One chip of the day strip (§3.1): [offset] −7..+7 (NHL, CHL; 0 = today) or −1 / 0 / +1 (NFL weeks); [key] the day's date
 * ("2026-11-21") or the week's key; [enabled] false for an NFL week the board did not name; [noGames] a day the board says is empty.
 */
@Immutable
data class DayChip(val offset: Int, val key: String?, val enabled: Boolean = true, val noGames: Boolean = false)

private val SelectedFill = Color(0xFFEEF1F6).copy(alpha = 0.85f)
private val Ink = Color(0xFF0B0E14)

/**
 * (Sports Hub WP-4, §3) The day strip: arrows (‹ ›, not focusable) in 40 px columns, then the chips (height 60, radius 30,
 * padding 24, gap 12). A plain scrolling row (a departure from "LazyRow": at most 15 chips, so every chip stays composed and
 * focusable and a focus request never meets an unmeasured item). The strip scrolls to keep one chip of context each side of
 * the focused chip (Default, Smaller) or to centre it (Larger, Largest). Focus costs nothing; only OK calls [onDay].
 *
 * Keys: Left / Right between the enabled chips; Up goes to [up] (the league chip); Down asks [onDown] (the grid's card).
 */
@Composable
internal fun DayStrip(
    chips: List<DayChip>,
    selectedOffset: Int,
    nfl: Boolean,
    requesters: Map<Int, FocusRequester>,
    up: FocusRequester,
    onDown: () -> Boolean,
    onDay: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val res = LocalResources.current
    val factor = UiTextScale.factor
    val centre = factor > 1.05f
    val scroll = rememberScrollState()
    val lefts = remember { HashMap<Int, Pair<Int, Int>>() }
    var focusedOffset by remember { mutableStateOf<Int?>(null) }
    var viewportW by remember { mutableIntStateOf(0) }
    val today = remember { LocalDate.now() }
    val indexOf = remember(chips) { chips.withIndex().associate { it.value.offset to it.index } }

    fun target(offset: Int): Int? {
        val (x, w) = lefts[offset] ?: return null
        val raw = if (centre) {
            x + w / 2 - viewportW / 2
        } else {
            val i = indexOf[offset] ?: return null
            val lo = lefts[chips.getOrNull(i - 1)?.offset] ?: (x to w)
            val hi = lefts[chips.getOrNull(i + 1)?.offset] ?: (x to w)
            when {
                lo.first < scroll.value -> lo.first
                hi.first + hi.second > scroll.value + viewportW -> hi.first + hi.second - viewportW
                else -> scroll.value
            }
        }
        return raw.coerceIn(0, scroll.maxValue.coerceAtLeast(0))
    }

    // Entry and a new selection: the selected chip is centred (§3.1).
    LaunchedEffect(selectedOffset, chips.size, viewportW) {
        androidx.compose.runtime.withFrameNanos { }
        val (x, w) = lefts[selectedOffset] ?: return@LaunchedEffect
        scroll.scrollTo((x + w / 2 - viewportW / 2).coerceIn(0, scroll.maxValue.coerceAtLeast(0)))
    }
    // Focus moves the strip so the focused chip keeps its context.
    LaunchedEffect(focusedOffset) {
        val f = focusedOffset ?: return@LaunchedEffect
        target(f)?.let { scroll.animateScrollTo(it) }
    }

    Row(modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(40.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            if (!nfl && scroll.value > 0) Line("‹", GuideUi.sans(32, FontWeight.Medium, GuideUi.Fg55), Modifier.testTag(SPORTS_DAY_LEFT_ARROW_TAG))
        }
        Row(
            Modifier.weight(1f).fillMaxHeight().testTag(SPORTS_DAY_STRIP_TAG).onGloballyPositioned { viewportW = it.size.width }.horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            chips.forEach { chip ->
                val date = if (nfl) null else chip.key?.let { k -> runCatching { LocalDate.parse(k) }.getOrNull() }
                // (Review L6) A key that does not parse (never expected) still reads as a day ("Mon · Nov 23"), never an ISO date.
                val label = if (nfl) SportsText.hubWeekChip(res, chip.offset, chip.key)
                else SportsText.hubDayLabel(res, chip.offset, date ?: today.plusDays(chip.offset.toLong()))
                val desc = if (chip.noGames && date != null) res.getString(R.string.sports_hub_day_no_games_desc, SportsText.spokenDate(date)) else label
                val idx = indexOf.getValue(chip.offset)
                DayChipView(
                    label = label, desc = desc, tag = sportsDayTag(chip.offset), selected = chip.offset == selectedOffset, enabled = chip.enabled,
                    dim = chip.noGames,
                    requester = requesters.getValue(chip.offset),
                    up = up,
                    onLeft = { chips.getOrNull(idx - 1)?.takeIf { it.enabled }?.let { runCatching { requesters.getValue(it.offset).requestFocus() } } },
                    onRight = { chips.getOrNull(idx + 1)?.takeIf { it.enabled }?.let { runCatching { requesters.getValue(it.offset).requestFocus() } } },
                    onDown = onDown,
                    onFocus = { on -> if (on) focusedOffset = chip.offset else if (focusedOffset == chip.offset) focusedOffset = null },
                    onPlaced = { x, w -> lefts[chip.offset] = x to w },
                    onClick = { onDay(chip.offset) },
                )
            }
        }
        Box(Modifier.width(40.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            if (!nfl && scroll.value < scroll.maxValue) Line("›", GuideUi.sans(32, FontWeight.Medium, GuideUi.Fg55), Modifier.testTag(SPORTS_DAY_RIGHT_ARROW_TAG))
        }
    }
}

@Composable
private fun DayChipView(
    label: String,
    desc: String,
    tag: String,
    selected: Boolean,
    enabled: Boolean,
    dim: Boolean,
    requester: FocusRequester,
    up: FocusRequester,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onDown: () -> Boolean,
    onFocus: (Boolean) -> Unit,
    onPlaced: (Int, Int) -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(30.dp)
    val focusable = LocalHubFocusable.current
    val ink = when {
        selected -> Ink
        !enabled -> GuideUi.Fg.copy(alpha = 0.35f)
        dim -> GuideUi.Fg.copy(alpha = 0.40f)
        else -> GuideUi.Fg
    }
    Box(
        Modifier
            .testTag(tag)
            .height(60.dp)
            .onGloballyPositioned { onPlaced(it.positionInParent().x.toInt(), it.size.width) }
            .focusRequester(requester)
            .focusProperties { canFocus = enabled && focusable; this.up = up }
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .onPreviewKeyEvent { e ->
                when {
                    !focused -> false
                    e.key == Key.DirectionLeft -> { if (e.type == KeyEventType.KeyDown) onLeft(); true }
                    e.key == Key.DirectionRight -> { if (e.type == KeyEventType.KeyDown) onRight(); true }
                    e.key == Key.DirectionDown -> if (e.type == KeyEventType.KeyDown) onDown() else true
                    else -> false
                }
            }
            .background(if (selected) SelectedFill else if (focused) GuideUi.White16 else GuideUi.White08, shape)
            .let { if (focused) it.border(3.dp, GuideUi.Accent, shape) else it }
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { contentDescription = desc }
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Line(label, GuideUi.sans(24, FontWeight.SemiBold, ink))
        if (focused) Box(Modifier.align(Alignment.BottomCenter).width(48.dp).height(4.dp).background(GuideUi.Accent, RoundedCornerShape(2.dp)))
    }
}
