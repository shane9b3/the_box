package app.thebox.ui.sports

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.settings.TextSize
import app.thebox.core.settings.UiTextScale
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.BoxScore
import app.thebox.core.sports.hub.ChlHubFixtures
import app.thebox.core.sports.hub.TeamBox
import app.thebox.ui.R
import app.thebox.ui.theme.BoxTheme
import app.thebox.ui.theme.DesignTokens
import java.io.File
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * (CHL hub P3, docs/design/m6-chl-hub.md §3, §6 goldens, §8 `ChlSheetsTest`) The CHL sheets from the commit-0 fakes and
 * every CHL hub screen as a picture:
 *
 * - the sheets' rows: the Matchup goalie order and Starting mark, the season Leaders header, the Last 5 section; the Box
 *   score's defence group, its TOI column hidden by data and the two-row hockey line score with an OT column;
 * - the nine design goldens under `ui/build/sports-tab/chl-*.png` (hashed by `sportsHashGate`), and the same screens plus
 *   the Largest size under `ui/build/chl-hub/` for review by eye;
 * - a layout check on every shot: no text node is cut (no visual overflow, no ellipsis) and every one lies inside the
 *   720 × 864 panel or the 968 × 864 sheet.
 *
 * Clock, time zone and locale pinned as in `NflHubScreenshotTest`; no image loader (logos show their codes, headshots
 * their empty disc).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChlSheetsTest {
    @get:Rule val rule = createComposeRule()

    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private val golden = File("build/sports-tab").apply { mkdirs() }
    private val review = File("build/chl-hub").apply { mkdirs() }
    private var zone: TimeZone? = null
    private var locale: Locale? = null
    private val k = ChlHubTestKit

    @Before fun pin() {
        zone = TimeZone.getDefault(); locale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        Locale.setDefault(Locale.US)
        ChlHubUi.enabled = true
    }

    @After fun unpin() {
        UiTextScale.running = TextSize.DEFAULT
        ChlHubUi.enabled = SportsConstants.CHL_HUB_ENABLED
        zone?.let { TimeZone.setDefault(it) }
        locale?.let { Locale.setDefault(it) }
    }

    // ================================================================== rows (pure)

    @Test fun theMatchupPutsTheMarkedStarterFirstAndSaysThisSeason() {
        val g = k.preQmjhl()
        val rows = matchupRows(ChlHubFixtures.preQmjhl32953(), res, g)
        assertEquals(MatchupRow.Header(R.string.sports_hub_matchup_leaders_season), rows[0])
        val goalies = rows.filterIsInstance<MatchupRow.Goalies>()
        assertEquals(2, goalies.size)
        assertEquals("J. Lee", goalies[0].away!!.name); assertTrue(goalies[0].away!!.starting)
        assertEquals("L. Denault", goalies[0].home!!.name); assertTrue(goalies[0].home!!.starting)
        assertFalse(goalies[1].away!!.starting || goalies[1].home!!.starting)
        assertEquals("6-6-1-1 · 2.84 GAA · .907 SV%", goalieLine(res, goalies[0].home!!))
        assertEquals("names only when the stats read did not list him", "", goalieLine(res, goalies[0].away!!))
        val recent = rows.filterIsInstance<MatchupRow.Recent>()
        assertEquals(listOf("Saguenéens", "Regiment"), recent.map { it.team })
        assertTrue(rows.contains(MatchupRow.Header(R.string.sports_hub_matchup_last5)))
    }

    @Test fun theOhlMatchupOrdersLondonsGoaliesAndListsItsLastFive() {
        val rows = matchupRows(ChlHubFixtures.preOhl29246(), res, k.preOhl())
        val goalies = rows.filterIsInstance<MatchupRow.Goalies>()
        assertEquals(listOf("B. Cooke", "A. Jaswal"), goalies.map { it.home!!.name })
        assertEquals("C. Haffner", goalies[0].away!!.name); assertTrue(goalies[0].away!!.starting)
        assertFalse("no starter marked for London", goalies.any { it.home!!.starting })
        val ldn = rows.filterIsInstance<MatchupRow.Recent>().last()
        assertEquals("Knights", ldn.team)
        assertEquals("L 6–5 @ SAG · W 4–3 SOO · L 4–2 @ GUE · L 5–0 PBO · W 2–0 @ ER", ldn.text)
        // WHL: MH by rule 2, no Starting mark; CGY has none.
        val whl = matchupRows(ChlHubFixtures.preWhl1023348(), res, k.preWhl()).filterIsInstance<MatchupRow.Goalies>()
        assertEquals(listOf("C. Casey", "C. Christie"), whl.map { it.home!!.name })
        assertTrue(whl.all { it.away == null && !it.home!!.starting })
    }

    @Test fun theNhlMatchupRowsAreUnchanged() {
        // The NHL never fills leadersSeason, Goalie.starting or recent: the last-5-games header and no Last 5 section.
        val d = ChlHubFixtures.preOhl29246().copy(leadersSeason = false, recent = emptyList())
        val rows = matchupRows(d, res, k.preOhl())
        assertEquals(MatchupRow.Header(R.string.sports_hub_matchup_leaders), rows[0])
        assertEquals(rows, matchupRows(d))
        assertFalse(rows.any { it is MatchupRow.Recent })
    }

    @Test fun theBoxScoreFollowsItsData() {
        val box = ChlHubFixtures.boxOhl29239()
        assertFalse("HockeyTech tracks no skater TOI", skaterToiShown(box))
        val nhl = box.copy(away = box.away.copy(skaters = box.away.skaters.map { it.copy(toi = "18:02") }))
        assertTrue(skaterToiShown(nhl))
        assertTrue(hockeyLineScoreShown(box))
        assertFalse("the NHL box carries no line score", hockeyLineScoreShown(box.copy(periods = emptyList(), periodShots = emptyList())))
        // LD / RD are the defence group.
        val rows = boxRows(box.away, failed = false)
        val sections = rows.filterIsInstance<BoxRow.Section>().map { it.title }
        assertEquals(listOf(R.string.sports_hub_box_forwards, R.string.sports_hub_box_defense), sections)
        val defence = rows.dropWhile { it != BoxRow.Section(R.string.sports_hub_box_defense) }.filterIsInstance<BoxRow.Skater>().map { it.line.name }
        assertEquals(listOf("J. Handsor", "K. Dietsch"), defence)
        // Goalies: Nikonovich on one line (stints added), 58:21, SA 38, SV 33, .868.
        val home = boxRows(box.home, failed = false).filterIsInstance<BoxRow.Goalie>()
        assertEquals(1, home.size)
        assertEquals(listOf("38", "33", ".868", "58:21"), goalieValues(home[0].line))
    }

    @Test fun theHockeyLineScoreHasAnOtColumnAndTotals() {
        val (heads, lines) = hockeyLineScore(res, k.finalQmjhl(), ChlHubFixtures.boxOtQmjhl32952())
        assertEquals(listOf("1st", "2nd", "3rd", "OT", "T"), heads)
        assertEquals(listOf("CHI", "", "NFL", ""), lines.map { it.team })
        assertEquals(listOf("Goals", "Shots", "Goals", "Shots"), lines.map { it.label })
        assertEquals(listOf("0", "3", "1", "1", "5"), lines[0].cells)
        assertEquals(listOf("5", "11", "9", "3", "28"), lines[1].cells)
        assertEquals(listOf("1", "1", "2", "0", "4"), lines[2].cells)
        assertEquals(listOf("17", "4", "5", "1", "27"), lines[3].cells)
        val (ohlHeads, ohl) = hockeyLineScore(res, k.finalOhl(), ChlHubFixtures.boxOhl29239())
        assertEquals(listOf("1st", "2nd", "3rd", "T"), ohlHeads)
        assertEquals(listOf("1", "1", "3", "5"), ohl[0].cells)
        assertEquals(listOf("15", "17", "6", "38"), ohl[1].cells)
    }

    // ================================================================== screens

    private fun texts(root: SemanticsNode): List<SemanticsNode> {
        val out = ArrayList<SemanticsNode>()
        fun walk(n: SemanticsNode) {
            if (n.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null) out += n
            n.children.forEach(::walk)
        }
        walk(root)
        return out
    }

    /** Every text in [tag]'s subtree is whole (no overflow, no ellipsis) and inside [box] (root px). */
    private fun assertNothingCut(name: String, tag: String, box: Rect, allowEllipsis: Set<String> = emptySet()) {
        val root = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val nodes = texts(root)
        assertTrue("$name has text", nodes.isNotEmpty())
        for (n in nodes) {
            val out = ArrayList<TextLayoutResult>()
            n.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
            val l = out.firstOrNull() ?: continue
            val text = l.layoutInput.text.text
            if (text.isEmpty()) continue
            val cut = l.hasVisualOverflow || (0 until l.lineCount).any { l.isLineEllipsized(it) }
            if (text !in allowEllipsis) assertFalse("$name: \"$text\" is cut (${l.size.width} of ${l.layoutInput.constraints.maxWidth} px)", cut)
            val b = n.boundsInRoot
            assertTrue("$name: \"$text\" at $b leaves $box", b.left >= box.left - 0.5f && b.right <= box.right + 0.5f && b.top >= box.top - 0.5f && b.bottom <= box.bottom + 0.5f)
        }
    }

    private fun save(name: String, tag: String, isGolden: Boolean) {
        rule.waitForIdle()
        val bmp = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val dirs = if (isGolden) listOf(golden, review) else listOf(review)
        for (d in dirs) File(d, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("$name.png", File(review, "$name.png").length() > 2_000)
    }

    /** Design px in root px: the theme scales 1 design px = the screen width ÷ 1920. */
    private fun px(designPx: Float) = designPx * rule.onRoot().fetchSemanticsNode().size.width / 1920f

    @Test fun theChlPanelsInEveryState() {
        val model = mutableStateOf(k.model(k.preOhl(), ChlHubFixtures.preOhl29246()))
        val size = mutableStateOf(TextSize.DEFAULT)
        UiTextScale.running = size.value
        rule.setContent {
            UiTextScale.running = size.value
            BoxTheme {
                key(size.value) {
                    Box(Modifier.testTag(SHOT).background(DesignTokens.Colors.Canvas).padding(16.dp)) {
                        HubPanel(model.value, clock24 = false, slotRequesters = remember { List(3) { FocusRequester() } }, onSlot = {}, onSlotFocus = {}, onExit = {}, entered = false)
                    }
                }
            }
        }
        val none = MatchState.Done(emptyList())
        val cases = listOf(
            "chl-hub-pre-ohl" to k.model(k.preOhl(), ChlHubFixtures.preOhl29246()),
            "chl-hub-pre-whl" to k.model(k.preWhl(), ChlHubFixtures.preWhl1023348()),
            "chl-hub-pre-qmjhl" to k.model(k.preQmjhl(), ChlHubFixtures.preQmjhl32953()),
            "chl-hub-final-ohl" to k.model(k.finalOhl(), ChlHubFixtures.finalOhl29239()),
            "chl-hub-final-nocatchup-whl" to k.model(k.finalWhl(), ChlHubFixtures.finalWhl1023337(), match = none),
            "chl-hub-final-ot-qmjhl" to k.model(k.finalQmjhl(), ChlHubFixtures.finalOtQmjhl32952(), match = none),
            "chl-hub-postponed" to k.model(k.preOhl(GamePhase.POSTPONED), null, match = MatchState.Idle),
            "chl-hub-failed-pre-ohl" to k.model(k.preOhl(), null, DetailKind.FAILED, FailKind.TIMEOUT),
            "chl-hub-failed-final-ohl" to k.model(k.finalOhl(), null, DetailKind.FAILED, FailKind.TIMEOUT),
        )
        val goldens = setOf("chl-hub-pre-ohl", "chl-hub-pre-whl", "chl-hub-final-ohl", "chl-hub-final-nocatchup-whl", "chl-hub-final-ot-qmjhl", "chl-hub-postponed")
        for (s in listOf(TextSize.DEFAULT, TextSize.LARGEST)) for ((name, m) in cases) {
            size.value = s
            model.value = m
            val largest = s == TextSize.LARGEST
            rule.waitForIdle()
            // The panel's own bounds: 720 × 864 design px.
            val panel = rule.onNodeWithTag(HUB_PANEL_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals("720 × 864", 720f / HubPanelPlan.PANEL_H, panel.width / panel.height, 0.002f)
            assertNothingCut("$name at $s", SHOT, panel)
            save(if (largest) "$name-largest" else name, SHOT, isGolden = !largest && name in goldens)
        }
    }

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        BoxTheme { Box(Modifier.fillMaxSize().testTag(SHOT).background(DesignTokens.Colors.Canvas)) { content() } }
    }

    /** The sheet frame: x 856, y 160, 968 × 864 (§6.4). */
    private fun sheetBox() = Rect(px(856f), px(160f), px(856f + 968f), px(160f + 864f))

    @Test fun theChlSheets() {
        val which = mutableStateOf(0)
        val size = mutableStateOf(TextSize.DEFAULT)
        UiTextScale.running = size.value
        val fin = k.finalOhl()
        val ot = k.finalQmjhl()
        rule.setContent {
            UiTextScale.running = size.value
            Frame {
                key(which.value, size.value) {
                    when (which.value) {
                        0 -> MatchupSheet(k.preQmjhl(), ChlHubFixtures.preQmjhl32953(), onClose = {})
                        1 -> MatchupSheet(k.preOhl(), ChlHubFixtures.preOhl29246(), onClose = {})
                        2 -> GoalsSheet(fin, ChlHubFixtures.finalOhl29239(), emptyMap(), listOf(k.catchUp), null, onPlay = { _, _ -> }, onPlayArchive = {}, onMissingClip = {}, onClose = {}, nowMs = k.AFTER)
                        3 -> GoalsSheet(ot, ChlHubFixtures.finalOtQmjhl32952(), emptyMap(), emptyList(), null, onPlay = { _, _ -> }, onPlayArchive = {}, onMissingClip = {}, onClose = {}, nowMs = k.AFTER)
                        4 -> BoxScoreSheet(fin, Fetch.Ok(ChlHubFixtures.boxOhl29239(), 0L, null, 0), onRetry = {}, onClose = {})
                        5 -> BoxScoreSheet(ot, Fetch.Ok(ChlHubFixtures.boxOtQmjhl32952(), 0L, null, 0), onRetry = {}, onClose = {})
                        else -> BoxScoreSheet(k.finalWhl(), Fetch.Failed(FailKind.TIMEOUT, null, 0L), onRetry = {}, onClose = {})
                    }
                }
            }
        }
        val names = listOf("chl-matchup-qmjhl", "chl-matchup-ohl", "chl-goals-final-ohl", "chl-goals-final-ot-qmjhl", "chl-box-final-ohl", "chl-box-final-ot-qmjhl", "chl-box-failed-whl")
        val goldens = setOf("chl-matchup-qmjhl", "chl-goals-final-ohl", "chl-box-final-ohl")
        for (s in listOf(TextSize.DEFAULT, TextSize.LARGEST)) names.forEachIndexed { i, name ->
            size.value = s
            which.value = i
            rule.waitForIdle()
            val largest = s == TextSize.LARGEST
            // The goal rows' line 2 and the catch-up row may ellipsize by design at Largest; the fixtures' lines must not.
            assertNothingCut("$name at $s", SHOT, sheetBox())
            save(if (largest) "$name-largest" else name, SHOT, isGolden = !largest && name in goldens)
        }
        // The box error names the league.
        which.value = 6
        size.value = TextSize.DEFAULT
        rule.waitForIdle()
        rule.onNodeWithTag(BOX_SCORE_SHEET_TAG).assertExists()
        assertTrue(texts(rule.onNodeWithTag(SHOT, useUnmergedTree = true).fetchSemanticsNode()).any {
            val out = ArrayList<TextLayoutResult>(); it.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
            out.firstOrNull()?.layoutInput?.text?.text == res.getString(R.string.sports_hub_error_box_score_chl, "WHL")
        })
    }

    /** A box whose skaters have times keeps the TOI column (the NHL's case), drawn without the hockey line score. */
    @Test fun aBoxWithTimesKeepsItsToiColumn() {
        val box = ChlHubFixtures.boxOhl29239().let { b ->
            BoxScore(TeamBox(b.away.skaters.map { it.copy(toi = "18:02") }, b.away.goalies), TeamBox(b.home.skaters.map { it.copy(toi = "17:45") }, b.home.goalies))
        }
        rule.setContent { Frame { BoxScoreSheet(k.finalOhl(), Fetch.Ok(box, 0L, null, 0), onRetry = {}, onClose = {}) } }
        rule.waitForIdle()
        rule.onNodeWithTag(BOX_HOCKEY_LINE_SCORE_TAG).assertDoesNotExist()
        val all = texts(rule.onNodeWithTag(SHOT, useUnmergedTree = true).fetchSemanticsNode()).mapNotNull {
            val out = ArrayList<TextLayoutResult>(); it.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out); out.firstOrNull()?.layoutInput?.text?.text
        }
        assertTrue(all.contains(res.getString(R.string.sports_hub_box_toi)))
        assertTrue(all.contains("18:02"))
    }

    private companion object { const val SHOT = "chl_hub_shot" }
}
