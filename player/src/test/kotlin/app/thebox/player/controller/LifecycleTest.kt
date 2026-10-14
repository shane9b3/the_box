package app.thebox.player.controller

import app.thebox.player.api.PlaybackState
import app.thebox.player.api.StopReason
import app.thebox.player.api.TuneTarget
import app.thebox.player.controller.fakes.CH1
import app.thebox.player.controller.fakes.ControllerFixture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §5.3 WP-D `LifecycleCoordinator`: Home stops at once; `ON_STOP` while not interactive or with the display off starts
 * the grace (review B4); screen-off/HDMI/display-off grace and its cancellation; resume re-resolves and refreshes the
 * account after 15 min.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LifecycleTest {
    private val f = ControllerFixture()
    private val lc get() = f.controller.lifecycle

    @After fun tearDown() = f.close()

    private fun playing() {
        lc.onProcessStart()
        f.controller.tune(TuneTarget.Channel(CH1))
        f.idle()
        f.play()
    }

    @Test
    fun homeWhileInteractiveStopsAtOnceAndReturnReResolves() {
        playing()
        lc.onProcessStop()
        val st = f.state as PlaybackState.Stopped
        assertEquals(StopReason.BACKGROUND, st.reason)
        assertTrue(st.resumable)
        assertEquals(1, f.transport.ended.size)
        assertEquals(1, f.resolver.calls.size)
        f.advance(2_000)
        lc.onProcessStart()
        f.idle()
        assertEquals(2, f.resolver.calls.size) // fresh resolve, never the old URL
        assertTrue(f.state is PlaybackState.Connecting)
        assertEquals(0, f.provider.refreshes)
    }

    @Test
    fun onStopWhileNotInteractiveStartsTheGraceInsteadOfStopping() {
        playing()
        f.device.interactive = false
        lc.onProcessStop()
        assertTrue(f.state is PlaybackState.Playing)
        assertEquals(StopReason.STANDBY, lc.graceReason)
        f.advance(29_000)
        assertTrue(f.state is PlaybackState.Playing)
        f.advance(1_100)
        assertEquals(StopReason.STANDBY, (f.state as PlaybackState.Stopped).reason)
    }

    @Test
    fun displayOffOnStopIsADisplayOffGrace() {
        playing()
        f.device.displayOn = false
        lc.onProcessStop()
        assertEquals(StopReason.DISPLAY_OFF, lc.graceReason)
    }

    @Test
    fun startWithinTheGraceCancelsIt() {
        playing()
        lc.onScreenOff()
        f.advance(10_000)
        lc.onProcessStart()
        assertNull(lc.graceReason)
        f.advance(30_000)
        assertTrue(f.state is PlaybackState.Playing)
        assertEquals(1, f.resolver.calls.size)
    }

    @Test
    fun timeScaleCompressesTheGrace() {
        f.settings.update { it.copy(lifecycleTimeScale = 0.1f) }
        playing()
        lc.onScreenOff()
        f.advance(3_100)
        assertEquals(StopReason.STANDBY, (f.state as PlaybackState.Stopped).reason)
        lc.onScreenOn() // screen on with the app in the foreground resumes
        f.idle()
        assertEquals(2, f.resolver.calls.size)
    }

    @Test
    fun hdmiUnplugIsATransitionNeverTheStickyBroadcast() {
        playing()
        lc.onHdmiAudioPlug(plugged = false, initialSticky = true)
        assertNull(lc.graceReason)
        lc.onHdmiAudioPlug(plugged = true, initialSticky = false)
        lc.onHdmiAudioPlug(plugged = false, initialSticky = false)
        assertEquals(StopReason.HDMI_UNPLUGGED, lc.graceReason)
        f.advance(30_100)
        assertEquals(StopReason.HDMI_UNPLUGGED, (f.state as PlaybackState.Stopped).reason)
        lc.onHdmiAudioPlug(plugged = true, initialSticky = false)
        f.idle()
        assertTrue(f.state is PlaybackState.Connecting)
    }

    @Test
    fun displayOffGraceAndDisplayOnCancel() {
        playing()
        lc.onDisplayStateChanged(on = false)
        assertEquals(StopReason.DISPLAY_OFF, lc.graceReason)
        lc.onDisplayStateChanged(on = true)
        assertNull(lc.graceReason)
    }

    @Test
    fun screenOffAfterTheDisplayTurnedOffIsStandby() {
        // Integration 2026-10-13 (device I-20, API 30): the display listener fires before ACTION_SCREEN_OFF.
        playing()
        lc.onDisplayStateChanged(on = false)
        lc.onScreenOff()
        assertEquals(StopReason.STANDBY, lc.graceReason)
    }

    @Test
    fun resumeAfterFifteenMinutesRefreshesTheAccountFirst() {
        playing()
        lc.onProcessStop()
        f.advance(16 * 60_000L)
        lc.onProcessStart()
        f.idle()
        assertEquals(1, f.provider.refreshes)
        assertEquals(2, f.resolver.calls.size)
    }

    @Test
    fun noResumeAfterLongStandbyWhenTheSettingIsOff() {
        f.settings.update { it.copy(resumeAfterStandby = false) }
        playing()
        lc.onProcessStop()
        f.advance(16 * 60_000L)
        lc.onProcessStart()
        f.idle()
        assertTrue(f.state is PlaybackState.Stopped)
        assertEquals(1, f.resolver.calls.size)
    }

    @Test
    fun userStopIsNeverResumedByTheLifecycle() {
        playing()
        f.controller.stop(StopReason.USER)
        lc.onProcessStop()
        lc.onProcessStart()
        f.idle()
        assertEquals(StopReason.USER, (f.state as PlaybackState.Stopped).reason)
    }

    @Test
    fun graceIsNotStartedWhenNothingPlays() {
        lc.onScreenOff()
        assertNull(lc.graceReason)
    }

    // ------------------------------------------------------------------ code review 2026-10-14

    /** Sleep: the Activity stops while not interactive, so the STANDBY grace runs. */
    private fun asleepInTheGrace() {
        playing()
        f.device.interactive = false
        lc.onScreenOff()
        lc.onProcessStop()
        f.advance(5_000)
        assertEquals(StopReason.STANDBY, lc.graceReason)
        f.device.interactive = true
    }

    @Test
    fun wakeWithoutStartStopsAsBackground() {
        // E-B2 (the reviewer's repro): woken to the launcher, no ON_START. Playback must not run behind the launcher.
        asleepInTheGrace()
        lc.onScreenOn()
        assertNull(lc.graceReason)
        assertEquals(StopReason.BACKGROUND, (f.state as PlaybackState.Stopped).reason)
        f.advance(120_000)
        assertEquals(StopReason.BACKGROUND, (f.state as PlaybackState.Stopped).reason)
        // The app comes to the front later: the lifecycle stop is resumed with a fresh resolve.
        lc.onProcessStart()
        f.idle()
        assertTrue(f.state is PlaybackState.Connecting)
        assertEquals(2, f.resolver.calls.size)
    }

    @Test
    fun wakeWithStartKeepsPlaying() {
        asleepInTheGrace()
        lc.onProcessStart() // the device brings the Activity back
        lc.onScreenOn()
        f.idle()
        assertTrue(f.state is PlaybackState.Playing)
        f.advance(120_000)
        assertTrue(f.state is PlaybackState.Playing)
        assertEquals(1, f.resolver.calls.size)
    }

    @Test
    fun displayOnAndHdmiReplugInTheBackgroundStopToo() {
        playing()
        lc.onHdmiAudioPlug(plugged = true, initialSticky = true)
        f.device.displayOn = false
        lc.onProcessStop() // display off: grace, not a stop
        assertEquals(StopReason.DISPLAY_OFF, lc.graceReason)
        f.device.displayOn = true
        lc.onDisplayStateChanged(on = true)
        assertEquals(StopReason.BACKGROUND, (f.state as PlaybackState.Stopped).reason)

        lc.onProcessStart()
        f.idle()
        f.play()
        lc.onHdmiAudioPlug(plugged = false, initialSticky = false)
        lc.onProcessStop() // Home during the HDMI grace while interactive: stops at once anyway
        assertEquals(StopReason.BACKGROUND, (f.state as PlaybackState.Stopped).reason)
    }

    @Test
    fun theStandbyGraceSuspendsTheVideoExpectation() {
        // E-S4: no FROZEN_VIDEO from a missing surface during the grace (it would count toward compat escalation).
        playing()
        f.controller.onSurfaceValidChanged(true)
        assertEquals(true to true, f.engine.watchdog.expectations)
        lc.onScreenOff()
        assertEquals(false to true, f.engine.watchdog.expectations)
        val resets = f.engine.watchdog.resets
        lc.onScreenOn() // foreground: the grace ends, playing continues
        assertEquals(true to true, f.engine.watchdog.expectations)
        assertTrue("baselines re-taken after the grace", f.engine.watchdog.resets > resets)
    }

    @Test
    fun aDestroyedSurfaceSuspendsTheVideoExpectation() {
        playing()
        f.controller.onSurfaceValidChanged(true)
        assertEquals(true to true, f.engine.watchdog.expectations)
        f.controller.onSurfaceValidChanged(false) // surfaceDestroyed (Activity stopped) or the view detached
        assertEquals(false to true, f.engine.watchdog.expectations)
        val resets = f.engine.watchdog.resets
        f.controller.onSurfaceValidChanged(true)
        assertEquals(true to true, f.engine.watchdog.expectations)
        assertEquals(resets + 1, f.engine.watchdog.resets)
    }
}
