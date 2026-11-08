package app.thebox.core.full.state

import app.thebox.core.full.PickerChoice
import app.thebox.core.full.QuickItem
import app.thebox.core.input.BoxAction.DOWN
import app.thebox.core.input.BoxAction.RIGHT
import app.thebox.core.input.BoxAction.SELECT
import app.thebox.core.input.BoxAction.UP
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.DayState
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.League
import app.thebox.core.sports.ScoreFacts
import app.thebox.core.sports.SportsFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** (NFL WP-5, docs/design/m6-nfl.md §7.3, S4) The score sheet's week divider: an NFL week board never reads "Today" or a raw key. */
class NflScoreDividerTest {
    private fun openSheet(w: PlayerWorld) {
        w.press(UP)
        repeat(QuickItem.entries.size) { if (w.ui.quick!!.let { it.items[it.focused] } != QuickItem.SCORE_BUG) w.press(RIGHT) }
        w.press(SELECT)
    }

    @Test fun aWeekBoardGetsAWeekDividerAndItsGamesFollow() {
        val w = PlayerWorld()
        val g = SportsFixtures.nflGame()
        w.player.scores(ScoreFacts(day = DayState.Board(DayBoard("w2026-2-9", "w2026-2-8", "w2026-2-10", listOf(g)), 0L, false)))
        openSheet(w)
        val choices = w.ui.picker!!.choices
        assertEquals(PickerChoice.ScoreDayDivider("w2026-2-9", current = true), choices.first { !it.selectable })
        assertTrue(PickerChoice.ScoreTodayDivider !in choices)
        assertEquals("401899001", (choices.last() as PickerChoice.ScoreGame).game.id.id)
    }

    @Test fun theLeagueRowIsSecondAndToggles() {
        val w = PlayerWorld()
        val g = SportsFixtures.game(id = "H1")
        w.player.scores(ScoreFacts(league = League.NHL, followed = SportsFixtures.liveView(g), game = SportsFixtures.followedOf(g), day = DayState.Board(SportsFixtures.board(g), 0L, false)))
        openSheet(w)
        val p = w.ui.picker!!
        assertEquals(PickerChoice.ScoreLeagueRow(League.NHL), p.choices[1], "after the followed game")
        w.press(DOWN) // the League row
        w.press(SELECT)
        assertEquals(listOf<Any?>(League.NFL), w.fx.calls.filter { it.name == "setScoreLeague" }.map { it.args[0] })
        val q = w.ui.picker!!
        assertEquals(PickerChoice.ScoreLeagueRow(League.NFL), q.choices[q.focused], "focus stays on the League row")
        assertTrue(q.choices.last() is PickerChoice.ScoreLoading, "the board reloads under a Loading row")
        assertEquals(PickerChoice.ScoreDayDivider("", current = true), q.choices.first { !it.selectable })
        // the host's confirmation keeps the row
        w.player.scores(ScoreFacts(league = League.NFL, day = DayState.Failed(FailKind.TIMEOUT)))
        assertEquals(PickerChoice.ScoreFailed(FailKind.TIMEOUT, League.NFL), w.ui.picker!!.choices.last())
    }

    @Test fun withoutAReportedLeagueThereIsNoLeagueRow() {
        val w = PlayerWorld()
        w.player.scores(ScoreFacts())
        openSheet(w)
        assertTrue(w.ui.picker!!.choices.none { it is PickerChoice.ScoreLeagueRow })
    }

    @Test fun theNhlsTodayBoardStillSaysToday() {
        val w = PlayerWorld()
        w.player.scores(ScoreFacts(day = DayState.Board(SportsFixtures.board(SportsFixtures.game(id = "H1")), 0L, false)))
        openSheet(w)
        assertTrue(PickerChoice.ScoreTodayDivider in w.ui.picker!!.choices)
    }
}
