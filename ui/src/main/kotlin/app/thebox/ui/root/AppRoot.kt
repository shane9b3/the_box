package app.thebox.ui.root

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.TunedState
import app.thebox.core.playback.RectPx
import app.thebox.core.playback.Viewport
import app.thebox.player.api.PlaybackState
import app.thebox.ui.ambient.AmbientBackground
import app.thebox.ui.full.FullScreenHost
import app.thebox.ui.guide.screen.GuideScreen
import app.thebox.ui.guide.screen.ProvideShellImageLoader
import app.thebox.ui.playback.VideoLayer
import app.thebox.ui.reminder.ReminderCard
import kotlinx.coroutines.delay
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

const val KEY_SINK_TAG = "key_sink"
const val VIDEO_LAYER_TAG = "video_layer"

/** `SCRIM_MS` (Appendix B): the black scrim over the hole fades in/out in 120 ms (§3.13.5). */
const val SCRIM_MS = 120
/**
 * Coming from a still (or the HDR poster), the FULL scrim lifts on the re-enabled video track's first rendered frame
 * (`VideoInfo.renderedFirstFrames`, code review 2026-10-14, H-S10), waiting at most this long (N6).
 */
const val STILL_TO_FULL_MAX_WAIT_MS = 2_000L

/** §3.13.2: what the hole shows for the displayed mode. */
fun holeModeFor(mode: ScreenMode, tuned: TunedState): HoleMode = when (mode) {
    ScreenMode.GUIDE -> if (tuned.still || nothingTuned(tuned)) HoleMode.STILL else HoleMode.MINI
    ScreenMode.FULL -> HoleMode.FULL
    ScreenMode.SEARCH, ScreenMode.SETTINGS -> HoleMode.COVERED
}

/** §3.13.2: GUIDE → MINI (HIDDEN while the mini is a still), FULL → FULL, SEARCH/SETTINGS → HIDDEN. */
fun viewportFor(hole: HoleMode): Viewport = when (hole) {
    HoleMode.MINI -> Viewport.MINI
    HoleMode.FULL -> Viewport.FULL
    HoleMode.STILL, HoleMode.COVERED -> Viewport.HIDDEN
}

private fun nothingTuned(t: TunedState) = t.identity == null || t.phase == MiniPhase.IDLE || t.phase == MiniPhase.STOPPED

/**
 * The root tree (docs/design/m3-guide.md §3.13.1, B1, B8):
 * ```
 * Box {                       // at the window origin; no layer, alpha or clip
 *   VideoLayer                 // the one SurfaceView; first, because it clears with an unclipped CLEAR on API ≤ 30
 *   AmbientBackground          // own default layer (B8): base, ambient bitmap, hole shadow; never inside the video rect
 *   HoleChrome                 // corners, ring, letterbox, still, scrim; drawn after the surface
 *   Box(key sink) {            // the one key sink (WP-D's KeyRouter.sinkModifier), survives every mode change
 *     GUIDE | FULL | SEARCH | SETTINGS screen; reminder card; toast
 *   }
 * }
 * ```
 * No node here applies `graphicsLayer`, alpha, clip, shadow or a RenderEffect to an ancestor of `VideoLayer`;
 * `RootTreeRuleTest` walks the tree. The key sink is a sibling after `HoleChrome`, never an ancestor of `VideoLayer`.
 *
 * GUIDE ↔ FULL runs the scrim sequence (§3.13.5): the scrim fades over the hole in [SCRIM_MS], the displayed mode
 * switches in one layout pass (viewport and video rect follow the *displayed* mode), two frames later it fades out;
 * coming from a still or the HDR poster it waits for the next rendered first frame or [STILL_TO_FULL_MAX_WAIT_MS] (N6).
 *
 * (code review 2026-10-14, H-B1) The HDR poster is the controller's state, not a hole mode: the viewport stays MINI
 * (the controller keeps the video track off there) and the mini overlay and [HoleChrome] draw the poster still.
 */
