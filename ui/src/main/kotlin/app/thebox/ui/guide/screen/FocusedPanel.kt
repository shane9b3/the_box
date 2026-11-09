package app.thebox.ui.guide.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.PanelState
import app.thebox.ui.R
import app.thebox.ui.settings.animationsOff
import app.thebox.ui.sports.motionOff
import app.thebox.ui.theme.BoxTextStyles
import app.thebox.ui.theme.DesignTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

const val FOCUSED_PANEL_TAG = "focused_panel"
const val PANEL_DESCRIPTION_TAG = "focused_panel_description"
/** The meta row (genre, ON NOW); absent when it would be empty. */
const val PANEL_META_TAG = "focused_panel_meta"

/**
 * The focused-programme panel, top-aligned in the header region under the tabs (docs/design/m3-guide.md §3.9.1,
 * §3.15; owner request 2026-11-09). Drawn from [GuideUiState.panel]; the description only arrives after the 150 ms
 * debounce (B2.8). (owner ruling 2026-11-01) No buttons. (owner request 2026-11-09,
 * docs/decisions/2026-11-09-guide-panel-description.md) The meta row shows the genre and ON NOW only (no channel number,
 * name or catch-up days, although [PanelState] still carries them), and the description fills the rest of the header
 * area: all of it when it fits; otherwise it scrolls slowly when [scrollDescription] is on (and the system animations
 * are on), or ends in an ellipsis.
 */
/** Debug composition counter key (I-12, §2.13 item 20). */
const val COMPOSITIONS_PANEL = "FocusedPanel"

@Composable
fun FocusedPanel(panel: PanelState?, modifier: Modifier = Modifier, scrollDescription: Boolean = true) {
    if (app.thebox.ui.guide.GuideDebugCounters.ENABLED) androidx.compose.runtime.SideEffect { app.thebox.ui.guide.GuideDebugCounters.bump(COMPOSITIONS_PANEL) } // I-12
    if (panel == null) return
    // Owner request 2026-10-18: poster art (the programme's EPG icon) on the right, the text column beside it.
    Row(modifier.testTag(FOCUSED_PANEL_TAG), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MetaRow(panel)
            val titleStyle = remember { BoxTextStyles.Title.copy(color = GuideUi.Fg, lineBreak = LineBreak.Heading) }
            Line(panel.title, titleStyle, maxLines = 2)
            Line(panel.timeLine, GuideUi.mono(24, color = GuideUi.Fg.copy(alpha = 0.75f)))
            panel.description?.let { desc ->
                // A new programme (or a new description) starts the scroll again from the top.
                key(panel.title, panel.timeLine, desc) {
                    PanelDescription(desc, scrollDescription, Modifier.weight(1f).widthIn(max = 980.dp))
                }
            }
        }
        panel.imageUrl?.let { Poster(it, panel.title) }
    }
}

/** The scroll's timing (owner request 2026-11-09, lead decision 3). */
internal object DescriptionScroll {
    const val HOLD_MS = 3_000L
    /** Design px per second, linear. */
    const val SPEED = 24f
    /** Fade back to the top: out, jump, in. */
    const val FADE_MS = 400L
    /** The soft edge of the viewport, design px. */
    const val EDGE = 24
}

/** Per-description scroll state. Every field is a primitive snapshot state: a frame writes floats, it allocates nothing. */
private class DescriptionScrollState {
    var offset by mutableFloatStateOf(0f)
    var alpha by mutableFloatStateOf(1f)
    var textH by mutableIntStateOf(0)
    var viewH by mutableIntStateOf(0)
    var max by mutableFloatStateOf(0f)
    private var frameNs = 0L
    private val onFrame: (Long) -> Unit = { frameNs = it }

    private suspend fun frame(): Long { withFrameNanos(onFrame); return frameNs }

    /** Runs [ms] of frames; [step] gets the fraction 0..1. */
    private suspend inline fun tween(ms: Long, step: (Float) -> Unit) {
        val start = frame()
        while (true) {
            val t = ((frame() - start) / 1_000_000f / ms).coerceAtMost(1f)
            step(t)
            if (t >= 1f) return
        }
    }

