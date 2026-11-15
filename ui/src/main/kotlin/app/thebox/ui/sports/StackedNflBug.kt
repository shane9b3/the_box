package app.thebox.ui.sports

import android.content.res.Resources
import android.graphics.Bitmap
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.thebox.core.sports.Box as DesignBox
import app.thebox.core.sports.CentreText
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GoalTimeline
import app.thebox.core.sports.League
import app.thebox.core.sports.NflBarKey
import app.thebox.core.sports.NflBarKind
import app.thebox.core.sports.NflScore
import app.thebox.core.sports.NflScoreFrame
import app.thebox.core.sports.NflScoreKind
import app.thebox.core.sports.NflScoreSpec
import app.thebox.core.sports.NflScoreTimeline
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.OverlayGeometry
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.PlacementInput
import app.thebox.core.sports.ScoreBugPlacement
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.ScoreBugSource
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.StackedColourTable
import app.thebox.core.sports.StackedNflFrame
import app.thebox.core.sports.StackedNflMock
import app.thebox.core.sports.StackedNflTargets
import app.thebox.core.sports.StackedNflTimeline
import app.thebox.core.sports.StackedNflTrack
import app.thebox.core.sports.StackedNflTransition
import app.thebox.core.sports.StackedPair
import app.thebox.core.sports.TeamColours
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
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * The NFL score bug in the Stacked style (docs/design/m6-nfl-stacked.md §4, §5; WP-4): its own copy of the shell (the
 * 150-ms fade on its own leaf `graphicsLayer { alpha; clip = true }` sized footprint + 2 M, the corner slide,
 * `FollowLogos` at the takeover badge size, the place hint, the live region), [StackedNflRun] (the eight layout tracks of
 * `StackedNflTimeline` keyed on `StackedNflTargets`, the flag sub-track, the pin) and the scoring takeover
 * (`NflScoreTimeline` under `NflScoreSpec.STACKED`, one score or a +7/+8 pair), drawing through one
 * `Spacer.drawBehind { … }` node tagged `SCORE_BUG_TAG` on the **drawn** box: the glow
 * ([StackedNflScorePainter.drawGlow]), the box ([StackedNflPainter.drawFrame]) and the takeover
 * ([StackedNflScorePainter.drawTakeover]). Reached only through `ScoreBug(...)`'s style branch (NFL and
 * `NhlBugStyle.STACKED`); [placement] carries `league = NFL` and `style = STACKED`, so `place` returns the footprint and
 * the box is drawn inside it, the rows at its left edge, or mirrored at its right edge in a right-hand corner.
 *
 * The ST S1 rules, carried from `StackedBug`:
 * 1. the pre-score body (and the bar's words) is held until the takeover's lead covers the box (216; the still path's
 *    frame A covers from its first draw); a pair's scorer digits follow tally A until B's lead covers (5 016);
 * 2. the first view of a follow, and its first non-waiting view, snap every track to rest (no burn, shake or slide);
 * 3. a hidden bug's tracks start nothing new and fade with it, park once the fade is done, and snap on reappearance; a
 *    follow that ends fades with its layout and colours and resets after the fade;
 * 4. place mode parks the tracks at the shown view's targets (the NFL §4.6 sample with no follow);
 * 5. a score mid-takeover restarts it and keeps the pin;
 * 6. a score and a layout change in one snapshot: every layout track is pinned (mid-transition ones at their end) until
 *    the 3 600 release (8 400 for a pair); the bar's REKEY to the score label is the only bar change while pinned, and a
 *    flag or timeout mark that started during the pin shows at the release only if it is still active (RC4: the
 *    takeover wins);
 * 7. hide, place mode, a new game and a size change cancel the takeover and release the pin with snaps.
 *
 * **Idle draws nothing.** Every driver is an `Animatable` read only in draw, so the node redraws only while a
 * transition, the flag hold (≤ 8 s) or a takeover runs; one composition per snapshot, none during a takeover.
 */
@Composable
internal fun StackedNflBug(
    source: ScoreBugSource,
    placement: PlacementInput,
    geometry: OverlayGeometry,
    modifier: Modifier,
    place: (PlacementInput, OverlayGeometry) -> DesignBox?,
    hooks: StackedNflHooks,
) {
    if (GuideDebugCounters.ENABLED) SideEffect { GuideDebugCounters.bump(COMPOSITIONS_STACKED_NFL_BUG) }
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val d = LocalDensity.current.density // device px per design px (BoxDesignScale)

    val bug by source.bug.collectAsState()
    val sample = remember(res) { stackedNflSampleView(res) }
    // R4: `view()` is null before kick-off, so nothing is drawn then (only place mode shows the sample).
    val view: ScoreBugView? = bug ?: if (placement.placing) sample else null
    val box = if (view != null) place(placement, geometry) else null // the footprint (§5.1)
    val large = placement.size == ScoreBugSize.LARGE

    val holder = remember { NflStackedHolder() }
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
    // The same function the placer uses, so the layer always matches the placed footprint (§5.1).
    val footW = ScoreBugPlacement.width(shownSize, League.NFL, NhlBugStyle.STACKED)
    val footH = ScoreBugPlacement.height(shownSize, League.NFL, NhlBugStyle.STACKED)
    // The box painter: words and measurement (built here, as the precedent's); the shadow bake runs off the main thread.
    val painter = remember(s, shownLarge) {
        StackedNflPainter(s, shownLarge, sansTypeface(context, R.font.instrument_sans_bold), StackedPainter.barTypeface(context))
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
    val followed = holder.lastFollowed
    LaunchedEffect(followed?.away?.logo, followed?.home?.logo, s) {
        if (followed == null) return@LaunchedEffect
        // ST S6: one bitmap per team at the takeover's badge size (58.5 × s); the rows draw it scaled down.
        val px = (STACKED_NFL_BADGE * s).roundToInt().coerceAtLeast(1)
        logos.start(followed.away.logo, followed.home.logo, px, px)
    }

    // ---- the runner and the takeover. Bookkeeping runs above the visibility return, so a score while hidden or placing
    // updates lastSeq and is never replayed on reappearance; takeoverOn and the drivers are read only in draw.
    val run = remember { StackedNflRun(scope, hooks) }
    val g = remember { NflStackedScoreRun() }
    val takeoverOn = remember { mutableStateOf(false) }
    val driver = remember { Animatable(0f) }
    val scoreFrame = remember { NflScoreFrame() }
    /** The pre-score body, drawn until the takeover's lead covers the box (rule 1); read only in draw. */
    val held = remember { mutableStateOf<StackedNflFrameState?>(null) }
    val liveTexts = remember { StackedNflLiveText() }
    // The colours and the names follow the **shown** view (the held one through a fade-out), never the place-mode sample.
    val colourView = (view ?: holder.lastView)?.takeIf { it !== sample }
    val awayAbbrev = colourView?.away?.abbrev ?: ""
    val homeAbbrev = colourView?.home?.abbrev ?: ""
    val pair = remember(awayAbbrev, homeAbbrev) { hooks.colours(awayAbbrev.ifEmpty { null }, homeAbbrev.ifEmpty { null }) }
    run.painter = painter; run.res = res; run.s = s; run.pair = pair; run.away = awayAbbrev; run.home = homeAbbrev
    val margin = if (shownLarge) K.GOAL_LAYER_MARGIN_LARGE else K.GOAL_LAYER_MARGIN
    hooks.probe?.let { p ->
        p.takeoverOn = takeoverOn; p.driver = driver
        p.pinned = { run.pinned }
        p.trackDriver = { run.track(it) }
        p.flagDriver = run.flagDriver
        p.barKey = { run.barWant }
        p.barContent = { run.bar }
        p.scorePainter = { run.scorePainter }
        p.painter = { painter }
        p.shadow = { shadow.value }
        p.glowKeys = { run.glowCacheKeys() }
    }

    // The takeover painter: nothing is built until a game is followed (the place-mode sample never needs it). Then the
    // Barlow load, once per size, off the main thread; a failure means no takeover, never a crash.
    val hasFollow = followed != null
    LaunchedEffect(s, shownLarge, hasFollow) {
        run.setScorePainter(null)
        if (!hasFollow) return@LaunchedEffect
        val built = try {
            withContext(hooks.background) {
                StackedNflScorePainter(
                    s, shownLarge,
                    sansTypeface(context, R.font.barlow_condensed_black_italic), sansTypeface(context, R.font.instrument_sans_bold),
                    hooks.spec,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        run.setScorePainter(built)
    }

    // ---- what the tracks follow: the shown view's targets (§3.4; the UI never re-derives a state)
    val tView = view ?: holder.lastView
    val targets: StackedNflTargets? = remember(tView) { tView?.let(hooks.targets) }
    run.inTargets = targets
    run.inFlagId = tView?.nfl?.flagId

    fun cancelTakeover(releaseThePin: Boolean = true) {
        if (g.active) { g.active = false; g.token++ }
        takeoverOn.value = false // a write, never a read: composition must not subscribe to it
        held.value = null
        if (releaseThePin) run.release(snap = true, driverMs = Snapshot.withoutReadObservation { driver.value })
    }
    val real = bug
    if (real == null) {
        cancelTakeover()
        // The follow ended (or went stale past the hide): the tracks freeze and fade with the bug (rule 3), and reset only
        // once the fade is done (or right away in place mode, or when the next follow's first view resets them).
        if (g.seen) holder.resetPending = true
        g.seen = false // the next snapshot is a "first snapshot" again: nothing replays
    } else if (!g.seen || real.gameId != g.gameId) {
        cancelTakeover()
        run.reset()
        holder.resetPending = false
        g.seen = true; g.gameId = real.gameId; g.lastSeq = real.goalSeq; g.size = placement.size
    }
    if (holder.resetPending && (box != null || fadeDone)) {
        holder.resetPending = false
        run.reset()
        run.dropGlow()
    }
    // Rules 3 and 4: hidden, the tracks freeze and fade with the bug, then park once the fade is done; place mode keeps
    // them at rest on the shown view's targets; reappearing and leaving place mode snap.
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
        // Rule 2: the first view of a follow, and its first non-waiting one, snap: nothing animates in on follow.
        if (!run.viewSeen) { run.viewSeen = true; run.snapAll() }
        if (!run.freshSeen && !waitingView(real)) { run.freshSeen = true; run.snapAll() }
        if (box == null || placement.placing || placement.size != g.size) {
            cancelTakeover()
            if (placement.size != g.size) run.snapAll()
            g.size = placement.size
        }
        if (real.goalSeq != g.lastSeq) {
            g.lastSeq = real.goalSeq
            // A new score mid-pin restarts the takeover, but the pin continues (rule 5): no release on this path.
            cancelTakeover(releaseThePin = false)
            val side = real.goalSide
            val scores = real.nfl?.scores.orEmpty()
            val sp = run.scorePainter
            if (side != null && scores.isNotEmpty() && box != null && !placement.placing && sp != null) {
                val a = scores[0]
                val b = scores.getOrNull(1)
                g.side = side
                g.first = a; g.second = b
                g.firstList = listOf(a); g.secondList = b?.let { listOf(it) }
                g.awayAbbrev = real.away.abbrev; g.homeAbbrev = real.home.abbrev
                g.glowOn = hooks.glowAllowed(placement, geometry, box, margin)
                g.active = true; g.token++
                // This composition already shows the score view: until the effect sets takeoverOn and the lead covers
                // the box, the draw keeps the previous body (rule 1) and the layout keeps the pre-score targets (rule 6).
                held.value = holder.lastFrame
                run.pin(side, a, b)
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
        val sp = run.scorePainter
        val first = g.first
        val firstList = g.firstList
        if (sp == null || first == null || firstList == null) { // the painter went (a size change): skipped
            g.active = false; held.value = null
            run.release(snap = false, driverMs = 0f)
            return@LaunchedEffect
        }
        val side = g.side
        val second = g.second
        val secondList = g.secondList
        val spec = hooks.spec
        val image = (if (side == Side.AWAY) logos.away else logos.home).image?.asAndroidBitmap()
        val p = run.pair ?: hooks.colours(null, null)
        val colours = (if (side == Side.AWAY) p.away else p.home).goal
        // §4.3: a pair's scorer digits follow tally A until B's lead covers the box (the body is immutable: one copy).
        g.pairBody = if (second != null) holder.lastFrame?.let { withScores(it, first.away, first.home) } else null
        withContext(hooks.context) {
            val still = motionOff() // the exact scale Compose applies to animateTo
            fun beginSegment(scores: List<NflScore>, segment: Int) {
                hooks.begin(sp, side, scores, colours, image, g.awayAbbrev, g.homeAbbrev, run.pinBoxW)
                hooks.probe?.begins?.add(StackedNflProbe.Begin(side, scores.single(), segment, run.pinBoxW, still))
            }
            beginSegment(firstList, 0)
            g.painter = sp
            g.still = still
            g.pair = second != null
            driver.snapTo(0f)
            takeoverOn.value = true
            val takeover = K.NFL_TAKEOVER_MS.toFloat()
            try {
                if (still) {
                    run.covered(0) // frame A covers the box from its first draw
                    delay(K.NFL_TAKEOVER_MS) // frame A; the delays are not scaled
                    driver.snapTo(takeover) // frame B
                    if (second == null || secondList == null) {
                        if (g.token == token) run.release(snap = false, driverMs = takeover)
                        delay(spec.stillFrameBMs.toLong())
                    } else {
                        if (g.token == token) run.interim()
                        delay((spec.pairSecondMs - takeover).toLong())
                        beginSegment(secondList, 1)
                        driver.snapTo(spec.pairSecondMs) // frame A2
                        if (g.token == token) run.covered(1)
                        delay(K.NFL_TAKEOVER_MS)
                        driver.snapTo(spec.pairSecondMs + takeover) // frame B2
                        if (g.token == token) run.release(snap = false, driverMs = spec.pairSecondMs + takeover)
                        delay((spec.pairEndMs - spec.pairSecondMs - takeover).toLong().coerceAtLeast(0L))
                    }
                } else {
                    // The lead covering the box, the pair's steps and the release are children watching the driver, so
                    // the takeover keeps its single tween under any MotionDurationScale.
                    launch {
                        snapshotFlow { driver.value >= GoalTimeline.LEAD_COVERED_MS }.first { it }
                        if (g.token == token) run.covered(0)
                    }
                    val releaseAt = if (second == null) takeover else spec.pairSecondMs + takeover
                    launch {
                        snapshotFlow { driver.value >= releaseAt }.first { it }
                        if (g.token == token) run.release(snap = false, driverMs = driver.value)
                    }
                    if (secondList != null) {
                        launch {
                            snapshotFlow { driver.value >= takeover }.first { it }
                            if (g.token == token) run.interim() // "EXTRA POINT" between the takeovers (NFL lead 3)
                        }
                        launch {
                            // B's lead starts from 0 at pairSecondMs: a frame drawn before this begin shows none of B.
                            snapshotFlow { driver.value >= spec.pairSecondMs }.first { it }
                            if (g.token == token) beginSegment(secondList, 1)
                        }
                        launch {
                            snapshotFlow { driver.value >= spec.pairSecondMs + GoalTimeline.LEAD_COVERED_MS }.first { it }
                            if (g.token == token) run.covered(1)
                        }
                    }
                    val end = if (second == null) spec.endMs else spec.pairEndMs
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
        if (GuideDebugCounters.ENABLED) GuideDebugCounters.bump(FRAMES_STACKED_NFL_BUG)
        hooks.frame(painter, shownView, shownTargets, res)
    }
    holder.lastFrame = frame
    // The column texts a folding clock column fades out with (a folded column's slot is Blank in the new body).
    if (frame.targets.clockOpen) holder.lastClockBody = frame
    val isSample = shownView === sample
    val desc = remember(shownView, shownTargets, res) { StackedNflDescribe(res, shownView, shownTargets).text() }
    val liveText = if (isSample) "" else liveTexts.next(shownView, res)
    // The bar opens below an effective top corner's box and above a bottom one's, from the placed footprint (S11).
    val up = ScoreBugPlacement.stackedBarUp(last)
    // §5.3: a right-hand corner draws the layout mirrored, the rows hugging the footprint's right edge.
    val mirrored = ScoreBugPlacement.stackedMirrored(last)
    val rightSide = last.l > 960f

    // The drawn box at the targets' rest, in design px from the footprint's top-left (§5.1: the rows never move).
    val restW = nflRestBoxW(shownTargets.clockOpen)
    val topBox = ScoreBugPlacement.stackedTopBox(last, shownSize, restW)
    val topH = topBox.b - topBox.t
    val barRest = if (shownTargets.bar != null) K.STACKED_BAR_H * k else 0f
    val tagW = topBox.r - topBox.l
    val tagX = topBox.l - last.l
    val topY = topBox.t - last.t
    val tagY = if (up) topY - barRest else topY
    val tagH = topH + barRest
    val originDy = (topY - tagY) * d
    val originDx = stackedOriginDx(mirrored, margin, footW, K.STACKED_ROWS_W * k, tagX, d)

    val args = remember { StackedNflDrawArgs() }
    val merge = remember { NflBodyMerge() }
    /** Fills [args] with this draw's inputs: the drivers are read here (in draw) and nowhere in composition. */
    fun fillArgs(): StackedNflDrawArgs {
        val a = args
        val on = takeoverOn.value
        var t = 0f
        a.score = null; a.scoreSide = null; a.takeoverOn = false
        if (on) {
            t = driver.value
            val f = hooks.scoreSampler.sample(t, g.still, g.kindA(), g.kindB(), scoreFrame)
            if (!f.done) { a.score = f; a.scoreSide = g.side; a.takeoverOn = true }
        }
        // Pending, or the lead still wiping in: the pre-score body (rule 1). The still path's frame A covers at once.
        val hold = held.value
        val covered = !g.still && t >= GoalTimeline.LEAD_COVERED_MS || g.still
        val bCovered = if (g.still) hooks.spec.pairSecondMs else hooks.spec.pairSecondMs + GoalTimeline.LEAD_COVERED_MS
        val pairA = g.pairBody
        val base = when {
            hold != null && (!on || !covered) -> hold
            on && g.pair && pairA != null && t < bCovered -> pairA
            else -> frame
        }
        val f = run.sampleForDraw()
        a.frame = f
        a.state = merge.of(base, f, holder.lastClockBody)
        a.bar = run.bar
        a.barPrev = run.barPrevForDraw()
        a.colours = pair
        if (isSample) {
            a.awayLogo = null; a.homeLogo = null // the sample never borrows the follow's logos
        } else {
            a.awayLogo = logos.away.image?.asAndroidBitmap()
            a.homeLogo = logos.home.image?.asAndroidBitmap()
        }
        a.mirrored = mirrored
        a.up = up
        a.still = if (on) g.still else run.still
        return a
    }
    fun drawBug(c: android.graphics.Canvas) {
        if (GuideDebugCounters.ENABLED) GuideDebugCounters.bump(DRAWS_STACKED_NFL_BUG)
        val a = fillArgs()
        val gp = g.painter
        val sf = a.score
        if (a.takeoverOn && gp != null && sf != null && g.glowOn) {
            // The glow box is the pinned box: its left edge mirrored left of the rows, its top above an upward bar.
            val gx = if (mirrored) (K.STACKED_ROWS_W - run.pinBoxW) * s else 0f
            val gy = if (up && run.pinBarOpen) -K.STACKED_BAR_H * s else 0f
            gp.drawGlow(c, sf, run.glowForDraw(), gx, gy)
        }
        painter.drawFrame(c, a)
        if (a.takeoverOn && gp != null && sf != null) gp.drawTakeover(c, sf, mirrored)
    }
    hooks.probe?.let { it.args = ::fillArgs; it.draw = ::drawBug }
    // The box is drawn at absolute offsets inside its footprint: a right-to-left locale must not mirror the spacers.
    val m = margin
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier
                .nflStackedPlaceAt(holder, pos, d, m)
                // A leaf layer (never an ancestor of the SurfaceView): the fade, clipped to the footprint + M (§4.5).
                .graphicsLayer { alpha = fade.value; clip = true }
                .size((footW + 2 * m).dp, (footH + 2 * m).dp)
                .testTag(STACKED_NFL_LAYER_TAG),
        ) {
            Spacer(
                Modifier
                    .offset((m + tagX).dp, (m + tagY).dp)
                    .size(tagW.dp, tagH.dp)
                    .testTag(SCORE_BUG_TAG)
                    .semantics { contentDescription = desc }
                    .drawBehind {
                        drawIntoCanvas {
                            val nc = it.nativeCanvas
                            // The painters' origin is the rows' left edge at the top part's top (StackedNflDrawArgs).
                            nc.translate(originDx, originDy)
                            drawBug(nc)
                            nc.translate(-originDx, -originDy)
                        }
                    },
            )
            // The persistent live region (GA S9): on the drawn box, always composed while the bug is.
            Spacer(
                Modifier
                    .offset((m + tagX).dp, (m + tagY).dp)
                    .size(tagW.dp, tagH.dp)
                    .testTag(SCORE_BUG_LIVE_TAG)
                    .semantics { contentDescription = liveText; liveRegion = LiveRegionMode.Polite },
            )
        }
        // A sibling of the clipped layer, not inside it (GA §1); positioned from the drawn box, not the footprint (N8).
        if (placement.placing) NflStackedPlaceHint(holder, pos, fade, rightSide, tagX, tagY, tagW, tagH)
    }
}

/** The takeover badge (mock 130 × 0.45 = 58.5 Standard design px); the logos are fetched at this size (ST S6). */
internal const val STACKED_NFL_BADGE = StackedNflMock.SCORE_BADGE * K.STACKED_SCALE

/** The flag bar's wave runs at most this long from its REKEY: the flag mark's hold (S-D8, the idle budget). */
private const val FLAG_HOLD_MS = K.NFL_FLAG_MARK_MS

/** The top part's width at rest, Standard design px: the rows plus the open clock column (§5.1: 333 / 243). */
internal fun nflRestBoxW(clockOpen: Boolean): Float = K.STACKED_ROWS_W + (if (clockOpen) K.STACKED_CLOCK_W else 0f)

/** NFL views put "Delayed" / "No update" on either line: the follow has not yet shown a fresh view. */
private fun waitingView(v: ScoreBugView): Boolean =
    v.top is CentreText.Delayed || v.top is CentreText.NoUpdate || v.bottom is CentreText.Delayed || v.bottom is CentreText.NoUpdate

/** [b] with the rows' scores replaced by a pair's first tally (§4.3: the digits follow tally A until 5 016). */
private fun withScores(b: StackedNflFrameState, away: Int, home: Int) = StackedNflFrameState(
    awayAbbrev = b.awayAbbrev, homeAbbrev = b.homeAbbrev, awayScore = away.toString(), homeScore = home.toString(),
    periodText = b.periodText, periodPx = b.periodPx, slot = b.slot, slotText = b.slotText, slotPx = b.slotPx,
    clockSecs = b.clockSecs, columnDim = b.columnDim, targets = b.targets,
    slotBreak = b.slotBreak, slotDy1 = b.slotDy1, slotDy2 = b.slotDy2,
)

/**
 * (NFL Stacked §8.1; WP-4) The NFL Stacked bug's seams, as `StackedHooks` for hockey. Defaults are the real objects.
 *
 * - [colours]: the game's pair (`StackedColourTable.pair(League.NFL, …)` → `NflStackedColours`).
 * - [sampler]: one layout track (`StackedNflTimeline.sample`); [scoreSampler]: the takeover (`NflScoreTimeline` under
 *   `NflScoreSpec.STACKED`, the still version when `still`).
 * - [bakeGlow]: the score painter's exact-box glow bake (Standard design px), off the main thread.
 * - [logoFetch]: the followed game's logo loader; null = the shell's image loader.
 * - [context]: added to the effects' coroutine context (tests put a `MotionDurationScale` here); empty in production.
 * - [background]: where the painters are built and the masks baked; tests pass [EmptyCoroutineContext].
 * - [probe]: what a test can observe; null in production.
 *
 * (WP-4, trailing and defaulted) [spec]: the takeover's lengths the runner sequences on (`endMs`, `pairSecondMs`,
 * `pairEndMs`, `stillFrameBMs`; the same spec [scoreSampler] samples); [pinnedSampler]: a CLOCK / BAR track that was
 * mid-transition when a takeover started (`samplePinned`); [pipSampler], [flagSampler]: the PIPS rows and the FLAG
 * sub-track; [totalMs]: each transition's length (the drivers' targets); [targets]: the §3.4 table
 * (`StackedNflTargets.of`); [glowAllowed]: whether the takeover's glow may draw around the footprint; [frame],
 * [fillBar], [begin]: the painters' once-per-change calls (the box's words, the bar's content, a takeover segment).
 */
internal class StackedNflHooks(
    val colours: (String?, String?) -> StackedPair = { a, h -> StackedColourTable.pair(League.NFL, a, h) },
    val sampler: StackedNflSampler = StackedNflSampler.Real,
    val scoreSampler: NflScoreSampler = NflScoreSampler.Real,
    val bakeGlow: (StackedNflScorePainter, Float, Float) -> GlowMasks? = { p, w, h -> p.bakeGlow(w, h) },
    val logoFetch: LogoFetch? = null,
    val context: CoroutineContext = EmptyCoroutineContext,
    val background: CoroutineContext = Dispatchers.Default,
    val probe: StackedNflProbe? = null,
    val spec: NflScoreSpec = NflScoreSpec.STACKED,
    val pinnedSampler: StackedNflSampler = StackedNflSampler.Pinned,
    val pipSampler: StackedNflPipSampler = StackedNflPipSampler.Real,
    val flagSampler: StackedNflFlagSampler = StackedNflFlagSampler.Real,
    val totalMs: (StackedNflTrack, StackedNflTransition) -> Float = { t, kind -> StackedNflTimeline.totalMs(t, kind) },
    val targets: (ScoreBugView) -> StackedNflTargets = { StackedNflTargets.of(it) },
    val glowAllowed: (PlacementInput, OverlayGeometry, DesignBox, Float) -> Boolean = ScoreBugPlacement::glowAllowed,
    val frame: (StackedNflPainter, ScoreBugView, StackedNflTargets, Resources) -> StackedNflFrameState =
        { p, v, t, res -> p.frame(v, t, res) },
    val fillBar: (StackedNflPainter, NflBarContent, NflBarKey, StackedPair?, String, String, Resources) -> Unit =
        { p, c, key, pair, away, home, res -> p.fillBar(c, key, pair, away, home, res) },
    val begin: (StackedNflScorePainter, Side, List<NflScore>, TeamColours, Bitmap?, String, String, Float) -> Unit =
        { p, side, scores, c, badge, away, home, boxW -> p.begin(side, scores, c, badge, away, home, boxW) },
) {
    companion object { val Default = StackedNflHooks() }
}

/** (NFL Stacked §4.1) Samples one layout track into a preallocated frame (draw path; a `fun interface`, so nothing boxes). */
internal fun interface StackedNflSampler {
    fun sample(track: StackedNflTrack, kind: StackedNflTransition, tMs: Float, from: StackedNflFrame, to: Boolean, out: StackedNflFrame): StackedNflFrame

    companion object {
        val Real = StackedNflSampler { track, kind, t, from, to, out -> StackedNflTimeline.sample(track, kind, t, from, to, out) }
        /** The pin's sampler (ST B4): the layout channels at the end of the transition. */
        val Pinned = StackedNflSampler { track, kind, t, from, to, out -> StackedNflTimeline.samplePinned(track, kind, t, from, to, out) }
    }
}

/** (NFL Stacked §4.1 PIPS; WP-4) Samples one side's pips into a preallocated frame (draw path). */
internal fun interface StackedNflPipSampler {
    fun sample(side: Side, kind: StackedNflTransition, fromLit: Int, toLit: Int, tMs: Float, from: StackedNflFrame, out: StackedNflFrame): StackedNflFrame

    companion object {
        val Real = StackedNflPipSampler { side, kind, a, b, t, from, out -> StackedNflTimeline.samplePips(side, kind, a, b, t, from, out) }
    }
}

/** (NFL Stacked §4.1 FLAG; WP-4) Samples the flag bar's shake and wave at ms since its REKEY (draw path). */
internal fun interface StackedNflFlagSampler {
    fun sample(tMs: Float, still: Boolean, out: StackedNflFrame): StackedNflFrame

    companion object {
        val Real = StackedNflFlagSampler { t, still, out -> StackedNflTimeline.sampleFlag(t, still, out) }
    }
}

/**
 * (NFL Stacked §4.3, §4.4) Samples the takeover into a preallocated frame (draw path; no allocation): [first] alone, or a
 * §5.7 pair with [second]; [still] = the Reduce-animations version.
 */
internal fun interface NflScoreSampler {
    fun sample(tMs: Float, still: Boolean, first: NflScoreKind, second: NflScoreKind?, out: NflScoreFrame): NflScoreFrame

    companion object {
        val Real = NflScoreSampler { t, still, first, second, out ->
            val spec = NflScoreSpec.STACKED
            when {
                still -> NflScoreTimeline.sampleStill(spec, first, second, t, out)
                second != null -> NflScoreTimeline.samplePair(spec, first, second, t, out)
                else -> NflScoreTimeline.sample(spec, first, t, out)
            }
        }
    }
}

/**
 * (NFL Stacked §9.2; WP-4) What a test can observe of the NFL Stacked runner (`StackedNflDrawTest`, `StackedNflSeamTest`,
 * `StackedNflBoundsTest`, `StackedNflLogoTest`).
 */
internal class StackedNflProbe {
    /** Fills and returns what the next draw would pass `StackedNflPainter.drawFrame` (shared holder: copy what you keep). */
    var args: (() -> StackedNflDrawArgs)? = null
    /** The bug's whole draw, in the top part's coordinates. */
    var draw: ((android.graphics.Canvas) -> Unit)? = null
    var takeoverOn: State<Boolean>? = null
    var driver: Animatable<Float, *>? = null
    var pinned: (() -> Boolean)? = null
    var trackDriver: ((StackedNflTrack) -> Animatable<Float, *>)? = null
    /** The flag sub-track's driver (ms since the flag bar's REKEY). */
    var flagDriver: Animatable<Float, *>? = null
    /** The bar track's key (null = closed); the pinned score label while pinned. */
    var barKey: (() -> NflBarKey?)? = null
    /** The bar's current content (live holder). */
    var barContent: (() -> NflBarContent)? = null
    var scorePainter: (() -> StackedNflScorePainter?)? = null
    var painter: (() -> StackedNflPainter)? = null
    var shadow: (() -> NineSlice?)? = null
    /** The cached glow boxes, newest first (Standard design px × 10, rounded). */
    var glowKeys: (() -> List<Pair<Int, Int>>)? = null
    /** Called right after the composition's kick ([StackedNflRun.step] in its `SideEffect`). */
    var afterStep: (() -> Unit)? = null

    /** One transition a track started; for the bar, its key at the start; for the pips, the lit counts. */
    class Rec(val track: StackedNflTrack, val kind: StackedNflTransition, val to: Boolean, val barKey: NflBarKey?, val fromLit: Int, val toLit: Int)
    val transitions = mutableListOf<Rec>()
    /** One start of the flag sub-track: the flag's `lastPlay` id and whether it runs still (no shake, no wave). */
    class Flag(val id: String?, val still: Boolean)
    val flags = mutableListOf<Flag>()
    /** One end of the pin: the takeover driver then, and whether it was a snap (a cancel). */
    class Release(val driverMs: Float, val snap: Boolean)
    val releases = mutableListOf<Release>()
    /** One takeover segment's `begin`: the scorer, the segment's score, 0 / 1, the pinned top-part width, the path. */
    class Begin(val side: Side, val score: NflScore, val segment: Int, val boxW: Float, val still: Boolean)
    val begins = mutableListOf<Begin>()
    /** Each glow bake started (Standard design px) and each one published. */
    val glowBakes = mutableListOf<Pair<Float, Float>>()
    var glowPublished = 0
    /** The lead covered the box (or a still frame A started), per segment. */
    val covers = mutableListOf<Int>()
}

/** Plain holder for what must survive recomposition without being snapshot state (writes happen in composition). */
private class NflStackedHolder {
    var box: DesignBox? = null
    var large = false
    var positioned = false
    var drawn = false
    var lastView: ScoreBugView? = null
    /** The body the bug last composed for drawing: what a takeover holds while it starts (rule 1). */
    var lastFrame: StackedNflFrameState? = null
    /** The last body with the clock column open: the texts a folding column fades out with. */
    var lastClockBody: StackedNflFrameState? = null
    var lastFollowed: ScoreBugView? = null
    var resetPending = false

    fun offset(pos: Animatable<Offset, *>): Offset =
        if (positioned) pos.value else box?.let { Offset(it.l, it.t) } ?: Offset.Zero
}

/** The takeover trigger (GA §5.2, NFL §5.4, §5.7): a plain holder, written in composition and in the effect. */
private class NflStackedScoreRun {
    var seen = false
    var gameId: GameId? = null
    var lastSeq = -1
    var size: ScoreBugSize? = null
    var token = 0
    var active = false
    // Captured at start; never re-read.
    var side: Side = Side.AWAY
    var first: NflScore? = null
    var second: NflScore? = null
    var firstList: List<NflScore>? = null
    var secondList: List<NflScore>? = null
    var awayAbbrev = ""
    var homeAbbrev = ""
    var still = false
    var pair = false
    var glowOn = false
    var painter: StackedNflScorePainter? = null
    /** The pair's body with tally A (rule 1, §4.3), built once per pair. */
    var pairBody: StackedNflFrameState? = null

    fun kindA(): NflScoreKind = first?.kind ?: NflScoreKind.TOUCHDOWN
    fun kindB(): NflScoreKind? = second?.kind
}

/** One layout track: its driver (elapsed ms, read only in draw), its `from`, its transition and where it heads. */
private class NflTrackRun(val id: StackedNflTrack) {
    val driver = Animatable(0f)
    val from = StackedNflFrame()
    val next = StackedNflFrame()
    var kind = StackedNflTransition.STILL
    /** The transition's rest state (open / wide / on). */
    var to = false
    /** Where a boolean track is heading. */
    var want = false
    /** A pip track: the lit count it heads to (−1 hidden, −2 never set) and the transition's from / to counts. */
    var lit = -2
    var fromLit = 0
    var toLit = 0
    var job: Job? = null
    /** Mid-transition when the takeover started: sampled with the pin's sampler until the release (B4). */
    var pinSample = false
    val idle: Boolean get() = job?.isActive != true
}

/**
 * (NFL Stacked §4.1, §4.3; WP-4) The layout runner: eight tracks (CLOCK, BAR, the two row stripes, the two balls, the two
 * pip rows), each kicked only when its own target key changes (a clock-only update just redraws), with CSS-transition
 * semantics (a kick starts from the interrupted transition's current value); the flag sub-track; the bar's content
 * ([bar], [barPrev], captured at OPEN / REKEY); the pin (B4) and its score labels; the exact-box glow cache. A plain
 * holder; [epoch], the drivers and the glow epoch are the only state, read only in draw.
 */
internal class StackedNflRun(private val scope: CoroutineScope, private val hooks: StackedNflHooks) {
    private val clock = NflTrackRun(StackedNflTrack.CLOCK)
    private val barTrack = NflTrackRun(StackedNflTrack.BAR)
    private val stripeAway = NflTrackRun(StackedNflTrack.STRIPE_AWAY)
    private val stripeHome = NflTrackRun(StackedNflTrack.STRIPE_HOME)
    private val ballAway = NflTrackRun(StackedNflTrack.BALL_AWAY)
    private val ballHome = NflTrackRun(StackedNflTrack.BALL_HOME)
    private val pipsAway = NflTrackRun(StackedNflTrack.PIPS_AWAY)
    private val pipsHome = NflTrackRun(StackedNflTrack.PIPS_HOME)
    private val tracks = arrayOf(clock, barTrack, stripeAway, stripeHome, ballAway, ballHome, pipsAway, pipsHome)
    private val layout = arrayOf(clock, barTrack, stripeAway, stripeHome, ballAway, ballHome)

    /** What the bar says now, and (during a REKEY) what it said before. */
    val bar = NflBarContent()
    private val barPrev = NflBarContent()
    /** The bar track's key: null = closed; the pinned score labels (`NflBarKind.SCORE`, `PAT`) only while pinned. */
    var barWant: NflBarKey? = null
        private set
    /** The flag id the bar's FLAG content was keyed with (a new id re-keys the same FLAG key). */
    private var barFlagId: String? = null

    // ---- the flag sub-track (§4.1 FLAG): ms since the flag bar's REKEY, up to the hold
    val flagDriver = Animatable(0f)
    private var flagJob: Job? = null
    private var flagOn = false
    private var flagStill = false
    private var flagDone = false

    private val out = StackedNflFrame()
    /** Bumped when what draw samples changes in plain fields only (a kick, a cut, the pin); read in draw. */
    private val epoch = mutableIntStateOf(0)
    private var running = 0

    // ---- inputs, written in composition
    var painter: StackedNflPainter? = null
    var res: Resources? = null
    var s = 1f
    var pair: StackedPair? = null
    var away = ""
    var home = ""
    var inTargets: StackedNflTargets? = null
    var inFlagId: String? = null
    var hidden = true
    var wasPlacing = false
    var viewSeen = false
    var freshSeen = false
    /** The last transition ran under "Reduce animations" (the draw's `still` outside a takeover). */
    var still = false
        private set
    private var snapPending = false

    // ---- the pin (§4.3, B4)
    var pinned = false
        private set
    private var covered = false
    /** The pinned top part's width (Standard design px; 333 in play, 243 in a break) and whether its bar is open. */
    var pinBoxW = nflRestBoxW(true)
        private set
    var pinBarOpen = false
        private set
    private var pinH = 0f
    private var labelA: NflBarKey? = null
    private var labelB: NflBarKey? = null
    private var interimKey: NflBarKey? = null

    // ---- the glow (§4.5): baked per exact box (Standard design px), cached for this size, LRU STACKED_GLOW_CACHE
    var scorePainter: StackedNflScorePainter? = null
        private set
    private val glowKeys = LongArray(K.STACKED_GLOW_CACHE)
    private val glowMasks = arrayOfNulls<GlowMasks>(K.STACKED_GLOW_CACHE)
    private var glowCount = 0
    private val baking = HashSet<Long>()
    private val glowEpoch = mutableIntStateOf(0)

    fun track(id: StackedNflTrack): Animatable<Float, *> = tracks[id.ordinal].driver

    fun snapAll() { snapPending = true }

    /** Parks every track at the rest of where it was heading (hide after the fade, rule 3); the reappearance snaps. */
    fun park() {
        for (t in tracks) {
            t.job?.cancel(); t.job = null
            t.kind = StackedNflTransition.STILL; t.to = t.want; t.pinSample = false
            if (t.lit >= 0) { t.fromLit = t.lit; t.toLit = t.lit }
        }
        stopFlag()
        snapPending = true
        bump(epoch)
    }

    /** A new follow (or none): everything parks closed; the next view snaps (rule 2). */
    fun reset() {
        for (t in tracks) { t.want = false; t.lit = -2; t.fromLit = 0; t.toLit = 0 }
        barWant = null
        barFlagId = null
        bar.clear()
        barPrev.clear()
        park()
        pinned = false; covered = false; pinBarOpen = false
        labelA = null; labelB = null; interimKey = null
        viewSeen = false; freshSeen = false
    }

    /**
     * `SideEffect`: kicks each track whose key changed (or snaps them all when a snap was asked). Nothing moves while
     * hidden (rule 3) or pinned (rule 6: the layout, the pips and the marks wait for the release).
     */
    fun step() {
        if (hidden) return
        val tg = inTargets ?: return
        if (pinned) return
        val snap = snapPending
        snapPending = false
        evalBool(clock, tg.clockOpen, snap)
        evalBar(tg, snap)
        evalBool(stripeAway, tg.possSide == Side.AWAY, snap)
        evalBool(stripeHome, tg.possSide == Side.HOME, snap)
        evalBool(ballAway, tg.possSide == Side.AWAY, snap)
        evalBool(ballHome, tg.possSide == Side.HOME, snap)
        evalPips(pipsAway, Side.AWAY, tg.awayTimeouts, tg.burnSide == Side.AWAY, snap)
        evalPips(pipsHome, Side.HOME, tg.homeTimeouts, tg.burnSide == Side.HOME, snap)
    }

    // ------------------------------------------------------------------ the pin

    /**
     * A takeover starts (rule 6, B4). A fresh pin snaps the CLOCK and BAR tracks that are mid-transition to their end
     * (draw samples them with [StackedNflHooks.pinnedSampler]), fixes the box, prepares the score labels and queues the
     * glow; a second score mid-pin keeps the pin (rule 5) and holds the bar until its own lead covers the box.
     */
    fun pin(side: Side, a: NflScore, b: NflScore?) {
        if (!pinned) {
            // A reappearance in the score's own composition snaps first (in composition: reads unobserved).
            if (snapPending && !hidden) Snapshot.withoutReadObservation { step() }
            pinned = true
            for (t in arrayOf(clock, barTrack)) if (!t.idle) t.pinSample = true
            pinBoxW = nflRestBoxW(clock.want)
            pinBarOpen = barWant != null
            pinH = K.STACKED_H + (if (pinBarOpen) K.STACKED_BAR_H else 0f)
            requestGlow(pinBoxW, pinH)
        }
        covered = false
        labelA = NflBarKey(NflBarKind.SCORE, side, score = a.kind, points = a.points)
        interimKey = if (b != null) NflBarKey(NflBarKind.PAT, side) else null
        labelB = b?.let { NflBarKey(NflBarKind.SCORE, side, score = it.kind, points = it.points) }
        bump(epoch)
    }

    /**
     * Segment [segment]'s lead covers the box (216; the still path: frame A). The bar, if it was open at the pin, re-keys
     * to the score label "TOUCHDOWN | KC +6" in the scorer's colours (the only bar change while pinned, §4.3).
     */
    fun covered(segment: Int) {
        if (!pinned) return
        covered = true
        hooks.probe?.covers?.add(segment)
        val key = if (segment == 0) labelA else labelB
        if (key != null) rekeyPinned(key)
        bump(epoch)
    }

    /** A pair's 3 600: the bar says "EXTRA POINT" in the scorer's colours until B's lead covers (NFL lead decision 3). */
    fun interim() {
        if (!pinned) return
        interimKey?.let { rekeyPinned(it) }
    }

    private fun rekeyPinned(key: NflBarKey) {
        if (!pinBarOpen || key == barWant) return
        barPrev.copyFrom(bar)
        fill(bar, key, dim = false)
        barWant = key
        barFlagId = null
        stopFlag()
        barTrack.pinSample = false
        launchPlay(barTrack, snap = false) { s -> if (s) StackedNflTransition.STILL else StackedNflTransition.REKEY }
    }

    /**
     * Ends the pin. [snap]: a cancel (rule 7): the next step snaps every track. Otherwise (the 3 600 / 8 400 release, or
     * the end of a takeover) every track re-evaluates now against the current view and the normal transitions run: the
     * bar re-keys to the current state (a flag or timeout mark only if it is still active).
     */
    fun release(snap: Boolean, driverMs: Float) {
        if (!pinned) return
        pinned = false; covered = false
        for (t in tracks) t.pinSample = false
        hooks.probe?.releases?.add(StackedNflProbe.Release(driverMs, snap))
        bump(epoch)
        if (snap) snapPending = true
        // A cancel releases from composition; the kicks there must not subscribe it to the drivers.
        Snapshot.withoutReadObservation { step() }
    }

    // ------------------------------------------------------------------ the transitions

    private fun evalBool(t: NflTrackRun, want: Boolean, snap: Boolean) {
        if (want == t.want && !(snap && !(t.idle && t.to == want))) return
        t.want = want
        launchPlay(t, snap) { still -> if (still) StackedNflTransition.STILL else if (want) StackedNflTransition.OPEN else StackedNflTransition.CLOSE }
    }

    private fun evalBar(tg: StackedNflTargets, snap: Boolean) {
        val want = tg.bar
        val flagKey = want?.kind == NflBarKind.FLAG
        // A new penalty while the flag bar shows is a new flag (§3.3): the same key, a new id, a new REKEY and shake.
        val newFlag = flagKey && want == barWant && inFlagId != barFlagId
        if (want == barWant && !newFlag) {
            if (!(snap && !(barTrack.idle && barTrack.to == (want != null)))) return
        }
        val old = barWant
        val kind = when {
            want == old && !newFlag -> StackedNflTransition.STILL // a snap with the same key
            want == null -> StackedNflTransition.CLOSE // slides out the words it opened with
            old == null -> {
                fill(bar, want, tg.barDim)
                StackedNflTransition.OPEN
            }
            else -> {
                barPrev.copyFrom(bar)
                fill(bar, want, tg.barDim)
                StackedNflTransition.REKEY
            }
        }
        barWant = want
        barFlagId = if (flagKey) inFlagId else null
        barTrack.want = want != null
        val snapped = snap || kind == StackedNflTransition.STILL
        launchPlay(barTrack, snapped) { still -> if (still) StackedNflTransition.STILL else kind }
        if (flagKey) {
            if (kind != StackedNflTransition.STILL || !flagOn) startFlag(inFlagId, snapped)
        } else {
            stopFlag()
        }
    }

    /**
     * (§4.1 PIPS) A side's lit count changed: a drop with the timeout mark's caller on this side burns the pip at the new
     * count (BURN); another drop fades (CLOSE), a rise lights (OPEN); hidden ↔ shown and every snap is STILL.
     */
    private fun evalPips(t: NflTrackRun, side: Side, lit: Int?, burn: Boolean, snap: Boolean) {
        val want = lit?.coerceIn(0, StackedNflFrame.PIPS) ?: -1
        if (want == t.lit && !(snap && !t.idle)) return
        val prev = t.lit
        t.lit = want
        val toLit = want.coerceAtLeast(0)
        val fromLit = if (prev < 0) toLit else prev
        t.fromLit = fromLit; t.toLit = toLit
        t.want = toLit > 0
        val kind = when {
            prev < 0 || want < 0 -> StackedNflTransition.STILL
            toLit < fromLit && burn -> StackedNflTransition.BURN
            toLit > fromLit -> StackedNflTransition.OPEN
            toLit < fromLit -> StackedNflTransition.CLOSE
            else -> StackedNflTransition.STILL
        }
        launchPlay(t, snap || kind == StackedNflTransition.STILL) { still -> if (still) StackedNflTransition.STILL else kind }
    }

    private fun fill(c: NflBarContent, key: NflBarKey, dim: Boolean) {
        c.key = key
        c.dim = dim
        val p = painter
        val r = res
        if (p != null && r != null) hooks.fillBar(p, c, key, pair, away, home, r)
        c.key = key
        c.dim = dim
    }

    /** The flag sub-track starts (a FLAG OPEN / REKEY): the shake and the wave from 0, bounded by the flag hold. */
    private fun startFlag(id: String?, snap: Boolean) {
        flagJob?.cancel()
        flagOn = true
        flagDone = false
        flagJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(hooks.context) {
                val st = snap || motionOff()
                flagStill = st
                flagDriver.snapTo(0f)
                bump(epoch)
                hooks.probe?.flags?.add(StackedNflProbe.Flag(id, st))
                if (!st) {
                    try {
                        flagDriver.animateTo(FLAG_HOLD_MS.toFloat(), tween(FLAG_HOLD_MS.toInt(), easing = LinearEasing))
                        flagDone = true // the hold is over: the icon rests at 0°
                    } finally {
                        bump(epoch)
                    }
                }
            }
        }
    }

    private fun stopFlag() {
        if (!flagOn && flagJob == null) return
        flagJob?.cancel(); flagJob = null
        flagOn = false
        bump(epoch)
    }

    private inline fun launchPlay(t: NflTrackRun, snap: Boolean, crossinline kindOf: (Boolean) -> StackedNflTransition) {
        t.job?.cancel()
        t.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            running++
            try {
                withContext(hooks.context) {
                    val st = snap || motionOff() // §4.4: every layout transition snaps
                    if (!snap) still = st
                    play(t, kindOf(st))
                }
            } finally {
                running--
                if (running == 0) atRest()
            }
        }
    }

    /**
     * Starts [kind] on [t] from where the interrupted transition is now (CSS semantics). The driver snaps before the new
     * kind and `from` are assigned (the S1 order), so no frame draws the new transition at the old elapsed time.
     */
    private suspend fun play(t: NflTrackRun, kind: StackedNflTransition) {
        sampleTrack(t, t.next)
        t.driver.snapTo(0f)
        t.from.copyFrom(t.next)
        t.kind = kind; t.to = t.want
        bump(epoch)
        hooks.probe?.transitions?.add(
            StackedNflProbe.Rec(t.id, kind, t.to, if (t === barTrack) barWant else null, t.fromLit, t.toLit),
        )
        val ms = if (kind == StackedNflTransition.STILL) 0f else hooks.totalMs(t.id, kind)
        if (ms > 0f) t.driver.animateTo(ms, tween(ms.roundToInt(), easing = LinearEasing))
    }

    private fun sampleTrack(t: NflTrackRun, into: StackedNflFrame) {
        val v = t.driver.value
        when (t.id) {
            StackedNflTrack.PIPS_AWAY -> hooks.pipSampler.sample(Side.AWAY, t.kind, t.fromLit, t.toLit, v, t.from, into)
            StackedNflTrack.PIPS_HOME -> hooks.pipSampler.sample(Side.HOME, t.kind, t.fromLit, t.toLit, v, t.from, into)
            else -> (if (t.pinSample) hooks.pinnedSampler else hooks.sampler).sample(t.id, t.kind, v, t.from, t.to, into)
        }
    }

    // ------------------------------------------------------------------ draw

    /** Samples every track and the flag sub-track into the preallocated frame for draw (no allocation). */
    fun sampleForDraw(): StackedNflFrame {
        epoch.intValue // read in draw: a bump invalidates the bug's draw node
        for (t in tracks) sampleTrack(t, out)
        if (flagOn) {
            hooks.flagSampler.sample(if (flagDone) FLAG_HOLD_MS.toFloat() else flagDriver.value, flagStill || flagDone, out)
        } else {
            out.shakeDx = 0f; out.waveDeg = 0f
        }
        return out
    }

    /** The previous bar content while a REKEY runs (the painter lerps the background from it, `barBgMix`). */
    fun barPrevForDraw(): NflBarContent? = if (barTrack.kind == StackedNflTransition.REKEY) barPrev else null

    // ------------------------------------------------------------------ the glow (§4.5, B4)

    /** A new takeover painter (per size; null while none): the cache is for its masks only. */
    fun setScorePainter(p: StackedNflScorePainter?) {
        scorePainter = p
        glowCount = 0
        glowMasks.fill(null)
        baking.clear()
        bump(glowEpoch)
        if (p != null) atRest()
    }

    /** The follow ended; the cached glow masks go (a later bake fills the cache again). */
    fun dropGlow() {
        glowCount = 0
        glowMasks.fill(null)
        bump(glowEpoch)
    }

    /** The masks for the pinned box, once baked (the glow joins on the first frame after they land); draw only. */
    fun glowForDraw(): GlowMasks? {
        glowEpoch.intValue
        return cached(key(pinBoxW, pinH))
    }

    /** Every track idle: prewarm the glow for the box at rest, so a score normally finds its masks ready (§4.5). */
    private fun atRest() {
        if (pinned || hidden || running > 0) return
        if (scorePainter?.s != s) return // after a size change the old painter is still set until its effect clears it
        val tg = inTargets ?: return
        if (clock.want != tg.clockOpen || barWant != tg.bar) return
        requestGlow(nflRestBoxW(clock.want), K.STACKED_H + (if (barWant != null) K.STACKED_BAR_H else 0f))
    }

    private fun key(w: Float, h: Float): Long = ((w * 10f).roundToInt().toLong() shl 32) or ((h * 10f).roundToInt().toLong() and 0xFFFFFFFFL)

    private fun cached(k: Long): GlowMasks? {
        for (i in 0 until glowCount) if (glowKeys[i] == k) return glowMasks[i]
        return null
    }

    private fun requestGlow(w: Float, h: Float) {
        val p = scorePainter ?: return
        if (!(w > 0f) || !(h > 0f)) return
        val k = key(w, h)
        if (cached(k) != null || !baking.add(k)) return
        hooks.probe?.glowBakes?.add(w to h)
        scope.launch {
            try {
                val masks = withContext(hooks.background) { hooks.bakeGlow(p, w, h) }
                if (masks != null && scorePainter === p) {
                    put(k, masks)
                    hooks.probe?.let { it.glowPublished++ }
                    bump(glowEpoch)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) { // no glow for that box
            } finally {
                if (scorePainter === p) baking.remove(k)
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

    /** Test seam: the cached glow boxes, newest first (Standard design px × 10, rounded). */
    fun glowCacheKeys(): List<Pair<Int, Int>> =
        (0 until glowCount).map { (glowKeys[it] ushr 32).toInt() to (glowKeys[it] and 0xFFFFFFFFL).toInt() }
}

/** Bumps [s] without reading it: a bump in composition must not subscribe the composition to the draw's epoch. */
private fun bump(s: androidx.compose.runtime.MutableIntState) = Snapshot.withoutReadObservation { s.intValue += 1 }

/**
 * The body a draw hands the painter: [StackedNflFrameState] is immutable, so while the clock column folds (or is pinned
 * open) over a body whose own targets have it folded, the column's texts come from the last body that had it open. Built
 * once per change of its inputs, never per frame.
 */
private class NflBodyMerge {
    private var src: StackedNflFrameState? = null
    private var col: StackedNflFrameState? = null
    private var out: StackedNflFrameState? = null

    fun of(body: StackedNflFrameState, f: StackedNflFrame, lastClock: StackedNflFrameState?): StackedNflFrameState {
        val c = if (!body.targets.clockOpen && f.clockWidth > 0f && lastClock != null && lastClock !== body) lastClock else null
        if (c == null) return body
        out?.let { if (body === src && c === col) return it }
        src = body; col = c
        return StackedNflFrameState(
            awayAbbrev = body.awayAbbrev, homeAbbrev = body.homeAbbrev, awayScore = body.awayScore, homeScore = body.homeScore,
            periodText = c.periodText, periodPx = c.periodPx, slot = c.slot, slotText = c.slotText, slotPx = c.slotPx,
            clockSecs = c.clockSecs, columnDim = c.columnDim, targets = body.targets,
            slotBreak = c.slotBreak, slotDy1 = c.slotDy1, slotDy2 = c.slotDy2,
        ).also { out = it }
    }
}

private fun shortOf(t: TeamLine) = t.shortName ?: t.name

/**
 * (NFL Stacked §8.4) The semantics string: the Pill's [NflDescribe] (score, clock, possession, down, red zone), plus the
 * timeouts while the pips show them ("Timeouts left: Chiefs 3, Bills 2"). The score string stands alone.
 */
internal class StackedNflDescribe(private val res: Resources, private val v: ScoreBugView, private val t: StackedNflTargets) {
    fun text(): String {
        val base = NflDescribe(res, v).text()
        val a = t.awayTimeouts
        val h = t.homeTimeouts
        if (v.goalSide != null || a == null || h == null || t.timeoutSlots <= 0) return base
        val sep = if (base.endsWith(".")) " " else ". "
        return base + sep + res.getString(R.string.sports_desc_nfl_timeouts, shortOf(v.away), a, shortOf(v.home), h)
    }
}

/**
 * (NFL §8, NFL Stacked §8.4) The live region's text: the score announcement while the score mark is set (one for a
 * +7/+8 pair); "Flag on the play." while a flag mark holds (once per `flagId`: the text does not change while it holds);
 * "Timeout, Bills." for a **confirmed** timeout mark only (Rec 9); else "". A stale or waiting view leaves it unchanged;
 * a new follow resets it. Idempotent per view instance.
 */
internal class StackedNflLiveText {
    private var game: GameId? = null
    private var lastView: ScoreBugView? = null
    var text = ""
        private set

    fun next(v: ScoreBugView, res: Resources): String {
        if (v === lastView) return text
        lastView = v
        if (v.gameId != game) { game = v.gameId; text = "" }
        if (waitingView(v)) return text
        val n = v.nfl
        val mark = n?.timeout
        text = when {
            v.goalSide != null -> stackedNflScoreText(res, v) ?: ""
            n?.flagId != null -> res.getString(R.string.sports_live_nfl_flag)
            mark != null && mark.confirmed -> res.getString(R.string.sports_live_nfl_timeout, shortOf(if (mark.side == Side.AWAY) v.away else v.home))
            else -> ""
        }
        return text
    }
}

private fun kindWord(res: Resources, k: NflScoreKind): String = res.getString(
    when (k) {
        NflScoreKind.TOUCHDOWN -> R.string.sports_nfl_td
        NflScoreKind.EXTRA_POINT -> R.string.sports_nfl_xp
        NflScoreKind.FIELD_GOAL -> R.string.sports_nfl_fg
        NflScoreKind.SAFETY -> R.string.sports_nfl_safety
        NflScoreKind.TWO_POINT -> R.string.sports_nfl_2pt
    },
).lowercase(Locale.getDefault())

/** The score announcement of a view under the mark (the Pill's wording); null with no score kind. */
private fun stackedNflScoreText(res: Resources, v: ScoreBugView): String? {
    val scores = v.nfl?.scores.orEmpty()
    val side = v.goalSide ?: return null
    if (scores.isEmpty()) return null
    val a = v.away
    val h = v.home
    val scorer = (if (side == Side.AWAY) a else h).name
    val first = kindWord(res, scores[0].kind)
    return if (scores.size >= 2) {
        res.getString(R.string.sports_live_nfl_pair, shortOf(a), a.score ?: 0, shortOf(h), h.score ?: 0, scorer, first, kindWord(res, scores[1].kind))
    } else {
        res.getString(R.string.sports_live_nfl_score, shortOf(a), a.score ?: 0, shortOf(h), h.score ?: 0, scorer, first)
    }
}

/** [max] when the parent bounds that axis, else the content's own [own] size. */
private fun fullOr(bounded: Boolean, max: Int, own: Int): Int = if (bounded) max else own

/**
 * Places the bug's layer so the footprint (inset by [margin] design px inside it) lands on the placed footprint. The node
 * takes the whole of its parent's bounded space and places the layer at an absolute offset inside it, so a right-to-left
 * parent cannot move the box.
 */
private fun Modifier.nflStackedPlaceAt(holder: NflStackedHolder, pos: Animatable<Offset, *>, d: Float, margin: Float): Modifier = layout { m, c ->
    val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
    layout(fullOr(c.hasBoundedWidth, c.maxWidth, p.width), fullOr(c.hasBoundedHeight, c.maxHeight, p.height)) {
        val o = holder.offset(pos)
        p.place(IntOffset(((o.x - margin) * d).roundToInt(), ((o.y - margin) * d).roundToInt()))
    }
}

/** The place-mode hint beside the **drawn** box toward the screen centre (SB §6, N8): a sibling of the clipped layer. */
@Composable
private fun NflStackedPlaceHint(
    holder: NflStackedHolder,
    pos: Animatable<Offset, *>,
    fade: Animatable<Float, *>,
    bugOnRight: Boolean,
    tagX: Float,
    tagY: Float,
    tagW: Float,
    tagH: Float,
) {
    val hintDesc = androidx.compose.ui.res.stringResource(R.string.sports_place_hint_desc)
    Box(
        Modifier
            .layout { m, c ->
                val p = m.measure(Constraints())
                layout(fullOr(c.hasBoundedWidth, c.maxWidth, p.width), fullOr(c.hasBoundedHeight, c.maxHeight, p.height)) {
                    val o = holder.offset(pos)
                    val gap = (K.SCORE_BAR_GAP * density).roundToInt()
                    val bx = ((o.x + tagX) * density).roundToInt()
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

/**
 * (NFL Stacked §3.4 "Place mode, no follow", NFL §4.6) The place-mode sample: KC 24 – BUF 17, "4TH" / "3:42",
 * "1ST & 10 | KC 35", the ball with KC, pips 3 / 2 of 3; logos as discs.
 */
private fun stackedNflSampleView(res: Resources): ScoreBugView {
    val v = nflSampleView(res)
    return v.copy(
        nfl = v.nfl?.copy(awayTimeouts = 3, homeTimeouts = 2, timeoutSlots = K.NFL_TIMEOUTS_MAX),
        period = PeriodLabel(PeriodKind.REG, 4),
    )
}
