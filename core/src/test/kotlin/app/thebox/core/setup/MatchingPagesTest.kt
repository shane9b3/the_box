package app.thebox.core.setup

import app.thebox.core.epg.EpgChannelInfo
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.match.MatchCandidate
import app.thebox.core.match.MatchMethod
import app.thebox.core.model.StableKey
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.ParentalState
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.settings.PagedList
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.setup.fakes.FakeSourceSetup
import app.thebox.core.setup.pages.FixPickerPage
import app.thebox.core.setup.pages.FixUnmatchedPage
import app.thebox.core.setup.pages.PageAction
import app.thebox.core.setup.pages.SetupPageDeps
import app.thebox.core.setup.pages.SetupPages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fix unmatched channels and the mapping picker (§3.6.3, S13), as page-builder tables over fake ports (§6.1 WP-B). */
@OptIn(ExperimentalCoroutinesApi::class)
class MatchingPagesTest {
    private val matching = FakeMatching()
    private val host = RecordingHost()
    private val parental = MutableStateFlow(ParentalState(pinSet = true, unlocked = false, policy = ParentalPolicy()))

    private fun ch(key: String, name: String = key, watch: Int = 0, score: Float? = null) =
        UnmatchedChannel(ChannelIdentity(1, StableKey(key)), name, "News", watch, score)

    private fun pages(scope: CoroutineScope) = SetupPages(
        SetupPageDeps(
            strings = KeyStrings(), normalizer = SourceInputNormalizerImpl(), setup = FakeSourceSetup(), admin = FakePlaylistAdmin(),
            groups = FakeGroups(), matching = matching, store = FakeSettingsStore(), parental = parental, scope = scope,
            nowMs = { 1_790_000_000_000L }, locale = { Locale.UK }, zone = { ZoneOffset.UTC },
        ),
    )

    @Test
    fun theUnmatchedListIsPagedWithTheNoDataChannelsAfterADivider() = runTest(UnconfinedTestDispatcher()) {
        matching.unmatched.value = (0 until 40).map { ch("c$it", "Channel $it", watch = 40 - it, score = if (it == 0) 0.94f else null) }
        matching.noData.value = listOf(ch("n1", "No Data One"))
        val p = pages(backgroundScope)
        val route = SettingsRoute.FixGuide(null)
        p.open(route, host)
        assertEquals(ParentalAccess.LOCKED_AUTO, matching.accessSeen, "the list is queried with the session's access (S10)")
        val list = assertNotNull(host.last.list)
        assertEquals(42, list.count, "40 unmatched + divider + 1 no-data")
        val first = list.loaded.getValue(0)
        assertEquals(PagedList.PAGE_SIZE, first.size)
        assertEquals("fix.ch.1.c0", first[0].id.key)
        assertEquals("FIX_NO_DATA_SUGGESTED(94)", first[0].description)
        assertEquals("FIX_NO_DATA", first[1].description)
        assertEquals(SettingsRoute.FixGuide(ChannelIdentity(1, StableKey("c0"))), (first[0].kind as RowKind.Item).route)
        assertEquals("FIX_SUBTITLE(40)", host.last.subtitle)

        p.loadItems(route, 32, 64, host)
        val (from, rows) = host.items.single()
        assertEquals(32, from)
        assertEquals(10, rows.size)
        assertEquals(FixUnmatchedPage.ROW_DIVIDER, rows[8].id.key)
        assertEquals("FIX_NO_PROGRAMMES", rows[9].description)
    }

    @Test
    fun anEmptyListSaysEveryChannelHasData() = runTest(UnconfinedTestDispatcher()) {
        pages(backgroundScope).open(SettingsRoute.FixGuide(null), host)
        assertEquals("FIX_NONE", host.last.placeholder)
        assertNull(host.last.list)
    }

