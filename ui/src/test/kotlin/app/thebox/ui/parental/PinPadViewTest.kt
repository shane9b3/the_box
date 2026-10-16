package app.thebox.ui.parental

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.SourceSection
import app.thebox.core.parental.PinMessage
import app.thebox.core.parental.PinOwner
import app.thebox.core.parental.PinPadMode
import app.thebox.core.parental.PinPromptState
import app.thebox.core.parental.PinPurpose
import app.thebox.ui.theme.BoxTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** docs/design/m5-setup-settings.md §6.2 `PinPadViewTest`: no digit ever in the semantics tree; titles, messages, cards. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class PinPadViewTest {
    @get:Rule val rule = createComposeRule()

    private val entry = GuideSourceEntry(GuideSource.Group(1, "adult"), "Adult", 3, SourceSection.GROUP, null, locked = true)
    private fun state(filled: Int, mode: PinPadMode = PinPadMode.VERIFY, message: String? = null, focused: Int = 4, until: Long? = null) =
        PinPromptState(PinPurpose.OpenSource(entry, PinOwner.GUIDE), mode, filled, focusedKey = focused, wrong = message != null, lockedUntilMs = until, message = message)

    /** Every text and content description in the tree, unmerged. */
    private fun allStrings(): List<String> {
        val out = ArrayList<String>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            n.config.getOrNull(SemanticsProperties.EditableText)?.let { out += it.text }
            n.children.forEach(::walk)
        }
        walk(rule.onNode(isRoot()).fetchSemanticsNode())
        return out
    }

    private fun assertNoDigit() {
        val strings = allStrings()
        val counts = Regex("^[0-4] of 4 digits entered$")
        for (s in strings) {
            if (counts.matches(s)) continue
            assertTrue("a digit in the semantics tree: \"$s\" in $strings", s.none { it.isDigit() })
        }
    }

    @Test fun noDigitEverInTheSemanticsTree() {
        var s by mutableStateOf(state(0))
        rule.setContent { BoxTheme { PinPadView(s, nowMs = { 0L }) } }
        for (n in 0..4) {
            s = state(n, focused = n * 2)
            rule.waitForIdle()
            assertNoDigit()
            rule.onNodeWithTag(PIN_DOTS_TAG).assertExists()
        }
        rule.onNodeWithText("Enter your PIN").assertExists()
    }

    @Test fun createTitlesAndMessages() {
        var s by mutableStateOf(state(0, PinPadMode.CREATE_FIRST))
        rule.setContent { BoxTheme { PinPadView(s, nowMs = { 0L }) } }
        rule.onNodeWithText("Choose a 4-digit PIN").assertExists()
        s = state(2, PinPadMode.CREATE_CONFIRM)
        rule.onNodeWithText("Enter it again").assertExists()
        s = state(0, PinPadMode.CREATE_FIRST, PinMessage.MISMATCH)
        rule.onNodeWithText("The PINs don't match. Try again.").assertExists()
        s = state(0, message = PinMessage.wrong(2))
        rule.onNodeWithText("Wrong PIN. 2 tries before a pause.").assertExists()
        s = state(0, message = PinMessage.UNAVAILABLE)
        rule.onNodeWithText("The PIN can't be checked right now: secure storage did not answer. Try again.").assertExists()
    }

    @Test fun lockoutShowsTheCountdown() {
        rule.mainClock.autoAdvance = false
        rule.setContent { BoxTheme { PinPadView(state(0, message = PinMessage.LOCKED_OUT, until = 30_000), nowMs = { 0L }) } }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("Too many wrong PINs. Try again in 0:30.").assertExists()
    }

    @Test fun notProtectedCardAndEasyConfirm() {
        var s by mutableStateOf(state(0).copy(message = PinMessage.NOT_PROTECTED, focusedKey = 0))
        rule.setContent { BoxTheme { PinPadView(s, nowMs = { 0L }) } }
        rule.onNodeWithTag(PIN_CARD_TAG).assertExists()
        rule.onNodeWithText("This group is not protected.").assertExists()
        rule.onNodeWithText("Set a PIN").assertExists()
        rule.onNodeWithText("Open anyway").assertExists()
        s = state(0, PinPadMode.CREATE_FIRST).copy(message = PinMessage.EASY, focusedKey = 1)
        rule.onNodeWithText("Easy to guess. Use it anyway?").assertExists()
        rule.onNodeWithText("Choose another").assertExists()
    }
}
