package app.thebox.ui.sports

import androidx.test.core.app.ApplicationProvider
import app.thebox.core.full.fakes.FakeArchivePlayback
import app.thebox.core.sports.DayBoard
import app.thebox.core.sports.DayState
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsPrefs
import app.thebox.core.sports.group
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.DetailState
import app.thebox.core.sports.hub.FakeClipResolver
import app.thebox.core.sports.hub.FakeGameChannelMatcher
import app.thebox.core.sports.hub.FakeGameDetailRepository
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.NflHubPipeline
import app.thebox.ui.R
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (NFL hub integration, docs/design/m6-nfl-hub.md §18) The `:ui` half of the real pipeline: every [NflHubPipeline] case's
 * detail as the real `EspnDetailMapper` and `EspnDetailSource` produce it (`NflHubPipelineDataTest` asserts the repository
 * publishes exactly [NflHubPipeline.expected]), published into `SportsViewModel` → `HubLeagues` / `NflHubUi` → the panel
 * contents (rows, summary lines, media, slots and their spoken reasons) and the three sheets (Scoring, Box score,
 * Matchup). Complements P3's hand-built `NflHubTestKit` cases with real data: pre-game, live, final with a highlight, a
 * final whose highlight expired, a final with no clip, and the surname and injury-count edge cases.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NflHubPipelineUiTest {
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
    private fun s(id: Int, vararg a: Any) = res.getString(id, *a)

    private class Run(val vm: SportsViewModel, val game: GameSnapshot, val detail: GameDetail, val repo: FakeGameDetailRepository)

    /** The case's board snapshot and its real detail, shown in a view model whose wall clock is the case's. */
    private fun run(c: NflHubPipeline.Case): Run {
        val game = NflHubPipeline.game(c)
        val d = NflHubPipeline.expected(c) ?: error("${c.name} does not map")
        val board = FakeScoreRepository(SportsPrefs(league = League.NFL))
        board.day.value = DayState.Board(DayBoard("w2026-2-12", "w2026-2-11", "w2026-2-13", listOf(game)), 0L, false, true)
        val repo = FakeGameDetailRepository()
        repo.detail.value = DetailState.Ready(d, 20_000L, failing = false, stale = false)
        NflHubPipeline.box(c)?.let { b -> repo.boxScoreAnswer = { Fetch.Ok(b, 20_000L, null, 0) } }
        val resolver = FakeClipResolver()
        val vm = SportsViewModel(
            board, null, { 10_000L }, { c.wallMs + scheduler.currentTime }, repo, FakeGameChannelMatcher(), resolver,
            { scope -> ClipSession(FakePlaybackController(), FakeArchivePlayback(), resolver, scope) },
            { app.thebox.core.parental.ParentalAccess.OPEN },
        ) { LocalDate.of(2026, 11, 23) }
        vm.onCardFocused(game.id.id)
        return Run(vm, game, d, repo)
    }

    /** [HubPanelModel] with the view model's other fields kept (its own `copy` takes slots only). */
    private fun HubPanelModel.with(detail: GameDetail? = this.detail, detailKind: DetailKind = this.detailKind, videoOff: Boolean = this.videoOff, slots: List<HubSlot>) =
        HubPanelModel(
            game, followed, detail, detailKind, failure, stale, failing, statsAtMs, awayScore, homeScore, match, recap, latestGoal,
            winningGoal, slots, nowMs, videoOff, imminent, offerTick,
        )

    private fun content(r: Run, m: HubPanelModel = r.vm.panel.value) = HubPanelContents.build(res, m, clock24 = false, factor = 1f, measure = wide, lowEnd = false)!!
    private fun reasons(m: HubPanelModel) = HubLeagues.of(m.game!!.id.league.group).slotReasons(res, m, m.slots)
    private fun scoring(r: Run, m: HubPanelModel = r.vm.panel.value) =
        NflHubUi.scoringRows(res, r.game, r.detail, emptyMap(), emptyList(), NflHubUi.offerNow(m.nowMs, r.detail), videoOn = !m.videoOff)

    @Test fun preGame() {
        val r = run(NflHubPipeline.PRE)
        val m = r.vm.panel.value
        assertEquals(DetailKind.READY, m.detailKind)
        val c = content(r)
        assertEquals(s(R.string.sports_hub_stats_season), c.headerCentre)
        assertEquals(listOf("6-4", "3-2", "22.6", "22.1", "351.1", "340.4"), c.rows.map { it.away })
        assertEquals(listOf("6-4", "4-1", "24.8", "18.7", "396.6", "295.8"), c.rows.map { it.home })
        assertEquals(listOf("Passing leaders · Love 2,838 · Stafford 2,623", "Rushing leaders · Johnson 214 · Williams 616"), c.summary.map { it.text })
        assertEquals("SoFi Stadium", (c.media as MediaSpec.Art).caption?.substringBefore(" · "))
        assertEquals(listOf(SlotAction.FOLLOW, SlotAction.CHANNEL, SlotAction.MATCHUP), m.slots.map { it.action })
        assertTrue("Matchup has rows", m.slots[2].enabled)
        // The Matchup sheet from the real detail.
        val rows = nflMatchupRows(res, r.game, r.detail)
        val labels = rows.filterIsInstance<MatchupRow.Label>().map { it.text }
        assertTrue(labels.containsAll(listOf("Passing", "Rushing", "Receiving", "Sacks", "Tackles")))
        val sacks = rows.filterIsInstance<MatchupRow.NflLeaders>().first { it.category == "sacks" }
        assertEquals("5.5 sacks", nflLeaderLine(res, "sacks", sacks.home!!))
        val recent = rows.filterIsInstance<MatchupRow.Recent>()
        assertEquals(listOf("Packers", "Rams"), recent.map { it.team })
        assertEquals(5, recent[0].text.split(" · ").size)
        val injuries = rows.filterIsInstance<MatchupRow.Injury>()
        assertTrue("IR is left out", injuries.isNotEmpty() && injuries.none { it.text.contains("Reserve") })
        assertTrue("no +n more with 3 lines", labels.none { it.startsWith("+") })
    }

    @Test fun live() {
        val r = run(NflHubPipeline.LIVE)
        val m = r.vm.panel.value
        val c = content(r)
        assertEquals(s(R.string.sports_hub_stats_team), c.headerCentre)
        assertEquals(5, c.rows.size)
        assertFalse("real totals, not dashes", c.rows.any { it.away == s(R.string.sports_hub_stat_none) })
        val last = r.detail.plays.last()
        assertTrue(c.summary.last().text, c.summary.last().text.startsWith(s(R.string.sports_hub_summary_latest_score)))
        assertTrue(c.summary.last().text.contains(NflHubUi.playText(last)))
        assertEquals(SlotAction.GOALS, m.slots[2].action)
        assertEquals(r.detail.plays.size, m.slots[2].count)
        assertTrue(m.slots[2].enabled)
        // Line 1 from the real board's situation (after a kick-off touchback: a down, but no possession key).
        assertEquals(2, c.summary.size)
        assertTrue(c.summary[0].text, c.summary[0].text.contains("1st") && c.summary[0].text.contains("GB"))
        assertTrue(c.summary[1].text, c.summary[1].text.contains("Kyren Williams 7 Yd Rush"))
        // The Scoring sheet: the plays in order; no video yet in this real Q2 capture.
        val rows = scoring(r)
        assertEquals(r.detail.plays.map { "play:${it.id}" }, rows.filterIsInstance<ScoringRow.Event>().map { it.key })
        assertTrue(rows.none { it is ScoringRow.Video })
    }

    /** The real halftime capture: no situation line (the board keeps a stale down), Latest score the tying field goal. */
    @Test fun halftimeFromTheRealCapture() {
        val r = run(NflHubPipeline.LIVE_HALFTIME)
        val c = content(r)
        assertEquals(1, c.summary.size)
        assertTrue(c.summary[0].text, c.summary[0].text.contains("Trey Smack 48 Yd Field Goal"))
        assertEquals(5, c.rows.size)
        assertEquals(6, r.vm.panel.value.slots[2].count)
    }

    @Test fun halftimeWithAnInGameClip_provisional() {
        val r = run(NflHubPipeline.LIVE_HALFTIME_PROVISIONAL)
        val rows = scoring(r)
        assertEquals(r.detail.plays.size, rows.count { it is ScoringRow.Event })
        assertEquals(listOf(ClipKind.PLAY), rows.filterIsInstance<ScoringRow.Video>().map { it.ref.kind })
    }

    @Test fun finalWithAHighlight() {
        val r = run(NflHubPipeline.FINAL_HIGHLIGHT)
        val m = r.vm.panel.value
        assertEquals(listOf(SlotAction.WATCH_RECAP, SlotAction.GOALS, SlotAction.BOX_SCORE), m.slots.map { it.action })
        assertEquals(SlotLabel.HIGHLIGHTS, m.slots[0].label)
        assertTrue(m.slots[0].usable)
        assertEquals(10, m.slots[1].count)
        val c = content(r)
        assertEquals("six final rows and Time of possession (the extra row fits beside the poster)", 7, c.rows.size)
        assertEquals("28:29", c.rows.last().away)
        val poster = c.media as MediaSpec.Poster
        assertEquals(50579688L, poster.videoId)
        assertTrue(c.summary.first().text.startsWith(s(R.string.sports_hub_summary_leaders)))
        assertTrue(c.summary.first().text.contains("Burrow"))
        assertTrue(c.summary.any { it.text.contains("22-20") })
        // The Scoring sheet: 10 plays, Game highlights, then More videos oldest first.
        val rows = scoring(r)
        assertEquals(10, rows.count { it is ScoringRow.Event })
        val videos = rows.filterIsInstance<ScoringRow.Video>()
        assertEquals(listOf(50579688L, 50578630L, 50578740L, 50579083L), videos.map { it.ref.videoId })
        assertEquals(s(R.string.sports_hub_header_more_videos), rows.filterIsInstance<ScoringRow.Header>().single().text)
        // The Box score sheet, through the view model and the repository's box.
        r.vm.onSlot(2)
        val sheet = r.vm.sheet.value as HubSheet.BoxScoreSheet
        val box = (sheet.box as Fetch.Ok).value
        val away = boxRows(box.away, failed = false, nfl = true)
        val header = away.filterIsInstance<BoxRow.TableHeader>().first()
        assertEquals(listOf("C/ATT", "YDS", "TD", "INT", "RTG"), header.columns.map { boxColumnLabel(res, it) })
        assertEquals("Joe Burrow", away.filterIsInstance<BoxRow.Athlete>().first().line.name)
        assertEquals(3, away.count { it is BoxRow.Table })
    }

    @Test fun finalWhoseHighlightExpired() {
        val r = run(NflHubPipeline.FINAL_EXPIRED)
        val m = r.vm.panel.value
        assertEquals(SlotLabel.NO_HIGHLIGHTS, m.slots[0].label)
        assertFalse(m.slots[0].enabled)
        val removed = s(R.string.sports_hub_caption_highlights_removed)
        assertEquals(removed, reasons(m)[0])
        assertEquals(removed, (content(r).media as MediaSpec.Art).caption)
        assertTrue(scoring(r).any { it is ScoringRow.Info && it.text == removed })
        assertTrue(scoring(r).none { it is ScoringRow.Video })
    }

    @Test fun finalWithNoClip() {
        val r = run(NflHubPipeline.NO_CLIP)
        val m = r.vm.panel.value
        val soon = s(R.string.sports_hub_caption_highlights_soon)
        assertEquals("75 min after the estimated end: still waiting", SlotLabel.HIGHLIGHTS_SOON, m.slots[0].label)
        assertEquals(soon, reasons(m)[0])
        assertEquals(soon, (content(r).media as MediaSpec.Art).caption)
        val leaders = content(r).summary.first().text
        assertTrue("the suffix is kept (never the last word alone): $leaders", leaders.contains("Cook III "))
        assertTrue(scoring(r).any { it is ScoringRow.Info && it.text == soon })
    }

    @Test fun theSurnameRuleAndTheInjuryCountThroughTheRealMapper() {
        val r = run(NflHubPipeline.NAMES_AND_INJURIES)
        val rows = nflMatchupRows(res, r.game, r.detail)
        val rec = rows.filterIsInstance<MatchupRow.NflLeaders>().first { it.category == "receivingYards" }
        assertEquals("A. St. Brown", rec.away!!.name)
        assertEquals("applied once, where drawn", "St. Brown", NflHubUi.shortName(rec.away!!.name))
        val labels = rows.filterIsInstance<MatchupRow.Label>().map { it.text }
        assertTrue(labels.toString(), labels.contains(s(R.string.sports_hub_more_n, 11)))
        assertEquals(6 + 1, rows.filterIsInstance<MatchupRow.Injury>().size)
    }

    /**
     * `ESPN_VIDEO_ENABLED = false` (the kill switch) makes the view model's NFL panel `videoOff`: a working stats-only panel
     * with the real data (rows and summary intact; no poster, no playable slot, the videos-off words). The constant is
     * compile-time, so the model is built as the view model builds it with the switch off.
     */
    @Test fun theKillSwitchGivesAStatsOnlyPanel() {
        val r = run(NflHubPipeline.FINAL_HIGHLIGHT)
        val on = r.vm.panel.value
        val ctx = HubSlots.Ctx(on.game!!, false, on.detail, on.failure, on.match, videoOff = true, nowMs = on.nowMs)
        val off = on.with(videoOff = true, slots = HubSlots.default(ctx))
        val c = content(r, off)
        val videosOff = s(R.string.sports_hub_caption_videos_off_espn)
        assertEquals(content(r).rows, c.rows)
        assertEquals(videosOff, (c.media as MediaSpec.Art).caption)
        assertFalse(off.slots[0].enabled)
        assertTrue("Scoring and Box score still work", off.slots[1].usable && off.slots[2].usable)
        val rows = scoring(r, off)
        assertTrue(rows.none { it is ScoringRow.Video })
        assertTrue(rows.any { it is ScoringRow.Info && it.text == videosOff })
        assertEquals(10, rows.count { it is ScoringRow.Event })
    }

    /** (Integration §18, P3 note 16) The grey Matchup slot speaks its reason: loading, or nothing listed. */
    @Test fun theGreyMatchupSlotSpeaksItsReason() {
        val r = run(NflHubPipeline.PRE)
        val m = r.vm.panel.value
        val empty = r.detail.copy(leaders = emptyList(), recent = emptyList(), injuries = emptyList(), injuryCounts = emptyMap())
        val none = m.with(detail = empty, slots = HubSlots.default(HubSlots.Ctx(m.game!!, false, empty, null, m.match, false, m.nowMs)))
        assertFalse(none.slots[2].enabled)
        assertEquals(s(R.string.sports_hub_reason_matchup_none), reasons(none)[2])
        val loading = m.with(detail = null, detailKind = DetailKind.LOADING, slots = HubSlots.default(HubSlots.Ctx(m.game!!, false, null, null, m.match, false, m.nowMs)))
        assertFalse(loading.slots[2].enabled)
        assertEquals(s(R.string.sports_hub_reason_matchup_loading), reasons(loading)[2])
        assertEquals(Side.AWAY, r.detail.injuries.first().side)
    }
}
