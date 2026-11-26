package app.thebox.ui.full

import androidx.test.core.app.ApplicationProvider
import app.thebox.core.full.PickerChoice
import app.thebox.core.full.state.selectable
import app.thebox.core.sports.FailKind
import app.thebox.core.sports.GameClock
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.League
import app.thebox.core.sports.NflBreak
import app.thebox.core.sports.NhlBugStyle
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.SportsFixtures
import app.thebox.ui.sports.Measure
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (Score sheet restyle) The score sheet's pure decisions: the one-line matchup ladder, the second line in each league and
 * phase, the winner dimming, the settings rows' label / value split and the height window (focus always shown, never
 * taller than the sheet) at Default and Largest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScoreSheetPlanTest {
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private val saved = TimeZone.getDefault()
    @Before fun zone() = TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    @After fun unzone() = TimeZone.setDefault(saved)

    /** A stand-in for the font: every character 0.56 em wide (Instrument Sans 600 averages a little under that). */
    private val measure: Measure = { text, px, _ -> text.length * px * 0.56f }

    // ------------------------------------------------------------------ the matchup ladder

    private fun fit(a: String, an: String, h: String, hn: String, maxPx: Float = ScoreSheetMetrics.gameTextW().toFloat()) =
        ScoreSheetFit.matchup(a, an, h, hn, "vs", maxPx, measure)

    @Test fun aShortPairKeepsTheFullForm() {
        // Beside the two inline logos the full form still fits, a size down; with room it is 26.
        val p = fit("CHI", "Bears", "DET", "Lions")
        assertEquals("CHI Bears" to "DET Lions", p.away to p.home)
        assertEquals(MatchupPlan("CHI Bears", "DET Lions", 26), fit("CHI", "Bears", "DET", "Lions", maxPx = 400f))
    }

    @Test fun aLongPairStepsDownTheLadderAndNeverExceedsTheColumn() {
        val column = ScoreSheetMetrics.gameTextW().toFloat()
        val withScore = ScoreSheetFit.titleWidth(column, true, ScoreSheetFit.scoreBlockPx(measure), ScoreSheetMetrics.SCORE_GAP.toFloat())
        for (max in listOf(column, withScore)) {
            for ((a, an, h, hn) in listOf(
                listOf("WSH", "Commanders", "NE", "Patriots"), listOf("TB", "Buccaneers", "SF", "49ers"),
                listOf("BRAM", "Steelheads", "FLNT", "Firebirds"), listOf("POR", "Winterhawks", "SEA", "Thunderbirds"),
            )) {
                val p = fit(a, an, h, hn, max)
                val w = measure(ScoreSheetFit.line(p.away, p.home, "vs"), p.designPx, 600)
                assertTrue("$a $an vs $h $hn fits ($p, $w > $max)", w <= max)
                assertTrue("never under 22: $p", p.designPx >= 22)
            }
        }
        // The steps in order: full at 26/24/22, then names, then codes.
        // (29 characters in full: 423 / 390 / 358 px at 26 / 24 / 22; 22 as names: 321 / 296 / 272.)
        assertEquals(MatchupPlan("WSH Commanders", "NE Patriots", 26), fit("WSH", "Commanders", "NE", "Patriots", 430f))
        assertEquals(MatchupPlan("WSH Commanders", "NE Patriots", 24), fit("WSH", "Commanders", "NE", "Patriots", 400f))
        assertEquals(MatchupPlan("WSH Commanders", "NE Patriots", 22), fit("WSH", "Commanders", "NE", "Patriots", 360f))
        assertEquals(MatchupPlan("Commanders", "Patriots", 26), fit("WSH", "Commanders", "NE", "Patriots", 330f))
        assertEquals(MatchupPlan("Commanders", "Patriots", 22), fit("WSH", "Commanders", "NE", "Patriots", 280f))
        assertEquals(MatchupPlan("WSH", "NE", 26), fit("WSH", "Commanders", "NE", "Patriots", 200f))
    }

    /** (Lead review 2026-11-26) Every title of a sheet shares one form and one size, and each fits its own row. */
    @Test fun allTitlesOfASheetShareOneStepThatFitsEveryRow() {
        val column = ScoreSheetMetrics.gameTextW().toFloat()
        val narrow = ScoreSheetFit.titleWidth(column, true, ScoreSheetFit.scoreBlockPx(measure), ScoreSheetMetrics.SCORE_GAP.toFloat())
        val asks = listOf(
            ScoreSheetFit.TitleAsk("CHI", "Bears", "DET", "Lions", column),
            ScoreSheetFit.TitleAsk("TB", "Buccaneers", "SF", "49ers", narrow),
            ScoreSheetFit.TitleAsk("WSH", "Commanders", "NE", "Patriots", column),
        )
        val step = ScoreSheetFit.commonStep(asks, "vs", measure)
        val plans = asks.map { ScoreSheetFit.shared(it, step, "vs", measure) }
        for ((a, p) in asks.zip(plans)) assertTrue(measure(ScoreSheetFit.line(p.away, p.home, "vs"), p.designPx, 600) <= a.maxPx)
        // The narrow row decides the list's form, but never past names: the rows that fit keep names at one size, and only
        // the row that cannot fit them beside its score drops to codes.
        assertEquals(ScoreSheetFit.LAST_NAMES_STEP, step)
        assertEquals("Bears" to "Commanders", plans[0].away to plans[2].away)
        assertEquals(plans[0].designPx, plans[2].designPx)
        assertEquals("TB", plans[1].away)
        // With room for every row, all share the same step.
        val roomy = asks.map { ScoreSheetFit.TitleAsk(it.awayAbbrev, it.awayName, it.homeAbbrev, it.homeName, column) }
        val s2 = ScoreSheetFit.commonStep(roomy, "vs", measure)
        assertEquals(1, roomy.map { ScoreSheetFit.shared(it, s2, "vs", measure).designPx }.distinct().size)
        // A sheet of short pairs with no score keeps the full form at 26.
        val easy = listOf(ScoreSheetFit.TitleAsk("CHI", "Bears", "DET", "Lions", column), ScoreSheetFit.TitleAsk("KC", "Chiefs", "BUF", "Bills", column))
        assertTrue(ScoreSheetFit.commonStep(easy, "vs", measure) < ScoreSheetFit.SIZES.size)
        assertEquals(ScoreSheetFit.LAST_NAMES_STEP, ScoreSheetFit.commonStep(listOf(ScoreSheetFit.TitleAsk("AAAA", "X", "BBBB", "Y", 1f)), "vs", measure))
    }

    @Test fun aBlankOrRedundantNameIsNotDoubled() {
        assertEquals("CHI", ScoreSheetFit.full("CHI", ""))
        assertEquals("CHI", ScoreSheetFit.full("CHI", "chi"))
        assertEquals("Bears", ScoreSheetFit.full("", "Bears"))
        assertEquals("CHI", ScoreSheetFit.short("CHI", " "))
        assertEquals(MatchupPlan("CHI", "DET Lions", 26), fit("CHI", "", "DET", "Lions"))
    }

    @Test fun theNamesAreNicknames() {
        val nfl = SportsFixtures.nflGame()
        assertEquals("Chiefs", ScoreSheetText.teamName(League.NFL, nfl.away))
        assertEquals("Flames", ScoreSheetText.teamName(League.NHL, SportsFixtures.team("CGY", "Flames")))
        assertEquals("Knights", ScoreSheetText.teamName(League.OHL, SportsFixtures.chlTeam("LDN", "London Knights")))
    }

    @Test fun aShownScoreNarrowsTheTitleAndTheLadderStillFitsIt() {
        val column = ScoreSheetMetrics.gameTextW().toFloat()
        val block = ScoreSheetFit.scoreBlockPx(measure)
        val gap = ScoreSheetMetrics.SCORE_GAP.toFloat()
        assertEquals(column, ScoreSheetFit.titleWidth(column, false, block, gap))
        val narrow = ScoreSheetFit.titleWidth(column, true, block, gap)
        assertEquals(column - block - gap, narrow)
        // "WSH Commanders vs NE Patriots" with a score: the narrower title still fits, on one line.
        for ((a, an, h, hn) in listOf(listOf("WSH", "Commanders", "NE", "Patriots"), listOf("TB", "Buccaneers", "SF", "49ers"))) {
            val p = fit(a, an, h, hn, narrow)
            assertTrue("$p fits $narrow", measure(ScoreSheetFit.line(p.away, p.home, "vs"), p.designPx, 600) <= narrow)
            // Wider without the score: never a smaller form than with it.
            val wide = fit(a, an, h, hn, column)
            assertTrue("$wide vs $p", wide.designPx >= p.designPx || wide.away.length > p.away.length)
        }
    }

    // ------------------------------------------------------------------ the winner

    @Test fun onlyAFinalsLoserIsDimmed() {
        val fin = SportsFixtures.game(phase = GamePhase.FINAL, away = SportsFixtures.team("CGY", "Flames", 3), home = SportsFixtures.team("EDM", "Oilers", 2))
        assertEquals(0, ScoreSheetFit.winner(fin))
        assertFalse(ScoreSheetFit.dimmed(fin, 0))
        assertTrue(ScoreSheetFit.dimmed(fin, 1))
        val homeWins = fin.copy(home = fin.home.copy(score = 5))
        assertTrue(ScoreSheetFit.dimmed(homeWins, 0))
        assertFalse(ScoreSheetFit.dimmed(homeWins, 1))
        val tie = fin.copy(home = fin.home.copy(score = 3))
        assertEquals(-1, ScoreSheetFit.winner(tie))
        assertFalse(ScoreSheetFit.dimmed(tie, 0) || ScoreSheetFit.dimmed(tie, 1))
        val live = SportsFixtures.game() // 0–2, in progress
        assertFalse(ScoreSheetFit.dimmed(live, 0) || ScoreSheetFit.dimmed(live, 1))
    }

    // ------------------------------------------------------------------ the second line

    private fun status(g: app.thebox.core.sports.GameSnapshot, nowMs: Long = g.startUtcMs - 3_600_000L) =
        ScoreSheetText.status(res, g, clock24 = false, nowMs = nowMs)

    @Test fun theNhlSecondLine() {
        val sched = SportsFixtures.game(phase = GamePhase.SCHEDULED, period = null, periodKind = null, clock = null)
        assertEquals(GameStatus(false, "7:00 PM"), status(sched))
        assertEquals(GameStatus(false, "7:00 PM"), status(sched, nowMs = sched.startUtcMs - 3 * 86_400_000L)) // hockey: the time only
        assertEquals(GameStatus(false, "19:00"), ScoreSheetText.status(res, sched, clock24 = true, nowMs = sched.startUtcMs))
        assertEquals(GameStatus(false, "Time TBD"), status(sched.copy(startTbd = true)))
        assertEquals(GameStatus(false, "Postponed"), status(sched.copy(phase = GamePhase.POSTPONED)))
        assertEquals(GameStatus(true, "2nd 10:22"), status(SportsFixtures.game()))
        val int = SportsFixtures.game(phase = GamePhase.INTERMISSION, period = 1, clock = GameClock(900, false, true))
        assertEquals(GameStatus(true, "1st INT"), status(int))
        assertEquals(GameStatus(false, "Final/OT"), status(SportsFixtures.game(phase = GamePhase.FINAL, finalKind = PeriodKind.OT)))
    }

    @Test fun theNflSecondLine() {
        val sunday = ZonedDateTime.of(2026, 11, 8, 13, 0, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        val sched = SportsFixtures.nflGame(phase = GamePhase.SCHEDULED, period = null, periodKind = null, clock = null, detail = null, startUtcMs = sunday)
        assertEquals(GameStatus(false, "Today 1:00 PM"), status(sched, nowMs = sunday - 3_600_000L))
        assertEquals(GameStatus(false, "Sun 1:00 PM"), status(sched, nowMs = sunday - 3 * 86_400_000L))
        assertEquals(GameStatus(false, "Today 13:00"), ScoreSheetText.status(res, sched, clock24 = true, nowMs = sunday))
        assertEquals(GameStatus(false, "Time TBD"), status(sched.copy(startTbd = true)))
        assertEquals(GameStatus(false, "Postponed"), status(sched.copy(phase = GamePhase.POSTPONED)))
        assertEquals(GameStatus(true, "4th 3:42"), status(SportsFixtures.nflGame()))
        val half = SportsFixtures.nflGame(
            phase = GamePhase.INTERMISSION, period = 2, clock = GameClock(0, false, true),
            detail = SportsFixtures.nflDetail(breakKind = NflBreak.HALFTIME),
        )
        assertEquals(GameStatus(true, "Halftime"), status(half))
        assertEquals(GameStatus(false, "Final"), status(SportsFixtures.nflGame(phase = GamePhase.FINAL, clock = null)))
    }

    @Test fun aChlLeaguesSecondLine() {
        val sched = SportsFixtures.chlGame(league = League.WHL, phase = GamePhase.PRE, period = null, periodKind = null, clock = null)
        assertEquals(GameStatus(false, "6:30 PM"), status(sched))
        assertEquals(GameStatus(false, "Time TBD"), status(sched.copy(startTbd = true)))
        assertEquals(GameStatus(false, "Postponed"), status(sched.copy(phase = GamePhase.POSTPONED)))
        assertEquals(GameStatus(true, "2nd 10:22"), status(SportsFixtures.chlGame(league = League.WHL)))
        val so = SportsFixtures.chlGame(league = League.WHL, periodKind = PeriodKind.SO, period = 5, clock = null)
        assertEquals(GameStatus(true, "SO"), status(so))
        assertEquals(GameStatus(false, "Final/SO"), status(SportsFixtures.chlGame(league = League.WHL, phase = GamePhase.FINAL, finalKind = PeriodKind.SO)))
    }

    // ------------------------------------------------------------------ settings rows and headers

    @Test fun theSettingsRowsSplitIntoLabelAndValue() {
        assertEquals(SettingText("League", "NFL", logo = ScoreSheetText.NFL_LOGO), ScoreSheetText.setting(res, PickerChoice.ScoreLeagueRow(League.NFL)))
        assertEquals(SettingText("League", "NHL", logo = ScoreSheetText.NHL_LOGO), ScoreSheetText.setting(res, PickerChoice.ScoreLeagueRow(League.NHL)))
        assertEquals(SettingText("League", "CHL", logo = ScoreSheetText.CHL_LOGO), ScoreSheetText.setting(res, PickerChoice.ScoreLeagueRow(League.OHL)))
        assertEquals(SettingText("CHL league", "OHL", logo = ScoreSheetText.OHL_LOGO), ScoreSheetText.setting(res, PickerChoice.ScoreChlRow(League.OHL)))
        assertEquals(ScoreSheetText.WHL_LOGO, ScoreSheetText.setting(res, PickerChoice.ScoreChlRow(League.WHL))?.logo)
        assertEquals(ScoreSheetText.QMJHL_LOGO, ScoreSheetText.setting(res, PickerChoice.ScoreChlRow(League.QMJHL))?.logo)
        assertEquals(SettingText("Corner", "Top right"), ScoreSheetText.setting(res, PickerChoice.ScoreCornerRow(ScoreCorner.TOP_RIGHT)))
        assertEquals(SettingText("Delay", "Off", chip = true), ScoreSheetText.setting(res, PickerChoice.ScoreDelayRow(0)))
        assertEquals(SettingText("Delay", "10 s", chip = true), ScoreSheetText.setting(res, PickerChoice.ScoreDelayRow(10)))
        assertEquals(SettingText("Style", "Pill"), ScoreSheetText.setting(res, PickerChoice.ScoreStyleRow(NhlBugStyle.PILL)))
        assertEquals(SettingText("Stop following", null), ScoreSheetText.setting(res, PickerChoice.ScoreStop))
        assertNull(ScoreSheetText.setting(res, PickerChoice.ScoreLoading))
        assertTrue(ScoreSheetText.NHL_LOGO.endsWith("NHL_dark.svg"))
    }

    @Test fun theTitleAndTheHeaders() {
        assertEquals("SCORE BUG SETTINGS", ScoreSheetText.title(res, delay = false, locale = java.util.Locale.US))
        assertEquals("DELAY", ScoreSheetText.title(res, delay = true, locale = java.util.Locale.US))
        assertEquals("THIS WEEK'S MATCHUPS", ScoreSheetText.header(res, PickerChoice.ScoreDayDivider("", current = true), false, java.util.Locale.US))
        assertEquals("TODAY'S GAMES", ScoreSheetText.header(res, PickerChoice.ScoreTodayDivider, false, java.util.Locale.US))
        assertEquals("WEEK 9", ScoreSheetText.header(res, PickerChoice.ScoreDayDivider("w2026-2-9"), false, java.util.Locale.US))
        assertEquals("WED, NOV 4", ScoreSheetText.header(res, PickerChoice.ScoreDayDivider("2026-11-04"), false, java.util.Locale.US))
    }

    // ------------------------------------------------------------------ the window

    /** A followed game, its refused feed, six settings rows, the header and 16 games. */
    private fun bigSheet(): List<PickerChoice> {
        val followed = SportsFixtures.chlGame(league = League.OHL, id = "99")
        return listOf(
            PickerChoice.ScoreGame(followed, followed = true), PickerChoice.ScoreFailed(FailKind.REFUSED, League.OHL),
            PickerChoice.ScoreLeagueRow(League.OHL), PickerChoice.ScoreChlRow(League.OHL), PickerChoice.ScoreCornerRow(ScoreCorner.TOP_RIGHT),
            PickerChoice.ScoreDelayRow(0), PickerChoice.ScoreStyleRow(NhlBugStyle.PILL), PickerChoice.ScoreStop,
            PickerChoice.ScoreTodayDivider,
        ) + (1..16).map { PickerChoice.ScoreGame(SportsFixtures.chlGame(league = League.OHL, id = "$it"), followed = false) }
    }

    @Test fun theSheetsGeometryAddsUpTo880() {
        for (f in listOf(0.9f, 1f, 1.1f, 1.2f)) {
            assertEquals(PlayerUi.PICKER_H.toInt(), ScoreSheetMetrics.budget(f) + ScoreSheetMetrics.titleH(f) + ScoreSheetMetrics.TITLE_GAP + 2 * ScoreSheetMetrics.PAD_V)
        }
        assertEquals(132, ScoreSheetMetrics.FAILED_H)
        assertEquals(101, ScoreSheetMetrics.height(PickerChoice.ScoreGame(SportsFixtures.game(), false), 1.2f))
        assertEquals(64, ScoreSheetMetrics.height(PickerChoice.ScoreStop, 0.9f))
    }

    @Test fun theWindowHoldsTheFocusAndFitsTheSheetAtEveryFocusAndSize() {
        val choices = bigSheet()
        for (factor in listOf(0.9f, 1f, 1.1f, 1.2f)) {
            val heights = choices.map { ScoreSheetMetrics.height(it, factor) }
            val budget = ScoreSheetMetrics.budget(factor)
            for (f in choices.indices) {
                val w = scoreWindow(heights, f, budget)
                assertTrue("x$factor focus $f in $w", f in w)
                val used = scoreWindowHeight(heights, w)
                assertTrue("x$factor focus $f: $used within $budget", used <= budget)
                // Full: neither neighbour would fit.
                if (w.first > 0) assertTrue("x$factor focus $f: no room above", used + ScoreSheetMetrics.GAP + heights[w.first - 1] > budget)
                if (w.last < choices.lastIndex) assertTrue("x$factor focus $f: no room below", used + ScoreSheetMetrics.GAP + heights[w.last + 1] > budget)
                if (choices[f].selectable && f > 0 && f < choices.lastIndex) {
                    // Around the focus where the list allows: at least one row each side unless that end is the list's.
                    assertTrue("x$factor focus $f: a row above", w.first < f)
                    assertTrue("x$factor focus $f: a row below", w.last > f)
                }
            }
        }
    }

    @Test fun theWindowShowsEverythingWhenItFitsAndALoneTallRow() {
        val heights = listOf(64, 64, 48, 84, 84)
        assertEquals(0 until 5, scoreWindow(heights, 4, 800))
        assertEquals(0 until 1, scoreWindow(listOf(900), 0, 800))
        assertEquals(IntRange.EMPTY, scoreWindow(emptyList(), 0, 800))
    }
}
