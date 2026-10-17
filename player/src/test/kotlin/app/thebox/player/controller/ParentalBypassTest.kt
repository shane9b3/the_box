package app.thebox.player.controller

import app.thebox.core.playback.TuneVerdict
import app.thebox.core.refresh.ChannelIdentity
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.PreviousResult
import app.thebox.player.api.StopReason
import app.thebox.player.api.TuneTarget
import app.thebox.player.api.ZapList
import app.thebox.player.controller.fakes.CH1
import app.thebox.player.controller.fakes.CH2
import app.thebox.player.controller.fakes.CH3
import app.thebox.player.controller.fakes.ControllerFixture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (M5, docs/design/m5-setup-settings.md §3.9.2, review B1/S16) The controller's `TuneGuard` backstop: tune, catch-up,
 * previous channel, zap, retry and resume after a lifecycle stop each refuse a locked identity and allow it once the
 * guard does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ParentalBypassTest {
    private val f = ControllerFixture()

    @After fun tearDown() = f.close()

    private val list = object : ZapList {
        val order = listOf(CH1, CH2, CH3)
        override val id = "all"
        override suspend fun neighbour(from: ChannelIdentity, delta: Int) = order[Math.floorMod(order.indexOf(from) + delta, order.size)]
    }

    private fun watch(id: ChannelIdentity) {
        f.controller.tune(TuneTarget.Channel(id), list)
        f.idle()
        f.play()
        f.advance(1_500)
    }

    @Test
    fun aRefusedTuneNeverStartsAndStopsAsLocked() {
        watch(CH1)
        val resolves = f.resolver.calls.size
        f.verdicts[CH2] = TuneVerdict.NEEDS_PIN
        f.controller.tune(TuneTarget.Channel(CH2))
        f.idle()
        val st = f.state as PlaybackState.Stopped
        assertEquals(StopReason.LOCKED, st.reason)
        assertFalse(st.resumable)
        assertEquals(CH2, st.target?.identity)
        assertEquals("no resolve for the locked channel", resolves, f.resolver.calls.size)
        assertEquals("the old stream was released", 1, f.transport.ended.size)

        f.verdicts.remove(CH2) // PIN entered: the machine tunes again
        f.controller.tune(TuneTarget.Channel(CH2))
        f.idle()
        assertEquals(CH2, f.state.target?.identity)
        assertTrue(f.state is PlaybackState.Connecting)
    }

    @Test
    fun notProtectedRefusesToo() {
        f.verdicts[CH1] = TuneVerdict.NOT_PROTECTED
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        assertEquals(StopReason.LOCKED, (f.state as PlaybackState.Stopped).reason)
        assertTrue(f.resolver.calls.isEmpty())
    }

    @Test
    fun previousChannelReturnsLockedAfterARelock() {
        watch(CH1)
        watch(CH2)
        f.verdicts[CH1] = TuneVerdict.NEEDS_PIN
        val result = f.controller.previousChannelResult()
        assertEquals(PreviousResult.Locked(CH1), result)
        assertEquals("nothing tuned", CH2, f.state.target?.identity)
        assertFalse("the Boolean form says nothing was tuned", f.controller.previousChannel())

        f.verdicts.remove(CH1)
        assertEquals(PreviousResult.Started, f.controller.previousChannelResult())
        f.idle()
        assertEquals(CH1, f.state.target?.identity)
    }

    @Test
    fun noPreviousIsReported() {
        assertEquals(PreviousResult.NoPrevious, f.controller.previousChannelResult())
    }

    @Test
    fun zapSkipsLockedNeighbours() {
        watch(CH1)
        f.verdicts[CH2] = TuneVerdict.NEEDS_PIN
        f.controller.zap(+1)
        f.idle()
        assertEquals(CH3, f.state.target?.identity)
    }

    @Test
    fun zapOverOnlyLockedChannelsDoesNothing() {
        watch(CH1)
        f.verdicts[CH2] = TuneVerdict.NEEDS_PIN
        f.verdicts[CH3] = TuneVerdict.NEEDS_PIN
        f.verdicts[CH1] = TuneVerdict.NEEDS_PIN // relocked while watching: the zap still must not tune a locked one
        val builds = f.sources.builds.size
        f.controller.zap(+1)
        f.idle()
        assertEquals(builds, f.sources.builds.size)
        assertNull(f.controller.pendingZap.value)
    }

    @Test
    fun resumeAfterHomeAsksInsteadOfResumingALockedChannel() {
        val lc = f.controller.lifecycle
        lc.onProcessStart()
        watch(CH1)
        lc.onProcessStop()
        assertEquals(StopReason.BACKGROUND, (f.state as PlaybackState.Stopped).reason)
        f.verdicts[CH1] = TuneVerdict.NEEDS_PIN // "Until The Box closes" relocked on ON_STOP
        val resolves = f.resolver.calls.size
        lc.onProcessStart()
        f.idle()
        val st = f.state as PlaybackState.Stopped
        assertEquals(StopReason.LOCKED, st.reason)
        assertEquals(CH1, st.target?.identity)
        assertEquals("no startCycle", resolves, f.resolver.calls.size)
    }

    @Test
    fun resumeAfterHomeResumesWhenAllowed() {
        val lc = f.controller.lifecycle
        lc.onProcessStart()
        watch(CH1)
        lc.onProcessStop()
        lc.onProcessStart()
        f.idle()
        assertTrue(f.state is PlaybackState.Connecting)
    }

    @Test
    fun retryOfALockedChannelIsRefused() {
        watch(CH1)
        f.verdicts[CH1] = TuneVerdict.NEEDS_PIN
        val resolves = f.resolver.calls.size
        f.controller.retry()
        f.idle()
        assertEquals(StopReason.LOCKED, (f.state as PlaybackState.Stopped).reason)
        assertEquals(resolves, f.resolver.calls.size)
    }

    @Test
    fun aFailingGuardFailsClosed() {
        f.guardThrows = true
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        assertEquals(StopReason.LOCKED, (f.state as PlaybackState.Stopped).reason)
        assertTrue(f.resolver.calls.isEmpty())
    }

    @Test
    fun directUrlsAreNotGuarded() {
        f.guardThrows = true
        f.controller.tune(TuneTarget.DirectUrl("http://example.test/a.ts"))
        f.idle()
        assertTrue(f.guardChecks.isEmpty())
        assertTrue(f.state is PlaybackState.Connecting)
    }

    /** D-S6 (M5 code review 2026-10-16, B1): a catch-up start of a locked channel never starts (no archive resolve). */
    @Test
    fun aRefusedCatchUpNeverStarts() {
        f.verdicts[CH1] = TuneVerdict.NEEDS_PIN
        val request = app.thebox.core.catchup.ArchiveRequest(
            url = "http://panel.example:8080/timeshift/{{u}}/{{p}}/60/2026-10-13:20-00/1.ts",
            headers = emptyMap(),
            mode = app.thebox.core.model.CatchupMode.entries.first(),
            variant = "xc",
            requestStart = java.time.Instant.parse("2026-10-13T18:00:00Z"),
            requestEnd = null,
            nativeSeekRange = null,
        )
        f.controller.tuneArchive(app.thebox.player.api.ArchiveTune(CH1, request))
        f.idle()
        val st = f.state as PlaybackState.Stopped
        assertEquals(StopReason.LOCKED, st.reason)
        assertEquals(CH1, st.target?.identity)
        assertTrue("no archive resolve", f.resolver.archiveCalls.isEmpty())
    }

    /** D-S6 (M5 code review 2026-10-16, B1): the Sources picker cannot replay a channel that was relocked while playing. */
    @Test
    fun selectServerOfALockedChannelIsRefused() {
        f.resolver.baseCount = 3
        watch(CH1)
        f.verdicts[CH1] = TuneVerdict.NEEDS_PIN
        val resolves = f.resolver.calls.size
        f.controller.selectServer(2)
        f.idle()
        assertEquals(StopReason.LOCKED, (f.state as PlaybackState.Stopped).reason)
        assertEquals("no re-resolve on the other base", resolves, f.resolver.calls.size)
    }
}
