package app.thebox.ui.sports

import androidx.test.core.app.ApplicationProvider
import app.thebox.core.full.fakes.FakeArchivePlayback
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.DayState
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.GameMedia
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.Market
import app.thebox.core.sports.SportsPrefs
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.DetailState
import app.thebox.core.sports.hub.FakeClipResolver
import app.thebox.core.sports.hub.FakeGameChannelMatcher
import app.thebox.core.sports.hub.FakeGameDetailRepository
import app.thebox.core.sports.hub.GameClip
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.MatchConfidence
import app.thebox.core.sports.hub.MatchReason
import app.thebox.core.sports.hub.VideoEntry
import app.thebox.ui.shelltest.FakePlaybackController
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (NFL hub §20, the GB @ LAR defect of 2026-11-26) The board's highlight is a first-class clip, and every surface asks the
 * one offered-highlight question (`NflHighlight.offered` through `NflHubUi.highlight`):
 * - the four surfaces (slot 1, the picture, the card chip, the Scoring sheet's Game highlights row) agree for every
 *   combination of {board highlight present / absent} × {summary highlight present / absent} × {offered / expired /
 *   embargoed}, with a catch-up airing found (so slot 1's alternative is ▶ From start, as on the emulator);
 * - a highlight that appears on the board while focus is in the panel (the slot set frozen) and the Scoring sheet is
 *   open turns slot 1 into ▶ Highlights in place, adds the sheet's row, reaches the repository, and plays; its expiry
 *   turns slot 1 back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NflBoardHighlightTest {
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private val scheduler = TestCoroutineScheduler()
    private var locale: Locale? = null

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        locale?.let { Locale.setDefault(it) }
    }

    private val wide: Measure = { _, _, _ -> 1f }
    private var base = NflHubTestKit.AFTER_FINAL
    private val now: () -> Long = { base + scheduler.currentTime }

    private val catchUp = ChannelCandidate(
        GuideFixtures.channel(1, name = "ESPN"), "Main", MatchConfidence.LIKELY,
        listOf(MatchReason.Broadcaster("ESPN", Market.NATIONAL, "US")), emptyList(), GuideFixtures.info(100, 280),
    )

    private class Run(val vm: SportsViewModel, val board: FakeScoreRepository, val detail: FakeGameDetailRepository, val resolver: FakeClipResolver)

    private fun run(g: GameSnapshot, d: GameDetail): Run {
        val board = FakeScoreRepository(SportsPrefs(league = League.NFL))
        board.day.value = DayState.Board(DayBoard("w2026-2-12", "w2026-2-11", "w2026-2-13", listOf(g)), 0L, false, true)
        val detail = FakeGameDetailRepository()
        detail.detail.value = DetailState.Ready(d, 20_000L, failing = false, stale = false)
        val matcher = FakeGameChannelMatcher().also { it.answers[g.id] = listOf(catchUp) }
        val resolver = FakeClipResolver()
        val vm = SportsViewModel(
            board, null, { 10_000L }, now, detail, matcher, resolver,
            { scope -> ClipSession(FakePlaybackController(), FakeArchivePlayback(), resolver, scope) },
            { app.thebox.core.parental.ParentalAccess.OPEN },
        ) { LocalDate.of(2026, 11, 23) }
        vm.onCardFocused(g.id.id)
        return Run(vm, board, detail, resolver)
    }

    private fun setBoard(r: Run, g: GameSnapshot) {
        r.board.day.value = DayState.Board(DayBoard("w2026-2-12", "w2026-2-11", "w2026-2-13", listOf(g)), 0L, false, true)
    }

    private fun media(c: GameClip) = GameMedia(c.videoId, null, 0, c.expiresMs, VideoEntry(c, "https://clips.invalid/${c.videoId}/playlist.m3u8"))

    private enum class Offer { OFFERED, EXPIRED, EMBARGOED }

    private fun clipIn(state: Offer): GameClip = when (state) {
        Offer.OFFERED -> NflHubTestKit.highlight()
        Offer.EXPIRED -> NflHubTestKit.highlight(expiresMs = base - 1)
        Offer.EMBARGOED -> NflHubTestKit.highlight(embargoMs = base + 3_600_000L)
    }

    /** The four surfaces, each as the app computes it for the shown game. */
    private data class Surfaces(val slot1: Boolean, val picture: Boolean, val chip: Boolean, val sheetRow: Boolean)

    private fun surfaces(r: Run, g: GameSnapshot): Surfaces {
        val m = r.vm.panel.value
        val d = m.detail
        val slot = m.slots[0]
        val content = HubPanelContents.build(res, m, clock24 = false, factor = 1f, measure = wide, lowEnd = false)!!
        val rows = NflHubUi.scoringRows(res, g, d, emptyMap(), listOf(catchUp), NflHubUi.offerNow(m.nowMs, d), videoOn = !m.videoOff)
        return Surfaces(
            slot1 = slot.action == SlotAction.WATCH_RECAP && slot.label == SlotLabel.HIGHLIGHTS && slot.usable,
            picture = content.media is MediaSpec.Poster,
            chip = cardHighlights(g, m.nowMs + (d?.clockSkewMs ?: 0L), d),
            sheetRow = rows.any { it is ScoringRow.Video && it.ref.kind == ClipKind.RECAP },
        )
    }

    @Test fun theFourSurfacesAgreeForEveryCombination() {
        for (onBoard in listOf(false, true)) for (inSummary in listOf(false, true)) for (state in Offer.entries) {
            val clip = clipIn(state)
            val g = NflHubTestKit.mnfFinal(media = if (onBoard) media(clip) else null)
            val d = NflHubTestKit.mnfFinalDetail(g, clips = (if (inSummary) listOf(clip) else emptyList()) + NflHubTestKit.PLAY_CLIPS)
            val r = run(g, d)
            // Entered (frozen) as on the emulator: the slot set is taken with whatever is offered now.
            r.vm.enterPanel(g.id.id)
            val s = surfaces(r, g)
            val want = state == Offer.OFFERED && (onBoard || inSummary)
            val name = "board=$onBoard summary=$inSummary $state"
            assertEquals(name, Surfaces(want, want, want, want), s)
            if (!want) {
                assertEquals("$name: slot 1 is From start (a catch-up airing)", SlotAction.WATCH_FROM_START, r.vm.panel.value.slots[0].action)
            }
        }
    }

    /**
     * The emulator's sequence for GB @ LAR: the panel entered and the Scoring sheet opened before ESPN posted the
     * highlight (slot 1 ▶ From start); the board then lists it (the summary never does). Slot 1 becomes ▶ Highlights in
     * place (Scoring keeps its index), the sheet's rows gain Game highlights, the repository gets the newer snapshot, and
     * ▶ Highlights plays the board's clip. At its expiry slot 1 goes back to ▶ From start.
     */
    @Test fun aHighlightThatAppearsOnTheBoardWhileThePanelAndTheSheetAreOpenTurnsSlot1InPlace() {
        val g0 = NflHubTestKit.mnfFinal()
        val d = NflHubTestKit.mnfFinalDetail(g0, clips = NflHubTestKit.PLAY_CLIPS)
        val r = run(g0, d)
        assertEquals(0, r.vm.enterPanel(g0.id.id))
        assertEquals(listOf(SlotAction.WATCH_FROM_START, SlotAction.GOALS, SlotAction.BOX_SCORE), r.vm.panel.value.slots.map { it.action })
        r.vm.onSlot(1)
        assertEquals(HubSheet.Goals(g0.id), r.vm.sheet.value)
        val shows = r.detail.showCalls.size
        // The board's highlight (the board's own entry; a clip id the summary does not list).
        val clip = GameClip(50601743L, ClipKind.RECAP, "Rams pick up important home win over the Packers", 80_000L, base - 60_000L, base - 62_000L, base + 3_600_000L, "https://img.invalid/50601743.jpg")
        val g1 = g0.copy(media = media(clip))
        setBoard(r, g1)
        val m = r.vm.panel.value
        assertEquals("slot 1 re-evaluated in place", listOf(SlotLabel.HIGHLIGHTS, SlotLabel.SCORING_N, SlotLabel.BOX_SCORE), m.slots.map { it.label })
        assertTrue(m.slots[0].usable)
        assertEquals("the sheet stays open", HubSheet.Goals(g0.id), r.vm.sheet.value)
        assertTrue("focus stays in the panel", r.vm.panelFocused.value)
        assertEquals("the repository gets the newer snapshot", g1, r.detail.showCalls.drop(shows).last())
        assertEquals(surfaces(r, g1), Surfaces(true, true, true, true))
        val content = HubPanelContents.build(res, m, clock24 = false, factor = 1f, measure = wide, lowEnd = false)!!
        assertEquals(50601743L, (content.media as MediaSpec.Poster).videoId)
        assertEquals(res.getString(app.thebox.ui.R.string.sports_hub_caption_highlights, "1:20"), (content.media as MediaSpec.Poster).caption)
        r.vm.closeSheet()
        r.vm.onSlot(0)
        assertEquals(listOf(50601743L), r.resolver.streamCalls.map { it.videoId })
        // The expiry (the one offer timer) turns slot 1 back to ▶ From start, still in place.
        scheduler.advanceTimeBy(NflHubTestKit.offEnd(clip) - now() + 1)
        scheduler.runCurrent()
        assertEquals(SlotAction.WATCH_FROM_START, r.vm.panel.value.slots[0].action)
        assertTrue(r.vm.panel.value.slots[0].usable)
        assertEquals(surfaces(r, g1), Surfaces(false, false, false, false))
    }
}
