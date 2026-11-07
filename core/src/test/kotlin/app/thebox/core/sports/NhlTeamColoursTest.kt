package app.thebox.core.sports

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/design/m6-goal-animation.md §3.2, §3.3, §8.1. */
class NhlTeamColoursTest {
    private val white = 0xFFFFFFFF.toInt()

    private fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)

    /** The independent team list (S10): a recorded standings answer, never derived from the table. */
    private fun fixtureAbbrevs(): Set<String> {
        val root = Json.parseToJsonElement(SportsFixtures.text("sports/nhl/teams-fixture.json")).jsonObject
        return root.getValue("standings").jsonArray
            .map { it.jsonObject.getValue("teamAbbrev").jsonObject.getValue("default").jsonPrimitive.content }
            .toSet()
    }

    /** The fake panel's `TEAMS` table (`tools/fakepanel/sports.py`), so the emulator pass never falls back. */
    private fun fakePanelAbbrevs(): Set<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "tools/fakepanel/sports.py").isFile) dir = dir.parentFile
        val src = File(checkNotNull(dir) { "tools/fakepanel/sports.py not found above ${File("").absolutePath}" }, "tools/fakepanel/sports.py").readText()
        val body = src.substringAfter("TEAMS = {").substringBefore("\n}")
        return Regex("\"([A-Z]{3})\"\\s*:").findAll(body).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun everyTeamHasAnEntry() {
        val fixture = fixtureAbbrevs()
        assertEquals(32, fixture.size, "the recorded standings list 32 teams")
        assertEquals(fixture, NhlTeamColours.TABLE.keys, "the table's keys are exactly the league's teams")
        val panel = fakePanelAbbrevs()
        assertTrue(panel.isNotEmpty(), "fake panel TEAMS parsed")
        assertTrue(NhlTeamColours.TABLE.keys.containsAll(panel), "fake panel teams without colours: ${panel - NhlTeamColours.TABLE.keys}")
        for (a in fixture) assertTrue(NhlTeamColours.of(a) != NhlTeamColours.FALLBACK, "$a falls back")
    }

    @Test
    fun c1WhiteOnBaseIsAtLeast45ExceptPhi() {
        assertEquals(setOf("PHI"), NhlTeamColours.C1_EXCEPTIONS, "owner ruling 12: the exception list is exactly {PHI}")
        for ((a, c) in NhlTeamColours.TABLE) {
            val r = Contrast.ratio(white, c.first)
            if (a in NhlTeamColours.C1_EXCEPTIONS) {
                assertTrue(r < SportsConstants.GOAL_C1_MIN, "$a is listed as an exception but passes C1 (${"%.2f".format(r)}): drop it")
            } else {
                assertTrue(r >= SportsConstants.GOAL_C1_MIN, "C1 $a: white on ${hex(c.first)} is ${"%.2f".format(r)}")
            }
        }
        assertTrue(Contrast.ratio(white, NhlTeamColours.FALLBACK.base) >= SportsConstants.GOAL_C1_MIN)
    }

    @Test
    fun c4PopOnWhiteIceIsAtLeast45Everywhere() {
        val all = NhlTeamColours.TABLE.keys.map { it to NhlTeamColours.of(it) } + ("fallback" to NhlTeamColours.FALLBACK)
        for ((a, c) in all) {
            val r = Contrast.ratio(c.pop, SportsConstants.GLASS_WHITE_ICE)
            assertTrue(r >= SportsConstants.GOAL_C4_MIN, "C4 $a: ${hex(c.pop)} on white ice is ${"%.2f".format(r)}")
        }
    }

    @Test
    fun c2AndC3AreReportedNotAsserted() {
        // Owner ruling 12: true team colours can be dark on dark; a faint lead is accepted. Printed for review only.
        val sb = StringBuilder("abbr  base     accent   C1     C2     C3     glow     pop\n")
        for ((a, bc) in NhlTeamColours.TABLE.toSortedMap()) {
            val c = NhlTeamColours.of(a)
            val c1 = Contrast.ratio(white, bc.first)
            val c2 = Contrast.ratio(bc.second, SportsConstants.GLASS_DARK)
            val c3 = Contrast.ratio(bc.second, bc.first)
            fun flag(v: Double, floor: Double) = if (v < floor) "*" else " "
            sb.append(
                "%-5s %s  %s  %5.2f%s %5.2f%s %5.2f%s %s  %s\n".format(
                    a, hex(c.base), hex(c.accent),
                    c1, flag(c1, SportsConstants.GOAL_C1_MIN),
                    c2, flag(c2, SportsConstants.GOAL_C2_REPORT),
                    c3, flag(c3, SportsConstants.GOAL_C3_REPORT),
                    hex(c.glow), hex(c.pop),
                ),
            )
        }
        sb.append("* below the rule's floor (C1 4.5 asserted except {PHI}; C2 3.0 and C3 1.5 informational)")
        println(sb)
    }

    @Test
    fun lookupTrimsUpperCasesAndFallsBack() {
        assertEquals(NhlTeamColours.FALLBACK, NhlTeamColours.of(null))
        assertEquals(NhlTeamColours.FALLBACK, NhlTeamColours.of("XYZ"))
        assertEquals(NhlTeamColours.FALLBACK, NhlTeamColours.of(""))
        assertEquals(NhlTeamColours.of("CGY"), NhlTeamColours.of(" cgy "))
        val f = NhlTeamColours.FALLBACK
        assertEquals(listOf("#2A3140", "#5CA8F2", "#5CA8F2", "#5CA8F2"), listOf(f.base, f.accent, f.glow, f.pop).map(::hex))
        // Opaque ARGB throughout.
        for (a in NhlTeamColours.TABLE.keys) {
            val c = NhlTeamColours.of(a)
            for (v in listOf(c.base, c.accent, c.glow, c.pop)) assertEquals(0xFF, v ushr 24, a)
        }
    }

    @Test
    fun glowAndPopFollowTheDesignTable() {
        // §3.3, columns Base / Accent / Glow / Pop. CGY, EDM, NSH and PHI are the screenshot teams; PHI pops from its
        // base (black accent), not grey.
        val expected = mapOf(
            "ANA" to listOf("#171C19", "#FE4111", "accent", "#FE6741"),
            "BOS" to listOf("#000000", "#FFB81C", "accent", "#FFB81C"),
            "BUF" to listOf("#003087", "#FFB81C", "accent", "#FFB81C"),
            "CGY" to listOf("#D2001C", "#FAAF19", "base", "#FAAF19"),
            "CAR" to listOf("#CE1126", "#A4A9AD", "base", "#A4A9AD"),
            "CHI" to listOf("#CF0A2C", "#FFD100", "base", "#FFD100"),
            "COL" to listOf("#6F263D", "#236192", "accent", "#7BA0BE"),
            "CBJ" to listOf("#002654", "#CE1126", "accent", "#E47C88"),
            "DAL" to listOf("#006847", "#8F8F8C", "base", "#9A9A98"),
            "DET" to listOf("#CE1126", "#FFFFFF", "base", "#FFFFFF"),
            "EDM" to listOf("#041E42", "#FF4C00", "accent", "#FF6726"),
            "FLA" to listOf("#C8102E", "#041E42", "base", "#E17C8C"),
            "LAK" to listOf("#111111", "#A2AAAD", "accent", "#A2AAAD"),
            "MIN" to listOf("#154734", "#A6192E", "accent", "#CE808C"),
            "MTL" to listOf("#AF1E2D", "#192168", "base", "#D3838C"),
            "NSH" to listOf("#041E42", "#FFB81C", "accent", "#FFB81C"),
            "NJD" to listOf("#CE1126", "#000000", "base", "#E47C88"),
            "NYI" to listOf("#00539B", "#F47D30", "base", "#F47D30"),
            "NYR" to listOf("#0038A8", "#CE1126", "accent", "#E47C88"),
            "OTT" to listOf("#DA1A32", "#B79257", "base", "#B79257"),
            "PHI" to listOf("#F74902", "#000000", "base", "#F96D35"),
            "PIT" to listOf("#000000", "#FCB514", "accent", "#FCB514"),
            "SJS" to listOf("#006D75", "#EA7200", "base", "#EB790D"),
            "SEA" to listOf("#001628", "#99D9D9", "accent", "#99D9D9"),
            "STL" to listOf("#002F87", "#FCB514", "accent", "#FCB514"),
            "TBL" to listOf("#002868", "#FFFFFF", "accent", "#FFFFFF"),
            "TOR" to listOf("#00205B", "#FFFFFF", "accent", "#FFFFFF"),
            "UTA" to listOf("#010101", "#69B3E7", "accent", "#69B3E7"),
            "VAN" to listOf("#00205B", "#00843D", "accent", "#4CA977"),
            "VGK" to listOf("#333F42", "#B4975A", "accent", "#B4975A"),
            "WSH" to listOf("#041E42", "#C8102E", "accent", "#E17C8C"),
            "WPG" to listOf("#041E42", "#004C97", "accent", "#739DC6"),
        )
        assertEquals(expected.keys, NhlTeamColours.TABLE.keys)
        for ((a, e) in expected) {
            val c = NhlTeamColours.of(a)
            val glow = when (c.glow) {
                c.base -> "base"
                c.accent -> "accent"
                else -> hex(c.glow)
            }
            assertEquals(e, listOf(hex(c.base), hex(c.accent), glow, hex(c.pop)), a)
        }
        // The glow rule's threshold, spelled out for EDM (navy base vanishes on dark video) and CGY (red base reads).
        assertTrue(Contrast.ratio(NhlTeamColours.of("EDM").base, SportsConstants.GLASS_DARK) < SportsConstants.GOAL_GLOW_BASE_MIN)
        assertTrue(Contrast.ratio(NhlTeamColours.of("CGY").base, SportsConstants.GLASS_DARK) >= SportsConstants.GOAL_GLOW_BASE_MIN)
    }
}
