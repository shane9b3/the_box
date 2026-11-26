package app.thebox.ui.sports

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.settings.TextSize
import app.thebox.core.settings.UiTextScale
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.Side
import app.thebox.core.sports.hub.GameResult
import app.thebox.core.sports.hub.InjuryLine
import app.thebox.core.sports.hub.RecentGame
import app.thebox.core.sports.hub.InjuryStatus
import app.thebox.ui.theme.BoxTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (NFL hub code review 2026-11-26, UI m6 and m9, the two "unsure" findings) Checked on a 1920 × 1080 window:
 * - m6: after scrolling down a sheet and back up to its first focusable row, the section header and column header above
 *   that row are on screen again (Matchup: "Season leaders" and "Passing"; Box score: "Passing" and the column header).
 * - m9: the Matchup's Last 5 row at the Largest text size keeps both lines of results inside its row.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class NflSheetsReviewTest {
    @get:Rule val rule = createComposeRule()
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @After fun reset() {
        UiTextScale.running = TextSize.DEFAULT
    }

    private fun press(k: Key) {
        rule.onRoot().performKeyInput { pressKey(k) }
        rule.waitForIdle()
    }

    @Test fun theMatchupHeadersComeBackWhenScrollingUpToTheFirstRow() {
        val pre = NflHubTestKit.pre()
        val many = NflHubTestKit.PRE_INJURIES + (1..6).map { InjuryLine(Side.AWAY, "Player Number$it", "LB", InjuryStatus.OUT, null) }
        rule.setContent { BoxTheme { MatchupSheet(pre, NflHubTestKit.preDetail(pre, injuries = many), onClose = {}) } }
        rule.waitForIdle()
        rule.onNodeWithText("Season leaders").assertIsDisplayed()
        repeat(16) { press(Key.DirectionDown) }
        rule.onNodeWithText("Season leaders").assertDoesNotExistOrIsNotDisplayed()
        repeat(20) { press(Key.DirectionUp) }
        rule.onNodeWithTag(hubSheetRowTag("nlead:2"), useUnmergedTree = true).assertIsFocused()
        rule.onNodeWithText("Season leaders").assertIsDisplayed()
        rule.onAllNodesWithText("Passing").onFirst().assertIsDisplayed()
    }

    @Test fun theBoxScoreTableHeaderComesBackWhenScrollingUpToTheFirstRow() {
        val g = NflHubTestKit.mnfFinal()
        rule.setContent { BoxTheme { BoxScoreSheet(g, Fetch.Ok(NflHubTestKit.mnfBox(), 0L, null, 0), onRetry = {}, onClose = {}) } }
        rule.waitForIdle()
        press(Key.DirectionDown)
        repeat(8) { press(Key.DirectionDown) }
        rule.onNodeWithText("Passing").assertDoesNotExistOrIsNotDisplayed()
        repeat(8) { press(Key.DirectionUp) }
        rule.onNodeWithText("Passing").assertIsDisplayed()
        rule.onAllNodesWithText("C/ATT").onFirst().assertIsDisplayed()
    }

    /**
     * m9 (found: real once the results wrap). The worst-case last five (five road games, two-digit scores, wide codes) at
     * Largest wraps to two lines; in the fixed 96 px row the second line was cut (ellipsized: 34 + 2 + 2 × 34 > 96). The
     * row now grows with the text size: never cut, inside the row. The real GB line fits on one line at Largest.
     */
    @Test fun theLastFiveRowFitsAtTheLargestTextSize() {
        UiTextScale.running = TextSize.LARGEST
        val pre = NflHubTestKit.pre()
        val worst = (1..5).map { RecentGame(Side.AWAY, atOpponent = true, opponent = "WSH", result = GameResult.W, score = "48-38", dateMs = null) }
        val d0 = NflHubTestKit.preDetail(pre)
        val d = d0.copy(recent = worst + d0.recent.filter { it.side == Side.HOME })
        val recent = nflMatchupRows(res, pre, d).filterIsInstance<MatchupRow.Recent>().first()
        rule.setContent { BoxTheme { MatchupSheet(pre, d, onClose = {}) } }
        rule.waitForIdle()
        // Down to the first Last 5 row.
        val rows = nflMatchupRows(res, pre, d)
        val target = rows.indexOfFirst { it is MatchupRow.Recent }
        val before = rows.take(target).count { it.focusable } - 1
        repeat(before + 1) { press(Key.DirectionDown) }
        val row = rule.onNodeWithTag(hubSheetRowTag("recent:$target"), useUnmergedTree = true)
        row.assertIsFocused()
        val rowBounds = row.fetchSemanticsNode().boundsInRoot
        val text = rule.onAllNodesWithText(recent.text, useUnmergedTree = true).onFirst().fetchSemanticsNode()
        val layouts = ArrayList<TextLayoutResult>()
        text.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        assertTrue("never ellipsized", (0 until layout.lineCount).none { layout.isLineEllipsized(it) })
        val textBottom = text.boundsInRoot.top + layout.size.height
        assertTrue("the results ($textBottom) end inside the row (${rowBounds.bottom})", textBottom <= rowBounds.bottom + 0.5f)
        assertTrue("at most the two lines the row allows: ${layout.lineCount}", layout.lineCount in 1..2)
    }

    // ---------------------------------------------------------------- M1, m4, m5, m7, m12

    /** (M1) A part with no number is never drawn: ESPN's live "3 CAR, - YDS" (−8 yards) is "–", never "- yds". */
    @Test fun aLeaderLineNeverDrawsAPartWithoutANumber() {
        val none = res.getString(app.thebox.ui.R.string.sports_hub_stat_none)
        assertEquals(none, nflLeaderLine(res, "rushingYards", app.thebox.core.sports.hub.Leader("J. Jacobs", -8, null, "3 CAR, - YDS")))
        assertEquals("41 yds", nflLeaderLine(res, "rushingYards", app.thebox.core.sports.hub.Leader("K. Williams", 41, null, "9 CAR, 41 YDS")))
        assertEquals("-8 yds · 1 TD", nflLeaderLine(res, "rushingYards", app.thebox.core.sports.hub.Leader("X. Y", -8, null, "3 CAR, -8 YDS, 1 TD")))
        assertEquals(none, nflLeaderLine(res, "sacks", app.thebox.core.sports.hub.Leader("B. Young", 0, null, "- sacks")))
        assertEquals("5.5 sacks", nflLeaderLine(res, "sacks", app.thebox.core.sports.hub.Leader("B. Young", 5, null, "5.5")))
    }

    /** (m4) The grey Matchup slot: "failed" when the summary failed with nothing cached, "loading" while it loads. */
    @Test fun theGreyMatchupReasonSaysFailedOrLoading() {
        val pre = NflHubTestKit.pre()
        fun reason(kind: DetailKind, d: app.thebox.core.sports.hub.GameDetail?, g: app.thebox.core.sports.GameSnapshot = pre): String? {
            val m = HubPanelModel(g, detail = d, detailKind = kind, failure = if (kind == DetailKind.FAILED) app.thebox.core.sports.FailKind.HTTP_429 else null, nowMs = NflHubTestKit.PRE_START - 3_600_000L)
            val slots = listOf(HubSlot(SlotAction.FOLLOW, SlotLabel.FOLLOW, true), HubSlot.EMPTY, HubSlot(SlotAction.MATCHUP, SlotLabel.MATCHUP, false))
            return NflHubUi.slotReasons(res, m.copy(slots = slots), slots)[2]
        }
        assertEquals(res.getString(app.thebox.ui.R.string.sports_hub_reason_matchup_failed), reason(DetailKind.FAILED, null))
        assertEquals(res.getString(app.thebox.ui.R.string.sports_hub_reason_matchup_loading), reason(DetailKind.LOADING, null))
        assertEquals(res.getString(app.thebox.ui.R.string.sports_hub_reason_matchup_none), reason(DetailKind.READY, NflHubTestKit.nflDetail(pre)))
        val live = pre.copy(phase = app.thebox.core.sports.GamePhase.LIVE)
        assertEquals(res.getString(app.thebox.ui.R.string.sports_hub_reason_matchup_after_kickoff), reason(DetailKind.READY, NflHubTestKit.nflDetail(live), live))
    }

    /** (m5) The slot's rule is the sheet's: enabled exactly when [nflMatchupRows] has a focusable row. */
    @Test fun theMatchupRuleMatchesTheSheetsRows() {
        val pre = NflHubTestKit.pre()
        val e = NflHubTestKit.nflDetail(pre)
        val cases = listOf(
            e,
            e.copy(leaders = listOf(app.thebox.core.sports.hub.LeaderPair("passingYards", null, null))),
            e.copy(leaders = listOf(app.thebox.core.sports.hub.LeaderPair("passingTouchdowns", app.thebox.core.sports.hub.Leader("J. Love", 20, null), null))),
            e.copy(leaders = NflHubTestKit.PRE_LEADERS.take(1)),
            e.copy(recent = NflHubTestKit.PRE_RECENT),
            e.copy(injuries = NflHubTestKit.PRE_INJURIES),
            NflHubTestKit.preDetail(pre),
        )
        for (d in cases) assertEquals("$d", nflMatchupRows(res, pre, d).any { it.focusable }, nflMatchupHasRows(d))
    }

    /** (m7) The focused More videos row expires: focus goes to the row that moved into its place, not to the top. */
    @Test fun anExpiringFocusedVideoHandsFocusToItsNeighbour() {
        val g = NflHubTestKit.mnfFinal()
        val at = NflHubTestKit.AFTER_FINAL
        val early = NflHubTestKit.PLAY_CLIPS.first { it.videoId == 50578740L }.copy(expiresMs = at + 3_600_000L)
        val clips = listOf(NflHubTestKit.highlight()) + NflHubTestKit.PLAY_CLIPS.filter { it.videoId != 50578740L } + early
        val d = NflHubTestKit.mnfFinalDetail(g, clips = clips)
        val clock = androidx.compose.runtime.mutableLongStateOf(at)
        rule.setContent {
            BoxTheme { GoalsSheet(g, d, emptyMap(), emptyList(), 50578740L, onPlay = { _, _ -> }, onPlayArchive = {}, onMissingClip = {}, onClose = {}, nowMs = clock.longValue) }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(hubSheetRowTag("video:PLAY:50578740"), useUnmergedTree = true).assertIsFocused()
        clock.longValue = NflHubTestKit.offEnd(early)
        rule.waitForIdle()
        rule.onNodeWithTag(hubSheetRowTag("video:PLAY:50579083"), useUnmergedTree = true).assertIsFocused()
        // The last row leaving hands focus to the one before it.
        fun v(id: Long) = ScoringRow.Video(app.thebox.core.sports.hub.ClipRef(id, app.thebox.core.sports.hub.ClipKind.PLAY, g.id), "t", null, null)
        val left = listOf(ScoringRow.Header("More videos"), v(1), v(2))
        assertEquals(2, scoringNeighbour(left, 3))
        assertEquals("the one that moved up", 1, scoringNeighbour(left, 1))
        assertNull("no focused row: the frame's default", scoringNeighbour(left, -1))
        assertNull(scoringNeighbour(listOf(ScoringRow.Info("x")), 0))
    }

    /** (m12) "Video k of n" counts the session's queue, so a skipped (expired) entry never shifts the numbers. */
    @Test fun videoKOfNComesFromTheQueue() {
        val g = NflHubTestKit.mnfFinal()
        fun ref(id: Long) = app.thebox.core.sports.hub.ClipRef(id, app.thebox.core.sports.hub.ClipKind.PLAY, g.id)
        val queue = listOf(ref(50578630L), ref(50578740L), ref(50579083L))
        // 50578740 expired after OK, so the panel's current list has two entries; the third queue entry plays.
        val videos = NflHubTestKit.PLAY_CLIPS.filter { it.videoId != 50578740L }.sortedBy { it.publishedMs }
        assertEquals("Video 3 of 3 · CIN at WSH · Video: ESPN", clipSubLine(res, ref(50579083L), g, emptyList(), videos, queue, 2))
        assertEquals("without the queue, the list", "Video 2 of 2 · CIN at WSH · Video: ESPN", clipSubLine(res, ref(50579083L), g, emptyList(), videos))
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistOrIsNotDisplayed() {
    val nodes = runCatching { fetchSemanticsNode() }.getOrNull() ?: return
    val b = nodes.boundsInRoot
    assertTrue("expected off screen, at $b", b.height <= 0f || b.width <= 0f || !runCatching { assertIsDisplayed() }.isSuccess)
}
