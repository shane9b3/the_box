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
        val stores = ArrayList<ViewModelStore>()

        fun viewModel(): GuideViewModel {
            val store = ViewModelStore().also { stores += it }
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = GuideViewModel(
                    context, controller, FakeCatchupLauncher(), library,
                    GuideRowsCacheFactory { lib, scope -> GuideRowsCacheImpl(lib, scope) },
                    GuideWindowPagerFactory { _, _, _, _, _ -> FakeGuideWindowPager().also { pagers += it } },
                    settings, FakeGuideSessionStore(), FakeReminderBook(), alerts, FakeKeyCapabilities(), GuideStateMachineImpl.FACTORY,
                    FakeNavigator(), labels, FakeGuideRenderer(), FakeDeviceClassSource(), FakeSyncFeed(), FakeGuideDataWindowSource(),
                    probe, router, FakeGuideMetricsFactory(), FakeRowCellsBuilder(), FakeGuideImages(), ImageLoader(context), GuideTrim(null, null),
                ) as T
            }
            val vm = ViewModelProvider(store, factory)[GuideViewModel::class.java]
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
}
