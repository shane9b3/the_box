package app.thebox.ui.full.vm

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.catchup.archive.ArchiveFailure
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.full.PlayerEvent
import app.thebox.core.full.PlayerSettings
import app.thebox.core.full.PlayerStateMachine
import app.thebox.core.full.PlayerToast
import app.thebox.core.full.fakes.FakeArchivePlayback
import app.thebox.core.full.fakes.FakePlayerSettings
import app.thebox.core.full.fakes.FakePlayerStateMachine
import app.thebox.core.full.fakes.FakeProgrammeFeed
import app.thebox.core.full.fakes.PlayerFixtures
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideEvent
import app.thebox.core.guide.GuideRowsCacheFactory
import app.thebox.core.guide.GuideToast
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.feed.GuideRowsCacheImpl
import app.thebox.core.guide.fakes.FakeDeviceClassSource
import app.thebox.core.guide.fakes.FakeGuideLibrary
import app.thebox.core.guide.fakes.FakeGuideSettings
import app.thebox.core.guide.fakes.FakeGuideStateMachine
import app.thebox.core.guide.fakes.FakeReminderBook
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.StreamFormat
import app.thebox.player.api.ConnectPhase
import app.thebox.player.api.NowPlaying
import app.thebox.player.api.PlaybackNotice
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.StreamStats
import app.thebox.player.api.TextTrackOption
import app.thebox.player.api.TrackInfo
import app.thebox.player.api.TrackRef
import app.thebox.ui.full.PlayerDebugProbe
import app.thebox.ui.root.ShellEvent
import app.thebox.ui.shelltest.FakePlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * `PlayerViewModel` with fakes on Robolectric's paused main looper (docs/design/m4-player-ux.md §5.0 B1, §5.4): the B1
 * script, forwarding through `ArchiveVisibility`, the ticker (deadline wake, `repeatOnLifecycle` gating), notice
 * suppression, Settings from the quick bar, and the effects' glue.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val channels: List<GuideChannel> = GuideFixtures.channels(20)
    private val controller = FakePlaybackController()
    private val archive = FakeArchivePlayback()
    private val feed = FakeProgrammeFeed()
    private val library = FakeGuideLibrary(channels)
    private val playerSettings = FakePlayerSettings()
    private val guideSettings = FakeGuideSettings()
    private val machine = FakePlayerStateMachine()
    private val reminders = FakeReminderBook()
    private val probe = object : PlayerDebugProbe {
        override var current: PlayerStateMachine? = null
        override val archive = null
        override fun register(machine: PlayerStateMachine?) { current = machine }
    }
    private val base = 1_760_000_000_000L

    private fun vm(): PlayerViewModel {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PlayerViewModel(
                context, controller, archive, feed, library, GuideRowsCacheFactory { lib, scope -> GuideRowsCacheImpl(lib, scope) },
                playerSettings, guideSettings, FakeDeviceClassSource(), FakePlayerStateMachine.factory(machine), probe, reminders,
            ) as T
        }
        val vm = ViewModelProvider(store, factory)[PlayerViewModel::class.java]
        vm.clock = { base + SystemClock.uptimeMillis() }
        idle()
        return vm
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val ch = channels[5] // archiveDays 7
    private fun np(id: Long, archive: Boolean = true) = NowPlaying(id, ch.identity, ch.displayName, archive, StreamFormat.TS)
    private fun tunedEvents() = machine.events.filterIsInstance<PlayerEvent.Tuned>()

    /**
     * B1 (§5.0): `Connecting → Error(CATCHUP_NOT_IN_ARCHIVE) → Connecting → Playing` while the archive session probes,
     * with `ArchiveStatus` lagging the `Error` by one emission. The machine never sees ERROR, and never CONNECTING after
     * first media (it would open the error layer and show "Connecting" on every hand-off).
     */
    @Test
    fun probeNeverShowsTheCard() {
        val vm = vm()
        val starting = ArchiveStatus(ArchivePhase.STARTING, channel = ch, identityHash = ch.identityHash, ownedTuneId = 5, attempt = 1, attempts = 3)
        archive.mutableStatus.value = starting; idle()
        controller.state.value = PlaybackState.Connecting(np(5), ConnectPhase.CONNECTING, 0, 0); idle()
        // The variant fails; the session has not moved to PROBING yet (one emission behind).
        controller.state.value = PlaybackState.Error(np(5), PlaybackError(PlaybackErrorCode.CATCHUP_NOT_IN_ARCHIVE)); idle()
        archive.mutableStatus.value = starting.copy(phase = ArchivePhase.PROBING, ownedTuneId = 6, attempt = 2); idle()
        controller.state.value = PlaybackState.Connecting(np(6), ConnectPhase.CONNECTING, 0, 0); idle()
        controller.state.value = PlaybackState.Playing(np(6)); idle()
        archive.mutableStatus.value = starting.copy(phase = ArchivePhase.PLAYING, ownedTuneId = 6, attempt = 2); idle()
        // A later continuation hand-off.
        archive.mutableStatus.value = starting.copy(phase = ArchivePhase.CONTINUING, ownedTuneId = 7); idle()
        controller.state.value = PlaybackState.Connecting(np(7), ConnectPhase.CONNECTING, 0, 0); idle()
        controller.state.value = PlaybackState.Playing(np(7)); idle()

        val phases = tunedEvents().map { it.state.phase }
        assertFalse("the layer can never be ERROR: $phases", MiniPhase.ERROR in phases)
        assertTrue(tunedEvents().all { it.state.errorDetail == null && it.state.error == null })
        val firstMedia = phases.indexOf(MiniPhase.PLAYING)
        assertTrue("reached first media: $phases", firstMedia >= 0)
        assertFalse("no CONNECTING after first media: $phases", phases.drop(firstMedia).any { it == MiniPhase.CONNECTING || it == MiniPhase.BUFFERING })
        assertTrue("hand-offs are marked", tunedEvents().any { it.state.handoff })
        assertTrue("the row was resolved", tunedEvents().filter { it.state.identity != null }.all { it.channel?.identityHash == ch.identityHash })
        assertSame(vm.machine, probe.current)
    }

    /** Unowned (a zap away) or live errors are not hidden: the card shows, with the ladder's detail (H-L9). */
    @Test
    fun liveErrorsAreForwardedWithTheirDetail() {
        vm()
        val e = PlayerFixtures.error(PlaybackErrorCode.FORBIDDEN)
        controller.state.value = PlaybackState.Error(np(9, archive = false), e); idle()
        val t = tunedEvents().last().state
        assertEquals(MiniPhase.ERROR, t.phase)
        assertEquals(e, t.errorDetail)
        assertEquals(9L, t.tuneId)
    }

    @Test
    fun feedsAreForwarded() {
        val vm = vm()
        controller.tracks.value = TrackInfo(text = listOf(
            TextTrackOption(TrackRef(3, "cc", 0), null, null, "CEA-608", false),
            TextTrackOption(TrackRef(3, "dvb", 0), "en", null, "DVB", false),
        ))
        idle()
        val tracks = machine.events.filterIsInstance<PlayerEvent.Tracks>().last().menu
        assertEquals(listOf("dvb", "cc"), tracks.text.map { it.key.groupId })
        assertTrue(tracks.text.last().speculative)

        controller.stats.value = StreamStats(serverIndex = 1, serverCount = 3); idle()
        assertTrue("stats only while on", machine.events.none { it is PlayerEvent.Stats })
        vm.effects.setStatsEnabled(true); idle()
        assertTrue(controller.statsOn)
        controller.stats.value = StreamStats(serverIndex = 2, serverCount = 3); idle()
        assertEquals(2, machine.events.filterIsInstance<PlayerEvent.Stats>().last().facts.serverIndex)
        vm.effects.setStatsEnabled(false); idle()
        val n = machine.events.count { it is PlayerEvent.Stats }
        controller.stats.value = StreamStats(serverIndex = 0); idle()
        assertEquals(n, machine.events.count { it is PlayerEvent.Stats })

        controller.previous.value = channels[3].identity; idle()
        assertEquals(channels[3].rowId, machine.events.filterIsInstance<PlayerEvent.Previous>().last().channel?.rowId)
        controller.pendingZap.value = channels[4].identity; idle()
        assertEquals(channels[4].rowId, machine.events.filterIsInstance<PlayerEvent.PendingZap>().last().channel?.rowId)
        archive.mutableStatus.value = ArchiveStatus(ArchivePhase.PAUSED_LIVE, channel = ch); idle()
        assertEquals(ArchivePhase.PAUSED_LIVE, machine.events.filterIsInstance<PlayerEvent.Archive>().last().status.phase)
        playerSettings.update { it.copy(infoBarMs = 8_000) }; idle()
        val s = machine.events.filterIsInstance<PlayerEvent.Settings>().last()
        assertEquals(PlayerSettings(infoBarMs = 8_000), s.settings)
        assertFalse(s.lowEnd)
        guideSettings.update { it.copy(performance = app.thebox.core.guide.PerformanceMode.LOW) }; idle()
        assertTrue(machine.events.filterIsInstance<PlayerEvent.Settings>().last().lowEnd)
        assertTrue("sources forwarded", machine.events.any { it is PlayerEvent.Sources })
    }

    @Test
    fun tickerWakesAtTheDeadlineOnlyWhileStarted() {
        val vm = vm()
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.CREATED
        vm.runTicker(owner.lifecycle)
        idle()
        assertTrue("no ticks before STARTED (H-L11)", machine.events.none { it is PlayerEvent.Tick })
        owner.registry.currentState = Lifecycle.State.STARTED
        idle()
        assertEquals(1, machine.events.count { it is PlayerEvent.Tick })
        // A deadline that moves earlier wakes the loop at once (one re-evaluation tick, GuideViewModel pattern) …
        machine.deadline.value = vm.clock() + 500; idle()
        assertEquals(2, machine.events.count { it is PlayerEvent.Tick })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(499))
        assertEquals("not before the deadline", 2, machine.events.count { it is PlayerEvent.Tick })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2))
        assertEquals("… and ticks at the deadline", 3, machine.events.count { it is PlayerEvent.Tick })
        val tick = machine.events.filterIsInstance<PlayerEvent.Tick>().last().nowMs
        assertTrue(tick >= machine.deadline.value)
        owner.registry.currentState = Lifecycle.State.CREATED
        idle()
        val n = machine.events.count { it is PlayerEvent.Tick }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(3))
        assertEquals("stopped: no ticks", n, machine.events.count { it is PlayerEvent.Tick })
    }

    private fun messages(vm: PlayerViewModel): MutableList<String> {
        val out = ArrayList<String>()
        CoroutineScope(Dispatchers.Main.immediate).launch { vm.events.collect { e -> if (e is ShellEvent.Message) out += e.text } }
        idle()
        return out
    }

    @Test
    fun noticesBecomeToastsWithTheSuppressionRules() {
        val vm = vm()
        val guide = FakeGuideStateMachine(GuideUiState(mode = ScreenMode.GUIDE))
        vm.attachGuide(guide)
        val out = messages(vm)
        controller.notices.tryEmit(PlaybackNotice.Reconnecting(2, 5)); idle()
        assertTrue("not in GUIDE (the mini pill says it)", out.isEmpty())
        guide.mutableUi.value = GuideUiState(mode = ScreenMode.FULL); idle()
        controller.notices.tryEmit(PlaybackNotice.Reconnecting(2, 5)); idle()
        assertEquals("Reconnecting… (2/5)", out.last())
        controller.notices.tryEmit(PlaybackNotice.SwitchedToBackupServer(1)); idle()
        assertEquals("Switched to backup server 2", out.last())

        // An archive resume asked for live: the controller's LiveResumed is the session's, not a user-visible event.
        archive.mutableStatus.value = ArchiveStatus(ArchivePhase.PAUSED_LIVE, channel = ch); idle()
        vm.effects.archiveResume()
        archive.mutableStatus.value = ArchiveStatus(); idle()
        val before = out.size
        controller.notices.tryEmit(PlaybackNotice.LiveResumed); idle()
        assertEquals("suppressed during an archive resume", before, out.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        controller.notices.tryEmit(PlaybackNotice.LiveResumed); idle()
        assertEquals("Back to live", out.last())
        // WP-B's archive notice: reason + next step.
        archive.mutableNotices.tryEmit(app.thebox.core.catchup.archive.ArchiveNotice.LiveNotReady(ch.identity)); idle()
        assertEquals("The last minute isn't in the archive yet. Back to live; try again in a minute.", out.last())
    }

    @Test
    fun quickBarSettingsOpensTheGuidesSettingsRoute() {
        val vm = vm()
        val guide = FakeGuideStateMachine()
        vm.attachGuide(guide)
        vm.effects.openSettings()
        assertEquals(listOf<GuideEvent>(GuideEvent.OpenSettings), guide.events)
    }

    @Test
    fun effectsGlue() {
        val vm = vm()
        val events = ArrayList<ShellEvent>()
        CoroutineScope(Dispatchers.Main.immediate).launch { vm.events.collect { events += it } }
        idle()
        // Programmes: `around` rows, the airing one enriched by `at` (WP-C: `around` rows are title-only).
        val nowMin = (vm.clock() / 60_000L).toInt()
        feed.put(ch, PlayerFixtures.programmes(ch, nowMin - 90, 6, 60))
        vm.effects.loadProgrammes(7, ch, nowMin, 360, 720); idle()
        val p = machine.events.filterIsInstance<PlayerEvent.Programmes>().last()
        assertEquals(7L, p.token)
        assertEquals(ch.identityHash, p.channelHash)
        assertTrue(p.programmes.isNotEmpty())
        assertTrue("around" in feed.calls)

        // Browse: the zap lineup's neighbour.
        vm.onGuideLineup(app.thebox.core.guide.GuideSource.All(1), app.thebox.core.guide.GuideLineup(app.thebox.core.guide.GuideSource.All(1), 1, LongArray(channels.size) { channels[it].rowId }, 7))
        idle()
        vm.effects.loadChannel(3, ch, +1); idle()
        assertEquals(channels[6].rowId, machine.events.filterIsInstance<PlayerEvent.BrowseChannel>().last().channel?.rowId)
        assertTrue(machine.events.any { it is PlayerEvent.GuideSourceNow })

        // Reminder toggle: set, then cancel.
        val future = PlayerFixtures.programmes(ch, nowMin + 60, 1, 30)[0]
        vm.effects.toggleReminder(ch, future); idle()
        assertEquals(ShellEvent.Toast(GuideToast.REMINDER_SET, null), events.last())
        assertEquals(1, reminders.pending.value.size)
        vm.effects.toggleReminder(ch, future); idle()
        assertEquals(ShellEvent.Toast(GuideToast.REMINDER_CANCELLED, null), events.last())

        // Archive start failures toast reason + next step.
        archive.startResult = app.thebox.core.catchup.archive.ArchiveStart.Failed(ArchiveFailure.OUTSIDE_ARCHIVE)
        vm.effects.startOver(ch); idle()
        assertTrue(archive.names().contains("startOver"))
        val failed = (events.last() as ShellEvent.Message).text
        assertTrue(failed, failed.startsWith("This programme is older than the channel's catch-up archive."))

        // Error-card Retry on an archive error resumes the session (WP-B), never controller.retry().
        archive.mutableStatus.value = ArchiveStatus(ArchivePhase.FAILED, channel = ch, failure = ArchiveFailure.PLAYBACK); idle()
        vm.effects.retry()
        assertEquals("resume", archive.names().last())
        assertFalse("retry" in controller.calls)
        archive.mutableStatus.value = ArchiveStatus(); idle()
        vm.effects.retry()
        assertTrue("retry" in controller.calls)

        vm.effects.selectAudio(app.thebox.core.full.TrackKey(1, "a1", 0))
        assertEquals(TrackRef(1, "a1", 0), controller.audioSelections.last())
        assertNull(vm.nowOf(channels[0]))
        vm.effects.toast(PlayerToast.NO_PREVIOUS, null); idle()
        assertEquals("No previous channel yet. Watch another channel first.", (events.last() as ShellEvent.Message).text)
    }

    @Test
    fun listNowLoadsAreDebouncedAndCached() {
        val vm = vm()
        val nowMin = (vm.clock() / 60_000L).toInt()
        channels.take(5).forEach { feed.put(it, PlayerFixtures.programmes(it, nowMin - 10, 2, 60)) }
        vm.onGuideLineup(app.thebox.core.guide.GuideSource.All(1), app.thebox.core.guide.GuideLineup(app.thebox.core.guide.GuideSource.All(1), 1, LongArray(channels.size) { channels[it].rowId }, 7))
        vm.effects.ensureListRows(0, 10); idle()
        vm.effects.ensureListNow(0, 5)
        vm.effects.ensureListNow(0, 5)
        idle()
        assertEquals("debounced", 0, feed.calls.count { it == "nowFor" })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertEquals(1, feed.calls.count { it == "nowFor" })
        assertTrue(machine.events.any { it is PlayerEvent.ListNow })
        assertEquals(channels[2].displayName + " 0", vm.nowOf(channels[2])?.title)
        vm.effects.ensureListNow(0, 5)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertEquals("fresh entries are not asked again", 1, feed.calls.count { it == "nowFor" })
    }

    /** (lead ruling) The info bar's synopsis: `at` for the focused programme, debounced, cancelled by the next change. */
    @Test
    fun focusedProgrammeDetailComesFromAt() {
        val vm = vm()
        val nowMin = (vm.clock() / 60_000L).toInt()
        val full = PlayerFixtures.programmes(ch, nowMin - 30, 3, 60)
        feed.put(ch, full)
        val titleOnly = full.map { it.copy(description = null) }
        fun focus(i: Int) {
            machine.mutableUi.value = app.thebox.core.full.PlayerUiState(info = app.thebox.core.full.InfoBarState(
                active = true, channel = ch, browsing = false, programmes = titleOnly, focused = i, nowIndex = 0,
                pill = app.thebox.core.full.PillState(), glyphs = app.thebox.core.full.InfoGlyphs(), loading = false,
            ))
            idle()
        }
        focus(0)
        focus(1)
        assertEquals("debounced", 0, feed.calls.count { it == "at" })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals("only the last focus is read", 1, feed.calls.count { it == "at" })
        assertEquals(full[1].description, vm.focusedDetail.value?.description)
        archive.mutableStatus.value = ArchiveStatus(ArchivePhase.FAILED, channel = ch, failure = ArchiveFailure.OUTSIDE_ARCHIVE); idle()
        assertEquals(ArchiveFailure.OUTSIDE_ARCHIVE, vm.archiveFailure.value)
        archive.mutableStatus.value = ArchiveStatus(ArchivePhase.PLAYING, channel = ch); idle()
        assertNull("a FAILED session that recovers clears the reason", vm.archiveFailure.value)
    }
}
