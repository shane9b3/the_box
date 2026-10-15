package app.thebox.ui.full

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.PlayerUiState
import app.thebox.core.guide.GuideToast
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.ReminderAlert
import app.thebox.core.guide.ReminderCardState
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.TunedState
import app.thebox.core.guide.fakes.FakeGuideStateMachine
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.ui.reminder.REMINDER_CARD_TAG
import app.thebox.ui.root.AppRoot
import app.thebox.ui.root.ShellEvent
import app.thebox.ui.root.TOAST_TAG
import app.thebox.ui.shelltest.FakePlayerHost
import app.thebox.ui.shelltest.FakeShellHost
import app.thebox.ui.theme.BoxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every FULL overlay lands on its §3.9 rect (docs/design/m4-player-ux.md §5.4 `OverlayBoundsTest`), the picker sheet
 * ends above the quick bar, and toasts and the reminder card lift over the bottom bars (N8). 1920 × 1080 window, so
 * 1 design px = 1 px.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class PlayerOverlayBoundsTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ch = GuideFixtures.channel(1, archiveDays = 7)
    private val tuned = TunedState(identity = ch.identity, rowId = ch.rowId, identityHash = ch.identityHash, phase = MiniPhase.PLAYING)
    private val guide = FakeGuideStateMachine(GuideUiState(mode = ScreenMode.FULL, tuned = tuned, tunedChannel = ch))
    private val player = FakePlayerHost()
    private val host = FakeShellHost(guide, player = player)

    private fun render() {
        rule.setContent { BoxTheme { AppRoot(host, grid = { m -> Spacer(m) }) } }
        rule.waitForIdle()
    }

    private fun show(state: PlayerUiState) {
        player.machine.mutableUi.value = state
        rule.waitForIdle()
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test
    fun infoBarScrubBarAndTrack() {
        render()
        show(PlayerUiState(layer = PlayerLayer.INFO, info = PlayerOverlayStates.info(ch)))
        assertEquals(Rect(64f, 820f, 1856f, 1040f), bounds(INFO_BAR_TAG))
        assertEquals(Rect(0f, 700f, 1920f, 1080f), bounds(BOTTOM_SCRIM_TAG))
        show(PlayerUiState(layer = PlayerLayer.SCRUB, scrub = PlayerOverlayStates.scrub(ch)))
        assertEquals(Rect(64f, 820f, 1856f, 1040f), bounds(SCRUB_BAR_TAG))
        assertEquals("the track node: bubble 916 … labels 996", Rect(64f, 916f, 1856f, 996f), bounds(SCRUB_TRACK_TAG))
    }

    @Test
    fun quickBarPickerStatsListAndCard() {
        render()
        show(PlayerUiState(layer = PlayerLayer.QUICK_BAR, quick = PlayerOverlayStates.quick(ch)))
        val quick = bounds(QUICK_BAR_TAG)
        assertEquals(Rect(64f, 952f, 1856f, 1040f), quick)
        show(PlayerUiState(layer = PlayerLayer.PICKER, picker = PlayerOverlayStates.picker()))
        val picker = bounds(PICKER_SHEET_TAG)
        assertEquals(Rect(1376f, 48f, 1872f, 928f), picker)
        assertTrue("the sheet ends above the quick bar", picker.bottom <= quick.top)
        show(PlayerUiState(layer = PlayerLayer.CHANNELS, channels = PlayerOverlayStates.channels()))
        assertEquals(Rect(48f, 48f, 668f, 1032f), bounds(CHANNEL_LIST_TAG))
        show(PlayerUiState(stats = PlayerOverlayStates.stats))
        val stats = bounds(STATS_OVERLAY_TAG)
        assertEquals(64f, stats.left)
        assertEquals(56f, stats.top)
        assertEquals(600f, stats.width)
        show(PlayerUiState(layer = PlayerLayer.ERROR, error = PlayerOverlayStates.error(ch)))
        val card = bounds(PLAYER_ERROR_CARD_TAG)
        assertEquals(720f, card.width)
        assertEquals("centred", 960f, (card.left + card.right) / 2f)
        assertEquals(540f, (card.top + card.bottom) / 2f, 1f)
        rule.onNodeWithText("The server refused this stream").assertExists()
        rule.onNodeWithText("HTTP 403 · panel.example:8080 · play.forbidden").assertExists()
        rule.onNodeWithText("Try another user agent in playlist settings").assertExists()
        rule.onNodeWithText("Retry").assertExists()
        rule.onNodeWithText("Guide").assertExists()
    }

    @Test
    fun screenOffCoversTheWindowAndHidesTheVideo() {
        render()
        show(PlayerUiState(layer = PlayerLayer.SCREEN_OFF))
        assertEquals(Rect(0f, 0f, 1920f, 1080f), bounds(SCREEN_OFF_TAG))
        assertEquals(app.thebox.core.playback.Viewport.HIDDEN, host.viewports.last())
        show(PlayerUiState())
        assertEquals("waking restores the FULL viewport", app.thebox.core.playback.Viewport.FULL, host.viewports.last())
    }

    @Test
    fun toastsLiftOverTheBottomBars() {
        render()
        fun toastBottom(): Float {
            host.eventsFlow.tryEmit(ShellEvent.Toast(GuideToast.NO_FAVOURITES, null))
            rule.waitForIdle()
            return bounds(TOAST_TAG).bottom
        }
        assertEquals(1080f - 96f, toastBottom())
        show(PlayerUiState(layer = PlayerLayer.INFO, info = PlayerOverlayStates.info(ch, active = false)))
        assertEquals(1080f - 276f, bounds(TOAST_TAG).bottom)
        show(PlayerUiState(layer = PlayerLayer.QUICK_BAR, quick = PlayerOverlayStates.quick(ch)))
        assertEquals(1080f - 156f, bounds(TOAST_TAG).bottom)
        assertTrue("the toast clears the quick bar", bounds(TOAST_TAG).bottom <= bounds(QUICK_BAR_TAG).top)
    }

    @Test
    fun playerMessagesShowAsToasts() {
        render()
        player.eventsFlow.tryEmit(ShellEvent.Message("Reconnected"))
        rule.waitForIdle()
        rule.onNodeWithText("Reconnected").assertExists()
    }

    @Test
    fun reminderCardLiftsAndMovesLeftOfThePicker() {
        render()
        guide.mutableUi.value = guide.ui.value.copy(reminder = ReminderCardState(ReminderAlert(1, ch.identity, "Northline", "News", 0, late = true, autoSwitch = false), 0, 30, 0))
        rule.waitForIdle()
        assertEquals(1080f - 96f, bounds(REMINDER_CARD_TAG).bottom)
        assertEquals(1920f - 64f, bounds(REMINDER_CARD_TAG).right)
        show(PlayerUiState(layer = PlayerLayer.SCRUB, scrub = PlayerOverlayStates.scrub(ch)))
        assertEquals(1080f - 276f, bounds(REMINDER_CARD_TAG).bottom)
        show(PlayerUiState(layer = PlayerLayer.PICKER, picker = PlayerOverlayStates.picker()))
        assertEquals(1920f - 568f, bounds(REMINDER_CARD_TAG).right)
        assertTrue("left of the sheet", bounds(REMINDER_CARD_TAG).right <= bounds(PICKER_SHEET_TAG).left)
    }
}
