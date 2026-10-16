package app.thebox.ui.guide.vm

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideRowsCacheFactory
import app.thebox.core.guide.GuideToast
import app.thebox.core.guide.GuideWindowPagerFactory
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
import app.thebox.core.input.BoxAction
import app.thebox.core.input.DefaultKeyMap
import app.thebox.core.input.KeyGestureDetectorImpl
import app.thebox.core.input.Keys
import app.thebox.core.playback.StreamFormat
import app.thebox.player.api.NowPlaying
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.VideoInfo
import app.thebox.ui.guide.grid.GuideTrim
import app.thebox.ui.input.KeyRouterImpl
import app.thebox.ui.root.ShellEvent
import app.thebox.ui.shelltest.FakeCatchupLauncher
import app.thebox.ui.shelltest.FakePlaybackController
import coil3.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.TimeZone

/**
 * `GuideViewModel` on the JVM (code review 2026-10-14, test gap H-1): the real state machine and rows cache over the
 * `:core` fakes and a fake controller, on Robolectric's paused main looper. Covers the review's view-model findings:
 * H-B1 (poster is not a still), H-B3 (shell events before subscription), H-S1/H-S2 (lineup re-emission and answer
 * order through the real feeds), H-S5 (stale `onCleared`), H-S11 (lost key-up through the router) and G-S3/H-S3 (clock
 * form and zone reach the time tables and the pager).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GuideViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val channels: List<GuideChannel> = GuideFixtures.channels(20)
    private val savedZone: TimeZone = TimeZone.getDefault()

    /** One "process": the singletons the view models share (router, probe, labels, controller, library). */
    private inner class World {
        val controller = FakePlaybackController()
        val library = FakeGuideLibrary(channels)
        val settings = FakeGuideSettings()
        val alerts = FakeReminderAlerts()
        val probe = FakeGuideDebugProbe()
        val labels = FakeTimeLabels()
        val router = KeyRouterImpl(DefaultKeyMap(), KeyGestureDetectorImpl(), FakeKeyCapabilities())
        val pagers = ArrayList<FakeGuideWindowPager>()
        val archive = app.thebox.core.full.fakes.FakeArchivePlayback()
        val playerSettings = app.thebox.core.full.fakes.FakePlayerSettings()
        val stores = ArrayList<ViewModelStore>()

        fun viewModel(machineFactory: app.thebox.core.guide.GuideStateMachineFactory = GuideStateMachineImpl.FACTORY): GuideViewModel {
            val store = ViewModelStore().also { stores += it }
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = GuideViewModel(
                    context, controller, FakeCatchupLauncher(), library,
                    GuideRowsCacheFactory { lib, scope -> GuideRowsCacheImpl(lib, scope) },
                    GuideWindowPagerFactory { _, _, _, _, _ -> FakeGuideWindowPager().also { pagers += it } },
                    settings, FakeGuideSessionStore(), FakeReminderBook(), alerts, FakeKeyCapabilities(), machineFactory,
                    FakeNavigator(), labels, FakeGuideRenderer(), FakeDeviceClassSource(), FakeSyncFeed(), FakeGuideDataWindowSource(),
                    probe, router, FakeGuideMetricsFactory(), FakeRowCellsBuilder(), FakeGuideImages(), ImageLoader(context), GuideTrim(null, null),
                    archive, playerSettings,
                    app.thebox.core.settings.fakes.FakeSettingsStore(), // M5 commit 0: startup waits for it (S1)
                ) as T
            }
            val vm = ViewModelProvider(store, factory)[GuideViewModel::class.java]
            // M5 commit 0 (S11): MainActivity attaches the settings machine before startup, which waits for it.
            vm.attachSettings(app.thebox.core.settings.fakes.RecordingSettingsMachine(consume = { false }))
            vm.startup() // M4 commit 0 (S9): MainActivity's call after attachPlayer
            idle()
            return vm
        }
    }

    @After fun tearDown() = TimeZone.setDefault(savedZone)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun playing(ch: GuideChannel) = PlaybackState.Playing(NowPlaying(1, ch.identity, ch.displayName, false, StreamFormat.TS))

    /** H-B1: an HDR poster keeps the mini viewport; only the Still setting (or low memory) is a still. */
    @Test fun hdrPosterIsNotAStill() {
        val w = World()
        val vm = w.viewModel()
        w.controller.state.value = playing(channels[2])
        w.controller.video.value = VideoInfo(posterMode = true)
        idle()
        assertEquals(channels[2].identity, vm.machine.ui.value.tuned.identity)
        assertFalse("posterMode is not mapped to a still", vm.machine.ui.value.tuned.still)
        w.settings.update { it.copy(miniStill = true) }
        idle()
        assertTrue("the Still setting is", vm.machine.ui.value.tuned.still)
    }

    /** H-B3: a toast sent before the root subscribes (the missed-reminders toast at a cold start) is delivered. */
    @Test fun missedToastSurvivesLateSubscription() {
        val w = World()
        w.alerts.missedCounts.trySend(3)
        val vm = w.viewModel() // forwards MissedReminders → machine → toast, before anyone collects `events`
        var got: ShellEvent? = null
        CoroutineScope(Dispatchers.Main.immediate).launch { got = vm.events.first() }
        idle()
        assertEquals(ShellEvent.Toast(GuideToast.REMINDERS_MISSED, "3"), got)
    }

    /** H-S5: the old view model's onCleared runs after the new one attached: the router and probe keep the new one. */
    @Test fun staleOnClearedKeepsTheNewViewModelAttached() {
        val w = World()
        val old = w.viewModel()
        val new = w.viewModel()
        assertSame(new.machine, w.router.attached)
        w.stores[0].clear() // old.onCleared()
        assertSame("router keeps the new machine", new.machine, w.router.attached)
        assertSame("probe keeps the new machine", new.machine, w.probe.current)
        assertTrue("keys still reach the guide", w.router.onNative(Keys.DPAD_DOWN, true, 0, 1_000, 1_000))
        w.stores[1].clear()
        assertEquals(null, w.router.attached)
        assertEquals(null, w.probe.current)
        assertNotNull(old)
    }

    /** M4 commit 0 (docs/design/m4-player-ux.md §1.7 item 10, S9): attachPlayer sets the guide machine's `full`. */
    @Test fun attachPlayerSetsTheGuideMachinesFull() {
        val w = World()
        val vm = w.viewModel()
        val player = app.thebox.core.full.fakes.FakePlayerStateMachine()
        vm.attachPlayer(player)
        assertSame(player, vm.machine.full)
        assertTrue("not FULL: not entered", player.entered.isEmpty())
    }

    /** H-S2 through the real feeds: the first lineup lands before the startup tune reports Playing. */
    @Test fun startupTuneAfterTheFirstLineupFocusesTheTunedRow() {
        val w = World()
        w.library.lastWatched = channels[7]
        val vm = w.viewModel()
        assertEquals("lineup first: row 0", 0, vm.machine.frame.focus!!.row)
        assertTrue("the startup tune was asked for", "tune" in w.controller.calls)
        w.controller.state.value = playing(channels[7])
        idle()
        assertEquals("then the tuned row", 7, vm.machine.frame.focus!!.row)
    }

    /** H-S1 through the real feeds: hiding the focused channel re-emits the lineup; focus keeps its index. */
    @Test fun hidingTheFocusedChannelKeepsTheIndex() {
        val w = World()
        val vm = w.viewModel()
        repeat(5) { vm.machine.dispatch(GuideEvent.Act(BoxAction.DOWN)) }
        idle()
        assertEquals(5, vm.machine.frame.focus!!.row)
        CoroutineScope(Dispatchers.Main.immediate).launch { w.library.hideChannel(channels[5].identity) }
        idle()
        assertEquals("same index after the re-emitted lineup", 5, vm.machine.frame.focus!!.row)
    }

    /** H-S11 through the router: a hold whose key-up is lost (focus loss) ends; descriptions load again. */
    @Test fun focusLossEndsAHoldInTheMachine() {
        val w = World()
        val vm = w.viewModel()
        w.router.onNative(Keys.DPAD_RIGHT, true, 0, 1_000, 1_000)
        w.router.onNative(Keys.DPAD_RIGHT, true, 3, 1_000, 1_400)
        idle()
        w.router.onFocusLost()
        val deadline = vm.machine.nextDeadlineMs.value
        assertTrue("the description timer is armed again, not stuck in the past", deadline == Long.MAX_VALUE || deadline >= System.currentTimeMillis() - 1_000)
    }

    /** G-S3/H-S3: the 12/24 h setting and a zone change reach the time tables and invalidate the pager's texts. */
    @Test fun clockFormAndZoneChangesResetTheLabels() {
        val w = World()
        w.viewModel()
        val pager = w.pagers.single()
        val resets = w.labels.resets
        val inv = pager.invalidations
        w.settings.update { it.copy(clock24 = false) }
        idle()
        assertFalse("12 h tables", w.labels.clock24)
        assertTrue("one reset", w.labels.resets > resets)
        assertTrue("snapshot texts rebuilt", pager.invalidations > inv)

        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        val inv2 = pager.invalidations
        context.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
        idle()
        assertEquals(ZoneId.of("Asia/Kolkata"), w.labels.zone)
        assertTrue(pager.invalidations > inv2)
    }

    // ---- M4 (docs/design/m4-player-ux.md §3.10.1, §3.10.3, §5.4; B1, S9)

    /** A player host that is also the guide bridge (what PlayerViewModel is), recording what the guide VM hands it. */
    private class BridgeHost : app.thebox.ui.full.PlayerHost, app.thebox.ui.full.vm.PlayerGuideBridge {
        override val machine = app.thebox.core.full.fakes.FakePlayerStateMachine()
        override val listRows: app.thebox.core.guide.GuideRows = app.thebox.core.guide.GuideRows.EMPTY
        override fun nowOf(row: GuideChannel): app.thebox.core.guide.ProgrammeInfo? = null
        override val cues = kotlinx.coroutines.flow.MutableStateFlow<List<androidx.media3.common.text.Cue>>(emptyList())
        override val events = kotlinx.coroutines.flow.emptyFlow<ShellEvent>()
        var guide: app.thebox.core.guide.GuideStateMachine? = null
        val lineups = ArrayList<Pair<app.thebox.core.guide.GuideSource?, app.thebox.core.guide.GuideLineup?>>()
        override fun attachGuide(guide: app.thebox.core.guide.GuideStateMachine) { this.guide = guide }
        override fun onGuideLineup(source: app.thebox.core.guide.GuideSource?, lineup: app.thebox.core.guide.GuideLineup?) { lineups += source to lineup }
    }

    @Test fun attachPlayerHostWiresTheShellTheMachineAndTheBridge() {
        val w = World()
        val vm = w.viewModel()
        assertEquals(null, vm.player)
        val host = BridgeHost()
        vm.attachPlayer(host)
        idle()
        assertSame(host, vm.player)
        assertSame(host.machine, vm.machine.full)
        assertSame(vm.machine, host.guide)
        assertTrue("the guide's source and lineup reach the player (GuideSourceNow)", host.lineups.isNotEmpty())
        assertNotNull(host.lineups.last().second)
    }

    /** B1 through the guide VM: an owned archive probe error never reaches the guide machine as ERROR. */
    @Test fun archiveProbeErrorsAreHiddenFromTheGuide() {
        val w = World()
        val vm = w.viewModel()
        val ch = channels[5]
        val np5 = NowPlaying(5, ch.identity, ch.displayName, true, StreamFormat.TS)
        w.archive.mutableStatus.value = app.thebox.core.catchup.archive.ArchiveStatus(app.thebox.core.catchup.archive.ArchivePhase.STARTING, channel = ch, identityHash = ch.identityHash, ownedTuneId = 5)
        w.controller.state.value = PlaybackState.Error(np5, app.thebox.core.playback.PlaybackError(app.thebox.core.playback.PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE))
        idle()
        assertEquals(app.thebox.core.guide.MiniPhase.CONNECTING, vm.machine.ui.value.tuned.phase)
        assertEquals(null, vm.machine.ui.value.tuned.errorDetail)
        // FAILED: the session gave up, the error shows with its detail.
        w.archive.mutableStatus.value = w.archive.status.value.copy(phase = app.thebox.core.catchup.archive.ArchivePhase.FAILED)
        idle()
        assertEquals(app.thebox.core.guide.MiniPhase.ERROR, vm.machine.ui.value.tuned.phase)
        assertEquals(app.thebox.core.playback.PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE, vm.machine.ui.value.tuned.errorDetail?.code)
    }

    @Test fun archiveStatusAndPlayerSettingsAreForwarded() {
        val w = World()
        val fake = app.thebox.core.guide.fakes.FakeGuideStateMachine()
        w.viewModel(app.thebox.core.guide.fakes.FakeGuideStateMachine.factory(fake))
        val st = app.thebox.core.catchup.archive.ArchiveStatus(app.thebox.core.catchup.archive.ArchivePhase.PAUSED_LIVE)
        w.archive.mutableStatus.value = st
        w.playerSettings.update { it.copy(numberEntryWaitMs = 2_500) }
        idle()
        assertEquals(st, fake.events.filterIsInstance<GuideEvent.Archive>().last().status)
        assertEquals(2_500L, fake.events.filterIsInstance<GuideEvent.Player>().last().settings.numberEntryWaitMs)
    }

    /** U-B1: a deadline the machine left in the past must not spin the ticker on main. */
    @Test fun pastDeadlineDoesNotSpinTheTicker() {
        val w = World()
        val fake = app.thebox.core.guide.fakes.FakeGuideStateMachine()
        var ticks = 0
        fake.onDispatch = { e -> if (e is GuideEvent.Tick && ++ticks > 5_000) throw IllegalStateException("the ticker spins: $ticks Ticks in one turn") }
        fake.deadline.value = 1L // long past
        w.viewModel(app.thebox.core.guide.fakes.FakeGuideStateMachine.factory(fake))
        val afterStart = ticks
        assertTrue("≤ 2 Ticks per looper turn ($afterStart)", afterStart <= 2)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        val perSecond = ticks - afterStart
        assertTrue("about one Tick per 16 ms ($perSecond in 1 s)", perSecond in 1..80)
    }

    @Test fun onLaunchModes() {
        fun run(mode: app.thebox.core.full.OnLaunch): Pair<World, app.thebox.core.guide.fakes.FakeGuideStateMachine> {
            val w = World()
            w.playerSettings.update { it.copy(onLaunch = mode) }
            val fake = app.thebox.core.guide.fakes.FakeGuideStateMachine()
            w.viewModel(app.thebox.core.guide.fakes.FakeGuideStateMachine.factory(fake))
            return w to fake
        }
        run(app.thebox.core.full.OnLaunch.GUIDE_WITH_LAST).let { (w, m) ->
            assertTrue("tune" in w.controller.calls)
            assertFalse(m.events.contains(GuideEvent.StartFull))
        }
        run(app.thebox.core.full.OnLaunch.FULL_LAST).let { (w, m) ->
            assertTrue("tune" in w.controller.calls)
            assertTrue(m.events.contains(GuideEvent.StartFull))
        }
        run(app.thebox.core.full.OnLaunch.GUIDE_NOTHING).let { (w, m) ->
            assertFalse("tune" in w.controller.calls)
            assertFalse(m.events.contains(GuideEvent.StartFull))
        }
        val w = World()
        w.library.lastWatched = null
        w.playerSettings.update { it.copy(onLaunch = app.thebox.core.full.OnLaunch.FULL_LAST) }
        val fake = app.thebox.core.guide.fakes.FakeGuideStateMachine()
        w.viewModel(app.thebox.core.guide.fakes.FakeGuideStateMachine.factory(fake))
        assertFalse("nothing to tune: no pending FULL", fake.events.contains(GuideEvent.StartFull))
    }
}
