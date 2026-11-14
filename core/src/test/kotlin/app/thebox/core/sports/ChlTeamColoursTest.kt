package app.thebox.core.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * (CHL WP-4, docs/design/m6-chl-leagues.md §6.2) The 61 CHL teams' colours, per league, mirroring `NhlTeamColoursTest`:
 * the keys are the recorded 2026-27 `teamsbyseason` codes, C1 (with its exceptions), C4, C5 and C6 hold for every team,
 * the lookup trims, upper-cases and falls back, and a code shared with the NHL or a second CHL league never crosses over.
 */
class ChlTeamColoursTest {
    private val white = 0xFFFFFFFF.toInt()
    private fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)

    private val fixtureFile = mapOf(
        League.OHL to "sports/chl/lscluster/teamsbyseason-ohl-2026-27.json",
        League.WHL to "sports/chl/lscluster/teamsbyseason-whl-2026-27.json",
        League.QMJHL to "sports/chl/lscluster/teamsbyseason-lhjmq-2026-27.json",
    )

    /** The independent team list: the recorded feed's codes, upper-cased (the QMJHL's are mixed case). */
    private fun fixtureCodes(l: League): Set<String> =
        Json.parseToJsonElement(SportsFixtures.text(fixtureFile.getValue(l))).jsonObject.getValue("SiteKit").jsonObject
            .getValue("Teamsbyseason").jsonArray.map { it.jsonObject.getValue("code").jsonPrimitive.content.uppercase() }.toSet()

    @Test
    fun everyTeamHasAnEntryAndThereAreNoExtras() {
        val sizes = mapOf(League.OHL to 20, League.WHL to 23, League.QMJHL to 18)
        for (l in CHL_LEAGUES) {
            val fixture = fixtureCodes(l)
            assertEquals(sizes.getValue(l), fixture.size, "$l: the recorded feed lists ${sizes[l]} teams")
            assertEquals(fixture, ChlTeamColours.table(l).keys, "$l: the table's keys are exactly the league's teams")
            for (c in ChlTeamColours.table(l).keys) assertEquals(c.uppercase(), c, "$l $c is upper-case")
            for (c in fixture) assertNotEquals(ChlTeamColours.FALLBACK, ChlTeamColours.of(l, c), "$l $c falls back")
        }
        assertEquals(61, CHL_LEAGUES.sumOf { ChlTeamColours.table(it).size })
        assertTrue("NFL" in ChlTeamColours.table(League.QMJHL), "Newfoundland Regiment is a QMJHL team code")
    }

    @Test
    fun c1WhiteOnBaseIsAtLeast45ExceptTheListed() {
        assertEquals(setOf(League.OHL, League.WHL, League.QMJHL), ChlTeamColours.C1_EXCEPTIONS.keys)
        assertEquals(setOf("MH"), ChlTeamColours.C1_EXCEPTIONS.values.flatten().toSet(), "the exception list is exactly {WHL MH}")
        for (l in CHL_LEAGUES) for ((a, c) in ChlTeamColours.table(l)) {
            val r = Contrast.ratio(white, c.first)
            if (a in ChlTeamColours.C1_EXCEPTIONS.getValue(l)) {
                assertTrue(r < SportsConstants.GOAL_C1_MIN, "$l $a is listed as an exception but passes C1 (${"%.2f".format(r)}): drop it")
            } else {
                assertTrue(r >= SportsConstants.GOAL_C1_MIN, "C1 $l $a: white on ${hex(c.first)} is ${"%.2f".format(r)}")
            }
        }
    }

    @Test
    fun theDerivedColoursPassC4C5C6ForEveryTeam() {
        for (l in CHL_LEAGUES) for (a in ChlTeamColours.table(l).keys) {
            val c = ChlTeamColours.of(l, a)
            val pop = Contrast.ratio(c.pop, SportsConstants.GLASS_WHITE_ICE)
            assertTrue(pop >= SportsConstants.GOAL_C4_MIN, "C4 $l $a: pop ${hex(c.pop)} is ${"%.2f".format(pop)}")
            val c5 = NhlTeamColours.c5(c.tab)
            assertTrue(c5 >= SportsConstants.PP_C5_MIN, "C5 $l $a: white on tab ${hex(c.tab)} is ${"%.2f".format(c5)}")
            val c6 = NhlTeamColours.c6(c.glow, c.tintScale)
            assertTrue(c6 >= SportsConstants.PP_C6_MIN, "C6 $l $a: glow ${hex(c.glow)} x ${c.tintScale} is ${"%.2f".format(c6)}")
            for (v in listOf(c.base, c.accent, c.glow, c.pop, c.tab)) assertEquals(0xFF, v ushr 24, "$l $a opaque")
            // The tab is glow when C5 passes on it, else the other colour (the same derivation as the NHL's).
            val other = if (c.glow == c.base) c.accent else c.base
            assertEquals(if (NhlTeamColours.c5(c.glow) >= SportsConstants.PP_C5_MIN) c.glow else other, c.tab, "$l $a tab")
            assertEquals(NhlTeamColours.derive(c.base, c.accent), c, "$l $a is NhlTeamColours.derive of its pair")
        }
    }

    @Test
    fun lookupTrimsUpperCasesAndFallsBack() {
        for (l in CHL_LEAGUES) {
            assertEquals(ChlTeamColours.FALLBACK, ChlTeamColours.of(l, null), "$l null")
            assertEquals(ChlTeamColours.FALLBACK, ChlTeamColours.of(l, "XYZ"), "$l unknown")
            assertEquals(ChlTeamColours.FALLBACK, ChlTeamColours.of(l, ""), "$l empty")
        }
        assertEquals(NhlTeamColours.FALLBACK, ChlTeamColours.FALLBACK)
        assertEquals(ChlTeamColours.of(League.OHL, "OTT"), ChlTeamColours.of(League.OHL, " ott "))
        assertEquals(ChlTeamColours.of(League.QMJHL, "BAC"), ChlTeamColours.of(League.QMJHL, "BaC"), "the feed's mixed case")
        // Any other league gives the fallback, even for a code the table has.
        assertEquals(ChlTeamColours.FALLBACK, ChlTeamColours.of(League.NHL, "OTT"))
        assertEquals(ChlTeamColours.FALLBACK, ChlTeamColours.of(League.NFL, "NFL"))
        // A code is only a code in its own league: the OHL has no "NFL", the QMJHL has.
        assertEquals(ChlTeamColours.FALLBACK, ChlTeamColours.of(League.OHL, "NFL"))
        assertNotEquals(ChlTeamColours.FALLBACK, ChlTeamColours.of(League.QMJHL, "NFL"))
        assertEquals(ChlTeamColours.of(League.QMJHL, "NFL"), TeamColourTable.of(League.QMJHL, "NFL"))
    }

    @Test
    fun aCodeSharedWithAnotherLeagueNeverCrossesOver() {
        // OHL OTT is the 67's, the NHL's is the Senators; likewise CGY, EDM, SEA, VAN (WHL) and CHI (QMJHL).
        val shared = listOf(League.OHL to "OTT", League.WHL to "CGY", League.WHL to "EDM", League.WHL to "SEA", League.WHL to "VAN", League.QMJHL to "CHI")
        for ((l, a) in shared) {
            assertNotEquals(NhlTeamColours.of(a), ChlTeamColours.of(l, a), "$l $a must not be the NHL's $a")
            assertEquals(ChlTeamColours.of(l, a), TeamColourTable.of(l, a), "$l $a through the league-keyed table")
            assertEquals(NhlTeamColours.of(a), TeamColourTable.of(League.NHL, a), "NHL $a unchanged")
        }
        // VIC is the WHL's Royals (blue) and the QMJHL's Tigres (black and gold).
        assertNotEquals(ChlTeamColours.of(League.WHL, "VIC"), ChlTeamColours.of(League.QMJHL, "VIC"))
        assertEquals(0xFF013974.toInt(), ChlTeamColours.of(League.WHL, "VIC").base)
        assertEquals(0xFF000000.toInt(), ChlTeamColours.of(League.QMJHL, "VIC").base)
        // And a code that is in no other league is simply absent from them.
        assertEquals(ChlTeamColours.FALLBACK, ChlTeamColours.of(League.WHL, "BAR"))
    }

    /** (S4) The documented departures from the research note: (league, code) to (base, accent) as the table has them. */
    private val nudges: Map<Pair<League, String>, Pair<Int, Int>> = mapOf(
        (League.OHL to "NB") to (0x7F7911 to 0x000000), // C1 4.46 -> 4.52
        (League.WHL to "KEL") to (0x008293 to 0x000000), // C1 4.49 -> 4.55
        (League.OHL to "PBO") to (0x77273E to 0x000000), // R2: base against the glass 1.97 -> 2.01 (maroon glow, not black)
        (League.WHL to "WEN") to (0x002F87 to 0x026FA8), // R2: sourced blue accent, not near-black 231F20
    )

    /** `docs/research/chl-team-colours.md`'s three team tables: (league, code) to (primary, secondary) as the note has them. */
    private fun noteRows(): Map<Pair<League, String>, Pair<Int, Int>> {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null && !java.io.File(dir, "docs/research/chl-team-colours.md").isFile) dir = dir.parentFile
        val text = checkNotNull(dir) { "docs/research/chl-team-colours.md not found above ${java.io.File("").absolutePath}" }
            .let { java.io.File(it, "docs/research/chl-team-colours.md").readText() }
        val row = Regex("^\\| ([A-Z]+) \\| [^|]+ \\| `#([0-9A-F]{6})` \\| `#([0-9A-F]{6})` \\|")
        val rows = HashMap<Pair<League, String>, Pair<Int, Int>>()
        var league: League? = null
        for (line in text.lines()) {
            if (line.startsWith("## ")) league = CHL_LEAGUES.firstOrNull { line.startsWith("## ${it.name} (") }
            val l = league ?: continue
            val m = row.find(line) ?: continue
            val key = l to m.groupValues[1]
            check(key !in rows) { "duplicate row $key in the note" }
            rows[key] = m.groupValues[2].toInt(16) to m.groupValues[3].toInt(16)
        }
        return rows
    }

    @Test
    fun everyBaseAndAccentEqualsTheResearchNoteExceptTheListedNudges() {
        val note = noteRows()
        assertEquals(61, note.size, "the note's three tables list 61 teams")
        for (l in CHL_LEAGUES) for (code in ChlTeamColours.codes(l)) {
            val want = nudges[l to code] ?: note.getValue(l to code)
            val got = ChlTeamColours.of(l, code)
            assertEquals(
                listOf(hex(want.first), hex(want.second)), listOf(hex(got.base), hex(got.accent)), "$l $code base/accent",
            )
        }
        assertEquals(note.keys, CHL_LEAGUES.flatMap { l -> ChlTeamColours.codes(l).map { l to it } }.toSet(), "no row missing or extra")
        // A nudge is a nudge: it differs from the note, and by no more than the stated change.
        for ((k, v) in nudges) assertNotEquals(note.getValue(k), v, "$k is listed as a nudge but equals the note: drop it")
    }

    @Test
    fun theSourcedPrimariesArePinned() {
        // Spot checks of the sourced hex (docs/research/chl-team-colours.md); NB and KEL are the 1 % C1 nudges.
        assertEquals("#AC1F2D", hex(ChlTeamColours.of(League.OHL, "OTT").base))
        assertEquals("#7F7911", hex(ChlTeamColours.of(League.OHL, "NB").base))
        assertEquals("#008293", hex(ChlTeamColours.of(League.WHL, "KEL").base))
        assertEquals("#E56B1F", hex(ChlTeamColours.of(League.WHL, "MH").base))
        assertEquals("#231F20", hex(ChlTeamColours.of(League.WHL, "MH").accent))
        // R2: PBO's glow and tab are maroon (not black), WEN's are blue (not near-black).
        assertEquals("#77273E", hex(ChlTeamColours.of(League.OHL, "PBO").glow))
        assertEquals("#77273E", hex(ChlTeamColours.of(League.OHL, "PBO").tab))
        assertEquals("#026FA8", hex(ChlTeamColours.of(League.WHL, "WEN").glow))
        assertEquals("#026FA8", hex(ChlTeamColours.of(League.WHL, "WEN").tab))
        assertEquals("#0E2342", hex(ChlTeamColours.of(League.QMJHL, "NFL").base))
        assertEquals("#FCB827", hex(ChlTeamColours.of(League.QMJHL, "VIC").accent))
    }
}
