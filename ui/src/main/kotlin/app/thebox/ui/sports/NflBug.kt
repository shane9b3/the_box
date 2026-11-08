// Contract (NFL commit 0, docs/design/m6-nfl.md §4.6, §11): `NflBug`'s and `NflHooks`' signatures are fixed for WP-4 (the
// shell copy, `NflRun`, `NflTabRun`); changes go through the lead. (WP-4) `NflHooks` gained trailing, defaulted seams only
// (`begin`, `ppSampler`, `bakeLabels`, `tabBegin`, `frame`, `probe`): the commit-0 call `NflHooks.Default` is unchanged.
package app.thebox.ui.sports

import android.content.res.Resources
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
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
import app.thebox.core.sports.Box as DesignBox
import app.thebox.core.sports.CentreText
import app.thebox.core.sports.DownText
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GoalTimeline
import app.thebox.core.sports.League
import app.thebox.core.sports.NflScore
import app.thebox.core.sports.NflScoreFrame
import app.thebox.core.sports.NflScoreKind
import app.thebox.core.sports.NflScoreTimeline
import app.thebox.core.sports.NflTab
import app.thebox.core.sports.NflTeamColours
import app.thebox.core.sports.OverlayGeometry
import app.thebox.core.sports.PlacementInput
import app.thebox.core.sports.PpFrame
import app.thebox.core.sports.PpTrack
import app.thebox.core.sports.PpTransition
import app.thebox.core.sports.ScoreBugPlacement
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.ScoreBugSource
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.TabLead
import app.thebox.core.sports.TabTail
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

/** Debug composition counter of [NflBug] (NFL §4.6: one per snapshot, none during a takeover); never the NHL's counter. */
const val COMPOSITIONS_NFL_BUG = "NflBug"
/** Debug counter: builds of the NFL bug's resolved words ([NflBugFrame]); one per change of what is shown. */
const val FRAMES_NFL_BUG = "NflBugFrame"
/** Debug counter: draws of the NFL bug's one drawing node (the instrumented `NflFramesTest` arms on its bursts). */
const val DRAWS_NFL_BUG = "NflBugDraw"

/** (NFL §5.1, §5.6, §5.7) Samples the NFL takeover into a preallocated frame (draw path; no allocation). */
internal fun interface NflSampler {
    /** [second] non-null = a §5.7 pair; [still] = the Reduce-animations version (§5.6). */
    fun sample(first: NflScoreKind, second: NflScoreKind?, tMs: Float, still: Boolean, out: NflScoreFrame): NflScoreFrame

    companion object {
        val Real = NflSampler { first, second, t, still, out ->
            when {
                still -> NflScoreTimeline.sampleStill(first, second, t, out)
                second != null -> NflScoreTimeline.samplePair(first, second, t, out)
                else -> NflScoreTimeline.sample(first, t, out)
            }
        }
    }
}

/**
 * (NFL §2 S7, §4.6) The NFL bug's seams, as [GoalHooks] for the NHL: the colour table ([NflTeamColours.of], never the
 * NHL's), the timeline, the placement checks, the bakes and the test contexts. Defaults are the real objects.
 *
 * (WP-4) The trailing seams stand in for the WP-3 painters in the wiring tests, as `GoalHooks` does for the NHL: the
 * takeover's [begin], the tab track's [ppSampler], the tab's [bakeLabels] and [tabBegin], the body's [frame], and the
 * [probe].
 */
internal class NflHooks(
    val colours: (String?) -> TeamColours = NflTeamColours::of,
    val sampler: NflSampler = NflSampler.Real,
    val glowAllowed: (PlacementInput, OverlayGeometry, DesignBox, Float) -> Boolean = ScoreBugPlacement::glowAllowed,
    val tabAllowed: (PlacementInput, OverlayGeometry, DesignBox) -> Boolean = ScoreBugPlacement::tabAllowed,
    /** The score painter's glow bake (never called in low-end mode), off the main thread. */
    val bake: (NflScorePainter) -> GlowMasks? = { it.bakeGlow() },
    /** The tab's per-size bake; `withShadow` is false in low-end mode. */
    val bakeSize: (NflTabPainter, Boolean) -> Unit = { p, withShadow -> p.bakeSize(withShadow) },
    /** The followed game's logo loader; null = the shell's image loader. */
    val logoFetch: LogoFetch? = null,
    /** Added to the takeover effect's coroutine context (tests put a `MotionDurationScale` here); empty in production. */
    val context: CoroutineContext = EmptyCoroutineContext,
    /** Where the painters are built and the masks baked: off the main thread in production. */
    val background: CoroutineContext = Dispatchers.Default,
    /** (WP-4) One takeover segment's `begin` (a §5.7 pair begins twice: at 0 and at `NFL_PAIR_SECOND_START_MS`). */
    val begin: (NflScorePainter, Side, TeamColours, Bitmap?, String, NflScore) -> Unit =
        { p, side, c, badge, abbrev, score -> p.begin(side, c, badge, abbrev, score) },
    /** (WP-4) The tab's track: PP's TAB track (§4.3); the NFL ignores its shine and label pulse. */
    val ppSampler: PpSampler = PpSampler.Real,
    /** (WP-4) The tab's label masks for the followed teams, off the main thread; published on the main thread. */
    val bakeLabels: (NflTabPainter, String, String, Resources) -> NflTabPainter.Labels? =
        { p, away, home, res -> p.buildLabels(away, home, res) },
    /** (WP-4) The tab's gradient for a side (`TeamColours.tab`, S1). */
    val tabBegin: (NflTabPainter, Side, TeamColours) -> Unit = { p, side, c -> p.begin(side, c) },
    /** (WP-4) The body's resolved words, once per shown view. */
    val frame: (NflBugPainter, ScoreBugView, Resources) -> NflBugFrame = { p, v, res -> p.frame(v, res) },
    /** (WP-4) What a test can observe; null in production. */
    val probe: NflProbe? = null,
) {
    companion object { val Default = NflHooks() }
}

