package app.thebox.ui.guide.vm

import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideRowsCacheFactory
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideWindowPagerFactory
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.ProgrammeInfo
import app.thebox.core.guide.ScreenMode
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
import app.thebox.core.parental.ParentalControls
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.StreamFormat
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.core.sports.hub.ResumePoint
import app.thebox.player.api.ConnectPhase
import app.thebox.player.api.NowPlaying
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.StopReason
import app.thebox.player.api.TuneTarget
import app.thebox.ui.guide.grid.GuideTrim
import app.thebox.ui.input.KeyRouterImpl
import app.thebox.ui.shelltest.FakeCatchupLauncher
import app.thebox.ui.shelltest.FakePlaybackController
import app.thebox.ui.sports.SportsEffect
import coil3.ImageLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * docs/design/m6-sports-hub.md §6.5 (the filter paragraph), §6.6, §10.2 `GuideViewModelClipFilterTest` (R-S1) and the
 * Sports host (`onSportsEffect`, §5.5, §5.6): the real guide machine over the `:core` fakes and a fake controller.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GuideViewModelClipFilterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val channels: List<GuideChannel> = GuideFixtures.channels(20)
    private val x = channels[4]

    private class RecordingParental : ParentalControls {
        val watching = ArrayList<ChannelIdentity?>()
        override fun openAnyway(source: GuideSource) = Unit
        override fun openAnyway(identity: ChannelIdentity) = Unit
        override fun watching(identity: ChannelIdentity?) { watching += identity }
    }

    private inner class World {
        val controller = FakePlaybackController()
        val library = FakeGuideLibrary(channels)
        val archive = app.thebox.core.full.fakes.FakeArchivePlayback()
        val parental = RecordingParental()

        fun viewModel(): GuideViewModel {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = GuideViewModel(
                    context, controller, FakeCatchupLauncher(), library,
                    GuideRowsCacheFactory { lib, scope -> GuideRowsCacheImpl(lib, scope) },
                    GuideWindowPagerFactory { _, _, _, _, _ -> FakeGuideWindowPager() },
                    FakeGuideSettings(), FakeGuideSessionStore(), FakeReminderBook(), FakeReminderAlerts(), GuideStateMachineImpl.FACTORY,
                    FakeNavigator(), FakeTimeLabels(), FakeGuideRenderer(), FakeDeviceClassSource(), FakeSyncFeed(), FakeGuideDataWindowSource(),
                    FakeGuideDebugProbe(), KeyRouterImpl(DefaultKeyMap(), KeyGestureDetectorImpl(), FakeKeyCapabilities()), FakeGuideMetricsFactory(),
                    FakeRowCellsBuilder(), FakeGuideImages(), ImageLoader(context), GuideTrim(null, null),
                    archive, app.thebox.core.full.fakes.FakePlayerSettings(), app.thebox.core.settings.fakes.FakeSettingsStore(),
                    parentalControls = parental,
                ) as T
            }
            val vm = ViewModelProvider(ViewModelStore(), factory)[GuideViewModel::class.java]
            vm.attachSettings(app.thebox.core.settings.fakes.RecordingSettingsMachine(consume = { false }))
            vm.startup()
            idle()
            return vm
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitFocus(vm: GuideViewModel) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (vm.machine.frame.focus == null && System.nanoTime() < deadline) { idle(); Thread.sleep(5) }
    }

    private fun live(ch: GuideChannel, tuneId: Long) = NowPlaying(tuneId, ch.identity, ch.displayName, false, StreamFormat.TS)
    private fun clip(tuneId: Long) = NowPlaying(tuneId, null, "Game recap", false, StreamFormat.HLS, isClip = true)

    @Test fun filterRule() {
        val c = clip(9)
        assertNull(PlaybackState.Connecting(c, ConnectPhase.CONNECTING, 1, 0).withoutActiveClip())
        assertNull(PlaybackState.Buffering(c, app.thebox.player.api.BufferingCause.REBUFFER, null).withoutActiveClip())
        assertNull(PlaybackState.Playing(c, paused = true).withoutActiveClip())
        assertNull(PlaybackState.Stalled(c, null, "restart", 1, 2).withoutActiveClip())
        assertEquals(PlaybackState.Idle, PlaybackState.Stopped(c, StopReason.USER, resumable = false).withoutActiveClip())
        assertEquals(PlaybackState.Idle, PlaybackState.Error(c, PlaybackError(PlaybackErrorCode.CLIP_EXPIRED)).withoutActiveClip())
        val l = PlaybackState.Playing(live(x, 1))
        assertEquals(l, l.withoutActiveClip())
        assertEquals(PlaybackState.Idle, PlaybackState.Idle.withoutActiveClip())
    }

    /** Active clip states never reach TunedStateMapper or `watching`; the pre-clip channel stays, then resumes. */
    @Test fun activeClipStatesKeepThePreClipChannel() {
        val w = World()
        val vm = w.viewModel()
        w.controller.state.value = PlaybackState.Playing(live(x, 1)); idle()
        assertEquals(x.identity, vm.machine.ui.value.tuned.identity)
        val watchedBefore = w.parental.watching.size
        for (st in listOf(PlaybackState.Connecting(clip(2), ConnectPhase.CONNECTING, 1, 0), PlaybackState.Playing(clip(2)), PlaybackState.Playing(clip(2), paused = true))) {
            w.controller.state.value = st; idle()
            assertEquals(x.identity, vm.machine.ui.value.tuned.identity)
            assertEquals(MiniPhase.PLAYING, vm.machine.ui.value.tuned.phase)
        }
        assertEquals("watching never told about the clip", watchedBefore, w.parental.watching.size)
        // Resume of the same channel (a new tune id): no identity change, the channel stays bound.
        w.controller.state.value = PlaybackState.Connecting(live(x, 3), ConnectPhase.CONNECTING, 1, 0); idle()
        w.controller.state.value = PlaybackState.Playing(live(x, 3)); idle()
        assertEquals(x.identity, vm.machine.ui.value.tuned.identity)
        assertEquals(MiniPhase.PLAYING, vm.machine.ui.value.tuned.phase)
        assertEquals(x.identity, w.parental.watching.last())
    }

    /** Home mid-clip: the clip stops not resumable; the mini player is not PLAYING and OK on X tunes X. */
    @Test fun homeMidClipLeavesNothingTuned() {
        val w = World()
        val vm = w.viewModel()
        awaitFocus(vm)
        w.controller.state.value = PlaybackState.Playing(live(x, 1)); idle()
        assertEquals("focus on the tuned row", 4, vm.machine.frame.focus!!.row)
        w.controller.state.value = PlaybackState.Playing(clip(2)); idle()
        w.controller.state.value = PlaybackState.Stopped(clip(2), StopReason.BACKGROUND, resumable = false); idle()
        assertEquals(MiniPhase.IDLE, vm.machine.ui.value.tuned.phase)
        assertNull(w.parental.watching.last())
        val tunes = w.controller.tunes.size
        vm.machine.dispatch(GuideEvent.Act(BoxAction.SELECT)); idle()
        assertEquals("OK on X tunes it (no FULL over nothing)", tunes + 1, w.controller.tunes.size)
        assertEquals(TuneTarget.Channel(x.identity), w.controller.tunes.last().first)
    }

    /** `ResumePoint.Nothing` after the clip's error card: the machines see Idle, no Retry is offered for the clip. */
    @Test fun nothingAfterAnErrorCard() {
        val w = World()
        val vm = w.viewModel()
        w.controller.state.value = PlaybackState.Error(live(x, 1), PlaybackError(PlaybackErrorCode.STREAM_UNDECODABLE)); idle()
        assertEquals(MiniPhase.ERROR, vm.machine.ui.value.tuned.phase)
        w.controller.state.value = PlaybackState.Playing(clip(2)); idle()
        w.controller.state.value = PlaybackState.Error(clip(2), PlaybackError(PlaybackErrorCode.CLIP_EXPIRED)); idle()
        assertEquals("terminal clip state arrives as Idle", MiniPhase.IDLE, vm.machine.ui.value.tuned.phase)
        assertNull(vm.machine.ui.value.tuned.error)
        vm.onSportsEffect(SportsEffect.Resume(ResumePoint.Nothing)); idle()
        assertEquals("stop", w.controller.calls.last())
        w.controller.state.value = PlaybackState.Stopped(clip(2), StopReason.USER, resumable = false); idle()
        assertEquals(MiniPhase.IDLE, vm.machine.ui.value.tuned.phase)
    }

    /** Retry after a clip: the guide has no error to retry, so nothing reaches `controller.retry()`. */
    @Test fun noRetryAfterAClip() {
        val w = World()
        val vm = w.viewModel()
        awaitFocus(vm)
        w.controller.state.value = PlaybackState.Playing(clip(2)); idle()
        w.controller.state.value = PlaybackState.Error(clip(2), PlaybackError(PlaybackErrorCode.CHANNEL_OFFLINE)); idle()
        vm.machine.dispatch(GuideEvent.Act(BoxAction.SELECT)); idle()
        assertFalse("retry" in w.controller.calls)
    }

    // ---- onSportsEffect (§5.5, §5.6, §6.6)

    @Test fun tuneClosesSportsAndGoesFullWithoutFollowing() {
        val w = World()
        val vm = w.viewModel()
        awaitFocus(vm)
        vm.machine.dispatch(GuideEvent.Act(BoxAction.UP)); vm.machine.dispatch(GuideEvent.Act(BoxAction.UP)); vm.machine.dispatch(GuideEvent.Act(BoxAction.UP))
        vm.machine.dispatch(GuideEvent.Act(BoxAction.RIGHT)); vm.machine.dispatch(GuideEvent.Act(BoxAction.RIGHT)); vm.machine.dispatch(GuideEvent.Act(BoxAction.SELECT))
        assertEquals(ScreenMode.SPORTS, vm.machine.ui.value.mode)
        vm.onSportsEffect(SportsEffect.Tune(channels[6])); idle()
        assertEquals(TuneTarget.Channel(channels[6].identity), w.controller.tunes.last().first)
        assertTrue("the guide's lineup is the zap list", w.controller.tunes.last().second != null)
        assertEquals(ScreenMode.GUIDE, vm.machine.ui.value.mode)
        w.controller.state.value = PlaybackState.Connecting(live(channels[6], 5), ConnectPhase.CONNECTING, 1, 0); idle()
        assertEquals(ScreenMode.FULL, vm.machine.ui.value.mode)
    }

    @Test fun resumeLiveTunesTheSnapshotChannel() {
        val w = World()
        val vm = w.viewModel()
        vm.onSportsEffect(SportsEffect.Resume(ResumePoint.Live(x.identity))); idle()
        assertEquals(TuneTarget.Channel(x.identity), w.controller.tunes.last().first)
    }

    /** (Integration) The resumed channel prepares only once the root has given the page its viewport back. */
    @Test fun resumeWaitsForThePagesViewport() {
        val w = World()
        val vm = w.viewModel()
        vm.setViewport(app.thebox.core.playback.Viewport.HIDDEN)
        vm.setViewport(app.thebox.core.playback.Viewport.FULL) // the clip's hole
        val before = w.controller.tunes.size
        vm.onSportsEffect(SportsEffect.Resume(ResumePoint.Live(x.identity))); idle()
        assertEquals("no tune while the viewport is still the clip's", before, w.controller.tunes.size)
        vm.setViewport(app.thebox.core.playback.Viewport.HIDDEN); idle()
        assertEquals(TuneTarget.Channel(x.identity), w.controller.tunes.last().first)
        assertEquals(before + 1, w.controller.tunes.size)
    }

    /** R-N3: the catch-up resumes through playProgramme (timeShift carried), then seeks on the first PLAYING. */
    @Test fun resumeArchiveSeeksOnTheFirstPlaying() {
        val w = World()
        val vm = w.viewModel()
        val info = ProgrammeInfo(rawStartMin = 29_000_000, startMin = 29_000_000, endMin = 29_000_120, title = "Match", subtitle = null,
            description = null, genre = null, episodeLabel = null, flags = 0, titleHash = 1)
        val playhead = 29_000_000 * 60_000L + 1_800_000L
        vm.onSportsEffect(SportsEffect.Resume(ResumePoint.Archive(x, info, rawEndMin = 29_000_120, timeShift = true, playheadMs = playhead))); idle()
        val call = w.archive.calls.single { it.name == "playProgramme" }
        assertEquals(listOf(x, info, 29_000_120, true), call.args)
        assertTrue("no seek before the first media", w.archive.seeks.isEmpty())
        w.archive.mutableStatus.value = ArchiveStatus(ArchivePhase.STARTING, channel = x, identityHash = x.identityHash, ownedTuneId = 7); idle()
        assertTrue(w.archive.seeks.isEmpty())
        w.archive.mutableStatus.value = w.archive.status.value.copy(phase = ArchivePhase.PLAYING); idle()
        assertEquals(listOf(playhead to 0L), w.archive.seeks)
    }

    @Test fun resumeArchiveDoesNotSeekAFailedSession() {
        val w = World()
        val vm = w.viewModel()
        val info = ProgrammeInfo(29_000_000, 29_000_000, 29_000_120, "Match", null, null, null, null, 0, 1)
        vm.onSportsEffect(SportsEffect.Resume(ResumePoint.Archive(x, info, 29_000_120, false, 29_000_000 * 60_000L + 600_000L))); idle()
        w.archive.mutableStatus.value = ArchiveStatus(ArchivePhase.STARTING, channel = x, identityHash = x.identityHash); idle()
        w.archive.mutableStatus.value = w.archive.status.value.copy(phase = ArchivePhase.FAILED); idle()
        w.archive.mutableStatus.value = w.archive.status.value.copy(phase = ArchivePhase.PLAYING); idle()
        assertTrue(w.archive.seeks.isEmpty())
    }
}
