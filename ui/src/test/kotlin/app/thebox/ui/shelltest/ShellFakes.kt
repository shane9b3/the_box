package app.thebox.ui.shelltest

import android.os.Looper
import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.media3.common.SimpleBasePlayer
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideSettings
import app.thebox.core.guide.GuideStateMachine
import app.thebox.core.guide.GuideToast
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.ScreenMode
import app.thebox.core.input.BoxAction
import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.VideoDecoderMode
import app.thebox.core.playback.Viewport
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.player.api.ArchiveTune
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.PlaybackDiagnostics
import app.thebox.player.api.PlaybackNotice
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.PositionSnapshot
import app.thebox.player.api.StopReason
import app.thebox.player.api.StreamStats
import app.thebox.player.api.TrackInfo
import app.thebox.player.api.TrackRef
import app.thebox.player.api.TuneTarget
import app.thebox.player.api.VideoInfo
import app.thebox.player.api.ZapList
import app.thebox.ui.ambient.AmbientState
import app.thebox.ui.guide.vm.ShellEffects
import app.thebox.ui.input.KeyRouter
import app.thebox.ui.playback.CatchupLaunch
import app.thebox.ui.playback.CatchupLauncher
import app.thebox.ui.root.ShellEvent
import app.thebox.ui.root.ShellHost
import coil3.ImageLoader
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** A Player with no commands: PlayerSurface creates its SurfaceView but attaches nothing (enough for layout tests). */
class StubPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    override fun getState(): State = State.Builder().build()
    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> = Futures.immediateVoidFuture()
    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> = Futures.immediateVoidFuture()
}

/**
 * A [KeyRouter] built exactly as the contract's KDoc says (§2.10): `focusRequester → onFocusChanged → onPreviewKeyEvent
 * → focusable`. It maps a handful of keys to [BoxAction]s and dispatches them to the attached machine; it returns true
 * for every handled key including the matching KeyUp, and passes everything except BACK through in SEARCH.
 */
class TestKeyRouter : KeyRouter {
    var machine: GuideStateMachine? = null
    val seen = ArrayList<BoxAction>()
    var focusLost = 0

    override fun onKey(event: KeyEvent): Boolean {
        val m = machine ?: return false
        val action = when (event.key) {
            Key.Back -> BoxAction.BACK
            Key.DirectionCenter, Key.Enter -> BoxAction.SELECT
            Key.DirectionUp -> BoxAction.UP
            Key.DirectionDown -> BoxAction.DOWN
            Key.DirectionLeft -> BoxAction.LEFT
            Key.DirectionRight -> BoxAction.RIGHT
            else -> return false
        }
        if (m.ui.value.mode == ScreenMode.SEARCH && action != BoxAction.BACK) return false
        if (event.type == KeyEventType.KeyDown) {
            seen += action
            m.dispatch(GuideEvent.Act(action))
        }
        return true
    }

    override fun onFocusLost() { focusLost++ }

    override fun attach(machine: GuideStateMachine?, searchFieldIdle: () -> Boolean) { this.machine = machine }

    override fun sinkModifier(requester: FocusRequester): Modifier =
        Modifier.focusRequester(requester).onFocusChanged { if (!it.isFocused) onFocusLost() }.onPreviewKeyEvent(::onKey).focusable()
}