@Composable
fun AppRoot(
    host: ShellHost,
    modifier: Modifier = Modifier,
    onExit: () -> Unit = {},
    debugHint: Boolean = false,
    videoLayer: @Composable (RectPx?) -> Unit = { rect -> VideoLayer(host.surfacePlayer, rect, Modifier.testTag(VIDEO_LAYER_TAG)) },
    search: @Composable () -> Unit = {},
    grid: @Composable (Modifier) -> Unit,
) {
    val ui by host.machine.ui.collectAsState()
    val video by host.video.collectAsState()
    val settings by host.settings.collectAsState()
    val lowEnd by host.lowEnd.collectAsState()

    // ---- geometry (window px)
    val container = LocalWindowInfo.current.containerSize
    val geometry = remember { HoleGeometry() }
    var holeVersion by remember { mutableIntStateOf(0) }
    if (container.width != geometry.windowWidthPx || container.height != geometry.windowHeightPx) {
        geometry.update(container.width, container.height)
    }
    LaunchedEffect(container) { host.onWindowSize(container.width, container.height) }
    val onHoleMeasured: (RectPx) -> Unit = remember(geometry) { { r -> if (geometry.publishMeasured(r)) holeVersion++ } }

    // ---- displayed mode and the scrim (§3.13.5)
    var shown by remember { mutableStateOf(ui.mode) }
    val scrim = remember { Animatable(0f) }
    val tunedNow by rememberUpdatedState(ui.tuned)
    val posterNow by rememberUpdatedState(video.posterMode)
    LaunchedEffect(ui.mode) {
        val target = ui.mode
        val from = shown
        val framesBefore = host.video.value.renderedFirstFrames
        if (from == target) {
            scrim.snapTo(0f)
            return@LaunchedEffect
        }
        val crossesFull = (from == ScreenMode.GUIDE && target == ScreenMode.FULL) || (from == ScreenMode.FULL && target == ScreenMode.GUIDE)
        if (!crossesFull) {
            shown = target
            scrim.snapTo(0f)
            return@LaunchedEffect
        }
        val fromStill = holeModeFor(from, tunedNow) == HoleMode.STILL || (from == ScreenMode.GUIDE && posterNow)
        scrim.animateTo(1f, tween(SCRIM_MS, easing = LinearEasing))
        shown = target
        withFrameNanos { }
        withFrameNanos { }
        if (target == ScreenMode.FULL && fromStill && host.playback.value !is PlaybackState.Idle) {
            // H-S10: the re-enabled track's first frame, not the next `Playing` (often equal, so never emitted).
            withTimeoutOrNull(STILL_TO_FULL_MAX_WAIT_MS) { host.video.first { it.renderedFirstFrames != framesBefore } }
        }
        scrim.animateTo(0f, tween(SCRIM_MS, easing = LinearEasing))
    }

    val holeMode = holeModeFor(shown, ui.tuned)
    LaunchedEffect(holeMode) { host.setViewport(viewportFor(holeMode)) }
    val rects = remember(holeMode, holeVersion, container, video.width, video.height, video.pixelRatio, video.aspectMode) {
        geometry.rects(holeMode, video.width, video.height, video.pixelRatio, video.aspectMode)
    }

    // ---- key sink focus (B1): on every mode change and on resume
    val requester = remember { FocusRequester() }
    val sink = remember(host.keyRouter, requester) { host.keyRouter.sinkModifier(requester) }
    LaunchedEffect(ui.mode) { requestSinkFocus(requester) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { requestSinkFocus(requester) }
    // H-S11: Compose drops the key-ups of keys held while the window loses focus or detaches; end their holds.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { host.keyRouter.onFocusLost() }
    DisposableEffect(host.keyRouter) { onDispose { host.keyRouter.onFocusLost() } }

    // ---- shell events
    val context = LocalContext.current
    val view = LocalView.current
    var toast by remember { mutableStateOf<Pair<Long, String>?>(null) }
    val exit by rememberUpdatedState(onExit)
    LaunchedEffect(host) {
        var seq = 0L
        host.events.collect { e ->
            when (e) {
                is ShellEvent.Toast -> {
                    val text = toastText(context.resources, e.kind, e.arg)
                    if (toast?.second != text) toast = (++seq) to text
                }
                is ShellEvent.Announce -> announce(view, e.text)
                ShellEvent.Exit -> exit()
                is ShellEvent.Message -> Unit // M4 commit 0 (§1.7 item 7); WP-D shows it as a toast
            }
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(TOAST_MS)
            toast = null
        }
    }

    val density = LocalDensity.current.density
    val anchor = remember(ui.overlay) { if (ui.overlay is Overlay.Menu || ui.overlay is Overlay.Sheet) cellAnchor(host, density) else null }
    val ambientShadowHole = if (holeMode == HoleMode.MINI || holeMode == HoleMode.STILL) rects.hole else null

    ProvideShellImageLoader(host.imageLoader) {
        Box(modifier.fillMaxSize()) {
            // VideoLayer first: on API ≤ 30 its SurfaceView punches the hole with an unclipped drawColor(CLEAR), which
            // would erase anything drawn before it in the frame (see AmbientBackground). The background then paints
            // everything except the video rect.
            videoLayer(rects.video)
            AmbientBackground(host.ambient, rects.video, ambientShadowHole, black = holeMode == HoleMode.FULL)
            HoleChrome(rects, holeMode, host.ambient, scrim = { scrim.value }, poster = holeMode == HoleMode.MINI && video.posterMode)
            Box(Modifier.fillMaxSize().testTag(KEY_SINK_TAG).then(sink)) {
                when (shown) {
                    ScreenMode.GUIDE -> GuideScreen(
                        state = ui,
                        clock24 = settings.clock24,
                        lowEnd = lowEnd,
                        poster = video.posterMode,
                        anchor = anchor,
                        onHoleMeasured = onHoleMeasured,
                        debugHint = debugHint,
                        grid = grid,
                    )
                    ScreenMode.FULL -> FullScreenHost(ui)
                    ScreenMode.SEARCH -> search()
                    ScreenMode.SETTINGS -> SettingsPlaceholder(host.versionName)
                }
                if (shown == ScreenMode.GUIDE || shown == ScreenMode.FULL) {
                    ui.reminder?.let { ReminderCard(it, Modifier.align(Alignment.BottomEnd).padding(end = 64.dp, bottom = 96.dp)) }
                }
                toast?.let { (_, text) -> ToastView(text, Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 96.dp)) }
            }
        }
    }
}

/** TalkBack/VoiceView announcement (§3.6.6); the platform call is the only one VoiceView on Fire OS honours. */
@Suppress("DEPRECATION")
private fun announce(view: android.view.View, text: String) = view.announceForAccessibility(text)

private fun requestSinkFocus(requester: FocusRequester) {
    try {
        requester.requestFocus()
    } catch (e: IllegalStateException) {
        // Not attached yet (first frame); the next mode change or ON_RESUME requests again.
    }
}

/** The focused cell in design px (renderer bounds are device px), or null when it is off-screen or unknown. */
private fun cellAnchor(host: ShellHost, density: Float): Rect? {
    val out = FloatArray(4)
    if (!host.focusedCellBounds(out) || density <= 0f) return null
    return Rect(out[0] / density, out[1] / density, out[2] / density, out[3] / density)
}
