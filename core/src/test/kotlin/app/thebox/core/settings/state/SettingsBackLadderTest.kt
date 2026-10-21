package app.thebox.core.settings.state

import app.thebox.core.input.BoxAction
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.Pane
import app.thebox.core.settings.RowId
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsEvent
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.tree.ChannelOptionsRows
import app.thebox.core.settings.tree.NetworkRows
import app.thebox.core.settings.tree.PlaybackRows
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
 * The Back ladder in SETTINGS (docs/design/m5-setup-settings.md §3.2.4): overlay → pop the page stack → focus SECTIONS
 * (detached: leave at once) → leave Settings (act answers false). Every overlay × stack depth, plus first run, detached
 * routes, onLeave (S9) and cursor memory.
 */
class SettingsBackLadderTest {
    private enum class Overlay { NONE, CHOICE, MULTI, MENU, CONFIRM, PAD, MOVE, CAPTURE }

    /**
     * Depth 2: Section(CHANNELS) → FavouriteLists; depth 3: … → Channel options (via the fixture link chain); (C-B1, C-S9)
     * depth 4: … → Fix guide data (its link on Channel options); depth 5: … → the picker's parent list pushed on top.
     */
    private fun at(depth: Int, overlay: Overlay): SettingsWorld {
        val w = SettingsWorld().open()
        w.enter(SectionId.CHANNELS)
        if (depth >= 2) { w.focus(RowId("fx.lists")); w.act(BoxAction.RIGHT) }
        if (depth >= 3) { w.act(BoxAction.LEFT); w.focus(RowId("fx.channel")); w.act(BoxAction.RIGHT) }
        if (depth >= 4) { w.focus(ChannelOptionsRows.FIX_GUIDE); w.act(BoxAction.SELECT) }
        if (depth >= 5) { w.machine.push(SettingsRoute.HiddenChannels(1)); w.pump() }
        check(w.ui.stack.size == mapOf(1 to 1, 2 to 2, 3 to 2, 4 to 3, 5 to 4).getValue(depth)) { "depth $depth: ${w.ui.stack} → ${w.ui.route}" }
        when (overlay) {
            Overlay.NONE -> Unit
            Overlay.CHOICE -> if (depth == 3) { w.focus(ChannelOptionsRows.ASPECT); w.act(BoxAction.SELECT) } else w.overlayOnLists(overlay)
            else -> w.overlayOnLists(overlay)
        }
        return w
    }

    private fun SettingsWorld.overlayOnLists(o: Overlay) {
        if (ui.route != SettingsRoute.FavouriteLists) return
        when (o) {
            Overlay.CHOICE, Overlay.MULTI -> { focus(RowId("fx.multi")); act(BoxAction.SELECT) }
            Overlay.MENU -> { focus(RowId("fx.list.1")); act(BoxAction.CONTEXT_MENU) }
            Overlay.CONFIRM -> { focus(RowId("fx.confirm")); act(BoxAction.SELECT) }
            Overlay.PAD -> { focus(RowId("fx.number")); act(BoxAction.DIGIT, digit = 4) }
            Overlay.MOVE -> { focus(RowId("fx.list.1")); act(BoxAction.CONTEXT_MENU); act(BoxAction.DOWN); act(BoxAction.SELECT) }
            Overlay.CAPTURE -> { focus(FixturePages.CAPTURE_ROW); act(BoxAction.SELECT) }
            Overlay.NONE -> Unit
        }
    }

    private fun overlayOpen(w: SettingsWorld) = with(w.ui) { choice != null || menu != null || confirm != null || numberPad != null || move != null || capture != null }

    @TestFactory
    fun everyOverlayAndDepth(): List<DynamicTest> = (1..5).flatMap { depth -> // C-B1 / C-S9: to depth 5
        Overlay.entries.map { o ->
            DynamicTest.dynamicTest("depth $depth, $o") {
                val w = at(depth, o)
                val stackBefore = w.ui.stack.size
                var backs = 0
                if (overlayOpen(w)) {
                    // A pad with digits needs one BACK per digit before it closes.
                    while (overlayOpen(w)) { assertTrue(w.act(BoxAction.BACK)); backs++; check(backs < 5) }
                    assertEquals(stackBefore, w.ui.stack.size, "the overlay closes first, the page stays")
                }
                while (w.ui.pane == Pane.PAGE) { assertTrue(w.act(BoxAction.BACK)); backs++; check(backs < 12) }
                assertEquals(SettingsRoute.Root, w.ui.route)
                assertEquals(SectionId.CHANNELS, w.ui.sections[w.ui.sectionCursor], "the section pane keeps the section")
                assertFalse(w.act(BoxAction.BACK), "BACK at the root leaves Settings")
                assertTrue(backs + 1 <= 12)
            }
        }
    }

