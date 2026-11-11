package app.thebox.ui.sports

import android.content.res.Resources
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.thebox.core.sports.BarKey
import app.thebox.core.sports.BarKind
import app.thebox.core.sports.Box as DesignBox
import app.thebox.core.sports.CentreText
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GoalFrame
import app.thebox.core.sports.GoalSpec
import app.thebox.core.sports.GoalTimeline
import app.thebox.core.sports.League
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.NhlStackedColours
import app.thebox.core.sports.OverlayGeometry
import app.thebox.core.sports.PlacementInput
import app.thebox.core.sports.ScoreBugPlacement
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.ScoreBugSource
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.StackedFrame
import app.thebox.core.sports.StackedPair
import app.thebox.core.sports.StackedTargets
import app.thebox.core.sports.StackedTimeline
import app.thebox.core.sports.StackedTrack
import app.thebox.core.sports.StackedTransition
import app.thebox.core.sports.TeamLine
import app.thebox.ui.R
import app.thebox.ui.full.PlayerUi
import app.thebox.ui.guide.GuideDebugCounters
import app.thebox.ui.guide.screen.LocalShellImageLoader
import app.thebox.ui.theme.BoxFonts
import app.thebox.ui.theme.DesignTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/** Debug composition counter of [StackedBug] (one per snapshot, none during a takeover); never the Pill's counter. */
const val COMPOSITIONS_STACKED_BUG = "StackedBug"
/** Debug counter: builds of the Stacked bug's resolved words ([StackedFrameState]); one per change of what is shown. */
const val FRAMES_STACKED_BUG = "StackedBugFrame"
/** The Stacked bug's bounded leaf layer (footprint + 2 M, §6), for the bounds test; no semantics of its own. */
const val STACKED_LAYER_TAG = "score_bug_stacked_layer"
/** Debug counter: draws of the Stacked bug's one drawing node (the instrumented `StackedFramesTest` arms on its bursts). */
const val DRAWS_STACKED_BUG = "StackedBugDraw"

/**
 * (Stacked §2.3; Stacked WP-3) The Stacked NHL score bug: its own shell (the 150-ms fade on its own leaf
 * `graphicsLayer { alpha; clip = true }` sized footprint + 2 M, the `SCORE_SLIDE_MS` corner slide, `FollowLogos` loaded at
 * the takeover size (S6), the place hint, the shared [LiveText] live region `SCORE_BUG_LIVE_TAG`), its [StackedRun] (the
 * five layout tracks of `StackedTimeline`, the pin, S1 rules 1–7) and the goal takeover, drawing through one
 * `Spacer.drawBehind { StackedPainter.drawFrame(...) }` node tagged `SCORE_BUG_TAG` on the **drawn** box. Reached only
 * through `ScoreBug(...)`'s style branch (NHL and `NhlBugStyle.STACKED`); [placement] always carries `style = STACKED`, so
 * `place` returns the **footprint** (§4.3) and the box is drawn inside it with the rows anchored at its left edge.
 * No `lowEnd` parameter (ruling 20); the still version follows `motionOff()`.
 *
 * **The S1 rules** carried from the Pill runner (§2.3), each pinned by a `StackedDrawTest` case:
 * 1. the pre-goal body is held until the takeover's lead covers the box (frame A on the still path);
 * 2. the first view of a follow, and its first non-waiting view, snap every track to rest;
 * 3. a hidden bug's tracks freeze and fade with it, park once the fade is done, and snap on reappearance;
 * 4. place mode parks the tracks (they rest at the shown view's targets, the sample's with no follow);
 * 5. a goal mid-takeover restarts it and keeps the pin; an earlier goal's "POWER PLAY GOAL" gives way once this goal's
 *    lead covers the box (a cut to the current power play's words on the same side, else a close);
 * 6. a goal and a layout change in one snapshot: the layout is pinned at the pre-goal targets until the 3 600 release;
 * 7. hide, place mode, a new game and a size change cancel the takeover and release the pin with snaps; a style flip
 *    removes this composable. `lastSeq` is updated above the visibility return, so nothing is replayed.
 *
 * **One composition per snapshot.** The composition writes plain fields; every driver, the glow and the shadow are read
 * only in draw.
 */
