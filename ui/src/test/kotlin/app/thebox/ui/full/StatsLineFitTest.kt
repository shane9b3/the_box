package app.thebox.ui.full

import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.full.StatLabel
import app.thebox.core.full.StatsFacts
import app.thebox.core.full.TunnelingOff
import app.thebox.core.full.feed.StatsFormatter
import app.thebox.core.settings.TextSize
import app.thebox.core.settings.UiTextScale
import app.thebox.ui.R
import app.thebox.ui.theme.BoxTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * (Code review 2026-11-26, A8 and B3) The stats overlay's new values are whole at Default and at Largest text: the
 * Tunneling reasons and the Restarts row (per tune and per visit), at most two lines each, never cut. Real fonts,
 * 1 dp = 1 design px (a 1,920-wide screen).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatsLineFitTest {
    @get:Rule val rule = createComposeRule()
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @After fun reset() { UiTextScale.running = TextSize.DEFAULT }

    private fun layoutOf(text: String): TextLayoutResult {
        val node = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val out = ArrayList<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.single()
    }

    @Test
    fun theNewValuesAreWholeAtDefaultAndLargest() {
        val facts = listOf(
            StatsFacts(tunnelingOff = TunnelingOff.SETTING),
            StatsFacts(tunnelingOff = TunnelingOff.STALLED),
            StatsFacts(tunnelingOff = TunnelingOff.STALLED_BEFORE),
            StatsFacts(tunnelingOff = TunnelingOff.REPLAY),
            StatsFacts(tunnelingOff = TunnelingOff.NOT_SUPPORTED),
            // The longest realistic Restarts and HTTP values: two-digit counts on both sides.
            StatsFacts(
                httpStatus = 200, redirects = 1, playlistRenewals = 12, visitPlaylistRenewals = 48,
                restarts = 12, quietRestarts = 10, visitRestarts = 48, visitQuietRestarts = 40,
            ),
        )
        val lines = facts.flatMap { f -> StatsFormatter.lines(f).filter { it.label == StatLabel.TUNNELING || it.label == StatLabel.RESTARTS || it.label == StatLabel.HTTP } }
        assertEquals("Restarts", res.getString(R.string.player_stat_restarts))
        val size = mutableStateOf(TextSize.DEFAULT)
        UiTextScale.running = size.value
        rule.setContent {
            UiTextScale.running = size.value
            BoxTheme { key(size.value) { StatsOverlay(lines.distinct()) } }
        }
        for (s in listOf(TextSize.DEFAULT, TextSize.LARGEST)) {
            size.value = s
            rule.waitForIdle()
            for (line in lines.distinct()) {
                val l = layoutOf(line.value)
                assertFalse("$s '${line.value}': ${l.lineCount} lines", l.lineCount > 2)
                assertFalse("$s '${line.value}': not cut", l.hasVisualOverflow || (0 until l.lineCount).any { l.isLineEllipsized(it) })
            }
        }
    }
}
