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
import app.thebox.core.sports.PowerPlay
import app.thebox.core.sports.PowerPlayKind
import app.thebox.core.sports.PowerPlayTimeline
import app.thebox.core.sports.PpFrame
import app.thebox.core.sports.PpLabel
import app.thebox.core.sports.PpTrack
import app.thebox.core.sports.PpTransition
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
import org.junit.Assert.assertFalse
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
    // `Animatable` are read only in draw (the probe hands them out). These tests are about the trigger, so the colour
    // table, the painter's begin / bake and `glowAllowed` are stand-ins injected through [GoalHooks] (the bake counts its
    // calls), and the painter setup runs on the test thread. `GoalSeamTest` drives the real hooks and renders the draw.

    private val probe = GoalProbe()
    private var placement by mutableStateOf(ScoreBugFixtures.input())

    /** Calls of the `bake` hook (S2 of the code review: never in low-end mode). */
    private var bakes = 0

    /** Power play: whether the placement allows the tab (`tabAllowed`), the label bakes asked for, and the halo bakes. */
    private var tabAllowedFlag by mutableStateOf(true)
    private val labelBakes = mutableListOf<Pair<String, String>>()
    private var halos = 0
    private val sizeBakes = mutableListOf<Boolean>()

    private fun goalHooks(context: CoroutineContext = EmptyCoroutineContext) = GoalHooks(
        colours = { TeamColours(0xFF112233.toInt(), 0xFF445566.toInt(), 0xFF778899.toInt(), 0xFFAABBCC.toInt()) },
        begin = { _, _, _, _, _, _, _ -> },
        bake = { bakes++; null },
        glowAllowed = { _, _, _, _ -> true },
        probe = probe,
        ppSampler = PpTestSamplers.Fake,
        tabAllowed = { _, _, _ -> tabAllowedFlag },
        bakeSize = { _, withShadow -> sizeBakes += withShadow },
        bakeHalo = { _, _, _ -> halos++ },
        bakeLabels = { _, away, home, _ -> labelBakes += away to home },
        context = context,
        background = EmptyCoroutineContext,
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

    /** §8.2: in low-end mode `bakeGlow` is never called; otherwise once per size. */
    @Test
    fun lowEndModeNeverBakesTheGlow() {
        startGoalHost(lowEnd = true)
        repo.bug.value = goal()
        advance(5000)
        assertEquals("a goal ran", 1, probe.begins.size)
        assertEquals("bake calls in low-end mode", 0, bakes)
    }

    @Test
    fun theGlowIsBakedOncePerSizeOutsideLowEnd() {
        startGoalHost()
        repo.bug.value = goal()
        advance(5000)
        assertEquals("one bake for the one size", 1, bakes)
        placement = ScoreBugFixtures.input(size = ScoreBugSize.LARGE)
        // With the clock idle (no frame running), a plain test-thread write to [placement] is not applied until the
        // global snapshot sends its notifications; the clock advance alone does not.
        Snapshot.sendApplyNotifications()
        advance(1000)
        assertEquals("and one more for Large", 2, bakes)
    }

    /** N3 of the code review: nothing of the takeover is built (no bake) until a game is followed. */
    @Test
    fun nothingIsBakedUntilAGameIsFollowed() {
        repo.bug.value = null
        placement = ScoreBugFixtures.input(placing = true) // the sample bug is drawn, but no game is followed
        rule.mainClock.autoAdvance = false
        val hooks = goalHooks()
        rule.setContent {
            BoxDesignScale { ScoreBugWith(repo, placement, ScoreBugFixtures.noGeometry, false, Modifier, ScoreBugPlacement::place, hooks) }
        }
        advance(2000)
        assertEquals("no bake for the sample bug", 0, bakes)
        repo.bug.value = ScoreBugFixtures.view(clockSecs = 1200)
        advance(500)
        assertEquals("baked once the game is followed", 1, bakes)
    }

    /** N10 of the code review: the follow stopped (view → null) mid-takeover cancels; a re-follow never replays. */
    @Test
    fun aFollowStoppedMidTakeoverCancelsAndIsNotReplayed() {
        startGoalHost()
        repo.bug.value = goal()
        advance(1000)
        assertTrue(on())
        repo.bug.value = null
        advance(100)
        assertTrue("cleared synchronously with the unfollow", !on())
        advance(1000)
        // The same game again, still carrying the mark: a first snapshot, so nothing plays.
        repo.bug.value = goal()
        advance(5000)
        assertTrue(!on())
        assertEquals(1, probe.begins.size)
    }


    // ------------------------------------------------------------------ the power-play tab (power play design §3.1, §5, §7, §9.3)
    //
    // `PowerPlayTimeline.sample` (WP-2) is a stub in this tree, so the runner is driven through its seams: a stand-in
    // sampler, `tabAllowed`, and the label / halo bakes. The probe shows each transition a track plays, the content it was
    // started with, the pin and its releases, and what a draw at this instant would show.

    private fun ppEdm(secs: Int? = 79, kind: PowerPlayKind = PowerPlayKind.ONE_MAN, side: Side = Side.HOME) =
        ScoreBugFixtures.ppView(
            if (side == Side.HOME) "EDM" else "CGY", side, kind, secs, opponent = if (side == Side.HOME) "CGY" else "EDM",
        )

    private fun tabRecs() = probe.pp.transitions.filter { it.track == PpTrack.TAB }
    private fun pillRecs() = probe.pp.transitions.filter { it.track == PpTrack.PILL }
    private fun tabDriver() = probe.pp.tabDriver!!.value
    private fun pinned() = probe.pp.pinned!!()
    private fun drawn() = probe.pp.drawnContent!!()
    private fun frame() = probe.pp.frame!!()

    private fun setAllowed(v: Boolean) {
        tabAllowedFlag = v
        Snapshot.sendApplyNotifications()
    }

    private fun applyPlacement(p: app.thebox.core.sports.PlacementInput) {
        placement = p
        Snapshot.sendApplyNotifications()
    }

    /** Advances the test clock a frame at a time until the takeover driver reaches [ms]. */
    private fun advanceTakeoverTo(ms: Float) {
        var guard = 0
        while (t() < ms) {
            rule.mainClock.advanceTimeBy(16)
            rule.waitForIdle()
            check(++guard < 1_000) { "the takeover driver stopped at ${t()}" }
        }
    }

    /** Followed, the tab open for EDM (home) at 5-on-4, settled. */
    private fun openEdmTab(lowEnd: Boolean = false, context: CoroutineContext = EmptyCoroutineContext) {
        startGoalHost(lowEnd, context)
        repo.bug.value = ppEdm()
        advance(2500)
    }

    @Test
    fun aPowerPlayStartIsOneRecompositionAndNoneDuringTheReveal() {
        startGoalHost()
        val comp0 = compositions()
        repo.bug.value = ppEdm()
        advance(100)
        assertEquals("one recomposition for the new snapshot", 1, compositions() - comp0)
        assertEquals(listOf(PpTransition.OPEN), tabRecs().map { it.kind })
        assertEquals(listOf(PpTransition.OPEN), pillRecs().map { it.kind })
        advance(2500)
        assertEquals("none while the tab slides, the shine sweeps and the label pulses", 1, compositions() - comp0)
        assertEquals("the tab reached rest", 1900f, tabDriver(), 0f)
    }

    /** B1: a seconds update, a toast and an unrelated placement change, mid-open, never restart or freeze the open. */
    @Test
    fun recompositionsDuringTheOpenNeverFreezeTheTab() {
        startGoalHost()
        repo.bug.value = ppEdm()
        advance(700)
        val mid = tabDriver()
        assertTrue("700 ms into the open: $mid", mid in 600f..800f)
        repo.bug.value = ppEdm(secs = 59) // a seconds update
        advance(100)
        applyPlacement(ScoreBugFixtures.input().copy(toast = true)) // a toast (top right is unaffected)
        advance(100)
        applyPlacement(ScoreBugFixtures.input().copy(toast = true, reminder = true))
        advance(100)
        assertTrue("the driver kept running: $mid → ${tabDriver()}", tabDriver() > mid + 250f)
        advance(1500)
        assertEquals("the tab still reaches rest at 1 900", 1900f, tabDriver(), 0f)
        assertEquals("the open was never re-started", 1, tabRecs().size)
        assertEquals(1, pillRecs().size)
        assertEquals("the seconds shown are the latest", 59, probe.pp.tabContent!!().secs)
        assertEquals(PpLabel.ONE_MAN, probe.pp.tabContent!!().label)
    }

    @Test
    fun aSecondsUpdateDoesNotRestartTheOpen() {
        startGoalHost()
        repo.bug.value = ppEdm()
        advance(700)
        val before = tabDriver()
        repo.bug.value = ppEdm(secs = 58)
        advance(100)
        assertTrue("the driver was not reset: $before → ${tabDriver()}", tabDriver() >= before + 90f)
        assertEquals(1, tabRecs().size)
        assertEquals(58, probe.pp.tabContent!!().secs)
    }

    @Test
    fun aSecondsUpdateAtRestDoesNotKickAtAll() {
        openEdmTab()
        repo.bug.value = ppEdm(secs = 40)
        advance(1000)
        assertEquals(1, tabRecs().size)
        assertEquals(1, pillRecs().size)
        assertEquals(40, probe.pp.tabContent!!().secs)
    }

    @Test
    fun aSideChangeClosesThenOpensEachWithItsOwnContent() {
        openEdmTab()
        repo.bug.value = ppEdm(side = Side.AWAY, secs = 100) // CGY now has the man advantage
        advance(100)
        assertEquals(listOf(PpTransition.OPEN, PpTransition.CLOSE), tabRecs().map { it.kind })
        assertEquals("the close slides out EDM's words", Side.HOME, tabRecs()[1].side)
        assertEquals(Side.HOME, probe.pp.tabContent!!().side)
        advance(700)
        assertEquals(listOf(PpTransition.OPEN, PpTransition.CLOSE, PpTransition.OPEN), tabRecs().map { it.kind })
        assertEquals("the open is CGY's", Side.AWAY, tabRecs()[2].side)
        assertEquals(100, tabRecs()[2].secs)
        assertEquals(listOf(PpTransition.OPEN, PpTransition.CLOSE, PpTransition.OPEN), pillRecs().map { it.kind })
        assertEquals(listOf(Side.HOME, Side.HOME, Side.AWAY), pillRecs().map { it.side })
    }

    @Test
    fun aKindChangePulsesTheLabelOnly() {
        openEdmTab()
        repo.bug.value = ppEdm(kind = PowerPlayKind.TWO_MAN, secs = 40)
        advance(100)
        assertEquals(listOf(PpTransition.OPEN, PpTransition.PULSE), tabRecs().map { it.kind })
        assertEquals(PpLabel.TWO_MAN, tabRecs()[1].label)
        assertEquals("the pill track has nothing to do", 1, pillRecs().size)
    }

    @Test
    fun tabAllowedFalseClosesTheTabAndTheTintStays() {
        openEdmTab()
        setAllowed(false)
        advance(100)
        assertEquals(listOf(PpTransition.OPEN, PpTransition.CLOSE), tabRecs().map { it.kind })
        assertEquals("the pill track stays (S3)", listOf(PpTransition.OPEN), pillRecs().map { it.kind })
        setAllowed(true)
        advance(100)
        assertEquals(listOf(PpTransition.OPEN, PpTransition.CLOSE, PpTransition.OPEN), tabRecs().map { it.kind })
    }

    @Test
    fun aTabThatIsNeverAllowedNeverOpensButTheTintDoes() {
        startGoalHost()
        setAllowed(false)
        repo.bug.value = ppEdm()
        advance(2500)
        assertTrue(tabRecs().isEmpty())
        assertEquals(listOf(PpTransition.OPEN), pillRecs().map { it.kind })
    }

    @Test
    fun aFollowThatStartsOnAPowerPlaySnapsOpen() {
        repo.bug.value = ppEdm()
        rule.mainClock.autoAdvance = false
        val hooks = goalHooks()
        rule.setContent {
            BoxDesignScale { ScoreBugWith(repo, placement, ScoreBugFixtures.noGeometry, false, Modifier, ScoreBugPlacement::place, hooks) }
        }
        advance(2500)
        assertEquals(listOf(PpTransition.STILL), tabRecs().map { it.kind })
        assertTrue(tabRecs()[0].to)
        assertEquals(listOf(PpTransition.STILL), pillRecs().map { it.kind })
    }

    @Test
    fun hiddenThenShownSnapsTheTabBack() {
        openEdmTab()
        assertFalse("the tab is drawn at rest", frame().tabClosed)
        applyPlacement(ScoreBugFixtures.input().copy(screenOff = true))
        advance(100)
        assertTrue("parked at once", frame().tabClosed && frame().pillClosed)
        advance(1000)
        applyPlacement(ScoreBugFixtures.input())
        advance(100)
        assertEquals(listOf(PpTransition.OPEN, PpTransition.STILL), tabRecs().map { it.kind })
        assertTrue(tabRecs()[1].to)
        assertEquals(listOf(PpTransition.OPEN, PpTransition.STILL), pillRecs().map { it.kind })
    }

    @Test
    fun placeModeShowsNoTabAndLeavingItSnaps() {
        openEdmTab()
        applyPlacement(ScoreBugFixtures.input(placing = true))
        advance(100)
        assertTrue(frame().tabClosed)
        applyPlacement(ScoreBugFixtures.input())
        advance(100)
        assertEquals(listOf(PpTransition.OPEN, PpTransition.STILL), tabRecs().map { it.kind })
    }

    @Test
    fun aSizeChangeClosesTheTabUntilItsLabelsAreBakedThenSnapsItOpen() {
        openEdmTab()
        assertEquals("labels baked once for the first painter", listOf("CGY" to "EDM"), labelBakes)
        repo.bug.value = ppEdm(secs = 40) // same teams: no new bake
        advance(100)
        assertEquals(1, labelBakes.size)
        applyPlacement(ScoreBugFixtures.input(size = ScoreBugSize.LARGE))
        advance(1000)
        assertEquals("one more bake for Large", 2, labelBakes.size)
        assertEquals(listOf(PpTransition.OPEN, PpTransition.STILL, PpTransition.STILL), tabRecs().map { it.kind })
        assertEquals(listOf(false, true), tabRecs().drop(1).map { it.to })
    }

    // ---- the pin and the release (§5)

    @Test
    fun aPowerPlayGoalPinsTheTabAndReleasesAt3600() {
        openEdmTab()
        repo.bug.value = ScoreBugFixtures.ppGoalView("EDM", Side.HOME, opponent = "CGY")
        advance(100)
        assertTrue(on())
        assertTrue("pinned from the goal view on", pinned())
        assertEquals("the spoiler hold: the words it had", PpLabel.ONE_MAN, drawn().label)
        advanceTakeoverTo(300f)
        assertEquals("then PP GOAL, a plain swap", PpLabel.PP_GOAL, drawn().label)
        advanceTakeoverTo(3_570f)
        assertTrue("still pinned at 3 570", pinned())
        assertTrue(probe.pp.releases.isEmpty())
        assertEquals("the tab is still open (no close yet)", 1, tabRecs().size)
        advanceTakeoverTo(3_700f)
        assertFalse(pinned())
        assertEquals("one release", 1, probe.pp.releases.size)
        val r = probe.pp.releases[0]
        assertFalse(r.snap)
        assertTrue("released at the driver's 3 600: ${r.driverMs}", r.driverMs in 3_600f..3_640f)
        // The power play ended in the goal snapshot: the tab closes, sliding PP GOAL out.
        assertEquals(listOf(PpTransition.OPEN, PpTransition.CLOSE), tabRecs().map { it.kind })
        assertEquals(PpLabel.PP_GOAL, tabRecs()[1].label)
    }

    @Test
    fun aFiveOnThreeGoalCutsBackToTheCurrentWordsWithNoPulse() {
        startGoalHost()
        repo.bug.value = ppEdm(kind = PowerPlayKind.TWO_MAN, secs = 40)
        advance(2500)
        val after = PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 45)
        repo.bug.value = ScoreBugFixtures.ppGoalView("EDM", Side.HOME, after = after, opponent = "CGY")
        advanceTakeoverTo(300f)
        assertEquals(PpLabel.PP_GOAL, drawn().label)
        advanceTakeoverTo(3_700f)
        assertFalse(pinned())
        assertEquals("no close, no pulse, no slide", 1, tabRecs().size)
        assertEquals(PpLabel.ONE_MAN, probe.pp.tabContent!!().label)
        assertEquals(45, probe.pp.tabContent!!().secs)
    }

    @Test
    fun aShorthandedGoalGetsNoPpGoalAndTheOpponentsTabStaysWithItsSecondsUpdating() {
        openEdmTab()
        val sh = ScoreBugFixtures.goalView("CGY", Side.AWAY, opponent = "EDM", away = 2, home = 2)
            .copy(powerPlay = PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 40)) // goalPp stays false
        repo.bug.value = sh
        advanceTakeoverTo(300f)
        assertEquals(PpLabel.ONE_MAN, drawn().label)
        assertEquals(Side.HOME, drawn().side)
        assertEquals("the seconds update once the lead covers the pill", 40, drawn().secs)
        advanceTakeoverTo(3_700f)
        assertEquals("the tab never moved", 1, tabRecs().size)
        assertEquals(40, probe.pp.tabContent!!().secs)
    }

    @Test
    fun aNewGoalMidPinKeepsPpGoalAndDoesNotRelease() {
        openEdmTab()
        repo.bug.value = ScoreBugFixtures.ppGoalView("EDM", Side.HOME, opponent = "CGY")
        advanceTakeoverTo(1_000f)
        assertEquals(PpLabel.PP_GOAL, drawn().label)
        // CGY scores at even strength mid-takeover: the takeover restarts, the pin continues.
        repo.bug.value = ScoreBugFixtures.goalView("CGY", Side.AWAY, opponent = "EDM", away = 2, home = 2, seq = 2)
        advance(100)
        assertEquals("restarted", 2, probe.begins.size)
        assertTrue(t() < 600f)
        assertTrue("no release on a restart", probe.pp.releases.isEmpty())
        assertTrue(pinned())
        assertEquals("held PP GOAL through the restart", PpLabel.PP_GOAL, drawn().label)
        advanceTakeoverTo(400f)
        assertEquals("and after the lead covers the pill again", PpLabel.PP_GOAL, drawn().label)
        advanceTakeoverTo(3_700f)
        assertEquals("one release, at the second run's 3 600", 1, probe.pp.releases.size)
        assertFalse(pinned())
        assertEquals("the close slides PP GOAL out", PpLabel.PP_GOAL, tabRecs().last().label)
        assertEquals(PpTransition.CLOSE, tabRecs().last().kind)
    }

    @Test
    fun anEvenStrengthGoalWhosePowerPlayStartsInTheGoalSnapshotOpensAt3600() {
        startGoalHost()
        repo.bug.value = ScoreBugFixtures.goalView("CGY", Side.AWAY, opponent = "EDM", away = 1, home = 2)
            .copy(powerPlay = PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 118))
        advanceTakeoverTo(3_000f)
        assertTrue("the tab stays closed through the takeover", tabRecs().isEmpty())
        advanceTakeoverTo(3_700f)
        assertEquals(listOf(PpTransition.OPEN), tabRecs().map { it.kind })
    }

    @Test
    fun aGoalWhenTheTabWasNotAllowedGetsNoPpGoalAndTheTabStaysClosed() {
        startGoalHost()
        setAllowed(false)
        repo.bug.value = ppEdm()
        advance(2500)
        assertTrue(tabRecs().isEmpty())
        repo.bug.value = ScoreBugFixtures.ppGoalView("EDM", Side.HOME, opponent = "CGY")
        advanceTakeoverTo(500f)
        assertTrue(frame().tabClosed)
        assertEquals("no PP GOAL for a tab that was not shown", PpLabel.ONE_MAN, drawn().label)
        advanceTakeoverTo(3_700f)
        assertTrue(tabRecs().isEmpty())
    }

    @Test
    fun anOverlayOpeningMidTakeoverKeepsTheTabUntil3600() {
        openEdmTab()
        repo.bug.value = ScoreBugFixtures.ppGoalView("EDM", Side.HOME, opponent = "CGY", after = PowerPlay(Side.HOME, PowerPlayKind.ONE_MAN, 45))
        advanceTakeoverTo(500f)
        setAllowed(false) // an overlay opens
        advanceTakeoverTo(3_500f)
        assertEquals("the pin holds the tab", 1, tabRecs().size)
        advanceTakeoverTo(3_700f)
        assertEquals("then it closes", listOf(PpTransition.OPEN, PpTransition.CLOSE), tabRecs().map { it.kind })
    }

    @Test
    fun hidingMidPinCancelsCleanly() {
        openEdmTab()
        repo.bug.value = ScoreBugFixtures.ppGoalView("EDM", Side.HOME, opponent = "CGY")
        advanceTakeoverTo(1_000f)
        applyPlacement(ScoreBugFixtures.input().copy(screenOff = true))
        advance(100)
        assertFalse(on())
        assertFalse(pinned())
        assertEquals(listOf(true), probe.pp.releases.map { it.snap })
        assertTrue(frame().tabClosed)
        advance(1000)
        applyPlacement(ScoreBugFixtures.input())
        advance(3000)
        assertEquals("no replay, and no tab: the power play ended in the goal", listOf(PpTransition.OPEN), tabRecs().map { it.kind })
        assertEquals(1, probe.begins.size)
    }

    @Test
    fun placeModeMidPinCancelsCleanly() {
        openEdmTab()
        repo.bug.value = ScoreBugFixtures.ppGoalView("EDM", Side.HOME, opponent = "CGY")
        advanceTakeoverTo(1_000f)
        applyPlacement(ScoreBugFixtures.input(placing = true))
        advance(100)
        assertFalse(pinned())
        assertEquals(listOf(true), probe.pp.releases.map { it.snap })
        assertTrue(frame().tabClosed)
    }

    @Test
    fun aFollowChangeMidPinCancelsCleanly() {
        openEdmTab()
        repo.bug.value = ScoreBugFixtures.ppGoalView("EDM", Side.HOME, opponent = "CGY")
        advanceTakeoverTo(1_000f)
        repo.bug.value = ScoreBugFixtures.view(goalSeq = 0, gameId = GameId(League.NHL, "2025020999"))
        advance(100)
        assertFalse(on())
        assertFalse(pinned())
        assertTrue(frame().tabClosed && frame().pillClosed)
        advance(3000)
        assertEquals(listOf(PpTransition.OPEN), tabRecs().map { it.kind })
    }

    // ---- the still version (§3.6)

    @Test
    fun lowEndModeUsesTheStillVersionAndBakesNoHalo() {
        startGoalHost(lowEnd = true)
        repo.bug.value = ppEdm()
        advance(300)
        assertEquals(listOf(PpTransition.STILL), tabRecs().map { it.kind })
        assertTrue(tabRecs()[0].to)
        assertEquals(listOf(PpTransition.STILL), pillRecs().map { it.kind })
        repo.bug.value = ppEdm(kind = PowerPlayKind.TWO_MAN)
        advance(300)
        assertEquals("a kind change snaps too: no pulse", listOf(PpTransition.STILL, PpTransition.STILL), tabRecs().map { it.kind })
        assertEquals(0, halos)
        assertTrue("the size bake ran", sizeBakes.isNotEmpty())
        assertTrue("low-end mode never bakes the shadow", sizeBakes.none { it })
        val f = frame()
        assertEquals("no shadow in low-end mode", 0f, f.shadowAlpha, 0f)
        assertEquals("no halo in low-end mode", 0f, f.halo, 0f)
    }

    @Test
    fun animationsOffUseTheStillVersionToo() {
        startGoalHost(context = FixedScale(0f))
        repo.bug.value = ppEdm()
        advance(300)
        assertEquals(listOf(PpTransition.STILL), tabRecs().map { it.kind })
        repo.bug.value = ppEdm(side = Side.AWAY)
        advance(300)
        assertEquals("a side change snaps: no close, no open", listOf(PpTransition.STILL, PpTransition.STILL), tabRecs().map { it.kind })
    }

    @Test
    fun theStillPathSwapsToPpGoalAtFrameA() {
        openEdmTab(lowEnd = true)
        repo.bug.value = ScoreBugFixtures.ppGoalView("EDM", Side.HOME, opponent = "CGY")
        advance(100)
        assertTrue(on())
        assertEquals("frame A", 0f, t(), 0f)
        assertEquals(PpLabel.PP_GOAL, drawn().label)
        advance(3600)
        assertFalse(pinned())
        assertEquals(listOf(PpTransition.STILL, PpTransition.STILL), tabRecs().map { it.kind })
        assertFalse("the tab snapped closed", tabRecs()[1].to)
        assertEquals(PpLabel.PP_GOAL, tabRecs()[1].label)
    }

    @Test
    fun withTheAnimatorScaleAtTwoTheTabStillReleasesAtDriver3600() {
        startGoalHost(context = FixedScale(2f))
        repo.bug.value = ppEdm()
        advance(4500)
        assertEquals("the open ran at half speed but finished", 1900f, tabDriver(), 0f)
        repo.bug.value = ScoreBugFixtures.ppGoalView("EDM", Side.HOME, opponent = "CGY")
        advance(100)
        advanceTakeoverTo(3_570f)
        assertTrue(pinned())
        advanceTakeoverTo(3_700f)
        assertFalse(pinned())
        val r = probe.pp.releases.single()
        assertTrue("released at the driver's 3 600, not at 3.6 s: ${r.driverMs}", r.driverMs in 3_600f..3_640f)
    }

    // ---- the live region and the description (§7)

    @Test
    fun theLiveRegionSaysEachStartKindChangeAndRealEndOnce() {
        startGoalHost()
        repo.bug.value = ppEdm()
        advance(100)
        assertEquals("Edmonton power play.", live())
        repo.bug.value = ppEdm(secs = 59)
        advance(100)
        assertEquals("a seconds update says nothing", "", live())
        repo.bug.value = ppEdm(kind = PowerPlayKind.TWO_MAN, secs = 50)
        advance(100)
        assertEquals("Edmonton five on three.", live())
        repo.bug.value = ScoreBugFixtures.view(away = 1, home = 2, clockSecs = 1100, ppEnded = true)
        advance(100)
        assertEquals("Power play over.", live())
        repo.bug.value = ScoreBugFixtures.stale
        advance(100)
        assertEquals("a stale view leaves the text unchanged", "Power play over.", live())
        repo.bug.value = ScoreBugFixtures.view(away = 1, home = 2, clockSecs = 1000)
        advance(100)
        assertEquals("any other view clears it", "", live())
    }

    @Test
    fun powerPlayOverIsNotAnnouncedAfterAGoal() {
        startGoalHost()
        repo.bug.value = ppEdm()
        advance(100)
        assertEquals("Edmonton power play.", live())
        repo.bug.value = ScoreBugFixtures.ppGoalView("EDM", Side.HOME, opponent = "CGY") // the power play ends in the goal
        advance(100)
        assertTrue(live(), live().startsWith("Edmonton goal."))
        repo.bug.value = ScoreBugFixtures.ppGoalView("EDM", Side.HOME, opponent = "CGY").copy(goalSide = null, goalPp = false)
        advance(100)
        assertEquals("the mark clears to empty, never to 'Power play over.'", "", live())
    }

    @Test
    fun theDescriptionCarriesThePowerPlayAndItsTime() {
        startGoalHost()
        repo.bug.value = ppEdm(secs = 79)
        advance(100)
        assertTrue(desc(), desc().endsWith(". Edmonton power play, 1:19 left"))
        repo.bug.value = ppEdm(secs = null)
        advance(100)
        assertTrue(desc(), desc().endsWith(". Edmonton power play"))
        repo.bug.value = ScoreBugFixtures.view(away = 1, home = 2, clockSecs = 1000)
        advance(100)
        assertTrue(desc(), !desc().contains("power play"))
    }

    private class FixedScale(override val scaleFactor: Float) : MotionDurationScale
}

/**
 * Stand-in for `PowerPlayTimeline.sample`: the wiring tests drive the runner, not the curves. [Fake] is a plain linear
 * timeline with the shadow and halo "on". Tests that need the real curves use `PpSampler.Real`.
 */
internal object PpTestSamplers {
    val Fake = PpSampler { track, kind, t, _, to, out ->
        val open = when (kind) {
            PpTransition.OPEN -> (t / 550f).coerceIn(0f, 1f)
            PpTransition.CLOSE -> 1f - (t / 550f).coerceIn(0f, 1f)
            PpTransition.PULSE -> 1f
            PpTransition.STILL -> if (to) 1f else 0f
        }
        val closed = open == 0f && (kind != PpTransition.OPEN)
        when (track) {
            PpTrack.TAB -> { out.slide = open; out.shadowAlpha = 0.5f * open; out.textAlpha = open; out.tabClosed = closed }
            PpTrack.PILL -> { out.tint = open; out.halo = open; out.pillClosed = closed }
        }
        out
    }

}
