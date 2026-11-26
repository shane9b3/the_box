package app.thebox.core.settings.state

import app.thebox.core.guide.GuideEvent
import app.thebox.core.input.BoxAction
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.Pane
import app.thebox.core.settings.RowId
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.SettingsPresentation
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.tree.PlaybackRows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The settings machine's second presentation (docs/design/m6-player-settings-panel.md §2.1, N5, N10, S10): [present]
 * publishes, clears the page cache and rebuilds the displayed page, keeps path, cursor, overlays and the visit; the
 * machine dispatches nothing to the guide on navigation; the Back ladder is the same in both presentations; and
 * [SettingsMachineImpl.announceOpen] waits for the page title.
 */
class SettingsMachinePanelTest {
    private fun SettingsWorld.panel(route: SettingsRoute = SettingsRoute.Root, detached: Boolean = false): SettingsWorld {
        machine.present(SettingsPresentation.PANEL)
        return open(route, detached)
    }

    @Test
    fun presentPublishesAndIsIdempotent() {
        val w = SettingsWorld()
        assertEquals(SettingsPresentation.FULL_SCREEN, w.ui.presentation)
        w.panel()
        assertEquals(SettingsPresentation.PANEL, w.ui.presentation)
        val loads = w.effects.loads.size
        w.machine.present(SettingsPresentation.PANEL)
        assertEquals(loads, w.effects.loads.size, "the same presentation again requests nothing")
        assertEquals(SettingsPresentation.PANEL, w.ui.presentation, "open keeps the presentation")
    }

    @Test
    fun presentRebuildsTheDisplayedPageAndKeepsPathCursorAndVisit() {
        val w = SettingsWorld().panel()
        w.enter(SectionId.PLAYBACK)
        w.focus(PlaybackRows.AUDIO_OUTPUT)
        w.act(BoxAction.SELECT) // the inline option list is open
        val before = w.ui
        assertNotNull(before.choice)
        val oldVersion = before.page!!.version

        w.machine.present(SettingsPresentation.FULL_SCREEN)
        // The rebuild was asked for; until it lands the old page stays shown with its option list.
        assertEquals(listOf<SettingsRoute>(SettingsRoute.Section(SectionId.PLAYBACK)), w.effects.loads)
        assertEquals(oldVersion, w.ui.page?.version)
        assertNotNull(w.ui.choice)
        w.pump()

        val after = w.ui
        assertEquals(SettingsPresentation.FULL_SCREEN, after.presentation)
        assertTrue(after.page!!.version > oldVersion, "the displayed page is rebuilt")
        assertEquals(before.route, after.route)
        assertEquals(before.stack, after.stack)
        assertEquals(before.pane, after.pane)
        assertEquals(before.cursor, after.cursor)
        assertEquals(before.choice, after.choice, "the option list stays open on its row")
        assertTrue(w.effects.closed.isEmpty(), "present never ends the visit")
    }

    @Test
    fun aRebuildForThePresentationIsTakenWhateverItsVersion() {
        val w = SettingsWorld().panel()
        w.enter(SectionId.PLAYBACK)
        w.focus(PlaybackRows.DECODER)
        val shown = w.ui.page!!
        w.machine.present(SettingsPresentation.FULL_SCREEN)
        w.effects.loads.clear()
        // A source that numbers its pages per build may answer with a lower version.
        w.machine.dispatch(SettingsEvent.Page(shown.copy(title = "rebuilt", version = 1)))
        assertEquals("rebuilt", w.ui.page?.title)
        assertEquals(PlaybackRows.DECODER, w.focusedRow()?.id, "the cursor follows its row")
        // Only that one rebuild: an older page after it is dropped as before.
        w.machine.dispatch(SettingsEvent.Page(shown.copy(title = "stale", version = 0)))
        assertEquals("rebuilt", w.ui.page?.title)
    }

    @Test
    fun presentClearsThePagesOnThePathSoParentsRebuildWhenShown() {
        val w = SettingsWorld().panel()
        w.enter(SectionId.CHANNELS)
        w.focus(RowId("fx.lists")); w.act(BoxAction.RIGHT)
        assertEquals(SettingsRoute.FavouriteLists, w.ui.route)
        w.machine.present(SettingsPresentation.FULL_SCREEN); w.pump()
        w.effects.loads.clear()
        w.machine.act(GuideEvent.Act(BoxAction.BACK))
        assertEquals(listOf<SettingsRoute>(SettingsRoute.Section(SectionId.CHANNELS)), w.effects.loads, "the parent page is built again")
        w.pump()
        assertEquals(RowId("fx.lists"), w.focusedRow()?.id, "cursor memory survives the cache clear")
    }

    @Test
    fun theRootListKeepsTheSectionPreviewInThePanel() {
        val w = SettingsWorld().panel()
        assertEquals(SettingsRoute.Root, w.ui.route)
        assertEquals(Pane.SECTIONS, w.ui.pane)
        assertTrue(w.ui.sections.isNotEmpty(), "the root list is the sections pane")
        assertEquals(SettingsRoute.Section(w.ui.sections[w.ui.sectionCursor]), w.ui.page?.route, "N1: the preview is kept")
    }

    @Test
    fun presentBeforeOpenBuildsTheFirstPageInThePanel() {
        val w = SettingsWorld()
        w.machine.present(SettingsPresentation.PANEL)
        assertTrue(w.effects.loads.isEmpty(), "no visit: nothing to rebuild")
        w.machine.open(SettingsRoute.Section(SectionId.PLAYBACK), fromFull = true)
        assertEquals(SettingsPresentation.PANEL, w.ui.presentation)
        assertEquals(listOf<SettingsRoute>(SettingsRoute.Section(SectionId.PLAYBACK)), w.effects.loads)
    }

