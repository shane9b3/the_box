package app.thebox.player.engine

import android.content.Context
import android.os.SystemClock
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.playback.AfrMode
import app.thebox.core.playback.DisplayModeInfo
import app.thebox.core.playback.DisplayModePicker
import app.thebox.core.playback.MonoClock
import app.thebox.core.playback.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * [DisplayModeController] (§3.9, review S12) with a fake picker (WP-A's `DisplayModePicker`), fake display modes and a
 * recording frame-rate voter, on both API paths (the controller's `sdkInt` stands for API 30 and API 31 devices; the
 * Robolectric SDK is 35 so both platform paths exist). [DisplayModeControllerApi28Test] runs the default path on a real
 * API 28 sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DisplayModeControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    internal class FakeDisplay(var current: DisplayModeInfo, val modes: List<DisplayModeInfo>) : DisplayAccess {
        private var onChange: (() -> Unit)? = null
        override fun current() = current
        override fun supported() = modes
        override fun setListener(listener: (() -> Unit)?) {
            onChange = listener
        }
        fun switchTo(id: Int) {
            current = modes.single { it.id == id }
            onChange?.invoke()
        }
    }

    internal class Voter : FrameRateVoter {
        val calls = mutableListOf<String>()
        override fun vote(holder: SurfaceHolder, fps: Float): Boolean {
            calls += "vote $fps"
            return true
        }
        override fun revert(holder: SurfaceHolder) {
            calls += "revert"
        }
        override fun clear(holder: SurfaceHolder) {
            calls += "clear"
        }
    }

    /** Fake picker: same resolution, refresh = k × fps (k = 1, 2), lowest first; null when the current mode fits. */
    internal object Picker : DisplayModePicker {
        override fun snapFps(fps: Float): Float? = fps
        override fun pick(current: DisplayModeInfo, supported: List<DisplayModeInfo>, fps: Float): DisplayModeInfo? {
            fun fits(m: DisplayModeInfo) = (1..2).any { k -> abs(m.refreshRate - k * fps) / (k * fps) < 0.001 }
            if (fits(current)) return null
            return supported.filter { it.width == current.width && it.height == current.height && fits(it) }.minByOrNull { it.refreshRate }
        }
    }

    private val m60 = DisplayModeInfo(1, 1920, 1080, 60f)
    private val m50 = DisplayModeInfo(2, 1920, 1080, 50f)
    private val m24 = DisplayModeInfo(3, 1920, 1080, 23.976f)
    private val m4k50 = DisplayModeInfo(4, 3840, 2160, 50f)

    private val display = FakeDisplay(m60, listOf(m60, m50, m24, m4k50))
    private val voter = Voter()
    private val strategies = mutableListOf<Int>()
    private var afr = AfrMode.ALWAYS

    private fun controller(sdk: Int) = DisplayModeController(
        picker = Picker,
        clock = MonoClock { SystemClock.uptimeMillis() },
        afrMode = { afr },
        setPlayerStrategy = { strategies += it },
        display = display,
        voter = voter,
        sdkInt = sdk,
    )

    private fun idle(ms: Long) = ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)

    // ------------------------------------------------------------------ API 31+: Surface.setFrameRate

    @Test
    fun api31VotesOnTheSurfaceAfterTheDwellInFull() {
        val c = controller(31)
        c.attachSurfaceView(SurfaceView(context))
        c.onViewport(Viewport.FULL)
        c.onTune(knownFpsMilli = 25_000)
        assertTrue("no switch before the dwell", voter.calls.isEmpty())
        idle(1_999)
        assertTrue(voter.calls.isEmpty())
        idle(1)
        assertEquals(listOf("vote 25.0"), voter.calls)
        assertEquals("the window path is not used on 31+", 0, c.request.value)
    }

    @Test
    fun api31RapidZappingNeverSwitches() {
        val c = controller(31)
        c.attachSurfaceView(SurfaceView(context))
        c.onViewport(Viewport.FULL)
        repeat(5) {
            c.onTune(25_000)
            idle(1_000)
        }
        assertTrue(voter.calls.isEmpty())
        idle(1_000)
        assertEquals(listOf("vote 25.0"), voter.calls)
    }

    @Test
    fun api31SwitchesAtOnceWhenThePreviousChannelWasWatchedForTheDwell() {
        val c = controller(31)
        c.attachSurfaceView(SurfaceView(context))
        c.onViewport(Viewport.FULL)
        c.onTune(null)
        idle(5_000)
        assertTrue("fps unknown: nothing", voter.calls.isEmpty())
        c.onTune(50_000) // CH+ after 5 s in full screen, cached fps
        assertEquals(listOf("vote 50.0"), voter.calls)
    }

    @Test
    fun api31MiniViewportNeverSwitchesAndRevertsAfterThirtySeconds() {
        val c = controller(31)
        c.attachSurfaceView(SurfaceView(context))
        c.onViewport(Viewport.MINI)
        c.onTune(25_000)
        idle(5_000)
        assertTrue("never in MINI (B1.8)", voter.calls.isEmpty())

        c.onViewport(Viewport.FULL)
        assertEquals(listOf("vote 25.0"), voter.calls)

        c.onViewport(Viewport.MINI)
        idle(29_999)
        assertEquals("content mode kept in MINI (no ping-pong)", listOf("vote 25.0"), voter.calls)
        c.onViewport(Viewport.FULL)
        c.onViewport(Viewport.MINI)
        idle(29_999)
        assertEquals(listOf("vote 25.0"), voter.calls)
        idle(1)
        assertEquals(listOf("vote 25.0", "revert"), voter.calls)
    }

    @Test
    fun api31FpsMeasuredLaterSwitchesOnceTheDwellPassed() {
        val c = controller(31)
        c.attachSurfaceView(SurfaceView(context))
        c.onViewport(Viewport.FULL)
        c.onTune(null)
        idle(500)
        c.onFps(29.97f)
        assertTrue(voter.calls.isEmpty())
        idle(1_500)
        assertEquals(listOf("vote 29.97"), voter.calls)
        c.onFps(29.97f)
        assertEquals("same fps: no new vote", 1, voter.calls.size)
    }

    @Test
    fun api31VoteIsReappliedWhenTheSurfaceIsRecreated() {
        val c = controller(31)
        val view = SurfaceView(context)
        c.attachSurfaceView(view)
        c.onViewport(Viewport.FULL)
        c.onTune(50_000)
        idle(2_000)
        c.surfaceCallback.surfaceCreated(view.holder) // standby → resume (S12)
        assertEquals(listOf("vote 50.0", "vote 50.0"), voter.calls)
        c.clear()
        c.surfaceCallback.surfaceCreated(view.holder)
        assertEquals(listOf("vote 50.0", "vote 50.0", "revert"), voter.calls)
    }

    @Test
    fun api31VotePendingWithoutSurfaceIsAppliedOnAttach() {
        val c = controller(31)
        c.onViewport(Viewport.FULL)
        c.onTune(25_000)
        idle(2_000)
        assertTrue(voter.calls.isEmpty())
        c.attachSurfaceView(SurfaceView(context))
        assertEquals(listOf("vote 25.0"), voter.calls)
    }

    @Test
    fun settingChangesClearTheSurfaceVoteAndSetThePlayerStrategy() {
        val c = controller(31)
        c.attachSurfaceView(SurfaceView(context))
        afr = AfrMode.SEAMLESS
        c.onAfrModeChanged(AfrMode.SEAMLESS)
        afr = AfrMode.ALWAYS
        c.onAfrModeChanged(AfrMode.ALWAYS)
        afr = AfrMode.OFF
        c.onAfrModeChanged(AfrMode.OFF)
        assertEquals(listOf("clear", "clear", "clear"), voter.calls)
        assertEquals(
            listOf(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF),
            strategies,
        )
    }

    @Test
    fun seamlessAndOffNeverSwitchAppSide() {
        for (mode in listOf(AfrMode.SEAMLESS, AfrMode.OFF)) {
            afr = mode
            for (sdk in listOf(30, 31)) {
                val c = controller(sdk)
                c.attachSurfaceView(SurfaceView(context))
                c.onViewport(Viewport.FULL)
                c.onTune(25_000)
                idle(5_000)
                assertEquals(0, c.request.value)
                c.release()
            }
        }
        assertTrue(voter.calls.isEmpty())
    }

    // ------------------------------------------------------------------ API < 31: preferredDisplayModeId

    @Test
    fun api30RequestsTheModeAndHoldsUntilTheDisplayChanged() {
        val c = controller(30)
        val events = mutableListOf<String>()
        c.switchListener = object : DisplayModeController.SwitchListener {
            override fun onSwitchStarted() { events += "hold" }
            override fun onSwitchSettled() { events += "resume" }
        }
        c.onViewport(Viewport.FULL)
        c.onTune(25_000)
        idle(2_000)
        assertEquals("25 fps → 50 Hz at the current resolution", m50.id, c.request.value)
        assertEquals(listOf("hold"), events)
        assertTrue(c.isHolding)
        assertEquals(60f, c.refreshHz.value)

        var played = false
        c.awaitSettled { played = true }
        assertFalse(played)
        display.switchTo(m50.id)
        assertTrue(played)
        assertEquals(listOf("hold", "resume"), events)
        assertEquals(50f, c.refreshHz.value)
        assertTrue(voter.calls.isEmpty())
    }

    @Test
    fun api30HoldEndsAfterThreeSecondsWithoutADisplayChange() {
        val c = controller(30)
        c.onViewport(Viewport.FULL)
        c.onTune(23_976)
        idle(2_000)
        assertEquals(m24.id, c.request.value)
        var played = false
        c.awaitSettled { played = true }
        idle(2_999)
        assertFalse(played)
        idle(1)
        assertTrue(played)
        assertFalse(c.isHolding)
    }

    @Test
    fun api30AwaitSettledReturnsAtOnceWithoutAPendingSwitch() {
        val c = controller(30)
        var played = false
        c.awaitSettled { played = true }
        assertTrue(played)
        c.onViewport(Viewport.FULL)
        c.onTune(30_000) // 60 Hz already fits 30 fps (2×): no switch
        idle(2_000)
        assertEquals(0, c.request.value)
        assertFalse(c.isHolding)
    }

    @Test
    fun api30RevertsTheRequestAfterThirtySecondsInMiniAndOnClear() {
        val c = controller(30)
        c.onViewport(Viewport.FULL)
        c.onTune(25_000)
        idle(2_000)
        display.switchTo(m50.id)
        assertEquals(m50.id, c.request.value)
        c.onViewport(Viewport.HIDDEN)
        idle(30_000)
        assertEquals(0, c.request.value)
        display.switchTo(m60.id) // the window went back to the default mode

        c.onViewport(Viewport.FULL)
        assertEquals("back in FULL after the dwell: request again", m50.id, c.request.value)
        assertTrue(c.isHolding)
        c.clear()
        assertEquals(0, c.request.value)
        assertFalse(c.isHolding)
    }

    @Test
    fun clearReleasesAPendingHold() {
        val c = controller(30)
        c.onViewport(Viewport.FULL)
        c.onTune(25_000)
        idle(2_000)
        var played = false
        c.awaitSettled { played = true }
        c.clear()
        assertTrue("a stop must not leave the controller waiting", played)
    }

    // ------------------------------------------------------------------ platform display access

    @Test
    fun platformDisplayAccessReadsTheDefaultDisplayModes() {
        ShadowDisplayManager.setSupportedModes(
            Display.DEFAULT_DISPLAY,
            ShadowDisplayManager.ModeBuilder.modeBuilder(10).setWidth(1920).setHeight(1080).setRefreshRate(60f).build(),
            ShadowDisplayManager.ModeBuilder.modeBuilder(11).setWidth(1920).setHeight(1080).setRefreshRate(50f).build(),
        )
        val access = PlatformDisplayAccess(context)
        val supported = access.supported()
        assertEquals(listOf(60f, 50f), supported.map { it.refreshRate })
        assertTrue(supported.all { it.width == 1920 && it.height == 1080 })
        assertTrue(access.current() != null)
        var changed = 0
        access.setListener { changed++ }
        ShadowDisplayManager.changeDisplay(Display.DEFAULT_DISPLAY, "w1280dp-h720dp")
        idle(0)
        assertTrue(changed >= 1)
        access.setListener(null)
    }
}