/**
 * (WP-4) Every argument the NFL bug's one draw hands [NflBugPainter.drawFrame], filled into one preallocated holder per
 * draw (no allocation). The probe reads it without drawing, so the wiring tests run before the WP-3 painters exist.
 */
internal class NflDrawArgs {
    var body: NflBugFrame? = null
    var score: NflScorePainter? = null
    var g: NflScoreFrame? = null
    var glow: GlowMasks? = null
    var glowOn = false
    var tab: NflTabPainter? = null
    var p: PpFrame? = null
    var content: NflTabContent? = null
    var up = false
    var redZone = 0f
    var pulse = 0f
    var popDigits: String? = null
    /** The takeover driver this draw read (0 with no takeover). */
    var t = 0f
}

/** (WP-4) What a test can observe of the NFL bug: the takeover, the tab runner and what a draw would pass. */
internal class NflProbe {
    var takeoverOn: State<Boolean>? = null
    var driver: Animatable<Float, *>? = null
    var tabDriver: Animatable<Float, *>? = null
    var rzDriver: Animatable<Float, *>? = null
    /** One `begin` of the score painter: [segment] 0 for a single score or a pair's first, 1 for a pair's second. */
    class Begin(val side: Side, val abbrev: String, val score: NflScore, val segment: Int, val still: Boolean)
    val begins = mutableListOf<Begin>()
    /** One tab transition: the content it started with. */
    class Rec(val kind: PpTransition, val to: Boolean, val side: Side, val lead: TabLead, val tail: TabTail?, val score: NflScoreKind?)
    val transitions = mutableListOf<Rec>()
    /** The RED ZONE layer's kicks (§4.5): the target, and whether it was a cut. */
    class Red(val to: Float, val cut: Boolean)
    val reds = mutableListOf<Red>()
    /** Each pin release: the takeover driver then, and whether it was a snap (a cancel). */
    class Release(val driverMs: Float, val snap: Boolean)
    val releases = mutableListOf<Release>()
    var pinned: (() -> Boolean)? = null
    /** The tab's own content (what a close slides out, or an open brought in). */
    var tabContent: (() -> NflTabContent)? = null
    /** Fills and returns what the next draw would pass `drawFrame` (shared holder: copy what you keep). */
    var args: (() -> NflDrawArgs)? = null
    /** The bug's whole draw, in the pill's own coordinates (needs the WP-3 painters). */
    var draw: ((android.graphics.Canvas) -> Unit)? = null
    /** Called right after the composition's kick ([NflTabRun.step] in its `SideEffect`). */
    var afterStep: (() -> Unit)? = null
    var labelBakes = 0
    val tabBegins = mutableListOf<Side>()
}

/** The takeover trigger (GA §5.2, NFL §5.4, §5.7): a plain holder, written in composition and in the effect. */
private class NflRun {
    var seen = false
    var gameId: GameId? = null
    var lastSeq = -1
    var size: ScoreBugSize? = null
    /** Bumped to start a takeover and to cancel one; the effect keys on it. [active]: the last bump was a start. */
    var token = 0
    var active = false
    // Captured at start; never re-read.
    var side: Side = Side.AWAY
    var abbrev = ""
    var first: NflScore? = null
    /** The pair's second score (§5.7), or null for one score. */
    var second: NflScore? = null
    var kindA = NflScoreKind.TOUCHDOWN
    var kindB: NflScoreKind? = null
    /** The scorer's digits of the pair's first tally (the body override until B's lead covers, §5.7). */
    var popA: String? = null
    var still = false
    var glowOn = false
    var painter: NflScorePainter? = null

    val pair: Boolean get() = second != null
    val totalMs: Float get() = if (pair) K.NFL_DOUBLE_MARK_MS.toFloat() else (K.NFL_TAKEOVER_MS + K.NFL_POP_MS).toFloat()
}

/** The NFL painters and masks of one size, built once a game is followed, off the main thread; plain fields. */
private class NflSetup {
    var score: NflScorePainter? = null
}

/**
 * The NFL score bug (docs/design/m6-nfl.md §4; WP-4): its own copy of the shell (the fade, the corner slide,
 * `FollowLogos`, the place hint, the live region), [NflRun] (the takeover and the §5.7 pair) and [NflTabRun] (the
 * possession tab), drawing through [NflBugPainter.drawFrame]. Reached only through `ScoreBug(...)`'s league branch.
 *
 * - **No low-end mode** (owner ruling 20): `ScoreBug`'s `lowEnd` is not passed here; the full animation runs on every box
 *   and the still version only under system "Reduce animations" (`MotionDurationScale == 0`, read in each effect).
 * - **The pill's size** is `ScoreBugPlacement.width(size, NFL)` × `height(size)`, the same function the placer uses, so
 *   the drawn pill always matches the placed box.
 * - **One drawing node**: the composition writes plain fields; the takeover driver, the tab's driver and the RED ZONE
 *   driver are read only in draw. A new snapshot recomposes once; a takeover or a pair recomposes nothing.
 */
