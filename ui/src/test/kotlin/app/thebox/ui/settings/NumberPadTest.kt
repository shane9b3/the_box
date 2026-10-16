package app.thebox.ui.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.thebox.core.settings.NumberPadState
import app.thebox.core.settings.RowId
import app.thebox.ui.theme.BoxTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The numeric pad as drawn from `NumberPadState` (docs/design/m5-setup-settings.md §3.1, §6.2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class NumberPadTest {
    @get:Rule val rule = createComposeRule()

    private fun selected(on: Boolean) = SemanticsMatcher("selected=$on") { it.config.getOrNull(SemanticsProperties.Selected) == on }

    @Test
    fun drawsTheGridTheValueAndTheFocusedKey() {
        rule.setContent { BoxTheme { NumberPad(NumberPadState(RowId("x.days"), "Catch-up days", digits = "14", min = 0, max = 30, focusedKey = PAD_KEY_OK)) } }
        rule.onNodeWithTag(NUMBER_PAD_TAG).assertExists()
        rule.onNodeWithText("Catch-up days", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(NUMBER_PAD_VALUE_TAG, useUnmergedTree = true).assertTextEquals("14")
        for (k in 0 until 12) rule.onNodeWithTag(NUMBER_PAD_KEY_TAG + k).assert(selected(k == PAD_KEY_OK))
        rule.onNodeWithText("⌫", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("OK", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("0", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("9", useUnmergedTree = true).assertExists()
    }

    @Test
    fun defaultFocusIsTheFiveKeyAndTheErrorLineShows() {
        rule.setContent { BoxTheme { NumberPad(NumberPadState(RowId("x.n"), "Max connections", digits = "", min = 1, max = 10, error = "Between 1 and 10")) } }
        rule.onNodeWithTag(NUMBER_PAD_KEY_TAG + 4).assert(selected(true)) // focusedKey default 4 = "5"
        rule.onNodeWithText("Between 1 and 10", useUnmergedTree = true).assertExists()
    }
}
