package app.thebox.ui.guide.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.SyncChip
import app.thebox.core.guide.Zone
import app.thebox.ui.R
import app.thebox.ui.guide.overlay.SyncChipView
import app.thebox.ui.theme.BoxTextStyles
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale

const val HEADER_TABS_TAG = "header_tabs"
internal const val HEADER_CLOCK_TAG = "header_clock"
internal const val HEADER_DATE_TAG = "header_date"
/** (docs/design/m6-score-bug.md U-N10) The date gives way before the sync chip does: four tabs fit at 1024 with the longest status. */
private const val HEADER_DATE_MAX_W = 220f

/** Tabs in their order (§3.4.3): Guide · Search · Settings gear. Recordings is hidden until v2 (plan §2). */
/**
 * (docs/design/m6-score-bug.md §5.1; append-only) SPORTS is drawn between Search and the gear (`TAB_COUNT` 4), so the
 * enum order is not the display order: [SHOWN_TABS] is.
 */
enum class GuideTab { GUIDE, SEARCH, SETTINGS, SPORTS }

/** The tabs drawn, in display order (index = `GuideUiState.tabFocused`; `GuideStateMachineImpl.TAB_*`). */
internal val SHOWN_TABS: List<GuideTab> = listOf(GuideTab.GUIDE, GuideTab.SEARCH, GuideTab.SPORTS, GuideTab.SETTINGS)

/**
 * The header row at (832, 56) → 1856 (docs/design/m3-guide.md §3.15): wordmark, tab pills, sync chip, then the date
 * and clock right-aligned. Active tab = 12 % fill + 16 % border; focused (TABS zone) = solid FG + accent ring. The clock
 * recomposes once a minute from its own minute-aligned producer, not from `GuideUiState` (§3.5.2).
 */
@Composable
fun HeaderTabs(zone: Zone, tabFocused: Int, sync: SyncChip?, clock24: Boolean, modifier: Modifier = Modifier) {
    HeaderTabsRow(zone, tabFocused, sync, modifier) { Clock(clock24) }
}

/** The status chip uses spare width; navigation and the current time retain their intrinsic sizes. */
@Composable
internal fun HeaderTabsRow(zone: Zone, tabFocused: Int, sync: SyncChip?, modifier: Modifier = Modifier, clock: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().testTag(HEADER_TABS_TAG), verticalAlignment = Alignment.CenterVertically) {
        Wordmark()
        Row(Modifier.padding(start = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SHOWN_TABS.forEachIndexed { i, tab ->
                val focused = zone == Zone.TABS && tabFocused == i
                val active = tab == GuideTab.GUIDE
                Chip(
                    focused = focused,
                    fill = if (active) GuideUi.White12 else Color.Transparent,
                    border = if (active) GuideUi.White16 else null,
                ) { contentColor ->
                    val color = if (focused) contentColor else if (active) GuideUi.Fg else GuideUi.Fg60
                    when (tab) {
                        GuideTab.GUIDE -> Line(stringResource(R.string.guide_tab_guide), GuideUi.sans(24, FontWeight.Medium, color))
                        GuideTab.SEARCH -> Line(stringResource(R.string.guide_tab_search), GuideUi.sans(24, FontWeight.Medium, color))
                        GuideTab.SPORTS -> Line(stringResource(R.string.sports_tab), GuideUi.sans(24, FontWeight.Medium, color))
                        GuideTab.SETTINGS -> {
                            val desc = stringResource(R.string.guide_tab_settings)
                            Gear(color, Modifier.size(22.dp).semantics { contentDescription = desc })
                        }
                    }
                }
            }
        }
        Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            sync?.let { SyncChipView(it) }
        }
        clock()
    }
}

/** WP-E's white wordmark vector (§3.17), 28 high, tinted `FG` (§2.13 item 16). */
@Composable
private fun Wordmark() {
    Image(
        painter = painterResource(R.drawable.wordmark),
        contentDescription = stringResource(R.string.guide_wordmark),
        colorFilter = ColorFilter.tint(GuideUi.Fg),
        modifier = Modifier.height(WORDMARK_H.dp).width((WORDMARK_H * WORDMARK_ASPECT).dp),
    )
}

/** Design px; the vector's viewport is 1158 × 480. */
private const val WORDMARK_H = 28f
private const val WORDMARK_ASPECT = 1158f / 480f

@Composable
private fun Clock(clock24: Boolean) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000L - value % 60_000L)
        }
    }
    val date = Date(now)
    // The configuration's locale (observable: a locale change recomposes); lint NonObservableLocale.
    val locale: Locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    // H-S9: formatters cached per (locale, zone, clock form), not built on every recomposition.
    val dateText = TimeFormats.format("EEE d MMM", locale, date)
    val timeText = TimeFormats.format(if (clock24) "HH:mm" else "h:mm a", locale, date)
    HeaderClock(dateText, timeText)
}

@Composable
internal fun HeaderClock(dateText: String, timeText: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
        // Measure the time first. A long localized date may ellipsize, but it cannot squeeze the time away.
        Line(dateText, GuideUi.sans(24, color = GuideUi.Fg65), Modifier.weight(1f, fill = false).widthIn(max = HEADER_DATE_MAX_W.dp).padding(bottom = 6.dp).testTag(HEADER_DATE_TAG))
        Line(timeText, BoxTextStyles.Clock.copy(color = GuideUi.Fg), Modifier.testTag(HEADER_CLOCK_TAG))
    }
}

/** Settings gear (no icon font is bundled): a ring with eight teeth. */
@Composable
private fun Gear(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension * 0.30f
        val tooth = size.minDimension * 0.13f
        for (k in 0 until 8) {
            rotate(k * 45f, c) {
                drawRect(color, topLeft = Offset(c.x - tooth / 2f, c.y - r - tooth), size = androidx.compose.ui.geometry.Size(tooth, tooth * 1.4f))
            }
        }
        drawCircle(color, radius = r, center = c, style = Stroke(width = size.minDimension * 0.14f))
    }
}
