package app.thebox.ui.search

import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideSearch
import app.thebox.core.guide.GuideToast
import app.thebox.core.guide.ProgrammeHit
import app.thebox.core.guide.SearchResults
import app.thebox.core.guide.fakes.FakeGuideLibrary
import app.thebox.core.guide.fakes.FakeGuideSearch
import app.thebox.core.guide.fakes.FakeGuideSessionStore
import app.thebox.core.guide.fakes.FakeGuideSettings
import app.thebox.core.guide.fakes.FakeReminderBook
import app.thebox.core.guide.fakes.GuideFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** docs/design/m3-guide.md §5.2 `SearchViewModelTest` (§3.10.3; moved from WP-C, S15; B7). */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val nowMin = GuideFixtures.NOW_MIN
    private val channel = GuideFixtures.channel(1, name = "BBC News")
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun TestScope.vm(search: GuideSearch = FakeGuideSearch(listOf(channel)), reminders: FakeReminderBook = FakeReminderBook()) =
        SearchViewModel(search, reminders, FakeGuideLibrary(listOf(channel)), FakeGuideSettings(), FakeGuideSessionStore()) {
            nowMin * 60_000L
        }

    private fun TestScope.effects(vm: SearchViewModel): MutableList<SearchEffect> {
        val out = ArrayList<SearchEffect>()
        backgroundScope.launch(dispatcher) { vm.effects.collect { out += it } }
        runCurrent()
        return out
    }

    private fun hit(start: Int, end: Int, inArchive: Boolean = false) =
        ProgrammeHit(channel, GuideFixtures.info(start, end, "News"), end, inArchive)

    @Test
    fun debounces300ms() = runTest(dispatcher) {
        val search = FakeGuideSearch(listOf(channel))
        val vm = vm(search)
        vm.onQuery("ne")
        advanceTimeBy(299)
        assertTrue(search.queries.isEmpty())
        assertTrue(vm.state.value.searching)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("ne"), search.queries)
        assertEquals(listOf(channel), vm.results.value!!.channels)
        assertFalse(vm.state.value.searching)
    }

    @Test
    fun typingCancelsThePendingAndTheRunningSearch() = runTest(dispatcher) {
        val seen = ArrayList<String>()
        val slow = object : GuideSearch {
            override suspend fun search(query: String, nowMs: Long, unlocked: Boolean): SearchResults {
                seen += query
                delay(1_000)
                return SearchResults(query, listOf(channel), emptyList())
            }
        }
        val vm = vm(slow)
        vm.onQuery("ne")
        advanceTimeBy(200)
        vm.onQuery("new") // replaces the debounced one
        advanceTimeBy(350) // "new" is running
        assertEquals(listOf("new"), seen)
        vm.onQuery("news") // cancels the running one
        advanceUntilIdle()
        assertEquals(listOf("new", "news"), seen)
        assertEquals("news", vm.results.value!!.query)
        assertEquals(2, vm.started)
    }

    @Test
    fun twoCharacterMinimum() = runTest(dispatcher) {
        val search = FakeGuideSearch(listOf(channel))
        val vm = vm(search)
        vm.onQuery("n")
        advanceUntilIdle()
        assertTrue(search.queries.isEmpty())
        assertNull(vm.results.value)
        vm.onQuery("n!")
        advanceUntilIdle()
        assertTrue("punctuation does not count", search.queries.isEmpty())
        vm.onQuery("n1")
        advanceUntilIdle()
        assertEquals(listOf("n1"), search.queries)
        vm.onQuery("n") // back under the minimum clears the results
        advanceUntilIdle()
        assertNull(vm.results.value)
    }

    @Test
    fun backClosesWithoutATune() = runTest(dispatcher) {
        val vm = vm()
        val out = effects(vm)
        vm.onBack()
        runCurrent()
        assertEquals(listOf<SearchEffect>(SearchEffect.Close(GuideEvent.SearchClosed(null, tuned = false))), out)
    }

    @Test
    fun tuneFromAChannelOrAnOnNowProgrammeClosesTuned() = runTest(dispatcher) {
        val vm = vm()
        val out = effects(vm)
        vm.onChannel(channel)
        vm.onProgramme(hit(nowMin - 10, nowMin + 20))
        runCurrent()
        val closed = SearchEffect.Close(GuideEvent.SearchClosed(channel, tuned = true))
        assertEquals(listOf(SearchEffect.Tune(channel), closed, SearchEffect.Tune(channel), closed), out)
    }

    @Test
    fun archiveProgrammePlaysCatchUpAndFutureOneTogglesItsReminder() = runTest(dispatcher) {
        val reminders = FakeReminderBook()
        val vm = vm(reminders = reminders)
        val out = effects(vm)
        val past = hit(nowMin - 120, nowMin - 60, inArchive = true)
        vm.onProgramme(past)
        runCurrent()
        assertEquals(listOf(SearchEffect.PlayArchive(channel, past.info), SearchEffect.Close(GuideEvent.SearchClosed(channel, tuned = true, archive = true))), out)
        out.clear()
        val future = hit(nowMin + 60, nowMin + 120)
        vm.onProgramme(future)
        advanceUntilIdle(); runCurrent() // background collectors run on runCurrent
        assertEquals(listOf<SearchEffect>(SearchEffect.Toast(GuideToast.REMINDER_SET_FIRST_TIME)), out)
        assertTrue(vm.hasReminder(future))
        vm.onProgramme(future)
        advanceUntilIdle(); runCurrent() // background collectors run on runCurrent
        assertEquals(SearchEffect.Toast(GuideToast.REMINDER_CANCELLED), out.last())
        assertFalse(vm.hasReminder(future))
        vm.onProgramme(future)
        advanceUntilIdle(); runCurrent() // background collectors run on runCurrent
        assertEquals("the first-time toast shows once", SearchEffect.Toast(GuideToast.REMINDER_SET), out.last())
        // Past and not in the archive: nothing to play.
        vm.onProgramme(hit(nowMin - 120, nowMin - 60))
        runCurrent()
        assertEquals(SearchEffect.Toast(GuideToast.NO_CATCHUP), out.last())
    }

    /** §2.13 item 7: "no guide data" comes from the results, not from an injected EpgStore. */
    @Test
    fun noGuideDataIsReported() = runTest(dispatcher) {
        val noGuide = object : GuideSearch {
            override suspend fun search(query: String, nowMs: Long, unlocked: Boolean) =
                SearchResults(query, listOf(channel), emptyList(), guideDataMissing = true)
        }
        val vm = vm(noGuide)
        assertFalse(vm.state.value.guideDataMissing)
        vm.onQuery("bbc")
        advanceUntilIdle()
        assertTrue(vm.state.value.guideDataMissing)
        assertEquals(listOf(channel), vm.state.value.results!!.channels)
        val withGuide = vm(FakeGuideSearch(listOf(channel)))
        withGuide.onQuery("bbc")
        advanceUntilIdle()
        assertFalse(withGuide.state.value.guideDataMissing)
    }

    @Test
    fun favouriteToggle() = runTest(dispatcher) {
        val vm = vm()
        val out = effects(vm)
        vm.toggleFavourite(channel)
        advanceUntilIdle(); runCurrent()
        assertTrue(vm.isFavourite(channel))
        assertEquals(SearchEffect.Toast(GuideToast.ADDED_FAVOURITE, "BBC News"), out.last())
        vm.toggleFavourite(channel)
        advanceUntilIdle(); runCurrent()
        assertFalse(vm.isFavourite(channel))
    }
}
