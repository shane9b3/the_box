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
}
