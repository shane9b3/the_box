package app.thebox.core.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import app.thebox.core.sports.SportsConstants as K

/**
 * (CHL WP-4, docs/design/m6-chl-leagues.md §1.5, §6.2) `StackedColourTable`: every `NhlStackedColoursTest` rule per CHL
 * league, the look-alike rule over all ordered pairs within each league, the cache, the cross-league keying, and the
 * NHL's results unchanged (byte for byte against `NhlStackedColours`).
 */
class StackedColourTableTest {
    private val white = 0xFFFFFFFF.toInt()
    private fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)
    private fun ratio(a: Int, b: Int) = Contrast.ratio(a, b)

    private fun teams(l: League): List<String> = ChlTeamColours.table(l).keys.sorted()
    private fun name(a: String?) = a ?: "fallback"

    private fun blend(c: StackedColours) = NhlStackedColours.over(c.stripe, c.rowFlashAlpha.toDouble(), K.STACKED_ROW_BG)

    /** Every per-colour rule, for one team's colours (primary or swapped). */
    private fun assertRules(c: StackedColours, what: String) {
        assertTrue(ratio(c.stripe, K.STACKED_ROW_BG) >= K.STACKED_STRIPE_MIN, "$what: stripe ${hex(c.stripe)} on the row ${ratio(c.stripe, K.STACKED_ROW_BG)}")
        assertTrue(ratio(c.stripe, K.STACKED_BAR_NEUTRAL) >= K.STACKED_STRIPE_MIN, "$what: stripe on the neutral bar ${ratio(c.stripe, K.STACKED_BAR_NEUTRAL)}")
        val b = blend(c)
        assertTrue(ratio(white, b) >= K.STACKED_C7_MIN, "$what: C7 white ${ratio(white, b)} at alpha ${c.rowFlashAlpha}")
        assertTrue(ratio(c.goal.pop, b) >= K.STACKED_C7_MIN, "$what: C7 pop ${ratio(c.goal.pop, b)} at alpha ${c.rowFlashAlpha}")
        assertTrue(c.rowFlashAlpha in 0.2999f..0.5501f, "$what: flash alpha ${c.rowFlashAlpha}")
    }

    @Test
    fun everyTeamPassesEveryRuleInItsLeague() {
        for (l in CHL_LEAGUES) for (a in teams(l) + listOf<String?>(null)) {
            val r = StackedColourTable.resolved(l, a)
            val c = r.primary
            val what = "$l ${name(a)}"
            assertRules(c, what)
            assertRules(r.swapped, "$what swapped")
            assertTrue(ratio(white, c.barBg) >= 4.5, "$what: white on barBg ${hex(c.barBg)} ${ratio(white, c.barBg)}")
            assertEquals(NhlTeamColours.mixBlack(c.goal.tab, K.STACKED_BAR_BG_BLACK), c.barBg, what)
            assertTrue(ratio(c.barA, c.barBg) >= K.STACKED_BAR_STRIPE_MIN, "$what: A ${hex(c.barA)} ${ratio(c.barA, c.barBg)}")
            assertTrue(ratio(c.barB, c.barBg) >= K.STACKED_BAR_STRIPE_MIN, "$what: B ${hex(c.barB)} ${ratio(c.barB, c.barBg)}")
            assertTrue(ratio(c.barA, c.barB) >= K.STACKED_BAR_AB_MIN, "$what: A/B ${ratio(c.barA, c.barB)}")
            // The goal panel keeps the true colours, and the swap changes only the stripe and its flash.
            assertEquals(ChlTeamColours.of(l, a), c.goal, what)
            assertEquals(c.goal, r.swapped.goal)
            assertEquals(c.barBg, r.swapped.barBg); assertEquals(c.barA, r.swapped.barA); assertEquals(c.barB, r.swapped.barB)
            // The stripe is the better team colour lightened to 3 : 1; the swap is the other one under the same rule.
            val g = c.goal
            val src = if (ratio(g.accent, K.STACKED_ROW_BG) > ratio(g.base, K.STACKED_ROW_BG)) g.accent else g.base
            val other = if (src == g.accent) g.base else g.accent
            assertEquals(NhlStackedColours.lighten(src), c.stripe, what)
            assertEquals(NhlStackedColours.lighten(other), r.swapped.stripe, "$what other")
        }
    }

    @Test
    fun everyOrderedPairInALeagueIsDistinctAfterPair() {
        for (l in CHL_LEAGUES) {
            val t = teams(l)
            var collided = 0
            var pairs = 0
            for (a in t) for (h in t) {
                if (a == h) continue
                pairs++
                val p = StackedColourTable.pair(l, a, h)
                val dE = NhlStackedColours.deltaE76(p.away.stripe, p.home.stripe)
                assertTrue(dE >= K.STACKED_STRIPE_DE_MIN, "$l $a @ $h: dE $dE (${hex(p.away.stripe)} / ${hex(p.home.stripe)})")
                val pa = StackedColourTable.resolved(l, a)
                val ph = StackedColourTable.resolved(l, h)
                if (NhlStackedColours.collides(pa.primary.stripe, ph.primary.stripe)) {
                    collided++
                    if (p.away != pa.primary) {
                        assertEquals(ph.primary, p.home, "$l $a @ $h: step 2 keeps home's primary")
                        assertEquals(pa.swapped, p.away, "$l $a @ $h: step 2 swaps away")
                    } else {
                        assertEquals(ph.swapped, p.home, "$l $a @ $h: step 1 swaps home")
                    }
                } else {
                    assertEquals(StackedPair(pa.primary, ph.primary), p, "$l $a @ $h: no collision, no swap")
                }
                // The true goal colours are never swapped.
                assertEquals(ChlTeamColours.of(l, a), p.away.goal)
                assertEquals(ChlTeamColours.of(l, h), p.home.goal)
            }
            assertEquals(t.size * (t.size - 1), pairs)
            assertTrue(collided > 0, "$l: the rule is exercised")
            println("$l: $collided of $pairs ordered pairs collide before the swap")
        }
    }

    @Test
    fun theLookAlikeRuleStaysInsideTheLeague() {
        // OHL OTT @ NHL-style rivals: the same code in two leagues is two different teams. VIC exists in the WHL and QMJHL.
        assertNotEquals(StackedColourTable.pair(League.WHL, "VIC", "VAN").away, StackedColourTable.pair(League.QMJHL, "VIC", "VAN").away)
        assertNotEquals(NhlStackedColours.of("OTT"), StackedColourTable.pair(League.OHL, "OTT", "KIT").away)
        assertEquals(StackedColourTable.resolved(League.OHL, "OTT").primary, StackedColourTable.pair(League.OHL, "OTT", "KIT").away)
        // An OHL code in the WHL is unknown there: the fallback.
        assertEquals(StackedColourTable.resolved(League.WHL, null).primary, StackedColourTable.pair(League.WHL, "BAR", "XYZ").away)
        // Two unknown teams collide with each other and still resolve.
        for (l in CHL_LEAGUES) {
            val u = StackedColourTable.pair(l, "XXX", null)
            assertTrue(NhlStackedColours.deltaE76(u.away.stripe, u.home.stripe) >= K.STACKED_STRIPE_DE_MIN, "$l unknown pair")
            for (t in teams(l)) for ((a, h) in listOf(t to null, null to t)) {
                val q = StackedColourTable.pair(l, a, h)
                assertTrue(NhlStackedColours.deltaE76(q.away.stripe, q.home.stripe) >= K.STACKED_STRIPE_DE_MIN, "$l ${name(a)} @ ${name(h)}")
            }
        }
    }

    @Test
    fun pairsAreCachedAndCaseInsensitive() {
        for (l in CHL_LEAGUES) {
            val t = teams(l)
            val p = StackedColourTable.pair(l, t[0], t[1])
            assertSame(p, StackedColourTable.pair(l, t[0], t[1]))
            assertSame(p, StackedColourTable.pair(l, " ${t[0].lowercase()}", "${t[1].lowercase()} "))
            assertSame(StackedColourTable.pair(l, null, null), StackedColourTable.pair(l, "XYZ", ""))
        }
        // The QMJHL's mixed-case feed code.
        assertSame(StackedColourTable.pair(League.QMJHL, "BAC", "MON"), StackedColourTable.pair(League.QMJHL, "BaC", "mon"))
    }

    @Test
    fun theNhlIsByteForByteTheNhlStackedColours() {
        val t = NhlTeamColours.TABLE.keys.sorted()
        assertEquals(32, t.size)
        var n = 0
        for (a in t) for (h in t) {
            if (a == h) continue
            assertSame(NhlStackedColours.pair(a, h), StackedColourTable.pair(League.NHL, a, h), "NHL $a @ $h")
            assertEquals(NhlStackedColours.pair(a, h), StackedColourTable.pair(League.NHL, a, h))
            n++
        }
        assertEquals(32 * 31, n)
        assertSame(NhlStackedColours.pair(null, null), StackedColourTable.pair(League.NHL, null, null))
        assertSame(NhlStackedColours.pair(null, null), StackedColourTable.pair(League.NFL, "BUF", "CHI"))
        // Same code, different leagues: the NHL's OTT is untouched by the OHL's.
        assertEquals(NhlStackedColours.pair("OTT", "TOR"), StackedColourTable.pair(League.NHL, "OTT", "TOR"))
        assertEquals("#DA1A32", hex(NhlTeamColours.of("OTT").base))
    }
}
