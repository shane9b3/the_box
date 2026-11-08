package app.thebox.ui.sports

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Typeface
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.asAndroidBitmap
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
import androidx.core.content.res.ResourcesCompat
import app.thebox.core.sports.Box as DesignBox
import app.thebox.core.sports.CentreText
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GoalFrame
import app.thebox.core.sports.GoalTimeline
import app.thebox.core.sports.NhlTeamColours
import app.thebox.core.sports.OverlayGeometry
import app.thebox.core.sports.PlacementInput
import app.thebox.core.sports.PowerPlay
import app.thebox.core.sports.PowerPlayKind
import app.thebox.core.sports.PowerPlayTimeline
import app.thebox.core.sports.PpContent
import app.thebox.core.sports.PpFrame
import app.thebox.core.sports.PpLabel
import app.thebox.core.sports.PpTrack
import app.thebox.core.sports.PpTransition
import app.thebox.core.sports.ScoreBugPlacement
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.ScoreBugSource
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.TeamColours
import app.thebox.core.sports.TeamLine
import app.thebox.ui.R
import app.thebox.ui.guide.GuideDebugCounters
import app.thebox.ui.guide.screen.LocalShellImageLoader
import app.thebox.ui.full.PlayerUi
import app.thebox.ui.theme.BoxFonts
import app.thebox.ui.theme.DesignTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
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
/** The persistent pill-sized live region (goal design §5.3, S9): its description is "" except during the goal mark. */
const val SCORE_BUG_LIVE_TAG = "score_bug_live"

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
) = ScoreBugWith(source, placement, geometry, lowEnd, modifier, place, GoalHooks.Default)

/**
 * (Goal animation, WP-3) The goal takeover's seams: what [ScoreBug] calls into WP-1/WP-2 for, so tests can stand in for the
 * colour table, the timeline, the painter's `begin` / `bakeGlow` and `glowAllowed`. Defaults are the real objects.
 * [sampler] is a `fun interface` so the per-frame call boxes nothing.
 */
internal class GoalHooks(
    val colours: (String?) -> TeamColours = NhlTeamColours::of,
    val sampler: GoalSampler = GoalSampler.Real,
    val begin: (GoalPainter, Side, TeamColours, Bitmap?, String, Int, Int) -> Unit = { p, side, c, badge, abbrev, a, h -> p.begin(side, c, badge, abbrev, a, h) },
    val bake: (GoalPainter) -> GlowMasks? = { it.bakeGlow() },
    val glowAllowed: (PlacementInput, OverlayGeometry, DesignBox, Float) -> Boolean = ScoreBugPlacement::glowAllowed,
    val probe: GoalProbe? = null,
    /** (Power play, WP-3) Samples one track of the power-play timeline into a preallocated [PpFrame]. */
    val ppSampler: PpSampler = PpSampler.Real,
    /** (Power play §4.4) Whether the tab may show at the placed [DesignBox]; called only while a power play or a tab exists. */
    val tabAllowed: (PlacementInput, OverlayGeometry, DesignBox) -> Boolean = ScoreBugPlacement::tabAllowed,
    /** (Power play §3.4, §3.6) The per-size bake; `withShadow` is false in low-end mode (the shadow is never baked there). */
    val bakeSize: (PowerPlayPainter, Boolean) -> Unit = { p, withShadow -> p.bakeSize(withShadow) },
    /**
     * (Power play §3.4) The halo mask for one logo bitmap (never called in low-end mode), off the main thread; the runner
     * publishes it on the main thread (null: none).
     */
    val bakeHalo: (PowerPlayPainter, Side, Bitmap) -> Bitmap? = { p, side, logo -> p.buildHalo(side, logo) },
    /**
     * (Power play §3.4) The five label masks for the followed teams' abbreviations, off the main thread; the runner publishes
     * them on the main thread only while those are still the followed teams (fix pass N1; null: nothing to publish).
     */
    val bakeLabels: (PowerPlayPainter, String, String, Resources) -> PowerPlayPainter.Labels? = { p, away, home, res -> p.buildLabels(away, home, res) },
    /** The followed game's logo loader; null = the shell's image loader (tests pass a fake so logos and halos really load). */
    val logoFetch: LogoFetch? = null,
    /** Added to the takeover effect's coroutine context (tests put a `MotionDurationScale` here); empty in production. */
    val context: CoroutineContext = EmptyCoroutineContext,
    /**
     * Where the goal painter is built (the Barlow load, the GOAL atlas) and the glow baked: off the main thread in
     * production; tests pass [EmptyCoroutineContext] so the setup lands deterministically on the test clock.
     */
    val background: CoroutineContext = Dispatchers.Default,
) {
    companion object { val Default = GoalHooks() }
}

/** Samples the timeline into a preallocated frame (draw path; no allocation). */
internal fun interface GoalSampler {
    fun sample(tMs: Float, still: Boolean, out: GoalFrame): GoalFrame

    companion object {
        val Real = GoalSampler { t, still, out -> if (still) GoalTimeline.sampleStill(t, out) else GoalTimeline.sample(t, out) }
    }
}

/** (Power play, WP-3) Samples one track of the timeline into a preallocated frame (draw path; no allocation). */
internal fun interface PpSampler {
    fun sample(track: PpTrack, kind: PpTransition, tMs: Float, from: PpFrame, to: Boolean, out: PpFrame): PpFrame

    companion object {
        val Real = PpSampler { track, kind, t, from, to, out -> PowerPlayTimeline.sample(track, kind, t, from, to, out) }
    }
}

/**
 * What a test can observe of the takeover: the draw-only state, each `begin` decision, the painter a takeover draws with,
 * and the bug's whole draw ([draw]: what the drawing node runs, in the pill's own coordinates), so a native-graphics test
 * can render the real WP-3 → WP-2 path into a bitmap.
 */
