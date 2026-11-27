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

    private fun FakeScoreRepository.board(vararg g: GameSnapshot, day: String = "2026-11-05", at: Long = 0L, counts: Map<String, Int> = emptyMap(), isToday: Boolean = true) {
        this.day.value = DayState.Board(DayBoard(day, null, null, g.toList(), gameCounts = counts), at, false, isToday)
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

    // ------------------------------------------------------------------ today, when the feed's day lags the calendar

    /** (Owner finding 2026-11-25) In the morning `/score/now` still names yesterday: Today asks for the Eastern date. */
    @Test fun aFinishedFeedDayBehindTheCalendarMakesTodayAskForItsDate() {
        val m = vm()
        m.onEntered()
        repo.board(final, day = "2026-11-04") // the feed's "now": yesterday, all over
        assertTrue("never yesterday's games under Today", m.state.value.body is SportsBody.Loading)
        assertEquals("2026-11-05", repo.showDayCalls.last())
        repo.day.value = DayState.Board(DayBoard("2026-11-05", null, null, listOf(pre)), 0L, false, isToday = false)
        assertEquals(listOf("P1"), m.cards())
        assertEquals("2026-11-05", m.state.value.days.first { it.offset == 0 }.key)
        // Yesterday, then OK on Today again: the dated day, not `/score/now`.
        m.onDay(-1)
        m.onDay(0)
        assertEquals("2026-11-05", repo.showDayCalls.last())
    }

    /** After midnight Eastern with a late game still on, the feed's day stays Today. */
    @Test fun aFeedDayBehindTheCalendarWithAGameStillOnStaysToday() {
        val m = vm()
        m.onEntered()
        val calls = repo.showDayCalls.size
        repo.board(live, final, day = "2026-11-04")
        assertEquals(setOf("L1", "X1"), m.cards().toSet())
        assertEquals("2026-11-04", m.state.value.days.first { it.offset == 0 }.key)
        assertEquals("no dated request", calls, repo.showDayCalls.size)
    }

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
        // (NFL hub §2.3, P3) NFL: Follow · Channel · Scoring; final: the grey Highlights soon (no catch-up airing), Scoring,
        // Box score; OK on the card lands on Scoring.
        val nflLive = SportsFixtures.nflGame(id = "N1")
        repo.prefs.value = SportsPrefs(league = League.NFL)
        repo.day.value = DayState.Board(DayBoard("w2026-2-9", "w2026-2-8", "w2026-2-10", listOf(nflLive, nflFinal)), 0L, false)
        val n = vm()
        n.onCardFocused("N1")
        assertEquals(listOf(SlotAction.FOLLOW, SlotAction.CHANNEL, SlotAction.GOALS), n.actions())
        n.onCardFocused("NF")
        assertEquals(listOf(SlotAction.WATCH_RECAP, SlotAction.GOALS, SlotAction.BOX_SCORE), n.actions())
        assertEquals(SlotLabel.HIGHLIGHTS_SOON, n.panel.value.slots[0].label)
        assertEquals("OK lands on Scoring", 1, n.panel.value.firstEnabled)
    }

    /**
     * (Code review 2026-11-26, UI m11) The CHL's final rules, unchanged from `main` (`ChlHubUi.slots`): no catch-up airing,
     * no slot at all and OK on the card does nothing; a catch-up airing, ▶ Watch from start alone.
     */
    @Test fun aFinalChlGameWithTheHubSwitchedOffOffersWatchFromStartOnlyWithACatchUpAiring() {
        // The kill switch's off position, stated explicitly (the build default is on since the CHL hub integration).
        ChlHubUi.enabled = false
        try {
            val r = FakeScoreRepository(SportsPrefs(league = League.OHL))
            val chlFinal = SportsFixtures.game(id = "C1", phase = GamePhase.FINAL, league = League.OHL, finalKind = PeriodKind.REG, clock = null)
            r.board(chlFinal, day = "1969-12-31")
            val m = vm(r)
            m.onCardFocused("C1")
            assertEquals(listOf<SlotAction?>(null, null, null), m.actions())
            assertNull("nothing to enter", m.enterPanel("C1"))
            // With a catch-up airing: Watch from start, and nothing else.
            matcher.answers[chlFinal.id] = listOf(candidate(airing = GuideFixtures.info(100, 280)))
            val n = vm(r)
            n.onCardFocused("C1")
            assertEquals(0, n.enterPanel("C1"))
            assertEquals(listOf<SlotAction?>(SlotAction.WATCH_FROM_START, null, null), n.actions())
            val effects = collect(n)
            n.onSlot(0)
            assertTrue(effects.single() is SportsEffect.PlayArchive)
        } finally {
            ChlHubUi.enabled = app.thebox.core.sports.SportsConstants.CHL_HUB_ENABLED
        }
    }

    @Test fun aFinalChlGameWithTheHubOnOffersGoalsAndBoxScoreAndWatchFromStartFirstWithACatchUpAiring() {
        // (CHL hub §2.4, Q7) The build default: Goals · Box score · empty; with a catch-up airing ▶ From start first.
        assertTrue(ChlHubUi.enabled)
        val r = FakeScoreRepository(SportsPrefs(league = League.OHL))
        val chlFinal = SportsFixtures.game(id = "C1", phase = GamePhase.FINAL, league = League.OHL, finalKind = PeriodKind.REG, clock = null)
        r.board(chlFinal, day = "1969-12-31")
        val m = vm(r)
        m.onCardFocused("C1")
        assertEquals(listOf(SlotAction.GOALS, SlotAction.BOX_SCORE, null), m.actions())
        matcher.answers[chlFinal.id] = listOf(candidate(airing = GuideFixtures.info(100, 280)))
        val n = vm(r)
        n.onCardFocused("C1")
        assertEquals(0, n.enterPanel("C1"))
        assertEquals(listOf(SlotAction.WATCH_FROM_START, SlotAction.GOALS, SlotAction.BOX_SCORE), n.actions())
    }

    @Test fun aFinalNflGameOffersWatchFromStartOnlyWithACatchUpAiring() {
        repo.prefs.value = SportsPrefs(league = League.NFL)
        val nflFinal = SportsFixtures.nflGame(id = "NF", phase = GamePhase.FINAL, detail = null)
        repo.day.value = DayState.Board(DayBoard("w2026-2-9", "w2026-2-8", "w2026-2-10", listOf(nflFinal)), 0L, false)
        matcher.answers[nflFinal.id] = listOf(candidate(airing = GuideFixtures.info(100, 280)))
        val m = vm()
        m.onCardFocused("NF")
        assertEquals(0, m.enterPanel("NF"))
        // (NFL hub §2.3, P3) ▶ From start, then Scoring and Box score.
        assertEquals(listOf(SlotAction.WATCH_FROM_START, SlotAction.GOALS, SlotAction.BOX_SCORE), m.actions())
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
        repo.board(final, day = "2026-11-04", isToday = false) // a dated day, as the repository marks it
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

    // ------------------------------------------------------------------ review 2026-11-25 (M1, M3, M7, L1, L3)

    /** (Review M1) The sheet's game leaves the board: the sheet closes and focus leaves the panel (the screen moves it to a card). */
    @Test fun aSheetWhoseGameLeavesTheBoardClosesAndThePanelThaws() {
        val m = vm()
        repo.board(final, live)
        m.onCardFocused("X1")
        m.enterPanel("X1")
        m.onSlot(2)
        assertTrue(m.sheet.value is HubSheet.BoxScoreSheet)
        repo.board(live)
        assertNull("the sheet closed with its game", m.sheet.value)
        assertFalse("focus left the panel", m.panelFocused.value)
        assertEquals("L1", m.panel.value.game?.id?.id)
    }

    /** (Review M3) An empty day after resting on a live game: the old game's detail and match stop. */
    @Test fun anEmptyBoardStopsTheDetailOfTheGameNoLongerShown() {
        val m = vm()
        repo.board(live)
        m.onCardFocused("L1")
        scheduler.advanceTimeBy(601)
        assertEquals("L1", detail.showCalls.last()?.id?.id)
        repo.board(day = "2026-11-05")
        assertNull("the repository is told nothing is shown", detail.showCalls.last())
        val calls = detail.showCalls.size
        repo.board(day = "2026-11-05")
        assertEquals("said once", calls, detail.showCalls.size)
    }

    /** (Review M7) A pre-game game going live under the panel: the repository sees the new phase and the match runs again. */
    @Test fun aPhaseChangeOfTheShownGameReachesTheRepositoryAndTheMatcher() {
        val m = vm()
        repo.board(pre)
        m.onCardFocused("P1")
        scheduler.advanceTimeBy(601)
        assertEquals(1, matcher.matchCalls.size)
        val shows = detail.showCalls.size
        val nowLive = pre.copy(phase = GamePhase.LIVE, period = 1, periodKind = PeriodKind.REG, clock = GameClock(1200, true, false))
        repo.board(nowLive)
        assertEquals("one show, no rest", shows + 1, detail.showCalls.size)
        assertEquals(GamePhase.LIVE, detail.showCalls.last()?.phase)
        assertEquals("the match ran again", 2, matcher.matchCalls.size)
        repo.board(nowLive)
        assertEquals("an unchanged phase shows nothing more", shows + 1, detail.showCalls.size)
    }

    /** A card focused in passing while a sheet or the panel holds focus never changes the panel's game. */
    @Test fun aCardFocusedWhileThePanelHoldsFocusChangesNothing() {
        val m = vm()
        repo.board(live, live2)
        m.onCardFocused("L1")
        m.enterPanel("L1")
        m.onCardFocused("L2")
        assertEquals("L1", m.panel.value.game?.id?.id)
        assertTrue(m.panelFocused.value)
    }

    /** (Review L1) The trim keeps the shown game's match, so its Channels slot does not fall back to "Finding…". */
    @Test fun aMemoryTrimKeepsTheShownGamesMatch() {
        val m = vm()
        repo.board(live)
        matcher.answers[live.id] = listOf(candidate(), candidate("TVA Sports", MatchConfidence.LIKELY))
        m.onCardFocused("L1")
        m.enterPanel("L1")
        assertEquals(SlotLabel.CHANNELS_N, m.panel.value.slots[1].label)
        m.onTrimMemory()
        assertEquals(1, detail.trims)
        assertEquals(SlotLabel.CHANNELS_N, m.panel.value.slots[1].label)
    }

    /** (Review L3) A re-emission with only a later clock is the same panel: the state flow does not emit it. */
    @Test fun thePanelIsNotRebuiltForAClockTick() {
        var now = 1_000_000L
        val m = SportsViewModel(repo, null, { 10_000L }, { now }, detail, matcher, null, { null }, { app.thebox.core.parental.ParentalAccess.OPEN }) { LocalDate.of(2026, 11, 5) }
        repo.board(live)
        val before = m.panel.value
        now += 5_000L
        repo.board(live) // a re-poll with the same snapshot
        assertTrue("the same instance: nothing re-emitted", before === m.panel.value || before == m.panel.value)
        assertEquals(before, m.panel.value)
    }

    /**
     * (Verification review L-c) UI_HIDDEN alone drops nothing; a trim under pressure while hidden makes no clip lookup;
     * a trim on screen clears the lookups and asks again.
     */
    @Suppress("DEPRECATION")
    @Test fun aTrimWhileHiddenMakesNoLookupAndUiHiddenAloneDropsNothing() {
        val resolver = app.thebox.core.sports.hub.FakeClipResolver()
        val withRecap = final.copy(media = GameMedia(recapId = 77L, condensedId = null, goalClips = 0))
        repo.board(withRecap)
        val m = SportsViewModel(repo, null, { 10_000L }, { 1_000_000L }, detail, matcher, resolver, { null }, { app.thebox.core.parental.ParentalAccess.OPEN }) { LocalDate.of(2026, 11, 5) }
        m.onCardFocused("X1")
        m.enterPanel("X1")
        assertEquals("the recap's lookup", 1, resolver.infoCalls.size)
        m.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, uiHidden = true)
        assertEquals("UI_HIDDEN is not memory pressure: the repository keeps its facts", 0, detail.trims)
        m.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, uiHidden = true)
        assertEquals(1, detail.trims)
        assertEquals("no lookup in the background", 1, resolver.infoCalls.size)
        m.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, uiHidden = false)
        assertEquals(2, detail.trims)
        assertEquals("on screen the lookups run again", 2, resolver.infoCalls.size)
    }

    /**
     * (Owner bug 2026-11-25) The condensed game's row had no thumbnail or duration: its lookup was never made. It is made
     * when the final game's Goals sheet opens (not while browsing), and its answer lands in `clipInfo`.
     */
    @Test fun theCondensedGameIsLookedUpWhenTheGoalsSheetOpens() {
        val resolver = app.thebox.core.sports.hub.FakeClipResolver()
        val g = final.copy(media = GameMedia(recapId = 77L, condensedId = 88L, goalClips = 2))
        val condensed = app.thebox.core.sports.hub.ClipRef(88L, app.thebox.core.sports.hub.ClipKind.CONDENSED, g.id)
        resolver.infos[88L] = app.thebox.core.sports.hub.ClipResult.Ok(app.thebox.core.sports.hub.ClipInfo(condensed, "Condensed game", null, 600_000L, null, null))
        repo.board(g)
        val m = SportsViewModel(repo, null, { 10_000L }, { 1_000_000L }, detail, matcher, resolver, { null }, { app.thebox.core.parental.ParentalAccess.OPEN }) { LocalDate.of(2026, 11, 5) }
        m.onCardFocused("X1")
        m.enterPanel("X1")
        assertTrue("browsing: no condensed lookup", resolver.infoCalls.none { it.videoId == 88L })
        val goals = m.panel.value.slots.indexOfFirst { it.action == SlotAction.GOALS }
        assertTrue(goals >= 0 && m.panel.value.slots[goals].usable)
        m.onSlot(goals)
        assertEquals(HubSheet.Goals(g.id), m.sheet.value)
        assertEquals(listOf(condensed), resolver.infoCalls.filter { it.videoId == 88L })
        assertEquals(600_000L, m.clipInfo.value[88L]?.durationMs)
        m.onSlot(goals)
        assertEquals("one lookup", 1, resolver.infoCalls.count { it.videoId == 88L })
    }

    /** (Verification review L-d) The NFL day word reads the local date: a build on the next day is a different panel. */
    @Test fun thePanelChangesAtMidnightButNotWithinTheDay() {
        val zone = java.time.ZoneId.systemDefault()
        val midnight = java.time.LocalDate.of(2026, 11, 26).atStartOfDay(zone).toInstant().toEpochMilli()
        val a = HubPanelModel(game = pre, nowMs = midnight - 3_600_000L)
        assertEquals("the same day, an hour apart", a, HubPanelModel(game = pre, nowMs = midnight - 60_000L))
        assertFalse("the next day", a == HubPanelModel(game = pre, nowMs = midnight + 60_000L))
    }

    /** (Verification review MED-1) Matchup is enabled exactly when its sheet has rows: two empty goalie lists draw none. */
    @Test fun matchupIsEnabledOnlyWithRowsToShow() {
        val m = vm()
        repo.board(pre)
        m.onCardFocused("P1")
        val empty = detailOf(pre).copy(goalies = app.thebox.core.sports.hub.GoaliePair(emptyList(), emptyList()))
        detail.detail.value = DetailState.Ready(empty, fetchedAtMono = 0L, failing = false, stale = false)
        assertEquals(SlotAction.MATCHUP, m.panel.value.slots[2].action)
        assertFalse("no rows: disabled", m.panel.value.slots[2].enabled)
        assertTrue(matchupRows(empty).isEmpty())
        val one = empty.copy(goalies = app.thebox.core.sports.hub.GoaliePair(listOf(app.thebox.core.sports.hub.Goalie("Vejmelka", "8-4-1", 2.41, 0.915, null)), emptyList()))
        detail.detail.value = DetailState.Ready(one, fetchedAtMono = 0L, failing = false, stale = false)
        assertTrue(m.panel.value.slots[2].enabled)
        assertTrue(matchupRows(one).isNotEmpty())
    }

    /** (Verification review HIGH-1) The Goals slot is usable from the board's count with no detail: it opens the sheet. */
    @Test fun goalsOpensFromTheBoardCountWhileTheDetailLoads() {
        val m = vm()
        val withGoals = live.copy(media = GameMedia(null, null, goalClips = 2))
        repo.board(withGoals)
        m.onCardFocused("L1")
        detail.detail.value = DetailState.Loading(withGoals.id)
        m.enterPanel("L1")
        assertTrue(m.panel.value.slots[2].usable)
        m.onSlot(2)
        assertEquals(HubSheet.Goals(withGoals.id), m.sheet.value)
    }

    private fun clockless(m: SportsViewModel) = m.panelFocused.value

    private fun collect(m: SportsViewModel): MutableList<SportsEffect> {
        val got = mutableListOf<SportsEffect>()
        CoroutineScope(Dispatchers.Unconfined).launch { m.effects.collect { got += it } }
        return got
    }

    @Suppress("unused") private fun unusedClock() = GameClock(0, false, false)
}
