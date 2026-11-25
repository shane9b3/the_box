package app.thebox.ui.sports

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.settings.TextSize
import app.thebox.core.settings.UiTextScale
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.GameMedia
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.hub.ClipInfo
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.GoalItem
import app.thebox.core.sports.hub.Strength
import app.thebox.ui.R
import app.thebox.ui.theme.BoxTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * (Sports Hub code review 2026-11-25: H3, M4, M5, M8, L2, L3) The panel's words and media for the states the review
 * named, and the headline as composed (not only the plan's arithmetic) at Default and Largest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HubPanelContentsTest {
    @get:Rule val rule = createComposeRule()
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @After fun resetScale() { UiTextScale.running = TextSize.DEFAULT }

    private val live = SportsFixtures.game(id = "L1", phase = GamePhase.LIVE)
    private val pre = SportsFixtures.game(id = "P1", phase = GamePhase.PRE, period = null, periodKind = null, clock = null)
    private val final = SportsFixtures.game(id = "X1", phase = GamePhase.FINAL, finalKind = PeriodKind.REG, clock = null)

    private fun goal(clip: Long?) = GoalItem(PeriodLabel(PeriodKind.REG, 1), "5:11", Side.HOME, "G. Vilardi", "Vilardi", 4, emptyList(), Strength.EV, false, 0, 1, clip, null)
    private fun detail(g: GameSnapshot, goals: List<GoalItem> = emptyList(), recap: Long? = null) = GameDetail(
        g.id, g.phase, null, null, null, null, emptyList(), null, emptyList(), goals, emptyList(), emptyList(), null, null, recap, null, emptyList(),
    )

    // (NFL hub commit 0) `build` takes a text measure (the NFL's fitted summary lines); the NHL words never read it.
    private val measure: Measure = { text, px, _ -> text.length * px * 0.5f }
    private fun content(m: HubPanelModel, factor: Float = 1f) = HubPanelContents.build(res, m, clock24 = false, factor = factor, measure = measure)!!

    // ------------------------------------------------------------------ H3: the failure's next step is on screen

    @Test fun aLiveFailureWithFrozenSlotsNeverNamesWatchRecap() {
        // OK froze the slots while the detail loaded (slot 1 Follow), then the fetch timed out.
        val frozen = listOf(HubSlot(SlotAction.FOLLOW, SlotLabel.FOLLOW, true), HubSlot(SlotAction.CHANNEL, SlotLabel.FINDING, false), HubSlot(SlotAction.GOALS, SlotLabel.GOALS, false))
        val m = HubPanelModel(live, detailKind = DetailKind.FAILED, failure = FailKind.TIMEOUT, slots = frozen)
        assertEquals(res.getString(R.string.sports_hub_error_detail_back), content(m).error)
        // A pre-game game, the same.
        assertEquals(res.getString(R.string.sports_hub_error_detail_back), content(HubPanelModel(pre, detailKind = DetailKind.FAILED, failure = FailKind.HTTP_5XX, slots = frozen)).error)
        // Unfrozen: slot 1 is Retry, and the text says so.
        val retry = listOf(HubSlot(SlotAction.RETRY, SlotLabel.RETRY, true)) + frozen.drop(1)
        assertEquals(res.getString(R.string.sports_hub_error_detail_retry), content(m.copy(slots = retry)).error)
    }

    @Test fun aFinalFailureNamesWatchRecapOnlyWhileItIsUsable() {
        val recap = final.copy(media = GameMedia(recapId = 77L, condensedId = null, goalClips = 2))
        val slots = listOf(HubSlot(SlotAction.WATCH_RECAP, SlotLabel.WATCH_RECAP, true), HubSlot(SlotAction.GOALS, SlotLabel.GOALS_N, true, 2), HubSlot(SlotAction.BOX_SCORE, SlotLabel.BOX_SCORE, true))
        val m = HubPanelModel(recap, detailKind = DetailKind.FAILED, failure = FailKind.TIMEOUT, slots = slots)
        assertEquals(res.getString(R.string.sports_hub_error_detail_auto), content(m).error)
        // Videos off: Watch recap is disabled, so the text must not name it.
        val off = listOf(slots[0].copy(enabled = false)) + slots.drop(1)
        assertEquals(res.getString(R.string.sports_hub_error_detail_back), content(m.copy(slots = off)).error)
    }

    // ------------------------------------------------------------------ M4: a poster only with a picture, never while videos are off

    @Test fun thePosterNeedsAPictureAndVideosOn() {
        val d = detail(live, listOf(goal(55L)))
        val bare = HubPanelModel(live, detail = d, detailKind = DetailKind.READY)
        assertTrue("no lookup yet: matchup art with its disc and the goals caption", content(bare).media is MediaSpec.Art)
        assertEquals(res.getString(R.string.sports_hub_caption_goals_soon), (content(bare).media as MediaSpec.Art).caption)
        val info = ClipInfo(ClipRef(55L, ClipKind.GOAL, live.id), "Vilardi strikes first", null, 46_000L, "https://img.test/p.jpg", null)
        val noPicture = info.copy(posterUrl = null)
        assertTrue("a lookup without a picture: still the art", content(HubPanelModel(live, detail = d, detailKind = DetailKind.READY, latestGoal = noPicture)).media is MediaSpec.Art)
        val pictured = HubPanelModel(live, detail = d, detailKind = DetailKind.READY, latestGoal = info)
        assertTrue(content(pictured).media is MediaSpec.Poster)
        val off = HubPanelModel(live, detail = d, detailKind = DetailKind.READY, latestGoal = info, videoOff = true)
        val art = content(off).media as MediaSpec.Art
        assertEquals("videos off: no play disc, no tag, the reason", res.getString(R.string.sports_hub_caption_videos_off), art.caption)
        // A final game's recap, the same rules.
        val fd = detail(final, recap = 70L)
        assertTrue(content(HubPanelModel(final, detail = fd, detailKind = DetailKind.READY)).media is MediaSpec.Art)
        val recapInfo = ClipInfo(ClipRef(70L, ClipKind.RECAP, final.id), "Recap", null, 295_000L, null, "https://img.test/t.jpg")
        assertTrue(content(HubPanelModel(final, detail = fd, detailKind = DetailKind.READY, recap = recapInfo)).media is MediaSpec.Poster)
        assertTrue(content(HubPanelModel(final, detail = fd, detailKind = DetailKind.READY, recap = recapInfo, videoOff = true)).media is MediaSpec.Art)
    }

    @Test fun thePlayDiscStaysInTheTallerPanelAtEverySize() {
        val d = detail(live, listOf(goal(55L)))
        val info = ClipInfo(ClipRef(55L, ClipKind.GOAL, live.id), "Vilardi strikes first", null, 46_000L, "https://img.test/p.jpg", null)
        val m = HubPanelModel(live, detail = d, detailKind = DetailKind.READY, latestGoal = info)
        val size = androidx.compose.runtime.mutableStateOf(TextSize.DEFAULT)
        rule.setContent {
            UiTextScale.running = size.value
            BoxTheme { androidx.compose.runtime.key(size.value) { HubPanel(m, false, androidx.compose.runtime.remember { List(3) { FocusRequester() } }, {}, {}, {}, entered = false) } }
        }
        rule.waitForIdle()
        assertTrue("Default: ${content(m).plan.mediaH} px", content(m).plan.mediaH >= HubPanelPlan.POSTER_DISC_MIN_H)
        rule.onNodeWithTag(HUB_PLAY_DISC_TAG, useUnmergedTree = true).assertExists()
        size.value = TextSize.LARGEST
        rule.waitForIdle()
        // Owner change 2026-11-25: the 864-high panel gives the media 52 px more, so the disc now fits at Largest too (the
        // drop rule, heightDp >= POSTER_DISC_MIN_H, is unchanged).
        assertTrue("Largest: ${content(m, 1.2f).plan.mediaH} px", content(m, 1.2f).plan.mediaH >= HubPanelPlan.POSTER_DISC_MIN_H)
        rule.onNodeWithTag(HUB_PLAY_DISC_TAG, useUnmergedTree = true).assertExists()
    }

    // ------------------------------------------------------------------ M5 / M8: the headline as composed

    private fun overflows(tag: String): Boolean {
        val node = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val results = ArrayList<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single().hasVisualOverflow
    }

    @Test fun theComposedHeadlineNeverCutsANameThatThePlanSaysFits() {
        val pairs = listOf(
            SportsFixtures.team("TOR", "Maple Leafs", 1) to SportsFixtures.team("BOS", "Bruins", 1),
            SportsFixtures.team("CBJ", "Blue Jackets", 10) to SportsFixtures.team("VGK", "Golden Knights", 12),
            SportsFixtures.team("VGK", "Golden Knights", 3) to SportsFixtures.team("TOR", "Maple Leafs", 2),
            SportsFixtures.team("TOR", "Maple Leafs", 4) to SportsFixtures.team("CBJ", "Blue Jackets", 4),
            SportsFixtures.team("MTL", "Canadiens", 0) to SportsFixtures.team("CBJ", "Blue Jackets", 0),
        )
        val model = androidx.compose.runtime.mutableStateOf(HubPanelModel(live))
        val size = androidx.compose.runtime.mutableStateOf(TextSize.DEFAULT)
        rule.setContent {
            UiTextScale.running = size.value
            BoxTheme { androidx.compose.runtime.key(size.value) { HubPanel(model.value, false, androidx.compose.runtime.remember { List(3) { FocusRequester() } }, {}, {}, {}, entered = false) } }
        }
        for (s in listOf(TextSize.DEFAULT, TextSize.LARGEST)) {
            size.value = s
            for ((a, h) in pairs) {
                for (phase in listOf(GamePhase.LIVE, GamePhase.PRE)) {
                    val g = SportsFixtures.game(id = "H", phase = phase, away = a, home = h, clock = null)
                    model.value = HubPanelModel(g, awayScore = a.score, homeScore = h.score)
                    rule.waitForIdle()
                    assertFalse("${a.name} at ${h.name}, $phase, $s: the away name is cut", overflows(HUB_HEADLINE_AWAY_TAG))
                    assertFalse("${a.name} at ${h.name}, $phase, $s: the home name is cut", overflows(HUB_HEADLINE_HOME_TAG))
                }
            }
        }
    }

    // ------------------------------------------------------------------ L2, L3

    @Test fun theHeadlineWordsComeFromTheStringsFile() {
        val g = SportsFixtures.game(id = "H", away = SportsFixtures.team("TOR", "Maple Leafs", 1), home = SportsFixtures.team("BOS", "Bruins", 2))
        rule.setContent { BoxTheme { HubPanel(HubPanelModel(g, awayScore = 1, homeScore = 2), false, androidx.compose.runtime.remember { List(3) { FocusRequester() } }, {}, {}, {}, entered = false) } }
        rule.waitForIdle()
        val desc = rule.onNodeWithTag(HUB_HEADLINE_TAG).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].single()
        assertEquals(res.getString(R.string.sports_hub_desc_headline_score, "Toronto Maple Leafs", 1, "Boston Bruins", 2), desc)
        assertEquals(" (1) vs. ", HubFit.HeadlineWords.of(res).middle(1, 2))
        assertEquals(" (2)", HubFit.HeadlineWords.of(res).tail(2))
    }

    @Test fun twoModelsThatDifferOnlyInTheirClockAreEqual() {
        val a = HubPanelModel(live, nowMs = 1_000L, imminent = true)
        val b = HubPanelModel(live, nowMs = 2_000L, imminent = true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals("what the clock decides is still compared", a, HubPanelModel(live, nowMs = 2_000L, imminent = false))
        assertEquals("copy keeps the clock", 1_000L, a.copy(slots = a.slots).nowMs)
    }
}
