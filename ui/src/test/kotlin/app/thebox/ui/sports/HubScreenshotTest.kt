package app.thebox.ui.sports

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.settings.TextSize
import app.thebox.core.settings.UiTextScale
import app.thebox.core.sports.GameClock
import app.thebox.core.sports.GameMedia
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.Market
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.PeriodLabel
import app.thebox.core.sports.Side
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.SportsFixtures.team
import app.thebox.core.sports.hub.ChannelCandidate
import app.thebox.core.sports.hub.ClipInfo
import app.thebox.core.sports.hub.ClipKind
import app.thebox.core.sports.hub.ClipRef
import app.thebox.core.sports.hub.GameDetail
import app.thebox.core.sports.hub.GoalItem
import app.thebox.core.sports.hub.Last10
import app.thebox.core.sports.hub.Leader
import app.thebox.core.sports.hub.LeaderPair
import app.thebox.core.sports.hub.MatchConfidence
import app.thebox.core.sports.hub.MatchReason
import app.thebox.core.sports.hub.PeriodScore
import app.thebox.core.sports.hub.Ranked
import app.thebox.core.sports.hub.SeasonComparison
import app.thebox.core.sports.hub.SeriesFacts
import app.thebox.core.sports.hub.Star
import app.thebox.core.sports.hub.StatKind
import app.thebox.core.sports.hub.StatRow
import app.thebox.core.sports.hub.StatValue
import app.thebox.core.sports.hub.Strength
import app.thebox.ui.theme.BoxTheme
import app.thebox.ui.theme.DesignTokens
import java.io.File
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * (Sports Hub §15.4, code review 2026-11-25 M8) Goldens for the hub: the detail panel live, final and pre-game, and one
 * row of two cards, each at Default and Largest text. Written to `ui/build/sports-tab/hub-*.png`, which `sportsHashGate`
 * checks against `sports-hashes.txt`. The clock is pinned: the model's `nowMs`, the time zone (America/New_York) and the
 * locale (en-US); no image loader is set, so logos show their codes and posters their backdrop (stable bytes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HubScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val out = File("build/sports-tab").apply { mkdirs() }
    private var zone: TimeZone? = null
    private var locale: Locale? = null

    @Before fun pin() {
        zone = TimeZone.getDefault(); locale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        Locale.setDefault(Locale.US)
    }

    @After fun unpin() {
        UiTextScale.running = TextSize.DEFAULT
        zone?.let { TimeZone.setDefault(it) }
        locale?.let { Locale.setDefault(it) }
    }

    /** 2026-11-06 19:55 UTC (as the tab's screenshot). */
    private val now = 1_793_997_300_000L

    private fun goal(n: Int, side: Side, a: Int, h: Int, clip: Long?, period: Int = 1, strength: Strength = Strength.EV) = GoalItem(
        PeriodLabel(PeriodKind.REG, period), "${n + 3}:1$n", side, "G. Scorer$n", "Scorer$n", n + 2, listOf("Connor", "Morrissey"),
        strength, false, a, h, clip, null,
    )

    private val stats = listOf(
        StatRow(StatKind.SOG, StatValue.Count(24), StatValue.Count(19)),
        StatRow(StatKind.POWER_PLAY, StatValue.Fraction(1, 3), StatValue.Fraction(0, 2)),
        StatRow(StatKind.HITS, StatValue.Count(18), StatValue.Count(22)),
        StatRow(StatKind.BLOCKED, StatValue.Count(9), StatValue.Count(11)),
        StatRow(StatKind.FACEOFF_PCT, StatValue.Pct(0.542), StatValue.Pct(0.458)),
    )

    private val channel = ChannelCandidate(
        GuideFixtures.channel(1, name = "Sportsnet Ontario"), "Main", MatchConfidence.LIKELY,
        listOf(MatchReason.Broadcaster("Sportsnet", Market.NATIONAL, "CA")), emptyList(), null,
    )

    private val live = SportsFixtures.game(
        id = "G1", phase = GamePhase.LIVE, away = team("TOR", "Maple Leafs", 2), home = team("BOS", "Bruins", 1),
        period = 2, clock = GameClock(951, running = true, inIntermission = false), startUtcMs = now - 3_600_000L,
    )
    private val final = SportsFixtures.game(
        id = "G2", phase = GamePhase.FINAL, away = team("VGK", "Golden Knights", 5), home = team("EDM", "Oilers", 3),
        period = 3, clock = null, finalKind = PeriodKind.REG, startUtcMs = now - 4 * 3_600_000L,
    ).copy(media = GameMedia(recapId = 70L, condensedId = 80L, goalClips = 8))
    private val pre = SportsFixtures.game(
        id = "G3", phase = GamePhase.PRE, away = team("CBJ", "Blue Jackets"), home = team("MTL", "Canadiens"),
        period = null, periodKind = null, clock = null, startUtcMs = now + 2 * 3_600_000L,
    )

    private fun detailOf(
        g: app.thebox.core.sports.GameSnapshot, goals: List<GoalItem> = emptyList(), periods: List<PeriodScore> = emptyList(),
        season: SeasonComparison? = null, stars: List<Star> = emptyList(), leaders: List<LeaderPair> = emptyList(),
        series: SeriesFacts? = null, recap: Long? = null,
    ) = GameDetail(
        g.id, g.phase, null, g.away.score, g.home.score, "Scotiabank Arena", if (g.phase == GamePhase.PRE) emptyList() else stats, season, periods,
        goals, stars, leaders, null, series, recap, null, emptyList(),
    )

    private fun slot(a: SlotAction, l: SlotLabel, enabled: Boolean = true, n: Int = 0) = HubSlot(a, l, enabled, n)

    private fun liveModel(): HubPanelModel {
        val goals = listOf(goal(1, Side.AWAY, 1, 0, 11), goal(2, Side.HOME, 1, 1, 12, strength = Strength.PP), goal(3, Side.AWAY, 2, 1, 13, period = 2))
        val d = detailOf(live, goals, listOf(PeriodScore(PeriodLabel(PeriodKind.REG, 1), 1, 1), PeriodScore(PeriodLabel(PeriodKind.REG, 2), 1, 0)))
        val info = ClipInfo(ClipRef(13, ClipKind.GOAL, live.id), "Matthews snipes it", null, 46_000L, "https://img.test/goal.jpg", null)
        return HubPanelModel(
            live, detail = d, detailKind = DetailKind.READY, awayScore = 2, homeScore = 1, match = MatchState.Done(listOf(channel, channel.copy(playlistName = "Backup"))),
            latestGoal = info, nowMs = now, imminent = true,
            slots = listOf(slot(SlotAction.FOLLOW, SlotLabel.FOLLOW), slot(SlotAction.CHANNEL, SlotLabel.CHANNELS_N, n = 2), slot(SlotAction.GOALS, SlotLabel.GOALS_N, n = 3)),
        )
    }

    private fun finalModel(): HubPanelModel {
        val goals = (1..8).map { n -> goal(n, if (n % 3 == 0) Side.HOME else Side.AWAY, 0, 0, 100L + n, period = (n + 2) / 3) }
        val stars = listOf(Star(1, "Jack Eichel", "Eichel", "VGK", null), Star(2, "Connor McDavid", "McDavid", "EDM", null), Star(3, "Mark Stone", "Stone", "VGK", null))
        val d = detailOf(final, goals, stars = stars, recap = 70L)
        val recap = ClipInfo(ClipRef(70, ClipKind.RECAP, final.id), "Recap: VGK @ EDM", null, 295_000L, null, "https://img.test/recap.jpg")
        return HubPanelModel(
            final, detail = d, detailKind = DetailKind.READY, awayScore = 5, homeScore = 3, match = MatchState.Done(emptyList()), recap = recap, nowMs = now,
            imminent = true,
            slots = listOf(slot(SlotAction.WATCH_RECAP, SlotLabel.WATCH_RECAP), slot(SlotAction.GOALS, SlotLabel.GOALS_N, n = 8), slot(SlotAction.BOX_SCORE, SlotLabel.BOX_SCORE)),
        )
    }

    private fun preModel(): HubPanelModel {
        val season = SeasonComparison(
            "12-6-2", "10-8-3", Last10("6-3-1", "W", 2), Last10("4-5-1", "L", 1),
            Ranked(3.21, 8), Ranked(2.95, 17), Ranked(2.88, 11), Ranked(3.10, 21), Ranked(0.243, 9), Ranked(0.198, 22), Ranked(0.812, 7), Ranked(0.776, 19),
        )
        val leaders = listOf(LeaderPair("points", Leader("Zach Werenski", 9, null), Leader("Nick Suzuki", 8, null)))
        val d = detailOf(pre, season = season, leaders = leaders, series = SeriesFacts(1, 0, 1))
        return HubPanelModel(
            pre, detail = d, detailKind = DetailKind.READY, match = MatchState.Done(listOf(channel)), nowMs = now, imminent = false,
            slots = listOf(slot(SlotAction.FOLLOW, SlotLabel.FOLLOW), slot(SlotAction.CHANNEL, SlotLabel.CHANNELS_N, n = 1), slot(SlotAction.MATCHUP, SlotLabel.MATCHUP)),
        )
    }

    private fun save(name: String, tag: String) {
        rule.waitForIdle()
        val bmp = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("$name.png", File(out, "$name.png").length() > 2_000)
    }

    @Test
    fun thePanelLiveFinalAndPreGameAtDefaultAndLargest() {
        val model = mutableStateOf(liveModel())
        val size = mutableStateOf(TextSize.DEFAULT)
        UiTextScale.running = size.value
        rule.setContent {
            UiTextScale.running = size.value
            BoxTheme {
                key(size.value) {
                    Box(Modifier.testTag(SHOT).background(DesignTokens.Colors.Canvas).padding(16.dp)) {
                        HubPanel(model.value, clock24 = false, slotRequesters = androidx.compose.runtime.remember { List(3) { FocusRequester() } }, onSlot = {}, onSlotFocus = {}, onExit = {}, entered = false)
                    }
                }
            }
        }
        for ((s, label) in listOf(TextSize.DEFAULT to "default", TextSize.LARGEST to "largest")) {
            size.value = s
            for ((phase, m) in listOf("live" to liveModel(), "final" to finalModel(), "pre" to preModel())) {
                model.value = m
                save("hub-panel-$phase-$label", SHOT)
                val bmp = rule.onNodeWithTag(SHOT).captureToImage()
                assertEquals("720 + 2 × 16", 752, bmp.width)
            }
        }
    }

    @Test
    fun aRowOfCardsAtDefaultAndLargest() {
        val cards = listOf(
            GameCardModel(live, followed = true, anchorMono = 0L),
            GameCardModel(final.copy(away = team("CBJ", "Blue Jackets", 4), home = team("VGK", "Golden Knights", 5)), followed = false, anchorMono = 0L),
        )
        val size = mutableStateOf(TextSize.DEFAULT)
        UiTextScale.running = size.value
        rule.setContent {
            UiTextScale.running = size.value
            BoxTheme {
                key(size.value) {
                    Row(
                        Modifier.testTag(SHOT).background(DesignTokens.Colors.Canvas).padding(16.dp).width((968 + 32).dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        cards.forEachIndexed { i, c ->
                            Box(Modifier.width(HubCardMetrics.CARD_W.dp)) {
                                HubCard(c, clock24 = false, selected = i == 1, leftColumn = i == 0, showHighlights = true, optionsText = null, onEnter = {}, onFocusChange = {})
                            }
                        }
                    }
                }
            }
        }
        for ((s, label) in listOf(TextSize.DEFAULT to "default", TextSize.LARGEST to "largest")) {
            size.value = s
            save("hub-cards-$label", SHOT)
        }
    }

    /**
     * (Emulator E1) At every text size the media area's tag, logos and caption never overlap: the logos shrink to the band
     * between the tag and the caption, and are dropped below `MatchupArtFit.LOGO_MIN`.
     */
    @Test
    fun theMediaTagLogosAndCaptionNeverOverlap() {
        val model = mutableStateOf(liveModel())
        val size = mutableStateOf(TextSize.DEFAULT)
        UiTextScale.running = size.value
        rule.setContent {
            UiTextScale.running = size.value
            BoxTheme {
                key(size.value) {
                    Box(Modifier.testTag(SHOT).background(DesignTokens.Colors.Canvas).padding(16.dp)) {
                        HubPanel(model.value, clock24 = false, slotRequesters = androidx.compose.runtime.remember { List(3) { FocusRequester() } }, onSlot = {}, onSlotFocus = {}, onExit = {}, entered = false)
                    }
                }
            }
        }
        fun bounds(tag: String): androidx.compose.ui.geometry.Rect? {
            val nodes = rule.onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()
            // A child the art measured but did not place (its caption, when the start-time disc needs the room) draws nothing.
            return nodes.firstOrNull()?.takeIf { it.layoutInfo.isPlaced }?.boundsInRoot
        }
        var logosSeen = 0
        for (s in TextSize.entries) {
            size.value = s
            for ((phase, m) in listOf("live" to liveModel(), "final" to finalModel(), "pre" to preModel())) {
                model.value = m
                rule.waitForIdle()
                val logos = listOfNotNull(bounds(HUB_ART_LOGO_TAG + "away"), bounds(HUB_ART_LOGO_TAG + "home"))
                logosSeen += logos.size
                val over = listOfNotNull(bounds(HUB_POSTER_TAG_TAG), bounds(HUB_POSTER_CAPTION_TAG), bounds(HUB_ART_CAPTION_TAG))
                for (l in logos) for (o in over) {
                    assertTrue("$phase at $s: a logo $l overlaps $o", l.intersect(o).let { it.width <= 0.5f || it.height <= 0.5f })
                }
            }
        }
        assertTrue("the logos are drawn where they fit", logosSeen > 0)
    }

    private companion object { const val SHOT = "hub_shot" }
}
