package app.thebox.ui.sports

import app.thebox.core.full.fakes.FakeArchivePlayback
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.DayState
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.GameMedia
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.LeagueGroup
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.SportsPrefs
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.ClipResult
import app.thebox.core.sports.hub.DetailState
import app.thebox.core.sports.hub.FakeClipResolver
import app.thebox.core.sports.hub.FakeGameChannelMatcher
import app.thebox.core.sports.hub.FakeGameDetailRepository
import app.thebox.core.sports.hub.GameDetail
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * (NFL hub P3, §8.2 `SportsViewModelHubTest` for the NFL) The view model's NFL rules: league routing through `HubLeagues`,
 * the one offer timer (§4.3: the panel rebuilds at `expiresMs − margin`), WATCH_RECAP and `playClips` never starting a
 * clip that is not offered, video off per league, the board's headline score, a failed detail, the toasts' league.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsViewModelNflTest {
    private val scheduler = TestCoroutineScheduler()
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    @After fun tearDown() = Dispatchers.resetMain()

    private val repo = FakeScoreRepository(SportsPrefs(league = League.NFL))
    private val detail = FakeGameDetailRepository()
    private val matcher = FakeGameChannelMatcher()
    private val resolver = FakeClipResolver()
    /** The wall clock: a base plus the scheduler's virtual time, so the offer timer's delay and the clock agree. */
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

    private val g = NflHubTestKit.mnfFinal()

    @Test fun theNflPanelIsADetailedLeague() {
        board(g)
        val m = vm()
        m.onCardFocused(g.id.id)
        assertEquals("no detail yet: NONE, never BOARD_ONLY", DetailKind.NONE, m.panel.value.detailKind)
        detail.detail.value = DetailState.Loading(g.id)
        assertEquals(DetailKind.LOADING, m.panel.value.detailKind)
        detail.detail.value = DetailState.Failed(g.id, FailKind.IO)
        assertEquals(DetailKind.FAILED, m.panel.value.detailKind)
        assertEquals("a retryable failure with nothing cached: Retry", SlotAction.RETRY, m.panel.value.slots[0].action)
        assertNotNull("a slot is always usable", m.panel.value.firstEnabled)
    }

    @Test fun theOfferTimerRebuildsThePanelAtTheHighlightsExpiry() {
        val clip = NflHubTestKit.highlight(expiresMs = base + 3_600_000L)
        board(g)
        ready(NflHubTestKit.mnfFinalDetail(g, clips = listOf(clip)))
        val m = vm()
        m.onCardFocused(g.id.id)
        assertEquals(SlotLabel.HIGHLIGHTS, m.panel.value.slots[0].label)
        assertEquals(0, m.offerTick.value)
        val off = NflHubTestKit.offEnd(clip)
        scheduler.advanceTimeBy(off - now() - 1)
        scheduler.runCurrent()
        assertEquals("not yet", SlotLabel.HIGHLIGHTS, m.panel.value.slots[0].label)
        scheduler.advanceTimeBy(2)
        scheduler.runCurrent()
        assertEquals(1, m.offerTick.value)
        assertEquals("the highlight is no longer offered", SlotLabel.NO_HIGHLIGHTS, m.panel.value.slots[0].label)
        assertFalse(m.panel.value.slots[0].enabled)
        assertTrue((m.panel.value.slots[1]).usable)
        // Nothing else changes: the timer does not spin.
        scheduler.advanceUntilIdle()
        assertEquals(1, m.offerTick.value)
    }

    @Test fun theOfferTimerAlsoWatchesTheCardsBoardHighlights() {
        val other = NflHubTestKit.mnfFinal(media = GameMedia(77L, null, 0, recapExpiresMs = base + 1_800_000L)).let { it.copy(id = it.id.copy(id = "401873099")) }
        board(g, other)
        val m = vm()
        m.onCardFocused(g.id.id)
        scheduler.advanceTimeBy(1_800_000L - app.thebox.core.sports.SportsConstants.HUB_CLIP_EXPIRY_MARGIN_MS + 1)
        scheduler.runCurrent()
        assertEquals("the card chip's expiry ticks too", 1, m.offerTick.value)
        assertFalse(cardHighlights(other, now()))
    }

    @Test fun watchRecapNeverPlaysAClipThatIsNoLongerOffered() {
        val clip = NflHubTestKit.highlight(expiresMs = base + 3_600_000L)
        board(g)
        ready(NflHubTestKit.mnfFinalDetail(g, clips = listOf(clip)))
        resolver.streams[clip.videoId] = FakeClipResolver.okStream(ClipRef(clip.videoId, ClipKind.RECAP, g.id))
        val m = vm(withSession = true)
        m.onCardFocused(g.id.id)
        assertEquals(0, m.enterPanel(g.id.id))
        // The clock passes the expiry before the timer's tick reaches the panel (the slot still reads ▶ Highlights).
        base += 3_600_000L
        m.onSlot(0)
        assertTrue("nothing streamed", resolver.streamCalls.isEmpty())
        // An offered one plays.
        base -= 3_600_000L
        m.onSlot(0)
        assertEquals(listOf(ClipRef(clip.videoId, ClipKind.RECAP, g.id)), resolver.streamCalls)
    }

    @Test fun anExpiredClipFromASheetRowIsTheExpiredToastInEspnWords() {
        val clip = NflHubTestKit.PLAY_CLIPS[0]
        board(g)
        ready(NflHubTestKit.mnfFinalDetail(g, clips = listOf(clip)))
        val m = vm(withSession = true)
        m.onCardFocused(g.id.id)
        base = NflHubTestKit.offEnd(clip)
        m.playClips(listOf(ClipRef(clip.videoId, ClipKind.PLAY, g.id)), 0)
        assertTrue(resolver.streamCalls.isEmpty())
        assertEquals(HubToastKind.VIDEO_EXPIRED, m.toast.value?.kind)
        assertEquals(LeagueGroup.NFL, m.toast.value?.league)
    }

    @Test fun aClipFailureNamesItsLeagueAndPlayNoVideoIsEspns() {
        val clip = NflHubTestKit.highlight()
        board(g)
        ready(NflHubTestKit.mnfFinalDetail(g, clips = listOf(clip)))
        resolver.streams[clip.videoId] = ClipResult.Failed(FailKind.IO)
        val m = vm(withSession = true)
        m.onCardFocused(g.id.id)
        m.playClips(listOf(ClipRef(clip.videoId, ClipKind.RECAP, g.id)), 0)
        scheduler.runCurrent()
        assertEquals(HubToastKind.VIDEO_UNAVAILABLE, m.toast.value?.kind)
        assertEquals(LeagueGroup.NFL, m.toast.value?.league)
        m.playNoVideo()
        assertEquals(HubToastKind.PLAY_NO_VIDEO, m.toast.value?.kind)
        assertEquals(LeagueGroup.NFL, m.toast.value?.league)
    }

    @Test fun videoOffIsPerLeague() {
        // An NHL refusal (its recap lookup) turns NHL video off, never ESPN's.
        val nhl = SportsFixtures.game(id = "X1", phase = GamePhase.FINAL, finalKind = app.thebox.core.sports.PeriodKind.REG, clock = null)
            .copy(media = GameMedia(70L, null, 0))
        repo.prefs.value = SportsPrefs(league = League.NHL)
        repo.day.value = DayState.Board(DayBoard("2026-11-23", null, null, listOf(nhl)), 0L, false, true)
        resolver.infos[70L] = ClipResult.Refused
        val m = vm()
        m.onCardFocused("X1")
        m.enterPanel("X1")
        scheduler.runCurrent()
        assertEquals(setOf(LeagueGroup.NHL), m.videoOffLeagues.value)
        assertTrue(m.videoRefused.value)
        // The NFL panel still offers its highlight.
        m.onPanelExited()
        repo.prefs.value = SportsPrefs(league = League.NFL)
        board(g)
        ready(NflHubTestKit.mnfFinalDetail(g))
        m.onCardFocused(g.id.id)
        assertFalse(m.panel.value.videoOff)
        assertEquals(SlotLabel.HIGHLIGHTS, m.panel.value.slots[0].label)
        // The NHL's flag clears after its re-read time.
        scheduler.advanceTimeBy(app.thebox.core.sports.SportsConstants.HUB_VIDEO_KEY_REREAD_MS + 1)
        scheduler.runCurrent()
        assertTrue(m.videoOffLeagues.value.isEmpty())
    }

    @Test fun theNflHeadlineScoreIsAlwaysTheBoards() {
        val live = NflHubTestKit.mnfLive()
        board(live)
        // A detail that (against the contract) carried a newer score is still not the headline's.
        ready(NflHubTestKit.mnfLiveDetail(live).copy(awayScore = 99, homeScore = 98), at = 50_000L)
        val m = vm()
        m.onCardFocused(live.id.id)
        assertEquals(17, m.panel.value.awayScore)
        assertEquals(13, m.panel.value.homeScore)
    }

    @Test fun theSlotsRouteThroughTheLeaguesHub() {
        val live = NflHubTestKit.mnfLive().let { it.copy(id = it.id.copy(id = "401873080")) }
        val pre = NflHubTestKit.pre()
        board(live, pre, g)
        ready(NflHubTestKit.mnfFinalDetail(g))
        val m = vm()
        m.onCardFocused(live.id.id)
        assertEquals(listOf(SlotAction.FOLLOW, SlotAction.CHANNEL, SlotAction.GOALS), m.panel.value.slots.map { it.action })
        m.onCardFocused(pre.id.id)
        assertEquals(listOf(SlotAction.FOLLOW, SlotAction.CHANNEL, SlotAction.MATCHUP), m.panel.value.slots.map { it.action })
        m.onCardFocused(g.id.id)
        assertEquals(listOf(SlotLabel.HIGHLIGHTS, SlotLabel.SCORING_N, SlotLabel.BOX_SCORE), m.panel.value.slots.map { it.label })
        // Frozen while focus is in the panel: the expired highlight greys slot 1 in place, Scoring and Box score stay usable.
        m.enterPanel(g.id.id)
        base = NflHubTestKit.offEnd(NflHubTestKit.highlight())
        scheduler.advanceUntilIdle()
        assertEquals(SlotLabel.NO_HIGHLIGHTS, m.panel.value.slots[0].label)
        assertEquals(1, m.panel.value.firstEnabled)
        // Scoring opens the scoring sheet.
        m.onSlot(1)
        assertEquals(HubSheet.Goals(g.id), m.sheet.value)
    }
}