/** The default (`Build.VERSION.SDK_INT`) path on a real API 28 sandbox: the window request, no surface vote. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DisplayModeControllerApi28Test {
    @Test
    fun belowApi31UsesTheWindowRequest() {
        val display = DisplayModeControllerTest.FakeDisplay(
            DisplayModeInfo(1, 1920, 1080, 60f),
            listOf(DisplayModeInfo(1, 1920, 1080, 60f), DisplayModeInfo(2, 1920, 1080, 50f)),
        )
        val voter = DisplayModeControllerTest.Voter()
        val c = DisplayModeController(DisplayModeControllerTest.Picker, MonoClock { SystemClock.uptimeMillis() }, { AfrMode.ALWAYS }, { }, display, voter)
        c.attachSurfaceView(SurfaceView(ApplicationProvider.getApplicationContext()))
        c.onViewport(Viewport.FULL)
        c.onTune(25_000)
        ShadowLooper.idleMainLooper(2_000, TimeUnit.MILLISECONDS)
        assertEquals(2, c.request.value)
        assertTrue(voter.calls.isEmpty())
        // The real platform voter is a no-op below API 31 (never calls the 3-argument setFrameRate).
        assertFalse(PlatformFrameRateVoter.vote(SurfaceView(ApplicationProvider.getApplicationContext()).holder, 25f))
    }
}
