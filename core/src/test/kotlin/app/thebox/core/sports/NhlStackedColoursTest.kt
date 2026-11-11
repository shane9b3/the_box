package app.thebox.core.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import app.thebox.core.sports.SportsConstants as K

/**
 * docs/design/m6-score-bug-stacked-theme.md §5, §10.1 `NhlStackedColoursTest` (S2, S3, S11): every rule for all 32 teams
 * and the fallback, the §5.1 table pinned for EDM / TOR / BOS / WPG, and the §5.2 collision rule over all 32 × 31 ordered
 * pairs.
 */
class NhlStackedColoursTest {
    private val white = 0xFFFFFFFF.toInt()
    private fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)
    private fun argb(rgb: Int) = (0xFF shl 24) or rgb

    private val teams: List<String> = NhlTeamColours.TABLE.keys.sorted()
    /** Every team plus the fallback (an unknown abbreviation). */
    private val all: List<String?> = teams + listOf<String?>(null)
    private fun name(a: String?) = a ?: "fallback"

    private fun ratio(a: Int, b: Int) = Contrast.ratio(a, b)

    /** The flash blend at the team's peak α over the row navy (t = 3 600, envelope 1). */
    private fun blend(c: StackedColours) = NhlStackedColours.over(c.stripe, c.rowFlashAlpha.toDouble(), K.STACKED_ROW_BG)

    /** Every per-colour rule, for one team's colours (primary or swapped). */
    private fun assertRules(c: StackedColours, what: String) {
        assertTrue(ratio(c.stripe, K.STACKED_ROW_BG) >= K.STACKED_STRIPE_MIN, "$what: stripe ${hex(c.stripe)} on the row ${ratio(c.stripe, K.STACKED_ROW_BG)}")
        assertTrue(ratio(c.stripe, K.STACKED_BAR_NEUTRAL) >= K.STACKED_STRIPE_MIN, "$what: stripe on the neutral bar ${ratio(c.stripe, K.STACKED_BAR_NEUTRAL)}")
        // C7 (S11): white and the scorer's pop ≥ 3.0 on the flash blend, α in .30..55 on the .05 grid.
        val b = blend(c)
        assertTrue(ratio(white, b) >= K.STACKED_C7_MIN, "$what: C7 white ${ratio(white, b)} at α ${c.rowFlashAlpha}")
        assertTrue(ratio(c.goal.pop, b) >= K.STACKED_C7_MIN, "$what: C7 pop ${ratio(c.goal.pop, b)} at α ${c.rowFlashAlpha}")
        assertTrue(c.rowFlashAlpha in 0.2999f..0.5501f, "$what: flash α ${c.rowFlashAlpha}")
        val steps = (K.STACKED_ROW_FLASH_ALPHA - c.rowFlashAlpha) / K.STACKED_ROW_FLASH_STEP
        assertEquals(Math.round(steps).toFloat(), steps, 1e-3f, "$what: α on the .05 grid")
        // The first step that passes: one step up fails (unless at .55).
        if (c.rowFlashAlpha < K.STACKED_ROW_FLASH_ALPHA - 1e-4f) {
            assertFalse(NhlStackedColours.c7(c.stripe, c.goal.pop, c.rowFlashAlpha + K.STACKED_ROW_FLASH_STEP), "$what: α is the first that passes")
        }
    }

    @Test
    fun everyTeamPassesEveryRule() {
        for (a in all) {
            val c = NhlStackedColours.of(a)
            val r = NhlStackedColours.resolved(a)
            assertEquals(c, r.primary)
            assertRules(c, name(a))
            assertRules(r.swapped, "${name(a)} swapped")
            // White bar text ≥ 4.5 on the PP bar, no exceptions (PP §6.2 C5's point).
            assertTrue(ratio(white, c.barBg) >= 4.5, "${name(a)}: white on barBg ${hex(c.barBg)} ${ratio(white, c.barBg)}")
            assertEquals(NhlTeamColours.mixBlack(c.goal.tab, K.STACKED_BAR_BG_BLACK), c.barBg, name(a))
            // S2: A and B each ≥ 2.0 against the bar; A against B ≥ 1.5.
            assertTrue(ratio(c.barA, c.barBg) >= K.STACKED_BAR_STRIPE_MIN, "${name(a)}: A ${hex(c.barA)} ${ratio(c.barA, c.barBg)}")
            assertTrue(ratio(c.barB, c.barBg) >= K.STACKED_BAR_STRIPE_MIN, "${name(a)}: B ${hex(c.barB)} ${ratio(c.barB, c.barBg)}")
            assertTrue(ratio(c.barA, c.barB) >= K.STACKED_BAR_AB_MIN, "${name(a)}: A/B ${ratio(c.barA, c.barB)}")
            // The goal panel keeps the true colours (ruling 12).
            assertEquals(NhlTeamColours.of(a), c.goal)
            assertEquals(c.goal, r.swapped.goal)
            assertEquals(c.barBg, r.swapped.barBg); assertEquals(c.barA, r.swapped.barA); assertEquals(c.barB, r.swapped.barB)
        }
    }

    @Test
    fun theStripeIsTheBetterTeamColourLightenedToThreeToOne() {
        for (a in all) {
            val g = NhlTeamColours.of(a)
            val c = NhlStackedColours.of(a)
            val row = K.STACKED_ROW_BG
            val src = if (ratio(g.accent, row) > ratio(g.base, row)) g.accent else g.base
            val other = if (src == g.accent) g.base else g.accent
            assertEquals(NhlStackedColours.lighten(src), c.stripe, name(a))
            assertEquals(NhlStackedColours.lighten(other), NhlStackedColours.resolved(a).swapped.stripe, "${name(a)} other")
        }
        // §5.1: unchanged for 23 teams and the fallback; the rest lightened as listed.
        val lightened = mapOf(
            "COL" to 0x4479A2, "CBJ" to 0xD53547, "NJD" to 0xD53547, "NYR" to 0xD53547,
            "FLA" to 0xD34058, "WSH" to 0xD34058, "MTL" to 0xBF4B57, "MIN" to 0xBC5262, "WPG" to 0x4079B1,
        )
        for (a in all) {
            val g = NhlTeamColours.of(a)
            val s = NhlStackedColours.of(a).stripe
            val expected = lightened[a]
            if (expected != null) {
                assertEquals(hex(argb(expected)), hex(s), "${name(a)} lightened")
            } else {
                assertTrue(s == g.base || s == g.accent, "${name(a)} unchanged: ${hex(s)}")
            }
        }
    }

    @Test
    fun theSection51TableIsPinned() {
        val edm = NhlStackedColours.of("EDM")
        assertEquals("#FF4C00", hex(edm.stripe))
        assertEquals("#D33F00", hex(edm.barBg))
        assertEquals("#041E42", hex(edm.barA))
        assertEquals("#FF9466", hex(edm.barB))
        assertEquals(0.40f, edm.rowFlashAlpha, 1e-4f)

        val tor = NhlStackedColours.of("TOR")
        assertEquals("#FFFFFF", hex(tor.stripe))
        assertEquals("#001A4B", hex(tor.barBg))
        assertEquals("#FFFFFF", hex(tor.barA))
        assertEquals("#66799D", hex(tor.barB))
        assertEquals(0.50f, tor.rowFlashAlpha, 1e-4f)

        // BOS: gold A and a grey B on a black bar.
        val bos = NhlStackedColours.of("BOS")
        assertEquals("#000000", hex(bos.barBg))
        assertEquals("#FFB81C", hex(bos.barA))
        assertEquals("#666666", hex(bos.barB))
        assertEquals("#FFB81C", hex(bos.stripe))

        // WPG: the stripe lightened 5 steps.
        val wpg = NhlStackedColours.of("WPG")
        assertEquals("#4079B1", hex(wpg.stripe))
        assertEquals(NhlStackedColours.towardWhite(argb(0x004C97), 0.25), wpg.stripe)

        // Case and whitespace are ignored; unknown and null are the fallback.
        assertEquals(edm, NhlStackedColours.of(" edm "))
        assertEquals(NhlStackedColours.of(null), NhlStackedColours.of("XYZ"))
        assertEquals(NhlTeamColours.FALLBACK, NhlStackedColours.of(null).goal)
    }

    @Test
    fun theFlashAlphaRangeMatchesTheDesign() {
        val alphas = teams.associateWith { NhlStackedColours.of(it).rowFlashAlpha }
        assertEquals(0.30f, alphas.values.min(), 1e-4f)
        assertEquals(0.55f, alphas.values.max(), 1e-4f)
        for (a in listOf("OTT", "VGK")) assertEquals(0.30f, alphas.getValue(a), 1e-4f, a)
        for (a in listOf("CBJ", "MTL", "NJD", "NYR")) assertEquals(0.55f, alphas.getValue(a), 1e-4f, a)
    }

    @Test
    fun theNavyBlackAndGreyBarsAreTheTwelveListed() {
        // §5.1: these PP bars are under 1.5 : 1 against the rows (the hairline and the stripes carry the edge).
        // NYI (#004580) is 1.4998 : 1, which §5.1 rounded to 1.50 and left out of its list of 12; it is listed here.
        val dark = teams.filter { ratio(NhlStackedColours.of(it).barBg, K.STACKED_ROW_BG) < 1.5 }.toSet()
        assertEquals(setOf("BUF", "STL", "TBL", "TOR", "NSH", "VGK", "SEA", "LAK", "WPG", "UTA", "BOS", "PIT", "NYI"), dark)
        assertEquals(1.4998, ratio(NhlStackedColours.of("NYI").barBg, K.STACKED_ROW_BG), 1e-4)
        assertTrue(ratio(NhlStackedColours.of(null).barBg, K.STACKED_ROW_BG) < 1.5, "and the fallback")
    }

    // ------------------------------------------------------------------ §5.2 collisions

    @Test
    fun deltaEIsCielab76() {
        assertEquals(0.0, NhlStackedColours.deltaE76(white, white), 1e-9)
        assertEquals(100.0, NhlStackedColours.deltaE76(white, argb(0)), 1e-3)
        val l = NhlStackedColours.lab(argb(0xFF0000))
        assertEquals(53.24, l[0], 0.01); assertEquals(80.09, l[1], 0.01); assertEquals(67.20, l[2], 0.01)
    }

    @Test
    fun everyOrderedPairIsDistinctAfterPair() {
        var collidedBefore = 0
        val awaySwaps = mutableSetOf<Pair<String, String>>()
        for (a in teams) for (h in teams) {
            if (a == h) continue
            val p = NhlStackedColours.pair(a, h)
            val dE = NhlStackedColours.deltaE76(p.away.stripe, p.home.stripe)
            assertTrue(dE >= K.STACKED_STRIPE_DE_MIN, "$a @ $h: ΔE $dE (${hex(p.away.stripe)} / ${hex(p.home.stripe)})")
            val pa = NhlStackedColours.of(a)
            val ph = NhlStackedColours.of(h)
            if (NhlStackedColours.collides(pa.stripe, ph.stripe)) {
                collidedBefore++
                if (p.away != pa) {
                    // Step 2: home keeps its primary, away uses its other colour.
                    assertEquals(ph, p.home)
                    assertEquals(NhlStackedColours.resolved(a).swapped, p.away)
                    awaySwaps += a to h
                } else {
                    // Step 1: home uses its other colour.
                    assertEquals(NhlStackedColours.resolved(h).swapped, p.home)
                }
            } else {
                assertEquals(StackedPair(pa, ph), p, "$a @ $h: no collision, no swap")
            }
        }
        // §5.2: 50 unordered pairs collide (100 ordered); step 2 only for COL–WPG, DAL–LAK, CAR–LAK.
        assertEquals(100, collidedBefore)
        val step2Teams = awaySwaps.map { setOf(it.first, it.second) }.toSet()
        assertEquals(setOf(setOf("COL", "WPG"), setOf("DAL", "LAK"), setOf("CAR", "LAK")), step2Teams, "step-2 pairs: $awaySwaps")
    }

    @Test
    fun theNamedMatchupsCollideAndResolve() {
        val named = listOf("NYR" to "NJD", "FLA" to "WSH", "TOR" to "DET", "BOS" to "PIT", "BUF" to "BOS",
            "CAR" to "LAK", "COL" to "WPG", "DAL" to "LAK")
        for ((x, y) in named) for ((a, h) in listOf(x to y, y to x)) {
            assertTrue(NhlStackedColours.collides(NhlStackedColours.of(a).stripe, NhlStackedColours.of(h).stripe), "$a @ $h collide")
            val p = NhlStackedColours.pair(a, h)
            assertTrue(NhlStackedColours.deltaE76(p.away.stripe, p.home.stripe) >= K.STACKED_STRIPE_DE_MIN, "$a @ $h resolved")
            // The swapped team keeps its true goal colours.
            assertEquals(NhlTeamColours.of(a), p.away.goal)
            assertEquals(NhlTeamColours.of(h), p.home.goal)
        }
        // NYR @ NJD: NJD (home) swaps to black, lightened.
        val p = NhlStackedColours.pair("NYR", "NJD")
        assertEquals(NhlStackedColours.of("NYR"), p.away)
        assertEquals(NhlStackedColours.resolved("NJD").swapped, p.home)
        // EDM @ TOR: no collision, the teams' own colours.
        assertEquals(StackedPair(NhlStackedColours.of("EDM"), NhlStackedColours.of("TOR")), NhlStackedColours.pair("EDM", "TOR"))
    }

    @Test
    fun pairsAreCachedAndCaseInsensitive() {
        val p = NhlStackedColours.pair("NYR", "NJD")
        assertSame(p, NhlStackedColours.pair("NYR", "NJD"))
        assertSame(p, NhlStackedColours.pair(" nyr", "njd "))
        // Two unknown teams collide with each other (the same stripe) and resolve too.
        val u = NhlStackedColours.pair("XXX", null)
        assertTrue(NhlStackedColours.deltaE76(u.away.stripe, u.home.stripe) >= K.STACKED_STRIPE_DE_MIN)
        // The fallback against every team.
        for (t in teams) for ((a, h) in listOf(t to null, null to t)) {
            val q = NhlStackedColours.pair(a, h)
            assertTrue(NhlStackedColours.deltaE76(q.away.stripe, q.home.stripe) >= K.STACKED_STRIPE_DE_MIN, "${name(a)} @ ${name(h)}")
        }
    }
}
