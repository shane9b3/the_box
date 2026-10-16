package app.thebox.ui.full

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.thebox.core.full.ScrubFocus
import app.thebox.core.full.ScrubState
import app.thebox.core.guide.GuideChannel
import app.thebox.ui.R
import app.thebox.ui.guide.screen.Chip
import app.thebox.ui.guide.screen.GuideUi
import app.thebox.ui.guide.screen.Line
import java.util.TimeZone

const val SCRUB_BAR_TAG = "player_scrub_bar"
const val SCRUB_TRACK_TAG = "player_scrub_track"
const val SCRUB_CHIPS_TAG = "player_scrub_chips"

/**
 * The archive / time-shift bar (docs/design/m4-player-ux.md §3.5, §3.9): the info bar's header (with the scoped
 * programme's title), then the track drawn by [ScrubBarRenderer] as **one** draw node, then the two chips under the
 * labels while [ScrubState.focus] is CHIPS (no Options chip: owner ruling). The caller places the bar at (64, 820)
 * 1792 × 220; the track lands at (128, 952) 1664 × 6 because the node is placed at an absolute offset inside the bar.
 *
 * Redraws (§4.1): the node reads a 1 Hz tick **only in its draw lambda** while the playhead advances or the pill grows,
 * so composition is untouched between status events; [nowMs] seeds the tick.
 */
@Composable
fun ScrubBar(state: ScrubState, nowMs: Long, modifier: Modifier = Modifier, channel: GuideChannel? = null, clock24: Boolean = true) {
    val context = LocalContext.current
    val scale = LocalDensity.current.density
    val catchup = stringResource(R.string.player_scrub_catchup)
    val live = stringResource(R.string.player_scrub_live)
    val renderer = remember(scale) { ScrubBarRenderer(scale).apply { setTypeface(monoTypeface(context)) } }
    renderer.clock24 = clock24
    renderer.zone = remember { TimeZone.getDefault() }
    renderer.setLabels(catchup, live)
    val moving = state.advancing || state.pill.behindGrows || state.atLive
    val tick = rememberTicker(1_000L, enabled = moving)
    val shape = RoundedCornerShape(PlayerUi.BAR_R.dp)
    Box(
        modifier
            .testTag(SCRUB_BAR_TAG)
            .size(PlayerUi.BAR_W.dp, PlayerUi.BAR_H.dp)
            .background(PlayerUi.BarFill, shape)
            .border(1.dp, PlayerUi.Border09, shape),
    ) {
        Column(Modifier.padding(horizontal = PlayerUi.BAR_PAD_H.dp, vertical = PlayerUi.BAR_PAD_V.dp)) {
            BarHeader(channel, state.pill, glyphs = null, title = state.programme?.title)
        }
        Spacer(
            Modifier
                .offset(0.dp, (PlayerUi.SCRUB_NODE_T - PlayerUi.BAR_T).dp)
                .size(PlayerUi.BAR_W.dp, PlayerUi.SCRUB_NODE_H.dp)
                .testTag(SCRUB_TRACK_TAG)
                .drawBehind {
                    // U-S5: the wall clock is read here, in the draw phase (the tick only invalidates the node).
                    val now = maxOf(tick.value, nowMs, System.currentTimeMillis())
                    drawIntoCanvas {
                        android.os.Trace.beginSection(TRACE_SCRUB) // U-S6 (L-14)
                        try { renderer.draw(it.nativeCanvas, state, now, size.width, size.height) } finally { android.os.Trace.endSection() }
                    }
                },
        )
        if (state.focus == ScrubFocus.CHIPS) {
            Row(
                Modifier
                    .offset(PlayerUi.TRACK_L.dp - PlayerUi.BAR_L.dp, (CHIPS_T - PlayerUi.BAR_T).dp)
                    .testTag(SCRUB_CHIPS_TAG),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.prev?.let { p ->
                    ScrubChip(stringResource(R.string.player_scrub_prev_chip, clockOfMin(p.startMin, clock24), p.title), state.chipFocused == 0)
                }
                state.next?.let { p ->
                    ScrubChip(stringResource(R.string.player_scrub_next_chip, clockOfMin(p.startMin, clock24), p.title), state.chipFocused == 1)
                }
            }
        }
    }
}

@Composable
private fun ScrubChip(text: String, focused: Boolean) {
    Chip(focused, fill = PlayerUi.ChipFill, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 5.dp)) { c ->
        Line(text, GuideUi.sans(17, FontWeight.Medium, c))
    }
}

/** Top of the chips row (design px): under the labels. */
private const val CHIPS_T = 998f
