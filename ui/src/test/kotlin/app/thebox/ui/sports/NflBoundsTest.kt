package app.thebox.ui.sports

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.thebox.core.sports.BottomBar
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.PlacementInput
import app.thebox.core.sports.ScoreBugPlacement
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.SportsPrefs
import app.thebox.ui.theme.BoxDesignScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * docs/design/m6-nfl.md §10.2 `NflBoundsTest` (WP-4, S4): the NFL bug lands on the box the real placer returns for the
 * NFL (1920 × 1080 window, 1 design px = 1 px), four corners × two sizes; the §4.1 rest boxes once WP-1's NFL widths have
 * landed; place mode draws the NFL sample with its hint; a null box fades out and leaves no node; and the root-tree rule:
 * the bug's only layer is its own leaf, never an ancestor of the video stand-in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class NflBoundsTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val repo = FakeScoreRepository(SportsPrefs())
    private val input = mutableStateOf(NflTestSupport.input())
    private val hooks = NflTestSupport.hooks(probe = null)

    private fun render() {
        rule.mainClock.autoAdvance = true
        rule.setContent {
            BoxDesignScale {
                Box(Modifier.fillMaxSize()) {
                    Spacer(Modifier.fillMaxSize().testTag(VIDEO))
                    NflBug(repo, input.value, ScoreBugFixtures.noGeometry, Modifier, ScoreBugPlacement::place, hooks)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String = SCORE_BUG_TAG): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun nfl(corner: ScoreCorner, size: ScoreBugSize = ScoreBugSize.STANDARD, bar: BottomBar = BottomBar.NONE): PlacementInput =
        ScoreBugFixtures.input(corner, size, bar = bar).copy(league = app.thebox.core.sports.League.NFL)

    private fun placed(p: PlacementInput): Rect = ScoreBugPlacement.place(p, ScoreBugFixtures.noGeometry)!!.let { Rect(it.l, it.t, it.r, it.b) }

    private fun cornersAt(size: ScoreBugSize, expected: Map<ScoreCorner, Rect>?) {
        repo.bug.value = SportsFixtures.nflView()
        input.value = nfl(ScoreCorner.TOP_RIGHT, size)
        render()
        for (corner in ScoreCorner.entries) {
            input.value = nfl(corner, size)
            rule.waitForIdle()
            rule.mainClock.advanceTimeBy(1000)
            val b = bounds()
            assertEquals("$corner $size on the placer's box", placed(nfl(corner, size)), b)
            assertEquals("the pill is the NFL width", ScoreBugPlacement.width(size, app.thebox.core.sports.League.NFL), b.width, 0.01f)
            expected?.get(corner)?.let { assertEquals("$corner $size §4.1", it, b) }
        }
    }

    @Test
    fun fourCornersAtStandardSize() = cornersAt(ScoreBugSize.STANDARD, null)

    @Test
    fun fourCornersAtLargeSize() = cornersAt(ScoreBugSize.LARGE, null)

    @Test
    fun theDesignedRestBoxes() {
        cornersAt(
            ScoreBugSize.STANDARD,
            mapOf(
                ScoreCorner.TOP_RIGHT to Rect(1304f, 56f, 1824f, 126f),
                ScoreCorner.TOP_LEFT to Rect(96f, 56f, 616f, 126f),
                ScoreCorner.BOTTOM_RIGHT to Rect(1304f, 954f, 1824f, 1024f),
                ScoreCorner.BOTTOM_LEFT to Rect(96f, 954f, 616f, 1024f),
            ),
        )
    }

    @Test
    fun theDesignedLargeRestBoxes() {
        cornersAt(
            ScoreBugSize.LARGE,
            mapOf(
                ScoreCorner.TOP_RIGHT to Rect(1174f, 56f, 1824f, 144f),
                ScoreCorner.TOP_LEFT to Rect(96f, 56f, 746f, 144f),
                ScoreCorner.BOTTOM_RIGHT to Rect(1174f, 936f, 1824f, 1024f),
                ScoreCorner.BOTTOM_LEFT to Rect(96f, 936f, 746f, 1024f),
            ),
        )
    }

    @Test
    fun nothingWithoutAFollowedLiveGame() {
        render()
        rule.onNodeWithTag(SCORE_BUG_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aNullBoxFadesOutAndLeavesNoNodeAndComesBack() {
        repo.bug.value = SportsFixtures.nflView()
        input.value = nfl(ScoreCorner.BOTTOM_LEFT)
        render()
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithTag(SCORE_BUG_TAG, useUnmergedTree = true).assertExists()
        input.value = nfl(ScoreCorner.BOTTOM_LEFT, bar = BottomBar.PASSIVE_INFO)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        rule.onNodeWithTag(SCORE_BUG_TAG, useUnmergedTree = true).assertDoesNotExist()
        input.value = nfl(ScoreCorner.BOTTOM_LEFT)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(placed(nfl(ScoreCorner.BOTTOM_LEFT)), bounds())
    }

    @Test
    fun placeModeDrawsTheNflSampleWithItsHintTowardTheCentre() {
        input.value = NflTestSupport.input(placing = true)
        render()
        rule.mainClock.advanceTimeBy(1000)
        val bug = bounds()
        assertEquals(placed(NflTestSupport.input(placing = true)), bug)
        val hint = bounds(SCORE_BUG_HINT_TAG)
        assertTrue("hint $hint left of the bug", hint.right <= bug.left)
        assertEquals((bug.top + bug.bottom) / 2f, (hint.top + hint.bottom) / 2f, 1.5f)
        val desc = rule.onNodeWithTag(SCORE_BUG_TAG, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(desc, desc.startsWith("Chiefs 24, Bills 17, 3:42 left in the "))
        assertTrue(desc, desc.endsWith(". Chiefs ball, 1st and 10."))
    }

    @Test
    fun theBugsOnlyLayerIsItsOwnLeafAndNeverAnAncestorOfTheVideo() {
        repo.bug.value = SportsFixtures.nflView()
        render()
        rule.mainClock.advanceTimeBy(1000)
        val video = layoutOf(VIDEO)
        val bug = layoutOf(SCORE_BUG_TAG)
        val bugChain = ancestors(bug)
        // The first layer above the pill is the bug's own (fade + clip); everything above it is layer-free.
        val layered = bugChain.filter { hasLayer(it) }
        assertEquals("one layer: the bug's own leaf", 1, layered.size)
        val own = layered.single()
        assertFalse("the bug's layer holds no video", ancestors(video).contains(own))
        for (a in ancestors(video)) {
            val topLevel = a.parentInfo?.parentInfo == null
            if (!topLevel) assertFalse("a video ancestor carries a layer", hasLayer(a))
        }
    }

    private fun layoutOf(tag: String): LayoutInfo = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().layoutInfo
    private fun ancestors(info: LayoutInfo): List<LayoutInfo> = generateSequence(info.parentInfo) { it.parentInfo }.toList()
    private fun hasLayer(info: LayoutInfo): Boolean = info.getModifierInfo().any { mi ->
        LAYER_NAMES.any { mi.modifier::class.java.name.contains(it) }
    }

    private companion object {
        const val VIDEO = "nfl_bounds_video"
        val LAYER_NAMES = listOf("GraphicsLayer", "Clip", "Shadow", "RenderEffect", "Alpha")
    }
}