internal class GoalProbe {
    var takeoverOn: State<Boolean>? = null
    var driver: Animatable<Float, *>? = null
    /** The painter the current (or last) takeover was begun on; null until one starts. */
    var painter: GoalPainter? = null
    var draw: ((android.graphics.Canvas) -> Unit)? = null
    class Begin(val side: Side, val abbrev: String, val away: Int, val home: Int, val still: Boolean)
    val begins = mutableListOf<Begin>()
    /** (Power play) The power-play runner's observable state. */
    val pp = PpProbe()
}

/** (Power play, WP-3) What a test can observe of the power-play runner: each transition it plays, each pin release, the drivers. */
internal class PpProbe {
    /** One transition a track started: [side] and [label] are the content it was started with (null for a pill CLOSE). */
    class Rec(val track: PpTrack, val kind: PpTransition, val to: Boolean, val side: Side?, val label: PpLabel?, val secs: Int)
    val transitions = mutableListOf<Rec>()
    /** The takeover driver's value at each pin release, and whether it was a snap (a cancel) rather than the 3 600 release. */
    class Release(val driverMs: Float, val snap: Boolean)
    val releases = mutableListOf<Release>()
    var tabDriver: Animatable<Float, *>? = null
    var pillDriver: Animatable<Float, *>? = null
    /** The tab track's content (what a close slides out, or an open brought in). What a draw shows is [lastDrawContent]. */
    var tabContent: (() -> PpContent)? = null
    var pinned: (() -> Boolean)? = null
    /** The power-play painter of the current size (null until built). */
    var painter: (() -> PowerPlayPainter?)? = null
    /** Samples both tracks at their current driver values into a fresh frame: what the next draw would hand the painter. */
    var frame: (() -> PpFrame)? = null
    /** The last `PowerPlayPainter.draw` arguments the bug's draw made (updated per draw; null until one). */
    var lastDrawContent: PpContent? = null
    var lastDrawSide: Side? = null
    var lastDrawFrame: PpFrame? = null
    /**
     * Called right after the composition's kick ([PpRun.step] in its `SideEffect`), before anything it launched is
     * dispatched: a test draws here to see the frame the same Choreographer frame would draw (S1).
     */
    var afterStep: (() -> Unit)? = null
    /** Draws that passed a power-play frame to the composite. */
    var ppDraws = 0
    /** Label bakes and halo bakes the wiring made. */
    var labelBakes = 0
    var haloBakes = 0
}

/** The state of the takeover trigger (goal design §5.2): a plain holder, written in composition and in the effect. */
private class GoalRun {
    var seen = false
    var gameId: GameId? = null
    var lastSeq = -1
    var size: ScoreBugSize? = null
    /** Bumped to start a takeover and to cancel one; the effect keys on it. [active]: the last bump was a start. */
    var token = 0
    var active = false
    // Captured at start; never re-read (B1.5).
    var side: Side = Side.AWAY
    var abbrev = ""
    var away = 0
    var home = 0
    var still = false
    /** Latched off for the rest of a run once a neighbour closes in (N4): a bar closing never snaps the glow back on. */
    var glowOn = false
    /** The painter this run was begun on (set in the effect before `takeoverOn`); draw reads it, never [GoalSetup]. */
    var painter: GoalPainter? = null
}

/** The goal painter and its glow masks, built once a game is followed (N3), off the main thread; plain fields. */
private class GoalSetup {
    var painter: GoalPainter? = null
}