    @Test
    fun firstRunBackExitsWithoutConfirmation() {
        val w = SettingsWorld().open(SettingsRoute.FirstRun)
        assertTrue(w.ui.sections.isEmpty())
        assertTrue(w.act(BoxAction.BACK))
        assertEquals(1, w.effects.exits)
    }

    @Test
    fun insideTheFirstRunFlowBackPopsTowardWelcome() {
        val w = SettingsWorld().open(SettingsRoute.FirstRun)
        w.machine.push(SettingsRoute.AddSource(firstRun = true)); w.pump()
        assertEquals(listOf<SettingsRoute>(SettingsRoute.FirstRun), w.ui.stack)
        assertTrue(w.act(BoxAction.BACK))
        assertEquals(SettingsRoute.FirstRun, w.ui.route)
        assertEquals(0, w.effects.exits)
    }

    @Test
    fun aDetachedRouteHasNothingOfSettingsBehindIt() {
        val w = SettingsWorld().open(SettingsRoute.SyncStatus, detached = true)
        assertTrue(w.ui.sections.isEmpty())
        assertTrue(w.ui.stack.isEmpty())
        assertFalse(w.act(BoxAction.BACK), "BACK leaves SETTINGS at once (S2)")
    }

    @Test
    fun aDeepRouteOpensWithItsParents() {
        val w = SettingsWorld().open(SettingsRoute.Channel(SettingsWorld.ID))
        assertEquals(listOf(SettingsRoute.Root, SettingsRoute.Section(SectionId.CHANNELS)), w.ui.stack)
        assertEquals(Pane.PAGE, w.ui.pane)
        w.act(BoxAction.BACK)
        assertEquals(SettingsRoute.Section(SectionId.CHANNELS), w.ui.route)
    }

    @Test
    fun onLeaveReportsChangedTimingsOncePerVisit() {
        val w = SettingsWorld().open()
        w.enter(SectionId.PLAYBACK)
        w.act(BoxAction.SELECT); w.act(BoxAction.DOWN); w.act(BoxAction.SELECT) // buffer → None
        w.machine.onLeave()
        w.machine.onLeave()
        assertEquals(listOf(setOf(EffectTiming.REPREPARE)), w.effects.closed)
        w.open()
        w.machine.onLeave()
        assertEquals(listOf(setOf(EffectTiming.REPREPARE), emptySet()), w.effects.closed, "a new visit starts empty")
    }

    @Test
    fun choosingTheSelectedOptionChangesNothing() {
        val w = SettingsWorld().open()
        w.enter(SectionId.PLAYBACK)
        w.focus(PlaybackRows.AUDIO_OUTPUT)
        w.act(BoxAction.SELECT); w.act(BoxAction.SELECT)
        assertTrue(w.effects.intents.isEmpty())
        w.machine.onLeave()
        assertEquals(listOf(emptySet<EffectTiming>()), w.effects.closed)
    }

    @Test
    fun cursorMemoryFollowsTheRowIdAcrossRebuilds() {
        val w = SettingsWorld().open()
        w.enter(SectionId.PLAYBACK)
        // (M6 time-shift commit 0) "Pause and rewind" is the fifth simple row now that "On launch" is ADVANCED.
        w.focus(PlaybackRows.TIMESHIFT)
        w.act(BoxAction.LEFT)
        w.enter(SectionId.PLAYBACK)
        assertEquals(PlaybackRows.TIMESHIFT, w.focusedRow()?.id)
        // A rebuild that inserts rows above keeps the focus on the same row.
        w.advanced = true
        w.machine.dispatch(SettingsEvent.Page(w.page(SettingsRoute.Section(SectionId.PLAYBACK))))
        assertEquals(PlaybackRows.TIMESHIFT, w.focusedRow()?.id)
    }

