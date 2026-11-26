package app.thebox.ui.sports

import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.FieldPos
import app.thebox.core.sports.GameMedia
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.LeagueGroup
import app.thebox.core.sports.NflBreak
import app.thebox.core.sports.NflPlay
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.StatKind
import app.thebox.ui.R
import app.thebox.ui.sports.NflHubTestKit.AFTER_FINAL
import app.thebox.ui.sports.NflHubTestKit.MNF_START
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (NFL hub P3, docs/design/m6-nfl-hub.md §8.2 `HubLeagueUiNflTest`; the NFL half of `HubPanelContentsTest`) The NFL
 * panel per phase and state: rows (§2.2), the situation line and the other summary words (§2.4), the media and its reason
 * captions (§2.1, §2.4), the slots and their spoken reasons (§2.3), the failure texts (§2.5), the pills (§2.1, §2.5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HubLeagueUiNflTest {
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private var locale: Locale? = null

    @Before fun pin() { locale = Locale.getDefault(); Locale.setDefault(Locale.US) }
    @After fun unpin() { locale?.let { Locale.setDefault(it) } }

    /** Every line fits (the words, not the fitting: HubPanelFitTest measures with the real fonts). */
    private val wide: Measure = { _, _, _ -> 1f }
    private fun s(id: Int, vararg a: Any) = res.getString(id, *a)

    private fun model(
        g: GameSnapshot,
        d: GameDetail? = null,
        kind: DetailKind = if (d != null) DetailKind.READY else DetailKind.LOADING,
        failure: FailKind? = null,
        now: Long = AFTER_FINAL,
        match: MatchState = MatchState.Done(emptyList()),
        videoOff: Boolean = false,
    ): HubPanelModel {
        val base = HubPanelModel(g, detail = d, detailKind = kind, failure = failure, nowMs = now, match = match, videoOff = videoOff)
        val ctx = HubSlots.Ctx(g, false, d, failure, match, videoOff, now)
        return base.copy(slots = HubSlots.default(ctx))
    }

    private fun content(m: HubPanelModel, lowEnd: Boolean = false, measure: Measure = wide) =
        HubPanelContents.build(res, m, clock24 = false, factor = 1f, measure = measure, lowEnd = lowEnd)!!

    // ------------------------------------------------------------------ routing

    @Test fun theNflRoutesToItsOwnHubAndHasADetailSource() {
        assertTrue(HubLeagues.of(LeagueGroup.NFL) === NflHubUi)
        assertTrue(NflHubUi.detailed)
        assertEquals(HubSource.ESPN, NflHubUi.source)
        assertTrue(NflHubUi.clipRefs(NflHubTestKit.mnfFinal(), NflHubTestKit.mnfFinalDetail(), emptyMap(), goalsOpen = true).isEmpty())
    }

    // ------------------------------------------------------------------ rows (§2.2)

    @Test fun preGameHasSixSeasonRowsInOrderWithTheBetterSide() {
        val g = NflHubTestKit.pre()
        val c = content(model(g, NflHubTestKit.preDetail(g), now = NflHubTestKit.PRE_START - 3_600_000L))
        assertEquals(s(R.string.sports_hub_stats_season), c.headerCentre)
        assertEquals(
            listOf("Record", "Last 5", "Points scored per game", "Points allowed per game", "Yards gained per game", "Yards allowed per game"),
            c.rows.map { it.label },
        )
        assertEquals(listOf("6-4", "3-2", "22.6", "22.1", "351.1", "340.4"), c.rows.map { it.away })
        assertEquals(listOf("6-4", "4-1", "24.8", "18.7", "396.6", "295.8"), c.rows.map { it.home })
        // none · none · higher · lower · higher · lower
        assertEquals(listOf(null, null, Side.HOME, Side.HOME, Side.HOME, Side.HOME), c.rows.map { it.better })
        assertTrue(c.rows[0].desc, c.rows[0].desc.contains("6 wins, 4 losses"))
        assertTrue(c.rows[1].desc, c.rows[1].desc.contains("4 wins, 1 loss"))
        assertNull("no 'More game details come for the NHL first' line", c.moreSoon)
    }

    @Test fun liveHasFiveGameRowsAndThirdDownComparesTheRate() {
        val g = NflHubTestKit.mnfLive()
        val c = content(model(g, NflHubTestKit.mnfLiveDetail(g)))
        assertEquals(s(R.string.sports_hub_stats_team), c.headerCentre)
        assertEquals(listOf("Total yards", "Passing yards", "Rushing yards", "3rd down", "Turnovers"), c.rows.take(5).map { it.label })
        val third = c.rows[3]
        assertEquals("3-8", third.away); assertEquals("2-7", third.home)
        assertEquals("3 of 8 beats 2 of 7", Side.AWAY, third.better)
        assertEquals("3rd down: Bengals 3 of 8, Commanders 2 of 7", third.desc)
        assertEquals("fewer turnovers is better", Side.AWAY, c.rows[4].better)
    }

    @Test fun finalHasSixRowsAndTimeOfPossessionAsTheExtra() {
        val g = NflHubTestKit.mnfFinal()
        val c = content(model(g, NflHubTestKit.mnfFinalDetail(g, clips = emptyList())))
        assertEquals(listOf("Total yards", "Passing yards", "Rushing yards", "First downs", "3rd down", "Turnovers"), c.rows.take(6).map { it.label })
        assertEquals("Total yards highlights the home side", Side.HOME, c.rows[0].better)
        assertNull("0–0 turnovers is a tie", c.rows[5].better)
        // Art (no highlight): Possession fits at Default (§2.6: 188).
        assertEquals(7, c.rows.size)
        val possession = c.rows[6]
        assertEquals("Time of possession", possession.label)
        assertEquals("28:29", possession.away)
        assertNull("possession is never compared", possession.better)
        assertEquals("Time of possession: Bengals 28 minutes 29, Commanders 31 minutes 31", possession.desc)
    }

    @Test fun loadingShowsThePhasesLabelsWithDashes() {
        val c = content(model(NflHubTestKit.mnfLive(), null, DetailKind.LOADING))
        assertTrue(c.loading)
        assertEquals(5, c.rows.size)
        assertTrue(c.rows.all { it.away == "–" && it.home == "–" })
    }

    @Test fun boardOnlyBeforeTheDataPackageReadsAsLoading() {
        // The commit-0 repository publishes BoardOnly for the NFL until P2 lands: the panel still has its rows.
        val c = content(model(NflHubTestKit.pre(), null, DetailKind.BOARD_ONLY))
        assertEquals(6, c.rows.size)
        assertTrue(c.loading)
    }

    // ------------------------------------------------------------------ failure (§2.5)

    @Test fun aFailedDetailNamesEspnAndTheNextStepOnScreen() {
        val live = NflHubTestKit.mnfLive()
        val m = model(live, null, DetailKind.FAILED, FailKind.TIMEOUT)
        assertEquals("slot 1 is Retry", SlotAction.RETRY, m.slots[0].action)
        assertEquals(s(R.string.sports_hub_error_detail_retry_espn), content(m).error)
        assertTrue(content(m).rows.isEmpty())
        assertEquals(s(R.string.sports_hub_error_detail_limited_espn), content(model(live, null, DetailKind.FAILED, FailKind.HTTP_429)).error)
        assertEquals(s(R.string.sports_hub_error_detail_limited_espn), content(model(live, null, DetailKind.FAILED, FailKind.REFUSED)).error)
        assertEquals(s(R.string.sports_hub_error_detail_changed_espn), content(model(live, null, DetailKind.FAILED, FailKind.BAD_BODY)).error)
        assertEquals(s(R.string.sports_hub_error_detail_offline), content(model(live, null, DetailKind.FAILED, FailKind.OFFLINE)).error)
        // Frozen slots (slot 1 still Follow): Back, then OK.
        val frozen = m.copy(slots = listOf(HubSlot(SlotAction.FOLLOW, SlotLabel.FOLLOW, true)) + m.slots.drop(1))
        assertEquals(s(R.string.sports_hub_error_detail_back_espn), content(frozen).error)
        // The summary keeps what the board gives: the situation, then the board's passing leader (none here).
        assertEquals("CIN ball · 2nd & 7 at WSH 35 · Red zone · Timeouts 2–3", content(m).summary[0].text)
    }

    @Test fun aFailedFinalWithAnOfferedBoardHighlightKeepsHighlightsInSlotOne() {
        val media = GameMedia(50579688L, null, 0, recapExpiresMs = AFTER_FINAL + 86_400_000L)
        val g = NflHubTestKit.mnfFinal(media = media)
        val m = model(g, null, DetailKind.FAILED, FailKind.HTTP_5XX)
        assertEquals(listOf(SlotLabel.HIGHLIGHTS, SlotLabel.SCORING, SlotLabel.BOX_SCORE), m.slots.map { it.label })
        assertTrue(m.slots.all { it.usable })
        assertEquals(s(R.string.sports_hub_error_detail_auto_espn), content(m).error)
        // Without it: Retry, Scoring (no count), Box score.
        val plain = model(NflHubTestKit.mnfFinal(), null, DetailKind.FAILED, FailKind.HTTP_5XX)
        assertEquals(listOf(SlotLabel.RETRY, SlotLabel.SCORING, SlotLabel.BOX_SCORE), plain.slots.map { it.label })
    }

    // ------------------------------------------------------------------ the situation line (§2.4, UX M1)

    private fun situation(g: GameSnapshot) = NflHubUi.situation(res, g, g.detail as? app.thebox.core.sports.LeagueDetail.Nfl, HubNames.of(g.id.league, g.away), HubNames.of(g.id.league, g.home), wide)

    @Test fun theSituationLineSaysWhoHasTheBallWhereAndTheTimeouts() {
        val (line, dropped) = situation(NflHubTestKit.mnfLive())!!
        assertEquals("CIN ball · 2nd & 7 at WSH 35 · Red zone · Timeouts 2–3", line.text)
        assertEquals("CIN ball", line.label)
        assertTrue(line.oneLine)
        assertFalse(dropped)
        assertEquals("Bengals ball, second and 7 at the Commanders 35, red zone, timeouts 2 and 3", line.desc)
        assertEquals(
            "CIN ball · 1st & Goal at WSH 4 · Red zone · Timeouts 2–3",
            situation(NflHubTestKit.mnfLive(play = NflPlay.Down(1, 4, goalToGo = true, field = FieldPos(Side.HOME, 4))))!!.first.text,
        )
        assertEquals(
            "WSH ball · 3rd & 2 at midfield",
            situation(NflHubTestKit.mnfLive(play = NflPlay.Down(3, 2, false, FieldPos(null, 50)), possession = Side.HOME, redZone = false, awayTimeouts = null))!!.first.text,
        )
        assertEquals("1st & 10", situation(NflHubTestKit.mnfLive(play = NflPlay.Down(1, 10, false, null), possession = null, redZone = false, homeTimeouts = null))!!.first.text)
        assertEquals("Kickoff · WSH", situation(NflHubTestKit.mnfLive(play = NflPlay.Kickoff(Side.HOME, null)))!!.first.text)
        assertEquals("After the score", situation(NflHubTestKit.mnfLive(play = NflPlay.Dead(Side.AWAY, 6)))!!.first.text)
        assertNull("no situation", situation(NflHubTestKit.mnfLive(play = NflPlay.None)))
        assertNull("halftime: the board does not say who receives", situation(NflHubTestKit.mnfLive(phase = GamePhase.INTERMISSION, breakKind = NflBreak.HALFTIME)))
        assertEquals(s(R.string.sports_hub_sit_delayed), situation(NflHubTestKit.mnfLive(delayed = true))!!.first.text)
    }

    @Test fun theSituationNeedsNoDetailAndLeadsTheSummary() {
        val g = NflHubTestKit.mnfLive()
        val loading = content(model(g, null, DetailKind.LOADING))
        assertEquals(1, loading.summary.size)
        assertTrue(loading.summary[0].text.startsWith("CIN ball"))
        val ready = content(model(g, NflHubTestKit.mnfLiveDetail(g)))
        assertTrue(ready.summary[0].text.startsWith("CIN ball"))
        assertEquals("Latest score · Drew Stevens 40 Yd Field Goal (WSH)", ready.summary[1].text)
        // End of a quarter keeps the situation (the drive goes on).
        val endQ = NflHubTestKit.mnfLive(phase = GamePhase.INTERMISSION, breakKind = NflBreak.END_PERIOD)
        assertTrue(content(model(endQ, NflHubTestKit.mnfLiveDetail(endQ))).summary[0].text.startsWith("CIN ball"))
    }

    @Test fun noPointsYetIsTheScoringSlotsVisibleAndSpokenReason() {
        val g = NflHubTestKit.mnfLive()
        val d = NflHubTestKit.mnfLiveDetail(g, plays = emptyList(), clips = emptyList())
        val m = model(g, d)
        assertEquals(SlotLabel.SCORING, m.slots[2].label)
        assertFalse(m.slots[2].enabled)
        assertEquals("Latest score · No points yet", content(m).summary[1].text)
        assertEquals(listOf(null, null, "No points yet"), NflHubUi.slotReasons(res, m, m.slots))
        // An offered clip, a scoring play, or no detail yet: enabled.
        assertTrue(model(g, NflHubTestKit.mnfLiveDetail(g, plays = emptyList())).slots[2].enabled)
        assertTrue(model(g, null, DetailKind.LOADING).slots[2].enabled)
        val six = model(g, NflHubTestKit.mnfLiveDetail(g))
        assertEquals(SlotLabel.SCORING_N, six.slots[2].label)
        assertEquals(6, six.slots[2].count)
    }

    // ------------------------------------------------------------------ final and pre-game lines

    @Test fun theFinalLinesAreLeadersThenTheRecapOnOneLine() {
        val g = NflHubTestKit.mnfFinal()
        val c = content(model(g, NflHubTestKit.mnfFinalDetail(g, clips = emptyList())))
        assertEquals("Leaders · Burrow 300 pass (CIN) · Daniels 55 rush (WSH) · Williams 122 rec (WSH)", c.summary[0].text)
        assertEquals("Recap · " + NflHubTestKit.RECAP_HEADLINE, c.summary[1].text)
        assertTrue(c.summary.all { it.oneLine })
    }

    @Test fun thePreGameLinesAreTheSeasonLeadersAwayFirst() {
        val g = NflHubTestKit.pre()
        val c = content(model(g, NflHubTestKit.preDetail(g), now = NflHubTestKit.PRE_START - 3_600_000L))
        assertEquals("Passing leaders · Love 2,838 · Stafford 2,623", c.summary[0].text)
        assertEquals("Rushing leaders · Johnson 214 · Williams 616", c.summary[1].text)
    }

    @Test fun namesKeepEverythingAfterTheFirstInitial() {
        assertEquals("Cook III", NflHubUi.shortName("J. Cook III"))
        assertEquals("Van Ness", NflHubUi.shortName("L. Van Ness"))
        assertEquals("Stroud", NflHubUi.shortName("C.J. Stroud"))
        assertEquals("St. Brown", NflHubUi.shortName("A. St. Brown"))
        assertEquals("Burrow", NflHubUi.shortName("Burrow"))
    }

    // ------------------------------------------------------------------ media (§2.1, §2.4; UX M3)

    @Test fun aFinalWithAnOfferedHighlightShowsItsStillAndCaption() {
        val g = NflHubTestKit.mnfFinal()
        val c = content(model(g, NflHubTestKit.mnfFinalDetail(g)))
        val p = c.media as MediaSpec.Poster
        assertEquals(50579688L, p.videoId)
        assertEquals("HIGHLIGHTS", p.tag)
        assertEquals("Game highlights (1:14)", p.caption)
        assertEquals("https://img.invalid/50579688.jpg", p.info?.thumbnailUrl)
        assertEquals("Game highlights, 1 minute 14 seconds", c.mediaDesc)
        // Low-end: never the 1920 thumbnail; art with the caption, no disc.
        val low = content(model(g, NflHubTestKit.mnfFinalDetail(g)), lowEnd = true).media as MediaSpec.Art
        assertEquals("Game highlights (1:14)", low.caption)
    }

    @Test fun afterTheHighlightExpiresTheArtSaysWhy() {
        val clip = NflHubTestKit.highlight()
        val g = NflHubTestKit.mnfFinal()
        val d = NflHubTestKit.mnfFinalDetail(g, clips = listOf(clip))
        val at = NflHubTestKit.offEnd(clip)
        val c = content(model(g, d, now = at))
        assertEquals(s(R.string.sports_hub_caption_highlights_removed), (c.media as MediaSpec.Art).caption)
        val m = model(g, d, now = at)
        assertEquals(SlotLabel.NO_HIGHLIGHTS, m.slots[0].label)
        assertFalse(m.slots[0].enabled)
        assertEquals(s(R.string.sports_hub_caption_highlights_removed), NflHubUi.slotReasons(res, m, m.slots)[0])
        // One ms earlier it is still offered.
        assertTrue(content(model(g, d, now = at - 1)).media is MediaSpec.Poster)
    }

    @Test fun theNoHighlightCaptionFollowsTheGamesAge() {
        val g = NflHubTestKit.mnfFinal()
        val d = NflHubTestKit.mnfFinalDetail(g, clips = emptyList())
        val ended = MNF_START + SportsConstants.HUB_NFL_GAME_LENGTH_MS
        fun caption(now: Long) = (content(model(g, d, now = now)).media as MediaSpec.Art).caption
        fun label(now: Long) = model(g, d, now = now).slots[0].label
        assertEquals(s(R.string.sports_hub_caption_highlights_soon), caption(ended + 30 * 60_000L))
        assertEquals(SlotLabel.HIGHLIGHTS_SOON, label(ended + 30 * 60_000L))
        assertEquals(s(R.string.sports_hub_caption_highlights_none), caption(ended + 3 * 3_600_000L))
        assertEquals(SlotLabel.NO_HIGHLIGHTS, label(ended + 3 * 3_600_000L))
        assertEquals(s(R.string.sports_hub_caption_highlights_removed), caption(ended + 50 * 3_600_000L))
    }

    @Test fun videoOffTurnsEveryVideoPieceOff() {
        val g = NflHubTestKit.mnfFinal()
        val m = model(g, NflHubTestKit.mnfFinalDetail(g), videoOff = true)
        assertEquals(s(R.string.sports_hub_caption_videos_off_espn), (content(m).media as MediaSpec.Art).caption)
        assertEquals(SlotLabel.NO_HIGHLIGHTS, m.slots[0].label)
        assertEquals(s(R.string.sports_hub_caption_videos_off_espn), NflHubUi.slotReasons(res, m, m.slots)[0])
    }

    @Test fun preGameAndLiveCaptionsAreVenueAndNetwork() {
        val g = NflHubTestKit.pre()
        assertEquals("SoFi Stadium · Netflix", (content(model(g, NflHubTestKit.preDetail(g), now = 0L)).media as MediaSpec.Art).caption)
        assertEquals("before the detail: the network alone", "Netflix", (content(model(g, null, now = 0L)).media as MediaSpec.Art).caption)
        val noNet = NflHubTestKit.pre(broadcasts = emptyList())
        assertEquals("SoFi Stadium", (content(model(noNet, NflHubTestKit.preDetail(noNet), now = 0L)).media as MediaSpec.Art).caption)
        val live = NflHubTestKit.mnfLive()
        assertEquals("Northwest Stadium · ESPN", (content(model(live, NflHubTestKit.mnfLiveDetail(live))).media as MediaSpec.Art).caption)
    }

    // ------------------------------------------------------------------ slots (§2.3, UX B1, M4)

    @Test fun theSlotsOfEachPhase() {
        val pre = NflHubTestKit.pre()
        assertEquals(listOf(SlotAction.FOLLOW, SlotAction.CHANNEL, SlotAction.MATCHUP), model(pre, NflHubTestKit.preDetail(pre), now = 0L).slots.map { it.action })
        assertTrue("Matchup enabled with rows", model(pre, NflHubTestKit.preDetail(pre), now = 0L).slots[2].enabled)
        assertFalse("Matchup grey before the detail", model(pre, null, now = 0L).slots[2].enabled)
        val live = NflHubTestKit.mnfLive()
        assertEquals(listOf(SlotAction.FOLLOW, SlotAction.CHANNEL, SlotAction.GOALS), model(live, NflHubTestKit.mnfLiveDetail(live)).slots.map { it.action })
        val g = NflHubTestKit.mnfFinal()
        val fin = model(g, NflHubTestKit.mnfFinalDetail(g))
        assertEquals(listOf(SlotLabel.HIGHLIGHTS, SlotLabel.SCORING_N, SlotLabel.BOX_SCORE), fin.slots.map { it.label })
        assertEquals(10, fin.slots[1].count)
        assertEquals("▶", slotGlyph(fin.slots[0], 0))
        assertEquals("Highlights", slotText(res, fin.slots[0]))
        val postponed = model(NflHubTestKit.pre(phase = GamePhase.POSTPONED), null, now = 0L)
        assertTrue(postponed.slots.all { it.action == null })
    }

    @Test fun aCatchUpAiringGivesFromStartWhenNoHighlightIsOffered() {
        val g = NflHubTestKit.mnfFinal()
        val airing = app.thebox.core.guide.fakes.GuideFixtures.info(100, 280)
        val d = NflHubTestKit.mnfFinalDetail(g, clips = emptyList())
        val c = app.thebox.core.sports.hub.ChannelCandidate(
            app.thebox.core.guide.fakes.GuideFixtures.channel(1, name = "ESPN"), "Main", app.thebox.core.sports.hub.MatchConfidence.CONFIRMED,
            emptyList(), emptyList(), airing,
        )
        val m = model(g, d, match = MatchState.Done(listOf(c)), now = MNF_START + SportsConstants.HUB_NFL_GAME_LENGTH_MS + 60_000L)
        assertEquals(SlotLabel.FROM_START, m.slots[0].label)
        assertEquals(SlotAction.WATCH_FROM_START, m.slots[0].action)
        assertEquals("▶", slotGlyph(m.slots[0], 0))
        assertEquals("From start", slotText(res, m.slots[0]))
        // The channel line is drawn on a final NFL game with a catch-up airing (as the NHL).
        assertNotNull(content(m).channelLine)
    }

    @Test fun disabledSlotsDrawNoGlyphAndSpeakTheirReason() {
        val g = NflHubTestKit.mnfFinal()
        val soon = model(g, NflHubTestKit.mnfFinalDetail(g, clips = emptyList()), now = MNF_START + SportsConstants.HUB_NFL_GAME_LENGTH_MS + 60_000L)
        assertEquals(SlotLabel.HIGHLIGHTS_SOON, soon.slots[0].label)
        assertNull("no ▶ on a grey slot", slotGlyph(soon.slots[0], 0))
        val reasons = NflHubUi.slotReasons(res, soon, soon.slots)
        assertEquals(listOf("Highlights about 15 min after the game", null, null), reasons)
        val descs = slotDescriptions(res, soon.slots, reasons, beyondChannels = false)
        assertEquals("Highlights soon, unavailable. Highlights about 15 min after the game", descs[0])
        assertEquals("Scoring (10). Highlights unavailable: Highlights about 15 min after the game", descs[1])
        assertEquals("Box score", descs[2])
        // The NHL's Recap soon (the one intended NHL change): no glyph, and its reason.
        val recapSoon = HubSlot(SlotAction.WATCH_RECAP, SlotLabel.RECAP_SOON, false)
        assertNull(slotGlyph(recapSoon, 0))
        assertEquals("Recap not posted yet", NhlHubUi.slotReasons(res, HubPanelModel(null), listOf(recapSoon))[0])
    }

    @Test fun everyNonPostponedStateLeavesAFocusableSlot() {
        val live = NflHubTestKit.mnfLive()
        val g = NflHubTestKit.mnfFinal()
        val pre = NflHubTestKit.pre()
        val models = listOf(
            model(pre, null, now = 0L), model(pre, NflHubTestKit.preDetail(pre), now = 0L), model(pre, null, DetailKind.FAILED, FailKind.IO, now = 0L),
            model(live, null), model(live, NflHubTestKit.mnfLiveDetail(live, plays = emptyList(), clips = emptyList())), model(live, null, DetailKind.FAILED, FailKind.HTTP_429),
            model(g, null), model(g, NflHubTestKit.mnfFinalDetail(g, clips = emptyList(), plays = emptyList())), model(g, null, DetailKind.FAILED, FailKind.OFFLINE),
            model(g, NflHubTestKit.mnfFinalDetail(g), videoOff = true),
        )
        for (m in models) assertNotNull("${m.game?.phase} ${m.detailKind}: ${m.slots}", m.firstEnabled)
    }

    // ------------------------------------------------------------------ pills (§2.1, §2.5; UX m4, m12)

    @Test fun footballBreaksReadEndOfQuarterAndDelaysSayDelayed() {
        val endQ = NflHubTestKit.mnfLive(phase = GamePhase.INTERMISSION, breakKind = NflBreak.END_PERIOD, period = 1)
        assertEquals("END Q1", SportsText.hubPill(res, endQ, false).text)
        val half = NflHubTestKit.mnfLive(phase = GamePhase.INTERMISSION, breakKind = NflBreak.HALFTIME, period = 2)
        assertEquals(s(R.string.sports_hub_halftime), SportsText.hubPill(res, half, false).text)
        val delayed = NflHubTestKit.mnfLive(delayed = true, period = 3, clockSecs = 332)
        assertEquals("DELAYED · Q3 5:32", SportsText.hubPill(res, delayed, false).text)
        // Hockey keeps "1ST INT".
        val hockey = app.thebox.core.sports.SportsFixtures.game(phase = GamePhase.INTERMISSION, period = 1)
        assertTrue(SportsText.hubPill(res, hockey, false).text.endsWith("INT"))
    }

    @Test fun theStatLabelsAndValuesHaveSpokenForms() {
        assertEquals("4 wins, 5 losses, 1 tie", NflHubUi.spokenRecord(res, "4-5-1"))
        assertNull(NflHubUi.spokenRecord(res, "4/5"))
        assertEquals("22.6", NflHubUi.spoken(res, StatKind.POINTS_PG, app.thebox.core.sports.hub.StatValue.Decimal(22.6)))
    }
}