    @Test
    fun thePickerShowsSuggestionsWithConfidenceAndTheNowLine() = runTest(UnconfinedTestDispatcher()) {
        val c = ChannelIdentity(1, StableKey("bbc1"))
        matching.unmatched.value = listOf(UnmatchedChannel(c, "BBC One HD", "UK", 9, 0.94f))
        val ref = EpgChannelRef(7, "bbc1.uk")
        matching.suggestions[c] = listOf(MatchCandidate(ref, "BBC One", 0.94f, MatchMethod.FUZZY), MatchCandidate(EpgChannelRef(7, "bbc2.uk"), "BBC Two", 0.61f, MatchMethod.FUZZY))
        matching.now = mapOf(ref to NowAiring("News", 1_790_000_000_000L - 600_000, 1_790_000_000_000L + 1_200_000))
        pages(backgroundScope).open(SettingsRoute.FixGuide(c), host)
        assertEquals("BBC One HD", host.last.title)
        assertEquals(listOf("fix.search", "fix.suggested", "fix.suggest.0", "fix.suggest.1", "fix.shift"), host.ids())
        val s0 = host.row("fix.suggest.0")
        assertEquals("BBC One", s0.label)
        assertEquals("FIX_SUGGESTED(94)", s0.value)
        assertEquals("FIX_NOW(News|14:03|14:33)", s0.description)
        assertEquals("bbc2.uk", host.row("fix.suggest.1").description, "no programme now: the id instead")
    }

    @Test
    fun oneEditorCommitIsOneSearchAndOkConfirmsTheChoice() = runTest(UnconfinedTestDispatcher()) {
        val c = ChannelIdentity(1, StableKey("itv"))
        matching.unmatched.value = listOf(UnmatchedChannel(c, "ITV1", "UK", 0, null))
        matching.epgChannels += EpgChannelInfo(3, "itv1.uk", listOf("ITV 1 London"), null, true, 120)
        matching.epgChannels += EpgChannelInfo(3, "itv2.uk", listOf("ITV 2"), null, true, 80)
        val p = pages(backgroundScope)
        val route = SettingsRoute.FixGuide(c)
        p.open(route, host)
        p.onIntent(route, RowIntent.Edit(RowId(FixPickerPage.ROW_SEARCH), "  itv ​"), host)
        assertEquals(listOf("itv"), matching.searches)
        assertEquals(listOf("fix.search", "fix.result.0", "fix.result.1", "fix.shift"), host.ids())
        p.onIntent(route, RowIntent.Run(RowId("fix.result.0")), host)
        assertEquals(listOf("choose(itv,itv1.uk)"), matching.chosen)
        assertEquals(PageAction.Pop, host.actions.last(), "the channel leaves the list: back to it")
        assertTrue(matching.unmatched.value.isEmpty())

        p.onIntent(route, RowIntent.Edit(RowId(FixPickerPage.ROW_SEARCH), "zzz"), host)
        assertEquals("FIX_NO_RESULTS(zzz)", host.row("fix.no_results").label)
    }

    @Test
    fun aHandMadeChoiceCanBeRemovedAndTheShiftIsPerChannel() = runTest(UnconfinedTestDispatcher()) {
        val c = ChannelIdentity(1, StableKey("x"))
        matching.overrides[c] = EpgChannelRef(2, "x.tv")
        val p = pages(backgroundScope)
        val route = SettingsRoute.FixGuide(c)
        p.open(route, host)
        assertEquals("x.tv", host.row(FixPickerPage.ROW_CLEAR).value)
        p.onIntent(route, RowIntent.Run(RowId(FixPickerPage.ROW_CLEAR)), host)
        assertEquals(listOf("clear(x)"), matching.chosen)
        assertNull(host.rowOrNull(FixPickerPage.ROW_CLEAR))
        assertEquals(PageAction.Notice("FIX_CLEARED"), host.actions.last())

        val shift = host.row(FixPickerPage.ROW_SHIFT).kind
        assertIs<RowKind.Choice>(shift)
        assertEquals(49, shift.options.size, "−12 h … +12 h in 0.5 h")
        assertEquals("0", shift.options[shift.selected].id)
        p.onIntent(route, RowIntent.Choose(RowId(FixPickerPage.ROW_SHIFT), "-90"), host)
        assertEquals(-90, matching.shifts[c])
        val after = host.row(FixPickerPage.ROW_SHIFT)
        assertEquals("FIX_SHIFT_HOURS(−1.5)", after.value)
    }

    @Test
    fun rowIdsRoundTripToTheChannelIdentity() {
        val c = ChannelIdentity(12, StableKey("xc:1234.with.dots"))
        assertEquals(c, FixPickerPage.identityOf(FixUnmatchedPage.channelRow(c)))
        assertNull(FixPickerPage.identityOf("fix.search"))
    }
}
