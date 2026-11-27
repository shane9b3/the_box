package app.thebox.ui.sports

import androidx.test.core.app.ApplicationProvider
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.GameClock
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.LeagueGroup
import app.thebox.core.sports.Market
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PowerPlay
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.ChlHubFixtures
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.MatchConfidence
import app.thebox.core.sports.hub.MatchReason
import app.thebox.core.sports.hub.Ranked
import app.thebox.ui.R
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (CHL hub P3) The six fixture games as the board would list them, with the commit-0 fakes ([ChlHubFixtures]) as their
 * details, and the panel model the view model builds. Shared by [HubLeagueUiChlTest], `ChlSheetsTest` and
 * `HubPanelFitTest`. [enabled] runs a block with `ChlHubUi.enabled` on (the build switch is still off until integration).
 */
internal object ChlHubTestKit {
    /** 2026-11-27T00:00:00Z (7:00 PM Eastern on the 26th). */
    const val START = 1_795_737_600_000L
    const val BEFORE = START - 7_200_000L
    const val AFTER = START + 4 * 3_600_000L

    private fun t(league: League, code: String, name: String, score: Int?, id: Int) =
        SportsFixtures.chlTeam(code, name, score, client = client(league), teamId = id)

    private fun client(league: League) = when (league) { League.WHL -> "whl"; League.QMJHL -> "lhjmq"; else -> "ohl" }

    private fun game(league: League, id: String, phase: GamePhase, away: app.thebox.core.sports.TeamLine, home: app.thebox.core.sports.TeamLine, finalKind: PeriodKind? = null) =
        SportsFixtures.chlGame(
            league = league, id = id, phase = phase, away = away, home = home,
            period = if (phase == GamePhase.FINAL) 3 else null, periodKind = if (phase == GamePhase.FINAL) PeriodKind.REG else null,
            clock = null, finalKind = finalKind, dayKey = "2026-11-26", startUtcMs = START,
        )

    fun preOhl(phase: GamePhase = GamePhase.SCHEDULED) = game(League.OHL, "29246", phase, t(League.OHL, "NIAG", "Niagara IceDogs", null, 20), t(League.OHL, "LDN", "London Knights", null, 14))
    fun preWhl() = game(League.WHL, "1023348", GamePhase.SCHEDULED, t(League.WHL, "CGY", "Calgary Hitmen", null, 202), t(League.WHL, "MH", "Medicine Hat Tigers", null, 206))
    fun preQmjhl() = game(League.QMJHL, "32953", GamePhase.SCHEDULED, t(League.QMJHL, "CHI", "Chicoutimi Saguenéens", null, 10), t(League.QMJHL, "NFL", "Newfoundland Regiment", null, 2))
    fun finalOhl() = game(League.OHL, "29239", GamePhase.FINAL, t(League.OHL, "OTT", "Ottawa 67's", 5, 7), t(League.OHL, "BAR", "Barrie Colts", 3, 6))
    fun finalWhl() = game(League.WHL, "1023337", GamePhase.FINAL, t(League.WHL, "RD", "Red Deer Rebels", 4, 210), t(League.WHL, "CGY", "Calgary Hitmen", 3, 202))
    fun finalQmjhl() = game(League.QMJHL, "32952", GamePhase.FINAL, t(League.QMJHL, "CHI", "Chicoutimi Saguenéens", 5, 10), t(League.QMJHL, "NFL", "Newfoundland Regiment", 4, 2), finalKind = PeriodKind.OT)
    fun liveOhl(): GameSnapshot = SportsFixtures.chlGame(
        id = "29239", away = t(League.OHL, "OTT", "Ottawa 67's", 2, 7), home = t(League.OHL, "BAR", "Barrie Colts", 2, 6), startUtcMs = START,
        clock = GameClock(622, running = true, inIntermission = false), powerPlay = PowerPlay(Side.HOME, app.thebox.core.sports.PowerPlayKind.ONE_MAN, 84),
    ).copy(extras = app.thebox.core.sports.BoardExtras(null, null, awayShots = 21, homeShots = 14))

    val channel = ChannelCandidate(
        GuideFixtures.channel(1, name = "TSN 3"), "Main", MatchConfidence.LIKELY,
        listOf(MatchReason.Broadcaster("TSN", Market.NATIONAL, "CA")), emptyList(), null,
    )
    val catchUp = channel.copy(airing = GuideFixtures.info(100, 280))

