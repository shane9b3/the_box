package app.thebox.ui.full

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import app.thebox.core.full.PickerChoice
import app.thebox.core.full.PickerKind
import app.thebox.core.full.PickerState
import app.thebox.core.full.state.selectable
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.League
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.SportsFixtures
import app.thebox.ui.sports.SportsText
import app.thebox.ui.theme.BoxTheme
import app.thebox.ui.theme.DesignTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * (CHL emulator pass, defect 1) The quick-bar score sheet's failure row: the followed game's refused feed ("OHL scores
 * stopped letting this app in. …") and every §5.4 day failure, for every league, is laid out whole (no ellipsis, no
 * overflow) at the Standard TV layout, focused and not; the row is two rows tall, the other rows keep their 64 px, and every
 * shown row stays inside the 880 px sheet wherever the focus is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScoreSheetFailedRowTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val sheet = mutableStateOf<PickerState?>(null)

    private fun show(state: PickerState) {
        if (sheet.value == null) {
            sheet.value = state
            rule.setContent { BoxTheme { Box(Modifier.fillMaxSize().background(DesignTokens.Colors.Canvas)) { sheet.value?.let { PickerSheet(it, clock24 = false) } } } }
        } else {
            sheet.value = state
        }
        rule.waitForIdle()
    }

    private fun layout(): TextLayoutResult {
        val node = rule.onNodeWithTag(PICKER_FAILED_TAG, useUnmergedTree = true).fetchSemanticsNode()
        val results = ArrayList<TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    /** The §6 rows of a followed CHL game whose feed refused the app, with four other games below the Today divider. */
    private fun refusedSheet(league: League, focused: Int): PickerState {
        val followed = SportsFixtures.chlGame(league = league, id = "${league.ordinal}9")
        val others = (1..4).map { SportsFixtures.chlGame(league = league, id = "${league.ordinal}$it") }
        val choices = listOf(
            PickerChoice.ScoreGame(followed, followed = true), PickerChoice.ScoreFailed(FailKind.REFUSED, league),
            PickerChoice.ScoreLeagueRow(league), PickerChoice.ScoreChlRow(league), PickerChoice.ScoreCornerRow(ScoreCorner.TOP_RIGHT),
            PickerChoice.ScoreDelayRow(0), PickerChoice.ScoreStyleRow(NhlBugStyle.STACKED), PickerChoice.ScoreStop,
            PickerChoice.ScoreTodayDivider,
        ) + others.map { PickerChoice.ScoreGame(it, followed = false) }
        return PickerState(PickerKind.SCORE_BUG, choices, current = 0, focused = focused, remember = null, fromQuickBar = true)
    }

    private fun assertWhole(expected: String, what: String) {
        rule.onNodeWithTag(PICKER_FAILED_TAG, useUnmergedTree = true).assertTextEquals(expected)
        val l = layout()
        assertFalse("$what: no visual overflow", l.hasVisualOverflow)
        for (line in 0 until l.lineCount) assertFalse("$what: line $line not ellipsised", l.isLineEllipsized(line))
        assertEquals("$what: every character laid out", expected.length, l.getLineEnd(l.lineCount - 1))
        assertTrue("$what: wraps to more than one line", l.lineCount > 1)
    }

    private fun assertInsideSheet(what: String) {
        val box = rule.onNodeWithTag(PICKER_SHEET_TAG).fetchSemanticsNode().boundsInRoot
        val rows = rule.onAllNodesWithTag(PICKER_ROW_TAG).fetchSemanticsNodes()
        assertTrue(what, rows.isNotEmpty())
        val scale = box.width / PlayerUi.PICKER_W
        for (r in rows) {
            assertTrue("$what: row inside the sheet (${r.boundsInRoot} in $box)", r.boundsInRoot.bottom <= box.bottom + 0.5f)
            val h = r.boundsInRoot.height / scale
            assertTrue("$what: a row is 64 or the 132 failure row, was $h", kotlin.math.abs(h - PlayerUi.PICKER_ROW_H) < 1f || kotlin.math.abs(h - FAILED_ROW_H) < 1f)
        }
        val failed = rule.onAllNodesWithTag(PICKER_FAILED_TAG, useUnmergedTree = true).fetchSemanticsNodes()
        for (f in failed) assertTrue("$what: failure text inside the sheet", f.boundsInRoot.bottom <= box.bottom + 0.5f)
    }

    @Test
    fun theRefusedRowUnderTheFollowedGameIsReadableWholeFocusedAndNot() {
        val res = rule.activity.resources
        for (league in listOf(League.OHL, League.WHL, League.QMJHL)) {
            val text = SportsText.failure(res, FailKind.REFUSED, league)
            for (focused in listOf(0, 1)) { // the followed game focused (the sheet opens there), then the failure row itself
                show(refusedSheet(league, focused))
                assertWhole(text, "$league refused, focus $focused")
                assertInsideSheet("$league refused, focus $focused")
            }
        }
    }

    @Test
    fun everyRowStaysInsideTheSheetWhereverTheFocusIs() {
        val n = refusedSheet(League.QMJHL, 0).choices.size
        for (focused in 0 until n) {
            val state = refusedSheet(League.QMJHL, focused)
            if (!state.choices[focused].selectable) continue
            show(state)
            assertInsideSheet("focus $focused")
        }
    }

    @Test
    fun everyDayFailureReadsWhole() {
        val res = rule.activity.resources
        for (league in listOf(League.NHL, League.NFL, League.OHL, League.WHL, League.QMJHL)) {
            for (kind in FailKind.entries) {
                val choices = listOf(
                    PickerChoice.ScoreLeagueRow(league), PickerChoice.ScoreCornerRow(ScoreCorner.TOP_RIGHT), PickerChoice.ScoreDelayRow(0),
                    PickerChoice.ScoreTodayDivider, PickerChoice.ScoreFailed(kind, league),
                )
                for (focused in listOf(0, 4)) {
                    show(PickerState(PickerKind.SCORE_BUG, choices, current = -1, focused = focused, remember = null, fromQuickBar = true))
                    assertWhole(SportsText.failure(res, kind, league), "$league $kind focus $focused")
                    assertInsideSheet("$league $kind focus $focused")
                }
            }
        }
    }

    @Test
    fun theWindowCountsTheFailureRowTwiceAndAlwaysHoldsTheFocus() {
        // With every row one slot the window is the old focus-centred one.
        for (rows in listOf(5, 11, 20)) for (f in 0 until rows) {
            val old = (f - 11 / 2).coerceIn(0, (rows - 11).coerceAtLeast(0)).let { it until minOf(rows, it + 11) }
            assertEquals("rows $rows focus $f", old, pickerWindow(rows, f, 11) { 1 })
        }
        // A two-slot row: never more than 11 slots, the focus always shown.
        val cost = { i: Int -> if (i == 1) FAILED_SLOTS else 1 }
        for (rows in listOf(5, 11, 12, 14, 20)) for (f in 0 until rows) {
            val w = pickerWindow(rows, f, 11, cost)
            assertTrue("rows $rows focus $f in $w", f in w)
            assertTrue("rows $rows focus $f within budget", w.sumOf(cost) <= 11)
            if (rows >= 12) assertTrue("rows $rows focus $f fills the sheet", w.sumOf(cost) >= 10)
        }
    }
}
