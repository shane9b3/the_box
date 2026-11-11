package app.thebox.core.settings.state

import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.Visibility
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.tree.SettingsTree
import app.thebox.core.settings.tree.SportsRows
import app.thebox.core.sports.NhlBugStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/design/m6-score-bug-stacked-theme.md §1.1 (WP-4): the Settings → Sports "Score bug style" row. */
class SportsStyleRowTest {
    private val store = FakeSettingsStore()
    private val pages = WpaPages(store, SettingsTexts.KEYS, FakePageEnv())

    private fun rows() = pages.build(SettingsRoute.Section(SectionId.SPORTS), advanced = true).rows
    private fun row(): SettingsRow = rows().first { it.id == SportsRows.STYLE }

    @Test
    fun theRowIsAdvancedLiveAndRegisteredAfterSize() {
        val r = rows()
        assertEquals(SportsRows.SIZE, r[r.indexOfFirst { it.id == SportsRows.STYLE } - 1].id)
        assertEquals(SportsRows.STYLE, r.last().id)
        assertEquals(Visibility.ADVANCED, row().visibility)
        assertEquals(EffectTiming.LIVE, row().effect)
        val spec = SettingsTree.spec(SportsRows.STYLE)!!
        assertEquals(Visibility.ADVANCED, spec.visibility)
        assertEquals(SectionId.SPORTS, spec.section)
        assertTrue(pages.build(SettingsRoute.Section(SectionId.SPORTS), advanced = false).rows.isEmpty(), "simple mode has no Sports rows")
    }

    @Test
    fun noSportsRowIsSimpleSoTheSimpleBudgetIsUntouched() {
        assertTrue(SettingsTree.rowsOf(SectionId.SPORTS).none { it.visibility == Visibility.SIMPLE })
    }

    @Test
    fun itOffersPillAndStackedAndDefaultsToPill() {
        val r = row()
        assertEquals("SPORTS_STYLE", r.label)
        assertEquals("SPORTS_STYLE_DESC", r.description)
        val opts = (r.kind as RowKind.Choice).options
        assertEquals(listOf("PILL", "STACKED"), opts.map { it.id })
        assertEquals(listOf("SPORTS_STYLE_PILL", "SPORTS_STYLE_STACKED"), opts.map { it.label })
        assertEquals("SPORTS_STYLE_PILL", r.value)
    }

    @Test
    fun theIntentWritesTheStyleAndTheRowFollowsIt() {
        assertTrue(pages.apply(RowIntent.Choose(SportsRows.STYLE, "STACKED")))
        assertEquals(NhlBugStyle.STACKED, store.flow(SportsDomain).value.nhlStyle)
        assertEquals("SPORTS_STYLE_STACKED", row().value)
        pages.apply(RowIntent.Choose(SportsRows.STYLE, "PILL"))
        assertEquals(NhlBugStyle.PILL, store.flow(SportsDomain).value.nhlStyle)
    }

    @Test
    fun anUnknownNameChangesNothing() {
        pages.apply(RowIntent.Choose(SportsRows.STYLE, "STACKED"))
        pages.apply(RowIntent.Choose(SportsRows.STYLE, "FUTURE"))
        assertEquals(NhlBugStyle.STACKED, store.flow(SportsDomain).value.nhlStyle)
    }
}
