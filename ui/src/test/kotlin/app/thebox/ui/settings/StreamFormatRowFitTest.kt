package app.thebox.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.settings.ChoiceOption
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsRow
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
 * (docs/decisions/2026-11-25-auto-format-retries-ts.md) Playlist › Stream format while Auto has learned HLS: every form
 * of the "will try TS again …" description is whole on the row's one line, at Default and at Largest text. Real fonts,
 * 1 dp = 1 design px (a 1,920-wide screen), the page pane's width.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StreamFormatRowFitTest {
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
    fun everyDescriptionFitsOneLineAtDefaultAndLargest() {
        val descriptions = listOf(
            res.getString(R.string.setup_format_learned_hls),
            res.getString(R.string.setup_format_learned_hls_retry_next),
            res.getString(R.string.setup_format_learned_hls_retry_today),
            res.getString(R.string.setup_format_learned_hls_retry_tomorrow),
            res.getString(R.string.setup_format_learned_hls_retry_days, 7),
        )
        val options = listOf(
            ChoiceOption("AUTO", res.getString(R.string.setup_format_auto), detail = res.getString(R.string.setup_format_auto_reset_detail), reselectable = true),
            ChoiceOption("TS", res.getString(R.string.setup_format_ts)),
            ChoiceOption("HLS", res.getString(R.string.setup_format_hls)),
        )
        val size = mutableStateOf(TextSize.DEFAULT)
        val row = mutableStateOf<SettingsRow?>(null)
        UiTextScale.running = size.value
        rule.setContent {
            UiTextScale.running = size.value
            BoxTheme {
                key(size.value) {
                    Box(Modifier.width((SettingsUi.PAGE_W - 2 * SettingsUi.PAGE_PAD_H).dp)) {
                        row.value?.let { SettingsRowView(it, focused = false, choice = null, moving = false) }
                    }
                }
            }
        }
        for (s in listOf(TextSize.DEFAULT, TextSize.LARGEST)) {
            size.value = s
            for (d in descriptions) {
                row.value = SettingsRow(
                    RowId("playlist.stream_format"), res.getString(R.string.setup_row_stream_format), description = d,
                    kind = RowKind.Choice(options, 0),
                )
                rule.waitForIdle()
                val l = layoutOf(d)
                assertEquals("$s: one line ($d)", 1, l.lineCount)
                assertFalse("$s: not cut ($d, ${l.size.width} of ${l.layoutInput.constraints.maxWidth} px)", l.hasVisualOverflow || l.isLineEllipsized(0))
            }
        }
    }
}
