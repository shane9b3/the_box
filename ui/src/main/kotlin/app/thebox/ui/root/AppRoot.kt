package app.thebox.ui.root

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.text.Cue
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.PlayerUiState
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.TunedState
import app.thebox.core.playback.RectPx
import app.thebox.core.playback.Viewport
import app.thebox.player.api.PlaybackState
import app.thebox.ui.ambient.AmbientBackground
import app.thebox.ui.full.FullScreenHost
import app.thebox.ui.full.PlayerUi
import app.thebox.ui.full.StickyVideo
import app.thebox.ui.full.SubtitleLayer
import app.thebox.ui.full.subtitleLiftPx
import app.thebox.ui.guide.screen.GuideScreen
import app.thebox.ui.guide.screen.ProvideShellImageLoader
import app.thebox.ui.playback.VideoLayer
import app.thebox.ui.reminder.ReminderCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

const val KEY_SINK_TAG = "key_sink"
/** The blank canvas drawn while `GuideUiState.booting` (owner ruling 2026-10-17). */
const val BOOT_TAG = "boot_blank"
/** The opaque SETTINGS layer (the settings screen, or an empty canvas while the settings machine is not attached, S11). */
const val SETTINGS_TAG = app.thebox.ui.settings.SETTINGS_SCREEN_TAG
const val VIDEO_LAYER_TAG = "video_layer"
/** U-S5: debug composition counter of the root body. */
const val COMPOSITIONS_ROOT = "AppRoot"

/** `SCRIM_MS` (Appendix B): the black scrim over the hole fades in/out in 120 ms (§3.13.5). */
const val SCRIM_MS = 120
/** The boot blank's hard cap: a slow first tune shows the player's own chrome after this rather than a blank. */
const val BOOT_BLANK_MAX_MS = 8_000L
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

/** The bottom bar FULL shows (toasts and captions lift over it, N8, U-B2). */
enum class PlayerBar { NONE, INFO_OR_SCRUB, QUICK }

fun barOf(player: PlayerUiState): PlayerBar = when {
    player.info != null || player.scrub != null -> PlayerBar.INFO_OR_SCRUB
    player.quick != null -> PlayerBar.QUICK
    else -> PlayerBar.NONE
}

/**
 * Bottom offset (design px) of the toasts in the displayed mode (docs/design/m4-player-ux.md §3.9, N8): 96, lifted to
 * 276 while the info or scrub bar shows in FULL and to 156 over the quick bar.
 */
fun toastBottomFor(shown: ScreenMode, bar: PlayerBar): Float = when {
    shown != ScreenMode.FULL -> PlayerUi.TOAST_B
    bar == PlayerBar.INFO_OR_SCRUB -> PlayerUi.TOAST_B_OVER_BAR
    bar == PlayerBar.QUICK -> PlayerUi.TOAST_B_OVER_QUICK
    else -> PlayerUi.TOAST_B
}

fun toastBottomFor(shown: ScreenMode, player: PlayerUiState): Float = toastBottomFor(shown, barOf(player))

/** Right offset (design px) of the reminder card: 64, or 568 while the picker sheet is open in FULL (N8). */
fun reminderEndFor(shown: ScreenMode, pickerOpen: Boolean): Float =
    if (shown == ScreenMode.FULL && pickerOpen) PlayerUi.REMINDER_R_BESIDE_PICKER else PlayerUi.REMINDER_R

fun reminderEndFor(shown: ScreenMode, player: PlayerUiState): Float = reminderEndFor(shown, player.picker != null)

