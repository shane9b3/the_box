package app.thebox.ui.full

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import app.thebox.core.full.PickerChoice
import app.thebox.core.full.PickerKind
import app.thebox.core.full.PickerState
import app.thebox.core.full.state.selectable
import app.thebox.core.settings.TextSize
import app.thebox.core.settings.UiTextScale
import app.thebox.core.sports.GameClock
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.NflBreak
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.SportsFixtures
import app.thebox.ui.sports.CardLogoCache
import app.thebox.ui.theme.BoxTheme
import app.thebox.ui.theme.DesignTokens
import java.io.File
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
 * (Score sheet restyle, owner mock-up and change 2026-11-26) The NFL score sheet laid out with real fonts: a followed live
 * game, a scheduled game with the longest names, a live game with two-digit scores on both sides, halftime and a final.
 * Every game title is one line with no overflow and ends before its row's score block; every shown row is inside the 880 px
 * sheet; at Default and at Largest, wherever the focus is. PNGs go to `ui/build/score-sheet/` (for review by eye; not goldens).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScoreSheetLayoutTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val saved = TimeZone.getDefault()
    @Before fun zone() = TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    @After fun reset() { TimeZone.setDefault(saved); UiTextScale.running = TextSize.DEFAULT }

    private val out = File("build/score-sheet").apply { mkdirs() }
    private val sheet = mutableStateOf<PickerState?>(null)

    private fun show(state: PickerState) {
        if (sheet.value == null) {
            sheet.value = state
            rule.setContent { BoxTheme { Box(Modifier.fillMaxSize().background(DesignTokens.Colors.Canvas)) { sheet.value?.let { PickerSheet(it, clock24 = false) } } } }
        } else {
            sheet.value = state
        }
        rule.waitForIdle()
    }

    /** A stand-in logo (a colour disc with a light rim), seeded into the card cache at the sheet's slot sizes. */
    private fun seed(url: String?, colour: Int) {
        url ?: return
        for (s in listOf(ScoreSheetMetrics.TEAM_LOGO, ScoreSheetMetrics.LEAGUE_LOGO)) {
            val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = 0xFFF2F2F2.toInt(); c.drawOval(1f, 1f, s - 1f, s - 1f, p)
            p.color = colour; c.drawOval(4f, 4f, s - 4f, s - 4f, p)
            CardLogoCache.put(url, s, s, b.asImageBitmap())
        }
    }

    private fun team(abbrev: String, name: String, short: String, score: Int?, colour: Int) =
        SportsFixtures.nflTeam(abbrev, name, short, score).also { seed(it.logo, colour) }

    private val now = System.currentTimeMillis()

    private fun games(): List<GameSnapshot> {
        val sched = SportsFixtures.nflGame(
            id = "1", phase = GamePhase.SCHEDULED, period = null, periodKind = null, clock = null, detail = null, startUtcMs = now + 3_600_000L,
            away = team("WSH", "Washington Commanders", "Commanders", null, 0xFF5A1414.toInt()),
            home = team("NE", "New England Patriots", "Patriots", null, 0xFF0B2240.toInt()),
        )
        val sunday = SportsFixtures.nflGame(
            id = "2", phase = GamePhase.SCHEDULED, period = null, periodKind = null, clock = null, detail = null, startUtcMs = now + 3 * 86_400_000L,
            away = team("CHI", "Chicago Bears", "Bears", null, 0xFF0B162A.toInt()),
            home = team("DET", "Detroit Lions", "Lions", null, 0xFF0076B6.toInt()),
        )
        val live = SportsFixtures.nflGame(
            id = "3", away = team("TB", "Tampa Bay Buccaneers", "Buccaneers", 21, 0xFFD50A0A.toInt()),
            home = team("SF", "San Francisco 49ers", "49ers", 17, 0xFFAA0000.toInt()),
        )
        val half = SportsFixtures.nflGame(
            id = "4", phase = GamePhase.INTERMISSION, period = 2, clock = GameClock(0, false, true),
            detail = SportsFixtures.nflDetail(breakKind = NflBreak.HALFTIME),
            away = team("JAX", "Jacksonville Jaguars", "Jaguars", 10, 0xFF006778.toInt()),
            home = team("LAC", "Los Angeles Chargers", "Chargers", 13, 0xFF0080C6.toInt()),
        )
        val fin = SportsFixtures.nflGame(
            id = "5", phase = GamePhase.FINAL, clock = null,
            away = team("KC", "Kansas City Chiefs", "Chiefs", 24, 0xFFE31837.toInt()),
            home = team("BUF", "Buffalo Bills", "Bills", 17, 0xFF00338D.toInt()),
        )
        return listOf(live, half, sched, sunday, fin)
    }

    private fun nflSheet(focused: Int): PickerState {
        seed(ScoreSheetText.NFL_LOGO, 0xFF013369.toInt())
        val followed = SportsFixtures.nflGame(
            id = "9", away = team("CHI", "Chicago Bears", "Bears", 14, 0xFF0B162A.toInt()),
            home = team("DET", "Detroit Lions", "Lions", 10, 0xFF0076B6.toInt()),
        )
        val choices = listOf(
            PickerChoice.ScoreGame(followed, followed = true), PickerChoice.ScoreLeagueRow(League.NFL),
            PickerChoice.ScoreCornerRow(ScoreCorner.TOP_RIGHT), PickerChoice.ScoreDelayRow(0), PickerChoice.ScoreStyleRow(NhlBugStyle.PILL),
            PickerChoice.ScoreStop, PickerChoice.ScoreDayDivider("", current = true),
        ) + games().map { PickerChoice.ScoreGame(it, followed = false) }
        return PickerState(PickerKind.SCORE_BUG, choices, current = 0, focused = focused, remember = null, fromQuickBar = true)
    }

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val results = ArrayList<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun assertSheet(what: String) {
        val box = rule.onNodeWithTag(PICKER_SHEET_TAG).fetchSemanticsNode().boundsInRoot
        val rows = rule.onAllNodesWithTag(PICKER_ROW_TAG).fetchSemanticsNodes()
        assertTrue(what, rows.isNotEmpty())
        for (r in rows) assertTrue("$what: row inside the sheet (${r.boundsInRoot} in $box)", r.boundsInRoot.bottom <= box.bottom - ScoreSheetMetrics.PAD_V + 0.5f)
        val titles = rule.onAllNodesWithTag(SCORE_GAME_TITLE_TAG, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("$what: game titles shown", titles.isNotEmpty())
        val scores = rule.onAllNodesWithTag(SCORE_GAME_SCORE_TAG, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        for (t in titles) {
            val l = layoutOf(t)
            val text = t.config.getOrNull(SemanticsProperties.Text)?.joinToString().orEmpty()
            assertEquals("$what: '$text' is one line", 1, l.lineCount)
            assertFalse("$what: '$text' does not overflow", l.hasVisualOverflow)
            assertFalse("$what: '$text' is not ellipsised", l.isLineEllipsized(0))
            val b = t.boundsInRoot
            // The score block on the same row (vertical overlap) starts after the title (the text node is as wide as its text).
            scores.filter { it.overlapsVertically(b) }.forEach { s ->
                assertTrue("$what: '$text' ($b) ends before the score ($s)", b.right <= s.left + 0.5f)
            }
            assertTrue("$what: '$text' inside the sheet", b.right <= box.right)
        }
    }

    private fun Rect.overlapsVertically(o: Rect) = top < o.bottom && o.top < bottom

    private fun capture(name: String) {
        rule.waitForIdle()
        val bmp = rule.onNodeWithTag(PICKER_SHEET_TAG).captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun everyGameTitleIsOneLineAndEveryRowFitsAtDefaultAndLargest() {
        for (size in listOf(TextSize.DEFAULT, TextSize.LARGEST)) {
            UiTextScale.running = size
            val n = nflSheet(0).choices.size
            for (f in 0 until n) {
                val state = nflSheet(f)
                if (!state.choices[f].selectable) continue
                show(state)
                assertSheet("$size focus $f")
            }
            // The live row with two-digit scores on both sides is shown with its score block.
            show(nflSheet(n - 1))
            assertTrue("$size: score blocks shown", rule.onAllNodesWithTag(SCORE_GAME_SCORE_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            val tag = size.name.lowercase()
            show(nflSheet(0)); capture("nfl-$tag-top")
            show(nflSheet(8)); capture("nfl-$tag-games")
            show(nflSheet(n - 1)); capture("nfl-$tag-bottom")
        }
    }

    @Test
    fun theDelayListAndAHockeySheet() {
        show(PickerState(PickerKind.SCORE_DELAY, listOf(0, 10, 20, 30, 45, 60).map { PickerChoice.ScoreDelayChoice(it) }, current = 1, focused = 2, remember = null, fromQuickBar = true))
        assertEquals(6, rule.onAllNodesWithTag(PICKER_ROW_TAG).fetchSemanticsNodes().size)
        capture("delay")
        seed(ScoreSheetText.NHL_LOGO, 0xFF111111.toInt())
        val sched = SportsFixtures.game(id = "1", phase = GamePhase.SCHEDULED, period = null, periodKind = null, clock = null)
        show(
            PickerState(
                PickerKind.SCORE_BUG,
                listOf(
                    PickerChoice.ScoreLeagueRow(League.NHL), PickerChoice.ScoreCornerRow(ScoreCorner.BOTTOM_LEFT), PickerChoice.ScoreDelayRow(10),
                    PickerChoice.ScoreStyleRow(NhlBugStyle.STACKED), PickerChoice.ScoreTodayDivider,
                    PickerChoice.ScoreGame(SportsFixtures.game(id = "2"), false), PickerChoice.ScoreGame(sched, false),
                    PickerChoice.ScoreGame(SportsFixtures.game(id = "3", phase = GamePhase.FINAL, finalKind = app.thebox.core.sports.PeriodKind.OT), false),
                ),
                current = -1, focused = 3, remember = null, fromQuickBar = true,
            ),
        )
        assertSheet("nhl")
        capture("nhl")
    }
}
