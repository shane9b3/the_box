package app.thebox.ui.settings.vm

import app.thebox.core.catchup.archive.PanelApplyResult
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.PageModel
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.SettingsPresentation
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsRow
import app.thebox.core.settings.SettingsUiState
import app.thebox.core.settings.fakes.FakeLiveSettingsApplier
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.PageContext
import app.thebox.core.settings.state.SettingsPageSource
import app.thebox.core.settings.state.SettingsPanelRules
import app.thebox.core.settings.state.SettingsText
import app.thebox.core.settings.state.SettingsTexts
import app.thebox.core.settings.tree.PlaybackRows
import app.thebox.ui.settings.FakeSettingsUiMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage A of the panel's live effect (docs/design/m6-player-settings-panel.md §4.2): a REPREPARE row changed in the panel
 * is applied once, [SettingsPanelRules.APPLY_DEBOUNCE_MS] after the last change, through `applyFromPanel()` with its
 * honest notice; connection and other rows schedule nothing; FULL_SCREEN keeps today's apply-on-close; `onClosed` does not
 * re-prepare twice (R9), but does after CARD_SHOWN; pages are built with `PageContext.panel`.
 */
class SettingsEffectsPanelApplyTest {
    private val route = SettingsRoute.Section(SectionId.PLAYBACK)
    private val subRow = RowId(PlaybackRows.DECODER.key + ".x")       // a sub-row of a REPREPARE row (S4 prefix)
    private val unlisted = RowId("fx.reprepare")                        // not in the registry: the row's own timing
    private val connection = RowId("playlist.stream_format")            // NEXT_TUNE (connection row)

    private inner class Rig(presentation: SettingsPresentation = SettingsPresentation.PANEL, writeMs: Long = 0) {
        val test = TestScope()
        val d = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(Job() + d)
        val applier = FakeLiveSettingsApplier()
        val stored = ArrayList<RowId>()
        val contexts = ArrayList<PageContext>()
        /** Applies seen, with how many writes had landed by then. */
        val appliesAfterWrites = ArrayList<Int>()
        val page = PageModel(route, "Playback", rows = listOf(
            SettingsRow(PlaybackRows.BUFFER, "Buffer", kind = RowKind.Toggle(false), effect = EffectTiming.REPREPARE),
            SettingsRow(PlaybackRows.TUNNELING, "Tunneling", kind = RowKind.Toggle(false), effect = EffectTiming.REPREPARE),
            SettingsRow(subRow, "Sub", kind = RowKind.Toggle(false)),
            SettingsRow(unlisted, "Unlisted", kind = RowKind.Toggle(false), effect = EffectTiming.REPREPARE),
            SettingsRow(connection, "Stream format", kind = RowKind.Toggle(false), effect = EffectTiming.NEXT_TUNE),
            SettingsRow(PlaybackRows.ASPECT, "Aspect", kind = RowKind.Toggle(false), effect = EffectTiming.NEXT_TUNE),
        ), version = 1)
        val source = object : SettingsPageSource {
            override fun handles(route: SettingsRoute) = true
            override suspend fun page(route: SettingsRoute, ctx: PageContext): PageModel { contexts += ctx; return page }
            override suspend fun onIntent(route: SettingsRoute, intent: RowIntent, ctx: PageContext): Boolean {
                if (writeMs > 0) delay(writeMs)
                stored += intent.row
                return true
            }
        }
        val effects = SettingsEffectsImpl(listOf(source), FakeSettingsStore(), object : app.thebox.core.catchup.archive.LiveSettingsApplier {
            override fun apply() = applier.apply()
            override fun applyFromPanel(): PanelApplyResult { appliesAfterWrites += stored.size; return applier.applyFromPanel() }
        }, SettingsTexts.KEYS, null, scope, d, d) { 0L }
        val machine = FakeSettingsUiMachine(SettingsUiState(route = route, page = page, presentation = presentation)).also { effects.machine = it }

        fun change(id: RowId) { effects.onIntent(RowIntent.Toggle(id, on = true)); test.runCurrent() }
        fun advance(ms: Long) { test.advanceTimeBy(ms); test.runCurrent() }
        fun notices() = machine.events.filterIsInstance<SettingsEvent.Notice>().map { it.text }
        fun present(p: SettingsPresentation) { machine.present(p) }
    }