    @Test
    fun theVisitSpansAnExpandAndEndsOnce() {
        val w = SettingsWorld().panel()
        w.enter(SectionId.PLAYBACK)
        w.act(BoxAction.SELECT); w.act(BoxAction.DOWN); w.act(BoxAction.SELECT) // buffer
        w.machine.present(SettingsPresentation.FULL_SCREEN); w.pump() // expand: no onLeave
        assertTrue(w.effects.closed.isEmpty())
        w.machine.onLeave(); w.machine.onLeave()
        assertEquals(listOf(setOf(EffectTiming.REPREPARE)), w.effects.closed)
    }

    @Test
    fun navigationDispatchesNothingToTheGuide() {
        val w = SettingsWorld().panel()
        val guide = ArrayList<GuideEvent>()
        w.machine.attachGuide { guide += it }
        w.enter(SectionId.PLAYLISTS)
        w.machine.push(SettingsRoute.AddSource()); w.pump() // an expanding route: the guide decides, not the machine (S10)
        w.machine.replace(SettingsRoute.EpgSources); w.pump()
        w.machine.popTo(SettingsRoute.Root); w.pump()
        w.enter(SectionId.PLAYBACK)
        w.act(BoxAction.DOWN); w.act(BoxAction.LEFT)
        w.machine.present(SettingsPresentation.FULL_SCREEN); w.pump()
        w.machine.present(SettingsPresentation.PANEL); w.pump()
        assertTrue(guide.isEmpty(), "dispatched: $guide")
    }

    @TestFactory
    fun theBackLadderIsTheSameInBothPresentations(): List<DynamicTest> = SettingsPresentation.entries.flatMap { p ->
        listOf(false, true).map { detached ->
            DynamicTest.dynamicTest("$p, detached=$detached") {
                val w = SettingsWorld()
                w.machine.present(p)
                if (detached) {
                    w.open(SettingsRoute.Section(SectionId.PLAYBACK), detached = true)
                    assertTrue(w.act(BoxAction.LEFT), "LEFT at a detached bottom is consumed (N6)")
                    assertEquals(SettingsRoute.Section(SectionId.PLAYBACK), w.ui.route)
                    assertFalse(w.act(BoxAction.BACK), "BACK at a detached bottom leaves")
                    return@dynamicTest
                }
                w.open()
                w.enter(SectionId.CHANNELS)
                w.focus(RowId("fx.lists")); w.act(BoxAction.RIGHT)
                w.focus(RowId("fx.multi")); w.act(BoxAction.SELECT)
                assertNotNull(w.ui.choice)
                assertTrue(w.act(BoxAction.BACK)); assertNull(w.ui.choice); assertEquals(SettingsRoute.FavouriteLists, w.ui.route)
                assertTrue(w.act(BoxAction.BACK)); assertEquals(SettingsRoute.Section(SectionId.CHANNELS), w.ui.route)
                assertEquals(RowId("fx.lists"), w.focusedRow()?.id, "parent page, cursor restored")
                assertTrue(w.act(BoxAction.BACK)); assertEquals(Pane.SECTIONS, w.ui.pane)
                assertEquals(SectionId.CHANNELS, w.ui.sections[w.ui.sectionCursor], "root list, cursor on that section")
                assertTrue(w.act(BoxAction.LEFT), "LEFT on the root list is consumed and does nothing")
                assertEquals(Pane.SECTIONS, w.ui.pane)
                assertFalse(w.act(BoxAction.BACK), "BACK at the root list leaves (the guide closes the panel)")
                assertEquals(p, w.ui.presentation)
            }
        }
    }

    @Test
    fun announceOpenOnTheRootListSaysSettings() {
        val w = SettingsWorld().panel()
        val events = collectShell(w)
        w.machine.announceOpen()
        assertEquals(listOf<SettingsShellEvent>(SettingsShellEvent.Announce(SettingsText.PANEL_ANNOUNCE_OPEN_ROOT.name)), events)
    }

    @Test
    fun announceOpenWaitsForThePageTitle() {
        val w = SettingsWorld()
        val events = collectShell(w)
        w.machine.present(SettingsPresentation.PANEL)
        w.machine.open(SettingsRoute.Section(SectionId.PLAYBACK), fromFull = true)
        w.machine.announceOpen()
        assertTrue(events.isEmpty(), "the page is not known yet")
        w.pump()
        val title = w.ui.page!!.title
        assertEquals(listOf<SettingsShellEvent>(SettingsShellEvent.Announce("${SettingsText.PANEL_ANNOUNCE_OPEN.name}($title)")), events)
        w.machine.dispatch(SettingsEvent.Page(w.page(SettingsRoute.Section(SectionId.PLAYBACK))))
        assertEquals(1, events.size, "once")
    }

    @Test
    fun aPendingAnnouncementDiesWithTheVisit() {
        val w = SettingsWorld()
        val events = collectShell(w)
        w.machine.present(SettingsPresentation.PANEL)
        w.machine.open(SettingsRoute.Section(SectionId.PLAYBACK), fromFull = true)
        w.machine.announceOpen()
        w.machine.onLeave()
        w.machine.open(SettingsRoute.Section(SectionId.PLAYBACK), fromFull = true); w.pump()
        assertTrue(events.isEmpty())
    }

    private fun collectShell(w: SettingsWorld): MutableList<SettingsShellEvent> {
        val out = ArrayList<SettingsShellEvent>()
        CoroutineScope(Dispatchers.Unconfined).launch { w.machine.shellEvents.collect { if (it is SettingsShellEvent.Announce) out += it } }
        return out
    }
}
