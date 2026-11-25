package app.thebox.ui.sports

import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.DayState
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.GameClock
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GameMedia
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.SportsPrefs
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.DetailState
import app.thebox.core.sports.hub.FakeGameChannelMatcher
import app.thebox.core.sports.hub.FakeGameDetailRepository
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.MatchConfidence
import app.thebox.core.sports.hub.MatchReason
import app.thebox.core.sports.Market
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * (Sports Hub WP-4, docs/design/m6-sports-hub.md §10.2 `SportsViewModelHubTest`) OK / Menu / Left into slot 1; the three
 * fixed slots frozen while focus is in the panel; the frozen card order; the panel model's id check; the 600 ms dwell; the
 * strip's keys per league; failure states; the Tune effect without a follow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsViewModelHubTest {
    private val scheduler = TestCoroutineScheduler()
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    @After fun tearDown() = Dispatchers.resetMain()

    private val live = SportsFixtures.game(id = "L1", phase = GamePhase.LIVE, startUtcMs = 3_000)
    private val live2 = SportsFixtures.game(id = "L2", phase = GamePhase.LIVE, startUtcMs = 4_000)
    private val pre = SportsFixtures.game(id = "P1", phase = GamePhase.PRE, startUtcMs = 9_000, period = null, periodKind = null, clock = null)
    private val final = SportsFixtures.game(id = "X1", phase = GamePhase.FINAL, startUtcMs = 1_000, finalKind = PeriodKind.REG)
    private val repo = FakeScoreRepository()
    private val detail = FakeGameDetailRepository()
    private val matcher = FakeGameChannelMatcher()

    private fun vm(
        r: FakeScoreRepository = repo, withMatcher: Boolean = true, mono: () -> Long = { 10_000L },
    ) = SportsViewModel(r, null, mono, { 1_000_000L }, detail, if (withMatcher) matcher else null, null, { null }, { app.thebox.core.parental.ParentalAccess.OPEN }) { LocalDate.of(2026, 11, 5) }

    private fun FakeScoreRepository.board(vararg g: GameSnapshot, day: String = "2026-11-05", at: Long = 0L, counts: Map<String, Int> = emptyMap()) {
        this.day.value = DayState.Board(DayBoard(day, null, null, g.toList(), gameCounts = counts), at, false)
    }

    private fun SportsViewModel.cards() = (state.value.body as SportsBody.Games).cards.map { it.game.id.id }
    private fun SportsViewModel.actions() = panel.value.slots.map { it.action }

    private fun detailOf(g: GameSnapshot, awayScore: Int? = null, homeScore: Int? = null, recapId: Long? = null) = GameDetail(
        id = g.id, fetchedPhase = g.phase, serverDateMs = null, awayScore = awayScore, homeScore = homeScore, venue = null, gameStats = emptyList(),
        season = null, periods = emptyList(), goals = emptyList(), stars = emptyList(), leaders = emptyList(), goalies = null, series = null,
        recapId = recapId, condensedId = null, broadcasts = emptyList(),
    )

    private fun candidate(name: String = "Sportsnet Ontario", confidence: MatchConfidence = MatchConfidence.CONFIRMED, airing: app.thebox.core.guide.ProgrammeInfo? = null) = ChannelCandidate(
        channel = GuideFixtures.channel(1, name = name), playlistName = "Main", confidence = confidence,
        reasons = listOf(MatchReason.Broadcaster("SN", Market.NATIONAL, "CA")), variants = emptyList(), airing = airing,
    )

    // ------------------------------------------------------------------ keys: OK / Menu / Left enter slot 1

    @Test fun enteringThePanelFocusesSlotOneOfThatCardsGameInEitherColumn() {
        val m = vm()
        repo.board(live, live2, pre)
        for (id in listOf("L1", "L2", "P1")) {
            m.onCardFocused(id)
            assertEquals("slot 1 (Follow) of $id", 0, m.enterPanel(id))
            assertEquals(id, m.panel.value.game?.id?.id)
            assertTrue(m.panelFocused.value)
            m.onPanelExited()
            assertFalse(m.panelFocused.value)
        }
    }

    @Test fun aGameWithNoEnabledSlotDoesNothing() {
        val m = vm()
        val ppd = SportsFixtures.game(id = "PP", phase = GamePhase.POSTPONED, startUtcMs = 5_000, period = null, periodKind = null, clock = null)
        repo.board(ppd)
        m.onCardFocused("PP")
        assertNull(m.enterPanel("PP"))
        assertFalse(m.panelFocused.value)
        assertNull(m.panel.value.firstEnabled)
        assertTrue(m.actions().all { it == null })
    }

    // ------------------------------------------------------------------ the three fixed slots

    @Test fun theSlotsOfEveryPhaseAreTheFixedThree() {
        val m = vm()
        val nflFinal = SportsFixtures.nflGame(id = "NF", phase = GamePhase.FINAL, detail = null)
        repo.board(live, pre, final)
        m.onCardFocused("L1")
        assertEquals(listOf(SlotAction.FOLLOW, SlotAction.CHANNEL, SlotAction.GOALS), m.actions())
        m.onCardFocused("P1")
        assertEquals(listOf(SlotAction.FOLLOW, SlotAction.CHANNEL, SlotAction.MATCHUP), m.actions())
        m.onCardFocused("X1")
        assertEquals(listOf(SlotAction.WATCH_RECAP, SlotAction.GOALS, SlotAction.BOX_SCORE), m.actions())
        assertEquals("recap not posted yet: disabled", SlotLabel.RECAP_SOON, m.panel.value.slots[0].label)
        assertFalse(m.panel.value.slots[0].enabled)
        // NFL: Follow · Channel · (empty); final: nothing without a catch-up airing.
        val nflLive = SportsFixtures.nflGame(id = "N1")
        repo.prefs.value = SportsPrefs(league = League.NFL)
        repo.day.value = DayState.Board(DayBoard("w2026-2-9", "w2026-2-8", "w2026-2-10", listOf(nflLive, nflFinal)), 0L, false)
        val n = vm()
        n.onCardFocused("N1")
        assertEquals(listOf(SlotAction.FOLLOW, SlotAction.CHANNEL, null), n.actions())
        n.onCardFocused("NF")
        assertEquals(listOf<SlotAction?>(null, null, null), n.actions())
        assertNull("the footer shows no OK", n.panel.value.firstEnabled)
    }

    @Test fun aFinalNflOrChlGameOffersWatchFromStartOnlyWithACatchUpAiring() {
        repo.prefs.value = SportsPrefs(league = League.NFL)
        val nflFinal = SportsFixtures.nflGame(id = "NF", phase = GamePhase.FINAL, detail = null)
        repo.day.value = DayState.Board(DayBoard("w2026-2-9", "w2026-2-8", "w2026-2-10", listOf(nflFinal)), 0L, false)
        matcher.answers[nflFinal.id] = listOf(candidate(airing = GuideFixtures.info(100, 280)))
        val m = vm()
        m.onCardFocused("NF")
        assertEquals(0, m.enterPanel("NF"))
        assertEquals(listOf(SlotAction.WATCH_FROM_START, null, null), m.actions())
        val effects = collect(m)
        m.onSlot(0)
        assertTrue(effects.single() is SportsEffect.PlayArchive)
    }

    @Test fun theSlotSetIsFrozenWhileFocusIsInThePanelAndThawsAfter() {
        val m = vm()
        repo.board(live, pre)
        m.onCardFocused("L1")
        m.enterPanel("L1")
        val before = m.actions()
        assertEquals(listOf(SlotAction.FOLLOW, SlotAction.CHANNEL, SlotAction.GOALS), before)
        // The game ends while focus is in its panel: Follow stays in slot 1, working; nothing moves.
        repo.board(live.copy(phase = GamePhase.FINAL, finalKind = PeriodKind.REG, clock = null), pre)
        assertEquals(before, m.actions())
        assertEquals(SlotLabel.FOLLOW, m.panel.value.slots[0].label)
        m.onSlot(0)
        assertEquals("L1", repo.followed.value?.gameId)
        assertEquals("the label updates in place", SlotLabel.FOLLOWING, m.panel.value.slots[0].label)
        // Focus leaves: the next entry shows the final's slots.
        m.onPanelExited()
        assertEquals(listOf(SlotAction.WATCH_RECAP, SlotAction.GOALS, SlotAction.BOX_SCORE), m.actions())
    }

    @Test fun aMatcherResultUpdatesTheLabelInPlaceWithoutMovingTheAction() {
        val m = vm()
        repo.board(live)
        m.onCardFocused("L1")
        matcher.answers[live.id] = listOf(candidate(), candidate("Sportsnet West", MatchConfidence.POSSIBLE))
        m.enterPanel("L1") // the match runs at once (OK skips the dwell)
        assertEquals(1, matcher.matchCalls.size)
        val ch = m.panel.value.slots[1]
        assertEquals(SlotAction.CHANNEL, ch.action)
        assertEquals(SlotLabel.CHANNELS_N, ch.label)
        assertEquals(2, ch.count)
        assertTrue(ch.enabled)
    }

    @Test fun watchTunesTheOneConfirmedChannelAndDoesNotFollow() {
        val m = vm()
        repo.board(live)
        matcher.answers[live.id] = listOf(candidate())
        val effects = collect(m)
        m.onCardFocused("L1")
        m.enterPanel("L1")
        assertEquals(SlotLabel.WATCH, m.panel.value.slots[1].label)
        m.onSlot(1)
        assertEquals(1, effects.size)
        assertEquals("Sportsnet Ontario", (effects.single() as SportsEffect.Tune).channel.displayName)
        assertTrue("picking a channel never follows", repo.followCalls.isEmpty())
        assertNull(m.sheet.value)
    }

    @Test fun severalChannelsOpenTheSheetAndAnUpcomingGameNeverTunesFromWatch() {
        val m = vm()
        repo.board(live, pre)
        matcher.answers[live.id] = listOf(candidate(), candidate("TVA Sports", MatchConfidence.LIKELY))
        m.onCardFocused("L1")
        m.enterPanel("L1")
        m.onSlot(1)
        assertEquals(HubSheet.Channels(live.id, archiveOnly = false), m.sheet.value)
        m.closeSheet()
        m.onPanelExited()
        // Pre-game, more than 30 minutes away (the clock is 1 000 000 ms, the game starts at 9 000: already past) -> use a far game.
        val far = pre.copy(id = GameId(League.NHL, "P2"), startUtcMs = 1_000_000L + 3 * 3_600_000L)
        repo.board(far)
        matcher.answers[far.id] = listOf(candidate())
        m.onCardFocused("P2")
        m.enterPanel("P2")
        assertEquals("a game hours away: Channels (1) opens the sheet", SlotLabel.CHANNELS_N, m.panel.value.slots[1].label)
    }

    @Test fun noMatcherMeansADisabledChannelsSlot() {
        val m = vm(withMatcher = false)
        repo.board(live)
        m.onCardFocused("L1")
        assertEquals(SlotLabel.CHANNELS, m.panel.value.slots[1].label)
        assertFalse(m.panel.value.slots[1].enabled)
    }

    // ------------------------------------------------------------------ the panel follows focus; fetches wait

    @Test fun theDetailStartsAfterTheDwellOrAtOnceOnOk() {
        val m = vm()
        repo.board(live, live2)
        m.onCardFocused("L1")
        scheduler.advanceTimeBy(599)
        assertTrue("nothing before the rest", detail.showCalls.isEmpty())
        scheduler.advanceTimeBy(2)
        // Shown twice: the second show() skips the repository's own rest (the page rested already).
        assertEquals(listOf("L1", "L1"), detail.showCalls.map { it?.id?.id })
        // Four quick moves: only the one that rests is fetched.
        m.onCardFocused("L2"); scheduler.advanceTimeBy(200)
        m.onCardFocused("L1"); scheduler.advanceTimeBy(200)
        m.onCardFocused("L2"); scheduler.advanceTimeBy(200)
        assertEquals(2, detail.showCalls.size)
        scheduler.advanceTimeBy(600)
        assertEquals(listOf("L1", "L1", "L2", "L2"), detail.showCalls.map { it?.id?.id })
        // OK shows again at once, with no dwell, even for a game already shown.
        m.enterPanel("L2")
        assertEquals(listOf("L1", "L1", "L2", "L2", "L2", "L2"), detail.showCalls.map { it?.id?.id })
    }

    @Test fun thePanelSwitchesSynchronouslyWithFocusAndANewBoardStartsOnItsInitialGame() {
        val m = vm()
        repo.board(pre, live)
        assertEquals("the board's initial-focus game (the live one)", "L1", m.panel.value.game?.id?.id)
        m.onCardFocused("P1")
        assertEquals("P1", m.panel.value.game?.id?.id)
        m.onCardFocused(null) // focus on a chip: the panel keeps its game
        assertEquals("P1", m.panel.value.game?.id?.id)
        repo.followed.value = SportsFixtures.followedOf(final)
        repo.board(pre, final, live, day = "2026-11-06")
        m.onDay(1)
        repo.board(pre, final, live, day = "2026-11-06")
        assertEquals("a new board starts on the followed game", "X1", m.panel.value.game?.id?.id)
    }

    @Test fun anEmptyBoardShowsAnEmptyPanel() {
        val m = vm()
        repo.board()
        assertNull(m.panel.value.game)
        assertTrue(m.panel.value.slots.all { it.action == null })
    }

    @Test fun theDetailOfAnotherGameIsNeverShown() {
        val m = vm()
        repo.board(live, live2)
        m.onCardFocused("L1")
        detail.detail.value = DetailState.Ready(detailOf(live2, 9, 9), fetchedAtMono = 0L, failing = false, stale = false)
        assertNull("a late answer for another game is dropped", m.panel.value.detail)
        assertEquals(DetailKind.NONE, m.panel.value.detailKind)
        detail.detail.value = DetailState.Ready(detailOf(live), fetchedAtMono = 0L, failing = false, stale = false)
        assertNotNull(m.panel.value.detail)
        assertEquals(DetailKind.READY, m.panel.value.detailKind)
        detail.detail.value = DetailState.Failed(live2.id, FailKind.TIMEOUT)
        assertEquals("a failure of another game is not this panel's", DetailKind.NONE, m.panel.value.detailKind)
    }

    @Test fun theHeadlineScoreIsTheNewerAnswer() {
        val m = vm()
        repo.board(live, at = 5_000L)
        m.onCardFocused("L1")
        detail.detail.value = DetailState.Ready(detailOf(live, 7, 6), fetchedAtMono = 5_000L, failing = false, stale = false)
        assertEquals("a tie takes the board", live.away.score, m.panel.value.awayScore)
        detail.detail.value = DetailState.Ready(detailOf(live, 7, 6), fetchedAtMono = 6_000L, failing = false, stale = false)
        assertEquals(7, m.panel.value.awayScore)
        assertEquals(6, m.panel.value.homeScore)
    }

    @Test fun aFailedDetailPutsRetryInSlotOneAndKeepsTheOthers() {
        val m = vm()
        repo.board(live)
        m.onCardFocused("L1")
        detail.detail.value = DetailState.Failed(live.id, FailKind.TIMEOUT)
        assertEquals(listOf(SlotAction.RETRY, SlotAction.CHANNEL, SlotAction.GOALS), m.actions())
        assertEquals("OK on the card lands on Retry", 0, m.enterPanel("L1"))
        m.onSlot(0)
        assertEquals(1, detail.retries)
        m.onPanelExited()
        detail.detail.value = DetailState.Failed(live.id, FailKind.HTTP_429)
        assertEquals("no Retry while the back-off runs", SlotAction.FOLLOW, m.panel.value.slots[0].action)
    }

    /** (Integration) A final game whose board carries the recap keeps Watch recap in slot 1 when the detail fails. */
    @Test fun aFailedDetailKeepsWatchRecapWhenTheBoardCarriesTheRecap() {
        val m = vm()
        val withRecap = final.copy(media = GameMedia(recapId = 77L, condensedId = null, goalClips = 3))
        repo.board(withRecap)
        m.onCardFocused("X1")
        detail.detail.value = DetailState.Failed(withRecap.id, FailKind.TIMEOUT)
        assertEquals(listOf(SlotAction.WATCH_RECAP, SlotAction.GOALS, SlotAction.BOX_SCORE), m.actions())
        assertTrue("Watch recap stays usable", m.panel.value.slots[0].usable)
        // Without a recap id anywhere, Retry takes slot 1 as the design says.
        repo.board(final)
        detail.detail.value = DetailState.Failed(final.id, FailKind.TIMEOUT)
        assertEquals(SlotAction.RETRY, m.panel.value.slots[0].action)
    }

    @Test fun theLiveDetailWithoutSeasonDataStillOffersItsSlots() {
        // Once the puck drops the detail has no leaders or goalies; the pre-game Matchup slot is then simply disabled.
        val m = vm()
        repo.board(pre)
        m.onCardFocused("P1")
        detail.detail.value = DetailState.Ready(detailOf(pre), fetchedAtMono = 0L, failing = false, stale = false)
        assertFalse(m.panel.value.slots[2].enabled)
        m.onPanelExited()
        val withMedia = live.copy(media = GameMedia(recapId = null, condensedId = null, goalClips = 0))
        repo.board(withMedia)
        m.onCardFocused("L1")
        assertEquals(SlotLabel.GOALS, m.panel.value.slots[2].label)
        assertFalse("no goal has a clip yet", m.panel.value.slots[2].enabled)
        repo.board(withMedia.copy(media = GameMedia(null, null, goalClips = 2)))
        assertEquals(SlotLabel.GOALS_N, m.panel.value.slots[2].label)
        assertTrue(m.panel.value.slots[2].enabled)
    }

    // ------------------------------------------------------------------ order, strips

    @Test fun theCardOrderIsFrozenAcrossRePolls() {
        val m = vm()
        val a = SportsFixtures.game(id = "A", phase = GamePhase.PRE, startUtcMs = 1_000, period = null, periodKind = null, clock = null)
        val b = SportsFixtures.game(id = "B", phase = GamePhase.PRE, startUtcMs = 2_000, period = null, periodKind = null, clock = null)
        val c = SportsFixtures.game(id = "C", phase = GamePhase.FINAL, startUtcMs = 500)
        repo.board(c, b, a)
        assertEquals(listOf("A", "B", "C"), m.cards())
        // B goes live in a re-poll: it would sort first, but the cards keep their places.
        repo.board(c, b.copy(phase = GamePhase.LIVE), a)
        assertEquals(listOf("A", "B", "C"), m.cards())
        // A new game goes last; a game that vanishes drops out.
        val d = SportsFixtures.game(id = "D", phase = GamePhase.LIVE, startUtcMs = 100)
        repo.board(c, b.copy(phase = GamePhase.LIVE), a, d)
        assertEquals(listOf("A", "B", "C", "D"), m.cards())
        repo.board(c, d, a)
        assertEquals(listOf("A", "C", "D"), m.cards())
        // Page entry runs the order again.
        m.onEntered()
        repo.board(c, b.copy(phase = GamePhase.LIVE), a, d)
        assertEquals(listOf("D", "B", "A", "C"), m.cards())
    }

    @Test fun theStripHasFifteenDaysAndDimsOnlyDaysKnownToBeEmpty() {
        val m = vm()
        assertEquals((-7..7).toList(), m.state.value.days.map { it.offset })
        repo.board(live, counts = mapOf("2026-11-03" to 0, "2026-11-04" to 5))
        val days = m.state.value.days
        assertEquals("2026-10-29", days.first().key)
        assertEquals("2026-11-12", days.last().key)
        assertTrue(days.first { it.key == "2026-11-03" }.noGames)
        assertFalse(days.first { it.key == "2026-11-04" }.noGames)
        assertFalse("unknown days are not dimmed", days.first { it.key == "2026-11-08" }.noGames)
        m.onDay(-7)
        assertEquals("2026-10-29", repo.showDayCalls.last())
        m.onDay(7)
        assertEquals("2026-11-12", repo.showDayCalls.last())
    }

    @Test fun aPastDayIsKeptSoSteppingBackDoesNotRefetch() {
        val m = vm()
        m.onEntered()
        repo.board(live)
        m.onDay(-1)
        repo.board(final, day = "2026-11-04")
        assertEquals(listOf("X1"), m.cards())
        val calls = repo.showDayCalls.size
        m.onDay(0)
        assertEquals(calls + 1, repo.showDayCalls.size)
        repo.board(live)
        m.onDay(-1)
        assertEquals("yesterday comes from memory: no request", calls + 1, repo.showDayCalls.size)
        assertEquals(listOf("X1"), m.cards())
    }

    @Test fun theChlStripUsesTheLeaguesOwnDate() {
        val r = FakeScoreRepository(SportsPrefs(league = League.OHL))
        val m = vm(r)
        // 1 000 000 ms is 1970-01-01 in Toronto at 05:00 roll-over: the day before.
        assertEquals("1969-12-31", m.state.value.days.first { it.offset == 0 }.key)
        m.onDay(1)
        assertEquals("1970-01-01", r.showDayCalls.last())
    }

    @Test fun onCardFocusedWithoutARepositoryOrBoardDoesNothing() {
        val m = SportsViewModel(null, null, { 0L }) { LocalDate.of(2026, 11, 5) }
        m.onEntered(); m.onCardFocused("x"); assertNull(m.enterPanel("x")); m.onBoardShown(); m.onLeft()
        assertNull(m.panel.value.game)
    }

    @Test fun leavingThePageClosesTheDetailAndTheSheet() {
        val m = vm()
        repo.board(final)
        m.onEntered()
        m.onCardFocused("X1")
        m.enterPanel("X1")
        m.onSlot(2)
        assertTrue(m.sheet.value is HubSheet.BoxScoreSheet)
        m.onLeft()
        assertNull(m.sheet.value)
        assertNull(detail.showCalls.last())
        assertEquals(clockless(m), false)
    }

    private fun clockless(m: SportsViewModel) = m.panelFocused.value

    private fun collect(m: SportsViewModel): MutableList<SportsEffect> {
        val got = mutableListOf<SportsEffect>()
        CoroutineScope(Dispatchers.Unconfined).launch { m.effects.collect { got += it } }
        return got
    }

    @Suppress("unused") private fun unusedClock() = GameClock(0, false, false)
}