@Composable
internal fun NflBug(
    source: ScoreBugSource,
    placement: PlacementInput,
    geometry: OverlayGeometry,
    modifier: Modifier,
    place: (PlacementInput, OverlayGeometry) -> DesignBox?,
    hooks: NflHooks,
) {
    if (GuideDebugCounters.ENABLED) SideEffect { GuideDebugCounters.bump(COMPOSITIONS_NFL_BUG) }
    val context = LocalContext.current
    val res = context.resources
    val d = LocalDensity.current.density // device px per design px (BoxDesignScale)

    val bug by source.bug.collectAsState()
    val sample = remember(res) { nflSampleView(res) }
    val view: ScoreBugView? = bug ?: if (placement.placing) sample else null
    val box = if (view != null) place(placement, geometry) else null
    val large = placement.size == ScoreBugSize.LARGE

    val holder = remember { NflHolder() }
    if (box != null) { holder.box = box; holder.large = large }
    if (view != null) holder.lastView = view
    bug?.let { holder.lastFollowed = it }

    // ---- visibility: slide between boxes, fade in and out (ruling 20: always animated)
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
    val k = if (shownLarge) K.SCORE_LARGE_SCALE else 1f
    val s = d * k
    val w = ScoreBugPlacement.width(shownSize, League.NFL)
    val h = ScoreBugPlacement.height(shownSize)
    val painter = remember(s, shownLarge, w, h) {
        NflBugPainter(
            s, sansTypeface(context, R.font.instrument_sans_semibold), sansTypeface(context, R.font.instrument_sans_bold),
            pillW = w * d, pillH = h * d,
        )
    }
    val glass = remember(painter) { NflGlassCache(painter) }
    val loader = LocalShellImageLoader.current
    val scope = rememberCoroutineScope()
    val logos = remember(loader) { FollowLogos(hooks.logoFetch ?: loader?.let { coilLogoFetch(it, context) }, scope) }
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

    // ---- the takeover (NFL §5.4). Bookkeeping runs above the visibility return, so a score while hidden or placing
    // updates lastSeq and is never replayed on reappearance; takeoverOn is read only in draw.
    val run = remember { NflRun() }
    val setup = remember { NflSetup() }
    val takeoverOn = remember { mutableStateOf(false) }
    val driver = remember { Animatable(0f) }
    val scoreFrame = remember { NflScoreFrame() }
    val glowMasks = remember { mutableStateOf<GlowMasks?>(null) }
    /** The body drawn while a takeover is pending or its lead is still wiping in: the frame shown before the score view. */
    val held = remember { mutableStateOf<NflBugFrame?>(null) }
    val tr = remember { NflTabRun(scope, hooks) }
    val liveTexts = remember { NflLiveText() }
    val awayAbbrev = bug?.away?.abbrev ?: ""
    val homeAbbrev = bug?.home?.abbrev ?: ""
    hooks.probe?.let {
        it.takeoverOn = takeoverOn; it.driver = driver
        it.tabDriver = tr.driver; it.rzDriver = tr.rz
        it.pinned = { tr.pinned }
        it.tabContent = { tr.content }
    }

    /** Bakes the tab's label masks for the current painter and teams unless that is done or under way. */
    suspend fun bakeLabels() {
        val p = tr.painter ?: return
        val a = tr.away
        val hm = tr.home
        if (a.isEmpty()) return
        val key = NflLabelKey(p, a, hm)
        if (tr.labelKey == key || tr.bakingKey == key) return
        tr.bakingKey = key
        try {
            val labels = withContext(hooks.background) { hooks.bakeLabels(p, a, hm, res) }
            hooks.probe?.let { it.labelBakes++ }
            // Published here, on the main thread, and only while the painter and the teams are still this bake's.
            if (tr.painter === p && tr.away == a && tr.home == hm) {
                if (labels != null) p.publishLabels(labels)
                tr.labelKey = key; tr.readyChanged()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) { // no labels: no tab
        } finally {
            if (tr.bakingKey == key) tr.bakingKey = null
        }
    }
    val margin = if (shownLarge) K.GOAL_LAYER_MARGIN_LARGE else K.GOAL_LAYER_MARGIN
    // Nothing of the takeover or the tab is built until a game is followed (the place-mode sample never needs it). Then the
    // score painter, its glow and the tab painter are built once per size, off the main thread. A failure means no
    // takeover (or no glow, or no tab), never a crash.
    val hasFollow = followed != null
    LaunchedEffect(s, shownLarge, hasFollow) {
        setup.score = null
        glowMasks.value = null
        tr.painter = null; tr.labelKey = null; tr.bakingKey = null
        tr.readyChanged()
        if (!hasFollow) return@LaunchedEffect
        val pillW = w * d
        val pillH = h * d
        val built = try {
            withContext(hooks.background) {
                val p = NflScorePainter(
                    s, pillW, pillH,
                    sansTypeface(context, R.font.barlow_condensed_black_italic), sansTypeface(context, R.font.instrument_sans_bold),
                    large = shownLarge,
                )
                p to (try { hooks.bake(p) } catch (e: CancellationException) { throw e } catch (_: Throwable) { null })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        setup.score = built?.first
        glowMasks.value = built?.second
        val tp = try {
            withContext(hooks.background) {
                NflTabPainter(
                    s, d, pillW, pillH,
                    sansTypeface(context, R.font.instrument_sans_bold), sansTypeface(context, R.font.instrument_sans_semibold),
                ).also { hooks.bakeSize(it, true) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        tr.painter = tp; tr.labelKey = null; tr.bakingKey = null
        tr.readyChanged()
        if (tp != null) bakeLabels()
    }
    LaunchedEffect(awayAbbrev, homeAbbrev) { bakeLabels() }

    // ---- the tab's inputs (§3.4, §4.3): the view's tab while shown, allowed and not placing
    val real = bug
    tr.away = awayAbbrev; tr.home = homeAbbrev
    val target: NflTab? = if (box != null && !placement.placing) real?.nfl?.tab else null
    val tabAllowedNow = box != null && !placement.placing && (target != null || tr.wantsOpen) && hooks.tabAllowed(placement, geometry, box)

    fun releasePin(snap: Boolean) {
        if (!tr.pinned) return
        hooks.probe?.releases?.add(NflProbe.Release(Snapshot.withoutReadObservation { driver.value }, snap))
        tr.release(snap)
    }
    fun cancelTakeover(releaseThePin: Boolean = true) {
        if (run.active) { run.active = false; run.token++ }
        takeoverOn.value = false // a write, never a read: composition must not subscribe to it
        held.value = null
        if (releaseThePin) releasePin(snap = true)
    }
    if (real == null) {
        cancelTakeover()
        tr.reset()
        run.seen = false // the next snapshot is a "first snapshot" again: nothing replays
    } else {
        if (!run.seen || real.gameId != run.gameId) {
            cancelTakeover()
            tr.reset()
            run.seen = true; run.gameId = real.gameId; run.lastSeq = real.goalSeq; run.size = placement.size
        }
        // Place mode parks the tab at once. Hidden: it is frozen where it is and fades with the bug, then parks once the
        // fade is done. The reappearance snaps (PP §3.1).
        if (placement.placing) {
            tr.park(); tr.hidden = true
        } else if (box == null) {
            tr.hidden = true
            if (fadeDone) tr.park()
        } else if (tr.hidden) {
            tr.hidden = false; tr.snap()
        }
        if (!tr.freshSeen && !waiting(real)) { tr.freshSeen = true; tr.snap() } // the first non-waiting view of a follow
        if (box == null || placement.placing || placement.size != run.size) {
            cancelTakeover()
            run.size = placement.size
        }
        if (real.goalSeq != run.lastSeq) {
            run.lastSeq = real.goalSeq
            // A new score mid-pin (or mid-pair) restarts the takeover; the pin continues and holds what is drawn now.
            val drawnNow = if (tr.pinned) Snapshot.withoutReadObservation { tr.drawContent(takeoverOn.value, run.still, driver.value, run.pair) } else null
            cancelTakeover(releaseThePin = false)
            val side = real.goalSide
            val scores = real.nfl?.scores.orEmpty()
            if (side != null && scores.isNotEmpty() && box != null && !placement.placing) {
                val line = if (side == Side.AWAY) real.away else real.home
                val a = scores[0]
                val b = scores.getOrNull(1)
                run.side = side; run.abbrev = line.abbrev
                run.first = a; run.second = b
                run.kindA = a.kind; run.kindB = b?.kind
                run.popA = if (b != null) (if (side == Side.AWAY) a.away else a.home).toString() else null
                run.glowOn = true
                run.active = true; run.token++
                // This composition already shows the score view; until the effect starts and the lead covers the pill,
                // the draw keeps the previous body (the no-spoiler hold).
                held.value = holder.lastFrame
                tr.pin(side, a, b, tabAllowedNow, drawnNow)
            } else {
                releasePin(snap = true)
            }
        }
        // The glow follows the layout while a takeover is live; latched off for the rest of the run (GA N4).
        if (run.active && box != null && run.glowOn) run.glowOn = hooks.glowAllowed(placement, geometry, box, margin)
    }
    // Same-side content changes are cuts, written in composition (they rebuild the draw lambda and redraw, never kick).
    tr.setInputs(if (target != null && tabAllowedNow) NflTabRun.keyOf(target) else 0, target)
    SideEffect {
        tr.step()
        hooks.probe?.afterStep?.invoke()
    }

    LaunchedEffect(run.token) {
        if (!run.active) return@LaunchedEffect
        val token = run.token
        val sp = setup.score
        val first = run.first
        if (sp == null || first == null) { // not built: skipped
            run.active = false; held.value = null
            releasePin(snap = false)
            return@LaunchedEffect
        }
        val side = run.side
        val second = run.second
        val image = (if (side == Side.AWAY) logos.away else logos.home).image?.asAndroidBitmap()
        val colours = hooks.colours(run.abbrev)
        withContext(hooks.context) {
            val still = motionOff() // ruling 20: the exact scale Compose applies to animateTo
            fun beginSegment(score: NflScore, segment: Int) {
                hooks.begin(sp, side, colours, image, run.abbrev, score)
                hooks.probe?.begins?.add(NflProbe.Begin(side, run.abbrev, score, segment, still))
            }
            beginSegment(first, 0)
            tr.beginSide(side) // the scorer's tab colours, for the pinned label from 216
            run.painter = sp
            run.still = still
            driver.snapTo(0f)
            takeoverOn.value = true
            try {
                if (still) {
                    // §5.6: frame A covers the pill from its first draw; the delays are not scaled.
                    delay(K.NFL_TAKEOVER_MS)
                    driver.snapTo(K.NFL_TAKEOVER_MS.toFloat()) // frame B (B1)
                    if (second == null) {
                        if (run.token == token) releasePin(snap = false)
                        delay(K.NFL_POP_MS)
                    } else {
                        delay(K.NFL_PAIR_SECOND_START_MS - K.NFL_TAKEOVER_MS)
                        beginSegment(second, 1)
                        driver.snapTo(K.NFL_PAIR_SECOND_START_MS.toFloat()) // frame A2
                        delay(K.NFL_TAKEOVER_MS)
                        driver.snapTo((K.NFL_PAIR_SECOND_START_MS + K.NFL_TAKEOVER_MS).toFloat()) // frame B2
                        if (run.token == token) releasePin(snap = false)
                        delay(K.NFL_POP_MS)
                    }
                } else {
                    // The release (and the pair's second begin) are children watching the driver, so the takeover keeps
                    // its single tween and a MotionDurationScale other than 1 still releases at the driver's 3 600 / 8 100.
                    val releaseAt = if (second == null) K.NFL_TAKEOVER_MS.toFloat() else (K.NFL_PAIR_SECOND_START_MS + K.NFL_TAKEOVER_MS).toFloat()
                    launch {
                        snapshotFlow { driver.value >= releaseAt }.first { it }
                        if (run.token == token) releasePin(snap = false)
                    }
                    if (second != null) {
                        launch {
                            // B's lead starts from 0 at 4 500 and the panel is hidden until 108 ms into it, so a frame
                            // drawn before this begin lands shows nothing of B's words (and the scorer's colours are A's).
                            snapshotFlow { driver.value >= K.NFL_PAIR_SECOND_START_MS.toFloat() }.first { it }
                            if (run.token == token) beginSegment(second, 1)
                        }
                    }
                    driver.animateTo(run.totalMs, tween(run.totalMs.toInt(), easing = LinearEasing))
                }
            } finally {
                // Only the run that is still current clears: a cancelled run's finally runs before its successor's body.
                if (run.token == token) {
                    takeoverOn.value = false; run.active = false; held.value = null
                    releasePin(snap = false)
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
    val frame = remember(shownView, painter) {
        if (GuideDebugCounters.ENABLED) GuideDebugCounters.bump(FRAMES_NFL_BUG)
        hooks.frame(painter, shownView, res)
    }
    holder.lastFrame = frame
    // The sample bug never borrows the followed game's logos.
    val shownLogos = if (shownView === sample) null else logos
    val placing = placement.placing
    val rightSide = last.l > 960f
    // A plain string per shown view; semantics read nothing that changes between snapshots.
    val desc = remember(shownView, res) { NflDescribe(res, shownView).text() }
    // §8: the live region's text changes twice per score (one announcement for a §5.7 pair), never per frame.
    val liveText = if (shownView === sample) "" else liveTexts.next(shownView, res)
    val up = last.t + h / 2f > 540f // the tab opens above a bottom corner's pill, below a top one's (ruling 16)

    val args = remember { NflDrawArgs() }
    /** Fills [args] with this draw's inputs: the drivers are read here (in draw) and nowhere in composition. */
    fun fillArgs(): NflDrawArgs {
        val a = args
        val on = takeoverOn.value
        var t = 0f
        a.score = null; a.g = null
        if (on) {
            t = driver.value
            val f = hooks.sampler.sample(run.kindA, run.kindB, t, run.still, scoreFrame)
            if (!f.done) { a.score = run.painter; a.g = f }
        }
        a.t = t
        // Pending, or the lead still wiping in: the previous body. The still path's frame A covers from its first draw.
        val hold = held.value
        a.body = if (hold != null && (!on || (!run.still && t < GoalTimeline.LEAD_COVERED_MS))) hold else frame
        a.glow = glowMasks.value
        a.glowOn = run.glowOn
        a.up = up
        // §5.7: the pair's scorer digits come from tally A until B's lead covers the pill.
        val bCovered = if (run.still) K.NFL_PAIR_SECOND_START_MS.toFloat() else K.NFL_PAIR_SECOND_START_MS + GoalTimeline.LEAD_COVERED_MS
        a.popDigits = if (on && run.pair && t < bCovered) run.popA else null
        val sampled = tr.sampleForDraw()
        if (sampled != null) {
            a.tab = tr.painter; a.p = sampled
            val c = tr.drawContent(on, run.still, t, run.pair)
            a.content = c
            a.redZone = tr.drawRed(on, run.still, t)
            a.pulse = if (c.score != null) (a.g?.tabPulse ?: 0f) else 0f
        } else {
            a.tab = null; a.p = null; a.content = null; a.redZone = 0f; a.pulse = 0f
        }
        return a
    }
    fun drawBug(c: android.graphics.Canvas) {
        if (GuideDebugCounters.ENABLED) GuideDebugCounters.bump(DRAWS_NFL_BUG)
        val a = fillArgs()
        painter.drawFrame(
            c, glass.bitmap, a.body ?: frame, shownLogos, logoWaited.value, placing,
            score = a.score, g = a.g, glow = a.glow, glowOn = a.glowOn,
            tab = a.tab, p = a.p, content = a.content, up = a.up, redZone = a.redZone, pulse = a.pulse, popDigits = a.popDigits,
        )
    }
    hooks.probe?.let { it.args = ::fillArgs; it.draw = ::drawBug }
    val m = margin
    Box(
        modifier
            .nflPlaceAt(holder, pos, d, m)
            // A leaf layer (never an ancestor of the SurfaceView): the fade, clipped to the pill + M (GA §1).
            .graphicsLayer { alpha = fade.value; clip = true }
            .size((w + 2 * m).dp, (h + 2 * m).dp),
    ) {
        Spacer(
            Modifier
                .offset(m.dp, m.dp)
                .size(w.dp, h.dp)
                .testTag(SCORE_BUG_TAG)
                .semantics { contentDescription = desc }
                .drawBehind { drawIntoCanvas { drawBug(it.nativeCanvas) } },
        )
        // The persistent live region (GA S9): pill-sized, always composed while the bug is.
        Spacer(
            Modifier
                .offset(m.dp, m.dp)
                .size(w.dp, h.dp)
                .testTag(SCORE_BUG_LIVE_TAG)
                .semantics { contentDescription = liveText; liveRegion = LiveRegionMode.Polite },
        )
    }
    // A sibling of the clipped layer, not inside it (GA §1): same position source, same look.
    if (placing) NflPlaceHint(holder, pos, fade, rightSide, w, h)
}

/** NFL two-line states put "Delayed" / "No update" on the top line; one-line breaks have only a top. */
private fun waiting(v: ScoreBugView): Boolean =
    v.top is CentreText.Delayed || v.top is CentreText.NoUpdate || v.bottom is CentreText.Delayed || v.bottom is CentreText.NoUpdate

/** The tab's label bake key: the painter (one per size) and the followed teams' abbreviations. */
private data class NflLabelKey(val painter: NflTabPainter, val away: String, val home: String)

/**
 * (NFL §4.3, §4.5, §5.4; WP-4) The possession tab's runner, mirroring the NHL `PpRun`'s tab track:
 * - one long-lived runner with one `Animatable<Float>` of elapsed ms ([driver]) read only in draw, and one running
 *   transition (PP's TAB track: OPEN, CLOSE, STILL; the NFL never pulses the label);
 * - **the S2 key** `(tabOn, tab.side)` ([keyOf]: 0 = closed, else 1 + side), evaluated only while not pinned. A same-side
 *   change of lead or tail is a cut written in composition; a side change is CLOSE with the old words, then OPEN;
 * - the S1 interrupt order (the driver snaps before `from` and the kind are assigned);
 * - the PP §3.1 snaps: the first non-waiting view, reappearing, a size change, leaving place mode, labels ready;
 * - **[rz]**, the RED ZONE layer's alpha 0..1 (§4.5): a 250 ms `ease` cross-fade that never kicks the slide; a cut with
 *   animations off, on an open and on a release;
 * - **the pin** (§5.4, §5.7): [pin] at the takeover's start; [drawContent] picks the held words (before 216), the score
 *   label, the pair's in-between "X BALL | EXTRA POINT" and the second label; [release] cuts to the current content,
 *   whatever its side, or CLOSEs the label; a cancel snaps.
 *
 * [NflTabContent.tail] convention for the painter: while the RED ZONE layer shows, the tail keeps the last field tail (so
 * the cross-fade can fade it out under "RED ZONE"); it is [TabTail.RedZone] only when the tab opened straight into the red
 * zone (then [rz] is 1 from the open). The painter draws the tail at `1 − redZone` and "RED ZONE" at `redZone`.
 */
private class NflTabRun(private val scope: CoroutineScope, private val hooks: NflHooks) {
    val driver = Animatable(0f)
    val rz = Animatable(0f)
    private val from = PpFrame()
    private val next = PpFrame()
    private var kind = PpTransition.STILL
    private var to = false
    /** Where the tab is heading (or rests): the key, 0 = closed. */
    private var want = 0
    private var job: Job? = null
    private var rzJob: Job? = null
    /** What the tab says, captured when a transition starts; same-side cuts write it in composition. */
    val content = NflTabContent()
    private val out = PpFrame()

    var painter: NflTabPainter? = null
    var labelKey: NflLabelKey? = null
    var bakingKey: NflLabelKey? = null
    var away = ""
    var home = ""
    private val ready: Boolean
        get() {
            val p = painter
            val k = labelKey
            return p != null && k != null && k.painter === p && k.away == away && k.home == home
        }

    // ---- bookkeeping, written in composition
    var freshSeen = false
    var hidden = true
    private var snapTab = false
    private var lastKey = -1
    private var inWant = 0
    private var inTab: NflTab? = null
    /** The RED ZONE target the layer last moved to (the cross-fade kicks only on its change). */
    private var rzLast = false
    /** A release's cut, in draw until its snap lands (no red frame after a release to PAT). −1 = none. */
    private var rzOverride = -1f

    /** The tab is open or opening (what a pin reads as "the tab as drawn on the previous frame"). */
    val wantsOpen: Boolean get() = want != 0
    private val active: Boolean get() = kind != PpTransition.STILL || to

    // ---- the pin (§5.4)
    var pinned = false
        private set
    private var pinTabOn = false
    private var pinEdge = false
    /** What the tab drew when the pin started (or, on a restart, what it drew then): held until the lead covers. */
    private val pinContent = NflTabContent()
    private val labelA = NflTabContent()
    private val interim = NflTabContent()
    private val labelB = NflTabContent()

    fun snap() { snapTab = true }

    /** Hides the tab at once: cancels what runs and parks at the closed rest (hide, place mode, a new follow). */
    fun park() {
        job?.cancel(); job = null
        kind = PpTransition.STILL; to = false; want = 0
        lastKey = -1
        snapTab = false
    }

    fun reset() {
        park()
        pinned = false; pinEdge = false; pinTabOn = false
        freshSeen = false
    }

    fun readyChanged() {
        snapTab = true
        step()
    }

    fun setInputs(key: Int, tab: NflTab?) {
        inWant = key
        inTab = tab
        // A same-side change of lead or tail (a yard line, BALL ↔ KICKOFF, EXTRA POINT, into or out of RED ZONE) is a cut.
        if (!pinned && tab != null && want != 0 && want == key && content.side == tab.side && content.score == null) cut(tab)
    }

    private fun effWant() = if (ready) inWant else 0

    /** `SideEffect`: starts a transition only when the key changed (or a snap was asked); the cross-fade on its own. */
    fun step() {
        if (hidden) return
        val w = effWant()
        if (pinned) {
            // Frozen (§5.4). A pin that starts with the tab open but not allowed closes it once; nothing else moves.
            if (pinEdge) {
                pinEdge = false
                if (!pinTabOn && want != 0) evaluate(0, snap = false)
            }
            lastKey = w
            return
        }
        if (snapTab || w != lastKey) {
            // Closing because the labels are not ready (a size change's bake) is a snap too.
            val snap = snapTab || (w == 0 && !ready && want != 0)
            snapTab = false
            lastKey = w
            evaluate(w, snap)
        }
        stepRed()
    }

    /** §4.5: the RED ZONE cross-fade, only while the tab is open (or opening) on the target's side. */
    private fun stepRed() {
        val t = inTab ?: return
        if (want == 0 || content.side != t.side || content.score != null) return
        val red = t.tail is TabTail.RedZone
        if (red == rzLast) return
        rzLast = red
        val target = if (red) 1f else 0f
        rzJob?.cancel()
        rzJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(hooks.context) {
                val cut = motionOff()
                hooks.probe?.reds?.add(NflProbe.Red(target, cut))
                if (cut) rz.snapTo(target) else rz.animateTo(target, tween(K.NFL_RED_ZONE_FADE_MS.toInt(), easing = EASE))
            }
        }
    }

    // ---- the pin

    /**
     * A takeover starts (§5.4, §5.7). A fresh pin captures what the tab is drawing and whether it was open or opening and
     * allowed ([allowed]); a restart mid-pin keeps the pin and holds [drawnNow], what it drew at the restart. The labels
     * are prepared here (never in draw).
     */
    fun pin(side: Side, a: NflScore, b: NflScore?, allowed: Boolean, drawnNow: NflTabContent?) {
        if (!pinned) {
            pinned = true
            pinContent.copyFrom(content)
            pinTabOn = want != 0 && kind != PpTransition.CLOSE && allowed
            pinEdge = true
        } else if (drawnNow != null) {
            pinContent.copyFrom(drawnNow)
        }
        label(labelA, side, a)
        interim.side = side; interim.lead = TabLead.BALL; interim.tail = TabTail.ExtraPoint; interim.score = null; interim.points = 0
        if (b != null) label(labelB, side, b)
        pinPair = b != null
    }

    private fun label(c: NflTabContent, side: Side, s: NflScore) {
        c.side = side; c.lead = TabLead.BALL; c.tail = null; c.score = s.kind; c.points = s.points
    }

    /** The scorer's colours, from the takeover's effect (never in composition). */
    fun beginSide(side: Side) = begin(side)

    /**
     * Ends the pin. [snap]: a cancel (hide, place mode, size, a new game); the next composition's kick snaps the tab.
     * Otherwise (3 600, or 8 100 for a pair) the key is evaluated again: with the tab on, a **cut** to the current content
     * in the current side's colours (whatever the side), with no slide; with it off, a CLOSE sliding the label out.
     */
    fun release(snap: Boolean) {
        if (!pinned) return
        pinned = false; pinEdge = false
        if (snap) { snapTab = true; lastKey = -1; return }
        val w = effWant()
        lastKey = w
        val t = inTab
        if (!pinTabOn) { evaluate(w, snap = false); return }
        if (w != 0 && t != null) {
            want = w
            setContent(t)
            begin(t.side)
            val red = t.tail is TabTail.RedZone
            rzLast = red
            cutRed(if (red) 1f else 0f)
        } else {
            // What the tab drew at the end of the takeover is what the close slides out (a pair releases after B's label).
            content.copyFrom(if (pinPair) labelB else labelA)
            evaluate(0, snap = false)
        }
    }

    /** The pin is a §5.7 pair's (it releases at 8 100, with B's label drawn). */
    private var pinPair = false

    /**
     * The words the tab draws now: the tab's own unless pinned; while pinned, the held copy until the lead covers the pill
     * (216; the still path covers at once), then the label, the pair's in-between words from 3 600 and B's label from
     * B's lead cover (4 716; still: 4 500).
     */
    fun drawContent(on: Boolean, still: Boolean, t: Float, pair: Boolean): NflTabContent {
        if (!pinned || !pinTabOn) return content
        if (!on) return pinContent
        if (!still && t < GoalTimeline.LEAD_COVERED_MS) return pinContent
        if (!pair) return labelA
        val bAt = if (still) K.NFL_PAIR_SECOND_START_MS.toFloat() else K.NFL_PAIR_SECOND_START_MS + GoalTimeline.LEAD_COVERED_MS
        return when {
            t < K.NFL_TAKEOVER_MS -> labelA
            t < bAt -> interim
            else -> labelB
        }
    }

    /** The RED ZONE layer's alpha in draw: never under a pinned label (§4.5); a release's cut until its snap lands. */
    fun drawRed(on: Boolean, still: Boolean, t: Float): Float {
        if (pinned && pinTabOn && on && (still || t >= GoalTimeline.LEAD_COVERED_MS)) return 0f
        val o = rzOverride
        return if (o >= 0f) o else rz.value
    }

    // ---- the transitions

    private fun cut(t: NflTab) {
        content.lead = t.lead
        if (t.tail !is TabTail.RedZone) content.tail = t.tail
        else if (content.tail == null || content.tail is TabTail.ExtraPoint) content.tail = TabTail.RedZone
    }

    private fun setContent(t: NflTab) {
        content.side = t.side; content.lead = t.lead; content.tail = t.tail; content.score = null; content.points = 0
    }

    private fun begin(side: Side) {
        val p = painter ?: return
        hooks.probe?.tabBegins?.add(side)
        hooks.tabBegin(p, side, hooks.colours(if (side == Side.AWAY) away else home))
    }

    private fun cutRed(v: Float) {
        rzJob?.cancel()
        rzOverride = v
        rzJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                rz.snapTo(v)
            } finally {
                if (rzOverride == v) rzOverride = -1f
            }
        }
    }

    private suspend fun snapRed(t: NflTab?) {
        val red = t?.tail is TabTail.RedZone
        rzLast = red
        rzJob?.cancel()
        rzOverride = -1f
        rz.snapTo(if (red) 1f else 0f)
    }

    private fun atRest(w: Int) = kind == PpTransition.STILL && to == (w != 0)

    private fun evaluate(w: Int, snap: Boolean) {
        val old = want
        if (w == old && !(snap && active && !atRest(w))) return
        want = w
        job?.cancel()
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(hooks.context) { seq(old, w, snap) }
        }
    }

    /** Opens on the latest target ([inTab]): a side change's OPEN after its CLOSE shows what is current then. */
    private suspend fun openWith(kindOf: PpTransition) {
        val t = inTab
        if (t != null) { setContent(t); begin(t.side) }
        snapRed(t)
        play(kindOf, true)
    }

    private suspend fun seq(old: Int, w: Int, snap: Boolean) {
        if (snap || motionOff()) { // the still version (§5.6): straight to rest
            if (w == 0) play(PpTransition.STILL, false) else openWith(PpTransition.STILL)
            return
        }
        when {
            old == 0 -> openWith(PpTransition.OPEN)
            w == 0 -> play(PpTransition.CLOSE, false) // keeps the content it opened with
            else -> { // a side change: X's words slide back, then Y's come out (PP B2)
                play(PpTransition.CLOSE, false)
                openWith(PpTransition.OPEN)
            }
        }
    }

    /** Starts [k] from where the interrupted transition is now (CSS semantics); the S1 order: snap, then assign. */
    private suspend fun play(k: PpTransition, toOpen: Boolean) {
        hooks.ppSampler.sample(PpTrack.TAB, kind, driver.value, from, to, next)
        driver.snapTo(0f)
        from.copyFrom(next)
        kind = k; to = toOpen
        hooks.probe?.transitions?.add(NflProbe.Rec(k, toOpen, content.side, content.lead, content.tail, content.score))
        val ms = when (k) {
            PpTransition.OPEN -> K.PP_OPEN_MS
            PpTransition.CLOSE -> K.PP_CLOSE_MS
            PpTransition.PULSE, PpTransition.STILL -> 0L
        }
        if (ms > 0) driver.animateTo(ms.toFloat(), tween(ms.toInt(), easing = LinearEasing))
    }

    /** Samples the tab track into the preallocated frame for draw; null when nothing is drawn. */
    fun sampleForDraw(): PpFrame? {
        if (painter == null || !active) return null
        hooks.ppSampler.sample(PpTrack.TAB, kind, driver.value, from, to, out)
        out.pillClosed = true
        return out
    }

    companion object {
        /** The S2 identity key: 0 = closed, else 1 + the side. */
        fun keyOf(t: NflTab): Int = 1 + t.side.ordinal

        /** NFL §4.5 `ease` (.25, .1, .25, 1). */
        val EASE = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
    }
}

/**
 * (NFL §8) The live region's text: while the score mark is set, "Kansas City Chiefs touchdown. Chiefs 13, Bills 7" (one
 * announcement for a §5.7 pair: "… touchdown and extra point. …"), else "". A stale or waiting view leaves it unchanged;
 * a new follow resets it. Possession, down and RED ZONE are never announced. Idempotent per view instance.
 */
private class NflLiveText {
    private var game: GameId? = null
    private var lastView: ScoreBugView? = null
    var text = ""
        private set

    fun next(v: ScoreBugView, res: Resources): String {
        if (v === lastView) return text
        lastView = v
        if (v.gameId != game) { game = v.gameId; text = "" }
        if (waiting(v)) return text
        text = if (v.goalSide != null) nflScoreText(res, v) ?: "" else ""
        return text
    }
}

private fun shortOf(t: TeamLine) = t.shortName ?: t.name

private fun kindWord(res: Resources, k: NflScoreKind): String = res.getString(
    when (k) {
        NflScoreKind.TOUCHDOWN -> R.string.sports_nfl_td
        NflScoreKind.EXTRA_POINT -> R.string.sports_nfl_xp
        NflScoreKind.FIELD_GOAL -> R.string.sports_nfl_fg
        NflScoreKind.SAFETY -> R.string.sports_nfl_safety
        NflScoreKind.TWO_POINT -> R.string.sports_nfl_2pt
    },
).lowercase(Locale.getDefault())

/** The score announcement of a view under the mark ("%5$s %6$s. %1$s %2$d, %3$s %4$d"); null with no score kind. */
private fun nflScoreText(res: Resources, v: ScoreBugView): String? {
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

/**
 * (NFL §4.2, §8) The NFL bug's semantics: "Bengals 0, Falcons 7, 11:12 left in the 1st. Falcons ball, 1st and 10, in the
 * red zone." The scores use `TeamLine.shortName` (S5); the possession ("ball", for a BALL tab), the down ("1st and 10",
 * "4th and goal") and RED ZONE are added when present. Under the score mark it is the announcement's text alone.
 */
internal class NflDescribe(private val res: Resources, private val v: ScoreBugView) {
    fun text(): String {
        val base = state()
        if (v.goalSide != null) return base
        val n = v.nfl ?: return base
        val parts = ArrayList<String>(3)
        val tab = n.tab
        if (tab != null && tab.lead == TabLead.BALL) {
            parts += res.getString(R.string.sports_desc_nfl_ball, shortOf(if (tab.side == Side.AWAY) v.away else v.home))
        }
        val down = n.down
        if (down is DownText.Down) {
            val word = downWord(down.down)
            if (word != null) {
                parts += when {
                    down.goal -> res.getString(R.string.sports_desc_nfl_down, word, res.getString(R.string.sports_desc_nfl_goal))
                    down.distance != null -> res.getString(R.string.sports_desc_nfl_down, word, down.distance.toString())
                    else -> word
                }
            }
        }
        if (tab?.tail is TabTail.RedZone) parts += res.getString(R.string.sports_desc_nfl_red_zone)
        return if (parts.isEmpty()) base else "$base. ${parts.joinToString(", ")}."
    }

    private fun downWord(n: Int): String? = when (n) {
        1 -> res.getString(R.string.sports_down_1)
        2 -> res.getString(R.string.sports_down_2)
        3 -> res.getString(R.string.sports_down_3)
        4 -> res.getString(R.string.sports_down_4)
        else -> null
    }

    private fun state(): String {
        val a = v.away
        val h = v.home
        fun f(id: Int, vararg extra: Any) = res.getString(id, shortOf(a), a.score ?: 0, shortOf(h), h.score ?: 0, *extra)
        val top = v.top
        val bottom = v.bottom
        if (v.goalSide != null) nflScoreText(res, v)?.let { return it }
        return when {
            top is CentreText.Final -> f(R.string.sports_desc_final, SportsText.final(res, top.kind))
            top is CentreText.NoUpdate || bottom is CentreText.NoUpdate -> f(R.string.sports_desc_no_update)
            top is CentreText.Delayed || bottom is CentreText.Delayed -> f(R.string.sports_desc_delayed)
            top is CentreText.Halftime -> f(R.string.sports_desc_halftime)
            top is CentreText.EndOf -> f(R.string.sports_desc_end_of, SportsText.period(res, top.period, League.NFL))
            top is CentreText.Period && bottom is CentreText.Clock ->
                f(R.string.sports_desc_live, SportsText.clock(v.clockSecs ?: 0), SportsText.period(res, top.period, League.NFL))
            else -> res.getString(R.string.sports_quick_score, a.abbrev, a.score ?: 0, h.score ?: 0, h.abbrev)
        }
    }
}

/** The baked glass of one painter (U-S1): built on first use, then held. Always with its shadow (ruling 20). */
private class NflGlassCache(private val painter: NflBugPainter) {
    val bitmap: Bitmap by lazy(LazyThreadSafetyMode.NONE) { painter.bakeGlass(withShadow = true) }
}

/** Plain holder for what must survive recomposition without being snapshot state (writes happen in composition). */
private class NflHolder {
    var box: DesignBox? = null
    var large = false
    var positioned = false
    var drawn = false
    var lastView: ScoreBugView? = null
    /** The frame the bug last composed for drawing: what a takeover holds while it starts. */
    var lastFrame: NflBugFrame? = null
    /** The last real (not sample) view: its logo URLs key the held [FollowLogos]. */
    var lastFollowed: ScoreBugView? = null

    fun pillOffset(pos: Animatable<Offset, *>): Offset =
        if (positioned) pos.value else box?.let { Offset(it.l, it.t) } ?: Offset.Zero
}

/** Places the bug's layer so the pill (inset by [margin] design px inside it) lands on the pill's box. */
private fun Modifier.nflPlaceAt(holder: NflHolder, pos: Animatable<Offset, *>, d: Float, margin: Float): Modifier = layout { m, c ->
    val p = m.measure(c)
    layout(p.width, p.height) {
        val o = holder.pillOffset(pos)
        p.place(IntOffset(((o.x - margin) * d).roundToInt(), ((o.y - margin) * d).roundToInt()))
    }
}

/** The place-mode hint beside the bug toward the screen centre (SB §6), as the NHL's: a sibling of the clipped layer. */
@Composable
private fun NflPlaceHint(holder: NflHolder, pos: Animatable<Offset, *>, fade: Animatable<Float, *>, bugOnRight: Boolean, bugW: Float, bugH: Float) {
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
