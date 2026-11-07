package app.thebox.ui.sports

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
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
import kotlin.math.roundToInt

const val SCORE_BUG_TAG = "score_bug"
/** The place-mode hint pill (§6). */
const val SCORE_BUG_HINT_TAG = "score_bug_hint"
/** Debug composition counter of [ScoreBug] (`GuideDebugCounters`; §4.7: zero per host recomposition, one per snapshot). */
const val COMPOSITIONS_SCORE_BUG = "ScoreBug"
/** Debug counter: builds of the bug's resolved words ([ScoreBugFrame]); one per change of what is shown, never per second. */
const val FRAMES_SCORE_BUG = "ScoreBugFrame"
/** Debug counter: draws of the bug's one drawing node (glass, body and clock). */
const val DRAWS_SCORE_BUG = "ScoreBugDraw"

/**
 * The score bug (docs/design/m6-score-bug.md §4; WP-3): a 404 × 70 fake-glass pill at the box
 * `ScoreBugPlacement.place(placement, geometry)` returns (null = not drawn), sliding 150 ms between boxes (snapped in
 * [lowEnd]). `FullScreenHost` calls it after the bars and the stats, before the channel list, in FULL only, while
 * Show Sports is on. [ScoreBugSource.bug] is collected **here**, never in the host. With no followed game and
 * [PlacementInput.placing], it draws the sample bug ("AWY 0 | 0:00 | HME 0").
 *
 * **No local countdown** (owner ruling 6, fix pass 2026-11-05): the clock is [ScoreBugView.clockSecs], the snapshot's own
 * value, so the bug recomposes and redraws only when a new snapshot (or state) becomes visible; nothing ticks between
 * them. One drawing node: [ScoreBugPainter.drawFrame] (the glass bitmap, the body: logos, scores, dividers, centre lines,
 * clock, place ring; and, from the goal animation's WP-2/WP-3, the takeover).
 *
 * (Goal animation, docs/design/m6-goal-animation.md §1) The bug's own leaf layer is bounded and clipped: the pill plus the
 * margin `M` ([K.GOAL_LAYER_MARGIN]) on every side, placed at the pill's box − `M`, so the glass shadow and the glow are
 * never cut during a fade and a takeover frame damages only that rect. [SCORE_BUG_TAG] stays on the pill-sized node. The
 * place-mode hint is emitted as a sibling of that layer (in `FullScreenHost`'s box), so the clip never cuts it.
 *
 * Held across hides (U-S1): the painter, the baked glass and the follow's [FollowLogos] stay while this composable is in
 * the composition (FULL with Show Sports on), so a reappearance after the passive info bar, a list or a toast neither
 * re-bakes the glass nor re-fetches the logos. The logos are released when the follow's logo URLs change.
 * [place] is a test seam (the placement function).
 */
@Composable
fun ScoreBug(
    source: ScoreBugSource,
    placement: PlacementInput,
    geometry: OverlayGeometry,
    lowEnd: Boolean,
    modifier: Modifier = Modifier,
    place: (PlacementInput, OverlayGeometry) -> DesignBox? = ScoreBugPlacement::place,
) {
    if (GuideDebugCounters.ENABLED) SideEffect { GuideDebugCounters.bump(COMPOSITIONS_SCORE_BUG) }
    val context = LocalContext.current
    val res = context.resources
    val d = LocalDensity.current.density // device px per design px (BoxDesignScale)

    val bug by source.bug.collectAsState()
    val sample = remember(res) { sampleView(res) }
    val view: ScoreBugView? = bug ?: if (placement.placing) sample else null
    val box = if (view != null) place(placement, geometry) else null
    val large = placement.size == ScoreBugSize.LARGE

    val holder = remember { Holder() }
    if (box != null) { holder.box = box; holder.large = large }
    if (view != null) holder.lastView = view
    bug?.let { holder.lastFollowed = it }

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

    // ---- held across hides (U-S1): above the visibility return, so hiding never drops them
    val shownLarge = holder.large
    val k = if (shownLarge) K.SCORE_LARGE_SCALE else 1f
    val s = d * k
    val painter = remember(s, shownLarge) {
        ScoreBugPainter(
            s, sansTypeface(context, R.font.instrument_sans_semibold), sansTypeface(context, R.font.instrument_sans_bold),
            pillW = (if (shownLarge) K.SCORE_LARGE_W else K.SCORE_W) * d, pillH = (if (shownLarge) K.SCORE_LARGE_H else K.SCORE_H) * d,
        )
    }
    // Baked on the first draw that needs it, then kept (never in a frame where the bug is not drawn).
    val glass = remember(painter, lowEnd) { GlassCache(painter, withShadow = !lowEnd) }
    val loader = LocalShellImageLoader.current
    val scope = rememberCoroutineScope()
    val logos = remember(loader) { FollowLogos(loader?.let { coilLogoFetch(it, context) }, scope) }
    DisposableEffect(logos) { onDispose { logos.release() } }
    val logoWaited = remember { mutableStateOf(false) }
    val followed = holder.lastFollowed
    LaunchedEffect(followed?.away?.logo, followed?.home?.logo, s) {
        if (followed == null) return@LaunchedEffect
        logos.start(
            followed.away.logo, followed.home.logo,
            (K.SCORE_LOGO_W * s).roundToInt().coerceAtLeast(1), (K.SCORE_LOGO_H * s).roundToInt().coerceAtLeast(1),
        )
        logoWaited.value = false
        delay(K.LOGO_WAIT_MS)
        logoWaited.value = true
    }

    val last = holder.box
    val visible = box != null || (holder.drawn && !fadeDone)
    if (!visible || last == null) { holder.drawn = false; return }
    holder.drawn = true

    // ---- what is drawn: rebuilt only when the shown view changes (a new snapshot or state), never per second
    val shownView = view ?: holder.lastView ?: return
    val frame = remember(shownView, painter) {
        if (GuideDebugCounters.ENABLED) GuideDebugCounters.bump(FRAMES_SCORE_BUG)
        painter.frame(shownView, res)
    }
    // The sample bug ("AWY | HME") never borrows the followed game's logos.
    val shownLogos = if (shownView === sample) null else logos

    val w = if (shownLarge) K.SCORE_LARGE_W else K.SCORE_W
    val h = if (shownLarge) K.SCORE_LARGE_H else K.SCORE_H
    val placing = placement.placing
    val rightSide = last.l > 960f
    // U-S7: a plain string per shown view; semantics read nothing that changes between snapshots.
    val desc = remember(shownView, res) { Describe(res, shownView).text() }

    val m = if (shownLarge) K.GOAL_LAYER_MARGIN_LARGE else K.GOAL_LAYER_MARGIN
    Box(
        modifier
            .placeAt(holder, pos, d, m)
            // A leaf layer (never an ancestor of the SurfaceView): the fade, clipped to the pill + M (goal design §1, S3).
            .graphicsLayer { alpha = fade.value; clip = true }
            .size((w + 2 * m).dp, (h + 2 * m).dp),
    ) {
        Spacer(
            Modifier
                .offset(m.dp, m.dp)
                .size(w.dp, h.dp)
                .testTag(SCORE_BUG_TAG)
                .semantics { contentDescription = desc }
                .drawBehind {
                    if (GuideDebugCounters.ENABLED) GuideDebugCounters.bump(DRAWS_SCORE_BUG)
                    drawIntoCanvas {
                        painter.drawFrame(
                            it.nativeCanvas, glass.bitmap, frame, shownLogos, logoWaited.value, placing,
                            goal = null, g = null, glow = null, glowOn = false, // the takeover: WP-3
                        )
                    }
                },
        )
    }
    // A sibling of the clipped layer, not inside it (goal design §1): same position source, same look.
    if (placing) PlaceHint(holder, pos, fade, rightSide, w, h)
}

