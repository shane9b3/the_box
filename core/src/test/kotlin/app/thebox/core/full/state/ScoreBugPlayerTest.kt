package app.thebox.core.full.state

import app.thebox.core.full.PickerChoice
import app.thebox.core.full.PickerKind
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.QuickItem
import app.thebox.core.input.BoxAction.BACK
import app.thebox.core.input.BoxAction.DOWN
import app.thebox.core.input.BoxAction.RIGHT
import app.thebox.core.input.BoxAction.SELECT
import app.thebox.core.input.BoxAction.UP
import app.thebox.core.sports.DayState
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.League
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.ScoreFacts
import app.thebox.core.sports.SportsFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The player machine's score bug parts (WP-4b, docs/design/m6-score-bug.md §6): publication gating, demand, live rows. */
class ScoreBugPlayerTest {
    private val g1 = SportsFixtures.game(id = "G1")
    private val g2 = SportsFixtures.game(id = "G2", away = SportsFixtures.team("TOR", "Maple Leafs", 1), home = SportsFixtures.team("MTL", "Canadiens", 1))
    private val g3 = SportsFixtures.game(id = "G3", phase = GamePhase.SCHEDULED, period = null, periodKind = null, clock = null)
    private fun board(vararg g: app.thebox.core.sports.GameSnapshot) = DayState.Board(SportsFixtures.board(*g), 0L, false)

    private fun openSheet(w: PlayerWorld) {
        w.press(UP)
        repeat(QuickItem.entries.size) { if (w.ui.quick!!.let { it.items[it.focused] } != QuickItem.SCORE_BUG) w.press(RIGHT) }
        w.press(SELECT)
    }

    @Test fun scoreUpdatesDoNotReachTheUiOnBareVideo() {
        val w = PlayerWorld()
        val before = w.ui
        w.player.scores(ScoreFacts(followed = SportsFixtures.liveView(g1), game = SportsFixtures.followedOf(g1), day = board(g1)))
        w.player.scores(ScoreFacts(day = board(g2), corner = ScoreCorner.BOTTOM_LEFT))
        assertSame(before, w.ui, "no recomposition source while the quick bar, sheet and place mode are closed")
    }

    @Test fun quickBarCarriesTheFactsAndFollowsThem() {
        val w = PlayerWorld()
        w.press(UP)
        val idle = w.ui.quick!!
        assertEquals(listOf(QuickItem.SCORE_BUG, QuickItem.SETTINGS), idle.items.takeLast(2))
        val facts = ScoreFacts(followed = SportsFixtures.liveView(g1), game = SportsFixtures.followedOf(g1), day = board(g1))
        w.player.scores(facts)
        val q = w.ui.quick!!
        assertEquals(facts, q.score)
        assertEquals(QuickItem.SUBTITLES, q.items[q.items.indexOf(QuickItem.SCORE_BUG) - 1])
        w.player.scores(ScoreFacts(enabled = false))
        assertFalse(QuickItem.SCORE_BUG in w.ui.quick!!.items)
    }

    @Test fun sheetRowsFollowTheDayAndKeepFocusOnTheSameGame() {
        val w = PlayerWorld()
        w.player.scores(ScoreFacts())
        openSheet(w)
        assertEquals(PickerKind.SCORE_BUG, w.ui.picker!!.kind)
        assertTrue(w.ui.picker!!.choices.last() is PickerChoice.ScoreLoading)
        w.player.scores(ScoreFacts(day = board(g3, g2)))
        val p = w.ui.picker!!
        assertEquals(listOf("ScoreCornerRow", "ScoreDelayRow", "ScoreTodayDivider", "ScoreGame", "ScoreGame"), p.choices.map { it::class.simpleName })
        assertEquals("G2", (p.choices[3] as PickerChoice.ScoreGame).game.id.id, "live before upcoming")
        assertEquals(0, p.focused, "Corner keeps focus")
        w.press(DOWN); w.press(DOWN) // Delay, then the first game (the divider is skipped)
        assertEquals(3, w.ui.picker!!.focused)
        w.player.scores(ScoreFacts(day = board(g2, g1, g3))) // a new game arrives
        val q = w.ui.picker!!
        assertEquals("G2", (q.choices[q.focused] as PickerChoice.ScoreGame).game.id.id, "focus stays on the same game")
        w.player.scores(ScoreFacts(day = DayState.Failed(FailKind.OFFLINE)))
        assertTrue(w.ui.picker!!.choices.last() is PickerChoice.ScoreFailed)
        assertTrue(w.ui.picker!!.choices[w.ui.picker!!.focused].selectable)
    }