/**
 * The root tree (docs/design/m3-guide.md §3.13.1, B1, B8; M4 docs/design/m4-player-ux.md §3.6.3):
 * ```
 * Box {                       // at the window origin; no layer, alpha or clip
 *   VideoLayer                 // the one SurfaceView; first, because it clears with an unclipped CLEAR on API ≤ 30
 *   AmbientBackground          // own default layer (B8): base, ambient bitmap, hole shadow; never inside the video rect
 *   HoleChrome                 // corners, ring, letterbox, still, scrim; drawn after the surface
 *   SubtitleLayer              // (M4, FULL only) AndroidView(SubtitleView) on the video rect; a direct child (N11)
 *   Box(key sink) {            // the one key sink (WP-D's KeyRouter.sinkModifier), survives every mode change
 *     GUIDE | FULL | SEARCH | SETTINGS screen (M5: SettingsScreen, in-tree overlays only); reminder card; toast
 *   }
 * }
 * ```
 * No node here applies `graphicsLayer`, alpha, clip, shadow or a RenderEffect to an ancestor of `VideoLayer`;
 * `RootTreeRuleTest` walks the tree. The key sink and the subtitle layer are siblings after `HoleChrome`, never
 * ancestors of `VideoLayer`.
 *
 * GUIDE ↔ FULL runs the scrim sequence (§3.13.5): the scrim fades over the hole in [SCRIM_MS], the displayed mode
 * switches in one layout pass (viewport and video rect follow the *displayed* mode), two frames later it fades out;
 * coming from a still or the HDR poster it waits for the next rendered first frame or [STILL_TO_FULL_MAX_WAIT_MS] (N6).
 *
 * (code review 2026-10-14, H-B1) The HDR poster is the controller's state, not a hole mode: the viewport stays MINI
 * (the controller keeps the video track off there) and the mini overlay and [HoleChrome] draw the poster still.
 *
 * (M4) The video rect comes from [StickyVideo] (§3.6.2): the previous picture's size and aspect are held across a zap
 * until the new tune renders or settles. The player's SCREEN_OFF layer maps to `HoleMode.COVERED` (viewport HIDDEN,
 * black); waking runs the scrim until the next rendered first frame (M3 H-S10). Player toasts and notices
 * ([ShellHost.player] events) share the toast slot, which lifts over the bottom bars (N8).
 */
