package app.thebox.ui.full

import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.settings.TextSize
import app.thebox.core.settings.UiTextScale
import app.thebox.player.api.PlaybackNotice
import app.thebox.ui.R
import app.thebox.ui.root.ToastView
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
 * (docs/decisions/2026-11-25-tunneling-off-per-visit.md) The "tunneling off for this visit" toast is whole at Default
 * and at Largest text: one line at both, never cut. Real fonts, 1 dp = 1 design
 * px (a 1,920-wide screen).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TunnelingNoticeFitTest {
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
    fun theNoticeSaysItIsTemporaryAndFits() {
        val text = PlayerText.notice(res, PlaybackNotice.TunnelingDisabled)!!
        assertEquals(res.getString(R.string.player_notice_tunneling_off), text)
        val size = mutableStateOf(TextSize.DEFAULT)
        UiTextScale.running = size.value
        rule.setContent {
            UiTextScale.running = size.value
            BoxTheme { key(size.value) { ToastView(text) } }
        }
        for ((s, maxLines) in listOf(TextSize.DEFAULT to 1, TextSize.LARGEST to 1)) {
            size.value = s
            rule.waitForIdle()
            val l = layoutOf(text)
            assertFalse("$s: lines ${l.lineCount} width ${l.size.width} max ${l.layoutInput.constraints.maxWidth}", l.lineCount > maxLines)
            assertFalse("$s: not cut", l.hasVisualOverflow || (0 until l.lineCount).any { l.isLineEllipsized(it) })
        }
    }
}
