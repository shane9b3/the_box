package app.thebox.ui.root

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.TunedState
import app.thebox.core.guide.fakes.FakeGuideStateMachine
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.playback.StreamFormat
import app.thebox.core.playback.Viewport
import app.thebox.player.api.ConnectPhase
import app.thebox.player.api.NowPlaying
import app.thebox.player.api.PlaybackState
import app.thebox.ui.full.SUBTITLE_LAYER_TAG
import app.thebox.ui.shelltest.FakePlayerHost
import app.thebox.ui.shelltest.FakeShellHost
import app.thebox.ui.sports.ClipUi
import app.thebox.ui.theme.BoxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §6.5, §10.2, A-S2; WP-5) The root while a Sports clip plays: SPORTS + clip is
 * a FULL hole, `beneath` keeps the viewport the page covered, the hole is black until the clip's first rendered frame,
 * `SubtitleLayer` is composed, the root draws no canvas behind the page, and a tune past the clip never gets the FULL hole.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppRootClipTest {
    @Suppress("DEPRECATION") // v1 rule, as RootTreeRuleTest
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val x = GuideFixtures.identity(1)
    private val playing = TunedState(identity = x, rowId = 1, phase = MiniPhase.PLAYING)
    private fun live(tuneId: Long) = PlaybackState.Playing(NowPlaying(tuneId, x, "Live", isArchive = false, format = StreamFormat.TS))
    private fun clip(tuneId: Long) = PlaybackState.Connecting(NowPlaying(tuneId, null, "Clip", false, StreamFormat.HLS, isClip = true), ConnectPhase.CONNECTING, 0, 0)

    private lateinit var machine: FakeGuideStateMachine
    private lateinit var host: FakeShellHost
    private val clipUi: MutableState<ClipUi> = mutableStateOf(ClipUi())

    private fun show(player: FakePlayerHost? = null, video: Boolean = true) {
        machine = FakeGuideStateMachine(GuideUiState(tuned = playing))
        host = FakeShellHost(machine, player = player)
        host.playbackFlow.value = live(5)
        rule.setContent {
            BoxTheme {
                if (video) AppRoot(host, sportsClip = clipUi, grid = { m -> Spacer(m.graphicsLayer()) })
                else AppRoot(host, sportsClip = clipUi, videoLayer = {}, grid = { m -> Spacer(m.graphicsLayer()) })
            }
        }
        rule.waitForIdle()
    }

    private fun mode(m: ScreenMode) {
        rule.runOnIdle { machine.mutableUi.value = machine.mutableUi.value.copy(mode = m) }
        rule.waitForIdle()
    }

    private fun startClip(tuneId: Long = 100) {
        rule.runOnIdle {
            host.playbackFlow.value = clip(tuneId)
            clipUi.value = ClipUi(showing = true, tuneId = tuneId)
        }
        rule.waitForIdle()
    }

    @Test
    fun theClipIsFullAndBeneathKeepsTheCoveredViewport() {
        show()
        mode(ScreenMode.SPORTS)
        startClip()
        assertEquals(Viewport.FULL, host.viewports.last())
        // ClipSession hides the layer, then the resume tune starts.
        rule.runOnIdle { clipUi.value = ClipUi() }
        rule.waitForIdle()
        rule.runOnIdle { host.playbackFlow.value = live(111) }
        rule.waitForIdle()
        assertEquals("guide MINI → Sports → clip FULL → Sports: ${host.viewports}", listOf(Viewport.MINI, Viewport.FULL, Viewport.MINI), runs(host.viewports))
        mode(ScreenMode.GUIDE)
        assertEquals(Viewport.MINI, host.viewports.last())
    }

    @Test
    fun aTunePastTheClipNeverGetsTheFullHoleEvenBeforeTheLayerHides() {
        show()
        mode(ScreenMode.SPORTS)
        startClip()
        rule.runOnIdle { host.playbackFlow.value = live(111) } // the resume tune arrives first
        rule.waitForIdle()
        assertEquals(Viewport.MINI, host.viewports.last())
        assertFalse(clipHoleFull(ScreenMode.SPORTS, ClipUi(true, 100), live(111)))
        assertTrue(clipHoleFull(ScreenMode.SPORTS, ClipUi(true, 100), live(5)))
        assertTrue(clipHoleFull(ScreenMode.SPORTS, ClipUi(true, 100), clip(105)))
        assertFalse(clipHoleFull(ScreenMode.GUIDE, ClipUi(true, 100), clip(100)))
        assertFalse(clipHoleFull(ScreenMode.SPORTS, ClipUi(false, 100), clip(100)))
    }

    @Test
    fun theHoleIsBlackUntilTheClipsFirstFrame() {
        show(video = false)
        mode(ScreenMode.SPORTS)
        rule.mainClock.autoAdvance = false
        startClip()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertTrue("black while the clip has no frame", centreIsBlack())
        rule.runOnIdle { host.videoFlow.value = host.videoFlow.value.copy(renderedFirstFrames = host.videoFlow.value.renderedFirstFrames + 1) }
        rule.mainClock.advanceTimeBy(SCRIM_MS * 3L)
        rule.waitForIdle()
        assertFalse("the scrim lifts on the first frame", centreIsBlack())
    }

    @Test
    fun theHoleStaysBlackUntilTheCapWithoutAFrame() {
        show(video = false)
        mode(ScreenMode.SPORTS)
        rule.mainClock.autoAdvance = false
        startClip()
        rule.mainClock.advanceTimeBy(CLIP_BLACK_MAX_WAIT_MS - 500)
        rule.waitForIdle()
        assertTrue(centreIsBlack())
        rule.mainClock.advanceTimeBy(500 + SCRIM_MS * 3L)
        rule.waitForIdle()
        assertFalse(centreIsBlack())
    }

    @Test
    fun subtitlesAreComposedForTheClipOnly() {
        show(player = FakePlayerHost())
        mode(ScreenMode.SPORTS)
        rule.onNodeWithTag(SUBTITLE_LAYER_TAG, useUnmergedTree = true).assertDoesNotExist()
        startClip()
        rule.onNodeWithTag(SUBTITLE_LAYER_TAG, useUnmergedTree = true).fetchSemanticsNode()
        rule.runOnIdle { clipUi.value = ClipUi() }
        rule.waitForIdle()
        rule.onNodeWithTag(SUBTITLE_LAYER_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun noCanvasBehindThePageWhileTheClipShows() {
        show()
        mode(ScreenMode.SPORTS)
        assertTrue("the page's canvas", sportsHasBackground())
        startClip()
        assertFalse("transparent over the clip", sportsHasBackground())
    }

    /** Consecutive duplicates collapsed. */
    private fun runs(v: List<Viewport>): List<Viewport> = v.filterIndexed { i, e -> i == 0 || v[i - 1] != e }

    private fun sportsHasBackground(): Boolean =
        rule.onNodeWithTag(SPORTS_TAG, useUnmergedTree = true).fetchSemanticsNode().layoutInfo.getModifierInfo()
            .any { it.modifier::class.java.name.contains("Background") }

    private fun centreIsBlack(): Boolean {
        val px = rule.onRoot().captureToImage().toPixelMap()
        val c = px[px.width / 2, px.height / 2]
        return c.alpha > 0.99f && c.red < 0.02f && c.green < 0.02f && c.blue < 0.02f
    }
}
