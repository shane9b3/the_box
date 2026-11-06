package app.thebox.ui.sports

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.thebox.core.sports.BottomBar
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.PlacementInput
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.SportsPrefs
import app.thebox.ui.full.PlayerUi
import app.thebox.ui.theme.BoxDesignScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * docs/design/m6-score-bug.md §8 `ScoreBugBoundsTest`: the bug lands on the box the placer returns (1920 × 1080 window, so
 * 1 design px = 1 px), at Standard and Large; place mode draws the ring's hint and the sample bug with no followed game; a
 * moved box slides and a null box fades out and leaves no node. The placer is the real `ScoreBugPlacement.place` (integration pass 2026-11-05).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class ScoreBugBoundsTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val repo = FakeScoreRepository(SportsPrefs())
    private val input = mutableStateOf(ScoreBugFixtures.input())
    private val low = mutableStateOf(false)

    private fun render() {
        rule.mainClock.autoAdvance = true
        rule.setContent {
            BoxDesignScale {
                ScoreBug(repo, input.value, ScoreBugFixtures.noGeometry, low.value)
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String = SCORE_BUG_TAG): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test
    fun fourCornersAtStandardSize() {
        repo.bug.value = ScoreBugFixtures.live
        render()
        val expected = mapOf(
            ScoreCorner.TOP_RIGHT to Rect(1420f, 56f, 1824f, 126f),
            ScoreCorner.TOP_LEFT to Rect(96f, 56f, 500f, 126f),
            ScoreCorner.BOTTOM_RIGHT to Rect(1420f, 954f, 1824f, 1024f),
            ScoreCorner.BOTTOM_LEFT to Rect(96f, 954f, 500f, 1024f),
        )
        for ((corner, rect) in expected) {
            input.value = ScoreBugFixtures.input(corner)
            rule.waitForIdle()
            rule.mainClock.advanceTimeBy(1000)
            assertEquals("$corner", rect, bounds())
        }
    }

    @Test
    fun fourCornersAtLargeSize() {
        repo.bug.value = ScoreBugFixtures.live
        input.value = ScoreBugFixtures.input(size = ScoreBugSize.LARGE)
        render()
        val expected = mapOf(
            ScoreCorner.TOP_RIGHT to Rect(1318f, 56f, 1824f, 144f),
            ScoreCorner.TOP_LEFT to Rect(96f, 56f, 602f, 144f),
            ScoreCorner.BOTTOM_RIGHT to Rect(1318f, 936f, 1824f, 1024f),
            ScoreCorner.BOTTOM_LEFT to Rect(96f, 936f, 602f, 1024f),
        )
        for ((corner, rect) in expected) {
            input.value = ScoreBugFixtures.input(corner, ScoreBugSize.LARGE)
            rule.waitForIdle()
            rule.mainClock.advanceTimeBy(1000)
            assertEquals("$corner", rect, bounds())
        }
    }

    @Test
    fun nothingWithoutAFollowedLiveGame() {
        render()
        rule.onNodeWithTag(SCORE_BUG_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aBottomCornerSlidesForAUserBarAndSnapsInLowEndMode() {
        repo.bug.value = ScoreBugFixtures.live
        input.value = ScoreBugFixtures.input(ScoreCorner.BOTTOM_RIGHT)
        render()
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(954f, bounds().top)
        input.value = ScoreBugFixtures.input(ScoreCorner.BOTTOM_RIGHT, bar = BottomBar.USER_BAR)
        rule.mainClock.autoAdvance = false
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(60)
        val mid = bounds().top
        assertTrue("mid-slide $mid", mid < 954f && mid > PlayerUi.BAR_T - 16f - 70f)
        rule.mainClock.advanceTimeBy(400)
        assertEquals(PlayerUi.BAR_T - PlayerUi.SCORE_BAR_GAP - 70f, bounds().top)
        // Low-end: the next move is immediate.
        low.value = true
        rule.waitForIdle()
        input.value = ScoreBugFixtures.input(ScoreCorner.BOTTOM_RIGHT, bar = BottomBar.QUICK)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(16)
        assertEquals(PlayerUi.QUICK_T - PlayerUi.SCORE_BAR_GAP - 70f, bounds().top)
    }

    @Test
    fun aNullBoxFadesOutAndLeavesNoNode() {
        repo.bug.value = ScoreBugFixtures.live
        input.value = ScoreBugFixtures.input(ScoreCorner.BOTTOM_LEFT)
        render()
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithTag(SCORE_BUG_TAG, useUnmergedTree = true).assertExists()
        input.value = ScoreBugFixtures.input(ScoreCorner.BOTTOM_LEFT, bar = BottomBar.PASSIVE_INFO)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        rule.onNodeWithTag(SCORE_BUG_TAG, useUnmergedTree = true).assertDoesNotExist()
        // And it comes back.
        input.value = ScoreBugFixtures.input(ScoreCorner.BOTTOM_LEFT)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(Rect(96f, 954f, 500f, 1024f), bounds())
    }

    @Test
    fun placeModeDrawsTheSampleBugWithItsHintTowardTheCentre() {
        // No followed game (bug == null): the sample bug, and the hint pill beside it.
        input.value = ScoreBugFixtures.input(ScoreCorner.TOP_RIGHT, placing = true)
        render()
        rule.mainClock.advanceTimeBy(1000)
        val bug = bounds()
        assertEquals(Rect(1420f, 56f, 1824f, 126f), bug)
        val hint = bounds(SCORE_BUG_HINT_TAG)
        assertTrue("hint ${hint} is left of the bug (toward the centre)", hint.right <= bug.left && hint.left > 400f)
        assertEquals("centred on the bug's height", (bug.top + bug.bottom) / 2f, (hint.top + hint.bottom) / 2f, 1.5f)
        rule.onNodeWithTag(SCORE_BUG_TAG, useUnmergedTree = true).assertContentDescriptionEquals("AWY 0–0 HME")
        input.value = ScoreBugFixtures.input(ScoreCorner.TOP_LEFT, placing = true)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        val left = bounds()
        assertTrue("hint is right of a left-corner bug", bounds(SCORE_BUG_HINT_TAG).left >= left.right)
    }

    @Test
    fun placeModeWithAFollowedGameShowsItsRealView() {
        repo.bug.value = ScoreBugFixtures.live
        input.value = ScoreBugFixtures.input(placing = true)
        render()
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithTag(SCORE_BUG_TAG, useUnmergedTree = true)
            .assertContentDescriptionEquals("Calgary 0, Edmonton 2, 10:22 left in the 1st") // the snapshot clock (owner ruling 6)
        rule.onNodeWithTag(SCORE_BUG_HINT_TAG, useUnmergedTree = true).assertExists()
    }

    @Test
    fun overlayGeometryMatchesTheDesignedBoxes() {
        // A-B2: the :core placer never imports PlayerUi, so the numbers it relies on are pinned here (§4.4 table).
        val g = PlayerUi.overlayGeometry()
        assertEquals(PlayerUi.BAR_T, g.infoBarTop)
        assertEquals(PlayerUi.QUICK_T, g.quickBarTop)
        assertEquals(804f + 16f, g.infoBarTop)
        assertEquals(952f, g.quickBarTop)
        assertEquals(app.thebox.core.sports.Box(1376f, 48f, 1872f, 928f), g.picker)
        assertEquals(app.thebox.core.sports.Box(48f, 48f, 668f, 1032f), g.channelList)
        assertTrue("the stats box starts at the stats' own corner", g.stats.l == PlayerUi.STATS_L && g.stats.t == PlayerUi.STATS_T)
        assertEquals(app.thebox.core.sports.Box(64f, 56f, 784f, 126f), g.groupCard)
        // The :core placement test's fixture is the same geometry (one set of numbers, A-B2).
        assertEquals(app.thebox.core.sports.OverlayGeometryFixture.DESIGN, g)
    }
}