    fun model(
        g: GameSnapshot,
        d: GameDetail?,
        kind: DetailKind = if (d != null) DetailKind.READY else DetailKind.LOADING,
        failure: FailKind? = null,
        now: Long = if (g.phase == GamePhase.FINAL) AFTER else BEFORE,
        match: MatchState = MatchState.Done(listOf(if (g.phase == GamePhase.FINAL) catchUp else channel)),
    ): HubPanelModel {
        val base = HubPanelModel(g, detail = d, detailKind = kind, failure = failure, nowMs = now, match = match, imminent = g.phase != GamePhase.SCHEDULED)
        return base.copy(slots = HubSlots.default(HubSlots.Ctx(g, false, d, failure, match, false, now)))
    }

    inline fun <T> enabled(block: () -> T): T {
        val was = ChlHubUi.enabled
        ChlHubUi.enabled = true
        try { return block() } finally { ChlHubUi.enabled = was }
    }
}

/**
 * (CHL hub P3, docs/design/m6-chl-hub.md §2, §8 `HubLeagueUiChlTest`) The CHL panel per phase from the commit-0 fakes:
 * rows, summary, caption and slots with the design's worked values; the final slot orders (catch-up, none, failed); the
 * postponed reason; the failure texts naming the league; live and the switched-off panel exactly as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HubLeagueUiChlTest {
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private var locale: Locale? = null
    private val k = ChlHubTestKit

    @Before fun pin() { locale = Locale.getDefault(); Locale.setDefault(Locale.US); ChlHubUi.enabled = true }
    @After fun unpin() { locale?.let { Locale.setDefault(it) }; ChlHubUi.enabled = app.thebox.core.sports.SportsConstants.CHL_HUB_ENABLED }

    private val wide: Measure = { _, _, _ -> 1f }
    private fun s(id: Int, vararg a: Any) = res.getString(id, *a)
    private fun content(m: HubPanelModel) = HubPanelContents.build(res, m, clock24 = false, factor = 1f, measure = wide, locale = Locale.US)!!
    private fun labels(m: HubPanelModel) = m.slots.map { if (it.action == null) "" else slotText(res, it) }

    @Test fun theChlRoutesToItsHubWithTheHockeyTechSource() {
        assertTrue(HubLeagues.of(LeagueGroup.CHL) === ChlHubUi)
        assertTrue(ChlHubUi.detailed)
        assertEquals(HubSource.HOCKEYTECH, ChlHubUi.source)
        assertFalse(ChlHubUi.detailCarriesScore)
        assertFalse(ChlHubUi.cardHighlights(k.finalOhl(), ChlHubFixtures.finalOhl29239(), k.AFTER))
        assertTrue(ChlHubUi.clipRefs(k.finalOhl(), ChlHubFixtures.finalOhl29239(), emptyMap(), goalsOpen = true).isEmpty())
    }

    // ------------------------------------------------------------------ pre-game (§2.1, §2.2)

    @Test fun ohlPreGameRowsCarryEveryRank() {
        val c = content(k.model(k.preOhl(), ChlHubFixtures.preOhl29246()))
        assertEquals(s(R.string.sports_hub_stats_season), c.headerCentre)
        assertEquals(listOf("Record", "Last 10", "Goals for per game", "Goals against per game", "Power play %", "Penalty kill %"), c.rows.map { it.label })
        assertEquals(listOf("10-10-3-0", "3-5-2-0", "3.09 (15th)", "4.04 (18th)", "17.0% (19th)", "70.9% (18th)"), c.rows.map { it.away })
        // LDN GA 87/24 = 3.625 → "3.63" (HALF_UP); PP 19/87 shares 9th.
        assertEquals(listOf("13-8-1-2", "4-5-0-1", "3.75 (8th)", "3.63 (12th)", "21.8% (9th)", "68.3% (20th)"), c.rows.map { it.home })
        assertEquals(listOf(null, null, Side.HOME, Side.HOME, Side.HOME, Side.AWAY), c.rows.map { it.better })
        assertNull(c.error); assertNull(c.moreSoon); assertFalse(c.loading)
        assertEquals(listOf("First meeting this season", "Points leaders · Edgar 22 · Zocco 27"), c.summary.map { it.text })
        assertEquals(listOf("Follow", "Channels (1)", "Matchup"), labels(k.model(k.preOhl(), ChlHubFixtures.preOhl29246())))
        assertTrue(k.model(k.preOhl(), ChlHubFixtures.preOhl29246()).slots[2].usable)
    }

    @Test fun whlPreGameRowsWithoutTheUncapturedRanks() {
        val c = content(k.model(k.preWhl(), ChlHubFixtures.preWhl1023348()))
        assertEquals(listOf("13-9-1-1", "5-3-1-1", "4.08", "3.58", "25.3% (5th)", "82.2% (6th)"), c.rows.map { it.away })
        assertEquals(listOf("14-6-1-2", "7-1-1-1", "4.09", "3.13", "24.6% (8th)", "82.5% (4th)"), c.rows.map { it.home })
        assertEquals(listOf("Season series · CGY leads 2–1", "Points leaders · Sanderson 31 · Ruck 33"), c.summary.map { it.text })
    }

    @Test fun qmjhlPreGameRows() {
        val c = content(k.model(k.preQmjhl(), ChlHubFixtures.preQmjhl32953()))
        assertEquals("3.64 (7th)", c.rows[2].away); assertEquals("3.36 (9th)", c.rows[3].away)
        assertEquals("3.38 (11th)", c.rows[2].home); assertEquals("3.15 (7th)", c.rows[3].home)
        // 17/80 = 0.2125: the formatter's own rounding of the 0..1 rate, pinned.
        assertEquals(HubPanelContents.pct(0.2125), c.rows[4].away)
        assertEquals(listOf("14.4%", "84.9%", "77.5%"), listOf(c.rows[4].home, c.rows[5].away, c.rows[5].home))
        assertEquals(listOf("Season series · CHI leads 1–0", "Points leaders · Lecompte 29 · Goguen 27"), c.summary.map { it.text })
    }

    @Test fun aPartialAnswerShowsTheValuesWithoutRanksAndNoError() {
        val d = ChlHubFixtures.preOhl29246()
        val s = d.season!!
        fun bare(r: Ranked?) = r?.copy(rank = null)
        val partial = d.copy(season = s.copy(awayGf = bare(s.awayGf), homeGf = bare(s.homeGf), awayPp = bare(s.awayPp), homePp = bare(s.homePp)))
        val c = content(k.model(k.preOhl(), partial))
        assertEquals("3.09", c.rows[2].away); assertEquals("21.8%", c.rows[4].home)
        assertEquals("4.04 (18th)", c.rows[3].away)
        assertNull(c.error); assertNull(c.note)
    }

    @Test fun preGameLoadingAndFailing() {
        val loading = content(k.model(k.preOhl(), null))
        assertTrue(loading.loading)
        assertEquals(6, loading.rows.size)
        assertTrue(loading.rows.all { it.away == "–" && it.home == "–" })
        assertTrue("Matchup waits for the detail", !k.model(k.preOhl(), null).slots[2].enabled)
        val refused = content(k.model(k.preOhl(), null, DetailKind.FAILED, FailKind.REFUSED))
        assertEquals(s(R.string.sports_hub_error_detail_limited_chl, "OHL"), refused.error)
        val retry = k.model(k.preWhl(), null, DetailKind.FAILED, FailKind.TIMEOUT)
        assertEquals(SlotAction.RETRY, retry.slots[0].action)
        assertEquals("Couldn't load game stats from the WHL. Trying again automatically. Select Retry to try now.", content(retry).error)
        val changed = content(k.model(k.preQmjhl(), null, DetailKind.FAILED, FailKind.BAD_BODY))
        assertEquals(s(R.string.sports_hub_error_detail_changed_chl, "QMJHL"), changed.error)
        // Frozen slots (slot 1 still Follow): Back, then OK, then Retry.
        val frozen = k.model(k.preOhl(), null, DetailKind.FAILED, FailKind.TIMEOUT).let { it.copy(slots = k.model(k.preOhl(), null).slots) }
        assertEquals(s(R.string.sports_hub_error_detail_back_chl, "OHL"), content(frozen).error)
    }

    // ------------------------------------------------------------------ final (§2.1–§2.4)

    @Test fun ohlFinalWithACatchUpAiring() {
        val m = k.model(k.finalOhl(), ChlHubFixtures.finalOhl29239())
        val c = content(m)
        assertEquals(s(R.string.sports_hub_stats_team), c.headerCentre)
        assertEquals(listOf("Shots on goal", "Power play", "Faceoff %", "Penalty minutes"), c.rows.map { it.label })
        assertEquals(listOf("38", "2/4", "53.2%", "6"), c.rows.map { it.away })
        assertEquals(listOf("21", "0/2", "46.8%", "10"), c.rows.map { it.home })
        assertEquals("fewer penalty minutes is better", Side.AWAY, c.rows[3].better)
        assertEquals(
            listOf("Game-winning goal · Dietsch (2nd) from Houben, 3rd period 10:53.", "Stars: Manza, Bowes, Dietsch"),
            c.summary.map { it.text },
        )
        assertEquals("Sadlon Arena · Attendance 2,801", (c.media as MediaSpec.Art).caption)
        assertEquals(listOf("Watch from start", "Goals (8)", "Box score"), labels(m))
        assertTrue(m.slots.all { it.usable })
        assertTrue("the catch-up airing's line", c.channelLine != null)
    }

    @Test fun whlFinalWithoutCatchUpHasGoalsFirst() {
        val m = k.model(k.finalWhl(), ChlHubFixtures.finalWhl1023337(), match = MatchState.Done(emptyList()))
        val c = content(m)
        assertEquals(listOf("Goals (7)", "Box score", ""), labels(m))
        assertEquals(0, m.firstEnabled)
        assertEquals(listOf("36", "1/2", "44.3%", "10"), c.rows.map { it.away })
        assertEquals("Game-winning goal · Hamilton (17th) from Andersen and Kuzma, 3rd period 19:25.", c.summary[0].text)
        assertEquals("Stars: Hamilton, Kuzma, Anaka", c.summary[1].text)
        assertEquals("Scotiabank Saddledome - Calgary, AB · Attendance 2,226", (c.media as MediaSpec.Art).caption)
    }

    @Test fun qmjhlOvertimeFinalHasHits() {
        val c = content(k.model(k.finalQmjhl(), ChlHubFixtures.finalOtQmjhl32952()))
        assertEquals("Hits", c.rows.last().label)
        assertEquals("2" to "10", c.rows.last().away to c.rows.last().home)
        assertEquals(5, c.rows.size)
        assertEquals("Game-winning goal · Dubé (4th) from Dufour and Hartl, OT period 4:16.", c.summary[0].text)
        assertEquals("Stars: Dubé, Lecompte, Norman", c.summary[1].text)
        assertEquals("Mary Brown's Centre · Attendance 4,852", (c.media as MediaSpec.Art).caption)
    }

    @Test fun theFinalCaptionIsTheVenueAloneWithoutAttendance() {
        val c = content(k.model(k.finalOhl(), ChlHubFixtures.finalOhl29239().copy(attendance = null)))
        assertEquals("Sadlon Arena", (c.media as MediaSpec.Art).caption)
    }

    @Test fun aFailedFinalPutsRetryAfterWatchFromStart() {
        val g = k.finalOhl()
        val withCatchUp = k.model(g, null, DetailKind.FAILED, FailKind.TIMEOUT)
        assertEquals(listOf(SlotAction.WATCH_FROM_START, SlotAction.RETRY, SlotAction.GOALS), withCatchUp.slots.map { it.action })
        val c = content(withCatchUp)
        assertEquals(s(R.string.sports_hub_error_detail_retry_chl, "OHL"), c.error)
        val none = k.model(g, null, DetailKind.FAILED, FailKind.IO, match = MatchState.Done(emptyList()))
        assertEquals(listOf(SlotAction.RETRY, SlotAction.GOALS, SlotAction.BOX_SCORE), none.slots.map { it.action })
        assertFalse("no goals yet", none.slots[1].enabled)
        assertTrue(none.slots[2].usable)
        // The board's Shots row stays while the final has no detail; the failure is then the summary.
        val boarded = g.copy(extras = app.thebox.core.sports.BoardExtras(null, null, awayShots = 38, homeShots = 21))
        val b = content(k.model(boarded, null, DetailKind.FAILED, FailKind.TIMEOUT))
        assertEquals(listOf("Shots"), b.rows.map { it.label })
        assertNull(b.error)
        assertEquals(s(R.string.sports_hub_error_detail_retry_chl, "OHL"), b.summary[0].text)
        val loading = content(k.model(boarded, null))
        assertEquals(listOf("Shots"), loading.rows.map { it.label })
        assertTrue(loading.summary.isEmpty())
    }

    // ------------------------------------------------------------------ postponed, live, switched off

    @Test fun postponedIsTheBoardStateWithAReasonLineAndNoSlots() {
        val m = k.model(k.preOhl(GamePhase.POSTPONED), null, DetailKind.LOADING, match = MatchState.Idle)
        val c = content(m)
        assertEquals(s(R.string.sports_hub_postponed_chl, "OHL"), c.summary[0].text)
        assertTrue(c.rows.isEmpty()); assertNull(c.error); assertNull(c.moreSoon)
        assertTrue(m.slots.all { it.action == null })
    }

    @Test fun liveIsTodaysBoardPanel() {
        val g = k.liveOhl()
        val on = content(k.model(g, null, DetailKind.BOARD_ONLY))
        val off = ChlHubUi.enabled.let { ChlHubUi.enabled = false; try { content(k.model(g, null, DetailKind.BOARD_ONLY)) } finally { ChlHubUi.enabled = true } }
        assertEquals(off.rows, on.rows)
        assertEquals(off.summary, on.summary)
        assertEquals(off.media, on.media)
        assertEquals(listOf("Shots", "Power play"), on.rows.map { it.label })
        assertEquals(listOf("Follow", "Channels (1)", ""), labels(k.model(g, null, DetailKind.BOARD_ONLY)))
    }

    @Test fun switchedOffEveryPhaseIsTodaysPanel() {
        ChlHubUi.enabled = false
        val fin = k.model(k.finalOhl(), null, DetailKind.BOARD_ONLY)
        assertEquals(listOf(SlotAction.WATCH_FROM_START, null, null), fin.slots.map { it.action })
        val pre = k.model(k.preOhl(), null, DetailKind.BOARD_ONLY)
        assertEquals(listOf(SlotAction.FOLLOW, SlotAction.CHANNEL, null), pre.slots.map { it.action })
        assertEquals(s(R.string.sports_hub_more_soon_chl), content(pre).moreSoon)
        assertEquals(HubSource.NONE, ChlHubUi.source)
        assertNull(ChlHubUi.boxScoreError(res, k.finalOhl()))
        assertTrue(ChlHubUi.scoringRows(res, k.finalOhl(), ChlHubFixtures.finalOhl29239(), emptyMap(), emptyList(), 0L, true).isEmpty())
    }

    // ------------------------------------------------------------------ the Goals rows and the box error (§3.2, §3.3)

    @Test fun theGoalsRowsCarryAssistsStrengthAndTheWinnerWithoutVideo() {
        val rows = ChlHubUi.scoringRows(res, k.finalOhl(), ChlHubFixtures.finalOhl29239(), emptyMap(), listOf(k.catchUp), 0L, true)
        val events = rows.filterIsInstance<ScoringRow.Event>()
        assertEquals(8, events.size)
        assertTrue(events.all { it.noVideo && it.clip == null })
        assertEquals("M. Manza (6) · PP", events[1].line1)
        assertEquals("1st 15:36 · Tied 1–1 · Bowes, Ruml", events[1].line2)
        assertEquals("S. Mogull (8)", events[4].line1)
        assertEquals("2nd 14:36 · BAR leads 3–2 · Matheson", events[4].line2)
        assertEquals("K. Dietsch (2) · Game-winning goal", events[6].line1)
        assertEquals("3rd 10:53 · OTT leads 4–3 · Houben", events[6].line2)
        assertEquals("https://headshots.test/ohl/8975.jpg", (events[6].icon as RowIcon.Headshot).url)
        assertTrue(rows.last() is ScoringRow.CatchUp)
        // Unassisted (QMJHL Arsenault, short-handed): no assists part.
        val q = ChlHubUi.scoringRows(res, k.finalQmjhl(), ChlHubFixtures.finalOtQmjhl32952(), emptyMap(), emptyList(), 0L, true).filterIsInstance<ScoringRow.Event>()
        assertEquals("L. Arsenault (13) · SH", q[0].line1)
        assertEquals("1st 6:36 · NFL leads 1–0", q[0].line2)
        assertEquals("OT 4:16 · CHI leads 5–4 · Dufour, Hartl", q.last().line2)
    }

    @Test fun theBoxErrorNamesTheLeague() {
        assertEquals("Couldn't load the box score from the WHL. Select Retry, or press Back to close.", ChlHubUi.boxScoreError(res, k.finalWhl()))
        assertNull("the NHL keeps its own words", NhlHubUi.boxScoreError(res, SportsFixtures.game()))
    }
}
