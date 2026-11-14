package app.thebox.ui.sports

import androidx.test.core.app.ApplicationProvider
import app.thebox.core.full.PickerChoice
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.sports.ChlTeamColours
import app.thebox.core.sports.DayState
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.League
import app.thebox.core.sports.NflTeamColours
import app.thebox.core.sports.Sport
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.SportsPrefs
import app.thebox.core.sports.TeamColourTable
import app.thebox.core.sports.group
import app.thebox.core.sports.isHockey
import app.thebox.core.sports.sport
import app.thebox.ui.full.PlayerText
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (CHL WP-5, docs/design/m6-chl-leagues.md §1.1, lead ruling L2) The QMJHL's Newfoundland Regiment has the team code "NFL".
 * A team code is never a league: following QMJHL NFL – CHA gives a hockey bug, its colours come from the CHL table, and the
 * tab and the picker never switch to the NFL league. A static check finds no `valueOf(` with an abbreviation argument in
 * `core`, `data` or `ui` main sources.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TeamCodeIsNotALeagueTest {
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private val game = SportsFixtures.qmjhlNflAtCha()

    @Test
    fun theFollowedNflCodedGameIsAHockeyGameOfTheQmjhl() {
        assertEquals(League.QMJHL, game.id.league)
        assertEquals("NFL", game.away.abbrev)
        val view = SportsFixtures.liveView(game)
        assertEquals(League.QMJHL, view.league)
        assertTrue(view.league.isHockey)
        assertEquals(Sport.HOCKEY, view.league.sport)
        assertEquals("the hockey views carry their period label", true, view.period != null)
        // the colours come from ChlTeamColours(QMJHL, "NFL"), through the league-keyed seam
        assertEquals(ChlTeamColours.of(League.QMJHL, "NFL"), TeamColourTable.of(view.league, view.away.abbrev))
        assertEquals(ChlTeamColours.of(League.QMJHL, "NFL"), GoalHooks.Default.colours(view.league, view.away.abbrev))
        // and the NFL league's own table is a different lookup (never reached for this game)
        assertEquals(NflTeamColours.of("NFL"), TeamColourTable.of(League.NFL, "NFL"))
    }

    @Test
    fun theTabAndTheViewModelNeverSwitchToTheNflLeague() {
        val repo = FakeScoreRepository(SportsPrefs(league = League.QMJHL, chlLeague = League.QMJHL))
        val store = FakeSettingsStore()
        repo.followed.value = SportsFixtures.followedOf(game)
        repo.day.value = DayState.Board(SportsFixtures.board(game, dayKey = "2026-11-13", league = League.QMJHL), 0L, false)
        val vm = SportsViewModel(repo, store, { 0L }, easternToday = { LocalDate.of(2026, 11, 13) })
        val s = vm.state.value
        assertEquals(League.QMJHL, s.prefs.league)
        val body = s.body as SportsBody.Games
        assertEquals(1, body.cards.size)
        assertTrue("the QMJHL game is the followed one", body.cards.single().followed)
        assertEquals(null, s.boardKey) // never read as an NFL week
        assertFalse(SportsText.isWeekKey(s.prefs.league.name))
        // The NFL-coded team is never the group: choosing the chip of a group, not of a code.
        vm.onGroup(app.thebox.core.sports.LeagueGroup.CHL)
        assertEquals("pressing CHL while a CHL league is shown changes nothing", League.QMJHL, repo.prefs.value.league)
        assertEquals(app.thebox.core.sports.LeagueGroup.CHL, League.QMJHL.group)
    }

    @Test
    fun thePickerNamesTheLeagueGroupAndTheSubLeagueNotTheTeamCode() {
        assertEquals("League · CHL", PlayerText.choice(res, PickerChoice.ScoreLeagueRow(League.QMJHL), 0))
        assertEquals("CHL league · QMJHL", PlayerText.choice(res, PickerChoice.ScoreChlRow(League.QMJHL), 0))
        val row = PlayerText.choice(res, PickerChoice.ScoreGame(game, followed = true), 0, clock24 = false, league = League.QMJHL)
        assertTrue(row, row.startsWith("NFL 2–3 CHA"))
        assertEquals("QMJHL games keep the hockey period words", "2nd 10:22", row.substringAfter("· "))
    }

    @Test
    fun noCodeIsEverReadAsALeagueInTheMainSources() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val offenders = ArrayList<String>()
        for (module in listOf("core", "data", "ui")) {
            File(root, "$module/src/main").walkTopDown().filter { it.extension == "kt" }.forEach { f ->
                f.readLines().forEachIndexed { i, raw ->
                    val line = raw.substringBefore("//").trim()
                    if (line.startsWith("*") || line.startsWith("/*")) return@forEachIndexed
                    if (Regex("""League\.valueOf\(|enumValueOf<League>|League\.entries\.(first|find)\w*\s*\{[^}]*(abbrev|name)""").containsMatchIn(line)) {
                        offenders += "${f.relativeTo(root)}:${i + 1}: $line"
                    }
                    // any valueOf( whose argument is a team code (abbrev, away, home, code)
                    Regex("""valueOf\(([^)]*)\)""").findAll(line).forEach { m ->
                        if (Regex("""\b(abbrev|away|home|code|teamCode)\b""").containsMatchIn(m.groupValues[1])) offenders += "${f.relativeTo(root)}:${i + 1}: $line"
                    }
                }
            }
        }
        assertTrue("a team code is read as a league:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
