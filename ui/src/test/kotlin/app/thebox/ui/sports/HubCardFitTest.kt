package app.thebox.ui.sports

import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.sports.GameClock
import app.thebox.core.sports.GamePhase
import app.thebox.core.sports.League
import app.thebox.core.sports.NhlTeamPlaces
import app.thebox.core.sports.PeriodKind
import app.thebox.core.sports.SportsFixtures
import app.thebox.core.sports.TeamLine
import app.thebox.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * (Sports Hub §1.2, §10.2) The card's texts fit their slots at the four Text sizes, measured with the bundled Instrument Sans
 * (1 design px = 1 px here, times the factor): all 32 NHL, 32 NFL and 61 CHL teams in the name column at the shrink floor
 * (24 × factor), the scores, and the status pill. A failure at the floor means a short form in `ChlTeamPlaces`, never a cut.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HubCardFitTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val res = context.resources
    private val semi = sansTypeface(context, R.font.instrument_sans_semibold)
    private val bold = sansTypeface(context, R.font.instrument_sans_bold)
    private val factors = listOf(0.9f, 1.0f, 1.1f, 1.2f)

    private fun measure(factor: Float): Measure = { text, px, weight ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = if (weight >= 700) bold else semi
            textSize = px * factor
            if (weight >= 700) fontFeatureSettings = "tnum"
        }.measureText(text)
    }

    private val nhl = listOf(
        "Ducks", "Bruins", "Sabres", "Flames", "Hurricanes", "Blackhawks", "Avalanche", "Blue Jackets", "Stars", "Red Wings", "Oilers",
        "Panthers", "Kings", "Wild", "Canadiens", "Predators", "Devils", "Islanders", "Rangers", "Senators", "Flyers", "Penguins",
        "Sharks", "Kraken", "Blues", "Lightning", "Maple Leafs", "Mammoth", "Canucks", "Golden Knights", "Capitals", "Jets",
    )
    private val nhlAbbrevs = NhlTeamPlaces.TABLE.keys.toList()

    /** The 32 NFL teams as the board gives them: the full name and the short name. */
    private val nfl = listOf(
        "Arizona Cardinals", "Atlanta Falcons", "Baltimore Ravens", "Buffalo Bills", "Carolina Panthers", "Chicago Bears",
        "Cincinnati Bengals", "Cleveland Browns", "Dallas Cowboys", "Denver Broncos", "Detroit Lions", "Green Bay Packers",
        "Houston Texans", "Indianapolis Colts", "Jacksonville Jaguars", "Kansas City Chiefs", "Las Vegas Raiders", "Los Angeles Chargers",
        "Los Angeles Rams", "Miami Dolphins", "Minnesota Vikings", "New England Patriots", "New Orleans Saints", "New York Giants",
        "New York Jets", "Philadelphia Eagles", "Pittsburgh Steelers", "San Francisco 49ers", "Seattle Seahawks", "Tampa Bay Buccaneers",
        "Tennessee Titans", "Washington Commanders",
    )

    private fun nflNames(): List<HubName> = nfl.map { full ->
        // The short name is the last word (the 49ers and the Buccaneers included); no team's nickname has two words.
        val short = full.substringAfterLast(' ')
        HubNames.of(League.NFL, SportsFixtures.nflTeam("XXX", full, short))
    }

    private fun chlNames(): List<Pair<String, HubName>> = ChlTestTeams.ALL.map { t ->
        "${t.league} ${t.code}" to HubNames.of(t.league, SportsFixtures.chlTeam(t.code, t.nickname, 0))
    }

    @Test fun thereAre32NhlAnd32NflAnd61ChlTeams() {
        assertEquals(32, nhl.size); assertEquals(32, nhlAbbrevs.size); assertEquals(32, nfl.size); assertEquals(61, chlNames().size)
    }

    @Test fun everyNameFitsTheNameColumnAtTheFloorAtEverySize() {
        val names = buildList {
            nhlAbbrevs.zip(nhl.sortedBy { it }).forEach { (a, n) -> add("NHL $a" to HubNames.of(League.NHL, SportsFixtures.team(a, n))) }
            // Every nickname against its own place: pair the sorted table so each place meets each nickname once.
            for (n in nhl) for (a in listOf("NJD", "TBL", "VGK", "CBJ", "LAK", "STL", "SJS")) add("NHL $a/$n" to HubNames.of(League.NHL, SportsFixtures.team(a, n)))
            nflNames().forEachIndexed { i, n -> add("NFL ${nfl[i]}" to n) }
            addAll(chlNames())
        }
        for (f in factors) {
            val m = measure(f)
            val col = HubCardMetrics.nameW(f).toFloat()
            for ((label, name) in names) {
                for (line in name.cardLines) {
                    val w = m(line, HubFit.NAME_SIZES.last(), 600)
                    assertTrue("$label \"$line\" is $w px at the floor, factor $f, column $col", w <= col)
                }
            }
        }
    }

    @Test fun theNamesSplitIntoPlaceAndNickname() {
        val nhlName = HubNames.of(League.NHL, SportsFixtures.team("WPG", "Jets"))
        assertEquals(listOf("Winnipeg", "Jets"), nhlName.cardLines)
        assertEquals("Winnipeg Jets", HubNames.of(League.NHL, SportsFixtures.team("WPG", "Winnipeg Jets")).full)
        val kc = HubNames.of(League.NFL, SportsFixtures.nflTeam("KC", "Kansas City Chiefs", "Chiefs"))
        assertEquals(listOf("Kansas City", "Chiefs"), kc.cardLines)
        assertEquals(listOf("Calgary", "Flames"), HubNames.of(League.NHL, SportsFixtures.team("CGY", "Flames")).cardLines)
        val blb = HubNames.of(League.QMJHL, SportsFixtures.chlTeam("BLB", "Armada", 0))
        assertEquals(listOf("Boisbriand", "Armada"), blb.cardLines)
        assertEquals("Blainville-Boisbriand Armada", blb.full)
        // A full "City Nickname" from a followed snapshot splits the same way.
        assertEquals(listOf("Boisbriand", "Armada"), HubNames.of(League.QMJHL, SportsFixtures.chlTeam("BLB", "Blainville-Boisbriand Armada", 0)).cardLines)
        // An unknown team is one line.
        assertEquals(listOf("Mystery"), HubNames.of(League.NHL, TeamLine("ZZZ", "Mystery", null, null)).cardLines)
    }

    @Test fun theScoreFitsItsColumnWithTheShrinkRule() {
        for (f in factors) {
            val m = measure(f)
            for (score in listOf("0", "8", "88", "10", "19")) {
                val size = HubFit.scoreSize(score, HubCardMetrics.SCORE_W.toFloat(), m)
                assertTrue("$score at $size x $f", m(score, size, 700) <= HubCardMetrics.SCORE_W)
            }
        }
    }

    @Test fun theLongestPillFitsTheCardAtEverySize() {
        val live2ot = SportsFixtures.game(phase = GamePhase.LIVE, period = 5, periodKind = PeriodKind.OT, otNumber = 2, clock = GameClock(622, true, false))
        val pills = listOf(
            SportsText.hubPill(res, live2ot, false).text, SportsText.hubPill(res, SportsFixtures.game(phase = GamePhase.FINAL, finalKind = PeriodKind.SO), false).text,
            res.getString(R.string.sports_hub_pill_date_time, "MON NOV 30", "10:30 PM"), res.getString(R.string.sports_hub_pill_tbd),
            res.getString(R.string.sports_hub_pill_postponed),
        )
        assertEquals("LIVE · 2OT 10:22", pills[0])
        for (f in factors) {
            val m = measure(f)
            for (p in pills) {
                val w = m(p, HubCardMetrics.PILL_PX, 700) + 2 * HubCardMetrics.PILL_PAD + 22
                assertTrue("\"$p\" is $w px at $f, the card's row is ${HubCardMetrics.INNER_W}", w <= HubCardMetrics.INNER_W)
            }
        }
        // At the default size a common pill and the Following chip share the row.
        val m = measure(1.0f)
        val final = m("FINAL", 22, 700) + 32
        val chip = m(res.getString(R.string.sports_hub_chip_following), 22, 600) + 32
        assertTrue(final + HubCardMetrics.BOTTOM_GAP + chip <= HubCardMetrics.INNER_W)
    }

    @Test fun theCardsGeometryAddsUp() {
        val used = HubCardMetrics.PAD + 2 * HubCardMetrics.ROW_H + HubCardMetrics.ROW_GAP + HubCardMetrics.BOTTOM_H + HubCardMetrics.PAD
        assertTrue("$used of ${HubCardMetrics.CARD_H}", used <= HubCardMetrics.CARD_H)
        for (f in factors) assertTrue(2 * (34 * f) <= HubCardMetrics.ROW_H.toFloat())
        assertEquals(88, HubCardMetrics.logoW(1.0f)); assertEquals(72, HubCardMetrics.logoW(1.1f)); assertEquals(248, HubCardMetrics.nameW(1.2f))
    }
}
