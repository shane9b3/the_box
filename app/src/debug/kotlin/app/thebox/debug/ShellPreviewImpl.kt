package app.thebox.debug

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.media3.common.Player
import app.thebox.core.guide.DayItem
import app.thebox.core.guide.FooterHint
import app.thebox.core.guide.GroupsShortcut
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideFrame
import app.thebox.core.guide.GuideSettings
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.GuideStateMachine
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.MenuItem
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.Overlay
import app.thebox.core.guide.PanelButton
import app.thebox.core.guide.PanelState
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.ReminderAlert
import app.thebox.core.guide.ReminderCardState
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.SourceSection
import app.thebox.core.guide.SyncChip
import app.thebox.core.guide.TunedState
import app.thebox.core.guide.Zone
import app.thebox.core.model.StableKey
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.Viewport
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.VideoInfo
import app.thebox.shell.ShellPreview
import app.thebox.ui.ambient.AmbientState
import app.thebox.ui.input.KeyRouter
import app.thebox.ui.root.ShellEvent
import app.thebox.ui.root.ShellHost
import coil3.ImageLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * TEMPORARY (WP-C visual check; the integrator deletes this file with `app/.../shell/ShellPreview.kt`). Canned
 * `GuideUiState`s over the real `PlaybackController` surface, selected with
 * `adb shell am start -n app.thebox.debug/app.thebox.MainActivity --ez thebox.shell_preview true --es thebox.shell_state groups`
 * (states: default, groups, menu, days, reminder, exit, error, full).
 */
@Module
@InstallIn(SingletonComponent::class)
object DebugShellPreviewModule {
    @Provides
    fun shellPreview(): ShellPreview = ShellPreviewImpl()
}

class ShellPreviewImpl : ShellPreview {
    override fun host(controller: PlaybackController, intent: Intent): ShellHost =
        PreviewHost(controller, PreviewStates.of(intent.getStringExtra("thebox.shell_state") ?: "default"))

    @Composable
    override fun Grid(modifier: Modifier) {
        // A stand-in for WP-A's GuideGridHost: the glass panel with a ruler and six rows of plain cells.
        Canvas(modifier.graphicsLayer()) {
            val s = size.width / 1792f
            drawRoundRect(Color.White.copy(alpha = 0.045f), cornerRadius = CornerRadius(24 * s))
            drawRoundRect(Color.White.copy(alpha = 0.09f), cornerRadius = CornerRadius(24 * s), style = Stroke(1 * s))
            val tl = 217f * s
            for (k in 0 until 6) {
                val x = tl + k * 259.67f * s
                drawRect(Color.White.copy(alpha = 0.1f), Offset(x, 0f), Size(1 * s, 48 * s))
            }
            for (r in 0 until 6) {
                val y = (49 + 68 * r) * s
                if (r == 2) drawRoundRect(Color.White.copy(alpha = 0.08f), Offset(17 * s, y), Size(200 * s, 60 * s), CornerRadius(12 * s))
                var x = tl
                var k = r
                while (x < size.width - 17 * s) {
                    val w = (intArrayOf(30, 60, 90, 30, 120)[k++ % 5] * 8.656f * s).coerceAtMost(size.width - 17 * s - x)
                    val focused = r == 2 && k == r + 2
                    drawRoundRect(if (focused) Color(0xFFEEF1F6) else Color.White.copy(alpha = 0.05f), Offset(x + 3 * s, y), Size(w - 6 * s, 60 * s), CornerRadius(12 * s))
                    x += w
                }
            }
            val nowX = tl + 47 * 8.656f * s
            drawRect(Color(0xFF5CA8F2), Offset(nowX, 43 * s), Size(2 * s, size.height - 55 * s))
        }
    }
}

private class PreviewMachine(initial: GuideUiState) : GuideStateMachine {
    override val ui: StateFlow<GuideUiState> = MutableStateFlow(initial)
    override val frame = GuideFrame()
    override val frameVersion: StateFlow<Long> = MutableStateFlow(0L)
    override fun dispatch(event: GuideEvent) = Unit
}

/** Keys pass through (Back leaves the preview). Follows the KeyRouter contract's sink shape. */
private object PreviewKeyRouter : KeyRouter {
    override fun onKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean = false
    override fun onFocusLost() = Unit
    override fun attach(machine: GuideStateMachine?, searchFieldIdle: () -> Boolean) = Unit
    override fun sinkModifier(requester: FocusRequester): Modifier =
        Modifier.focusRequester(requester).onPreviewKeyEvent(::onKey).focusable()
}

