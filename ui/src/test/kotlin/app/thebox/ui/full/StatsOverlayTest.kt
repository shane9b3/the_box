package app.thebox.ui.full

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import app.thebox.core.full.StatLabel
import app.thebox.core.full.StatLine
import app.thebox.ui.theme.BoxTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** U-S12 (M5 code review 2026-10-16, B2): the stats overlay's audio line names "Always passthrough" and Back to Auto. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w960dp-h540dp-xhdpi")
class StatsOverlayTest {
    @get:Rule val rule = createComposeRule()
    private val lines = listOf(StatLine(StatLabel.VIDEO, "H.264 High"), StatLine(StatLabel.AUDIO, "E-AC-3 5.1"))

    @Test
    fun audioLineSaysAlwaysPassthroughWhileForced() {
        rule.setContent { BoxTheme { StatsOverlay(lines, audioOutputForced = true) } }
        rule.onAllNodesWithText("Always passthrough", substring = true).assertCountEquals(1)
        rule.onAllNodesWithText("Back to Auto", substring = true).assertCountEquals(1)
    }

    @Test
    fun audioLineIsPlainOtherwise() {
        rule.setContent { BoxTheme { StatsOverlay(lines) } }
        rule.onAllNodesWithText("Always passthrough", substring = true).assertCountEquals(0)
    }
}
