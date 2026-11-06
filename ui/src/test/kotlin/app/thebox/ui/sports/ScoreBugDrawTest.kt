package app.thebox.ui.sports

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.SportsPrefs
import app.thebox.ui.guide.GuideDebugCounters
import app.thebox.ui.theme.BoxDesignScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * docs/design/m6-score-bug.md §4.7, §8 `ScoreBugDrawTest`: 60 s with three polls recompose neither the host nor
 * `ScoreBug`, and the clock layer redraws once per second; the low-end variant; a stopped clock draws nothing. Time is the
 * compose test clock (`mono` reads it), advanced a second at a time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class ScoreBugDrawTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val repo = FakeScoreRepository(SportsPrefs())
    private var hostCompositions = 0

    private fun render(lowEnd: Boolean) {
        rule.setContent {
            BoxDesignScale { Host(lowEnd) }
        }
        rule.waitForIdle()
    }

    /** Stands for `FullScreenHost`: it must not recompose when the bug's flows emit. */
    @Composable
    private fun Host(lowEnd: Boolean) {
        SideEffect { hostCompositions++ }
        ScoreBug(
            repo, ScoreBugFixtures.input(), ScoreBugFixtures.noGeometry, lowEnd,
            mono = { rule.mainClock.currentTime },
        )
    }

    // The displayed second changes once per tick and each change invalidates exactly the one layer that reads it; Robolectric
    // does not run the hardware draw pass, so the invalidations are what is counted here (DRAWS_SCORE_CLOCK is for the device run).
    private fun clockDraws() = GuideDebugCounters.count(TICKS_SCORE_CLOCK)
    private fun compositions() = GuideDebugCounters.count(COMPOSITIONS_SCORE_BUG)

    private fun sixtySecondsWithThreePolls(lowEnd: Boolean) {
        repo.bug.value = ScoreBugFixtures.live
        repo.clock.value = ScoreBugFixtures.anchor(secs = 1200, atMono = 0L)
        rule.mainClock.autoAdvance = false
        render(lowEnd)
        rule.mainClock.advanceTimeBy(2000) // fade in, the first draws
        rule.waitForIdle()
        val host0 = hostCompositions
        val comp0 = compositions()
        val draws0 = clockDraws()
        val start = rule.mainClock.currentTime
        for (i in 1..60) {
            rule.mainClock.advanceTimeBy(1000)
            // A poll every 20 s: a new anchor for the same game (the displayed second is unchanged or resynced by 1).
            if (i % 20 == 0) repo.clock.value = ScoreBugFixtures.anchor(secs = 1200 - i, atMono = start + i * 1000L)
        }
        rule.waitForIdle()
        assertEquals("FullScreenHost stand-in recompositions", 0, hostCompositions - host0)
        assertEquals("ScoreBug recompositions per tick and per poll", 0, compositions() - comp0)
        val draws = clockDraws() - draws0
        assertTrue("clock-layer draws in 60 s: $draws", draws in 60..62)
    }

    @Test
    fun sixtySecondsWithThreePollsDrawsTheClockLayerSixtyTimesAndRecomposesNothing() = sixtySecondsWithThreePolls(lowEnd = false)

    @Test
    fun theLowEndVariantTicksTheSameWay() = sixtySecondsWithThreePolls(lowEnd = true)

    @Test
    fun aStoppedClockDrawsNothing() {
        repo.bug.value = ScoreBugFixtures.live
        repo.clock.value = ScoreBugFixtures.anchor(secs = 900, ticking = false)
        rule.mainClock.autoAdvance = false
        render(lowEnd = false)
        rule.mainClock.advanceTimeBy(2000)
        rule.waitForIdle()
        val draws0 = clockDraws()
        val comp0 = compositions()
        rule.mainClock.advanceTimeBy(30_000)
        rule.waitForIdle()
        assertEquals("a stopped clock", 0, clockDraws() - draws0)
        assertEquals(0, compositions() - comp0)
    }

    @Test
    fun theClockStopsAtItsExtrapolationCap() {
        repo.bug.value = ScoreBugFixtures.live
        repo.clock.value = ScoreBugFixtures.anchor(secs = 900, atMono = 0L, stopAt = 40_000L)
        rule.mainClock.autoAdvance = false
        render(lowEnd = false)
        rule.mainClock.advanceTimeBy(45_000)
        rule.waitForIdle()
        val draws0 = clockDraws()
        rule.mainClock.advanceTimeBy(20_000)
        rule.waitForIdle()
        assertEquals("past stopAtMono nothing ticks", 0, clockDraws() - draws0)
    }

    @Test
    fun clockMathCountsDownFloorsAtZeroAndFreezesAtTheCap() {
        val a = ScoreBugFixtures.anchor(secs = 5, atMono = 1_000L, stopAt = 4_500L)
        assertEquals(5, ClockMath.secsAt(a, 1_000L))
        assertEquals(5, ClockMath.secsAt(a, 1_999L))
        assertEquals(4, ClockMath.secsAt(a, 2_000L))
        assertEquals(2, ClockMath.secsAt(a, 4_500L))
        assertEquals("frozen at the cap", 2, ClockMath.secsAt(a, 60_000L))
        assertEquals(2_000L, ClockMath.nextTickAt(a, 1_000L))
        assertEquals(3_000L, ClockMath.nextTickAt(a, 2_000L))
        assertEquals(4_000L, ClockMath.nextTickAt(a, 3_999L))
        assertNull("the next whole second is past the cap", ClockMath.nextTickAt(a, 4_100L))
        val free = ScoreBugFixtures.anchor(secs = 2, atMono = 0L)
        assertEquals(0, ClockMath.secsAt(free, 10_000L))
        assertNull("at 0:00 there is nothing left to tick", ClockMath.nextTickAt(free, 2_000L))
        val still = ScoreBugFixtures.anchor(secs = 900, ticking = false)
        assertEquals(900, ClockMath.secsAt(still, 99_000L))
        assertNull(ClockMath.nextTickAt(still, 0L))
    }
}
