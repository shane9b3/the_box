package app.thebox.ui.sports

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.thebox.core.sports.CentreText
import app.thebox.core.sports.DownText
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.FieldZone
import app.thebox.core.sports.GameClock
import app.thebox.core.sports.League
import app.thebox.core.sports.NflScore
import app.thebox.core.sports.NflScoreFrame
import app.thebox.core.sports.NflScoreKind
import app.thebox.core.sports.NflScoreTimeline
import app.thebox.core.sports.NflTab
import app.thebox.core.sports.NflView
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PlacementInput
import app.thebox.core.sports.PpTransition
import app.thebox.core.sports.ScoreBugPlacement
import app.thebox.core.sports.ScoreBugSize
import app.thebox.core.sports.ScoreBugView
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants as K
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.SportsPrefs
import app.thebox.core.sports.TabLead
import app.thebox.core.sports.TabTail
import app.thebox.core.sports.TeamColours
import app.thebox.ui.guide.GuideDebugCounters
import app.thebox.ui.theme.BoxDesignScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Stand-ins for the WP-3 painters and timeline (NFL WP-4): the wiring tests drive the trigger, the pin and the tab runner,
 * not the curves or the pixels. The frame stand-in carries the view's scores, so the no-spoiler holds are observable.
 */
internal object NflTestSupport {
    val colours = TeamColours(0xFF112233.toInt(), 0xFF445566.toInt(), 0xFF778899.toInt(), 0xFFAABBCC.toInt())

    fun frameOf(v: ScoreBugView) = NflBugFrame(
        v.away.abbrev, v.home.abbrev, (v.away.score ?: 0).toString(), (v.home.score ?: 0).toString(),
        topText = null, topPx = K.NFL_QUARTER_PX, clockOnBottom = v.bottom == CentreText.Clock, clockSecs = v.clockSecs ?: 0,
        oneLine = v.bottom == null, down = null, downPx = K.NFL_DOWN_PX, dim = v.dim,
    )

    /** A linear stand-in for the timeline: `done` at the end of one score (4 500) or a pair (9 000); a constant pulse. */
    val sampler = NflSampler { _, second, t, _, out ->
        val total = if (second != null) K.NFL_DOUBLE_MARK_MS else K.NFL_TAKEOVER_MS + K.NFL_POP_MS
        out.done = t >= total
        out.segment = if (second != null && t >= K.NFL_PAIR_SECOND_START_MS) 1 else 0
        out.tabPulse = 0.5f
        out
    }

    fun hooks(probe: NflProbe?, context: CoroutineContext = EmptyCoroutineContext, tabAllowed: () -> Boolean = { true }) = NflHooks(
        colours = { colours },
        sampler = sampler,
        glowAllowed = { _, _, _, _ -> true },
        tabAllowed = { _, _, _ -> tabAllowed() },
        bake = { null },
        bakeSize = { _, _ -> },
        context = context,
        background = EmptyCoroutineContext,
        begin = { _, _, _, _, _, _ -> },
        ppSampler = PpTestSamplers.Fake,
        bakeLabels = { _, _, _, _ -> null },
        tabBegin = { _, _, _ -> },
        frame = { _, v, _ -> frameOf(v) },
        probe = probe,
    )

    fun input(
        size: ScoreBugSize = ScoreBugSize.STANDARD,
        placing: Boolean = false,
    ): PlacementInput = ScoreBugFixtures.input(size = size, placing = placing).copy(league = League.NFL)

    /** True once WP-3 has replaced the commit-0 `TODO()` bodies (the real painters and timeline can run). */
    fun wp3Landed(res: android.content.res.Resources): Boolean = runCatching {
        NflScoreTimeline.sample(NflScoreKind.TOUCHDOWN, 0f, NflScoreFrame())
        NflBugPainter(1f, null, null).frame(SportsFixtures.nflView(), res)
    }.isSuccess
}

