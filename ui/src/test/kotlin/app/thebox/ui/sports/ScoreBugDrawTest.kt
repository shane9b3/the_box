package app.thebox.ui.sports

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.GameId
import app.thebox.core.sports.League
import app.thebox.core.sports.ScoreBugPlacement
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.Side
import app.thebox.core.sports.TeamColours
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import app.thebox.core.sports.SportsPrefs
import app.thebox.ui.full.StableScoreBug
import app.thebox.ui.guide.GuideDebugCounters
import app.thebox.ui.theme.BoxDesignScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * docs/design/m6-score-bug.md §4.7, §8 `ScoreBugDrawTest`, as amended by owner ruling 6 (no local countdown, fix pass
 * 2026-11-05): the bug recomposes, rebuilds its words and redraws **only** when a new snapshot (or state) becomes
 * visible; a minute without one does nothing at all; a host recomposition for an unrelated reason (a fresh but equal
 * `PlacementInput`, as `FullScreenHost` builds at the stats' 2 Hz, U-S2) never recomposes it; and its semantics carry the
 * snapshot's clock, not a ticking second (U-S7). The host is the real [StableScoreBug] wrapper `FullScreenHost` uses.
 * Time is the compose test clock, advanced a second at a time.
 *
 * Robolectric runs no draw pass (legacy or native graphics: checked in the fix pass), so draws are not counted here.
 * Instead a snapshot apply observer proves that nothing writes state while no snapshot arrives: a ticker read only in
 * a draw lambda (the removed clock layer) would show there. `DRAWS_SCORE_BUG` is the device-run counter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class ScoreBugDrawTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val repo = FakeScoreRepository(SportsPrefs())
    private var hostCompositions = 0
    private var unrelated by mutableIntStateOf(0)

    private fun render(lowEnd: Boolean) {
        rule.setContent {
            BoxDesignScale { Host(lowEnd) }
        }
        rule.waitForIdle()
    }

    /** Stands for `FullScreenHost`: it recomposes when [unrelated] changes and builds a fresh placement each time. */
    @Composable
    private fun Host(lowEnd: Boolean) {
        SideEffect { hostCompositions++ }
        @Suppress("UNUSED_VARIABLE") val read = unrelated
        StableScoreBug(repo, ScoreBugFixtures.input(), lowEnd)
    }

    private fun compositions() = GuideDebugCounters.count(COMPOSITIONS_SCORE_BUG)
    private fun frames() = GuideDebugCounters.count(FRAMES_SCORE_BUG)

    private fun desc(): String =
        rule.onNodeWithTag(SCORE_BUG_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()

    private fun start(lowEnd: Boolean) {
        repo.bug.value = ScoreBugFixtures.live.copy(clockSecs = 1200)
        rule.mainClock.autoAdvance = false
        render(lowEnd)
        rule.mainClock.advanceTimeBy(2000) // fade in, the first draws
        rule.waitForIdle()
    }

    private fun aMinuteWithoutASnapshotDoesNothing(lowEnd: Boolean) {
        start(lowEnd)
        val comp0 = compositions()
        val frames0 = frames()
        val desc0 = desc()
        // Any per-second ticker, even one read only in a draw lambda (the old clock layer), writes snapshot state.
        var applies = 0
        val handle = Snapshot.registerApplyObserver { _, _ -> applies++ }
        try {
            for (i in 1..60) rule.mainClock.advanceTimeBy(1000)
            rule.waitForIdle()
        } finally {
            handle.dispose()
        }
        assertEquals("snapshot state changes in a minute with no new snapshot (a ticker)", 0, applies)
        assertEquals("ScoreBug recompositions with no new snapshot", 0, compositions() - comp0)
        assertEquals("frame rebuilds with no new snapshot", 0, frames() - frames0)
        assertEquals("the clock in the semantics is the snapshot's and does not tick", desc0, desc())
        assertTrue(desc0, desc0.contains("20:00"))
    }

    @Test
    fun aMinuteWithoutASnapshotRecomposesRebuildsAndDrawsNothing() = aMinuteWithoutASnapshotDoesNothing(lowEnd = false)

    @Test
    fun theLowEndVariantIsJustAsStill() = aMinuteWithoutASnapshotDoesNothing(lowEnd = true)

    @Test
    fun eachNewSnapshotRedrawsOnceAndTheClockStepsToItsValue() {
        start(lowEnd = false)
        val comp0 = compositions()
        val frames0 = frames()
        for (i in 1..60) {
            rule.mainClock.advanceTimeBy(1000)
            // A snapshot every 20 s, each with the feed's own clock.
            if (i % 20 == 0) repo.bug.value = ScoreBugFixtures.live.copy(clockSecs = 1200 - i)
        }
        rule.mainClock.advanceTimeBy(1000) // the frame that shows the last one
        rule.waitForIdle()
        assertEquals("one recomposition per snapshot", 3, compositions() - comp0)
        assertEquals("one frame build per snapshot", 3, frames() - frames0)
        assertTrue(desc(), desc().contains("19:00")) // 1200 − 60 s, exactly as the last snapshot said
    }

    @Test
    fun anEqualReEmissionDoesNothing() {
        start(lowEnd = false)
        val comp0 = compositions()
        val frames0 = frames()
        repo.bug.value = ScoreBugFixtures.live.copy(clockSecs = 1200) // equal, a new instance
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        assertEquals(0, compositions() - comp0)
        assertEquals(0, frames() - frames0)
    }

    @Test
    fun aHostRecompositionForAnUnrelatedReasonNeverRecomposesTheBug() {
        start(lowEnd = false)
        val host0 = hostCompositions
        val comp0 = compositions()
        for (i in 1..10) {
            unrelated = i // e.g. the stats overlay republishing PlayerUiState at 2 Hz
            rule.mainClock.advanceTimeBy(500)
            rule.waitForIdle()
        }
        assertEquals("the stand-in host did recompose", 10, hostCompositions - host0)
        assertEquals("ScoreBug skipped every host recomposition (U-S2)", 0, compositions() - comp0)
    }

    // ------------------------------------------------------------------ the goal takeover (goal design §5.2, §5.3, §8.3)
    //
    // Robolectric runs no draw pass, so the draw path is observed through its state: `takeoverOn` and the one driver
    // `Animatable` are read only in draw (the probe hands them out), and the colour table, the painter's begin / bake and
    // `glowAllowed` are the commit-0 stubs in this package, so every one is injected through [GoalHooks].

    private val probe = GoalProbe()
    private var placement by mutableStateOf(ScoreBugFixtures.input())

    private fun goalHooks(context: CoroutineContext = EmptyCoroutineContext) = GoalHooks(
        colours = { TeamColours(0xFF112233.toInt(), 0xFF445566.toInt(), 0xFF778899.toInt(), 0xFFAABBCC.toInt()) },
        begin = { _, _, _, _, _, _, _ -> },
        bake = { null },
        glowAllowed = { _, _, _, _ -> true },
        probe = probe,
        context = context,
    )

    private fun startGoalHost(lowEnd: Boolean = false, context: CoroutineContext = EmptyCoroutineContext) {
        repo.bug.value = ScoreBugFixtures.view(clockSecs = 1200)
        rule.mainClock.autoAdvance = false
        val hooks = goalHooks(context)
        rule.setContent {
            BoxDesignScale {
                ScoreBugWith(repo, placement, ScoreBugFixtures.noGeometry, lowEnd, Modifier, ScoreBugPlacement::place, hooks)
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2000)
        rule.waitForIdle()
    }

    private fun advance(ms: Long, step: Long = 100) {
        var left = ms
        while (left > 0) { val n = minOf(step, left); rule.mainClock.advanceTimeBy(n); left -= n }
        rule.waitForIdle()
    }

    private fun on() = probe.takeoverOn!!.value
    private fun t() = probe.driver!!.value
    private fun live() = rule.onNodeWithTag(SCORE_BUG_LIVE_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()

    private fun goal(side: Side = Side.AWAY, seq: Int = 1, team: String = "CGY") =
        ScoreBugFixtures.goalView(team, side, seq = seq)

    @Test
    fun aGoalRunsOnceWithTwoRecompositionsAndNoneBetween() {
        startGoalHost()
        val comp0 = compositions()
        repo.bug.value = goal()
        advance(100)
        assertTrue("takeover on", on())
        assertEquals(1, probe.begins.size)
        assertEquals(Side.AWAY, probe.begins[0].side)
        assertEquals("CGY", probe.begins[0].abbrev)
        assertEquals("the tally is captured at the start", 2 to 1, probe.begins[0].away to probe.begins[0].home)
        val afterMark = compositions() - comp0
        var applyRecompositionsBase = compositions()
        advance(3000)
        assertTrue("the driver advanced (read only in draw): ${t()}", t() > 2000f && t() < 4500f)
        assertEquals("no recomposition while the takeover animates", applyRecompositionsBase, compositions())
        // The mark ends (the machine clears goalSide): the UI still owns the timeline.
        repo.bug.value = goal().copy(goalSide = null)
        advance(100)
        applyRecompositionsBase = compositions()
        advance(2000)
        assertTrue("takeover over", !on())
        assertEquals(1, afterMark)
        assertEquals("mark start and mark end", 2, applyRecompositionsBase - comp0)
        assertEquals(1, probe.begins.size)
    }

    @Test
    fun theLiveRegionGoesEmptyThenTheGoalStringThenEmpty() {
        startGoalHost()
        assertEquals("", live())
        repo.bug.value = goal()
        advance(100)
        assertTrue(live(), live().startsWith("Calgary goal."))
        repo.bug.value = goal().copy(goalSide = null)
        advance(100)
        assertEquals("", live())
    }

    @Test
    fun aGoalWhileHiddenIsNotReplayedWhenTheBugReturnsInsideTheMark() {
        startGoalHost()
        placement = ScoreBugFixtures.input().copy(screenOff = true)
        advance(1000)
        repo.bug.value = goal()
        advance(1000)
        placement = ScoreBugFixtures.input()
        advance(1000)
        assertTrue(probe.begins.isEmpty())
        assertTrue(!on())
    }

    @Test
    fun hidingMidTakeoverClearsToThePlainBugAndNeverResumes() {
        startGoalHost()
        repo.bug.value = goal()
        advance(1000)
        assertTrue(on())
        placement = ScoreBugFixtures.input().copy(screenOff = true)
        advance(100)
        assertTrue("cleared synchronously with the hide", !on())
        advance(1000) // the fade is done
        placement = ScoreBugFixtures.input()
        advance(1000)
        assertTrue("no frozen half-wipe, no late replay", !on())
        assertEquals(1, probe.begins.size)
    }

    @Test
    fun aFollowChangedMidTakeoverCancels() {
        startGoalHost()
        repo.bug.value = goal()
        advance(1000)
        assertTrue(on())
        repo.bug.value = ScoreBugFixtures.view(goalSeq = 0, gameId = GameId(League.NHL, "2025020999"))
        advance(100)
        assertTrue(!on())
        advance(5000)
        assertEquals(1, probe.begins.size)
    }

    @Test
    fun placeModeMidTakeoverCancelsAndIsNotReplayed() {
        startGoalHost()
        repo.bug.value = goal()
        advance(1000)
        placement = ScoreBugFixtures.input(placing = true)
        advance(100)
        assertTrue(!on())
        placement = ScoreBugFixtures.input()
        advance(1000)
        assertTrue(!on())
        assertEquals(1, probe.begins.size)
    }

    @Test
    fun aGoalInPlaceModeDoesNotPlayLater() {
        startGoalHost()
        placement = ScoreBugFixtures.input(placing = true)
        advance(500)
        repo.bug.value = goal()
        advance(500)
        placement = ScoreBugFixtures.input()
        advance(1000)
        assertTrue(probe.begins.isEmpty())
    }

    @Test
    fun aSizeChangeMidTakeoverCancels() {
        startGoalHost()
        repo.bug.value = goal()
        advance(1000)
        assertTrue(on())
        placement = ScoreBugFixtures.input(size = ScoreBugSize.LARGE)
        advance(100)
        assertTrue(!on())
        assertEquals(1, probe.begins.size)
    }

    @Test
    fun aSeqBumpWithoutASideDoesNothing() {
        startGoalHost()
        repo.bug.value = ScoreBugFixtures.view(goalSeq = 1, goalSide = null)
        advance(500)
        assertTrue(probe.begins.isEmpty())
        assertTrue(!on())
    }

    @Test
    fun aSecondGoalMidTakeoverRestartsWithTheNewSideAndTally() {
        startGoalHost()
        repo.bug.value = goal(Side.AWAY, seq = 1)
        advance(1500)
        assertTrue(t() > 1000f)
        repo.bug.value = ScoreBugFixtures.goalView("EDM", Side.HOME, opponent = "CGY", away = 2, home = 3, seq = 2)
        advance(100)
        assertEquals(2, probe.begins.size)
        assertEquals(Side.HOME, probe.begins[1].side)
        assertEquals("EDM", probe.begins[1].abbrev)
        assertEquals(2 to 3, probe.begins[1].away to probe.begins[1].home)
        assertTrue("restarted at t = 0: ${t()}", t() < 600f)
        assertTrue(on())
    }

    @Test
    fun theFirstSnapshotOfAFollowNeverPlays() {
        repo.bug.value = ScoreBugFixtures.goalView("CGY", Side.AWAY, seq = 7)
        rule.mainClock.autoAdvance = false
        val hooks = goalHooks()
        rule.setContent {
            BoxDesignScale { ScoreBugWith(repo, placement, ScoreBugFixtures.noGeometry, false, Modifier, ScoreBugPlacement::place, hooks) }
        }
        advance(2000)
        assertTrue(probe.begins.isEmpty())
    }

    @Test
    fun withTheAnimatorScaleAtTwoThePopStillCompletesAfterTheMarkEnds() {
        startGoalHost(context = FixedScale(2f))
        repo.bug.value = goal()
        advance(100)
        assertTrue(!probe.begins[0].still)
        repo.bug.value = goal().copy(goalSide = null) // the mark ends at 4.5 s on the machine's clock
        advance(5000)
        assertTrue("still running at scale 2 (the UI owns the timeline)", on())
        advance(5000)
        assertTrue(!on())
    }

    @Test
    fun withTheAnimatorScaleAtZeroTheStillPathSnapsThroughThreeDraws() {
        startGoalHost(context = FixedScale(0f))
        repo.bug.value = goal()
        advance(100)
        assertTrue(probe.begins[0].still)
        assertTrue(on())
        assertEquals("frame A", 0f, t(), 0f)
        advance(3600)
        assertEquals("frame B", 3600f, t(), 0f)
        assertTrue(on())
        advance(1000)
        assertTrue("then the plain bug", !on())
    }

    @Test
    fun lowEndModeUsesTheStillPathToo() {
        startGoalHost(lowEnd = true)
        repo.bug.value = goal()
        advance(100)
        assertTrue(probe.begins[0].still)
    }

    private class FixedScale(override val scaleFactor: Float) : MotionDurationScale
}
