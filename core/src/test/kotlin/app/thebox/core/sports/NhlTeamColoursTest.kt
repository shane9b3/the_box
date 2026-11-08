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

    // ------------------------------------------------------------------ power play (docs/design/m6-power-play.md §6)

    /** Independent of the production helpers: each channel × (1 − k), rounded half to even. */
    private fun mixBlack(argb: Int, k: Double): Int {
        fun ch(sh: Int) = Math.rint(((argb shr sh) and 0xFF) * (1 - k)).toInt() shl sh
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    private fun over(fg: Int, a: Double, bg: Int): Int {
        fun ch(sh: Int): Int {
            val f = (fg shr sh) and 0xFF
            val b = (bg shr sh) and 0xFF
            return Math.rint(b + (f - b) * a).toInt() shl sh
        }
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    /** §6.2: the far cap edge (.80) between the .28 @ .5 and .10 @ 1 stops: k = .172. */
    private fun c5(tab: Int) = Contrast.ratio(white, mixBlack(tab, 0.172))

    /** §6.3: x 92 of 404 lies between the .9 @ 0 and .5 @ 25 % stops: alpha .536. */
    private fun c6(glow: Int, scale: Double) =
        Contrast.ratio(white, over(glow, (0.9 - 0.4 * (92.0 / 404.0) / 0.25) * scale, SportsConstants.GLASS_WHITE_ICE))

    private fun all() = NhlTeamColours.TABLE.keys.sorted().map { it to NhlTeamColours.of(it) } + ("fallback" to NhlTeamColours.FALLBACK)

    @Test
    fun c5WhiteTabTextIsAtLeast45ForEveryTeamWithNoExceptions() {
        assertEquals(emptySet(), NhlTeamColours.C5_EXCEPTIONS, "owner ruling 17: no C5 exceptions")
        assertEquals(0.172, NhlTeamColours.gradientBlackAt(SportsConstants.PP_C5_POINT), 1e-6)
        assertEquals(listOf(0.70f, 0.28f, 0.10f), SportsConstants.PP_GRADIENT_BLACK, "ruling 17: the far stop is .10")
        for ((a, c) in all()) {
            val r = c5(c.tab)
            assertTrue(r >= SportsConstants.PP_C5_MIN, "C5 $a: white on ${hex(c.tab)} at .80 is ${"%.2f".format(r)}")
            assertEquals(r, NhlTeamColours.c5(c.tab), 1e-9, a)
        }
    }

    @Test
    fun c6WhiteDigitsOnTheTintOverWhiteIceIsAtLeast45ForEveryTeam() {
        assertEquals(0.536, NhlTeamColours.tintAlphaAt(SportsConstants.PP_C6_X), 1e-3)
        for ((a, c) in all()) {
            val r = c6(c.glow, c.tintScale.toDouble())
            assertTrue(r >= SportsConstants.PP_C6_MIN, "C6 $a: ${hex(c.glow)} × ${c.tintScale} is ${"%.2f".format(r)}")
            // The scale is the largest 5 % step that passes.
            if (c.tintScale < 1f) {
                val up = c6(c.glow, c.tintScale + 0.05)
                assertTrue(up < SportsConstants.PP_C6_MIN, "$a: ${c.tintScale + 0.05} already passes (${"%.2f".format(up)})")
            }
        }
    }

    @Test
    fun tabAndTintScaleFollowTheDesignTable() {
        // §6.4: (tab, C5, tintScale, C6), two decimals.
        val expected = mapOf(
            "ANA" to listOf("#FE4111", "4.91", "1.00", "6.95"), "NJD" to listOf("#CE1126", "7.46", "1.00", "9.55"),
            "BOS" to listOf("#000000", "21.00", "0.90", "4.55"), "NYI" to listOf("#00539B", "9.71", "1.00", "10.39"),
            "BUF" to listOf("#003087", "13.66", "0.90", "4.55"), "NYR" to listOf("#CE1126", "7.46", "1.00", "9.55"),
            "CGY" to listOf("#D2001C", "7.47", "1.00", "9.81"), "OTT" to listOf("#DA1A32", "6.75", "1.00", "8.88"),
            "CAR" to listOf("#CE1126", "7.46", "1.00", "9.55"), "PHI" to listOf("#F74902", "4.95", "1.00", "6.92"),
            "CHI" to listOf("#CF0A2C", "7.52", "1.00", "9.70"), "PIT" to listOf("#000000", "21.00", "0.90", "4.64"),
            "COL" to listOf("#236192", "8.48", "1.00", "9.30"), "SJS" to listOf("#006D75", "7.97", "1.00", "9.07"),
            "CBJ" to listOf("#CE1126", "7.46", "1.00", "9.55"), "SEA" to listOf("#001628", "18.94", "0.85", "4.50"),
            "DAL" to listOf("#006847", "8.77", "1.00", "9.59"), "STL" to listOf("#002F87", "13.78", "0.90", "4.64"),
            "DET" to listOf("#CE1126", "7.46", "1.00", "9.55"), "TBL" to listOf("#002868", "15.53", "0.60", "4.82"),
            "EDM" to listOf("#FF4C00", "4.69", "1.00", "6.65"), "TOR" to listOf("#00205B", "16.81", "0.60", "4.82"),
            "FLA" to listOf("#C8102E", "7.76", "1.00", "9.78"), "UTA" to listOf("#010101", "20.87", "1.00", "4.84"),
            "LAK" to listOf("#111111", "19.30", "1.00", "4.95"), "VAN" to listOf("#00843D", "6.49", "1.00", "7.91"),
            "MIN" to listOf("#A6192E", "9.55", "1.00", "10.85"), "VGK" to listOf("#333F42", "12.77", "1.00", "5.54"),
            "MTL" to listOf("#AF1E2D", "8.86", "1.00", "10.32"), "WSH" to listOf("#C8102E", "7.76", "1.00", "9.78"),
            "NSH" to listOf("#041E42", "17.53", "0.90", "4.55"), "WPG" to listOf("#004C97", "10.46", "1.00", "10.80"),
            "fallback" to listOf("#2A3140", "14.58", "1.00", "5.19"),
        )
        assertEquals(expected.keys, all().map { it.first }.toSet())
        for ((a, c) in all()) {
            val got = listOf(hex(c.tab), "%.2f".format(c5(c.tab)), "%.2f".format(c.tintScale), "%.2f".format(c6(c.glow, c.tintScale.toDouble())))
            assertEquals(expected.getValue(a), got, a)
            // The tab is glow when C5 passes on it, else the other colour.
            val other = if (c.glow == c.base) c.accent else c.base
            assertEquals(if (c5(c.glow) >= SportsConstants.PP_C5_MIN) c.glow else other, c.tab, a)
        }
        // §6.4 "which colour goes where": 21 one-colour teams, 11 with a dark tab and a bright tint and halo.
        val split = NhlTeamColours.TABLE.keys.partition { NhlTeamColours.of(it).tab == NhlTeamColours.of(it).glow }
        assertEquals(21, split.first.size, "one colour: ${split.first}")
        assertEquals(setOf("BOS", "BUF", "LAK", "NSH", "PIT", "SEA", "STL", "TBL", "TOR", "UTA", "VGK"), split.second.toSet())
        assertEquals(
            setOf("TBL", "TOR", "SEA", "BOS", "BUF", "NSH", "PIT", "STL"),
            NhlTeamColours.TABLE.keys.filter { NhlTeamColours.of(it).tintScale < 1f }.toSet(),
            "the tint is weakened for 8 teams",
        )
    }

    @Test
    fun tabAndTintScalePinned() {
        // §9.1: the screenshot teams' tabs, and the tint scales.
        assertEquals("#D2001C", hex(NhlTeamColours.of("CGY").tab))
        assertEquals("#FF4C00", hex(NhlTeamColours.of("EDM").tab)) // orange (ruling 17), not the navy base
        assertEquals("#041E42", hex(NhlTeamColours.of("NSH").tab)) // navy tab, gold tint
        assertEquals("#F74902", hex(NhlTeamColours.of("PHI").tab))
        assertEquals("#FFB81C", hex(NhlTeamColours.of("NSH").glow))
        assertEquals(0.60f, NhlTeamColours.of("TBL").tintScale, 1e-4f)
        assertEquals(0.60f, NhlTeamColours.of("TOR").tintScale, 1e-4f)
        assertEquals(0.85f, NhlTeamColours.of("SEA").tintScale, 1e-4f)
        assertEquals(0.90f, NhlTeamColours.of("NSH").tintScale, 1e-4f)
        assertEquals(1f, NhlTeamColours.of("CGY").tintScale)
        for ((a, c) in all()) assertEquals(0xFF, c.tab ushr 24, a)
    }

    @Test
    fun mixBlackRoundsEachChannel() {
        assertEquals("#D3D300", hex(NhlTeamColours.mixBlack(0xFFFFFF00.toInt(), 0.172))) // 255 × .828 = 211.1
        assertEquals(0xFF, NhlTeamColours.mixBlack(0x00FFFFFF, 0.5) ushr 24, "always opaque")
        assertEquals("#000000", hex(NhlTeamColours.mixBlack(0xFFFF4C00.toInt(), 1.0)))
        assertEquals("#FF4C00", hex(NhlTeamColours.mixBlack(0xFFFF4C00.toInt(), 0.0)))
        // EDM at the far cap edge: (255, 76, 0) × .828 = (211.1, 62.9, 0) → #D33F00.
        assertEquals("#D33F00", hex(NhlTeamColours.mixBlack(0xFFFF4C00.toInt(), 0.172)))
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