    @Test fun anotherDaysBoardIsLabelledWithItsDateNeverToday() {
        // U-B1: until the picker's own /score/now answer lands, a board the Sports tab picked names its day.
        val w = PlayerWorld()
        w.player.scores(ScoreFacts(day = DayState.Board(SportsFixtures.board(g2, dayKey = "2026-11-04"), 0L, false, isToday = false)))
        openSheet(w)
        val divider = w.ui.picker!!.choices.first { !it.selectable }
        assertEquals(PickerChoice.ScoreDayDivider("2026-11-04"), divider)
        w.player.scores(ScoreFacts(day = board(g2)))
        assertTrue(PickerChoice.ScoreTodayDivider in w.ui.picker!!.choices)
    }

    @Test fun aHiddenLiveBugKeepsItsScoreInTheFollowedRow() {
        // U-S4: the bug is hidden (no update for 5 min) and the day list does not have the game: the followed row is
        // live with the last known score, not "7:00 PM".
        val w = PlayerWorld()
        w.player.scores(ScoreFacts(lastScore = SportsFixtures.liveView(g1), game = SportsFixtures.followedOf(g1), day = DayState.Loading))
        openSheet(w)
        val row = w.ui.picker!!.choices.first() as PickerChoice.ScoreGame
        assertTrue(row.followed)
        assertEquals(GamePhase.LIVE, row.game.phase)
        assertEquals(2, row.game.home.score)
    }

    @Test fun demandIsOnExactlyWhileASheetIsOpen() {
        val w = PlayerWorld()
        fun demand() = w.fx.calls.filter { it.name == "scorePicker" }.map { it.args[0] as Boolean }
        openSheet(w)
        assertEquals(listOf(true), demand())
        w.press(DOWN); w.press(DOWN); w.press(SELECT) // the delay list is still a score sheet
        assertEquals(PickerKind.SCORE_DELAY, w.ui.picker!!.kind)
        assertEquals(listOf(true), demand())
        w.press(BACK); w.press(BACK) // sheet, then the quick bar
        assertEquals(listOf(true, false), demand())
        assertEquals(PlayerLayer.QUICK_BAR, w.ui.layer)
        openSheet(w)
        w.player.leaveFull()
        assertEquals(listOf(true, false, true, false), demand())
    }

    @Test fun placeModeSavesTheCornerAndShowsASampleWithoutAGame() {
        val w = PlayerWorld()
        openSheet(w) // Corner has focus (no games)
        w.press(SELECT)
        assertEquals(PlayerLayer.PLACE_SCORE, w.ui.layer)
        assertTrue(w.ui.placeScore!!.sample)
        w.press(DOWN)
        assertEquals(ScoreCorner.BOTTOM_RIGHT, w.ui.placeScore!!.corner)
        w.press(SELECT)
        assertEquals(ScoreCorner.BOTTOM_RIGHT, w.fx.last("setScoreCorner")!!.args[0])
        assertEquals(PlayerLayer.QUICK_BAR, w.ui.layer)
        assertNull(w.ui.placeScore)
        // The next sheet shows the saved corner at once.
        w.press(SELECT)
        assertEquals(ScoreCorner.BOTTOM_RIGHT, (w.ui.picker!!.choices[0] as PickerChoice.ScoreCornerRow).corner)
    }

    // ---- (Stacked WP-4, docs/design/m6-score-bug-stacked-theme.md §1.1, S8 / M4) The sheet's Style row

    private fun styleRows(w: PlayerWorld) = w.ui.picker!!.choices.filterIsInstance<PickerChoice.ScoreStyleRow>()

    @Test fun noStyleRowUntilTheHostReportsTheStyle() {
        val w = PlayerWorld()
        w.player.scores(ScoreFacts(league = League.NHL))
        openSheet(w)
        assertTrue(styleRows(w).isEmpty())
    }

