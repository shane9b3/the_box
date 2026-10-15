package app.thebox.ui.root

import androidx.media3.common.Player
import app.thebox.core.guide.GuideSettings
import app.thebox.core.guide.GuideStateMachine
import app.thebox.core.guide.GuideToast
import app.thebox.core.playback.Viewport
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.VideoInfo
import app.thebox.ui.ambient.AmbientState
import app.thebox.ui.input.KeyRouter
import coil3.ImageLoader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** One-shot shell events (toasts, announcements, exit). */
sealed interface ShellEvent {
    data class Toast(val kind: GuideToast, val arg: String?) : ShellEvent
    data class Announce(val text: String) : ShellEvent
    data object Exit : ShellEvent
    /** (M4, docs/design/m4-player-ux.md §2.5) Already-localised text, used for notices. */
    data class Message(val text: String) : ShellEvent
}

/**
 * What [AppRoot] needs from the app (docs/design/m3-guide.md §3.13.1, §3.16). `GuideViewModel` implements it; tests
 * and the debug shell preview supply fakes. Main thread only.
 */
interface ShellHost {
    val machine: GuideStateMachine
    val keyRouter: KeyRouter
    /** `PlaybackController.surfacePlayer`: given to `VideoLayer` and nothing else. */
    val surfacePlayer: Player
    val video: StateFlow<VideoInfo>
    val playback: StateFlow<PlaybackState>
    val settings: StateFlow<GuideSettings>
    /** Low-end mode resolved from [GuideSettings.performance] and the device class (§0.11). */
    val lowEnd: StateFlow<Boolean>
    val ambient: AmbientState
    /**
     * One-shot events, each delivered once to one collector and buffered while nobody collects (code review
     * 2026-10-14, H-B3): a toast emitted before the root composes (the missed-reminders toast at a cold start) is shown
     * when it does.
     */
    val events: Flow<ShellEvent>
    /** The app's Coil loader for logos on Compose surfaces (panel, mini player, error card); null in tests. */
    val imageLoader: ImageLoader?
    /** `BuildConfig.VERSION_NAME` of the app, for the Settings placeholder. */
    val versionName: String

    /** The viewport follows the *displayed* mode (§3.13.2); the root dedupes. */
    fun setViewport(viewport: Viewport)

    /** Window size in px (metrics for the grid, §3.1). Called on every size change. */
    fun onWindowSize(widthPx: Int, heightPx: Int)

    /** Device-px bounds of the focused grid cell (l, t, r, b) for anchoring Menu/Sheet; false when unknown. */
    fun focusedCellBounds(out: FloatArray): Boolean

    /** (M4, docs/design/m4-player-ux.md §2.5) The player view model; null in tests and the debug shell preview. */
    val player: app.thebox.ui.full.PlayerHost? get() = null
}