@Composable
internal fun ScoreBugWith(
    source: ScoreBugSource,
    placement: PlacementInput,
    geometry: OverlayGeometry,
    lowEnd: Boolean,
    modifier: Modifier,
    place: (PlacementInput, OverlayGeometry) -> DesignBox?,
    hooks: GoalHooks,
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
    val logos = remember(loader) { FollowLogos(hooks.logoFetch ?: loader?.let { coilLogoFetch(it, context) }, scope) }
    DisposableEffect(logos) { onDispose { logos.release() } }
    val logoWaited = remember { mutableStateOf(false) }
    // ---- the power-play runner (power play §3.1): plain holders; the drivers are read only in draw
    val pr = remember { PpRun(scope, hooks) }
    /** Bumped on a game change (S3): the halo watchers re-bake from the slots' current logos after the halos are cleared. */
    val haloEpoch = remember { mutableIntStateOf(0) }
    val followed = holder.lastFollowed
    LaunchedEffect(followed?.away?.logo, followed?.home?.logo, s) {
        if (followed == null) return@LaunchedEffect
        // S3 (fix pass): new logos, so no halo of the previous ones is kept (draw also checks the bitmap's identity).
        pr.painter?.clearHalos()
        logos.start(
            followed.away.logo, followed.home.logo,
            (K.SCORE_LOGO_W * s).roundToInt().coerceAtLeast(1), (K.SCORE_LOGO_H * s).roundToInt().coerceAtLeast(1),
        )
        logoWaited.value = false
        delay(K.LOGO_WAIT_MS)
        logoWaited.value = true
    }

    // ---- the goal takeover (goal design §5.2). Bookkeeping runs above the visibility return, so a goal while hidden or
    // placing updates lastSeq and is never replayed on reappearance; takeoverOn is read only in draw.
    val run = remember { GoalRun() }
    val setup = remember { GoalSetup() }
    val takeoverOn = remember { mutableStateOf(false) }
    val driver = remember { Animatable(0f) }
    val goalFrame = remember { GoalFrame() }
    val glowMasks = remember { mutableStateOf<GlowMasks?>(null) }
    /**
     * The body drawn while a takeover is pending or its lead is still wiping in: the frame shown **before** the goal view
     * (old score, old centre text), so the new score is never seen on the plain bug ahead of the takeover (fix pass,
     * emulator finding). Written in composition at the start and in the effect; read only in draw.
     */
    val held = remember { mutableStateOf<ScoreBugFrame?>(null) }
    hooks.probe?.let { it.takeoverOn = takeoverOn; it.driver = driver }
    val liveTexts = remember { LiveText() }
    val awayAbbrev = bug?.away?.abbrev ?: ""
    val homeAbbrev = bug?.home?.abbrev ?: ""
    /** Bakes the five label masks for the current painter and teams unless that is done or under way (S8). */
    suspend fun bakeLabels() {
        val p = pr.painter ?: return
        val a = pr.away
        val h = pr.home
        if (a.isEmpty()) return
        val key = PpLabelKey(p, a, h)
        if (pr.labelKey == key || pr.bakingKey == key) return
        pr.bakingKey = key
        try {
            val labels = withContext(hooks.background) { hooks.bakeLabels(p, a, h, res) }
            hooks.probe?.pp?.let { it.labelBakes++ }
            // N1 (fix pass): published here, on the main thread, and only while the painter and the teams are still the ones
            // this bake was for. A stale bake that finishes after a newer one is dropped, so the masks always match the key.
            if (pr.painter === p && pr.away == a && pr.home == h) {
                if (labels != null) p.publishLabels(labels)
                pr.labelKey = key; pr.readyChanged()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) { // no labels: no tab
        } finally {
            if (pr.bakingKey == key) pr.bakingKey = null
        }
    }
    hooks.probe?.pp?.let {
        it.tabDriver = pr.tab.driver; it.pillDriver = pr.pill.driver
        it.tabContent = { pr.tab.content }
        it.pinned = { pr.pinned }
        it.painter = { pr.painter }
        it.frame = { PpFrame().also { f -> pr.sampleInto(f) } }
    }
    /** The layer margin `M` (goal design §1), design px: the layer's inset, and the glow's neighbour distance. */
    val margin = if (shownLarge) K.GOAL_LAYER_MARGIN_LARGE else K.GOAL_LAYER_MARGIN
    // N3: nothing of the takeover is built until a game is followed (the sample bug in place mode never needs it). Then
    // the Barlow load, the GOAL atlas and (never in low-end mode) the glow bake run once per size, off the main thread.
    // The first snapshot of a follow never plays (§5.2), so the painter is ready long before a goal can; if it is not,
    // that goal is skipped (the effect below). A failed bake just means no glow.
    val hasFollow = followed != null
    val goalWord = remember(res) { res.getString(R.string.sports_goal_word) }
    LaunchedEffect(s, shownLarge, hasFollow, lowEnd) {
        setup.painter = null
        glowMasks.value = null
        pr.painter = null; pr.labelKey = null; pr.bakingKey = null
        pr.readyChanged() // plain fields, not composition state: a painter arriving never recomposes the bug
        if (!hasFollow) return@LaunchedEffect
        val pillW = (if (shownLarge) K.SCORE_LARGE_W else K.SCORE_W) * d
        val pillH = (if (shownLarge) K.SCORE_LARGE_H else K.SCORE_H) * d
        val built = try {
            withContext(hooks.background) {
                val p = GoalPainter(
                    s, pillW, pillH,
                    sansTypeface(context, R.font.barlow_condensed_black_italic), sansTypeface(context, R.font.instrument_sans_bold),
                    goalWord,
                )
                p to (if (lowEnd) null else try { hooks.bake(p) } catch (e: CancellationException) { throw e } catch (_: Throwable) { null })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        built?.first?.prewarm(built.second)
        setup.painter = built?.first
        glowMasks.value = built?.second
        // The power-play painter and its per-size bakes (§3.4), beside the goal painter; a failure just means no tab.
        val pp = try {
            withContext(hooks.background) {
                PowerPlayPainter(s, d, pillW, pillH, sansTypeface(context, R.font.instrument_sans_bold)).also { hooks.bakeSize(it, !lowEnd) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        pr.painter = pp; pr.labelKey = null; pr.bakingKey = null
        pr.readyChanged()
        if (pp == null) return@LaunchedEffect
        bakeLabels()
        if (lowEnd) return@LaunchedEffect
        // The halo mask, per logo bitmap identity, never in low-end mode (§3.4). The logos are read through snapshotFlow, so
        // an arriving logo never recomposes the bug. S3 (fix pass): built off the main thread, published here only while
        // that bitmap is still the slot's logo; a game change clears the halos and bumps [haloEpoch], so this re-bakes.
        suspend fun watch(side: Side, slot: FollowLogos.Slot) {
            snapshotFlow { slot.image to haloEpoch.intValue }.collect { (img, _) ->
                val bmp = img?.asAndroidBitmap() ?: return@collect
                if (pp.haloBakedFor(side, bmp)) return@collect
                try {
                    val mask = withContext(hooks.background) { hooks.bakeHalo(pp, side, bmp) }
                    hooks.probe?.pp?.let { it.haloBakes++ }
                    if (mask != null && slot.image === img && pr.painter === pp) pp.publishHalo(side, bmp, mask)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                }
            }
        }
        coroutineScope {
            launch { watch(Side.AWAY, logos.away) }
            launch { watch(Side.HOME, logos.home) }
        }
    }
    // The label masks, per painter (size) and followed teams (S8): the tab stays closed until they are ready. A change of
    // teams re-bakes here; a new painter bakes at the end of the setup effect above.
    LaunchedEffect(awayAbbrev, homeAbbrev) { bakeLabels() }
    // S3 (fix pass): a new game never draws the previous game's halos, even with the same logo URLs (then the watchers
    // re-bake from the same bitmaps).
    val gameKey = bug?.gameId
    LaunchedEffect(gameKey) {
        if (gameKey == null) return@LaunchedEffect
        val was = pr.haloGame
        pr.haloGame = gameKey
        if (was == null || was == gameKey) return@LaunchedEffect // the first game of the bug: nothing to clear
        pr.painter?.clearHalos()
        haloEpoch.intValue++
    }

    // ---- power play (§3.1): what the runner is told this composition. The tab needs the placement to allow it, the labels
    // baked for this size and these teams, and a power play; the pill track needs only the painter and a power play. The
    // placement function runs only while a power play or a tab exists, so a game without one never calls it.
    val real = bug
    pr.lowEnd = lowEnd; pr.away = awayAbbrev; pr.home = homeAbbrev
    val ppShown = box != null && !placement.placing
    val target: PowerPlay? = if (ppShown) real?.powerPlay else null
    val tabAllowedNow = box != null && !placement.placing && (target != null || pr.tab.want != 0) && hooks.tabAllowed(placement, geometry, box)

    /** Ends the pin (§5): [snap] for a cancel; [goalShown] for the release at 3 600, which slides PP GOAL out if it was shown. */
    fun releasePin(snap: Boolean, goalShown: Boolean = false) {
        if (!pr.pinned) return
        hooks.probe?.pp?.releases?.add(PpProbe.Release(Snapshot.withoutReadObservation { driver.value }, snap))
        pr.release(snap, goalShown)
    }
    fun cancelTakeover(releaseThePin: Boolean = true) {
        if (run.active) { run.active = false; run.token++ }
        takeoverOn.value = false // a write, never a read: composition must not subscribe to it
        held.value = null
        pr.releasePillHold() // the next SideEffect step lets the pill follow the data
        if (releaseThePin) releasePin(snap = true)
    }
    if (real == null) {
        cancelTakeover()
        pr.reset()
        run.seen = false // the next snapshot is a "first snapshot" again: nothing replays
    } else {
        if (!run.seen || real.gameId != run.gameId) {
            cancelTakeover()
            pr.reset()
            run.seen = true; run.gameId = real.gameId; run.lastSeq = real.goalSeq; run.size = placement.size
        }
        // Place mode: both power-play tracks park at once (the sample bug is shown). Hidden: they are frozen where they are
        // and fade with the bug (fix pass N4), then park once the fade is done. The reappearance snaps (§3.1).
        if (placement.placing) {
            pr.park(); pr.hidden = true
        } else if (box == null) {
            pr.hidden = true
            if (fadeDone) pr.park()
        } else if (pr.hidden) {
            pr.hidden = false; pr.snapAll()
        }
        val waiting = real.bottom is CentreText.Delayed || real.bottom is CentreText.NoUpdate
        if (!pr.freshSeen && !waiting) { pr.freshSeen = true; pr.snapAll() } // the first non-waiting view of a follow (N5)
        if (box == null || placement.placing || placement.size != run.size) {
            cancelTakeover()
            run.size = placement.size
        }
        if (real.goalSeq != run.lastSeq) {
            run.lastSeq = real.goalSeq
            // A new goal mid-pin restarts the takeover, but the pin continues (§5): no release on this path.
            val coveredNow = pr.pinned && Snapshot.withoutReadObservation {
                takeoverOn.value && (run.still || driver.value >= GoalTimeline.LEAD_COVERED_MS)
            }
            cancelTakeover(releaseThePin = false)
            val side = real.goalSide
            if (side != null && box != null && !placement.placing) {
                val line = if (side == Side.AWAY) real.away else real.home
                run.side = side; run.abbrev = line.abbrev
                run.away = real.away.score ?: 0; run.home = real.home.score ?: 0
                run.glowOn = !lowEnd
                run.active = true; run.token++
                // This composition already shows the goal view; until the effect sets takeoverOn and the lead covers the
                // pill, the draw keeps the previous body.
                held.value = holder.lastFrame
                pr.pin(real.goalPp, side, tabAllowedNow, coveredNow)
            } else {
                releasePin(snap = true)
            }
        }
        // The glow follows the layout while a takeover is live (a bar opening mid-takeover turns it off on the next frame);
        // never on the idle path. Latched (N4): once off, it stays off for the rest of the run instead of snapping back
        // on at its current timeline alpha when the bar closes.
        if (run.active && box != null && run.glowOn) run.glowOn = !lowEnd && hooks.glowAllowed(placement, geometry, box, margin)
    }
    // The tab's seconds (§3.1): written in composition while the tab and the power play agree on side and kind, so a seconds
    // update rebuilds the draw lambda and redraws; it never kicks a track. A pinned tab takes them in draw (§5).
    pr.targetSide = target?.side
    pr.targetSecs = target?.secondsRemaining ?: PpContent.NO_SECS
    if (!pr.pinned && target != null && pr.tab.want != 0 && pr.tab.content.side == target.side &&
        pr.tab.content.label == (if (target.kind == PowerPlayKind.TWO_MAN) PpLabel.TWO_MAN else PpLabel.ONE_MAN)
    ) pr.tab.content.secs = pr.targetSecs
    // The runner gates these on its painter and labels (plain fields it owns): ready labels snap the tab open (S8).
    val tabWant = if (target != null && tabAllowedNow) PpRun.tabWant(target) else 0
    val pillWant = if (target != null) PpRun.pillWant(target) else 0
    val targetSide = target?.side ?: Side.AWAY
    val targetKind = target?.kind ?: PowerPlayKind.ONE_MAN
    val targetSecs = pr.targetSecs
    pr.setInputs(tabWant, pillWant, targetSide, targetKind, targetSecs)
    SideEffect {
        pr.step()
        hooks.probe?.pp?.afterStep?.invoke()
    }

    LaunchedEffect(run.token) {
        if (!run.active) return@LaunchedEffect
        val token = run.token
        val goalPainter = setup.painter
        if (goalPainter == null) { // not built: skipped
            run.active = false; held.value = null
            releasePin(snap = false)
            pr.releasePillHold(); pr.step()
            return@LaunchedEffect
        }
        val side = run.side
        val image = (if (side == Side.AWAY) logos.away else logos.home).image
        withContext(hooks.context) {
            val still = lowEnd || motionOff() // S8: the exact scale Compose applies to animateTo
            hooks.begin(goalPainter, side, hooks.colours(run.abbrev), image?.asAndroidBitmap(), run.abbrev, run.away, run.home)
            hooks.probe?.begins?.add(GoalProbe.Begin(side, run.abbrev, run.away, run.home, still))
            hooks.probe?.painter = goalPainter
            run.painter = goalPainter
            run.still = still
            driver.snapTo(0f)
            takeoverOn.value = true
            try {
                if (still) {
                    pr.covered() // frame A covers the pill from its first draw (S2, B2.4 override)
                    delay(K.GOAL_TAKEOVER_MS) // frame A; the delay is not scaled
                    driver.snapTo(K.GOAL_TAKEOVER_MS.toFloat()) // frame B
                    if (run.token == token) releasePin(snap = false, goalShown = true) // the pin's release (§5, S4)
                    delay(K.GOAL_POP_MS)
                } else {
                    // The pin's release (§5, S4): a child watching the driver, so the takeover keeps its single tween and a
                    // MotionDurationScale other than 1 still releases at driver 3 600.
                    launch {
                        snapshotFlow { driver.value >= K.GOAL_TAKEOVER_MS.toFloat() }.first { it }
                        if (run.token == token) releasePin(snap = false, goalShown = true)
                    }
                    // S2 / the B2.4 override (fix pass): once the lead covers the pill, the pill follows the data and a PP GOAL
                    // left from an earlier goal gives way to this goal's words. A child, like the release.
                    launch {
                        snapshotFlow { driver.value >= GoalTimeline.LEAD_COVERED_MS }.first { it }
                        if (run.token == token) pr.covered()
                    }
                    driver.animateTo(TAKEOVER_TOTAL_MS, tween(TAKEOVER_TOTAL_MS.toInt(), easing = LinearEasing))
                }
            } finally {
                // Only the run that is still current clears: a cancelled run's finally runs before its successor's body.
                if (run.token == token) {
                    takeoverOn.value = false; run.active = false; held.value = null
                    releasePin(snap = false)
                    pr.releasePillHold(); pr.step()
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
        if (GuideDebugCounters.ENABLED) GuideDebugCounters.bump(FRAMES_SCORE_BUG)
        painter.frame(shownView, res)
    }
    holder.lastFrame = frame
    // The sample bug ("AWY | HME") never borrows the followed game's logos.
    val shownLogos = if (shownView === sample) null else logos

    val w = if (shownLarge) K.SCORE_LARGE_W else K.SCORE_W
    val h = if (shownLarge) K.SCORE_LARGE_H else K.SCORE_H
    val placing = placement.placing
    val rightSide = last.l > 960f
    // U-S7: a plain string per shown view; semantics read nothing that changes between snapshots.
    val desc = remember(shownView, res) { Describe(res, shownView).text() }
    // S9: the live region's text changes (goal string while the mark is set, "" otherwise): twice per goal, never per frame.
    // (Power play §7) plus a power play's start, kind change and real end; the sample bug never speaks.
    val liveText = if (shownView === sample) "" else liveTexts.next(shownView, desc, res)
    val up = last.t + h / 2f > 540f // the tab opens above a bottom corner's pill, below a top one's (§4.2)

    val m = margin
    // The drawing node's whole draw, in the pill's own coordinates: takeoverOn, the driver and the glow state are read
    // here (in draw) and nowhere in composition. Handed to the probe so a test can render the real path.
    fun drawBug(c: android.graphics.Canvas) {
        if (GuideDebugCounters.ENABLED) GuideDebugCounters.bump(DRAWS_SCORE_BUG)
        var goal: GoalPainter? = null
        var g: GoalFrame? = null
        val on = takeoverOn.value
        var t = 0f
        if (on) {
            t = driver.value
            val f = hooks.sampler.sample(t, run.still, goalFrame)
            if (!f.done) { goal = run.painter; g = f }
        }
        // Pending (the effect has not started) or the lead still wiping in: the previous body. The still path's frame A
        // covers the pill from its first draw.
        val hold = held.value
        val body = if (hold != null && (!on || (!run.still && t < GoalTimeline.LEAD_COVERED_MS))) hold else frame
        // The power-play tracks (§3.5): sampled into the run's preallocated frame; the tab's words follow the pin rules (§5).
        var ppP: PowerPlayPainter? = null
        var ppF: PpFrame? = null
        var ppC: PpContent? = null
        var ppSide: Side? = null
        val sampled = pr.sampleForDraw()
        if (sampled != null) {
            ppP = pr.painter; ppF = sampled
            ppC = pr.drawContent(on && (run.still || t >= GoalTimeline.LEAD_COVERED_MS))
            ppSide = if (pr.pill.active) pr.pill.content.side else null
            hooks.probe?.pp?.let { pp ->
                pp.ppDraws++
                pp.lastDrawFrame = sampled; pp.lastDrawContent = ppC; pp.lastDrawSide = ppSide
            }
        }
        painter.drawFrame(
            c, glass.bitmap, body, shownLogos, logoWaited.value, placing,
            goal = goal, g = g, glow = glowMasks.value, glowOn = run.glowOn,
            pp = ppP, p = ppF, content = ppC, pillSide = ppSide, up = up,
        )
    }
    hooks.probe?.draw = ::drawBug
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
                .drawBehind { drawIntoCanvas { drawBug(it.nativeCanvas) } },
        )
        // The persistent live region (S9): pill-sized, always composed while the bug is.
        Spacer(
            Modifier
                .offset(m.dp, m.dp)
                .size(w.dp, h.dp)
                .testTag(SCORE_BUG_LIVE_TAG)
                .semantics { contentDescription = liveText; liveRegion = LiveRegionMode.Polite },
        )
    }
    // A sibling of the clipped layer, not inside it (goal design §1): same position source, same look.
    if (placing) PlaceHint(holder, pos, fade, rightSide, w, h)
}

/** The label bake's key (S8): the painter (one per size) and the followed teams' abbreviations. */
private data class PpLabelKey(val painter: PowerPlayPainter, val away: String, val home: String)

/** One animated track of the power-play runner (§3.1): its driver (elapsed ms, read only in draw), its `from` and its words. */
private class PpTrackRun(val track: PpTrack) {
    val driver = Animatable(0f)
    val from = PpFrame()
    /** Where the interrupted transition is, sampled before the driver snaps; becomes [from] once it has (S1). */
    val next = PpFrame()
    var kind = PpTransition.STILL
    var to = false
    /** Where the track is heading (or rests): 0 = closed, else the encoded target (the tab: side and kind; the pill: side). */
    var want = 0
    var job: Job? = null
    /** What the track says, captured when a transition starts (B2). The pill uses only [PpContent.side]. */
    val content = PpContent()
    /** The track has left its closed rest: draw has something to sample. */
    val active: Boolean get() = kind != PpTransition.STILL || to

    /** Cancels the running transition and snaps to the closed rest (no animation). */
    fun park() {
        job?.cancel(); job = null
        kind = PpTransition.STILL; to = false; want = 0
    }
}

/**
 * (Power play §3.1, §5; WP-3) The power-play runner: a plain holder in the composition, like [GoalRun]. Two tracks (the tab
 * and the pill), each with one `Animatable<Float>` of elapsed ms read only in draw and one running transition. A track is
 * kicked only when its key changes (null / non-null, side, kind, pin, tab allowed): [step] runs in a `SideEffect`, a seconds
 * update only redraws, and a kick whose target equals the running transition's never cancels it (review B1). The run owns
 * what the tab says ([PpTrackRun.content]), captured when a transition starts (B2).
 *
 * Targets are encoded as ints: the tab `1 + side * 2 + kind`, the pill `1 + side`, 0 = closed.
 */
private class PpRun(private val scope: CoroutineScope, private val hooks: GoalHooks) {
    val tab = PpTrackRun(PpTrack.TAB)
    val pill = PpTrackRun(PpTrack.PILL)
    /** The frame draw fills (both tracks write their own channels). */
    private val out = PpFrame()

    /** The power-play painter of this size (built off the main thread) and the label bake that is done / under way. */
    var painter: PowerPlayPainter? = null
    var labelKey: PpLabelKey? = null
    var bakingKey: PpLabelKey? = null
    var lowEnd = false
    /** The game the halos were last cleared for (S3): a different one clears them. */
    var haloGame: GameId? = null
    var away = ""
    var home = ""
    /** The painter has baked the labels of the followed teams: the tab may open (S8). */
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
    private var snapPill = false
    private var lastTabKey = -1
    private var lastPillKey = -1
    /** The latest inputs, kept so a pin's release or a painter arriving can re-evaluate without a composition. */
    private var inTabWant = 0
    private var inPillWant = 0
    private var inSide = Side.AWAY
    private var inKind = PowerPlayKind.ONE_MAN
    private var inSecs = PpContent.NO_SECS
    /** The shown power play's side and seconds, for a pinned tab's seconds (never another side's words). */
    var targetSide: Side? = null
    var targetSecs = PpContent.NO_SECS

    // ---- the pin (§5)
    var pinned = false
        private set
    private var pinTabOn = false
    private var ppGoal = false
    private var pinEdge = false
    private val pinContent = PpContent()
    private val ppGoalContent = PpContent(label = PpLabel.PP_GOAL)
    private val live = PpContent()
    /**
     * S2 (fix pass): a goal's takeover is pending or its lead has not covered the pill yet, so the pill track keeps what
     * it shows (no kick), like the tab and the body. Released by [covered] and on every end of the takeover.
     */
    private var pillHeld = false

    fun snapAll() { snapTab = true; snapPill = true }

    /** Hides both tracks at once: cancels what runs and parks at the closed rest (hide, place mode, a new follow). */
    fun park() {
        tab.park(); pill.park()
        lastTabKey = -1; lastPillKey = -1
        snapTab = false; snapPill = false
    }

    fun reset() {
        park()
        pinned = false; ppGoal = false; pinEdge = false
        freshSeen = false
    }

    private fun effTab() = if (ready) inTabWant else 0
    private fun effPill() = if (painter != null) inPillWant else 0

    /**
     * Written in composition (plain fields), so an effect that runs before the `SideEffect` ([step]) already sees them: the
     * order of a `LaunchedEffect` and a `SideEffect` in one apply is not ours to rely on.
     */
    fun setInputs(tabWant: Int, pillWant: Int, side: Side, kind: PowerPlayKind, secs: Int) {
        inTabWant = tabWant; inPillWant = pillWant; inSide = side; inKind = kind; inSecs = secs
    }

    /** The painter or the labels arrived or went (a size change, a new follow): the tracks snap to where they belong (S8). */
    fun readyChanged() {
        snapAll()
        step()
    }

    /**
     * `SideEffect`: starts a track's transition only when its key changed (or a snap was requested). Nothing moves while
     * the bug is hidden: the tracks fade with it as they are (N4), and the reappearance snaps.
     */
    fun step() {
        if (hidden) return
        val tabWant = effTab()
        val pillWant = effPill()
        val side = inSide; val kind = inKind; val secs = inSecs
        val tabKey = tabWant or (if (pinned) PIN_BIT else 0)
        if (pinned) {
            // Frozen (§5). A pin that starts with the tab open but not allowed closes it once; nothing else moves.
            if (pinEdge) {
                pinEdge = false
                if (!pinTabOn && tab.want != 0) evaluateTab(0, side, kind, secs, snap = false, cut = false)
            }
            lastTabKey = tabKey
        } else if (snapTab || tabKey != lastTabKey) {
            // Closing because the labels are not ready (a size change's bake) is a snap too (S8).
            val snap = snapTab || (tabWant == 0 && !ready && tab.want != 0)
            snapTab = false
            lastTabKey = tabKey
            evaluateTab(tabWant, side, kind, secs, snap, cut = false)
        }
        if (!pillHeld && (snapPill || pillWant != lastPillKey)) {
            val snap = snapPill
            snapPill = false
            lastPillKey = pillWant
            evaluatePill(pillWant, side, snap)
        }
    }

    // ---- the pin

    /**
     * A takeover starts (§5). A fresh pin captures what the tab is drawing; a second goal mid-pin keeps the pin and holds
     * what is drawn right now ([coveredNow]), `PP GOAL` included, until its own lead covers the pill (the B2.4 override:
     * then [covered] gives that goal its own words). [goalPp], [goalSide] and [allowed] decide `PP GOAL`: only for the
     * pinned tab's own side. The pill is held until [covered] (S2).
     */
    fun pin(goalPp: Boolean, goalSide: Side, allowed: Boolean, coveredNow: Boolean) {
        pillHeld = true
        if (!pinned) {
            pinned = true
            pinContent.copyFrom(tab.content)
            pinTabOn = tab.want != 0 && allowed
            pinEdge = true
        } else {
            live.copyFrom(drawContent(coveredNow))
            pinContent.copyFrom(live)
        }
        ppGoalContent.side = pinContent.side // N3: set here, so a release that drew no covered frame slides the right colours
        ppGoal = goalPp && pinTabOn && goalSide == pinContent.side
    }

    /**
     * The current takeover's lead covers the pill (the still path: frame A). S2: the pill follows the data again. The B2.4
     * override: if the tab still says an earlier goal's `PP GOAL` and this goal is not a power-play goal for it, the tab
     * takes the current power play's words (same side, a plain cut) or closes, sliding `PP GOAL` out, and stays closed
     * until the release. Called from the takeover effect (never in composition).
     */
    fun covered() {
        if (pinned && !ppGoal && pinContent.label == PpLabel.PP_GOAL) {
            if (targetSide == pinContent.side) {
                pinContent.label = labelOf(inKind); pinContent.secs = inSecs
                tab.content.copyFrom(pinContent)
            } else if (tab.want != 0) {
                tab.content.copyFrom(pinContent)
                pinTabOn = false
                evaluateTab(0, inSide, inKind, inSecs, snap = false, cut = false)
            }
        }
        releasePillHold()
        step()
    }

    /** Ends the pill hold (S2); the caller steps, or the next composition's `SideEffect` does. */
    fun releasePillHold() { pillHeld = false }

    /**
     * Ends the pin (the release sites, §5). [snap]: a cancel; the next composition's kick snaps the tab. Otherwise (the
     * takeover reached 3 600) the tab is re-evaluated now: a CLOSE if the power play is over, a close then an open on a side
     * change, else a plain cut to the current label and seconds (N6). [goalShown]: `PP GOAL` was on the tab, so a close slides
     * those words out.
     */
    fun release(snap: Boolean, goalShown: Boolean) {
        if (!pinned) return
        pinned = false
        // What the tab drew at the end of the takeover is what a close slides out, or what the cut replaces.
        if (goalShown) tab.content.copyFrom(if (ppGoal) ppGoalContent else pinContent)
        ppGoal = false; pinEdge = false; pillHeld = false
        if (snap) { snapTab = true; lastTabKey = -1; return }
        val want = effTab()
        lastTabKey = want
        evaluateTab(want, inSide, inKind, inSecs, snap = false, cut = true)
    }

    /** The words the tab draws now (§5): the tab's own unless pinned; the held copy until the lead covers the pill. */
    fun drawContent(covered: Boolean): PpContent {
        if (!pinned) return tab.content
        if (!covered) return pinContent
        if (ppGoal) return ppGoalContent
        live.copyFrom(pinContent)
        if (live.label != PpLabel.PP_GOAL && targetSide == pinContent.side) live.secs = targetSecs
        return live
    }

    // ---- the transitions

    /** The track already sits at the snapped rest for [want]: a second snap would only repeat it. */
    private fun atRest(t: PpTrackRun, want: Int) = t.kind == PpTransition.STILL && t.to == (want != 0)

    private fun labelOf(kind: PowerPlayKind) = if (kind == PowerPlayKind.TWO_MAN) PpLabel.TWO_MAN else PpLabel.ONE_MAN

    private fun setTab(side: Side, kind: PowerPlayKind, secs: Int) {
        tab.content.side = side; tab.content.label = labelOf(kind); tab.content.secs = secs
    }

    private fun begin(side: Side) {
        val p = painter ?: return
        p.begin(side, hooks.colours(if (side == Side.AWAY) away else home))
    }

    private fun evaluateTab(want: Int, side: Side, kind: PowerPlayKind, secs: Int, snap: Boolean, cut: Boolean) {
        val old = tab.want
        if (cut && want != 0 && old != 0 && sideOfTab(old) == side) {
            // Release with the same side still on: a cut to the current words, no pulse, no slide (N6).
            tab.want = want
            setTab(side, kind, secs)
            return
        }
        if (want == old && !(snap && tab.active && !atRest(tab, want))) return
        tab.want = want
        tab.job?.cancel()
        tab.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(hooks.context) { tabSeq(old, want, side, kind, secs, snap) }
        }
    }

    private suspend fun tabSeq(old: Int, want: Int, side: Side, kind: PowerPlayKind, secs: Int, snap: Boolean) {
        if (snap || lowEnd || motionOff()) { // the still version (§3.6): straight to rest, no shine, no pulse
            if (want == 0) {
                play(tab, PpTransition.STILL, false)
            } else {
                setTab(side, kind, secs); begin(side)
                play(tab, PpTransition.STILL, true)
            }
            return
        }
        when {
            old == 0 -> { setTab(side, kind, secs); begin(side); play(tab, PpTransition.OPEN, true) }
            want == 0 -> play(tab, PpTransition.CLOSE, false) // keeps the content it opened with
            sideOfTab(old) == side -> { setTab(side, kind, secs); play(tab, PpTransition.PULSE, true) } // the label switches, no slide
            else -> {
                play(tab, PpTransition.CLOSE, false) // X's words
                setTab(side, kind, secs); begin(side)
                play(tab, PpTransition.OPEN, true) // Y's words
            }
        }
    }

    private fun evaluatePill(want: Int, side: Side, snap: Boolean) {
        val old = pill.want
        if (want == old && !(snap && pill.active && !atRest(pill, want))) return
        pill.want = want
        pill.job?.cancel()
        pill.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(hooks.context) { pillSeq(old, want, side, snap) }
        }
    }

    private suspend fun pillSeq(old: Int, want: Int, side: Side, snap: Boolean) {
        if (snap || lowEnd || motionOff()) {
            if (want == 0) {
                play(pill, PpTransition.STILL, false)
            } else {
                pill.content.side = side; begin(side)
                play(pill, PpTransition.STILL, true)
            }
            return
        }
        when {
            old == 0 -> { pill.content.side = side; begin(side); play(pill, PpTransition.OPEN, true) }
            want == 0 -> play(pill, PpTransition.CLOSE, false)
            else -> { // off, then in, for another side (A → B)
                play(pill, PpTransition.CLOSE, false)
                pill.content.side = side; begin(side)
                play(pill, PpTransition.OPEN, true)
            }
        }
    }

    /**
     * Starts [kind] on [t] from where the interrupted transition is now (CSS transition semantics), then runs it.
     *
     * S1 (fix pass): the driver snaps **before** the new kind and `from` are assigned. A cancelled `animateTo` can hold the
     * driver's mutex until its continuation is dispatched, so `snapTo` may suspend; until it lands, draw keeps sampling the
     * old transition at its (frozen) old time, which is exactly [PpTrackRun.next]. No frame ever draws the new transition
     * at the old elapsed time.
     */
    private suspend fun play(t: PpTrackRun, kind: PpTransition, to: Boolean) {
        hooks.ppSampler.sample(t.track, t.kind, t.driver.value, t.from, t.to, t.next)
        t.driver.snapTo(0f)
        t.from.copyFrom(t.next)
        t.kind = kind; t.to = to
        hooks.probe?.pp?.transitions?.add(PpProbe.Rec(t.track, kind, to, t.content.side, t.content.label, t.content.secs))
        val ms = when (kind) {
            PpTransition.OPEN -> K.PP_OPEN_MS
            PpTransition.CLOSE -> K.PP_CLOSE_MS
            PpTransition.PULSE -> K.PP_PULSE_MS
            PpTransition.STILL -> 0L
        }
        if (ms > 0) t.driver.animateTo(ms.toFloat(), tween(ms.toInt(), easing = LinearEasing))
    }

    /** Samples both tracks into the preallocated frame for draw (no allocation); null when nothing is drawn. */
    fun sampleForDraw(): PpFrame? {
        if (painter == null || !(tab.active || pill.active)) return null
        return sampleInto(out)
    }

    /** Samples both tracks at their drivers' current values into [f]. */
    fun sampleInto(f: PpFrame): PpFrame {
        if (tab.active) hooks.ppSampler.sample(PpTrack.TAB, tab.kind, tab.driver.value, tab.from, tab.to, f) else f.tabClosed = true
        if (pill.active) hooks.ppSampler.sample(PpTrack.PILL, pill.kind, pill.driver.value, pill.from, pill.to, f) else f.pillClosed = true
        // Low-end mode never draws a shadow or a halo (§3.6), whatever the painter baked.
        if (lowEnd) { f.shadowAlpha = 0f; f.halo = 0f }
        return f
    }

    companion object {
        private const val PIN_BIT = 8

        fun tabWant(pp: PowerPlay) = 1 + pp.side.ordinal * 2 + pp.kind.ordinal
        fun pillWant(pp: PowerPlay) = 1 + pp.side.ordinal
        fun sideOfTab(want: Int) = if ((want - 1) / 2 == 0) Side.AWAY else Side.HOME
    }
}

/**
 * (Power play §7) The live region's text for the power play: the first of the goal string (while the mark is set), a start or
 * kind change, a real end ([ScoreBugView.ppEnded]), else "". A stale or waiting view leaves it unchanged; a new follow
 * resets it. Idempotent per view instance, so recompositions never repeat or lose a phrase.
 */
private class LiveText {
    private var game: GameId? = null
    private var lastView: ScoreBugView? = null
    private var prev: PowerPlay? = null
    var text = ""
        private set

    fun next(v: ScoreBugView, goalDesc: String, res: Resources): String {
        if (v === lastView) return text
        lastView = v
        val first = v.gameId != game
        if (first) { game = v.gameId; prev = null; text = "" }
        if (v.bottom is CentreText.Delayed || v.bottom is CentreText.NoUpdate) return text
        val pp = v.powerPlay
        val p = prev
        val began = !first && pp != null && (p == null || p.side != pp.side || p.kind != pp.kind)
        prev = pp
        text = when {
            v.goalSide != null -> goalDesc
            began && pp != null -> res.getString(
                if (pp.kind == PowerPlayKind.TWO_MAN) R.string.sports_live_pp_two_man else R.string.sports_live_pp,
                (if (pp.side == Side.AWAY) v.away else v.home).name,
            )
            v.ppEnded -> res.getString(R.string.sports_live_pp_over)
            else -> ""
        }
        return text
    }
}

private const val TAKEOVER_TOTAL_MS = (K.GOAL_TAKEOVER_MS + K.GOAL_POP_MS).toFloat()

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
    /** The frame the bug last composed for drawing: what a goal's takeover holds while it starts. */
    var lastFrame: ScoreBugFrame? = null
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
        val base = state(secs)
        val pp = v.powerPlay
        // (Power play §7) The power play joins the state's description in code with ". " (N3); the goal string stands alone.
        if (pp == null || v.goalSide != null) return base
        val team = (if (pp.side == Side.AWAY) v.away else v.home).name
        val left = pp.secondsRemaining
        val part = if (left == null) res.getString(R.string.sports_desc_pp_no_time, team)
        else res.getString(R.string.sports_desc_pp, team, SportsText.clock(left))
        return "$base. $part"
    }

    private fun state(secs: Int): String {
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
