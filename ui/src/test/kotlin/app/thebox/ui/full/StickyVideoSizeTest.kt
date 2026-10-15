package app.thebox.ui.full

import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.TunedState
import app.thebox.core.guide.fakes.FakeGuideStateMachine
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.playback.AspectMode
import app.thebox.player.api.ConnectPhase
import app.thebox.core.playback.PlaybackError
import app.thebox.core.playback.PlaybackErrorCode
import app.thebox.core.playback.StreamFormat
import app.thebox.player.api.NowPlaying
import app.thebox.player.api.PlaybackState
import app.thebox.player.api.VideoInfo
import app.thebox.ui.root.AppRoot
import app.thebox.ui.shelltest.FakeShellHost
import app.thebox.ui.theme.BoxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sticky video size across a zap (docs/design/m4-player-ux.md §3.6.2, §5.4; S2, N4): the `SurfaceView` keeps its rect
 * from the old channel's `stop()` until the new tune renders a first frame; radio (first media without a frame) and an
 * `Error` release it; the aspect is held during an owned archive hand-off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class StickyVideoSizeTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ch = GuideFixtures.channel(1)
    private fun np(tuneId: Long) = NowPlaying(tuneId, ch.identity, ch.displayName, false, StreamFormat.TS)
    private fun connecting(id: Long) = PlaybackState.Connecting(np(id), ConnectPhase.CONNECTING, 0, 0)
    private fun playing(id: Long) = PlaybackState.Playing(np(id))
    private val fourThree = VideoInfo(width = 1440, height = 1080, renderedFirstFrames = 1)

    @Test
    fun rectHeldFromStopUntilTheNextFirstFrame() {
        val guide = FakeGuideStateMachine(GuideUiState(mode = ScreenMode.FULL, tuned = TunedState(identity = ch.identity, rowId = ch.rowId, phase = MiniPhase.PLAYING)))
        val host = FakeShellHost(guide)
        host.playbackFlow.value = playing(1)
        host.videoFlow.value = fourThree
        rule.setContent { BoxTheme { AppRoot(host, grid = { m -> Spacer(m) }) } }
        rule.waitForIdle()
        val pillarboxed = listOf(240, 0, 1680, 1080)
        assertEquals(pillarboxed, surfaceRect())
        // Zap: the controller stops (last frame kept) and resets VideoInfo to size 0 with the new tune.
        host.playbackFlow.value = connecting(2)
        host.videoFlow.value = VideoInfo(renderedFirstFrames = 1)
        rule.waitForIdle()
        assertEquals("held under the frozen frame", pillarboxed, surfaceRect())
        host.videoFlow.value = VideoInfo(width = 1920, height = 1080, renderedFirstFrames = 1)
        rule.waitForIdle()
        assertEquals("new size known, no frame yet: still held", pillarboxed, surfaceRect())
        host.videoFlow.value = VideoInfo(width = 1920, height = 1080, renderedFirstFrames = 2)
        rule.waitForIdle()
        assertEquals("first frame: the new rect", listOf(0, 0, 1920, 1080), surfaceRect())
    }

    @Test
    fun radioFirstMediaAndErrorRelease() {
        val s = StickyVideo()
        s.resolve(fourThree, playing(1), handoff = false)
        val held = s.resolve(VideoInfo(renderedFirstFrames = 1), connecting(2), handoff = false)
        assertEquals(1440, held.width)
        assertTrue(s.isHolding)
        val radio = s.resolve(VideoInfo(renderedFirstFrames = 1), playing(2), handoff = false)
        assertFalse("first media of a radio channel releases (N4)", s.isHolding)
        assertEquals(0, radio.width)

        val e = StickyVideo()
        e.resolve(fourThree, playing(1), handoff = false)
        e.resolve(VideoInfo(renderedFirstFrames = 1), connecting(2), handoff = false)
        assertTrue(e.isHolding)
        e.resolve(VideoInfo(renderedFirstFrames = 1), PlaybackState.Error(np(2), PlaybackError(PlaybackErrorCode.CHANNEL_OFFLINE)), handoff = false)
        assertFalse("an Error releases (N4)", e.isHolding)
    }

    @Test
    fun firstTuneHoldsNothingAndAStaleStateDoesNotRelease() {
        val s = StickyVideo()
        assertEquals(0, s.resolve(VideoInfo(), connecting(1), handoff = false).width)
        assertFalse(s.isHolding)
        s.resolve(fourThree, playing(1), handoff = false)
        s.resolve(VideoInfo(renderedFirstFrames = 1), connecting(2), handoff = false)
        assertTrue(s.isHolding)
    }

    @Test
    fun aspectHeldDuringAnOwnedHandoff() {
        val s = StickyVideo()
        val fill = fourThree.copy(aspectMode = AspectMode.FILL)
        assertEquals(AspectMode.FILL, s.resolve(fill, playing(1), handoff = false).aspectMode)
        // The new archive cycle resets VideoInfo (aspect back to the default FIT) while the hand-off is hidden.
        val during = s.resolve(VideoInfo(renderedFirstFrames = 1, aspectMode = AspectMode.FIT), connecting(2), handoff = true)
        assertEquals(AspectMode.FILL, during.aspectMode)
        val sized = s.resolve(VideoInfo(width = 1440, height = 1080, renderedFirstFrames = 2, aspectMode = AspectMode.FIT), playing(2), handoff = true)
        assertEquals("still the hand-off: FILL kept until ArchivePlayback re-applies it", AspectMode.FILL, sized.aspectMode)
        val after = s.resolve(VideoInfo(width = 1440, height = 1080, renderedFirstFrames = 2, aspectMode = AspectMode.FILL), playing(2), handoff = false)
        assertEquals(AspectMode.FILL, after.aspectMode)
    }

    private fun surfaceRect(): List<Int> {
        fun find(v: View): SurfaceView? {
            if (v is SurfaceView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        val v = find(rule.activity.window.decorView)!!
        val xy = IntArray(2)
        v.getLocationInWindow(xy)
        return listOf(xy[0], xy[1], xy[0] + v.width, xy[1] + v.height)
    }
}
