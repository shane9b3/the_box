package app.thebox.ui.sports

import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsFixtures
import app.thebox.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * (Sports Hub §1.3, §1.4, §1.5, §10.2) The panel's height budget at the four Text sizes for every phase, the headline's names
 * giving way before a score does, and the three buttons fitting 664 px in every phase (so no slot ever moves into a sheet).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HubPanelFitTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val res = context.resources
    private val semi = sansTypeface(context, R.font.instrument_sans_semibold)
    private val factors = listOf(0.9f, 1.0f, 1.1f, 1.2f)
    private val poster = HubPanelPlan.POSTER_FLOOR
    private val art = HubPanelPlan.ART_FLOOR

    private fun measure(f: Float): Measure = { text, px, _ -> Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = semi; textSize = px * f }.measureText(text) }

    private fun plan(f: Float, rows: Int, extra: Int = 0, summary: Int, channel: Boolean = true, note: Boolean = false, floor: Int) =
        HubPanelPlan.plan(f, rows, extra, summary, channel, note, floor, hasRows = rows > 0)

    @Test fun theDefaultSizeMatchesTheSpecsNumbers() {
        assertEquals("live and final NHL", 264, plan(1.0f, 5, summary = 2, floor = poster).mediaH)
        assertEquals("pre-game NHL", 226, plan(1.0f, 6, summary = 2, floor = art).mediaH)
    }

    @Test fun everyPhaseKeepsItsMediaFloorAtEverySize() {
        for (f in factors) {
            val nhlLive = plan(f, 5, summary = 2, floor = poster)
            assertTrue("NHL live / final at $f: $nhlLive", nhlLive.ok)
            val nhlPre = plan(f, 6, summary = 2, floor = art)
            assertTrue("NHL pre-game at $f: $nhlPre", nhlPre.ok)
            // NFL: the quarters and the total, then the optional rows only while the floor holds; the CHL has fewer rows.
            val nflLive = plan(f, 5, extra = 3, summary = 2, floor = art)
            assertTrue("NFL live at $f: $nflLive", nflLive.ok)
            assertTrue(nflLive.extraRows in 0..3)
            assertTrue("NFL pre-game at $f", plan(f, 1, summary = 0, floor = art).ok)
            assertTrue("CHL live at $f", plan(f, 2, summary = 0, floor = art).ok)
            assertTrue("a failed detail's text in place of the rows at $f", plan(f, HubPanelPlan.ERROR_ROWS, summary = 0, floor = art).ok)
            assertTrue("nothing to show at $f", plan(f, 0, summary = 0, channel = false, floor = art).ok)
        }
    }

    // Owner change 2026-11-25: the panel is 864 high (was 812), so the 52 extra px keep summary line 2 even at Largest (§1.4's
    // drop order still applies when the floors bite: the line goes before the media goes under its floor).
    @Test fun theSummaryKeepsBothLinesAtTheLargestSizeInTheTallerPanel() {
        val live = plan(1.2f, 5, summary = 2, floor = poster)
        assertEquals("both lines fit at Largest in 864", 2, live.summaryLines)
        assertTrue("media ${live.mediaH}", live.mediaH >= poster)
        val pre = plan(1.2f, 6, summary = 2, floor = art)
        assertEquals(2, pre.summaryLines)
        assertEquals("the default keeps both lines", 2, plan(1.0f, 5, summary = 2, floor = poster).summaryLines)
        assertEquals("Smaller does not shrink the rows", 264, plan(0.9f, 5, summary = 2, floor = poster).mediaH)
        // The drop order itself: with more rows than the floor allows, the line drops before the media gives way.
        val tight = plan(1.2f, 7, summary = 2, floor = poster)
        assertTrue("tight $tight", tight.summaryLines < 2 && tight.mediaH >= poster)
    }

    @Test fun theHeadlineNamesGiveWayAndNoScoreIsEverCut() {
        val longest = listOf(
            "Golden Knights" to "Blue Jackets", "Maple Leafs" to "Golden Knights", "Blue Jackets" to "Maple Leafs",
        )
        for (f in factors) {
            val m = measure(f)
            val col = HubPanelPlan.INNER_W.toFloat()
            for ((a, h) in longest) {
                val away = HubNames.of(League.NHL, SportsFixtures.team(if (a == "Golden Knights") "VGK" else if (a == "Maple Leafs") "TOR" else "CBJ", a))
                val home = HubNames.of(League.NHL, SportsFixtures.team(if (h == "Golden Knights") "VGK" else if (h == "Maple Leafs") "TOR" else "CBJ", h))
                for ((sa, sh) in listOf(10 to 12, 0 to 0, null to null)) {
                    val p = HubFit.headline(away, home, sa, sh, col, m)
                    assertFalse("$a vs $h at $f must not ellipsize: $p", p.ellipsis)
                    val fixed = HubFit.headlineFixedPx(sa, sh, p.designPx, m)
                    val names = (if (p.nicknames) listOf(away.nickname, home.nickname) else listOf(away.full, home.full)).sumOf { m(it, p.designPx, 600).toDouble() }
                    assertTrue("scores and names fit 664: ${fixed + names}", fixed + names <= col)
                }
            }
            // The longest NFL and CHL names: nicknames at worst, the scores intact.
            val tb = HubNames.of(League.NFL, SportsFixtures.nflTeam("TB", "Tampa Bay Buccaneers", "Buccaneers"))
            val wsh = HubNames.of(League.NFL, SportsFixtures.nflTeam("WSH", "Washington Commanders", "Commanders"))
            val p = HubFit.headline(tb, wsh, 31, 28, col, m)
            assertFalse("NFL at $f: $p", p.ellipsis)
            val sag = HubNames.of(League.QMJHL, SportsFixtures.chlTeam("CHI", "Saguenéens", 0))
            val thb = HubNames.of(League.WHL, SportsFixtures.chlTeam("SEA", "Thunderbirds", 0))
            assertFalse(HubFit.headline(sag, thb, 4, 12, col, m).ellipsis)
            // Full names win while they fit: "Vegas Golden Knights vs. Edmonton Oilers" at the default size.
        }
        val vgk = HubNames.of(League.NHL, SportsFixtures.team("VGK", "Golden Knights"))
        val edm = HubNames.of(League.NHL, SportsFixtures.team("EDM", "Oilers"))
        val p = HubFit.headline(vgk, edm, null, null, 664f, measure(1.0f))
        assertEquals(32, p.designPx); assertFalse(p.nicknames)
    }

    @Test fun threeButtonsFitInEveryPhaseAtEverySize() {
        val live = listOf(res.getString(R.string.sports_hub_slot_following), res.getString(R.string.sports_hub_slot_channels_n, 3), res.getString(R.string.sports_hub_slot_goals_n, 6))
        val finalNhl = listOf(res.getString(R.string.sports_hub_slot_watch_recap), res.getString(R.string.sports_hub_slot_goals_n, 6), res.getString(R.string.sports_hub_slot_box_score))
        val pre = listOf(res.getString(R.string.sports_hub_slot_following), res.getString(R.string.sports_hub_slot_channels_n, 3), res.getString(R.string.sports_hub_slot_matchup))
        for (f in factors) {
            val m = measure(f)
            val large = f > 1.05f
            val pad = if (large) 20 else 28
            val gap = if (large) 12 else 16
            for ((name, labels) in listOf("live" to live, "final" to finalNhl, "pre" to pre)) {
                // Slot 1 carries a 24 px glyph and its 8 px gap (the widest case: "▶ Watch recap").
                val widths = labels.mapIndexed { i, l -> m(l, 24, 600) + 2 * pad + if (i == 0) m("▶", 24, 600) + 8 else 0f }
                val total = widths.sum() + 2 * gap
                assertTrue("$name at $f: $total px of ${HubPanelPlan.INNER_W}", total <= HubPanelPlan.INNER_W)
            }
        }
    }

    // ================================================================== (NFL hub P3, §2.3, §2.6, §8.2)

    private val regular = sansTypeface(context, R.font.instrument_sans_regular)
    private val medium = sansTypeface(context, R.font.instrument_sans_medium)

    /** The panel's fonts by weight (400 / 500 / 600), at design px × the factor: the method the design's tables use. */
    private fun weighted(f: Float): Measure = { text, px, w ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = if (w >= 600) semi else if (w >= 500) medium else regular; textSize = px * f }.measureText(text)
    }

    /** A slot row's width as `Buttons` lays it out: label + 2 × padding (+ glyph + 8 when drawn) + 2 × gap. */
    private fun slotRowWidth(slots: List<HubSlot>, f: Float): Float {
        val m = measure(f)
        val large = HubCardMetrics.large(f)
        val pad = if (large) 20 else 28
        val gap = if (large) 12 else 16
        return slots.mapIndexed { i, sl ->
            val glyph = slotGlyph(sl, i)
            m(slotText(res, sl), 24, 600) + 2 * pad + (if (glyph != null) m(glyph, 24, 600) + 8 else 0f)
        }.sum() + 2 * gap
    }

    @Test fun everyNflSlotSetFitsWithTwoDigitCountsAtEverySize() {
        fun sl(a: SlotAction, l: SlotLabel, n: Int = 0, enabled: Boolean = true) = HubSlot(a, l, enabled, n)
        val scoring = sl(SlotAction.GOALS, SlotLabel.SCORING_N, 12)
        val box = sl(SlotAction.BOX_SCORE, SlotLabel.BOX_SCORE)
        val sets = linkedMapOf(
            "▶ Highlights · Scoring (12) · Box score" to listOf(sl(SlotAction.WATCH_RECAP, SlotLabel.HIGHLIGHTS), scoring, box),
            "▶ From start · Scoring (12) · Box score" to listOf(sl(SlotAction.WATCH_FROM_START, SlotLabel.FROM_START), scoring, box),
            "Highlights soon · Scoring (12) · Box score" to listOf(sl(SlotAction.WATCH_RECAP, SlotLabel.HIGHLIGHTS_SOON, enabled = false), scoring, box),
            "No highlights · Scoring (12) · Box score" to listOf(sl(SlotAction.WATCH_RECAP, SlotLabel.NO_HIGHLIGHTS, enabled = false), scoring, box),
            "↻ Retry · Scoring · Box score" to listOf(sl(SlotAction.RETRY, SlotLabel.RETRY), sl(SlotAction.GOALS, SlotLabel.SCORING), box),
            "Following ✓ · Channels (36) · Scoring (12)" to listOf(sl(SlotAction.FOLLOW, SlotLabel.FOLLOWING), sl(SlotAction.CHANNEL, SlotLabel.CHANNELS_N, 36), scoring),
            "+ Follow · Channels (36) · Scoring (12)" to listOf(sl(SlotAction.FOLLOW, SlotLabel.FOLLOW), sl(SlotAction.CHANNEL, SlotLabel.CHANNELS_N, 36), scoring),
            "Following ✓ · Channels (36) · Matchup" to listOf(sl(SlotAction.FOLLOW, SlotLabel.FOLLOWING), sl(SlotAction.CHANNEL, SlotLabel.CHANNELS_N, 36), sl(SlotAction.MATCHUP, SlotLabel.MATCHUP)),
        )
        val report = StringBuilder("NFL slot sets (px of 664) at 0.9 / 1.0 / 1.1 / 1.2:\n")
        for ((name, slots) in sets) {
            val widths = factors.map { f -> slotRowWidth(slots, f) }
            report.append("  $name: ").append(widths.joinToString(" / ") { Math.round(it).toString() }).append('\n')
            widths.forEachIndexed { i, w -> assertTrue("$name at ${factors[i]}: $w px", w <= HubPanelPlan.INNER_W) }
        }
        println(report)
        // UX B1: the rejected long labels did not fit at Largest; the disabled slots draw no glyph.
        assertEquals(null, slotGlyph(sets.getValue("Highlights soon · Scoring (12) · Box score")[0], 0))
    }

    @Test fun theNflStatLabelsFitTheCentreColumnAtLargest() {
        val labels = listOf(
            R.string.sports_hub_stat_total_yards, R.string.sports_hub_stat_pass_yards, R.string.sports_hub_stat_rush_yards,
            R.string.sports_hub_stat_first_downs, R.string.sports_hub_stat_third_down, R.string.sports_hub_stat_turnovers,
            R.string.sports_hub_stat_possession_time, R.string.sports_hub_stat_last5, R.string.sports_hub_stat_points_pg,
            R.string.sports_hub_stat_points_allowed_pg, R.string.sports_hub_stat_yards_pg, R.string.sports_hub_stat_yards_allowed_pg,
            R.string.sports_hub_stat_timeouts,
        )
        val m = weighted(1.2f)
        for (id in labels) {
            val w = m(res.getString(id), 24, 500)
            assertTrue("${res.getString(id)}: $w of 332", w <= 332f)
        }
        assertTrue("the widest side value", m("4-5-1", 24, 500) <= 166f)
    }

    private fun nflContent(m: HubPanelModel, f: Float): PanelContent {
        return HubPanelContents.build(res, m, clock24 = false, factor = f, measure = weighted(f))!!
    }

    private val catchUp = app.thebox.core.sports.hub.ChannelCandidate(
        app.thebox.core.guide.fakes.GuideFixtures.channel(1, name = "ESPN"), "Main", app.thebox.core.sports.hub.MatchConfidence.CONFIRMED,
        emptyList(), emptyList(), app.thebox.core.guide.fakes.GuideFixtures.info(100, 280),
    )

    private fun nflModel(g: app.thebox.core.sports.GameSnapshot, d: app.thebox.core.sports.hub.GameDetail?, now: Long, match: MatchState): HubPanelModel {
        val base = HubPanelModel(g, detail = d, detailKind = if (d != null) DetailKind.READY else DetailKind.LOADING, nowMs = now, match = match, imminent = true)
        return base.copy(slots = HubSlots.default(HubSlots.Ctx(g, false, d, null, match, false, now)))
    }

    @Test fun theNflHeightPlanMatchesTheDesignsTable() {
        val now = NflHubTestKit.AFTER_FINAL
        val ch = MatchState.Done(listOf(catchUp))
        val g = NflHubTestKit.mnfFinal()
        // Final, highlight poster, a channel line: Possession dropped at Default (188 < 200), the Leaders line drawn.
        val poster = nflContent(nflModel(g, NflHubTestKit.mnfFinalDetail(g), now, ch), 1.0f)
        assertTrue(poster.media is MediaSpec.Poster)
        assertEquals(226, poster.plan.mediaH)
        assertEquals("Possession gave way", 6, poster.rows.size)
        assertEquals(2, poster.plan.summaryLines)
        assertEquals("Leaders · Burrow 300 pass · Daniels 55 rush · Williams 122 rec", poster.summary[0].text)
        // Without a channel line: 234 with Possession (≥ 200, the disc stays).
        val posterNoLine = nflContent(nflModel(g, NflHubTestKit.mnfFinalDetail(g), now, MatchState.Done(emptyList())), 1.0f)
        assertEquals(7, posterNoLine.rows.size)
        assertEquals(234, posterNoLine.plan.mediaH)
        // Largest: Possession and the Recap line go; 172 (< 200: no disc, U-N11).
        val posterL = nflContent(nflModel(g, NflHubTestKit.mnfFinalDetail(g), now, ch), 1.2f)
        assertEquals(172, posterL.plan.mediaH)
        assertEquals(1, posterL.plan.summaryLines)
        assertEquals("Leaders · Burrow 300 pass · Daniels 55 rush", posterL.summary[0].text)
        assertEquals(6, posterL.rows.size)
        // Final, art: 188 with Possession at Default; 133 at Largest with both lines.
        val art = nflContent(nflModel(g, NflHubTestKit.mnfFinalDetail(g, clips = emptyList()), now, ch), 1.0f)
        assertTrue(art.media is MediaSpec.Art)
        assertEquals(188, art.plan.mediaH)
        assertEquals(7, art.rows.size)
        val artL = nflContent(nflModel(g, NflHubTestKit.mnfFinalDetail(g, clips = emptyList()), now, ch), 1.2f)
        assertEquals(133, artL.plan.mediaH)
        assertEquals(2, artL.plan.summaryLines)
        assertEquals(6, artL.rows.size)
        // Pre-game: 226 / 133 (a channel line from the one candidate).
        val pre = NflHubTestKit.pre()
        val preM = nflModel(pre, NflHubTestKit.preDetail(pre), NflHubTestKit.PRE_START - 7_200_000L, MatchState.Done(listOf(catchUp.copy(airing = null))))
        assertEquals(226, nflContent(preM, 1.0f).plan.mediaH)
        assertEquals(133, nflContent(preM, 1.2f).plan.mediaH)
        assertEquals(2, nflContent(preM, 1.2f).plan.summaryLines)
        // Live: 264 at Default with the whole situation; at Largest Timeouts leave the line for the extra row (133).
        val live = NflHubTestKit.mnfLive()
        val liveM = nflModel(live, NflHubTestKit.mnfLiveDetail(live), now, MatchState.Done(listOf(catchUp.copy(airing = null))))
        val ld = nflContent(liveM, 1.0f)
        assertEquals(264, ld.plan.mediaH)
        assertEquals("CIN ball · 2nd & 7 at WSH 35 · Red zone · Timeouts 2–3", ld.summary[0].text)
        assertEquals(5, ld.rows.size)
        val ll = nflContent(liveM, 1.2f)
        assertEquals("CIN ball · 2nd & 7 at WSH 35 · Red zone", ll.summary[0].text)
        assertEquals("the Timeouts row", 6, ll.rows.size)
        assertEquals(res.getString(R.string.sports_hub_stat_timeouts), ll.rows.last().label)
        assertEquals(133, ll.plan.mediaH)
        assertEquals(2, ll.plan.summaryLines)
        // Every case keeps its floor at every size.
        for (f in factors) for (m in listOf(preM, liveM, nflModel(g, NflHubTestKit.mnfFinalDetail(g), now, ch), nflModel(g, NflHubTestKit.mnfFinalDetail(g, clips = emptyList()), now, ch))) {
            val c = nflContent(m, f)
            assertTrue("${m.game?.phase} at $f: ${c.plan}", c.plan.ok)
        }
    }

    @Test fun theFittedLinesNeverExceedTheLine() {
        val now = NflHubTestKit.AFTER_FINAL
        val g = NflHubTestKit.mnfFinal()
        for (f in factors) {
            val m = weighted(f)
            val c = nflContent(nflModel(g, NflHubTestKit.mnfFinalDetail(g), now, MatchState.Done(emptyList())), f)
            val leaders = c.summary[0]
            val w = m(leaders.label, 24, 600) + m(leaders.text.removePrefix(leaders.label), 24, 400)
            assertTrue("Leaders at $f: $w", w <= HubPanelPlan.INNER_W)
            // The 99-character recap is one line, ellipsized (it is always wider than the line).
            if (c.summary.size > 1) {
                assertTrue(c.summary[1].oneLine)
                assertTrue(m(c.summary[1].text, 24, 400) > HubPanelPlan.INNER_W)
            }
            // The goal-to-go situation fits after its drop, at every size.
            val gtg = NflHubTestKit.mnfLive(play = app.thebox.core.sports.NflPlay.Down(1, 4, true, app.thebox.core.sports.FieldPos(app.thebox.core.sports.Side.HOME, 4)))
            val (line, _) = NflHubUi.situation(res, gtg, gtg.detail as app.thebox.core.sports.LeagueDetail.Nfl, HubNames.of(gtg.id.league, gtg.away), HubNames.of(gtg.id.league, gtg.home), m)!!
            assertTrue("situation at $f: ${line.text}", m(line.label, 24, 600) + m(line.text.removePrefix(line.label), 24, 400) <= HubPanelPlan.INNER_W)
        }
    }
}