private class PreviewHost(private val controller: PlaybackController, state: GuideUiState) : ShellHost {
    override val machine: GuideStateMachine = PreviewMachine(state)
    override val keyRouter: KeyRouter = PreviewKeyRouter
    override val surfacePlayer: Player get() = controller.surfacePlayer
    override val video: StateFlow<VideoInfo> = MutableStateFlow(VideoInfo(width = 1920, height = 1080))
    override val playback: StateFlow<PlaybackState> get() = controller.state
    override val settings: StateFlow<GuideSettings> = MutableStateFlow(GuideSettings())
    override val lowEnd: StateFlow<Boolean> = MutableStateFlow(false)
    override val ambient = AmbientState().apply { targetHue = 20f }
    override val events: SharedFlow<ShellEvent> = MutableSharedFlow()
    override val imageLoader: ImageLoader? = null
    override val versionName: String = "preview"
    private var scale = 1f
    override fun setViewport(viewport: Viewport) = controller.setViewport(viewport)
    override fun onWindowSize(widthPx: Int, heightPx: Int) { scale = widthPx / 1920f }
    override fun focusedCellBounds(out: FloatArray): Boolean {
        out[0] = 800f * scale; out[1] = 685f * scale; out[2] = 1060f * scale; out[3] = 745f * scale
        return true
    }
}

private object PreviewStates {
    private val tunedId = ChannelIdentity(1, StableKey("preview:101"))
    private val tunedChannel = GuideChannel(
        rowId = 1, identity = tunedId, displayName = "Northline", number = 101, logoUrl = null, archiveDays = 7,
        epg = null, epgShiftMin = 0, epgHasData = false, identityHash = GuideChannel.hashOf(tunedId),
    )
    private val nowMin = (System.currentTimeMillis() / 60_000L).toInt()
    private val tunedNow = ProgrammeInfo(nowMin - 30, nowMin - 30, nowMin + 30, "Evening Report", null, null, "News", null, 0, 0)
    private val entries = listOf(
        GuideSourceEntry(GuideSource.Favourites(1), "Favourites", 12, SourceSection.FAVOURITES, null),
        GuideSourceEntry(GuideSource.Recent, "Recently watched", 8, SourceSection.RECENT, null),
        GuideSourceEntry(GuideSource.All(1), "All channels", 2000, SourceSection.ALL, null),
    ) + (1..20).map { GuideSourceEntry(GuideSource.Group(1, "g$it"), "Group $it", 40 + it, SourceSection.GROUP, null) }

    private val base = GuideUiState(
        mode = ScreenMode.GUIDE,
        zone = Zone.GRID,
        sourceLabel = "All channels",
        dayLabel = "Today",
        panel = PanelState(
            channelNumber = 103, channelName = "Meridian", genre = "Drama", onNow = true, catchupDays = 7,
            title = "Saltmarsh  S2·E4", timeLine = "19:00 – 20:00 · 1 h",
            description = "A storm cuts the island off from the mainland, and Ada finally reads the letter.",
            buttons = listOf(PanelButton.WATCH_NOW, PanelButton.FROM_START, PanelButton.MORE), noInfo = false,
        ),
        tuned = TunedState(identity = tunedId, rowId = 1, identityHash = tunedChannel.identityHash, phase = MiniPhase.PLAYING, still = true),
        tunedNow = tunedNow,
        tunedChannel = tunedChannel,
        footer = listOf(FooterHint("← → ↑ ↓", "Browse"), FooterHint("OK", "Watch"), FooterHint("Hold OK", "Options"), FooterHint("CH±", "Page")),
        sync = SyncChip("Updating guide…", failed = false),
    )

    fun of(name: String): GuideUiState = when (name) {
        "groups" -> base.copy(overlay = Overlay.Groups(listOf(GroupsShortcut.SEARCH, GroupsShortcut.SETTINGS), entries, focused = 4, current = 2, menu = null, menuFocused = 0))
        "menu" -> base.copy(overlay = Overlay.Menu(listOf(MenuItem.WATCH_FROM_START, MenuItem.ADD_FAVOURITE, MenuItem.HIDE_CHANNEL), focused = 0))
        "days" -> base.copy(overlay = Overlay.Days(listOf(DayItem(1, "Yesterday", true), DayItem(2, "Today", true), DayItem(3, "Tonight 20:00", true, true), DayItem(4, "Tomorrow", true), DayItem(5, "Fri 16 Oct", false)), focused = 1))
        "reminder" -> base.copy(reminder = ReminderCardState(ReminderAlert(1, tunedId, "Northline", "World Tonight", System.currentTimeMillis() + 60_000, late = false, autoSwitch = false), focused = 0, secondsLeft = 58, inputFromMs = 0))
        "exit" -> base.copy(overlay = Overlay.Exit(dialog = true, focused = 1))
        "error" -> base.copy(tuned = base.tuned.copy(phase = MiniPhase.ERROR, error = PlaybackErrorCode.CHANNEL_OFFLINE, still = false))
        "full" -> base.copy(mode = ScreenMode.FULL, groupCard = "Group 4")
        "panel" -> base.copy(zone = Zone.PANEL, panelFocused = 1)
        else -> base
    }
}
