package app.thebox.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.thebox.core.input.BindingVerdict
import app.thebox.core.input.BoxAction
import app.thebox.core.input.CaptureState
import app.thebox.core.input.CapturedKey
import app.thebox.core.input.KeyContext
import app.thebox.core.input.Keys
import app.thebox.core.input.Press
import app.thebox.core.input.ReservedReason
import app.thebox.ui.theme.BoxTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** docs/design/m5-setup-settings.md §6.2 `CaptureOverlayTest`: waiting, captured with device and verdict, test mode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class CaptureOverlayTest {
    @get:Rule val rule = createComposeRule()

    private val waiting = CaptureState(token = 1, action = BoxAction.AUDIO, press = Press.SHORT, startedAtMs = 0)

    @Test fun waitingAsksForTheButtonWithACountdown() {
        rule.mainClock.autoAdvance = false
        rule.setContent { BoxTheme { CaptureOverlay(waiting, focusedButton = 0, nowMs = { 0L }) } }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag(CAPTURE_OVERLAY_TAG).assertExists()
        rule.onNodeWithText("Press the button for Audio track now").assertExists()
        rule.onNodeWithText("0:10").assertExists()
        rule.onNodeWithText("Cancel").assertExists()
    }

    @Test fun capturedShowsKeyDeviceVerdictAndButtons() {
        val s = waiting.copy(
            captured = CapturedKey(Keys.PROG_RED, "KEYCODE_PROG_RED", "Amazon Fire TV Remote", 3, isVirtual = false),
            verdict = BindingVerdict.Replaces(BoxAction.START_OVER, setOf(KeyContext.PLAYER)),
        )
        rule.setContent { BoxTheme { CaptureOverlay(s, focusedButton = 0) } }
        rule.onNodeWithText("Red").assertExists()
        rule.onNodeWithText("from Amazon Fire TV Remote").assertExists()
        rule.onNodeWithText("This button now does Start over; saving moves it here.").assertExists()
        rule.onNodeWithText("Save").assertExists()
        rule.onNodeWithText("Try another").assertExists()
    }

    @Test fun unknownDeviceAndReserved() {
        val s = waiting.copy(
            captured = CapturedKey(Keys.DPAD_UP, "KEYCODE_DPAD_UP", null, -1, isVirtual = true),
            verdict = BindingVerdict.Reserved(ReservedReason.NAVIGATION),
        )
        rule.setContent { BoxTheme { CaptureOverlay(s, focusedButton = 1) } }
        rule.onNodeWithText("from an unknown device").assertExists()
        rule.onNodeWithText("The D-pad always navigates. Try another button.").assertExists()
    }

    @Test fun longPressWarnsAboutTheRepeat() {
        val s = waiting.copy(
            press = Press.LONG,
            captured = CapturedKey(Keys.CHANNEL_UP, "KEYCODE_CHANNEL_UP", "Remote", 3, isVirtual = false),
            verdict = BindingVerdict.LosesRepeat(BoxAction.ZAP_UP, BindingVerdict.Free),
        )
        rule.setContent { BoxTheme { CaptureOverlay(s, focusedButton = 0) } }
        rule.onNodeWithText("Holding Channel up will no longer repeat Channel up.").assertExists()
    }

    @Test fun testAKeyReportsTheGuideAndThePlayer() {
        val s = waiting.copy(captured = CapturedKey(Keys.PROG_GREEN, "KEYCODE_PROG_GREEN", "Remote", 3, false), verdict = BindingVerdict.Free)
        rule.setContent { BoxTheme { CaptureOverlay(s, focusedButton = 1, testReport = BoxAction.FAVOURITES to null) } }
        rule.onNodeWithText("In the guide: Favourites").assertExists()
        rule.onNodeWithText("While watching: nothing").assertExists()
    }
}