class FakeShellHost(
    override val machine: GuideStateMachine,
    override val keyRouter: KeyRouter = TestKeyRouter().also { it.attach(machine) { true } },
) : ShellHost {
    override val surfacePlayer = StubPlayer()
    val videoFlow = MutableStateFlow(VideoInfo(width = 1920, height = 1080))
    override val video: StateFlow<VideoInfo> = videoFlow
    val playbackFlow = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val playback: StateFlow<PlaybackState> = playbackFlow
    override val settings: StateFlow<GuideSettings> = MutableStateFlow(GuideSettings())
    override val lowEnd: StateFlow<Boolean> = MutableStateFlow(false)
    override val ambient = AmbientState().apply { flat = true }
    val eventsFlow = MutableSharedFlow<ShellEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<ShellEvent> = eventsFlow
    override val imageLoader: ImageLoader? = null
    override val versionName = "test"
    val viewports = ArrayList<Viewport>()
    var window = 0 to 0
    override fun setViewport(viewport: Viewport) { viewports += viewport }
    override fun onWindowSize(widthPx: Int, heightPx: Int) { window = widthPx to heightPx }
    override fun focusedCellBounds(out: FloatArray): Boolean = false
}

class RecordingShellEffects : ShellEffects {
    val calls = ArrayList<String>()
    val toasts = ArrayList<Pair<GuideToast, String?>>()
    override fun setFullScreen(full: Boolean) { calls += "setFullScreen:$full" }
    override fun toast(kind: GuideToast, arg: String?) { toasts += kind to arg; calls += "toast:$kind" }
    override fun announce(text: String) { calls += "announce" }
    override fun exitApp() { calls += "exitApp" }
    override fun openSearch() { calls += "openSearch" }
    override fun openSettings() { calls += "openSettings" }
}

class FakeCatchupLauncher(var result: CatchupLaunch = CatchupLaunch.Started) : CatchupLauncher {
    val calls = ArrayList<Triple<GuideChannel, ProgrammeInfo, Int>>()
    override suspend fun play(channel: GuideChannel, info: ProgrammeInfo, rawEndMin: Int): CatchupLaunch {
        calls += Triple(channel, info, rawEndMin)
        return result
    }
}

/** Records the calls the guide makes; flows are settable. */
class FakePlaybackController : PlaybackController {
    val calls = ArrayList<String>()
    val tunes = ArrayList<Pair<TuneTarget, ZapList?>>()
    val zapLists = ArrayList<ZapList>()
    override val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val pendingZap = MutableStateFlow<ChannelIdentity?>(null)
    override val video = MutableStateFlow(VideoInfo())
    override val tracks = MutableStateFlow(TrackInfo())
    override val stats = MutableStateFlow(StreamStats())
    override val notices = MutableSharedFlow<PlaybackNotice>()
    override val keepScreenOn = MutableStateFlow(false)
    override val displayModeRequest = MutableStateFlow(0)
    override val surfacePlayer: androidx.media3.common.Player get() = error("unused")
    override val diagnostics: PlaybackDiagnostics get() = error("unused")
    override fun tune(target: TuneTarget, zapList: ZapList?) { calls += "tune"; tunes += target to zapList }
    override fun zap(delta: Int, isRepeat: Boolean) { calls += "zap:$delta:$isRepeat" }
    override fun setZapList(list: ZapList) { calls += "setZapList"; zapLists += list }
    override fun previousChannel(): Boolean { calls += "previousChannel"; return true }
    override fun tuneArchive(tune: ArchiveTune) { calls += "tuneArchive" }
    override fun seekTo(positionMs: Long) { calls += "seekTo" }
    override fun position() = PositionSnapshot(0, 0, null, false, null)
    override fun pause() { calls += "pause" }
    override fun resume() { calls += "resume" }
    override fun stop(reason: StopReason) { calls += "stop" }
    override fun retry() { calls += "retry" }
    override fun setViewport(viewport: Viewport) { calls += "viewport:$viewport" }
    override fun setStatsEnabled(enabled: Boolean) = Unit
    override fun selectAudioTrack(track: TrackRef?, remember: Boolean) = Unit
    override fun selectTextTrack(track: TrackRef?, remember: Boolean) = Unit
    override fun setAspectMode(mode: AspectMode, remember: Boolean) = Unit
    override fun setVideoDecoderOverride(mode: VideoDecoderMode?, remember: Boolean) = Unit
    override fun prewarm() { calls += "prewarm" }
}
