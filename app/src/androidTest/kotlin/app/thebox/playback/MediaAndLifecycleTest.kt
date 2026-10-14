package app.thebox.playback

import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.thebox.core.playback.AfrMode
import app.thebox.core.playback.PlaybackEventType
import app.thebox.core.playback.PlaybackWarning
import app.thebox.core.playback.Viewport
import app.thebox.ffmpeg.FfmpegAvailability
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** I-13 MP2, I-20a radio, I-21 hole punch, I-22 AFR gating, I-19/I-20 lifecycle. (I-23 runs in :data's androidTest.) */
@RunWith(AndroidJUnit4::class)
class MediaAndLifecycleTest : PanelTestBase() {
    private val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun i13Mp2Audio() {
        launch(ch(3))
        awaitPlaying(15_000)
        val seen = runFor(10_000)
        assertNoError(seen)
        val s = stats()
        val st = controller.state.value
        log("I-13 ffmpeg=${FfmpegAvailability.isAvailable} audioDecoder=${s.audioDecoder} state=$st")
        if (FfmpegAvailability.isAvailable) {
            assertTrue(s.audioDecoder.orEmpty().contains("ffmpeg", ignoreCase = true))
        } else {
            assertTrue(
                (st is PlaybackState.Playing && st.warning == PlaybackWarning.AUDIO_UNSUPPORTED) || s.audioDecoder != null,
            )
        }
    }

    @Test
    fun i20aRadioReachesFirstMediaOnAudio() {
        launch(ch(13))
        awaitPlaying(15_000)
        val z = awaitFirstMedia(1, 15_000)
        assertNotNull(z.firstFrameMs)
        assertEquals("audio", events(PlaybackEventType.FIRST_FRAME).last().code)
        assertNoError(runFor(20_000))
    }

    @Test
    fun i21HolePunchShowsVideo() {
        launch(ch(1))
        awaitFirstMedia(1)
        Thread.sleep(1_000)
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val a = ui.takeScreenshot()
        Thread.sleep(1_000)
        val b = ui.takeScreenshot()
        val (uniformA, diff) = centre(a, b)
        log("I-21 centre uniform=$uniformA diff=$diff")
        assertFalse("centre is uniform black", uniformA)
        assertTrue("picture does not change", diff > 0)
    }

    private fun centre(a: Bitmap, b: Bitmap): Pair<Boolean, Int> {
        val cx = a.width / 2
        val cy = a.height / 2
        val first = a.getPixel(cx, cy)
        var uniform = true
        var diff = 0
        for (dy in -40..40 step 8) for (dx in -40..40 step 8) {
            val pa = a.getPixel(cx + dx, cy + dy)
            if (pa != first) uniform = false
            if (abs(pa - b.getPixel(cx + dx, cy + dy)) > 0) diff++
        }
        return (uniform && (first and 0xFFFFFF) == 0) to diff
    }

    @Test
    fun i22AfrGatedByViewport() {
        ep.settings().update { it.copy(afrMode = AfrMode.ALWAYS) }
        launch(ch(4), mini = true)
        awaitFirstMedia(1)
        Thread.sleep(3_000)
        assertEquals("no AFR request in MINI", 0, controller.displayModeRequest.value)
        onMain { controller.setViewport(Viewport.FULL) }
        Thread.sleep(3_500)
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val has50 = display.supportedModes.any { abs(it.refreshRate - 50f) < 0.5f }
        log("I-22 FULL request=${controller.displayModeRequest.value} modes=${display.supportedModes.map { it.refreshRate }}")
        if (!has50) assertEquals("no 50 Hz mode: no switch", 0, controller.displayModeRequest.value)
    }

    @Test
    fun i19HomeStopsAtOnceAndRelaunchReResolves() {
        launch(ch(1))
        awaitFirstMedia(1)
        val requestsBefore = panel.stats().streamRequests(1).size
        device.pressHome()
        val (st, ms) = awaitState("Stopped(BACKGROUND)", 5_000) { it is PlaybackState.Stopped }
        log("I-19 stopped after $ms ms (design ≤ 2 s)")
        assertEquals(StopReason.BACKGROUND, (st as PlaybackState.Stopped).reason)
        val deadline = System.currentTimeMillis() + 3_000
        while (panel.stats().openConnections > 0 && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertEquals(0, panel.stats().openConnections)
        relaunch()
        awaitPlaying(15_000)
        assertTrue(panel.stats().streamRequests(1).size > requestsBefore)
    }

    @Test
    fun i20ScreenOffUsesTheGrace() {
        launch(ch(1))
        awaitFirstMedia(1)
        device.sleep()
        try {
            val early = runFor(1_500)
            assertTrue("no BACKGROUND stop on standby (B4)", early.none { it is PlaybackState.Stopped && it.reason == StopReason.BACKGROUND })
            val (st, ms) = awaitState("Stopped(STANDBY)", 10_000) { it is PlaybackState.Stopped }
            log("I-20 standby stop after ${ms + 1_500} ms (scaled grace 3 s)")
            assertEquals(StopReason.STANDBY, (st as PlaybackState.Stopped).reason)
        } finally {
            device.wakeUp() // a failure must not leave the screen off for the next test
        }
        awaitPlaying(20_000)
    }
}
