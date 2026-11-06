package app.thebox.ui.sports

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import app.thebox.core.sports.Box as DesignBox
import app.thebox.core.sports.CentreText
import app.thebox.core.sports.ClockAnchor
import app.thebox.core.sports.secsAt
import app.thebox.core.sports.OverlayGeometry
import app.thebox.core.sports.PlacementInput
import app.thebox.core.sports.ScoreBugPlacement
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.ScoreBugSource
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.TeamLine
import app.thebox.ui.R
import app.thebox.ui.guide.GuideDebugCounters
import app.thebox.ui.guide.screen.LocalShellImageLoader
import app.thebox.ui.full.PlayerUi
import app.thebox.ui.theme.BoxFonts
import app.thebox.ui.theme.DesignTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

const val SCORE_BUG_TAG = "score_bug"
/** The place-mode hint pill (§6). */
const val SCORE_BUG_HINT_TAG = "score_bug_hint"
/** Debug composition counter of [ScoreBug] (`GuideDebugCounters`; §4.7: zero per tick and per poll). */
const val COMPOSITIONS_SCORE_BUG = "ScoreBug"
/** Debug counter: draws of the clock node's own layer (§4.7, §8: 60 in 60 s). */
const val DRAWS_SCORE_CLOCK = "ScoreBugClockDraw"
/** Debug counter: changes of the displayed second, each of which invalidates only the clock layer (§4.7). */
const val TICKS_SCORE_CLOCK = "ScoreBugClockTick"

/**
 * The score bug (docs/design/m6-score-bug.md §4; WP-3): a 404 × 70 fake-glass pill at the box
 * `ScoreBugPlacement.place(placement, geometry)` returns (null = not drawn), sliding 150 ms between boxes (snapped in
 * [lowEnd]). `FullScreenHost` calls it after the bars and the stats, before the channel list, in FULL only, while
 * Show Sports is on. [ScoreBugSource.bug] and [ScoreBugSource.clock] are collected **here**, never in the host; the
 * clock is read only in the clock node's own layer (A-S10). With no followed game and [PlacementInput.placing], it
 * draws the sample bug ("AWY 0 | 0:00 | HME 0").
 *
 * Layers, bottom to top inside one pill-sized box: the baked glass bitmap, the static body (logos, scores, dividers, goal
 * box, period line, place ring), and the clock in its own `graphicsLayer` (the one node a tick re-records). [place] and
 * [mono] are test seams (the placement function, and `elapsedRealtime` for the clock anchor).
 */
