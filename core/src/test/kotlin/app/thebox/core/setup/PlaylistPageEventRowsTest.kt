package app.thebox.core.setup

import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.ParentalState
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowIntent
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.Visibility
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.setup.fakes.FakeSourceSetup
import app.thebox.core.setup.pages.PlaylistPage
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/design/m6-event-channels.md §5.5, §10, §11.4 state 7: the playlist page's "Hide channels with no event" switch
 * (off by default, its description, the port call) and the Update interval row showing the 6 h event default with its
 * recommendation; a picked value stands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistPageEventRowsTest {
    private val admin = FakePlaylistAdmin()
    private val host = RecordingHost()

    private fun pages(scope: CoroutineScope) = SetupPages(
        SetupPageDeps(
            strings = KeyStrings(), normalizer = SourceInputNormalizerImpl(), setup = FakeSourceSetup(), admin = admin, groups = FakeGroups(),
            matching = FakeMatching(), store = FakeSettingsStore(), parental = MutableStateFlow(ParentalState(pinSet = false, unlocked = false, policy = ParentalPolicy())),
            scope = scope, nowMs = { 1_790_000_000_000L }, locale = { Locale.UK }, zone = { ZoneOffset.UTC },
        ),
    )

    private fun auto(events: Int) = detail(summary(1)).let {
        val effective = if (events >= 50) 6 else it.refreshIntervalH
        it.copy(refreshIntervalAuto = true, eventCount = events, effectiveIntervalH = effective)
    }

    @Test
    fun theSwitchIsOffByDefaultAdvancedAndWritesThroughThePort() = runTest(UnconfinedTestDispatcher()) {
        admin.put(detail(summary(1)))
        val p = pages(backgroundScope)
        val r = SettingsRoute.Playlist(1)
        p.open(r, host)
        val row = host.row(PlaylistPage.ROW_HIDE_EMPTY_EVENTS)
        assertEquals("ROW_HIDE_EMPTY_EVENTS", row.label)
        assertEquals("HIDE_EMPTY_EVENTS_DESC", row.description)
        assertEquals(false, assertIs<RowKind.Toggle>(row.kind).on)
        assertEquals(Visibility.ADVANCED, row.visibility)
        assertEquals(EffectTiming.LIVE, row.effect)
        val ids = host.ids()
        assertEquals(ids.indexOf(PlaylistPage.ROW_INTERVAL) + 1, ids.indexOf(PlaylistPage.ROW_HIDE_EMPTY_EVENTS), "next to the interval")
        p.onIntent(r, RowIntent.Toggle(RowId(PlaylistPage.ROW_HIDE_EMPTY_EVENTS), true), host)
        assertEquals("hideEmptyEvents(true)", admin.calls.last())
        assertEquals(true, assertIs<RowKind.Toggle>(host.row(PlaylistPage.ROW_HIDE_EMPTY_EVENTS).kind).on)
    }

    @Test
    fun theIntervalRowShowsTheEventDefaultWithItsRecommendation() = runTest(UnconfinedTestDispatcher()) {
        admin.put(auto(events = 1_543))
        val p = pages(backgroundScope)
        val r = SettingsRoute.Playlist(1)
        p.open(r, host)
        val row = host.row(PlaylistPage.ROW_INTERVAL)
        assertEquals("INTERVAL_HOURS(6)", row.value)
        assertEquals("INTERVAL_EVENT_DEFAULT", row.description)
        // Picking 24 h again clears auto and stands (re-review E13).
        p.onIntent(r, RowIntent.Choose(RowId(PlaylistPage.ROW_INTERVAL), "24"), host)
        assertEquals("interval(24)", admin.calls.last())
        assertEquals("INTERVAL_HOURS(24)", host.row(PlaylistPage.ROW_INTERVAL).value)
        assertNull(host.row(PlaylistPage.ROW_INTERVAL).description)
    }

    @Test
    fun noRecommendationBelowTheEventCountOrWhenTheUserPicked() = runTest(UnconfinedTestDispatcher()) {
        admin.put(auto(events = 49))
        val p = pages(backgroundScope)
        p.open(SettingsRoute.Playlist(1), host)
        assertEquals("INTERVAL_HOURS(24)", host.row(PlaylistPage.ROW_INTERVAL).value)
        assertNull(host.row(PlaylistPage.ROW_INTERVAL).description)
        admin.put(detail(summary(1)).copy(refreshIntervalH = 0, effectiveIntervalH = 0, eventCount = 1_543))
        p.open(SettingsRoute.Playlist(1), host)
        assertEquals("INTERVAL_MANUAL", host.row(PlaylistPage.ROW_INTERVAL).value)
        assertNull(host.row(PlaylistPage.ROW_INTERVAL).description)
    }

    @Test
    fun eventDefaultAppliesRule() {
        assertTrue(PlaylistPage.eventDefaultApplies(auto(50)))
        assertFalse(PlaylistPage.eventDefaultApplies(auto(49)))
        assertFalse(PlaylistPage.eventDefaultApplies(auto(500).copy(refreshIntervalAuto = false, effectiveIntervalH = 24)))
        assertFalse(PlaylistPage.eventDefaultApplies(auto(500).copy(refreshIntervalH = 0, effectiveIntervalH = 0)))
    }
}