    /** Hold, scroll at [pxPerMs], hold, fade back to the top; restarts whenever the overflow changes. */
    suspend fun run(pxPerMs: Float) {
        snapshotFlow { (textH - viewH).coerceAtLeast(0) }.collectLatest { over ->
            offset = 0f; alpha = 1f; max = over.toFloat()
            if (over <= 0 || motionOff()) return@collectLatest
            val scrollMs = (over / pxPerMs).toLong().coerceAtLeast(1L)
            while (true) {
                delay(DescriptionScroll.HOLD_MS)
                // M8 (performance review 2026-11-09): whole pixels only. An equal write does not invalidate, so the
                // layer re-draws about SPEED times a second instead of every vsync, with the same pixels and timing.
                tween(scrollMs) { t -> offset = (over * t).roundToInt().toFloat() }
                delay(DescriptionScroll.HOLD_MS)
                tween(DescriptionScroll.FADE_MS / 2) { t -> alpha = 1f - t }
                offset = 0f
                tween(DescriptionScroll.FADE_MS / 2) { t -> alpha = t }
            }
        }
    }
}

/**
 * The description viewport: all the height left under the time line. Off (or animations off): every line that fits,
 * the last one ellipsized. On: the text is laid out whole, clipped, and moved by a layout-phase offset from a
 * [LaunchedEffect], so the scroll re-records only this text inside the header column's own layer; it never recomposes.
 * The soft top/bottom edges are a `DstIn` gradient mask on this viewport only (no blur; not an ancestor of the video).
 */
@Composable
private fun PanelDescription(text: String, scroll: Boolean, modifier: Modifier) {
    val style = remember { BoxTextStyles.Body.copy(color = GuideUi.Fg.copy(alpha = 0.78f)) }
    if (!scroll || animationsOff()) {
        Text(text, modifier.fillMaxWidth().testTag(PANEL_DESCRIPTION_TAG), style = style, overflow = TextOverflow.Ellipsis)
        return
    }
    val state = remember { DescriptionScrollState() }
    val density = LocalDensity.current
    val pxPerMs = with(density) { DescriptionScroll.SPEED.dp.toPx() } / 1000f
    val edge = with(density) { DescriptionScroll.EDGE.dp.toPx() }
    LaunchedEffect(state, pxPerMs) { state.run(pxPerMs) }
    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { state.viewH = it.height }
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                alpha = state.alpha
            }
            .clipToBounds()
            .drawWithCache {
                val top = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = edge)
                val bottom = Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = size.height - edge, endY = size.height)
                val band = Size(size.width, edge)
                val bottomAt = Offset(0f, size.height - edge)
                onDrawWithContent {
                    drawContent()
                    val o = state.offset
                    if (state.max > 0f) {
                        if (o > 0.5f) drawRect(top, size = band, blendMode = BlendMode.DstIn)
                        if (o < state.max - 0.5f) drawRect(bottom, topLeft = bottomAt, size = band, blendMode = BlendMode.DstIn)
                    }
                }
            },
    ) {
        Text(
            text,
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .offset { IntOffset(0, -state.offset.roundToInt()) }
                .testTag(PANEL_DESCRIPTION_TAG),
            style = style,
            overflow = TextOverflow.Clip,
            onTextLayout = { state.textH = it.size.height },
        )
    }
}

/** The programme's artwork: a 2:3 card, 200 × 300 design px, fitted (posters and 16:9 stills both read). */
@Composable
private fun Poster(url: String, title: String) {
    val loader = LocalShellImageLoader.current ?: return
    Box(
        Modifier
            .size(PANEL_POSTER_W.dp, PANEL_POSTER_H.dp)
            .background(GuideUi.White08, RoundedCornerShape(DesignTokens.Radii.Chip))
            .testTag(PANEL_POSTER_TAG),
        contentAlignment = Alignment.Center,
    ) {
        coil3.compose.AsyncImage(
            model = url,
            contentDescription = title,
            imageLoader = loader,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(DesignTokens.Radii.Chip)),
        )
    }
}

const val PANEL_POSTER_TAG = "focused_panel_poster"
private const val PANEL_POSTER_W = 200
private const val PANEL_POSTER_H = 300

@Composable
private fun MetaRow(panel: PanelState) {
    // (owner request 2026-11-09) No channel number, name or catch-up days; an empty row takes no space.
    val genre = panel.genre?.takeIf { it.isNotBlank() }
    if (genre == null && !panel.onNow) return
    Row(Modifier.testTag(PANEL_META_TAG), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        genre?.let { g -> Line(g, GuideUi.sans(24, color = GuideUi.Fg70)) }
        if (panel.onNow) {
            Box(
                Modifier
                    .background(DesignTokens.Colors.OnNowFill, RoundedCornerShape(DesignTokens.Radii.Pill))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) { Line(stringResource(R.string.guide_on_now), GuideUi.sans(20, FontWeight.SemiBold, DesignTokens.Colors.AccentText, 0.06f)) }
        }
    }
}