@Composable
fun ScoreBug(
    source: ScoreBugSource,
    placement: PlacementInput,
    geometry: OverlayGeometry,
    lowEnd: Boolean,
    modifier: Modifier = Modifier,
    place: (PlacementInput, OverlayGeometry) -> DesignBox? = ScoreBugPlacement::place,
    mono: () -> Long = SystemClock::elapsedRealtime,
) {
    if (GuideDebugCounters.ENABLED) SideEffect { GuideDebugCounters.bump(COMPOSITIONS_SCORE_BUG) }
    val context = LocalContext.current
    val res = context.resources
    val d = LocalDensity.current.density // device px per design px (BoxDesignScale)

    val bug by source.bug.collectAsState()
    val view: ScoreBugView? = bug ?: if (placement.placing) sampleView(res) else null
    val box = if (view != null) place(placement, geometry) else null
    val large = placement.size == ScoreBugSize.LARGE

    val holder = remember { Holder() }
    if (box != null) { holder.box = box; holder.large = large }

    // ---- visibility: slide between boxes, fade in and out (snapped in low-end mode)
    val fade = remember { Animatable(0f) }
    val pos = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var fadeDone by remember { mutableStateOf(false) }
    LaunchedEffect(box != null, lowEnd) {
        if (box != null) {
            fadeDone = false
            if (lowEnd) fade.snapTo(1f) else fade.animateTo(1f, tween(PlayerUi.FADE_MS))
        } else {
            if (lowEnd) fade.snapTo(0f) else fade.animateTo(0f, tween(PlayerUi.FADE_MS))
            holder.positioned = false
            fadeDone = true
        }
    }
    LaunchedEffect(box, lowEnd) {
        if (box != null) {
            val o = Offset(box.l, box.t)
            if (lowEnd || !holder.positioned) pos.snapTo(o) else pos.animateTo(o, tween(K.SCORE_SLIDE_MS, easing = FastOutSlowInEasing))
            holder.positioned = true
        }
    }
    val last = holder.box
    val visible = box != null || (holder.drawn && !fadeDone)
    if (!visible || last == null) { holder.drawn = false; return }
    holder.drawn = true
    val shownLarge = holder.large

    // ---- what is drawn
    val k = if (shownLarge) K.SCORE_LARGE_SCALE else 1f
    val s = d * k
    val painter = remember(s, shownLarge) {
        ScoreBugPainter(
            s, sansTypeface(context, R.font.instrument_sans_semibold), sansTypeface(context, R.font.instrument_sans_bold),
            pillW = (if (shownLarge) K.SCORE_LARGE_W else K.SCORE_W) * d, pillH = (if (shownLarge) K.SCORE_LARGE_H else K.SCORE_H) * d,
        )
    }
    val glass = remember(painter, lowEnd) { painter.bakeGlass(withShadow = !lowEnd) }
    val shownView = view ?: holder.lastView ?: return
    holder.lastView = shownView
    val frame = remember(shownView, painter) { painter.frame(shownView, res) }

    // ---- logos: held for the life of the follow (keyed by the two URLs)
    val loader = LocalShellImageLoader.current
    val scope = rememberCoroutineScope()
    val logos = remember(loader) { FollowLogos(loader?.let { coilLogoFetch(it, context) }, scope) }
    val logoWaited = remember { mutableStateOf(false) }
    LaunchedEffect(shownView.away.logo, shownView.home.logo, s) {
        logos.start(
            shownView.away.logo, shownView.home.logo,
            (K.SCORE_LOGO_W * s).roundToInt().coerceAtLeast(1), (K.SCORE_LOGO_H * s).roundToInt().coerceAtLeast(1),
        )
        logoWaited.value = false
        delay(K.LOGO_WAIT_MS)
        logoWaited.value = true
    }
    DisposableEffect(logos) { onDispose { logos.release() } }

    // ---- goal accent: one Animatable read in draw (no box, and no motion, in low-end mode)
    val goal = remember { Animatable(0f) }
    if (holder.goalSeq < 0) holder.goalSeq = shownView.goalSeq
    LaunchedEffect(shownView.goalSeq, lowEnd) {
        if (shownView.goalSeq != holder.goalSeq) {
            holder.goalSeq = shownView.goalSeq
            if (!lowEnd && shownView.goalSide != null) {
                holder.goalSide = shownView.goalSide
                goal.snapTo(0f)
                goal.animateTo(1f, tween(K.GOAL_IN_MS))
                delay(K.GOAL_HOLD_MS.toLong())
                goal.animateTo(0f, tween(K.GOAL_OUT_MS))
            }
        }
    }

    // ---- the clock: collected here, never in the host; the second is state read only in the clock layer's draw
    val secs = remember { mutableIntStateOf(0) }
    LaunchedEffect(source) {
        source.clock.collectLatest { a ->
            if (a == null) { secs.intValue = 0; return@collectLatest }
            while (true) {
                val now = mono()
                val shown = ClockMath.secsAt(a, now)
                if (secs.intValue != shown) {
                    secs.intValue = shown
                    if (GuideDebugCounters.ENABLED) GuideDebugCounters.bump(TICKS_SCORE_CLOCK)
                }
                val next = ClockMath.nextTickAt(a, now) ?: break
                delay((next - now).coerceAtLeast(1L))
            }
        }
    }

    val w = if (shownLarge) K.SCORE_LARGE_W else K.SCORE_W
    val h = if (shownLarge) K.SCORE_LARGE_H else K.SCORE_H
    val placing = placement.placing
    val rightSide = last.l > 960f
    val desc = remember(shownView, res) { Describe(res, shownView) }

    Box(
        modifier
            .placeAt(holder, pos, d)
            .graphicsLayer { alpha = fade.value }
            .size(w.dp, h.dp),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .testTag(SCORE_BUG_TAG)
                .semantics(mergeDescendants = true) { contentDescription = desc.text(secs.intValue) },
        ) {
            Spacer(Modifier.fillMaxSize().drawBehind { drawIntoCanvas { painter.drawGlass(it.nativeCanvas, glass) } })
            Spacer(
                Modifier.fillMaxSize().drawBehind {
                    drawIntoCanvas {
                        painter.drawBody(it.nativeCanvas, frame, logos, logoWaited.value, goal.value, holder.goalSide, placing)
                    }
                },
            )
            if (frame.clockOnTop) {
                // The one node a tick re-records: its own layer, and the only reader of `secs`.
                Spacer(
                    Modifier.fillMaxSize().graphicsLayer { }.drawBehind {
                        if (GuideDebugCounters.ENABLED) GuideDebugCounters.bump(DRAWS_SCORE_CLOCK)
                        drawIntoCanvas { painter.drawClock(it.nativeCanvas, secs.intValue, frame) }
                    },
                )
            }
        }
        if (placing) PlaceHint(rightSide, w)
    }
}

/** Plain holder for what must survive recomposition without being snapshot state (writes happen in composition). */
private class Holder {
    var box: DesignBox? = null
    var large = false
    var positioned = false
    var drawn = false
    var lastView: ScoreBugView? = null
    var goalSeq = -1
    var goalSide: Side? = null
}

