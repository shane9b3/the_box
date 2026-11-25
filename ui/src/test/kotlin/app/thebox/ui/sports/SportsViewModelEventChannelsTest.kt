package app.thebox.ui.sports

import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.DayState
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.FakeGameChannelMatcher
import app.thebox.core.sports.hub.FakeGameDetailRepository
import app.thebox.core.sports.hub.MatchConfidence
import app.thebox.core.sports.hub.MatchReason
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * (Event channels, docs/design/m6-event-channels.md §8.4, §8.5, §8.6) `SportsViewModel.startMatch`: beyond the guide only
 * `teamChannels` runs and the slot is the grey Channels at once (never "Finding…"); inside it the full match runs with the
 * shown day's games as the board; the user's team channel is row 0 of the sheet; picking a channel tunes only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsViewModelEventChannelsTest {
    private val scheduler = TestCoroutineScheduler()
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    @After fun tearDown() = Dispatchers.resetMain()

    private val now = 1_764_000_000_000L
    private val hour = 3_600_000L
    private val repo = FakeScoreRepository()
    private val matcher = FakeGameChannelMatcher()

    private fun vm() = SportsViewModel(repo, null, { 10_000L }, { now }, FakeGameDetailRepository(), matcher, null, { null }, { ParentalAccess.OPEN }) { LocalDate.of(2026, 11, 24) }

    private fun game(id: String, start: Long, phase: GamePhase = GamePhase.SCHEDULED) = SportsFixtures.game(
        id = id, phase = phase, startUtcMs = start, away = SportsFixtures.team("VGK", "Golden Knights"), home = SportsFixtures.team("EDM", "Oilers"),
        period = null, periodKind = null, clock = null,
    )

    private fun board(vararg g: GameSnapshot) {
        repo.day.value = DayState.Board(DayBoard("2026-11-24", null, null, g.toList()), 0L, false, true)
    }

    private fun team(side: Side) = ChannelCandidate(
        GuideFixtures.channel(if (side == Side.HOME) 1 else 2, name = if (side == Side.HOME) "Edmonton Oilers" else "Vegas Golden Knights"), "Main",
        MatchConfidence.CONFIRMED, listOf(MatchReason.TeamChannel("x", if (side == Side.HOME) "Oilers" else "Golden Knights", side)), emptyList(), null,
    )

    @Test fun beyondTheGuideOnlyTheTeamChannelsAreRead() {
        matcher.guideEnd = now + 24 * hour
        val far = game("F1", now + 30 * hour)
        matcher.teamAnswers[far.id] = listOf(team(Side.HOME), team(Side.AWAY))
        val m = vm()
        board(far)
        m.onCardFocused("F1")
        m.enterPanel("F1")
        assertTrue("no channel search", matcher.matchCalls.isEmpty())
        assertEquals(1, matcher.teamCalls.size)
        val state = m.panel.value.match as MatchState.BeyondGuide
        assertEquals(2, state.teams.size)
        assertFalse(state.noGuide)
        val slot = m.panel.value.slots[1]
        assertEquals(SlotLabel.CHANNELS, slot.label)
        assertFalse("the owner's grey chip", slot.enabled)
    }

    @Test fun withNoGuideTheFloorIsTwelveHours() {
        matcher.guideEnd = null
        val tonight = game("T1", now + 11 * hour)
        val tomorrow = game("T2", now + 13 * hour)
        val m = vm()
        board(tonight, tomorrow)
        m.onCardFocused("T1"); m.enterPanel("T1")
        assertEquals(1, matcher.matchCalls.size)
        assertTrue(m.panel.value.match is MatchState.Done)
        m.onPanelExited()
        m.onCardFocused("T2"); m.enterPanel("T2")
        assertEquals("no second match", 1, matcher.matchCalls.size)
        assertTrue((m.panel.value.match as MatchState.BeyondGuide).noGuide)
    }

    @Test fun theMatchCarriesTheShownDaysBoard() {
        matcher.guideEnd = now + 24 * hour
        val a = game("A1", now + hour)
        val b = game("B1", now + 2 * hour)
        val c = SportsFixtures.game(id = "N1", phase = GamePhase.SCHEDULED, startUtcMs = now + hour).let { it.copy(id = GameId(League.NFL, "N1")) }
        val m = vm()
        board(a, b, c)
        m.onCardFocused("A1"); m.enterPanel("A1")
        assertEquals(1, matcher.boards.size)
        assertEquals("the shown league's games", setOf("A1", "B1"), matcher.boards.single().map { it.id.id }.toSet())
    }

    /** Owner ruling b: Channels (n) for an NHL game, row 0 the user's team channel; OK there tunes and never follows. */
    @Test fun channelsNOpensWithTheUsersTeamFirstAndTunesOnly() {
        matcher.guideEnd = now + 24 * hour
        val g = game("L1", now - 600_000L, GamePhase.LIVE)
        matcher.answers[g.id] = listOf(team(Side.HOME), team(Side.AWAY))
        val effects = mutableListOf<SportsEffect>()
        val m = vm()
        CoroutineScope(Dispatchers.Unconfined).launch { m.effects.collect { effects += it } }
        board(g)
        m.onCardFocused("L1"); m.enterPanel("L1")
        assertEquals(SlotLabel.CHANNELS_N, m.panel.value.slots[1].label)
        assertEquals(2, m.panel.value.slots[1].count)
        assertEquals("Edmonton Oilers", m.panel.value.candidates.first().channel.displayName)
        m.tune(m.panel.value.candidates.first())
        assertEquals("Edmonton Oilers", (effects.single() as SportsEffect.Tune).channel.displayName)
        assertTrue("picking a channel never follows", repo.followCalls.isEmpty())
    }
}
