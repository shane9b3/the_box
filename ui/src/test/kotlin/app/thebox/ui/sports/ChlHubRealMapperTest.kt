package app.thebox.ui.sports

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import app.thebox.core.settings.TextSize
import app.thebox.core.settings.UiTextScale
import app.thebox.core.sports.Fetch
import app.thebox.core.sports.GameId
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.SportsConstants
import app.thebox.core.sports.hub.BoxScore
import app.thebox.core.sports.hub.ChlDetailMapper
import app.thebox.core.sports.hub.ChlHubFixtures
import app.thebox.core.sports.hub.ChlHubFixtures.obj
import app.thebox.core.sports.hub.ChlHubFixtures.table
import app.thebox.core.sports.hub.GameDetail
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
 * (CHL hub integration) The OHL screens drawn from the **real mapper's** output for the fixture bodies (not the
 * hand-built fakes): the pre-game panel (29246, ranks from both OHL tables, LDN's goalies), the final panel (29239), and
 * the Matchup, Goals and Box score sheets, at Default and Largest. Nothing is cut (no overflow, no ellipsis, nothing
 * outside the 720 × 864 panel or the 968 × 864 sheet), and every final (all three leagues) shows the three stars on the
 * summary's second line with the game-winning goal on one line. Review pictures go to `ui/build/chl-hub/real-*.png`
 * (no goldens: `ChlSheetsTest` owns those).
 *
 * The switch is the build's (`ChlHubUi.enabled` is not touched): this test also proves the shipped panel is the detailed
 * one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChlHubRealMapperTest {
    @get:Rule val rule = createComposeRule()

    private val review = File("build/chl-hub").apply { mkdirs() }
    private var zone: TimeZone? = null
    private var locale: Locale? = null
    private val k = ChlHubTestKit

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

    private fun shot(l: League) = ChlHubFixtures.HEADSHOT(l)

    private val preOhl: GameDetail by lazy {
        ChlDetailMapper.preview(
            GameId(League.OHL, "29246"), obj(ChlHubFixtures.PREVIEW_OHL_29246), null, obj(ChlHubFixtures.GOALIES_OHL_T14),
            ChlDetailMapper.ranks(table(ChlHubFixtures.TABLE_OHL), table(ChlHubFixtures.TABLE_SPECIAL_OHL)), false, shot(League.OHL),
        )!!
    }

    private fun summary(l: League, id: String, path: String): Pair<GameDetail, BoxScore> = ChlDetailMapper.summary(GameId(l, id), obj(path), shot(l))!!

    private val finalOhl by lazy { summary(League.OHL, "29239", ChlHubFixtures.SUMMARY_OHL_29239) }
    private val finalWhl by lazy { summary(League.WHL, "1023337", ChlHubFixtures.SUMMARY_WHL_1023337) }
    private val finalQmjhl by lazy { summary(League.QMJHL, "32952", ChlHubFixtures.SUMMARY_OT_QMJHL_32952) }

    private fun texts(root: SemanticsNode): List<SemanticsNode> {
        val out = ArrayList<SemanticsNode>()
        fun walk(n: SemanticsNode) {
            if (n.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null) out += n
            n.children.forEach(::walk)
        }
        walk(root)
        return out
    }

    private fun layout(n: SemanticsNode): TextLayoutResult? {
        val out = ArrayList<TextLayoutResult>()
        n.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.firstOrNull()
    }

    private fun assertNothingCut(name: String, box: Rect) {
        val nodes = texts(rule.onNodeWithTag(SHOT, useUnmergedTree = true).fetchSemanticsNode())
        assertTrue("$name has text", nodes.isNotEmpty())
        for (n in nodes) {
            val l = layout(n) ?: continue
            val text = l.layoutInput.text.text
            if (text.isEmpty()) continue
            val cut = l.hasVisualOverflow || (0 until l.lineCount).any { l.isLineEllipsized(it) }
            assertFalse("$name: \"$text\" is cut (${l.size.width} of ${l.layoutInput.constraints.maxWidth} px)", cut)
            val b = n.boundsInRoot
            assertTrue("$name: \"$text\" at $b leaves $box", b.left >= box.left - 0.5f && b.right <= box.right + 0.5f && b.top >= box.top - 0.5f && b.bottom <= box.bottom + 0.5f)
        }
    }

    /** The summary line [i] as drawn, or null when it is not drawn. */
    private fun summaryLine(i: Int): TextLayoutResult? = runCatching {
        layout(rule.onNodeWithTag(HUB_SUMMARY_TAG + i, useUnmergedTree = true).fetchSemanticsNode())
    }.getOrNull()

    private fun save(name: String) {
        rule.waitForIdle()
        val bmp = rule.onNodeWithTag(SHOT).captureToImage().asAndroidBitmap()
        File(review, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("$name.png", File(review, "$name.png").length() > 2_000)
    }

    private fun px(designPx: Float) = designPx * rule.onRoot().fetchSemanticsNode().size.width / 1920f

    @Test fun theShippedSwitchDrawsTheDetailedPanel() {
        assertTrue(SportsConstants.CHL_HUB_ENABLED)
        assertTrue(ChlHubUi.detailed)
        assertEquals(HubSource.HOCKEYTECH, ChlHubUi.source)
    }

    @Test fun thePanelsFromTheRealMapperFitAndTheFinalsKeepTheirStars() {
        val model = mutableStateOf(k.model(k.preOhl(), preOhl))
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
        data class Case(val name: String, val game: GameSnapshot, val detail: GameDetail, val match: MatchState?, val stars: String?)
        val cases = listOf(
            Case("real-pre-ohl", k.preOhl(), preOhl, null, null),
            Case("real-final-ohl", k.finalOhl(), finalOhl.first, null, "Stars: Manza, Bowes, Dietsch"),
            Case("real-final-whl", k.finalWhl(), finalWhl.first, none, "Stars: " + finalWhl.first.stars.sortedBy { it.rank }.joinToString(", ") { it.lastName }),
            Case("real-final-ot-qmjhl", k.finalQmjhl(), finalQmjhl.first, none, "Stars: " + finalQmjhl.first.stars.sortedBy { it.rank }.joinToString(", ") { it.lastName }),
        )
        for (s in listOf(TextSize.DEFAULT, TextSize.LARGEST)) for (c in cases) {
            size.value = s
            model.value = if (c.match != null) k.model(c.game, c.detail, match = c.match) else k.model(c.game, c.detail)
            rule.waitForIdle()
            val name = "${c.name} at $s"
            val panel = rule.onNodeWithTag(HUB_PANEL_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertNothingCut(name, panel)
            val line0 = summaryLine(0)
            val line1 = summaryLine(1)
            if (c.stars != null) {
                assertTrue("$name: the feed has three stars", c.detail.stars.size == 3)
                assertEquals("$name: the game-winning goal takes one line", 1, line0?.lineCount)
                assertEquals("$name: the stars are drawn", c.stars, line1?.layoutInput?.text?.text)
                assertTrue("$name: the winning goal line is the full or the short form", line0!!.layoutInput.text.text.let { it.startsWith("Game-winning goal · ") || it.startsWith("GWG · ") })
            } else {
                assertEquals("First meeting this season", line0?.layoutInput?.text?.text)
                assertEquals("Points leaders · Edgar 22 · Zocco 27", line1?.layoutInput?.text?.text)
            }
            save(if (s == TextSize.LARGEST) "${c.name}-largest" else c.name)
        }
    }

    @Test fun theSheetsFromTheRealMapperFit() {
        val which = mutableStateOf(0)
        val size = mutableStateOf(TextSize.DEFAULT)
        UiTextScale.running = size.value
        val fin = k.finalOhl()
        rule.setContent {
            UiTextScale.running = size.value
            BoxTheme {
                Box(Modifier.fillMaxSize().testTag(SHOT).background(DesignTokens.Colors.Canvas)) {
                    key(which.value, size.value) {
                        when (which.value) {
                            0 -> MatchupSheet(k.preOhl(), preOhl, onClose = {})
                            1 -> GoalsSheet(fin, finalOhl.first, emptyMap(), listOf(k.catchUp), null, onPlay = { _, _ -> }, onPlayArchive = {}, onMissingClip = {}, onClose = {}, nowMs = k.AFTER)
                            else -> BoxScoreSheet(fin, Fetch.Ok(finalOhl.second, 0L, null, 0), onRetry = {}, onClose = {})
                        }
                    }
                }
            }
        }
        val names = listOf("real-matchup-ohl", "real-goals-final-ohl", "real-box-final-ohl")
        val sheet = { Rect(px(856f), px(160f), px(856f + 968f), px(160f + 864f)) }
        for (s in listOf(TextSize.DEFAULT, TextSize.LARGEST)) names.forEachIndexed { i, name ->
            size.value = s
            which.value = i
            rule.waitForIdle()
            assertNothingCut("$name at $s", sheet())
            save(if (s == TextSize.LARGEST) "$name-largest" else name)
        }
        // The real box's hockey line score (OTT 15 17 6 = 38 shots, BAR 21) and drawn.
        size.value = TextSize.DEFAULT
        which.value = 2
        rule.waitForIdle()
        rule.onNodeWithTag(BOX_HOCKEY_LINE_SCORE_TAG).assertExists()
        val all = texts(rule.onNodeWithTag(SHOT, useUnmergedTree = true).fetchSemanticsNode()).mapNotNull { layout(it)?.layoutInput?.text?.text }
        assertTrue(all.containsAll(listOf("15", "17", "38", "21")))
    }

    private companion object { const val SHOT = "chl_hub_real_shot" }
}