private fun Modifier.placeAt(holder: Holder, pos: Animatable<Offset, *>, d: Float): Modifier = layout { m, c ->
    val p = m.measure(c)
    layout(p.width, p.height) {
        val o = if (holder.positioned) pos.value else holder.box?.let { Offset(it.l, it.t) } ?: Offset.Zero
        p.place(IntOffset((o.x * d).roundToInt(), (o.y * d).roundToInt()))
    }
}

/** "← → ↑ ↓ Move · OK Done", 22 px on PanelFill, beside the bug toward the screen centre (§6, U-B1). */
@Composable
private fun PlaceHint(bugOnRight: Boolean, bugW: Float) {
    val hintDesc = androidx.compose.ui.res.stringResource(R.string.sports_place_hint_desc)
    Box(
        Modifier
            .layout { m, c ->
                val p = m.measure(Constraints())
                // The hint is outside the pill's box: place it in the parent's coordinates.
                layout(0, 0) {
                    val gap = (K.SCORE_BAR_GAP * density).roundToInt()
                    val x = if (bugOnRight) -p.width - gap else (bugW * density).roundToInt() + gap
                    p.place(x, (c.maxHeight - p.height) / 2)
                }
            }
            .testTag(SCORE_BUG_HINT_TAG)
            .semantics { contentDescription = hintDesc }
            .background(PlayerUi.PanelFill, RoundedCornerShape(50))
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        BasicText(
            androidx.compose.ui.res.stringResource(R.string.sports_place_hint),
            style = TextStyle(
                fontFamily = BoxFonts.Sans, fontSize = K.PLACE_HINT_PX.sp, fontWeight = FontWeight.Medium,
                color = DesignTokens.Colors.Fg,
            ),
        )
    }
}

internal fun sansTypeface(context: android.content.Context, font: Int): Typeface? = try {
    ResourcesCompat.getFont(context, font)
} catch (_: Throwable) {
    null
}

private fun sampleView(res: Resources) = ScoreBugView(
    away = TeamLine(res.getString(R.string.sports_sample_away), res.getString(R.string.sports_sample_away), 0, null),
    home = TeamLine(res.getString(R.string.sports_sample_home), res.getString(R.string.sports_sample_home), 0, null),
    top = CentreText.Clock,
    bottom = null,
    dim = false,
    goalSeq = 0,
    goalSide = null,
)

/**
 * The displayed second of a [ClockAnchor] (§2.7, §4.7). [secsAt] is WP-1's `ClockAnchor.secsAt` (one rule for the machine,
 * the bug and the cards: counts down 1/s while ticking, floored at 0, frozen at `stopAtMono`). Pure.
 */
internal object ClockMath {
    fun secsAt(a: ClockAnchor, now: Long): Int = a.secsAt(now)

    /** The next instant the displayed second changes, or null when it never will (not ticking, at 0, past the cap). */
    fun nextTickAt(a: ClockAnchor, now: Long): Long? {
        if (!a.ticking) return null
        if (secsAt(a, now) <= 0) return null
        val elapsed = (now - a.atMono).coerceAtLeast(0L)
        val next = a.atMono + (elapsed / 1000L + 1L) * 1000L
        val stop = a.stopAtMono
        return if (stop != null && next > stop) null else next
    }
}

/** The merged semantics string of a state (U-N12): "Calgary 0, Edmonton 2, 10:22 left in the 1st". */
internal class Describe(private val res: Resources, private val v: ScoreBugView) {
    fun text(secs: Int): String {
        val a = v.away
        val h = v.home
        fun f(id: Int, vararg extra: Any) = res.getString(id, a.name, a.score ?: 0, h.name, h.score ?: 0, *extra)
        val b = v.bottom
        return when {
            b is CentreText.Goal -> f(R.string.sports_desc_goal, (if (b.side == Side.AWAY) a else h).abbrev)
            v.top is CentreText.Shootout -> f(R.string.sports_desc_shootout)
            v.top is CentreText.Final -> f(R.string.sports_desc_final, SportsText.final(res, (v.top as CentreText.Final).kind))
            b is CentreText.NoUpdate -> f(R.string.sports_desc_no_update)
            b is CentreText.Delayed -> f(R.string.sports_desc_delayed)
            b is CentreText.Intermission -> f(R.string.sports_desc_intermission, SportsText.period(res, b.period), SportsText.clock(secs))
            b is CentreText.EndOf -> f(R.string.sports_desc_end_of, SportsText.period(res, b.period))
            b is CentreText.Period -> f(R.string.sports_desc_live, SportsText.clock(secs), SportsText.period(res, b.period))
            // A clock with no period line (the place-mode sample): just the score.
            else -> res.getString(R.string.sports_quick_score, a.abbrev, a.score ?: 0, h.score ?: 0, h.abbrev)
        }
    }
}
