package app.thebox.ui.parental

import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideLibrary
import app.thebox.core.guide.GuideLineup
import app.thebox.core.guide.GuideRowsCacheFactory
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.guide.GuideWindowPagerFactory
import app.thebox.core.guide.SourceSection
import app.thebox.core.guide.feed.GuideRowsCacheImpl
import app.thebox.core.guide.fakes.FakeDeviceClassSource
import app.thebox.core.guide.fakes.FakeGuideDataWindowSource
import app.thebox.core.guide.fakes.FakeGuideDebugProbe
import app.thebox.core.guide.fakes.FakeGuideImages
import app.thebox.core.guide.fakes.FakeGuideLibrary
import app.thebox.core.guide.fakes.FakeGuideMetricsFactory
import app.thebox.core.guide.fakes.FakeGuideRenderer
import app.thebox.core.guide.fakes.FakeGuideSessionStore
import app.thebox.core.guide.fakes.FakeGuideSettings
import app.thebox.core.guide.fakes.FakeGuideWindowPager
import app.thebox.core.guide.fakes.FakeKeyCapabilities
import app.thebox.core.guide.fakes.FakeNavigator
import app.thebox.core.guide.fakes.FakeReminderAlerts
import app.thebox.core.guide.fakes.FakeReminderBook
import app.thebox.core.guide.fakes.FakeRowCellsBuilder
import app.thebox.core.guide.fakes.FakeSyncFeed
import app.thebox.core.guide.fakes.FakeTimeLabels
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.guide.state.GuideStateMachineImpl
import app.thebox.core.input.DefaultKeyMap
import app.thebox.core.input.KeyGestureDetectorImpl
import app.thebox.core.parental.LockedInGuide
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.ParentalState
import app.thebox.core.parental.PinPurpose
import app.thebox.core.parental.RelockReason
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.TuneVerdict
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.settings.fakes.FakeParentalSession
import app.thebox.core.settings.fakes.FakeTuneGuard
import app.thebox.player.api.NowPlaying
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.PreviousResult
import app.thebox.player.api.StopReason
import app.thebox.player.api.TuneTarget
import app.thebox.ui.guide.grid.GuideTrim
import app.thebox.ui.guide.vm.GuideViewModel
import app.thebox.ui.input.KeyRouterImpl
import app.thebox.ui.search.SearchEffect
import app.thebox.ui.shelltest.FakeCatchupLauncher
import app.thebox.ui.shelltest.FakePlaybackController
import coil3.ImageLoader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * WP-C's `:ui` glue (docs/design/m5-setup-settings.md §3.9, S10, B1): the guarded controller (tune, previous, archive),
 * the library decorator (the session's access, restarts, Hide, opened anyway, D1 number entry) and the guide view
 * model's parental feeds (relock clears the zap list, refusals and `Stopped(LOCKED)` open the prompt).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ParentalUiTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val channels: List<GuideChannel> = GuideFixtures.channels(20)
    private val pinLocked = ParentalState(pinSet = true, unlocked = false, policy = ParentalPolicy())
    private val pinOpen = pinLocked.copy(unlocked = true)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // ---------------------------------------------------------------- GuardedPlaybackController

    @Test fun guardedControllerRefusesLockedTargetsAndReports() {
        val inner = FakePlaybackController()
        val guard = FakeTuneGuard()
        val refused = ArrayList<Pair<ChannelIdentity, TuneVerdict>>()
        val c = GuardedPlaybackController(inner, guard) { id, v -> refused += id to v }
        val locked = channels[3].identity
        guard.verdicts[locked] = TuneVerdict.NEEDS_PIN
        c.tune(TuneTarget.Channel(locked))
        assertTrue("never forwarded", inner.tunes.isEmpty())
        assertEquals(listOf(locked to TuneVerdict.NEEDS_PIN), refused)
        c.tune(TuneTarget.Channel(channels[1].identity))
        assertEquals(1, inner.tunes.size)
        // Previous channel (Blue / LAST_CHANNEL) to a locked target.
        inner.previous.value = locked
        assertTrue("true: the prompt opened, no 'No previous channel' toast", c.previousChannel())
        assertFalse(inner.calls.contains("previousChannel"))
        assertEquals(PreviousResult.Locked(locked), c.previousChannelResult())
        guard.verdicts[locked] = TuneVerdict.ALLOWED
        assertTrue(c.previousChannel())
        assertTrue(inner.calls.contains("previousChannel"))
        // A direct URL is not a channel: never asked.
        c.tune(TuneTarget.DirectUrl("http://h/x"))
        assertEquals(2, inner.tunes.size)
    }

    // ---------------------------------------------------------------- ParentalGuideLibrary

    private class Recording(private val entries: List<GuideSourceEntry>) : GuideLibrary by FakeGuideLibrary() {
        val sourcesAccess = ArrayList<ParentalAccess>()
        val lineupAccess = ArrayList<Pair<GuideSource, ParentalAccess>>()
        val numberAccess = ArrayList<ParentalAccess>()
        var numbered: (ParentalAccess) -> GuideChannel? = { null }
        override fun sources(access: ParentalAccess): Flow<List<GuideSourceEntry>> { sourcesAccess += access; return flowOf(entries) }
        override fun lineup(source: GuideSource, access: ParentalAccess): Flow<GuideLineup> { lineupAccess += source to access; return flowOf(GuideLineup(source, 1, LongArray(0), 0)) }
        override suspend fun byNumber(playlistId: Long?, number: Int, access: ParentalAccess): GuideChannel? { numberAccess += access; return numbered(access) }
    }

    @Test fun decoratorFeedsTheSessionsAccessAndRestarts() = runBlocking {
        val adult = GuideSourceEntry(GuideSource.Group(1, "adult"), "Adult", 3, SourceSection.GROUP, null, locked = true)
        val inner = Recording(listOf(GuideSourceEntry(GuideSource.All(1), "All", 9, SourceSection.ALL, null), adult))
        val session = FakeParentalSession(pinLocked)
        val lib = ParentalGuideLibrary(inner, session)
        assertEquals(2, lib.sources(ParentalAccess.LOCKED_AUTO).first().size)
        assertEquals(listOf(ParentalAccess.LOCKED_AUTO), inner.sourcesAccess)
        session.state.value = pinLocked.copy(policy = ParentalPolicy(lockedInGuide = LockedInGuide.HIDE))
        assertEquals("Hide leaves the locked group out", listOf("All"), lib.sources(ParentalAccess.LOCKED_AUTO).first().map { it.label })
        session.state.value = pinOpen
        lib.lineup(adult.source, ParentalAccess.LOCKED_AUTO).first()
        assertEquals(adult.source to ParentalAccess.OPEN, inner.lineupAccess.last())
        // Debug unlock (OPEN requested) stays OPEN whatever the session says.
        session.state.value = pinLocked
        lib.lineup(adult.source, ParentalAccess.OPEN).first()
        assertEquals(ParentalAccess.OPEN, inner.lineupAccess.last().second)
        // FILTERED_NO_PIN + Open anyway: that source is queried with OPEN, others stay filtered.
        session.state.value = ParentalState(pinSet = false, unlocked = false, policy = ParentalPolicy(), openedAnyway = setOf(adult.source))
        lib.lineup(adult.source, ParentalAccess.LOCKED_AUTO).first()
        assertEquals(ParentalAccess.OPEN, inner.lineupAccess.last().second)
        lib.lineup(GuideSource.All(1), ParentalAccess.LOCKED_AUTO).first()
        assertEquals(ParentalAccess.FILTERED_NO_PIN, inner.lineupAccess.last().second)
    }

    @Test fun numberEntryFindsLockedContentSoTheGuardAsks() = runBlocking {
        val inner = Recording(emptyList())
        val lib = ParentalGuideLibrary(inner, FakeParentalSession(pinLocked))
        inner.numbered = { a -> if (a == ParentalAccess.OPEN) channels[5] else null }
        assertSame(channels[5], lib.byNumber(1, 106, ParentalAccess.LOCKED_AUTO))
        assertEquals(listOf(ParentalAccess.LOCKED_AUTO, ParentalAccess.OPEN), inner.numberAccess)
    }

    // ---------------------------------------------------------------- GuideViewModel

    private inner class World(state: ParentalState) {
        val controller = FakePlaybackController()
        val library = FakeGuideLibrary(channels)
        val session = FakeParentalSession(state)
        val guard = FakeTuneGuard()
        val router = KeyRouterImpl(DefaultKeyMap(), KeyGestureDetectorImpl(), FakeKeyCapabilities())

        fun viewModel(): GuideViewModel {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = GuideViewModel(
                    context, controller, FakeCatchupLauncher(), library,
                    GuideRowsCacheFactory { lib, scope -> GuideRowsCacheImpl(lib, scope) },
                    GuideWindowPagerFactory { _, _, _, _, _ -> FakeGuideWindowPager() },
                    FakeGuideSettings(), FakeGuideSessionStore(), FakeReminderBook(), FakeReminderAlerts(), GuideStateMachineImpl.FACTORY,
                    FakeNavigator(), FakeTimeLabels(), FakeGuideRenderer(), FakeDeviceClassSource(), FakeSyncFeed(), FakeGuideDataWindowSource(),
                    FakeGuideDebugProbe(), router, FakeGuideMetricsFactory(), FakeRowCellsBuilder(), FakeGuideImages(), ImageLoader(context), GuideTrim(null, null),
                    app.thebox.core.full.fakes.FakeArchivePlayback(), app.thebox.core.full.fakes.FakePlayerSettings(),
                    app.thebox.core.settings.fakes.FakeSettingsStore(),
                    parental = session, tuneGuard = guard,
                ) as T
            }
            val vm = ViewModelProvider(ViewModelStore(), factory)[GuideViewModel::class.java]
            vm.attachSettings(app.thebox.core.settings.fakes.RecordingSettingsMachine(consume = { false }))
            vm.startup()
            idle()
            return vm
        }
    }

    private fun playing(ch: GuideChannel) = PlaybackState.Playing(NowPlaying(1, ch.identity, ch.displayName, false, StreamFormat.TS))

    /** B1 (`ParentalBypassTest`): the zap list is cleared on relock and rebuilt from the next lineup. */
    @Test fun relockClearsTheZapList() {
        val w = World(pinOpen)
        w.viewModel()
        w.controller.state.value = playing(channels[2])
        idle()
        w.session.relock(RelockReason.APP_STOPPED)
        idle()
        assertTrue("an empty zap list was set", w.controller.zapLists.any { it === EmptyZapList })
    }

    @Test fun refusedSearchTuneOpensThePrompt() {
        val w = World(pinLocked)
        val vm = w.viewModel()
        val ch = channels[4]
        w.guard.verdicts[ch.identity] = TuneVerdict.NEEDS_PIN
        val before = w.controller.tunes.size
        vm.onSearchEffect(SearchEffect.Tune(ch))
        idle()
        assertEquals("no cycle for a refused target", before, w.controller.tunes.size)
        val pin = vm.machine.ui.value.pin!!
        assertEquals(ch.identity, (pin.purpose as PinPurpose.Tune).channel.identity)
    }

    /** B1: a resume the controller refused (`Stopped(LOCKED)`) opens the prompt and shows nothing as tuned. */
    @Test fun stoppedLockedOpensThePromptAndShowsNothing() {
        val w = World(pinLocked)
        val vm = w.viewModel()
        val ch = channels[6]
        w.controller.state.value = PlaybackState.Stopped(NowPlaying(9, ch.identity, ch.displayName, false, StreamFormat.TS), StopReason.LOCKED, resumable = false)
        idle()
        assertNotNull(vm.machine.ui.value.pin)
        assertNull("the refused channel is not shown as tuned", vm.machine.ui.value.tuned.identity)
    }

    @Test fun sessionStateReachesTheMachine() {
        val w = World(pinOpen)
        val vm = w.viewModel()
        // Locked with a PIN: the machine asks (it knows the session's state).
        w.session.state.value = pinLocked
        idle()
        assertTrue((vm.machine as app.thebox.core.parental.ParentalHost).request(PinPurpose.EnterSettings(app.thebox.core.settings.SettingsRoute.Root)))
        idle()
        assertNotNull(vm.machine.ui.value.pin)
    }
}
