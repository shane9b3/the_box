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
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.Side
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.InjuryLine
import app.thebox.core.sports.hub.InjuryStatus
import app.thebox.ui.R
import app.thebox.ui.theme.BoxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (NFL hub P3, §3.1, §3.2, §3.5, §3.6) The NFL sheets composed on a 1920 × 1080 window: the Scoring sheet's rows, order,
 * focus on open and on return from a clip, D-pad scrolling past its header rows with the focused row visible, the long
 * play text on one line, OK on a play and on a video; the Box score's line score and tables; the Matchup sheet's sections.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class NflSheetsTest {
    @get:Rule val rule = createComposeRule()
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private val now = NflHubTestKit.AFTER_FINAL
    private val g = NflHubTestKit.mnfFinal()

    private fun press(k: Key) {
        rule.onRoot().performKeyInput { pressKey(k) }
        rule.waitForIdle()
    }

    private fun focused(key: String) = rule.onNodeWithTag(hubSheetRowTag(key), useUnmergedTree = true).assertIsFocused()

    @Test fun theScoringSheetListsPlaysThenVideosAndScrollsWithTheDpad() {
        val played = ArrayList<List<ClipRef>>()
        var noVideo = 0
        var closed = 0
        rule.setContent {
            BoxTheme {
                GoalsSheet(
                    g, NflHubTestKit.mnfFinalDetail(g), emptyMap(), emptyList(), null, onPlay = { q, _ -> played += q }, onPlayArchive = {},
                    onMissingClip = {}, onClose = { closed++ }, nowMs = now, onPlayNoVideo = { noVideo++ },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Scoring · CIN at WSH").assertExists()
        focused("play:4018730781")
        press(Key.DirectionCenter)
        assertEquals("OK on a play: the toast", 1, noVideo)
        assertTrue(played.isEmpty())
        // Down through the ten plays to Game highlights, past the More videos header (not focusable), to the last video.
        repeat(9) { press(Key.DirectionDown) }
        focused("play:4018730790")
        press(Key.DirectionDown)
        focused("video:RECAP:50579688")
        rule.onNodeWithTag(hubSheetRowTag("video:RECAP:50579688"), useUnmergedTree = true).assertIsDisplayed()
        press(Key.DirectionDown)
        focused("video:PLAY:50578630")
        rule.onNodeWithTag(hubSheetRowTag("video:PLAY:50578630"), useUnmergedTree = true).assertIsDisplayed()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        focused("video:PLAY:50579083")
        rule.onNodeWithTag(hubSheetRowTag("video:PLAY:50579083"), useUnmergedTree = true).assertIsDisplayed()
        press(Key.DirectionDown)
        focused("video:PLAY:50579083")
        press(Key.DirectionUp)
        press(Key.DirectionCenter)
        assertEquals("a More videos row and every later one", listOf(listOf(50578740L, 50579083L)), played.map { q -> q.map { it.videoId } })
        press(Key.Back)
        assertEquals(1, closed)
    }

    @Test fun theSheetFocusesTheClipJustPlayedAndTheLatestPlayLive() {
        rule.setContent {
            BoxTheme {
                GoalsSheet(g, NflHubTestKit.mnfFinalDetail(g), emptyMap(), emptyList(), 50578740L, onPlay = { _, _ -> }, onPlayArchive = {}, onMissingClip = {}, onClose = {}, nowMs = now)
            }
        }
        rule.waitForIdle()
        focused("video:PLAY:50578740")
        rule.onNodeWithTag(hubSheetRowTag("video:PLAY:50578740"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun liveTheLatestPlayHasFocus() {
        val live = NflHubTestKit.mnfLive()
        rule.setContent {
            BoxTheme { GoalsSheet(live, NflHubTestKit.mnfLiveDetail(live), emptyMap(), emptyList(), null, onPlay = { _, _ -> }, onPlayArchive = {}, onMissingClip = {}, onClose = {}, nowMs = now) }
        }
        rule.waitForIdle()
        focused("play:4018730786")
    }

    @Test fun aLongPlayTextStaysOnOneLineInItsRow() {
        val d = NflHubTestKit.mnfFinalDetail(plays = listOf(NflHubTestKit.LONG_PLAY), clips = emptyList())
        rule.setContent { BoxTheme { GoalsSheet(g, d, emptyMap(), emptyList(), null, onPlay = { _, _ -> }, onPlayArchive = {}, onMissingClip = {}, onClose = {}, nowMs = now) } }
        rule.waitForIdle()
        val row = rule.onNodeWithTag(hubSheetRowTag("play:4018730799"), useUnmergedTree = true)
        row.assertIsFocused()
        assertEquals("the row keeps its 96 px", 96f, row.fetchSemanticsNode().size.height.toFloat(), 1f)
        val text = rule.onAllNodesWithText(NflHubUi.playText(NflHubTestKit.LONG_PLAY), substring = false, useUnmergedTree = true).onFirst()
        val layouts = ArrayList<TextLayoutResult>()
        text.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals("one line", 1, layouts.single().lineCount)
        assertTrue("ellipsized", layouts.single().isLineEllipsized(0))
        // The spoken form keeps the whole play.
        assertTrue(row.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].single().contains("goal line"))
    }

    @Test fun anInfoOnlySheetHoldsFocusOnItsTitleAndBackCloses() {
        var closed = 0
        val d = NflHubTestKit.mnfFinalDetail(plays = emptyList())
        rule.setContent {
            BoxTheme { GoalsSheet(g, d, emptyMap(), emptyList(), null, onPlay = { _, _ -> }, onPlayArchive = {}, onMissingClip = {}, onClose = { closed++ }, videoOn = false, nowMs = now) }
        }
        rule.waitForIdle()
        rule.onNodeWithText(res.getString(R.string.sports_hub_scoring_final_none)).assertExists()
        rule.onNodeWithText(res.getString(R.string.sports_hub_caption_videos_off_espn)).assertExists()
        rule.onNodeWithTag(HUB_SHEET_FRAME_TAG).assertIsFocused()
        press(Key.Back)
        assertEquals(1, closed)
    }

    @Test fun theNflBoxScoreHasTheLineScoreAndThreeTablesPerTeam() {
        rule.setContent { BoxTheme { BoxScoreSheet(g, Fetch.Ok(NflHubTestKit.mnfBox(), 0L, null, 0), onRetry = {}, onClose = {}) } }
        rule.waitForIdle()
        rule.onNodeWithTag(BOX_LINE_SCORE_TAG).assertExists()
        rule.onAllNodesWithText("Q4").onFirst().assertExists()
        rule.onNodeWithText("Passing").assertExists()
        rule.onNodeWithText("Rushing").assertExists()
        rule.onAllNodesWithText("C/ATT").onFirst().assertExists()
        rule.onNodeWithText("Joe Burrow").assertExists()
        rule.onNodeWithText("108.3").assertExists()
        rule.onNodeWithTag(boxScoreTabTag(Side.AWAY)).assertIsFocused()
        press(Key.DirectionRight)
        rule.onAllNodesWithText("Jayden Daniels").onFirst().assertExists()
        press(Key.DirectionDown)
        repeat(6) { press(Key.DirectionDown) }
        rule.onNodeWithText("Austin Williams").assertIsDisplayed()
    }

    @Test fun aFailedNflBoxScoreNamesEspn() {
        rule.setContent { BoxTheme { BoxScoreSheet(g, Fetch.Failed(FailKind.HTTP_5XX, null, 0L), onRetry = {}, onClose = {}) } }
        rule.waitForIdle()
        rule.onNodeWithText(res.getString(R.string.sports_hub_error_box_score_espn)).assertExists()
    }

    @Test fun theNflMatchupHasLeadersLastFiveAndInjuries() {
        val pre = NflHubTestKit.pre()
        val many = NflHubTestKit.PRE_INJURIES + (1..6).map { InjuryLine(Side.AWAY, "Player Number$it", "LB", InjuryStatus.OUT, null) }
        rule.setContent { BoxTheme { MatchupSheet(pre, NflHubTestKit.preDetail(pre, injuries = many), onClose = {}) } }
        rule.waitForIdle()
        rule.onNodeWithText("Season leaders").assertExists()
        rule.onNodeWithText("Love").assertExists()
        rule.onNodeWithText("2,838 yds · 20 TD · 7 INT").assertExists()
        // Down through the sections to the last row: the list scrolls with the focus.
        repeat(16) { press(Key.DirectionDown) }
        rule.onNodeWithText("C. Shelton · C · Out (hamstring)").assertIsDisplayed()
        rule.onNodeWithText("+3 more").assertExists()
    }

    @Test fun theMatchupWordsFollowTheDesign() {
        val pre = NflHubTestKit.pre()
        val d = NflHubTestKit.preDetail(pre)
        val rows = nflMatchupRows(res, pre, d)
        val recent = rows.filterIsInstance<MatchupRow.Recent>()
        assertEquals("W 23–13 DAL · L 20–17 @ DET · W 27–19 CAR · W 45–27 @ NE · L 30–10 MIN", recent[0].text)
        assertEquals("Packers", recent[0].team)
        val inj = rows.filterIsInstance<MatchupRow.Injury>()
        assertEquals("K. Zeitler · G · Questionable (triceps)", inj[0].text)
        assertEquals("Kevin Zeitler, guard, questionable, triceps", inj[0].desc)
        assertEquals("Travis Glover, offensive tackle, questionable, shoulder", inj[2].desc)
        val sacks = d.leaders.first { it.category == "sacks" }
        assertEquals("5.5 sacks", nflLeaderLine(res, "sacks", sacks.home!!))
        assertEquals("70 rec · 715 yds · 4 TD", nflLeaderLine(res, "receivingYards", d.leaders.first { it.category == "receivingYards" }.home!!))
        assertEquals("616 yds · 6 TD", nflLeaderLine(res, "rushingYards", d.leaders.first { it.category == "rushingYards" }.home!!))
        assertEquals("67 tackles", nflLeaderLine(res, "totalTackles", d.leaders.first { it.category == "totalTackles" }.home!!))
        assertTrue("the slot's rule", hasMatchupRows(d))
        assertEquals("positions unknown are read as written", "XYZ", positionName(res, "XYZ"))
    }

    @Test fun theClipBarSaysVideoEspnOnEveryEspnClip() {
        val videos = NflHubUi.moreVideos(NflHubTestKit.mnfFinalDetail(g), now)
        assertEquals("Game highlights · CIN at WSH · Video: ESPN", clipSubLine(res, ClipRef(50579688, ClipKind.RECAP, g.id), g, emptyList(), videos))
        assertEquals("Video 2 of 3 · CIN at WSH · Video: ESPN", clipSubLine(res, ClipRef(50578740, ClipKind.PLAY, g.id), g, emptyList(), videos))
        assertEquals("CIN at WSH · Video: ESPN", clipSubLine(res, ClipRef(1, ClipKind.PLAY, g.id), g, emptyList(), videos))
        assertEquals("Video: ESPN", clipSubLine(res, ClipRef(1, ClipKind.PLAY, g.id), null, emptyList()))
    }
}