    @Test
    fun aReprepareChangeInThePanelAppliesOnceAfterTheDebounce() {
        val r = Rig()
        r.change(PlaybackRows.BUFFER)
        r.advance(SettingsPanelRules.APPLY_DEBOUNCE_MS - 1)
        assertEquals("not before the debounce", 0, r.applier.panelApplies)
        r.advance(1)
        assertEquals(1, r.applier.panelApplies)
        assertEquals("the stored value is read", listOf(1), r.appliesAfterWrites)
        assertEquals("never apply() for the panel change", 0, r.applier.applied)
        assertEquals(listOf(SettingsText.PANEL_NOTICE_REPREPARING.name), r.notices())
        r.test.advanceUntilIdle()
        assertEquals(1, r.applier.panelApplies)
    }

    @Test
    fun theDebounceStartsAfterTheWrite() {
        val r = Rig(writeMs = 300)
        r.change(PlaybackRows.BUFFER)
        r.advance(300 + SettingsPanelRules.APPLY_DEBOUNCE_MS - 1)
        assertEquals(0, r.applier.panelApplies)
        r.advance(1)
        assertEquals(listOf(1), r.appliesAfterWrites)
    }

    @Test
    fun twoChangesWithinTheDebounceApplyOnce() {
        val r = Rig()
        r.change(PlaybackRows.BUFFER)
        r.advance(300)
        r.change(PlaybackRows.TUNNELING)
        r.advance(300)
        assertEquals(0, r.applier.panelApplies)
        r.advance(100)
        assertEquals(1, r.applier.panelApplies)
        assertEquals(listOf(2), r.appliesAfterWrites)
    }

    @Test
    fun aSubRowOfAReprepareRowAndARowCarryingTheTimingApply() {
        for (id in listOf(subRow, unlisted)) {
            val r = Rig()
            r.change(id)
            r.advance(SettingsPanelRules.APPLY_DEBOUNCE_MS)
            assertEquals(id.key, 1, r.applier.panelApplies)
        }
    }

    @Test
    fun connectionAndOtherRowsScheduleNothing() {
        val r = Rig()
        r.change(connection)
        r.change(PlaybackRows.ASPECT)
        r.change(RowId("interface.clock"))
        r.test.advanceUntilIdle()
        assertEquals(0, r.applier.panelApplies)
        assertTrue(r.notices().isEmpty())
        r.effects.onClosed(setOf(EffectTiming.NEXT_TUNE, EffectTiming.LIVE))
        assertEquals(0, r.applier.applied)
    }

    @Test
    fun fullScreenAppliesOnlyOnClose() {
        val r = Rig(SettingsPresentation.FULL_SCREEN)
        r.change(PlaybackRows.BUFFER)
        r.test.advanceUntilIdle()
        assertEquals(0, r.applier.panelApplies)
        r.effects.onClosed(setOf(EffectTiming.REPREPARE))
        assertEquals(1, r.applier.applied)
    }

    @Test
    fun closingAfterAPanelApplyDoesNotApplyAgain() {
        val r = Rig()
        r.change(PlaybackRows.BUFFER)
        r.advance(SettingsPanelRules.APPLY_DEBOUNCE_MS)
        r.effects.onClosed(setOf(EffectTiming.REPREPARE))
        assertEquals(1, r.applier.panelApplies)
        assertEquals(0, r.applier.applied)
        // The guard is per visit: the next visit's full-screen change applies on close.
        r.present(SettingsPresentation.FULL_SCREEN)
        r.change(PlaybackRows.BUFFER)
        r.effects.onClosed(setOf(EffectTiming.REPREPARE))
        assertEquals(1, r.applier.applied)
    }

    @Test
    fun closingWithAnApplyPendingRunsItAtOnceAndOnlyOnce() {
        val r = Rig()
        r.change(PlaybackRows.BUFFER)
        r.advance(100)
        r.effects.onClosed(setOf(EffectTiming.REPREPARE))
        assertEquals(1, r.applier.panelApplies)
        assertEquals(0, r.applier.applied)
        assertTrue("no notice while the panel closes", r.notices().isEmpty())
        r.test.advanceUntilIdle()
        assertEquals(1, r.applier.panelApplies)
    }

