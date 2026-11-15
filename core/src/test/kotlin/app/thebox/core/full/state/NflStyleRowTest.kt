package app.thebox.core.full.state

import app.thebox.core.full.PickerChoice
import app.thebox.core.full.PickerKind
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.QuickItem
import app.thebox.core.input.BoxAction.DOWN
import app.thebox.core.input.BoxAction.RIGHT
import app.thebox.core.input.BoxAction.SELECT
import app.thebox.core.input.BoxAction.UP
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.FakePageEnv
import app.thebox.core.settings.state.SettingsTexts
import app.thebox.core.settings.state.SportsDomain
import app.thebox.core.settings.state.WpaPages
import app.thebox.core.settings.tree.SportsRows
import app.thebox.core.sports.League
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.ScoreFacts
import app.thebox.core.sports.SportsFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * (NFL Stacked WP-5, docs/design/m6-nfl-stacked.md §7) With an NFL game followed the quick-bar Score bug sheet shows the Style
 * row and OK toggles Pill <-> Stacked (the one shared `nhlStyle`); Settings shows the same row, one style for all sports.
 */
class NflStyleRowTest {
    private fun openSheet(w: PlayerWorld) {
        w.press(UP)
        repeat(QuickItem.entries.size) { if (w.ui.quick!!.let { it.items[it.focused] } != QuickItem.SCORE_BUG) w.press(RIGHT) }
        w.press(SELECT)
    }

    private fun styleRows(w: PlayerWorld) = w.ui.picker!!.choices.filterIsInstance<PickerChoice.ScoreStyleRow>()
    private fun styleCalls(w: PlayerWorld) = w.fx.calls.filter { it.name == "setScoreStyle" }.map { it.args.single() }

    private fun nflFollowed(style: NhlBugStyle): ScoreFacts {
        val nfl = SportsFixtures.nflGame()
        return ScoreFacts(style = style, league = League.NFL, followed = SportsFixtures.nflView(game = nfl), game = SportsFixtures.followedOf(nfl))
    }

    @Test fun theSheetShowsTheStyleRowForAFollowedNflGameAndOkTogglesPillAndStacked() {
        val w = PlayerWorld()
        w.player.scores(nflFollowed(NhlBugStyle.PILL))
        openSheet(w)
        assertEquals(1, styleRows(w).size)
        assertEquals(PickerKind.SCORE_BUG, w.ui.picker!!.kind)
        repeat(12) { if (w.ui.picker!!.let { it.choices[it.focused] } !is PickerChoice.ScoreStyleRow) w.press(DOWN) }
        assertEquals(PickerChoice.ScoreStyleRow(NhlBugStyle.PILL), w.ui.picker!!.let { it.choices[it.focused] })
        w.press(SELECT)
        assertEquals(listOf(NhlBugStyle.STACKED), styleCalls(w))
        assertEquals(PlayerLayer.PICKER, w.ui.layer, "the sheet stays open")
        assertEquals(PickerChoice.ScoreStyleRow(NhlBugStyle.STACKED), w.ui.picker!!.let { it.choices[it.focused] }, "shown at once, focus on the row")
        w.player.scores(nflFollowed(NhlBugStyle.STACKED)) // the store's flow confirms
        w.press(SELECT)
        assertEquals(listOf(NhlBugStyle.STACKED, NhlBugStyle.PILL), styleCalls(w))
        assertEquals(PickerChoice.ScoreStyleRow(NhlBugStyle.PILL), w.ui.picker!!.let { it.choices[it.focused] }, "and back")
    }

    @Test fun theRowSitsAfterDelayAndBeforeStopWithTheNflFollowed() {
        val w = PlayerWorld()
        w.player.scores(nflFollowed(NhlBugStyle.STACKED))
        openSheet(w)
        assertEquals(
            listOf("ScoreGame", "ScoreLeagueRow", "ScoreCornerRow", "ScoreDelayRow", "ScoreStyleRow", "ScoreStop"),
            w.ui.picker!!.choices.take(6).map { it::class.simpleName },
        )
        assertEquals(NhlBugStyle.STACKED, styleRows(w).single().style)
    }

    @Test fun settingsShowsTheSharedStyleRowForEverySportWithTheNewDescription() {
        val store = FakeSettingsStore()
        val pages = WpaPages(store, SettingsTexts.KEYS, FakePageEnv())
        fun row() = pages.build(SettingsRoute.Section(SectionId.SPORTS), advanced = true).rows.first { it.id == SportsRows.STYLE }
        assertEquals("SPORTS_STYLE", row().label)
        assertEquals("SPORTS_STYLE_DESC", row().description)
        assertEquals(listOf("PILL", "STACKED"), (row().kind as RowKind.Choice).options.map { it.id })
        assertTrue(pages.apply(RowIntent.Choose(SportsRows.STYLE, "STACKED")))
        assertEquals(NhlBugStyle.STACKED, store.flow(SportsDomain).value.nhlStyle, "the one setting every league reads (NHL, CHL, NFL)")
        assertEquals("SPORTS_STYLE_STACKED", row().value)
        pages.apply(RowIntent.Choose(SportsRows.STYLE, "PILL"))
        assertEquals(NhlBugStyle.PILL, store.flow(SportsDomain).value.nhlStyle)
    }
}