    @Test
    fun turningAdvancedOffLeavesTheNetworkSection() {
        val w = SettingsWorld().open()
        repeat(9) { w.act(BoxAction.DOWN) }
        w.act(BoxAction.SELECT) // advanced on
        assertTrue(SectionId.NETWORK in w.ui.sections)
        w.act(BoxAction.UP)
        w.act(BoxAction.RIGHT)
        assertEquals(SettingsRoute.Section(SectionId.NETWORK), w.ui.route)
        assertNotNull(w.ui.page?.rows?.firstOrNull { it.id == NetworkRows.DNS })
        w.machine.dispatch(SettingsEvent.Advanced(false)); w.pump()
        assertEquals(SettingsRoute.Root, w.ui.route)
        assertFalse(SectionId.NETWORK in w.ui.sections)
    }

    @Test
    fun editorValidationKeepsTheEditorOpenWithTheError() {
        val store = app.thebox.core.settings.fakes.FakeSettingsStore()
        store.update(GuideDomain) { it.copy(advancedSettings = true) }
        store.update(NetworkDomain) { it.copy(dns = app.thebox.core.net.DnsChoice.CUSTOM) }
        val w = SettingsWorld(store).also { it.advanced = true; it.machine.dispatch(SettingsEvent.Advanced(true)) }.open()
        w.enter(SectionId.NETWORK)
        w.focus(NetworkRows.CUSTOM_DOH)
        w.act(BoxAction.SELECT)
        assertTrue(w.machine.textInputActive)
        w.machine.dispatch(SettingsEvent.EditorClosed(NetworkRows.CUSTOM_DOH, "http://dns.example/dns-query", committed = true)); w.pump()
        assertEquals(SettingsText.ERR_HTTPS.name, w.ui.editor?.error)
        w.machine.dispatch(SettingsEvent.EditorClosed(NetworkRows.CUSTOM_DOH, "https://dns.example/dns-query", committed = true)); w.pump()
        assertNull(w.ui.editor)
        assertEquals("https://dns.example/dns-query", store.flow(NetworkDomain).value.customDohUrl)
    }

    @Test
    fun editorBackIsHandedToTheOverlay() {
        val w = SettingsWorld().open()
        w.enter(SectionId.CHANNELS); w.focus(RowId("fx.lists")); w.act(BoxAction.RIGHT)
        w.focus(RowId("fx.text")); w.act(BoxAction.SELECT)
        var backs = 0
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch { w.machine.editorBacks.collect { backs++ } }
        w.machine.dispatch(SettingsEvent.EditorBack)
        job.cancel()
        assertEquals(1, backs)
        assertNotNull(w.ui.editor, "the overlay decides (field → Cancel, chips → field)")
    }

    @Test
    fun pageNavigationForOtherWpsPages() {
        val w = SettingsWorld().open(SettingsRoute.FirstRun)
        val guide = ArrayList<app.thebox.core.guide.GuideEvent>()
        w.machine.attachGuide { guide += it }
        w.machine.push(SettingsRoute.AddSource(firstRun = true)); w.pump()
        w.machine.replace(SettingsRoute.ImportProgress(5, firstRun = true)); w.pump()
        assertEquals(listOf<SettingsRoute>(SettingsRoute.FirstRun), w.ui.stack, "replace skips the old page on BACK")
        var asked = 0
        w.machine.backHandler = { r -> asked++; r is SettingsRoute.ImportProgress && asked == 1 }
        assertTrue(w.act(BoxAction.BACK))
        assertEquals(SettingsRoute.ImportProgress(5, firstRun = true), w.ui.route, "the source consumed the first BACK")
        w.machine.confirm(app.thebox.core.settings.ConfirmSpec("T", "B", "Stop", "Keep"), app.thebox.core.settings.RowIntent.Run(RowId("setup.stop")))
        assertNotNull(w.ui.confirm)
        w.machine.popTo(SettingsRoute.FirstRun); w.pump()
        assertEquals(SettingsRoute.FirstRun, w.ui.route)
        w.machine.leaveToGuide()
        assertEquals(app.thebox.core.input.BoxAction.OPEN_GUIDE, (guide.single() as app.thebox.core.guide.GuideEvent.Act).action)
    }

    @Test
    fun pinFirstThenTheActionRuns() {
        val w = SettingsWorld(pinSet = true).open()
        w.enter(SectionId.CHANNELS); w.focus(RowId("fx.lists")); w.act(BoxAction.RIGHT)
        w.focus(RowId("fx.pin")); w.act(BoxAction.SELECT)
        assertTrue(w.effects.intents.isEmpty())
        val purpose = w.gate.requests.single()
        w.machine.dispatch(SettingsEvent.PinResult(purpose, granted = true)); w.pump()
        assertEquals("Run", w.effects.intents.single()::class.simpleName)
    }
}