    @Test fun theStyleRowIsAfterDelayAndShowsForNothingFollowedOnTheNhlOrAnUnreportedLeague() {
        val w = PlayerWorld()
        w.player.scores(ScoreFacts(style = NhlBugStyle.PILL))
        openSheet(w)
        assertEquals(listOf("ScoreCornerRow", "ScoreDelayRow", "ScoreStyleRow"), w.ui.picker!!.choices.take(3).map { it::class.simpleName })
        w.player.scores(ScoreFacts(style = NhlBugStyle.PILL, league = League.NHL))
        assertEquals(1, styleRows(w).size)
        w.player.scores(ScoreFacts(style = NhlBugStyle.PILL, league = League.NFL))
        assertTrue(styleRows(w).isEmpty(), "browsing the NFL with nothing followed: no row")
    }

    @Test fun theRowFollowsTheFollowedGamesLeague() {
        val w = PlayerWorld()
        val nhl = SportsFixtures.game(id = "H1")
        val nfl = SportsFixtures.nflGame()
        // An NHL follow while browsing the NFL list keeps the row.
        w.player.scores(ScoreFacts(style = NhlBugStyle.PILL, league = League.NFL, followed = SportsFixtures.liveView(nhl), game = SportsFixtures.followedOf(nhl), day = board(nfl)))
        openSheet(w)
        assertEquals(1, styleRows(w).size)
        assertEquals(listOf("ScoreGame", "ScoreLeagueRow", "ScoreCornerRow", "ScoreDelayRow", "ScoreStyleRow", "ScoreStop"), w.ui.picker!!.choices.take(6).map { it::class.simpleName })
        // An NFL follow while browsing the NHL list hides it.
        w.player.scores(ScoreFacts(style = NhlBugStyle.PILL, league = League.NHL, followed = SportsFixtures.nflView(game = nfl), game = SportsFixtures.followedOf(nfl), day = board(nhl)))
        assertTrue(styleRows(w).isEmpty(), "an NFL follow never shows a row that changes nothing on screen")
    }

    @Test fun okTogglesTheStyleAndTheSheetStaysOpenWithFocusOnTheRow() {
        val w = PlayerWorld()
        w.player.scores(ScoreFacts(style = NhlBugStyle.PILL))
        openSheet(w)
        w.press(DOWN); w.press(DOWN) // Corner, Delay, then Style
        assertEquals(PickerChoice.ScoreStyleRow(NhlBugStyle.PILL), w.ui.picker!!.let { it.choices[it.focused] })
        w.press(SELECT)
        // The effect (Stacked WP-3, flagged by WP-4): OK writes the other style through PlayerEffects.setScoreStyle.
        assertEquals(listOf(NhlBugStyle.STACKED), w.fx.calls.filter { it.name == "setScoreStyle" }.map { it.args.single() })
        assertEquals(PlayerLayer.PICKER, w.ui.layer)
        assertEquals(PickerKind.SCORE_BUG, w.ui.picker!!.kind)
        val p = w.ui.picker!!
        assertEquals(PickerChoice.ScoreStyleRow(NhlBugStyle.STACKED), p.choices[p.focused], "shown at once, focus stays on the row")
        // The store's flow confirms it: the same style arrives from the host.
        w.player.scores(ScoreFacts(style = NhlBugStyle.STACKED))
        assertEquals(PickerChoice.ScoreStyleRow(NhlBugStyle.STACKED), w.ui.picker!!.let { it.choices[it.focused] })
        w.press(SELECT)
        val q = w.ui.picker!!
        assertEquals(PickerChoice.ScoreStyleRow(NhlBugStyle.PILL), q.choices[q.focused], "and back")
        assertEquals(listOf(NhlBugStyle.STACKED, NhlBugStyle.PILL), w.fx.calls.filter { it.name == "setScoreStyle" }.map { it.args.single() })
        assertEquals(PlayerLayer.PICKER, w.ui.layer)
    }

    @Test fun theStyleRowSurvivesTheDelayListRoundTrip() {
        val w = PlayerWorld()
        w.player.scores(ScoreFacts(style = NhlBugStyle.STACKED))
        openSheet(w)
        w.press(DOWN) // Delay
        w.press(SELECT)
        assertEquals(PickerKind.SCORE_DELAY, w.ui.picker!!.kind)
        w.press(SELECT) // choose the current delay: back to the sheet on the Delay row
        assertEquals(PickerKind.SCORE_BUG, w.ui.picker!!.kind)
        assertEquals(NhlBugStyle.STACKED, styleRows(w).single().style)
    }
}
