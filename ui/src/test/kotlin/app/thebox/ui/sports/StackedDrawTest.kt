package app.thebox.ui.sports

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.thebox.core.sports.BarKind
import app.thebox.core.sports.CentreText
import app.thebox.core.sports.ClockSlot
import app.thebox.core.sports.Demand
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameClock
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.GoalTimeline
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PlacementInput
import app.thebox.core.sports.PowerPlay
import app.thebox.core.sports.PowerPlayKind
import app.thebox.core.sports.ScoreBugMachine
import app.thebox.core.sports.ScoreBugPlacement
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.SportsPrefs
import app.thebox.core.sports.StackedTrack
import app.thebox.core.sports.StackedTransition
import app.thebox.ui.guide.GuideDebugCounters
import app.thebox.ui.theme.BoxDesignScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random

/**
 * docs/design/m6-score-bug-stacked-theme.md §10.2 `StackedDrawTest` (Stacked WP-3; compose test clock): the recomposition
 * budget, the seconds-only update, each S1 rule 1–7 as its own case, the pin (width and bar height), the PP GOAL words at
 * `LEAD_COVERED_MS`, the text swap vs the change, the still path, a style flip mid-takeover, the subtitle-moved bottom
 * corner, and the §8 states end to end through the real `ScoreBugMachine` → `StackedTargets` → runner → painter.
 *
 * Robolectric runs no draw pass, so the draw is observed through [StackedProbe.args]: the arguments the bug's one draw
 * would hand `StackedPainter.drawFrame`, filled from the same drivers. Everything is the production code (the real
 * timeline, colours, painters) except the glow bake (a 1 × 1 stand-in) and the background context (the test thread).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class StackedDrawTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val repo = FakeScoreRepository(SportsPrefs(nhlStyle = NhlBugStyle.STACKED))
    private val probe = StackedProbe()
    private var placement by mutableStateOf(StackedRunFixtures.input())

    // ------------------------------------------------------------------ host and helpers

    private fun start(first: ScoreBugView? = StackedRunFixtures.view(), context: CoroutineContext = EmptyCoroutineContext) {
        repo.bug.value = first
        rule.mainClock.autoAdvance = false
        val hooks = StackedRunFixtures.hooks(probe, context)
        rule.setContent {
            BoxDesignScale { StackedBug(repo, placement, ScoreBugFixtures.noGeometry, Modifier, ScoreBugPlacement::place, hooks) }
        }
        rule.waitForIdle()
        advance(2000)
    }

    private fun advance(ms: Long, step: Long = 16) {
        var left = ms
        while (left > 0) { val n = minOf(step, left); rule.mainClock.advanceTimeBy(n); left -= n }
        rule.waitForIdle()
    }

    private fun show(v: ScoreBugView?) {
        repo.bug.value = v
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(16)
        rule.waitForIdle()
    }

    private fun on() = probe.takeoverOn!!.value
    private fun t() = probe.driver!!.value
    private fun pinned() = probe.pinned!!()
    private fun args() = probe.args!!()
    private fun f() = args().f!!
    private fun body() = args().body!!
    private fun words() = probe.barWords!!()
    private fun moves() = probe.transitions.filter { it.kind != StackedTransition.STILL }
    private fun movesOf(track: StackedTrack) = moves().filter { it.track == track }

    private fun advanceTakeoverTo(ms: Float) {
        var guard = 0
        while (t() < ms) {
            rule.mainClock.advanceTimeBy(16)
            rule.waitForIdle()
            check(++guard < 2_000) { "the takeover driver stopped at ${t()}" }
        }
    }

    private fun applyPlacement(p: PlacementInput) {
        placement = p
        Snapshot.sendApplyNotifications()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(16)
        rule.waitForIdle()
    }

    private fun compositions() = GuideDebugCounters.count(COMPOSITIONS_STACKED_BUG)
    private fun frames() = GuideDebugCounters.count(FRAMES_STACKED_BUG)
    private fun desc() = rule.onNodeWithTag(SCORE_BUG_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()

    private val ppHome = StackedRunFixtures.pp(Side.HOME)
    private fun live(away: Int = 0, home: Int = 2, pp: PowerPlay? = null, clock: Int = 622, seq: Int = 0, side: Side? = null, goalPp: Boolean = false) =
        StackedRunFixtures.view(away = away, home = home, pp = pp, clockSecs = clock, goalSeq = seq, goalSide = side, goalPp = goalPp)

    /** At rest: SOG [sog], clock [clock], bar [bar] open (by the sampled widths). */
    private fun assertLayout(msg: String, sog: Boolean, clock: Boolean, bar: Boolean) {
        val f = f()
        assertEquals("$msg: SOG width", if (sog) 1f else 0f, f.sogWidth, 1e-4f)
        assertEquals("$msg: clock width", if (clock) 1f else 0f, f.clockWidth, 1e-4f)
        assertEquals("$msg: bar height", if (bar) K.STACKED_BAR_H else 0f, f.barH, 1e-3f)
    }

    // ------------------------------------------------------------------ the budget (SB §4.7, ruling 6)

    @Test
    fun oneCompositionPerSnapshotAndNoneBetween() {
        start(live(pp = ppHome))
        val comp0 = compositions()
        val frames0 = frames()
        var applies = 0
        val handle = Snapshot.registerApplyObserver { _, _ -> applies++ }
        try {
            for (i in 1..60) rule.mainClock.advanceTimeBy(1000)
            rule.waitForIdle()
        } finally {
            handle.dispose()
        }
        assertEquals("snapshot state changes in a minute with no snapshot", 0, applies)
        assertEquals(0, compositions() - comp0)
        assertEquals(0, frames() - frames0)
        for (i in 1..3) {
            repo.bug.value = live(pp = ppHome.copy(secondsRemaining = 79 - 20 * i), clock = 622 - 20 * i)
            advance(20_000, 1000)
        }
        assertEquals("one recomposition per snapshot", 3, compositions() - comp0)
        assertEquals("one body per snapshot", 3, frames() - frames0)
    }

    @Test
    fun aTakeoverRecomposesNothing() {
        start()
        show(live(away = 1, seq = 1, side = Side.AWAY))
        val comp0 = compositions()
        advanceTakeoverTo(4_700f)
        assertEquals("the takeover is draw-only", 0, compositions() - comp0)
    }

    /** PP §3.1 B1, ruling 13: a seconds-only update redraws (new words, new clock) and kicks no track. */
    @Test
    fun aSecondsOnlyUpdateRedrawsWithoutAKick() {
        start(live(pp = ppHome))
        assertEquals("EDM POWER PLAY 1:19", words())
        val n = probe.transitions.size
        show(live(pp = ppHome.copy(secondsRemaining = 60), clock = 600))
        advance(1000)
        assertEquals("no track was kicked", n, probe.transitions.size)
        assertEquals("the bar's seconds updated in place", "EDM POWER PLAY 1:00", words())
        assertEquals(600, body().clockSecs)
        assertLayout("still at rest", sog = true, clock = true, bar = true)
    }

    // ------------------------------------------------------------------ S1 rule 1: the held previous body

    @Test
    fun rule1TheOldScoreIsHeldUntilTheLeadCovers() {
        start(live(clock = 622))
        show(live(away = 1, clock = 600, seq = 1, side = Side.AWAY))
        assertEquals("the goal view's composition still draws the pre-goal score", "0", body().awayScore)
        assertEquals(622, body().clockSecs)
        advanceTakeoverTo(GoalTimeline.LEAD_COVERED_MS - 20f)
        assertTrue(on())
        assertEquals("before the lead covers", "0", body().awayScore)
        assertEquals(622, body().clockSecs)
        advanceTakeoverTo(GoalTimeline.LEAD_COVERED_MS)
        assertEquals("covered: the new score under the takeover", "1", body().awayScore)
        assertEquals(600, body().clockSecs)
    }

    // ------------------------------------------------------------------ S1 rule 2: the first view snaps

    @Test
    fun rule2TheFirstViewOfAFollowSnaps() {
        start(live(pp = ppHome))
        assertTrue("nothing animates in on follow: ${moves().map { it.kind }}", moves().isEmpty())
        assertLayout("at the power play's rest from the start", sog = true, clock = true, bar = true)
        assertEquals("EDM POWER PLAY 1:19", words())
    }

    @Test
    fun rule2AWaitingFirstViewThenTheFirstFreshOneBothSnap() {
        start(StackedRunFixtures.intermission(dim = true, bottom = CentreText.Delayed))
        assertEquals("DELAYED", words())
        show(StackedRunFixtures.intermission())
        advance(100)
        assertTrue("the first fresh view snaps too: ${moves().map { "${it.track} ${it.kind}" }}", moves().isEmpty())
        assertEquals("1ST INTERMISSION", words())
        assertLayout("intermission", sog = true, clock = false, bar = true)
    }

    // ------------------------------------------------------------------ S1 rule 3: hide freezes, reappearance snaps

    @Test
    fun rule3AHiddenBugFreezesThenSnapsOnReappearance() {
        start(live())
        applyPlacement(StackedRunFixtures.input().copy(screenOff = true)) // hidden
        show(live(pp = ppHome))
        advance(1000)
        assertTrue("nothing moves while hidden", moves().isEmpty())
        applyPlacement(StackedRunFixtures.input())
        advance(100)
        assertTrue("the reappearance snaps: ${moves().map { it.kind }}", moves().isEmpty())
        assertLayout("at the new view's rest", sog = true, clock = true, bar = true)
    }

    /**
     * (Fix pass 2026-11-11, review C S1) The follow ends (Stop following, or the 5-minute stale hide: the source's view
     * goes null) on a power play: through the 150-ms fade the box keeps its layout, its bar words and the game's colours;
     * the runner resets only once the fade is done.
     */
    @Test
    fun rule3AFollowThatEndsFadesWithItsLayoutAndColoursThenResets() {
        start(live(pp = ppHome))
        val colours = args().colours
        assertEquals(app.thebox.core.sports.NhlStackedColours.pair("CGY", "EDM"), colours)
        val n = probe.transitions.size
        show(null) // 16 ms into the fade
        assertLayout("mid-fade: the power play's box", sog = true, clock = true, bar = true)
        assertEquals("mid-fade: the bar's words", "EDM POWER PLAY 1:19", words())
        assertTrue("mid-fade: the game's colours, not the fallback", args().colours === colours)
        assertEquals("CGY", body().awayAbbrev)
        assertEquals("nothing was kicked", n, probe.transitions.size)
        advance(80) // still fading
        assertLayout("later in the fade", sog = true, clock = true, bar = true)
        assertTrue(args().colours === colours)
        advance(1_000) // the fade is done: the runner resets
        assertEquals("reset after the fade", "", words())
        assertEquals("parked closed", 0f, f().sogWidth, 1e-4f)
    }

    // ------------------------------------------------------------------ S1 rule 4: place mode parks

    @Test
    fun rule4PlaceModeParksAndShowsTheSample() {
        start(null)
        applyPlacement(StackedRunFixtures.input(placing = true))
        advance(500)
        assertEquals("AWY", body().awayAbbrev)
        assertEquals("HME", body().homeAbbrev)
        assertEquals(ClockSlot.Clock, body().slot)
        assertEquals("", body().periodText)
        assertEquals("the anchored play-width box", K.STACKED_ROWS_W + K.STACKED_CLOCK_W, f().boxW, 1e-3f)
        assertTrue(args().placing)
        assertTrue(moves().isEmpty())
    }

    @Test
    fun rule4PlaceModeWithAFollowRestsOnTheViewAndNothingAnimates() {
        start(live())
        applyPlacement(StackedRunFixtures.input(placing = true))
        show(live(pp = ppHome))
        show(StackedRunFixtures.intermission())
        advance(500)
        assertTrue("parked: snaps only (${moves().map { it.kind }})", moves().isEmpty())
        assertLayout("the shown view's rest", sog = true, clock = false, bar = true)
        applyPlacement(StackedRunFixtures.input()) // Done
        advance(500)
        assertTrue("leaving place mode snaps", moves().isEmpty())
    }

    // ------------------------------------------------------------------ S1 rule 5: back-to-back goals

    /**
     * EDM's power-play goal (ruling 15), then a CGY goal mid-takeover: the takeover restarts and the pin holds. Once CGY's
     * lead covers the box the old "EDM POWER PLAY GOAL" words fade out (no power play now), but the bar keeps its height
     * and the pinned box its size and glow until the 3 600 release, where the bar closes (fix pass 2026-11-11, review C
     * S2: rule 5 against B4's fixed box).
     */
    @Test
    fun rule5ABackToBackGoalHidesAnEarlierPowerPlayGoalsWordsAndClosesTheBarAtTheRelease() {
        start(live(pp = ppHome))
        show(live(home = 3, seq = 1, side = Side.HOME, goalPp = true)) // the PP is over in the goal snapshot
        advanceTakeoverTo(GoalTimeline.LEAD_COVERED_MS + 50f)
        assertEquals("EDM POWER PLAY GOAL", words())
        advanceTakeoverTo(1_000f)
        show(live(away = 1, home = 3, seq = 2, side = Side.AWAY))
        assertEquals("the takeover restarted", 2, probe.begins.size)
        assertTrue("the pin continues", pinned())
        assertTrue(probe.releases.isEmpty())
        assertEquals("held until the new lead covers", "EDM POWER PLAY GOAL", words())
        val glow = args().glow
        assertNotNull(glow)
        advanceTakeoverTo(GoalTimeline.LEAD_COVERED_MS + 50f)
        val swap = movesOf(StackedTrack.BAR).last()
        assertEquals("the words fade out: a text swap to nothing", StackedTransition.SWAP, swap.kind)
        assertEquals(BarKind.PP_GOAL, swap.barKind)
        var t = GoalTimeline.LEAD_COVERED_MS + 50f
        while (t < K.GOAL_TAKEOVER_MS - 50f) {
            advanceTakeoverTo(t)
            assertEquals("the bar keeps its height at $t", K.STACKED_BAR_H, f().barH, 1e-3f)
            assertEquals("the pinned box keeps its width at $t", K.STACKED_ROWS_W + K.STACKED_SOG_W + K.STACKED_CLOCK_W, f().boxW, 1e-3f)
            assertTrue("the glow keeps its masks at $t", args().glow === glow)
            t += 250f
        }
        assertEquals("no words on the bar", "", words())
        assertTrue("nothing closed during the pin", movesOf(StackedTrack.BAR).none { it.kind == StackedTransition.CLOSE })
        assertTrue("still pinned until 3 600", pinned())
        advanceTakeoverTo(K.GOAL_TAKEOVER_MS.toFloat() + 32f)
        assertFalse(pinned())
        val close = movesOf(StackedTrack.BAR).last()
        assertEquals("the bar closes at the release", StackedTransition.CLOSE, close.kind)
        assertEquals(K.GOAL_TAKEOVER_MS.toFloat(), probe.releases.last().driverMs, 40f)
        advance(1_000)
        assertLayout("after the release", sog = false, clock = true, bar = false)
    }

    /** A 5-on-3 power-play goal leaves a 5-on-4; a CGY goal follows: the old words cut to EDM's current power play. */
    @Test
    fun rule5ABackToBackGoalCutsToTheCurrentPowerPlaysWords() {
        start(live(pp = ppHome.copy(kind = PowerPlayKind.TWO_MAN)))
        show(live(home = 3, pp = ppHome.copy(secondsRemaining = 60), seq = 1, side = Side.HOME, goalPp = true))
        advanceTakeoverTo(GoalTimeline.LEAD_COVERED_MS + 50f)
        assertEquals("EDM POWER PLAY GOAL", words())
        advanceTakeoverTo(800f)
        show(live(away = 1, home = 3, pp = ppHome.copy(secondsRemaining = 55), seq = 2, side = Side.AWAY))
        val n = moves().size
        advanceTakeoverTo(GoalTimeline.LEAD_COVERED_MS + 50f)
        assertEquals("a plain cut to the current words", "EDM POWER PLAY 0:55", words())
        assertEquals("no slide", n, moves().size)
    }

    // ------------------------------------------------------------------ S1 rule 6: a goal and a layout change at once

    @Test
    fun rule6AGoalAndAPowerPlayStartArePinnedUntilTheRelease() {
        start(live())
        show(live(away = 1, pp = StackedRunFixtures.pp(Side.HOME), seq = 1, side = Side.AWAY))
        advanceTakeoverTo(2_000f)
        assertLayout("pinned at the pre-goal layout", sog = false, clock = true, bar = false)
        assertTrue(moves().isEmpty())
        assertEquals("the takeover is laid out on the pre-goal width", K.STACKED_ROWS_W + K.STACKED_CLOCK_W, probe.begins.single().boxW, 1e-3f)
        advanceTakeoverTo(K.GOAL_TAKEOVER_MS.toFloat() + 32f)
        assertFalse(pinned())
        assertEquals(StackedTransition.OPEN, movesOf(StackedTrack.SOG).single().kind)
        assertEquals(StackedTransition.OPEN, movesOf(StackedTrack.BAR).single().kind)
        assertEquals(K.GOAL_TAKEOVER_MS.toFloat(), probe.releases.single().driverMs, 40f)
    }

    @Test
    fun rule6AGoalAndTheIntermissionKeepTheClockColumnUntilTheRelease() {
        start(live(clock = 1))
        val goalInt = StackedRunFixtures.intermission().copy(
            away = StackedRunFixtures.intermission().away.copy(score = 1), goalSeq = 1, goalSide = Side.AWAY,
        )
        show(goalInt)
        advanceTakeoverTo(3_000f)
        assertLayout("pinned", sog = false, clock = true, bar = false)
        assertTrue("the folding clock column keeps its text over a Blank body", body().slot == ClockSlot.Clock)
        advanceTakeoverTo(K.GOAL_TAKEOVER_MS.toFloat() + 32f)
        assertEquals(StackedTransition.CLOSE, movesOf(StackedTrack.CLOCK).single().kind)
        assertEquals(StackedTransition.OPEN, movesOf(StackedTrack.SOG).single().kind)
        assertEquals(StackedTransition.OPEN, movesOf(StackedTrack.BAR).single().kind)
        advance(2_000)
        assertLayout("the intermission", sog = true, clock = false, bar = true)
    }

    @Test
    fun rule6AGoalAndThePowerPlaysEndCloseTheBarOnlyAtTheRelease() {
        start(live(pp = ppHome))
        show(live(away = 1, seq = 1, side = Side.AWAY)) // a shorthanded goal against the PP: the PP is over
        advanceTakeoverTo(3_000f)
        assertLayout("pinned", sog = true, clock = true, bar = true)
        assertEquals("the PP team's words stay", "EDM POWER PLAY 1:19", words())
        advanceTakeoverTo(K.GOAL_TAKEOVER_MS.toFloat() + 32f)
        assertEquals(StackedTransition.CLOSE, movesOf(StackedTrack.BAR).single().kind)
        assertEquals(StackedTransition.CLOSE, movesOf(StackedTrack.SOG).single().kind)
    }

    /** B4 / N5: a goal while the PP's columns and bar are mid-open: width and bar height snap open; the stripes go on. */
    @Test
    fun thePinSnapsTheWidthAndTheBarHeight() {
        start(live())
        show(live(pp = ppHome))
        advance(300)
        val mid = f()
        assertTrue("mid-open: SOG ${mid.sogWidth}, bar ${mid.barH}", mid.sogWidth in 0.05f..0.95f && mid.barH < K.STACKED_BAR_H)
        show(live(home = 3, pp = ppHome, seq = 1, side = Side.HOME))
        val p = f()
        assertEquals("the SOG width snaps to the end of its open", 1f, p.sogWidth, 1e-4f)
        assertEquals("and the bar height", K.STACKED_BAR_H, p.barH, 1e-3f)
        assertTrue("the stripes finish on their own clock (${p.stripeAX})", p.stripeAX < 0f)
        assertEquals("the takeover's pinned width", K.STACKED_ROWS_W + K.STACKED_SOG_W + K.STACKED_CLOCK_W, probe.begins.single().boxW, 1e-3f)
        // 1 design px = 1 device px here (a 1920-px window under BoxDesignScale).
        assertEquals("one glow bake for the pinned box", K.STACKED_ROWS_W + K.STACKED_SOG_W + K.STACKED_CLOCK_W,
            probe.glowBakes.last().first, 0.5f)
    }

    /** §6 (B4): the exact-box glow is baked at every layout rest, cached per box (LRU 2), and ready when a goal comes. */
    @Test
    fun theGlowIsPrewarmedAtLayoutRestAndCachedPerBox() {
        start(live())
        val run = probe.run!!
        assertEquals("play: 333 × 108", listOf(333 to 108), run.glowCacheKeys())
        show(live(pp = ppHome))
        advance(2_000)
        assertEquals("a power play: 391.5 × 141, newest first", listOf(392 to 141, 333 to 108), run.glowCacheKeys())
        show(StackedRunFixtures.intermission())
        advance(2_000)
        assertEquals("LRU 2: the play box went", listOf(302 to 141, 392 to 141), run.glowCacheKeys())
        val bakes = probe.glowBakes.size
        show(StackedRunFixtures.intermission().let { it.copy(away = it.away.copy(score = 1), goalSeq = 1, goalSide = Side.AWAY) })
        advanceTakeoverTo(100f)
        assertNotNull("the goal found its masks ready", args().glow)
        assertEquals("no bake at the goal", bakes, probe.glowBakes.size)
    }

    // ------------------------------------------------------------------ S1 rule 7: cancel

    @Test
    fun rule7HidingCancelsAndAGoalWhileHiddenIsNeverReplayed() {
        start(live())
        show(live(away = 1, seq = 1, side = Side.AWAY))
        advanceTakeoverTo(1_000f)
        applyPlacement(StackedRunFixtures.input().copy(screenOff = true))
        assertFalse("cancelled", on())
        assertTrue("released with a snap", probe.releases.single().snap)
        show(live(away = 2, seq = 2, side = Side.AWAY)) // while hidden
        applyPlacement(StackedRunFixtures.input())
        advance(5_000)
        assertEquals("never replayed", 1, probe.begins.size)
        assertEquals("2", body().awayScore)
    }

    @Test
    fun rule7ASizeChangeOrANewGameCancels() {
        start(live())
        show(live(away = 1, seq = 1, side = Side.AWAY))
        advanceTakeoverTo(500f)
        applyPlacement(StackedRunFixtures.input(size = ScoreBugSize.LARGE))
        assertFalse(on())
        assertFalse(pinned())
        show(live(away = 2, seq = 2, side = Side.AWAY))
        advanceTakeoverTo(500f)
        show(live(away = 0, home = 0).copy(gameId = app.thebox.core.sports.GameId(app.thebox.core.sports.League.NHL, "other")))
        assertFalse("a new game cancels", on())
        assertFalse(pinned())
    }

    // ------------------------------------------------------------------ ruling 15, the swap and the change

    @Test
    fun aPowerPlayGoalCutsTheWordsAtLeadCoveredAndClosesAtTheRelease() {
        start(live(pp = ppHome))
        show(live(home = 3, seq = 1, side = Side.HOME, goalPp = true))
        advanceTakeoverTo(GoalTimeline.LEAD_COVERED_MS - 20f)
        assertEquals("EDM POWER PLAY 1:19", words())
        advanceTakeoverTo(GoalTimeline.LEAD_COVERED_MS)
        assertEquals("EDM POWER PLAY GOAL", words())
        assertTrue("a cut, no slide", moves().isEmpty())
        advanceTakeoverTo(K.GOAL_TAKEOVER_MS.toFloat() + 32f)
        val close = movesOf(StackedTrack.BAR).single()
        assertEquals(StackedTransition.CLOSE, close.kind)
        assertEquals("EDM POWER PLAY GOAL", close.words)
    }

    @Test
    fun aTextSwapForTheSameLookAndAChangeForAnother() {
        start(live(pp = ppHome))
        show(live(pp = ppHome.copy(kind = PowerPlayKind.TWO_MAN)))
        assertEquals(StackedTransition.SWAP, movesOf(StackedTrack.BAR).last().kind)
        advance(1_500)
        assertEquals("EDM 5-ON-3 1:19", words())
        show(live(pp = StackedRunFixtures.pp(Side.AWAY)))
        assertEquals("another side: a change", StackedTransition.CHANGE, movesOf(StackedTrack.BAR).last().kind)
        advance(1_500)
        assertEquals("CGY POWER PLAY 1:19", words())
        show(StackedRunFixtures.intermission())
        assertEquals("a team bar to a break: a change", StackedTransition.CHANGE, movesOf(StackedTrack.BAR).last().kind)
        advance(1_500)
        show(StackedRunFixtures.intermission(dim = true, bottom = CentreText.NoUpdate))
        assertEquals("a break to NO UPDATE: a swap", StackedTransition.SWAP, movesOf(StackedTrack.BAR).last().kind)
        advance(1_500)
        assertEquals("NO UPDATE", words())
        assertTrue(args().bar!!.dim)
    }

    // ------------------------------------------------------------------ the still version (§7.3)

    @Test
    fun theStillPathSnapsEveryTransitionAndShowsFramesAThenB() {
        start(live(), FixedScale(0f))
        show(live(pp = ppHome))
        advance(50)
        assertTrue("every layout transition snaps: ${moves().map { it.kind }}", moves().isEmpty())
        assertLayout("at rest at once", sog = true, clock = true, bar = true)
        show(live(home = 3, seq = 1, side = Side.HOME, goalPp = true))
        advance(50)
        assertTrue(probe.begins.single().still)
        assertEquals("frame A covers from its first draw: the new score", "3", body().homeScore)
        assertEquals("PP GOAL cut at frame A", "EDM POWER PLAY GOAL", words())
        assertTrue(args().still)
        advance(K.GOAL_TAKEOVER_MS + 50)
        assertFalse("released at frame B", pinned())
        assertLayout("the bar snapped closed at the release", sog = false, clock = true, bar = false)
    }

    // ------------------------------------------------------------------ S11: a style flip mid-takeover, the bar direction

    /** The flip swaps the composables (as `ScoreBug`'s `key(league, style)` does): the takeover leaves with Stacked, and
     * the Pill's first view sets its lastSeq, so the goal is not replayed. */
    @Test
    fun aStyleFlipMidTakeoverCancelsItAndThePillDoesNotReplayTheGoal() {
        var style by mutableStateOf(NhlBugStyle.STACKED)
        val goalProbe = GoalProbe()
        repo.bug.value = live()
        rule.mainClock.autoAdvance = false
        val hooks = StackedRunFixtures.hooks(probe)
        val pillHooks = GoalHooks(probe = goalProbe, background = EmptyCoroutineContext)
        rule.setContent {
            BoxDesignScale {
                key(style) {
                    if (style == NhlBugStyle.STACKED) {
                        StackedBug(repo, placement, ScoreBugFixtures.noGeometry, Modifier, ScoreBugPlacement::place, hooks)
                    } else {
                        ScoreBugWith(repo, ScoreBugFixtures.input(), ScoreBugFixtures.noGeometry, false, Modifier, ScoreBugPlacement::place, pillHooks)
                    }
                }
            }
        }
        advance(2000)
        show(live(away = 1, seq = 1, side = Side.AWAY))
        advanceTakeoverTo(1_000f)
        style = NhlBugStyle.PILL
        Snapshot.sendApplyNotifications()
        advance(100)
        val driverAfterFlip = t()
        advance(6_000, 100)
        assertEquals("the Stacked takeover left with its composable", driverAfterFlip, t(), 1f)
        assertTrue("well short of its end", driverAfterFlip < 1_500f)
        assertTrue("the Pill does not replay the goal", goalProbe.begins.isEmpty())
    }

    @Test
    fun aSubtitleMovedBottomCornerDropsItsBarDownward() {
        start(live(pp = ppHome))
        applyPlacement(StackedRunFixtures.input(ScoreCorner.BOTTOM_RIGHT))
        assertTrue("a bottom corner's bar opens above the box", args().up)
        applyPlacement(StackedRunFixtures.input(ScoreCorner.BOTTOM_RIGHT, textTrackOn = true))
        assertFalse("moved to the top by subtitles: the bar drops below", args().up)
    }

    // ------------------------------------------------------------------ the live region and the shots phrase

    @Test
    fun theDescriptionCarriesTheShotsWhileTheColumnShowsThem() {
        start(live())
        assertFalse(desc(), desc().contains("Shots on goal"))
        show(live(pp = ppHome))
        assertTrue(desc(), desc().endsWith("Shots on goal: Calgary 12, Edmonton 20"))
    }

    // ------------------------------------------------------------------ §8 end to end: machine → targets → runner → painter

    private class Rig {
        var now = 0L
        private val wall0 = 1_793_923_200_000L + 30 * 60_000L
        val m = ScoreBugMachine({ now }, { wall0 + now }, 1_500L, Random(7))
        init { m.prefs(0) }
        fun follow(g: GameSnapshot) { m.follow(SportsFixtures.followedOf(g)); m.demand(Demand.SEEN) }
        fun ok(s: GameSnapshot) { m.fetched(Fetch.Ok(s, now, wall0 + now, 0)); m.step() }
        fun at(t: Long) { now = t; m.step() }
    }

    private fun game(
        away: Int = 0, home: Int = 2, secs: Int = 622, running: Boolean = true, period: Int = 2,
        kind: PeriodKind = PeriodKind.REG, phase: GamePhase = GamePhase.LIVE, inInt: Boolean = false,
        pp: PowerPlay? = null, awaySog: Int? = 12, homeSog: Int? = 20, finalKind: PeriodKind? = null,
    ) = SportsFixtures.game(
        phase = phase,
        away = SportsFixtures.team("CGY", "Flames", away).copy(sog = awaySog),
        home = SportsFixtures.team("EDM", "Oilers", home).copy(sog = homeSog),
        period = period, periodKind = kind, clock = GameClock(secs, running, inInt), powerPlay = pp, finalKind = finalKind,
    )

    /** Each §8 row the machine can produce, at rest: the layout, the bar's words, the period and slot texts. */
    @Test
    fun everyStateEndToEndThroughTheMachine() {
        val r = Rig()
        r.follow(game())
        r.ok(game())
        start(r.m.view())
        fun push(s: GameSnapshot? = null, at: Long? = null) {
            at?.let { r.at(it) }
            s?.let { r.ok(it) }
            repo.bug.value = r.m.view()
            advance(2_000, 50)
        }
        fun check(name: String, sog: Boolean, clock: Boolean, bar: String?, period: String? = null, slot: String? = null, ppSide: Side? = null) {
            assertLayout(name, sog, clock, bar != null)
            if (bar != null) assertEquals("$name: bar", bar, words())
            if (period != null) assertEquals("$name: period", period, body().periodText)
            if (slot != null) assertEquals("$name: slot", slot, if (body().slot == ClockSlot.Clock) "clock" else body().slotText)
            val f = f()
            assertEquals("$name: away stripe", if (ppSide == Side.AWAY) 1f else 0f, f.stripeAway, 1e-4f)
            assertEquals("$name: home stripe", if (ppSide == Side.HOME) 1f else 0f, f.stripeHome, 1e-4f)
        }
        check("even strength", sog = false, clock = true, bar = null, period = "2ND", slot = "clock")
        push(game(secs = 600, pp = PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 97)), at = 20_000)
        check("power play", sog = true, clock = true, bar = "EDM POWER PLAY 1:37", ppSide = Side.HOME)
        // Review C N13: after the first view the power play animates open (rule 2 snaps only the first).
        assertTrue("the SOG column animated open", movesOf(StackedTrack.SOG).any { it.kind == StackedTransition.OPEN })
        assertTrue("the bar animated open", movesOf(StackedTrack.BAR).any { it.kind == StackedTransition.OPEN })
        push(game(secs = 580, pp = PowerPlay(Side.HOME, PowerPlayKind.TWO_MAN, 77)), at = 40_000)
        check("5-on-3", sog = true, clock = true, bar = "EDM 5-ON-3 1:17", ppSide = Side.HOME)
        push(game(secs = 0, running = false), at = 60_000)
        check("end of period", sog = false, clock = true, bar = null, period = "2ND", slot = "END")
        push(game(secs = 0, running = false, phase = GamePhase.INTERMISSION, inInt = true, pp = PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 72)), at = 80_000)
        check("a PP carried into the intermission", sog = true, clock = true, bar = "EDM POWER PLAY 1:12", period = "2ND", slot = "INT", ppSide = Side.HOME)
        push(at = 300_000) // no answer for minutes: stale in the PP-in-INT
        check("stale in a PP carried into INT", sog = true, clock = true, bar = null, period = "2ND", slot = "No update")
        push(game(secs = 0, running = false, phase = GamePhase.INTERMISSION, inInt = true), at = 310_000)
        check("intermission", sog = true, clock = false, bar = "2ND INTERMISSION")
        push(at = 600_000)
        check("stale in the intermission", sog = true, clock = false, bar = "NO UPDATE")
        assertTrue(args().bar!!.dim)
        push(game(away = 2, home = 2, secs = 300, period = 4, kind = PeriodKind.OT, pp = PowerPlay(Side.AWAY, PowerPlayKind.ONE_MAN, 48)), at = 610_000)
        advance(6_000, 100) // the tying goal's takeover
        check("4-on-3 in OT", sog = true, clock = true, bar = "CGY POWER PLAY 0:48", period = "OT", ppSide = Side.AWAY)
        push(game(away = 2, home = 2, secs = 0, running = false, period = 5, kind = PeriodKind.SO), at = 630_000)
        check("shootout", sog = true, clock = false, bar = "SHOOTOUT")
        push(game(away = 2, home = 3, secs = 0, running = false, period = 5, kind = PeriodKind.SO, phase = GamePhase.FINAL, finalKind = PeriodKind.SO), at = 650_000)
        advance(6_000, 100) // the shootout winner's takeover (ruling 11)
        check("final", sog = true, clock = false, bar = "FINAL/SO")
        push(game(away = 2, home = 3, awaySog = null, phase = GamePhase.FINAL, finalKind = PeriodKind.SO, period = 5, kind = PeriodKind.SO, secs = 0, running = false), at = 670_000)
        check("missing SOG never opens the column", sog = false, clock = false, bar = "FINAL/SO")
        assertNotNull(args().shadow)
        assertNull("no takeover at rest", args().g)
    }

    private class FixedScale(override val scaleFactor: Float) : MotionDurationScale
}
