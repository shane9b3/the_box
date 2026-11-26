package app.thebox.ui.sports

import androidx.test.core.app.ApplicationProvider
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.sports.GameMedia
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.GoalItem
import app.thebox.core.sports.hub.MatchConfidence
import app.thebox.core.sports.hub.PlayScore
import app.thebox.core.sports.hub.ScoringPlay
import app.thebox.core.sports.hub.Strength
import app.thebox.ui.R
import app.thebox.ui.sports.NflHubTestKit.AFTER_FINAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (NFL hub P3, §8.2 `ScoringRowsTest`) The Scoring sheet's rows (§3.1, §3.2): their order and sections, the row words and
 * spoken forms, the queues, the focus on open; and the NHL's rows through the same type with today's words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScoringRowsTest {
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private val g = NflHubTestKit.mnfFinal()
    private val catchUp = ChannelCandidate(GuideFixtures.channel(1, name = "ESPN"), "Main", MatchConfidence.CONFIRMED, emptyList(), emptyList(), GuideFixtures.info(100, 280))

    private fun rows(d: GameDetail?, game: app.thebox.core.sports.GameSnapshot = g, now: Long = AFTER_FINAL, videoOn: Boolean = true, c: List<ChannelCandidate> = emptyList()) =
        NflHubUi.scoringRows(res, game, d, emptyMap(), c, NflHubUi.offerNow(now, d), videoOn)

    @Test fun anEspnGameListsPlaysThenGameHighlightsThenMoreVideosThenCatchUp() {
        val r = rows(NflHubTestKit.mnfFinalDetail(), c = listOf(catchUp))
        val kinds = r.map { it::class.simpleName }
        assertEquals(List(10) { "Event" } + listOf("Video", "Header", "Video", "Video", "Video", "CatchUp"), kinds)
        val hl = r[10] as ScoringRow.Video
        assertEquals(ClipKind.RECAP, hl.ref.kind)
        assertEquals("Game highlights", hl.label)
        assertEquals(74_000L, hl.durationMs)
        assertEquals("More videos", (r[11] as ScoringRow.Header).text)
        assertEquals("oldest first", listOf(50578630L, 50578740L, 50579083L), r.subList(12, 15).map { (it as ScoringRow.Video).ref.videoId })
        assertTrue(r.subList(12, 15).all { (it as ScoringRow.Video).ref.kind == ClipKind.PLAY })
    }

    @Test fun aScoringRowsWordsAndSpokenForm() {
        val r = rows(NflHubTestKit.mnfFinalDetail())
        val td = r[1] as ScoringRow.Event
        assertEquals("Tee Higgins 31 Yd pass from Joe Burrow", td.line1)
        assertEquals("Q2 1:55 · CIN TD · CIN leads 10–0", td.line2)
        assertEquals("Bengals touchdown. Tee Higgins 31 yard pass from Joe Burrow. Second quarter, 1:55. Bengals lead 10 to 0.", td.desc)
        assertEquals(RowIcon.TeamLogo(Side.AWAY), td.icon)
        assertNull("no clip-to-play link (§4.4)", td.clip)
        val tie = r[3] as ScoringRow.Event
        assertEquals("Q3 11:47 · WSH FG · Tied 10–10", tie.line2)
        assertTrue(tie.desc.endsWith("Tied 10 to 10."))
        val last = r[9] as ScoringRow.Event
        assertEquals("Q4 0:00 · WSH FG · WSH leads 22–20", last.line2)
        assertTrue(last.desc, last.desc.startsWith("Commanders field goal. Drew Stevens 26 yard Field Goal. Fourth quarter, 0:00."))
    }

    @Test fun typeWordsAndOvertime() {
        val ot = NflHubTestKit.LONG_PLAY
        val safety = ScoringPlay("s", PeriodLabel(PeriodKind.REG, 1), "3:00", Side.HOME, PlayScore.SAFETY, "Team Safety", 0, 2)
        val other = ScoringPlay("o", PeriodLabel(PeriodKind.REG, 4), "0:00", Side.HOME, PlayScore.OTHER, "Two-point conversion", 8, 8)
        val r = rows(NflHubTestKit.mnfFinalDetail(plays = listOf(safety, other, ot), clips = emptyList()))
        assertEquals("Q1 3:00 · WSH Safety · WSH leads 2–0", (r[0] as ScoringRow.Event).line2)
        assertEquals("OTHER has no type word", "Q4 0:00 · Tied 8–8", (r[1] as ScoringRow.Event).line2)
        val e = r[2] as ScoringRow.Event
        assertEquals("OT 4:12 · CIN Def TD · CIN leads 26–20", e.line2)
        assertTrue(e.desc, e.desc.startsWith("Bengals defensive touchdown.") && e.desc.contains("Overtime, 4:12"))
        assertTrue("the long text is the drawn line before its parenthesis", e.line1.length > 100 && !e.line1.contains("("))
    }

    @Test fun theSheetStates() {
        // Final, no scoring plays.
        val none = rows(NflHubTestKit.mnfFinalDetail(plays = emptyList()))
        assertEquals(res.getString(R.string.sports_hub_scoring_final_none), (none[0] as ScoringRow.Info).text)
        // Live, no score yet: the info row, the video rows still listed.
        val live = NflHubTestKit.mnfLive()
        val lr = rows(NflHubTestKit.mnfLiveDetail(live, plays = emptyList()), game = live)
        assertEquals(res.getString(R.string.sports_hub_scoring_live_none), (lr[0] as ScoringRow.Info).text)
        assertTrue(lr.any { it is ScoringRow.Video })
        // Video off: no video rows, one info row with the words.
        val off = rows(NflHubTestKit.mnfFinalDetail(), videoOn = false)
        assertTrue(off.none { it is ScoringRow.Video || it is ScoringRow.Header })
        assertEquals(res.getString(R.string.sports_hub_caption_videos_off_espn), (off.last() as ScoringRow.Info).text)
        // A non-ESPN game: the highlight only, no More videos section.
        val single = rows(NflHubTestKit.mnfFinalDetail(clips = listOf(NflHubTestKit.highlight())))
        assertTrue(single.none { it is ScoringRow.Header })
        assertEquals(1, single.count { it is ScoringRow.Video })
        // No highlight offered on a final: an info row with the panel caption's words.
        val expired = NflHubTestKit.highlight()
        val gone = rows(NflHubTestKit.mnfFinalDetail(clips = listOf(expired)), now = NflHubTestKit.offEnd(expired))
        assertEquals(res.getString(R.string.sports_hub_caption_highlights_removed), (gone.last() as ScoringRow.Info).text)
        // The detail still loading: the board's highlight and the catch-up rows (the sheet's note says why).
        val media = GameMedia(50579688L, null, 0, recapExpiresMs = AFTER_FINAL + 86_400_000L)
        val loading = rows(null, game = NflHubTestKit.mnfFinal(media = media), c = listOf(catchUp))
        assertEquals(listOf("Video", "CatchUp"), loading.map { it::class.simpleName })
    }

    @Test fun anExpiringClipLeavesTheSheetAtItsInstant() {
        val d = NflHubTestKit.mnfFinalDetail()
        val play = NflHubTestKit.PLAY_CLIPS[1]
        assertEquals(3, rows(d, now = NflHubTestKit.offEnd(play) - 1).count { it is ScoringRow.Video && it.ref.kind == ClipKind.PLAY })
        // Every PLAY clip shares this expiry: the section leaves with them.
        assertTrue(rows(d, now = NflHubTestKit.offEnd(play)).none { it is ScoringRow.Header })
    }

    @Test fun queuesAndFocusOnOpen() {
        val r = rows(NflHubTestKit.mnfFinalDetail(), c = listOf(catchUp))
        assertTrue("a scoring row plays nothing (the toast)", scoringQueue(r, 1).isEmpty())
        assertEquals("Game highlights alone", listOf(50579688L), scoringQueue(r, 10).map { it.videoId })
        assertEquals("a More videos row and every later one", listOf(50578740L, 50579083L), scoringQueue(r, 13).map { it.videoId })
        assertTrue("headers queue nothing", scoringQueue(r, 11).isEmpty())
        assertFalse(r[11].focusable)
        assertEquals("final: the first row", 0, scoringInitialRow(r, null, final = true))
        assertEquals("the clip just played", 13, scoringInitialRow(r, 50578740L, final = true))
        val live = NflHubTestKit.mnfLive()
        val lr = rows(NflHubTestKit.mnfLiveDetail(live), game = live)
        assertEquals("live: the latest scoring play", 5, scoringInitialRow(lr, null, final = false))
        // Only info rows: nothing focusable, index 0 (the frame's title holds focus).
        val info = rows(NflHubTestKit.mnfFinalDetail(plays = emptyList()), videoOn = false)
        assertTrue(info.none { it.focusable })
        assertEquals(0, scoringInitialRow(info, null, final = true))
    }

    @Test fun theNhlRowsKeepTodaysWords() {
        val live = SportsFixtures.game(id = "L1", phase = GamePhase.LIVE, away = SportsFixtures.team("UTA", "Mammoth"), home = SportsFixtures.team("WPG", "Jets"))
        fun goal(i: Int, clip: Long?) = GoalItem(PeriodLabel(PeriodKind.REG, 1), "5:1$i", Side.HOME, "G. Vilardi", "Vilardi", 4, listOf("Connor"), Strength.PP, false, 0, i, clip, "https://img.invalid/h.png")
        val d = GameDetail(live.id, live.phase, null, null, null, null, emptyList(), null, emptyList(), listOf(goal(1, 11), goal(2, null), goal(3, 13)), emptyList(), emptyList(), null, null, 70, 80, emptyList())
        val r = NhlHubUi.scoringRows(res, live, d, emptyMap(), emptyList(), 0L, true)
        assertEquals(listOf("goal:0", "goal:1", "goal:2"), r.map { it.key })
        val e = r[0] as ScoringRow.Event
        assertEquals(goalLine1(res, d.goals[0]), e.line1)
        assertEquals(goalLine2(res, live, d.goals[0]), e.line2)
        assertEquals(ClipRef(11, ClipKind.GOAL, live.id), e.clip)
        assertEquals(RowIcon.Headshot("https://img.invalid/h.png"), e.icon)
        assertEquals("the goal and every later goal with a clip", listOf(11L, 13L), scoringQueue(r, 0).map { it.videoId })
        assertTrue(scoringQueue(r, 1).isEmpty())
        val fin = NhlHubUi.scoringRows(res, live.copy(phase = GamePhase.FINAL), d, emptyMap(), emptyList(), 0L, true)
        assertEquals(listOf("goal:0", "goal:1", "goal:2", "video:RECAP:70", "video:CONDENSED:80"), fin.map { it.key })
        assertEquals("Game recap", (fin[3] as ScoringRow.Video).label)
    }
}
