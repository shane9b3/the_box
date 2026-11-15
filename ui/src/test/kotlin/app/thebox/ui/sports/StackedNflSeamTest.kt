package app.thebox.ui.sports

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.SportsPrefs
import app.thebox.ui.guide.GuideDebugCounters
import app.thebox.ui.sports.StackedNflRunFixtures as F
import app.thebox.ui.theme.BoxDesignScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * docs/design/m6-nfl-stacked.md §9.2 `StackedNflSeamTest` (WP-4), through `ScoreBug(...)` with the production hooks:
 * NFL + STACKED composes the runner (its bounded layer, the drawn box at the right margin) and never `NflBug`; NFL + PILL
 * composes `NflBug` and never the Stacked NFL runner (so no Stacked NFL painter is built); a scoring takeover recomposes
 * nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class StackedNflSeamTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val repo = FakeScoreRepository(SportsPrefs(nhlStyle = NhlBugStyle.STACKED))

    private fun stackedNfl() = GuideDebugCounters.count(COMPOSITIONS_STACKED_NFL_BUG)
    private fun pill() = GuideDebugCounters.count(COMPOSITIONS_NFL_BUG)
    private fun hockey() = GuideDebugCounters.count(COMPOSITIONS_STACKED_BUG)

    private fun host() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            BoxDesignScale { ScoreBug(repo, ScoreBugFixtures.input(), ScoreBugFixtures.noGeometry, lowEnd = false) }
        }
        advance(1_000)
    }

    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) { val n = minOf(16L, left); rule.mainClock.advanceTimeBy(n); left -= n }
        rule.waitForIdle()
    }

    @Test
    fun nflAndStackedComposeTheRunnerAndNeverThePill() {
        repo.bug.value = F.view()
        val n0 = stackedNfl()
        val p0 = pill()
        val h0 = hockey()
        host()
        assertTrue("the NFL Stacked runner composed", stackedNfl() > n0)
        assertEquals("NflBug never composed", 0, pill() - p0)
        assertEquals("the hockey Stacked bug never composed", 0, hockey() - h0)
        rule.onNodeWithTag(STACKED_NFL_LAYER_TAG, useUnmergedTree = true).assertExists()
        val b = rule.onNodeWithTag(SCORE_BUG_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("the play box: mirrored to the right margin", 1824f, b.right, 1f)
        assertEquals(1491f, b.left, 1f)
        assertEquals(56f, b.top, 1f)

        val c0 = stackedNfl()
        repo.bug.value = F.touchdown()
        advance(5_000)
        assertEquals("one composition for the score snapshot, none during the takeover", 1, stackedNfl() - c0)
    }

    @Test
    fun nflAndPillComposeThePillAndNeverTheRunner() {
        repo.prefs.value = SportsPrefs(nhlStyle = NhlBugStyle.PILL)
        repo.bug.value = F.view()
        val n0 = stackedNfl()
        val p0 = pill()
        host()
        assertTrue(pill() > p0)
        assertEquals("the Stacked NFL runner (and its painters) never composed", 0, stackedNfl() - n0)
        rule.onNodeWithTag(STACKED_NFL_LAYER_TAG, useUnmergedTree = true).assertDoesNotExist()
    }
}
