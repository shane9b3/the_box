package app.thebox.ui.sports

import androidx.activity.ComponentActivity
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.GoalFrame
import app.thebox.core.sports.GoalSpec
import app.thebox.core.sports.GoalTimeline
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.ScoreBugPlacement
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.SportsPrefs
import app.thebox.ui.guide.GuideDebugCounters
import app.thebox.ui.theme.BoxDesignScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext

/**
 * docs/design/m6-score-bug-stacked-theme.md §10.2 `StackedSeamTest` (Stacked WP-3): `ScoreBug(...)`'s style seam with the
 * production hooks. STACKED never composes the Pill (so never builds its `GoalPainter`, `ScoreBugPainter` or
 * `PowerPlayPainter`, which only `ScoreBugWith` constructs) and PILL never composes Stacked (so never builds a Stacked
 * painter); an NFL view with STACKED prefs takes `NflBug`; a style flip swaps the bug with no fade.
 *
 * Also the `:ui` side of the S9 pin WP-1 could not write: the Pill's `GoalSampler.Real` and its takeover length
 * (`ScoreBug.kt`'s private `TAKEOVER_TOTAL_MS`) are still `GoalSpec.CLASSIC`'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class StackedSeamTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val repo = FakeScoreRepository(SportsPrefs(nhlStyle = NhlBugStyle.STACKED))

    private fun pill() = GuideDebugCounters.count(COMPOSITIONS_SCORE_BUG)
    private fun stacked() = GuideDebugCounters.count(COMPOSITIONS_STACKED_BUG)
    private fun nfl() = GuideDebugCounters.count(COMPOSITIONS_NFL_BUG)

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

    private fun bounds(): Rect = rule.onNodeWithTag(SCORE_BUG_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test
    fun stackedNeverComposesThePill() {
        repo.bug.value = StackedRunFixtures.view()
        val p0 = pill()
        val s0 = stacked()
        host()
        repo.bug.value = ScoreBugFixtures.goalMark(Side.AWAY)
        advance(5_000)
        assertTrue("the Stacked bug composed", stacked() - s0 >= 2)
        assertEquals("ScoreBugWith (GoalPainter, ScoreBugPainter, PowerPlayPainter) never composed", 0, pill() - p0)
        assertEquals("the drawn box is the Stacked one (333 × 108 in play)", Rect(1432f, 56f, 1765f, 164f), bounds())
    }

    @Test
    fun thePillNeverComposesStacked() {
        repo.prefs.value = SportsPrefs(nhlStyle = NhlBugStyle.PILL)
        repo.bug.value = StackedRunFixtures.view()
        val p0 = pill()
        val s0 = stacked()
        host()
        repo.bug.value = ScoreBugFixtures.goalMark(Side.AWAY)
        advance(5_000)
        assertTrue(pill() - p0 >= 2)
        assertEquals("StackedBug (StackedPainter, StackedGoalPainter, NineSlice) never composed", 0, stacked() - s0)
        assertEquals(Rect(1420f, 56f, 1824f, 126f), bounds())
    }

    @Test
    fun anNflViewWithStackedPrefsTakesTheNflBug() {
        repo.bug.value = SportsFixtures.nflView()
        val n0 = nfl()
        val s0 = stacked()
        val p0 = pill()
        host()
        assertTrue("the NFL bug composed", nfl() > n0)
        assertEquals(0, stacked() - s0)
        assertEquals(0, pill() - p0)
    }

    /** §2.2: a style change swaps the bug at once: the old one leaves without fading out, the new one fades in. */
    @Test
    fun aStyleFlipSwapsWithNoFade() {
        repo.bug.value = StackedRunFixtures.view()
        host()
        assertEquals(Rect(1432f, 56f, 1765f, 164f), bounds())
        repo.prefs.value = SportsPrefs(nhlStyle = NhlBugStyle.PILL)
        Snapshot.sendApplyNotifications()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(16)
        rule.waitForIdle()
        assertEquals("one bug, at once", 1, rule.onAllNodesWithTag(SCORE_BUG_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals("the Pill, the next frame", Rect(1420f, 56f, 1824f, 126f), bounds())
        rule.onNodeWithTag(STACKED_LAYER_TAG, useUnmergedTree = true).assertDoesNotExist()
        repo.prefs.value = SportsPrefs(nhlStyle = NhlBugStyle.STACKED)
        Snapshot.sendApplyNotifications()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(16)
        rule.waitForIdle()
        assertEquals("and back", Rect(1432f, 56f, 1765f, 164f), bounds())
    }

    // ------------------------------------------------------------------ S9: the Pill stays on GoalSpec.CLASSIC

    /** `GoalSampler.Real` samples exactly `GoalTimeline.sample(CLASSIC, …)` / `sampleStill(CLASSIC, …)`, bit for bit. */
    @Test
    fun thePillsSamplerIsTheClassicSpec() {
        val fields = GoalFrame::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible = true }
        val a = GoalFrame()
        val b = GoalFrame()
        var t = -1f
        while (t <= 4_700f) {
            for (still in listOf(false, true)) {
                GoalSampler.Real.sample(t, still, a)
                if (still) GoalTimeline.sampleStill(GoalSpec.CLASSIC, t, b) else GoalTimeline.sample(GoalSpec.CLASSIC, t, b)
                for (f in fields) {
                    val x = f.get(a)
                    val y = f.get(b)
                    val same = when (x) {
                        is Float -> x.toBits() == (y as Float).toBits()
                        is FloatArray -> x.map { it.toBits() } == (y as FloatArray).map { it.toBits() }
                        is BooleanArray -> x.contentEquals(y as BooleanArray)
                        else -> x == y
                    }
                    assertTrue("t=$t still=$still: ${f.name} $x vs $y", same)
                }
            }
            t += 0.5f
        }
    }

    /**
     * `TAKEOVER_TOTAL_MS` (private in `ScoreBug.kt`, so read through the Pill's own driver: reflection-free) is CLASSIC's
     * end, 4 500, not STACKED's 4 800: a Pill takeover's driver ends there.
     */
    @Test
    fun thePillsTakeoverLengthIsTheClassicEnd() {
        assertEquals(4_500f, GoalSpec.CLASSIC.endMs)
        assertEquals((K.GOAL_TAKEOVER_MS + K.GOAL_POP_MS).toFloat(), GoalSpec.CLASSIC.endMs)
        val probe = GoalProbe()
        repo.bug.value = ScoreBugFixtures.live
        rule.mainClock.autoAdvance = false
        val hooks = GoalHooks(probe = probe, background = EmptyCoroutineContext)
        rule.setContent {
            BoxDesignScale {
                ScoreBugWith(repo, ScoreBugFixtures.input(), ScoreBugFixtures.noGeometry, false, Modifier, ScoreBugPlacement::place, hooks)
            }
        }
        advance(2_000)
        repo.bug.value = ScoreBugFixtures.goalMark(Side.AWAY)
        advance(7_000)
        assertEquals("the Pill's takeover ran", 1, probe.begins.size)
        assertEquals("TAKEOVER_TOTAL_MS == GoalSpec.CLASSIC.endMs", GoalSpec.CLASSIC.endMs, probe.driver!!.value, 0.001f)
        assertTrue(GoalSpec.STACKED.endMs > GoalSpec.CLASSIC.endMs)
    }
}
