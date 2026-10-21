package app.thebox.ui.full.vm

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchivePlayback
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.catchup.archive.TimelineSource
import app.thebox.core.full.LocalBufferInfo
import app.thebox.core.full.PlayerEvent
import app.thebox.core.full.PlayerToast
import app.thebox.core.full.fakes.FakeArchivePlayback
import app.thebox.core.full.fakes.FakePlayerSettings
import app.thebox.core.full.fakes.FakePlayerStateMachine
import app.thebox.core.full.fakes.FakeProgrammeFeed
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideRowsCacheFactory
import app.thebox.core.guide.feed.GuideRowsCacheImpl
import app.thebox.core.guide.fakes.FakeDeviceClassSource
import app.thebox.core.guide.fakes.FakeGuideLibrary
import app.thebox.core.guide.fakes.FakeGuideSettings
import app.thebox.core.guide.fakes.FakeReminderBook
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.playback.timeshift.RecordStop
import app.thebox.core.playback.timeshift.TimeRanges
import app.thebox.player.api.LocalBufferState
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.PlaybackNotice
import app.thebox.ui.full.PlayerDebugProbe
import app.thebox.ui.full.PlayerText
import app.thebox.ui.root.ShellEvent
import app.thebox.ui.shelltest.FakePlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The player VM's time-shift glue (docs/design/m6-timeshift.md §11.2, §13, §17.3 `PlayerViewModelTest`): `Buffer` from
 * `ArchivePlayback.localBuffer` only (A-C1), the notices → §13 toasts (the landing time, once a day, the header instead
 * of a toast while paused), and the values the machine's toasts carry turned into words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimeshiftViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val channels: List<GuideChannel> = GuideFixtures.channels(8)
    private val ch = channels[3]
    private val rawController = FakePlaybackController()
    private val localState = MutableStateFlow<LocalBufferState?>(null)
    private val controller: PlaybackController = object : PlaybackController by rawController {
        override val localBuffer: StateFlow<LocalBufferState?> get() = localState
    }
    private val fakeArchive = FakeArchivePlayback()
    private val localInfo = MutableStateFlow<LocalBufferInfo?>(null)
    private val archive: ArchivePlayback = object : ArchivePlayback by fakeArchive {
        override val localBuffer: StateFlow<LocalBufferInfo?> get() = localInfo
    }
    private val machine = FakePlayerStateMachine()
    private val base = 1_760_000_000_000L

    private fun vm(): PlayerViewModel {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PlayerViewModel(
                context, controller, archive, FakeProgrammeFeed(), FakeGuideLibrary(channels), GuideRowsCacheFactory { lib, scope -> GuideRowsCacheImpl(lib, scope) },
                FakePlayerSettings(), FakeGuideSettings(), FakeDeviceClassSource(), FakePlayerStateMachine.factory(machine),
                object : PlayerDebugProbe {
                    override var current: app.thebox.core.full.PlayerStateMachine? = null
                    override val archive = null
                    override fun register(machine: app.thebox.core.full.PlayerStateMachine?) { current = machine }
                },
                FakeReminderBook(),
            ) as T
        }
        val vm = ViewModelProvider(store, factory)[PlayerViewModel::class.java]
        vm.clock = { base + SystemClock.uptimeMillis() }
        idle()
        return vm
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun messages(vm: PlayerViewModel): MutableList<String> {
        val out = ArrayList<String>()
        CoroutineScope(Dispatchers.Main.immediate).launch { vm.events.collect { e -> if (e is ShellEvent.Message) out += e.text } }
        idle()
        return out
    }

    private fun info(available: Boolean = true) = LocalBufferInfo(
        available = available, fromMs = base - 1_200_000, atMs = base, saving = true, notSaved = TimeRanges.EMPTY,
        pauseRemainingMs = 17 * 60_000L, stop = null, off = null, hls = false, limitedToday = false,
    )

    /** A-C1: the VM's only source for `PlayerEvent.Buffer` is `ArchivePlayback.localBuffer`. */
    @Test
    fun bufferEventsComeFromTheArchiveFlow() {
        vm()
        val i = info()
        localInfo.value = i; idle()
        assertEquals(i, machine.events.filterIsInstance<PlayerEvent.Buffer>().last().info)
        localState.value = LocalBufferState(identity = ch.identity, available = true); idle()
        assertEquals("the raw controller state never becomes a Buffer event", 1, machine.events.filterIsInstance<PlayerEvent.Buffer>().count { it.info != null })
        localInfo.value = null; idle()
        assertEquals(null, machine.events.filterIsInstance<PlayerEvent.Buffer>().last().info)
    }

    /** §13: GAP_SKIPPED_* with the gap's length and the landing time ("Now at 21:26"), which only the timeline knows. */
    @Test
    fun gapSkippedCarriesTheLandingTime() {
        val vm = vm()
        val out = messages(vm)
        rawController.notices.tryEmit(PlaybackNotice.GapSkipped(40_000, stream = true)); idle()
        assertTrue(out.last(), out.last().startsWith("Skipped 40 s the provider didn't send. Now at "))
        rawController.notices.tryEmit(PlaybackNotice.GapSkipped(12 * 60_000L, stream = false)); idle()
        assertTrue(out.last(), out.last().startsWith("Skipped 12 min that weren't saved while paused. Now at "))
    }

    /** §8.4 (U-S2): REWIND_LIMITED_TODAY at most once a day, from the controller or the machine. */
    @Test
    fun limitedTodayOnceADay() {
        val vm = vm()
        val out = messages(vm)
        rawController.notices.tryEmit(PlaybackNotice.LimitedToday); idle()
        vm.effects.toast(PlayerToast.REWIND_LIMITED_TODAY, null); idle()
        rawController.notices.tryEmit(PlaybackNotice.LimitedToday); idle()
        assertEquals(1, out.count { it.startsWith("Rewind is shorter for the rest of today") })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofHours(25))
        rawController.notices.tryEmit(PlaybackNotice.LimitedToday); idle()
        assertEquals(2, out.count { it.startsWith("Rewind is shorter for the rest of today") })
    }

    /** §11.5: a lost slot while paused is the header line, not a toast; while behind live it is SAVING_STOPPED_CONNECTION. */
    @Test
    fun savingStoppedWhilePausedIsTheHeaderLine() {
        val vm = vm()
        val out = messages(vm)
        fakeArchive.mutableStatus.value = ArchiveStatus(ArchivePhase.PAUSED, channel = ch, identityHash = ch.identityHash, source = TimelineSource.LOCAL); idle()
        rawController.notices.tryEmit(PlaybackNotice.SavingStopped(RecordStop.SLOT_LOST)); idle()
        assertTrue(out.isEmpty())
        fakeArchive.mutableStatus.value = ArchiveStatus(ArchivePhase.PLAYING, channel = ch, identityHash = ch.identityHash, source = TimelineSource.LOCAL); idle()
        rawController.notices.tryEmit(PlaybackNotice.SavingStopped(RecordStop.SLOT_LOST)); idle()
        assertTrue(out.last().startsWith("Saving for rewind stopped: another device"))
        rawController.notices.tryEmit(PlaybackNotice.LeftAppCleared); idle()
        assertEquals("Back to live: leaving The Box clears what was paused. Stay in The Box to keep your place.", out.last())
    }

    /** §13: the machine passes raw values; the VM writes "20 min", the clock, "your playlist". */
    @Test
    fun toastValuesBecomeWords() {
        val vm = vm()
        val out = messages(vm)
        vm.effects.toast(PlayerToast.ZAP_CONFIRM, (20 * 60_000L).toString()); idle()
        assertEquals("Changing channel ends your pause (20 min saved). Press again to change.", out.last())
        vm.effects.toast(PlayerToast.ZAP_CONFIRM_NUMBER, (20 * 60_000L + 20_000L).toString()); idle()
        assertEquals("Changing channel ends your pause (20 min saved). Press OK to change.", out.last())
        localInfo.value = info().copy(windowMs = 30 * 60_000L); idle()
        vm.effects.toast(PlayerToast.REWIND_NOTHING_YET, (15 * 60_000L).toString()); idle()
        assertEquals("the window from ArchivePlayback.localBuffer (A-C1)", "Nothing to rewind yet: The Box saves this channel from when you tune in, up to 30 min.", out.last())
        vm.effects.toast(PlayerToast.PAUSE_NO_REWIND_HLS, ""); idle()
        // Fix pass S5: "Show advanced settings" is the sections pane's toggle, so it comes first.
        assertTrue(out.last(), out.last().endsWith("TS can fix it: Settings → Show advanced settings, then Playlists → your playlist → Stream format."))
        vm.effects.toast(PlayerToast.PAUSE_NO_REWIND_HLS, null); idle()
        assertTrue(out.last().endsWith("ask your provider for TS links."))
        vm.effects.toast(PlayerToast.REWIND_START, (21 * 3_600_000L + 2 * 60_000L).toString()); idle()
        assertTrue(out.last(), out.last().startsWith("That's as far back as you can go (") && out.last().contains(", when you tuned in)"))
        // Fix pass S8: the window variant when the oldest point is not where the cycle began.
        vm.effects.toast(PlayerToast.REWIND_START, (21 * 3_600_000L).toString() + PlayerText.ARG_SEP + (30 * 60_000L)); idle()
        assertTrue(out.last(), out.last().contains(": rewind holds 30 min). This channel has no catch-up for earlier."))
        // Fix pass S3: the machine's GAP_SKIPPED values are raw ms, like every toast's.
        vm.effects.toast(PlayerToast.GAP_SKIPPED_PAUSED, (10 * 60_000L).toString() + PlayerText.ARG_SEP + base); idle()
        assertTrue(out.last(), out.last().startsWith("Skipped 10 min that weren't saved while paused. Now at "))
    }

    /** Fix pass S5, S7: every no-rewind toast is its reason then a next step that exists. */
    @Test
    fun noRewindToastsHaveAReasonAndAFollowableStep() {
        val vm = vm()
        val out = messages(vm)
        vm.effects.toast(PlayerToast.PAUSE_NO_REWIND_FORMAT, null); idle()
        assertEquals("This channel can't be rewound, so Play returns to live. Its stream isn't TS, the format The Box can save; ask your provider for TS links.", out.last())
        vm.effects.toast(PlayerToast.PAUSE_NO_REWIND_STORAGE, null); idle()
        assertEquals("Saving for rewind failed on this device's storage, so Play returns to live. Change channel and back to try again.", out.last())
        vm.effects.toast(PlayerToast.PAUSE_NO_REWIND_OFF, null); idle()
        assertEquals("Rewind is turned off, so Play returns to live. To keep your place: Settings → Playback → Pause and rewind.", out.last())
        vm.effects.toast(PlayerToast.PAUSE_NO_REWIND_SPACE, "0.6 GB"); idle()
        assertEquals("Not enough free storage to save this channel (0.6 GB free), so Play returns to live. Free up space or choose a shorter rewind in Settings → Playback.", out.last())
        vm.effects.toast(PlayerToast.REWIND_LIMITED_TODAY, null); idle()
        assertTrue(out.last(), out.last().endsWith("Change it in Settings → Show advanced settings, then Playback → Protect built-in storage."))
    }

    /** Fix pass S6: BACK_TO_LIVE_UNBUFFERED carries the pause reason's next step, once per tune. */
    @Test
    fun backToLiveUnbufferedCarriesTheStepOncePerTune() {
        val vm = vm()
        val out = messages(vm)
        rawController.state.value = app.thebox.player.api.PlaybackState.Playing(app.thebox.player.api.NowPlaying(1, ch.identity, null, false, app.thebox.core.playback.StreamFormat.HLS))
        idle()
        vm.effects.toast(PlayerToast.PAUSE_NO_REWIND_HLS, null); idle()
        rawController.notices.tryEmit(PlaybackNotice.LiveResumed); idle()
        assertEquals("Back to live: this channel can't be rewound, so the paused part was skipped. It streams as HLS; ask your provider for TS links.", out.last())
        rawController.notices.tryEmit(PlaybackNotice.LiveResumed); idle()
        assertEquals("once", "Back to live: this channel can't be rewound, so the paused part was skipped.", out.last())
        // Another channel: the step belongs to the channel it was given for.
        vm.effects.toast(PlayerToast.PAUSE_NO_REWIND_OFF, null); idle()
        rawController.state.value = app.thebox.player.api.PlaybackState.Playing(app.thebox.player.api.NowPlaying(2, channels[4].identity, null, false, app.thebox.core.playback.StreamFormat.TS))
        idle()
        rawController.notices.tryEmit(PlaybackNotice.LiveResumed); idle()
        assertEquals("Back to live: this channel can't be rewound, so the paused part was skipped.", out.last())
    }

    /**
     * §11.5: the paused facts: the LOCAL estimate, and (fix pass S1, S2, UX review §7) the window and the hold from
     * `ArchivePlayback.localBuffer`, the media keys and the dim from `PlayerUiState.paused` (no cast, no raw controller).
     */
    @Test
    fun pausedFacts() {
        val vm = vm()
        localState.value = LocalBufferState(identity = ch.identity, available = true, windowMs = 99 * 60_000L, holdMs = 99 * 60_000L); idle()
        localInfo.value = info().copy(windowMs = 30 * 60_000L, holdMs = 10 * 60_000L); idle()
        fakeArchive.mutableStatus.value = ArchiveStatus(
            ArchivePhase.PAUSED, channel = ch, identityHash = ch.identityHash, source = TimelineSource.LOCAL, pauseRemainingMs = 17 * 60_000L,
        ); idle()
        machine.mutableUi.value = app.thebox.core.full.PlayerUiState(paused = app.thebox.core.full.PausedUi(pausedSinceMs = base, dimmed = true, dimSinceMs = base + 1, mediaKeys = true)); idle()
        val f = vm.pausedFacts.value
        assertEquals(17 * 60_000L, f.pauseRemainingMs)
        assertEquals(30 * 60_000L, f.windowMs)
        assertEquals("the hold limit in force, not arithmetic on the pause time", 10 * 60_000L, f.holdMs)
        assertTrue(f.mediaKeys && f.dimmed)
        assertEquals(base + 1, f.dimSinceMs)
    }
}
