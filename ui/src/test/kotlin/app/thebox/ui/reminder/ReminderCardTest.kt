package app.thebox.ui.reminder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.thebox.core.guide.ReminderAlert
import app.thebox.core.guide.ReminderCardState
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.ui.theme.BoxTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The modal card's input guard as the user sees it (docs/design/m3-guide.md §3.11.3, S2): 600 ms of disabled buttons. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class ReminderCardTest {
    @get:Rule val rule = createComposeRule()

    private val t0 = 1_800_000_000_000L
    private var now = t0

    private fun state(late: Boolean = false, startAt: Long = t0 + 60_000) = ReminderCardState(
        alert = ReminderAlert(7, GuideFixtures.identity(1), "Northline", "World Tonight", startAt, late = late, autoSwitch = false),
        focused = 0, secondsLeft = 60, inputFromMs = t0 + 600,
    )

    @Test
    fun buttonsAreDisabledDuringTheGuardThenEnabled() {
        var card by mutableStateOf(state())
        rule.setContent { BoxTheme { ReminderCard(card, clock = { now }) } }
        rule.onNodeWithTag(REMINDER_SWITCH_TAG).assertIsNotEnabled()
        rule.onNodeWithTag(REMINDER_DISMISS_TAG).assertIsNotEnabled()
        rule.onNodeWithText("World Tonight starts in 1 min on Northline").assertExists()

        now = t0 + 599
        card = card.copy(secondsLeft = 59) // the machine's countdown tick recomposes the card
        rule.onNodeWithTag(REMINDER_SWITCH_TAG).assertIsNotEnabled()

        now = t0 + 650
        card = card.copy(secondsLeft = 58)
        rule.onNodeWithTag(REMINDER_SWITCH_TAG).assertIsEnabled()
        rule.onNodeWithTag(REMINDER_DISMISS_TAG).assertIsEnabled()
    }

    @Test
    fun lateCardSaysHowLongAgo() {
        now = t0 + 5 * 60_000
        rule.setContent { BoxTheme { ReminderCard(state(late = true, startAt = t0), clock = { now }) } }
        rule.onNodeWithText("World Tonight started 5 min ago on Northline").assertExists()
        rule.onNodeWithTag(REMINDER_SWITCH_TAG).assertIsEnabled()
    }
}
