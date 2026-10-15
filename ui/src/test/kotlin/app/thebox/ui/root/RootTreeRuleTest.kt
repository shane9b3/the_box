package app.thebox.ui.root

import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.MiniPhase
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.TunedState
import app.thebox.core.guide.fakes.FakeGuideStateMachine
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.playback.Viewport
import app.thebox.ui.ambient.AMBIENT_TAG
import app.thebox.ui.guide.screen.GRID_SLOT_TAG
import app.thebox.ui.guide.screen.HEADER_COLUMN_TAG
import app.thebox.ui.shelltest.FakeShellHost
import app.thebox.ui.theme.BoxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The root tree rule (docs/design/m3-guide.md §3.13.1, §5.3 `RootTreeRuleTest`, CLAUDE.md, B1, B8) on a 1920 × 1080
 * window with the real `VideoLayer` (media3 `PlayerSurface` → `AndroidView` → `SurfaceView`):
 * - no ancestor of `VideoLayer` carries a layer (`graphicsLayer`, alpha, clip, shadow) or a layer-making modifier;
 * - the ambient background and the header/panel column each carry their own default layer (B8);
 * - the key sink is a sibling of `VideoLayer`, not its ancestor (B1); `VideoLayer` is the root's first child;
 * - the hole and the `SurfaceView` land on the §3.1 rect, and the viewport follows the hole mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class RootTreeRuleTest {
    @Suppress("DEPRECATION") // v1 rule: UnconfinedTestDispatcher, matching how these tests synchronise
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val tunedId = GuideFixtures.identity(1)
    private fun playing(still: Boolean = false) = TunedState(identity = tunedId, rowId = 1, phase = MiniPhase.PLAYING, still = still)

    private fun show(state: GuideUiState): FakeShellHost {
        val host = FakeShellHost(FakeGuideStateMachine(state))
        rule.setContent { BoxTheme { AppRoot(host, grid = { m -> Spacer(m.graphicsLayer()) }) } }
        rule.waitForIdle()
        return host
    }

    private fun layoutOf(tag: String): LayoutInfo = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().layoutInfo

    private fun ancestors(info: LayoutInfo): List<LayoutInfo> = generateSequence(info.parentInfo) { it.parentInfo }.toList()

    private fun hasLayer(info: LayoutInfo): Boolean = info.getModifierInfo().any { mi ->
        mi.extra != null || LAYER_NAMES.any { mi.modifier::class.java.name.contains(it) }
    }

    private fun surfaceView(): SurfaceView {
        fun find(v: View): SurfaceView? {
            if (v is SurfaceView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        val v = find(rule.activity.window.decorView)
        assertNotNull("PlayerSurface created its SurfaceView", v)
        return v!!
    }

    private fun windowRect(v: View): IntArray {
        val xy = IntArray(2)
        v.getLocationInWindow(xy)
        return intArrayOf(xy[0], xy[1], xy[0] + v.width, xy[1] + v.height)
    }

    @Test
    fun noLayerOnAnyAncestorOfTheVideoLayer() {
        show(GuideUiState(tuned = playing()))
        val video = layoutOf(VIDEO_LAYER_TAG)
        val chain = ancestors(video)
        assertTrue("the video layer has ancestors", chain.isNotEmpty())
        for (a in chain) {
            val names = a.getModifierInfo().map { it.modifier::class.java.simpleName + "/" + (it.extra?.let { e -> e::class.java.simpleName } ?: "-") }
            // No layer-making modifier (graphicsLayer, alpha, clip, shadow, RenderEffect) on any ancestor.
            assertFalse("ancestor carries a layer modifier: $names", a.getModifierInfo().any { mi -> LAYER_NAMES.any { mi.modifier::class.java.name.contains(it) } })
            // No coordinator layer either, except the one Compose's RootMeasurePolicy gives every top-level content
            // node (`placeRelativeWithLayer(0, 0)`, ui 1.12.1 RootMeasurePolicy.kt:42/59): a default RenderNode with
            // no alpha, clip or offscreen buffer, equivalent to the AndroidComposeView's own.
            val topLevel = a.parentInfo?.parentInfo == null
            if (!topLevel) assertFalse("ancestor has a coordinator layer: $names", a.getModifierInfo().any { it.extra != null })
        }
    }

    @Test
    fun siblingsCarryTheirOwnDefaultLayer() {
        show(GuideUiState(tuned = playing()))
        assertTrue("AmbientBackground has a layer (B8)", hasLayer(layoutOf(AMBIENT_TAG)))
        assertTrue("the header/panel column has a layer (B8)", hasLayer(layoutOf(HEADER_COLUMN_TAG)))
        assertTrue("the grid slot passes the host its modifier", hasLayer(layoutOf(GRID_SLOT_TAG)))
    }

    @Test
    fun keySinkIsASiblingAndVideoLayerComesFirst() {
        show(GuideUiState(tuned = playing()))
        val video = layoutOf(VIDEO_LAYER_TAG)
        val sink = layoutOf(KEY_SINK_TAG)
        val ambient = layoutOf(AMBIENT_TAG)
        val chrome = layoutOf(HOLE_CHROME_TAG)
        assertSame("same parent", video.parentInfo, sink.parentInfo)
        assertFalse("the sink is not an ancestor of the video layer", ancestors(video).contains(sink))
        assertTrue("same parent for all four", listOf(ambient, chrome).all { it.parentInfo === video.parentInfo })
        // Draw order = child order of the root Box: VideoLayer, AmbientBackground, HoleChrome, key sink.
        val tags = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode().children.mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
        assertEquals(listOf(VIDEO_LAYER_TAG, AMBIENT_TAG, HOLE_CHROME_TAG, KEY_SINK_TAG), tags.filter { it in setOf(VIDEO_LAYER_TAG, AMBIENT_TAG, HOLE_CHROME_TAG, KEY_SINK_TAG) })
    }

    @Test
    fun miniHoleAndSurfaceSitOnTheDesignRect() {
        val host = show(GuideUiState(tuned = playing()))
        assertEquals(1920 to 1080, host.window)
        assertEquals(listOf(64, 56, 784, 461), windowRect(surfaceView()).toList())
        assertEquals(Viewport.MINI, host.viewports.last())
    }

    @Test
    fun stillHidesTheVideoTrackAndParksTheSurface() {
        val host = show(GuideUiState(tuned = playing(still = true)))
        assertEquals("still → viewport HIDDEN (TrackController disables video)", Viewport.HIDDEN, host.viewports.last())
        val r = windowRect(surfaceView())
        assertEquals(1, r[2] - r[0])
        assertEquals(1, r[3] - r[1])
    }

    @Test
    fun fullReEnablesVideoAtTheWholeWindow() {
        val host = show(GuideUiState(mode = ScreenMode.FULL, tuned = playing(still = true)))
        assertEquals("FULL re-enables the track even when the mini was a still", Viewport.FULL, host.viewports.last())
        assertEquals(listOf(0, 0, 1920, 1080), windowRect(surfaceView()).toList())
    }

    @Test
    fun searchAndSettingsCoverTheHole() {
        val host = show(GuideUiState(mode = ScreenMode.SETTINGS, tuned = playing()))
        assertEquals(Viewport.HIDDEN, host.viewports.last())
        rule.onNodeWithTag(SETTINGS_TAG).fetchSemanticsNode()
    }

    /** Code review 2026-10-14, H-S8: HoleChrome has its own default layer, as a sibling (not an ancestor) of the video. */
    @Test
    fun holeChromeCarriesItsOwnLayerAsASibling() {
        show(GuideUiState(tuned = playing()))
        val chrome = layoutOf(HOLE_CHROME_TAG)
        assertTrue("HoleChrome has a layer (H-S8)", hasLayer(chrome))
        assertFalse("not an ancestor of the video layer", ancestors(layoutOf(VIDEO_LAYER_TAG)).contains(chrome))
    }

    /**
     * Code review 2026-10-14, H-B1: an HDR poster in the mini player keeps viewport MINI and shows the poster still; the
     * host reacts like the controller (posterMode while MINI) and the viewport settles at once.
     */
    @Test
    fun hdrPosterStaysMiniAndShowsThePoster() {
        val host = FakeShellHost(FakeGuideStateMachine(GuideUiState(tuned = playing())))
        host.onViewport = { vp -> host.videoFlow.value = host.videoFlow.value.copy(posterMode = vp == Viewport.MINI) }
        rule.setContent { BoxTheme { AppRoot(host, grid = { m -> Spacer(m.graphicsLayer()) }) } }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertEquals("one MINI request, no flip-flop", listOf(Viewport.MINI), host.viewports.distinct())
        assertTrue("settled: ${host.viewports}", host.viewports.size <= 2)
        assertTrue(host.videoFlow.value.posterMode)
        rule.onNodeWithTag(app.thebox.ui.guide.screen.MINI_STILL_TAG, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(listOf(64, 56, 784, 461), windowRect(surfaceView()).toList())
    }

    private companion object {
        val LAYER_NAMES = listOf("GraphicsLayer", "Clip", "Shadow", "RenderEffect", "Alpha")
    }
}
