package app.thebox.ui.root

import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsFocused
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

    /**
     * Owner report 2026-11-10 (docs/decisions/2026-11-10-guide-start-back-audio.md): the audio dropped for about a second
     * when Settings opened over the guide and again on the way back. Settings (Search, Sports) covering the hole keeps the
     * viewport of the screen beneath, so the video track is never toggled (a toggle re-selects tracks in the player).
     */
    @Test
    fun anOpaqueScreenOverTheGuideKeepsTheMiniViewport() {
        val machine = FakeGuideStateMachine(GuideUiState(tuned = playing()))
        val host = FakeShellHost(machine)
        rule.setContent { BoxTheme { AppRoot(host, grid = { m -> Spacer(m.graphicsLayer()) }) } }
        rule.waitForIdle()
        for (over in listOf(ScreenMode.SETTINGS, ScreenMode.SEARCH, ScreenMode.SPORTS)) {
            rule.runOnIdle { machine.mutableUi.value = machine.mutableUi.value.copy(mode = over) }
            rule.waitForIdle()
            rule.runOnIdle { machine.mutableUi.value = machine.mutableUi.value.copy(mode = ScreenMode.GUIDE) }
            rule.waitForIdle()
        }
        assertEquals("guide → Settings/Search/Sports → guide: ${host.viewports}", listOf(Viewport.MINI), host.viewports.distinct())
    }

    @Test
    fun settingsOverFullKeepsTheFullViewport() {
        val machine = FakeGuideStateMachine(GuideUiState(mode = ScreenMode.FULL, tuned = playing()))
        val host = FakeShellHost(machine)
        rule.setContent { BoxTheme { AppRoot(host, grid = { m -> Spacer(m.graphicsLayer()) }) } }
        rule.waitForIdle()
        rule.runOnIdle { machine.mutableUi.value = machine.mutableUi.value.copy(mode = ScreenMode.SETTINGS) }
        rule.waitForIdle()
        rule.onNodeWithTag(SETTINGS_TAG).fetchSemanticsNode()
        rule.runOnIdle { machine.mutableUi.value = machine.mutableUi.value.copy(mode = ScreenMode.FULL) }
        rule.waitForIdle()
        assertEquals("FULL → Settings → FULL: ${host.viewports}", listOf(Viewport.FULL), host.viewports.distinct())
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

    // ---- M4 (docs/design/m4-player-ux.md §3.6.3, §5.4; N11, N15)

    private fun showFull(player: app.thebox.ui.shelltest.FakePlayerHost = app.thebox.ui.shelltest.FakePlayerHost()): FakeShellHost {
        val host = FakeShellHost(FakeGuideStateMachine(GuideUiState(mode = ScreenMode.FULL, tuned = playing())), player = player)
        rule.setContent { BoxTheme { AppRoot(host, grid = { m -> Spacer(m.graphicsLayer()) }) } }
        rule.waitForIdle()
        return host
    }

    private fun subtitleView(): androidx.media3.ui.SubtitleView {
        fun find(v: View): androidx.media3.ui.SubtitleView? {
            if (v is androidx.media3.ui.SubtitleView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        val v = find(rule.activity.window.decorView)
        assertNotNull("SubtitleLayer created its SubtitleView", v)
        return v!!
    }

    /** §5.4: SubtitleLayer is a direct child of the root Box, after HoleChrome and before the key sink; never an ancestor. */
    @Test
    fun subtitleLayerIsADirectSiblingBetweenChromeAndSink() {
        showFull()
        val video = layoutOf(VIDEO_LAYER_TAG)
        val subs = layoutOf(app.thebox.ui.full.SUBTITLE_LAYER_TAG)
        assertSame("direct child of the root Box (N11)", video.parentInfo, subs.parentInfo)
        assertFalse("not an ancestor of the video layer", ancestors(video).contains(subs))
        val tags = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode().children.mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
        val order = listOf(VIDEO_LAYER_TAG, AMBIENT_TAG, HOLE_CHROME_TAG, app.thebox.ui.full.SUBTITLE_LAYER_TAG, KEY_SINK_TAG)
        assertEquals(order, tags.filter { it in order })
        // §8 Q16: record whether AndroidView adds a layer of its own (a sibling's layer is allowed by the surface rule).
        println("RootTreeRuleTest: AndroidView(SubtitleView) node has a layer = ${hasLayer(subs)}; modifiers = " +
            subs.getModifierInfo().map { it.modifier::class.java.simpleName + "/" + (it.extra?.let { e -> e::class.java.simpleName } ?: "-") })
    }

    /** §5.4: FULL with every overlay drawn keeps the rule: no layer on any ancestor of VideoLayer, overlays are in the sink. */
    @Test
    fun fullOverlaysStaySiblingsOfTheVideoLayer() {
        val player = app.thebox.ui.shelltest.FakePlayerHost()
        player.machine.mutableUi.value = app.thebox.ui.full.PlayerOverlayStates.all(GuideFixtures.channel(1))
        showFull(player)
        val video = layoutOf(VIDEO_LAYER_TAG)
        for (a in ancestors(video)) {
            val topLevel = a.parentInfo?.parentInfo == null
            assertFalse(a.getModifierInfo().any { mi -> LAYER_NAMES.any { mi.modifier::class.java.name.contains(it) } })
            if (!topLevel) assertFalse(a.getModifierInfo().any { it.extra != null })
        }
        val sink = layoutOf(KEY_SINK_TAG)
        for (tag in listOf(app.thebox.ui.full.INFO_BAR_TAG, app.thebox.ui.full.QUICK_BAR_TAG, app.thebox.ui.full.STATS_OVERLAY_TAG, app.thebox.ui.full.PICKER_SHEET_TAG, app.thebox.ui.full.PLAYER_ERROR_CARD_TAG)) {
            val node = layoutOf(tag)
            assertTrue("$tag is inside the key sink", ancestors(node).contains(sink))
            assertFalse("$tag is not an ancestor of the video layer", ancestors(video).contains(node))
        }
    }

    /** N15 (`SubtitleViewFocusTest`): the SubtitleView never takes focus from the key sink and is hidden from a11y. */
    @Test
    fun subtitleViewNeverTakesFocus() {
        showFull()
        val v = subtitleView()
        assertFalse(v.isFocusable)
        assertFalse(v.isFocusableInTouchMode)
        assertFalse(v.isClickable)
        assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, v.descendantFocusability)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, v.importantForAccessibility)
        rule.onNodeWithTag(KEY_SINK_TAG).assertIsFocused()
        assertFalse("the view does not hold focus", v.hasFocus())
    }

    /** The subtitle layer sits on the video rect (window px). */
    @Test
    fun subtitleLayerSitsOnTheVideoRect() {
        showFull()
        assertEquals(listOf(0, 0, 1920, 1080), windowRect(subtitleView()).toList())
    }

    @Test
    fun noSubtitleLayerInTheGuide() {
        val host = FakeShellHost(FakeGuideStateMachine(GuideUiState(tuned = playing())), player = app.thebox.ui.shelltest.FakePlayerHost())
        rule.setContent { BoxTheme { AppRoot(host, grid = { m -> Spacer(m.graphicsLayer()) }) } }
        rule.waitForIdle()
        rule.onNodeWithTag(app.thebox.ui.full.SUBTITLE_LAYER_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    private companion object {
        val LAYER_NAMES = listOf("GraphicsLayer", "Clip", "Shadow", "RenderEffect", "Alpha")
    }
}
