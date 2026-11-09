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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import app.thebox.core.guide.GuideLibrary
import app.thebox.core.refresh.ChannelIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.parental.ParentalState
import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.RelockReason
import app.thebox.core.settings.fakes.FakeParentalSession
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
import org.junit.Assert.assertSame
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

    private fun TestScope.vm(search: GuideSearch = FakeGuideSearch(listOf(channel), listOf(
        hit(nowMin - 10, nowMin + 20),
        hit(nowMin - 120, nowMin - 60, inArchive = true),
        hit(nowMin - 120, nowMin - 60),
        hit(nowMin + 60, nowMin + 120),
    )), reminders: FakeReminderBook = FakeReminderBook()) =
        SearchViewModel(search, reminders, FakeGuideLibrary(listOf(channel)), FakeGuideSettings(), FakeGuideSessionStore()) {
            nowMin * 60_000L
        }

    private fun TestScope.effects(vm: SearchViewModel): MutableList<SearchEffect> {
        val out = ArrayList<SearchEffect>()
        backgroundScope.launch(dispatcher) { vm.effects.collect { out += it } }
        runCurrent()
        return out
    }

    /** Actions use the same published snapshot as the screen, including after reopening Search. */
    private fun TestScope.snapshot(vm: SearchViewModel): SearchResults {
        vm.onEntered()
        if (vm.state.value.query.isEmpty()) vm.onQuery("news") else vm.refresh()
        advanceUntilIdle()
        return vm.results.value!!
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
            override suspend fun search(query: String, nowMs: Long, access: app.thebox.core.parental.ParentalAccess): SearchResults {
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
        vm.onChannel(channel, snapshot(vm))
        vm.onProgramme(hit(nowMin - 10, nowMin + 20), snapshot(vm))
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
        vm.onProgramme(past, snapshot(vm))
        runCurrent()
        assertEquals(listOf(SearchEffect.PlayArchive(channel, past.info), SearchEffect.Close(GuideEvent.SearchClosed(channel, tuned = true, archive = true))), out)
        out.clear()
        val future = hit(nowMin + 60, nowMin + 120)
        vm.onProgramme(future, snapshot(vm))
        advanceUntilIdle(); runCurrent() // background collectors run on runCurrent
        assertEquals(listOf<SearchEffect>(SearchEffect.Toast(GuideToast.REMINDER_SET_FIRST_TIME)), out)
        assertTrue(vm.hasReminder(future))
        vm.onProgramme(future, snapshot(vm))
        advanceUntilIdle(); runCurrent() // background collectors run on runCurrent
        assertEquals(SearchEffect.Toast(GuideToast.REMINDER_CANCELLED), out.last())
        assertFalse(vm.hasReminder(future))
        vm.onProgramme(future, snapshot(vm))
        advanceUntilIdle(); runCurrent() // background collectors run on runCurrent
        assertEquals("the first-time toast shows once", SearchEffect.Toast(GuideToast.REMINDER_SET), out.last())
        // Past and not in the archive: nothing to play.
        vm.onProgramme(hit(nowMin - 120, nowMin - 60), snapshot(vm))
        runCurrent()
        assertEquals(SearchEffect.Toast(GuideToast.NO_CATCHUP), out.last())
    }

    /** §2.13 item 7: "no guide data" comes from the results, not from an injected EpgStore. */
    @Test
    fun noGuideDataIsReported() = runTest(dispatcher) {
        val noGuide = object : GuideSearch {
            override suspend fun search(query: String, nowMs: Long, access: app.thebox.core.parental.ParentalAccess) =
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
        vm.toggleFavourite(channel, snapshot(vm))
        advanceUntilIdle(); runCurrent()
        assertTrue(vm.isFavourite(channel))
        assertEquals(SearchEffect.Toast(GuideToast.ADDED_FAVOURITE, "BBC News"), out.last())
        vm.toggleFavourite(channel, snapshot(vm))
        advanceUntilIdle(); runCurrent()
        assertFalse(vm.isFavourite(channel))
    }

    @Test
    fun relockClearsProtectedResultsAndRequeriesTheUnchangedText() = runTest(dispatcher) {
        val parental = FakeParentalSession(ParentalState(true, true, ParentalPolicy()))
        val calls = ArrayList<ParentalAccess>()
        val search = object : GuideSearch {
            override suspend fun search(query: String, nowMs: Long, access: ParentalAccess): SearchResults {
                calls += access
                return SearchResults(query, if (access == ParentalAccess.OPEN) listOf(channel) else emptyList(), emptyList())
            }
        }
        val vm = SearchViewModel(search, FakeReminderBook(), FakeGuideLibrary(listOf(channel)), FakeGuideSettings(), FakeGuideSessionStore(), parental) { nowMin * 60_000L }
        runCurrent()
        vm.onQuery("news")
        advanceUntilIdle()
        val old = vm.results.value!!
        assertTrue(vm.isCurrent(old))
        parental.relock(RelockReason.APP_STOPPED)
        assertFalse("actions reject the old access even before its collector runs", vm.isCurrent(old))
        runCurrent()
        assertNull(vm.results.value)
        assertEquals("news", vm.state.value.query)
        advanceUntilIdle()
        assertTrue(vm.results.value!!.channels.isEmpty())
        assertEquals(listOf(ParentalAccess.OPEN, ParentalAccess.LOCKED_AUTO), calls)
        // Returning to Search re-runs even when the query and access are unchanged.
        vm.onBack()
        vm.onEntered()
        advanceUntilIdle()
        assertEquals(3, calls.size)
        assertFalse(vm.isCurrent(old))
    }

    @Test
    fun aCancelledOpenSearchCannotRepublishAfterRelock() = runTest(dispatcher) {
        val parental = FakeParentalSession(ParentalState(true, true, ParentalPolicy()))
        var oldSearchReturned = false
        val search = object : GuideSearch {
            override suspend fun search(query: String, nowMs: Long, access: ParentalAccess): SearchResults {
                if (access == ParentalAccess.OPEN) withContext(NonCancellable) { delay(1_000) }
                if (access == ParentalAccess.OPEN) oldSearchReturned = true
                return SearchResults(query, if (access == ParentalAccess.OPEN) listOf(channel) else emptyList(), emptyList())
            }
        }
        val vm = SearchViewModel(search, FakeReminderBook(), FakeGuideLibrary(listOf(channel)), FakeGuideSettings(), FakeGuideSessionStore(), parental) { nowMin * 60_000L }
        runCurrent()
        vm.onQuery("news")
        advanceTimeBy(350)
        parental.relock(RelockReason.TIMEOUT)
        runCurrent()
        advanceTimeBy(350)
        runCurrent()
        assertTrue(vm.results.value!!.channels.isEmpty())
        advanceUntilIdle() // the cancellation-ignoring OPEN result finally returns
        assertTrue("the stale provider returned despite cancellation", oldSearchReturned)
        assertTrue(vm.results.value!!.channels.isEmpty())
        assertFalse(vm.state.value.searching)
    }

    @Test
    fun contentAndDebugAccessChangesInvalidateSnapshotsWithoutChangingQuery() = runTest(dispatcher) {
        val library = FakeGuideLibrary(listOf(channel))
        val settings = FakeGuideSettings()
        val content = MutableStateFlow(0L)
        val search = FakeGuideSearch(listOf(channel))
        val vm = SearchViewModel(search, FakeReminderBook(), library, settings, FakeGuideSessionStore(), contentChanges = content) { nowMin * 60_000L }
        runCurrent()
        vm.onQuery("news")
        advanceUntilIdle()
        val old = vm.results.value!!
        library.tickRowContent()
        runCurrent()
        // U2 (code review 2026-11-09): a content change keeps the shown snapshot (cards and focus) until it is replaced.
        assertSame(old, vm.results.value)
        assertTrue(vm.isCurrent(old))
        advanceUntilIdle()
        assertTrue("an equal re-query keeps the same snapshot (a state flow keeps equal values)", vm.isCurrent(old))
        search.channels = emptyList() // the EPG / library change altered the answer
        content.value++
        runCurrent()
        assertSame(old, vm.results.value)
        advanceUntilIdle()
        assertTrue(vm.results.value!!.channels.isEmpty())
        assertFalse(vm.isCurrent(old))
        search.channels = listOf(channel)
        // An access change still clears first.
        settings.update { it.copy(debugUnlocked = true) }
        runCurrent()
        assertNull(vm.results.value)
        advanceUntilIdle()
        assertEquals(listOf("news", "news", "news", "news"), search.queries)
    }

    /** U2 (code review 2026-11-09): the user's own Add to favourites re-emits the sources; the results must not blank. */
    @Test
    fun addingAFavouriteKeepsTheShownResultsUntilTheRequeryReplacesThem() = runTest(dispatcher) {
        val library = FakeGuideLibrary(listOf(channel))
        val search = FakeGuideSearch(listOf(channel))
        val vm = SearchViewModel(search, FakeReminderBook(), library, FakeGuideSettings(), FakeGuideSessionStore()) { nowMin * 60_000L }
        vm.onEntered()
        vm.onQuery("news")
        advanceUntilIdle()
        val shown = vm.results.value!!
        val seen = ArrayList<SearchResults?>()
        backgroundScope.launch(dispatcher) { vm.results.collect { seen += it } }
        runCurrent()
        vm.toggleFavourite(channel, shown)
        runCurrent()
        library.tickRowContent() // the real library re-emits its sources (the favourite tables) here; the fake does not
        advanceUntilIdle()
        assertTrue(vm.isFavourite(channel))
        assertEquals(2, search.queries.size)
        assertFalse("never published null", seen.any { it == null })
        assertEquals(listOf(channel), vm.results.value!!.channels)
    }

    @Test
    fun relockingDuringFavouriteLookupPreventsMutationAndProtectedNameToast() = runTest(dispatcher) {
        val parental = FakeParentalSession(ParentalState(true, true, ParentalPolicy()))
        val base = FakeGuideLibrary(listOf(channel))
        val lookup = CompletableDeferred<Boolean>()
        val library = object : GuideLibrary by base {
            override suspend fun isFavourite(identity: ChannelIdentity): Boolean = lookup.await()
        }
        val vm = SearchViewModel(FakeGuideSearch(listOf(channel)), FakeReminderBook(), library, FakeGuideSettings(), FakeGuideSessionStore(), parental) { nowMin * 60_000L }
        val out = effects(vm)
        vm.onQuery("news")
        advanceUntilIdle()
        val snapshot = vm.results.value!!
        vm.toggleFavourite(channel, snapshot)
        runCurrent() // suspended inside the lookup
        parental.relock(RelockReason.APP_STOPPED)
        lookup.complete(false)
        runCurrent()
        vm.onChannel(channel, snapshot)
        advanceUntilIdle()
        assertTrue(base.favourites.isEmpty())
        assertTrue("neither tune nor stale protected-name toast is emitted", out.isEmpty())
    }

    @Test
    fun actionsRejectItemsThatAreNotMembersOfThePublishedSnapshot() = runTest(dispatcher) {
        val vm = vm()
        val out = effects(vm)
        val results = snapshot(vm)
        val absent = GuideFixtures.channel(99, name = "Hidden channel")
        vm.onChannel(absent, results)
        vm.toggleFavourite(absent, results)
        vm.onProgramme(hit(nowMin + 200, nowMin + 300), results)
        advanceUntilIdle()
        assertTrue(out.isEmpty())
        assertFalse(vm.isFavourite(absent))
    }

    @Test
    fun contentSubscriptionsExistOnlyDuringSearchVisitsAndReopenRefreshes() = runTest(dispatcher) {
        val base = FakeGuideLibrary(listOf(channel))
        var subscriptions = 0
        val library = object : GuideLibrary by base {
            override fun sources(access: ParentalAccess): Flow<List<GuideSourceEntry>> = flow {
                subscriptions++
                try { base.sources(access).collect { emit(it) } } finally { subscriptions-- }
            }
        }
        val search = FakeGuideSearch(listOf(channel))
        val vm = SearchViewModel(search, FakeReminderBook(), library, FakeGuideSettings(), FakeGuideSessionStore()) { nowMin * 60_000L }
        runCurrent()
        assertEquals(0, subscriptions)
        vm.onQuery("news") // typing can activate, as well as composition entry
        advanceUntilIdle()
        assertEquals(1, subscriptions)
        assertEquals(1, search.queries.size)
        vm.onEntered() // does not restart the already active visit
        advanceUntilIdle()
        assertEquals(1, search.queries.size)
        vm.onLeft()
        runCurrent()
        assertEquals(0, subscriptions)
        assertNull(vm.results.value)
        base.tickRowContent()
        advanceUntilIdle()
        assertEquals(1, search.queries.size)
        vm.onEntered()
        advanceUntilIdle()
        assertEquals(1, subscriptions)
        assertEquals(listOf("news", "news"), search.queries)
        vm.onLeft()
        runCurrent()
        assertEquals(0, subscriptions)
    }
}
