package app.thebox.core.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** docs/design/m6-nfl.md §6, §4.5, §10.1 (rulings 12, 26, 27; S1). */
class NflTeamColoursTest {
    private val white = 0xFFFFFFFF.toInt()

    private fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)

    private class Row(val abbr: String, val base: String, val accent: String, val c1: Double, val glowIsBase: Boolean, val pop: String, val c5: Double)

    /** The §6 table: base, accent, C1, glow, pop, tab C5. */
    private val table = listOf(
        Row("ARI", "#97233F", "#000000", 7.99, true, "#C68695", 10.01),
        Row("ATL", "#A71930", "#000000", 7.43, true, "#CF808D", 9.47),
        Row("BAL", "#241773", "#9E7C0C", 14.53, false, "#B1963D", 15.99),
        Row("BUF", "#00338D", "#C60C30", 11.30, false, "#E0798D", 13.22),
        Row("CAR", "#0085CA", "#101820", 4.03, true, "#40A4D7", 5.54),
        Row("CHI", "#0B162A", "#C83803", 18.07, false, "#DB7E5B", 18.71),
        Row("CIN", "#FB4F14", "#000000", 3.37, true, "#FC6937", 4.72),
        Row("CLE", "#311D00", "#FF3C00", 16.08, false, "#FF6333", 17.14),
        Row("DAL", "#041E42", "#869397", 16.54, false, "#8C989C", 17.53),
        Row("DEN", "#FB4F14", "#002244", 3.37, true, "#FC6937", 4.72),
        Row("DET", "#0076B6", "#B0B7BC", 4.92, true, "#B0B7BC", 6.57),
        Row("GB", "#203731", "#FFB612", 12.71, false, "#FFB612", 14.32),
        Row("HOU", "#03202F", "#A71930", 16.78, false, "#CF808D", 17.80),
        Row("IND", "#002C5F", "#A2AAAD", 13.77, false, "#A2AAAD", 15.38),
        Row("JAX", "#006778", "#D7A22A", 6.54, true, "#D7A22A", 8.47),
        Row("KC", "#E31837", "#FFB81C", 4.72, true, "#FFB81C", 6.41),
        Row("LAC", "#0080C6", "#FFC20E", 4.28, true, "#FFC20E", 5.84),
        Row("LAR", "#003594", "#FFA300", 10.84, false, "#FFA300", 12.76),
        Row("LV", "#000000", "#A5ACAF", 21.0, false, "#A5ACAF", 21.0),
        Row("MIA", "#008E97", "#FC4C02", 3.95, true, "#FC6728", 5.40),
        Row("MIN", "#4F2683", "#FFC62F", 10.84, false, "#FFC62F", 12.83),
        Row("NE", "#002244", "#C60C30", 16.00, false, "#E0798D", 17.17),
        Row("NO", "#101820", "#D3BC8D", 17.89, false, "#D3BC8D", 18.55),
        Row("NYG", "#0B2265", "#A71930", 14.68, false, "#CF808D", 16.08),
        Row("NYJ", "#125740", "#000000", 8.53, true, "#7DA396", 10.51),
        Row("PHI", "#004C54", "#A5ACAF", 9.71, true, "#A5ACAF", 11.67),
        Row("PIT", "#101820", "#FFB612", 17.89, false, "#FFB612", 18.55),
        Row("SEA", "#002244", "#69BE28", 16.00, false, "#69BE28", 17.17),
        Row("SF", "#AA0000", "#B3995D", 7.75, true, "#B3995D", 9.85),
        Row("TB", "#D50A0A", "#34302B", 5.42, true, "#E87878", 7.28),
        Row("TEN", "#0C2340", "#4B92DB", 15.79, false, "#5D9DDF", 16.93),
        Row("WSH", "#5A1414", "#FFB612", 13.59, false, "#FFB612", 15.14),
    )

    /** The independent team list: the recorded ESPN `teams` answer, never derived from the table. */
    private fun espnAbbrevs(): Set<String> {
        val root = Json.parseToJsonElement(SportsFixtures.text("sports/nfl/teams-2026-11-08.json")).jsonObject
        return root.getValue("teams").jsonArray
            .map { it.jsonObject.getValue("abbreviation").jsonPrimitive.content }
            .toSet()
    }

    @Test
    fun everyTeamHasAnEntry() {
        val espn = espnAbbrevs()
        assertEquals(32, espn.size, "the recorded teams answer lists 32 teams")
        assertEquals(espn, NflTeamColours.TABLE.keys, "the table's keys are exactly the league's teams")
        assertEquals(table.map { it.abbr }.toSet(), NflTeamColours.TABLE.keys)
        for (a in espn) assertNotEquals(NflTeamColours.FALLBACK, NflTeamColours.of(a), "$a falls back")
        assertEquals(NflTeamColours.of("KC"), NflTeamColours.of(" kc "), "trimmed and upper-cased")
        assertEquals(NflTeamColours.FALLBACK, NflTeamColours.of(null))
        assertEquals(NflTeamColours.FALLBACK, NflTeamColours.of("XYZ"))
    }

    @Test
    fun theSection6TableIsPinned() {
        for (r in table) {
            val c = NflTeamColours.of(r.abbr)
            assertEquals(r.base, hex(c.base), "${r.abbr} base")
            assertEquals(r.accent, hex(c.accent), "${r.abbr} accent")
            assertEquals(if (r.glowIsBase) r.base else r.accent, hex(c.glow), "${r.abbr} glow")
            assertEquals(r.pop, hex(c.pop), "${r.abbr} pop")
            assertEquals(c.base, c.tab, "${r.abbr}: S1 tab == base")
            val c1 = Contrast.ratio(white, c.base)
            assertTrue(abs(c1 - r.c1) < 0.006, "${r.abbr} C1 ${"%.3f".format(c1)} vs ${r.c1}")
            val c5 = NhlTeamColours.c5(c.tab)
            assertTrue(abs(c5 - r.c5) < 0.006, "${r.abbr} C5 ${"%.3f".format(c5)} vs ${r.c5}")
        }
        val f = NflTeamColours.FALLBACK
        assertEquals("#2A3140", hex(f.base))
        assertEquals("#5CA8F2", hex(f.accent))
    }

    @Test
    fun theSharedDeriveIsUnchanged() {
        assertEquals(0xFFFF4C00.toInt(), NhlTeamColours.of("EDM").tab, "the NHL EDM tab is still the derived glow")
        assertNotEquals(TeamColourTable.of(League.NFL, "BUF"), TeamColourTable.of(League.NHL, "BUF"), "BUF exists in both leagues")
        assertEquals(NflTeamColours.of("BUF"), TeamColourTable.of(League.NFL, "BUF"))
        assertEquals(NhlTeamColours.of("BUF"), TeamColourTable.of(League.NHL, "BUF"))
        for (a in listOf("CAR", "CHI", "DAL", "DET", "MIN", "PHI", "PIT", "SEA")) {
            assertNotEquals(TeamColourTable.of(League.NFL, a), TeamColourTable.of(League.NHL, a), a)
        }
    }

    @Test
    fun c1ExceptionsAreExactlyTheFive() {
        assertEquals(setOf("CIN", "DEN", "MIA", "CAR", "LAC"), NflTeamColours.C1_EXCEPTIONS)
        for ((a, c) in NflTeamColours.TABLE) {
            val r = Contrast.ratio(white, c.first)
            if (a in NflTeamColours.C1_EXCEPTIONS) {
                assertTrue(r < SportsConstants.GOAL_C1_MIN, "$a is listed as an exception but passes C1")
                assertTrue(r >= 3.0, "$a: the word still passes 3 : 1 (${"%.2f".format(r)})")
            } else {
                assertTrue(r >= SportsConstants.GOAL_C1_MIN, "C1 $a: white on ${hex(c.first)} is ${"%.2f".format(r)}")
            }
        }
    }

    @Test
    fun c5PassesForAllAndC4PopsOnWhiteIce() {
        assertTrue(NflTeamColours.C5_EXCEPTIONS.isEmpty())
        val all = NflTeamColours.TABLE.keys.map { it to NflTeamColours.of(it) } + ("fallback" to NflTeamColours.FALLBACK)
        for ((a, c) in all) {
            val c5 = NhlTeamColours.c5(c.tab)
            assertTrue(c5 >= SportsConstants.PP_C5_MIN, "C5 $a: ${"%.2f".format(c5)}")
            val c4 = Contrast.ratio(c.pop, SportsConstants.GLASS_WHITE_ICE)
            assertTrue(c4 >= SportsConstants.GOAL_C4_MIN, "C4 $a: ${hex(c.pop)} on white ice is ${"%.2f".format(c4)}")
        }
    }

    @Test
    fun theRedZoneRedPassesAtEveryStop() {
        val red = SportsConstants.NFL_RED_ZONE
        assertEquals("#C8102E", hex(red))
        assertTrue(Contrast.ratio(white, red) >= 4.5, "flat")
        assertTrue(abs(Contrast.ratio(white, red) - 5.88) < 0.006)
        for (k in SportsConstants.PP_GRADIENT_BLACK) {
            val r = Contrast.ratio(white, NhlTeamColours.mixBlack(red, k.toDouble()))
            assertTrue(r >= 4.5, "white on the red at mixBlack $k: ${"%.2f".format(r)}")
        }
        assertTrue(abs(Contrast.ratio(white, NhlTeamColours.mixBlack(red, 0.10)) - 6.92) < 0.006, "the far stop")
        assertTrue(abs(NhlTeamColours.c5(red) - 7.76) < 0.006, "the C5 point")
        // Every point along the gradient, not only the stops.
        for (i in 0..100) {
            val k = NhlTeamColours.gradientBlackAt(i / 100f)
            assertTrue(Contrast.ratio(white, NhlTeamColours.mixBlack(red, k)) >= 4.5, "at ${i / 100f}")
        }
    }
}
