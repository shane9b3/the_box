package app.thebox.ui.guide.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.Zone
import app.thebox.ui.R
import app.thebox.ui.guide.overlay.SyncChipView
import app.thebox.ui.theme.BoxTextStyles
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val HEADER_TABS_TAG = "header_tabs"

/** Tabs in their order (§3.4.3): Guide · Search · Settings gear. Recordings is hidden until v2 (plan §2). */
enum class GuideTab { GUIDE, SEARCH, SETTINGS }

/**
 * The header row at (832, 56) → 1856 (docs/design/m3-guide.md §3.15): wordmark, tab pills, sync chip, then the date
 * and clock right-aligned. Active tab = 12 % fill + 16 % border; focused (TABS zone) = solid FG + accent ring. The clock
 * recomposes once a minute from its own minute-aligned producer, not from `GuideUiState` (§3.5.2).
 */
@Composable
fun HeaderTabs(state: GuideUiState, clock24: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().testTag(HEADER_TABS_TAG), verticalAlignment = Alignment.CenterVertically) {
        Wordmark()
        Row(Modifier.padding(start = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GuideTab.entries.forEachIndexed { i, tab ->
                val focused = state.zone == Zone.TABS && state.tabFocused == i
                val active = tab == GuideTab.GUIDE
                Chip(
                    focused = focused,
                    fill = if (active) GuideUi.White12 else Color.Transparent,
                    border = if (active) GuideUi.White16 else null,
                ) { contentColor ->
                    val color = if (focused) contentColor else if (active) GuideUi.Fg else GuideUi.Fg60
                    when (tab) {
                        GuideTab.GUIDE -> Line(stringResource(R.string.guide_tab_guide), GuideUi.sans(18, FontWeight.Medium, color))
                        GuideTab.SEARCH -> Line(stringResource(R.string.guide_tab_search), GuideUi.sans(18, FontWeight.Medium, color))
                        GuideTab.SETTINGS -> {
                            val desc = stringResource(R.string.guide_tab_settings)
                            Gear(color, Modifier.size(22.dp).semantics { contentDescription = desc })
                        }
                    }
                }
            }
        }
        state.sync?.let { SyncChipView(state, Modifier.padding(start = 16.dp)) }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Bottom) {
            Clock(clock24)
        }
    }
}

/** White wordmark, 28 high. WP-E's `drawable/wordmark` vector replaces this text when it lands (integrator). */
@Composable
private fun Wordmark() {
    Line(stringResource(R.string.guide_wordmark), GuideUi.sans(26, FontWeight.Bold, GuideUi.Fg, -0.02f))
}

@Composable
private fun Clock(clock24: Boolean) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000L - value % 60_000L)
        }
    }
    val date = Date(now)
    val dateText = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(date)
    val timeText = SimpleDateFormat(if (clock24) "HH:mm" else "h:mm a", Locale.getDefault()).format(date)
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
        Line(dateText, GuideUi.sans(18, color = GuideUi.Fg65), Modifier.padding(bottom = 6.dp))
        Line(timeText, BoxTextStyles.Clock.copy(color = GuideUi.Fg))
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