/** The baked glass of one painter and mode (U-S1): built on first use, then held. */
private class GlassCache(private val painter: ScoreBugPainter, private val withShadow: Boolean) {
    val bitmap: Bitmap by lazy(LazyThreadSafetyMode.NONE) { painter.bakeGlass(withShadow) }
}

/** Plain holder for what must survive recomposition without being snapshot state (writes happen in composition). */
private class Holder {
    var box: DesignBox? = null
    var large = false
    var positioned = false
    var drawn = false
    var lastView: ScoreBugView? = null
    /** The last real (not sample) view: its logo URLs key the held [FollowLogos]. */
    var lastFollowed: ScoreBugView? = null

    /** The pill's top-left in design px: the sliding position once positioned, else the placed box. */
    fun pillOffset(pos: Animatable<Offset, *>): Offset =
        if (positioned) pos.value else box?.let { Offset(it.l, it.t) } ?: Offset.Zero
}

/** Places the bug's layer so the pill (inset by [margin] design px inside it) lands on the pill's box. */
private fun Modifier.placeAt(holder: Holder, pos: Animatable<Offset, *>, d: Float, margin: Float): Modifier = layout { m, c ->
    val p = m.measure(c)
    layout(p.width, p.height) {
        val o = holder.pillOffset(pos)
        p.place(IntOffset(((o.x - margin) * d).roundToInt(), ((o.y - margin) * d).roundToInt()))
    }
}

/**
 * "← → ↑ ↓ Move · OK Done", 22 px on PanelFill, beside the bug toward the screen centre (§6, U-B1). A sibling of the
 * bug's clipped layer (goal design §1), placed from the same [Holder] box / [pos] and faded with the bug.
 */
@Composable
private fun PlaceHint(holder: Holder, pos: Animatable<Offset, *>, fade: Animatable<Float, *>, bugOnRight: Boolean, bugW: Float, bugH: Float) {
    val hintDesc = androidx.compose.ui.res.stringResource(R.string.sports_place_hint_desc)
    Box(
        Modifier
            .layout { m, _ ->
                val p = m.measure(Constraints())
                layout(p.width, p.height) {
                    val o = holder.pillOffset(pos)
                    val gap = (K.SCORE_BAR_GAP * density).roundToInt()
                    val bx = (o.x * density).roundToInt()
                    val x = if (bugOnRight) bx - p.width - gap else bx + (bugW * density).roundToInt() + gap
                    p.place(x, (o.y * density).roundToInt() + ((bugH * density).roundToInt() - p.height) / 2)
                }
            }
            .graphicsLayer { alpha = fade.value }
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
    clockSecs = 0,
)

/** The merged semantics string of a state (U-N12): "Calgary 0, Edmonton 2, 10:22 left in the 1st". */
internal class Describe(private val res: Resources, private val v: ScoreBugView) {
    /** [secs] defaults to the view's own clock (owner ruling 6: the snapshot value). */
    fun text(secs: Int = v.clockSecs ?: 0): String {
        val a = v.away
        val h = v.home
        fun f(id: Int, vararg extra: Any) = res.getString(id, a.name, a.score ?: 0, h.name, h.score ?: 0, *extra)
        val b = v.bottom
        return when {
            // The goal mark (goal design §5.3): %5 = the scoring team's name.
            v.goalSide != null -> f(R.string.sports_desc_goal, (if (v.goalSide == Side.AWAY) a else h).name)
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