@Composable
internal fun StackedBug(
    source: ScoreBugSource,
    placement: PlacementInput,
    geometry: OverlayGeometry,
    modifier: Modifier,
    place: (PlacementInput, OverlayGeometry) -> DesignBox?,
    hooks: StackedHooks,
) {
    if (GuideDebugCounters.ENABLED) SideEffect { GuideDebugCounters.bump(COMPOSITIONS_STACKED_BUG) }
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val d = LocalDensity.current.density // device px per design px (BoxDesignScale)

    val bug by source.bug.collectAsState()
    val sample = remember(res) { stackedSampleView(res) }
    val view: ScoreBugView? = bug ?: if (placement.placing) sample else null
    val box = if (view != null) place(placement, geometry) else null // the footprint (§4.3)
    val large = placement.size == ScoreBugSize.LARGE

    val holder = remember { StackedHolder() }
    if (box != null) { holder.box = box; holder.large = large }
    if (view != null) holder.lastView = view
    bug?.let { holder.lastFollowed = it }

    // ---- visibility: slide between footprints, fade in and out (ruling 20: always animated)
    val fade = remember { Animatable(0f) }
    val pos = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var fadeDone by remember { mutableStateOf(false) }
    LaunchedEffect(box != null) {
        if (box != null) {
            fadeDone = false
            fade.animateTo(1f, tween(PlayerUi.FADE_MS))
        } else {
            fade.animateTo(0f, tween(PlayerUi.FADE_MS))
            holder.positioned = false
            fadeDone = true
        }
    }
    LaunchedEffect(box) {
        if (box != null) {
            val o = Offset(box.l, box.t)
            if (!holder.positioned) pos.snapTo(o) else pos.animateTo(o, tween(K.SCORE_SLIDE_MS, easing = FastOutSlowInEasing))
            holder.positioned = true
        }
    }

    // ---- held across hides (U-S1): above the visibility return, so hiding never drops them
    val shownLarge = holder.large
    val shownSize = if (shownLarge) ScoreBugSize.LARGE else ScoreBugSize.STANDARD
    val k = ScoreBugPlacement.stackedScale(shownSize)
    val s = d * k
    val footW = ScoreBugPlacement.width(shownSize, League.NHL, NhlBugStyle.STACKED)
    val footH = ScoreBugPlacement.height(shownSize, League.NHL, NhlBugStyle.STACKED)
    // The words' painter: measurement only (paints, the clock's digit table), so it is built here like the Pill's; the
    // bakes (the shadow, the GOAL atlas, the glow) run off the main thread below.
    val painter = remember(s, shownLarge) {
        StackedPainter(s, shownLarge, sansTypeface(context, R.font.instrument_sans_bold), StackedPainter.barTypeface(context))
    }
    val shadow = remember(painter) { mutableStateOf<NineSlice?>(null) }
    LaunchedEffect(painter) {
        shadow.value = try {
            withContext(hooks.background) { painter.bakeShadow() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null // no shadow, never a crash
        }
    }
    val loader = LocalShellImageLoader.current
    val scope = rememberCoroutineScope()
    val logos = remember(loader) { FollowLogos(hooks.logoFetch ?: loader?.let { coilLogoFetch(it, context) }, scope) }
    DisposableEffect(logos) { onDispose { logos.release() } }
    val logoWaited = remember { mutableStateOf(false) }
    val followed = holder.lastFollowed
    LaunchedEffect(followed?.away?.logo, followed?.home?.logo, s) {
        if (followed == null) return@LaunchedEffect
        // S6: one bitmap per team at the takeover's badge size (67.5 × s); the rows draw it scaled down.
        val px = (K.STACKED_GOAL_LOGO * s).roundToInt().coerceAtLeast(1)
        logos.start(followed.away.logo, followed.home.logo, px, px)
        logoWaited.value = false
        delay(K.LOGO_WAIT_MS)
        logoWaited.value = true
    }

    // ---- the runner and the takeover. Bookkeeping runs above the visibility return, so a goal while hidden or placing
    // updates lastSeq and is never replayed on reappearance; takeoverOn and the drivers are read only in draw.
    val run = remember { StackedRun(scope, hooks) }
    val g = remember { StackedGoalRun() }
    val takeoverOn = remember { mutableStateOf(false) }
    val driver = remember { Animatable(0f) }
    val goalFrame = remember { GoalFrame() }
    /** The pre-goal body, drawn until the takeover's lead covers the box (S1 rule 1); read only in draw. */
    val held = remember { mutableStateOf<StackedFrameState?>(null) }
    val liveTexts = remember { LiveText() }
    val awayAbbrev = bug?.away?.abbrev ?: ""
    val homeAbbrev = bug?.home?.abbrev ?: ""
    val pair = remember(awayAbbrev, homeAbbrev) { hooks.colours(awayAbbrev.ifEmpty { null }, homeAbbrev.ifEmpty { null }) }
    run.painter = painter; run.res = res; run.s = s; run.pair = pair; run.away = awayAbbrev; run.home = homeAbbrev
    val margin = if (shownLarge) K.GOAL_LAYER_MARGIN_LARGE else K.GOAL_LAYER_MARGIN
    hooks.probe?.let { p ->
        p.takeoverOn = takeoverOn; p.driver = driver
        p.pinned = { run.pinned }
        p.trackDriver = { run.track(it) }
        p.barWords = { run.barWords() }
        p.goalPainter = { run.goalPainter }
        p.painter = { painter }
        p.shadow = { shadow.value }
        p.run = run
    }

    // The takeover painter: nothing is built until a game is followed (the place-mode sample never needs it). Then the
    // Barlow load and the GOAL atlas, once per size, off the main thread; a failure means no takeover, never a crash.
    val hasFollow = followed != null
    val goalWord = remember(res) { res.getString(R.string.sports_goal_word) }
    LaunchedEffect(s, shownLarge, hasFollow) {
        run.setGoalPainter(null)
        if (!hasFollow) return@LaunchedEffect
        val built = try {
            withContext(hooks.background) {
                StackedGoalPainter(
                    s, shownLarge,
                    sansTypeface(context, R.font.barlow_condensed_black_italic), sansTypeface(context, R.font.instrument_sans_bold),
                    goalWord,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        built?.prewarm(null) // the atlas to the GPU ahead of the first goal
        run.setGoalPainter(built)
    }

    // ---- what the tracks follow: the shown view's targets, with the last fresh ones (WP-1: the stale PP-into-INT row)
    val tView = view ?: holder.lastView
    val real = bug
    if (real != null && (!g.seen || real.gameId != g.gameId)) holder.lastFresh = null // a new follow: no history
    val targets: StackedTargets? = remember(tView) {
        tView?.let { StackedTargets.of(it, if (it === sample) null else holder.lastFresh) }
    }
    if (tView != null && tView !== sample && !tView.dim && targets != null) holder.lastFresh = targets
    run.inTargets = targets

    fun cancelTakeover(releaseThePin: Boolean = true) {
        if (g.active) { g.active = false; g.token++ }
        takeoverOn.value = false // a write, never a read: composition must not subscribe to it
        held.value = null
        if (releaseThePin) run.release(snap = true, driverMs = Snapshot.withoutReadObservation { driver.value })
    }
    if (real == null) {
        cancelTakeover()
        if (g.seen) run.reset()
        g.seen = false // the next snapshot is a "first snapshot" again: nothing replays
    } else if (!g.seen || real.gameId != g.gameId) {
        cancelTakeover()
        run.reset()
        g.seen = true; g.gameId = real.gameId; g.lastSeq = real.goalSeq; g.size = placement.size
    }
    // Rules 3 and 4: hidden, the tracks freeze where they are and fade with the bug, then park once the fade is done; place
    // mode keeps them at rest on the shown view's targets; reappearing and leaving place mode snap.
    if (placement.placing && box != null) {
        run.hidden = false; run.snapAll()
    } else if (box == null) {
        run.hidden = true
        if (fadeDone) run.park()
    } else if (run.hidden || run.wasPlacing) {
        run.hidden = false; run.snapAll()
    }
    run.wasPlacing = placement.placing && box != null
    if (real != null) {
        // Rule 2: the first view of a follow, and its first non-waiting one, snap: nothing animates in on follow (PP N5).
        if (!run.viewSeen) { run.viewSeen = true; run.snapAll() }
        if (!run.freshSeen && !waiting(real)) { run.freshSeen = true; run.snapAll() }
        if (box == null || placement.placing || placement.size != g.size) {
            cancelTakeover()
            if (placement.size != g.size) run.snapAll()
            g.size = placement.size
        }
        if (real.goalSeq != g.lastSeq) {
            g.lastSeq = real.goalSeq
            // A new goal mid-pin restarts the takeover, but the pin continues (rule 5): no release on this path.
            cancelTakeover(releaseThePin = false)
            val side = real.goalSide
            val gp = run.goalPainter
            if (side != null && box != null && !placement.placing && gp != null) {
                g.side = side
                g.away = real.away.score ?: 0; g.home = real.home.score ?: 0
                g.awayAbbrev = real.away.abbrev; g.homeAbbrev = real.home.abbrev
                g.glowOn = hooks.glowAllowed(placement, geometry, box, margin)
                g.active = true; g.token++
                // This composition already shows the goal view: until the effect sets takeoverOn and the lead covers the
                // box, the draw keeps the previous body (rule 1) and the layout keeps the pre-goal targets (rule 6).
                held.value = holder.lastFrame
                run.pin(real.goalPp, side)
            } else {
                run.release(snap = true, driverMs = 0f)
            }
        }
        // The glow follows the layout while a takeover is live; latched off for the rest of the run (GA N4).
        if (g.active && box != null && g.glowOn) g.glowOn = hooks.glowAllowed(placement, geometry, box, margin)
    }
    SideEffect {
        run.step()
        hooks.probe?.afterStep?.invoke()
    }

    LaunchedEffect(g.token) {
        if (!g.active) return@LaunchedEffect
        val token = g.token
        val gp = run.goalPainter
        if (gp == null) { // the painter went (a size change): skipped
            g.active = false; held.value = null
            run.release(snap = false, driverMs = 0f)
            return@LaunchedEffect
        }
        val side = g.side
        val image = (if (side == Side.AWAY) logos.away else logos.home).image?.asAndroidBitmap()
        val p = run.pair ?: NhlStackedColours.pair(null, null)
        val colours = (if (side == Side.AWAY) p.away else p.home).goal
        withContext(hooks.context) {
            val still = motionOff() // the exact scale Compose applies to animateTo
            gp.begin(side, colours, image, g.awayAbbrev, g.homeAbbrev, g.away, g.home, run.pinBoxW)
            hooks.probe?.begins?.add(StackedProbe.Begin(side, run.pinBoxW, g.away, g.home, still))
            g.painter = gp
            g.still = still
            driver.snapTo(0f)
            takeoverOn.value = true
            try {
                if (still) {
                    run.covered() // frame A covers the box from its first draw
                    delay(K.GOAL_TAKEOVER_MS) // frame A; the delay is not scaled
                    driver.snapTo(K.GOAL_TAKEOVER_MS.toFloat()) // frame B
                    if (g.token == token) run.release(snap = false, driverMs = K.GOAL_TAKEOVER_MS.toFloat())
                    delay(gp.spec.stillFrameBMs.toLong())
                } else {
                    // The lead covering the box (rules 1 and 5, ruling 15) and the release at 3 600 (rule 6) are children
                    // watching the driver, so the takeover keeps its single tween under any MotionDurationScale.
                    launch {
                        snapshotFlow { driver.value >= GoalTimeline.LEAD_COVERED_MS }.first { it }
                        if (g.token == token) run.covered()
                    }
                    launch {
                        snapshotFlow { driver.value >= K.GOAL_TAKEOVER_MS.toFloat() }.first { it }
                        if (g.token == token) run.release(snap = false, driverMs = driver.value)
                    }
                    val end = gp.spec.endMs
                    driver.animateTo(end, tween(end.roundToInt(), easing = LinearEasing))
                }
            } finally {
                // Only the run that is still current clears: a cancelled run's finally runs before its successor's body.
                if (g.token == token) {
                    takeoverOn.value = false; g.active = false; held.value = null
                    run.release(snap = false, driverMs = Snapshot.withoutReadObservation { driver.value })
                }
            }
        }
    }

    val last = holder.box
    val visible = box != null || (holder.drawn && !fadeDone)
    if (!visible || last == null) { holder.drawn = false; return }
    holder.drawn = true

    // ---- what is drawn: rebuilt only when the shown view changes (a new snapshot or state), never per second
    val shownView = view ?: holder.lastView ?: return
    val shownTargets = targets ?: return
    val frame = remember(shownView, shownTargets, painter) {
        if (GuideDebugCounters.ENABLED) GuideDebugCounters.bump(FRAMES_STACKED_BUG)
        painter.frame(shownView, shownTargets, res)
    }
    holder.lastFrame = frame
    // The column texts a folding column fades out with (WP-2: a folded column's slot is Blank in the new body).
    if (frame.targets.clockOpen) holder.lastClockBody = frame
    if (frame.targets.sogOpen) holder.lastSogBody = frame
    val shownLogos = if (shownView === sample) null else logos // the sample never borrows the follow's logos
    val placing = placement.placing
    val rightSide = last.l > 960f
    val desc = remember(shownView, shownTargets, res) { StackedDescribe(res, shownView, shownTargets).text() }
    val liveText = if (shownView === sample) "" else liveTexts.next(shownView, desc, res)
    // S11: the bar opens below an effective top corner's box and above a bottom one's, from the placed footprint.
    val up = ScoreBugPlacement.stackedBarUp(last)

    // The drawn box at the targets' rest, in design px from the footprint's top-left (§4.3: the rows never move).
    val restW = restBoxW(shownTargets.sogOpen, shownTargets.clockOpen)
    val topBox = ScoreBugPlacement.stackedTopBox(last, shownSize, restW) // WP-1: the rows' box in the footprint
    val topH = topBox.b - topBox.t
    val barRest = if (shownTargets.bar != null) K.STACKED_BAR_H * k else 0f
    val tagW = topBox.r - topBox.l
    val topY = topBox.t - last.t
    val tagY = if (up) topY - barRest else topY
    val tagH = topH + barRest
    val originDy = (topY - tagY) * d

    val args = remember { StackedDrawArgs() }
    val merge = remember { BodyMerge() }
    /** Fills [args] with this draw's inputs: the drivers are read here (in draw) and nowhere in composition. */
    fun fillArgs(): StackedDrawArgs {
        val a = args
        val on = takeoverOn.value
        var t = 0f
        a.goal = null; a.g = null
        if (on) {
            t = driver.value
            val f = hooks.goalSampler.sample(t, g.still, goalFrame)
            if (!f.done) { a.goal = g.painter; a.g = f }
        }
        a.t = t
        // Pending, or the lead still wiping in: the pre-goal body (rule 1). The still path's frame A covers from its first draw.
        val hold = held.value
        val base = if (hold != null && (!on || (!g.still && t < GoalTimeline.LEAD_COVERED_MS))) hold else frame
        val f = run.sampleForDraw()
        a.f = f
        a.body = merge.of(base, f, holder.lastClockBody, holder.lastSogBody)
        a.colours = pair
        a.bar = run.bar
        a.barPrev = run.barPrevForDraw()
        a.up = up
        a.logos = shownLogos
        a.logoWaited = logoWaited.value
        a.placing = placing
        a.shadow = shadow.value
        a.glow = if (on) run.glowForDraw() else null
        a.glowOn = g.glowOn
        a.still = g.still
        return a
    }
    fun drawBug(c: android.graphics.Canvas) {
        if (GuideDebugCounters.ENABLED) GuideDebugCounters.bump(DRAWS_STACKED_BUG)
        painter.drawFrame(c, fillArgs())
    }
    hooks.probe?.let { it.args = ::fillArgs; it.draw = ::drawBug }
    val m = margin
    Box(
        modifier
            .stackedPlaceAt(holder, pos, d, m)
            // A leaf layer (never an ancestor of the SurfaceView): the fade, clipped to the footprint + M (GA §1, §6).
            .graphicsLayer { alpha = fade.value; clip = true }
            .size((footW + 2 * m).dp, (footH + 2 * m).dp)
            .testTag(STACKED_LAYER_TAG),
    ) {
        Spacer(
            Modifier
                .offset(m.dp, (m + tagY).dp)
                .size(tagW.dp, tagH.dp)
                .testTag(SCORE_BUG_TAG)
                .semantics { contentDescription = desc }
                .drawBehind {
                    drawIntoCanvas {
                        val nc = it.nativeCanvas
                        // The painter's origin is the top part's top-left (StackedDrawArgs); the node is the drawn box.
                        nc.translate(0f, originDy)
                        drawBug(nc)
                        nc.translate(0f, -originDy)
                    }
                },
        )
        // The persistent live region (GA S9): on the drawn box, always composed while the bug is.
        Spacer(
            Modifier
                .offset(m.dp, (m + tagY).dp)
                .size(tagW.dp, tagH.dp)
                .testTag(SCORE_BUG_LIVE_TAG)
                .semantics { contentDescription = liveText; liveRegion = LiveRegionMode.Polite },
        )
    }
    // A sibling of the clipped layer, not inside it (GA §1); positioned from the drawn box, not the footprint (N8).
    if (placing) StackedPlaceHint(holder, pos, fade, rightSide, tagY, tagW, tagH)
}

/** Bumps [s] without reading it: a bump in composition must not subscribe the composition to the draw's epoch. */
private fun bump(s: androidx.compose.runtime.MutableIntState) = Snapshot.withoutReadObservation { s.intValue += 1 }

/** The top part's width at rest, Standard design px: the rows plus each open column (§4.2: 333 / 391.5 / 301.5). */
private fun restBoxW(sogOpen: Boolean, clockOpen: Boolean): Float =
    K.STACKED_ROWS_W + (if (sogOpen) K.STACKED_SOG_W else 0f) + (if (clockOpen) K.STACKED_CLOCK_W else 0f)

/** A view whose bottom line is the delay's or a stale feed's: the follow has not yet shown a fresh view. */
private fun waiting(v: ScoreBugView): Boolean = v.bottom is CentreText.Delayed || v.bottom is CentreText.NoUpdate

/**
 * (Stacked §2.3) The Stacked bug's test seams, as [GoalHooks] / [NflHooks]; the defaults are the real objects. Owned by
 * Stacked WP-3, which may add seams (the defaults must stay the real objects).
 *
 * - [colours]: the game's pair (`NhlStackedColours.pair`, S3's collision rule applied).
 * - [sampler]: the layout tracks (`StackedTimeline.sample`); [goalSampler]: the takeover under `GoalSpec.STACKED`.
 * - [bakeGlow]: the exact-box glow ([StackedGoalPainter.bakeGlow], w × h device px), off the main thread.
 * - [logoFetch]: the followed game's logo loader; null = the shell's image loader.
 * - [context]: added to the effects' coroutine context (tests put a `MotionDurationScale` here); empty in production.
 * - [background]: where the painters are built and the masks baked; tests pass [EmptyCoroutineContext].
 * - [probe]: what a test can observe; null in production.
 * - (WP-3) [pinnedSampler]: a track that was mid-transition when a takeover started (`StackedTimeline.samplePinned`:
 *   its layout channel at the end of the transition, the rest on its own clock, B4).
 * - (WP-3) [glowAllowed]: whether the takeover's glow may draw around the placed footprint (§4.3, GA §2.4).
 */
internal class StackedHooks(
    val colours: (String?, String?) -> StackedPair = NhlStackedColours::pair,
    val sampler: StackedSampler = StackedSampler.Real,
    val goalSampler: GoalSampler = GoalSampler { t, still, out ->
        if (still) GoalTimeline.sampleStill(GoalSpec.STACKED, t, out) else GoalTimeline.sample(GoalSpec.STACKED, t, out)
    },
    val bakeGlow: (StackedGoalPainter, Float, Float) -> GlowMasks? = { p, w, h -> p.bakeGlow(w, h) },
    val logoFetch: LogoFetch? = null,
    val context: CoroutineContext = EmptyCoroutineContext,
    val background: CoroutineContext = Dispatchers.Default,
    val probe: StackedProbe? = null,
    val pinnedSampler: StackedSampler = StackedSampler.Pinned,
    val glowAllowed: (PlacementInput, OverlayGeometry, DesignBox, Float) -> Boolean = ScoreBugPlacement::glowAllowed,
) {
    companion object { val Default = StackedHooks() }
}

/** (Stacked §2.3) Samples one layout track into a preallocated frame (draw path; a `fun interface`, so nothing boxes). */
internal fun interface StackedSampler {
    fun sample(track: StackedTrack, kind: StackedTransition, tMs: Float, from: StackedFrame, to: Boolean, out: StackedFrame): StackedFrame

    companion object {
        val Real = StackedSampler { track, kind, t, from, to, out -> StackedTimeline.sample(track, kind, t, from, to, out) }
        /** (WP-3) The pin's sampler (B4): the layout channel at the end of the transition. */
        val Pinned = StackedSampler { track, kind, t, from, to, out -> StackedTimeline.samplePinned(track, kind, t, from, to, out) }
    }
}

/**
 * (Stacked §10.2) What a test can observe of the Stacked runner (`StackedDrawTest`, `StackedSeamTest`, `StackedBoundsTest`,
 * `StackedLogoTest`). Owned by Stacked WP-3.
 */
internal class StackedProbe {
    /** Fills and returns what the next draw would pass `StackedPainter.drawFrame` (shared holder: copy what you keep). */
    var args: (() -> StackedDrawArgs)? = null
    /** The bug's whole draw, in the top part's coordinates (origin = the rows' left edge, the top of the away row). */
    var draw: ((android.graphics.Canvas) -> Unit)? = null
    var takeoverOn: State<Boolean>? = null
    var driver: Animatable<Float, *>? = null
    var pinned: (() -> Boolean)? = null
    var trackDriver: ((StackedTrack) -> Animatable<Float, *>)? = null
    /** The bar's current words (allocates; probes only). */
    var barWords: (() -> String)? = null
    var goalPainter: (() -> StackedGoalPainter?)? = null
    var painter: (() -> StackedPainter)? = null
    var shadow: (() -> NineSlice?)? = null
    /** Called right after the composition's kick ([StackedRun.step] in its `SideEffect`). */
    var afterStep: (() -> Unit)? = null
    internal var run: StackedRun? = null

    /** One transition a track started; for the bar, the content's kind, side and words at its start. */
    class Rec(val track: StackedTrack, val kind: StackedTransition, val to: Boolean, val barKind: BarKind?, val barSide: Side?, val words: String)
    val transitions = mutableListOf<Rec>()
    /** One end of the pin: the takeover driver then, and whether it was a snap (a cancel). */
    class Release(val driverMs: Float, val snap: Boolean)
    val releases = mutableListOf<Release>()
    /** One `StackedGoalPainter.begin`: the scorer, the pinned top-part width (Standard design px), the tally, the path. */
    class Begin(val side: Side, val boxW: Float, val away: Int, val home: Int, val still: Boolean)
    val begins = mutableListOf<Begin>()
    /** Each glow bake started (device px) and each one published. */
    val glowBakes = mutableListOf<Pair<Float, Float>>()
    var glowPublished = 0
    /** The lead covered the box (or frame A started): rule 1's and rule 5's instant. */
    var covers = 0
}

/** Plain holder for what must survive recomposition without being snapshot state (writes happen in composition). */
private class StackedHolder {
    /** The placed footprint (§4.3). */
    var box: DesignBox? = null
    var large = false
    var positioned = false
    var drawn = false
    var lastView: ScoreBugView? = null
    /** The body the bug last composed for drawing: what a goal's takeover holds while it starts (rule 1). */
    var lastFrame: StackedFrameState? = null
    /** The last body with the clock column open / the SOG column open: the texts a folding column fades out with. */
    var lastClockBody: StackedFrameState? = null
    var lastSogBody: StackedFrameState? = null
    /** The last real (not sample) view: its logo URLs key the held [FollowLogos]. */
    var lastFollowed: ScoreBugView? = null
    /** The targets of the last fresh (non-`dim`) view of this follow (`StackedTargets.of(v, lastFresh)`). */
    var lastFresh: StackedTargets? = null

    /** The footprint's top-left in design px: the sliding position once positioned, else the placed box. */
    fun offset(pos: Animatable<Offset, *>): Offset =
        if (positioned) pos.value else box?.let { Offset(it.l, it.t) } ?: Offset.Zero
}

/** The takeover trigger (GA §5.2): a plain holder, written in composition and in the effect. */
private class StackedGoalRun {
    var seen = false
    var gameId: GameId? = null
    var lastSeq = -1
    var size: ScoreBugSize? = null
    /** Bumped to start a takeover and to cancel one; the effect keys on it. [active]: the last bump was a start. */
    var token = 0
    var active = false
    // Captured at start; never re-read.
    var side: Side = Side.AWAY
    var away = 0
    var home = 0
    var awayAbbrev = ""
    var homeAbbrev = ""
    var still = false
    /** Latched off for the rest of a run once a neighbour closes in (GA N4). */
    var glowOn = false
    /** The painter this run was begun on; draw reads it. */
    var painter: StackedGoalPainter? = null
}

/** One layout track (§2.3): its driver (elapsed ms, read only in draw), its `from`, its transition and where it heads. */
private class StackedTrackRun(val id: StackedTrack) {
    val driver = Animatable(0f)
    val from = StackedFrame()
    val next = StackedFrame()
    var kind = StackedTransition.STILL
    /** The transition's rest state (open / wide); for CHANGE and SWAP, open. */
    var to = false
    /** Where the track is heading (open / wide): the boolean tracks' key. The bar's key is [StackedRun.barWant]. */
    var want = false
    var job: Job? = null
    /** Mid-transition when the takeover started: sampled with the pin's sampler until the release (B4). */
    var pinSample = false
    val idle: Boolean get() = job?.isActive != true
}

/**
 * (Stacked §2.3, §7.1; WP-3) The layout runner: five tracks (SOG, CLOCK, BAR, the two row stripes), each kicked only when
 * its target key changes (PP §3.1 B1: a seconds-only update just redraws); CSS-transition semantics (a kick starts from
 * the interrupted transition's current value); the run owns what the bar says (PP §3.1 B2: [bar], [barPrev]); the pin
 * (B4); the exact-box glow cache and its prewarm at layout rest (§6). A plain holder; [epoch] and the drivers are the only
 * state, read only in draw.
 */
internal class StackedRun(private val scope: CoroutineScope, private val hooks: StackedHooks) {
    private val sog = StackedTrackRun(StackedTrack.SOG)
    private val clock = StackedTrackRun(StackedTrack.CLOCK)
    private val barTrack = StackedTrackRun(StackedTrack.BAR)
    private val stripeAway = StackedTrackRun(StackedTrack.STRIPE_AWAY)
    private val stripeHome = StackedTrackRun(StackedTrack.STRIPE_HOME)
    private val tracks = arrayOf(sog, clock, barTrack, stripeAway, stripeHome)

    /** What the bar says now, and (during a change or a text swap) what it said before. */
    val bar = BarContent()
    private val barPrev = BarContent()
    /** The bar track's key: null = closed. `PP_GOAL` only as the pin's cut (ruling 15). */
    var barWant: BarKey? = null
        private set

    private val out = StackedFrame()
    private val scratch = StackedFrame()
    /** Bumped when what draw samples changes in plain fields only (a kick, a cut, the pin); read in draw. */
    private val epoch = mutableIntStateOf(0)
    private var running = 0

    // ---- inputs, written in composition
    var painter: StackedPainter? = null
    var res: Resources? = null
    var s = 1f
    var pair: StackedPair? = null
    var away = ""
    var home = ""
    var inTargets: StackedTargets? = null
    var hidden = true
    var wasPlacing = false
    var viewSeen = false
    var freshSeen = false
    private var snapPending = false

    // ---- the pin (§2.3, B4)
    var pinned = false
        private set
    private var covered = false
    private var goalPp = false
    private var goalSide: Side? = null
    /** The pinned box: the top part's width (Standard design px) and the whole box in device px (the glow's key). */
    var pinBoxW = K.STACKED_ROWS_W + K.STACKED_CLOCK_W
        private set
    private var pinW = 0f
    private var pinH = 0f

    // ---- the glow (§6, B4): baked per exact box, cached by (w, h) for this size, LRU STACKED_GLOW_CACHE
    var goalPainter: StackedGoalPainter? = null
        private set
    private val glowKeys = LongArray(K.STACKED_GLOW_CACHE)
    private val glowMasks = arrayOfNulls<GlowMasks>(K.STACKED_GLOW_CACHE)
    private var glowCount = 0
    private val baking = HashSet<Long>()
    private val glowEpoch = mutableIntStateOf(0)

    fun track(id: StackedTrack): Animatable<Float, *> = tracks[id.ordinal].driver

    fun snapAll() { snapPending = true }

    /** Parks every track at the rest of where it was heading (hide after the fade, rule 3); the reappearance snaps. */
    fun park() {
        for (t in tracks) {
            t.job?.cancel(); t.job = null
            t.kind = StackedTransition.STILL; t.to = t.want; t.pinSample = false
        }
        snapPending = true
        bump(epoch)
    }

    /** A new follow (or none): everything parks closed; the next view snaps (rule 2). */
    fun reset() {
        for (t in tracks) t.want = false
        barWant = null
        bar.len = 0
        park()
        pinned = false; covered = false; goalPp = false; goalSide = null
        viewSeen = false; freshSeen = false
    }

    /**
     * `SideEffect`: kicks each track whose key changed (or snaps them all when a snap was asked). Nothing moves while
     * hidden (rule 3) or pinned (rule 6); a pinned bar takes its seconds in place once the lead has covered the box.
     */
    fun step() {
        if (hidden) return
        val tg = inTargets ?: return
        if (pinned) {
            if (covered && barWant != null && barWant == tg.bar) updateInPlace(tg)
            return
        }
        val snap = snapPending
        snapPending = false
        evalBool(sog, tg.sogOpen, snap)
        evalBool(clock, tg.clockOpen, snap)
        evalBar(tg, snap)
        evalBool(stripeAway, tg.ppSide == Side.AWAY, snap)
        evalBool(stripeHome, tg.ppSide == Side.HOME, snap)
    }

    // ------------------------------------------------------------------ the pin

    /**
     * A takeover starts (rule 6, B4). A fresh pin snaps every layout track that is mid-transition to the end of it (draw
     * samples it with [StackedHooks.pinnedSampler]), fixes the box and queues its glow; a second goal mid-pin keeps the
     * pin (rule 5) and holds the bar until its own lead covers the box.
     */
    fun pin(goalPp: Boolean, side: Side) {
        if (!pinned) {
            // A reappearance in the goal's own composition snaps first (in composition: reads unobserved).
            if (snapPending && !hidden) Snapshot.withoutReadObservation { step() }
            pinned = true
            for (t in arrayOf(sog, clock, barTrack)) if (!t.idle) t.pinSample = true
            pinBoxW = restBoxW(sog.want, clock.want)
            pinW = pinBoxW * s
            pinH = (K.STACKED_H + (if (barWant != null) K.STACKED_BAR_H else 0f)) * s
            requestGlow(pinW, pinH)
        }
        covered = false
        this.goalPp = goalPp
        goalSide = side
        bump(epoch)
    }

    /**
     * The takeover's lead covers the box (the still path: frame A). Ruling 15: a power-play goal for the open bar's side
     * cuts its words to "… POWER PLAY GOAL". Rule 5 (the B2.4 override): a bar still saying an earlier goal's "POWER PLAY
     * GOAL" when this goal is not one for that side takes the current power play's words (same side: a cut) or closes. An
     * unchanged bar key takes the current seconds.
     */
    fun covered() {
        if (!pinned) return
        covered = true
        hooks.probe?.let { it.covers++ }
        val cur = barWant
        val side = goalSide
        val tg = inTargets
        when {
            cur == null -> Unit
            cur.kind == BarKind.PP_GOAL -> if (!(goalPp && side == cur.side)) {
                val tb = tg?.bar
                if (tb != null && StackedTargets.isTeam(tb.kind) && tb.side == cur.side) {
                    fill(bar, tb, tg.barSecs, tg.barPeriod, tg.barDim)
                    barWant = tb
                } else {
                    kickBar(null, tg, snap = false) // slides "POWER PLAY GOAL" out; closed until the release
                }
            }
            goalPp && StackedTargets.isTeam(cur.kind) && cur.side == side -> {
                val key = BarKey(BarKind.PP_GOAL, side)
                fill(bar, key, null, null, false)
                barWant = key
            }
            tg != null && cur == tg.bar -> updateInPlace(tg)
        }
        bump(epoch)
    }

    /**
     * Ends the pin. [snap]: a cancel (rule 7): the next step snaps every track. Otherwise (the 3 600 release, rule 6, or
     * the end of a takeover) every track re-evaluates now against the current view, and the normal transitions run.
     */
    fun release(snap: Boolean, driverMs: Float) {
        if (!pinned) return
        pinned = false; covered = false; goalPp = false; goalSide = null
        for (t in tracks) t.pinSample = false
        hooks.probe?.releases?.add(StackedProbe.Release(driverMs, snap))
        bump(epoch)
        if (snap) snapPending = true
        step()
    }

    // ------------------------------------------------------------------ the transitions

    private fun evalBool(t: StackedTrackRun, want: Boolean, snap: Boolean) {
        if (want == t.want && !(snap && !(t.idle && t.to == want))) return
        t.want = want
        launchPlay(t, snap) { still -> if (still) StackedTransition.STILL else if (want) StackedTransition.OPEN else StackedTransition.CLOSE }
    }

    private fun evalBar(tg: StackedTargets, snap: Boolean) {
        val want = tg.bar
        if (want == barWant) {
            if (want != null) updateInPlace(tg)
            if (!(snap && !(barTrack.idle && barTrack.to == (want != null)))) return
        }
        kickBar(want, tg, snap)
    }

    /** Starts the bar's transition to [want], capturing what it says (PP §3.1 B2). */
    private fun kickBar(want: BarKey?, tg: StackedTargets?, snap: Boolean) {
        val old = barWant
        // What the bar shows now: during a change or a swap before its swap instant, the previous words.
        sampleTrack(barTrack, scratch)
        val showingPrev = (barTrack.kind == StackedTransition.CHANGE || barTrack.kind == StackedTransition.SWAP) && !scratch.barSwapped
        val kind = when {
            want == old -> StackedTransition.STILL // a snap with the same key
            want == null -> {
                if (showingPrev) bar.copyFrom(barPrev) // a close slides out the words on screen
                StackedTransition.CLOSE
            }
            old == null -> {
                fill(bar, want, tg?.barSecs, tg?.barPeriod, tg?.barDim ?: false)
                StackedTransition.OPEN
            }
            else -> {
                if (!showingPrev) barPrev.copyFrom(bar)
                fill(bar, want, tg?.barSecs, tg?.barPeriod, tg?.barDim ?: false)
                if (StackedTargets.sameLook(old, want)) StackedTransition.SWAP else StackedTransition.CHANGE
            }
        }
        barWant = want
        barTrack.want = want != null
        launchPlay(barTrack, snap || kind == StackedTransition.STILL) { still -> if (still) StackedTransition.STILL else kind }
    }

    /** Ruling 13: an open bar whose key is unchanged takes the new seconds (and period, dim) in place: a redraw, no kick. */
    private fun updateInPlace(tg: StackedTargets) {
        val key = tg.bar ?: return
        if (pinned && !covered) return // rule 1: nothing new before the takeover covers the box
        val secs = tg.barSecs ?: BarContent.NO_SECS
        if (bar.kind == key.kind && bar.side == key.side && bar.secs == secs && bar.period == tg.barPeriod && bar.dim == tg.barDim) return
        fill(bar, key, tg.barSecs, tg.barPeriod, tg.barDim)
        bump(epoch)
    }

    private fun fill(c: BarContent, key: BarKey, secs: Int?, period: app.thebox.core.sports.PeriodLabel?, dim: Boolean) {
        c.kind = key.kind
        c.side = key.side
        c.secs = secs ?: BarContent.NO_SECS
        c.period = period
        c.dim = dim
        val p = painter
        val r = res
        if (p != null && r != null) p.fillBar(c, pair, away, home, r)
    }

    private inline fun launchPlay(t: StackedTrackRun, snap: Boolean, crossinline kindOf: (Boolean) -> StackedTransition) {
        t.job?.cancel()
        t.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            running++
            try {
                withContext(hooks.context) {
                    val still = snap || motionOff() // §7.3: every layout transition snaps
                    play(t, kindOf(still), t.want)
                }
            } finally {
                running--
                if (running == 0) atRest()
            }
        }
    }

    /**
     * Starts [kind] on [t] from where the interrupted transition is now (CSS transition semantics), then runs it. The
     * driver snaps before the new kind and `from` are assigned (the Pill's S1 order), so no frame draws the new transition
     * at the old elapsed time.
     */
    private suspend fun play(t: StackedTrackRun, kind: StackedTransition, to: Boolean) {
        sampleTrack(t, t.next)
        t.driver.snapTo(0f)
        t.from.copyFrom(t.next)
        t.kind = kind; t.to = to
        bump(epoch)
        hooks.probe?.transitions?.add(
            StackedProbe.Rec(
                t.id, kind, to,
                if (t === barTrack) bar.kind else null, if (t === barTrack) bar.side else null,
                if (t === barTrack) barWords() else "",
            ),
        )
        val ms = StackedTimeline.totalMs(t.id, kind)
        if (ms > 0f) t.driver.animateTo(ms, tween(ms.roundToInt(), easing = LinearEasing))
    }

    private fun sampleTrack(t: StackedTrackRun, into: StackedFrame) {
        val sampler = if (t.pinSample) hooks.pinnedSampler else hooks.sampler
        sampler.sample(t.id, t.kind, t.driver.value, t.from, t.to, into)
    }

    // ------------------------------------------------------------------ draw

    /** Samples every track into the preallocated frame for draw (no allocation). */
    fun sampleForDraw(): StackedFrame {
        epoch.intValue // read in draw: a bump invalidates the bug's draw node
        for (t in tracks) sampleTrack(t, out)
        return out
    }

    /** The previous bar content while a change or a text swap runs (the painter draws it before the swap instant). */
    fun barPrevForDraw(): BarContent? =
        if (barTrack.kind == StackedTransition.CHANGE || barTrack.kind == StackedTransition.SWAP) barPrev else null

    /** The bar's words (allocates; probes only). */
    fun barWords(): String = String(bar.chars, 0, bar.len)

    // ------------------------------------------------------------------ the glow (§6, B4)

    /** A new takeover painter (per size; null while none): the cache is for its masks only. */
    fun setGoalPainter(p: StackedGoalPainter?) {
        goalPainter = p
        glowCount = 0
        glowMasks.fill(null)
        baking.clear()
        bump(glowEpoch)
        if (p != null) atRest()
    }

    /** The masks for the pinned box, once baked (the glow joins on the first frame after they land, §6); draw only. */
    fun glowForDraw(): GlowMasks? {
        glowEpoch.intValue
        return cached(key(pinW, pinH))
    }

    /** Every track idle: prewarm the glow for the box at rest, so a goal normally finds its masks ready (§6). */
    private fun atRest() {
        if (pinned || hidden || goalPainter == null || running > 0) return
        // Only once the tracks head where the view says (a step still to come would kick them, then call back here).
        val tg = inTargets ?: return
        if (sog.want != tg.sogOpen || clock.want != tg.clockOpen || barWant != tg.bar) return
        val w = restBoxW(sog.want, clock.want) * s
        val h = (K.STACKED_H + (if (barWant != null) K.STACKED_BAR_H else 0f)) * s
        requestGlow(w, h)
    }

    private fun key(w: Float, h: Float): Long = (w.roundToInt().toLong() shl 32) or (h.roundToInt().toLong() and 0xFFFFFFFFL)

    private fun cached(k: Long): GlowMasks? {
        for (i in 0 until glowCount) if (glowKeys[i] == k) return glowMasks[i]
        return null
    }

    private fun requestGlow(w: Float, h: Float) {
        val p = goalPainter ?: return
        if (!(w > 0f) || !(h > 0f)) return
        val k = key(w, h)
        if (cached(k) != null || !baking.add(k)) return
        hooks.probe?.glowBakes?.add(w to h)
        scope.launch {
            try {
                val masks = withContext(hooks.background) { hooks.bakeGlow(p, w, h) }
                if (masks != null && goalPainter === p) {
                    p.prewarm(masks)
                    put(k, masks)
                    hooks.probe?.let { it.glowPublished++ }
                    bump(glowEpoch)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) { // no glow for that box
            } finally {
                if (goalPainter === p) baking.remove(k)
            }
        }
    }

    /** LRU of [K.STACKED_GLOW_CACHE]: the newest first. */
    private fun put(k: Long, m: GlowMasks) {
        var i = 0
        while (i < glowCount && glowKeys[i] != k) i++
        if (i == glowCount) i = if (glowCount < glowKeys.size) glowCount++ else glowCount - 1
        while (i > 0) { glowKeys[i] = glowKeys[i - 1]; glowMasks[i] = glowMasks[i - 1]; i-- }
        glowKeys[0] = k; glowMasks[0] = m
    }

    /** Test seam: the cached glow boxes, newest first (device px, rounded). */
    internal fun glowCacheKeys(): List<Pair<Int, Int>> =
        (0 until glowCount).map { (glowKeys[it] ushr 32).toInt() to (glowKeys[it] and 0xFFFFFFFFL).toInt() }
}

/**
 * The body a draw hands the painter: [StackedFrameState] is immutable, so while a column folds (or is pinned open) over a
 * body whose own targets have it closed, the column's texts come from the last body that had it open (WP-2: a folded
 * column's slot is `Blank`). Built once per change of its inputs, never per frame.
 */
private class BodyMerge {
    private var src: StackedFrameState? = null
    private var col: StackedFrameState? = null
    private var sogSrc: StackedFrameState? = null
    private var out: StackedFrameState? = null

    fun of(body: StackedFrameState, f: StackedFrame, lastClock: StackedFrameState?, lastSog: StackedFrameState?): StackedFrameState {
        val c = if (!body.targets.clockOpen && f.clockWidth > 0f && lastClock != null && lastClock !== body) lastClock else null
        val g = if (!body.targets.sogOpen && f.sogWidth > 0f && lastSog != null && lastSog !== body &&
            (body.awaySog.isEmpty() || body.homeSog.isEmpty())) lastSog else null
        if (c == null && g == null) return body
        out?.let { if (body === src && c === col && g === sogSrc) return it }
        src = body; col = c; sogSrc = g
        return StackedFrameState(
            awayAbbrev = body.awayAbbrev, homeAbbrev = body.homeAbbrev, awayScore = body.awayScore, homeScore = body.homeScore,
            awaySog = g?.awaySog ?: body.awaySog, homeSog = g?.homeSog ?: body.homeSog,
            periodText = c?.periodText ?: body.periodText, periodPx = c?.periodPx ?: body.periodPx,
            slot = c?.slot ?: body.slot, slotText = c?.slotText ?: body.slotText, slotPx = c?.slotPx ?: body.slotPx,
            clockSecs = c?.clockSecs ?: body.clockSecs, columnDim = c?.columnDim ?: body.columnDim, targets = body.targets,
        ).also { out = it }
    }
}

/**
 * (Stacked §10) The merged semantics string: the Pill's [Describe], plus the shots phrase while the SOG column shows them
 * ("Shots on goal: Calgary 12, Edmonton 20"). The goal string stands alone, as the power play's does.
 */
internal class StackedDescribe(private val res: Resources, private val v: ScoreBugView, private val t: StackedTargets) {
    fun text(): String {
        val base = Describe(res, v).text()
        val a = v.away.sog
        val h = v.home.sog
        if (v.goalSide != null || !t.sogOpen || a == null || h == null) return base
        return "$base. " + res.getString(R.string.sports_desc_sog, v.away.name, a, v.home.name, h)
    }
}

/** Places the bug's layer so the footprint (inset by [margin] design px inside it) lands on the placed footprint. */
private fun Modifier.stackedPlaceAt(holder: StackedHolder, pos: Animatable<Offset, *>, d: Float, margin: Float): Modifier = layout { m, c ->
    val p = m.measure(c)
    layout(p.width, p.height) {
        val o = holder.offset(pos)
        p.place(IntOffset(((o.x - margin) * d).roundToInt(), ((o.y - margin) * d).roundToInt()))
    }
}

/**
 * The place-mode hint beside the **drawn** box toward the screen centre (SB §6, N8): the drawn box is ([tagY], [tagW],
 * [tagH]) design px from the footprint's top-left. A sibling of the clipped layer, placed from the same [pos].
 */
@Composable
private fun StackedPlaceHint(
    holder: StackedHolder,
    pos: Animatable<Offset, *>,
    fade: Animatable<Float, *>,
    bugOnRight: Boolean,
    tagY: Float,
    tagW: Float,
    tagH: Float,
) {
    val hintDesc = androidx.compose.ui.res.stringResource(R.string.sports_place_hint_desc)
    Box(
        Modifier
            .layout { m, _ ->
                val p = m.measure(Constraints())
                layout(p.width, p.height) {
                    val o = holder.offset(pos)
                    val gap = (K.SCORE_BAR_GAP * density).roundToInt()
                    val bx = (o.x * density).roundToInt()
                    val x = if (bugOnRight) bx - p.width - gap else bx + (tagW * density).roundToInt() + gap
                    p.place(x, ((o.y + tagY) * density).roundToInt() + ((tagH * density).roundToInt() - p.height) / 2)
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

/** (§4.3, §8) The place-mode sample with no follow: "AWY 0 / HME 0", clock "0:00", the period slot empty, fallback discs. */
private fun stackedSampleView(res: Resources) = ScoreBugView(
    away = TeamLine(res.getString(R.string.sports_sample_away), res.getString(R.string.sports_sample_away), 0, null),
    home = TeamLine(res.getString(R.string.sports_sample_home), res.getString(R.string.sports_sample_home), 0, null),
    top = CentreText.Clock,
    bottom = null,
    dim = false,
    goalSeq = 0,
    goalSide = null,
    clockSecs = 0,
)