/**
 * docs/design/m6-nfl.md §10.2 `NflBugDrawTest` (WP-4): the league seam (B4), the recomposition budget, the takeover
 * trigger and the §5.7 pair, the possession tab's S2 key, the pin and its release (S2), RED ZONE (§4.5), the still path
 * (ruling 20), and the live region and description (§8).
 *
 * Robolectric runs no draw pass, so the draw is observed through [NflProbe.args]: the arguments the bug's one draw would
 * hand `NflBugPainter.drawFrame`, filled from the same drivers. The WP-3 painters are stand-ins ([NflTestSupport]); the
 * seam tests drive `ScoreBug(...)` with the production hooks while the bug is hidden (nothing is drawn, so nothing of
 * WP-3 runs), and one visible seam test runs once WP-3 has landed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class NflBugDrawTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val repo = FakeScoreRepository(SportsPrefs())
    private val probe = NflProbe()
    private var placement by mutableStateOf(NflTestSupport.input())
    private var allowed = true

    // ------------------------------------------------------------------ views (KC @ BUF)

    private val kcBall35 = NflTab(Side.AWAY, TabLead.BALL, TabTail.Field(FieldZone.OWN, 35))

    private fun nfl(
        tab: NflTab? = kcBall35,
        kc: Int = 24,
        buf: Int = 17,
        scores: List<NflScore> = emptyList(),
        seq: Int = 0,
        side: Side? = null,
        down: DownText? = DownText.Down(1, 10, goal = false),
        period: Int = 1,
        clock: Int = 672,
    ): ScoreBugView = SportsFixtures.nflView(
        game = SportsFixtures.nflGame(
            away = SportsFixtures.nflTeam("KC", "Kansas City Chiefs", "Chiefs", kc),
            home = SportsFixtures.nflTeam("BUF", "Buffalo Bills", "Bills", buf),
            period = period, clock = GameClock(clock, running = false, inIntermission = false),
        ),
        nfl = NflView(down, tab, scores),
        goalSeq = seq, goalSide = side,
    )

    /** KC's touchdown (24 → 30): the stale body renders the try (§3.3). */
    private fun kcTd(seq: Int = 1) = nfl(
        tab = NflTab(Side.AWAY, TabLead.BALL, TabTail.ExtraPoint), kc = 30, seq = seq, side = Side.AWAY, down = DownText.Pat,
        scores = listOf(NflScore(NflScoreKind.TOUCHDOWN, 6, 30, 17)),
    )

    /** A +7 update (ruling 25): TD (30 – 17), then XP (31 – 17), already in the kick-off state. */
    private fun kcPair(seq: Int = 1) = nfl(
        tab = NflTab(Side.AWAY, TabLead.KICKOFF, TabTail.Field(FieldZone.OWN, 35)), kc = 31, seq = seq, side = Side.AWAY,
        down = DownText.Kickoff,
        scores = listOf(NflScore(NflScoreKind.TOUCHDOWN, 6, 30, 17), NflScore(NflScoreKind.EXTRA_POINT, 1, 31, 17)),
    )

    // ------------------------------------------------------------------ host and helpers

    private fun start(first: ScoreBugView = nfl(), context: CoroutineContext = EmptyCoroutineContext) {
        repo.bug.value = first
        rule.mainClock.autoAdvance = false
        val hooks = NflTestSupport.hooks(probe, context) { allowed }
        rule.setContent {
            BoxDesignScale { NflBug(repo, placement, ScoreBugFixtures.noGeometry, Modifier, ScoreBugPlacement::place, hooks) }
        }
        rule.waitForIdle()
        advance(2000)
    }

    /** Followed with no tab, then the tab opens (an OPEN) for KC at its own 35, settled. */
    private fun startOpen(context: CoroutineContext = EmptyCoroutineContext) {
        start(nfl(tab = null), context)
        repo.bug.value = nfl()
        advance(2500)
    }

    private fun advance(ms: Long, step: Long = 100) {
        var left = ms
        while (left > 0) { val n = minOf(step, left); rule.mainClock.advanceTimeBy(n); left -= n }
        rule.waitForIdle()
    }

    private fun on() = probe.takeoverOn!!.value
    private fun t() = probe.driver!!.value
    private fun pinned() = probe.pinned!!()
    private fun args() = probe.args!!()
    private fun recs() = probe.transitions.map { it.kind }
    private fun tab() = probe.tabContent!!()

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
    }

    private fun live() = rule.onNodeWithTag(SCORE_BUG_LIVE_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
    private fun desc() = rule.onNodeWithTag(SCORE_BUG_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()

    private fun nhlCount() = GuideDebugCounters.count(COMPOSITIONS_SCORE_BUG)
    private fun nflCount() = GuideDebugCounters.count(COMPOSITIONS_NFL_BUG)

    // ------------------------------------------------------------------ the league seam (B4), through ScoreBug(...)

    /** The production branch, hidden (screen off): the NFL bug composes, the NHL composable never does. */
    private fun seamHost() {
        rule.mainClock.autoAdvance = false
        placement = NflTestSupport.input().copy(screenOff = true)
        rule.setContent { BoxDesignScale { ScoreBug(repo, placement, ScoreBugFixtures.noGeometry, lowEnd = false) } }
        advance(1000)
    }

    @Test
    fun anNflViewWithAScoreNeverReachesTheNhlBug() {
        repo.bug.value = nfl()
        val nhl0 = nhlCount()
        val nfl0 = nflCount()
        seamHost()
        repo.bug.value = kcTd()
        advance(1000)
        assertTrue("the NFL bug composed", nflCount() - nfl0 >= 2)
        assertEquals("ScoreBugWith (GoalRun, GoalPainter) never composed", 0, nhlCount() - nhl0)
    }

    @Test
    fun theLeagueIsHeldWhileTheBugIsHidden() {
        repo.bug.value = nfl()
        val nhl0 = nhlCount()
        seamHost()
        val nfl0 = nflCount()
        repo.bug.value = null // hidden / fading: the last shown league holds
        advance(500)
        assertTrue(nflCount() > nfl0)
        assertEquals(0, nhlCount() - nhl0)
    }

    @Test
    fun aLeagueChangeOfTheFollowSwapsTheBug() {
        repo.bug.value = ScoreBugFixtures.live // an NHL follow
        val nhl0 = nhlCount()
        val nfl0 = nflCount()
        seamHost()
        assertTrue("the NHL bug composed", nhlCount() > nhl0)
        assertEquals(0, nflCount() - nfl0)
        val nhl1 = nhlCount()
        repo.bug.value = nfl()
        advance(500)
        assertTrue("the NFL bug took over", nflCount() > nfl0)
        repo.bug.value = nfl(clock = 600)
        advance(500)
        assertEquals("and the NHL bug left", nhl1, nhlCount())
    }

    /** The same seam visible, with the real painters: runs once WP-3 has landed (skipped while its bodies are `TODO()`). */
    @Test
    fun theVisibleSeamWithTheRealPainters() {
        assumeTrue("WP-3 painters and timeline", NflTestSupport.wp3Landed(rule.activity.resources))
        repo.bug.value = nfl()
        val nhl0 = nhlCount()
        rule.mainClock.autoAdvance = false
        rule.setContent { BoxDesignScale { ScoreBug(repo, placement, ScoreBugFixtures.noGeometry, lowEnd = false) } }
        advance(2000)
        repo.bug.value = kcTd()
        advance(5000)
        rule.onNodeWithTag(SCORE_BUG_TAG, useUnmergedTree = true).assertExists()
        assertEquals(0, nhlCount() - nhl0)
    }

    // ------------------------------------------------------------------ the budget

    @Test
    fun oneRecompositionPerSnapshotAndNoneDuringATakeoverOrAPair() {
        startOpen()
        val c0 = nflCount()
        repo.bug.value = nfl(clock = 640)
        advance(100)
        assertEquals("one per snapshot", 1, nflCount() - c0)
        repo.bug.value = kcPair()
        advance(100)
        assertTrue(on())
        val c1 = nflCount()
        advance(8500)
        assertEquals("none while the pair animates", c1, nflCount())
        assertFalse(on())
    }

    @Test
    fun theFirstSnapshotOfAFollowNeverPlays() {
        start(kcTd(seq = 4))
        assertTrue(probe.begins.isEmpty())
        assertFalse(on())
    }

    // ------------------------------------------------------------------ the tab's S2 key (§4.3)

    @Test
    fun aFollowThatStartsWithATabSnapsItOpen() {
        start(nfl())
        assertEquals(listOf(PpTransition.STILL), recs())
        assertTrue(probe.transitions[0].to)
        assertEquals(Side.AWAY, probe.transitions[0].side)
    }

    @Test
    fun aYardLineOrALeadChangeOnTheSameSideIsACutNeverAKick() {
        startOpen()
        assertEquals(listOf(PpTransition.OPEN), recs())
        repo.bug.value = nfl(tab = NflTab(Side.AWAY, TabLead.BALL, TabTail.Field(FieldZone.OPP, 38)))
        advance(300)
        repo.bug.value = nfl(tab = NflTab(Side.AWAY, TabLead.KICKOFF, TabTail.Field(FieldZone.OWN, 35)), down = DownText.Kickoff)
        advance(300)
        assertEquals("no kick", listOf(PpTransition.OPEN), recs())
        assertEquals(TabLead.KICKOFF, tab().lead)
        assertEquals(TabTail.Field(FieldZone.OWN, 35), tab().tail)
        assertEquals("the draw shows the cut", TabLead.KICKOFF, args().content!!.lead)
    }

    @Test
    fun aSideChangeClosesThenOpensEachWithItsOwnWords() {
        startOpen()
        repo.bug.value = nfl(tab = NflTab(Side.HOME, TabLead.BALL, TabTail.Field(FieldZone.OWN, 25)))
        advance(100)
        assertEquals(listOf(PpTransition.OPEN, PpTransition.CLOSE), recs())
        assertEquals("the close slides KC's words out", Side.AWAY, probe.transitions[1].side)
        advance(700)
        assertEquals(listOf(PpTransition.OPEN, PpTransition.CLOSE, PpTransition.OPEN), recs())
        assertEquals(Side.HOME, probe.transitions[2].side)
        assertEquals(TabTail.Field(FieldZone.OWN, 25), probe.transitions[2].tail)
        assertTrue("BUF's colours were begun", probe.tabBegins.contains(Side.HOME))
    }

    @Test
    fun noTabClosesItAndATabNotAllowedClosesIt() {
        startOpen()
        repo.bug.value = nfl(tab = null) // a break, Final, stale: the machine gives no tab
        advance(100)
        assertEquals(listOf(PpTransition.OPEN, PpTransition.CLOSE), recs())
        repo.bug.value = nfl()
        advance(2500)
        allowed = false
        repo.bug.value = nfl(clock = 600)
        advance(100)
        assertEquals(listOf(PpTransition.OPEN, PpTransition.CLOSE, PpTransition.OPEN, PpTransition.CLOSE), recs())
    }

    // ------------------------------------------------------------------ RED ZONE (§4.5)

    @Test
    fun theRedZoneCrossFadesWithoutKickingTheSlide() {
        startOpen()
        repo.bug.value = nfl(tab = NflTab(Side.AWAY, TabLead.BALL, TabTail.RedZone), down = DownText.Down(1, null, goal = true))
        advance(100)
        assertEquals("the slide is not kicked", listOf(PpTransition.OPEN), recs())
        assertEquals(1, probe.reds.size)
        assertFalse("a fade, not a cut", probe.reds[0].cut)
        val mid = probe.rzDriver!!.value
        assertTrue("mid fade: $mid", mid > 0f && mid < 1f)
        assertEquals("the field tail stays under the fade", TabTail.Field(FieldZone.OWN, 35), tab().tail)
        advance(300)
        assertEquals(1f, args().redZone, 0f)
        repo.bug.value = nfl(tab = NflTab(Side.AWAY, TabLead.BALL, TabTail.Field(FieldZone.OPP, 27)))
        advance(400)
        assertEquals("out again", 0f, args().redZone, 0f)
        assertEquals(listOf(PpTransition.OPEN), recs())
    }

    @Test
    fun theRedZoneIsACutWithAnimationsOff() {
        startOpen(FixedScale(0f))
        repo.bug.value = nfl(tab = NflTab(Side.AWAY, TabLead.BALL, TabTail.RedZone))
        advance(100)
        assertTrue(probe.reds.single().cut)
        assertEquals(1f, probe.rzDriver!!.value, 0f)
    }

    // ------------------------------------------------------------------ the pin (§5.4, S2)

    @Test
    fun aTouchdownPinsTheLabelAndReleasesAt3600WithACutAndNoKick() {
        startOpen()
        repo.bug.value = kcTd()
        advance(16, 16)
        assertTrue(on())
        assertTrue(pinned())
        val pre = args()
        assertEquals("the spoiler hold: the words it had", TabTail.Field(FieldZone.OWN, 35), pre.content!!.tail)
        assertEquals("the previous body", "24", pre.body!!.awayScore)
        advanceTakeoverTo(300f)
        val a = args()
        assertEquals(NflScoreKind.TOUCHDOWN, a.content!!.score)
        assertEquals(6, a.content!!.points)
        assertEquals(Side.AWAY, a.content!!.side)
        assertEquals("the pinned label pulses", 0.5f, a.pulse, 0f)
        assertEquals("the view's body once covered", "30", a.body!!.awayScore)
        advanceTakeoverTo(3_570f)
        assertTrue(pinned())
        advanceTakeoverTo(3_700f)
        assertFalse(pinned())
        val r = probe.releases.single()
        assertFalse(r.snap)
        assertTrue("released at the driver's 3 600: ${r.driverMs}", r.driverMs in 3_600f..3_640f)
        assertEquals("no kick at the pin or the release", listOf(PpTransition.OPEN), recs())
        assertEquals("a cut to the try", TabTail.ExtraPoint, tab().tail)
        assertNull(args().content!!.score)
    }

    @Test
    fun aDefensiveTouchdownReleasesWithACutToTheOtherSide() {
        startOpen()
        repo.bug.value = nfl(
            tab = NflTab(Side.HOME, TabLead.BALL, TabTail.ExtraPoint), buf = 23, seq = 1, side = Side.HOME, down = DownText.Pat,
            scores = listOf(NflScore(NflScoreKind.TOUCHDOWN, 6, 24, 23)),
        )
        advanceTakeoverTo(300f)
        assertEquals("the scorer's label", Side.HOME, args().content!!.side)
        advanceTakeoverTo(3_700f)
        assertFalse(pinned())
        assertEquals("a cut, no CLOSE / OPEN", listOf(PpTransition.OPEN), recs())
        assertEquals(Side.HOME, tab().side)
        assertEquals(TabTail.ExtraPoint, tab().tail)
        assertTrue(probe.tabBegins.contains(Side.HOME))
    }

    @Test
    fun aSafetyReleasesWithACutToTheKickingTeam() {
        startOpen()
        repo.bug.value = nfl(
            tab = NflTab(Side.AWAY, TabLead.KICKOFF, TabTail.Field(FieldZone.OWN, 20)), buf = 19, seq = 1, side = Side.HOME,
            down = DownText.Kickoff, scores = listOf(NflScore(NflScoreKind.SAFETY, 2, 24, 19)),
        )
        advanceTakeoverTo(300f)
        assertEquals(Side.HOME, args().content!!.side)
        assertEquals(NflScoreKind.SAFETY, args().content!!.score)
        advanceTakeoverTo(3_700f)
        assertEquals(listOf(PpTransition.OPEN), recs())
        assertEquals(Side.AWAY, tab().side)
        assertEquals(TabLead.KICKOFF, tab().lead)
    }

    private fun labelThenCloseAt3600(v: ScoreBugView, kind: NflScoreKind) {
        startOpen()
        repo.bug.value = v
        advanceTakeoverTo(300f)
        assertEquals(kind, args().content!!.score)
        advanceTakeoverTo(3_500f)
        assertEquals("pinned: no close yet", listOf(PpTransition.OPEN), recs())
        advanceTakeoverTo(3_700f)
        assertEquals(listOf(PpTransition.OPEN, PpTransition.CLOSE), recs())
        assertEquals("the close slides the label out", kind, probe.transitions[1].score)
    }

    @Test
    fun aFieldGoalOnTheHalftimeSnapshotShowsTheLabelThenClosesAt3600() = labelThenCloseAt3600(
        nfl(tab = null, down = null, buf = 20, seq = 1, side = Side.HOME, scores = listOf(NflScore(NflScoreKind.FIELD_GOAL, 3, 24, 20)))
            .copy(top = CentreText.Halftime, bottom = null, clockSecs = null),
        NflScoreKind.FIELD_GOAL,
    )

    @Test
    fun aWalkOffTouchdownOnTheFinalSnapshotShowsTheLabelThenClosesAt3600() = labelThenCloseAt3600(
        nfl(tab = null, down = null, kc = 30, seq = 1, side = Side.AWAY, scores = listOf(NflScore(NflScoreKind.TOUCHDOWN, 6, 30, 17)))
            .copy(top = CentreText.Final(PeriodKind.OT), bottom = null, clockSecs = null),
        NflScoreKind.TOUCHDOWN,
    )

    @Test
    fun aTabThatWasClosingIsNotPinnedAndOpensOnlyAfterTheTakeover() {
        startOpen()
        repo.bug.value = nfl(tab = null)
        advance(100) // the CLOSE is under way: the tab as drawn is not open
        repo.bug.value = kcTd()
        advanceTakeoverTo(300f)
        assertNull("no label on a tab that was not open", args().content?.score)
        advanceTakeoverTo(3_000f)
        assertEquals(listOf(PpTransition.OPEN, PpTransition.CLOSE), recs())
        advanceTakeoverTo(3_700f)
        assertEquals("then the key is evaluated again", listOf(PpTransition.OPEN, PpTransition.CLOSE, PpTransition.OPEN), recs())
    }

    @Test
    fun withTheAnimatorScaleAtTwoTheReleaseIsAtTheDrivers3600() {
        start(nfl(tab = null), FixedScale(2f))
        repo.bug.value = nfl()
        advance(5000)
        repo.bug.value = kcTd()
        advanceTakeoverTo(3_570f)
        assertTrue(pinned())
        advanceTakeoverTo(3_700f)
        assertFalse(pinned())
        assertTrue(probe.releases.single().driverMs in 3_600f..3_640f)
    }

    @Test
    fun hidingMidPinCancelsWithASnapAndNeverReplays() {
        startOpen()
        repo.bug.value = kcTd()
        advanceTakeoverTo(1_000f)
        applyPlacement(NflTestSupport.input().copy(screenOff = true))
        advance(100)
        assertFalse(on())
        assertFalse(pinned())
        assertEquals(listOf(true), probe.releases.map { it.snap })
        advance(1000)
        applyPlacement(NflTestSupport.input())
        advance(5000)
        assertEquals(1, probe.begins.size)
        assertFalse(on())
    }

    // ------------------------------------------------------------------ the still path (ruling 20, §5.6)

    @Test
    fun withAnimationsOffTheStillVersionShowsTheLabelAtFrameAAndReleasesAt3600() {
        startOpen(FixedScale(0f))
        repo.bug.value = kcTd()
        advance(100)
        assertTrue(probe.begins.single().still)
        assertEquals("frame A", 0f, t(), 0f)
        assertEquals("covered from frame A", NflScoreKind.TOUCHDOWN, args().content!!.score)
        assertEquals("30", args().body!!.awayScore)
        advance(3600)
        assertEquals("frame B", 3_600f, t(), 0f)
        assertFalse(pinned())
        advance(1000)
        assertFalse(on())
    }

    // ------------------------------------------------------------------ the §5.7 pair (ruling 25)

    @Test
    fun thePairRunsTwoTakeoversWithTheIntermediateDigitAndTheInBetweenTab() {
        startOpen()
        repo.bug.value = kcPair()
        advance(16, 16)
        assertEquals("the old body before the lead covers", "24", args().body!!.awayScore)
        advanceTakeoverTo(300f)
        assertEquals(NflScoreKind.TOUCHDOWN, args().content!!.score)
        assertEquals("tally A's digit, never the final", "30", args().popDigits)
        advanceTakeoverTo(3_700f)
        assertTrue("still pinned between the two", pinned())
        val between = args()
        assertNull(between.content!!.score)
        assertEquals(TabLead.BALL, between.content!!.lead)
        assertEquals(TabTail.ExtraPoint, between.content!!.tail)
        assertEquals("no pulse between", 0f, between.pulse, 0f)
        assertEquals("30", between.popDigits)
        advanceTakeoverTo(4_600f)
        assertEquals("B begun at 4 500", listOf(0, 1), probe.begins.map { it.segment })
        assertEquals(NflScoreKind.EXTRA_POINT, probe.begins[1].score.kind)
        assertEquals("no final digit before 4 716", "30", args().popDigits)
        assertNull(args().content!!.score)
        advanceTakeoverTo(4_750f)
        assertEquals(NflScoreKind.EXTRA_POINT, args().content!!.score)
        assertEquals(1, args().content!!.points)
        assertNull("the view's digits from B's cover", args().popDigits)
        advanceTakeoverTo(8_000f)
        assertTrue(pinned())
        advanceTakeoverTo(8_200f)
        assertFalse(pinned())
        assertTrue(probe.releases.single().driverMs in 8_100f..8_140f)
        assertEquals("a cut to the kick-off", listOf(PpTransition.OPEN), recs())
        assertEquals(TabLead.KICKOFF, tab().lead)
        advance(1000)
        assertFalse(on())
    }

    @Test
    fun aNewScoreAt5000RestartsWithTheLatest() {
        startOpen()
        repo.bug.value = kcPair()
        advanceTakeoverTo(5_000f)
        repo.bug.value = nfl(
            tab = kcBall35, kc = 34, seq = 2, side = Side.AWAY,
            scores = listOf(NflScore(NflScoreKind.FIELD_GOAL, 3, 34, 17)),
        )
        advance(100)
        assertEquals(3, probe.begins.size)
        assertEquals(NflScoreKind.FIELD_GOAL, probe.begins[2].score.kind)
        assertEquals(0, probe.begins[2].segment)
        assertTrue("restarted: ${t()}", t() < 600f)
        assertTrue("no release on a restart", probe.releases.isEmpty())
        assertTrue(pinned())
        assertNull("a single score has no digit override", args().popDigits)
    }

    @Test
    fun aHideAt2000ClearsThePairWithNoReplay() {
        startOpen()
        repo.bug.value = kcPair()
        advanceTakeoverTo(2_000f)
        applyPlacement(NflTestSupport.input().copy(screenOff = true))
        advance(100)
        assertFalse(on())
        assertFalse(pinned())
        advance(1000)
        applyPlacement(NflTestSupport.input())
        advance(9000)
        assertEquals("no replay of B", 1, probe.begins.size)
    }

    @Test
    fun theStillPairIsFourFrames() {
        startOpen(FixedScale(0f))
        repo.bug.value = kcPair()
        advance(100)
        assertEquals(0f, t(), 0f)
        assertEquals(NflScoreKind.TOUCHDOWN, args().content!!.score)
        advance(3600)
        assertEquals("B1", 3_600f, t(), 0f)
        assertEquals(TabTail.ExtraPoint, args().content!!.tail)
        advance(900)
        assertEquals("A2", 4_500f, t(), 0f)
        assertEquals(NflScoreKind.EXTRA_POINT, args().content!!.score)
        assertNull(args().popDigits)
        advance(3600)
        assertEquals("B2", 8_100f, t(), 0f)
        assertFalse(pinned())
        advance(1000)
        assertFalse(on())
    }

    // ------------------------------------------------------------------ the live region and the description (§8)

    @Test
    fun theLiveRegionSaysTheScoreOnceAndThePairInOneAnnouncement() {
        startOpen()
        assertEquals("", live())
        repo.bug.value = kcTd()
        advance(100)
        assertEquals("Kansas City Chiefs touchdown. Chiefs 30, Bills 17", live())
        repo.bug.value = kcTd().copy(goalSide = null)
        advance(100)
        assertEquals("", live())
        repo.bug.value = kcPair(seq = 2)
        advance(100)
        assertEquals("Kansas City Chiefs touchdown and extra point. Chiefs 31, Bills 17", live())
        repo.bug.value = nfl(tab = NflTab(Side.HOME, TabLead.BALL, TabTail.Field(FieldZone.OWN, 25)), kc = 31)
        advance(100)
        assertEquals("a possession change is not announced", "", live())
    }

    @Test
    fun theDescriptionCarriesThePossessionTheDownAndTheRedZone() {
        start(nfl())
        assertEquals("Chiefs 24, Bills 17, 11:12 left in the 1st. Chiefs ball, 1st and 10.", desc())
        repo.bug.value = nfl(tab = NflTab(Side.AWAY, TabLead.BALL, TabTail.RedZone), down = DownText.Down(1, null, goal = true))
        advance(100)
        assertEquals("Chiefs 24, Bills 17, 11:12 left in the 1st. Chiefs ball, 1st and goal, in the red zone.", desc())
        repo.bug.value = nfl(tab = null, down = null).copy(top = CentreText.Halftime, bottom = null, clockSecs = null)
        advance(100)
        assertEquals("Chiefs 24, Bills 17, halftime", desc())
    }

    private class FixedScale(override val scaleFactor: Float) : MotionDurationScale
}
