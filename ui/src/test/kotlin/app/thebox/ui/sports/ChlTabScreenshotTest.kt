package app.thebox.ui.sports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import app.thebox.core.full.PickerChoice
import app.thebox.core.full.PickerKind
import app.thebox.core.full.PickerState
import app.thebox.core.sports.DayState
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.GameClock
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.GameSnapshot
import app.thebox.core.sports.League
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.SportsPrefs
import app.thebox.ui.full.PICKER_SHEET_TAG
import app.thebox.ui.full.PickerSheet
import app.thebox.ui.theme.BoxTheme
import app.thebox.ui.theme.DesignTokens
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * (CHL WP-5, docs/design/m6-chl-leagues.md §6.3, §7.2) The owner's screenshot sheets for the Sports tab and the quick-bar
 * score sheet: the tab with the CHL chip and the inline sub-switch for each of OHL, WHL and QMJHL (cards with logos, with
 * the fallback plate, an empty day, a failure, a refused key) and the quick-bar sheet with the League row and the CHL row.
 * They are written to `ui/build/chl-ui/` and, when it exists or can be made, to the review scratchpad; for review by eye,
 * not goldens.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChlTabScreenshotTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val outs = listOf(
        File("build/chl-ui"),
        File("/private/tmp/claude-501/-Users-shawnjohnson-Projects-The-Box/dedf5c50-b6f4-4a8c-a23c-fadfe67ec05f/scratchpad/chl-ui"),
    ).onEach { runCatching { it.mkdirs() } }

    private fun save(name: String, bmp: Bitmap) {
        for (o in outs) runCatching { File(o, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    /** A synthetic stand-in for a team logo (a colour oval with a light border and a transparent corner). */
    private fun logo(colour: Int, w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        val c = Canvas(this)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFFF2F2F2.toInt(); c.drawOval(w * 0.08f, 1f, w * 0.92f, h - 1f, p)
        p.color = colour; c.drawOval(w * 0.08f + 6, 7f, w * 0.92f - 6, h - 7f, p)
    }

    private class TeamSpec(val code: String, val nick: String, val logoId: Int?, val colour: Int)

    private val teams: Map<League, List<TeamSpec>> = mapOf(
        League.OHL to listOf(
            TeamSpec("LDN", "Knights", 14, 0xFF1B3A6B.toInt()), TeamSpec("KIT", "Rangers", 10, 0xFFB5121B.toInt()),
            TeamSpec("BRAM", "Steelheads", null, 0), TeamSpec("FLNT", "Firebirds", null, 0),
            TeamSpec("ER", "Otters", 8, 0xFF0A5C36.toInt()), TeamSpec("OS", "Attack", 11, 0xFF111111.toInt()),
            TeamSpec("SOO", "Greyhounds", 16, 0xFF0D4F9E.toInt()), TeamSpec("OTT", "67's", 5, 0xFF8E1B20.toInt()),
            TeamSpec("NIAG", "IceDogs", 20, 0xFF0E3B73.toInt()), TeamSpec("BAR", "Colts", 7, 0xFF1D4E89.toInt()),
        ),
        League.WHL to listOf(
            TeamSpec("POR", "Winterhawks", 208, 0xFFB31B1B.toInt()), TeamSpec("SEA", "Thunderbirds", 214, 0xFF0B5B3C.toInt()),
            TeamSpec("KEL", "Rockets", null, 0), TeamSpec("WEN", "Wild", null, 0),
            TeamSpec("MH", "Tigers", 206, 0xFFE87722.toInt()), TeamSpec("MJ", "Warriors", 207, 0xFF7A1F2B.toInt()),
            TeamSpec("CGY", "Hitmen", 202, 0xFFB5121B.toInt()), TeamSpec("EDM", "Oil Kings", 228, 0xFF0B3D91.toInt()),
            TeamSpec("TC", "Americans", 217, 0xFF1B2A6B.toInt()), TeamSpec("PG", "Cougars", 210, 0xFF1A5E3A.toInt()),
        ),
        League.QMJHL to listOf(
            TeamSpec("BLB", "Armada", 19, 0xFF0E3B73.toInt()), TeamSpec("CHI", "Saguenéens", 10, 0xFFB5121B.toInt()),
            TeamSpec("NFL", "Regiment", 2, 0xFF0B3D91.toInt()), TeamSpec("CHA", "Islanders", 7, 0xFFE87722.toInt()),
            TeamSpec("QUE", "Remparts", 9, 0xFF1B2A6B.toInt()), TeamSpec("RIM", "Océanic", 18, 0xFF0A6BA8.toInt()),
            TeamSpec("SHE", "Phœnix", 60, 0xFF8E1B20.toInt()), TeamSpec("VDO", "Foreurs", 15, 0xFF1A5E3A.toInt()),
            TeamSpec("MON", "Wildcats", 1, 0xFF0B3D91.toInt()), TeamSpec("HAL", "Mooseheads", 5, 0xFF1B2A6B.toInt()),
        ),
    )

    private val client = mapOf(League.OHL to "ohl", League.WHL to "whl", League.QMJHL to "lhjmq")

    private fun team(league: League, p: TeamSpec, score: Int?): app.thebox.core.sports.TeamLine {
        val line = SportsFixtures.chlTeam(p.code, p.nick, score, client = client.getValue(league), teamId = p.logoId)
        // The cards draw logos from their process cache; the sheet seeds it with stand-ins (70 × 45 design px at the root's density, and at 1.5 and 2).
        line.logo?.let { u ->
            for ((w, h) in listOf(70 to 45, 105 to 68, 140 to 90)) CardLogoCache.put(u, w, h, logo(p.colour, w, h).asImageBitmap())
        }
        return line
    }

    private fun games(league: League): List<GameSnapshot> {
        val t = teams.getValue(league)
        fun g(i: Int, id: String, phase: GamePhase, a: Int, h: Int, score: Score = Score(0, 0), tbd: Boolean = false) = SportsFixtures.chlGame(
            league = league, id = id, phase = phase,
            away = team(league, t[i * 2], if (phase == GamePhase.PRE || phase == GamePhase.SCHEDULED) null else score.a),
            home = team(league, t[i * 2 + 1], if (phase == GamePhase.PRE || phase == GamePhase.SCHEDULED) null else score.h),
            period = if (phase == GamePhase.PRE || phase == GamePhase.SCHEDULED) null else a,
            periodKind = if (phase == GamePhase.PRE || phase == GamePhase.SCHEDULED) null else if (h == 5) PeriodKind.OT else PeriodKind.REG,
            otNumber = if (h == 5) 1 else null,
            finalKind = if (phase == GamePhase.FINAL) PeriodKind.SO else null,
            clock = if (phase == GamePhase.LIVE) GameClock(622, true, false) else if (phase == GamePhase.INTERMISSION) GameClock(0, false, true) else null,
            startTbd = tbd, startUtcMs = 1_794_612_600_000L + i * 600_000L,
        )
        return listOf(
            g(0, "${league.ordinal}1", GamePhase.LIVE, 2, 0, Score(1, 2)),
            g(1, "${league.ordinal}2", GamePhase.LIVE, 4, 5, Score(3, 3)),
            g(2, "${league.ordinal}3", GamePhase.INTERMISSION, 1, 0, Score(0, 1)),
            g(3, "${league.ordinal}4", GamePhase.PRE, 0, 0, tbd = true),
            g(4, "${league.ordinal}5", GamePhase.FINAL, 3, 0, Score(4, 3)),
        )
    }

    private class Score(val a: Int, val h: Int)

    private val repo = FakeScoreRepository(SportsPrefs())

    private fun capture(name: String) {
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        assertEquals(1920, bmp.width)
        save(name, bmp)
    }

    @Test
    fun theSportsTabForEachChlLeague() {
        val vm = SportsViewModel(repo, app.thebox.core.settings.fakes.FakeSettingsStore(), { android.os.SystemClock.elapsedRealtime() }, easternToday = { LocalDate.of(2026, 11, 13) })
        repo.prefs.value = SportsPrefs(league = League.OHL, chlLeague = League.OHL)
        repo.day.value = DayState.Board(SportsFixtures.board(dayKey = "2026-11-13", league = League.OHL), 0L, false)
        rule.setContent { BoxTheme { Box(Modifier.fillMaxSize().background(DesignTokens.Colors.Canvas)) { SportsScreen(vm, clock24 = false) } } }
        rule.waitForIdle()
        for (league in listOf(League.OHL, League.WHL, League.QMJHL)) {
            val n = league.name.lowercase()
            repo.prefs.value = SportsPrefs(league = league, chlLeague = league)
            repo.day.value = DayState.Board(SportsFixtures.board(*games(league).toTypedArray(), dayKey = "2026-11-13", league = league), android.os.SystemClock.elapsedRealtime(), false)
            capture("tab-$n-cards")
            repo.day.value = DayState.Board(SportsFixtures.board(dayKey = "2026-11-13", league = league), 0L, false)
            capture("tab-$n-empty")
            repo.day.value = DayState.Failed(FailKind.IO)
            capture("tab-$n-failed")
            repo.day.value = DayState.Failed(FailKind.REFUSED)
            capture("tab-$n-refused")
        }
        assertTrue(File("build/chl-ui/tab-qmjhl-refused.png").length() > 5_000)
    }

    @Test
    fun theQuickBarScoreSheetWithTheChlRow() {
        val sheet = androidx.compose.runtime.mutableStateOf<PickerState?>(null)
        rule.setContent {
            BoxTheme {
                Box(Modifier.fillMaxSize().background(DesignTokens.Colors.Canvas)) { sheet.value?.let { PickerSheet(it, clock24 = false) } }
            }
        }
        for (league in listOf(League.OHL, League.WHL, League.QMJHL)) {
            val live = games(league).first()
            val state = PickerState(
                kind = PickerKind.SCORE_BUG,
                choices = listOf(
                    PickerChoice.ScoreGame(live, followed = true), PickerChoice.ScoreLeagueRow(league), PickerChoice.ScoreChlRow(league),
                    PickerChoice.ScoreCornerRow(ScoreCorner.TOP_RIGHT), PickerChoice.ScoreDelayRow(0), PickerChoice.ScoreStyleRow(NhlBugStyle.STACKED),
                    PickerChoice.ScoreStop, PickerChoice.ScoreTodayDivider,
                ) + games(league).drop(1).map { PickerChoice.ScoreGame(it, followed = false) },
                current = 0, focused = 2, remember = null, fromQuickBar = true,
            )
            sheet.value = state
            rule.waitForIdle()
            val bmp = rule.onNodeWithTag(PICKER_SHEET_TAG).captureToImage().asAndroidBitmap()
            save("sheet-${league.name.lowercase()}", bmp)
        }
    }
}
