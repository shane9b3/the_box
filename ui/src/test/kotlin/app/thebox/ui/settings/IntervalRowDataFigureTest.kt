package app.thebox.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.settings.ChoiceOption
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.tree.SportsRows
import app.thebox.ui.R
import app.thebox.ui.theme.BoxTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * (CHL emulator pass, defect 4) Settings → Sports → "… score updates": the data-use figure ("About 2.9 MB an hour") leads
 * the detail, so the unfocused row's one ellipsised line always shows it, and the focused row's wrapped detail is whole in
 * its 6 lines. For the NHL, NFL and CHL rows, each of the three detail forms (unmeasured, measured, under 0.1 s). Real
 * fonts, 1 dp = 1 design px (a 1,920-wide screen), the page pane's width.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntervalRowDataFigureTest {
    @get:Rule val rule = createComposeRule()
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    private fun layoutOf(text: String): TextLayoutResult {
        val node = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val out = ArrayList<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.single()
    }

    /** The detail as `WpaPages.intervalRow` joins it (no slowed / fallback / faster line: the league line is last). */
    private fun details(group: String): List<Pair<String, String>> {
        val mb = "12" // two digits: the widest figure (one decimal under 10)
        val rec = if (group == "NHL") 20 else 5
        val tail = buildList {
            if (group == "NHL") add(res.getString(R.string.settings_sports_interval_nhl_floor))
            add(res.getString(R.string.settings_sports_interval_league, group))
        }
        return listOf(
            res.getString(R.string.settings_sports_interval_desc_unmeasured, rec, mb),
            res.getString(R.string.settings_sports_interval_desc, rec, "0.4", mb),
            res.getString(R.string.settings_sports_interval_desc_fast, rec, mb),
        ).map { (listOf(it) + tail).joinToString(" ") to res.getString(R.string.settings_sports_interval_desc_unmeasured, rec, mb).substringBefore(" while") }
    }

    @Test
    fun theDataFigureLeadsAndIsAlwaysVisible() {
        val row = mutableStateOf<Pair<SettingsRow, Boolean>?>(null)
        rule.setContent {
            BoxTheme {
                Box(Modifier.width((SettingsUi.PAGE_W - 2 * SettingsUi.PAGE_PAD_H).dp)) {
                    row.value?.let { (r, focused) -> SettingsRowView(r, focused = focused, choice = null, moving = false) }
                }
            }
        }
        for (group in listOf("NHL", "NFL", "CHL")) {
            val auto = if (group == "NHL") res.getString(R.string.settings_sports_interval_auto, 20) else res.getString(R.string.settings_sports_interval_auto_after, 5)
            for ((detail, figure) in details(group)) {
                assertTrue("$group: the figure leads ($detail)", detail.startsWith(figure) && figure.endsWith("MB an hour"))
                val r = SettingsRow(
                    SportsRows.INTERVAL, res.getString(R.string.settings_sports_interval, group), description = detail,
                    kind = RowKind.Choice(listOf(ChoiceOption("0", auto)), 0),
                )
                // Unfocused: one line, ellipsised; the figure (and the ellipsis after it) fits in that line's width.
                row.value = r to false
                rule.waitForIdle()
                val one = layoutOf(detail)
                val input = one.layoutInput
                val measurer = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection)
                val prefix = measurer.measure(
                    "$figure…", input.style, overflow = TextOverflow.Clip, softWrap = false, maxLines = 1,
                    constraints = Constraints(maxWidth = Constraints.Infinity),
                )
                assertTrue(
                    "$group: \"$figure…\" (${prefix.size.width} px) fits the unfocused line (${input.constraints.maxWidth} px)",
                    prefix.size.width <= input.constraints.maxWidth,
                )
                // Focused: the whole detail in its 6 lines.
                row.value = r to true
                rule.waitForIdle()
                val all = layoutOf(detail)
                assertFalse("$group: the focused detail is whole ($detail)", all.hasVisualOverflow)
                assertTrue(all.lineCount <= INTERVAL_DESC_LINES)
                for (i in 0 until all.lineCount) assertFalse(all.isLineEllipsized(i))
            }
        }
    }
}