@Composable
fun AppRoot(
    host: ShellHost,
    modifier: Modifier = Modifier,
    onExit: () -> Unit = {},
    /** Selected startup destination is visible and usable, rather than the initial blank frame. */
    onReady: () -> Unit = {},
    debugHint: Boolean = false,
    videoLayer: @Composable (RectPx?) -> Unit = { rect -> VideoLayer(host.surfacePlayer, rect, Modifier.testTag(VIDEO_LAYER_TAG)) },
    search: @Composable () -> Unit = {},
    /** (M5 integration) WP-D's phone page for `SettingsRoute.PhoneSetup` (the activity owns its view model). */
    settingsPhone: (@Composable (app.thebox.core.settings.PhonePurpose, Long?) -> Unit)? = null,
    /** (M6, docs/design/m6-channel-editor.md §2.0) The channel editor page for `SettingsRoute.ChannelEditor`. */
    settingsEditor: (@Composable (app.thebox.core.settings.SettingsRoute.ChannelEditor) -> Unit)? = null,
    grid: @Composable (Modifier) -> Unit,
) {
    if (app.thebox.ui.guide.GuideDebugCounters.ENABLED) androidx.compose.runtime.SideEffect { app.thebox.ui.guide.GuideDebugCounters.bump(COMPOSITIONS_ROOT) } // U-S5
    val ui by host.machine.ui.collectAsState()
    val video by host.video.collectAsState()
    val playback by host.playback.collectAsState()
    val settings by host.settings.collectAsState()
    val lowEnd by host.lowEnd.collectAsState()
    val player = host.player
    val playerFlow = remember(player) { player?.machine?.ui ?: MutableStateFlow(PlayerUiState()) }
    // U-S5: the player's state and the cues are never read in the root body. The root derives the few values it needs
    // (screen off, the bar under the toast and the captions, the picker), so a 25 Hz list or quick-bar hold recomposes
    // FullScreenHost only, and a cue list re-runs SubtitleLayer's update only.
    val puiState = playerFlow.collectAsState()
    val cuesFlow: StateFlow<List<Cue>> = remember(player) { player?.cues ?: MutableStateFlow(emptyList()) }
    val cues = cuesFlow.collectAsState()
    val playerScreenOff by remember(puiState) { derivedStateOf { puiState.value.layer == PlayerLayer.SCREEN_OFF } }
    val playerBar by remember(puiState) { derivedStateOf { barOf(puiState.value) } }
    val pickerOpen by remember(puiState) { derivedStateOf { puiState.value.picker != null } }

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
    // Owner ruling 2026-10-17: the boot blank outlives `GuideUiState.booting` until the displayed mode has caught up
    // with the machine's (the GUIDE → FULL crossing below animates the scrim before `shown` moves), so a launch into
    // FULL never shows the guide, not even for the crossing.
    var bootBlank by remember { mutableStateOf(ui.booting) }
    LaunchedEffect(ui.booting, ui.mode, shown) { if (!ui.booting && shown == ui.mode) bootBlank = false }
    // Safety net (2026-10-18): whatever the machine and the crossing do, the blank never outlives BOOT_BLANK_MAX_MS.
    LaunchedEffect(Unit) { if (bootBlank) { kotlinx.coroutines.delay(BOOT_BLANK_MAX_MS); bootBlank = false } }
    val onReadyNow by rememberUpdatedState(onReady)
    var reportedReady by remember { mutableStateOf(false) }
    LaunchedEffect(bootBlank, ui.booting, shown, ui.mode) {
        if (!reportedReady && !bootBlank && !ui.booting && shown == ui.mode) {
            withFrameNanos { }
            onReadyNow()
            reportedReady = true
        }
    }
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

    // ---- screen off (§3.4.5): COVERED while off; waking waits for the next rendered first frame under the scrim.
    val screenOff = shown == ScreenMode.FULL && playerScreenOff
    var wasOff by remember { mutableStateOf(false) }
    LaunchedEffect(screenOff) {
        if (screenOff) {
            wasOff = true
            return@LaunchedEffect
        }
        if (!wasOff) return@LaunchedEffect
        wasOff = false
        val framesBefore = host.video.value.renderedFirstFrames
        scrim.snapTo(1f)
        withTimeoutOrNull(STILL_TO_FULL_MAX_WAIT_MS) { host.video.first { it.renderedFirstFrames != framesBefore } }
        scrim.animateTo(0f, tween(SCRIM_MS, easing = LinearEasing))
    }

    val holeMode = if (screenOff) HoleMode.COVERED else holeModeFor(shown, ui.tuned)
    LaunchedEffect(holeMode) { host.setViewport(viewportFor(holeMode)) }
    val sticky = remember { StickyVideo() }
    val placed = sticky.resolve(video, playback, ui.tuned.handoff)
    val rects = remember(holeMode, holeVersion, container, placed.width, placed.height, placed.pixelRatio, placed.aspectMode) {
        geometry.rects(holeMode, placed.width, placed.height, placed.pixelRatio, placed.aspectMode)
    }

    // ---- key sink focus (B1): on every mode change and on resume
    val requester = remember { FocusRequester() }
    val sink = remember(host.keyRouter, requester) { host.keyRouter.sinkModifier(requester) }
    LaunchedEffect(ui.mode) { requestSinkFocus(requester) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { requestSinkFocus(requester) }
    // H-S11: Compose drops the key-ups of keys held while the window loses focus or detaches; end their holds.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { host.keyRouter.onFocusLost() }
    DisposableEffect(host.keyRouter) { onDispose { host.keyRouter.onFocusLost() } }

    // ---- shell events (the guide's and the player's share one toast slot)
    val resources = androidx.compose.ui.platform.LocalResources.current
    val view = LocalView.current
    var toast by remember { mutableStateOf<Pair<Long, String>?>(null) }
    val exit by rememberUpdatedState(onExit)
    val seq = remember { longArrayOf(0L) }
    val onEvent: (ShellEvent) -> Unit = { e ->
        when (e) {
            is ShellEvent.Toast -> {
                val text = toastText(resources, e.kind, e.arg)
                if (toast?.second != text) toast = (++seq[0]) to text
            }
            is ShellEvent.Message -> if (toast?.second != e.text) toast = (++seq[0]) to e.text
            is ShellEvent.Announce -> announce(view, e.text)
            ShellEvent.Exit -> exit()
        }
    }
    LaunchedEffect(host) { host.events.collect { onEvent(it) } }
    // (M5, docs/design/m5-setup-settings.md §3.2) The settings machine the guide composes for SETTINGS. AppRoot is the one
    // place that sees both, so it links the guide's dispatch (FULL → Settings on a route) and shows the settings layer's
    // shell events (toast slot, announcements, exit).
    val settingsMachine = host.machine.settings
    val settingsLink = settingsMachine as? app.thebox.core.settings.state.SettingsShellLink
    DisposableEffect(host.machine, settingsLink) {
        settingsLink?.attachGuide(host.machine::dispatch)
        onDispose { settingsLink?.attachGuide(null) }
    }
    LaunchedEffect(settingsLink) {
        settingsLink?.shellEvents?.collect { e ->
            when (e) {
                is app.thebox.core.settings.state.SettingsShellEvent.Toast -> onEvent(ShellEvent.Message(e.text))
                is app.thebox.core.settings.state.SettingsShellEvent.Announce -> announce(view, e.text)
                app.thebox.core.settings.state.SettingsShellEvent.Exit -> exit()
            }
        }
    }
    // The editor overlay owned Compose focus (S3); when it closes the root key sink takes focus back, as after SEARCH.
    val settingsUiFlow = remember(settingsMachine) { settingsMachine?.ui }
    // U-L6 (code review 2026-10-16): only "is the editor open" recomposes the root, not every settings key.
    val editorOpenFlow = remember(settingsUiFlow) {
        settingsUiFlow?.map { it.editor != null }?.distinctUntilChanged() ?: kotlinx.coroutines.flow.flowOf(false)
    }
    val editorOpen by editorOpenFlow.collectAsState(initial = false)
    LaunchedEffect(editorOpen) { if (!editorOpen) requestSinkFocus(requester) }
    LaunchedEffect(player) { player?.events?.collect { onEvent(it) } }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(TOAST_MS)
            toast = null
        }
    }

    val density = LocalDensity.current.density
    val anchor = remember(ui.overlay) { if (ui.overlay is Overlay.Menu || ui.overlay is Overlay.Sheet) cellAnchor(host, density) else null }
    val ambientShadowHole = if (holeMode == HoleMode.MINI || holeMode == HoleMode.STILL) rects.hole else null
    val toastBottom = toastBottomFor(shown, playerBar)
    val reminderEnd = reminderEndFor(shown, pickerOpen)

    ProvideShellImageLoader(host.imageLoader) {
        run { // (was the static LocalPlayerArchive provider; U-S5/L-4: GuideScreen passes state.archive itself)
            Box(modifier.fillMaxSize()) {
                // VideoLayer first: on API ≤ 30 its SurfaceView punches the hole with an unclipped drawColor(CLEAR), which
                // would erase anything drawn before it in the frame (see AmbientBackground). The background then paints
                // everything except the video rect.
                videoLayer(rects.video)
                AmbientBackground(host.ambient, rects.video, ambientShadowHole, black = holeMode == HoleMode.FULL || screenOff || bootBlank)
                if (!bootBlank) HoleChrome(rects, holeMode, host.ambient, scrim = { scrim.value }, poster = holeMode == HoleMode.MINI && video.posterMode)
                val videoRect = rects.video
                if (shown == ScreenMode.FULL && !screenOff && player != null && videoRect != null) {
                    val barTop = when (playerBar) {
                        PlayerBar.INFO_OR_SCRUB -> PlayerUi.BAR_T * density
                        PlayerBar.QUICK -> PlayerUi.QUICK_T * density
                        PlayerBar.NONE -> null
                    }
                    SubtitleLayer(cues, videoRect, subtitleLiftPx(videoRect, barTop, density)) // U-B2
                }
                Box(Modifier.fillMaxSize().testTag(KEY_SINK_TAG).then(sink)) {
                    // Owner ruling 2026-10-17: nothing but the canvas until the startup decision (no guide flash before FULL).
                    if (bootBlank) Box(Modifier.fillMaxSize().background(app.thebox.ui.theme.DesignTokens.Colors.Canvas).testTag(BOOT_TAG))
                    else when (shown) {
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
                        ScreenMode.FULL -> FullScreenHost(ui, player = player, clock24 = settings.clock24, lowEnd = lowEnd)
                        ScreenMode.SEARCH -> search()
                        ScreenMode.SETTINGS -> if (settingsMachine != null) {
                            app.thebox.ui.settings.SettingsScreen(settingsMachine, settings.clock24, phone = settingsPhone, channelEditor = settingsEditor)
                        } else {
                            // S11: the settings machine is not attached yet; the guide machine waits. Opaque, covers the hole.
                            Box(Modifier.fillMaxSize().background(app.thebox.ui.theme.DesignTokens.Colors.Canvas).testTag(SETTINGS_TAG))
                        }
                    }
                    if (shown == ScreenMode.GUIDE || shown == ScreenMode.FULL) {
                        ui.reminder?.let { ReminderCard(it, Modifier.align(Alignment.BottomEnd).padding(end = reminderEnd.dp, bottom = toastBottom.dp)) }
                    }
                    // M5 commit 0 (docs/design/m5-setup-settings.md §1.3a): the update card in the guide only (S17), the
                    // PIN prompt above every mode (§0.9). Bodies: WP-E, WP-C.
                    if (shown == ScreenMode.GUIDE) ui.update?.let { app.thebox.ui.update.UpdateCard(it) }
                    // U-L5 (code review 2026-10-16): the countdown reads the clock `lockedUntilMs` came from (DebugOverrides).
                    ui.pin?.let { app.thebox.ui.parental.PinPadView(it, nowMs = app.thebox.core.diag.DebugOverrides::nowMs) }
                    toast?.let { (_, text) -> ToastView(text, Modifier.align(Alignment.BottomStart).padding(start = PlayerUi.TOAST_L.dp, bottom = toastBottom.dp)) }
                }
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
