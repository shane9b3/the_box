package app.thebox.ui.sports

import app.thebox.core.full.fakes.FakeArchivePlayback
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.DayState
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.LeagueGroup
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.SportsPrefs
import app.thebox.core.sports.hub.DetailState
import app.thebox.core.sports.hub.FakeClipResolver
import app.thebox.core.sports.hub.FakeGameChannelMatcher
import app.thebox.core.sports.hub.FakeGameDetailRepository
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.Leader
import app.thebox.core.sports.hub.LeaderPair
import app.thebox.ui.shelltest.FakePlaybackController
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * (NFL hub code review 2026-11-26, UI half) The view model's fixes: the pre-game-only Matchup and the re-frozen slot set at
 * kick-off (M1), the caption windows on the one offer timer (m1), an embargoed highlight read as "soon" (m2), the toast
 * for a frozen ▶ Highlights whose clip just expired (m3), the Matchup slot enabled only with a focusable row (m5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsViewModelNflReviewTest {
    private val scheduler = TestCoroutineScheduler()
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    @After fun tearDown() = Dispatchers.resetMain()

    private val repo = FakeScoreRepository(SportsPrefs(league = League.NFL))
    private val detail = FakeGameDetailRepository()
    private val matcher = FakeGameChannelMatcher()
    private val resolver = FakeClipResolver()
    private var base = NflHubTestKit.AFTER_FINAL
    private val now: () -> Long = { base + scheduler.currentTime }

    private fun vm(withSession: Boolean = false) = SportsViewModel(
        repo, null, { 10_000L }, now, detail, matcher, resolver,
        { scope -> if (withSession) ClipSession(FakePlaybackController(), FakeArchivePlayback(), resolver, scope) else null },
        { app.thebox.core.parental.ParentalAccess.OPEN },
    ) { LocalDate.of(2026, 11, 23) }

    private fun board(vararg g: GameSnapshot) {
        repo.day.value = DayState.Board(DayBoard("w2026-2-12", "w2026-2-11", "w2026-2-13", g.toList()), 0L, false, true)
    }

    private fun ready(d: GameDetail, at: Long = 20_000L) {
        detail.detail.value = DetailState.Ready(d, at, failing = false, stale = false)
    }

    private fun SportsViewModel.actions() = panel.value.slots.map { it.action }

    /** The GB @ LAR live summary's leaders (§18.4): tonight's numbers, a rusher at "3 CAR, - YDS" (−8 yards). */
    private val liveLeaders = listOf(
        LeaderPair("passingYards", Leader("J. Love", 74, null, "7/12, 74 YDS"), Leader("M. Stafford", 188, null, "14/19, 188 YDS, 1 TD")),
        LeaderPair("rushingYards", Leader("J. Jacobs", -8, null, "3 CAR, - YDS"), Leader("K. Williams", 41, null, "9 CAR, 41 YDS")),
    )

    @Test fun matchupIsPreGameOnlyAndTheFrozenSetIsReEvaluatedAtKickoff() {
        val pre = NflHubTestKit.pre()
        board(pre)
        ready(NflHubTestKit.preDetail(pre))
        base = NflHubTestKit.PRE_START - 600_000L
        val m = vm()
        m.onCardFocused(pre.id.id)
        assertEquals(0, m.enterPanel(pre.id.id))
        assertEquals(listOf(SlotAction.FOLLOW, SlotAction.CHANNEL, SlotAction.MATCHUP), m.actions())
        assertTrue(m.panel.value.slots[2].usable)
        m.onSlot(2)
        assertEquals(HubSheet.Matchup(pre.id), m.sheet.value)
        // Kick-off: the board turns LIVE and the detail becomes the live summary (game leaders, game-day inactives).
        val live = pre.copy(phase = GamePhase.LIVE)
        board(live)
        ready(NflHubTestKit.nflDetail(live, leaders = liveLeaders, injuries = NflHubTestKit.PRE_INJURIES))
        assertNull("the Matchup sheet closed itself", m.sheet.value)
        assertTrue("focus stays in the panel", m.panelFocused.value)
        assertEquals("re-frozen to the live set", listOf(SlotAction.FOLLOW, SlotAction.CHANNEL, SlotAction.GOALS), m.actions())
        // And frozen again from here: a later phase change re-evaluates once more (live → final).
        board(live.copy(phase = GamePhase.FINAL))
        assertEquals(listOf(SlotAction.GOALS, SlotAction.BOX_SCORE), m.actions().drop(1))
        // Live, a Matchup slot (a frozen set of another league's shape) is never usable, whatever the detail lists.
        val ctx = HubSlots.Ctx(live, false, NflHubTestKit.nflDetail(live, leaders = liveLeaders), null, MatchState.Idle, false, now())
        assertFalse(HubSlots.forAction(SlotAction.MATCHUP, ctx).enabled)
        m.onPanelExited()
    }

    /** The NHL (and CHL) keep their frozen set through a phase change, as before (`SportsViewModelHubTest`). */
    @Test fun onlyTheNflReFreezes() {
        assertTrue(HubLeagues.of(LeagueGroup.NFL).refreezeOnPhaseChange)
        assertFalse(HubLeagues.of(LeagueGroup.NHL).refreezeOnPhaseChange)
        assertFalse(HubLeagues.of(LeagueGroup.CHL).refreezeOnPhaseChange)
        assertTrue(HubLeagues.of(LeagueGroup.NHL).matchupOpen(NflHubTestKit.mnfLive()))
    }

    /** (m1) Highlights soon → No highlights at ended + 2 h, then the 48 h caption change: each on the one timer. */
    @Test fun theHighlightWindowsAreOnTheOfferTimer() {
        val g = NflHubTestKit.mnfFinal()
        val ended = NflHubTestKit.MNF_START + SportsConstants.HUB_NFL_GAME_LENGTH_MS
        base = ended + 3_600_000L
        board(g)
        ready(NflHubTestKit.mnfFinalDetail(g, clips = emptyList()))
        val m = vm()
        m.onCardFocused(g.id.id)
        assertEquals(SlotLabel.HIGHLIGHTS_SOON, m.panel.value.slots[0].label)
        val soonEnds = ended + SportsConstants.HUB_NFL_HIGHLIGHT_WAIT_MS
        assertEquals(soonEnds, NflHubUi.nextOfferChange(g, m.panel.value.detail, now(), 0L))
        scheduler.advanceTimeBy(soonEnds - now() - 1)
        scheduler.runCurrent()
        assertEquals("not yet", SlotLabel.HIGHLIGHTS_SOON, m.panel.value.slots[0].label)
        scheduler.advanceTimeBy(2)
        scheduler.runCurrent()
        assertEquals(1, m.offerTick.value)
        assertEquals(SlotLabel.NO_HIGHLIGHTS, m.panel.value.slots[0].label)
        // The 48 h caption change ("ESPN didn't post…" → "ESPN removes videos…") ticks the model too.
        val removedAt = ended + SportsConstants.HUB_CLIP_DEFAULT_LIFE_MS
        assertEquals(removedAt, NflHubUi.nextOfferChange(g, m.panel.value.detail, now(), 0L))
        scheduler.advanceTimeBy(removedAt - now() + 1)
        scheduler.runCurrent()
        assertEquals(2, m.offerTick.value)
        assertNull("nothing left to wait for", NflHubUi.nextOfferChange(g, m.panel.value.detail, now(), 0L))
        scheduler.advanceUntilIdle()
        assertEquals("the timer does not spin", 2, m.offerTick.value)
    }

    /** (m2, data #3) A highlight under embargo reads Highlights soon (never "removed"), and plays when the embargo lifts. */
    @Test fun anEmbargoedHighlightIsSoonThenOffered() {
        val g = NflHubTestKit.mnfFinal()
        val lift = base + 600_000L
        board(g)
        ready(NflHubTestKit.mnfFinalDetail(g, clips = listOf(NflHubTestKit.highlight(embargoMs = lift))))
        val m = vm()
        m.onCardFocused(g.id.id)
        assertEquals(SlotLabel.HIGHLIGHTS_SOON, m.panel.value.slots[0].label)
        scheduler.advanceTimeBy(lift - now())
        scheduler.runCurrent()
        assertEquals(SlotLabel.HIGHLIGHTS, m.panel.value.slots[0].label)
        assertTrue(m.panel.value.slots[0].usable)
    }

    /** (m3) A frozen ▶ Highlights whose clip expired a moment ago: the "ESPN removed this video" toast, nothing streamed. */
    @Test fun aFrozenHighlightThatJustExpiredSaysSo() {
        val g = NflHubTestKit.mnfFinal()
        val clip = NflHubTestKit.highlight(expiresMs = base + 3_600_000L)
        board(g)
        ready(NflHubTestKit.mnfFinalDetail(g, clips = listOf(clip)))
        val m = vm(withSession = true)
        m.onCardFocused(g.id.id)
        assertEquals(0, m.enterPanel(g.id.id))
        base += 3_600_000L
        m.onSlot(0)
        assertTrue(resolver.streamCalls.isEmpty())
        assertEquals(HubToastKind.VIDEO_EXPIRED, m.toast.value?.kind)
        assertEquals(LeagueGroup.NFL, m.toast.value?.league)
    }

    /** (m5) Leaders that draw no row (both sides empty, a category the sheet does not draw) never enable the Matchup slot. */
    @Test fun theMatchupSlotNeedsAFocusableRow() {
        val pre = NflHubTestKit.pre()
        fun slotWith(d: GameDetail) = NflHubUi.matchupSlot(HubSlots.Ctx(pre, false, d, null, MatchState.Idle, false, base))
        val empty = NflHubTestKit.nflDetail(pre)
        assertFalse(slotWith(empty).enabled)
        assertFalse(slotWith(empty.copy(leaders = listOf(LeaderPair("passingYards", null, null)))).enabled)
        assertFalse(slotWith(empty.copy(leaders = listOf(LeaderPair("passingTouchdowns", Leader("J. Love", 20, null, "20"), null)))).enabled)
        assertTrue(slotWith(empty.copy(leaders = listOf(LeaderPair("sacks", null, Leader("B. Young", 5, null, "5.5"))))).enabled)
        assertTrue(slotWith(empty.copy(injuries = NflHubTestKit.PRE_INJURIES.take(1))).enabled)
        assertTrue(slotWith(empty.copy(recent = NflHubTestKit.PRE_RECENT.take(1))).enabled)
    }

    /** (m8) After a clip whose slot went grey, focus goes to the nearest usable slot. */
    @Test fun theNearestUsableSlot() {
        val grey = HubSlot(SlotAction.WATCH_RECAP, SlotLabel.NO_HIGHLIGHTS, false)
        val scoring = HubSlot(SlotAction.GOALS, SlotLabel.SCORING_N, true, 10)
        val box = HubSlot(SlotAction.BOX_SCORE, SlotLabel.BOX_SCORE, true)
        assertEquals(1, nearestUsableSlot(listOf(grey, scoring, box), 0))
        assertEquals(0, nearestUsableSlot(listOf(scoring, grey, box), 0))
        assertEquals("a tie takes the later one", 2, nearestUsableSlot(listOf(scoring, grey, box), 1))
        assertNull(nearestUsableSlot(listOf(grey, HubSlot.EMPTY, HubSlot.EMPTY), 0))
    }
}
