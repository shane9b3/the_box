package app.thebox.player.controller

import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.thebox.player.api.StopReason
import app.thebox.player.controller.fakes.FakeDeviceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (Score bug commit 0, docs/design/m6-score-bug.md §2.3, A-S5) `DeviceAwakeState` follows `LifecycleCoordinator`'s power,
 * display and HDMI facts, also while the process stays STARTED (display off without ON_STOP), and never from the
 * initial sticky HDMI broadcast.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceAwakeTest {
    private val device = FakeDeviceState()
    private val awake = DeviceAwakeState()
    private val target = object : LifecycleCoordinator.Target {
        override fun isPlaybackActive() = false
        override fun stopForLifecycle(reason: StopReason) = Unit
        override fun resumeAfterLifecycle() = Unit
        override fun onForegroundChanged(foreground: Boolean) = Unit
    }
    private val lc = LifecycleCoordinator(
        ApplicationProvider.getApplicationContext(), target, device, handler = Handler(Looper.getMainLooper()), awakeSink = awake::set,
    )

    @Test
    fun displayOffWhileStartedIsAsleepAndOnWakes() {
        lc.onProcessStart()
        assertTrue(awake.awake.value)
        device.displayOn = false
        lc.onDisplayStateChanged(false)
        assertFalse(awake.awake.value)
        device.displayOn = true
        lc.onDisplayStateChanged(true)
        assertTrue(awake.awake.value)
    }

    @Test
    fun screenOffIsAsleepAtOnce() {
        lc.onProcessStart()
        lc.onScreenOff() // isInteractive may still read true when the broadcast lands
        assertFalse(awake.awake.value)
        lc.onScreenOn()
        assertTrue(awake.awake.value)
    }

    @Test
    fun hdmiUnplugTransitionIsAsleepButTheInitialStickyIsNot() {
        lc.onProcessStart()
        lc.onHdmiAudioPlug(plugged = false, initialSticky = true)
        assertTrue(awake.awake.value)
        lc.onHdmiAudioPlug(plugged = true, initialSticky = false)
        lc.onHdmiAudioPlug(plugged = false, initialSticky = false)
        assertFalse(awake.awake.value)
        lc.onHdmiAudioPlug(plugged = true, initialSticky = false)
        assertTrue(awake.awake.value)
    }

    @Test
    fun notInteractiveOnStopIsAsleep() {
        lc.onProcessStart()
        device.interactive = false
        lc.onProcessStop()
        assertFalse(awake.awake.value)
        device.interactive = true
        lc.onProcessStart()
        assertEquals(true, awake.awake.value)
    }
}