    @Test
    fun theCardShownAppliesNothingAndClosingStillApplies() {
        val r = Rig()
        r.applier.panelResult = PanelApplyResult.CARD_SHOWN
        r.change(PlaybackRows.BUFFER)
        r.advance(SettingsPanelRules.APPLY_DEBOUNCE_MS)
        assertEquals(listOf(SettingsText.PANEL_NOTICE_CARD_SHOWN.name), r.notices())
        r.effects.onClosed(setOf(EffectTiming.REPREPARE))
        assertEquals("leaving Settings applies as today", 1, r.applier.applied)
    }

    @Test
    fun resultNotices() {
        val expected = mapOf(
            PanelApplyResult.REPREPARING to listOf(SettingsText.PANEL_NOTICE_REPREPARING.name),
            PanelApplyResult.WAITS_FOR_RESUME to listOf(SettingsText.PANEL_NOTICE_WAITS_FOR_RESUME.name),
            PanelApplyResult.CARD_SHOWN to listOf(SettingsText.PANEL_NOTICE_CARD_SHOWN.name),
            PanelApplyResult.NOTHING_PLAYING to emptyList(),
        )
        assertEquals(PanelApplyResult.entries.toSet(), expected.keys)
        for ((result, notices) in expected) {
            val r = Rig()
            r.applier.panelResult = result
            r.change(PlaybackRows.BUFFER)
            r.advance(SettingsPanelRules.APPLY_DEBOUNCE_MS)
            assertEquals(result.name, notices, r.notices())
            assertFalse("stage A never says Applied", r.notices().any { it.contains("APPLIED") })
            r.effects.onClosed(setOf(EffectTiming.REPREPARE))
            assertEquals(result.name, if (result == PanelApplyResult.CARD_SHOWN) 1 else 0, r.applier.applied)
        }
    }

    @Test
    fun aPanelChangeAfterAPanelApplyIsAppliedAgain() {
        val r = Rig()
        r.change(PlaybackRows.BUFFER)
        r.advance(SettingsPanelRules.APPLY_DEBOUNCE_MS)
        r.change(PlaybackRows.TUNNELING)
        r.advance(SettingsPanelRules.APPLY_DEBOUNCE_MS)
        assertEquals(2, r.applier.panelApplies)
        r.effects.onClosed(setOf(EffectTiming.REPREPARE))
        assertEquals(0, r.applier.applied)
    }

    @Test
    fun anExpandBeforeTheDebounceLeavesTheApplyToClose() {
        val r = Rig()
        r.change(PlaybackRows.BUFFER)
        r.advance(100)
        r.present(SettingsPresentation.FULL_SCREEN) // expanded: the video is covered
        r.advance(SettingsPanelRules.APPLY_DEBOUNCE_MS)
        assertEquals(0, r.applier.panelApplies)
        r.effects.onClosed(setOf(EffectTiming.REPREPARE))
        assertEquals(0, r.applier.panelApplies)
        assertEquals(1, r.applier.applied)
    }

    @Test
    fun aFullScreenChangeAfterAPanelApplyIsAppliedOnClose() {
        val r = Rig()
        r.change(PlaybackRows.BUFFER)
        r.advance(SettingsPanelRules.APPLY_DEBOUNCE_MS)
        r.present(SettingsPresentation.FULL_SCREEN) // expand, then a change there
        r.change(PlaybackRows.TUNNELING)
        r.test.advanceUntilIdle()
        r.effects.onClosed(setOf(EffectTiming.REPREPARE))
        assertEquals(1, r.applier.panelApplies)
        assertEquals(1, r.applier.applied)
    }

    @Test
    fun anIntentStillWritingWhenSettingsClosesSchedulesNothingLater() {
        val r = Rig(writeMs = 200)
        r.change(PlaybackRows.BUFFER)
        r.effects.onClosed(setOf(EffectTiming.REPREPARE))
        assertEquals("closing applies the unapplied change", 1, r.applier.applied)
        r.test.advanceUntilIdle()
        assertEquals(0, r.applier.panelApplies)
    }

    @Test
    fun pagesAreBuiltWithThePresentationsContext() {
        val r = Rig()
        r.effects.loadPage(route); r.test.runCurrent()
        r.present(SettingsPresentation.FULL_SCREEN)
        r.effects.loadPage(route); r.test.runCurrent()
        assertEquals(listOf(true, false), r.contexts.map { it.panel })
    }
}
