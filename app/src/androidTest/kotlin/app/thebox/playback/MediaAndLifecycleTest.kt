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
import org.junit.Assume.assumeTrue
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
            // §5.5 I-13 (E-S6): AUDIO_UNSUPPORTED, or a *platform MP2* decoder on the MP2 track (not just any decoder).
            assertTrue(
                "state=$st audio=${s.audioMime}/${s.audioDecoder}",
                (st is PlaybackState.Playing && st.warning == PlaybackWarning.AUDIO_UNSUPPORTED) ||
                    (s.audioMime == "audio/mpeg-L2" && s.audioDecoder != null && st is PlaybackState.Playing && st.warning == null),
            )
        }
    }

    @Test
    fun i20aRadioReachesFirstMediaOnAudio() {
        launch(ch(13))
        awaitPlaying(15_000)
        val z = awaitFirstMedia(1, 15_000) // this test's tune (E-S5)
        assertNotNull(z.firstFrameMs)
        assertEquals("audio", events(PlaybackEventType.FIRST_FRAME).last { it.tuneId == z.tuneId }.code)
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

    private fun has50HzMode(): Boolean {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        log("I-22 modes=${display.supportedModes.map { "${it.modeId}@${it.refreshRate}" }}")
        return display.supportedModes.any { abs(it.refreshRate - 50f) < 0.5f }
    }

    /** §5.5 I-22 FULL: the controller evaluated AFR for this 50 fps channel; a switch where the display can do 50 Hz. */
    private fun assertAfrInFull(what: String) {
        // The fps estimate needs 30 rendered frames with steady deltas; the emulator's soft decoder can take a few s.
        val deadline = System.currentTimeMillis() + 10_000
        while (stats().fps == null && System.currentTimeMillis() < deadline) Thread.sleep(250)
        val s = stats()
        log("I-22 $what: request=${controller.displayModeRequest.value} afr=${s.afr} fps=${s.fps} refresh=${s.displayRefreshHz}")
        assertEquals("$what: AFR mode in the stats", AfrMode.ALWAYS, s.afr)
        assertNotNull("$what: fps known for ch 4", s.fps)
        assertEquals("$what: ch 4 is 50 fps", 50f, s.fps!!, 0.5f)
        // The emulator AVD has one 60 Hz mode (§5.8 O-4): a real switch cannot be shown there. Skipped with a reason,
        // never passed vacuously (code review 2026-10-14, E-S6).
        assumeTrue("display has no 50 Hz mode: the AFR switch itself cannot be asserted on this device", has50HzMode())
        assertTrue("$what: a display mode request in FULL", controller.displayModeRequest.value != 0)
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
        assertAfrInFull("MINI → FULL")
    }

    @Test
    fun i22bAfrInFullFromTheStart() {
        // Code review 2026-10-14, E-S2: FULL from the start (no viewport change) must evaluate AFR too.
        ep.settings().update { it.copy(afrMode = AfrMode.ALWAYS) }
        launch(ch(4))
        awaitFirstMedia(1)
        Thread.sleep(3_500)
        assertAfrInFull("FULL")
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
        val sleptAt = android.os.SystemClock.elapsedRealtime()
        device.sleep()
        try {
            val early = runFor(1_500)
            assertTrue("no BACKGROUND stop on standby (B4)", early.none { it is PlaybackState.Stopped && it.reason == StopReason.BACKGROUND })
            val (st, ms) = awaitState("Stopped(STANDBY)", 10_000) { it is PlaybackState.Stopped }
            log("I-20 standby stop after ${ms + 1_500} ms (scaled grace 3 s)")
            assertEquals(StopReason.STANDBY, (st as PlaybackState.Stopped).reason)
            // E-S4: the surface is gone during the grace; no frozen-picture verdict may come from that.
            val frozen = events(PlaybackEventType.STALL).filter { it.elapsedMs >= sleptAt && it.code == "FROZEN_VIDEO" }
            assertTrue("FROZEN_VIDEO during the standby grace: $frozen", frozen.isEmpty())
        } finally {
            device.wakeUp() // a failure must not leave the screen off for the next test
        }
        awaitPlaying(20_000)
    }
}
