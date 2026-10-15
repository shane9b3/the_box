package app.thebox.ui.full

import app.thebox.core.full.ChannelListState
import app.thebox.core.full.ErrorButton
import app.thebox.core.full.ErrorCardState
import app.thebox.core.full.InfoBarState
import app.thebox.core.full.InfoGlyphs
import app.thebox.core.full.ListColumn
import app.thebox.core.full.PickerChoice
import app.thebox.core.full.PickerKind
import app.thebox.core.full.PickerState
import app.thebox.core.full.PillKind
import app.thebox.core.full.PillState
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.PlayerUiState
import app.thebox.core.full.QuickBarState
import app.thebox.core.full.QuickItem
import app.thebox.core.full.ScrubFocus
import app.thebox.core.full.ScrubState
import app.thebox.core.full.StatLabel
import app.thebox.core.full.StatLine
import app.thebox.core.full.fakes.PlayerFixtures
import app.thebox.core.guide.GuideChannel
import app.thebox.core.playback.AspectMode
import app.thebox.core.playback.PlaybackErrorCode

/** `PlayerUiState` builders for the shell tests (WP-D drives the UI state directly, §5.4). */
object PlayerOverlayStates {
    const val NOW_MS = 1_760_000_000_000L
    val NOW_MIN = (NOW_MS / 60_000L).toInt()

    fun info(ch: GuideChannel, active: Boolean = true): InfoBarState {
        val ps = PlayerFixtures.programmes(ch, NOW_MIN - 30, 4, 60)
        return InfoBarState(
            active = active, channel = ch, browsing = false, programmes = ps, focused = 0, nowIndex = 0,
            pill = PillState(PillKind.LIVE), glyphs = InfoGlyphs(catchupDays = 7, resolution = "1080p50", audioLayout = "5.1", subtitlesAvailable = true), loading = false,
        )
    }

    fun scrub(ch: GuideChannel, focus: ScrubFocus = ScrubFocus.BAR, preview: Long? = null, pill: PillState = PillState(PillKind.BEHIND, behindMs = 1_070_000, atMs = NOW_MS)): ScrubState {
        val p = PlayerFixtures.programmes(ch, NOW_MIN - 60, 3, 90)
        return ScrubState(
            focused = true, focus = focus, programme = p[0], barFromMs = p[0].startMin * 60_000L, barToMs = p[0].endMin * 60_000L,
            archiveFromMs = p[0].startMin * 60_000L + 600_000, liveEdgeMs = NOW_MS, playheadMs = NOW_MS - 1_070_000, atMs = NOW_MS,
            advancing = true, previewMs = preview, prev = null, next = p[1], chipFocused = if (focus == ScrubFocus.CHIPS) 1 else -1,
            displayShiftMs = 0, pill = pill, seeking = false, atLive = false,
        )
    }

    fun quick(ch: GuideChannel) = QuickBarState(
        items = QuickItem.entries.filter { it != QuickItem.FAVOURITE_REMOVE && it != QuickItem.JUMP_TO_LIVE },
        focused = 0, statsOn = false, audio = PlayerFixtures.trackMenu().audio[0], subtitle = null, aspect = AspectMode.FIT, decoder = null, previous = ch,
    )

    fun picker() = PickerState(
        PickerKind.ASPECT, AspectMode.entries.map { PickerChoice.Aspect(it) }, current = 0, focused = 1, remember = true, fromQuickBar = true,
    )

    fun error(ch: GuideChannel, code: PlaybackErrorCode = PlaybackErrorCode.FORBIDDEN) = ErrorCardState(
        error = PlayerFixtures.error(code), channel = ch, buttons = listOf(ErrorButton.RETRY, ErrorButton.GUIDE), focused = 0,
        textStep = app.thebox.core.playback.NextStep.CHANGE_USER_AGENT, archiveFailed = false,
    )

    fun channels() = ChannelListState(
        column = ListColumn.CHANNELS, groups = emptyList(), groupFocused = 0, groupCurrent = 0, source = null, lineupVersion = 1, size = 0,
        focused = 0, top = 0, previous = null, tunedRowId = -1, rowsVersion = 0, nowVersion = 0, openedByBack = false, menu = null, menuFocused = 0,
    )

    val stats = listOf(StatLine(StatLabel.VIDEO, "H.264 High"), StatLine(StatLabel.SERVER, "panel.example · server 2 of 3"))

    /** Every overlay at once (layout tests only; the machine never opens them together). */
    fun all(ch: GuideChannel) = PlayerUiState(
        layer = PlayerLayer.QUICK_BAR, info = info(ch), quick = quick(ch), picker = picker(), error = error(ch), stats = stats,
    )
}
