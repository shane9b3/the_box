package app.thebox.core.full.state

import app.thebox.core.full.PickerChoice
import app.thebox.core.full.QuickItem
import app.thebox.core.input.BoxAction.DOWN
import app.thebox.core.input.BoxAction.RIGHT
import app.thebox.core.input.BoxAction.SELECT
import app.thebox.core.input.BoxAction.UP
import app.thebox.core.sports.CHL_LEAGUES
import app.thebox.core.sports.DayState
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.League
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.ScoreFacts
import app.thebox.core.sports.SportsFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** (CHL WP-2, docs/design/m6-chl-leagues.md §1.6 #31-34, §6.3) The score sheet's League, CHL league and Style rows. */
class ChlScoreSheetTest {
    private fun openSheet(w: PlayerWorld) {
        w.press(UP)
        repeat(QuickItem.entries.size) { if (w.ui.quick!!.let { it.items[it.focused] } != QuickItem.SCORE_BUG) w.press(RIGHT) }
        w.press(SELECT)
    }

    private fun focusOn(w: PlayerWorld, row: (PickerChoice) -> Boolean) {
        repeat(12) { if (!w.ui.picker!!.let { row(it.choices[it.focused]) }) w.press(DOWN) }
        assertTrue(w.ui.picker!!.let { row(it.choices[it.focused]) }, "focus reached the row")
    }

    private fun leagueCalls(w: PlayerWorld, name: String) = w.fx.calls.filter { it.name == name }.map { it.args[0] }

    @Test fun theLeagueRowStepsNhlNflChlAndBackThroughTheRememberedSubLeague() {
        for (remembered in listOf<League?>(null, League.OHL, League.WHL, League.QMJHL)) {
            val w = PlayerWorld()
            w.player.scores(ScoreFacts(league = League.NHL, chlLeague = remembered))
            openSheet(w)
            focusOn(w) { it is PickerChoice.ScoreLeagueRow }
            w.press(SELECT)
            assertEquals(listOf<Any?>(League.NFL), leagueCalls(w, "setScoreLeague"))
            assertTrue(w.ui.picker!!.let { it.choices[it.focused] } is PickerChoice.ScoreLeagueRow, "focus stays on the League row")
            w.player.scores(ScoreFacts(league = League.NFL, chlLeague = remembered))
            focusOn(w) { it is PickerChoice.ScoreLeagueRow }
            w.press(SELECT)
            val chl = remembered ?: League.OHL
            assertEquals(listOf<Any?>(League.NFL, chl), leagueCalls(w, "setScoreLeague"), "NFL -> CHL steps to $chl")
            assertTrue(w.ui.picker!!.let { it.choices[it.focused] } is PickerChoice.ScoreLeagueRow, "focus stays after NFL -> CHL")
            assertEquals(PickerChoice.ScoreChlRow(chl), w.ui.picker!!.choices.single { it is PickerChoice.ScoreChlRow }, "the CHL row shows $chl at once")
            w.player.scores(ScoreFacts(league = chl, chlLeague = chl))
            focusOn(w) { it is PickerChoice.ScoreLeagueRow }
            w.press(SELECT)
            assertEquals(listOf<Any?>(League.NFL, chl, League.NHL), leagueCalls(w, "setScoreLeague"), "CHL -> NHL")
            assertTrue(w.ui.picker!!.choices.none { it is PickerChoice.ScoreChlRow })
            assertTrue(w.ui.picker!!.let { it.choices[it.focused] } is PickerChoice.ScoreLeagueRow, "focus stays after CHL -> NHL")
        }
    }

    @Test fun theChlRowFollowsTheLeagueRowOnlyForAChlLeagueAndCyclesTheSubLeagues() {
        for (league in CHL_LEAGUES) {
            val w = PlayerWorld()
            w.player.scores(ScoreFacts(league = league, chlLeague = league))
            openSheet(w)
            val names = w.ui.picker!!.choices.map { it::class.simpleName }
            assertEquals(listOf("ScoreLeagueRow", "ScoreChlRow", "ScoreCornerRow"), names.take(3), "$league")
            focusOn(w) { it is PickerChoice.ScoreChlRow }
        }
        for (league in listOf(League.NHL, League.NFL)) {
            val w = PlayerWorld()
            w.player.scores(ScoreFacts(league = league))
            openSheet(w)
            assertTrue(w.ui.picker!!.choices.none { it is PickerChoice.ScoreChlRow }, "$league has no CHL row")
        }
        val w = PlayerWorld()
        w.player.scores(ScoreFacts(league = League.OHL, chlLeague = League.OHL))
        openSheet(w)
        val order = listOf(League.WHL, League.QMJHL, League.OHL)
        for ((i, next) in order.withIndex()) {
            focusOn(w) { it is PickerChoice.ScoreChlRow }
            w.press(SELECT)
            assertEquals(order.take(i + 1), leagueCalls(w, "setScoreChlLeague"))
            val q = w.ui.picker!!
            assertEquals(PickerChoice.ScoreChlRow(next), q.choices[q.focused], "focus stays on the CHL row")
            assertTrue(q.choices.last() is PickerChoice.ScoreLoading, "the board reloads under a Loading row")
            w.player.scores(ScoreFacts(league = next, chlLeague = next, day = DayState.Loading))
        }
    }

    @Test fun theStyleRowShowsForAnyHockeyLeagueAndNotForTheNfl() {
        for (league in CHL_LEAGUES + League.NHL) {
            val w = PlayerWorld()
            w.player.scores(ScoreFacts(style = NhlBugStyle.PILL, league = league))
            openSheet(w)
            assertEquals(1, w.ui.picker!!.choices.count { it is PickerChoice.ScoreStyleRow }, "$league browsing")
            // A CHL follow while browsing the NFL list keeps the row; an NFL follow while browsing a CHL list hides it.
            val chl = SportsFixtures.chlGame(league = league.takeIf { it != League.NHL } ?: League.OHL)
            w.player.scores(
                ScoreFacts(
                    style = NhlBugStyle.PILL, league = League.NFL,
                    followed = SportsFixtures.liveView(chl), game = SportsFixtures.followedOf(chl),
                ),
            )
            assertEquals(1, w.ui.picker!!.choices.count { it is PickerChoice.ScoreStyleRow }, "$league followed while browsing the NFL")
            val nfl = SportsFixtures.nflGame()
            w.player.scores(
                ScoreFacts(
                    style = NhlBugStyle.PILL, league = league,
                    followed = SportsFixtures.nflView(game = nfl), game = SportsFixtures.followedOf(nfl),
                ),
            )
            assertTrue(w.ui.picker!!.choices.none { it is PickerChoice.ScoreStyleRow }, "$league: an NFL follow hides it")
        }
    }

    /** (CHL integration, WP-3 review L10) A refused follow: its league's reason row right under the followed game. */
    @Test fun aRefusedFollowShowsItsReasonUnderTheFollowedGame() {
        val ohl = SportsFixtures.chlGame()
        val followed = SportsFixtures.followedOf(ohl)
        val board = DayState.Board(SportsFixtures.board(ohl, dayKey = "2026-11-13", league = League.WHL), 0L, failing = false)
        val w = PlayerWorld()
        w.player.scores(ScoreFacts(game = followed, day = board, league = League.WHL, chlLeague = League.WHL, followFailure = FailKind.REFUSED))
        openSheet(w)
        val rows = w.ui.picker!!.choices
        assertTrue(rows[0] is PickerChoice.ScoreGame && (rows[0] as PickerChoice.ScoreGame).followed)
        assertEquals(PickerChoice.ScoreFailed(FailKind.REFUSED, League.OHL), rows[1], "the followed league's words, not the shown one's")
        assertTrue(rows[2] is PickerChoice.ScoreLeagueRow)
        assertTrue(w.ui.picker!!.let { it.choices[it.focused] } is PickerChoice.ScoreGame, "focus still lands on the followed game")
        // An answer clears it; another kind never adds the row.
        for (kind in listOf<FailKind?>(null, FailKind.TIMEOUT, FailKind.HTTP_5XX, FailKind.BAD_BODY)) {
            w.player.scores(ScoreFacts(game = followed, day = board, league = League.WHL, chlLeague = League.WHL, followFailure = kind))
            assertTrue(w.ui.picker!!.choices.none { it is PickerChoice.ScoreFailed }, "$kind")
        }
        // Not twice when the day row already says it for the followed league.
        val both = PlayerWorld()
        both.player.scores(ScoreFacts(game = followed, day = DayState.Failed(FailKind.REFUSED), league = League.OHL, chlLeague = League.OHL, followFailure = FailKind.REFUSED))
        openSheet(both)
        assertEquals(listOf(PickerChoice.ScoreFailed(FailKind.REFUSED, League.OHL)), both.ui.picker!!.choices.filterIsInstance<PickerChoice.ScoreFailed>())
        assertTrue(both.ui.picker!!.choices[1] is PickerChoice.ScoreLeagueRow, "the day row stays at the bottom")
        // Nothing followed: no row, whatever the flow says.
        val none = PlayerWorld()
        none.player.scores(ScoreFacts(day = board, league = League.WHL, followFailure = FailKind.REFUSED))
        openSheet(none)
        assertTrue(none.ui.picker!!.choices.none { it is PickerChoice.ScoreFailed })
    }
}
