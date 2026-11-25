package app.thebox.ui.sports

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.full.fakes.FakeArchivePlayback
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.Market
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.hub.BoxScore
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.FakeClipResolver
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.Goalie
import app.thebox.core.sports.hub.GoaliePair
import app.thebox.core.sports.hub.GoalieLine
import app.thebox.core.sports.hub.GoalItem
import app.thebox.core.sports.hub.Leader
import app.thebox.core.sports.hub.LeaderPair
import app.thebox.core.sports.hub.MatchConfidence
import app.thebox.core.sports.hub.MatchReason
import app.thebox.core.sports.hub.SkaterLine
import app.thebox.core.sports.hub.Strength
import app.thebox.core.sports.hub.TeamBox
import app.thebox.ui.shelltest.FakePlaybackController
import app.thebox.ui.theme.BoxTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (Sports Hub, docs/design/m6-sports-hub.md §2.5, §5.3, §5.5, §6.4, §6.5; WP-5) The four sheets and the clip layer on a
 * 1920 × 1080 window: focus on open, what OK does, Back, the §5.3 wording, the goal lines and queues, and the layer's
 * Back hold.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class HubSheetsTest {
    @get:Rule val rule = createComposeRule()
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    private val live = SportsFixtures.game(phase = GamePhase.LIVE, away = SportsFixtures.team("UTA", "Mammoth", 1), home = SportsFixtures.team("WPG", "Jets", 2))
    private val final = live.copy(phase = GamePhase.FINAL)
    private fun goal(n: Int, side: Side, a: Int, h: Int, clip: Long?) = GoalItem(
        PeriodLabel(PeriodKind.REG, 1), "5:1$n", side, "G. Scorer$n", "Scorer$n", n, listOf("Connor", "Morrissey"),
        if (n == 2) Strength.PP else Strength.EV, emptyNet = false, away = a, home = h, clipId = clip, headshot = null,
    )
    private val goals = listOf(goal(1, Side.HOME, 0, 1, 11), goal(2, Side.AWAY, 1, 1, null), goal(3, Side.HOME, 1, 2, 13))
    private fun detail(goals: List<GoalItem> = this.goals) = GameDetail(
        live.id, GamePhase.LIVE, null, 1, 2, null, emptyList(), null, emptyList(), goals, emptyList(),
        leaders = listOf(LeaderPair("goals", Leader("A. Away", 3, null), Leader("H. Home", 4, null)), LeaderPair("points", Leader("P. Away", 5, null), null)),
        goalies = GoaliePair(listOf(Goalie("Vejmelka", "8-4-1", 2.41, 0.915, null)), listOf(Goalie("Hellebuyck", "10-2-0", 2.1, 0.925, null))),
        series = null, recapId = 70, condensedId = 80, broadcasts = emptyList(),
    )

    // ---- pure pieces

    @Test
    fun goalLinesAndQueues() {
        assertEquals("G. Scorer2 (2) · PP", goalLine1(res, goals[1]))
        assertEquals("1st 5:11 · WPG leads 1–0 · Connor, Morrissey", goalLine2(res, live, goals[0]))
        assertEquals("1st 5:12 · Tied 1–1 · Connor, Morrissey · Video soon", goalLine2(res, live, goals[1]))
        assertEquals("from goal 1: it and every later goal with a clip", listOf(11L, 13L), goalQueue(live, goals, 0).map { it.videoId })
        assertTrue("a goal without a clip has no queue", goalQueue(live, goals, 1).isEmpty())
        val rows = goalsSheetRows(detail(), emptyList(), final = true)
        assertEquals(listOf("goal:0", "goal:1", "goal:2", "video:RECAP:70", "video:CONDENSED:80"), rows.map { it.key })
        assertEquals("live: the latest goal", 2, goalsSheetInitialRow(goalsSheetRows(detail(), emptyList(), false), null, final = false))
        assertEquals("final: the first", 0, goalsSheetInitialRow(rows, null, final = true))
        assertEquals("U-S14: the clip just played", 3, goalsSheetInitialRow(rows, 70, final = true))
    }

    @Test
    fun channelWording() {
        val ch = GuideFixtures.channel(1, name = "CA| SPORTSNET ONTARIO HD")
        fun cand(conf: MatchConfidence, vararg r: MatchReason) = ChannelCandidate(ch, "P", conf, r.toList(), emptyList(), null)
        assertEquals(
            "Sportsnet carries this game in Canada.",
            channelReason(res, cand(MatchConfidence.LIKELY, MatchReason.Broadcaster("Sportsnet", Market.NATIONAL, "CA")), live, false),
        )
        // (Event channels §9.1) No "local channel" wording: a regional broadcaster is a candidate only on guide evidence,
        // and a row without a nameable network says so plainly (U-E-N6).
        assertEquals("A national broadcaster carries this game.", channelReason(res, cand(MatchConfidence.POSSIBLE, MatchReason.Broadcaster("SNO", Market.HOME, "CA")), live, false))
        assertEquals("A national broadcaster carries this game.", channelReason(res, cand(MatchConfidence.POSSIBLE), live, false))
        assertEquals("A national broadcaster carries this game.", channelReason(res, cand(MatchConfidence.POSSIBLE, MatchReason.Broadcaster("X1", Market.NATIONAL, null)), live, false))
        assertTrue(channelReason(res, cand(MatchConfidence.CONFIRMED, MatchReason.Guide("NHL Hockey", 0, 9_000_000, false)), live, true).let { it.startsWith("In your guide, ") && it.endsWith(": NHL Hockey") })
        assertEquals("Sportsnet carries this game.", channelReason(res, cand(MatchConfidence.LIKELY, MatchReason.Broadcaster("Sportsnet", Market.NATIONAL, null)), live, false))
        assertTrue(channelReason(res, cand(MatchConfidence.LIKELY, MatchReason.SportListing("NHL Hockey", 0)), live, true).let { it.startsWith("In your guide at ") && it.endsWith(": NHL Hockey") })
        val v = ChannelCandidate(ch, "P", MatchConfidence.CONFIRMED, emptyList(), listOf(GuideFixtures.channel(2, name = "CA| SPORTSNET ONTARIO FHD")), null)
        assertEquals("Also: FHD", alsoLine(res, v))
        assertEquals(".915", svPct(0.915))
    }

    // ---- composed

    @Test
    fun goalsSheetFocusesTheLatestGoalPlaysItsQueueAndCloses() {
        val played = ArrayList<List<ClipRef>>()
        var missing = 0
        var closed = 0
        rule.setContent {
            BoxTheme {
                GoalsSheet(live, detail(), emptyMap(), emptyList(), null, onPlay = { q, _ -> played += q }, onPlayArchive = {}, onMissingClip = { missing++ }, onClose = { closed++ })
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(hubSheetRowTag("goal:2"), useUnmergedTree = true).assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(listOf(listOf(ClipRef(13, ClipKind.GOAL, live.id))), played)
        rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals("OK on a goal without a clip", 1, missing)
        rule.onRoot().performKeyInput { pressKey(Key.Back) }
        rule.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun channelSheetTunesOrSaysNotYet() {
        val ch = GuideFixtures.channel(1, name = "Sportsnet Ontario")
        val c = ChannelCandidate(ch, "P", MatchConfidence.CONFIRMED, emptyList(), emptyList(), null)
        val tuned = ArrayList<ChannelCandidate>()
        var notYet = 0
        val tunable = androidx.compose.runtime.mutableStateOf(true)
        rule.setContent {
            BoxTheme { ChannelSheet(live, listOf(c), tunable.value, false, false, onTune = { tuned += it }, onPlayArchive = {}, onNotYet = { notYet++ }, onClose = {}) }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(hubSheetRowTag("ch:0"), useUnmergedTree = true).assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(listOf(c), tuned)
        tunable.value = false
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(1, notYet)
        // (Event channels §9.1) No footer note: the regional note went with the regional guesses.
        assertTrue(rule.onAllNodesWithText("Regional channels", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    /**
     * (Owner rulings 2026-11-25, event channels §18) The sheet lists every matched channel: a long list scrolls with the
     * D-pad (the focused row stays shown, focus never leaves the list at either end), the feeds of one channel are
     * separate rows in order, and a following feed says it is another feed of the channel above.
     */
    @Test
    fun aLongChannelListScrollsAndEveryFeedIsARow() {
        val rows = (0 until 30).map { i ->
            ChannelCandidate(
                GuideFixtures.channel(100 + i, name = "Channel $i"), "P", MatchConfidence.CONFIRMED,
                listOf(MatchReason.Broadcaster("TNT", Market.NATIONAL, null)), emptyList(), null, anotherFeed = i in 1..2,
            )
        }
        val tuned = ArrayList<ChannelCandidate>()
        rule.setContent {
            BoxTheme { ChannelSheet(live, rows, true, false, false, onTune = { tuned += it }, onPlayArchive = {}, onNotYet = {}, onClose = {}) }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(hubSheetRowTag("ch:0"), useUnmergedTree = true).assertIsFocused()
        assertTrue(rule.onAllNodesWithText("Same channel, another feed", useUnmergedTree = true).fetchSemanticsNodes().size == 2)
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithTag(hubSheetRowTag("ch:1"), useUnmergedTree = true).assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals("a following feed is selectable", listOf(rows[1]), tuned)
        for (i in 2 until 30) {
            rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitForIdle()
            rule.onNodeWithTag(hubSheetRowTag("ch:$i"), useUnmergedTree = true).assertIsFocused().assertIsDisplayed()
        }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithTag(hubSheetRowTag("ch:29"), useUnmergedTree = true).assertIsFocused()
        assertTrue("the list is lazy: the first row is no longer composed", rule.onAllNodesWithTag(hubSheetRowTag("ch:0"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        for (i in 28 downTo 0) {
            rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
            rule.waitForIdle()
            rule.onNodeWithTag(hubSheetRowTag("ch:$i"), useUnmergedTree = true).assertIsFocused().assertIsDisplayed()
        }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        rule.waitForIdle()
        rule.onNodeWithTag(hubSheetRowTag("ch:0"), useUnmergedTree = true).assertIsFocused()
    }

    @Test
    fun boxScoreFocusesTheAwayTabSwitchesAndRetries() {
        val box = BoxScore(
            TeamBox(listOf(SkaterLine("C. Keller", "C", 1, 1, 2, 1, 4, "18:20"), SkaterLine("M. Sergachev", "D", 0, 1, 1, -1, 2, "24:01")), listOf(GoalieLine("Vejmelka", 30, 28, 0.933, "60:00"))),
            TeamBox(listOf(SkaterLine("K. Connor", "R", 1, 0, 1, 0, 5, "19:00")), emptyList()),
        )
        val fetch = androidx.compose.runtime.mutableStateOf<Fetch<BoxScore>?>(null)
        var retries = 0
        rule.setContent { BoxTheme { BoxScoreSheet(final, fetch.value, onRetry = { retries++ }, onClose = {}) } }
        rule.waitForIdle()
        rule.onNodeWithTag(boxScoreTabTag(Side.AWAY), useUnmergedTree = true).assertIsFocused()
        fetch.value = Fetch.Ok(box, 0, null, 0)
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        rule.onNodeWithTag(boxScoreTabTag(Side.HOME), useUnmergedTree = true).assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithTag(hubSheetRowTag("sk:2"), useUnmergedTree = true).assertIsFocused()
        // (Review H1, M8) A failure replaces the rows under the focused skater: focus goes to Retry, not nowhere.
        fetch.value = Fetch.Failed(app.thebox.core.sports.FailKind.IO, null, 0)
        rule.waitForIdle()
        rule.onNodeWithTag(hubSheetRowTag("retry"), useUnmergedTree = true).assertIsFocused()
        assertEquals(listOf(BoxRow.Error, BoxRow.Retry), boxRows(null, failed = true))
        assertEquals(0, retries)
    }

    /**
     * (Review H1) OK on Retry: the view model sets the sheet back to loading, which removes the focused Retry row. Focus must
     * land on the shown team's tab (and on Retry again if the retry fails), so Back still closes the sheet.
     */
    @Test
    fun retryInTheBoxScoreKeepsFocusInTheSheetAndBackStillCloses() {
        val fetch = androidx.compose.runtime.mutableStateOf<Fetch<BoxScore>?>(Fetch.Failed(app.thebox.core.sports.FailKind.TIMEOUT, null, 0))
        var retries = 0
        var closed = 0
        rule.setContent { BoxTheme { BoxScoreSheet(final, fetch.value, onRetry = { retries++; fetch.value = null }, onClose = { closed++ }) } }
        rule.waitForIdle()
        rule.onNodeWithTag(boxScoreTabTag(Side.AWAY), useUnmergedTree = true).assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithTag(hubSheetRowTag("retry"), useUnmergedTree = true).assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals("OK on Retry retries", 1, retries)
        rule.onNodeWithTag(boxScoreTabTag(Side.AWAY), useUnmergedTree = true).assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.Back) }
        rule.waitForIdle()
        assertEquals("Back closes the sheet after a retry", 1, closed)
    }

    /** (Review H2) Under a clip ([LocalHubFocusable] false) a change of the rows never pulls focus into the hidden sheet. */
    @Test
    fun aHiddenGoalsSheetTakesNoFocusWhenItsRowsChange() {
        val d = androidx.compose.runtime.mutableStateOf(detail(goals.take(2)))
        val focusable = androidx.compose.runtime.mutableStateOf(true)
        val other = androidx.compose.ui.focus.FocusRequester()
        rule.setContent {
            BoxTheme {
                androidx.compose.foundation.layout.Box {
                    androidx.compose.runtime.CompositionLocalProvider(LocalHubFocusable provides focusable.value) {
                        GoalsSheet(live, d.value, emptyMap(), emptyList(), null, onPlay = { _, _ -> }, onPlayArchive = {}, onMissingClip = {}, onClose = {})
                    }
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.testTag("other").focusRequester(other).focusable(),
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(hubSheetRowTag("goal:1"), useUnmergedTree = true).assertIsFocused()
        focusable.value = false
        rule.runOnIdle { other.requestFocus() }
        rule.waitForIdle()
        rule.onNodeWithTag("other").assertIsFocused()
        d.value = detail(goals) // a new goal arrives while the clip plays
        rule.waitForIdle()
        rule.onNodeWithTag("other").assertIsFocused()
        rule.onNodeWithTag(hubSheetRowTag("goal:2"), useUnmergedTree = true).assertIsNotFocused()
    }

    @Test
    fun matchupRowsAndFocus() {
        val rows = matchupRows(detail())
        assertTrue("points first", (rows[1] as MatchupRow.Leaders).pair.category == "points")
        assertEquals("header, points, goals, header, one goalie row", 5, rows.size)
        rule.setContent { BoxTheme { MatchupSheet(live, detail(), onClose = {}) } }
        rule.waitForIdle()
        rule.onNodeWithTag(hubSheetRowTag("lead:1"), useUnmergedTree = true).assertIsFocused()
    }

    /**
     * (Verification review MED-1) A sheet with no row (a Matchup without leaders or goalies, a Goals sheet before its
     * detail): the frame's title takes focus, so Back closes it; goal rows arriving then take focus from the title.
     */
    @Test
    fun aSheetWithoutRowsHoldsFocusInItsFrameAndBackCloses() {
        var closed = 0
        val empty = detail().copy(leaders = emptyList(), goalies = null)
        rule.setContent { BoxTheme { MatchupSheet(live, empty, onClose = { closed++ }) } }
        rule.waitForIdle()
        rule.onNodeWithTag(HUB_SHEET_FRAME_TAG, useUnmergedTree = true).assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.Back) }
        rule.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun aGoalsSheetWithoutItsDetailHoldsFocusThenHandsItToTheRows() {
        var closed = 0
        val d = androidx.compose.runtime.mutableStateOf<GameDetail?>(null)
        rule.setContent {
            BoxTheme { GoalsSheet(live, d.value, emptyMap(), emptyList(), null, onPlay = { _, _ -> }, onPlayArchive = {}, onMissingClip = {}, onClose = { closed++ }, note = "Loading") }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(HUB_SHEET_FRAME_TAG, useUnmergedTree = true).assertIsFocused()
        rule.onNodeWithTag(GOALS_SHEET_NOTE_TAG, useUnmergedTree = true).assertExists()
        d.value = detail()
        rule.waitForIdle()
        rule.onNodeWithTag(hubSheetRowTag("goal:2"), useUnmergedTree = true).assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.Back) }
        rule.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun aFinalGoalsSheetWithoutItsDetailListsTheBoardsVideos() {
        val media = app.thebox.core.sports.GameMedia(recapId = 70L, condensedId = 80L, goalClips = 3)
        assertEquals(listOf("video:RECAP:70", "video:CONDENSED:80"), goalsSheetRows(null, emptyList(), final = true, media = media).map { it.key })
        assertTrue("live: nothing to list before the detail", goalsSheetRows(null, emptyList(), final = false, media = media).isEmpty())
    }

    @Test
    fun clipLayerHoldsBackAndBackEndsTheSession() {
        val controller = FakePlaybackController()
        val session = ClipSession(controller, FakeArchivePlayback(), FakeClipResolver(), CoroutineScope(SupervisorJob() + Dispatchers.Main))
        val backFree = ArrayList<Boolean>()
        val shown = androidx.compose.runtime.mutableStateOf(true)
        rule.setContent { BoxTheme { if (shown.value) ClipPlayerLayer(session, onBackFreeChanged = { backFree += it }, game = live, goals = goals) } }
        rule.waitForIdle()
        assertEquals(listOf(false), backFree)
        rule.onNodeWithTag(CLIP_LAYER_TAG).assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionLeft) } // consumed, never moves focus
        rule.onNodeWithTag(CLIP_LAYER_TAG).assertIsFocused()
        shown.value = false
        rule.waitForIdle()
        assertEquals(listOf(false, true), backFree)
        assertEquals("Goal 3 of 3 · UTA at WPG · 1st 5:13", clipSubLine(res, ClipRef(13, ClipKind.GOAL, live.id), live, goals))
        assertEquals("Game recap · UTA at WPG", clipSubLine(res, ClipRef(70, ClipKind.RECAP, live.id), live, goals))
    }
}
