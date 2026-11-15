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
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GoalTimeline
import app.thebox.core.sports.League
import app.thebox.core.sports.NflBarKey
import app.thebox.core.sports.NflBarKind
import app.thebox.core.sports.NflScoreKind
import app.thebox.core.sports.NflTimeoutMark
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.PlacementInput
import app.thebox.core.sports.ScoreBugPlacement
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.SportsPrefs
import app.thebox.core.sports.StackedNflTrack
import app.thebox.core.sports.StackedNflTransition
import app.thebox.ui.guide.GuideDebugCounters
import app.thebox.ui.sports.StackedNflRunFixtures as F
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

/**
 * docs/design/m6-nfl-stacked.md §9.2 `StackedNflDrawTest` (WP-4; compose test clock): the runner against the foundation's
 * contracts, with WP-2's table and timelines and WP-3's painters replaced by [StackedNflRunFixtures]' fakes.
 *
 * Covered: one composition per snapshot and none between or during a takeover; idle draws nothing; a clock-only update
 * redraws without a kick; each §4.1 transition (bar OPEN / CLOSE / REKEY, the possession ball and stripe, the clock
 * column fold to the 243 box, the pip BURN on the caller's side, the flag shake once per `flagId`); the ST S1 rules (the
 * held old score, the first view snaps, hide freezes, the follow ending, place mode, back-to-back scores, the pin,
 * cancels); the takeover's score label at 216, its precedence over a flag and a timeout, the +7 pair at 4 800; the still
 * version; the live region and the description.
 *
 * Robolectric runs no draw pass, so the draw is observed through [StackedNflProbe.args]: what the one draw would hand
 * `StackedNflPainter.drawFrame`, filled from the same drivers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class StackedNflDrawTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val repo = FakeScoreRepository(SportsPrefs(nhlStyle = NhlBugStyle.STACKED))
    private val probe = StackedNflProbe()
    private val calls = F.Calls()
    private var placement by mutableStateOf(F.input())

    // ------------------------------------------------------------------ host and helpers

    private fun start(first: ScoreBugView? = F.view(), context: CoroutineContext = EmptyCoroutineContext) {
        repo.bug.value = first
        rule.mainClock.autoAdvance = false
        val hooks = F.hooks(probe, calls, context)
        rule.setContent {
            BoxDesignScale { StackedNflBug(repo, placement, ScoreBugFixtures.noGeometry, Modifier, ScoreBugPlacement::place, hooks) }
        }
        rule.waitForIdle()
        advance(2_000)
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
    private fun f() = args().frame!!
    private fun body() = args().state!!
    private fun barKey() = probe.barKey!!()
    private fun words() = F.words(probe.barContent!!())
    private fun moves() = probe.transitions.filter { it.kind != StackedNflTransition.STILL }
    private fun movesOf(track: StackedNflTrack) = moves().filter { it.track == track }

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

    /** Counts the snapshot state changes over [ms] of test clock with no new snapshot (0 = no frame was asked for). */
    private fun appliesOver(ms: Long): Int {
        var applies = 0
        val handle = Snapshot.registerApplyObserver { _, _ -> applies++ }
        try {
            advance(ms, 500)
        } finally {
            handle.dispose()
        }
        return applies
    }

    private fun compositions() = GuideDebugCounters.count(COMPOSITIONS_STACKED_NFL_BUG)
    private fun frames() = GuideDebugCounters.count(FRAMES_STACKED_NFL_BUG)
    private fun desc() = rule.onNodeWithTag(SCORE_BUG_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
    private fun live() = rule.onNodeWithTag(SCORE_BUG_LIVE_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()

    private val play = NflBarKey(NflBarKind.PLAY, Side.AWAY, down = F.firstAndTen, tail = F.kcTab.tail)

    // ------------------------------------------------------------------ the budget (R6) and idle

    @Test
    fun oneCompositionPerSnapshotAndNoneBetween() {
        start()
        val comp0 = compositions()
        val frames0 = frames()
        assertEquals("nothing is asked for in a minute with no snapshot", 0, appliesOver(60_000))
        assertEquals(0, compositions() - comp0)
        assertEquals(0, frames() - frames0)
        val n = probe.transitions.size
        for (i in 1..3) {
            repo.bug.value = F.view(clock = 222 - 20 * i)
            advance(20_000, 1_000)
        }
        assertEquals("one recomposition per snapshot", 3, compositions() - comp0)
        assertEquals("one body per snapshot", 3, frames() - frames0)
        assertEquals("a clock-only update kicks no track", n, probe.transitions.size)
        assertEquals("the new clock is drawn", 162, body().clockSecs)
    }

    @Test
    fun idleAfterARekeyAndAfterTheFlagHoldDrawsNothing() {
        start()
        show(F.view(down = app.thebox.core.sports.DownText.Down(2, 4, goal = false)))
        advance(1_000)
        assertEquals("the REKEY is over: idle", 0, appliesOver(30_000))
        show(F.view(flagId = "401873061503"))
        advance(500)
        assertTrue("the flag wave draws while the flag holds", appliesOver(2_000) > 0)
        advance(K.NFL_FLAG_MARK_MS)
        assertEquals("the wave ends with the hold (S-D8)", 0, appliesOver(30_000))
    }

    @Test
    fun aTakeoverRecomposesNothing() {
        start()
        show(F.touchdown())
        val comp0 = compositions()
        advanceTakeoverTo(4_700f)
        assertEquals("the takeover is draw-only", 0, compositions() - comp0)
    }

    // ------------------------------------------------------------------ rule 2: the first view snaps

    @Test
    fun theFirstViewOfAFollowSnapsEveryTrack() {
        start(F.view(flagId = "401873061503"))
        assertTrue("nothing animates in on follow: ${moves().map { "${it.track} ${it.kind}" }}", moves().isEmpty())
        assertEquals(NflBarKind.FLAG, barKey()!!.kind)
        assertTrue("the first view's flag runs still (no shake)", probe.flags.single().still)
        assertEquals("the bar at rest", K.STACKED_BAR_H, f().barH, 1e-3f)
        assertEquals("the play box", K.STACKED_NFL_W_MAX, f().boxW, 1e-3f)
        assertEquals("pips lit 3 / 2", listOf(1f, 1f, 1f, 1f, 1f, 0f), f().pipAway.toList() + f().pipHome.toList())
    }

    // ------------------------------------------------------------------ §4.1 transitions

    @Test
    fun aContentChangeRekeysTheBarAndAClockOnlyUpdateDoesNot() {
        start()
        assertEquals(play, barKey())
        val second = app.thebox.core.sports.DownText.Down(2, 4, goal = false)
        show(F.view(down = second, clock = 200))
        val rekey = movesOf(StackedNflTrack.BAR).single()
        assertEquals(StackedNflTransition.REKEY, rekey.kind)
        assertEquals(NflBarKey(NflBarKind.PLAY, Side.AWAY, down = second, tail = F.kcTab.tail), rekey.barKey)
        assertNotNull("the previous content is drawn under the background lerp", args().barPrev)
        assertTrue("the sampler saw the REKEY", (StackedNflTrack.BAR to StackedNflTransition.REKEY) in calls.sampled)
        advance(1_000)
        show(F.view(down = second, clock = 190))
        advance(1_000)
        assertEquals("a clock-only update is no REKEY", 1, movesOf(StackedNflTrack.BAR).size)
        assertEquals("fillBar once per content: PLAY, then the 2nd down", 2, calls.fills.size)
    }

    @Test
    fun staleInPlayClosesTheBarAndFreshOpensItAgain() {
        start()
        show(F.stale())
        assertEquals(StackedNflTransition.CLOSE, movesOf(StackedNflTrack.BAR).single().kind)
        assertTrue("the ball goes (no possession while stale)", movesOf(StackedNflTrack.BALL_AWAY).single().kind == StackedNflTransition.CLOSE)
        advance(1_000)
        assertEquals("the clock column stays open (\"No update\" in its slot)", K.STACKED_NFL_W_MAX, f().boxW, 1e-3f)
        assertEquals(0f, f().barH, 1e-3f)
        assertTrue(f().barClosed)
        show(F.view())
        assertEquals(StackedNflTransition.OPEN, movesOf(StackedNflTrack.BAR).last().kind)
        assertEquals(play, barKey())
    }

    @Test
    fun aPossessionChangeMovesTheBallAndTheWideStripe() {
        start()
        show(F.view(tab = F.bufTab))
        assertEquals(StackedNflTransition.CLOSE, movesOf(StackedNflTrack.BALL_AWAY).single().kind)
        assertEquals(StackedNflTransition.OPEN, movesOf(StackedNflTrack.BALL_HOME).single().kind)
        assertEquals(StackedNflTransition.CLOSE, movesOf(StackedNflTrack.STRIPE_AWAY).single().kind)
        assertEquals(StackedNflTransition.OPEN, movesOf(StackedNflTrack.STRIPE_HOME).single().kind)
        assertEquals("the bar takes BUF's colours: a REKEY", Side.HOME, movesOf(StackedNflTrack.BAR).single().barKey!!.side)
        advance(1_000)
        assertEquals(0f, f().ballAwayAlpha, 1e-4f)
        assertEquals(1f, f().ballHomeAlpha, 1e-4f)
        assertEquals(1f, f().stripeHome, 1e-4f)
    }

    @Test
    fun halftimeFoldsTheClockToThe243BoxAndTheSecondHalfOpensIt() {
        start()
        show(F.halftime())
        assertEquals(StackedNflTransition.CLOSE, movesOf(StackedNflTrack.CLOCK).single().kind)
        assertEquals(NflBarKey(NflBarKind.HALFTIME, null), movesOf(StackedNflTrack.BAR).single().barKey)
        assertEquals(StackedNflTransition.REKEY, movesOf(StackedNflTrack.BAR).single().kind)
        advance(200)
        val mid = f().boxW
        assertTrue("mid-fold: $mid", mid > K.STACKED_ROWS_W && mid < K.STACKED_NFL_W_MAX)
        assertEquals("the folding column keeps its texts", "Q4", body().periodText)
        advance(1_000)
        assertEquals("the break box", K.STACKED_ROWS_W, f().boxW, 1e-3f)
        val tag = rule.onNodeWithTag(SCORE_BUG_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("the drawn box is 243 wide (rows only)", K.STACKED_ROWS_W, tag.width, 1f)
        assertEquals("the rows hug the right margin", 1824f, tag.right, 1f)
        show(F.view())
        assertEquals(StackedNflTransition.OPEN, movesOf(StackedNflTrack.CLOCK).last().kind)
        advance(1_000)
        assertEquals(K.STACKED_NFL_W_MAX, f().boxW, 1e-3f)
    }

    @Test
    fun aTimeoutBurnsOnePipOnTheCallersSide() {
        start()
        val mark = NflTimeoutMark(Side.HOME, 1, confirmed = true)
        show(F.view(homeTimeouts = 1, timeout = mark))
        val burn = movesOf(StackedNflTrack.PIPS_HOME).single()
        assertEquals(StackedNflTransition.BURN, burn.kind)
        assertEquals("from 2 lit to 1: the pip at index 1 burns", 2 to 1, burn.fromLit to burn.toLit)
        assertTrue("nothing on the away side", movesOf(StackedNflTrack.PIPS_AWAY).isEmpty())
        assertEquals(NflBarKey(NflBarKind.TIMEOUT, Side.HOME, left = 1), barKey())
        assertEquals(StackedNflTransition.REKEY, movesOf(StackedNflTrack.BAR).single().kind)
        args() // a draw samples the burn (Robolectric draws nothing on its own)
        assertTrue("${calls.pipSamples}", (Triple(Side.HOME, StackedNflTransition.BURN, 2 to 1)) in calls.pipSamples)
        advance(500)
        show(F.view(homeTimeouts = 1, timeout = mark, clock = 200)) // the held poll
        advance(3_000)
        assertEquals("one burn per drop", 1, movesOf(StackedNflTrack.PIPS_HOME).size)
        // A drop with no mark (the low-water flap is the machine's; the runner just fades the pip).
        show(F.view(awayTimeouts = 2, homeTimeouts = 1))
        assertEquals(StackedNflTransition.CLOSE, movesOf(StackedNflTrack.PIPS_AWAY).single().kind)
    }

    @Test
    fun aNewFlagIdShakesExactlyOnce() {
        start()
        show(F.view(flagId = "401873061503"))
        assertEquals(NflBarKind.FLAG, barKey()!!.kind)
        assertEquals(StackedNflTransition.REKEY, movesOf(StackedNflTrack.BAR).single().kind)
        assertEquals(listOf("401873061503"), probe.flags.filter { !it.still }.map { it.id })
        advance(300)
        assertTrue("the flag sub-track is sampled in draw", f().shakeDx > 0f && calls.flagSamples > 0)
        show(F.view(flagId = "401873061503", clock = 200)) // the same flag, a later poll
        advance(500)
        assertEquals("the same id: no second shake", 1, probe.flags.size)
        assertEquals(1, movesOf(StackedNflTrack.BAR).size)
        show(F.view(flagId = "401873061520"))
        assertEquals("a new id: one more shake", listOf("401873061503", "401873061520"), probe.flags.map { it.id })
        assertEquals("and a REKEY of the same FLAG key", 2, movesOf(StackedNflTrack.BAR).size)
        show(F.view())
        advance(100)
        assertEquals("off the flag bar: the sub-track stops", 0f, f().shakeDx, 0f)
        assertEquals(play, barKey())
    }

    // ------------------------------------------------------------------ rule 1 and the takeover

    @Test
    fun theOldScoreIsHeldUntilTheLeadCoversThenTheScoreLabel() {
        start()
        show(F.touchdown())
        assertEquals("the score view's composition still draws the old score", "24", body().awayScore)
        advanceTakeoverTo(GoalTimeline.LEAD_COVERED_MS - 20f)
        assertTrue(on())
        assertEquals("before the lead covers", "24", body().awayScore)
        assertEquals("the bar still says the play", play, barKey())
        advanceTakeoverTo(GoalTimeline.LEAD_COVERED_MS)
        assertEquals("covered: the new score under the takeover", "30", body().awayScore)
        assertEquals(NflBarKey(NflBarKind.SCORE, Side.AWAY, score = NflScoreKind.TOUCHDOWN, points = 6), barKey())
        assertEquals(StackedNflTransition.REKEY, movesOf(StackedNflTrack.BAR).single().kind)
        assertEquals(listOf(0), probe.covers)
        assertTrue(args().takeoverOn)
        assertEquals(Side.AWAY, args().scoreSide)
        assertEquals("the takeover is laid out on the play width", K.STACKED_NFL_W_MAX, probe.begins.single().boxW, 1e-3f)
        assertEquals(listOf(F.td()), calls.begins.single().second)
        advanceTakeoverTo(K.NFL_TAKEOVER_MS + 32f)
        assertFalse("released at 3 600", pinned())
        assertEquals(K.NFL_TAKEOVER_MS.toFloat(), probe.releases.single().driverMs, 40f)
        assertEquals("the bar re-keys to the current state", play, barKey())
        advanceTakeoverTo(4_799f)
        advance(200)
        assertFalse("done at 4 800 (STACKED end)", on())
    }

    /** RC4: the takeover wins over a flag and a timeout; a mark that started in the pin shows at the release if active. */
    @Test
    fun marksDuringThePinWaitForTheReleaseAndShowOnlyIfStillActive() {
        start()
        show(F.touchdown())
        advanceTakeoverTo(1_000f)
        show(F.touchdown().let { it.copy(nfl = it.nfl!!.copy(flagId = "401873061600")) })
        advanceTakeoverTo(2_000f)
        assertEquals("the score label stays", NflBarKind.SCORE, barKey()!!.kind)
        assertTrue("no flag while pinned", probe.flags.isEmpty())
        show(F.touchdown().let { it.copy(nfl = it.nfl!!.copy(homeTimeouts = 1, timeout = NflTimeoutMark(Side.HOME, 1, true))) })
        advanceTakeoverTo(3_000f)
        assertTrue("no burn while pinned", movesOf(StackedNflTrack.PIPS_HOME).isEmpty())
        advanceTakeoverTo(K.NFL_TAKEOVER_MS + 32f)
        assertEquals("the timeout, still active, shows at the release", NflBarKey(NflBarKind.TIMEOUT, Side.HOME, left = 1), barKey())
        assertEquals("and its pip burns then", StackedNflTransition.BURN, movesOf(StackedNflTrack.PIPS_HOME).single().kind)
        assertTrue("the flag that ended in the pin never showed", probe.flags.isEmpty())
    }

    @Test
    fun aPairRunsAt4800WithTheExtraPointBetweenAndTallyAUntil5016() {
        start()
        show(F.pair())
        advanceTakeoverTo(GoalTimeline.LEAD_COVERED_MS + 16f)
        assertEquals("tally A on the box", "30", body().awayScore)
        assertEquals(NflBarKey(NflBarKind.SCORE, Side.AWAY, score = NflScoreKind.TOUCHDOWN, points = 6), barKey())
        advanceTakeoverTo(K.NFL_TAKEOVER_MS + 16f)
        assertTrue("a pair stays pinned through 3 600", pinned())
        assertEquals("EXTRA POINT between the takeovers", NflBarKey(NflBarKind.PAT, Side.AWAY), barKey())
        assertEquals("one segment begun", 1, probe.begins.size)
        advanceTakeoverTo(F.SPEC.pairSecondMs - 32f)
        assertEquals(1, probe.begins.size)
        advanceTakeoverTo(F.SPEC.pairSecondMs + 16f)
        assertEquals("B begins at 4 800", listOf(0, 1), probe.begins.map { it.segment })
        assertEquals(F.xp(), probe.begins[1].score)
        assertEquals("still tally A before B's lead covers", "30", body().awayScore)
        advanceTakeoverTo(F.SPEC.pairSecondMs + GoalTimeline.LEAD_COVERED_MS + 16f)
        assertEquals("from 5 016: the pair's end score", "31", body().awayScore)
        assertEquals(NflBarKey(NflBarKind.SCORE, Side.AWAY, score = NflScoreKind.EXTRA_POINT, points = 1), barKey())
        assertEquals(listOf(0, 1), probe.covers)
        advanceTakeoverTo(F.SPEC.pairSecondMs + K.NFL_TAKEOVER_MS - 32f)
        assertTrue(pinned())
        advanceTakeoverTo(F.SPEC.pairSecondMs + K.NFL_TAKEOVER_MS + 32f)
        assertFalse("released at 8 400", pinned())
        assertEquals(8_400f, probe.releases.single().driverMs, 40f)
        advanceTakeoverTo(9_590f)
        advance(200)
        assertFalse("done at 9 600", on())
        assertEquals("the bar REKEYs: TD, PAT, XP, then the play", 4, movesOf(StackedNflTrack.BAR).size)
    }

    @Test
    fun aScoreWithTheBarOpeningMidwayPinsItsHeight() {
        start()
        show(F.stale())
        advance(1_000)
        show(F.view())
        advance(150)
        val mid = f().barH
        assertTrue("mid-open: $mid", mid > 0f && mid < K.STACKED_BAR_H)
        show(F.touchdown())
        assertEquals("the pin snaps the bar height to the end of its open", K.STACKED_BAR_H, f().barH, 1e-3f)
        assertTrue("the pinned box's glow (333 × 141) is cached", (3330 to 1410) in probe.glowKeys!!())
        advanceTakeoverTo(GoalTimeline.LEAD_COVERED_MS + 16f)
        assertEquals("the bar was open at the pin: the score label", NflBarKind.SCORE, barKey()!!.kind)
    }

    /** A score while the bar is closed (no `situation`): the box is fixed, so no label opens the bar until the release. */
    @Test
    fun aScoreWithTheBarClosedOpensNoLabelUntilTheRelease() {
        start(F.view(tab = null, down = null))
        assertNull(barKey())
        show(F.view(tab = null, down = null, away = 30, seq = 1, side = Side.AWAY, scores = listOf(F.td())))
        advanceTakeoverTo(1_000f)
        assertNull("the bar stays closed while pinned", barKey())
        assertEquals(0f, f().barH, 1e-3f)
    }

    @Test
    fun aScoreWithTheClockFoldingKeepsThePinnedBreakWidth() {
        start()
        show(F.halftime())
        advance(100)
        show(F.halftime().let { it.copy(away = it.away.copy(score = 27), goalSeq = 1, goalSide = Side.AWAY, nfl = it.nfl!!.copy(scores = listOf(F.td(away = 27)))) })
        assertEquals("the folding column snaps to its end", K.STACKED_ROWS_W, f().boxW, 1e-3f)
        assertEquals("the takeover fits the break width", K.STACKED_ROWS_W, probe.begins.single().boxW, 1e-3f)
    }

    @Test
    fun theGlowIsPrewarmedAtRestAndReadyWhenAScoreComes() {
        start()
        assertEquals("play: 333 × 141", listOf(3330 to 1410), probe.glowKeys!!())
        show(F.halftime())
        advance(2_000)
        assertEquals("the break: 243 × 141, newest first", listOf(2430 to 1410, 3330 to 1410), probe.glowKeys!!())
        val bakes = probe.glowBakes.size
        show(F.halftime().let { it.copy(goalSeq = 1, goalSide = Side.AWAY, nfl = it.nfl!!.copy(scores = listOf(F.td()))) })
        advanceTakeoverTo(100f)
        assertEquals("no bake at the score", bakes, probe.glowBakes.size)
    }

    // ------------------------------------------------------------------ rules 3, 4, 5, 7

    @Test
    fun aFollowThatEndsMidRekeyFadesWithItsLayoutThenResets() {
        start()
        show(F.view(tab = F.bufTab))
        show(null) // mid-REKEY, 16 ms into the fade
        assertEquals("mid-fade: the bar's key is kept", Side.HOME, barKey()!!.side)
        assertEquals("mid-fade: the game's colours", "KC", body().awayAbbrev)
        assertNotNull(args().colours)
        advance(80)
        assertEquals(K.STACKED_BAR_H, f().barH, 1e-3f)
        advance(1_000)
        assertNull("reset after the fade", barKey())
    }

    @Test
    fun aHiddenBugFreezesThenSnapsOnReappearance() {
        start()
        applyPlacement(F.input().copy(screenOff = true))
        show(F.view(flagId = "401873061503", homeTimeouts = 1))
        advance(1_000)
        assertTrue("nothing moves while hidden", moves().isEmpty())
        applyPlacement(F.input())
        advance(100)
        assertTrue("the reappearance snaps: ${moves().map { it.kind }}", moves().isEmpty())
        assertEquals(NflBarKind.FLAG, barKey()!!.kind)
        assertTrue("a snapped flag is still", probe.flags.all { it.still })
    }

    @Test
    fun hidingMidTakeoverCancelsAndAScoreWhileHiddenIsNeverReplayed() {
        start()
        show(F.touchdown())
        advanceTakeoverTo(1_000f)
        applyPlacement(F.input().copy(screenOff = true))
        assertFalse("cancelled", on())
        assertTrue("released with a snap", probe.releases.single().snap)
        show(F.touchdown(seq = 2).let { it.copy(away = it.away.copy(score = 37)) })
        applyPlacement(F.input())
        advance(6_000)
        assertEquals("never replayed", 1, probe.begins.size)
        assertEquals("37", body().awayScore)
    }

    @Test
    fun placeModeShowsTheSampleAtRest() {
        start(null)
        applyPlacement(F.input(placing = true))
        advance(500)
        assertEquals("KC", body().awayAbbrev)
        assertEquals("BUF", body().homeAbbrev)
        assertEquals("the anchored 333 box", K.STACKED_NFL_W_MAX, f().boxW, 1e-3f)
        assertTrue(moves().isEmpty())
        assertEquals("the sample's possession bar", NflBarKey(NflBarKind.PLAY, Side.AWAY, down = F.firstAndTen, tail = F.kcTab.tail), barKey())
        assertNull("the sample never borrows logos", args().awayLogo)
    }

    @Test
    fun aScoreMidTakeoverRestartsAndKeepsThePin() {
        start()
        show(F.touchdown())
        advanceTakeoverTo(1_000f)
        show(F.view(away = 30, home = 20, seq = 2, side = Side.HOME, scores = listOf(app.thebox.core.sports.NflScore(NflScoreKind.FIELD_GOAL, 3, 30, 20))))
        assertEquals("restarted", 2, probe.begins.size)
        assertTrue("the pin continues", pinned())
        assertTrue(probe.releases.isEmpty())
        advanceTakeoverTo(GoalTimeline.LEAD_COVERED_MS + 16f)
        assertEquals(NflBarKey(NflBarKind.SCORE, Side.HOME, score = NflScoreKind.FIELD_GOAL, points = 3), barKey())
    }

    @Test
    fun aSizeChangeOrANewGameCancels() {
        start()
        show(F.touchdown())
        advanceTakeoverTo(500f)
        applyPlacement(F.input(size = ScoreBugSize.LARGE))
        assertFalse(on())
        assertFalse(pinned())
        show(F.touchdown(seq = 2))
        advanceTakeoverTo(500f)
        show(F.view(away = 0, home = 0, gameId = GameId(League.NFL, "other")))
        assertFalse("a new game cancels", on())
        assertFalse(pinned())
    }

    @Test
    fun aStyleFlipMidTakeoverLeavesWithTheComposable() {
        var style by mutableStateOf(NhlBugStyle.STACKED)
        repo.bug.value = F.view()
        rule.mainClock.autoAdvance = false
        val hooks = F.hooks(probe, calls)
        rule.setContent {
            BoxDesignScale {
                key(style) {
                    if (style == NhlBugStyle.STACKED) {
                        StackedNflBug(repo, placement, ScoreBugFixtures.noGeometry, Modifier, ScoreBugPlacement::place, hooks)
                    } else {
                        NflBug(repo, F.input().copy(style = NhlBugStyle.PILL), ScoreBugFixtures.noGeometry, Modifier, ScoreBugPlacement::place, NflHooks(background = EmptyCoroutineContext))
                    }
                }
            }
        }
        advance(2_000)
        show(F.touchdown())
        advanceTakeoverTo(1_000f)
        style = NhlBugStyle.PILL
        Snapshot.sendApplyNotifications()
        advance(100)
        val after = t()
        advance(6_000, 100)
        assertEquals("the Stacked NFL takeover left with its composable", after, t(), 1f)
        assertTrue(after < 1_500f)
    }

    // ------------------------------------------------------------------ the still version (§4.4)

    @Test
    fun theStillPathSnapsEveryTransitionAndShowsFramesAThenB() {
        start(F.view(), FixedScale(0f))
        show(F.view(tab = F.bufTab, homeTimeouts = 1, timeout = NflTimeoutMark(Side.HOME, 1, true)))
        advance(50)
        assertTrue("every transition snaps (no burn): ${moves().map { "${it.track} ${it.kind}" }}", moves().isEmpty())
        assertEquals("the burnt pip is straight to used", listOf(1f, 0f, 0f), f().pipHome.toList())
        show(F.view(flagId = "401873061503"))
        assertTrue("no shake or wave", probe.flags.last().still)
        show(F.touchdown())
        advance(50)
        assertTrue(probe.begins.single().still)
        assertEquals("frame A covers from its first draw: the new score", "30", body().awayScore)
        assertEquals(NflScoreKind.TOUCHDOWN, barKey()!!.score)
        assertTrue(args().still)
        advance(K.NFL_TAKEOVER_MS + 50)
        assertFalse("released at frame B", pinned())
        advance(F.SPEC.stillFrameBMs.toLong() + 100)
        assertFalse(on())
    }

    @Test
    fun theStillPairShowsA1B1A2B2() {
        start(F.view(), FixedScale(0f))
        show(F.pair())
        advance(50)
        assertEquals(listOf(0), probe.begins.map { it.segment })
        advance(K.NFL_TAKEOVER_MS)
        assertEquals(NflBarKey(NflBarKind.PAT, Side.AWAY), barKey())
        advance((F.SPEC.pairSecondMs - K.NFL_TAKEOVER_MS).toLong())
        assertEquals(listOf(0, 1), probe.begins.map { it.segment })
        assertEquals("31", body().awayScore)
        assertTrue(pinned())
        advance(K.NFL_TAKEOVER_MS + 50)
        assertFalse(pinned())
    }

    // ------------------------------------------------------------------ placement, live region, description

    @Test
    fun theBarDirectionAndTheMirrorFollowThePlacedCorner() {
        start()
        assertTrue("top right: mirrored", args().mirrored)
        assertFalse(args().up)
        applyPlacement(F.input(ScoreCorner.BOTTOM_LEFT))
        assertFalse(args().mirrored)
        assertTrue("a bottom corner's bar opens above", args().up)
        applyPlacement(F.input(ScoreCorner.BOTTOM_LEFT, textTrackOn = true))
        assertFalse("moved to the top by subtitles: the bar drops below", args().up)
    }

    @Test
    fun theLiveRegionAnnouncesAFlagOncePerIdAndOnlyConfirmedTimeouts() {
        start()
        assertEquals("", live())
        show(F.view(flagId = "401873061503"))
        assertEquals("Flag on the play.", live())
        show(F.view())
        assertEquals("", live())
        show(F.view(homeTimeouts = 1, timeout = NflTimeoutMark(Side.HOME, 1, confirmed = false)))
        assertEquals("the fallback mark is not announced (Rec 9)", "", live())
        show(F.view(homeTimeouts = 1, timeout = NflTimeoutMark(Side.HOME, 1, confirmed = true)))
        assertEquals("Timeout, Bills.", live())
        show(F.touchdown())
        assertTrue(live(), live().startsWith("Kansas City Chiefs touchdown."))
    }

    @Test
    fun theDescriptionCarriesTheTimeouts() {
        start()
        assertTrue(desc(), desc().endsWith("Timeouts left: Chiefs 3, Bills 2"))
        show(F.view(awayTimeouts = null, homeTimeouts = null))
        assertFalse(desc(), desc().contains("Timeouts"))
    }

    private class FixedScale(override val scaleFactor: Float) : MotionDurationScale
}
