package app.thebox.ui.editor

import androidx.test.core.app.ApplicationProvider
import app.thebox.core.editor.ChannelEditorEvent
import app.thebox.core.editor.ChannelEditorMachine
import app.thebox.core.editor.ChannelEditorMachineFactory
import app.thebox.core.editor.ChannelEditorUiState
import app.thebox.core.editor.ChannelTarget
import app.thebox.core.editor.EditorFilter
import app.thebox.core.editor.EditorList
import app.thebox.core.editor.EditorText
import app.thebox.core.editor.EditorWrite
import app.thebox.core.editor.GroupsPane
import app.thebox.core.editor.WriteResult
import app.thebox.core.guide.GuideEvent
import app.thebox.core.settings.SettingsRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The view model's effects over [FakeEditorRepository] (docs/design/m6-channel-editor.md §3.3 machine rules): versions,
 * `keepKey` answers, page versions, the change feed, the FIFO, and the shell seams' defaults. A recording machine stands
 * in for the real one so every event the view model sends is visible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChannelEditorViewModelTest {
    private val texts = ResourceEditorTexts(ApplicationProvider.getApplicationContext())

    private class RecordingMachine : ChannelEditorMachine {
        val events = ArrayList<ChannelEditorEvent>()
        val opened = ArrayList<Pair<Long, String?>>()
        var closes = 0
        override val ui = MutableStateFlow(
            ChannelEditorUiState(0L, "", groups = GroupsPane(listOf(EditorList.All), null, 0, 0), list = EditorList.All, channels = null),
        )
        override val nextDeadlineMs = MutableStateFlow(Long.MAX_VALUE)
        override fun open(playlistId: Long, groupKey: String?) { opened += playlistId to groupKey }
        override fun act(action: GuideEvent.Act): Boolean = true
        override fun dispatch(event: ChannelEditorEvent) { events += event }
        override fun close() { closes++ }
        inline fun <reified T : ChannelEditorEvent> last(): T = events.filterIsInstance<T>().last()
    }

    private class World(test: TestScope, val repo: FakeEditorRepository, parental: FakeParentalSession, texts: ResourceEditorTexts) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val writeScope = CoroutineScope(SupervisorJob() + dispatcher)
        val machine = RecordingMachine()
        val vm = ChannelEditorViewModel(
            ChannelEditorMachineFactory { _, _, _ -> machine }, repo, parental, texts, EditorWriteRunner(repo, writeScope, dispatcher),
            store = null, scope = scope, main = dispatcher, io = dispatcher, nowMs = { test.testScheduler.currentTime },
        )
        fun end() { scope.cancel(); writeScope.cancel() }
    }

    private fun world(test: TestScope, repo: FakeEditorRepository = FakeEditorRepository.sample(), parental: FakeParentalSession = FakeParentalSession()) =
        World(test, repo, parental, texts)

    @Test
    fun versionsIncreaseOnEveryLoadAndKeepKeyIsAnsweredByIndex() = runTest {
        val w = world(this)
        w.vm.open(SettingsRoute.ChannelEditor(1L))
        assertEquals(listOf(1L to null), w.machine.opened)
        w.vm.loadGroups(1L, true, "sport")
        runCurrent()
        val g = w.machine.last<ChannelEditorEvent.Groups>()
        assertEquals(6, g.count); assertEquals(2, g.hidden); assertEquals(1, g.cursorIndex)
        w.vm.loadChannels(1L, EditorList.Group("ent"), EditorFilter.ALL, "itv1")
        runCurrent()
        val c1 = w.machine.last<ChannelEditorEvent.Channels>()
        assertTrue(c1.version > g.version)
        assertEquals(2, c1.cursorIndex); assertEquals(6, c1.count); assertEquals(1, c1.hidden)
        w.vm.loadChannels(1L, EditorList.Group("ent"), EditorFilter.ALL, null)
        runCurrent()
        val c2 = w.machine.last<ChannelEditorEvent.Channels>()
        assertTrue(c2.version > c1.version)
        assertNull(c2.cursorIndex)
        // A key that left the list answers null (keep the index).
        w.vm.loadChannels(1L, EditorList.Group("ent"), EditorFilter.SHOWN, "ch4")
        runCurrent()
        assertNull(w.machine.last<ChannelEditorEvent.Channels>().cursorIndex)
        w.end()
    }

    @Test
    fun pagesAreReadOnlyForTheCurrentVersionAndNeverUnasked() = runTest {
        val w = world(this)
        w.vm.open(SettingsRoute.ChannelEditor(1L, "ent"))
        w.vm.loadChannels(1L, EditorList.Group("ent"), EditorFilter.ALL, null)
        runCurrent()
        assertTrue(w.machine.events.none { it is ChannelEditorEvent.ChannelRows || it is ChannelEditorEvent.GroupRows })
        val v1 = w.machine.last<ChannelEditorEvent.Channels>().version
        w.vm.loadChannels(1L, EditorList.Group("ent"), EditorFilter.ALL, null)
        runCurrent()
        val v2 = w.machine.last<ChannelEditorEvent.Channels>().version
        w.vm.loadChannelRows(v1, 0, 6)
        runCurrent()
        assertTrue(w.machine.events.none { it is ChannelEditorEvent.ChannelRows })
        w.vm.loadChannelRows(v2, 0, 6)
        runCurrent()
        val rows = w.machine.last<ChannelEditorEvent.ChannelRows>()
        assertEquals(v2, rows.version); assertEquals(0, rows.from)
        assertEquals(listOf("bbc1", "bbc2", "itv1", "ch4", "sky", "dave"), rows.rows.map { it!!.stableKey })
        w.vm.loadGroups(1L, true, null)
        runCurrent()
        val gv = w.machine.last<ChannelEditorEvent.Groups>().version
        w.vm.loadGroupRows(gv, 2, 4)
        runCurrent()
        val gr = w.machine.last<ChannelEditorEvent.GroupRows>()
        assertEquals(2, gr.from); assertEquals(listOf("news", "adult"), gr.rows.map { it.groupKey })
        w.end()
    }

    /** Review S2: a change-feed burst becomes one Changed for the machine; the view model issues no load of its own. */
    @Test
    fun aDataChangeGoesThroughTheMachineAndNeverCompetesWithItsKeyedLoad() = runTest {
        val w = world(this)
        w.vm.open(SettingsRoute.ChannelEditor(1L, "ent"))
        runCurrent()
        w.vm.loadChannels(1L, EditorList.Group("ent"), EditorFilter.ALL, "bbc2")
        // The write's feed fires (twice: two table groups) while the machine's keyed load is still in flight.
        w.repo.changesFlow.tryEmit(Unit)
        w.repo.changesFlow.tryEmit(Unit)
        advanceTimeBy(ChannelEditorViewModel.CHANGE_GAP_MS + 1)
        runCurrent()
        assertEquals("one Changed per burst", 1, w.machine.events.count { it == ChannelEditorEvent.Changed })
        assertEquals("only the machine's keyed list read, none of the view model's own", 1, w.repo.channelsCalls)
        assertEquals("the keyed load was not cancelled", 1, w.machine.last<ChannelEditorEvent.Channels>().cursorIndex)
        w.end()
    }

    /** Review S3: a write that throws (even a CancellationException) is answered STORAGE and the runner goes on. */
    @Test
    fun theWriteRunnerSurvivesAThrowingWriteAndAnswersEveryWrite() = runTest {
        val w = world(this)
        w.vm.open(SettingsRoute.ChannelEditor(1L, "ent"))
        w.repo.throwOnNextWrite = kotlinx.coroutines.CancellationException("transaction cancelled")
        val a = EditorWrite.HideChannels(1L, ChannelTarget.Keys(setOf("bbc1")), true)
        val b = EditorWrite.HideChannels(1L, ChannelTarget.Keys(setOf("bbc2")), true)
        w.vm.write(a)
        advanceUntilIdle()
        w.vm.write(b)
        advanceUntilIdle()
        w.repo.throwOnNextWrite = IllegalStateException("disk")
        val c = EditorWrite.Favorite(1L, ChannelTarget.Keys(setOf("sky")), true)
        w.vm.write(c)
        advanceUntilIdle()
        val written = w.machine.events.filterIsInstance<ChannelEditorEvent.Written>()
        assertEquals(listOf(a, b, c), written.map { it.write })
        assertEquals(WriteResult.Failed(app.thebox.core.editor.WriteFailure.STORAGE), written[0].result)
        assertTrue(written[1].result is WriteResult.Done)
        assertEquals(WriteResult.Failed(app.thebox.core.editor.WriteFailure.STORAGE), written[2].result)
        w.end()
    }

    /** Review S4: a failed count is SummaryFailed(token), never a list failure; closing the menu cancels the count. */
    @Test
    fun aSummaryFailureIsTheMenusAndAClosedMenuCancelsItsCount() = runTest {
        val w = world(this)
        w.vm.open(SettingsRoute.ChannelEditor(1L, "ent"))
        val target = ChannelTarget.Keys(setOf("bbc1", "bbc2"))
        val open = w.machine.ui.value.copy(menu = app.thebox.core.editor.EditorMenu(app.thebox.core.editor.EditorPane.CHANNELS, "2", emptyList(), 0, loading = true))
        w.machine.ui.value = open // the machine publishes the loading menu as it asks for the count
        runCurrent()
        w.repo.summaryError = IllegalStateException("io")
        w.vm.summarize(7L, 1L, target)
        runCurrent()
        assertTrue(w.machine.events.contains(ChannelEditorEvent.SummaryFailed(7L)))
        assertTrue(w.machine.events.none { it is ChannelEditorEvent.LoadFailed })
        // A count in flight while its menu is open, then the menu closes: no late Summary arrives.
        w.repo.summaryError = null
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        w.repo.summaryGate = gate
        w.vm.summarize(8L, 1L, target)
        runCurrent()
        w.machine.ui.value = open.copy(menu = null)
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(w.machine.events.none { it is ChannelEditorEvent.Summary || it == ChannelEditorEvent.SummaryFailed(8L) })
        w.end()
    }

    @Test
    fun playlistGoneAndLoadFailuresReachTheMachine() = runTest {
        val repo = FakeEditorRepository.sample().apply { exists = false }
        val w = world(this, repo)
        w.vm.open(SettingsRoute.ChannelEditor(1L))
        w.vm.loadGroups(1L, true, null)
        runCurrent()
        assertTrue(w.machine.events.contains(ChannelEditorEvent.PlaylistGone))
        w.end()
    }

    @Test
    fun writesRunInOrderInTheWriteScopeAndSurviveLeavingTheEditor() = runTest {
        val w = world(this)
        w.vm.open(SettingsRoute.ChannelEditor(1L, "ent"))
        val a = EditorWrite.HideChannels(1L, ChannelTarget.Keys(setOf("bbc1")), true)
        val b = EditorWrite.Favorite(1L, ChannelTarget.Keys(setOf("bbc2")), true)
        val c = EditorWrite.RenameChannel(1L, "itv1", "ITV")
        w.vm.write(a); w.vm.write(b); w.vm.write(c)
        w.vm.close() // Back out of the editor before they run
        advanceUntilIdle()
        assertEquals(listOf(a, b, c), w.repo.writes)
        val written = w.machine.events.filterIsInstance<ChannelEditorEvent.Written>()
        assertEquals(listOf(a, b, c), written.map { it.write })
        assertTrue(written.all { it.result is WriteResult.Done })
        assertEquals(1, w.machine.closes)
        w.end()
    }

    @Test
    fun accessFollowsTheParentalSessionFromTheStart() = runTest {
        val parental = FakeParentalSession(pinSet = true, unlocked = false)
        val w = world(this, parental = parental)
        runCurrent()
        assertEquals(ChannelEditorEvent.Access(pinSet = true, unlocked = false), w.machine.last<ChannelEditorEvent.Access>())
        parental.unlock(0)
        runCurrent()
        assertEquals(ChannelEditorEvent.Access(pinSet = true, unlocked = true), w.machine.last<ChannelEditorEvent.Access>())
        w.end()
    }

    @Test
    fun withoutTheShellTheHandOffsAnswerAtOnce() = runTest {
        val w = world(this)
        w.vm.open(SettingsRoute.ChannelEditor(1L))
        w.vm.editText(EditorText.EDIT_FIND, "", allowEmpty = false)
        w.vm.editNumber(EditorText.EDIT_NUMBER, 5)
        w.vm.requestPin()
        runCurrent()
        assertTrue(w.machine.events.contains(ChannelEditorEvent.TextResult(null)))
        assertTrue(w.machine.events.contains(ChannelEditorEvent.NumberResult(null)))
        assertTrue(w.machine.events.contains(ChannelEditorEvent.PinResult(false)))
        w.end()
    }

    @Test
    fun theRoutesPageIsTitledWithThePlaylist() = runTest {
        val source = ChannelEditorPageSource(texts) { id -> if (id == 1L) "MyProvider" else null }
        val route = SettingsRoute.ChannelEditor(1L)
        assertTrue(source.handles(route) && !source.handles(SettingsRoute.Root))
        val page = source.page(route, app.thebox.core.settings.state.PageContext(advanced = false, nowMs = 0))
        assertEquals("Channel editor · MyProvider", page.title)
        assertTrue(page.rows.isEmpty())
        assertEquals("Channel editor", source.page(SettingsRoute.ChannelEditor(2L), app.thebox.core.settings.state.PageContext(false, 0)).title)
    }
}
